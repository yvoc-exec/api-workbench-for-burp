package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.RedirectionMode;
import burp.auth.OAuth2Config;
import burp.auth.OAuth2Manager;
import burp.auth.TokenStore;
import burp.diagnostics.DiagnosticEvent;
import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;
import burp.ui.history.HistoryNativeHttpMessageFactory;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RedirectHop;
import burp.models.RedirectPolicy;
import burp.models.RedirectTerminationReason;
import burp.parser.VariableResolver;
import burp.scripts.ScriptAssertionResult;
import burp.scripts.ExecutionSource;
import burp.scripts.ScriptExecutionResult;
import burp.scripts.UnifiedScriptRuntime;

import java.util.*;

/**
 * Shared execution pipeline for both Workbench Send and Collection Runner.
 * Creates a fresh resolver per call, seeds it with unified precedence, runs scripts,
 * handles OAuth2 refresh, builds and sends the HTTP request, then runs post-response
 * scripts and merges extracted variables back into the collection runtime context.
 *
 * Precedence, lowest to highest:
 * 1. Collection environment
 * 2. Collection definition variables
 * 3. Ancestor-folder variables
 * 4. Collection runtime OAuth2
 * 5. Collection runtime variables
 * 6. Active Environment overlay
 * 7. Explicit execution/runtime/script overlay
 * 8. Request-level variables
 * 9. Auth/runtime mapping when enabled
 *
 * Default placeholder values are applied only when a key remains unresolved.
 */
public class SharedRequestPipeline implements AutoCloseable {
    enum ExecutionIntent {
        PREVIEW,
        LIVE
    }

    public interface OAuth2TokenSink {
        Map<String, String> store(ApiCollection collection, TokenStore.TokenEntry entry);
    }

    public interface RuntimeVariableSink {
        void apply(ApiCollection collection, Map<String, String> changedVars, Set<String> removedKeys);
    }

    private final MontoyaApi api;
    private final RequestBuilder requestBuilder;
    private final ScriptEngine scriptEngine;
    private final UnifiedScriptRuntime unifiedScriptRuntime;
    private final OAuth2Manager oauth2Manager;
    private final RequestOptions noRedirectRequestOptions = new NoRedirectRequestOptions();
    private volatile boolean closed;

    public SharedRequestPipeline(MontoyaApi api, RequestBuilder requestBuilder,
                                 ScriptEngine scriptEngine, OAuth2Manager oauth2Manager) {
        this(api, requestBuilder, scriptEngine, oauth2Manager, null);
    }

    SharedRequestPipeline(MontoyaApi api,
                          RequestBuilder requestBuilder,
                          ScriptEngine scriptEngine,
                          OAuth2Manager oauth2Manager,
                          UnifiedScriptRuntime unifiedScriptRuntime) {
        this.api = api;
        this.requestBuilder = requestBuilder;
        this.scriptEngine = scriptEngine;
        this.oauth2Manager = oauth2Manager;
        this.unifiedScriptRuntime = unifiedScriptRuntime != null
                ? unifiedScriptRuntime
                : new UnifiedScriptRuntime(
                        api,
                        scriptEngine != null ? scriptEngine.getScriptMode() : ScriptMode.DISABLED
                );
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
        return execute(req, col, followRedirects, null, null, null, null, ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults());
    }

    public ExecutionResult build(ApiRequest req, ApiCollection col) {
        return build(req, col, null, null, null, null, ExecutionSource.BUILD_PREVIEW, null);
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink) {
        return execute(req, col, followRedirects, runtimeOverlay, oauth2TokenSink, null, null, ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults());
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink,
                                   RuntimeVariableSink runtimeVariableSink) {
        return execute(req, col, followRedirects, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, null, ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults());
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink,
                                   RuntimeVariableSink runtimeVariableSink,
                                   EnvironmentProfile activeEnvironment) {
        ExecutionResult result = new ExecutionResult();
        return executeInternal(req, col, followRedirects, result, true, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults());
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink,
                                   RuntimeVariableSink runtimeVariableSink,
                                   EnvironmentProfile activeEnvironment,
                                   ExecutionSource executionSource) {
        ExecutionResult result = new ExecutionResult();
        return executeInternal(req, col, followRedirects, result, true, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, null, RedirectPolicy.defaults());
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink,
                                   RuntimeVariableSink runtimeVariableSink,
                                   EnvironmentProfile activeEnvironment,
                                   ExecutionSource executionSource,
                                   burp.scripts.ScriptDependentRequestExecutor dependentRequestExecutor) {
        ExecutionResult result = new ExecutionResult();
        return executeInternal(req, col, followRedirects, result, true, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, dependentRequestExecutor, RedirectPolicy.defaults());
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink,
                                   RuntimeVariableSink runtimeVariableSink,
                                   EnvironmentProfile activeEnvironment,
                                   ExecutionSource executionSource,
                                   burp.scripts.ScriptDependentRequestExecutor dependentRequestExecutor,
                                   RedirectPolicy redirectPolicy) {
        ExecutionResult result = new ExecutionResult();
        return executeInternal(req, col, followRedirects, result, true, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, dependentRequestExecutor, redirectPolicy);
    }

    public ExecutionResult build(ApiRequest req, ApiCollection col,
                                 Map<String, String> runtimeOverlay,
                                 OAuth2TokenSink oauth2TokenSink) {
        return build(req, col, runtimeOverlay, oauth2TokenSink, null, null, ExecutionSource.BUILD_PREVIEW, null);
    }

