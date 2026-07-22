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
    private volatile RunnerRetryPolicy retryPolicy = RunnerRetryPolicy.safeDefaults();
    private boolean followRedirects = true;
    private RedirectPolicy redirectPolicy = RedirectPolicy.defaults();
    private boolean debugRawRequest = false;
    private volatile boolean addResponsesToSiteMap = false;
    private volatile RunnerStopConditions stopConditions = new RunnerStopConditions();
    private final Object pauseLock = new Object();
    private volatile boolean pauseRequested = false;
    private volatile boolean singleStepRequested = false;
    private volatile boolean requestInFlight = false;
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
    private final RunnerFlowTargetResolver flowTargetResolver = new RunnerFlowTargetResolver();

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
    public void setMaxRetries(int maxRetries) {
        RunnerRetryPolicy copy = RunnerRetryPolicy.copyOf(retryPolicy);
        copy.maxRetries = maxRetries;
        if (copy.maxRetries > 0 && !copy.retryConnectionFailures && !copy.retryTimeouts) {
            copy.retryConnectionFailures = true;
            copy.retryTimeouts = true;
        }
        copy.normalize();
        setRetryPolicy(copy);
    }
    public void setRetryPolicy(RunnerRetryPolicy policy) {
        RunnerRetryPolicy copy = RunnerRetryPolicy.copyOf(policy);
        copy.normalize();
        this.retryPolicy = copy;
    }
    public RunnerRetryPolicy getRetryPolicy() {
        return RunnerRetryPolicy.copyOf(retryPolicy);
    }
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
    public void setAddResponsesToSiteMap(boolean enabled) { this.addResponsesToSiteMap = enabled; }
    public boolean isAddResponsesToSiteMap() { return addResponsesToSiteMap; }
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
        if (!canStep()) {
            return;
        }
        synchronized (pauseLock) {
            if (!canStep()) {
                return;
            }
            singleStepRequested = true;
            pauseRequested = false;
            pauseLock.notifyAll();
        }
    }

    public boolean isPaused() {
        return pauseRequested;
    }

    public boolean isRequestInFlight() {
        return requestInFlight;
    }

    public boolean canStep() {
        return running && pauseRequested && !requestInFlight && !cancelled;
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
        requestInFlight = false;
        final RunnerStopConditions activeStopConditions = copyStopConditions(ensureStopConditions());
        synchronized (pauseLock) {
            pauseRequested = startPaused;
            singleStepRequested = false;
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
        final List<ApiCollection> activeSourceCollections = sourceCollections != null ? new ArrayList<>(sourceCollections) : Collections.emptyList();

        List<ApiRequest> ordered = orderRequestsForRun(selectedRequests);
        RunnerDependentRequestExecutor dependentRequestExecutor = new RunnerDependentRequestExecutor(activeSourceCollections, reqToColMap, colMap);

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

                    requestInFlight = true;
                    RequestExecutionOutcome outcome = executeRequest(req, col, dependentRequestExecutor, false, false, null, null, 0);
                    if (outcome == null || outcome.state == RequestOutcomeState.FAILED_BEFORE_RESULT || outcome.result == null) {
                        requestInFlight = false;
                        break;
                    }

                    RunnerResult result = outcome.result;
                    results.add(result);
                    requestInFlight = false;
                    fireOnRequestComplete(result);
                    if (outcome.state == RequestOutcomeState.CANCELLED || result.cancellationState != RunnerCancellationState.NOT_CANCELLED) {
                        termination.stop(RunnerTerminationType.CANCELLED,
                                "User cancelled the runner.",
                                result,
                                null,
                                "cancelled",
                                null,
                                null);
                        break;
                    }
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

                    int nextIndex = applyFlowControl(activeSourceCollections, ordered, i, result, flowControlJumps, maxFlowControlJumps, termination);
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
        RunnerRetryPolicy retryPolicySnapshot = getRetryPolicy();
        ExecutionPolicy previewPolicy = effectiveExecutionPolicy();
        int responseTimeoutMillis = previewPolicy.responseTimeoutMillis;
        String targetChangePolicy = previewPolicy.targetChangeMode != null ? previewPolicy.targetChangeMode.name() : "";

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
            row.method = req.method != null ? req.method.trim().toUpperCase(Locale.ROOT) : "GET";
            row.urlPreview = resolver.resolve(req.url);
            row.authStatus = describeAuth(req);
            row.unresolvedVariables.addAll(collectUnresolvedVariables(resolver, req));
            int maximumAttempts = retryPolicySnapshot.maximumAttemptsFor(row.method);
            row.retryEligible = maximumAttempts > 1;
            row.maximumAttempts = maximumAttempts;
            row.retryPolicySummary = maximumAttempts <= 1
                    ? "No retries"
                    : "Eligible; up to " + maximumAttempts + " attempts; connection=" + retryPolicySnapshot.retryConnectionFailures
                    + "; timeout=" + retryPolicySnapshot.retryTimeouts
                    + "; status=" + (retryPolicySnapshot.retryableStatusCodes != null && !retryPolicySnapshot.retryableStatusCodes.isEmpty()
                    ? retryPolicySnapshot.retryableStatusCodes.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "))
                    : "none");
            row.responseTimeoutMillis = responseTimeoutMillis;
            row.targetChangePolicy = targetChangePolicy;
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
        return executeRequest(
                req,
                col,
                dependentRequestExecutor,
                dependentExecution,
                adHocExecution,
                parentRequestName,
                parentRequestId,
                dependentDepth,
                null,
                null);
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
        return executeRequest(
                req,
                col,
                dependentRequestExecutor,
                dependentExecution,
                adHocExecution,
                parentRequestName,
                parentRequestId,
                dependentDepth,
                runtimeOverlayOverride,
                null);
    }

    private RequestExecutionOutcome executeRequest(ApiRequest req,
                                                   ApiCollection col,
                                                   ScriptDependentRequestExecutor dependentRequestExecutor,
                                                   boolean dependentExecution,
                                                   boolean adHocExecution,
                                                   String parentRequestName,
                                                   String parentRequestId,
                                                   int dependentDepth,
                                                   Map<String, String> runtimeOverlayOverride,
                                                   FlowTargetResolution targetResolution) throws InterruptedException {
        RunnerRetryPolicy policySnapshot = getRetryPolicy();
        int maxAttempts = policySnapshot.maximumAttemptsFor(req != null ? req.method : null);
        RunnerResult requestStart = newAttemptResult(req, col, dependentExecution, adHocExecution, parentRequestName, parentRequestId, dependentDepth, 1, maxAttempts, targetResolution);
        fireOnRequestStart(snapshotRequestStart(requestStart, req, col));

        if (pipeline == null) {
            RunnerResult result = newAttemptResult(req, col, dependentExecution, adHocExecution, parentRequestName, parentRequestId, dependentDepth, 1, maxAttempts, targetResolution);
            result.success = false;
            result.errorMessage = "Runner pipeline unavailable";
            result.retryDecision = "NO_RETRY";
            result.retryReason = "Retry disabled: unconfigured failure type.";
            publishAttempt(req, col, result);
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
        RunnerResult result = null;
        while (attempts < maxAttempts && !cancelled) {
            attempts++;
            result = newAttemptResult(req, col, dependentExecution, adHocExecution, parentRequestName, parentRequestId, dependentDepth, attempts, maxAttempts, targetResolution);
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
                    exec = pipeline.execute(req, col, followRedirects, overlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, burp.scripts.ExecutionSource.RUNNER, dependentRequestExecutor, redirectPolicy, currentPolicy, null, () -> cancelled || Thread.currentThread().isInterrupted());
                }
                if (exec == null) {
                    result.success = false;
                    result.errorMessage = "Runner pipeline unavailable";
                    result.retryDecision = "NO_RETRY";
                    result.retryReason = "Retry disabled: unconfigured failure type.";
                    result.retryDelayMillis = 0;
                    publishAttempt(req, col, result);
                    return new RequestExecutionOutcome(result, attempts, RequestOutcomeState.COMPLETED);
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
                    RetryFailureType failureType =
                            classifyRetryFailure(
                                    exec,
                                    result,
                                    policySnapshot);

                    AttemptDisposition disposition =
                            finalizeAndPublishAttempt(
                                    req,
                                    col,
                                    result,
                                    failureType,
                                    policySnapshot,
                                    attempts,
                                    maxAttempts);

                    return new RequestExecutionOutcome(
                            result,
                            attempts,
                            disposition == AttemptDisposition.CANCELLED
                                    ? RequestOutcomeState.CANCELLED
                                    : RequestOutcomeState.COMPLETED);
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
                if (isRunnerCancellationResult(exec, result)) {
                    markCancelledAttempt(
                            result,
                            cancellationStateFor(exec, result),
                            policySnapshot,
                            attempts,
                            maxAttempts);
                    publishAttempt(req, col, result);
                    return new RequestExecutionOutcome(
                            result,
                            attempts,
                            RequestOutcomeState.CANCELLED);
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
                    recordRunnerDiagnostic(result,
                            exec.scriptFlowControl == burp.scripts.ScriptFlowControl.SKIP_REQUEST ? DiagnosticSeverity.INFO : DiagnosticSeverity.WARNING,
                            exec.scriptFlowControl == burp.scripts.ScriptFlowControl.SKIP_REQUEST ? "Skipped by script" : "Stopped by script",
                            "Flow Control: " + exec.scriptFlowControl);
                    finalizeAndPublishAttempt(
                            req,
                            col,
                            result,
                            classifyRetryFailure(
                                    exec,
                                    result,
                                    policySnapshot),
                            policySnapshot,
                            attempts,
                            maxAttempts);
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
                    if (api != null && addResponsesToSiteMap) {
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

                    RetryFailureType statusFailure =
                            classifyRetryFailure(
                                    exec,
                                    result,
                                    policySnapshot);

                    if (statusFailure == RetryFailureType.HTTP_STATUS) {
                        AttemptDisposition disposition =
                                finalizeAndPublishAttempt(
                                        req,
                                        col,
                                        result,
                                        statusFailure,
                                        policySnapshot,
                                        attempts,
                                        maxAttempts);

                        if (disposition == AttemptDisposition.CANCELLED) {
                            return new RequestExecutionOutcome(
                                    result,
                                    attempts,
                                    RequestOutcomeState.CANCELLED);
                        }

                        if (disposition == AttemptDisposition.RETRY) {
                            continue;
                        }

                        break;
                    }

                    finalizeSuccessfulAttempt(result, attempts, maxAttempts);
                    publishAttempt(req, col, result);
                    break; // Success, exit retry loop
                } else {
                    result.success = false;
                    result.errorMessage = exec.errorMessage != null ? exec.errorMessage : "No response received";
                    result.initialResolvedUrl = exec.initialResolvedUrl != null ? exec.initialResolvedUrl : exec.resolvedUrl;
                    result.finalResolvedUrl = exec.finalResolvedUrl != null ? exec.finalResolvedUrl : exec.resolvedUrl;
                    result.redirectsEnabled = exec.redirectsEnabled;
                    result.redirectTerminationReason = exec.redirectTerminationReason;
                    result.redirectHops = copyRedirectHops(exec.redirectHops);
                    if (exec.response != null && exec.response.response() != null) {
                        result.statusCode = exec.response.response().statusCode();
                        result.responseSize = exec.response.response().body() != null ? exec.response.response().body().length() : 0;
                        result.responseTimeMs = exec.elapsedMs;
                    }
                    if (!cancelled && !Thread.currentThread().isInterrupted()) {
                        fireOnDebug("Attempt " + attempts + "/" + maxAttempts + " failed: " + result.errorMessage);
                    }
                    RetryFailureType failureType =
                            classifyRetryFailure(
                                    exec,
                                    result,
                                    policySnapshot);

                    AttemptDisposition disposition =
                            finalizeAndPublishAttempt(
                                    req,
                                    col,
                                    result,
                                    failureType,
                                    policySnapshot,
                                    attempts,
                                    maxAttempts);

                    if (disposition == AttemptDisposition.CANCELLED) {
                        return new RequestExecutionOutcome(
                                result,
                                attempts,
                                RequestOutcomeState.CANCELLED);
                    }

                    if (disposition == AttemptDisposition.COMPLETE) {
                        break;
                    }

                    continue;
                }
            } catch (Exception e) {
                if (cancelled || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    markCancelledAttempt(
                            result,
                            cancellationStateFor(null, result),
                            policySnapshot,
                            attempts,
                            maxAttempts);
                    publishAttempt(req, col, result);
                    return new RequestExecutionOutcome(
                            result,
                            attempts,
                            RequestOutcomeState.CANCELLED);
                }
                result.success = false;
                result.errorMessage = extractCleanError(e);
                if (!cancelled && !Thread.currentThread().isInterrupted()) {
                    fireOnDebug("Attempt " + attempts + "/" + maxAttempts + " failed: " + result.errorMessage);
                }
                AttemptDisposition disposition =
                        finalizeAndPublishAttempt(
                                req,
                                col,
                                result,
                                RetryFailureType.SCRIPT_FAILURE,
                                policySnapshot,
                                attempts,
                                maxAttempts);

                if (disposition == AttemptDisposition.CANCELLED) {
                    return new RequestExecutionOutcome(
                            result,
                            attempts,
                            RequestOutcomeState.CANCELLED);
                }

                if (disposition == AttemptDisposition.COMPLETE) {
                    break;
                }
            }
        }

        if (result == null) {
            result = newAttemptResult(
                    req,
                    col,
                    dependentExecution,
                    adHocExecution,
                    parentRequestName,
                    parentRequestId,
                    dependentDepth,
                    Math.max(1, attempts),
                    maxAttempts,
                    targetResolution);
            markCancelledAttempt(
                    result,
                    RunnerCancellationState.CANCELLED_BEFORE_SEND,
                    policySnapshot,
                    Math.max(1, attempts),
                    maxAttempts);
            publishAttempt(req, col, result);
            return new RequestExecutionOutcome(
                    result,
                    Math.max(1, attempts),
                    RequestOutcomeState.CANCELLED);
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
            snapshot.collectionId = source.collectionId;
            snapshot.collectionName = source.collectionName;
            snapshot.folderPath = source.folderPath;
            snapshot.method = source.method;
            snapshot.buildMode = source.buildMode;
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
            snapshot.targetResolutionForm = source.targetResolutionForm;
            snapshot.qualifiedTargetPath = source.qualifiedTargetPath;
            snapshot.cancellationState = source.cancellationState;
            snapshot.attemptNumber = Math.max(1, source.attemptNumber);
            snapshot.totalAttempts = Math.max(1, source.totalAttempts);
        } else if (req != null) {
            snapshot.requestName = req.name;
            snapshot.requestId = req.id;
            snapshot.collectionId = col != null ? col.ensureId() : null;
            snapshot.collectionName = col != null ? col.name : null;
            snapshot.method = req.method != null ? req.method.toUpperCase() : "GET";
            snapshot.buildMode = req.resolveBuildMode();
            snapshot.requestUrl = req.url;
            snapshot.initialResolvedUrl = req.url;
            snapshot.finalResolvedUrl = req.url;
            snapshot.path = req.path;
        }
        if (col != null && snapshot.collectionName == null) {
            snapshot.collectionName = col.name;
        }
        if (col != null && snapshot.collectionId == null) {
            snapshot.collectionId = col.ensureId();
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

    static List<ScriptDependentRequestResult> copyDependentRequestResults(List<ScriptDependentRequestResult> results) {
        List<ScriptDependentRequestResult> copy = new ArrayList<>();
        if (results == null) {
            return copy;
        }
        for (ScriptDependentRequestResult result : results) {
            if (result == null) {
                continue;
            }
            ScriptDependentRequestResult item = new ScriptDependentRequestResult();
            item.executed = result.executed;
            item.success = result.success;
            item.message = result.message;
            item.warningMessage = result.warningMessage;
            item.errorMessage = result.errorMessage;
            item.targetNameOrId = result.targetNameOrId;
            item.resolvedRequestName = result.resolvedRequestName;
            item.resolvedRequestId = result.resolvedRequestId;
            item.parentRequestName = result.parentRequestName;
            item.parentRequestId = result.parentRequestId;
            item.depth = result.depth;
            item.adHoc = result.adHoc;
            item.targetResolutionForm = result.targetResolutionForm;
            item.qualifiedTargetPath = result.qualifiedTargetPath;
            item.candidateQualifiedPaths = result.candidateQualifiedPaths != null ? new ArrayList<>(result.candidateQualifiedPaths) : new ArrayList<>();
            item.runnerResult = result.runnerResult;
            copy.add(item);
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
        result.dependentExecution = result.dependentExecution
                || exec.dependentExecution;
        result.adHocExecution = result.adHocExecution
                || exec.adHocExecution;

        if (exec.parentRequestName != null
                && !exec.parentRequestName.isBlank()) {
            result.parentRequestName = exec.parentRequestName;
        }

        if (exec.parentRequestId != null
                && !exec.parentRequestId.isBlank()) {
            result.parentRequestId = exec.parentRequestId;
        }

        result.dependentDepth = Math.max(
                result.dependentDepth,
                exec.dependentDepth);

        result.triggeredByScript = result.triggeredByScript
                || exec.triggeredByScript
                || result.dependentExecution
                || result.adHocExecution;
    }

    static void copyExecutionResultMetadata(RunnerResult result, ExecutionResult exec) {
        if (result == null || exec == null) {
            return;
        }
        result.requestSent = exec.requestSent;
        if (exec.cancellationRequested
                || exec.preflightStatus == ExecutionPreflightStatus.CANCELLED) {
            result.cancellationState = exec.lateResponseIgnored
                    ? RunnerCancellationState.LATE_RESPONSE_IGNORED
                    : exec.requestSent
                    ? RunnerCancellationState.CANCELLED_DURING_HTTP_WAIT
                    : RunnerCancellationState.CANCELLED_BEFORE_SEND;
        }
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

    private int applyFlowControl(List<ApiCollection> collections,
                                 List<ApiRequest> ordered,
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
                FlowTargetResolution resolution = flowTargetResolver.resolve(
                        collections,
                        ordered,
                        result.scriptFlowNextRequestName,
                        null,
                        result.scriptFlowNextRequestId,
                        null,
                        null,
                        null);
                if (resolution == null || resolution.status == FlowTargetResolutionStatus.NOT_FOUND) {
                    fireOnDebug("Script requested next request \"" + safeFlowTarget(result) + "\" but no matching request was found.");
                    recordRunnerDiagnostic(result, DiagnosticSeverity.WARNING, "Next request target not found", "Flow Control: SET_NEXT_REQUEST");
                    yield -1;
                }
                if (resolution.status == FlowTargetResolutionStatus.AMBIGUOUS) {
                    result.targetResolutionForm = FlowTargetResolutionForm.NONE;
                    result.qualifiedTargetPath = null;
                    String details = resolution.safeMessage != null ? resolution.safeMessage : "Flow target is ambiguous.";
                    recordRunnerDiagnostic(result, DiagnosticSeverity.ERROR, "Next request target ambiguous", details);
                    if (termination != null) {
                        termination.stop(RunnerTerminationType.STOPPED_BY_SCRIPT,
                                "Runner stopped because setNextRequest target is ambiguous.",
                                result,
                                null,
                                "setNextRequest target ambiguous",
                                ScriptFlowControl.SET_NEXT_REQUEST,
                                null);
                    }
                    yield Integer.MIN_VALUE;
                }
                if (resolution.status == FlowTargetResolutionStatus.DISABLED) {
                    result.targetResolutionForm = FlowTargetResolutionForm.NONE;
                    result.qualifiedTargetPath = null;
                    String details = resolution.safeMessage != null ? resolution.safeMessage : "Flow target is disabled.";
                    recordRunnerDiagnostic(result, DiagnosticSeverity.ERROR, "Next request target disabled", details);
                    if (termination != null) {
                        termination.stop(RunnerTerminationType.STOPPED_BY_SCRIPT,
                                "Runner stopped because setNextRequest target is disabled.",
                                result,
                                null,
                                "setNextRequest target disabled",
                                ScriptFlowControl.SET_NEXT_REQUEST,
                                null);
                    }
                    yield Integer.MIN_VALUE;
                }
                result.targetResolutionForm = resolution.form != null ? resolution.form : FlowTargetResolutionForm.NONE;
                result.qualifiedTargetPath = resolution.qualifiedPath;
                recordRunnerDiagnostic(result, DiagnosticSeverity.INFO, "Next request selected by script", "Flow Control: SET_NEXT_REQUEST -> " + safeFlowTarget(result));
                yield resolution.orderedIndex;
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
        requestInFlight = false;
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

        private final List<ApiCollection> sourceCollections;
        private final Map<ApiRequest, ApiCollection> requestCollections;
        private final Map<String, ApiCollection> collectionsByName;
        private final Deque<String> requestStack = new ArrayDeque<>();
        private int dependentDepth = 0;
        private int dependentCount = 0;

        private RunnerDependentRequestExecutor(List<ApiCollection> sourceCollections,
                                               Map<ApiRequest, ApiCollection> requestCollections,
                                               Map<String, ApiCollection> collectionsByName) {
            this.sourceCollections = sourceCollections != null ? new ArrayList<>(sourceCollections) : new ArrayList<>();
            this.requestCollections = requestCollections != null ? requestCollections : new IdentityHashMap<>();
            this.collectionsByName = collectionsByName != null ? collectionsByName : new HashMap<>();
        }

        @Override
        public ScriptDependentRequestResult runRequest(ScriptExecutionContext context, String targetNameOrId) {
            if (context == null) {
                return ScriptDependentRequestResult.failure("runRequest is recognized but no script context is available.");
            }
            if (context.executionSource != burp.scripts.ExecutionSource.RUNNER) {
                return ScriptDependentRequestResult.ignored("runRequest is ignored outside Runner mode.");
            }
            if (cancelled || Thread.currentThread().isInterrupted()) {
                return ScriptDependentRequestResult.failure("Dependent request was not started because the Runner was cancelled.");
            }
            List<ApiCollection> allowedCollections = new ArrayList<>(sourceCollections);
            List<ApiRequest> allowedRequests = new ArrayList<>();
            Set<ApiRequest> seenRequests = Collections.newSetFromMap(new IdentityHashMap<>());
            for (ApiCollection collection : sourceCollections) {
                if (collection == null || collection.requests == null) {
                    continue;
                }
                for (ApiRequest request : collection.requests) {
                    if (request != null && seenRequests.add(request)) {
                        allowedRequests.add(request);
                    }
                }
            }
            for (ApiRequest request : requestCollections.keySet()) {
                if (request != null && seenRequests.add(request)) {
                    allowedRequests.add(request);
                }
            }
            FlowTargetResolution resolution = flowTargetResolver.resolve(allowedCollections, allowedRequests, targetNameOrId, null, null, null, null, null);
            if (resolution == null || resolution.status == FlowTargetResolutionStatus.NOT_FOUND) {
                String message = resolution != null && resolution.safeMessage != null ? resolution.safeMessage : "Flow target was not found: " + safeLabel(targetNameOrId) + ".";
                CollectionRunner.this.recordRunnerDiagnostic(null, DiagnosticSeverity.WARNING, message, message);
                return ScriptDependentRequestResult.failure(message);
            }
            if (resolution.status == FlowTargetResolutionStatus.AMBIGUOUS) {
                String message = resolution.safeMessage != null ? resolution.safeMessage : "Flow target is ambiguous: " + safeLabel(targetNameOrId) + ".";
                CollectionRunner.this.recordRunnerDiagnostic(null, DiagnosticSeverity.ERROR, message, String.join(", ", resolution.candidateQualifiedPaths));
                ScriptDependentRequestResult failure = ScriptDependentRequestResult.failure(message);
                failure.targetResolutionForm = FlowTargetResolutionForm.NONE.name();
                failure.candidateQualifiedPaths = new ArrayList<>(resolution.candidateQualifiedPaths);
                return failure;
            }
            if (resolution.status == FlowTargetResolutionStatus.DISABLED) {
                String message = resolution.safeMessage != null ? resolution.safeMessage : "Flow target is disabled: " + safeLabel(targetNameOrId) + ".";
                CollectionRunner.this.recordRunnerDiagnostic(null, DiagnosticSeverity.ERROR, message, message);
                ScriptDependentRequestResult failure = ScriptDependentRequestResult.failure(message);
                failure.targetResolutionForm = FlowTargetResolutionForm.NONE.name();
                failure.candidateQualifiedPaths = new ArrayList<>(resolution.candidateQualifiedPaths);
                return failure;
            }
            ApiRequest target = resolution.request;
            if (target == null) {
                String message = "Dependent request target was not found: " + safeLabel(targetNameOrId);
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
                            context.variableStore != null ? context.variableStore.effectiveVariablesSnapshot() : null,
                            resolution);
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
                child.targetResolutionForm = resolution.form;
                child.qualifiedTargetPath = resolution.qualifiedPath;
                results.add(child);
                fireOnRequestComplete(child);
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
                dependentResult.targetResolutionForm = child.targetResolutionForm != null ? child.targetResolutionForm.name() : resolution.form.name();
                dependentResult.qualifiedTargetPath = child.qualifiedTargetPath;
                dependentResult.candidateQualifiedPaths = new ArrayList<>(resolution.candidateQualifiedPaths);
                dependentResult.runnerResult = child;
                return dependentResult;
            } finally {
                if (!requestStack.isEmpty()) {
                    String top = requestStack.peek();
                    if (Objects.equals(top, stackKey)) {
                        requestStack.pop();
                    } else {
                        requestStack.removeFirstOccurrence(stackKey);
                    }
                }
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
            if (cancelled || Thread.currentThread().isInterrupted()) {
                return ScriptDependentRequestResult.failure("Dependent request was not started because the Runner was cancelled.");
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
            String stackKey = "ad-hoc:" + dependentCount;
            requestStack.push(stackKey);
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
                dependentResult.targetResolutionForm = child.targetResolutionForm != null ? child.targetResolutionForm.name() : FlowTargetResolutionForm.NONE.name();
                dependentResult.qualifiedTargetPath = child.qualifiedTargetPath;
                dependentResult.runnerResult = child;
                return dependentResult;
            } finally {
                if (!requestStack.isEmpty()) {
                    String top = requestStack.peek();
                    if (Objects.equals(top, stackKey)) {
                        requestStack.pop();
                    } else {
                        requestStack.removeFirstOccurrence(stackKey);
                    }
                }
                dependentDepth = Math.max(0, dependentDepth - 1);
            }
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

    private RunnerResult newAttemptResult(ApiRequest request,
                                          ApiCollection collection,
                                          boolean dependentExecution,
                                          boolean adHocExecution,
                                          String parentRequestName,
                                          String parentRequestId,
                                          int dependentDepth,
                                          int attemptNumber,
                                          int totalAttempts,
                                          FlowTargetResolution targetResolution) {
        RunnerResult result = new RunnerResult();
        result.requestName = request != null ? request.name : null;
        result.requestId = request != null ? request.id : null;
        result.collectionId = collection != null ? collection.ensureId() : null;
        result.collectionName = collection != null && collection.name != null ? collection.name : (request != null ? request.sourceCollection : null);
        result.folderPath = collection != null ? RequestPathResolver.getRequestFolderPath(collection, request) : (request != null ? request.path : null);
        result.method = request != null && request.method != null ? request.method.toUpperCase(Locale.ROOT) : "GET";
        result.buildMode = request != null ? request.resolveBuildMode() : null;
        result.requestUrl = request != null ? request.url : null;
        result.initialResolvedUrl = request != null ? request.url : null;
        result.finalResolvedUrl = request != null ? request.url : null;
        result.dependentExecution = dependentExecution;
        result.adHocExecution = adHocExecution;
        result.parentRequestName = parentRequestName;
        result.parentRequestId = parentRequestId;
        result.dependentDepth = dependentDepth;
        result.triggeredByScript = dependentExecution || adHocExecution;
        result.attemptNumber = Math.max(1, attemptNumber);
        result.totalAttempts = Math.max(1, totalAttempts);
        result.retryDecision = "NO_RETRY";
        result.retryReason = "Retry disabled: unconfigured failure type.";
        result.retryDelayMillis = 0;
        result.retryFailureType = null;
        result.requestMayHaveBeenProcessed = false;
        result.targetResolutionForm = FlowTargetResolutionForm.NONE;
        result.qualifiedTargetPath = null;
        result.cancellationState = RunnerCancellationState.NOT_CANCELLED;
        if (targetResolution != null
                && targetResolution.status == FlowTargetResolutionStatus.RESOLVED) {
            result.targetResolutionForm = targetResolution.form != null
                    ? targetResolution.form
                    : FlowTargetResolutionForm.NONE;
            result.qualifiedTargetPath = targetResolution.qualifiedPath;
        }
        return result;
    }

    private void finalizeSuccessfulAttempt(RunnerResult result, int attemptNumber, int totalAttempts) {
        if (result == null) {
            return;
        }
        result.attemptNumber = Math.max(1, attemptNumber);
        result.totalAttempts = Math.max(1, totalAttempts);
        result.retryDecision = "NO_RETRY";
        result.retryReason = "Request completed.";
        result.retryDelayMillis = 0;
        result.retryFailureType = null;
        result.requestMayHaveBeenProcessed = false;
        result.cancellationState = result.cancellationState != null ? result.cancellationState : RunnerCancellationState.NOT_CANCELLED;
    }

    private RunnerRetryDecision finalizeRetryMetadata(RunnerResult result,
                                                      RetryFailureType failureType,
                                                      RunnerRetryPolicy policySnapshot,
                                                      int attemptNumber,
                                                      int totalAttempts) {
        if (result == null) {
            return new RunnerRetryDecision(false, false, "Retry disabled: unconfigured failure type.", 0, false, Math.max(1, totalAttempts));
        }
        RunnerRetryDecision decision = policySnapshot != null
                ? policySnapshot.evaluate(result.method, failureType, result.requestSent, result.statusCode > 0 ? result.statusCode : null, attemptNumber)
                : new RunnerRetryDecision(false, false, "Retry disabled: unconfigured failure type.", 0, false, Math.max(1, totalAttempts));
        result.attemptNumber = Math.max(1, attemptNumber);
        result.totalAttempts = Math.max(1, decision.maximumAttempts());
        result.retryDecision = decision.retry() ? "RETRY" : "NO_RETRY";
        result.retryReason = decision.reason();
        result.retryDelayMillis = decision.delayMillis();
        result.retryFailureType = failureType;
        result.requestMayHaveBeenProcessed = decision.requestMayHaveBeenProcessed();
        if (result.cancellationState == null) {
            result.cancellationState = RunnerCancellationState.NOT_CANCELLED;
        }
        return decision;
    }

    private AttemptDisposition finalizeAndPublishAttempt(
            ApiRequest request,
            ApiCollection collection,
            RunnerResult result,
            RetryFailureType failureType,
            RunnerRetryPolicy policySnapshot,
            int attemptNumber,
            int maximumAttempts) {
        RunnerRetryDecision decision = finalizeRetryMetadata(
                result,
                failureType,
                policySnapshot,
                attemptNumber,
                maximumAttempts);

        if (isRunnerCancellationRequested(result)) {
            RunnerCancellationState state =
                    result != null
                            && result.cancellationState != null
                            && result.cancellationState
                                    != RunnerCancellationState.NOT_CANCELLED
                            ? result.cancellationState
                            : decision.retry()
                            ? RunnerCancellationState.CANCELLED_BEFORE_SEND
                            : result != null && result.requestSent
                            ? RunnerCancellationState.CANCELLED_DURING_HTTP_WAIT
                            : RunnerCancellationState.CANCELLED_BEFORE_SEND;

            markCancelledAttempt(
                    result,
                    state,
                    policySnapshot,
                    attemptNumber,
                    maximumAttempts);
            publishAttempt(request, collection, result);
            return AttemptDisposition.CANCELLED;
        }

        if (!decision.retry()
                || attemptNumber >= maximumAttempts) {
            publishAttempt(request, collection, result);
            return AttemptDisposition.COMPLETE;
        }

        fireOnDebug(
                "Retrying in "
                        + decision.delayMillis()
                        + "ms");

        try {
            if (!waitForRetryDelay(decision.delayMillis())) {
                markCancelledAttempt(
                        result,
                        RunnerCancellationState.CANCELLED_BEFORE_SEND,
                        policySnapshot,
                        attemptNumber,
                        maximumAttempts);
                publishAttempt(request, collection, result);
                return AttemptDisposition.CANCELLED;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            markCancelledAttempt(
                    result,
                    RunnerCancellationState.CANCELLED_BEFORE_SEND,
                    policySnapshot,
                    attemptNumber,
                    maximumAttempts);
            publishAttempt(request, collection, result);
            return AttemptDisposition.CANCELLED;
        }

        publishAttempt(request, collection, result);
        return AttemptDisposition.RETRY;
    }

    private boolean isRunnerCancellationRequested(
            RunnerResult result) {
        return cancelled
                || Thread.currentThread().isInterrupted()
                || result != null
                && (result.preflightStatus
                            == ExecutionPreflightStatus.CANCELLED
                        || result.cancellationState != null
                        && result.cancellationState
                            != RunnerCancellationState.NOT_CANCELLED);
    }

    private boolean isRunnerCancellationResult(
            ExecutionResult execution,
            RunnerResult result) {
        return execution != null
                && (execution.cancellationRequested
                        || execution.preflightStatus
                            == ExecutionPreflightStatus.CANCELLED)
                || result != null
                && (result.preflightStatus
                            == ExecutionPreflightStatus.CANCELLED
                        || result.cancellationState != null
                        && result.cancellationState
                            != RunnerCancellationState.NOT_CANCELLED);
    }

    private RunnerCancellationState cancellationStateFor(
            ExecutionResult execution,
            RunnerResult result) {
        if (result != null
                && result.cancellationState != null
                && result.cancellationState
                    != RunnerCancellationState.NOT_CANCELLED) {
            return result.cancellationState;
        }

        if (execution != null && execution.lateResponseIgnored) {
            return RunnerCancellationState.LATE_RESPONSE_IGNORED;
        }

        boolean requestWasSent =
                execution != null && execution.requestSent
                        || result != null && result.requestSent;

        return requestWasSent
                ? RunnerCancellationState.CANCELLED_DURING_HTTP_WAIT
                : RunnerCancellationState.CANCELLED_BEFORE_SEND;
    }

    private void markCancelledAttempt(
            RunnerResult result,
            RunnerCancellationState cancellationState,
            RunnerRetryPolicy policySnapshot,
            int attemptNumber,
            int maximumAttempts) {
        if (result == null) {
            return;
        }

        result.cancellationState =
                cancellationState != null
                        ? cancellationState
                        : result.requestSent
                        ? RunnerCancellationState.CANCELLED_DURING_HTTP_WAIT
                        : RunnerCancellationState.CANCELLED_BEFORE_SEND;
        result.success = false;
        result.preflightStatus = ExecutionPreflightStatus.CANCELLED;
        result.preflightMessage = "Runner execution cancelled.";
        result.errorMessage = "Runner execution cancelled.";

        finalizeRetryMetadata(
                result,
                RetryFailureType.CANCELLED,
                policySnapshot,
                attemptNumber,
                maximumAttempts);
    }

    private RetryFailureType classifyRetryFailure(
            ExecutionResult execution,
            RunnerResult attemptResult,
            RunnerRetryPolicy policySnapshot) {
        Set<Integer> retryableStatusCodes =
                policySnapshot != null
                        && policySnapshot.retryableStatusCodes != null
                        ? policySnapshot.retryableStatusCodes
                        : Collections.emptySet();
        if (attemptResult != null && attemptResult.cancellationState != null && attemptResult.cancellationState != RunnerCancellationState.NOT_CANCELLED) {
            return RetryFailureType.CANCELLED;
        }
        if (execution != null && execution.preflightStatus == ExecutionPreflightStatus.CANCELLED) {
            return RetryFailureType.CANCELLED;
        }
        if (execution != null) {
            if (execution.preflightStatus == ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR
                    || execution.preflightStatus == ExecutionPreflightStatus.BLOCKED_SCRIPT_TIMEOUT
                    || (execution.scriptErrors != null && !execution.scriptErrors.isEmpty())) {
                return RetryFailureType.SCRIPT_FAILURE;
            }
            if ((execution.scriptFlowControl == ScriptFlowControl.STOP_RUN
                    || execution.scriptFlowControl == ScriptFlowControl.SKIP_REQUEST)
                    && execution.response == null) {
                return RetryFailureType.PREFLIGHT_BLOCK;
            }
            if (execution.isBlockedBeforeSend()) {
                return RetryFailureType.PREFLIGHT_BLOCK;
            }
            if (execution.responseTimedOut) {
                return RetryFailureType.RESPONSE_TIMEOUT;
            }
            if (execution.response != null && execution.response.response() != null) {
                int status = execution.response.response().statusCode();
                if (retryableStatusCodes.contains(status)) {
                    return RetryFailureType.HTTP_STATUS;
                }
            }
            if (execution.requestSent && execution.response == null) {
                return RetryFailureType.CONNECTION_FAILURE;
            }
        }
        if (attemptResult != null && attemptResult.requestSent) {
            if (attemptResult.responseTimedOut) {
                return RetryFailureType.RESPONSE_TIMEOUT;
            }
            if (attemptResult.statusCode > 0
                    && retryableStatusCodes.contains(
                            attemptResult.statusCode)) {
                return RetryFailureType.HTTP_STATUS;
            }
            if (attemptResult.responseBody == null && attemptResult.responseHeaders == null && attemptResult.statusCode <= 0) {
                return RetryFailureType.CONNECTION_FAILURE;
            }
        }
        if (attemptResult != null && attemptResult.preflightStatus != null) {
            if (attemptResult.preflightStatus == ExecutionPreflightStatus.CANCELLED) {
                return RetryFailureType.CANCELLED;
            }
            if (attemptResult.preflightStatus == ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR
                    || attemptResult.preflightStatus == ExecutionPreflightStatus.BLOCKED_SCRIPT_TIMEOUT) {
                return RetryFailureType.SCRIPT_FAILURE;
            }
            if (attemptResult.preflightStatus == ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE
                    || attemptResult.preflightStatus == ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES
                    || attemptResult.preflightStatus == ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE
                    || attemptResult.preflightStatus == ExecutionPreflightStatus.BLOCKED_POLICY) {
                return RetryFailureType.PREFLIGHT_BLOCK;
            }
        }
        return null;
    }

    private void publishAttempt(ApiRequest request, ApiCollection collection, RunnerResult result) {
        RunnerResult snapshot = snapshotAttemptResult(result, null, result != null ? result.attemptNumber : 1, result != null ? result.totalAttempts : 1);
        fireOnAttemptComplete(snapshot);
        fireOnTimeline(buildTimelineRow(request, collection, result, result != null ? result.attemptNumber : 1, result != null ? result.totalAttempts : 1));
    }

    private boolean waitForRetryDelay(int delayMillis) throws InterruptedException {
        int remaining = Math.max(0, delayMillis);
        if (remaining <= 0) {
            return !(cancelled || Thread.currentThread().isInterrupted());
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(remaining);
        while (remaining > 0) {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                return false;
            }
            int slice = Math.min(remaining, 250);
            Thread.sleep(slice);
            remaining = (int) java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        }
        return !(cancelled || Thread.currentThread().isInterrupted());
    }

    private RunnerResult snapshotAttemptResult(RunnerResult source,
                                               ExecutionResult exec,
                                               int attemptNumber,
                                               int totalAttempts) {
        RunnerResult snapshot = new RunnerResult();
        if (source != null) {
            snapshot.requestName = source.requestName;
            snapshot.requestId = source.requestId;
            snapshot.collectionId = source.collectionId;
            snapshot.collectionName = source.collectionName;
            snapshot.folderPath = source.folderPath;
            snapshot.host = source.host;
            snapshot.path = source.path;
            snapshot.method = source.method;
            snapshot.buildMode = source.buildMode;
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
            snapshot.retryDecision = source.retryDecision;
            snapshot.retryReason = source.retryReason;
            snapshot.retryDelayMillis = source.retryDelayMillis;
            snapshot.retryFailureType = source.retryFailureType;
            snapshot.requestMayHaveBeenProcessed = source.requestMayHaveBeenProcessed;
            snapshot.targetResolutionForm = source.targetResolutionForm;
            snapshot.qualifiedTargetPath = source.qualifiedTargetPath;
            snapshot.cancellationState = source.cancellationState;
            snapshot.parentRequestName = source.parentRequestName;
            snapshot.parentRequestId = source.parentRequestId;
            snapshot.dependentExecution = source.dependentExecution;
            snapshot.adHocExecution = source.adHocExecution;
            snapshot.dependentDepth = source.dependentDepth;
            snapshot.triggeredByScript = source.triggeredByScript;
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
            snapshot.scriptDependentRequestResults = copyDependentRequestResults(source.scriptDependentRequestResults);
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
            if (exec.scriptDependentRequestResults != null && !exec.scriptDependentRequestResults.isEmpty()) {
                snapshot.scriptDependentRequestResults = copyDependentRequestResults(exec.scriptDependentRequestResults);
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

    private RunnerTimelineRow buildTimelineRow(ApiRequest req, ApiCollection col, RunnerResult result, int attemptNumber, int totalAttempts) {
        RunnerTimelineRow row = new RunnerTimelineRow();
        row.order = req != null ? req.sequenceOrder : 0;
        row.collectionName = col != null && col.name != null ? col.name : (req != null ? req.sourceCollection : "");
        row.requestName = req != null ? req.name : "";
        row.status = timelineStatusLabel(result);
        row.timeMs = result != null ? result.responseTimeMs : 0L;
        row.attemptNumber = Math.max(1, attemptNumber);
        row.totalAttempts = Math.max(1, totalAttempts);
        row.retries = Math.max(0, row.attemptNumber - 1);
        row.varsChanged = result != null && result.extractedVariables != null ? result.extractedVariables.size() : 0;
        row.assertions = formatAssertionSummary(result);
        row.executionKind = executionKind(result);
        row.retryReason = result != null ? result.retryReason : null;
        row.cancellationState = result != null && result.cancellationState != null ? result.cancellationState.name() : RunnerCancellationState.NOT_CANCELLED.name();
        row.requestMayHaveBeenProcessed = result != null && result.requestMayHaveBeenProcessed;
        return row;
    }

    private String timelineStatusLabel(RunnerResult result) {
        if (result == null) {
            return "";
        }
        String label = result.displayStatusLabel();
        if (label.endsWith(" (dependent)")) {
            return label.substring(0, label.length() - " (dependent)".length());
        }
        if (label.endsWith(" (ad hoc)")) {
            return label.substring(0, label.length() - " (ad hoc)".length());
        }
        return label;
    }

    private String executionKind(RunnerResult result) {
        if (result == null) {
            return "QUEUED";
        }
        if (result.cancellationState != null && result.cancellationState != RunnerCancellationState.NOT_CANCELLED) {
            return "CANCELLED";
        }
        if (result.preflightStatus == ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR
                || result.preflightStatus == ExecutionPreflightStatus.BLOCKED_SCRIPT_TIMEOUT
                || result.preflightStatus == ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE
                || result.preflightStatus == ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES
                || result.preflightStatus == ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE
                || result.preflightStatus == ExecutionPreflightStatus.BLOCKED_POLICY) {
            return "PREFLIGHT_BLOCKED";
        }
        if (result.responseTimedOut) {
            return "TIMED_OUT";
        }
        if (result.adHocExecution) {
            return "AD_HOC";
        }
        if (result.dependentExecution) {
            return "DEPENDENT";
        }
        if (result.attemptNumber > 1) {
            return "RETRY";
        }
        return "QUEUED";
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
        requestInFlight = false;
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

    private enum AttemptDisposition {
        COMPLETE,
        RETRY,
        CANCELLED
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
