package burp.runner;

import burp.models.*;
import burp.parser.VariableResolver;
import burp.utils.ExecutionResult;
import burp.utils.ExecutionPolicy;
import burp.utils.ExecutionPreflightStatus;
import burp.utils.HttpUtils;
import burp.utils.ScriptEngine;
import burp.utils.RequestBuilder;
import burp.utils.RequestPathResolver;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.RedirectionMode;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.HighlightColor;
import burp.auth.OAuth2Config;
import burp.auth.TokenStore;
import burp.models.RedirectPolicy;
import burp.utils.SharedRequestPipeline;
import burp.diagnostics.DiagnosticEvent;
import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;
import burp.scripts.ScriptExecutionContext;
import burp.scripts.ScriptAdHocRequest;
import burp.scripts.ScriptDependentRequestExecutor;
import burp.scripts.ScriptDependentRequestResult;
import burp.scripts.ScriptFlowControl;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Collection Runner - executes API requests sequentially with variable extraction.
 * Like Postman Collection Runner but inside Burp Suite.
 */
public class CollectionRunner {
    private final MontoyaApi api;
    private final burp.utils.SharedRequestPipeline pipeline;
    private final List<RunnerListener> listeners = new ArrayList<>();
    private volatile boolean running = false;
    private volatile boolean cancelled = false;
    private int delayMs = 200;
    private int maxRetries = 1;
    private boolean followRedirects = true;
    private RedirectPolicy redirectPolicy = RedirectPolicy.defaults();
    private boolean debugRawRequest = false;
    private volatile RunnerStopConditions stopConditions = new RunnerStopConditions();
    private final Object pauseLock = new Object();
    private volatile boolean pauseRequested = false;
    private volatile boolean singleStepRequested = false;
    private final List<RunnerResult> results = new CopyOnWriteArrayList<>();
    private final Map<ApiCollection, Map<String, String>> extractedVarsByCollection =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<String, String> extractedVars = new ConcurrentHashMap<>();
    private volatile ExecutorService activeExecutor;
    private volatile Future<?> activeFuture;
    private volatile RunnerTerminationResult lastTerminationResult;
    private volatile RunLifecycle currentRunLifecycle;
    private java.util.function.Supplier<ExecutorService> executorServiceFactory = Executors::newSingleThreadExecutor;
    private Function<ApiCollection, Map<String, String>> runtimeOverlayProvider = null;
    private Function<ApiCollection, EnvironmentProfile> activeEnvironmentProvider = null;
    private SharedRequestPipeline.OAuth2TokenSink oauth2TokenSink;
    private SharedRequestPipeline.RuntimeVariableSink runtimeVariableSink;
    private volatile ExecutionPolicy executionPolicy = ExecutionPolicy.runnerDefaults(false);

    public CollectionRunner(MontoyaApi api) {
        this(api, null, null);
    }

    public CollectionRunner(MontoyaApi api, burp.utils.SharedRequestPipeline pipeline) {
        this(api, pipeline, null);
    }

    public CollectionRunner(MontoyaApi api, burp.utils.SharedRequestPipeline pipeline, burp.auth.OAuth2Manager oauth2Manager) {
        this.api = api;
        this.pipeline = pipeline;
    }

    public void addListener(RunnerListener listener) {
        listeners.add(listener);
    }

    public void removeListener(RunnerListener listener) {
        listeners.remove(listener);
    }

    public void setDelayMs(int delayMs) { this.delayMs = delayMs; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = Math.max(0, maxRetries); }
    public void setStopOnError(boolean stopOnError) {
        ensureStopConditions().stopOnError = stopOnError;
    }
    public void setStopConditions(RunnerStopConditions stopConditions) {
        this.stopConditions = copyStopConditions(stopConditions);
    }
    public RunnerStopConditions getStopConditions() {
        return copyStopConditions(ensureStopConditions());
    }
    public void setFollowRedirects(boolean followRedirects) { this.followRedirects = followRedirects; }
    public void setRedirectPolicy(RedirectPolicy policy) {
        this.redirectPolicy = RedirectPolicy.copyOf(policy);
        if (this.redirectPolicy == null) {
            this.redirectPolicy = RedirectPolicy.defaults();
        }
        this.redirectPolicy.normalize();
    }
    public RedirectPolicy getRedirectPolicy() { return RedirectPolicy.copyOf(redirectPolicy); }
    void setExecutorServiceFactory(java.util.function.Supplier<ExecutorService> factory) {
        this.executorServiceFactory = factory != null ? factory : Executors::newSingleThreadExecutor;
    }
    public void setDebugRawRequest(boolean debugRawRequest) { this.debugRawRequest = debugRawRequest; }
    public void setRuntimeOverlayProvider(Function<ApiCollection, Map<String, String>> provider) {
        this.runtimeOverlayProvider = provider;
    }
    public void setActiveEnvironmentProvider(Function<ApiCollection, EnvironmentProfile> provider) {
        this.activeEnvironmentProvider = provider;
    }
    public void setOAuth2TokenSink(SharedRequestPipeline.OAuth2TokenSink oauth2TokenSink) {
        this.oauth2TokenSink = oauth2TokenSink;
    }
    public void setRuntimeVariableSink(SharedRequestPipeline.RuntimeVariableSink runtimeVariableSink) {
        this.runtimeVariableSink = runtimeVariableSink;
    }

    public void setExecutionPolicy(ExecutionPolicy policy) {
        ExecutionPolicy copy = policy != null ? policy.copy() : ExecutionPolicy.runnerDefaults(false);
        copy.normalize();
        this.executionPolicy = copy;
    }

    public ExecutionPolicy getExecutionPolicy() {
        ExecutionPolicy copy = executionPolicy != null ? executionPolicy.copy() : ExecutionPolicy.runnerDefaults(false);
        copy.normalize();
        return copy;
    }

    private ExecutionPolicy effectiveExecutionPolicy() {
        ExecutionPolicy copy = getExecutionPolicy();
        RunnerStopConditions conditions = ensureStopConditions();
        if (conditions != null && conditions.stopOnMissingVariable) {
            copy.unresolvedVariableMode = ExecutionPolicy.UnresolvedVariableMode.ABORT;
        }
        copy.normalize();
        return copy;
    }

    public RunnerTerminationResult getLastTerminationResult() {
        return lastTerminationResult;
    }

    public void pauseAfterCurrent() {
        synchronized (pauseLock) {
            pauseRequested = true;
            singleStepRequested = false;
        }
    }

    public void resume() {
        synchronized (pauseLock) {
            pauseRequested = false;
            singleStepRequested = false;
            pauseLock.notifyAll();
        }
    }

    public void runNextOnly() {
        synchronized (pauseLock) {
            singleStepRequested = true;
            pauseRequested = false;
            pauseLock.notifyAll();
        }
    }

    public boolean isPaused() {
        return pauseRequested;
    }

    /** Legacy single-collection runner (kept for compatibility). */
    public void runCollection(ApiCollection collection, List<ApiRequest> selectedRequests,
                              Map<String, String> initialVars) {
        if (collection != null && initialVars != null && !initialVars.isEmpty()) {
            collection.putAllRuntimeVars(initialVars);
        }
        runCollections(Collections.singletonList(collection), selectedRequests);
    }

    /**
     * Multi-collection runner with per-collection scoped variable resolution.
     */
    public void runCollections(List<ApiCollection> sourceCollections, List<ApiRequest> selectedRequests) {
        runCollections(sourceCollections, selectedRequests, false);
    }

