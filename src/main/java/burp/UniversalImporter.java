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

import javax.swing.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core importer logic. Handles parsing, variable resolution, and sending to Burp tools.
 */
public class UniversalImporter {
    private final MontoyaApi api;
    private final VariableResolver resolver;
    private final RequestBuilder requestBuilder;
    private final SharedRequestPipeline pipeline;
    private final Set<String> existingTabs = ConcurrentHashMap.newKeySet();
    private final ImporterPanel ui;
    private final WorkspaceStateService workspaceStateService;
    private final DebouncedSwingAction debouncedWorkspaceSave;
    private String lastSavedWorkspaceJson;
    private boolean followRedirects = true;
    private boolean debugRawRequest = false;

    public UniversalImporter(MontoyaApi api, ScriptMode scriptMode) {
        this(api, scriptMode, api != null ? new WorkspaceStateService(api) : null);
    }

    public UniversalImporter(MontoyaApi api, ScriptMode scriptMode, WorkspaceStateService workspaceStateService) {
        this.api = api;
        this.workspaceStateService = workspaceStateService;
        this.resolver = new VariableResolver();
        OAuth2Manager oauth2Manager = new OAuth2Manager(api);
        this.requestBuilder = new RequestBuilder(api, oauth2Manager);
        ScriptEngine scriptEngine = new ScriptEngine(api, scriptMode);
        this.pipeline = new SharedRequestPipeline(api, requestBuilder, scriptEngine, oauth2Manager);
        burp.runner.CollectionRunner runner = new burp.runner.CollectionRunner(api, pipeline, oauth2Manager);
        this.ui = new ImporterPanel(this, runner, oauth2Manager, scriptMode);
        this.debouncedWorkspaceSave = new DebouncedSwingAction(3000, this::saveWorkspaceState);
        this.ui.setWorkspaceChangeListener(this::requestWorkspaceStateSave);
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
                                processRequest(qr.collection, qr.request, destination, delayMs, logCallback, colSources);
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
        ExecutionResult exec = pipeline.execute(req, colContext, followRedirects);
        if (!exec.success) {
            throw new Exception(exec.errorMessage != null ? exec.errorMessage : "Request failed");
        }
        return new SingleSendResult(
            exec.response,
            exec.builtRequest,
            exec.requestHeaders,
            exec.resolvedUrl,
            exec.elapsedMs,
            exec.errorMessage
        );
    }

    public static class SingleSendResult {
        public final burp.api.montoya.http.message.HttpRequestResponse response;
        public final HttpRequest builtRequest;
        public final String rawRequestText;
        public final String resolvedUrl;
        public final long elapsedMs;
        public final String errorMessage;

        public SingleSendResult(burp.api.montoya.http.message.HttpRequestResponse response, HttpRequest builtRequest) {
            this(response, builtRequest, null, null, 0L, null);
        }

