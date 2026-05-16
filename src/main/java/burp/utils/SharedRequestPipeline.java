package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.RedirectionMode;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.core.ByteArray;
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
 * Precedence (lowest → highest, as added to resolver):
 *   1. Collection environment
 *   2. Collection definition variables
 *   3. Scoped OAuth2 runtime vars (runtimeOAuth2)
 *   4. Scoped runtime vars (runtimeVars, includes previously extracted)
 *   5. Request-level variables
 *   6. Default values {{var|default}} handled by resolver internally
 */
public class SharedRequestPipeline {
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
        ExecutionResult result = new ExecutionResult();
        VariableResolver resolver = new VariableResolver();

        try {
            // 1. Seed resolver in unified precedence order
            if (col != null) {
                resolver.addEnvironmentVariables(col);
                resolver.addCollectionVariables(col);
                if (col.runtimeOAuth2 != null) resolver.addAll(col.runtimeOAuth2);
                if (col.runtimeVars != null) resolver.addAll(col.runtimeVars);
            }
            resolver.addRequestVariables(req);

            // Auth mapping: normalize request-level auth metadata into canonical oauth2_* vars
            if (req.hasAuth()) {
                Map<String, String> authVars = OAuth2RuntimeMapper.mapAuthToVars(req.auth, resolver.getVariables(), true);
                if (!authVars.isEmpty()) resolver.addAll(authVars);
            }

            // 2. Pre-request scripts (use isolated copy to track mutations)
            Map<String, String> scriptContext = col != null ? new HashMap<>(col.runtimeVars) : new HashMap<>();
            if (col != null) {
                scriptEngine.executePreRequest(req, resolver, scriptContext);
            }

            // 3. OAuth2 token refresh if needed
            if (oauth2Manager != null && req.hasAuth() && "oauth2".equalsIgnoreCase(req.auth.type)) {
                try {
                    OAuth2Config config = OAuth2Config.fromVariables(resolver.getVariables());
                    if (config.isValid()) {
                        TokenStore.TokenEntry entry = oauth2Manager.getValidToken(config);
                        if (entry != null && entry.accessToken != null) {
                            resolver.addCustomVariable("oauth2_access_token", entry.accessToken);
                            // Persist token into collection OAuth2 context for UI mirroring
                            if (col != null) {
                                col.putRuntimeOAuth2("oauth2_access_token", entry.accessToken);
                            }
                        }
                    }
                } catch (Exception e) {
                    if (api != null) api.logging().logToOutput("OAuth2 refresh failed: " + e.getMessage());
                }
            }

            // 4. Build request
            byte[] rawRequest = requestBuilder.buildRequest(req, resolver);
            warnIfUnresolved(rawRequest, req.name);

            String resolvedUrl = resolver.resolve(req.url);
            result.resolvedUrl = resolvedUrl;
            HttpUtils.ParsedTarget parsed = HttpUtils.parseTargetForRequest(resolvedUrl);
            HttpService service = HttpService.httpService(parsed.host, parsed.port, parsed.useHttps);
            HttpRequest httpRequest = HttpRequest.httpRequest(service, ByteArray.byteArray(rawRequest));

            result.requestHeaders = new String(rawRequest, java.nio.charset.StandardCharsets.UTF_8);
            if (req.body != null && req.body.raw != null) {
                result.requestBody = req.body.raw;
            }
            result.builtRequest = httpRequest;

            // 5. Send HTTP
            long startTime = System.currentTimeMillis();
            RequestOptions options = RequestOptions.requestOptions()
                    .withRedirectionMode(followRedirects ? RedirectionMode.ALWAYS : RedirectionMode.NEVER);
            var response = api.http().sendRequest(httpRequest, options);
            long endTime = System.currentTimeMillis();
            result.elapsedMs = endTime - startTime;
            result.response = response;

            if (response != null && response.response() != null) {
                result.success = true;

                // 6. Post-response scripts
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

                    // Commit script mutations back to collection runtime context via helper (fires change listeners)
                    if (col != null) {
                        col.putAllRuntimeVars(scriptContext);
                    }
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
}