    public void runCollections(List<ApiCollection> sourceCollections, List<ApiRequest> selectedRequests, boolean startPaused) {
        if (running) return;
        if (selectedRequests == null || selectedRequests.isEmpty()) {
            fireOnError("No requests selected for runner");
            return;
        }
        running = true;
        cancelled = false;
        final RunnerStopConditions activeStopConditions = copyStopConditions(ensureStopConditions());
        synchronized (pauseLock) {
            pauseRequested = startPaused;
            singleStepRequested = startPaused;
        }
        lastTerminationResult = null;
        results.clear();
        extractedVars.clear();
        extractedVarsByCollection.clear();
        RunLifecycle lifecycle = new RunLifecycle();
        currentRunLifecycle = lifecycle;

        // Build collection lookup maps: identity map preferred, name fallback
        Map<ApiRequest, ApiCollection> reqToColMap = new IdentityHashMap<>();
        Map<String, ApiCollection> colMap = new HashMap<>();
        if (sourceCollections != null) {
            for (ApiCollection c : sourceCollections) {
                if (c != null) {
                    if (c.name != null) colMap.put(c.name, c);
                    if (c.requests != null) {
                        for (ApiRequest r : c.requests) {
                            reqToColMap.put(r, c);
                        }
                    }
                }
            }
        }

        List<ApiRequest> ordered = orderRequestsForRun(selectedRequests);
        RunnerDependentRequestExecutor dependentRequestExecutor = new RunnerDependentRequestExecutor(sourceCollections, reqToColMap, colMap);

        ExecutorService chosenExecutor = executorServiceFactory != null ? executorServiceFactory.get() : null;
        if (chosenExecutor == null) {
            chosenExecutor = Executors.newSingleThreadExecutor();
        }
        final ExecutorService executor = chosenExecutor;
        activeExecutor = executor;
        AtomicInteger failedResultCount = new AtomicInteger();
        AtomicInteger completedQueuedCount = new AtomicInteger();
        TerminationState termination = new TerminationState();
        AtomicReference<FutureTask<Void>> taskRef = new AtomicReference<>();
        Callable<Void> worker = () -> {
            lifecycle.workerStarted.set(true);
            if (lifecycle.terminalDelivered.get()) {
                return null;
            }
            try {
                if (cancelled || Thread.currentThread().isInterrupted()) {
                    termination.stop(RunnerTerminationType.CANCELLED,
                            "User cancelled the runner.",
                            null,
                            null,
                            "cancelled",
                            null,
                            null);
                    return null;
                }
                fireOnStart("Runner", ordered.size());
                int[] flowControlJumps = new int[]{0};
                int maxFlowControlJumps = Math.max(ordered.size() * 4, 20);

                for (int i = 0; i < ordered.size() && !cancelled; i++) {
                    if (!waitIfPausedOrStepConsumed()) {
                        break;
                    }
                    if (i > 0 && delayMs > 0) {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            if (cancelled) {
                                break;
                            }
                            throw ie;
                        }
                    }

                    if (cancelled || Thread.currentThread().isInterrupted()) {
                        break;
                    }

                    ApiRequest req = ordered.get(i);
                    if (req.disabled) {
                        fireOnSkip(req.name, "Request disabled");
                        continue;
                    }

                    ApiCollection col = reqToColMap.get(req);
                    if (col == null) {
                        col = colMap.getOrDefault(req.sourceCollection, null);
                    }

                    RequestExecutionOutcome outcome = executeRequest(req, col, dependentRequestExecutor, false, false, null, null, 0);
                    if (outcome == null || outcome.state == RequestOutcomeState.CANCELLED || outcome.state == RequestOutcomeState.FAILED_BEFORE_RESULT || outcome.result == null) {
                        break;
                    }

                    RunnerResult result = outcome.result;
                    results.add(result);
                    fireOnRequestComplete(result);
                    fireOnTimeline(buildTimelineRow(req, col, result, outcome.attempts));
                    if (!result.dependentExecution && !result.adHocExecution) {
                        completedQueuedCount.incrementAndGet();
                    }

                    boolean assertionFailed = hasAssertionFailure(result);
                    boolean statusFailed = hasStatusAtLeast400(result);
                    boolean executionFailed = !result.success;
                    boolean anyFailure = executionFailed || assertionFailed || statusFailed;
                    if (anyFailure) {
                        failedResultCount.incrementAndGet();
                    }

                    if (cancelled || Thread.currentThread().isInterrupted()) {
                        break;
                    }

                    if (activeStopConditions.stopOnError && executionFailed) {
                        termination.stop(RunnerTerminationType.STOPPED_ON_ERROR,
                                "Stopped on error: " + safeRunnerReason(result.errorMessage),
                                result,
                                null,
                                "stopOnError",
                                null,
                                null);
                        break;
                    }
                    if (activeStopConditions.stopOnAssertionFailure && assertionFailed) {
                        termination.stop(RunnerTerminationType.STOPPED_ON_ASSERTION_FAILURE,
                                "Stopped on assertion failure for " + safeRunnerLabel(result.requestName),
                                result,
                                null,
                                "stopOnAssertionFailure",
                                null,
                                null);
                        break;
                    }
                    if (activeStopConditions.stopOnStatusAtLeast400 && statusFailed) {
                        termination.stop(RunnerTerminationType.STOPPED_ON_STATUS,
                                "Stopped on status >= 400 for " + safeRunnerLabel(result.requestName) + " (" + result.statusCode + ")",
                                result,
                                result.statusCode,
                                "stopOnStatusAtLeast400",
                                null,
                                null);
                        break;
                    }
                    if (activeStopConditions.stopAfterFailureCount > 0 &&
                        failedResultCount.get() >= activeStopConditions.stopAfterFailureCount) {
                        termination.stop(RunnerTerminationType.STOPPED_ON_FAILURE_COUNT,
                                "Stopped after failure count reached: " +
                                        failedResultCount.get() + "/" + activeStopConditions.stopAfterFailureCount,
                                result,
                                result.statusCode,
                                "stopAfterFailureCount",
                                null,
                                null);
                        break;
                    }

                    int nextIndex = applyFlowControl(ordered, i, result, flowControlJumps, maxFlowControlJumps, termination);
                    if (nextIndex == Integer.MIN_VALUE) {
                        break;
                    }
                    if (nextIndex >= 0) {
                        i = nextIndex - 1;
                    }
                }

                if (!termination.isSet()) {
                    if (cancelled || Thread.currentThread().isInterrupted()) {
                        termination.stop(RunnerTerminationType.CANCELLED,
                                "User cancelled the runner.",
                                null,
                                null,
                                "cancelled",
                                null,
                                null);
                    } else {
                        termination.complete("Runner completed successfully.");
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (cancelled) {
                    if (!termination.isSet()) {
                        termination.stop(RunnerTerminationType.CANCELLED,
                                "User cancelled the runner.",
                                null,
                                null,
                                "cancelled",
                                null,
                                null);
                    }
                    return null;
                }
                termination.internalError(cleanRunnerErrorReason(ie), ie);
            } catch (Exception e) {
                if (cancelled || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    if (!termination.isSet()) {
                        termination.stop(RunnerTerminationType.CANCELLED,
                                "User cancelled the runner.",
                                null,
                                null,
                                "cancelled",
                                null,
                                null);
                    }
                } else {
                    termination.internalError(cleanRunnerErrorReason(e), e);
                }
            } finally {
                if (lifecycle.workerStarted.get()) {
                    FutureTask<Void> currentTask = taskRef.get();
                    if (currentTask != null) {
                        finalizeRunnerRun(lifecycle, termination, completedQueuedCount.get(), ordered.size(), failedResultCount.get(), executor, currentTask);
                    }
                }
            }
            return null;
        };
        FutureTask<Void> task = new FutureTask<>(worker) {
            @Override
            protected void done() {
                if (!lifecycle.workerStarted.get()) {
                    finalizeRunnerRun(lifecycle, termination, completedQueuedCount.get(), ordered.size(), failedResultCount.get(), executor, this);
                }
            }
        };
        taskRef.set(task);
        activeFuture = task;
        executor.execute(task);
    }

    public List<RunnerPreviewRow> buildRunPreview(List<ApiCollection> sourceCollections, List<ApiRequest> selectedRequests) {
        List<RunnerPreviewRow> previewRows = new ArrayList<>();
        if (selectedRequests == null || selectedRequests.isEmpty()) {
            return previewRows;
        }

        Map<ApiRequest, ApiCollection> reqToColMap = new IdentityHashMap<>();
        Map<String, ApiCollection> colMap = new HashMap<>();
        if (sourceCollections != null) {
            for (ApiCollection c : sourceCollections) {
                if (c != null) {
                    if (c.name != null) colMap.put(c.name, c);
                    if (c.requests != null) {
                        for (ApiRequest r : c.requests) {
                            reqToColMap.put(r, c);
                        }
                    }
                }
            }
        }

        List<ApiRequest> ordered = orderRequestsForRun(selectedRequests);

        for (ApiRequest req : ordered) {
            if (req == null || req.disabled) {
                continue;
            }

            ApiCollection col = reqToColMap.get(req);
            if (col == null) {
                col = colMap.getOrDefault(req.sourceCollection, null);
            }

            VariableResolver resolver = buildPreviewResolver(req, col);
            RunnerPreviewRow row = new RunnerPreviewRow();
            row.order = req.sequenceOrder;
            row.collectionName = col != null && col.name != null ? col.name : req.sourceCollection;
            row.requestName = req.name;
            row.method = req.method != null ? req.method.toUpperCase() : "GET";
            row.urlPreview = resolver.resolve(req.url);
            row.authStatus = describeAuth(req);
            row.unresolvedVariables.addAll(collectUnresolvedVariables(resolver, req));
            previewRows.add(row);
        }

        return previewRows;
    }

    private List<ApiRequest> orderRequestsForRun(List<ApiRequest> selectedRequests) {
        List<ApiRequest> ordered = new ArrayList<>(selectedRequests);
        boolean allHavePositiveSequence = !ordered.isEmpty()
                && ordered.stream().allMatch(r -> r != null && r.sequenceOrder > 0);
        if (allHavePositiveSequence) {
            ordered.sort(Comparator.comparingInt(r -> r.sequenceOrder));
        }
        return ordered;
    }

    private String describeAuth(ApiRequest req) {
        if (req == null || req.auth == null || req.auth.type == null || "none".equalsIgnoreCase(req.auth.type)) {
            return req != null && req.authExplicitlyDisabled ? "No auth (request)" : "No auth";
        }
        String source = req.authSource != null && !req.authSource.isBlank()
                ? " from " + req.authSource
                : (req.authInherited ? " inherited" : "");
        return req.auth.type + source;
    }

    private RequestExecutionOutcome executeRequest(ApiRequest req,
                                                   ApiCollection col,
                                                   ScriptDependentRequestExecutor dependentRequestExecutor,
                                                   boolean dependentExecution,
                                                   boolean adHocExecution,
                                                   String parentRequestName,
                                                   String parentRequestId,
                                                   int dependentDepth) throws InterruptedException {
        return executeRequest(req, col, dependentRequestExecutor, dependentExecution, adHocExecution, parentRequestName, parentRequestId, dependentDepth, null);
    }

    private RequestExecutionOutcome executeRequest(ApiRequest req,
                                                   ApiCollection col,
                                                   ScriptDependentRequestExecutor dependentRequestExecutor,
                                                   boolean dependentExecution,
                                                   boolean adHocExecution,
                                                   String parentRequestName,
                                                   String parentRequestId,
                                                   int dependentDepth,
                                                   Map<String, String> runtimeOverlayOverride) throws InterruptedException {
        RunnerResult result = new RunnerResult();
        result.requestName = req.name;
        result.requestId = req.id;
        result.collectionName = col != null && col.name != null ? col.name : req.sourceCollection;
        result.folderPath = col != null ? RequestPathResolver.getRequestFolderPath(col, req) : req.path;
        result.method = req.method != null ? req.method.toUpperCase() : "GET";
        result.dependentExecution = dependentExecution;
        result.adHocExecution = adHocExecution;
        result.parentRequestName = parentRequestName;
        result.parentRequestId = parentRequestId;
        result.dependentDepth = dependentDepth;
        result.triggeredByScript = dependentExecution || adHocExecution;

        fireOnRequestStart(snapshotRequestStart(result, req, col));

        if (pipeline == null) {
            result.success = false;
            result.errorMessage = "Runner pipeline unavailable";
            return new RequestExecutionOutcome(result, 0, RequestOutcomeState.COMPLETED);
        }

        Map<String, String> initialOverlay = mergeRuntimeOverlays(runtimeOverlayFor(col), runtimeOverlayOverride);
        boolean activeEnvironmentMode = initialOverlay != null;
        Map<String, String> scopedExtractedVars = extractedVars;
        if (col != null && !activeEnvironmentMode) {
            scopedExtractedVars = extractedVarsByCollection.computeIfAbsent(col, c -> new ConcurrentHashMap<>());
            // Merge previously extracted vars only for the current collection so pipeline sees them.
            if (!scopedExtractedVars.isEmpty()) {
                col.putAllRuntimeVars(scopedExtractedVars);
            }
        }

        int attempts = 0;
        int maxAttempts = Math.max(1, maxRetries + 1);
        while (attempts < maxAttempts && !cancelled) {
            attempts++;
            try {
                Map<String, String> overlay = mergeRuntimeOverlays(runtimeOverlayFor(col), runtimeOverlayOverride);
                EnvironmentProfile activeEnvironment = activeEnvironmentFor(col);
                ExecutionPolicy currentPolicy = effectiveExecutionPolicy();
                boolean subclassPipeline = pipeline.getClass() != SharedRequestPipeline.class;
                boolean previewHasUnresolvedVariables = false;
                if (subclassPipeline && currentPolicy.unresolvedVariableMode == ExecutionPolicy.UnresolvedVariableMode.ABORT) {
                    previewHasUnresolvedVariables = !collectUnresolvedVariables(buildPreviewResolver(req, col), req).isEmpty();
                }
                ExecutionResult exec;
                if (subclassPipeline && !(currentPolicy.unresolvedVariableMode == ExecutionPolicy.UnresolvedVariableMode.ABORT && previewHasUnresolvedVariables)) {
                    // Preserve compatibility with legacy test subclasses that override the older
                    // execute overloads but not the explicit ExecutionSource variant.
                    if (activeEnvironment == null && overlay == null && oauth2TokenSink == null && runtimeVariableSink == null) {
                        exec = pipeline.execute(req, col, followRedirects);
                    } else if (activeEnvironment == null && runtimeVariableSink == null) {
                        exec = pipeline.execute(req, col, followRedirects, overlay, oauth2TokenSink);
                    } else if (activeEnvironment == null) {
                        exec = pipeline.execute(req, col, followRedirects, overlay, oauth2TokenSink, runtimeVariableSink);
                    } else if (overlay == null && oauth2TokenSink == null) {
                        exec = pipeline.execute(req, col, followRedirects, null, null, runtimeVariableSink, activeEnvironment);
                    } else {
                        exec = pipeline.execute(req, col, followRedirects, overlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment);
                    }
                    if (exec != null) {
                        exec.executionSource = burp.scripts.ExecutionSource.RUNNER;
                        if (runtimeVariableSink != null && exec.extractedVars != null && !exec.extractedVars.isEmpty()) {
                            Set<String> removedKeys = exec.removedVars != null
                                    ? new LinkedHashSet<>(exec.removedVars)
                                    : Collections.emptySet();
                            runtimeVariableSink.apply(col, exec.extractedVars, removedKeys);
                        }
                    }
                } else {
                    exec = pipeline.execute(req, col, followRedirects, overlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, burp.scripts.ExecutionSource.RUNNER, dependentRequestExecutor, redirectPolicy, currentPolicy, null);
                }
                if (exec == null) {
                    result.success = false;
                    result.errorMessage = "Runner pipeline unavailable";
                    result.attemptNumber = attempts;
                    result.totalAttempts = maxAttempts;
                    fireOnAttemptComplete(snapshotAttemptResult(result, null, attempts, maxAttempts));
                    break;
                }
                if (exec.isBlockedBeforeSend()) {
                    copyScriptOutput(result, exec);
                    copyExecutionResultMetadata(result, exec);
                    result.success = false;
                    result.requestSent = false;
                    result.statusCode = 0;
                    result.responseSize = 0;
                    result.responseTimeMs = exec.elapsedMs;
                    result.errorMessage = exec.preflightMessage;
                    result.attemptNumber = attempts;
                    result.totalAttempts = maxAttempts;
                    fireOnAttemptComplete(snapshotAttemptResult(result, exec, attempts, maxAttempts));
                    return new RequestExecutionOutcome(result, attempts, RequestOutcomeState.COMPLETED);
                }
                if (debugRawRequest && exec != null) {
                    String rawRequestText = exec.rawRequestText != null
                            ? exec.rawRequestText
                            : (exec.rawRequestBytes != null
                            ? new String(exec.rawRequestBytes, java.nio.charset.StandardCharsets.UTF_8)
                            : exec.requestHeaders);
                    if (rawRequestText != null) {
                        fireOnDebug("=== Runner Raw Request [" + req.name + "] ===\n" + rawRequestText + "\n=== End Runner Raw Request ===");
                    }
                }

                copyScriptOutput(result, exec);
                copyExecutionResultMetadata(result, exec);
                if ((cancelled || Thread.currentThread().isInterrupted()) && exec.response == null) {
                    return new RequestExecutionOutcome(null, attempts, RequestOutcomeState.CANCELLED);
                }
                if (exec.assertions != null && !exec.assertions.isEmpty()) {
                    result.assertions.addAll(exec.assertions);
                }

                if ((exec.scriptFlowControl == burp.scripts.ScriptFlowControl.STOP_RUN
                        || exec.scriptFlowControl == burp.scripts.ScriptFlowControl.SKIP_REQUEST)
                        && exec.response == null) {
                    boolean hasScriptErrors = result.scriptErrors != null && !result.scriptErrors.isEmpty();
                    result.success = !hasScriptErrors;
                    result.errorMessage = hasScriptErrors ? String.join("; ", result.scriptErrors) : null;
                    result.statusCode = 0;
                    result.requestUrl = exec.resolvedUrl != null ? exec.resolvedUrl : req.url;
                    result.initialResolvedUrl = exec.initialResolvedUrl != null ? exec.initialResolvedUrl : result.requestUrl;
                    result.finalResolvedUrl = exec.finalResolvedUrl != null ? exec.finalResolvedUrl : result.requestUrl;
                    result.redirectsEnabled = exec.redirectsEnabled;
                    result.redirectTerminationReason = exec.redirectTerminationReason;
                    result.redirectHops = copyRedirectHops(exec.redirectHops);
                    result.requestHeaders = exec.requestHeaders;
                    result.requestBody = exec.requestBody;
                    result.resolvedVariables = exec.resolvedVariables != null ? new HashMap<>(exec.resolvedVariables) : new HashMap<>();
                    result.attemptNumber = attempts;
                    result.totalAttempts = maxAttempts;
                    recordRunnerDiagnostic(result,
                            exec.scriptFlowControl == burp.scripts.ScriptFlowControl.SKIP_REQUEST ? DiagnosticSeverity.INFO : DiagnosticSeverity.WARNING,
                            exec.scriptFlowControl == burp.scripts.ScriptFlowControl.SKIP_REQUEST ? "Skipped by script" : "Stopped by script",
                            "Flow Control: " + exec.scriptFlowControl);
                    fireOnAttemptComplete(snapshotAttemptResult(result, exec, attempts, maxAttempts));
                    return new RequestExecutionOutcome(result, attempts, RequestOutcomeState.COMPLETED);
                }

                if (exec.response != null && exec.response.response() != null && exec.success) {
                    var response = exec.response.response();
                    result.success = exec.success;
                    result.errorMessage = exec.errorMessage;
                    result.statusCode = response.statusCode();
                    result.responseSize = response.body().length();
                    result.rawRequestBytes = exec.rawRequestBytes != null ? exec.rawRequestBytes.clone() : null;
                    result.rawRequestText = exec.rawRequestText != null
                            ? exec.rawRequestText
                            : (exec.rawRequestBytes != null
                            ? new String(exec.rawRequestBytes, java.nio.charset.StandardCharsets.UTF_8)
                            : null);

                    String body = response.bodyToString();
                    result.responseBody = body;
                    result.responseBodyLength = body.length();
                    result.responseBodyPreview = body.length() > 500 ? body.substring(0, 500) + "..." : body;
                    result.responseTimeMs = exec.elapsedMs;

                    // Store response headers
                    StringBuilder respHeaders = new StringBuilder();
                    respHeaders.append("HTTP/1.1 ").append(response.statusCode()).append(" OK\n");
                    for (var header : response.headers()) {
                        respHeaders.append(header.name()).append(": ").append(header.value()).append("\n");
                    }
                    result.responseHeaders = respHeaders.toString();

                    // Store request details for result pane
                    result.requestUrl = exec.resolvedUrl != null ? exec.resolvedUrl : req.url;
                    result.initialResolvedUrl = exec.initialResolvedUrl != null ? exec.initialResolvedUrl : result.requestUrl;
                    result.finalResolvedUrl = exec.finalResolvedUrl != null ? exec.finalResolvedUrl : result.requestUrl;
                    result.redirectsEnabled = exec.redirectsEnabled;
                    result.redirectTerminationReason = exec.redirectTerminationReason;
                    result.redirectHops = copyRedirectHops(exec.redirectHops);
                    result.requestHeaders = exec.requestHeaders;
                    result.requestBody = exec.requestBody;
                    result.resolvedVariables = exec.resolvedVariables != null ? new HashMap<>(exec.resolvedVariables) : new HashMap<>();
                    HttpUtils.ParsedTarget parsed = HttpUtils.parseTargetForRequest(
                        exec.resolvedUrl != null ? exec.resolvedUrl : req.url);
                    result.host = parsed.host;
                    result.path = parsed.pathWithQuery;

                    // Annotate sitemap entry for visibility
                    if (api != null) {
                        try {
                            Annotations annotations = Annotations.annotations(
                                    "[Runner] " + req.name, HighlightColor.CYAN);
                            api.siteMap().add(exec.response.withAnnotations(annotations));
                        } catch (Throwable ignored) {
                            // Unit tests and non-Burp environments do not always provide the Montoya
                            // object factory needed by annotation helpers. The request/response still
                            // completes; we simply skip sitemap annotation in that case.
                        }
                    }

                    // Copy extracted vars and assertions for cross-request continuity
                    if (activeEnvironmentMode) {
                        copyExecutionVariablesToResultOnly(result, exec);
                    } else {
                        mergeExecutionVariables(scopedExtractedVars, extractedVars, result, exec);
                    }

                    result.attemptNumber = attempts;
                    result.totalAttempts = maxAttempts;
                    fireOnAttemptComplete(snapshotAttemptResult(result, exec, attempts, maxAttempts));

                    break; // Success, exit retry loop
                } else {
                    result.success = false;
                    result.errorMessage = exec.errorMessage != null ? exec.errorMessage : "No response received";
                    result.initialResolvedUrl = exec.initialResolvedUrl != null ? exec.initialResolvedUrl : exec.resolvedUrl;
                    result.finalResolvedUrl = exec.finalResolvedUrl != null ? exec.finalResolvedUrl : exec.resolvedUrl;
                    result.redirectsEnabled = exec.redirectsEnabled;
                    result.redirectTerminationReason = exec.redirectTerminationReason;
                    result.redirectHops = copyRedirectHops(exec.redirectHops);
                    if (!cancelled && !Thread.currentThread().isInterrupted()) {
                        fireOnDebug("Attempt " + attempts + "/" + maxAttempts + " failed: " + result.errorMessage);
                    }
                    result.attemptNumber = attempts;
                    result.totalAttempts = maxAttempts;
                    fireOnAttemptComplete(snapshotAttemptResult(result, exec, attempts, maxAttempts));
                    if (attempts >= maxAttempts || cancelled) {
                        break;
                    }
                    int retryDelay = retryDelayMs(attempts);
                    fireOnDebug("Retrying in " + retryDelay + "ms");
                    try {
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        if (cancelled) {
                            return new RequestExecutionOutcome(null, attempts, RequestOutcomeState.CANCELLED);
                        }
                        throw ie;
                    }
                }
            } catch (Exception e) {
                if (cancelled || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    return new RequestExecutionOutcome(null, attempts, RequestOutcomeState.CANCELLED);
                }
                result.success = false;
                result.errorMessage = extractCleanError(e);
                if (!cancelled && !Thread.currentThread().isInterrupted()) {
                    fireOnDebug("Attempt " + attempts + "/" + maxAttempts + " failed: " + result.errorMessage);
                }
                result.attemptNumber = attempts;
                result.totalAttempts = maxAttempts;
                fireOnAttemptComplete(snapshotAttemptResult(result, null, attempts, maxAttempts));
                if (attempts >= maxAttempts || cancelled) {
                    break;
                }
                int retryDelay = retryDelayMs(attempts);
                fireOnDebug("Retrying in " + retryDelay + "ms");
                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    if (cancelled) {
                        return new RequestExecutionOutcome(null, attempts, RequestOutcomeState.CANCELLED);
                    }
                    throw ie;
                }
            }
        }

        if (result.success && attempts > 1) {
            fireOnDebug("Attempt " + attempts + "/" + maxAttempts + " passed");
        }

        result.attemptNumber = attempts;
        result.totalAttempts = maxAttempts;

        return new RequestExecutionOutcome(result, attempts, RequestOutcomeState.COMPLETED);
    }

