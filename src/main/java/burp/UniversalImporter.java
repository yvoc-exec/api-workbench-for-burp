package burp;

import burp.models.*;
import burp.parser.*;
import burp.ui.ImporterPanel;
import burp.auth.OAuth2Manager;
import burp.utils.*;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.RedirectionMode;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.HighlightColor;
import burp.ui.history.HistoryNativeHttpMessageFactory;
import burp.payload.ExactPayloadMigrationService;
import burp.payload.FileManagedPayloadStore;
import burp.payload.ManagedPayloadStore;
import burp.payload.ManagedPayloadStorePaths;
import burp.payload.WorkspacePayloadReferenceCollector;

import javax.swing.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core importer logic. Handles parsing, variable resolution, and sending to Burp tools.
 */
public class UniversalImporter {
    private static final long WORKSPACE_SHUTDOWN_TIMEOUT_SECONDS = 10L;
    static final String WORKSPACE_SAVE_THREAD_NAME_PREFIX = "awb-workspace-save";

    private final MontoyaApi api;
    private final VariableResolver resolver;
    private final RequestBuilder requestBuilder;
    private final SharedRequestPipeline pipeline;
    private final Set<String> existingTabs = ConcurrentHashMap.newKeySet();
    private final ImporterPanel ui;
    private final WorkspaceStateService workspaceStateService;
    private final DebouncedSwingAction debouncedWorkspaceSave;
    private final WorkspaceSaveCoordinator workspaceSaveCoordinator;
    private final ManagedPayloadStore payloadStore;
    private final AtomicLong workspaceRevisionCounter = new AtomicLong();
    private final AtomicBoolean workspaceSaveClosed = new AtomicBoolean();
    private final AtomicBoolean cleanupStarted = new AtomicBoolean();
    private volatile long latestRequestedWorkspaceRevision;
    private volatile long lastSuccessfulWorkspaceRevision;
    private volatile String lastSavedWorkspaceSha256;
    private volatile long lastSavedWorkspaceLength = -1L;
    private boolean followRedirects = true;
    private boolean debugRawRequest = false;

    public UniversalImporter(MontoyaApi api, ScriptMode scriptMode) {
        this(api, scriptMode, api != null ? new WorkspaceStateService(api) : null);
    }

    public UniversalImporter(MontoyaApi api, ScriptMode scriptMode, WorkspaceStateService workspaceStateService) {
        this(api, scriptMode, workspaceStateService, createPayloadStore(api));
    }

    public UniversalImporter(MontoyaApi api, ScriptMode scriptMode,
                             WorkspaceStateService workspaceStateService,
                             ManagedPayloadStore payloadStore) {
        this.api = api;
        this.workspaceStateService = workspaceStateService;
        this.payloadStore = payloadStore;
        this.resolver = new VariableResolver();
        OAuth2Manager oauth2Manager = new OAuth2Manager(api);
        this.requestBuilder = new RequestBuilder(api, oauth2Manager, payloadStore);
        ScriptEngine scriptEngine = new ScriptEngine(api, scriptMode);
        this.pipeline = createSharedRequestPipeline(api, requestBuilder, scriptEngine, oauth2Manager);
        burp.runner.CollectionRunner runner = createCollectionRunner(api, pipeline, oauth2Manager);
        this.ui = new ImporterPanel(this, runner, oauth2Manager, scriptMode);
        this.workspaceSaveCoordinator = workspaceStateService != null
                ? new WorkspaceSaveCoordinator(
                        WORKSPACE_SAVE_THREAD_NAME_PREFIX,
                        this::persistDetachedWorkspaceState)
                : null;
        this.debouncedWorkspaceSave = new DebouncedSwingAction(3000, this::scheduleWorkspaceStateSave);
        this.ui.setWorkspaceChangeListener(this::requestWorkspaceStateSave);
    }

    protected SharedRequestPipeline createSharedRequestPipeline(MontoyaApi api,
                                                                RequestBuilder requestBuilder,
                                                                ScriptEngine scriptEngine,
                                                                OAuth2Manager oauth2Manager) {
        return new SharedRequestPipeline(api, requestBuilder, scriptEngine, oauth2Manager);
    }

    protected burp.runner.CollectionRunner createCollectionRunner(MontoyaApi api,
                                                                    SharedRequestPipeline pipeline,
                                                                    OAuth2Manager oauth2Manager) {
        return new burp.runner.CollectionRunner(api, pipeline, oauth2Manager);
    }

    public JPanel getMainPanel() {
        return ui.getPanel();
    }

    public ImporterPanel getUI() {
        return ui;
    }

    public MontoyaApi getApi() {
        return api;
    }

    public ManagedPayloadStore getPayloadStore() {
        return payloadStore;
    }

    private static ManagedPayloadStore createPayloadStore(MontoyaApi api) {
        if (api == null) return null;
        try {
            return new FileManagedPayloadStore(ManagedPayloadStorePaths.forProject(api));
        } catch (IOException failure) {
            throw new IllegalStateException("Managed payload storage could not be initialized.", failure);
        }
    }

