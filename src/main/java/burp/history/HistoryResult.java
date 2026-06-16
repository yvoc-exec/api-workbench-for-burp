package burp.history;

public enum HistoryResult {
    SUCCESS("Success"),
    FAILURE("Failure"),
    ERROR("Error"),
    ASSERTION_FAILURE("Assertion Failure"),
    MISSING_VARIABLE("Missing Variable"),
    SKIPPED("Skipped"),
    UNKNOWN("Unknown");

    private final String displayName;

    HistoryResult(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static HistoryResult from(boolean success,
                                     String errorMessage,
                                     boolean assertionFailure,
                                     boolean unresolvedVariables) {
        if (assertionFailure) {
            return ASSERTION_FAILURE;
        }
        if (unresolvedVariables) {
            return MISSING_VARIABLE;
        }
        if (success) {
            return SUCCESS;
        }
        if (errorMessage != null && !errorMessage.isBlank()) {
            return ERROR;
        }
        return FAILURE;
    }
}
