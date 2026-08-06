package burp.models;

import burp.runner.FlowTargetResolutionForm;
import burp.runner.RetryFailureType;
import burp.scripts.ExecutionSource;
import burp.scripts.ScriptDependentRequestResult;
import burp.scripts.ScriptFlowControl;
import burp.scripts.ScriptLogEntry;
import burp.utils.ExecutionPreflightStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, immutable projection of a completed Runner result. */
public final class RunnerResultSummary {
    public static final int LABEL_LIMIT = 512;
    public static final int PATH_LIMIT = 2_048;
    public static final int URL_LIMIT = 8_192;
    public static final int METHOD_LIMIT = 32;
    public static final int MESSAGE_LIMIT = 4_096;
    public static final int RESPONSE_PREVIEW_LIMIT = 500;
    private static final int COMPATIBILITY_ITEM_LIMIT = 256;

    private final String requestId;
    private final String requestName;
    private final String historyEntryId;
    private final String collectionId;
    private final String collectionName;
    private final String folderPath;
    private final String parentRequestId;
    private final String parentRequestName;
    private final String qualifiedTargetPath;
    private final String host;
    private final String path;
    private final String method;
    private final ApiRequest.BuildMode buildMode;
    private final String requestUrl;
    private final String initialResolvedUrl;
    private final String finalResolvedUrl;
    private final String originalResolvedUrl;
    private final String effectiveResolvedUrl;
    private final boolean redirectsEnabled;
    private final RedirectTerminationReason redirectTerminationReason;
    private final List<RedirectSummary> redirectSummaries;
    private final boolean requestSent;
    private final ExecutionPreflightStatus preflightStatus;
    private final String preflightMessage;
    private final boolean responseTimedOut;
    private final int timeoutMillis;
    private final boolean targetChanged;
    private final boolean oauth2Required;
    private final boolean oauth2Ready;
    private final boolean oauth2UsedStaleToken;
    private final boolean oauth2SentWithoutToken;
    private final boolean success;
    private final int statusCode;
    private final long responseTimeMs;
    private final int responseSize;
    private final int responseBodyLength;
    private final String responseBodyPreview;
    private final String errorMessage;
    private final int attemptNumber;
    private final int totalAttempts;
    private final String retryDecision;
    private final String retryReason;
    private final int retryDelayMillis;
    private final RetryFailureType retryFailureType;
    private final boolean requestMayHaveBeenProcessed;
    private final FlowTargetResolutionForm targetResolutionForm;
    private final RunnerCancellationState cancellationState;
    private final boolean dependentExecution;
    private final boolean adHocExecution;
    private final int dependentDepth;
    private final boolean triggeredByScript;
    private final int dependentRequestCount;
    private final String scriptEngineName;
    private final ExecutionSource executionSource;
    private final ScriptFlowControl scriptFlowControl;
    private final String scriptFlowMessage;
    private final String scriptFlowNextRequestName;
    private final String scriptFlowNextRequestId;
    private final int assertionPassedCount;
    private final int assertionTotalCount;
    private final int extractedVariableCount;
    private final List<String> extractedVariableNames;
    private final int unresolvedVariableCount;
    private final int scriptLogCount;
    private final int scriptWarningCount;
    private final int scriptErrorCount;
    private final boolean fullEvidenceRetained;
    private final String evidenceRetentionMessage;

