package burp.models;

public enum RunnerCancellationState {
    NOT_CANCELLED,
    CANCELLED_BEFORE_SEND,
    CANCELLED_DURING_HTTP_WAIT,
    LATE_RESPONSE_IGNORED
}
