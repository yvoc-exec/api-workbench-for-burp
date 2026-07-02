package burp.scripts;

import burp.models.ApiRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScriptExecutionResult {
    public boolean success = true;
    public String engineName = "Unavailable";
    public ScriptFlowControl flowControl = ScriptFlowControl.CONTINUE;
    public String nextRequestName;
    public String nextRequestId;
    public String message;
    public final List<ScriptLogEntry> logs = new ArrayList<>();
    public final List<ScriptAssertionResult> assertions = new ArrayList<>();
    public final List<ScriptVariableMutation> variableMutations = new ArrayList<>();
    public final List<ScriptDependentRequestResult> dependentRequestResults = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();
    public final Map<String, String> effectiveVariables = new LinkedHashMap<>();
    public ApiRequest mutatedRequest;
    public int dependentRequestCount;
    public boolean timedOut;
    public boolean cancelled;
    public long timeoutMillis;

    public boolean hasScriptErrors() {
        return !errors.isEmpty();
    }
}