    private RunnerResultSummary(RunnerResult result) {
        requestId = bounded(result.requestId, LABEL_LIMIT);
        requestName = bounded(result.requestName, LABEL_LIMIT);
        historyEntryId = bounded(result.historyEntryId, LABEL_LIMIT);
        collectionId = bounded(result.collectionId, LABEL_LIMIT);
        collectionName = bounded(result.collectionName, LABEL_LIMIT);
        folderPath = bounded(result.folderPath, PATH_LIMIT);
        parentRequestId = bounded(result.parentRequestId, LABEL_LIMIT);
        parentRequestName = bounded(result.parentRequestName, LABEL_LIMIT);
        qualifiedTargetPath = bounded(result.qualifiedTargetPath, PATH_LIMIT);
        host = bounded(result.host, LABEL_LIMIT);
        path = bounded(result.path, PATH_LIMIT);
        method = bounded(result.method, METHOD_LIMIT);
        buildMode = result.buildMode;
        requestUrl = bounded(result.requestUrl, URL_LIMIT);
        initialResolvedUrl = bounded(result.initialResolvedUrl, URL_LIMIT);
        finalResolvedUrl = bounded(result.finalResolvedUrl, URL_LIMIT);
        originalResolvedUrl = bounded(result.originalResolvedUrl, URL_LIMIT);
        effectiveResolvedUrl = bounded(result.effectiveResolvedUrl, URL_LIMIT);
        redirectsEnabled = result.redirectsEnabled;
        redirectTerminationReason = result.redirectTerminationReason != null
                ? result.redirectTerminationReason : RedirectTerminationReason.NONE;
        redirectSummaries = redirectSummaries(result.redirectHops);
        requestSent = result.requestSent;
        preflightStatus = result.preflightStatus != null ? result.preflightStatus : ExecutionPreflightStatus.READY;
        preflightMessage = bounded(result.preflightMessage, MESSAGE_LIMIT);
        responseTimedOut = result.responseTimedOut;
        timeoutMillis = result.timeoutMillis;
        targetChanged = result.targetChanged;
        oauth2Required = result.oauth2Required;
        oauth2Ready = result.oauth2Ready;
        oauth2UsedStaleToken = result.oauth2UsedStaleToken;
        oauth2SentWithoutToken = result.oauth2SentWithoutToken;
        success = result.success;
        statusCode = result.statusCode;
        responseTimeMs = result.responseTimeMs;
        responseSize = result.responseSize;
        responseBodyLength = result.responseBodyLength > 0
                ? result.responseBodyLength : length(result.responseBody);
        responseBodyPreview = responsePreview(result);
        errorMessage = bounded(result.errorMessage, MESSAGE_LIMIT);
        attemptNumber = Math.max(1, result.attemptNumber);
        totalAttempts = Math.max(1, result.totalAttempts);
        retryDecision = bounded(result.retryDecision, LABEL_LIMIT);
        retryReason = bounded(result.retryReason, MESSAGE_LIMIT);
        retryDelayMillis = result.retryDelayMillis;
        retryFailureType = result.retryFailureType;
        requestMayHaveBeenProcessed = result.requestMayHaveBeenProcessed;
        targetResolutionForm = result.targetResolutionForm != null
                ? result.targetResolutionForm : FlowTargetResolutionForm.NONE;
        cancellationState = result.cancellationState != null
                ? result.cancellationState : RunnerCancellationState.NOT_CANCELLED;
        dependentExecution = result.dependentExecution;
        adHocExecution = result.adHocExecution;
        dependentDepth = Math.max(0, result.dependentDepth);
        triggeredByScript = result.triggeredByScript;
        dependentRequestCount = Math.max(0, result.dependentRequestCount);
        scriptEngineName = bounded(result.scriptEngineName, LABEL_LIMIT);
        executionSource = result.executionSource;
        scriptFlowControl = result.scriptFlowControl != null ? result.scriptFlowControl : ScriptFlowControl.CONTINUE;
        scriptFlowMessage = bounded(result.scriptFlowMessage, MESSAGE_LIMIT);
        scriptFlowNextRequestName = bounded(result.scriptFlowNextRequestName, LABEL_LIMIT);
        scriptFlowNextRequestId = bounded(result.scriptFlowNextRequestId, LABEL_LIMIT);
        int[] assertions = assertionCounts(result.assertions);
        assertionPassedCount = assertions[0];
        assertionTotalCount = assertions[1];
        extractedVariableNames = boundedNames(result.extractedVariables != null
                ? result.extractedVariables.keySet() : List.of());
        extractedVariableCount = result.extractedVariables != null ? result.extractedVariables.size() : 0;
        unresolvedVariableCount = result.unresolvedVariables != null ? result.unresolvedVariables.size() : 0;
        scriptLogCount = result.scriptLogs != null ? result.scriptLogs.size() : 0;
        scriptWarningCount = result.scriptWarnings != null ? result.scriptWarnings.size() : 0;
        scriptErrorCount = result.scriptErrors != null ? result.scriptErrors.size() : 0;
        fullEvidenceRetained = result.fullEvidenceRetained;
        evidenceRetentionMessage = bounded(result.evidenceRetentionMessage, MESSAGE_LIMIT);
    }

    public static RunnerResultSummary from(RunnerResult result) {
        return result != null ? new RunnerResultSummary(result) : null;
    }

