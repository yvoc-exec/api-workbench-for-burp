package burp.models;

public class RunnerTimelineRow {
    public int order;
    public String collectionName;
    public String requestName;
    public String status;
    public long timeMs;
    public int retries;
    public int varsChanged;
    public String assertions;
    public int attemptNumber = 1;
    public int totalAttempts = 1;
    public String executionKind;
    public String retryReason;
    public String cancellationState;
    public boolean requestMayHaveBeenProcessed;
}
