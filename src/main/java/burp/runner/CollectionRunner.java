package burp.runner;

import burp.models.*;
import burp.parser.VariableResolver;
import burp.utils.ExecutionResult;
import burp.utils.HttpUtils;
import burp.utils.ScriptEngine;
import burp.utils.RequestBuilder;
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
import burp.utils.SharedRequestPipeline;

import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.*;

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
    private Function<ApiCollection, Map<String, String>> runtimeOverlayProvider = null;
    private SharedRequestPipeline.OAuth2TokenSink oauth2TokenSink;

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
    public void setDebugRawRequest(boolean debugRawRequest) { this.debugRawRequest = debugRawRequest; }
    public void setRuntimeOverlayProvider(Function<ApiCollection, Map<String, String>> provider) {
        this.runtimeOverlayProvider = provider;
    }
    public void setOAuth2TokenSink(SharedRequestPipeline.OAuth2TokenSink oauth2TokenSink) {
        this.oauth2TokenSink = oauth2TokenSink;
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
        if (running) return;
        if (selectedRequests == null || selectedRequests.isEmpty()) {
            fireOnError("No requests selected for runner");
            return;
        }
        running = true;
        cancelled = false;
        final RunnerStopConditions activeStopConditions = copyStopConditions(ensureStopConditions());
        synchronized (pauseLock) {
            pauseRequested = false;
            singleStepRequested = false;
        }
        results.clear();
        extractedVars.clear();
        extractedVarsByCollection.clear();

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

        ExecutorService executor = Executors.newSingleThreadExecutor();
        activeExecutor = executor;
        Future<?> future = executor.submit(() -> {
            try {
                fireOnStart("Runner", ordered.size());
                int failedResultCount = 0;
                boolean stoppedByCondition = false;

                for (int i = 0; i < ordered.size() && !cancelled; i++) {
                    if (i > 0) {
                        if (!waitIfPausedOrStepConsumed()) {
                            break;
                        }
                        if (delayMs > 0) {
                            Thread.sleep(delayMs);
                        }
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

                    List<String> unresolvedVariables = activeStopConditions.stopOnMissingVariable
                        ? collectUnresolvedVariables(buildPreviewResolver(req, col), req)
                        : Collections.emptyList();
                    if (activeStopConditions.stopOnMissingVariable && !unresolvedVariables.isEmpty()) {
                        fireOnError("Stopped on missing variable(s): " + String.join(", ", unresolvedVariables));
                        stoppedByCondition = true;
                        break;
                    }

                    RequestExecutionOutcome outcome = executeRequest(req, col);
                    if (outcome == null || outcome.result == null || cancelled || Thread.currentThread().isInterrupted()) {
                        break;
                    }

                    RunnerResult result = outcome.result;
                    results.add(result);
                    fireOnRequestComplete(result);
                    fireOnTimeline(buildTimelineRow(req, col, result, outcome.attempts));

                    boolean assertionFailed = hasAssertionFailure(result);
                    boolean statusFailed = hasStatusAtLeast400(result);
                    boolean executionFailed = !result.success;
                    boolean anyFailure = executionFailed || assertionFailed || statusFailed;
                    if (anyFailure) {
                        failedResultCount++;
                    }

                    if (activeStopConditions.stopOnError && executionFailed) {
                        fireOnError("Stopped on error: " + result.errorMessage);
                        stoppedByCondition = true;
                        break;
                    }
                    if (activeStopConditions.stopOnAssertionFailure && assertionFailed) {
                        fireOnError("Stopped on assertion failure for " + result.requestName);
                        stoppedByCondition = true;
                        break;
                    }
                    if (activeStopConditions.stopOnStatusAtLeast400 && statusFailed) {
                        fireOnError("Stopped on status >= 400 for " + result.requestName + " (" + result.statusCode + ")");
                        stoppedByCondition = true;
                        break;
                    }
                    if (activeStopConditions.stopAfterFailureCount > 0 &&
                        failedResultCount >= activeStopConditions.stopAfterFailureCount) {
                        fireOnError("Stopped after failure count reached: " +
                            failedResultCount + "/" + activeStopConditions.stopAfterFailureCount);
                        stoppedByCondition = true;
                        break;
                    }
                }

                if (cancelled || Thread.currentThread().isInterrupted()) {
                    fireOnDebug("Runner cancelled.");
                } else {
                    fireOnComplete(results);
                }
            } catch (Exception e) {
                if (!cancelled && !Thread.currentThread().isInterrupted()) {
                    fireOnError("Runner error: " + e.getMessage());
                }
            } finally {
                running = false;
                if (activeExecutor == executor) {
                    activeExecutor = null;
                    activeFuture = null;
                }
                executor.shutdown();
            }
        });
        activeFuture = future;
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

    private RequestExecutionOutcome executeRequest(ApiRequest req, ApiCollection col) {
        RunnerResult result = new RunnerResult();
        result.requestName = req.name;
        result.requestId = req.id;
        result.method = req.method != null ? req.method.toUpperCase() : "GET";

        Map<String, String> scopedExtractedVars = extractedVars;
        if (col != null) {
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
                Map<String, String> overlay = runtimeOverlayFor(col);
                ExecutionResult exec;
                if (pipeline == null) {
                    exec = null;
                } else if (overlay == null && oauth2TokenSink == null) {
                    exec = pipeline.execute(req, col, followRedirects);
                } else {
                    exec = pipeline.execute(req, col, followRedirects, overlay, oauth2TokenSink);
                }

                if (cancelled || Thread.currentThread().isInterrupted()) {
                    return null;
                }
                if (debugRawRequest && exec != null && exec.requestHeaders != null) {
                    fireOnDebug("=== Runner Raw Request [" + req.name + "] ===\n" + exec.requestHeaders + "\n=== End Runner Raw Request ===");
                }

                if (cancelled || Thread.currentThread().isInterrupted()) {
                    return null;
                }

                if (exec.success && exec.response != null && exec.response.response() != null) {
                    var response = exec.response.response();
                    result.success = true;
                    result.statusCode = response.statusCode();
                    result.responseSize = response.body().length();

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
                    result.requestHeaders = exec.requestHeaders;
                    result.requestBody = exec.requestBody;
                    HttpUtils.ParsedTarget parsed = HttpUtils.parseTargetForRequest(
                        exec.resolvedUrl != null ? exec.resolvedUrl : req.url);
                    result.host = parsed.host;
                    result.path = parsed.pathWithQuery;

                    // Annotate sitemap entry for visibility
                    if (api != null) {
                        Annotations annotations = Annotations.annotations(
                                "[Runner] " + req.name, HighlightColor.CYAN);
                        api.siteMap().add(exec.response.withAnnotations(annotations));
                    }

                    // Copy extracted vars and assertions for cross-request continuity
                    mergeExecutionVariables(scopedExtractedVars, extractedVars, result, exec);
                    if (!exec.assertions.isEmpty()) {
                        result.assertions.addAll(exec.assertions);
                    }

                    break; // Success, exit retry loop
                } else {
                    result.success = false;
                    result.errorMessage = exec.errorMessage != null ? exec.errorMessage : "No response received";
                    fireOnDebug("Attempt " + attempts + "/" + maxAttempts + " failed: " + result.errorMessage);
                    if (attempts >= maxAttempts || cancelled) {
                        break;
                    }
                    int retryDelay = retryDelayMs(attempts);
                    fireOnDebug("Retrying in " + retryDelay + "ms");
                    try {
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            } catch (Exception e) {
                result.success = false;
                result.errorMessage = extractCleanError(e);
                fireOnDebug("Attempt " + attempts + "/" + maxAttempts + " failed: " + result.errorMessage);
                if (attempts >= maxAttempts || cancelled) {
                    break;
                }
                int retryDelay = retryDelayMs(attempts);
                fireOnDebug("Retrying in " + retryDelay + "ms");
                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        if (result.success && attempts > 1) {
            fireOnDebug("Attempt " + attempts + "/" + maxAttempts + " passed");
        }

        return new RequestExecutionOutcome(result, attempts);
    }

    private Map<String, String> extractVariablesFromResponse(ApiRequest req, RunnerResult result) {
        Map<String, String> extracted = new HashMap<>();

        // Parse post-response scripts for variable extraction patterns
        // Support patterns like: pm.environment.set("token", jsonData.access_token)
        // or: bru.setVar("token", res.body.access_token)
        for (ApiRequest.Script script : req.postResponseScripts) {
            if (script.exec == null) continue;

            // Simple regex-based extraction for common patterns
            // pm.environment.set("key", jsonData.path) or bru.setVar("key", res.body.path)
            Pattern setVarPattern = Pattern.compile(
                "(?:pm\\.environment\\.set|bru\\.setVar|pm\\.collectionVariables\\.set)\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*(.+?)\\s*\\)"
            );
            Matcher matcher = setVarPattern.matcher(script.exec);
            while (matcher.find()) {
                String varName = matcher.group(1);
                String expression = matcher.group(2).trim();

                // Try to resolve simple expressions from response
                String value = resolveExpression(expression, result);
                if (value != null) {
                    extracted.put(varName, value);
                }
            }

            // Also support JSONPath-like extraction comments
            // // extract: token = $.data.token
            Pattern extractComment = Pattern.compile("//\\s*extract:\\s*(\\w+)\\s*=\\s*(.+?)$", Pattern.MULTILINE);
            Matcher extractMatcher = extractComment.matcher(script.exec);
            while (extractMatcher.find()) {
                String varName = extractMatcher.group(1);
                String jsonPath = extractMatcher.group(2).trim();
                String value = extractJsonPath(result.responseBody != null ? result.responseBody : result.responseBodyPreview, jsonPath);
                if (value != null) {
                    extracted.put(varName, value);
                }
            }
        }

        return extracted;
    }

    private String resolveExpression(String expression, RunnerResult result) {
        String sourceBody = result.responseBody != null ? result.responseBody : result.responseBodyPreview;
        // Handle jsonData.xxx patterns
        if (expression.startsWith("jsonData")) {
            String path = expression;
            if (path.startsWith("jsonData.")) path = path.substring("jsonData.".length());
            else if (path.startsWith("jsonData")) path = path.substring("jsonData".length());
            path = path.replace("[", "").replace("]", "").replace("'", "").replace('"', ' ').trim();
            return extractJsonPath(sourceBody, path);
        }
        // Handle res.body.xxx patterns
        if (expression.startsWith("res.body")) {
            String path = expression;
            if (path.startsWith("res.body.")) path = path.substring("res.body.".length());
            else if (path.startsWith("res.body")) path = path.substring("res.body".length());
            path = path.replace("[", "").replace("]", "").replace("'", "").replace('"', ' ').trim();
            return extractJsonPath(sourceBody, path);
        }
        // Direct string literal
        if ((expression.startsWith("\"") && expression.endsWith("\"")) ||
            (expression.startsWith("'") && expression.endsWith("'"))) {
            return expression.substring(1, expression.length() - 1);
        }
        return null;
    }

    private String extractJsonPath(String json, String path) {
        if (json == null || json.isEmpty()) return null;
        try {
            com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(json);
            String[] parts = path.replace("$", "").split("\\.");
            com.google.gson.JsonElement current = element;
            for (String part : parts) {
                if (part.isEmpty()) continue;
                if (current.isJsonObject()) {
                    current = current.getAsJsonObject().get(part);
                } else {
                    return null;
                }
            }
            if (current != null && current.isJsonPrimitive()) {
                return current.getAsString();
            }
        } catch (Exception e) {
            return null;
        }
        return null;
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

    private RunnerTimelineRow buildTimelineRow(ApiRequest req, ApiCollection col, RunnerResult result, int attempts) {
        RunnerTimelineRow row = new RunnerTimelineRow();
        row.order = req != null ? req.sequenceOrder : 0;
        row.collectionName = col != null && col.name != null ? col.name : (req != null ? req.sourceCollection : "");
        row.requestName = req != null ? req.name : "";
        row.status = result != null && result.success ? String.valueOf(result.statusCode) : "ERR";
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

    public interface RunnerListener {
        void onStart(String collectionName, int totalRequests);
        void onSkip(String requestName, String reason);
        void onRequestComplete(RunnerResult result);
        default void onTimeline(RunnerTimelineRow row) { }
        void onComplete(List<RunnerResult> results);
        void onError(String message);
        default void onDebug(String message) { }
    }

    private static final class RequestExecutionOutcome {
        private final RunnerResult result;
        private final int attempts;

        private RequestExecutionOutcome(RunnerResult result, int attempts) {
            this.result = result;
            this.attempts = attempts;
        }
    }
}
