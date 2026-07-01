package burp.models;

public enum RedirectTerminationReason {
    NONE("None"),
    FOLLOW_DISABLED("Redirects disabled"),
    FINAL_RESPONSE("Final response"),
    INVALID_LOCATION("Invalid redirect Location"),
    UNSUPPORTED_SCHEME("Unsupported redirect target"),
    LOOP_DETECTED("Redirect loop detected"),
    LIMIT_EXCEEDED("Redirect limit exceeded"),
    REQUEST_BUILD_FAILED("Failed to build redirect request"),
    SEND_FAILED("Redirect send failed");

    private final String displayLabel;

    RedirectTerminationReason(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String displayLabel() {
        return displayLabel;
    }
}