    public RunnerResult toCompatibilityResult() {
        RunnerResult result = new RunnerResult();
        result.requestId = requestId;
        result.requestName = requestName;
        result.historyEntryId = historyEntryId;
        result.collectionId = collectionId;
        result.collectionName = collectionName;
        result.folderPath = folderPath;
        result.parentRequestId = parentRequestId;
        result.parentRequestName = parentRequestName;
        result.qualifiedTargetPath = qualifiedTargetPath;
        result.host = host;
        result.path = path;
        result.method = method;
        result.buildMode = buildMode;
        result.requestUrl = requestUrl;
        result.initialResolvedUrl = initialResolvedUrl;
        result.finalResolvedUrl = finalResolvedUrl;
        result.originalResolvedUrl = originalResolvedUrl;
        result.effectiveResolvedUrl = effectiveResolvedUrl;
        result.redirectsEnabled = redirectsEnabled;
        result.redirectTerminationReason = redirectTerminationReason;
        result.redirectHops = compatibilityRedirects();
        result.requestSent = requestSent;
        result.preflightStatus = preflightStatus;
        result.preflightMessage = preflightMessage;
        result.responseTimedOut = responseTimedOut;
        result.timeoutMillis = timeoutMillis;
        result.targetChanged = targetChanged;
        result.oauth2Required = oauth2Required;
        result.oauth2Ready = oauth2Ready;
        result.oauth2UsedStaleToken = oauth2UsedStaleToken;
        result.oauth2SentWithoutToken = oauth2SentWithoutToken;
        result.success = success;
        result.statusCode = statusCode;
        result.responseTimeMs = responseTimeMs;
        result.responseSize = responseSize;
        result.responseBodyLength = responseBodyLength;
        result.responseBodyPreview = responseBodyPreview;
        result.errorMessage = errorMessage;
        result.attemptNumber = attemptNumber;
        result.totalAttempts = totalAttempts;
        result.retryDecision = retryDecision;
        result.retryReason = retryReason;
        result.retryDelayMillis = retryDelayMillis;
        result.retryFailureType = retryFailureType;
        result.requestMayHaveBeenProcessed = requestMayHaveBeenProcessed;
        result.targetResolutionForm = targetResolutionForm;
        result.cancellationState = cancellationState;
        result.dependentExecution = dependentExecution;
        result.adHocExecution = adHocExecution;
        result.dependentDepth = dependentDepth;
        result.triggeredByScript = triggeredByScript;
        result.dependentRequestCount = dependentRequestCount;
        result.scriptEngineName = scriptEngineName;
        result.executionSource = executionSource;
        result.scriptFlowControl = scriptFlowControl;
        result.scriptFlowMessage = scriptFlowMessage;
        result.scriptFlowNextRequestName = scriptFlowNextRequestName;
        result.scriptFlowNextRequestId = scriptFlowNextRequestId;
        result.assertions = compatibilityAssertions();
        result.extractedVariables = compatibilityExtractedVariables();
        result.unresolvedVariables = placeholders("unresolved", unresolvedVariableCount);
        result.scriptLogs = compatibilityLogs(scriptLogCount);
        result.scriptWarnings = placeholders("Script warning", scriptWarningCount);
        result.scriptErrors = placeholders("Script error", scriptErrorCount);
        result.scriptDependentRequestResults = compatibilityDependentResults();
        result.canonicalCaptureComplete = true;
        result.fullEvidenceRetained = fullEvidenceRetained;
        result.evidenceRetentionMessage = evidenceRetentionMessage;
        result.markHeavyPayloadReleasedForCompatibility();
        return result;
    }

    public String displayStatusLabel() {
        return toCompatibilityResult().displayStatusLabel();
    }

    public String displayLogStatusLabel() {
        return toCompatibilityResult().displayLogStatusLabel();
    }

    private List<RedirectHop> compatibilityRedirects() {
        List<RedirectHop> redirects = new ArrayList<>(redirectSummaries.size());
        for (RedirectSummary summary : redirectSummaries) {
            redirects.add(summary.toCompatibilityRedirect());
        }
        return redirects;
    }

    private List<RunnerResult.AssertionResult> compatibilityAssertions() {
        int retainedCount = boundedItemCount(assertionTotalCount);
        List<RunnerResult.AssertionResult> values = new ArrayList<>(retainedCount);
        for (int i = 0; i < retainedCount; i++) {
            values.add(new RunnerResult.AssertionResult(
                    "Assertion " + (i + 1), i < assertionPassedCount, "", ""));
        }
        return values;
    }

