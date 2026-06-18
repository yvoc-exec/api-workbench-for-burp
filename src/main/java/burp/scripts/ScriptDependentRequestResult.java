package burp.scripts;

import burp.models.RunnerResult;

public class ScriptDependentRequestResult {
    public boolean executed;
    public boolean success;
    public String message;
    public String warningMessage;
    public String errorMessage;
    public String targetNameOrId;
    public String resolvedRequestName;
    public String resolvedRequestId;
    public String parentRequestName;
    public String parentRequestId;
    public int depth;
    public boolean adHoc;
    public RunnerResult runnerResult;

    public static ScriptDependentRequestResult ignored(String warning) {
        ScriptDependentRequestResult result = new ScriptDependentRequestResult();
        result.executed = false;
        result.success = true;
        result.warningMessage = warning;
        result.message = warning;
        return result;
    }

    public static ScriptDependentRequestResult failure(String error) {
        ScriptDependentRequestResult result = new ScriptDependentRequestResult();
        result.executed = false;
        result.success = false;
        result.errorMessage = error;
        result.message = error;
        return result;
    }
}
