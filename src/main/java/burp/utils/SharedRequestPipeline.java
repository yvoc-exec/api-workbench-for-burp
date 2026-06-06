package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.RedirectionMode;
import burp.auth.OAuth2Config;
import burp.auth.OAuth2Manager;
import burp.auth.TokenStore;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.VariableResolver;

import java.util.*;

/**
 * Shared execution pipeline for both Workbench Send and Collection Runner.
 * Creates a fresh resolver per call, seeds it with unified precedence, runs scripts,
 * handles OAuth2 refresh, builds and sends the HTTP request, then runs post-response
 * scripts and merges extracted variables back into the collection runtime context.
 *
 * Precedence (lowest -> highest, as added to resolver):
 *   1. Collection environment
 *   2. Collection definition variables
 *   3. Bruno folder variables
 *   4. Scoped OAuth2 runtime vars (runtimeOAuth2)
 *   5. Scoped runtime vars (runtimeVars, includes previously extracted)
 *   6. Request-level variables
 *   6. Default values {{var|default}} handled by resolver internally
 */
public class SharedRequestPipeline {
    public interface OAuth2TokenSink {
        Map<String, String> store(ApiCollection collection, TokenStore.TokenEntry entry);
    }

    private final MontoyaApi api;
    private final RequestBuilder requestBuilder;
    private final ScriptEngine scriptEngine;
    private final OAuth2Manager oauth2Manager;

    public SharedRequestPipeline(MontoyaApi api, RequestBuilder requestBuilder,
                                 ScriptEngine scriptEngine, OAuth2Manager oauth2Manager) {
        this.api = api;
        this.requestBuilder = requestBuilder;
        this.scriptEngine = scriptEngine;
        this.oauth2Manager = oauth2Manager;
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
        return execute(req, col, followRedirects, Collections.emptyMap(), null);
    }

    public ExecutionResult build(ApiRequest req, ApiCollection col) {
        return build(req, col, Collections.emptyMap(), null);
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink) {
        ExecutionResult result = new ExecutionResult();
        return executeInternal(req, col, followRedirects, result, true, runtimeOverlay, oauth2TokenSink);
    }

    public ExecutionResult build(ApiRequest req, ApiCollection col,
                                 Map<String, String> runtimeOverlay,
                                 OAuth2TokenSink oauth2TokenSink) {
        return executeInternal(req, col, true, new ExecutionResult(), false, runtimeOverlay, oauth2TokenSink);
    }

