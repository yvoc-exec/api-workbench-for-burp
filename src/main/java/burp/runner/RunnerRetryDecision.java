package burp.runner;

public record RunnerRetryDecision(
        boolean retry,
        boolean retryEligible,
        String reason,
        int delayMillis,
        boolean requestMayHaveBeenProcessed,
        int maximumAttempts) {
}
