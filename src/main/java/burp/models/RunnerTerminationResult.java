package burp.models;

import burp.scripts.ScriptFlowControl;

public final class RunnerTerminationResult {
    public final RunnerTerminationType type;
    public final String reason;
    public final String requestName;
    public final String requestId;
    public final Integer statusCode;
    public final int completedCount;
    public final int totalQueuedCount;
    public final int failureCount;
    public final ScriptFlowControl scriptFlowControl;
    public final String configuredCondition;
    public final String internalErrorMessage;

    public RunnerTerminationResult(RunnerTerminationType type,
                                   String reason,
                                   String requestName,
                                   String requestId,
                                   Integer statusCode,
                                   int completedCount,
                                   int totalQueuedCount,
                                   int failureCount,
                                   ScriptFlowControl scriptFlowControl,
                                   String configuredCondition,
                                   String internalErrorMessage) {
        this.type = type != null ? type : RunnerTerminationType.INTERNAL_ERROR;
        this.reason = reason;
        this.requestName = requestName;
        this.requestId = requestId;
        this.statusCode = statusCode;
        this.completedCount = Math.max(0, completedCount);
        this.totalQueuedCount = Math.max(0, totalQueuedCount);
        this.failureCount = Math.max(0, failureCount);
        this.scriptFlowControl = scriptFlowControl;
        this.configuredCondition = configuredCondition;
        this.internalErrorMessage = internalErrorMessage;
    }

    public String displayLabel() {
        return type != null ? type.displayLabel() : RunnerTerminationType.INTERNAL_ERROR.displayLabel();
    }

    public boolean isCompleted() {
        return type == RunnerTerminationType.COMPLETED;
    }

    public boolean isInternalError() {
        return type == RunnerTerminationType.INTERNAL_ERROR;
    }
}
