package burp.history;

import burp.auth.TokenStore;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RedirectHop;
import burp.models.RedirectTerminationReason;
import burp.models.RunnerResult;
import burp.scripts.ScriptFlowControl;
import burp.scripts.ScriptLogEntry;
import burp.scripts.ScriptVariableMutation;
import burp.diagnostics.DiagnosticEvent;
import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;
import burp.ui.ImporterPanel;
import burp.utils.HttpUtils;
import burp.utils.ExecutionResult;
import burp.utils.ExecutionPreflightStatus;
import burp.utils.RequestPathResolver;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HistoryEntry {
    public String id;
    public Instant timestamp;
    public HistorySource source = HistorySource.WORKBENCH;
    public int attemptNumber = 1;
    public int totalAttempts = 1;
    public String collectionId;
    public String collectionName;
    public String folderPath;
    public String requestId;
    public String requestName;
    public String environmentId;
    public String environmentName;
    public HistoryRequestSnapshot requestSnapshot;
    public HistoryResponseSnapshot responseSnapshot;
    public int statusCode = -1;
    public long durationMillis;
    public long requestSizeBytes;
    public long responseSizeBytes;
    public HistoryResult result = HistoryResult.UNKNOWN;
    public String errorMessage;
    public List<String> unresolvedVariables = new ArrayList<>();
    public boolean requestSent;
    public String preflightStatus;
    public String preflightMessage;
    public boolean responseTimedOut;
    public int timeoutMillis;
    public String originalResolvedUrl;
    public String effectiveResolvedUrl;
    public boolean targetChanged;
    public boolean oauth2Required;
    public boolean oauth2Ready;
    public boolean oauth2UsedStaleToken;
    public boolean oauth2SentWithoutToken;
    public List<String> policyOverridesApplied = new ArrayList<>();
    public List<HistoryAssertionResult> assertions = new ArrayList<>();
    public List<HistoryExtractionResult> extractions = new ArrayList<>();
    public String scriptEngineName;
    public String executionSource;
    public List<ScriptLogEntry> scriptLogs = new ArrayList<>();
    public List<String> scriptWarnings = new ArrayList<>();
    public List<String> scriptErrors = new ArrayList<>();
    public List<ScriptVariableMutation> scriptVariableMutations = new ArrayList<>();
    public ScriptFlowControl scriptFlowControl = ScriptFlowControl.CONTINUE;
    public String scriptFlowMessage;
    public String scriptFlowNextRequestName;
    public String scriptFlowNextRequestId;
    public String finalResolvedUrl;
    public Boolean redirectsEnabled;
    public String initialResolvedUrl;
    public RedirectTerminationReason redirectTerminationReason;
    public List<RedirectHop> redirectHops = new ArrayList<>();
    public String host;
    public String scriptMode;
    public String scriptDialect;
    public String resultClassification;
    public String variablesSummaryText;
    public String scriptOutputSummaryText;
    public String assertionsSummaryText;
    public String metadataSummaryText;

    public static HistoryEntry fromWorkbenchExecution(ApiCollection collection,
                                                      ApiRequest request,
                                                      EnvironmentProfile environment,
                                                      ExecutionResult exec,
                                                      int attemptNumber,
                                                      int totalAttempts,
                                                      Collection<String> unresolvedVariables) {
        HistoryEntry entry = createBase(HistorySource.WORKBENCH, collection, request, environment, attemptNumber, totalAttempts);
        entry.timestamp = Instant.now();
        entry.requestSnapshot = HistoryRequestSnapshot.from(request);
        if (exec != null) {
            entry.durationMillis = exec.elapsedMs;
            entry.errorMessage = exec.errorMessage;
            entry.statusCode = determineStatusCode(exec, null);
            entry.responseSnapshot = exec.response != null ? HistoryResponseSnapshot.from(exec.response.response()) : null;
            entry.responseSizeBytes = entry.responseSnapshot != null && entry.responseSnapshot.body != null
                    ? entry.responseSnapshot.body.length
                    : 0L;
            entry.requestSizeBytes = exec.rawRequestBytes != null ? exec.rawRequestBytes.length : entry.requestSnapshot.approximateSizeBytes();
            entry.requestSent = exec.requestSent;
            entry.preflightStatus = exec.preflightStatus != null ? exec.preflightStatus.name() : null;
            entry.preflightMessage = exec.preflightMessage;
            entry.responseTimedOut = exec.responseTimedOut;
            entry.timeoutMillis = exec.timeoutMillis;
            entry.originalResolvedUrl = exec.originalResolvedUrl;
            entry.effectiveResolvedUrl = exec.effectiveResolvedUrl;
            entry.targetChanged = exec.targetChangeAllowed || (exec.preflight != null && exec.preflight.targetChanged);
            entry.oauth2Required = exec.oauth2Required;
            entry.oauth2Ready = exec.oauth2Ready;
            entry.oauth2UsedStaleToken = exec.oauth2UsedStaleToken;
            entry.oauth2SentWithoutToken = exec.oauth2SentWithoutToken;
            entry.policyOverridesApplied = exec.preflight != null
                    ? new ArrayList<>(exec.preflight.policyOverridesApplied)
                    : new ArrayList<>(exec.policyOverridesApplied);
            if (entry.requestSnapshot != null) {
                if (exec.requestSent) {
                    entry.requestSnapshot.rawRequestSent = exec.rawRequestBytes != null ? exec.rawRequestBytes.clone() : null;
                    entry.requestSnapshot.rawRequestSentText = exec.rawRequestText != null
                            ? exec.rawRequestText
                            : (exec.rawRequestBytes != null
                            ? new String(exec.rawRequestBytes, java.nio.charset.StandardCharsets.UTF_8)
                            : null);
                }
                entry.requestSnapshot.resolvedUrl = exec.resolvedUrl;
                entry.requestSnapshot.resolvedVariables = exec.resolvedVariables != null
                        ? new LinkedHashMap<>(exec.resolvedVariables)
                        : new LinkedHashMap<>();
            }
            entry.result = HistoryResult.from(exec, hasFailedAssertion(exec.assertions),
                    unresolvedVariables != null && !unresolvedVariables.isEmpty());
            entry.finalResolvedUrl = exec.finalResolvedUrl != null ? exec.finalResolvedUrl : exec.resolvedUrl;
            entry.effectiveResolvedUrl = exec.effectiveResolvedUrl != null ? exec.effectiveResolvedUrl : entry.finalResolvedUrl;
            entry.host = parseHost(entry.finalResolvedUrl);
            entry.resultClassification = entry.result != null ? entry.result.displayName() : null;
            entry.assertions = copyAssertions(exec.assertions);
            entry.extractions = copyExtractions(exec.extractedVars);
            entry.scriptEngineName = exec.scriptEngineName;
            entry.executionSource = exec.executionSource != null ? exec.executionSource.name() : null;
            entry.scriptLogs = copyScriptLogs(exec.scriptLogs);
            entry.scriptWarnings = exec.scriptWarnings != null ? new ArrayList<>(exec.scriptWarnings) : new ArrayList<>();
            entry.scriptErrors = exec.scriptErrors != null ? new ArrayList<>(exec.scriptErrors) : new ArrayList<>();
            entry.scriptVariableMutations = copyScriptMutations(exec.scriptVariableMutations);
            entry.scriptFlowControl = exec.scriptFlowControl != null ? exec.scriptFlowControl : ScriptFlowControl.CONTINUE;
            entry.scriptFlowMessage = exec.scriptFlowMessage;
            entry.scriptFlowNextRequestName = exec.scriptFlowNextRequestName;
            entry.scriptFlowNextRequestId = exec.scriptFlowNextRequestId;
            entry.redirectsEnabled = exec.redirectsEnabled;
            entry.initialResolvedUrl = exec.initialResolvedUrl != null ? exec.initialResolvedUrl : exec.resolvedUrl;
            entry.redirectTerminationReason = exec.redirectTerminationReason;
            entry.redirectHops = copyRedirectHops(exec.redirectHops);
            if (entry.statusCode >= 400 && entry.result == HistoryResult.SUCCESS) {
                entry.result = HistoryResult.FAILURE;
            }
            entry.resultClassification = entry.result != null ? entry.result.displayName() : null;
            entry.metadataSummaryText = buildExecutionMetadataText(entry);
            DiagnosticStore.getInstance().record(DiagnosticEvent.of(DiagnosticOperation.HISTORY_CAPTURE, DiagnosticSeverity.INFO, "HistoryEntry",
                    "Workbench history captured")
                    .withDetails("rawRequestAvailable=" + (entry.requestSnapshot != null && entry.requestSnapshot.hasRawRequestSent())
                            + " authoredTemplateAvailable=" + (entry.requestSnapshot != null && entry.requestSnapshot.authoredRequest != null)));
        } else {
            entry.result = HistoryResult.from(false, null, false, unresolvedVariables != null && !unresolvedVariables.isEmpty());
        }
        entry.unresolvedVariables = normalizeStrings(unresolvedVariables);
        if (entry.responseSnapshot == null
                && entry.statusCode <= 0
                && entry.errorMessage != null
                && !entry.errorMessage.isBlank()
                && entry.result != HistoryResult.BLOCKED
                && entry.result != HistoryResult.TIMEOUT) {
            entry.result = HistoryResult.ERROR;
        }
        return entry;
    }

    public static HistoryEntry fromRunnerAttempt(ApiCollection collection,
                                                 ApiRequest request,
                                                 EnvironmentProfile environment,
                                                 RunnerResult result) {
        HistoryEntry entry = createBase(HistorySource.RUNNER, collection, request, environment,
                result != null ? Math.max(1, result.attemptNumber) : 1,
                result != null ? Math.max(1, result.totalAttempts) : 1);
        entry.timestamp = Instant.now();
        entry.requestSnapshot = HistoryRequestSnapshot.from(request);
        if (result != null) {
            entry.statusCode = result.statusCode >= 0 ? result.statusCode : -1;
            entry.durationMillis = result.responseTimeMs;
            entry.requestSizeBytes = estimateRequestSize(result);
            entry.responseSizeBytes = result.responseSize;
            entry.responseSnapshot = HistoryResponseSnapshot.from(result);
            entry.errorMessage = result.errorMessage;
            entry.requestSent = result.requestSent;
            entry.preflightStatus = result.preflightStatus != null ? result.preflightStatus.name() : null;
            entry.preflightMessage = result.preflightMessage;
            entry.responseTimedOut = result.responseTimedOut;
            entry.timeoutMillis = result.timeoutMillis;
            entry.originalResolvedUrl = result.originalResolvedUrl;
            entry.effectiveResolvedUrl = result.effectiveResolvedUrl;
            entry.targetChanged = result.targetChanged;
            entry.oauth2Required = result.oauth2Required;
            entry.oauth2Ready = result.oauth2Ready;
            entry.oauth2UsedStaleToken = result.oauth2UsedStaleToken;
            entry.oauth2SentWithoutToken = result.oauth2SentWithoutToken;
            entry.policyOverridesApplied = result.policyOverridesApplied != null ? new ArrayList<>(result.policyOverridesApplied) : new ArrayList<>();
            entry.assertions = copyAssertions(result.assertions);
            entry.extractions = copyExtractions(result.extractedVariables);
            entry.unresolvedVariables = normalizeStrings(result.unresolvedVariables != null && !result.unresolvedVariables.isEmpty()
                    ? result.unresolvedVariables
                    : extractUnresolvedFromResult(result));
            entry.scriptEngineName = result.scriptEngineName;
            entry.executionSource = result.executionSource != null ? result.executionSource.name() : null;
            entry.scriptLogs = copyScriptLogs(result.scriptLogs);
            entry.scriptWarnings = result.scriptWarnings != null ? new ArrayList<>(result.scriptWarnings) : new ArrayList<>();
            entry.scriptErrors = result.scriptErrors != null ? new ArrayList<>(result.scriptErrors) : new ArrayList<>();
            entry.scriptVariableMutations = copyScriptMutations(result.scriptVariableMutations);
            entry.scriptFlowControl = result.scriptFlowControl != null ? result.scriptFlowControl : ScriptFlowControl.CONTINUE;
            entry.scriptFlowMessage = result.scriptFlowMessage;
            entry.scriptFlowNextRequestName = result.scriptFlowNextRequestName;
            entry.scriptFlowNextRequestId = result.scriptFlowNextRequestId;
            entry.redirectsEnabled = result.redirectsEnabled;
            entry.initialResolvedUrl = result.initialResolvedUrl != null ? result.initialResolvedUrl : result.requestUrl;
            entry.finalResolvedUrl = result.finalResolvedUrl != null ? result.finalResolvedUrl : result.requestUrl;
            entry.redirectTerminationReason = result.redirectTerminationReason;
            entry.redirectHops = copyRedirectHops(result.redirectHops);
            entry.host = result.host != null && !result.host.isBlank() ? result.host : parseHost(entry.finalResolvedUrl);
            entry.resultClassification = entry.result != null ? entry.result.displayName() : null;
            if (entry.requestSnapshot != null) {
                if (result.requestSent) {
                    entry.requestSnapshot.rawRequestSent = result.rawRequestBytes != null ? result.rawRequestBytes.clone() : null;
                    entry.requestSnapshot.rawRequestSentText = result.rawRequestText != null
                            ? result.rawRequestText
                            : (result.rawRequestBytes != null
                            ? new String(result.rawRequestBytes, java.nio.charset.StandardCharsets.UTF_8)
                            : null);
                }
                entry.requestSnapshot.resolvedUrl = result.requestUrl;
                entry.requestSnapshot.resolvedVariables = result.resolvedVariables != null
                        ? new LinkedHashMap<>(result.resolvedVariables)
                        : new LinkedHashMap<>();
            }
            entry.result = HistoryResult.from(result, hasFailedAssertion(result.assertions), !entry.unresolvedVariables.isEmpty());
            if (entry.statusCode >= 400 && entry.result == HistoryResult.SUCCESS) {
                entry.result = HistoryResult.FAILURE;
            }
            entry.resultClassification = entry.result != null ? entry.result.displayName() : null;
            entry.metadataSummaryText = buildExecutionMetadataText(entry);
            if (entry.requestSizeBytes <= 0 && entry.requestSnapshot != null) {
                entry.requestSizeBytes = entry.requestSnapshot.approximateSizeBytes();
            }
            DiagnosticStore.getInstance().record(DiagnosticEvent.of(DiagnosticOperation.HISTORY_CAPTURE, DiagnosticSeverity.INFO, "HistoryEntry",
                    "Runner history captured")
                    .withDetails("rawRequestAvailable=" + (entry.requestSnapshot != null && entry.requestSnapshot.hasRawRequestSent())
                            + " authoredTemplateAvailable=" + (entry.requestSnapshot != null && entry.requestSnapshot.authoredRequest != null)));
        }
        if (entry.responseSnapshot == null
                && entry.statusCode <= 0
                && entry.errorMessage != null
                && !entry.errorMessage.isBlank()
                && entry.result != HistoryResult.BLOCKED
                && entry.result != HistoryResult.TIMEOUT) {
            entry.result = HistoryResult.ERROR;
        }
        return entry;
    }

    public static HistoryEntry fromRedirectHop(RunnerResult parent, RedirectHop hop) {
        HistoryEntry entry = createBase(HistorySource.RUNNER, null, null, null,
                parent != null ? Math.max(1, parent.attemptNumber) : 1,
                parent != null ? Math.max(1, parent.totalAttempts) : 1);
        entry.timestamp = Instant.now();
        entry.requestName = parent != null ? parent.requestName : null;
        entry.requestId = parent != null ? parent.requestId : null;
        entry.collectionId = parent != null ? parent.collectionName : null;
        entry.collectionName = parent != null ? parent.collectionName : null;
        entry.folderPath = parent != null ? parent.folderPath : null;
        entry.statusCode = hop != null ? hop.statusCode : -1;
        entry.durationMillis = hop != null ? hop.elapsedMs : 0L;
        entry.requestSizeBytes = hop != null && hop.rawRequestBytes != null ? hop.rawRequestBytes.length : 0L;
        entry.responseSnapshot = responseSnapshotFromRedirectHop(hop);
        entry.responseSizeBytes = entry.responseSnapshot != null && entry.responseSnapshot.body != null ? entry.responseSnapshot.body.length : 0L;
        entry.errorMessage = hop != null ? hop.failureReason : null;
        entry.result = hop != null && hop.followed ? HistoryResult.SUCCESS : HistoryResult.STOPPED;
        entry.resultClassification = entry.result.displayName();
        entry.executionSource = parent != null && parent.executionSource != null ? parent.executionSource.name() : null;
        entry.redirectsEnabled = parent != null ? parent.redirectsEnabled : null;
        entry.initialResolvedUrl = parent != null ? parent.initialResolvedUrl : null;
        entry.finalResolvedUrl = hop != null ? hop.targetUrl : null;
        entry.redirectTerminationReason = parent != null ? parent.redirectTerminationReason : RedirectTerminationReason.NONE;
        entry.redirectHops = hop != null ? List.of(RedirectHop.copyOf(hop)) : new ArrayList<>();
        entry.requestSnapshot = new HistoryRequestSnapshot();
        entry.requestSnapshot.method = hop != null ? hop.sourceMethod : null;
        entry.requestSnapshot.urlTemplate = hop != null ? hop.sourceUrl : null;
        entry.requestSnapshot.resolvedUrl = hop != null ? hop.sourceUrl : null;
        entry.requestSnapshot.rawRequestSent = hop != null && hop.rawRequestBytes != null ? hop.rawRequestBytes.clone() : null;
        entry.requestSnapshot.rawRequestSentText = hop != null ? hop.rawRequestText : null;
        entry.requestSnapshot.authoredRequest = null;
        entry.requestSnapshot.resolvedVariables = new LinkedHashMap<>();
        entry.requestSnapshot.headersAsAuthored = new ArrayList<>();
        if (hop != null && hop.rawRequestText != null) {
            entry.requestSnapshot.bodyAsAuthored = hop.rawRequestText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        entry.metadataSummaryText = buildRedirectHopMetadataText(hop);
        return entry;
    }

    public static HistoryEntry copyOf(HistoryEntry source) {
        if (source == null) {
            return null;
        }
        HistoryEntry copy = new HistoryEntry();
        copy.id = source.id;
        copy.timestamp = source.timestamp;
        copy.source = source.source;
        copy.attemptNumber = source.attemptNumber;
        copy.totalAttempts = source.totalAttempts;
        copy.collectionId = source.collectionId;
        copy.collectionName = source.collectionName;
        copy.folderPath = source.folderPath;
        copy.requestId = source.requestId;
        copy.requestName = source.requestName;
        copy.environmentId = source.environmentId;
        copy.environmentName = source.environmentName;
        copy.requestSnapshot = HistoryRequestSnapshot.copyOf(source.requestSnapshot);
        copy.responseSnapshot = HistoryResponseSnapshot.copyOf(source.responseSnapshot);
        copy.statusCode = source.statusCode;
        copy.durationMillis = source.durationMillis;
        copy.requestSizeBytes = source.requestSizeBytes;
        copy.responseSizeBytes = source.responseSizeBytes;
        copy.result = source.result;
        copy.errorMessage = source.errorMessage;
        copy.unresolvedVariables = source.unresolvedVariables != null ? new ArrayList<>(source.unresolvedVariables) : new ArrayList<>();
        copy.requestSent = source.requestSent;
        copy.preflightStatus = source.preflightStatus;
        copy.preflightMessage = source.preflightMessage;
        copy.responseTimedOut = source.responseTimedOut;
        copy.timeoutMillis = source.timeoutMillis;
        copy.originalResolvedUrl = source.originalResolvedUrl;
        copy.effectiveResolvedUrl = source.effectiveResolvedUrl;
        copy.targetChanged = source.targetChanged;
        copy.oauth2Required = source.oauth2Required;
        copy.oauth2Ready = source.oauth2Ready;
        copy.oauth2UsedStaleToken = source.oauth2UsedStaleToken;
        copy.oauth2SentWithoutToken = source.oauth2SentWithoutToken;
        copy.policyOverridesApplied = source.policyOverridesApplied != null ? new ArrayList<>(source.policyOverridesApplied) : new ArrayList<>();
        copy.assertions = copyHistoryAssertions(source.assertions);
        copy.extractions = copyHistoryExtractions(source.extractions);
        copy.scriptEngineName = source.scriptEngineName;
        copy.executionSource = source.executionSource;
        copy.scriptLogs = copyScriptLogs(source.scriptLogs);
        copy.scriptWarnings = source.scriptWarnings != null ? new ArrayList<>(source.scriptWarnings) : new ArrayList<>();
        copy.scriptErrors = source.scriptErrors != null ? new ArrayList<>(source.scriptErrors) : new ArrayList<>();
        copy.scriptVariableMutations = copyScriptMutations(source.scriptVariableMutations);
        copy.scriptFlowControl = source.scriptFlowControl != null ? source.scriptFlowControl : ScriptFlowControl.CONTINUE;
        copy.scriptFlowMessage = source.scriptFlowMessage;
        copy.scriptFlowNextRequestName = source.scriptFlowNextRequestName;
        copy.scriptFlowNextRequestId = source.scriptFlowNextRequestId;
        copy.redirectsEnabled = source.redirectsEnabled;
        copy.initialResolvedUrl = source.initialResolvedUrl;
        copy.finalResolvedUrl = source.finalResolvedUrl;
        copy.redirectTerminationReason = source.redirectTerminationReason;
        copy.redirectHops = copyRedirectHops(source.redirectHops);
        copy.host = source.host;
        copy.scriptMode = source.scriptMode;
        copy.scriptDialect = source.scriptDialect;
        copy.resultClassification = source.resultClassification;
        copy.variablesSummaryText = source.variablesSummaryText;
        copy.scriptOutputSummaryText = source.scriptOutputSummaryText;
        copy.assertionsSummaryText = source.assertionsSummaryText;
        copy.metadataSummaryText = source.metadataSummaryText;
        return copy;
    }

    public void ensureDefaults() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (source == null) {
            source = HistorySource.WORKBENCH;
        }
        if (attemptNumber <= 0) {
            attemptNumber = 1;
        }
        if (totalAttempts <= 0) {
            totalAttempts = 1;
        }
        if (requestSnapshot == null) {
            requestSnapshot = new HistoryRequestSnapshot();
        }
        if (responseSnapshot == null) {
            responseSnapshot = new HistoryResponseSnapshot();
        }
        if (unresolvedVariables == null) {
            unresolvedVariables = new ArrayList<>();
        }
        if (policyOverridesApplied == null) {
            policyOverridesApplied = new ArrayList<>();
        }
        if (assertions == null) {
            assertions = new ArrayList<>();
        }
        if (extractions == null) {
            extractions = new ArrayList<>();
        }
        if (scriptLogs == null) {
            scriptLogs = new ArrayList<>();
        }
        if (scriptWarnings == null) {
            scriptWarnings = new ArrayList<>();
        }
        if (scriptErrors == null) {
            scriptErrors = new ArrayList<>();
        }
        if (scriptVariableMutations == null) {
            scriptVariableMutations = new ArrayList<>();
        }
        if (scriptFlowControl == null) {
            scriptFlowControl = ScriptFlowControl.CONTINUE;
        }
        if (redirectTerminationReason == null) {
            redirectTerminationReason = RedirectTerminationReason.NONE;
        }
        if (redirectHops == null) {
            redirectHops = new ArrayList<>();
        }
        if (result == null) {
            result = HistoryResult.UNKNOWN;
        }
        if (preflightStatus == null) {
            preflightStatus = result == HistoryResult.BLOCKED ? ExecutionPreflightStatus.BLOCKED_POLICY.name() : ExecutionPreflightStatus.READY.name();
        }
        if (resultClassification == null && result != null) {
            resultClassification = result.displayName();
        }
    }

    public String attemptDisplay() {
        return attemptNumber + "/" + totalAttempts;
    }

    public boolean hasResponseBody() {
        return responseSnapshot != null && responseSnapshot.hasBody();
    }

    public boolean hasError() {
        return errorMessage != null && !errorMessage.isBlank();
    }

    public boolean hasAssertionFailure() {
        if (assertions == null) {
            return false;
        }
        for (HistoryAssertionResult assertion : assertions) {
            if (assertion != null && !assertion.passed) {
                return true;
            }
        }
        return false;
    }

    public String requestDisplayName() {
        return requestName != null && !requestName.isBlank()
                ? requestName
                : (requestSnapshot != null ? requestSnapshot.urlTemplate : "");
    }

    public String collectionDisplayName() {
        return collectionName != null && !collectionName.isBlank() ? collectionName : "";
    }

    public String folderDisplayName() {
        return folderPath != null ? folderPath : "";
    }

    public String environmentDisplayName() {
        return environmentName != null && !environmentName.isBlank() ? environmentName : "No Environment";
    }

    public String resultDisplayName() {
        return result != null ? result.displayName() : HistoryResult.UNKNOWN.displayName();
    }

    public String timeDisplay() {
        return timestamp != null ? timestamp.toString() : "";
    }

    public String summaryLine() {
        return String.join(" | ",
                timeDisplay(),
                source != null ? source.displayName() : "",
                attemptDisplay(),
                collectionDisplayName(),
                folderDisplayName(),
                requestDisplayName(),
                requestSnapshot != null && requestSnapshot.method != null ? requestSnapshot.method : "",
                requestSnapshot != null && requestSnapshot.urlTemplate != null ? requestSnapshot.urlTemplate : "",
                statusCode > 0 ? String.valueOf(statusCode) : (hasError() ? "ERR" : ""),
                durationMillis > 0 ? durationMillis + "ms" : "",
                historySizeLabel(),
                environmentDisplayName(),
                resultDisplayName());
    }

    public String historySizeLabel() {
        long bytes = responseSizeBytes > 0 ? responseSizeBytes : requestSizeBytes;
        if (bytes <= 0) {
            return "";
        }
        return formatBytes(bytes);
    }

    public String toMetadataText() {
        if (metadataSummaryText != null && !metadataSummaryText.isBlank()) {
            return metadataSummaryText.trim();
        }
        return buildLegacyMetadataText(this);
    }

    private static String buildLegacyMetadataText(HistoryEntry entry) {
        if (entry == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("History ID: ").append(entry.id != null ? entry.id : "").append('\n');
        sb.append("Created / Executed Timestamp: ").append(entry.timeDisplay()).append('\n');
        sb.append("Source: ").append(entry.source != null ? entry.source.displayName() : "").append('\n');
        sb.append("Collection: ").append(entry.collectionName != null ? entry.collectionName : "").append('\n');
        sb.append("Folder / Request Path: ").append(entry.folderPath != null ? entry.folderPath : "").append('\n');
        sb.append("Request Name: ").append(entry.requestName != null ? entry.requestName : "").append('\n');
        sb.append("Method: ").append(entry.requestSnapshot != null && entry.requestSnapshot.method != null ? entry.requestSnapshot.method : "").append('\n');
        sb.append("URL Template: ").append(entry.requestSnapshot != null && entry.requestSnapshot.urlTemplate != null ? entry.requestSnapshot.urlTemplate : "").append('\n');
        sb.append("Redirects Enabled: ").append(entry.redirectsEnabled != null ? entry.redirectsEnabled : "Not yet sent").append('\n');
        sb.append("Initial Resolved URL: ").append(entry.initialResolvedUrl != null && !entry.initialResolvedUrl.isBlank() ? entry.initialResolvedUrl : "Not yet sent").append('\n');
        sb.append("Final Resolved URL: ").append(entry.finalResolvedUrl != null && !entry.finalResolvedUrl.isBlank() ? entry.finalResolvedUrl : "Not yet sent").append('\n');
        sb.append("Redirect Termination Reason: ").append(entry.redirectTerminationReason != null ? entry.redirectTerminationReason.displayLabel() : "Not yet sent").append('\n');
        sb.append("Followed Redirect Hops: ").append(entry.countFollowedRedirectHops()).append('\n');
        sb.append("Host: ").append(entry.host != null && !entry.host.isBlank() ? entry.host : "Not yet sent").append('\n');
        sb.append("Build Mode: ").append(entry.requestSnapshot != null && entry.requestSnapshot.buildMode != null
                ? entry.requestSnapshot.buildMode.name()
                : entry.requestSnapshot != null && entry.requestSnapshot.authoredRequest != null && entry.requestSnapshot.authoredRequest.resolveBuildMode() != null
                    ? entry.requestSnapshot.authoredRequest.resolveBuildMode().name()
                    : "Not yet sent").append('\n');
        sb.append("Active Environment: ").append(entry.environmentName != null ? entry.environmentName : "No Environment").append('\n');
        sb.append("Auth Mode / Auth Source: ").append(entry.resolveAuthLabel()).append('\n');
        sb.append("Execution Source: ").append(entry.executionSource != null && !entry.executionSource.isBlank() ? entry.executionSource : "Not yet sent").append('\n');
        sb.append("Execution Attempt: ").append(entry.isExecuted() ? entry.attemptDisplay() : "Not yet sent").append('\n');
        sb.append("Runner Attempt Number: ").append(entry.source == HistorySource.RUNNER ? entry.attemptDisplay() : (entry.isExecuted() ? "Not applicable" : "Not yet sent")).append('\n');
        sb.append("Status Code: ").append(entry.statusCode > 0 ? entry.statusCode : "Not yet sent").append('\n');
        sb.append("Duration: ").append(entry.durationMillis > 0 ? entry.durationMillis + " ms" : "Not yet sent").append('\n');
        sb.append("Request Size: ").append(entry.requestSizeBytes > 0 ? entry.requestSizeBytes + " bytes" : "Not yet sent").append('\n');
        sb.append("Response Size: ").append(entry.responseSizeBytes > 0 ? entry.responseSizeBytes + " bytes" : "Not yet sent").append('\n');
        sb.append("Result Classification: ").append(entry.resultClassification != null && !entry.resultClassification.isBlank() ? entry.resultClassification : (entry.isExecuted() ? entry.resultDisplayName() : "Not yet sent")).append('\n');
        sb.append("Script Engine: ").append(entry.displayValue(entry.scriptEngineName)).append('\n');
        sb.append("Script Mode: ").append(entry.displayValue(entry.scriptMode)).append('\n');
        sb.append("Script Dialect: ").append(entry.displayValue(entry.scriptDialect)).append('\n');
        sb.append("Flow Control: ").append(entry.scriptFlowControl != null ? entry.scriptFlowControl : ScriptFlowControl.CONTINUE).append('\n');
        sb.append("Flow Message: ").append(entry.scriptFlowMessage != null ? entry.scriptFlowMessage : "").append('\n');
        sb.append("Raw Request Available: ").append(entry.requestSnapshot != null && entry.requestSnapshot.hasRawRequestSent() ? "yes" : "no").append('\n');
        sb.append("Response Available: ").append(entry.responseSnapshot != null && entry.responseSnapshot.hasBody() ? "yes" : "no").append('\n');
        sb.append("Script Logs: ").append(entry.scriptLogs != null ? entry.scriptLogs.size() : 0).append('\n');
        sb.append("Script Warnings: ").append(entry.scriptWarnings != null ? entry.scriptWarnings.size() : 0).append('\n');
        sb.append("Script Errors: ").append(entry.scriptErrors != null ? entry.scriptErrors.size() : 0).append('\n');
        sb.append("Script Mutations: ").append(entry.scriptVariableMutations != null ? entry.scriptVariableMutations.size() : 0).append('\n');
        sb.append("Error Message: ").append(entry.errorMessage != null ? entry.errorMessage : "").append('\n');
        sb.append("Unresolved Variables: ").append(String.join(", ", entry.unresolvedVariables != null ? entry.unresolvedVariables : List.of())).append('\n');
        sb.append("Request Sent: ").append(entry.requestSent ? "Yes" : "No").append('\n');
        sb.append("Preflight Status: ").append(entry.preflightStatus != null ? entry.preflightStatus : "").append('\n');
        sb.append("Preflight Message: ").append(entry.preflightMessage != null ? entry.preflightMessage : "").append('\n');
        sb.append("Timeout: ").append(entry.timeoutMillis > 0 ? entry.timeoutMillis + " ms" : "").append('\n');
        sb.append("Original Origin: ").append(originDisplay(entry.originalResolvedUrl)).append('\n');
        sb.append("Effective Origin: ").append(originDisplay(entry.effectiveResolvedUrl != null ? entry.effectiveResolvedUrl : entry.finalResolvedUrl)).append('\n');
        sb.append("Policy Overrides: ").append(String.join(", ", entry.policyOverridesApplied != null ? entry.policyOverridesApplied : List.of())).append('\n');
        if (entry.redirectHops != null && !entry.redirectHops.isEmpty()) {
            sb.append("Redirect Hop Evidence:").append('\n');
            for (RedirectHop hop : entry.redirectHops) {
                if (hop != null) {
                    sb.append(" - ").append(hop.safeSummary()).append('\n');
                }
            }
        }
        return sb.toString().trim();
    }

    private String displayValue(String value) {
        if (value != null && !value.isBlank()) {
            return value;
        }
        return isExecuted() ? "Not available" : "Not yet sent";
    }

    public boolean isExecuted() {
        if (result != null && result != HistoryResult.UNKNOWN) {
            return true;
        }
        return (statusCode > 0)
                || durationMillis > 0
                || (responseSnapshot != null && responseSnapshot.hasBody())
                || (finalResolvedUrl != null && !finalResolvedUrl.isBlank())
                || (requestSnapshot != null && requestSnapshot.hasRawRequestSent());
    }

    private String resolveAuthLabel() {
        String authType = requestSnapshot != null ? requestSnapshot.authType : null;
        if (authType == null || authType.isBlank()) {
            return isExecuted() ? "Not yet sent" : "Not yet sent";
        }
        String normalizedType = authType.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                + (authType.length() > 1 ? authType.substring(1).toLowerCase(java.util.Locale.ROOT) : "");
        StringBuilder sb = new StringBuilder(normalizedType);
        if (requestSnapshot != null && requestSnapshot.authoredRequest != null
                && requestSnapshot.authoredRequest.authSource != null
                && !requestSnapshot.authoredRequest.authSource.isBlank()) {
            sb.append(" (").append(requestSnapshot.authoredRequest.authSource).append(")");
        }
        return sb.toString();
    }

    private static HistoryResponseSnapshot responseSnapshotFromRedirectHop(RedirectHop hop) {
        HistoryResponseSnapshot snapshot = new HistoryResponseSnapshot();
        if (hop == null) {
            return snapshot;
        }
        snapshot.statusCode = hop.statusCode;
        snapshot.reasonPhrase = parseReasonPhrase(hop.responseHeadersText);
        snapshot.headers = parseHeaders(hop.responseHeadersText);
        snapshot.body = hop.responseBody != null ? hop.responseBody.clone() : null;
        snapshot.mimeType = findContentType(snapshot.headers);
        return snapshot;
    }

    private static List<HistoryHeader> parseHeaders(String responseHeaders) {
        List<HistoryHeader> headers = new ArrayList<>();
        if (responseHeaders == null || responseHeaders.isBlank()) {
            return headers;
        }
        String[] lines = responseHeaders.replace("\r", "").split("\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            headers.add(new HistoryHeader(name, value, false));
        }
        return headers;
    }

    private static String parseReasonPhrase(String responseHeaders) {
        if (responseHeaders == null || responseHeaders.isBlank()) {
            return "";
        }
        String[] lines = responseHeaders.replace("\r", "").split("\n");
        if (lines.length == 0) {
            return "";
        }
        String statusLine = lines[0].trim();
        int firstSpace = statusLine.indexOf(' ');
        if (firstSpace < 0) {
            return "";
        }
        String remainder = statusLine.substring(firstSpace + 1).trim();
        int secondSpace = remainder.indexOf(' ');
        if (secondSpace < 0) {
            return remainder;
        }
        return remainder.substring(secondSpace + 1).trim();
    }

    private static String findContentType(List<HistoryHeader> headers) {
        for (HistoryHeader header : headers != null ? headers : List.<HistoryHeader>of()) {
            if (header != null && header.name != null && "content-type".equalsIgnoreCase(header.name)) {
                return header.value;
            }
        }
        return null;
    }

    private static List<RedirectHop> copyRedirectHops(List<RedirectHop> hops) {
        List<RedirectHop> copy = new ArrayList<>();
        if (hops == null) {
            return copy;
        }
        for (RedirectHop hop : hops) {
            RedirectHop hopCopy = RedirectHop.copyOf(hop);
            if (hopCopy != null) {
                copy.add(hopCopy);
            }
        }
        return copy;
    }

    private static String buildRedirectHopMetadataText(RedirectHop hop) {
        if (hop == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Redirect Hop: ").append(hop.hopNumber > 0 ? hop.hopNumber : "?").append('\n');
        sb.append("Source URL: ").append(hop.sourceUrl != null ? hop.sourceUrl : "").append('\n');
        sb.append("Source Method: ").append(hop.sourceMethod != null ? hop.sourceMethod : "").append('\n');
        sb.append("Redirect Status: ").append(hop.statusCode > 0 ? hop.statusCode : "").append('\n');
        sb.append("Location: ").append(hop.location != null ? hop.location : "").append('\n');
        sb.append("Target URL: ").append(hop.targetUrl != null ? hop.targetUrl : "").append('\n');
        sb.append("Target Method: ").append(hop.targetMethod != null ? hop.targetMethod : "").append('\n');
        sb.append("Elapsed: ").append(hop.elapsedMs > 0 ? hop.elapsedMs + " ms" : "").append('\n');
        sb.append("Followed: ").append(hop.followed).append('\n');
        sb.append("Failure Reason: ").append(hop.failureReason != null ? hop.failureReason : "").append('\n');
        sb.append("Forwarded Sensitive Header Names: ").append(String.join(", ", hop.forwardedSensitiveHeaderNames != null ? hop.forwardedSensitiveHeaderNames : List.of())).append('\n');
        sb.append("Stripped Sensitive Header Names: ").append(String.join(", ", hop.strippedSensitiveHeaderNames != null ? hop.strippedSensitiveHeaderNames : List.of())).append('\n');
        return sb.toString().trim();
    }

    private static String buildExecutionMetadataText(HistoryEntry entry) {
        if (entry == null) {
            return "";
        }
        return buildLegacyMetadataText(entry);
    }

    private static String originDisplay(String resolvedUrl) {
        if (resolvedUrl == null || resolvedUrl.isBlank()) {
            return "";
        }
        try {
            HttpUtils.ParsedTarget parsed = HttpUtils.parseTargetForRequest(resolvedUrl);
            if (parsed == null || parsed.host == null || parsed.host.isBlank()) {
                return "";
            }
            String scheme = parsed.useHttps ? "https" : "http";
            String host = parsed.host.startsWith("[") && parsed.host.endsWith("]")
                    ? parsed.host.substring(1, parsed.host.length() - 1)
                    : parsed.host.toLowerCase(java.util.Locale.ROOT);
            int port = parsed.port > 0 ? parsed.port : (parsed.useHttps ? 443 : 80);
            return scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            return resolvedUrl;
        }
    }

    private int countFollowedRedirectHops() {
        if (redirectHops == null || redirectHops.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (RedirectHop hop : redirectHops) {
            if (hop != null && hop.followed) {
                count++;
            }
        }
        return count;
    }

    private static String parseHost(String resolvedUrl) {
        if (resolvedUrl == null || resolvedUrl.isBlank()) {
            return null;
        }
        try {
            return HttpUtils.parseTargetForRequest(resolvedUrl).host;
        } catch (Exception e) {
            return null;
        }
    }

    private static HistoryEntry createBase(HistorySource source,
                                           ApiCollection collection,
                                           ApiRequest request,
                                           EnvironmentProfile environment,
                                           int attemptNumber,
                                           int totalAttempts) {
        HistoryEntry entry = new HistoryEntry();
        entry.id = UUID.randomUUID().toString();
        entry.source = source != null ? source : HistorySource.WORKBENCH;
        entry.attemptNumber = Math.max(1, attemptNumber);
        entry.totalAttempts = Math.max(1, totalAttempts);
        if (collection != null) {
            entry.collectionName = collection.name;
            entry.collectionId = collection.ensureId();
        }
        if (request != null) {
            entry.requestId = request.id;
            entry.requestName = request.name;
            entry.folderPath = RequestPathResolver.getRequestFolderPath(collection, request);
        }
        if (environment != null) {
            entry.environmentId = environment.id;
            entry.environmentName = environment.displayName();
        }
        return entry;
    }

    private static long estimateRequestSize(RunnerResult result) {
        long size = 0L;
        if (result == null) {
            return size;
        }
        if (result.rawRequestBytes != null && result.rawRequestBytes.length > 0) {
            return result.rawRequestBytes.length;
        }
        if (result.requestHeaders != null) {
            size += result.requestHeaders.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        if (result.requestBody != null) {
            size += result.requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        if (size <= 0 && result.requestUrl != null) {
            size += result.requestUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        return size;
    }

    private static int determineStatusCode(ExecutionResult exec, HttpResponse response) {
        if (response != null) {
            return response.statusCode();
        }
        if (exec != null && (exec.isBlockedBeforeSend() || exec.responseTimedOut)) {
            return 0;
        }
        if (exec != null && exec.response != null && exec.response.response() != null) {
            return exec.response.response().statusCode();
        }
        return -1;
    }

    private static boolean hasFailedAssertion(List<RunnerResult.AssertionResult> assertions) {
        if (assertions == null) {
            return false;
        }
        for (RunnerResult.AssertionResult assertion : assertions) {
            if (assertion != null && !assertion.passed) {
                return true;
            }
        }
        return false;
    }

    private static List<HistoryAssertionResult> copyAssertions(List<RunnerResult.AssertionResult> assertions) {
        List<HistoryAssertionResult> out = new ArrayList<>();
        if (assertions == null) {
            return out;
        }
        for (RunnerResult.AssertionResult assertion : assertions) {
            if (assertion == null) {
                continue;
            }
            out.add(new HistoryAssertionResult(assertion.name, assertion.passed, assertion.expected, assertion.actual));
        }
        return out;
    }

    private static List<HistoryAssertionResult> copyHistoryAssertions(List<HistoryAssertionResult> assertions) {
        List<HistoryAssertionResult> out = new ArrayList<>();
        if (assertions == null) {
            return out;
        }
        for (HistoryAssertionResult assertion : assertions) {
            HistoryAssertionResult copy = HistoryAssertionResult.copyOf(assertion);
            if (copy != null) {
                out.add(copy);
            }
        }
        return out;
    }

    private static List<HistoryExtractionResult> copyExtractions(Map<String, String> extracted) {
        List<HistoryExtractionResult> out = new ArrayList<>();
        if (extracted == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : extracted.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            out.add(new HistoryExtractionResult(entry.getKey(), entry.getValue(), "extracted", null));
        }
        return out;
    }

    private static List<HistoryExtractionResult> copyHistoryExtractions(List<HistoryExtractionResult> extractions) {
        List<HistoryExtractionResult> out = new ArrayList<>();
        if (extractions == null) {
            return out;
        }
        for (HistoryExtractionResult extraction : extractions) {
            HistoryExtractionResult copy = HistoryExtractionResult.copyOf(extraction);
            if (copy != null) {
                out.add(copy);
            }
        }
        return out;
    }

    private static List<ScriptLogEntry> copyScriptLogs(List<ScriptLogEntry> logs) {
        List<ScriptLogEntry> out = new ArrayList<>();
        if (logs == null) {
            return out;
        }
        for (ScriptLogEntry log : logs) {
            if (log == null) {
                continue;
            }
            ScriptLogEntry copy = new ScriptLogEntry();
            copy.level = log.level;
            copy.message = log.message;
            copy.scriptId = log.scriptId;
            copy.scriptName = log.scriptName;
            out.add(copy);
        }
        return out;
    }

    private static List<ScriptVariableMutation> copyScriptMutations(List<ScriptVariableMutation> mutations) {
        List<ScriptVariableMutation> out = new ArrayList<>();
        if (mutations == null) {
            return out;
        }
        for (ScriptVariableMutation mutation : mutations) {
            if (mutation == null) {
                continue;
            }
            ScriptVariableMutation copy = new ScriptVariableMutation();
            copy.key = mutation.key;
            copy.oldValue = mutation.oldValue;
            copy.newValue = mutation.newValue;
            copy.scope = mutation.scope;
            copy.persistent = mutation.persistent;
            copy.sourceScriptId = mutation.sourceScriptId;
            copy.sourceScriptName = mutation.sourceScriptName;
            out.add(copy);
        }
        return out;
    }

    private static boolean isIntentionalNoResponseFlow(ScriptFlowControl flowControl, boolean noResponse) {
        return noResponse && (flowControl == ScriptFlowControl.SKIP_REQUEST || flowControl == ScriptFlowControl.STOP_RUN);
    }

    private static HistoryResult historyResultForFlowControl(ScriptFlowControl flowControl) {
        if (flowControl == ScriptFlowControl.SKIP_REQUEST) {
            return HistoryResult.SKIPPED;
        }
        if (flowControl == ScriptFlowControl.STOP_RUN) {
            return HistoryResult.STOPPED;
        }
        return HistoryResult.UNKNOWN;
    }

    private static List<String> extractUnresolvedFromResult(RunnerResult result) {
        List<String> unresolved = new ArrayList<>();
        if (result == null) {
            return unresolved;
        }
        if (result.errorMessage != null && result.errorMessage.toLowerCase().contains("variable")) {
            unresolved.add(result.errorMessage);
        }
        return unresolved;
    }

    private static List<String> normalizeStrings(Collection<String> values) {
        List<String> normalized = new ArrayList<>();
        if (values == null) {
            return normalized;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (seen.add(value)) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB"};
        int unitIndex = -1;
        while (value >= 1024 && unitIndex + 1 < units.length) {
            value /= 1024.0;
            unitIndex++;
        }
        if (unitIndex < 0) {
            return bytes + "B";
        }
        return String.format(java.util.Locale.ROOT, "%.1f%s", value, units[unitIndex]);
    }
}