        public SingleSendResult(burp.api.montoya.http.message.HttpRequestResponse response, HttpRequest builtRequest,
                                String rawRequestText, String resolvedUrl, long elapsedMs, String errorMessage) {
            this.response = response;
            this.builtRequest = builtRequest;
            this.rawRequestText = rawRequestText;
            this.resolvedUrl = resolvedUrl;
            this.elapsedMs = elapsedMs;
            this.errorMessage = errorMessage;
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
        int count = 0;
        String name = environmentFile.getName().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".bru")) {
                return parseBrunoEnvFile(environmentFile, target);
            }
            try (java.io.InputStreamReader reader = new java.io.InputStreamReader(new java.io.FileInputStream(environmentFile), java.nio.charset.StandardCharsets.UTF_8)) {
                com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                if (obj.has("values") && obj.get("values").isJsonArray()) {
                    for (com.google.gson.JsonElement v : obj.getAsJsonArray("values")) {
                        if (!v.isJsonObject()) {
                            continue;
                        }
                        com.google.gson.JsonObject var = v.getAsJsonObject();
                        String key = getBrunoEnvName(var, "key");
                        if (key == null) {
                            continue;
                        }
                        target.put(key, getBrunoEnvString(var, "value", ""));
                        count++;
                    }
                } else if (obj.has("variables") && obj.get("variables").isJsonArray()) {
                    for (com.google.gson.JsonElement v : obj.getAsJsonArray("variables")) {
                        if (!v.isJsonObject()) {
                            continue;
                        }
                        com.google.gson.JsonObject var = v.getAsJsonObject();
                        String key = getBrunoEnvName(var, "name");
                        if (key == null) {
                            continue;
                        }
                        boolean enabled = !var.has("enabled") || var.get("enabled").isJsonNull() || var.get("enabled").getAsBoolean();
                        if (!enabled) {
                            continue;
                        }
                        target.put(key, getBrunoEnvString(var, "value", ""));
                        count++;
                    }
                }
                return new EnvLoadResult(count, null);
            }
        } catch (Exception e) {
            return new EnvLoadResult(0, e.getMessage());
        }
    }

    private static EnvLoadResult parseBrunoEnvFile(File environmentFile, Map<String, String> target) {
        int count = 0;
        boolean inVars = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(environmentFile), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (!inVars) {
                    if (trimmed.startsWith("vars") && trimmed.contains("{")) {
                        inVars = true;
                    }
                    continue;
                }
                if (trimmed.startsWith("}")) {
                    inVars = false;
                    continue;
                }
                if (trimmed.startsWith("#")) {
                    continue;
                }
                int colonIdx = trimmed.indexOf(':');
                if (colonIdx <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, colonIdx).trim();
                if (key.isEmpty()) {
                    continue;
                }
                String value = trimmed.substring(colonIdx + 1);
                if (value == null) {
                    value = "";
                }
                target.put(key, value.trim());
                count++;
            }
            return new EnvLoadResult(count, null);
        } catch (Exception e) {
            return new EnvLoadResult(0, e.getMessage());
        }
    }

    private static String getBrunoEnvName(com.google.gson.JsonObject var, String key) {
        if (var == null || key == null || !var.has(key) || var.get(key).isJsonNull()) {
            return null;
        }
        if (!var.get(key).isJsonPrimitive()) {
            return null;
        }
        String value = var.get(key).getAsString();
        return value == null || value.isBlank() ? null : value;
    }

    private static String getBrunoEnvString(com.google.gson.JsonObject var, String key, String defaultValue) {
        if (var == null || key == null || !var.has(key) || var.get(key).isJsonNull()) {
            return defaultValue;
        }
        if (!var.get(key).isJsonPrimitive()) {
            return defaultValue;
        }
        return var.get(key).getAsString();
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
                                LogCallback logCallback,
                                Map<String, String> colSources) throws Exception {
        String destinationLower = destination.toLowerCase();
        boolean liveSend = "sitemap".equals(destinationLower);
        ExecutionResult exec = liveSend
                ? pipeline.execute(req, collection, followRedirects)
                : pipeline.build(req, collection);
        if (exec == null || !exec.success || exec.requestHeaders == null) {
            throw new Exception(exec != null && exec.errorMessage != null ? exec.errorMessage : "Failed to build request");
        }
        byte[] rawRequest = exec.rawRequestBytes != null
                ? exec.rawRequestBytes
                : (exec.requestHeaders != null
                ? exec.requestHeaders.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                : new byte[0]);
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
                Annotations annotations = Annotations.annotations(
                        "[Imported] " + req.name, HighlightColor.CYAN);
                api.siteMap().add(exec.response.withAnnotations(annotations));
            } else {
                throw new Exception("Sitemap request failed: no response received (possible timeout/DNS failure)");
            }
            return;
        }

        String resolvedUrl = exec.resolvedUrl != null ? exec.resolvedUrl : req.url;
        HttpUtils.ParsedTarget parsed = HttpUtils.parseTargetForRequest(resolvedUrl);

        burp.api.montoya.http.HttpService service = burp.api.montoya.http.HttpService.httpService(
                parsed.host, parsed.port, parsed.useHttps);

        HttpRequest httpRequest = exec.builtRequest != null ? exec.builtRequest
                : HttpRequest.httpRequest(service, ByteArray.byteArray(rawRequest));
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
            HttpRequest httpRequest = HttpRequest.httpRequest(service, ByteArray.byteArray(request));
            RequestOptions options = RequestOptions.requestOptions()
                    .withRedirectionMode(followRedirects ? RedirectionMode.ALWAYS : RedirectionMode.NEVER);
            burp.api.montoya.http.message.HttpRequestResponse response = api.http().sendRequest(httpRequest, options);
            if (response != null && response.response() != null) {
                Annotations annotations = Annotations.annotations(
                        "[Imported] " + name, HighlightColor.CYAN);
                api.siteMap().add(response.withAnnotations(annotations));
            } else {
                throw new Exception("Sitemap request failed: no response received (possible timeout/DNS failure)");
            }
        } catch (Exception e) {
            throw new Exception("Sitemap request failed: " + extractCleanError(e));
        }
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
        flushWorkspaceStateSave();
        if (ui != null) {
            ui.cleanup();
        }
        clearVariables();
    }

    private void restoreWorkspaceState() {
        if (workspaceStateService == null || ui == null) {
            return;
        }
        try {
            WorkspaceState state = workspaceStateService.load();
            traceWorkflow4("reason=load-after stateNull=" + (state == null)
                    + " collectionCount=" + countCollections(state != null ? state.collections : null)
                    + " requestCount=" + countRequests(state != null ? state.collections : null)
                    + " requestTreePathsCount=" + countRequestTreePaths(state != null ? state.requestTreePaths : null)
                    + " expandedTreePathKeysCount=" + countExpandedTreePathKeys(state != null ? state.expandedTreePathKeys : null)
                    + " nestedModelPathCount=" + countNestedModelPaths(state != null ? state.collections : null)
                    + " flatModelPathCount=" + countFlatModelPaths(state != null ? state.collections : null)
                    + " requestPathSamples=" + sampleRequestPaths(state != null ? state.collections : null, 10));
            if (state == null || state.collections == null || state.collections.isEmpty()) {
                return;
            }
            lastSavedWorkspaceJson = WorkspaceStateJson.toJson(state);
            SwingUtilities.invokeLater(() -> {
                try {
                    ui.restoreWorkspaceState(state);
                } catch (Exception e) {
                    logWorkspaceStateError("restore", e);
                }
            });
        } catch (Exception e) {
            logWorkspaceStateError("load", e);
        }
    }

    void restoreWorkspaceStateAfterUiRegistration() {
        restoreWorkspaceState();
    }

    void requestWorkspaceStateSave() {
        if (debouncedWorkspaceSave != null) {
            debouncedWorkspaceSave.restart();
        }
    }

    public void requestWorkspaceStateSaveNow() {
        flushWorkspaceStateSave();
    }

    void flushWorkspaceStateSave() {
        if (debouncedWorkspaceSave != null) {
            debouncedWorkspaceSave.stop();
        }
        saveWorkspaceState();
    }

    void saveWorkspaceState() {
        if (workspaceStateService == null || ui == null) {
            return;
        }
        try {
            WorkspaceState state = SwingEdt.call(ui::getWorkspaceStateSnapshot);
            String json = WorkspaceStateJson.toJson(state);
            traceWorkflow4("reason=save-before jsonLength=" + safeLength(json)
                    + " jsonHash=" + hashString(json)
                    + " skippedBecauseIdentical=" + Objects.equals(json, lastSavedWorkspaceJson)
                    + " collectionCount=" + countCollections(state != null ? state.collections : null)
                    + " requestCount=" + countRequests(state != null ? state.collections : null)
                    + " nestedPathCount=" + countNestedModelPaths(state != null ? state.collections : null)
                    + " flatPathCount=" + countFlatModelPaths(state != null ? state.collections : null)
                    + " requestTreePathsCount=" + countRequestTreePaths(state != null ? state.requestTreePaths : null));
            if (Objects.equals(json, lastSavedWorkspaceJson)) {
                return;
            }
            workspaceStateService.saveJson(json);
            lastSavedWorkspaceJson = json;
            String readbackJson = workspaceStateService.readJson();
            traceWorkflow4("reason=save-after readbackAvailable=" + (readbackJson != null)
                    + " readbackHash=" + hashString(readbackJson)
                    + " matchesSavedHash=" + Objects.equals(hashString(json), hashString(readbackJson)));
        } catch (Exception e) {
            logWorkspaceStateError("save", e);
        }
    }

    private void traceWorkflow4(String message) {
        String line = "[API-WB-WF4] " + message;
        if (api != null && api.logging() != null) {
            api.logging().logToOutput(line);
        } else {
            System.out.println(line);
        }
    }

    private int countCollections(List<ApiCollection> collections) {
        return collections != null ? collections.size() : 0;
    }

    private int countRequests(List<ApiCollection> collections) {
        if (collections == null) {
            return 0;
        }
        int count = 0;
        for (ApiCollection collection : collections) {
            if (collection != null && collection.requests != null) {
                count += collection.requests.size();
            }
        }
        return count;
    }

    private int countRequestTreePaths(Map<String, String> requestTreePaths) {
        return requestTreePaths != null ? requestTreePaths.size() : 0;
    }

    private int countExpandedTreePathKeys(List<String> expandedTreePathKeys) {
        return expandedTreePathKeys != null ? expandedTreePathKeys.size() : 0;
    }

    private int countNestedModelPaths(List<ApiCollection> collections) {
        if (collections == null) {
            return 0;
        }
        int count = 0;
        for (ApiCollection collection : collections) {
            if (collection == null || collection.requests == null) {
                continue;
            }
            for (ApiRequest request : collection.requests) {
                if (request == null) {
                    continue;
                }
                String folderPath = folderPathFromRequestPath(request.path, request.name);
                if (folderPath != null && !folderPath.isBlank()) {
                    count++;
                }
            }
        }
        return count;
    }

    private int countFlatModelPaths(List<ApiCollection> collections) {
        if (collections == null) {
            return 0;
        }
        int count = 0;
        for (ApiCollection collection : collections) {
            if (collection == null || collection.requests == null) {
                continue;
            }
            for (ApiRequest request : collection.requests) {
                if (request == null) {
                    continue;
                }
                String folderPath = folderPathFromRequestPath(request.path, request.name);
                if (folderPath == null || folderPath.isBlank()) {
                    count++;
                }
            }
        }
        return count;
    }

    private String folderPathFromRequestPath(String requestPath, String requestName) {
        if (requestPath == null) {
            return "";
        }
        String normalizedPath = requestPath.replace('\\', '/').trim();
        if (normalizedPath.isEmpty()) {
            return "";
        }
        String normalizedName = requestName != null ? requestName.replace('\\', '/').trim() : "";
        if (!normalizedName.isEmpty()) {
            String suffix = "/" + normalizedName;
            if (normalizedPath.equals(normalizedName)) {
                return "";
            }
            if (normalizedPath.endsWith(suffix)) {
                return normalizedPath.substring(0, normalizedPath.length() - suffix.length());
            }
        }
        int lastSlash = normalizedPath.lastIndexOf('/');
        if (lastSlash < 0) {
            return "";
        }
        if (normalizedName.isEmpty()) {
            return normalizedPath.substring(0, lastSlash);
        }
        return normalizedPath;
    }

    private List<String> sampleRequestPaths(List<ApiCollection> collections, int limit) {
        List<String> samples = new ArrayList<>();
        if (collections == null || limit <= 0) {
            return samples;
        }
        for (int collectionIndex = 0; collectionIndex < collections.size() && samples.size() < limit; collectionIndex++) {
            ApiCollection collection = collections.get(collectionIndex);
            if (collection == null || collection.requests == null) {
                continue;
            }
            for (int requestIndex = 0; requestIndex < collection.requests.size() && samples.size() < limit; requestIndex++) {
                ApiRequest request = collection.requests.get(requestIndex);
                if (request == null) {
                    continue;
                }
                samples.add((collection.name != null ? collection.name : "none")
                        + "#" + requestIndex
                        + ":" + safeRequestName(request)
                        + "->" + safeRequestPath(request));
            }
        }
        return samples;
    }

    private String safeRequestName(ApiRequest request) {
        if (request == null || request.name == null || request.name.isBlank()) {
            return "none";
        }
        return request.name;
    }

    private String safeRequestPath(ApiRequest request) {
        if (request == null || request.path == null || request.path.isBlank()) {
            return "none";
        }
        return request.path;
    }

    private int safeLength(String value) {
        return value != null ? value.length() : 0;
    }

    private String hashString(String value) {
        return value != null ? Integer.toHexString(value.hashCode()) : "null";
    }

    private String objectId(Object value) {
        if (value == null) {
            return "null";
        }
        String simpleName = value.getClass().getSimpleName();
        if (simpleName == null || simpleName.isBlank()) {
            simpleName = value.getClass().getName();
        }
        return simpleName + "@" + Integer.toHexString(System.identityHashCode(value));
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
