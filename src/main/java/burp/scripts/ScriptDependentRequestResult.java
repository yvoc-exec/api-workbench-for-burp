package burp.scripts;

import burp.models.RunnerResult;

import java.util.ArrayList;
import java.util.List;

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
    public String targetResolutionForm;
    public String qualifiedTargetPath;
    public List<String> candidateQualifiedPaths = new ArrayList<>();

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
