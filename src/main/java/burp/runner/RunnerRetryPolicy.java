package burp.runner;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class RunnerRetryPolicy {
    public int maxRetries;
    public Set<String> retryableMethods;
    public Set<Integer> retryableStatusCodes;
    public boolean retryConnectionFailures;
    public boolean retryTimeouts;
    public boolean retryNonIdempotentMethods;
    public int baseDelayMillis;
    public int maxDelayMillis;

    public static RunnerRetryPolicy safeDefaults() {
        RunnerRetryPolicy policy = new RunnerRetryPolicy();
        policy.maxRetries = 0;
        policy.retryableMethods = new LinkedHashSet<>(java.util.List.of("GET", "HEAD", "OPTIONS"));
        policy.retryableStatusCodes = new LinkedHashSet<>();
        policy.retryConnectionFailures = false;
        policy.retryTimeouts = false;
        policy.retryNonIdempotentMethods = false;
        policy.baseDelayMillis = 200;
        policy.maxDelayMillis = 5_000;
        return policy;
    }

    public static RunnerRetryPolicy copyOf(RunnerRetryPolicy source) {
        RunnerRetryPolicy copy = safeDefaults();
        if (source == null) {
            return copy;
        }
        copy.maxRetries = source.maxRetries;
        copy.retryableMethods = source.retryableMethods != null ? new LinkedHashSet<>(source.retryableMethods) : new LinkedHashSet<>(copy.retryableMethods);
        copy.retryableStatusCodes = source.retryableStatusCodes != null ? new LinkedHashSet<>(source.retryableStatusCodes) : new LinkedHashSet<>();
        copy.retryConnectionFailures = source.retryConnectionFailures;
        copy.retryTimeouts = source.retryTimeouts;
        copy.retryNonIdempotentMethods = source.retryNonIdempotentMethods;
        copy.baseDelayMillis = source.baseDelayMillis;
        copy.maxDelayMillis = source.maxDelayMillis;
        copy.normalize();
        return copy;
    }

    public void normalize() {
        maxRetries = clamp(maxRetries, 0, 10);
        baseDelayMillis = clamp(baseDelayMillis, 0, 60_000);
        if (retryableMethods == null) {
            retryableMethods = new LinkedHashSet<>(java.util.List.of("GET", "HEAD", "OPTIONS"));
        } else {
            Set<String> normalized = new LinkedHashSet<>();
            for (String method : retryableMethods) {
                String value = normalizeMethod(method);
                if (!value.isBlank()) {
                    normalized.add(value);
                }
            }
            retryableMethods = normalized;
        }
        if (retryableStatusCodes == null) {
            retryableStatusCodes = new LinkedHashSet<>();
        } else {
            Set<Integer> normalized = new LinkedHashSet<>();
            for (Integer code : retryableStatusCodes) {
                if (code != null && code >= 100 && code <= 599) {
                    normalized.add(code);
                }
            }
            retryableStatusCodes = normalized;
        }
        maxDelayMillis = clamp(maxDelayMillis, baseDelayMillis, 300_000);
    }

    public boolean isMethodRetryEligible(String method) {
        normalize();
        if (maxRetries == 0) {
            return false;
        }
        String normalizedMethod = normalizeMethod(method);
        if (normalizedMethod.isBlank()) {
            normalizedMethod = "GET";
        }
        if (!retryableMethods.contains(normalizedMethod)) {
            return false;
        }
        return switch (normalizedMethod) {
            case "GET", "HEAD", "OPTIONS", "PUT", "DELETE" -> true;
            case "POST", "PATCH", "CONNECT", "TRACE" -> retryNonIdempotentMethods;
            default -> retryNonIdempotentMethods;
        };
    }

    public boolean hasAnyRetryTrigger() {
        normalize();
        return retryConnectionFailures || retryTimeouts || !retryableStatusCodes.isEmpty();
    }

    public int maximumAttemptsFor(String method) {
        if (maxRetries == 0) {
            return 1;
        }
        if (!isMethodRetryEligible(method)) {
            return 1;
        }
        if (!hasAnyRetryTrigger()) {
            return 1;
        }
        return 1 + maxRetries;
    }

    public RunnerRetryDecision evaluate(
            String method,
            RetryFailureType failureType,
            boolean requestSent,
            Integer responseStatus,
            int completedAttempts) {
        normalize();
        int maximumAttempts = maximumAttemptsFor(method);
        boolean retryEligible = maximumAttempts > 1;
        String normalizedMethod = normalizeMethod(method);
        if (normalizedMethod.isBlank()) {
            normalizedMethod = "GET";
        }
        boolean requestMayHaveBeenProcessed = requestSent
                && (failureType == RetryFailureType.RESPONSE_TIMEOUT
                || failureType == RetryFailureType.CONNECTION_FAILURE
                || failureType == RetryFailureType.CANCELLED);

        if (completedAttempts >= maximumAttempts) {
            return noRetry(false, requestMayHaveBeenProcessed, maximumAttempts, "Retry disabled: maximum attempts reached.");
        }
        if (failureType == null) {
            return noRetry(retryEligible, requestMayHaveBeenProcessed, maximumAttempts, "Retry disabled: unconfigured failure type.");
        }
        if (failureType == RetryFailureType.SCRIPT_FAILURE) {
            return noRetry(retryEligible, requestMayHaveBeenProcessed, maximumAttempts, "Retry disabled for script failure.");
        }
        if (failureType == RetryFailureType.PREFLIGHT_BLOCK) {
            return noRetry(retryEligible, requestMayHaveBeenProcessed, maximumAttempts, "Retry disabled for preflight block.");
        }
        if (failureType == RetryFailureType.CANCELLED) {
            return noRetry(retryEligible, requestMayHaveBeenProcessed, maximumAttempts, "Retry disabled for cancellation.");
        }
        if (!requestSent) {
            return noRetry(retryEligible, requestMayHaveBeenProcessed, maximumAttempts, "Retry disabled: request was not sent.");
        }
        if (!isMethodRetryEligible(method)) {
            return noRetry(false, requestMayHaveBeenProcessed, maximumAttempts, "Retry disabled for method " + normalizedMethod + ".");
        }
        if (failureType == RetryFailureType.CONNECTION_FAILURE) {
            if (!retryConnectionFailures) {
                return noRetry(true, requestMayHaveBeenProcessed, maximumAttempts, "Retry disabled for unconfigured failure type.");
            }
            return retryDecision(normalizedMethod, failureType, requestMayHaveBeenProcessed, maximumAttempts, completedAttempts, null);
        }
        if (failureType == RetryFailureType.RESPONSE_TIMEOUT) {
            if (!retryTimeouts) {
                return noRetry(true, requestMayHaveBeenProcessed, maximumAttempts, "Retry disabled for unconfigured failure type.");
            }
            return retryDecision(normalizedMethod, failureType, requestMayHaveBeenProcessed, maximumAttempts, completedAttempts, null);
        }
        if (failureType == RetryFailureType.HTTP_STATUS) {
            if (responseStatus == null || !retryableStatusCodes.contains(responseStatus)) {
                return noRetry(true, requestMayHaveBeenProcessed, maximumAttempts,
                        "Retry disabled for HTTP status " + (responseStatus != null ? responseStatus : "unknown") + ".");
            }
            return retryDecision(normalizedMethod, failureType, requestMayHaveBeenProcessed, maximumAttempts, completedAttempts, responseStatus);
        }
        return noRetry(retryEligible, requestMayHaveBeenProcessed, maximumAttempts, "Retry disabled: unconfigured failure type.");
    }

    private RunnerRetryDecision retryDecision(String method,
                                              RetryFailureType failureType,
                                              boolean requestMayHaveBeenProcessed,
                                              int maximumAttempts,
                                              int completedAttempts,
                                              Integer responseStatus) {
        int nextAttempt = Math.max(1, completedAttempts);
        long delay = baseDelayMillis;
        if (nextAttempt > 1) {
            int exponent = Math.max(0, completedAttempts - 1);
            delay = (long) baseDelayMillis * (1L << Math.min(exponent, 30));
        }
        delay = Math.min(delay, maxDelayMillis);
        String reason;
        if (failureType == RetryFailureType.CONNECTION_FAILURE) {
            reason = "Retrying connection failure for eligible method " + method + ".";
        } else if (failureType == RetryFailureType.RESPONSE_TIMEOUT) {
            reason = "Retrying response timeout for eligible method " + method + "; the request may have been processed.";
        } else if (failureType == RetryFailureType.HTTP_STATUS) {
            reason = "Retrying configured HTTP status " + Objects.toString(responseStatus, "unknown") + " for eligible method " + method + ".";
        } else {
            reason = "Retry disabled: unconfigured failure type.";
        }
        return new RunnerRetryDecision(true, true, reason, (int) Math.max(0L, delay), requestMayHaveBeenProcessed, maximumAttempts);
    }

    private RunnerRetryDecision noRetry(boolean retryEligible,
                                        boolean requestMayHaveBeenProcessed,
                                        int maximumAttempts,
                                        String reason) {
        return new RunnerRetryDecision(false, retryEligible, reason, 0, requestMayHaveBeenProcessed, maximumAttempts);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalizeMethod(String method) {
        if (method == null) {
            return "GET";
        }
        String value = method.trim();
        if (value.isEmpty()) {
            return "GET";
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