    public ExecutionResult build(ApiRequest req, ApiCollection col,
                                 Map<String, String> runtimeOverlay,
                                 OAuth2TokenSink oauth2TokenSink,
                                 RuntimeVariableSink runtimeVariableSink) {
        return build(req, col, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, null, ExecutionSource.BUILD_PREVIEW, null);
    }

    public ExecutionResult build(ApiRequest req, ApiCollection col,
                                 Map<String, String> runtimeOverlay,
                                 OAuth2TokenSink oauth2TokenSink,
                                 RuntimeVariableSink runtimeVariableSink,
                                 EnvironmentProfile activeEnvironment) {
        return executeInternal(req, col, true, new ExecutionResult(), false, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, ExecutionSource.BUILD_PREVIEW, null, RedirectPolicy.defaults());
    }

    public ExecutionResult build(ApiRequest req, ApiCollection col,
                                 Map<String, String> runtimeOverlay,
                                 OAuth2TokenSink oauth2TokenSink,
                                 RuntimeVariableSink runtimeVariableSink,
                                 EnvironmentProfile activeEnvironment,
                                 ExecutionSource executionSource) {
        return executeInternal(req, col, true, new ExecutionResult(), false, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, null, RedirectPolicy.defaults());
    }

    public ExecutionResult build(ApiRequest req, ApiCollection col,
                                 Map<String, String> runtimeOverlay,
                                 OAuth2TokenSink oauth2TokenSink,
                                 RuntimeVariableSink runtimeVariableSink,
                                 EnvironmentProfile activeEnvironment,
                                 ExecutionSource executionSource,
                                 burp.scripts.ScriptDependentRequestExecutor dependentRequestExecutor) {
        return executeInternal(req, col, true, new ExecutionResult(), false, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, dependentRequestExecutor, RedirectPolicy.defaults());
    }