    public void setFollowRedirects(boolean followRedirects) {
        this.followRedirects = followRedirects;
    }

    public boolean isFollowRedirects() {
        return followRedirects;
    }

    public void setDebugRawRequest(boolean debugRawRequest) {
        this.debugRawRequest = debugRawRequest;
    }

    public boolean isDebugRawRequest() {
        return debugRawRequest;
    }

    /** Legacy single-collection import (kept for compatibility). */
    public void importRequests(ApiCollection collection, List<ApiRequest> selectedRequests,
                               File environmentFile, List<String> destinations, int delayMs,
                               LogCallback logCallback, ResultCallback resultCallback) {
        importRequests(collection, selectedRequests, environmentFile, destinations, delayMs,
                       Collections.emptyMap(), logCallback, resultCallback);
    }

    /** Legacy single-collection import (kept for compatibility). */
    public void importRequests(ApiCollection collection, List<ApiRequest> selectedRequests,
                               File environmentFile, List<String> destinations, int delayMs,
                               Map<String, String> initialVars,
                               LogCallback logCallback, ResultCallback resultCallback) {
        // Bind env file into collection runtime vars for scoped behavior
        if (environmentFile != null) {
            EnvLoadResult result = loadEnvFileIntoMap(environmentFile, collection.runtimeVars);
            if (!result.isSuccess() && ui != null) {
                ui.appendImportLog("Env bind warning: " + result.errorMessage);
            }
        }
        if (initialVars != null && !initialVars.isEmpty()) {
            collection.putAllRuntimeVars(initialVars);
        }
        List<QueuedRequest> queue = new ArrayList<>();
        for (ApiRequest req : selectedRequests) {
            queue.add(new QueuedRequest(collection, req));
        }
        importRequestsSequential(queue, destinations, delayMs, logCallback, resultCallback);
    }

    /**
     * Deterministic sequential import across multiple collections.
     * Each request resolves against its own source collection context.
     */
    public void importRequestsSequential(List<QueuedRequest> queue, List<String> destinations,
                                         int delayMs, LogCallback logCallback, ResultCallback resultCallback) {
        importRequestsSequential(queue, destinations, delayMs, null, null, null, null, logCallback, resultCallback);
    }