    private ExecutionResult executeInternal(ApiRequest req, ApiCollection col, boolean followRedirects,
                                            ExecutionResult result, boolean sendRequest,
                                            Map<String, String> runtimeOverlay,
                                            OAuth2TokenSink oauth2TokenSink) {
        VariableResolver resolver = RuntimeResolverFactory.build(
                col,
                req,
                runtimeOverlay != null && !runtimeOverlay.isEmpty()
                        ? RuntimeResolverFactory.Options.withRuntimeVariableOverlay(runtimeOverlay)
                        : RuntimeResolverFactory.Options.defaultOptions()
        );
        Map<String, String> scriptContext = col != null ? new HashMap<>(col.runtimeVars) : new HashMap<>();
        Map<String, String> beforeScriptContext = new HashMap<>(scriptContext);
        Set<String> beforeScriptKeys = new HashSet<>(scriptContext.keySet());

        try {
            // 1. Pre-request scripts (use isolated copy to track mutations)
            if (col != null) {
                scriptEngine.executePreRequest(req, resolver, scriptContext);
            }

            // 2. OAuth2 token refresh if needed
            if (oauth2Manager != null && req.hasAuth() && "oauth2".equalsIgnoreCase(req.auth.type)) {
                try {
                        OAuth2Config config = OAuth2Config.fromVariables(resolver.getVariables());
                        if (config.isValid()) {
                            TokenStore.TokenEntry entry = oauth2Manager.getValidToken(config);
                            if (entry != null && entry.accessToken != null) {
                                Map<String, String> storedVars;
                                if (oauth2TokenSink != null) {
                                    storedVars = oauth2TokenSink.store(col, entry);
                                } else {
                                    storedVars = new LinkedHashMap<>();
                                    if (col != null) {
                                        col.putRuntimeOAuth2("oauth2_access_token", entry.accessToken);
                                        storedVars.put("oauth2_access_token", entry.accessToken);
                                        if (entry.refreshToken != null && !entry.refreshToken.isEmpty()) {
                                            col.putRuntimeOAuth2("oauth2_refresh_token", entry.refreshToken);
                                            storedVars.put("oauth2_refresh_token", entry.refreshToken);
                                        }
                                    } else {
                                        storedVars.put("oauth2_access_token", entry.accessToken);
                                        if (entry.refreshToken != null && !entry.refreshToken.isEmpty()) {
                                            storedVars.put("oauth2_refresh_token", entry.refreshToken);
                                        }
                                    }
                                }
                                if (storedVars != null && !storedVars.isEmpty()) {
                                    resolver.addAll(storedVars);
                                }
                            }
                        }
                } catch (Exception e) {
                    if (api != null) api.logging().logToOutput("OAuth2 refresh failed: " + e.getMessage());
                }
            }

            // 3. Build request
            byte[] rawRequest = requestBuilder.buildRequest(req, resolver);
            result.rawRequestBytes = rawRequest;
            result.resolvedVariables = new HashMap<>(resolver.getVariables());
            warnIfUnresolved(rawRequest, req.name);

            String resolvedUrl = resolver.resolve(req.url);
            result.resolvedUrl = resolvedUrl;
            String[] requestParts = splitRawRequest(rawRequest);
            result.requestHeaders = requestParts[0];
            result.requestBody = requestParts[1];

            if (!sendRequest) {
                result.success = true;
                result.response = null;
                return result;
            }
            HttpUtils.ParsedTarget parsed = HttpUtils.parseTargetForRequest(resolvedUrl);
            HttpService service = HttpService.httpService(parsed.host, parsed.port, parsed.useHttps);

            burp.api.montoya.http.message.requests.HttpRequest httpRequest =
                    burp.api.montoya.http.message.requests.HttpRequest.httpRequest(
                            service, burp.api.montoya.core.ByteArray.byteArray(rawRequest));
            result.builtRequest = httpRequest;

            // 4. Send HTTP
            long startTime = System.currentTimeMillis();
            RequestOptions options = RequestOptions.requestOptions()
                    .withRedirectionMode(followRedirects ? RedirectionMode.ALWAYS : RedirectionMode.NEVER);
            var response = api.http().sendRequest(httpRequest, options);
            long endTime = System.currentTimeMillis();
            result.elapsedMs = endTime - startTime;
            result.response = response;

            if (response != null && response.response() != null) {
                result.success = true;

                // 5. Post-response scripts
                if (col != null) {
                    String body = response.response().bodyToString();
                    int statusCode = response.response().statusCode();
                    Map<String, List<String>> headersMap = new HashMap<>();
                    for (var header : response.response().headers()) {
                        headersMap.computeIfAbsent(header.name().toLowerCase(), k -> new ArrayList<>()).add(header.value());
                    }
                    // Create a temporary RunnerResult-like holder for script extraction
                    burp.models.RunnerResult scriptResult = new burp.models.RunnerResult();
                    scriptResult.responseBody = body;
                    scriptResult.responseBodyPreview = body.length() > 500 ? body.substring(0, 500) + "..." : body;
                    scriptResult.statusCode = statusCode;

                    scriptEngine.executePostResponse(req, resolver, scriptContext, scriptResult, body, statusCode, headersMap);

                    if (!scriptResult.extractedVariables.isEmpty()) {
                        result.extractedVars.putAll(scriptResult.extractedVariables);
                    }
                    if (!scriptResult.assertions.isEmpty()) {
                        result.assertions.addAll(scriptResult.assertions);
                    }
                }
            } else {
                result.success = false;
                result.errorMessage = "No response received";
            }
        } catch (Exception e) {
            result.success = false;
            result.errorMessage = extractCleanError(e);
        } finally {
            result.removedVars.clear();
            Set<String> removedKeys = new LinkedHashSet<>();
            for (String key : beforeScriptKeys) {
                if (!scriptContext.containsKey(key)) {
                    result.removedVars.add(key);
                    removedKeys.add(key);
                }
            }

            Map<String, String> changedVars = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : scriptContext.entrySet()) {
                String key = entry.getKey();
                if (!beforeScriptContext.containsKey(key) || !Objects.equals(beforeScriptContext.get(key), entry.getValue())) {
                    changedVars.put(key, entry.getValue());
                }
            }

            // Commit script mutations back to collection runtime context via helper (fires change listeners)
            // Guaranteed path: pre-script mutations persist even on HTTP failure or exception
            if (col != null) {
                col.applyRuntimeVarDelta(changedVars, removedKeys);
            }
        }
        return result;
    }

    private void warnIfUnresolved(byte[] rawRequest, String requestName) {
        Set<String> unresolved = RequestBuilder.findUnresolvedTokens(rawRequest);
        if (!unresolved.isEmpty() && api != null) {
            api.logging().logToOutput("[WARN] Unresolved variables in request '" + requestName + "': " + String.join(", ", unresolved));
        }
    }

    private String extractCleanError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        if (msg.contains("UnknownHostException")) return "DNS resolution failed - check network/VPN";
        if (msg.contains("ConnectException")) return "Connection refused - service may be down or firewalled";
        if (msg.contains("SocketTimeoutException")) return "Connection timeout - target unresponsive";
        return msg;
    }

    private String[] splitRawRequest(byte[] rawRequest) {
        if (rawRequest == null || rawRequest.length == 0) {
            return new String[]{"", ""};
        }

        byte[] separator = "\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int separatorIndex = indexOf(rawRequest, separator);
        if (separatorIndex < 0) {
            return new String[]{new String(rawRequest, java.nio.charset.StandardCharsets.UTF_8), ""};
        }

        String headerText = new String(rawRequest, 0, separatorIndex, java.nio.charset.StandardCharsets.UTF_8);
        int bodyStart = separatorIndex + separator.length;
        String bodyText = bodyStart <= rawRequest.length
                ? new String(rawRequest, bodyStart, rawRequest.length - bodyStart, java.nio.charset.StandardCharsets.UTF_8)
                : "";
        return new String[]{headerText, bodyText};
    }

    private int indexOf(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
