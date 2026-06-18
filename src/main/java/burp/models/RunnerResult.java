package burp.models;

import burp.scripts.ExecutionSource;
import burp.scripts.ScriptDependentRequestResult;
import burp.scripts.ScriptFlowControl;
import burp.scripts.ScriptLogEntry;
import burp.scripts.ScriptVariableMutation;

import java.util.*;

/**
 * Result of a single request in the Collection Runner.
 */
public class RunnerResult {
    public String requestName;
    public String requestId;
    public String collectionName;
    public String folderPath;
    public String host;
    public String path;
    public String method;
    public String requestUrl;
    public String requestHeaders;
    public String requestBody;
    public byte[] rawRequestBytes;
    public String rawRequestText;
    public boolean success;
    public int statusCode;
    public long responseTimeMs;
    public int responseSize;
    public int responseBodyLength;
    public String responseHeaders;
    public String responseBody;
    public String errorMessage;
    public String responseBodyPreview;
    public Map<String, String> extractedVariables = new HashMap<>();
    public Map<String, String> resolvedVariables = new HashMap<>();
    public List<AssertionResult> assertions = new ArrayList<>();
    public String scriptEngineName;
    public ExecutionSource executionSource;
    public List<ScriptLogEntry> scriptLogs = new ArrayList<>();
    public List<String> scriptWarnings = new ArrayList<>();
    public List<String> scriptErrors = new ArrayList<>();
    public List<ScriptVariableMutation> scriptVariableMutations = new ArrayList<>();
    public List<ScriptDependentRequestResult> scriptDependentRequestResults = new ArrayList<>();
    public ScriptFlowControl scriptFlowControl = ScriptFlowControl.CONTINUE;
    public String scriptFlowMessage;
    public String scriptFlowNextRequestName;
    public String scriptFlowNextRequestId;
    public int dependentRequestCount;
    public boolean dependentExecution;
    public boolean adHocExecution;
    public String parentRequestName;
    public String parentRequestId;
    public int dependentDepth;
    public boolean triggeredByScript;
    public int attemptNumber = 1;
    public int totalAttempts = 1;

    public boolean isSkippedByScript() {
        return scriptFlowControl == ScriptFlowControl.SKIP_REQUEST;
    }

    public boolean isStoppedByScript() {
        return scriptFlowControl == ScriptFlowControl.STOP_RUN;
    }

    public boolean isIntentionalNoResponseFlowControl() {
        return isSkippedByScript() || isStoppedByScript();
    }

    public String displayStatusLabel() {
        if (isSkippedByScript()) {
            return "Skipped by Script";
        }
        if (isStoppedByScript()) {
            return "Stopped by Script";
        }
        if (success) {
            String label = statusCode > 0 ? String.valueOf(statusCode) : "OK";
            return dependentExecution ? label + " (dependent)" : label;
        }
        return dependentExecution ? "ERR (dependent)" : "ERR";
    }

    public String displayLogStatusLabel() {
        if (isSkippedByScript()) {
            return success || errorMessage == null || errorMessage.isBlank()
                    ? "SKIPPED by script"
                    : "SKIPPED by script (" + errorMessage + ")";
        }
        if (isStoppedByScript()) {
            return success || errorMessage == null || errorMessage.isBlank()
                    ? "STOPPED by script"
                    : "STOPPED by script (" + errorMessage + ")";
        }
        if (success) {
            String label = statusCode > 0 ? "OK " + statusCode : "OK";
            return dependentExecution ? label + " (dependent)" : label;
        }
        String label = "FAIL " + (errorMessage != null && !errorMessage.isBlank() ? errorMessage : "Unknown error");
        return dependentExecution ? label + " (dependent)" : label;
    }

    public static class AssertionResult {
        public String name;
        public boolean passed;
        public String expected;
        public String actual;
        public AssertionResult(String name, boolean passed, String expected, String actual) {
            this.name = name;
            this.passed = passed;
            this.expected = expected;
            this.actual = actual;
        }
    }
}