    public void importRequestsSequential(List<QueuedRequest> queue, List<String> destinations,
                                         int delayMs,
                                         Map<String, String> runtimeOverlay,
                                         SharedRequestPipeline.OAuth2TokenSink oauth2TokenSink,
                                         SharedRequestPipeline.RuntimeVariableSink runtimeVariableSink,
                                         EnvironmentProfile activeEnvironment,
                                         LogCallback logCallback,
                                         ResultCallback resultCallback) {
        SwingWorker<ImportResult, String> worker = new SwingWorker<>() {
            @Override
            protected ImportResult doInBackground() throws Exception {
                ImportResult result = new ImportResult();
                result.totalRequests = queue.size();

                try {
                    publish("Processing " + queue.size() + " requests sequentially...");
                    for (int i = 0; i < queue.size(); i++) {
                        if (isCancelled()) break;
                        QueuedRequest qr = queue.get(i);
                        try {
                            resolver.clear();
                            Map<String, String> colSources = seedResolverForCollection(qr.collection);
                            for (String destination : destinations) {
                                processRequest(qr.collection, qr.request, destination, delayMs, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, logCallback, colSources);
                            }
                            result.successCount++;
                            publish("[OK] " + qr.request.name);
                        } catch (Exception e) {
                            result.failedRequestDetails.add(new ImportResult.FailedRequestInfo(
                                    qr.request.name, qr.request.path, e.getMessage(), qr.request));
                            result.failedRequests.add(qr.request.name + ": " + e.getMessage());
                            publish("[FAIL] " + qr.request.name + " - " + e.getMessage());
                        }
                        setProgress((i + 1) * 100 / queue.size());
                    }
                } catch (Exception e) {
                    result.error = e.getMessage();
                    publish("Fatal error: " + e.getMessage());
                }
                return result;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) logCallback.log(msg);
            }

            @Override
            protected void done() {
                try {
                    ImportResult result = get();
                    resultCallback.onResult(result);
                } catch (Exception e) {
                    logCallback.log("Import failed: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    /**
     * Direct-send a single request from the Workbench editor, using its
     * source collection context for variable resolution.
     */
    public burp.api.montoya.http.message.HttpRequestResponse sendSingleRequest(
            ApiRequest req, ApiCollection colContext, boolean followRedirects) throws Exception {
        SingleSendResult result = sendSingleRequestWithBuiltRequest(req, colContext, followRedirects);
        return result.response;
    }

    /**
     * Send a single request and also return the built HttpRequest (for Repeater duplication).
     * Performs exactly one live HTTP send.
     */
    public SingleSendResult sendSingleRequestWithBuiltRequest(
            ApiRequest req, ApiCollection colContext, boolean followRedirects) throws Exception {
        return sendSingleRequestWithBuiltRequest(req, colContext, followRedirects, null, null, null, null);
    }

    public SingleSendResult sendSingleRequestWithBuiltRequest(
            ApiRequest req,
            ApiCollection colContext,
            boolean followRedirects,
            Map<String, String> runtimeOverlay,
            SharedRequestPipeline.OAuth2TokenSink oauth2TokenSink) throws Exception {
        return sendSingleRequestWithBuiltRequest(req, colContext, followRedirects, runtimeOverlay, oauth2TokenSink, null, null);
    }

    public SingleSendResult sendSingleRequestWithBuiltRequest(
            ApiRequest req,
            ApiCollection colContext,
            boolean followRedirects,
            Map<String, String> runtimeOverlay,
            SharedRequestPipeline.OAuth2TokenSink oauth2TokenSink,
            SharedRequestPipeline.RuntimeVariableSink runtimeVariableSink) throws Exception {
        return sendSingleRequestWithBuiltRequest(req, colContext, followRedirects, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, null);
    }

    public SingleSendResult sendSingleRequestWithBuiltRequest(
            ApiRequest req,
            ApiCollection colContext,
            boolean followRedirects,
            Map<String, String> runtimeOverlay,
            SharedRequestPipeline.OAuth2TokenSink oauth2TokenSink,
            SharedRequestPipeline.RuntimeVariableSink runtimeVariableSink,
            EnvironmentProfile activeEnvironment) throws Exception {
        return sendSingleRequestWithBuiltRequest(
                req,
                colContext,
                followRedirects,
                runtimeOverlay,
                oauth2TokenSink,
                runtimeVariableSink,
                activeEnvironment,
                burp.scripts.ExecutionSource.WORKBENCH_SEND,
                RedirectPolicy.defaults());
    }

    public SingleSendResult sendSingleRequestWithBuiltRequest(
            ApiRequest req,
            ApiCollection colContext,
            boolean followRedirects,
            Map<String, String> runtimeOverlay,
            SharedRequestPipeline.OAuth2TokenSink oauth2TokenSink,
            SharedRequestPipeline.RuntimeVariableSink runtimeVariableSink,
            EnvironmentProfile activeEnvironment,
            burp.scripts.ExecutionSource executionSource,
            RedirectPolicy redirectPolicy) throws Exception {
        return sendSingleRequestWithBuiltRequest(
                req,
                colContext,
                followRedirects,
                runtimeOverlay,
                oauth2TokenSink,
                runtimeVariableSink,
                activeEnvironment,
                executionSource,
                redirectPolicy,
                ExecutionPolicy.workbenchDefaults(),
                null);
    }

    public SingleSendResult sendSingleRequestWithBuiltRequest(
            ApiRequest req,
            ApiCollection colContext,
            boolean followRedirects,
            Map<String, String> runtimeOverlay,
            SharedRequestPipeline.OAuth2TokenSink oauth2TokenSink,
            SharedRequestPipeline.RuntimeVariableSink runtimeVariableSink,
            EnvironmentProfile activeEnvironment,
            burp.scripts.ExecutionSource executionSource,
            RedirectPolicy redirectPolicy,
            ExecutionPolicy executionPolicy,
            PreflightDecisionHandler preflightDecisionHandler) throws Exception {
        ExecutionResult exec = pipeline.execute(req, colContext, followRedirects, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, null, redirectPolicy, executionPolicy, preflightDecisionHandler);
        SingleSendResult result = new SingleSendResult(
            exec.response,
            exec.builtRequest,
            exec.rawRequestText != null
                    ? exec.rawRequestText
                    : (exec.rawRequestBytes != null
                        ? new String(exec.rawRequestBytes, java.nio.charset.StandardCharsets.UTF_8)
                        : exec.requestHeaders),
            exec.resolvedUrl,
            exec.elapsedMs,
            exec.errorMessage,
            exec
        );
        if (!exec.success) {
            String message = exec.errorMessage != null
                    ? exec.errorMessage
                    : (exec.preflightMessage != null ? exec.preflightMessage : "Request failed");
            throw new RequestExecutionException(message, exec, result);
        }
        return result;
    }

    public SingleSendResult sendSingleRequestWithBuiltRequest(
            ApiRequest req,
            ApiCollection colContext,
            boolean followRedirects,
            Map<String, String> runtimeOverlay,
            SharedRequestPipeline.OAuth2TokenSink oauth2TokenSink,
            SharedRequestPipeline.RuntimeVariableSink runtimeVariableSink,
            EnvironmentProfile activeEnvironment,
            burp.scripts.ExecutionSource executionSource) throws Exception {
        return sendSingleRequestWithBuiltRequest(req, colContext, followRedirects, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, RedirectPolicy.defaults());
    }

    public static class SingleSendResult {
        public final burp.api.montoya.http.message.HttpRequestResponse response;
        public final HttpRequest builtRequest;
        public final String rawRequestText;
        public final String resolvedUrl;
        public final long elapsedMs;
        public final String errorMessage;
        public final ExecutionResult executionResult;

        public SingleSendResult(burp.api.montoya.http.message.HttpRequestResponse response, HttpRequest builtRequest) {
            this(response, builtRequest, null, null, 0L, null, null);
        }

        public SingleSendResult(burp.api.montoya.http.message.HttpRequestResponse response, HttpRequest builtRequest,
                                String rawRequestText, String resolvedUrl, long elapsedMs, String errorMessage) {
            this(response, builtRequest, rawRequestText, resolvedUrl, elapsedMs, errorMessage, null);
        }

        public SingleSendResult(burp.api.montoya.http.message.HttpRequestResponse response, HttpRequest builtRequest,
                                String rawRequestText, String resolvedUrl, long elapsedMs, String errorMessage,
                                ExecutionResult executionResult) {
            this.response = response;
            this.builtRequest = builtRequest;
            this.rawRequestText = rawRequestText;
            this.resolvedUrl = resolvedUrl;
            this.elapsedMs = elapsedMs;
            this.errorMessage = errorMessage;
            this.executionResult = executionResult;
        }
    }

    public void sendToRepeater(HttpRequest request, String tabName) {
        api.repeater().sendToRepeater(request, tabName);
    }

    public String generateRepeaterTabName(String baseName, String collectionName) {
        return generateUniqueTabName(baseName, collectionName);
    }

    private Map<String, String> buildSourceMap(ApiRequest req, Map<String, String> resolvedVars, Map<String, String> colSources) {
        Map<String, String> sources = new LinkedHashMap<>();
        if (colSources != null) sources.putAll(colSources);
        if (resolvedVars == null || resolvedVars.isEmpty()) {
            return sources;
        }
        for (String key : resolvedVars.keySet()) {
            if (req.variables != null && req.variables.stream().anyMatch(v -> v.key.equals(key) && v.value != null)) {
                sources.put(key, "request-level");
            } else {
                sources.putIfAbsent(key, "resolved");
            }
        }
        return sources;
    }

    private Map<String, String> seedResolverForCollection(ApiCollection collection) {
        Map<String, String> sources = new LinkedHashMap<>();
        if (collection == null) return sources;
        // Precedence (lowest -> highest): environment -> variables -> runtimeOAuth2 -> runtimeVars
        resolver.addEnvironmentVariables(collection);
        if (collection.environment != null) {
            for (String key : collection.environment.keySet()) sources.put(key, "collection-env");
        }
        resolver.addCollectionVariables(collection);
        for (ApiRequest.Variable v : collection.variables) {
            if (v.value != null) sources.put(v.key, "collection-var");
        }
        if (collection.runtimeOAuth2 != null) {
            resolver.addAll(collection.runtimeOAuth2);
            for (String key : collection.runtimeOAuth2.keySet()) sources.put(key, "scoped-oauth2");
        }
        if (collection.runtimeVars != null) {
            resolver.addAll(collection.runtimeVars);
            for (String key : collection.runtimeVars.keySet()) sources.put(key, "scoped-runtime");
        }
        return sources;
    }

    public static class EnvLoadResult {
        public final int loadedCount;
        public final String errorMessage;
        public EnvLoadResult(int loadedCount, String errorMessage) {
            this.loadedCount = loadedCount;
            this.errorMessage = errorMessage;
        }
        public boolean isSuccess() { return errorMessage == null; }
    }

    public static EnvLoadResult loadEnvFileIntoMap(File environmentFile, Map<String, String> target) {
        if (environmentFile == null || target == null) {
            return new EnvLoadResult(0, "Null file or target map");
        }
        try {
            List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(environmentFile);
            int count = 0;
            for (EnvironmentProfile profile : profiles) {
                if (profile == null || profile.variables == null) {
                    continue;
                }
                for (Map.Entry<String, String> entry : profile.variables.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank()) {
                        continue;
                    }
                    target.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
                    count++;
                }
            }
            return new EnvLoadResult(count, null);
        } catch (Exception e) {
            return new EnvLoadResult(0, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    public static class QueuedRequest {
        public final ApiCollection collection;
        public final ApiRequest request;
        public QueuedRequest(ApiCollection collection, ApiRequest request) {
            this.collection = collection;
            this.request = request;
        }
    }

    private void processRequest(ApiCollection collection, ApiRequest req, String destination, int delayMs,
                                Map<String, String> runtimeOverlay,
                                SharedRequestPipeline.OAuth2TokenSink oauth2TokenSink,
                                SharedRequestPipeline.RuntimeVariableSink runtimeVariableSink,
                                EnvironmentProfile activeEnvironment,
                                LogCallback logCallback,
                                Map<String, String> colSources) throws Exception {
        String destinationLower = destination.toLowerCase();
        boolean liveSend = "sitemap".equals(destinationLower);
        ExecutionResult exec = runtimeOverlay != null
                ? (liveSend
                    ? pipeline.execute(req, collection, followRedirects, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, burp.scripts.ExecutionSource.WORKBENCH_SEND)
                    : pipeline.build(req, collection, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, burp.scripts.ExecutionSource.BUILD_PREVIEW))
                : (liveSend
                    ? pipeline.execute(req, collection, followRedirects, null, null, null, activeEnvironment, burp.scripts.ExecutionSource.WORKBENCH_SEND)
                    : pipeline.build(req, collection, null, null, null, activeEnvironment, burp.scripts.ExecutionSource.BUILD_PREVIEW));
        if (exec == null || !exec.success || exec.requestHeaders == null) {
            throw new Exception(exec != null && exec.errorMessage != null ? exec.errorMessage : "Failed to build request");
        }
        byte[] rawRequest = exec.rawRequestBytes != null
                ? exec.rawRequestBytes
                : exec.requestHeaders.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        warnIfUnresolved(rawRequest, req.name);
        if (debugRawRequest) {
            String debug = RequestDebugFormatter.format(rawRequest, destination, req.name);
            logCallback.log(debug);
            if (api != null) api.logging().logToOutput(debug);
            Map<String, String> resolvedVars = exec.resolvedVariables != null ? exec.resolvedVariables : Collections.emptyMap();
            Map<String, String> sources = buildSourceMap(req, resolvedVars, colSources);
            String varsDebug = VariableDebugFormatter.format(resolvedVars, sources, destination + " / " + req.name);
            logCallback.log(varsDebug);
            if (api != null) api.logging().logToOutput(varsDebug);
        }

        if (liveSend) {
            if (delayMs > 0) Thread.sleep(delayMs);
            if (exec.response != null && exec.response.response() != null) {
                try {
                    Annotations annotations = Annotations.annotations(
                            "[Imported] " + req.name, HighlightColor.CYAN);
                    api.siteMap().add(exec.response.withAnnotations(annotations));
                } catch (Throwable ignored) {
                    // Burp object factory is unavailable in unit tests/non-Burp environments.
                }
            } else {
                throw new Exception("Sitemap request failed: no response received (possible timeout/DNS failure)");
            }
            return;
        }

        String resolvedUrl = exec.resolvedUrl != null ? exec.resolvedUrl : req.url;
        HttpUtils.ParsedTarget parsed = HttpUtils.parseTargetForRequest(resolvedUrl);

        burp.api.montoya.http.HttpService service = null;
        try {
            service = burp.api.montoya.http.HttpService.httpService(
                    parsed.host, parsed.port, parsed.useHttps);
        } catch (Throwable ignored) {
            // Unit tests / non-Burp environments may not have a Montoya object factory.
        }

        HttpRequest httpRequest = exec.builtRequest != null ? exec.builtRequest
                : buildHttpRequest(rawRequest, service);
        String tabName = generateUniqueTabName(req.name, req.sourceCollection != null ? req.sourceCollection : "Unknown");

        switch (destinationLower) {
            case "repeater":
                api.repeater().sendToRepeater(httpRequest, tabName);
                existingTabs.add(tabName);
                break;
            case "intruder":
                api.intruder().sendToIntruder(httpRequest);
                break;
        }
    }

    private void sendToSitemap(burp.api.montoya.http.HttpService service, byte[] request, String name) throws Exception {
        try {
            HttpRequest httpRequest = buildHttpRequest(request, service);
            burp.api.montoya.http.message.HttpRequestResponse response;
            try {
                RequestOptions options = RequestOptions.requestOptions()
                        .withRedirectionMode(followRedirects ? RedirectionMode.ALWAYS : RedirectionMode.NEVER);
                response = api.http().sendRequest(httpRequest, options);
            } catch (Throwable factoryError) {
                response = api.http().sendRequest(httpRequest);
            }
            if (response != null && response.response() != null) {
                try {
                    Annotations annotations = Annotations.annotations(
                            "[Imported] " + name, HighlightColor.CYAN);
                    api.siteMap().add(response.withAnnotations(annotations));
                } catch (Throwable ignored) {
                    // Annotation helpers can be unavailable in unit tests.
                }
            } else {
                throw new Exception("Sitemap request failed: no response received (possible timeout/DNS failure)");
            }
        } catch (Exception e) {
            throw new Exception("Sitemap request failed: " + extractCleanError(e));
        }
    }

    private HttpRequest buildHttpRequest(byte[] rawRequest, burp.api.montoya.http.HttpService service) throws Exception {
        try {
            if (service != null) {
                return HttpRequest.httpRequest(service, ByteArray.byteArray(rawRequest));
            }
        } catch (Throwable ignored) {
        }
        String fallbackRaw = rawRequest != null ? new String(rawRequest, java.nio.charset.StandardCharsets.UTF_8) : "";
        return HistoryNativeHttpMessageFactory.request(fallbackRaw);
    }

    private String generateUniqueTabName(String baseName, String collectionName) {
        String tabName = baseName;
        if (existingTabs.contains(tabName)) {
            tabName = collectionName + " - " + baseName;
        }
        int counter = 1;
        while (existingTabs.contains(tabName)) {
            tabName = collectionName + " - " + baseName + " (" + counter++ + ")";
        }
        return tabName;
    }

    private String extractCleanError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        if (msg.contains("UnknownHostException")) return "DNS failed - check network/VPN";
        if (msg.contains("ConnectException")) return "Connection refused";
        if (msg.contains("SocketTimeoutException")) return "Connection timeout";
        return msg;
    }

    private void warnIfUnresolved(byte[] rawRequest, String requestName) {
        Set<String> unresolved = RequestBuilder.findUnresolvedTokens(rawRequest);
        if (!unresolved.isEmpty() && api != null) {
            api.logging().logToOutput("[WARN] Unresolved variables in request '" + requestName + "': " + String.join(", ", unresolved));
        }
    }

    public void clearVariables() {
        resolver.clear();
        existingTabs.clear();
    }

    public void cleanup() {
        cleanupWithin(WORKSPACE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    void cleanupWithin(long timeout, TimeUnit unit) {
        if (!cleanupStarted.compareAndSet(false, true)) {
            return;
        }
        long started = System.nanoTime();
        long timeoutNanos = unit != null && timeout > 0L ? unit.toNanos(timeout) : 0L;
        long deadline = timeoutNanos >= Long.MAX_VALUE - started
                ? Long.MAX_VALUE
                : started + timeoutNanos;
        try {
            if (debouncedWorkspaceSave != null) {
                debouncedWorkspaceSave.close();
            }
            if (workspaceSaveCoordinator != null && !workspaceSaveClosed.get()) {
                long revision = allocateWorkspaceRevision();
                try {
                    WorkspaceState detached = captureWorkspaceStateSnapshot(true);
                    submitWorkspaceStateSave(revision, detached, false);
                } catch (Exception e) {
                    logWorkspaceStateError("save", unwrapWorkspaceStateSaveException(e));
                }
            }
        } finally {
            workspaceSaveClosed.set(true);
            if (workspaceSaveCoordinator != null) {
                long remaining = Math.max(0L, deadline - System.nanoTime());
                WorkspaceSaveResult result = workspaceSaveCoordinator.closeWithin(
                        remaining, TimeUnit.NANOSECONDS);
                logWorkspaceSaveFailure("save", result);
            }
            try {
                if (ui != null) {
                    ui.cleanup();
                }
            } finally {
                if (pipeline != null) {
                    pipeline.close();
                }
                try {
                    if (payloadStore != null) payloadStore.close();
                } catch (IOException failure) {
                    logWorkspaceStateError("payload cleanup", failure);
                } finally {
                    clearVariables();
                }
            }
        }
    }

    private void restoreWorkspaceState() {
        if (workspaceStateService == null || ui == null) {
            return;
        }
        try {
            String json = workspaceStateService.loadJson();
            if (json == null || json.isBlank()) {
                return;
            }
            long loadedLength = WorkspaceStateService.utf8Length(json);
            String loadedSha256 = WorkspaceStateService.sha256(json);
            WorkspaceState state = WorkspaceStateJson.fromJson(json);
            if (payloadStore != null) {
                new ExactPayloadMigrationService(payloadStore).migrateDetached(state);
                markUnavailablePayloads(state);
            }
            lastSavedWorkspaceLength = loadedLength;
            lastSavedWorkspaceSha256 = loadedSha256;
            lastSuccessfulWorkspaceRevision = 0L;
            if (!hasRestorableWorkspaceState(state)) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                try {
                    if (latestRequestedWorkspaceRevision != 0L) {
                        if (api != null) {
                            api.logging().logToOutput("Workspace state restore skipped because newer workspace state was saved before restore executed.");
                        }
                        return;
                    }
                    if (ui.hasUnsavedEnvironmentEditorChanges()) {
                        if (api != null) {
                            api.logging().logToOutput("Workspace state restore skipped because the Environment editor has unsaved changes.");
                        }
                        return;
                    }
                    ui.restoreWorkspaceState(state);
                    requestWorkspaceStateSaveNowFromModel();
                } catch (Exception e) {
                    logWorkspaceStateError("restore", e);
                }
            });
        } catch (Exception e) {
            logWorkspaceStateError("load", e);
        }
    }

    private static boolean hasRestorableWorkspaceState(WorkspaceState state) {
        if (state == null) {
            return false;
        }
        boolean hasCollections = state.collections != null && !state.collections.isEmpty();
        boolean hasEnvironments = state.environments != null && !state.environments.isEmpty();
        boolean hasHistory = state.historyEntries != null && !state.historyEntries.isEmpty();
        return hasCollections || hasEnvironments || hasHistory;
    }

    void restoreWorkspaceStateAfterUiRegistration() {
        restoreWorkspaceState();
    }

    void requestWorkspaceStateSave() {
        if (!cleanupStarted.get() && !workspaceSaveClosed.get() && debouncedWorkspaceSave != null) {
            latestRequestedWorkspaceRevision = workspaceRevisionCounter.incrementAndGet();
            debouncedWorkspaceSave.restart();
        }
    }

    public void requestWorkspaceStateSaveNow() {
        requestImmediateWorkspaceStateSave(true);
    }

    public void requestWorkspaceStateSaveNowFromModel() {
        requestImmediateWorkspaceStateSave(false);
    }

    void flushWorkspaceStateSave() {
        requestImmediateWorkspaceStateSave(true);
    }

    void saveWorkspaceState() {
        requestImmediateWorkspaceStateSave(true);
    }

    WorkspaceState captureWorkspaceStateSnapshot() throws Exception {
        return captureWorkspaceStateSnapshot(true);
    }

    WorkspaceState captureWorkspaceStateSnapshot(boolean persistRequestEditorState) throws Exception {
        if (workspaceStateService == null || ui == null) {
            return new WorkspaceState();
        }
        return SwingEdt.call(() -> persistRequestEditorState
                ? ui.getWorkspaceStateSnapshotForPersistence()
                : ui.getWorkspaceStateSnapshotFromModelForPersistence());
    }

    private void markUnavailablePayloads(WorkspaceState state) {
        if (state == null || payloadStore == null) return;
        if (state.collections != null) {
            for (ApiCollection collection : state.collections) {
                if (collection == null || collection.requests == null) continue;
                for (ApiRequest request : collection.requests) {
                    if (request != null && request.exactHttpRequest != null
                            && request.exactHttpRequest.payloadRef != null) {
                        request.exactHttpRequest.payloadUnavailable =
                                !isPayloadAvailable(request.exactHttpRequest.payloadRef);
                    }
                }
            }
        }
        if (state.historyEntries != null) {
            for (burp.history.HistoryEntry entry : state.historyEntries) {
                if (entry != null && entry.requestSnapshot != null
                        && entry.requestSnapshot.authoredExactPayloadRef != null) {
                    entry.requestSnapshot.authoredExactPayloadUnavailable =
                            !isPayloadAvailable(entry.requestSnapshot.authoredExactPayloadRef);
                }
            }
        }
    }

    private boolean isPayloadAvailable(burp.payload.ManagedPayloadRef ref) {
        try {
            payloadStore.verify(ref);
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    /** Validates a detached candidate without consuming its shared exact transport owners. */
    public void validateWorkspaceStatePersistable(WorkspaceState candidate) {
        if (workspaceStateService == null) {
            return;
        }
        WorkspaceState validationCopy = WorkspaceState.copyOfSharingPersistencePayload(candidate);
        WorkspaceStateJson.normalizeForSave(validationCopy);
        WorkspaceStateJson.serializeDetachedWithMetadataAndRelease(
                validationCopy, workspaceStateService.maxSerializedWorkspaceBytes());
    }

    boolean isWorkspaceSaveCoordinatorTerminatedForTests() {
        return workspaceSaveCoordinator == null || workspaceSaveCoordinator.metrics().workerTerminated();
    }

    boolean isWorkspaceSaveExecutorTerminatedForTests() {
        return isWorkspaceSaveCoordinatorTerminatedForTests();
    }

    CompletableFuture<WorkspaceSaveResult> submitWorkspaceStateSaveForTests(WorkspaceState snapshot) {
        long revision = allocateWorkspaceRevision();
        return submitWorkspaceStateSave(revision, WorkspaceState.copyOf(snapshot), false);
    }

    int activeWorkspaceStateSaveCountForTests() {
        return workspaceSaveCoordinator != null
                ? workspaceSaveCoordinator.metrics().activeSnapshotCount()
                : 0;
    }

    int pendingWorkspaceStateSaveCountForTests() {
        return workspaceSaveCoordinator != null
                ? workspaceSaveCoordinator.metrics().pendingSnapshotCount()
                : 0;
    }

    int maxWorkspaceStateSaveSnapshotCountForTests() {
        return workspaceSaveCoordinator != null
                ? workspaceSaveCoordinator.metrics().maximumSnapshotCountObserved()
                : 0;
    }

    String lastSavedWorkspaceSha256ForTests() {
        return lastSavedWorkspaceSha256;
    }

    long lastSavedWorkspaceLengthForTests() {
        return lastSavedWorkspaceLength;
    }

    long lastSuccessfulWorkspaceRevisionForTests() {
        return lastSuccessfulWorkspaceRevision;
    }

    long latestRequestedWorkspaceRevisionForTests() {
        return latestRequestedWorkspaceRevision;
    }

    long highestSubmittedWorkspaceRevisionForTests() {
        return workspaceSaveCoordinator != null
                ? workspaceSaveCoordinator.metrics().highestSubmittedRevision()
                : -1L;
    }

    long highestCompletedWorkspaceRevisionForTests() {
        return workspaceSaveCoordinator != null
                ? workspaceSaveCoordinator.metrics().highestCompletedRevision()
                : -1L;
    }

    WorkspaceSaveResult flushWorkspaceSaveCoordinatorForTests() {
        return workspaceSaveCoordinator != null ? workspaceSaveCoordinator.flushLatest() : null;
    }

    private void scheduleWorkspaceStateSave() {
        if (cleanupStarted.get() || workspaceSaveClosed.get()) {
            return;
        }
        long revision = latestRequestedWorkspaceRevision;
        try {
            WorkspaceState detached = captureWorkspaceStateSnapshot(true);
            submitWorkspaceStateSave(revision, detached, true);
        } catch (Exception e) {
            logWorkspaceStateError("save", unwrapWorkspaceStateSaveException(e));
        }
    }

    private void requestImmediateWorkspaceStateSave(boolean persistRequestEditorState) {
        if (cleanupStarted.get()
                || workspaceSaveClosed.get()
                || workspaceStateService == null
                || ui == null) {
            return;
        }
        if (debouncedWorkspaceSave != null) {
            debouncedWorkspaceSave.stop();
        }
        long revision = allocateWorkspaceRevision();
        try {
            WorkspaceState detached = captureWorkspaceStateSnapshot(persistRequestEditorState);
            WorkspaceSaveResult result = submitWorkspaceStateSave(revision, detached, false).join();
            logWorkspaceSaveFailure("save", result);
        } catch (Exception e) {
            logWorkspaceStateError("save", unwrapWorkspaceStateSaveException(e));
        }
    }

    private long allocateWorkspaceRevision() {
        long revision = workspaceRevisionCounter.incrementAndGet();
        latestRequestedWorkspaceRevision = revision;
        return revision;
    }

    private CompletableFuture<WorkspaceSaveResult> submitWorkspaceStateSave(
            long revision,
            WorkspaceState detached,
            boolean logFailuresInBackground) {
        if (workspaceSaveCoordinator == null || workspaceSaveClosed.get()) {
            return CompletableFuture.completedFuture(new WorkspaceSaveResult(
                    revision,
                    WorkspaceSaveResult.Status.CLOSED,
                    0L,
                    null,
                    "Workspace saving is closed."));
        }
        CompletableFuture<WorkspaceSaveResult> future = workspaceSaveCoordinator.submit(revision, detached);
        if (logFailuresInBackground) {
            future.thenAccept(result -> logWorkspaceSaveFailure("save", result));
        }
        return future;
    }

    private WorkspaceSaveResult persistDetachedWorkspaceState(long revision, WorkspaceState detached) {
        try {
            if (payloadStore != null) {
                new ExactPayloadMigrationService(payloadStore).migrateDetached(detached);
            }
            WorkspaceStateJson.normalizeForSave(detached);
            Set<String> livePayloadIds = WorkspacePayloadReferenceCollector.collect(detached);
            WorkspaceStateJson.SerializedWorkspace serialized =
                    WorkspaceStateJson.serializeDetachedWithMetadataAndRelease(
                            detached,
                            workspaceStateService.maxSerializedWorkspaceBytes());
            long length = serialized.utf8Length();
            String sha256 = serialized.sha256();
            if (length == lastSavedWorkspaceLength && Objects.equals(sha256, lastSavedWorkspaceSha256)) {
                lastSuccessfulWorkspaceRevision = revision;
                updatePayloadReachability(revision, livePayloadIds);
                return new WorkspaceSaveResult(
                        revision, WorkspaceSaveResult.Status.UNCHANGED, length, sha256, null);
            }
            WorkspaceSaveResult result = workspaceStateService.saveSerialized(
                    revision, serialized);
            if (result.status() == WorkspaceSaveResult.Status.SAVED) {
                updatePayloadReachability(revision, livePayloadIds);
                lastSavedWorkspaceLength = length;
                lastSavedWorkspaceSha256 = sha256;
                lastSuccessfulWorkspaceRevision = revision;
            }
            return result;
        } catch (Exception e) {
            return new WorkspaceSaveResult(
                    revision,
                    WorkspaceSaveResult.Status.FAILED,
                    0L,
                    null,
                    "Workspace normalization or serialization failed ("
                            + e.getClass().getSimpleName() + ").");
        }
    }

    private void updatePayloadReachability(long revision, Set<String> livePayloadIds) throws IOException {
        if (payloadStore == null) return;
        payloadStore.updateManifest(revision, livePayloadIds);
        payloadStore.sweepUnreferenced(livePayloadIds, payloadStore.activeLeasePayloadIds());
    }

    private Exception unwrapWorkspaceStateSaveException(Exception exception) {
        if (exception instanceof java.util.concurrent.ExecutionException executionException
                && executionException.getCause() instanceof Exception cause) {
            return cause;
        }
        return exception;
    }

    private void logWorkspaceSaveFailure(String action, WorkspaceSaveResult result) {
        if (result == null
                || result.successful()
                || result.status() == WorkspaceSaveResult.Status.SUPERSEDED) {
            return;
        }
        logWorkspaceStateError(action, new IllegalStateException(
                result.failureReason() != null ? result.failureReason() : result.status().name()));
    }


    private void logWorkspaceStateError(String action, Exception e) {
        String message = "Workspace state " + action + " failed: " + (e != null && e.getMessage() != null ? e.getMessage() : "unknown error");
        if (api != null) {
            api.logging().logToError(message);
        } else {
            System.err.println(message);
        }
    }

    public interface LogCallback {
        void log(String message);
    }

    public interface ResultCallback {
        void onResult(ImportResult result);
    }
}