    private Map<String, String> compatibilityExtractedVariables() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : extractedVariableNames) {
            values.put(name, "");
        }
        return values;
    }

    private static List<String> placeholders(String prefix, int count) {
        int retainedCount = boundedItemCount(count);
        List<String> values = new ArrayList<>(retainedCount);
        for (int i = 0; i < retainedCount; i++) {
            values.add(prefix + "-" + (i + 1));
        }
        return values;
    }

    private static List<ScriptLogEntry> compatibilityLogs(int count) {
        int retainedCount = boundedItemCount(count);
        List<ScriptLogEntry> values = new ArrayList<>(retainedCount);
        for (int i = 0; i < retainedCount; i++) {
            values.add(new ScriptLogEntry("info", "Script log metadata retained", null, null));
        }
        return values;
    }

    private List<ScriptDependentRequestResult> compatibilityDependentResults() {
        int retainedCount = boundedItemCount(dependentRequestCount);
        List<ScriptDependentRequestResult> values = new ArrayList<>(retainedCount);
        for (int i = 0; i < retainedCount; i++) {
            ScriptDependentRequestResult dependent = new ScriptDependentRequestResult();
            dependent.executed = true;
            dependent.success = true;
            dependent.parentRequestId = requestId;
            dependent.parentRequestName = requestName;
            dependent.depth = dependentDepth + 1;
            values.add(dependent);
        }
        return values;
    }

    private static int boundedItemCount(int count) {
        return Math.min(Math.max(0, count), COMPATIBILITY_ITEM_LIMIT);
    }

    private static List<RedirectSummary> redirectSummaries(List<RedirectHop> hops) {
        if (hops == null || hops.isEmpty()) {
            return List.of();
        }
        List<RedirectSummary> summaries = new ArrayList<>(hops.size());
        for (RedirectHop hop : hops) {
            if (hop != null) {
                summaries.add(new RedirectSummary(hop));
            }
        }
        return List.copyOf(summaries);
    }

    private static int[] assertionCounts(List<RunnerResult.AssertionResult> assertions) {
        int passed = 0;
        int total = 0;
        if (assertions != null) {
            for (RunnerResult.AssertionResult assertion : assertions) {
                if (assertion != null) {
                    total++;
                    if (assertion.passed) {
                        passed++;
                    }
                }
            }
        }
        return new int[]{passed, total};
    }

    private static List<String> boundedNames(Iterable<String> names) {
        List<String> values = new ArrayList<>();
        if (names != null) {
            for (String name : names) {
                if (values.size() >= COMPATIBILITY_ITEM_LIMIT) {
                    break;
                }
                values.add(bounded(name, LABEL_LIMIT));
            }
        }
        return List.copyOf(values);
    }

    private static String responsePreview(RunnerResult result) {
        String preview = result.responseBodyPreview;
        if (preview == null && result.responseBody != null) {
            preview = result.responseBody.length() > RESPONSE_PREVIEW_LIMIT
                    ? result.responseBody.substring(0, RESPONSE_PREVIEW_LIMIT) + "..."
                    : result.responseBody;
        }
        return bounded(preview, RESPONSE_PREVIEW_LIMIT + 3);
    }

    private static String bounded(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static int length(String value) {
        return value != null ? value.length() : 0;
    }

    public String requestId() { return requestId; }
    public String requestName() { return requestName; }
    public String historyEntryId() { return historyEntryId; }
    public String collectionId() { return collectionId; }
    public String collectionName() { return collectionName; }
    public String folderPath() { return folderPath; }
    public String parentRequestId() { return parentRequestId; }
    public String parentRequestName() { return parentRequestName; }
    public String qualifiedTargetPath() { return qualifiedTargetPath; }
    public String host() { return host; }
    public String path() { return path; }
    public String method() { return method; }
    public ApiRequest.BuildMode buildMode() { return buildMode; }
    public String requestUrl() { return requestUrl; }
    public String initialResolvedUrl() { return initialResolvedUrl; }
    public String finalResolvedUrl() { return finalResolvedUrl; }
    public String originalResolvedUrl() { return originalResolvedUrl; }
    public String effectiveResolvedUrl() { return effectiveResolvedUrl; }
    public boolean redirectsEnabled() { return redirectsEnabled; }
    public RedirectTerminationReason redirectTerminationReason() { return redirectTerminationReason; }
    public List<RedirectSummary> redirectSummaries() { return redirectSummaries; }
    public boolean requestSent() { return requestSent; }
    public ExecutionPreflightStatus preflightStatus() { return preflightStatus; }
    public String preflightMessage() { return preflightMessage; }
    public boolean responseTimedOut() { return responseTimedOut; }
    public int timeoutMillis() { return timeoutMillis; }
    public boolean targetChanged() { return targetChanged; }
    public boolean oauth2Required() { return oauth2Required; }
    public boolean oauth2Ready() { return oauth2Ready; }
    public boolean oauth2UsedStaleToken() { return oauth2UsedStaleToken; }
    public boolean oauth2SentWithoutToken() { return oauth2SentWithoutToken; }
    public boolean success() { return success; }
    public int statusCode() { return statusCode; }
    public long responseTimeMs() { return responseTimeMs; }
    public int responseSize() { return responseSize; }
    public int responseBodyLength() { return responseBodyLength; }
    public String responseBodyPreview() { return responseBodyPreview; }
    public String errorMessage() { return errorMessage; }
    public int attemptNumber() { return attemptNumber; }
    public int totalAttempts() { return totalAttempts; }
    public String retryDecision() { return retryDecision; }
    public String retryReason() { return retryReason; }
    public int retryDelayMillis() { return retryDelayMillis; }
    public RetryFailureType retryFailureType() { return retryFailureType; }
    public boolean requestMayHaveBeenProcessed() { return requestMayHaveBeenProcessed; }
    public FlowTargetResolutionForm targetResolutionForm() { return targetResolutionForm; }
    public RunnerCancellationState cancellationState() { return cancellationState; }
    public boolean dependentExecution() { return dependentExecution; }
    public boolean adHocExecution() { return adHocExecution; }
    public int dependentDepth() { return dependentDepth; }
    public boolean triggeredByScript() { return triggeredByScript; }
    public int dependentRequestCount() { return dependentRequestCount; }
    public String scriptEngineName() { return scriptEngineName; }
    public ExecutionSource executionSource() { return executionSource; }
    public ScriptFlowControl scriptFlowControl() { return scriptFlowControl; }
    public String scriptFlowMessage() { return scriptFlowMessage; }
    public String scriptFlowNextRequestName() { return scriptFlowNextRequestName; }
    public String scriptFlowNextRequestId() { return scriptFlowNextRequestId; }
    public int assertionPassedCount() { return assertionPassedCount; }
    public int assertionTotalCount() { return assertionTotalCount; }
    public int extractedVariableCount() { return extractedVariableCount; }
    public List<String> extractedVariableNames() { return extractedVariableNames; }
    public int unresolvedVariableCount() { return unresolvedVariableCount; }
    public int scriptLogCount() { return scriptLogCount; }
    public int scriptWarningCount() { return scriptWarningCount; }
    public int scriptErrorCount() { return scriptErrorCount; }
    public boolean fullEvidenceRetained() { return fullEvidenceRetained; }
    public String evidenceRetentionMessage() { return evidenceRetentionMessage; }

    public static final class RedirectSummary {
        private final int hopNumber;
        private final String sourceMethod;
        private final String sourceUrl;
        private final String targetUrl;
        private final int statusCode;
        private final long elapsedMs;
        private final boolean followed;
        private final String failureReason;
        private final List<String> forwardedSensitiveHeaderNames;
        private final List<String> strippedSensitiveHeaderNames;

        private RedirectSummary(RedirectHop hop) {
            hopNumber = hop.hopNumber;
            sourceMethod = bounded(hop.sourceMethod, METHOD_LIMIT);
            sourceUrl = bounded(hop.sourceUrl, URL_LIMIT);
            targetUrl = bounded(hop.targetUrl, URL_LIMIT);
            statusCode = hop.statusCode;
            elapsedMs = hop.elapsedMs;
            followed = hop.followed;
            failureReason = bounded(hop.failureReason, MESSAGE_LIMIT);
            forwardedSensitiveHeaderNames = boundedNames(hop.forwardedSensitiveHeaderNames);
            strippedSensitiveHeaderNames = boundedNames(hop.strippedSensitiveHeaderNames);
        }

        private RedirectHop toCompatibilityRedirect() {
            RedirectHop hop = new RedirectHop();
            hop.hopNumber = hopNumber;
            hop.sourceMethod = sourceMethod;
            hop.sourceUrl = sourceUrl;
            hop.targetUrl = targetUrl;
            hop.location = targetUrl;
            hop.statusCode = statusCode;
            hop.elapsedMs = elapsedMs;
            hop.followed = followed;
            hop.failureReason = failureReason;
            hop.forwardedSensitiveHeaderNames = new ArrayList<>(forwardedSensitiveHeaderNames);
            hop.strippedSensitiveHeaderNames = new ArrayList<>(strippedSensitiveHeaderNames);
            return hop;
        }

        public int hopNumber() { return hopNumber; }
        public String sourceMethod() { return sourceMethod; }
        public String sourceUrl() { return sourceUrl; }
        public String targetUrl() { return targetUrl; }
        public int statusCode() { return statusCode; }
        public long elapsedMs() { return elapsedMs; }
        public boolean followed() { return followed; }
        public String failureReason() { return failureReason; }
        public List<String> forwardedSensitiveHeaderNames() { return forwardedSensitiveHeaderNames; }
        public List<String> strippedSensitiveHeaderNames() { return strippedSensitiveHeaderNames; }
    }
}
