package burp.history;

import burp.auth.TokenStore;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RedirectHop;
import burp.models.RedirectTerminationReason;
import burp.models.RunnerCancellationState;
import burp.models.RunnerResult;
import burp.runner.FlowTargetResolutionForm;
import burp.scripts.ScriptFlowControl;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptLogEntry;
import burp.scripts.ScriptVariableMutation;
import burp.runner.RetryFailureType;
import burp.parser.HistoryRawHttpMessageParser;
import burp.parser.HistoryRawHttpMessageParser.ParsedRawHttpMessage;
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
import java.util.function.ToLongFunction;
import java.util.UUID;

public class HistoryEntry {
    public String id;
    public Instant timestamp;
    public HistorySource source = HistorySource.WORKBENCH;
    public int attemptNumber = 1;
    public int totalAttempts = 1;
    public String retryDecision;
    public String retryReason;
    public int retryDelayMillis;
    public String retryFailureType;
    public boolean requestMayHaveBeenProcessed;
    public String parentRequestName;
    public String parentRequestId;
    public boolean dependentExecution;
    public boolean adHocExecution;
    public int dependentDepth;
    public String targetResolutionForm;
    public String qualifiedTargetPath;
    public String cancellationState;
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
    public boolean pinned;
    public String analystNotes = "";
    public LinkedHashSet<String> tags = new LinkedHashSet<>();