    private RunnerResult snapshotRequestStart(RunnerResult source, ApiRequest req, ApiCollection col) {
        RunnerResult snapshot = new RunnerResult();
        if (source != null) {
            snapshot.requestName = source.requestName;
            snapshot.requestId = source.requestId;
            snapshot.collectionName = source.collectionName;
            snapshot.folderPath = source.folderPath;
            snapshot.method = source.method;
            snapshot.host = source.host;
            snapshot.path = source.path;
            snapshot.requestUrl = source.requestUrl;
            snapshot.initialResolvedUrl = source.initialResolvedUrl;
            snapshot.finalResolvedUrl = source.finalResolvedUrl;
            snapshot.redirectsEnabled = source.redirectsEnabled;
            snapshot.redirectTerminationReason = source.redirectTerminationReason;
            snapshot.redirectHops = copyRedirectHops(source.redirectHops);
            snapshot.dependentExecution = source.dependentExecution;
            snapshot.adHocExecution = source.adHocExecution;
            snapshot.parentRequestName = source.parentRequestName;
            snapshot.parentRequestId = source.parentRequestId;
            snapshot.dependentDepth = source.dependentDepth;
            snapshot.triggeredByScript = source.triggeredByScript;
            snapshot.attemptNumber = Math.max(1, source.attemptNumber);
            snapshot.totalAttempts = Math.max(1, source.totalAttempts);
        } else if (req != null) {
            snapshot.requestName = req.name;
            snapshot.requestId = req.id;
            snapshot.method = req.method != null ? req.method.toUpperCase() : "GET";
            snapshot.requestUrl = req.url;
            snapshot.initialResolvedUrl = req.url;
            snapshot.finalResolvedUrl = req.url;
            snapshot.path = req.path;
        }
        if (col != null && snapshot.collectionName == null) {
            snapshot.collectionName = col.name;
        }
        return snapshot;
    }

