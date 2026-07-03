package burp.utils;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.models.RedirectHop;
import burp.models.RedirectTerminationReason;
import burp.models.RunnerResult;
import burp.scripts.ExecutionSource;
import burp.scripts.ScriptDependentRequestResult;
import burp.scripts.ScriptFlowControl;
import burp.scripts.ScriptLogEntry;
import burp.scripts.ScriptVariableMutation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of a single request execution through the shared pipeline.
 */
public class ExecutionResult {
    public ExecutionPreflightResult preflight;
    public ExecutionPreflightStatus preflightStatus = ExecutionPreflightStatus.READY;
    public String preflightMessage;
    public boolean requestSent;
    public boolean responseTimedOut;
    public boolean cancellationRequested;
    public boolean lateResponseIgnored;
    public int timeoutMillis;
    public String originalResolvedUrl;
    public String effectiveResolvedUrl;
    public boolean oauth2Required;
    public boolean oauth2Ready;
    public boolean oauth2UsedStaleToken;
    public boolean oauth2SentWithoutToken;
    public boolean continuedAfterScriptFailure;
    public boolean unresolvedVariablesAllowed;
    public boolean targetChangeAllowed;
    public final List<String> policyOverridesApplied = new ArrayList<>();
    public boolean success;
    public HttpRequestResponse response;
    public HttpRequest builtRequest;
    public HttpRequest finalRequest;
    public final Map<String, String> extractedVars = new HashMap<>();
    public final Set<String> removedVars = new LinkedHashSet<>();
    public final List<RunnerResult.AssertionResult> assertions = new ArrayList<>();
    public long elapsedMs;
    public String errorMessage;
    public byte[] rawRequestBytes;
    public String rawRequestText;
    public Map<String, String> resolvedVariables = new HashMap<>();
    public String requestHeaders;
    public String requestBody;
    public String resolvedUrl;
    public String initialResolvedUrl;
    public String finalResolvedUrl;
    public boolean redirectsEnabled;
    public RedirectTerminationReason redirectTerminationReason = RedirectTerminationReason.NONE;
    public final List<RedirectHop> redirectHops = new ArrayList<>();
    public String scriptEngineName;
    public ExecutionSource executionSource;
    public final List<ScriptLogEntry> scriptLogs = new ArrayList<>();
    public final List<String> scriptWarnings = new ArrayList<>();
    public final List<String> scriptErrors = new ArrayList<>();
    public final List<ScriptVariableMutation> scriptVariableMutations = new ArrayList<>();
    public final List<ScriptDependentRequestResult> scriptDependentRequestResults = new ArrayList<>();
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

    public boolean isBlockedBeforeSend() {
        if (requestSent) {
            return false;
        }
        return switch (preflightStatus != null ? preflightStatus : ExecutionPreflightStatus.READY) {
            case BLOCKED_SCRIPT_ERROR,
                 BLOCKED_SCRIPT_TIMEOUT,
                 BLOCKED_OAUTH2_FAILURE,
                 BLOCKED_UNRESOLVED_VARIABLES,
                 BLOCKED_TARGET_CHANGE,
                 BLOCKED_POLICY,
                 CANCELLED -> true;
            default -> false;
        };
    }
}
