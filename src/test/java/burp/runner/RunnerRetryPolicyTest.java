package burp.runner;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerRetryPolicyTest {

    @Test
    void defaultMaxRetriesIsZero() {
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();

        assertThat(policy.maxRetries).isZero();
        assertThat(policy.retryableMethods).containsExactly("GET", "HEAD", "OPTIONS");
        assertThat(policy.retryableStatusCodes).isEmpty();
        assertThat(policy.baseDelayMillis).isEqualTo(200);
        assertThat(policy.maxDelayMillis).isEqualTo(5_000);
    }

    @Test
    void getMayRetryWhenEnabled() {
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryConnectionFailures = true;
        policy.normalize();

        assertThat(policy.isMethodRetryEligible("GET")).isTrue();
        assertThat(policy.maximumAttemptsFor("GET")).isEqualTo(2);
        assertThat(policy.evaluate("GET", RetryFailureType.CONNECTION_FAILURE, true, null, 1).retry()).isTrue();
    }

    @Test
    void postDoesNotRetryByDefault() {
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryConnectionFailures = true;
        policy.normalize();

        assertThat(policy.evaluate("POST", RetryFailureType.CONNECTION_FAILURE, true, null, 1).retry()).isFalse();
    }

    @Test
    void postRetryRequiresExplicitUnsafeOverride() {
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryConnectionFailures = true;
        policy.retryableMethods = new LinkedHashSet<>(List.of("POST"));
        policy.normalize();

        assertThat(policy.isMethodRetryEligible("POST")).isFalse();

        policy.retryNonIdempotentMethods = true;
        policy.normalize();

        assertThat(policy.isMethodRetryEligible("POST")).isTrue();
        assertThat(policy.evaluate("POST", RetryFailureType.CONNECTION_FAILURE, true, null, 1).retry()).isTrue();
    }

    @Test
    void putRetryRequiresExplicitMethodEntry() {
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryConnectionFailures = true;
        policy.normalize();

        assertThat(policy.evaluate("PUT", RetryFailureType.CONNECTION_FAILURE, true, null, 1).retry()).isFalse();

        policy.retryableMethods = new LinkedHashSet<>(List.of("GET", "HEAD", "OPTIONS", "PUT"));
        policy.normalize();

        assertThat(policy.isMethodRetryEligible("PUT")).isTrue();
        assertThat(policy.evaluate("PUT", RetryFailureType.CONNECTION_FAILURE, true, null, 1).retry()).isTrue();
    }

    @Test
    void preflightAndScriptFailuresNeverRetry() {
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 5;
        policy.retryConnectionFailures = true;
        policy.retryTimeouts = true;
        policy.normalize();

        assertThat(policy.evaluate("GET", RetryFailureType.SCRIPT_FAILURE, true, null, 1).retry()).isFalse();
        assertThat(policy.evaluate("GET", RetryFailureType.PREFLIGHT_BLOCK, false, null, 1).retry()).isFalse();
        assertThat(policy.evaluate("GET", RetryFailureType.CANCELLED, true, null, 1).retry()).isFalse();
    }

    @Test
    void requestNotSentNeverRetries() {
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryConnectionFailures = true;
        policy.normalize();

        RunnerRetryDecision decision = policy.evaluate("GET", RetryFailureType.CONNECTION_FAILURE, false, null, 1);
        assertThat(decision.retry()).isFalse();
        assertThat(decision.reason()).isEqualTo("Retry disabled: request was not sent.");
    }

    @Test
    void timeoutOnPostMarksMayHaveBeenProcessed() {
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryTimeouts = true;
        policy.retryNonIdempotentMethods = true;
        policy.retryableMethods = new LinkedHashSet<>(List.of("POST"));
        policy.normalize();

        RunnerRetryDecision decision = policy.evaluate("POST", RetryFailureType.RESPONSE_TIMEOUT, true, null, 1);
        assertThat(decision.retry()).isTrue();
        assertThat(decision.requestMayHaveBeenProcessed()).isTrue();
        assertThat(decision.reason()).contains("request may have been processed");
    }

    @Test
    void configuredStatusRetryHonorsMethodPolicy() {
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryableStatusCodes = new LinkedHashSet<>(List.of(503));
        policy.retryConnectionFailures = false;
        policy.retryTimeouts = false;
        policy.normalize();

        assertThat(policy.evaluate("GET", RetryFailureType.HTTP_STATUS, true, 503, 1).retry()).isTrue();
        assertThat(policy.evaluate("POST", RetryFailureType.HTTP_STATUS, true, 503, 1).retry()).isFalse();
    }

    @Test
    void delayIsExponentiallyBackedOffAndClamped() {
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 5;
        policy.retryConnectionFailures = true;
        policy.baseDelayMillis = 200;
        policy.maxDelayMillis = 500;
        policy.normalize();

        assertThat(policy.evaluate("GET", RetryFailureType.CONNECTION_FAILURE, true, null, 1).delayMillis()).isEqualTo(200);
        assertThat(policy.evaluate("GET", RetryFailureType.CONNECTION_FAILURE, true, null, 2).delayMillis()).isEqualTo(400);
        assertThat(policy.evaluate("GET", RetryFailureType.CONNECTION_FAILURE, true, null, 3).delayMillis()).isEqualTo(500);
    }

    @Test
    void normalizationRejectsInvalidMethodsAndStatuses() {
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 12;
        LinkedHashSet<String> methods = new LinkedHashSet<>();
        methods.add(null);
        methods.add(" get ");
        methods.add("POST");
        methods.add("");
        methods.add("PUT");
        methods.add("get");
        policy.retryableMethods = methods;
        policy.retryableStatusCodes = new LinkedHashSet<>(List.of(99, 100, 503, 600));
        policy.baseDelayMillis = -10;
        policy.maxDelayMillis = 1;
        policy.normalize();

        assertThat(policy.maxRetries).isEqualTo(10);
        assertThat(policy.retryableMethods).containsExactly("GET", "POST", "PUT");
        assertThat(policy.retryableStatusCodes).containsExactly(100, 503);
        assertThat(policy.baseDelayMillis).isZero();
        assertThat(policy.maxDelayMillis).isEqualTo(1);
    }
}