    private ExecutionResult executeInternal(ApiRequest req, ApiCollection col, boolean followRedirects,
                                            ExecutionResult result, boolean sendRequest,
                                            Map<String, String> runtimeOverlay,
                                            OAuth2TokenSink oauth2TokenSink,
                                            RuntimeVariableSink runtimeVariableSink,
                                            EnvironmentProfile activeEnvironment,
                                            ExecutionSource executionSource,
                                            burp.scripts.ScriptDependentRequestExecutor dependentRequestExecutor,
                                            RedirectPolicy redirectPolicy) {
        ExecutionIntent intent = sendRequest ? ExecutionIntent.LIVE : ExecutionIntent.PREVIEW;
        ExecutionSource effectiveSource = intent == ExecutionIntent.PREVIEW
                ? ExecutionSource.BUILD_PREVIEW
                : (executionSource != null ? executionSource : ExecutionSource.WORKBENCH_SEND);
        if (result != null) {
            result.executionSource = effectiveSource;
            result.scriptEngineName = unifiedScriptRuntime != null ? unifiedScriptRuntime.getEngineName() : "Unavailable";
            result.success = true;
            result.redirectsEnabled = followRedirects;
        }
        recordDiagnostic(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, effectiveSource,
                col, req, activeEnvironment, "Request build started", null);
        ApiCollection executionCollection = intent == ExecutionIntent.PREVIEW ? copyCollectionForPreview(col) : col;
        EnvironmentProfile executionEnvironment = intent == ExecutionIntent.PREVIEW && activeEnvironment != null ? activeEnvironment.copy() : activeEnvironment;
        ApiRequest effectiveRequest = intent == ExecutionIntent.PREVIEW ? burp.scripts.ScriptExecutionContext.copyRequest(req) : req;
        burp.scripts.ScriptDependentRequestExecutor scriptDependentExecutor = intent == ExecutionIntent.PREVIEW ? null : dependentRequestExecutor;
        Map<String, String> scriptContext = buildInitialRuntimeOverlay(executionCollection, runtimeOverlay, executionEnvironment);
        Set<String> beforeScriptKeys = new HashSet<>(scriptContext.keySet());
        Map<String, String> finalRuntimeOverlay = new LinkedHashMap<>(scriptContext);

        try {
            // 1. Pre-request scripts (use isolated copy to track mutations)
            if (shouldUseUnifiedRuntime()) {
                ScriptExecutionResult scriptResult = unifiedScriptRuntime.executePreRequest(
                        executionCollection,
                        effectiveRequest,
                        executionEnvironment,
                        effectiveSource,
                        1,
                        scriptDependentExecutor,
                        runtimeOverlay
                );
                effectiveRequest = applyScriptResultToRequest(effectiveRequest, scriptResult);
                mergeScriptResult(result, scriptResult);
                recordScriptDiagnostic(effectiveSource, col, req, activeEnvironment, scriptResult, "Pre-request completed");
                finalRuntimeOverlay = new LinkedHashMap<>(scriptResult.effectiveVariables);
                result.resolvedVariables = new LinkedHashMap<>(finalRuntimeOverlay);
                if (intent == ExecutionIntent.LIVE && !scriptResult.timedOut && !scriptResult.cancelled) {
                    commitScriptVariableMutations(scriptResult, runtimeOverlay, runtimeVariableSink, col, req, activeEnvironment, effectiveSource);
                }
                if (scriptResult.timedOut || scriptResult.cancelled) {
                    result.builtRequest = null;
                    result.rawRequestBytes = null;
                    result.rawRequestText = null;
                    result.resolvedUrl = effectiveRequest != null ? effectiveRequest.url : null;
                    return result;
                }
                if (sendRequest && scriptResult.flowControl == burp.scripts.ScriptFlowControl.SKIP_REQUEST) {
                    recordDiagnostic(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, effectiveSource,
                            col, req, activeEnvironment, "Request skipped by script", "Flow control: SKIP_REQUEST");
                    result.response = null;
                    result.builtRequest = null;
                    result.rawRequestBytes = null;
                    result.rawRequestText = null;
                    result.resolvedUrl = effectiveRequest != null ? effectiveRequest.url : null;
                    return result;
                }
            } else if (scriptEngine != null && scriptEngine.getScriptMode() == ScriptMode.LIMITED && col != null) {
                VariableResolver legacyResolver = RuntimeResolverFactory.build(
                        executionCollection,
                        effectiveRequest,
                        RuntimeResolverFactory.Options.withRuntimeVariableOverlay(scriptContext)
                );
                scriptEngine.executePreRequest(effectiveRequest, legacyResolver, scriptContext);
            }

            VariableResolver resolver = new VariableResolver();
            resolver.addAll(finalRuntimeOverlay);

            // 2. OAuth2 token refresh if needed
            if (intent == ExecutionIntent.LIVE && oauth2Manager != null && effectiveRequest != null && effectiveRequest.hasAuth() && "oauth2".equalsIgnoreCase(effectiveRequest.auth.type)) {
                try {
                    OAuth2Config config = OAuth2Config.fromVariables(resolver.getVariables());
                    if (config.isValid()) {
                        TokenStore.TokenEntry entry = oauth2Manager.getValidToken(config);
                        if (entry != null && entry.accessToken != null) {
                            recordDiagnostic(DiagnosticOperation.OAUTH2_TOKEN_FETCH, DiagnosticSeverity.INFO, effectiveSource,
                                    col, req, activeEnvironment, "OAuth2 token acquired", "Token resolved and injected");
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
                    recordDiagnostic(DiagnosticOperation.OAUTH2_TOKEN_FETCH, DiagnosticSeverity.ERROR, effectiveSource,
                            col, req, activeEnvironment, "OAuth2 refresh failed", e.getMessage());
                }
            }

            // 3. Build request
            byte[] rawRequest = requestBuilder.buildRequest(effectiveRequest, resolver);
            result.rawRequestBytes = rawRequest;
            result.rawRequestText = new String(rawRequest, java.nio.charset.StandardCharsets.UTF_8);
            result.resolvedVariables = new LinkedHashMap<>(resolver.getVariables());
            warnIfUnresolved(rawRequest, effectiveRequest != null ? effectiveRequest.name : null);
            recordDiagnostic(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, effectiveSource,
                    col, effectiveRequest, activeEnvironment, "Raw request built", result.rawRequestText);

            String resolvedUrl = resolver.resolve(effectiveRequest != null ? effectiveRequest.url : null);
            result.resolvedUrl = resolvedUrl;
            result.initialResolvedUrl = resolvedUrl;
            String[] requestParts = splitRawRequest(rawRequest);
            result.requestHeaders = requestParts[0];
            result.requestBody = requestParts[1];

            if (!sendRequest) {
                result.response = null;
                return result;
            }
            HttpUtils.ParsedTarget parsed = HttpUtils.parseTargetForRequest(resolvedUrl);
            burp.api.montoya.http.message.requests.HttpRequest httpRequest;
            boolean usedFallbackRequest = false;
            try {
                HttpService service = HttpService.httpService(parsed.host, parsed.port, parsed.useHttps);
                httpRequest = burp.api.montoya.http.message.requests.HttpRequest.httpRequest(
                        service, burp.api.montoya.core.ByteArray.byteArray(rawRequest));
            } catch (Throwable factoryError) {
                usedFallbackRequest = true;
                String fallbackRaw = result.rawRequestText != null
                        ? result.rawRequestText
                        : new String(rawRequest, java.nio.charset.StandardCharsets.UTF_8);
                httpRequest = HistoryNativeHttpMessageFactory.request(fallbackRaw);
            }
            result.builtRequest = httpRequest;

            // 4. Send HTTP with manual redirect handling
            long startTime = System.currentTimeMillis();
            RedirectExecutor redirectExecutor = new RedirectExecutor();
            RedirectExecutor.RedirectRequest redirectRequest = new RedirectExecutor.RedirectRequest();
            redirectRequest.initialRequest = httpRequest;
            redirectRequest.initialUrl = resolvedUrl;
            redirectRequest.initialRawRequestBytes = rawRequest.clone();
            redirectRequest.followRedirects = followRedirects;
            redirectRequest.redirectPolicy = redirectPolicy != null ? redirectPolicy : RedirectPolicy.defaults();
            redirectRequest.hopSender = request -> api != null
                    ? api.http().sendRequest(request, noRedirectRequestOptions)
                    : null;
            RedirectExecutor.RedirectResult redirectResult = redirectExecutor.execute(redirectRequest);
            long endTime = System.currentTimeMillis();
            result.elapsedMs = Math.max(0L, endTime - startTime);
            result.response = redirectResult.finalResponse;
            result.finalRequest = redirectResult.finalRequest;
            result.finalResolvedUrl = redirectResult.finalUrl;
            result.redirectTerminationReason = redirectResult.terminationReason;
            result.redirectHops.clear();
            if (redirectResult.redirectHops != null) {
                for (RedirectHop hop : redirectResult.redirectHops) {
                    result.redirectHops.add(RedirectHop.copyOf(hop));
                }
            }
            result.success = redirectResult.success;
            result.errorMessage = redirectResult.errorMessage;
            result.resolvedUrl = resolvedUrl;
            recordRedirectDiagnostics(effectiveSource, col, effectiveRequest, activeEnvironment, result.redirectHops, result.redirectTerminationReason);
            recordDiagnostic(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, effectiveSource,
                    col, effectiveRequest, activeEnvironment, "HTTP response received",
                    result.response != null && result.response.response() != null ? "status=" + result.response.response().statusCode() : "no response");

            if (result.response != null && result.response.response() != null && result.success) {
                // 5. Post-response scripts
                if ((shouldUseUnifiedRuntime() || scriptEngine != null) && col != null) {
                    Map<String, String> postExecutionOverlay = new LinkedHashMap<>(result.resolvedVariables);
                    String body = result.response.response().bodyToString();
                    int statusCode = result.response.response().statusCode();
                    Map<String, List<String>> headersMap = new HashMap<>();
                    for (var header : result.response.response().headers()) {
                        headersMap.computeIfAbsent(header.name().toLowerCase(), k -> new ArrayList<>()).add(header.value());
                    }
                    // Create a temporary RunnerResult-like holder for script extraction
                    burp.models.RunnerResult scriptResult = new burp.models.RunnerResult();
                    scriptResult.responseBody = body;
                    scriptResult.responseBodyPreview = body.length() > 500 ? body.substring(0, 500) + "..." : body;
                    scriptResult.statusCode = statusCode;

                    if (shouldUseUnifiedRuntime()) {
                        ScriptExecutionResult postResult = unifiedScriptRuntime.executePostResponse(
                                executionCollection,
                                effectiveRequest,
                                executionEnvironment,
                                effectiveSource,
                                1,
                                body,
                                statusCode,
                                headersMap,
                                result.elapsedMs,
                                scriptResult,
                                scriptDependentExecutor,
                                postExecutionOverlay
                        );
                        mergeScriptResult(result, postResult);
                        mergeScriptResult(scriptResult, postResult);
                        recordScriptDiagnostic(effectiveSource, col, effectiveRequest, activeEnvironment, postResult, "Post-response completed");
                        if (intent == ExecutionIntent.LIVE && !postResult.timedOut && !postResult.cancelled) {
                            commitScriptVariableMutations(postResult, result.resolvedVariables, runtimeVariableSink, col, req, activeEnvironment, effectiveSource);
                        }
                    } else if (scriptEngine != null && scriptEngine.getScriptMode() == ScriptMode.LIMITED && col != null) {
                        scriptEngine.executePostResponse(effectiveRequest, resolver, scriptContext, scriptResult, body, statusCode, headersMap);
                    }

                    if (!shouldUseUnifiedRuntime()) {
                        if (!scriptResult.extractedVariables.isEmpty()) {
                            result.extractedVars.putAll(scriptResult.extractedVariables);
                        }
                        if (!scriptResult.assertions.isEmpty()) {
                            result.assertions.addAll(scriptResult.assertions);
                        }
                    }
                }
            } else if (result.success) {
                result.success = false;
                result.errorMessage = "No response received";
            }
        } catch (Exception e) {
            result.success = false;
            result.errorMessage = extractCleanError(e);
            recordDiagnostic(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.ERROR, effectiveSource,
                    col, req, activeEnvironment, "Request execution failed", result.errorMessage);
        } finally {
            result.removedVars.clear();
            for (String key : beforeScriptKeys) {
                if (!scriptContext.containsKey(key)) {
                    result.removedVars.add(key);
                }
            }
        }
        return result;
    }

    public void cancelActiveScriptExecutions() {
        if (unifiedScriptRuntime != null) {
            unifiedScriptRuntime.cancelActiveExecutions();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancelActiveScriptExecutions();
        if (unifiedScriptRuntime != null) {
            unifiedScriptRuntime.close();
        }
    }

    boolean isClosedForTests() {
        return closed;
    }

    private boolean shouldUseUnifiedRuntime() {
        return unifiedScriptRuntime != null && unifiedScriptRuntime.isEnabled();
    }

    private Map<String, String> buildInitialRuntimeOverlay(ApiCollection collection,
                                                           Map<String, String> runtimeOverlay,
                                                           EnvironmentProfile activeEnvironment) {
        Map<String, String> overlay = new LinkedHashMap<>();
        if (collection != null) {
            if (collection.runtimeOAuth2 != null && !collection.runtimeOAuth2.isEmpty()) {
                overlay.putAll(collection.runtimeOAuth2);
            }
            if (collection.runtimeVars != null && !collection.runtimeVars.isEmpty()) {
                overlay.putAll(collection.runtimeVars);
            }
        }
        if (activeEnvironment != null) {
            overlay.putAll(activeEnvironment.toRuntimeOverlay());
        }
        if (runtimeOverlay != null && !runtimeOverlay.isEmpty()) {
            overlay.putAll(runtimeOverlay);
        }
        return overlay;
    }

    private ApiRequest applyScriptResultToRequest(ApiRequest currentRequest, ScriptExecutionResult scriptResult) {
        if (scriptResult == null || scriptResult.mutatedRequest == null) {
            return currentRequest;
        }
        return scriptResult.mutatedRequest;
    }

    private void commitRuntimeMutations(Map<String, String> scriptContext,
                                        Map<String, String> baselineContext,
                                        Set<String> baselineKeys,
                                        Map<String, String> runtimeOverlay,
                                        RuntimeVariableSink runtimeVariableSink,
                                        ApiCollection col,
                                        ApiRequest req,
                                        EnvironmentProfile activeEnvironment,
                                        ExecutionSource effectiveSource) {
        if (scriptContext == null || baselineContext == null || baselineKeys == null) {
            return;
        }
        Map<String, String> changedVars = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : scriptContext.entrySet()) {
            String key = entry.getKey();
            if (!baselineContext.containsKey(key) || !Objects.equals(baselineContext.get(key), entry.getValue())) {
                changedVars.put(key, entry.getValue());
            }
        }
        Set<String> removedKeys = new LinkedHashSet<>(baselineKeys);
        removedKeys.removeAll(scriptContext.keySet());
        if (changedVars.isEmpty() && removedKeys.isEmpty()) {
            return;
        }
        if (runtimeVariableSink != null && runtimeOverlay != null) {
            runtimeVariableSink.apply(col, changedVars, removedKeys);
        } else if (col != null) {
            col.applyRuntimeVarDelta(changedVars, removedKeys);
        }
        recordDiagnostic(DiagnosticOperation.VARIABLE_RESOLUTION, DiagnosticSeverity.INFO, effectiveSource,
                col, req, activeEnvironment, "Runtime variables updated", "changed=" + changedVars.keySet() + " removed=" + removedKeys);
        baselineContext.clear();
        baselineContext.putAll(scriptContext);
        baselineKeys.clear();
        baselineKeys.addAll(scriptContext.keySet());
    }

    private void commitScriptVariableMutations(ScriptExecutionResult scriptResult,
                                               Map<String, String> runtimeOverlay,
                                               RuntimeVariableSink runtimeVariableSink,
                                               ApiCollection col,
                                               ApiRequest req,
                                               EnvironmentProfile activeEnvironment,
                                               ExecutionSource effectiveSource) {
        if (scriptResult == null
                || scriptResult.timedOut
                || scriptResult.cancelled
                || scriptResult.variableMutations == null
                || scriptResult.variableMutations.isEmpty()) {
            return;
        }
        boolean collectionChanged = false;
        boolean environmentChanged = false;
        Set<String> warnedGlobalKeys = new LinkedHashSet<>();
        Set<String> warnedEnvironmentKeys = new LinkedHashSet<>();
        Set<String> warnedFolderKeys = new LinkedHashSet<>();
        Map<String, String> runtimeEnvironmentChanged = new LinkedHashMap<>();
        Set<String> runtimeEnvironmentRemoved = new LinkedHashSet<>();

        for (burp.scripts.ScriptVariableMutation mutation : scriptResult.variableMutations) {
            if (mutation == null || mutation.key == null || mutation.key.isBlank()) {
                continue;
            }
            String scope = normalizeScope(mutation.scope);
            switch (scope) {
                case "local", "request" -> {
                    // Execution-only; resolver already saw these mutations for this request.
                }
                case "environment" -> {
                    if (activeEnvironment == null) {
                        if (warnedEnvironmentKeys.add(mutation.key)) {
                            scriptResult.warnings.add("Environment variable persistence is unsupported without an active environment; value kept for this execution only.");
                        }
                        break;
                    }
                    activeEnvironment.ensureDefaults();
                    if (mutation.persistent) {
                        if (mutation.newValue == null) {
                            if (activeEnvironment.variables.remove(mutation.key) != null) {
                                environmentChanged = true;
                            }
                        } else {
                            String value = mutation.newValue;
                            String current = activeEnvironment.variables.put(mutation.key, value);
                            if (!Objects.equals(current, value)) {
                                environmentChanged = true;
                            }
                        }
                    } else {
                        if (mutation.newValue == null) {
                            runtimeEnvironmentChanged.remove(mutation.key);
                            runtimeEnvironmentRemoved.add(mutation.key);
                        } else {
                            runtimeEnvironmentRemoved.remove(mutation.key);
                            runtimeEnvironmentChanged.put(mutation.key, mutation.newValue);
                        }
                        environmentChanged = true;
                    }
                }
                case "collection" -> {
                    if (col == null) {
                        break;
                    }
                    col.ensureDefaults();
                    if (mutation.persistent) {
                        if (mutation.newValue == null) {
                            if (removeAuthoredCollectionVariable(col, mutation.key)) {
                                collectionChanged = true;
                            }
                        } else if (upsertAuthoredCollectionVariable(col, mutation.key, mutation.newValue)) {
                            collectionChanged = true;
                        }
                    } else {
                        String value = mutation.newValue;
                        String current = col.runtimeVars.put(mutation.key, value);
                        if (!Objects.equals(current, value)) {
                            collectionChanged = true;
                        }
                    }
                }
                case "folder" -> {
                    if (col == null || mutation.scopePath == null || mutation.scopePath.isBlank()) {
                        if (warnedFolderKeys.add(mutation.key)) {
                            scriptResult.warnings.add("Folder variable persistence is unsupported without a request folder; value kept for this execution only.");
                        }
                        break;
                    }
                    col.ensureDefaults();
                    String folderPath = RequestPathResolver.normalizeFolderPath(mutation.scopePath);
                    if (folderPath.isBlank()) {
                        break;
                    }
                    Map<String, Map<String, String>> target = mutation.persistent ? col.folderVars : col.runtimeFolderVars;
                    if (target == null) {
                        target = new LinkedHashMap<>();
                        if (mutation.persistent) {
                            col.folderVars = target;
                        } else {
                            col.runtimeFolderVars = target;
                        }
                    }
                    if (mutation.newValue == null) {
                        Map<String, String> folder = target.get(folderPath);
                        if (folder != null && folder.remove(mutation.key) != null) {
                            collectionChanged = true;
                            if (folder.isEmpty()) {
                                target.remove(folderPath);
                            }
                        }
                    } else {
                        Map<String, String> folder = target.computeIfAbsent(folderPath, k -> new LinkedHashMap<>());
                        String value = mutation.newValue;
                        String current = folder.put(mutation.key, value);
                        if (!Objects.equals(current, value)) {
                            collectionChanged = true;
                        }
                    }
                }
                case "global" -> {
                    if (warnedGlobalKeys.add(mutation.key)) {
                        scriptResult.warnings.add("Global variable persistence is unsupported; value kept for this execution only.");
                    }
                }
                default -> {
                    // Unknown scope is treated as execution-only to avoid leaking into collection runtime state.
                }
            }
        }

        if (collectionChanged && col != null) {
            col.fireChanged();
        }

        if (activeEnvironment != null && (!runtimeEnvironmentChanged.isEmpty() || !runtimeEnvironmentRemoved.isEmpty())) {
            activeEnvironment.ensureDefaults();
            if (runtimeVariableSink != null) {
                runtimeVariableSink.apply(col, runtimeEnvironmentChanged, runtimeEnvironmentRemoved);
            } else {
                for (String key : runtimeEnvironmentRemoved) {
                    activeEnvironment.runtimeVariables.remove(key);
                }
                for (Map.Entry<String, String> entry : runtimeEnvironmentChanged.entrySet()) {
                    activeEnvironment.runtimeVariables.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
                }
            }
        }

        if (collectionChanged || environmentChanged || !warnedGlobalKeys.isEmpty() || !warnedEnvironmentKeys.isEmpty() || !warnedFolderKeys.isEmpty()) {
            recordDiagnostic(DiagnosticOperation.VARIABLE_RESOLUTION, DiagnosticSeverity.INFO, effectiveSource,
                    col, req, activeEnvironment, "Script variable mutations committed",
                    "collectionChanged=" + collectionChanged
                            + " environmentChanged=" + environmentChanged
                            + " globalWarnings=" + warnedGlobalKeys.size());
        }
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "local";
        }
        String normalized = scope.trim().toLowerCase(Locale.ROOT);
        if ("globals".equals(normalized)) {
            return "global";
        }
        return normalized;
    }

    private boolean upsertAuthoredCollectionVariable(ApiCollection collection, String key, String value) {
        if (collection == null || key == null || key.isBlank()) {
            return false;
        }
        collection.ensureDefaults();
        String normalizedValue = value != null ? value : "";
        for (ApiRequest.Variable variable : collection.variables) {
            if (variable != null && key.equals(variable.key)) {
                if (!Objects.equals(variable.value, normalizedValue)) {
                    variable.value = normalizedValue;
                    return true;
                }
                return false;
            }
        }
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = normalizedValue;
        variable.type = "string";
        variable.enabled = true;
        collection.variables.add(variable);
        return true;
    }

    private boolean removeAuthoredCollectionVariable(ApiCollection collection, String key) {
        if (collection == null || key == null || key.isBlank() || collection.variables == null || collection.variables.isEmpty()) {
            return false;
        }
        boolean removed = collection.variables.removeIf(variable -> variable != null && key.equals(variable.key));
        return removed;
    }

    private ApiCollection copyCollectionForPreview(ApiCollection source) {
        if (source == null) {
            return null;
        }
        source.ensureDefaults();
        ApiCollection copy = new ApiCollection();
        copy.id = source.id;
        copy.name = source.name;
        copy.description = source.description;
        copy.format = source.format;
        copy.version = source.version;
        copy.requests = new ArrayList<>();
        if (source.requests != null) {
            for (ApiRequest request : source.requests) {
                copy.requests.add(burp.scripts.ScriptExecutionContext.copyRequest(request));
            }
        }
        copy.folderPaths = source.folderPaths != null ? new ArrayList<>(source.folderPaths) : new ArrayList<>();
        copy.variables = new ArrayList<>();
        if (source.variables != null) {
            for (ApiRequest.Variable variable : source.variables) {
                if (variable == null) {
                    continue;
                }
                ApiRequest.Variable v = new ApiRequest.Variable();
                v.key = variable.key;
                v.value = variable.value;
                v.type = variable.type;
                v.enabled = variable.enabled;
                copy.variables.add(v);
            }
        }
        copy.folderVars = copyNestedStringMap(source.folderVars);
        copy.runtimeFolderVars = copyNestedStringMap(source.runtimeFolderVars);
        copy.environment = source.environment != null ? new LinkedHashMap<>(source.environment) : new LinkedHashMap<>();
        copy.runtimeVars = source.runtimeVars != null ? new LinkedHashMap<>(source.runtimeVars) : new LinkedHashMap<>();
        copy.runtimeOAuth2 = source.runtimeOAuth2 != null ? new LinkedHashMap<>(source.runtimeOAuth2) : new LinkedHashMap<>();
        copy.auth = copyAuth(source.auth);
        copy.scriptBlocks = copyScriptBlocks(source.scriptBlocks);
        copy.folderScriptBlocks = new LinkedHashMap<>();
        if (source.folderScriptBlocks != null) {
            for (Map.Entry<String, List<burp.scripts.ScriptBlock>> entry : source.folderScriptBlocks.entrySet()) {
                copy.folderScriptBlocks.put(entry.getKey(), copyScriptBlocks(entry.getValue()));
            }
        }
        copy.folderAuthModes = source.folderAuthModes != null ? new LinkedHashMap<>(source.folderAuthModes) : new LinkedHashMap<>();
        copy.folderAuth = new LinkedHashMap<>();
        if (source.folderAuth != null) {
            for (Map.Entry<String, ApiRequest.Auth> entry : source.folderAuth.entrySet()) {
                copy.folderAuth.put(entry.getKey(), copyAuth(entry.getValue()));
            }
        }
        return copy;
    }

    private Map<String, Map<String, String>> copyNestedStringMap(Map<String, Map<String, String>> source) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<String, Map<String, String>> entry : source.entrySet()) {
                out.put(entry.getKey(), entry.getValue() != null ? new LinkedHashMap<>(entry.getValue()) : new LinkedHashMap<>());
            }
        }
        return out;
    }

    private List<burp.scripts.ScriptBlock> copyScriptBlocks(List<burp.scripts.ScriptBlock> source) {
        List<burp.scripts.ScriptBlock> out = new ArrayList<>();
        if (source != null) {
            for (burp.scripts.ScriptBlock block : source) {
                burp.scripts.ScriptBlock copy = burp.scripts.ScriptBlock.copyOf(block);
                if (copy != null) {
                    out.add(copy);
                }
            }
        }
        return out;
    }

    private ApiRequest.Auth copyAuth(ApiRequest.Auth source) {
        if (source == null) {
            return null;
        }
        ApiRequest.Auth copy = new ApiRequest.Auth();
        copy.type = source.type;
        copy.properties = source.properties != null ? new LinkedHashMap<>(source.properties) : new LinkedHashMap<>();
        return copy;
    }

    private void mergeScriptResult(ExecutionResult executionResult, ScriptExecutionResult scriptResult) {
        if (executionResult == null || scriptResult == null) {
            return;
        }
        executionResult.scriptEngineName = scriptResult.engineName;
        if (scriptResult.flowControl != null && scriptResult.flowControl != burp.scripts.ScriptFlowControl.CONTINUE) {
            executionResult.scriptFlowControl = scriptResult.flowControl;
            executionResult.scriptFlowMessage = scriptResult.message;
            executionResult.scriptFlowNextRequestName = scriptResult.nextRequestName;
            executionResult.scriptFlowNextRequestId = scriptResult.nextRequestId;
        }
        if (scriptResult.logs != null) {
            executionResult.scriptLogs.addAll(scriptResult.logs);
        }
        if (scriptResult.warnings != null) {
            executionResult.scriptWarnings.addAll(scriptResult.warnings);
        }
        if (scriptResult.errors != null) {
            executionResult.scriptErrors.addAll(scriptResult.errors);
        }
        if (scriptResult.variableMutations != null) {
            executionResult.scriptVariableMutations.addAll(scriptResult.variableMutations);
        }
        if (scriptResult.dependentRequestResults != null) {
            executionResult.scriptDependentRequestResults.addAll(scriptResult.dependentRequestResults);
        }
        if (scriptResult.dependentRequestCount > 0) {
            executionResult.dependentRequestCount += scriptResult.dependentRequestCount;
        } else if (scriptResult.dependentRequestResults != null && !scriptResult.dependentRequestResults.isEmpty()) {
            executionResult.dependentRequestCount += scriptResult.dependentRequestResults.size();
        }
        if (scriptResult.assertions != null) {
            for (ScriptAssertionResult assertion : scriptResult.assertions) {
                if (assertion == null) {
                    continue;
                }
                executionResult.assertions.add(new burp.models.RunnerResult.AssertionResult(
                        assertion.name != null ? assertion.name : "script assertion",
                        assertion.passed,
                        assertion.expected,
                        assertion.actual
                ));
            }
        }
        if (!scriptResult.success) {
            executionResult.success = false;
        }
        if (scriptResult.errors != null) {
            for (String error : scriptResult.errors) {
                executionResult.assertions.add(new burp.models.RunnerResult.AssertionResult(
                        "Script error",
                        false,
                        "no error",
                        error
                ));
            }
        }
    }

    private void mergeScriptResult(burp.models.RunnerResult runnerResult, ScriptExecutionResult scriptResult) {
        if (runnerResult == null || scriptResult == null) {
            return;
        }
        runnerResult.scriptEngineName = scriptResult.engineName;
        if (scriptResult.flowControl != null && scriptResult.flowControl != burp.scripts.ScriptFlowControl.CONTINUE) {
            runnerResult.scriptFlowControl = scriptResult.flowControl;
            runnerResult.scriptFlowMessage = scriptResult.message;
            runnerResult.scriptFlowNextRequestName = scriptResult.nextRequestName;
            runnerResult.scriptFlowNextRequestId = scriptResult.nextRequestId;
        }
        if (scriptResult.logs != null) {
            runnerResult.scriptLogs.addAll(scriptResult.logs);
        }
        if (scriptResult.warnings != null) {
            runnerResult.scriptWarnings.addAll(scriptResult.warnings);
        }
        if (scriptResult.errors != null) {
            runnerResult.scriptErrors.addAll(scriptResult.errors);
        }
        if (scriptResult.variableMutations != null) {
            runnerResult.scriptVariableMutations.addAll(scriptResult.variableMutations);
        }
        if (scriptResult.dependentRequestResults != null) {
            runnerResult.scriptDependentRequestResults.addAll(scriptResult.dependentRequestResults);
        }
        if (scriptResult.dependentRequestCount > 0) {
            runnerResult.dependentRequestCount += scriptResult.dependentRequestCount;
        } else if (scriptResult.dependentRequestResults != null && !scriptResult.dependentRequestResults.isEmpty()) {
            runnerResult.dependentRequestCount += scriptResult.dependentRequestResults.size();
        }
        if (scriptResult.assertions != null) {
            for (ScriptAssertionResult assertion : scriptResult.assertions) {
                if (assertion == null) {
                    continue;
                }
                runnerResult.assertions.add(new burp.models.RunnerResult.AssertionResult(
                        assertion.name != null ? assertion.name : "script assertion",
                        assertion.passed,
                        assertion.expected,
                        assertion.actual
                ));
            }
        }
        if (!scriptResult.success) {
            runnerResult.success = false;
        }
        if (scriptResult.errors != null) {
            for (String error : scriptResult.errors) {
                runnerResult.assertions.add(new burp.models.RunnerResult.AssertionResult(
                        "Script error",
                        false,
                        "no error",
                        error
                ));
            }
        }
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

    private static final class NoRedirectRequestOptions implements RequestOptions {
        @Override
        public RequestOptions withHttpMode(burp.api.montoya.http.HttpMode httpMode) {
            return this;
        }

        @Override
        public RequestOptions withConnectionId(String connectionId) {
            return this;
        }

        @Override
        public RequestOptions withUpstreamTLSVerification() {
            return this;
        }

        @Override
        public RequestOptions withRedirectionMode(RedirectionMode redirectionMode) {
            return this;
        }

        @Override
        public RequestOptions withServerNameIndicator(String serverNameIndicator) {
            return this;
        }

        @Override
        public RequestOptions withResponseTimeout(long responseTimeout) {
            return this;
        }
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

    private void recordDiagnostic(DiagnosticOperation operation,
                                  DiagnosticSeverity severity,
                                  ExecutionSource source,
                                  ApiCollection collection,
                                  ApiRequest request,
                                  EnvironmentProfile environment,
                                  String message,
                                  String details) {
        DiagnosticEvent event = DiagnosticEvent.of(operation, severity, source != null ? source.name() : null, message);
        if (collection != null) {
            event.collectionName = collection.name;
        }
        if (request != null) {
            event.requestName = request.name;
            event.requestId = request.id;
            event.folderPath = request.path;
            event.withAttribute("buildMode", request.resolveBuildMode() != null ? request.resolveBuildMode().name() : null);
        }
        if (environment != null) {
            event.environmentName = environment.displayName();
        }
        event.executionSource = source;
        event.withDetails(details);
        DiagnosticStore.getInstance().record(event);
    }

    private void recordScriptDiagnostic(ExecutionSource source,
                                        ApiCollection collection,
                                        ApiRequest request,
                                        EnvironmentProfile environment,
                                        ScriptExecutionResult scriptResult,
                                        String message) {
        if (scriptResult == null) {
            return;
        }
        DiagnosticEvent event = DiagnosticEvent.of(DiagnosticOperation.SCRIPT_EXECUTION, DiagnosticSeverity.INFO,
                source != null ? source.name() : null, message);
        if (collection != null) {
            event.collectionName = collection.name;
        }
        if (request != null) {
            event.requestName = request.name;
            event.requestId = request.id;
            event.folderPath = request.path;
        }
        if (environment != null) {
            event.environmentName = environment.displayName();
        }
        event.executionSource = source;
        event.scriptDialect = null;
        StringBuilder details = new StringBuilder();
        if (scriptResult.logs != null && !scriptResult.logs.isEmpty()) {
            details.append("logs=").append(scriptResult.logs.size()).append('\n');
        }
        if (scriptResult.warnings != null && !scriptResult.warnings.isEmpty()) {
            details.append("warnings=").append(scriptResult.warnings.size()).append('\n');
        }
        if (scriptResult.errors != null && !scriptResult.errors.isEmpty()) {
            details.append("errors=").append(scriptResult.errors.size()).append('\n');
        }
        if (scriptResult.variableMutations != null && !scriptResult.variableMutations.isEmpty()) {
            details.append("mutations=").append(scriptResult.variableMutations.size()).append('\n');
        }
        if (scriptResult.flowControl != null) {
            details.append("flowControl=").append(scriptResult.flowControl).append('\n');
        }
        event.withDetails(details.toString().trim());
        DiagnosticStore.getInstance().record(event);
    }

    private void recordRedirectDiagnostics(ExecutionSource source,
                                           ApiCollection collection,
                                           ApiRequest request,
                                           EnvironmentProfile environment,
                                           List<RedirectHop> redirectHops,
                                           RedirectTerminationReason terminationReason) {
        if (redirectHops != null) {
            for (RedirectHop hop : redirectHops) {
                if (hop == null) {
                    continue;
                }
                recordDiagnostic(DiagnosticOperation.REDIRECT, hop.followed ? DiagnosticSeverity.INFO : DiagnosticSeverity.WARNING,
                        source, collection, request, environment,
                        hop.followed ? "Redirect followed" : "Redirect blocked",
                        hop.safeSummary());
            }
        }
        if (terminationReason != null && terminationReason != RedirectTerminationReason.NONE) {
            recordDiagnostic(DiagnosticOperation.REDIRECT, terminationReason == RedirectTerminationReason.FINAL_RESPONSE
                            ? DiagnosticSeverity.INFO
                            : DiagnosticSeverity.WARNING,
                    source, collection, request, environment,
                    "Redirect processing complete",
                    terminationReason.displayLabel());
        }
    }
}
