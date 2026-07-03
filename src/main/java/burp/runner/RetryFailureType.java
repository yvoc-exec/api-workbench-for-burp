package burp.runner;

public enum RetryFailureType {
    CONNECTION_FAILURE,
    RESPONSE_TIMEOUT,
    HTTP_STATUS,
    SCRIPT_FAILURE,
    PREFLIGHT_BLOCK,
    CANCELLED
}
