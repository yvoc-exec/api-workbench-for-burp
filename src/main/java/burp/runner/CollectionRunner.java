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
import burp.auth.OAuth2Manager;
import burp.auth.OAuth2Config;
import burp.auth.TokenStore;

import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private boolean stopOnError = false;
    private boolean followRedirects = true;
    private boolean debugRawRequest = false;
    private final List<RunnerResult> results = new CopyOnWriteArrayList<>();
    private final Map<ApiCollection, Map<String, String>> extractedVarsByCollection =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<String, String> extractedVars = new ConcurrentHashMap<>();
    private volatile ExecutorService activeExecutor;
    private volatile Future<?> activeFuture;

    private OAuth2Manager oauth2Manager;

    public CollectionRunner(MontoyaApi api) {
        this(api, null, null);
    }

    public CollectionRunner(MontoyaApi api, burp.utils.SharedRequestPipeline pipeline) {
        this(api, pipeline, null);
    }

    public CollectionRunner(MontoyaApi api, burp.utils.SharedRequestPipeline pipeline, OAuth2Manager oauth2Manager) {
        this.api = api;
        this.pipeline = pipeline;
        this.oauth2Manager = oauth2Manager;
    }

    public void addListener(RunnerListener listener) {
        listeners.add(listener);
    }

    public void removeListener(RunnerListener listener) {
        listeners.remove(listener);
    }

    public void setDelayMs(int delayMs) { this.delayMs = delayMs; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = Math.max(0, maxRetries); }
    public void setStopOnError(boolean stopOnError) { this.stopOnError = stopOnError; }
    public void setFollowRedirects(boolean followRedirects) { this.followRedirects = followRedirects; }
    public void setDebugRawRequest(boolean debugRawRequest) { this.debugRawRequest = debugRawRequest; }

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

        // Sort by sequence order if available
        List<ApiRequest> ordered = new ArrayList<>(selectedRequests);
        ordered.sort(Comparator.comparingInt(r -> r.sequenceOrder));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        activeExecutor = executor;
        Future<?> future = executor.submit(() -> {
            try {
                fireOnStart("Runner", ordered.size());

                for (int i = 0; i < ordered.size() && !cancelled; i++) {
                    ApiRequest req = ordered.get(i);
                    if (req.disabled) {
                        fireOnSkip(req.name, "Request disabled");
                        continue;
                    }

                    ApiCollection col = reqToColMap.get(req);
                    if (col == null) {
                        col = colMap.getOrDefault(req.sourceCollection, null);
                    }

                    RunnerResult result = executeRequest(req, col);
                    if (result == null || cancelled || Thread.currentThread().isInterrupted()) {
                        break;
                    }

                    results.add(result);
                    fireOnRequestComplete(result);

                    if (!result.success && stopOnError) {
                        fireOnError("Stopped on error: " + result.errorMessage);
                        break;
                    }

                    // Delay between requests
                    if (delayMs > 0 && i < ordered.size() - 1) {
                        Thread.sleep(delayMs);
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

    private RunnerResult executeRequest(ApiRequest req, ApiCollection col) {
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
                ExecutionResult exec = pipeline.execute(req, col, followRedirects);

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
                    if (attempts >= maxAttempts || cancelled) {
                        break;
                    }
                    try {
                        Thread.sleep(delayMs * attempts); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            } catch (Exception e) {
                result.success = false;
                result.errorMessage = extractCleanError(e);
                if (attempts >= maxAttempts || cancelled) {
                    break;
                }
                try {
                    Thread.sleep(delayMs * attempts); // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        return result;
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

    public void cancel() {
        cancelled = true;
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
        void onComplete(List<RunnerResult> results);
        void onError(String message);
        default void onDebug(String message) { }
    }
}
