package burp.models;

public class RunnerStopConditions {
    public boolean stopOnError;
    public boolean stopOnAssertionFailure;
    public boolean stopOnStatusAtLeast400;
    public boolean stopOnMissingVariable;
    public int stopAfterFailureCount;
}