    public static HistoryEntry fromWorkbenchExecution(ApiCollection collection,
                                                      ApiRequest request,
                                                      EnvironmentProfile environment,
                                                      ExecutionResult exec,
                                                      int attemptNumber,
                                                      int totalAttempts,
                                                      Collection<String> unresolvedVariables) {
        HistoryEntry entry = createBase(
                HistorySource.WORKBENCH,
                collection,
                request,
                environment,
                attemptNumber,
                totalAttempts
        );
        entry.timestamp = Instant.now();
        entry.requestSnapshot = HistoryRequestSnapshot.from(request);
        entry.unresolvedVariables = normalizeStrings(unresolvedVariables);

        if (exec != null) {
            entry.durationMillis = exec.elapsedMs;
            entry.errorMessage = exec.errorMessage;
            entry.statusCode = determineStatusCode(exec, null);
            entry.responseSnapshot = exec.response != null
                    ? HistoryResponseSnapshot.from(exec.response.response())
                    : null;
            entry.responseSizeBytes = entry.responseSnapshot != null
                    && entry.responseSnapshot.body != null
                    ? entry.responseSnapshot.body.length
                    : 0L;
            entry.requestSizeBytes = exec.rawRequestBytes != null
                    ? exec.rawRequestBytes.length
                    : entry.requestSnapshot.approximateSizeBytes();
            entry.requestSent = exec.requestSent;
            entry.preflightStatus = exec.preflightStatus != null
                    ? exec.preflightStatus.name()
                    : null;
            entry.preflightMessage = exec.preflightMessage;
            entry.responseTimedOut = exec.responseTimedOut;
            entry.timeoutMillis = exec.timeoutMillis;
            entry.originalResolvedUrl = exec.originalResolvedUrl;
            entry.effectiveResolvedUrl = exec.effectiveResolvedUrl;
            entry.targetChanged = exec.targetChangeAllowed
                    || (exec.preflight != null && exec.preflight.targetChanged);
            entry.oauth2Required = exec.oauth2Required;
            entry.oauth2Ready = exec.oauth2Ready;
            entry.oauth2UsedStaleToken = exec.oauth2UsedStaleToken;
            entry.oauth2SentWithoutToken = exec.oauth2SentWithoutToken;
            entry.policyOverridesApplied = exec.preflight != null
                    ? new ArrayList<>(exec.preflight.policyOverridesApplied)
                    : new ArrayList<>(exec.policyOverridesApplied);

            if (entry.requestSnapshot != null) {
                if (exec.requestSent) {
                    entry.requestSnapshot.rawRequestSent = exec.rawRequestBytes != null
                            ? exec.rawRequestBytes.clone()
                            : null;
                    entry.requestSnapshot.rawRequestSentText = exec.rawRequestText != null
                            ? exec.rawRequestText
                            : (exec.rawRequestBytes != null
                            ? new String(
                                    exec.rawRequestBytes,
                                    java.nio.charset.StandardCharsets.UTF_8
                            )
                            : null);
                }
                entry.requestSnapshot.resolvedUrl = exec.resolvedUrl;
                entry.requestSnapshot.resolvedVariables = exec.resolvedVariables != null
                        ? new LinkedHashMap<>(exec.resolvedVariables)
                        : new LinkedHashMap<>();
            }
            entry.result = HistoryResult.from(
                    exec,
                    hasFailedAssertion(exec.assertions),
                    !entry.unresolvedVariables.isEmpty()
            );
            entry.finalResolvedUrl = exec.finalResolvedUrl != null
                    ? exec.finalResolvedUrl
                    : exec.resolvedUrl;
            entry.effectiveResolvedUrl = exec.effectiveResolvedUrl != null
                    ? exec.effectiveResolvedUrl
                    : entry.finalResolvedUrl;
            entry.host = parseHost(entry.finalResolvedUrl);
            entry.assertions = copyAssertions(exec.assertions);
            entry.extractions = copyExtractions(exec.extractedVars);
            entry.scriptEngineName = exec.scriptEngineName;
            entry.executionSource = exec.executionSource != null
                    ? exec.executionSource.name()
                    : null;
            entry.scriptLogs = copyScriptLogs(exec.scriptLogs);
            entry.scriptWarnings = exec.scriptWarnings != null
                    ? new ArrayList<>(exec.scriptWarnings)
                    : new ArrayList<>();
            entry.scriptErrors = exec.scriptErrors != null
                    ? new ArrayList<>(exec.scriptErrors)
                    : new ArrayList<>();
            entry.scriptVariableMutations = copyScriptMutations(exec.scriptVariableMutations);
            entry.scriptFlowControl = exec.scriptFlowControl != null
                    ? exec.scriptFlowControl
                    : ScriptFlowControl.CONTINUE;
            entry.scriptFlowMessage = exec.scriptFlowMessage;
            entry.scriptFlowNextRequestName = exec.scriptFlowNextRequestName;
            entry.scriptFlowNextRequestId = exec.scriptFlowNextRequestId;
            entry.redirectsEnabled = exec.redirectsEnabled;
            entry.initialResolvedUrl = exec.initialResolvedUrl != null
                    ? exec.initialResolvedUrl
                    : exec.resolvedUrl;
            entry.redirectTerminationReason = exec.redirectTerminationReason;
            entry.redirectHops = copyRedirectHops(exec.redirectHops);

            if (entry.statusCode >= 400 && entry.result == HistoryResult.SUCCESS) {
                entry.result = HistoryResult.FAILURE;
            }
            DiagnosticStore.getInstance().record(DiagnosticEvent.of(DiagnosticOperation.HISTORY_CAPTURE, DiagnosticSeverity.INFO, "HistoryEntry",
                    "Workbench history captured")
                    .withDetails("rawRequestAvailable=" + (entry.requestSnapshot != null && entry.requestSnapshot.hasRawRequestSent())
                            + " authoredTemplateAvailable=" + (entry.requestSnapshot != null && entry.requestSnapshot.authoredRequest != null)));
        } else {
            entry.result = HistoryResult.from(
                    false,
                    null,
                    false,
                    !entry.unresolvedVariables.isEmpty()
            );
        }

        if (entry.responseSnapshot == null
                && entry.statusCode <= 0
                && entry.errorMessage != null
                && !entry.errorMessage.isBlank()
                && entry.result != HistoryResult.BLOCKED
                && entry.result != HistoryResult.TIMEOUT) {
            entry.result = HistoryResult.ERROR;
        }
        entry.resultClassification = entry.result != null
                ? entry.result.displayName()
                : null;
        entry.metadataSummaryText = buildExecutionMetadataText(entry);
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
            if ((entry.collectionId == null || entry.collectionId.isBlank()) && result.collectionId != null) {
                entry.collectionId = result.collectionId;
            }
            if ((entry.collectionName == null || entry.collectionName.isBlank()) && result.collectionName != null) {
                entry.collectionName = result.collectionName;
            }
            if (entry.requestSnapshot != null && result.buildMode != null) {
                entry.requestSnapshot.buildMode = result.buildMode;
            }
            entry.statusCode = result.statusCode >= 0 ? result.statusCode : -1;
            entry.durationMillis = result.responseTimeMs;
            entry.requestSizeBytes = estimateRequestSize(result);
            entry.responseSizeBytes = result.responseSize;
            entry.responseSnapshot = HistoryResponseSnapshot.from(result);
            entry.errorMessage = result.errorMessage;
            entry.requestSent = result.requestSent;
            entry.retryDecision = result.retryDecision;
            entry.retryReason = result.retryReason;
            entry.retryDelayMillis = result.retryDelayMillis;
            entry.retryFailureType = result.retryFailureType != null ? result.retryFailureType.name() : null;
            entry.requestMayHaveBeenProcessed = result.requestMayHaveBeenProcessed;
            entry.parentRequestName = result.parentRequestName;
            entry.parentRequestId = result.parentRequestId;
            entry.dependentExecution = result.dependentExecution;
            entry.adHocExecution = result.adHocExecution;
            entry.dependentDepth = result.dependentDepth;
            entry.targetResolutionForm = result.targetResolutionForm != null ? result.targetResolutionForm.name() : null;
            entry.qualifiedTargetPath = result.qualifiedTargetPath;
            entry.cancellationState = result.cancellationState != null ? result.cancellationState.name() : null;
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
        entry.collectionId = parent != null ? parent.collectionId : null;
        entry.collectionName = parent != null ? parent.collectionName : null;
        entry.folderPath = parent != null ? parent.folderPath : null;
        entry.statusCode = hop != null ? hop.statusCode : -1;
        entry.durationMillis = hop != null ? hop.elapsedMs : 0L;
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
        ParsedRawHttpMessage parsed = HistoryRawHttpMessageParser.parseRequest(
                hop != null ? hop.rawRequestBytes : null,
                hop != null ? hop.rawRequestText : null
        );
        entry.requestSizeBytes = parsed.rawBytes().length;
        entry.requestSnapshot.method = parsed.isTrustedRequest()
                ? parsed.method()
                : (hop != null ? hop.sourceMethod : null);
        entry.requestSnapshot.urlTemplate = hop != null ? hop.sourceUrl : null;
        entry.requestSnapshot.resolvedUrl = hop != null ? hop.sourceUrl : null;
        entry.requestSnapshot.rawRequestSent = parsed.rawBytes();
        entry.requestSnapshot.rawRequestSentText = parsed.rawText();
        entry.requestSnapshot.authoredRequest = null;
        entry.requestSnapshot.bodyMode = "raw";
        entry.requestSnapshot.bodyAsAuthored = parsed.isTrustedRequest() ? parsed.bodyBytes() : new byte[0];
        entry.requestSnapshot.parseWarning = parsed.parseWarning();
        entry.requestSnapshot.resolvedVariables = new LinkedHashMap<>();
        entry.requestSnapshot.headersAsAuthored = new ArrayList<>();
        if (parsed.isTrustedRequest()) {
            for (HistoryHeader header : parsed.headers()) {
                HistoryHeader headerCopy = HistoryHeader.copyOf(header);
                if (headerCopy != null) {
                    entry.requestSnapshot.headersAsAuthored.add(headerCopy);
                }
            }
        }
        if (hop != null) {
            entry.requestSnapshot.rawBodyTruncated = hop.rawRequestBodyTruncated;
            entry.requestSnapshot.originalRawBodyLength = hop.originalRawRequestBodyLength;
            entry.requestSnapshot.storedRawBodyLength = hop.storedRawRequestBodyLength;
            entry.requestSnapshot.fullRawBodySha256 = hop.fullRawRequestBodySha256;
            entry.requestSnapshot.rawTruncationReason = hop.rawRequestTruncationReason;
        }
        entry.requestSnapshot.buildMode = parent != null ? parent.buildMode : null;
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
        copy.retryDecision = source.retryDecision;
        copy.retryReason = source.retryReason;
        copy.retryDelayMillis = source.retryDelayMillis;
        copy.retryFailureType = source.retryFailureType;
        copy.requestMayHaveBeenProcessed = source.requestMayHaveBeenProcessed;
        copy.parentRequestName = source.parentRequestName;
        copy.parentRequestId = source.parentRequestId;
        copy.dependentExecution = source.dependentExecution;
        copy.adHocExecution = source.adHocExecution;
        copy.dependentDepth = source.dependentDepth;
        copy.targetResolutionForm = source.targetResolutionForm;
        copy.qualifiedTargetPath = source.qualifiedTargetPath;
        copy.cancellationState = source.cancellationState;
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
        copy.pinned = source.pinned;
        copy.analystNotes = source.analystNotes;
        copy.tags = source.tags != null ? new LinkedHashSet<>(source.tags) : new LinkedHashSet<>();
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
        if (cancellationState == null || cancellationState.isBlank()) {
            cancellationState = RunnerCancellationState.NOT_CANCELLED.name();
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
        if (analystNotes == null) {
            analystNotes = "";
        }
        if (tags == null) {
            tags = new LinkedHashSet<>();
        } else {
            tags = HistoryBodyTruncator.normalizeTags(tags);
        }
        HistoryBodyTruncator.normalizeSnapshotDefaults(this);
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

    public long estimatedStoredBytes() {
        long size = 0L;

        size = addUtf8(size, id);
        size = addUtf8(size, timestamp != null ? timestamp.toString() : null);
        size = addUtf8(size, source != null ? source.name() : null);
        size = addUtf8(size, retryDecision);
        size = addUtf8(size, retryReason);
        size = addUtf8(size, retryFailureType);
        size = addUtf8(size, parentRequestName);
        size = addUtf8(size, parentRequestId);
        size = addUtf8(size, targetResolutionForm);
        size = addUtf8(size, qualifiedTargetPath);
        size = addUtf8(size, cancellationState);
        size = addUtf8(size, collectionId);
        size = addUtf8(size, collectionName);
        size = addUtf8(size, folderPath);
        size = addUtf8(size, requestId);
        size = addUtf8(size, requestName);
        size = addUtf8(size, environmentId);
        size = addUtf8(size, environmentName);
        size = addUtf8(size, errorMessage);
        size = addUtf8(size, preflightStatus);
        size = addUtf8(size, preflightMessage);
        size = addUtf8(size, originalResolvedUrl);
        size = addUtf8(size, effectiveResolvedUrl);
        size = addUtf8(size, finalResolvedUrl);
        size = addUtf8(size, initialResolvedUrl);
        size = addUtf8(size, host);
        size = addUtf8(size, scriptMode);
        size = addUtf8(size, scriptDialect);
        size = addUtf8(size, resultClassification);
        size = addUtf8(size, variablesSummaryText);
        size = addUtf8(size, scriptOutputSummaryText);
        size = addUtf8(size, assertionsSummaryText);
        size = addUtf8(size, metadataSummaryText);
        size = addUtf8(size, analystNotes);
        size = addUtf8(size, scriptEngineName);
        size = addUtf8(size, executionSource);
        size = addUtf8(size, scriptFlowMessage);
        size = addUtf8(size, scriptFlowNextRequestName);
        size = addUtf8(size, scriptFlowNextRequestId);
        size = addUtf8(size, result != null ? result.name() : null);
        size = addUtf8(size, requestSnapshot != null ? requestSnapshot.truncationSummary() : null);
        size = addUtf8(size, responseSnapshot != null ? responseSnapshot.truncationSummary() : null);

        size = addBoolean(size, pinned);
        size = addBoolean(size, requestMayHaveBeenProcessed);
        size = addBoolean(size, dependentExecution);
        size = addBoolean(size, adHocExecution);
        size = addBoolean(size, requestSent);
        size = addBoolean(size, responseTimedOut);
        size = addBoolean(size, targetChanged);
        size = addBoolean(size, oauth2Required);
        size = addBoolean(size, oauth2Ready);
        size = addBoolean(size, oauth2UsedStaleToken);
        size = addBoolean(size, oauth2SentWithoutToken);
        size = addBoolean(size, redirectsEnabled != null ? redirectsEnabled : false);

        size = addLong(size, attemptNumber);
        size = addLong(size, totalAttempts);
        size = addLong(size, retryDelayMillis);
        size = addLong(size, dependentDepth);
        size = addLong(size, timeoutMillis);
        size = addLong(size, statusCode);
        size = addLong(size, durationMillis);
        size = addLong(size, requestSizeBytes);
        size = addLong(size, responseSizeBytes);

        size = addCollectionStrings(size, unresolvedVariables);
        size = addCollectionStrings(size, policyOverridesApplied);
        size = addCollectionStrings(size, scriptWarnings);
        size = addCollectionStrings(size, scriptErrors);
        size = addCollectionStrings(size, tags);

        size = addCollectionObjects(size, assertions, assertion -> {
            long total = 0L;
            total = addUtf8(total, assertion != null ? assertion.name : null);
            total = addUtf8(total, assertion != null ? assertion.expected : null);
            total = addUtf8(total, assertion != null ? assertion.actual : null);
            total = addUtf8(total, assertion != null ? assertion.message : null);
            total = addBoolean(total, assertion != null && assertion.passed);
            return total;
        });

        size = addCollectionObjects(size, extractions, extraction -> {
            long total = 0L;
            total = addUtf8(total, extraction != null ? extraction.name : null);
            total = addUtf8(total, extraction != null ? extraction.value : null);
            total = addUtf8(total, extraction != null ? extraction.source : null);
            total = addUtf8(total, extraction != null ? extraction.message : null);
            return total;
        });

        size = addCollectionObjects(size, scriptLogs, log -> {
            long total = 0L;
            total = addUtf8(total, log != null ? log.level : null);
            total = addUtf8(total, log != null ? log.message : null);
            total = addUtf8(total, log != null ? log.scriptId : null);
            total = addUtf8(total, log != null ? log.scriptName : null);
            return total;
        });

        size = addCollectionObjects(size, scriptVariableMutations, mutation -> {
            long total = 0L;
            total = addUtf8(total, mutation != null ? mutation.key : null);
            total = addUtf8(total, mutation != null ? mutation.oldValue : null);
            total = addUtf8(total, mutation != null ? mutation.newValue : null);
            total = addUtf8(total, mutation != null ? mutation.scope : null);
            total = addUtf8(total, mutation != null ? mutation.scopePath : null);
            total = addUtf8(total, mutation != null ? mutation.sourceScriptId : null);
            total = addUtf8(total, mutation != null ? mutation.sourceScriptName : null);
            total = addBoolean(total, mutation != null && mutation.persistent);
            return total;
        });

        size = addCollectionObjects(size, redirectHops, hop -> {
            long total = 0L;
            total = addUtf8(total, hop != null ? hop.sourceUrl : null);
            total = addUtf8(total, hop != null ? hop.sourceMethod : null);
            total = addUtf8(total, hop != null ? hop.location : null);
            total = addUtf8(total, hop != null ? hop.targetUrl : null);
            total = addUtf8(total, hop != null ? hop.targetMethod : null);
            total = addUtf8(total, hop != null ? hop.responseHeadersText : null);
            total = addUtf8(total, hop != null ? hop.rawRequestText : null);
            total = addUtf8(total, hop != null ? hop.fullRawRequestBodySha256 : null);
            total = addUtf8(total, hop != null ? hop.rawRequestTruncationReason : null);
            total = addUtf8(total, hop != null ? hop.fullResponseBodySha256 : null);
            total = addUtf8(total, hop != null ? hop.responseTruncationReason : null);
            total = addUtf8(total, hop != null ? hop.failureReason : null);
            total = addBytes(total, hop != null ? hop.rawRequestBytes : null);
            total = addBytes(total, hop != null ? hop.responseBody : null);
            total = addLong(total, hop != null ? hop.hopNumber : 0);
            total = addLong(total, hop != null ? hop.statusCode : 0);
            total = addLong(total, hop != null ? hop.elapsedMs : 0L);
            total = addBoolean(total, hop != null && hop.rawRequestBodyTruncated);
            total = addBoolean(total, hop != null && hop.responseBodyTruncated);
            total = addLong(total, hop != null ? hop.originalRawRequestBodyLength : 0L);
            total = addLong(total, hop != null ? hop.storedRawRequestBodyLength : 0L);
            total = addLong(total, hop != null ? hop.originalResponseBodyLength : 0L);
            total = addLong(total, hop != null ? hop.storedResponseBodyLength : 0L);
            total = addCollectionStrings(total, hop != null ? hop.forwardedSensitiveHeaderNames : null);
            total = addCollectionStrings(total, hop != null ? hop.strippedSensitiveHeaderNames : null);
            return total;
        });

        size = addRequestSnapshot(size, requestSnapshot);
        size = addResponseSnapshot(size, responseSnapshot);

        return Math.max(size, 0L);
    }

    public String toMetadataText() {
        String base = metadataSummaryText != null && !metadataSummaryText.isBlank()
                ? metadataSummaryText.trim()
                : buildLegacyMetadataText(this);
        String evidence = buildEvidenceMetadataText(this);
        if (evidence.isBlank()) {
            return base;
        }
        if (base == null || base.isBlank()) {
            return evidence.trim();
        }
        return (base + '\n' + evidence).trim();
    }

    public boolean hasTruncatedEvidence() {
        if (requestSnapshot != null
                && (requestSnapshot.bodyTruncated || requestSnapshot.rawBodyTruncated)) {
            return true;
        }
        if (responseSnapshot != null && responseSnapshot.bodyTruncated) {
            return true;
        }
        if (redirectHops == null || redirectHops.isEmpty()) {
            return false;
        }
        for (RedirectHop hop : redirectHops) {
            if (hop != null && (hop.rawRequestBodyTruncated || hop.responseBodyTruncated)) {
                return true;
            }
        }
        return false;
    }

    private static String buildLegacyMetadataText(HistoryEntry entry) {
        if (entry == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("History ID: ").append(entry.id != null ? entry.id : "").append('\n');
        sb.append("Created / Executed Timestamp: ").append(entry.timeDisplay()).append('\n');
        sb.append("Source: ").append(entry.source != null ? entry.source.displayName() : "").append('\n');
        sb.append("Collection ID: ").append(entry.collectionId != null ? entry.collectionId : "").append('\n');
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

    private static String buildEvidenceMetadataText(HistoryEntry entry) {
        if (entry == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendEvidenceLine(sb, "Pinned", entry.pinned ? "Yes" : "No");
        appendEvidenceLine(sb, "Analyst Notes", entry.analystNotes != null ? entry.analystNotes : "");
        appendEvidenceLine(sb, "Tags", String.join(", ", entry.tags != null ? entry.tags : List.<String>of()));
        if (entry.requestSnapshot != null) {
            appendEvidenceBlock(sb, entry.requestSnapshot.truncationSummary());
        }
        if (entry.responseSnapshot != null) {
            appendEvidenceBlock(sb, entry.responseSnapshot.truncationSummary());
        }
        appendRedirectTruncationEvidence(sb, entry.redirectHops);
        return sb.toString().trim();
    }

    private static void appendEvidenceBlock(StringBuilder sb, String block) {
        if (block == null || block.isBlank()) {
            return;
        }
        for (String line : block.split("\n")) {
            appendEvidenceLine(sb, line);
        }
    }

    private static void appendEvidenceLine(StringBuilder sb, String label, String value) {
        appendEvidenceLine(sb, label + ": " + (value != null ? value : ""));
    }

    private static void appendEvidenceLine(StringBuilder sb, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(line);
    }

    private static void appendRedirectTruncationEvidence(StringBuilder sb, List<RedirectHop> hops) {
        if (hops == null || hops.isEmpty()) {
            return;
        }
        int encounterIndex = 0;
        for (RedirectHop hop : hops) {
            encounterIndex++;
            if (hop == null) {
                continue;
            }
            int displayHopNumber = hop.hopNumber > 0 ? hop.hopNumber : encounterIndex;
            if (hop.rawRequestBodyTruncated) {
                appendEvidenceLine(sb,
                        "Redirect hop " + displayHopNumber
                                + " raw request truncated: stored " + hop.storedRawRequestBodyLength
                                + " of " + hop.originalRawRequestBodyLength
                                + " bytes; SHA-256=" + (hop.fullRawRequestBodySha256 != null ? hop.fullRawRequestBodySha256 : "")
                                + "; reason=" + (hop.rawRequestTruncationReason != null ? hop.rawRequestTruncationReason : ""));
            }
            if (hop.responseBodyTruncated) {
                appendEvidenceLine(sb,
                        "Redirect hop " + displayHopNumber
                                + " response body truncated: stored " + hop.storedResponseBodyLength
                                + " of " + hop.originalResponseBodyLength
                                + " bytes; SHA-256=" + (hop.fullResponseBodySha256 != null ? hop.fullResponseBodySha256 : "")
                                + "; reason=" + (hop.responseTruncationReason != null ? hop.responseTruncationReason : ""));
            }
        }
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
        snapshot.bodyTruncated = hop.responseBodyTruncated;
        snapshot.originalBodyLength = hop.originalResponseBodyLength > 0
                ? hop.originalResponseBodyLength
                : snapshot.body != null ? snapshot.body.length : 0L;
        snapshot.storedBodyLength = hop.storedResponseBodyLength > 0
                ? hop.storedResponseBodyLength
                : snapshot.body != null ? snapshot.body.length : 0L;
        snapshot.fullBodySha256 = hop.fullResponseBodySha256 != null ? hop.fullResponseBodySha256 : "";
        snapshot.truncationReason = hop.responseTruncationReason != null ? hop.responseTruncationReason : "";
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
        StringBuilder sb = new StringBuilder(buildLegacyMetadataText(entry));
        appendMetadataLine(sb, "Attempt", entry.attemptDisplay());
        appendMetadataLine(sb, "Retry Decision", entry.retryDecision != null && !entry.retryDecision.isBlank() ? entry.retryDecision : "NONE");
        appendMetadataLine(sb, "Retry Reason", entry.retryReason != null ? entry.retryReason : "");
        appendMetadataLine(sb, "Retry Delay", entry.retryDelayMillis > 0 ? entry.retryDelayMillis + " ms" : "0 ms");
        appendMetadataLine(sb, "Execution Kind", executionKind(entry));
        appendMetadataLine(sb, "Parent Request", parentRequestDisplay(entry));
        appendMetadataLine(sb, "Target Resolution", targetResolutionDisplay(entry));
        appendMetadataLine(sb, "Cancellation State", cancellationDisplay(entry));
        appendMetadataLine(sb, "Request May Have Been Processed", entry.requestMayHaveBeenProcessed ? "yes" : "no");
        return sb.toString().trim();
    }

    private static String executionKind(HistoryEntry entry) {
        if (entry == null) {
            return "";
        }
        if (entry.cancellationState != null && !entry.cancellationState.isBlank() && !RunnerCancellationState.NOT_CANCELLED.name().equals(entry.cancellationState)) {
            return "CANCELLED";
        }
        if (entry.preflightStatus != null
                && (entry.preflightStatus.equals(ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR.name())
                || entry.preflightStatus.equals(ExecutionPreflightStatus.BLOCKED_SCRIPT_TIMEOUT.name())
                || entry.preflightStatus.equals(ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE.name())
                || entry.preflightStatus.equals(ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES.name())
                || entry.preflightStatus.equals(ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE.name())
                || entry.preflightStatus.equals(ExecutionPreflightStatus.BLOCKED_POLICY.name()))) {
            return "PREFLIGHT_BLOCKED";
        }
        if (entry.responseTimedOut) {
            return "TIMED_OUT";
        }
        if (entry.adHocExecution) {
            return "AD_HOC";
        }
        if (entry.dependentExecution) {
            return "DEPENDENT";
        }
        if (entry.attemptNumber > 1) {
            return "RETRY";
        }
        return "QUEUED";
    }

    private static String parentRequestDisplay(HistoryEntry entry) {
        if (entry == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (entry.parentRequestName != null && !entry.parentRequestName.isBlank()) {
            sb.append(entry.parentRequestName);
        }
        if (entry.parentRequestId != null && !entry.parentRequestId.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" [");
            } else {
                sb.append('[');
            }
            sb.append(entry.parentRequestId).append(']');
        }
        return sb.toString();
    }

    private static String targetResolutionDisplay(HistoryEntry entry) {
        if (entry == null) {
            return "";
        }
        String form = entry.targetResolutionForm != null && !entry.targetResolutionForm.isBlank()
                ? entry.targetResolutionForm
                : FlowTargetResolutionForm.NONE.name();
        String path = entry.qualifiedTargetPath != null ? entry.qualifiedTargetPath : "";
        return path.isBlank() ? form : form + " " + path;
    }

    private static String cancellationDisplay(HistoryEntry entry) {
        if (entry == null || entry.cancellationState == null || entry.cancellationState.isBlank()) {
            return RunnerCancellationState.NOT_CANCELLED.name();
        }
        return entry.cancellationState;
    }

    private static void appendMetadataLine(StringBuilder sb, String label, String value) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(label).append(": ").append(value != null ? value : "");
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

    private static long addRequestSnapshot(long size, HistoryRequestSnapshot snapshot) {
        if (snapshot == null) {
            return size;
        }
        size = addUtf8(size, snapshot.bodyMode);
        size = addUtf8(size, snapshot.authType);
        size = addUtf8(size, snapshot.fullBodySha256);
        size = addUtf8(size, snapshot.truncationReason);
        size = addUtf8(size, snapshot.fullRawBodySha256);
        size = addUtf8(size, snapshot.rawTruncationReason);
        size = addUtf8(size, snapshot.parseWarning);
        size = addUtf8(size, snapshot.resolvedUrl);
        size = addBytes(size, snapshot.bodyAsAuthored);
        size = addBytes(size, snapshot.rawRequestSent);
        size = addUtf8(size, snapshot.rawRequestSentText);
        size = addLong(size, snapshot.originalBodyLength);
        size = addLong(size, snapshot.storedBodyLength);
        size = addLong(size, snapshot.originalRawBodyLength);
        size = addLong(size, snapshot.storedRawBodyLength);
        size = addLong(size, snapshot.bodyTruncated ? 1 : 0);
        size = addLong(size, snapshot.rawBodyTruncated ? 1 : 0);
        size = addCollectionMap(size, snapshot.requestVariablesAsAuthored);
        size = addCollectionMap(size, snapshot.resolvedVariables);
        size = addApiRequest(size, snapshot.authoredRequest);
        return size;
    }

    private static long addResponseSnapshot(long size, HistoryResponseSnapshot snapshot) {
        if (snapshot == null) {
            return size;
        }
        size = addUtf8(size, snapshot.reasonPhrase);
        size = addUtf8(size, snapshot.mimeType);
        size = addUtf8(size, snapshot.fullBodySha256);
        size = addUtf8(size, snapshot.truncationReason);
        size = addBytes(size, snapshot.body);
        size = addLong(size, snapshot.originalBodyLength);
        size = addLong(size, snapshot.storedBodyLength);
        size = addLong(size, snapshot.bodyTruncated ? 1 : 0);
        size = addHeaders(size, snapshot.headers);
        return size;
    }

    private static long addApiRequest(long size, ApiRequest request) {
        if (request == null) {
            return size;
        }
        size = addUtf8(size, request.id);
        size = addUtf8(size, request.name);
        size = addUtf8(size, request.path);
        size = addUtf8(size, request.sourceCollection);
        size = addUtf8(size, request.method);
        size = addUtf8(size, request.url);
        size = addUtf8(size, request.description);
        size = addUtf8(size, request.authOverrideMode);
        size = addUtf8(size, request.authSource);
        size = addBoolean(size, request.editorMaterialized);
        size = addUtf8(size, request.buildMode != null ? request.buildMode.name() : null);
        size = addCollectionStrings(size, request.suppressedAutoHeaders != null ? request.suppressedAutoHeaders : List.of());
        size = addCollectionObjects(size, request.headers, header -> {
            long total = 0L;
            total = addUtf8(total, header != null ? header.key : null);
            total = addUtf8(total, header != null ? header.value : null);
            total = addBoolean(total, header != null && header.disabled);
            return total;
        });
        size = addApiRequestBody(size, request.body);
        size = addCollectionObjects(size, request.variables, variable -> {
            long total = 0L;
            total = addUtf8(total, variable != null ? variable.key : null);
            total = addUtf8(total, variable != null ? variable.value : null);
            total = addUtf8(total, variable != null ? variable.type : null);
            total = addBoolean(total, variable != null && variable.enabled);
            return total;
        });
        size = addCollectionObjects(size, request.preRequestScripts, script -> {
            long total = 0L;
            total = addUtf8(total, script != null ? script.type : null);
            total = addUtf8(total, script != null ? script.exec : null);
            return total;
        });
        size = addCollectionObjects(size, request.postResponseScripts, script -> {
            long total = 0L;
            total = addUtf8(total, script != null ? script.type : null);
            total = addUtf8(total, script != null ? script.exec : null);
            return total;
        });
        size = addCollectionObjects(size, request.scriptBlocks, block -> {
            long total = 0L;
            total = addUtf8(total, block != null && block.id != null ? block.id : null);
            total = addUtf8(total, block != null && block.dialect != null ? block.dialect.name() : null);
            total = addUtf8(total, block != null && block.phase != null ? block.phase.name() : null);
            total = addUtf8(total, block != null && block.scope != null ? block.scope.name() : null);
            total = addUtf8(total, block != null ? block.source : null);
            total = addUtf8(total, block != null ? block.sourceFormat : null);
            total = addUtf8(total, block != null ? block.sourcePath : null);
            total = addLong(total, block != null ? block.order : 0);
            if (block != null && block.metadata != null) {
                total = addCollectionMap(total, block.metadata);
            }
            total = addBoolean(total, block != null && block.enabled);
            return total;
        });
        size = addApiAuth(size, request.auth);
        size = addApiAuth(size, request.explicitAuth);
        return size;
    }

    private static long addApiRequestBody(long size, ApiRequest.Body body) {
        if (body == null) {
            return size;
        }
        size = addUtf8(size, body.mode);
        size = addUtf8(size, body.raw);
        size = addUtf8(size, body.contentType);
        if (body.graphql != null) {
            size = addUtf8(size, body.graphql.query);
            size = addUtf8(size, body.graphql.variables);
        }
        size = addCollectionObjects(size, body.urlencoded, field -> {
            long total = 0L;
            total = addUtf8(total, field != null ? field.key : null);
            total = addUtf8(total, field != null ? field.value : null);
            total = addUtf8(total, field != null ? field.type : null);
            total = addUtf8(total, field != null ? field.filePath : null);
            total = addBoolean(total, field != null && field.fileUpload);
            total = addBoolean(total, field != null && field.disabled);
            return total;
        });
        size = addCollectionObjects(size, body.formdata, field -> {
            long total = 0L;
            total = addUtf8(total, field != null ? field.key : null);
            total = addUtf8(total, field != null ? field.value : null);
            total = addUtf8(total, field != null ? field.type : null);
            total = addUtf8(total, field != null ? field.filePath : null);
            total = addBoolean(total, field != null && field.fileUpload);
            total = addBoolean(total, field != null && field.disabled);
            return total;
        });
        return size;
    }

    private static long addApiAuth(long size, ApiRequest.Auth auth) {
        if (auth == null) {
            return size;
        }
        size = addUtf8(size, auth.type);
        size = addCollectionMap(size, auth.properties);
        return size;
    }

    private static long addHeaders(long size, List<HistoryHeader> headers) {
        if (headers == null) {
            return size;
        }
        for (HistoryHeader header : headers) {
            size = safeAdd(size, 16L);
            size = addUtf8(size, header != null ? header.name : null);
            size = addUtf8(size, header != null ? header.value : null);
            size = addBoolean(size, header != null && header.disabled);
        }
        return size;
    }

    private static long addCollectionMap(long size, Map<String, String> values) {
        if (values == null) {
            return size;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            size = safeAdd(size, 16L);
            size = addUtf8(size, entry.getKey());
            size = addUtf8(size, entry.getValue());
        }
        return size;
    }

    private static long addCollectionStrings(long size, Collection<String> values) {
        if (values == null) {
            return size;
        }
        for (String value : values) {
            size = safeAdd(size, 12L);
            size = addUtf8(size, value);
        }
        return size;
    }

    private static <T> long addCollectionObjects(long size, Collection<T> values, ToLongFunction<T> estimator) {
        if (values == null || estimator == null) {
            return size;
        }
        for (T value : values) {
            size = safeAdd(size, 12L);
            size = safeAdd(size, estimator.applyAsLong(value));
        }
        return size;
    }

    private static long addBytes(long size, byte[] bytes) {
        return safeAdd(size, bytes != null ? bytes.length : 0L);
    }

    private static long addUtf8(long size, String value) {
        return safeAdd(size, utf8Length(value));
    }

    private static long addLong(long size, long value) {
        return safeAdd(size, 8L);
    }

    private static long addBoolean(long size, boolean value) {
        return safeAdd(size, 1L);
    }

    private static long utf8Length(String value) {
        return value != null ? value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0L;
    }

    private static long safeAdd(long left, long right) {
        if (right <= 0) {
            return left;
        }
        if (left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        long total = left + right;
        return total < 0 ? Long.MAX_VALUE : total;
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
