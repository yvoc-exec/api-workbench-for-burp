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

    public interface RuntimeVariableSink {
        void apply(ApiCollection collection, Map<String, String> changedVars, Set<String> removedKeys);
    }

    private final MontoyaApi api;
    private final RequestBuilder requestBuilder;
    private final ScriptEngine scriptEngine;
    private final UnifiedScriptRuntime unifiedScriptRuntime;
    private final OAuth2Manager oauth2Manager;

    public SharedRequestPipeline(MontoyaApi api, RequestBuilder requestBuilder,
                                 ScriptEngine scriptEngine, OAuth2Manager oauth2Manager) {
        this.api = api;
        this.requestBuilder = requestBuilder;
        this.scriptEngine = scriptEngine;
        this.oauth2Manager = oauth2Manager;
        this.unifiedScriptRuntime = new UnifiedScriptRuntime(
                api,
                scriptEngine != null ? scriptEngine.getScriptMode() : ScriptMode.DISABLED
        );
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
        return execute(req, col, followRedirects, null, null, null, null, ExecutionSource.WORKBENCH_SEND, null);
    }

    public ExecutionResult build(ApiRequest req, ApiCollection col) {
        return build(req, col, null, null, null, null, ExecutionSource.BUILD_PREVIEW, null);
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink) {
        return execute(req, col, followRedirects, runtimeOverlay, oauth2TokenSink, null, null, ExecutionSource.WORKBENCH_SEND, null);
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink,
                                   RuntimeVariableSink runtimeVariableSink) {
        return execute(req, col, followRedirects, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, null, ExecutionSource.WORKBENCH_SEND, null);
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink,
                                   RuntimeVariableSink runtimeVariableSink,
                                   EnvironmentProfile activeEnvironment) {
        ExecutionResult result = new ExecutionResult();
        return executeInternal(req, col, followRedirects, result, true, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, ExecutionSource.WORKBENCH_SEND, null);
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink,
                                   RuntimeVariableSink runtimeVariableSink,
                                   EnvironmentProfile activeEnvironment,
                                   ExecutionSource executionSource) {
        ExecutionResult result = new ExecutionResult();
        return executeInternal(req, col, followRedirects, result, true, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, null);
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink,
                                   RuntimeVariableSink runtimeVariableSink,
                                   EnvironmentProfile activeEnvironment,
                                   ExecutionSource executionSource,
                                   burp.scripts.ScriptDependentRequestExecutor dependentRequestExecutor) {
        ExecutionResult result = new ExecutionResult();
        return executeInternal(req, col, followRedirects, result, true, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, dependentRequestExecutor);
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
        return executeInternal(req, col, true, new ExecutionResult(), false, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, ExecutionSource.BUILD_PREVIEW, null);
    }

    public ExecutionResult build(ApiRequest req, ApiCollection col,
                                 Map<String, String> runtimeOverlay,
                                 OAuth2TokenSink oauth2TokenSink,
                                 RuntimeVariableSink runtimeVariableSink,
                                 EnvironmentProfile activeEnvironment,
                                 ExecutionSource executionSource) {
        return executeInternal(req, col, true, new ExecutionResult(), false, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, null);
    }

    public ExecutionResult build(ApiRequest req, ApiCollection col,
                                 Map<String, String> runtimeOverlay,
                                 OAuth2TokenSink oauth2TokenSink,
                                 RuntimeVariableSink runtimeVariableSink,
                                 EnvironmentProfile activeEnvironment,
                                 ExecutionSource executionSource,
                                 burp.scripts.ScriptDependentRequestExecutor dependentRequestExecutor) {
        return executeInternal(req, col, true, new ExecutionResult(), false, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, dependentRequestExecutor);
    }

    private ExecutionResult executeInternal(ApiRequest req, ApiCollection col, boolean followRedirects,
                                            ExecutionResult result, boolean sendRequest,
                                            Map<String, String> runtimeOverlay,
                                            OAuth2TokenSink oauth2TokenSink,
                                            RuntimeVariableSink runtimeVariableSink,
                                            EnvironmentProfile activeEnvironment,
                                            ExecutionSource executionSource,
                                            burp.scripts.ScriptDependentRequestExecutor dependentRequestExecutor) {
        ExecutionSource effectiveSource = executionSource != null ? executionSource : (sendRequest ? ExecutionSource.WORKBENCH_SEND : ExecutionSource.BUILD_PREVIEW);
        if (result != null) {
            result.executionSource = effectiveSource;
            result.scriptEngineName = unifiedScriptRuntime != null ? unifiedScriptRuntime.getEngineName() : "Unavailable";
            result.success = true;
        }
        recordDiagnostic(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, effectiveSource,
                col, req, activeEnvironment, "Request build started", null);
        Map<String, String> scriptContext = buildInitialRuntimeOverlay(col, runtimeOverlay, activeEnvironment);
        Map<String, String> beforeScriptContext = new HashMap<>(scriptContext);
        Set<String> beforeScriptKeys = new HashSet<>(scriptContext.keySet());
        ApiRequest effectiveRequest = req;

        try {
            // 1. Pre-request scripts (use isolated copy to track mutations)
            if (shouldUseUnifiedRuntime()) {
                ScriptExecutionResult scriptResult = unifiedScriptRuntime.executePreRequest(
                        col,
                        req,
                        activeEnvironment,
                        effectiveSource,
                        1,
                        dependentRequestExecutor
                );
                effectiveRequest = applyScriptResultToRequest(effectiveRequest, scriptResult);
                applyRuntimeMutations(scriptContext, scriptResult);
                commitRuntimeMutations(scriptContext, beforeScriptContext, beforeScriptKeys,
                        runtimeOverlay, runtimeVariableSink, col, req, activeEnvironment, effectiveSource);
                mergeScriptResult(result, scriptResult);
                recordScriptDiagnostic(effectiveSource, col, req, activeEnvironment, scriptResult, "Pre-request completed");
                if (sendRequest && scriptResult.flowControl == burp.scripts.ScriptFlowControl.SKIP_REQUEST) {
                    recordDiagnostic(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, effectiveSource,
                            col, req, activeEnvironment, "Request skipped by script", "Flow control: SKIP_REQUEST");
                    result.response = null;
                    result.builtRequest = null;
                    result.rawRequestBytes = null;
                    result.rawRequestText = null;
                    result.resolvedVariables = new HashMap<>(scriptContext);
                    result.resolvedUrl = effectiveRequest != null ? effectiveRequest.url : null;
                    return result;
                }
            } else if (scriptEngine != null && col != null) {
                VariableResolver legacyResolver = RuntimeResolverFactory.build(
                        col,
                        req,
                        RuntimeResolverFactory.Options.withRuntimeVariableOverlay(scriptContext)
                );
                scriptEngine.executePreRequest(req, legacyResolver, scriptContext);
            }

            VariableResolver resolver = RuntimeResolverFactory.build(
                    col,
                    effectiveRequest,
                    RuntimeResolverFactory.Options.withRuntimeVariableOverlay(scriptContext)
            );

            // 2. OAuth2 token refresh if needed
            if (oauth2Manager != null && effectiveRequest != null && effectiveRequest.hasAuth() && "oauth2".equalsIgnoreCase(effectiveRequest.auth.type)) {
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
            result.rawRequestText = rawRequest != null ? new String(rawRequest, java.nio.charset.StandardCharsets.UTF_8) : null;
            result.resolvedVariables = new HashMap<>(resolver.getVariables());
            warnIfUnresolved(rawRequest, effectiveRequest != null ? effectiveRequest.name : null);
            recordDiagnostic(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, effectiveSource,
                    col, effectiveRequest, activeEnvironment, "Raw request built", result.rawRequestText);

            String resolvedUrl = resolver.resolve(effectiveRequest != null ? effectiveRequest.url : null);
            result.resolvedUrl = resolvedUrl;
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

            // 4. Send HTTP
            long startTime = System.currentTimeMillis();
            var response = usedFallbackRequest
                    ? api.http().sendRequest(httpRequest)
                    : api.http().sendRequest(httpRequest, RequestOptions.requestOptions()
                    .withRedirectionMode(followRedirects ? RedirectionMode.ALWAYS : RedirectionMode.NEVER));
            long endTime = System.currentTimeMillis();
            result.elapsedMs = endTime - startTime;
            result.response = response;
            recordDiagnostic(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, effectiveSource,
                    col, effectiveRequest, activeEnvironment, "HTTP response received",
                    response != null && response.response() != null ? "status=" + response.response().statusCode() : "no response");

            if (response != null && response.response() != null) {
                // 5. Post-response scripts
                if ((shouldUseUnifiedRuntime() || scriptEngine != null) && col != null) {
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

                    if (shouldUseUnifiedRuntime()) {
                        ScriptExecutionResult postResult = unifiedScriptRuntime.executePostResponse(
                                col,
                        effectiveRequest,
                        activeEnvironment,
                        effectiveSource,
                        1,
                        body,
                        statusCode,
                        headersMap,
                        result.elapsedMs,
                        scriptResult,
                        dependentRequestExecutor
                        );
                        applyRuntimeMutations(scriptContext, postResult);
                        commitRuntimeMutations(scriptContext, beforeScriptContext, beforeScriptKeys,
                                runtimeOverlay, runtimeVariableSink, col, req, activeEnvironment, effectiveSource);
                        mergeScriptResult(result, postResult);
                        mergeScriptResult(scriptResult, postResult);
                        recordScriptDiagnostic(effectiveSource, col, effectiveRequest, activeEnvironment, postResult, "Post-response completed");
                    } else if (scriptEngine != null && col != null) {
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
            } else {
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
            Set<String> removedKeys = new LinkedHashSet<>();
            for (String key : beforeScriptKeys) {
                if (!scriptContext.containsKey(key)) {
                    result.removedVars.add(key);
                    removedKeys.add(key);
                }
            }

            commitRuntimeMutations(scriptContext, beforeScriptContext, beforeScriptKeys,
                    runtimeOverlay, runtimeVariableSink, col, req, activeEnvironment, effectiveSource);
        }
        return result;
    }

    private boolean shouldUseUnifiedRuntime() {
        return unifiedScriptRuntime != null && unifiedScriptRuntime.isEnabled();
    }

    private Map<String, String> buildInitialRuntimeOverlay(ApiCollection collection,
                                                           Map<String, String> runtimeOverlay,
                                                           EnvironmentProfile activeEnvironment) {
        Map<String, String> overlay = new LinkedHashMap<>();
        boolean hasOverlaySource = false;
        if (activeEnvironment != null) {
            overlay.putAll(activeEnvironment.toRuntimeOverlay());
            hasOverlaySource = true;
        }
        if (runtimeOverlay != null && !runtimeOverlay.isEmpty()) {
            overlay.putAll(runtimeOverlay);
            hasOverlaySource = true;
        }
        if (!hasOverlaySource && collection != null) {
            if (collection.runtimeOAuth2 != null && !collection.runtimeOAuth2.isEmpty()) {
                overlay.putAll(collection.runtimeOAuth2);
            }
            if (collection.runtimeVars != null && !collection.runtimeVars.isEmpty()) {
                overlay.putAll(collection.runtimeVars);
            }
        }
        return overlay;
    }

    private ApiRequest applyScriptResultToRequest(ApiRequest currentRequest, ScriptExecutionResult scriptResult) {
        if (scriptResult == null || scriptResult.mutatedRequest == null) {
            return currentRequest;
        }
        return scriptResult.mutatedRequest;
    }

    private void applyRuntimeMutations(Map<String, String> scriptContext, ScriptExecutionResult scriptResult) {
        if (scriptContext == null || scriptResult == null || scriptResult.variableMutations == null) {
            return;
        }
        for (var mutation : scriptResult.variableMutations) {
            if (mutation == null || mutation.key == null || mutation.key.isBlank()) {
                continue;
            }
            if (mutation.newValue == null) {
                scriptContext.remove(mutation.key);
            } else {
                scriptContext.put(mutation.key, mutation.newValue);
            }
        }
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
}
