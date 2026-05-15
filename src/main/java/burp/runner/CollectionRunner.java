package burp.runner;

import burp.models.*;
import burp.parser.VariableResolver;
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
    private final VariableResolver resolver;
    private final RequestBuilder requestBuilder;
    private final List<RunnerListener> listeners = new ArrayList<>();
    private volatile boolean running = false;
    private volatile boolean cancelled = false;
    private int delayMs = 200;
    private int maxRetries = 1;
    private boolean stopOnError = false;
    private boolean followRedirects = true;
    private boolean debugRawRequest = false;
    private final List<RunnerResult> results = new CopyOnWriteArrayList<>();
    private final Map<String, String> extractedVars = new ConcurrentHashMap<>();
    private ScriptEngine scriptEngine;

    private OAuth2Manager oauth2Manager;

    private static final List<String> OAUTH2_CANONICAL_KEYS = Arrays.asList(
        "oauth2_grant", "oauth2_token_url", "oauth2_auth_url",
        "oauth2_client_id", "oauth2_client_secret", "oauth2_scope",
        "oauth2_username", "oauth2_password", "oauth2_refresh_token",
        "oauth2_code", "oauth2_redirect_uri", "oauth2_code_verifier",
        "oauth2_client_auth", "oauth2_access_token"
    );

    public CollectionRunner(MontoyaApi api) {
        this(api, null);
    }

    public CollectionRunner(MontoyaApi api, OAuth2Manager oauth2Manager) {
        this.oauth2Manager = oauth2Manager;
        this.api = api;
        this.resolver = new VariableResolver();
        this.requestBuilder = new RequestBuilder(api, resolver);
        this.scriptEngine = new ScriptEngine(api, resolver);
    }

    public void addListener(RunnerListener listener) {
        listeners.add(listener);
    }

    public void removeListener(RunnerListener listener) {
        listeners.remove(listener);
    }

    public void setDelayMs(int delayMs) { this.delayMs = delayMs; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public void setStopOnError(boolean stopOnError) { this.stopOnError = stopOnError; }
    public void setFollowRedirects(boolean followRedirects) { this.followRedirects = followRedirects; }
    public void setDebugRawRequest(boolean debugRawRequest) { this.debugRawRequest = debugRawRequest; }

    public void runCollection(ApiCollection collection, List<ApiRequest> selectedRequests,
                              Map<String, String> initialVars) {
        if (running) return;
        if (selectedRequests == null || selectedRequests.isEmpty()) {
            fireOnError("No requests selected for runner");
            return;
        }
        running = true;
        cancelled = false;
        results.clear();
        extractedVars.clear();

        // Seed resolver with environment + collection + initial vars
        resolver.clear();
        resolver.addEnvironmentVariables(collection);
        resolver.addCollectionVariables(collection);
        resolver.addAll(initialVars);

        // Sort by sequence order if available
        List<ApiRequest> ordered = new ArrayList<>(selectedRequests);
        ordered.sort(Comparator.comparingInt(r -> r.sequenceOrder));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                fireOnStart(collection.name, ordered.size());

                for (int i = 0; i < ordered.size() && !cancelled; i++) {
                    ApiRequest req = ordered.get(i);
                    if (req.disabled) {
                        fireOnSkip(req.name, "Request disabled");
                        continue;
                    }

                    // Apply request-level variables
                    resolver.addRequestVariables(req);
                    // Apply any previously extracted vars
                    resolver.addAll(extractedVars);

                    // Execute pre-request scripts
                    scriptEngine.executePreRequest(req, extractedVars);

                    RunnerResult result = executeRequest(req, i + 1, ordered.size(), extractedVars);
                    results.add(result);

                    // Extract variables from response
                    if (result.success) {
                        Map<String, String> newVars = extractVariablesFromResponse(req, result);
                        extractedVars.putAll(newVars);
                        result.extractedVariables.putAll(newVars);
                    }

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

                fireOnComplete(results);
            } catch (Exception e) {
                fireOnError("Runner error: " + e.getMessage());
            } finally {
                running = false;
                executor.shutdown();
            }
        });
    }

    private RunnerResult executeRequest(ApiRequest req, int current, int total, Map<String, String> extractedVars) {
        RunnerResult result = new RunnerResult();
        result.requestName = req.name;
        result.requestId = req.id;
        result.method = req.method != null ? req.method.toUpperCase() : "GET";

        // Snapshot current OAuth2 canonical values before this request
        Map<String, String> oauth2Snapshot = snapshotOAuth2Vars();

        int attempts = 0;
        while (attempts < maxRetries) {
            attempts++;
            try {
                // Apply current request's auth mapping with override so this request's auth wins
                if (req.hasAuth()) {
                    Map<String, String> authVars = burp.utils.OAuth2RuntimeMapper.mapAuthToVars(req.auth, resolver.getVariables(), true);
                    if (!authVars.isEmpty()) {
                        resolver.addAll(authVars);
                    }
                }

                // Refresh OAuth2 token if needed before executing
                if (oauth2Manager != null && req.hasAuth() && "oauth2".equalsIgnoreCase(req.auth.type)) {
                    try {
                        OAuth2Config config = OAuth2Config.fromVariables(resolver.getVariables());
                        if (config.isValid()) {
                            TokenStore.TokenEntry entry = oauth2Manager.getValidToken(config);
                            resolver.addCustomVariable("oauth2_access_token", entry.accessToken);
                        }
                    } catch (Exception e) {
                        api.logging().logToOutput("OAuth2 refresh in runner failed: " + e.getMessage());
                    }
                }
                byte[] rawRequest = requestBuilder.buildRequest(req);
                if (debugRawRequest) {
                    String debug = burp.utils.RequestDebugFormatter.format(rawRequest, "runner", req.name);
                    api.logging().logToOutput(debug);
                    fireOnDebug(debug);
                    String varsDebug = burp.utils.VariableDebugFormatter.format(resolver.getVariables(), "runner / " + req.name);
                    api.logging().logToOutput(varsDebug);
                    fireOnDebug(varsDebug);
                }
                String resolvedUrl = resolver.resolve(req.url);
                HttpUtils.ParsedTarget parsed = HttpUtils.parseTargetForRequest(resolvedUrl);
                result.host = parsed.host;
                result.path = parsed.pathWithQuery;

                burp.api.montoya.http.HttpService service = burp.api.montoya.http.HttpService.httpService(
                        parsed.host, parsed.port, parsed.useHttps);

                HttpRequest httpRequest = HttpRequest.httpRequest(service, ByteArray.byteArray(rawRequest));

                // Store request details for result pane
                result.requestUrl = resolvedUrl;
                result.requestHeaders = new String(rawRequest, java.nio.charset.StandardCharsets.UTF_8);
                if (req.body != null && req.body.raw != null) {
                    result.requestBody = req.body.raw;
                }

                long startTime = System.currentTimeMillis();
                RequestOptions options = RequestOptions.requestOptions()
                        .withRedirectionMode(followRedirects ? RedirectionMode.ALWAYS : RedirectionMode.NEVER);
                HttpRequestResponse response = api.http().sendRequest(httpRequest, options);
                long endTime = System.currentTimeMillis();

                result.responseTimeMs = endTime - startTime;

                if (response.response() != null) {
                    result.success = true;
                    result.statusCode = response.response().statusCode();
                    result.responseSize = response.response().body().length();

                    // Preview of response body (first 500 chars)
                    String body = response.response().bodyToString();
                    result.responseBody = body;
                    result.responseBodyLength = body.length();
                    result.responseBodyPreview = body.length() > 500 ? body.substring(0, 500) + "..." : body;

                    // Store response headers
                    StringBuilder respHeaders = new StringBuilder();
                    respHeaders.append("HTTP/1.1 ").append(response.response().statusCode()).append(" OK\n");
                    for (var header : response.response().headers()) {
                        respHeaders.append(header.name()).append(": ").append(header.value()).append("\n");
                    }
                    result.responseHeaders = respHeaders.toString();

                    // Annotate sitemap entry for visibility
                    Annotations annotations = Annotations.annotations(
                            "[Runner] " + req.name, HighlightColor.CYAN);
                    api.siteMap().add(response.withAnnotations(annotations));

                    // Execute post-response scripts (assertions + variable extraction)
                    Map<String, List<String>> headersMap = new HashMap<>();
                    for (var header : response.response().headers()) {
                        headersMap.computeIfAbsent(header.name().toLowerCase(), k -> new ArrayList<>()).add(header.value());
                    }
                    scriptEngine.executePostResponse(req, result, extractedVars, body, result.statusCode, headersMap);
                } else {
                    result.success = false;
                    result.errorMessage = "No response received";
                }

                break; // Success, exit retry loop

            } catch (Exception e) {
                result.success = false;
                result.errorMessage = extractCleanError(e);
                if (attempts >= maxRetries) {
                    break;
                }
                try {
                    Thread.sleep(delayMs * attempts); // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Restore snapshot so next request starts clean for OAuth2 keys
        restoreOAuth2Vars(oauth2Snapshot);

        return result;
    }

    private Map<String, String> snapshotOAuth2Vars() {
        Map<String, String> snapshot = new HashMap<>();
        Map<String, String> vars = resolver.getVariables();
        for (String key : OAUTH2_CANONICAL_KEYS) {
            if (vars.containsKey(key)) {
                snapshot.put(key, vars.get(key));
            }
        }
        return snapshot;
    }

    private void restoreOAuth2Vars(Map<String, String> snapshot) {
        Map<String, String> vars = resolver.mutableVariables();
        if (vars == null) return;
        // Remove all canonical keys
        for (String key : OAUTH2_CANONICAL_KEYS) {
            vars.remove(key);
        }
        // Re-add snapped values
        vars.putAll(snapshot);
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

    public void cancel() {
        cancelled = true;
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
