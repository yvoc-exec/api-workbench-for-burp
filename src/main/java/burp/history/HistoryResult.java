package burp.history;

import burp.utils.ExecutionResult;
import burp.utils.ExecutionPreflightStatus;
import burp.models.RunnerResult;

public enum HistoryResult {
    SUCCESS("Success"),
    FAILURE("Failure"),
    ERROR("Error"),
    ASSERTION_FAILURE("Assertion Failure"),
    MISSING_VARIABLE("Missing Variable"),
    CANCELLED("Cancelled"),
    BLOCKED("Blocked Before Send"),
    TIMEOUT("Response Timeout"),
    SKIPPED("Skipped by Script"),
    STOPPED("Stopped by Script"),
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

    public static HistoryResult from(ExecutionResult exec,
                                     boolean assertionFailure,
                                     boolean unresolvedVariables) {
        return classify(
                exec != null && exec.success,
                exec != null ? exec.errorMessage : null,
                assertionFailure,
                unresolvedVariables,
                exec != null && exec.responseTimedOut,
                exec != null && exec.scriptFlowControl == burp.scripts.ScriptFlowControl.SKIP_REQUEST,
                exec != null && exec.scriptFlowControl == burp.scripts.ScriptFlowControl.STOP_RUN,
                exec != null ? exec.preflightStatus : null
        );
    }

    public static HistoryResult from(RunnerResult result,
                                     boolean assertionFailure,
                                     boolean unresolvedVariables) {
        return classify(
                result != null && result.success,
                result != null ? result.errorMessage : null,
                assertionFailure,
                unresolvedVariables,
                result != null && result.responseTimedOut,
                result != null && result.scriptFlowControl == burp.scripts.ScriptFlowControl.SKIP_REQUEST,
                result != null && result.scriptFlowControl == burp.scripts.ScriptFlowControl.STOP_RUN,
                result != null ? result.preflightStatus : null
        );
    }

    private static HistoryResult classify(boolean success,
                                          String errorMessage,
                                          boolean assertionFailure,
                                          boolean unresolvedVariables,
                                          boolean responseTimedOut,
                                          boolean skipped,
                                          boolean stopped,
                                          ExecutionPreflightStatus preflightStatus) {
        if (preflightStatus == ExecutionPreflightStatus.CANCELLED) {
            return CANCELLED;
        }
        if (preflightStatus == ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR
                || preflightStatus == ExecutionPreflightStatus.BLOCKED_SCRIPT_TIMEOUT
                || preflightStatus == ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE
                || preflightStatus == ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES
                || preflightStatus == ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE
                || preflightStatus == ExecutionPreflightStatus.BLOCKED_POLICY
                ) {
            return BLOCKED;
        }
        if (responseTimedOut) {
            return TIMEOUT;
        }
        if (skipped) {
            return SKIPPED;
        }
        if (stopped) {
            return STOPPED;
        }
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