    private String extractCleanError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        if (msg.contains("UnknownHostException")) {
            return "DNS resolution failed - check network/VPN";
        }
        if (msg.contains("ConnectException")) {
            return "Connection refused - service may be down or firewalled";
        }
        if (msg.contains("SocketTimeoutException")) {
            return "Connection timeout - target unresponsive";
        }
        return msg;
    }

    static void mergeExecutionVariables(Map<String, String> scopedExtractedVars,
                                        Map<String, String> aggregateExtractedVars,
                                        RunnerResult result,
                                        ExecutionResult exec) {
        if (exec == null || result == null) {
            return;
        }
        if (exec.removedVars != null && !exec.removedVars.isEmpty()) {
            for (String key : exec.removedVars) {
                if (scopedExtractedVars != null) {
                    scopedExtractedVars.remove(key);
                }
                if (aggregateExtractedVars != null) {
                    aggregateExtractedVars.remove(key);
                }
                result.extractedVariables.remove(key);
            }
        }
        if (!exec.extractedVars.isEmpty()) {
            if (scopedExtractedVars != null) {
                scopedExtractedVars.putAll(exec.extractedVars);
            }
            if (aggregateExtractedVars != null) {
                aggregateExtractedVars.putAll(exec.extractedVars);
            }
            result.extractedVariables.putAll(exec.extractedVars);
        }
    }

    static void copyExecutionVariablesToResultOnly(RunnerResult result, ExecutionResult exec) {
        if (exec == null || result == null) {
            return;
        }
        if (exec.removedVars != null && !exec.removedVars.isEmpty()) {
            for (String key : exec.removedVars) {
                result.extractedVariables.remove(key);
            }
        }
        if (exec.extractedVars != null && !exec.extractedVars.isEmpty()) {
            result.extractedVariables.putAll(exec.extractedVars);
        }
    }

    static List<RedirectHop> copyRedirectHops(List<RedirectHop> hops) {
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

    static void copyScriptOutput(RunnerResult result, ExecutionResult exec) {
        if (result == null || exec == null) {
            return;
        }
        result.scriptEngineName = exec.scriptEngineName;
        result.executionSource = exec.executionSource;
        result.scriptFlowControl = exec.scriptFlowControl != null ? exec.scriptFlowControl : burp.scripts.ScriptFlowControl.CONTINUE;
        result.scriptFlowMessage = exec.scriptFlowMessage;
        result.scriptFlowNextRequestName = exec.scriptFlowNextRequestName;
        result.scriptFlowNextRequestId = exec.scriptFlowNextRequestId;
        if (exec.scriptLogs != null && !exec.scriptLogs.isEmpty()) {
            result.scriptLogs.addAll(exec.scriptLogs);
        }
        if (exec.scriptWarnings != null && !exec.scriptWarnings.isEmpty()) {
            result.scriptWarnings.addAll(exec.scriptWarnings);
        }
        if (exec.scriptErrors != null && !exec.scriptErrors.isEmpty()) {
            result.scriptErrors.addAll(exec.scriptErrors);
            result.success = false;
        }
        if (exec.scriptVariableMutations != null && !exec.scriptVariableMutations.isEmpty()) {
            result.scriptVariableMutations.addAll(exec.scriptVariableMutations);
        }
        if (exec.scriptDependentRequestResults != null && !exec.scriptDependentRequestResults.isEmpty()) {
            result.scriptDependentRequestResults.addAll(exec.scriptDependentRequestResults);
        }
        if (exec.dependentRequestCount > 0) {
            result.dependentRequestCount += exec.dependentRequestCount;
        } else if (exec.scriptDependentRequestResults != null && !exec.scriptDependentRequestResults.isEmpty()) {
            result.dependentRequestCount += exec.scriptDependentRequestResults.size();
        }
        result.dependentExecution = exec.dependentExecution;
        result.adHocExecution = exec.adHocExecution;
        result.parentRequestName = exec.parentRequestName;
        result.parentRequestId = exec.parentRequestId;
        result.dependentDepth = exec.dependentDepth;
        result.triggeredByScript = exec.triggeredByScript;
    }

    static void copyExecutionResultMetadata(RunnerResult result, ExecutionResult exec) {
        if (result == null || exec == null) {
            return;
        }
        result.requestSent = exec.requestSent;
        result.preflightStatus = exec.preflightStatus != null ? exec.preflightStatus : ExecutionPreflightStatus.READY;
        result.preflightMessage = exec.preflightMessage;
        result.responseTimedOut = exec.responseTimedOut;
        result.timeoutMillis = exec.timeoutMillis;
        result.originalResolvedUrl = exec.originalResolvedUrl;
        result.effectiveResolvedUrl = exec.effectiveResolvedUrl;
        result.targetChanged = exec.targetChangeAllowed || (exec.preflight != null && exec.preflight.targetChanged);
        result.oauth2Required = exec.oauth2Required;
        result.oauth2Ready = exec.oauth2Ready;
        result.oauth2UsedStaleToken = exec.oauth2UsedStaleToken;
        result.oauth2SentWithoutToken = exec.oauth2SentWithoutToken;
        if (exec.preflight != null) {
            result.unresolvedVariables = new ArrayList<>(exec.preflight.unresolvedVariables);
            result.policyOverridesApplied = new ArrayList<>(exec.preflight.policyOverridesApplied);
            result.targetChanged = exec.preflight.targetChanged;
        } else {
            result.unresolvedVariables = new ArrayList<>();
            result.policyOverridesApplied = new ArrayList<>(exec.policyOverridesApplied);
        }
    }

    private int applyFlowControl(List<ApiRequest> ordered,
                                 int currentIndex,
                                 RunnerResult result,
                                 int[] flowControlJumps,
                                 int maxFlowControlJumps,
                                 TerminationState termination) {
        if (result == null || result.scriptFlowControl == null) {
            return -1;
        }
        return switch (result.scriptFlowControl) {
            case STOP_RUN -> {
                if (termination != null) {
                    termination.stop(RunnerTerminationType.STOPPED_BY_SCRIPT,
                            "Runner stopped by script: " + safeRunnerReason(result.scriptFlowMessage),
                            result,
                            null,
                            "STOP_RUN",
                            ScriptFlowControl.STOP_RUN,
                            null);
                }
                yield Integer.MIN_VALUE;
            }
            case SET_NEXT_REQUEST -> {
                if (flowControlJumps != null && flowControlJumps.length > 0 && flowControlJumps[0] >= maxFlowControlJumps) {
                    if (termination != null) {
                        termination.stop(RunnerTerminationType.STOPPED_BY_SCRIPT,
                                "Stopped after too many flow-control jumps",
                                result,
                                null,
                                "SET_NEXT_REQUEST flow-control jump limit",
                                ScriptFlowControl.SET_NEXT_REQUEST,
                                null);
                    }
                    yield Integer.MIN_VALUE;
                }
                if (flowControlJumps != null && flowControlJumps.length > 0) {
                    flowControlJumps[0]++;
                }
                int targetIndex = resolveNextRequestIndex(ordered, result.scriptFlowNextRequestName, result.scriptFlowNextRequestId);
                if (targetIndex < 0) {
                    fireOnDebug("Script requested next request \"" + safeFlowTarget(result) + "\" but no matching request was found.");
                    recordRunnerDiagnostic(result, DiagnosticSeverity.WARNING, "Next request target not found", "Flow Control: SET_NEXT_REQUEST");
                    yield -1;
                }
                recordRunnerDiagnostic(result, DiagnosticSeverity.INFO, "Next request selected by script", "Flow Control: SET_NEXT_REQUEST -> " + safeFlowTarget(result));
                yield targetIndex;
            }
            case RUN_REQUEST, SEND_AD_HOC_REQUEST -> {
                if (result.dependentRequestCount > 0 || (result.scriptDependentRequestResults != null && !result.scriptDependentRequestResults.isEmpty())) {
                    recordRunnerDiagnostic(result, DiagnosticSeverity.INFO, "Dependent request executed by script", "Flow Control: " + result.scriptFlowControl);
                    yield -1;
                }
                String warning = "Script flow control " + result.scriptFlowControl + " is recognized but not executed yet.";
                recordRunnerDiagnostic(result, DiagnosticSeverity.WARNING, warning, warning);
                fireOnDebug(warning);
                yield -1;
            }
            default -> -1;
        };
    }

    private void recordRunnerTerminationDiagnostic(RunnerTerminationResult termination) {
        if (termination == null) {
            return;
        }
        DiagnosticEvent event = DiagnosticEvent.of(DiagnosticOperation.RUNNER_RUN, termination.type != null ? termination.type.diagnosticSeverity() : DiagnosticSeverity.INFO,
                "CollectionRunner", termination.displayLabel());
        event.withAttribute("terminationType", termination.type != null ? termination.type.name() : RunnerTerminationType.INTERNAL_ERROR.name());
        event.withAttribute("completedCount", String.valueOf(termination.completedCount));
        event.withAttribute("queuedCount", String.valueOf(termination.totalQueuedCount));
        event.withAttribute("failureCount", String.valueOf(termination.failureCount));
        if (termination.requestName != null && !termination.requestName.isBlank()) {
            event.requestName = termination.requestName;
        }
        if (termination.requestId != null && !termination.requestId.isBlank()) {
            event.requestId = termination.requestId;
        }
        if (termination.reason != null && !termination.reason.isBlank()) {
            event.withAttribute("safeReason", termination.reason);
            event.withDetails("reason=" + termination.reason
                    + "\ncompletedCount=" + termination.completedCount
                    + "\nqueuedCount=" + termination.totalQueuedCount
                    + "\nfailureCount=" + termination.failureCount
                    + (termination.configuredCondition != null ? "\nconfiguredCondition=" + termination.configuredCondition : "")
                    + (termination.statusCode != null ? "\nstatusCode=" + termination.statusCode : "")
                    + (termination.scriptFlowControl != null ? "\nscriptFlowControl=" + termination.scriptFlowControl : ""));
        } else {
            event.withDetails("completedCount=" + termination.completedCount
                    + "\nqueuedCount=" + termination.totalQueuedCount
                    + "\nfailureCount=" + termination.failureCount);
        }
        if (termination.configuredCondition != null && !termination.configuredCondition.isBlank()) {
            event.withAttribute("configuredCondition", termination.configuredCondition);
        }
        if (termination.statusCode != null) {
            event.withAttribute("statusCode", String.valueOf(termination.statusCode));
        }
        if (termination.scriptFlowControl != null) {
            event.withAttribute("scriptFlowControl", termination.scriptFlowControl.name());
        }
        if (termination.internalErrorMessage != null && !termination.internalErrorMessage.isBlank()) {
            event.withAttribute("internalError", termination.internalErrorMessage);
        }
        DiagnosticStore.getInstance().record(event);
    }

    private void finalizeRunnerRun(RunLifecycle lifecycle,
                                   TerminationState termination,
                                   int completedCount,
                                   int totalQueuedCount,
                                   int failureCount,
                                   ExecutorService executor,
                                   Future<?> future) {
        if (lifecycle == null || !lifecycle.terminalDelivered.compareAndSet(false, true)) {
            return;
        }
        if (termination == null || !termination.isSet()) {
            if (termination == null) {
                termination = new TerminationState();
            }
            if (cancelled || (future != null && future.isCancelled())) {
                termination.stop(RunnerTerminationType.CANCELLED,
                        "User cancelled the runner.",
                        null,
                        null,
                        "cancelled",
                        null,
                        null);
            } else {
                termination.internalError("Unknown runner termination.", null);
            }
        }
        RunnerTerminationResult terminalResult = termination.toResult(completedCount, totalQueuedCount, failureCount);
        lastTerminationResult = terminalResult;
        running = false;
        if (activeExecutor == executor) {
            activeExecutor = null;
        }
        if (activeFuture == future) {
            activeFuture = null;
        }
        if (currentRunLifecycle == lifecycle) {
            currentRunLifecycle = null;
        }
        recordRunnerTerminationDiagnostic(terminalResult);
        fireTerminalCallbacks(terminalResult, new ArrayList<>(results));
        if (executor != null) {
            executor.shutdown();
        }
    }

    private void fireTerminalCallbacks(RunnerTerminationResult termination, List<RunnerResult> resultsSnapshot) {
        SwingUtilities.invokeLater(() -> {
            List<RunnerResult> snapshot = resultsSnapshot != null ? resultsSnapshot : Collections.emptyList();
            for (RunnerListener l : listeners) {
                l.onTerminal(termination, snapshot);
            }
            for (RunnerListener l : listeners) {
                if (termination == null || termination.isCompleted()) {
                    l.onComplete(snapshot);
                } else {
                    l.onError(safeTerminalLegacyMessage(termination));
                }
            }
        });
    }

    private String safeTerminalLegacyMessage(RunnerTerminationResult termination) {
        if (termination == null || termination.reason == null || termination.reason.isBlank()) {
            return "Runner terminated.";
        }
        return termination.reason;
    }

    private String cleanRunnerErrorReason(Exception e) {
        if (e == null) {
            return "Unknown runner error";
        }
        String message = e.getMessage();
        String cleaned = burp.diagnostics.DiagnosticSanitizer.sanitizeText(message != null ? message : e.getClass().getSimpleName());
        return e.getClass().getSimpleName() + (cleaned != null && !cleaned.isBlank() ? ": " + cleaned : "");
    }

    private String safeRunnerReason(String reason) {
        return reason != null && !reason.isBlank() ? burp.diagnostics.DiagnosticSanitizer.sanitizeText(reason) : "No details";
    }

    private String safeRunnerLabel(String value) {
        return value != null && !value.isBlank() ? value : "request";
    }

    private void recordRunnerDiagnostic(RunnerResult result, DiagnosticSeverity severity, String message, String details) {
        DiagnosticEvent event = DiagnosticEvent.of(DiagnosticOperation.RUNNER_RUN, severity, "CollectionRunner", message);
        if (result != null) {
            event.collectionName = result.collectionName;
            event.requestName = result.requestName;
            event.requestId = result.requestId;
            event.folderPath = result.folderPath;
            event.executionSource = result.executionSource;
            event.scriptDialect = null;
            event.scriptPhase = null;
        }
        event.withDetails(details);
        DiagnosticStore.getInstance().record(event);
    }

    private int resolveNextRequestIndex(List<ApiRequest> ordered, String nextRequestName, String nextRequestId) {
        if (ordered == null || ordered.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < ordered.size(); i++) {
            ApiRequest request = ordered.get(i);
            if (request == null) {
                continue;
            }
            if (nextRequestId != null && !nextRequestId.isBlank() && nextRequestId.equals(request.id)) {
                return i;
            }
            if (nextRequestName != null && !nextRequestName.isBlank() && nextRequestName.equals(request.name)) {
                return i;
            }
        }
        return -1;
    }

    private String safeFlowTarget(RunnerResult result) {
        if (result == null) {
            return "";
        }
        if (result.scriptFlowNextRequestName != null && !result.scriptFlowNextRequestName.isBlank()) {
            return result.scriptFlowNextRequestName;
        }
        if (result.scriptFlowNextRequestId != null && !result.scriptFlowNextRequestId.isBlank()) {
            return result.scriptFlowNextRequestId;
        }
        return "";
    }

    private final class RunnerDependentRequestExecutor implements ScriptDependentRequestExecutor {
        private static final int MAX_DEPENDENT_DEPTH = 3;
        private static final int MAX_DEPENDENT_REQUESTS = 20;

        private final Map<String, ApiRequest> requestById = new LinkedHashMap<>();
        private final Map<String, List<ApiRequest>> requestByName = new LinkedHashMap<>();
        private final Map<ApiRequest, ApiCollection> requestCollections;
        private final Map<String, ApiCollection> collectionsByName;
        private final Deque<String> requestStack = new ArrayDeque<>();
        private int dependentDepth = 0;
        private int dependentCount = 0;

        private RunnerDependentRequestExecutor(List<ApiCollection> sourceCollections,
                                               Map<ApiRequest, ApiCollection> requestCollections,
                                               Map<String, ApiCollection> collectionsByName) {
            this.requestCollections = requestCollections != null ? requestCollections : new IdentityHashMap<>();
            this.collectionsByName = collectionsByName != null ? collectionsByName : new HashMap<>();
            if (sourceCollections != null) {
                for (ApiCollection collection : sourceCollections) {
                    if (collection == null || collection.requests == null) {
                        continue;
                    }
                    for (ApiRequest request : collection.requests) {
                        if (request == null) {
                            continue;
                        }
                        if (request.id != null && !request.id.isBlank()) {
                            requestById.putIfAbsent(request.id, request);
                        }
                        if (request.name != null && !request.name.isBlank()) {
                            requestByName.computeIfAbsent(request.name, k -> new ArrayList<>()).add(request);
                        }
                    }
                }
            }
        }

        @Override
        public ScriptDependentRequestResult runRequest(ScriptExecutionContext context, String targetNameOrId) {
            if (context == null) {
                return ScriptDependentRequestResult.failure("runRequest is recognized but no script context is available.");
            }
            if (context.executionSource != burp.scripts.ExecutionSource.RUNNER) {
                return ScriptDependentRequestResult.ignored("runRequest is ignored outside Runner mode.");
            }
            ApiRequest target = resolveRequest(targetNameOrId);
            if (target == null) {
                String message = "Dependent request target not found: " + safeLabel(targetNameOrId);
                CollectionRunner.this.recordRunnerDiagnostic(null, DiagnosticSeverity.WARNING, message, message);
                return ScriptDependentRequestResult.failure(message);
            }
            if (target.disabled) {
                String message = "Dependent request target is disabled: " + safeLabel(target.name != null && !target.name.isBlank() ? target.name : targetNameOrId);
                CollectionRunner.this.recordRunnerDiagnostic(null, DiagnosticSeverity.WARNING, message, message);
                return ScriptDependentRequestResult.failure(message);
            }
            if (context.request != null && sameRequest(target, context.request)) {
                String message = "Dependent request recursion detected: " + dependentKey(target);
                CollectionRunner.this.recordRunnerDiagnostic(null, DiagnosticSeverity.WARNING, message, message);
                return ScriptDependentRequestResult.failure(message);
            }
            String stackKey = dependentKey(target);
            if (requestStack.contains(stackKey)) {
                String message = "Dependent request recursion detected: " + stackKey;
                CollectionRunner.this.recordRunnerDiagnostic(null, DiagnosticSeverity.WARNING, message, message);
                return ScriptDependentRequestResult.failure(message);
            }
            if (dependentDepth >= MAX_DEPENDENT_DEPTH) {
                String message = "Dependent request depth limit reached.";
                CollectionRunner.this.recordRunnerDiagnostic(null, DiagnosticSeverity.WARNING, message, message);
                return ScriptDependentRequestResult.failure(message);
            }
            if (dependentCount >= MAX_DEPENDENT_REQUESTS) {
                String message = "Dependent request count limit reached.";
                CollectionRunner.this.recordRunnerDiagnostic(null, DiagnosticSeverity.WARNING, message, message);
                return ScriptDependentRequestResult.failure(message);
            }
            dependentDepth++;
            int currentDepth = dependentDepth;
            dependentCount++;
            requestStack.push(stackKey);
            try {
                ApiCollection targetCollection = requestCollections.get(target);
                if (targetCollection == null && target.sourceCollection != null) {
                    targetCollection = collectionsByName.get(target.sourceCollection);
                }
                RequestExecutionOutcome outcome;
                try {
                    outcome = executeRequest(target,
                            targetCollection,
                            this,
                            true,
                            false,
                            context.request != null ? context.request.name : null,
                            context.request != null ? context.request.id : null,
                            currentDepth,
                            context.variableStore != null ? context.variableStore.effectiveVariablesSnapshot() : null);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return ScriptDependentRequestResult.failure("Dependent request interrupted.");
                }
                RunnerResult child = outcome.result;
                if (child == null) {
                    return ScriptDependentRequestResult.failure("Dependent request execution produced no result.");
                }
                child.dependentExecution = true;
                child.triggeredByScript = true;
                child.parentRequestName = context.request != null ? context.request.name : null;
                child.parentRequestId = context.request != null ? context.request.id : null;
                child.dependentDepth = currentDepth;
                results.add(child);
                fireOnRequestComplete(child);
                fireOnTimeline(buildTimelineRow(target, targetCollection, child, outcome.attempts));
                CollectionRunner.this.recordRunnerDiagnostic(child,
                        child.success ? DiagnosticSeverity.INFO : DiagnosticSeverity.ERROR,
                        child.dependentExecution ? "Dependent request executed" : "Request executed",
                        "dependent=" + child.dependentExecution + " depth=" + child.dependentDepth);

                ScriptDependentRequestResult dependentResult = new ScriptDependentRequestResult();
                dependentResult.executed = true;
                dependentResult.success = child.success;
                dependentResult.message = child.errorMessage != null && !child.errorMessage.isBlank()
                        ? child.errorMessage
                        : "runRequest";
                dependentResult.targetNameOrId = targetNameOrId;
                dependentResult.resolvedRequestName = child.requestName;
                dependentResult.resolvedRequestId = child.requestId;
                dependentResult.parentRequestName = context.request != null ? context.request.name : null;
                dependentResult.parentRequestId = context.request != null ? context.request.id : null;
                dependentResult.depth = currentDepth;
                dependentResult.runnerResult = child;
                return dependentResult;
            } finally {
                requestStack.pop();
                dependentDepth = Math.max(0, dependentDepth - 1);
            }
        }

        @Override
        public ScriptDependentRequestResult sendAdHocRequest(ScriptExecutionContext context, ScriptAdHocRequest request) {
            if (context == null) {
                return ScriptDependentRequestResult.failure("sendAdHocRequest is recognized but no script context is available.");
            }
            if (context.executionSource != burp.scripts.ExecutionSource.RUNNER) {
                return ScriptDependentRequestResult.ignored("sendAdHocRequest is ignored outside Runner mode.");
            }
            if (request == null || request.url == null || request.url.isBlank()) {
                String message = "sendAdHocRequest requires a URL.";
                CollectionRunner.this.recordRunnerDiagnostic(null, DiagnosticSeverity.WARNING, message, message);
                return ScriptDependentRequestResult.failure(message);
            }
            if (dependentDepth >= MAX_DEPENDENT_DEPTH) {
                String message = "Dependent request depth limit reached.";
                CollectionRunner.this.recordRunnerDiagnostic(null, DiagnosticSeverity.WARNING, message, message);
                return ScriptDependentRequestResult.failure(message);
            }
            if (dependentCount >= MAX_DEPENDENT_REQUESTS) {
                String message = "Dependent request count limit reached.";
                CollectionRunner.this.recordRunnerDiagnostic(null, DiagnosticSeverity.WARNING, message, message);
                return ScriptDependentRequestResult.failure(message);
            }
            dependentDepth++;
            int currentDepth = dependentDepth;
            dependentCount++;
            requestStack.push("ad-hoc:" + dependentCount);
            try {
                ApiRequest target = request.toApiRequest();
                ApiCollection targetCollection = context.collection;
                RequestExecutionOutcome outcome;
                try {
                    outcome = executeRequest(target,
                            targetCollection,
                            this,
                            true,
                            true,
                            context.request != null ? context.request.name : null,
                            context.request != null ? context.request.id : null,
                            currentDepth,
                            context.variableStore != null ? context.variableStore.effectiveVariablesSnapshot() : null);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return ScriptDependentRequestResult.failure("Ad-hoc request interrupted.");
                }
                RunnerResult child = outcome.result;
                if (child == null) {
                    return ScriptDependentRequestResult.failure("Ad-hoc request execution produced no result.");
                }
                child.dependentExecution = true;
                child.adHocExecution = true;
                child.triggeredByScript = true;
                child.parentRequestName = context.request != null ? context.request.name : null;
                child.parentRequestId = context.request != null ? context.request.id : null;
                child.dependentDepth = currentDepth;
                results.add(child);
                fireOnRequestComplete(child);
                fireOnTimeline(buildTimelineRow(target, targetCollection, child, outcome.attempts));
                CollectionRunner.this.recordRunnerDiagnostic(child,
                        child.success ? DiagnosticSeverity.INFO : DiagnosticSeverity.ERROR,
                        child.adHocExecution ? "Ad-hoc request executed" : "Request executed",
                        "adHoc=" + child.adHocExecution + " depth=" + child.dependentDepth);

                ScriptDependentRequestResult dependentResult = new ScriptDependentRequestResult();
                dependentResult.executed = true;
                dependentResult.success = child.success;
                dependentResult.message = child.errorMessage != null && !child.errorMessage.isBlank()
                        ? child.errorMessage
                        : "sendAdHocRequest";
                dependentResult.targetNameOrId = request.name != null && !request.name.isBlank() ? request.name : request.url;
                dependentResult.resolvedRequestName = child.requestName;
                dependentResult.resolvedRequestId = child.requestId;
                dependentResult.parentRequestName = context.request != null ? context.request.name : null;
                dependentResult.parentRequestId = context.request != null ? context.request.id : null;
                dependentResult.depth = currentDepth;
                dependentResult.adHoc = true;
                dependentResult.runnerResult = child;
                return dependentResult;
            } finally {
                requestStack.pop();
                dependentDepth = Math.max(0, dependentDepth - 1);
            }
        }

        private ApiRequest resolveRequest(String targetNameOrId) {
            if (targetNameOrId == null || targetNameOrId.isBlank()) {
                return null;
            }
            ApiRequest byId = requestById.get(targetNameOrId);
            if (byId != null) {
                return byId;
            }
            List<ApiRequest> byName = requestByName.get(targetNameOrId);
            if (byName != null && !byName.isEmpty()) {
                return byName.get(0);
            }
            for (Map.Entry<String, ApiRequest> entry : requestById.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(targetNameOrId)) {
                    return entry.getValue();
                }
            }
            for (Map.Entry<String, List<ApiRequest>> entry : requestByName.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(targetNameOrId)) {
                    return entry.getValue().isEmpty() ? null : entry.getValue().get(0);
                }
            }
            return null;
        }

        private String dependentKey(ApiRequest request) {
            if (request == null) {
                return "";
            }
            if (request.id != null && !request.id.isBlank()) {
                return request.id;
            }
            if (request.name != null && !request.name.isBlank()) {
                return request.name;
            }
            return "";
        }

        private boolean sameRequest(ApiRequest left, ApiRequest right) {
            if (left == null || right == null) {
                return false;
            }
            if (left.id != null && !left.id.isBlank() && right.id != null && !right.id.isBlank()) {
                return left.id.equals(right.id);
            }
            if (left.name != null && !left.name.isBlank() && right.name != null && !right.name.isBlank()) {
                return left.name.equalsIgnoreCase(right.name);
            }
            return false;
        }

        private String safeLabel(String value) {
            return value != null && !value.isBlank() ? value : "unknown";
        }
    }

    private void warnIfUnresolved(byte[] rawRequest, String requestName) {
        Set<String> unresolved = burp.utils.RequestBuilder.findUnresolvedTokens(rawRequest);
        if (!unresolved.isEmpty() && api != null) {
            api.logging().logToOutput("[WARN] Unresolved variables in request '" + requestName + "': " + String.join(", ", unresolved));
        }
    }

    private VariableResolver buildPreviewResolver(ApiRequest req, ApiCollection col) {
        Map<String, String> overlay = runtimeOverlayFor(col);
        return burp.utils.RuntimeResolverFactory.build(
                col,
                req,
                overlay != null
                        ? burp.utils.RuntimeResolverFactory.Options.withRuntimeVariableOverlay(overlay)
                        : burp.utils.RuntimeResolverFactory.Options.defaultOptions()
        );
    }

    private Map<String, String> runtimeOverlayFor(ApiCollection col) {
        if (runtimeOverlayProvider == null) {
            return null;
        }
        return runtimeOverlayProvider.apply(col);
    }

    private Map<String, String> mergeRuntimeOverlays(Map<String, String> baseOverlay, Map<String, String> explicitOverlay) {
        if ((baseOverlay == null || baseOverlay.isEmpty()) && (explicitOverlay == null || explicitOverlay.isEmpty())) {
            return null;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        if (baseOverlay != null && !baseOverlay.isEmpty()) {
            merged.putAll(baseOverlay);
        }
        if (explicitOverlay != null && !explicitOverlay.isEmpty()) {
            merged.putAll(explicitOverlay);
        }
        return merged;
    }

    private EnvironmentProfile activeEnvironmentFor(ApiCollection col) {
        if (activeEnvironmentProvider == null) {
            return null;
        }
        return activeEnvironmentProvider.apply(col);
    }

    private List<String> collectUnresolvedVariables(VariableResolver resolver, ApiRequest req) {
        Set<String> unresolved = new LinkedHashSet<>();
        if (req == null) {
            return new ArrayList<>();
        }

        addUnresolved(resolver, unresolved, req.url);
        if (req.headers != null) {
            for (ApiRequest.Header header : req.headers) {
                if (header == null || header.disabled) continue;
                addUnresolved(resolver, unresolved, header.key);
                addUnresolved(resolver, unresolved, header.value);
            }
        }
        if (req.body != null) {
            addUnresolved(resolver, unresolved, req.body.raw);
            if (req.body.urlencoded != null) {
                for (ApiRequest.Body.FormField field : req.body.urlencoded) {
                    if (field == null || field.disabled) continue;
                    addUnresolved(resolver, unresolved, field.key);
                    addUnresolved(resolver, unresolved, field.value);
                    addUnresolved(resolver, unresolved, field.filePath);
                }
            }
            if (req.body.formdata != null) {
                for (ApiRequest.Body.FormField field : req.body.formdata) {
                    if (field == null || field.disabled) continue;
                    addUnresolved(resolver, unresolved, field.key);
                    addUnresolved(resolver, unresolved, field.value);
                    addUnresolved(resolver, unresolved, field.filePath);
                }
            }
            if (req.body.graphql != null) {
                addUnresolved(resolver, unresolved, req.body.graphql.query);
                addUnresolved(resolver, unresolved, req.body.graphql.variables);
            }
        }
        if (req.auth != null && req.auth.properties != null) {
            for (String value : req.auth.properties.values()) {
                addUnresolved(resolver, unresolved, value);
            }
        }

        return new ArrayList<>(unresolved);
    }

    private void addUnresolved(VariableResolver resolver, Set<String> unresolved, String input) {
        if (resolver == null || unresolved == null || input == null || input.isEmpty()) {
            return;
        }
        unresolved.addAll(resolver.findUnresolvedVariables(input));
    }

    private boolean hasAssertionFailure(RunnerResult result) {
        if (result == null || result.assertions == null || result.assertions.isEmpty()) {
            return false;
        }
        for (RunnerResult.AssertionResult assertion : result.assertions) {
            if (assertion != null && !assertion.passed) {
                return true;
            }
        }
        return false;
    }

    private boolean hasStatusAtLeast400(RunnerResult result) {
        return result != null && result.success && result.statusCode >= 400;
    }

    private RunnerResult snapshotAttemptResult(RunnerResult source,
                                               ExecutionResult exec,
                                               int attemptNumber,
                                               int totalAttempts) {
        RunnerResult snapshot = new RunnerResult();
        if (source != null) {
            snapshot.requestName = source.requestName;
            snapshot.requestId = source.requestId;
            snapshot.collectionName = source.collectionName;
            snapshot.folderPath = source.folderPath;
            snapshot.host = source.host;
            snapshot.path = source.path;
            snapshot.method = source.method;
            snapshot.requestUrl = source.requestUrl;
            snapshot.initialResolvedUrl = source.initialResolvedUrl;
            snapshot.finalResolvedUrl = source.finalResolvedUrl;
            snapshot.originalResolvedUrl = source.originalResolvedUrl;
            snapshot.effectiveResolvedUrl = source.effectiveResolvedUrl;
            snapshot.redirectsEnabled = source.redirectsEnabled;
            snapshot.redirectTerminationReason = source.redirectTerminationReason;
            snapshot.redirectHops = copyRedirectHops(source.redirectHops);
            snapshot.requestSent = source.requestSent;
            snapshot.preflightStatus = source.preflightStatus;
            snapshot.preflightMessage = source.preflightMessage;
            snapshot.responseTimedOut = source.responseTimedOut;
            snapshot.timeoutMillis = source.timeoutMillis;
            snapshot.targetChanged = source.targetChanged;
            snapshot.oauth2Required = source.oauth2Required;
            snapshot.oauth2Ready = source.oauth2Ready;
            snapshot.oauth2UsedStaleToken = source.oauth2UsedStaleToken;
            snapshot.oauth2SentWithoutToken = source.oauth2SentWithoutToken;
            snapshot.unresolvedVariables = source.unresolvedVariables != null ? new ArrayList<>(source.unresolvedVariables) : new ArrayList<>();
            snapshot.policyOverridesApplied = source.policyOverridesApplied != null ? new ArrayList<>(source.policyOverridesApplied) : new ArrayList<>();
            snapshot.requestHeaders = source.requestHeaders;
            snapshot.requestBody = source.requestBody;
            snapshot.success = source.success;
            snapshot.statusCode = source.statusCode;
            snapshot.responseTimeMs = source.responseTimeMs;
            snapshot.responseSize = source.responseSize;
            snapshot.responseBodyLength = source.responseBodyLength;
            snapshot.responseHeaders = source.responseHeaders;
            snapshot.responseBody = source.responseBody;
            snapshot.errorMessage = source.errorMessage;
            snapshot.responseBodyPreview = source.responseBodyPreview;
            snapshot.extractedVariables = source.extractedVariables != null ? new HashMap<>(source.extractedVariables) : new HashMap<>();
            snapshot.assertions = source.assertions != null ? new ArrayList<>(source.assertions) : new ArrayList<>();
            snapshot.rawRequestBytes = source.rawRequestBytes != null ? source.rawRequestBytes.clone() : null;
            snapshot.rawRequestText = source.rawRequestText;
            snapshot.resolvedVariables = source.resolvedVariables != null ? new HashMap<>(source.resolvedVariables) : new HashMap<>();
            snapshot.scriptEngineName = source.scriptEngineName;
            snapshot.executionSource = source.executionSource;
            snapshot.scriptLogs = source.scriptLogs != null ? new ArrayList<>(source.scriptLogs) : new ArrayList<>();
            snapshot.scriptWarnings = source.scriptWarnings != null ? new ArrayList<>(source.scriptWarnings) : new ArrayList<>();
            snapshot.scriptErrors = source.scriptErrors != null ? new ArrayList<>(source.scriptErrors) : new ArrayList<>();
            snapshot.scriptVariableMutations = source.scriptVariableMutations != null ? new ArrayList<>(source.scriptVariableMutations) : new ArrayList<>();
            snapshot.scriptFlowControl = source.scriptFlowControl;
            snapshot.scriptFlowMessage = source.scriptFlowMessage;
            snapshot.scriptFlowNextRequestName = source.scriptFlowNextRequestName;
            snapshot.scriptFlowNextRequestId = source.scriptFlowNextRequestId;
        }
        if (exec != null) {
            if (exec.requestHeaders != null) {
                snapshot.requestHeaders = exec.requestHeaders;
            }
            if (exec.requestBody != null) {
                snapshot.requestBody = exec.requestBody;
            }
            if (exec.rawRequestBytes != null) {
                snapshot.rawRequestBytes = exec.rawRequestBytes.clone();
                snapshot.rawRequestText = exec.rawRequestText != null
                        ? exec.rawRequestText
                        : new String(exec.rawRequestBytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            if (exec.resolvedUrl != null) {
                snapshot.requestUrl = exec.resolvedUrl;
            }
            snapshot.initialResolvedUrl = exec.initialResolvedUrl != null ? exec.initialResolvedUrl : snapshot.initialResolvedUrl;
            snapshot.finalResolvedUrl = exec.finalResolvedUrl != null ? exec.finalResolvedUrl : snapshot.finalResolvedUrl;
            snapshot.originalResolvedUrl = exec.originalResolvedUrl != null ? exec.originalResolvedUrl : snapshot.originalResolvedUrl;
            snapshot.effectiveResolvedUrl = exec.effectiveResolvedUrl != null ? exec.effectiveResolvedUrl : snapshot.effectiveResolvedUrl;
            snapshot.redirectsEnabled = exec.redirectsEnabled;
            snapshot.redirectTerminationReason = exec.redirectTerminationReason;
            snapshot.redirectHops = copyRedirectHops(exec.redirectHops);
            snapshot.requestSent = exec.requestSent;
            snapshot.preflightStatus = exec.preflightStatus != null ? exec.preflightStatus : snapshot.preflightStatus;
            snapshot.preflightMessage = exec.preflightMessage != null ? exec.preflightMessage : snapshot.preflightMessage;
            snapshot.responseTimedOut = exec.responseTimedOut;
            snapshot.timeoutMillis = exec.timeoutMillis > 0 ? exec.timeoutMillis : snapshot.timeoutMillis;
            snapshot.targetChanged = exec.targetChangeAllowed || (exec.preflight != null && exec.preflight.targetChanged) || snapshot.targetChanged;
            snapshot.oauth2Required = exec.oauth2Required;
            snapshot.oauth2Ready = exec.oauth2Ready;
            snapshot.oauth2UsedStaleToken = exec.oauth2UsedStaleToken;
            snapshot.oauth2SentWithoutToken = exec.oauth2SentWithoutToken;
            if (exec.preflight != null) {
                snapshot.unresolvedVariables = new ArrayList<>(exec.preflight.unresolvedVariables);
                snapshot.policyOverridesApplied = new ArrayList<>(exec.preflight.policyOverridesApplied);
            } else if (exec.policyOverridesApplied != null && !exec.policyOverridesApplied.isEmpty()) {
                snapshot.policyOverridesApplied = new ArrayList<>(exec.policyOverridesApplied);
            }
            if (exec.assertions != null && !exec.assertions.isEmpty()) {
                snapshot.assertions = new ArrayList<>(exec.assertions);
            }
            if (exec.extractedVars != null && !exec.extractedVars.isEmpty()) {
                snapshot.extractedVariables = new HashMap<>(exec.extractedVars);
            }
            if (exec.resolvedVariables != null && !exec.resolvedVariables.isEmpty()) {
                snapshot.resolvedVariables = new HashMap<>(exec.resolvedVariables);
            }
            if (exec.scriptEngineName != null) {
                snapshot.scriptEngineName = exec.scriptEngineName;
            }
            snapshot.executionSource = exec.executionSource != null ? exec.executionSource : snapshot.executionSource;
            if (exec.scriptLogs != null && !exec.scriptLogs.isEmpty()) {
                snapshot.scriptLogs = new ArrayList<>(exec.scriptLogs);
            }
            if (exec.scriptWarnings != null && !exec.scriptWarnings.isEmpty()) {
                snapshot.scriptWarnings = new ArrayList<>(exec.scriptWarnings);
            }
            if (exec.scriptErrors != null && !exec.scriptErrors.isEmpty()) {
                snapshot.scriptErrors = new ArrayList<>(exec.scriptErrors);
            }
            if (exec.scriptVariableMutations != null && !exec.scriptVariableMutations.isEmpty()) {
                snapshot.scriptVariableMutations = new ArrayList<>(exec.scriptVariableMutations);
            }
            snapshot.scriptFlowControl = exec.scriptFlowControl != null ? exec.scriptFlowControl : snapshot.scriptFlowControl;
            snapshot.scriptFlowMessage = exec.scriptFlowMessage != null ? exec.scriptFlowMessage : snapshot.scriptFlowMessage;
            snapshot.scriptFlowNextRequestName = exec.scriptFlowNextRequestName != null ? exec.scriptFlowNextRequestName : snapshot.scriptFlowNextRequestName;
            snapshot.scriptFlowNextRequestId = exec.scriptFlowNextRequestId != null ? exec.scriptFlowNextRequestId : snapshot.scriptFlowNextRequestId;
        }
        snapshot.attemptNumber = Math.max(1, attemptNumber);
        snapshot.totalAttempts = Math.max(1, totalAttempts);
        return snapshot;
    }

    private RunnerTimelineRow buildTimelineRow(ApiRequest req, ApiCollection col, RunnerResult result, int attempts) {
        RunnerTimelineRow row = new RunnerTimelineRow();
        row.order = req != null ? req.sequenceOrder : 0;
        row.collectionName = col != null && col.name != null ? col.name : (req != null ? req.sourceCollection : "");
        row.requestName = req != null ? req.name : "";
        row.status = result != null ? result.displayStatusLabel() : "";
        row.timeMs = result != null ? result.responseTimeMs : 0L;
        row.retries = Math.max(0, attempts - 1);
        row.varsChanged = result != null && result.extractedVariables != null ? result.extractedVariables.size() : 0;
        row.assertions = formatAssertionSummary(result);
        return row;
    }

    private String formatAssertionSummary(RunnerResult result) {
        if (result == null || result.assertions == null || result.assertions.isEmpty()) {
            return "0/0";
        }
        int passed = 0;
        int total = 0;
        for (RunnerResult.AssertionResult assertion : result.assertions) {
            if (assertion == null) {
                continue;
            }
            total++;
            if (assertion.passed) {
                passed++;
            }
        }
        return passed + "/" + total;
    }

    private int retryDelayMs(int attemptNumber) {
        return Math.max(0, delayMs * attemptNumber);
    }

    private RunnerStopConditions ensureStopConditions() {
        if (stopConditions == null) {
            stopConditions = new RunnerStopConditions();
        }
        return stopConditions;
    }

    private RunnerStopConditions copyStopConditions(RunnerStopConditions source) {
        RunnerStopConditions copy = new RunnerStopConditions();
        if (source != null) {
            copy.stopOnError = source.stopOnError;
            copy.stopOnAssertionFailure = source.stopOnAssertionFailure;
            copy.stopOnStatusAtLeast400 = source.stopOnStatusAtLeast400;
            copy.stopOnMissingVariable = source.stopOnMissingVariable;
            copy.stopAfterFailureCount = source.stopAfterFailureCount;
        }
        return copy;
    }

    public void cancel() {
        cancelled = true;
        if (pipeline != null) {
            pipeline.cancelActiveScriptExecutions();
        }
        synchronized (pauseLock) {
            pauseRequested = false;
            singleStepRequested = false;
            pauseLock.notifyAll();
        }
        Future<?> future = activeFuture;
        if (future != null) {
            future.cancel(true);
        }
        ExecutorService executor = activeExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public boolean isRunning() { return running; }
    public List<RunnerResult> getResults() { return new ArrayList<>(results); }
    public Map<String, String> getExtractedVariables() { return new HashMap<>(extractedVars); }

    private boolean waitIfPausedOrStepConsumed() throws InterruptedException {
        synchronized (pauseLock) {
            while (!cancelled && pauseRequested && !singleStepRequested) {
                pauseLock.wait();
            }
            if (cancelled) {
                return false;
            }
            if (singleStepRequested) {
                singleStepRequested = false;
                pauseRequested = true;
            }
            return true;
        }
    }

    // Listener notifications - all dispatched on EDT for Swing safety
    private void fireOnStart(String collectionName, int totalRequests) {
        SwingUtilities.invokeLater(() -> {
            for (RunnerListener l : listeners) l.onStart(collectionName, totalRequests);
        });
    }
    private void fireOnRequestStart(RunnerResult result) {
        SwingUtilities.invokeLater(() -> {
            for (RunnerListener l : listeners) l.onRequestStart(result);
        });
    }
    private void fireOnSkip(String requestName, String reason) {
        SwingUtilities.invokeLater(() -> {
            for (RunnerListener l : listeners) l.onSkip(requestName, reason);
        });
    }
    private void fireOnRequestComplete(RunnerResult result) {
        SwingUtilities.invokeLater(() -> {
            for (RunnerListener l : listeners) l.onRequestComplete(result);
        });
    }
    private void fireOnAttemptComplete(RunnerResult result) {
        SwingUtilities.invokeLater(() -> {
            for (RunnerListener l : listeners) l.onAttemptComplete(result);
        });
    }
    private void fireOnTimeline(RunnerTimelineRow row) {
        SwingUtilities.invokeLater(() -> {
            for (RunnerListener l : listeners) l.onTimeline(row);
        });
    }
    private void fireOnComplete(List<RunnerResult> results) {
        SwingUtilities.invokeLater(() -> {
            for (RunnerListener l : listeners) l.onComplete(results);
        });
    }
    private void fireOnDebug(String message) {
        SwingUtilities.invokeLater(() -> {
            for (RunnerListener l : listeners) l.onDebug(message);
        });
    }
    private void fireOnError(String message) {
        SwingUtilities.invokeLater(() -> {
            for (RunnerListener l : listeners) l.onError(message);
        });
    }

    private static final class TerminationState {
        private RunnerTerminationType type;
        private String reason;
        private String requestName;
        private String requestId;
        private Integer statusCode;
        private String configuredCondition;
        private ScriptFlowControl scriptFlowControl;
        private String internalErrorMessage;

        boolean isSet() {
            return type != null;
        }

        void complete(String reason) {
            set(RunnerTerminationType.COMPLETED, reason, null, null, null, null, null);
        }

        void internalError(String reason, Exception error) {
            set(RunnerTerminationType.INTERNAL_ERROR, reason, null, null, null, null,
                    error != null ? error.getClass().getSimpleName() : null);
        }

        void stop(RunnerTerminationType type,
                  String reason,
                  RunnerResult trigger,
                  Integer statusCode,
                  String configuredCondition,
                  ScriptFlowControl flowControl,
                  String internalErrorMessage) {
            set(type, reason, trigger, statusCode, configuredCondition, flowControl, internalErrorMessage);
        }

        private void set(RunnerTerminationType type,
                         String reason,
                         RunnerResult trigger,
                         Integer statusCode,
                         String configuredCondition,
                         ScriptFlowControl flowControl,
                         String internalErrorMessage) {
            if (this.type != null) {
                return;
            }
            this.type = type != null ? type : RunnerTerminationType.INTERNAL_ERROR;
            this.reason = reason;
            this.requestName = trigger != null ? trigger.requestName : null;
            this.requestId = trigger != null ? trigger.requestId : null;
            this.statusCode = statusCode != null ? statusCode : trigger != null && trigger.statusCode > 0 ? trigger.statusCode : null;
            this.configuredCondition = configuredCondition;
            this.scriptFlowControl = flowControl != null ? flowControl : trigger != null ? trigger.scriptFlowControl : null;
            this.internalErrorMessage = internalErrorMessage;
        }

        RunnerTerminationResult toResult(int completedCount, int totalQueuedCount, int failureCount) {
            return new RunnerTerminationResult(type, reason, requestName, requestId, statusCode, completedCount, totalQueuedCount, failureCount, scriptFlowControl, configuredCondition, internalErrorMessage);
        }
    }

    private static final class RunLifecycle {
        private final AtomicBoolean workerStarted = new AtomicBoolean(false);
        private final AtomicBoolean terminalDelivered = new AtomicBoolean(false);
    }

    private enum RequestOutcomeState {
        COMPLETED,
        CANCELLED,
        FAILED_BEFORE_RESULT
    }

    public interface RunnerListener {
        void onStart(String collectionName, int totalRequests);
        default void onRequestStart(RunnerResult result) { }
        void onSkip(String requestName, String reason);
        void onRequestComplete(RunnerResult result);
        default void onAttemptComplete(RunnerResult result) { }
        default void onTimeline(RunnerTimelineRow row) { }
        void onComplete(List<RunnerResult> results);
        default void onTerminal(RunnerTerminationResult termination, List<RunnerResult> results) { }
        void onError(String message);
        default void onDebug(String message) { }
    }

    private static final class RequestExecutionOutcome {
        private final RunnerResult result;
        private final int attempts;
        private final RequestOutcomeState state;

        private RequestExecutionOutcome(RunnerResult result, int attempts, RequestOutcomeState state) {
            this.result = result;
            this.attempts = attempts;
            this.state = state != null ? state : RequestOutcomeState.COMPLETED;
        }
    }
}
