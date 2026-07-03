package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.RedirectionMode;
import burp.api.montoya.http.message.requests.HttpRequest;
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

        default void environmentMutationCommitted(EnvironmentProfile environment,
                                                   boolean persistedChanged,
                                                   boolean runtimeChanged) {
        }
    }

    private final MontoyaApi api;
    private final RequestBuilder requestBuilder;
    private final ScriptEngine scriptEngine;
    private final UnifiedScriptRuntime unifiedScriptRuntime;
    private final OAuth2Manager oauth2Manager;
    private final RequestOptionsFactory requestOptionsFactory;
    private volatile boolean closed;

    interface RequestOptionsFactory {
        RequestOptions create(int timeoutMillis);
    }

    public SharedRequestPipeline(MontoyaApi api, RequestBuilder requestBuilder,
                                 ScriptEngine scriptEngine, OAuth2Manager oauth2Manager) {
        this(api, requestBuilder, scriptEngine, oauth2Manager, null, SharedRequestPipeline::defaultRequestOptions);
    }

    SharedRequestPipeline(MontoyaApi api,
                          RequestBuilder requestBuilder,
                          ScriptEngine scriptEngine,
                          OAuth2Manager oauth2Manager,
                          UnifiedScriptRuntime unifiedScriptRuntime) {
        this(api, requestBuilder, scriptEngine, oauth2Manager, unifiedScriptRuntime, SharedRequestPipeline::defaultRequestOptions);
    }

    SharedRequestPipeline(MontoyaApi api,
                          RequestBuilder requestBuilder,
                          ScriptEngine scriptEngine,
                          OAuth2Manager oauth2Manager,
                          UnifiedScriptRuntime unifiedScriptRuntime,
                          RequestOptionsFactory requestOptionsFactory) {
        this.api = api;
        this.requestBuilder = requestBuilder;
        this.scriptEngine = scriptEngine;
        this.oauth2Manager = oauth2Manager;
        this.requestOptionsFactory = requestOptionsFactory != null ? requestOptionsFactory : SharedRequestPipeline::defaultRequestOptions;
        this.unifiedScriptRuntime = unifiedScriptRuntime != null
                ? unifiedScriptRuntime
                : new UnifiedScriptRuntime(
                        api,
                        scriptEngine != null ? scriptEngine.getScriptMode() : ScriptMode.DISABLED
                );
    }

    public static SharedRequestPipeline withRequestOptionsFactory(MontoyaApi api,
                                                                   RequestBuilder requestBuilder,
                                                                   ScriptEngine scriptEngine,
                                                                   OAuth2Manager oauth2Manager,
                                                                   UnifiedScriptRuntime unifiedScriptRuntime,
                                                                   java.util.function.IntFunction<RequestOptions> requestOptionsFactory) {
        return new SharedRequestPipeline(api, requestBuilder, scriptEngine, oauth2Manager, unifiedScriptRuntime,
                requestOptionsFactory != null ? requestOptionsFactory::apply : null);
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
        return execute(req, col, followRedirects, null, null, null, null, ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), ExecutionPolicy.workbenchDefaults(), null);
    }

    public ExecutionResult build(ApiRequest req, ApiCollection col) {
        return build(req, col, null, null, null, null, ExecutionSource.BUILD_PREVIEW, null);
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink) {
        return execute(req, col, followRedirects, runtimeOverlay, oauth2TokenSink, null, null, ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), ExecutionPolicy.workbenchDefaults(), null);
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink,
                                   RuntimeVariableSink runtimeVariableSink) {
        return execute(req, col, followRedirects, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, null, ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), ExecutionPolicy.workbenchDefaults(), null);
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink,
                                   RuntimeVariableSink runtimeVariableSink,
                                   EnvironmentProfile activeEnvironment) {
        ExecutionResult result = new ExecutionResult();
        return executeInternal(req, col, followRedirects, result, true, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), ExecutionPolicy.workbenchDefaults(), null);
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink,
                                   RuntimeVariableSink runtimeVariableSink,
                                   EnvironmentProfile activeEnvironment,
                                   ExecutionSource executionSource) {
        ExecutionResult result = new ExecutionResult();
        return executeInternal(req, col, followRedirects, result, true, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, null, RedirectPolicy.defaults(), ExecutionPolicy.workbenchDefaults(), null);
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink,
                                   RuntimeVariableSink runtimeVariableSink,
                                   EnvironmentProfile activeEnvironment,
                                   ExecutionSource executionSource,
                                   burp.scripts.ScriptDependentRequestExecutor dependentRequestExecutor) {
        ExecutionResult result = new ExecutionResult();
        return executeInternal(req, col, followRedirects, result, true, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, dependentRequestExecutor, RedirectPolicy.defaults(), ExecutionPolicy.workbenchDefaults(), null);
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
        return executeInternal(req, col, followRedirects, result, true, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, dependentRequestExecutor, redirectPolicy, ExecutionPolicy.workbenchDefaults(), null);
    }

    public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                   Map<String, String> runtimeOverlay,
                                   OAuth2TokenSink oauth2TokenSink,
                                   RuntimeVariableSink runtimeVariableSink,
                                   EnvironmentProfile activeEnvironment,
                                   ExecutionSource executionSource,
                                   burp.scripts.ScriptDependentRequestExecutor dependentRequestExecutor,
                                   RedirectPolicy redirectPolicy,
                                   ExecutionPolicy executionPolicy,
                                   PreflightDecisionHandler preflightDecisionHandler) {
        ExecutionResult result = new ExecutionResult();
        return executeInternal(req, col, followRedirects, result, true, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, dependentRequestExecutor, redirectPolicy, executionPolicy, preflightDecisionHandler);
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
        return executeInternal(req, col, true, new ExecutionResult(), false, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, ExecutionSource.BUILD_PREVIEW, null, RedirectPolicy.defaults(), ExecutionPolicy.previewDefaults(), null);
    }

    public ExecutionResult build(ApiRequest req, ApiCollection col,
                                 Map<String, String> runtimeOverlay,
                                 OAuth2TokenSink oauth2TokenSink,
                                 RuntimeVariableSink runtimeVariableSink,
                                 EnvironmentProfile activeEnvironment,
                                 ExecutionSource executionSource) {
        return executeInternal(req, col, true, new ExecutionResult(), false, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, null, RedirectPolicy.defaults(), ExecutionPolicy.previewDefaults(), null);
    }

    public ExecutionResult build(ApiRequest req, ApiCollection col,
                                 Map<String, String> runtimeOverlay,
                                 OAuth2TokenSink oauth2TokenSink,
                                 RuntimeVariableSink runtimeVariableSink,
                                 EnvironmentProfile activeEnvironment,
                                 ExecutionSource executionSource,
                                 burp.scripts.ScriptDependentRequestExecutor dependentRequestExecutor) {
        return executeInternal(req, col, true, new ExecutionResult(), false, runtimeOverlay, oauth2TokenSink, runtimeVariableSink, activeEnvironment, executionSource, dependentRequestExecutor, RedirectPolicy.defaults(), ExecutionPolicy.previewDefaults(), null);
    }

    private ExecutionResult executeInternal(ApiRequest req, ApiCollection col, boolean followRedirects,
                                            ExecutionResult result, boolean sendRequest,
                                            Map<String, String> runtimeOverlay,
                                            OAuth2TokenSink oauth2TokenSink,
                                            RuntimeVariableSink runtimeVariableSink,
                                            EnvironmentProfile activeEnvironment,
                                            ExecutionSource executionSource,
                                            burp.scripts.ScriptDependentRequestExecutor dependentRequestExecutor,
                                            RedirectPolicy redirectPolicy,
                                            ExecutionPolicy executionPolicy,
                                            PreflightDecisionHandler preflightDecisionHandler) {
        ExecutionIntent intent = sendRequest ? ExecutionIntent.LIVE : ExecutionIntent.PREVIEW;
        ExecutionPolicy effectivePolicy = executionPolicy != null ? executionPolicy.copy() : (sendRequest ? ExecutionPolicy.workbenchDefaults() : ExecutionPolicy.previewDefaults());
        effectivePolicy.normalize();
        ExecutionSource effectiveSource = intent == ExecutionIntent.PREVIEW
                ? ExecutionSource.BUILD_PREVIEW
                : (executionSource != null ? executionSource : ExecutionSource.WORKBENCH_SEND);
        if (result != null) {
            result.executionSource = effectiveSource;
            result.scriptEngineName = unifiedScriptRuntime != null ? unifiedScriptRuntime.getEngineName() : "Unavailable";
            result.redirectsEnabled = followRedirects;
            result.requestSent = false;
            result.responseTimedOut = false;
            result.timeoutMillis = effectivePolicy.responseTimeoutMillis;
            result.preflightStatus = intent == ExecutionIntent.PREVIEW ? ExecutionPreflightStatus.PREVIEW_ONLY : ExecutionPreflightStatus.READY;
            result.success = true;
        }
        recordDiagnostic(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, effectiveSource,
                col, req, activeEnvironment, "Request build started", null);

        ApiCollection executionCollection = intent == ExecutionIntent.PREVIEW ? copyCollectionForPreview(col) : col;
        EnvironmentProfile executionEnvironment = intent == ExecutionIntent.PREVIEW && activeEnvironment != null ? activeEnvironment.copy() : activeEnvironment;
        ApiRequest effectiveRequest = intent == ExecutionIntent.PREVIEW ? burp.scripts.ScriptExecutionContext.copyRequest(req) : req;
        burp.scripts.ScriptDependentRequestExecutor scriptDependentExecutor = intent == ExecutionIntent.PREVIEW ? null : dependentRequestExecutor;
        VariableResolver baselineResolver = RuntimeResolverFactory.build(executionCollection, effectiveRequest, executionEnvironment, runtimeOverlay);
        String originalResolvedUrl = baselineResolver.resolve(effectiveRequest != null ? effectiveRequest.url : null);
        if (result != null) {
            result.originalResolvedUrl = originalResolvedUrl;
        }
        Map<String, String> finalResolvedVariables = new LinkedHashMap<>(baselineResolver.getVariables());
        boolean preRequestScriptFailed = false;
        boolean preRequestScriptTimedOut = false;
        boolean preRequestScriptCancelled = false;
        boolean continuedAfterScriptFailure = false;
        List<String> policyOverrides = new ArrayList<>();
        ScriptExecutionResult scriptResult = null;
        try {
            if (shouldUseUnifiedRuntime()) {
                scriptResult = unifiedScriptRuntime.executePreRequest(
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
                if (scriptResult.timedOut || scriptResult.cancelled) {
                    preRequestScriptTimedOut = scriptResult.timedOut;
                    preRequestScriptCancelled = scriptResult.cancelled;
                } else {
                    finalResolvedVariables = new LinkedHashMap<>(scriptResult.effectiveVariables);
                    if (!scriptResult.errors.isEmpty() || !scriptResult.success) {
                        preRequestScriptFailed = true;
                    }
                }
            } else if (scriptEngine != null && scriptEngine.getScriptMode() == ScriptMode.LIMITED && col != null) {
                Map<String, String> scriptContext = new LinkedHashMap<>(finalResolvedVariables);
                VariableResolver legacyResolver = RuntimeResolverFactory.build(
                        executionCollection,
                        effectiveRequest,
                        executionEnvironment,
                        runtimeOverlay,
                        RuntimeResolverFactory.Options.withRuntimeVariableOverlay(scriptContext)
                );
                scriptEngine.executePreRequest(effectiveRequest, legacyResolver, scriptContext);
                finalResolvedVariables = new LinkedHashMap<>(legacyResolver.getVariables());
            }
        } catch (Exception e) {
            String detailedError = extractCleanError(e);
            if (result != null) {
                result.success = false;
                result.requestSent = false;
                result.preflightStatus = ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR;
                result.preflightMessage = "Request not sent — pre-request script failed.";
                result.errorMessage = result.preflightMessage;
                result.preflight = ExecutionPreflightResult.blocked(
                        ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR,
                        result.preflightMessage,
                        List.of(),
                        originalResolvedUrl,
                        originalResolvedUrl,
                        originDisplay(originalResolvedUrl),
                        originDisplay(originalResolvedUrl),
                        false,
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        List.of(ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR),
                        List.of()
                );
                result.resolvedVariables = new LinkedHashMap<>(baselineResolver.getVariables());
                if (detailedError != null && !detailedError.isBlank()) {
                    result.scriptErrors.add(detailedError);
                }
            }
            recordDiagnostic(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.ERROR, effectiveSource,
                    col, req, activeEnvironment, "Pre-request script failed", detailedError);
            return result;
        }

        if (scriptResult != null) {
            if (scriptResult.timedOut) {
                if (result != null) {
                    result.preflightStatus = ExecutionPreflightStatus.BLOCKED_SCRIPT_TIMEOUT;
                    result.preflightMessage = "Request not sent — pre-request script timed out.";
                    result.errorMessage = result.preflightMessage;
                    result.preflight = ExecutionPreflightResult.blocked(
                            ExecutionPreflightStatus.BLOCKED_SCRIPT_TIMEOUT,
                            result.preflightMessage,
                            List.of(),
                            originalResolvedUrl,
                            originalResolvedUrl,
                            originDisplay(originalResolvedUrl),
                            originDisplay(originalResolvedUrl),
                            false,
                            false,
                            false,
                            true,
                            true,
                            false,
                            false,
                            List.of(ExecutionPreflightStatus.BLOCKED_SCRIPT_TIMEOUT),
                            List.of()
                    );
                    result.success = false;
                    result.requestSent = false;
                    result.resolvedVariables = new LinkedHashMap<>(baselineResolver.getVariables());
                }
                return result;
            }
            if (scriptResult.cancelled) {
                if (result != null) {
                    result.preflightStatus = ExecutionPreflightStatus.CANCELLED;
                    result.preflightMessage = "Request not sent — script execution was cancelled.";
                    result.errorMessage = result.preflightMessage;
                    result.preflight = ExecutionPreflightResult.cancelled(result.preflightMessage, originalResolvedUrl, originalResolvedUrl);
                    result.success = false;
                    result.requestSent = false;
                    result.resolvedVariables = new LinkedHashMap<>(baselineResolver.getVariables());
                }
                return result;
            }
            if (!scriptResult.errors.isEmpty() || !scriptResult.success) {
                preRequestScriptFailed = true;
            }
        }

        if (preRequestScriptFailed) {
            if (effectivePolicy.scriptFailureMode == ExecutionPolicy.ScriptFailureMode.ABORT) {
                if (result != null) {
                    result.preflightStatus = ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR;
                    result.preflightMessage = "Request not sent — pre-request script failed.";
                    result.errorMessage = result.preflightMessage;
                    result.preflight = ExecutionPreflightResult.blocked(
                            ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR,
                            result.preflightMessage,
                            List.of(),
                            originalResolvedUrl,
                            originalResolvedUrl,
                            originDisplay(originalResolvedUrl),
                            originDisplay(originalResolvedUrl),
                            false,
                            false,
                            false,
                            true,
                            false,
                            false,
                            false,
                            List.of(ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR),
                            List.of()
                    );
                    result.success = false;
                    result.requestSent = false;
                    result.resolvedVariables = new LinkedHashMap<>(baselineResolver.getVariables());
                }
                return result;
            }
            continuedAfterScriptFailure = true;
            if (result != null) {
                result.continuedAfterScriptFailure = true;
            }
        }

        VariableResolver resolver = new VariableResolver();
        resolver.addAll(finalResolvedVariables);
        boolean oauth2Required = effectiveRequest != null && effectiveRequest.hasAuth() && effectiveRequest.auth != null && "oauth2".equalsIgnoreCase(effectiveRequest.auth.type);
        boolean oauth2Ready = !oauth2Required;
        boolean oauth2UsedStaleToken = false;
        boolean oauth2SentWithoutToken = false;
        TokenStore.TokenEntry pendingOAuth2Entry = null;
        Map<String, String> pendingOAuth2Variables = new LinkedHashMap<>();
        ApiRequest requestForBuild = effectiveRequest;
        String preOAuth2ResolvedUrl = resolver.resolve(
                requestForBuild != null ? requestForBuild.url : null
        );
        boolean scriptMutatedDestination =
                scriptResult != null && scriptResult.mutatedRequest != null
                        || !finalResolvedVariables.equals(baselineResolver.getVariables());
        if (scriptMutatedDestination
                && isInvalidEffectiveDestination(preOAuth2ResolvedUrl)) {
            List<String> preOAuth2UnresolvedVariables = new ArrayList<>(
                    RequestBuilder.findUnresolvedTokens(
                            preOAuth2ResolvedUrl != null
                                    ? preOAuth2ResolvedUrl.getBytes(
                                            java.nio.charset.StandardCharsets.UTF_8
                                    )
                                    : new byte[0]
                    )
            );
            return blockInvalidEffectiveDestination(
                    result,
                    preOAuth2UnresolvedVariables,
                    originalResolvedUrl,
                    preOAuth2ResolvedUrl,
                    oauth2Required,
                    oauth2Ready,
                    preRequestScriptFailed,
                    preRequestScriptTimedOut,
                    policyOverrides,
                    resolver
            );
        }
        if (intent != ExecutionIntent.PREVIEW && oauth2Required) {
            try {
                OAuth2Config config = OAuth2Config.fromVariables(resolver.getVariables());
                if (config == null || !config.isValid() || oauth2Manager == null) {
                    throw new IllegalStateException("OAuth2 configuration unavailable");
                }
                TokenStore.TokenEntry entry = oauth2Manager.getValidToken(config);
                if (entry == null || entry.accessToken == null || entry.accessToken.isBlank()) {
                    throw new IllegalStateException("OAuth2 token acquisition failed");
                }
                pendingOAuth2Entry = entry;
                oauth2Ready = true;
                pendingOAuth2Variables.put("oauth2_access_token", entry.accessToken);
                if (entry.refreshToken != null && !entry.refreshToken.isBlank()) {
                    pendingOAuth2Variables.put("oauth2_refresh_token", entry.refreshToken);
                }
            } catch (Exception oauth2Error) {
                oauth2Ready = false;
                switch (effectivePolicy.oauth2FailureMode != null ? effectivePolicy.oauth2FailureMode : ExecutionPolicy.OAuth2FailureMode.ABORT) {
                    case USE_STALE_TOKEN -> {
                        String staleToken = findStaleOAuth2Token(effectiveRequest, executionCollection, activeEnvironment, resolver);
                        if (staleToken == null || staleToken.isBlank()) {
                            if (result != null) {
                                result.preflightStatus = ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE;
                                result.preflightMessage = "Request not sent — OAuth2 token acquisition failed.";
                                result.errorMessage = result.preflightMessage;
                                result.preflight = ExecutionPreflightResult.blocked(
                                        ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE,
                                        result.preflightMessage,
                                        List.of(),
                                        originalResolvedUrl,
                                        originalResolvedUrl,
                                        originDisplay(originalResolvedUrl),
                                        originDisplay(originalResolvedUrl),
                                        false,
                                        true,
                                        false,
                                        preRequestScriptFailed,
                                        preRequestScriptTimedOut,
                                        false,
                                        false,
                                        List.of(ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE),
                                        List.of()
                                );
                                result.success = false;
                                return result;
                            }
                        } else {
                            resolver.mutableVariables().put("oauth2_access_token", staleToken);
                            oauth2UsedStaleToken = true;
                            oauth2Ready = true;
                            policyOverrides.add("OAuth2 stale-token override");
                        }
                    }
                    case SEND_WITHOUT_TOKEN -> {
                        requestForBuild = burp.scripts.ScriptExecutionContext.copyRequest(effectiveRequest);
                        if (requestForBuild != null) {
                            requestForBuild.auth = null;
                        }
                        oauth2SentWithoutToken = true;
                        oauth2Ready = false;
                        policyOverrides.add("OAuth2 send-without-token override");
                    }
                    default -> {
                        if (result != null) {
                            result.preflightStatus = ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE;
                            result.preflightMessage = "Request not sent — OAuth2 token acquisition failed.";
                            result.errorMessage = result.preflightMessage;
                            result.preflight = ExecutionPreflightResult.blocked(
                                    ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE,
                                    result.preflightMessage,
                                    List.of(),
                                    originalResolvedUrl,
                                    originalResolvedUrl,
                                    originDisplay(originalResolvedUrl),
                                    originDisplay(originalResolvedUrl),
                                    false,
                                    true,
                                    false,
                                    preRequestScriptFailed,
                                    preRequestScriptTimedOut,
                                    false,
                                    false,
                                    List.of(ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE),
                                    List.of()
                            );
                            result.success = false;
                            result.oauth2Required = true;
                            result.oauth2Ready = false;
                            result.requestSent = false;
                        }
                        return result;
                    }
                }
            }
        }
        if (!pendingOAuth2Variables.isEmpty()) {
            resolver.addAll(pendingOAuth2Variables);
        }

        ExecutionPreflightResult preflight = null;
        byte[] rawRequest;
        try {
            rawRequest = requestBuilder.buildRequest(requestForBuild, resolver);
        } catch (Exception buildError) {
            if (result != null) {
                result.success = false;
                result.errorMessage = extractCleanError(buildError);
                result.preflightStatus = ExecutionPreflightStatus.BLOCKED_POLICY;
                result.preflightMessage = "Request not sent — execution policy blocked transmission.";
                result.preflight = ExecutionPreflightResult.blocked(
                        ExecutionPreflightStatus.BLOCKED_POLICY,
                        result.preflightMessage,
                        List.of(),
                        originalResolvedUrl,
                        originalResolvedUrl,
                        originDisplay(originalResolvedUrl),
                        originDisplay(originalResolvedUrl),
                        false,
                        oauth2Required,
                        oauth2Ready,
                        preRequestScriptFailed,
                        preRequestScriptTimedOut,
                        false,
                        false,
                        List.of(ExecutionPreflightStatus.BLOCKED_POLICY),
                        policyOverrides
                );
            }
            return result;
        }
        String rawRequestText = new String(rawRequest, java.nio.charset.StandardCharsets.UTF_8);
        String resolvedUrl = resolver.resolve(requestForBuild != null ? requestForBuild.url : null);
        String effectiveOrigin = originDisplay(resolvedUrl);
        String originalOrigin = originDisplay(originalResolvedUrl);
        boolean targetChanged = hasTargetChanged(originalResolvedUrl, resolvedUrl);
        Set<String> unresolvedTokens = RequestBuilder.findUnresolvedTokens(rawRequest);
        List<String> unresolvedVariables = new ArrayList<>(unresolvedTokens);
        if (result != null) {
            result.rawRequestBytes = rawRequest;
            result.rawRequestText = rawRequestText;
            result.resolvedVariables = new LinkedHashMap<>(resolver.getVariables());
            result.requestHeaders = splitRawRequest(rawRequest)[0];
            result.requestBody = splitRawRequest(rawRequest)[1];
            result.resolvedUrl = resolvedUrl;
            result.originalResolvedUrl = originalResolvedUrl;
            result.effectiveResolvedUrl = resolvedUrl;
            result.oauth2Required = oauth2Required;
            result.oauth2Ready = oauth2Ready;
            result.oauth2UsedStaleToken = oauth2UsedStaleToken;
            result.oauth2SentWithoutToken = oauth2SentWithoutToken;
            result.policyOverridesApplied.clear();
            result.policyOverridesApplied.addAll(policyOverrides);
            result.continuedAfterScriptFailure = continuedAfterScriptFailure;
            result.unresolvedVariablesAllowed = false;
            result.targetChangeAllowed = false;
            result.requestSent = false;
            result.responseTimedOut = false;
        }

        if (intent == ExecutionIntent.PREVIEW) {
            if (result != null) {
                result.preflightStatus = ExecutionPreflightStatus.PREVIEW_ONLY;
                result.preflightMessage = "Preview only — request not sent.";
                result.preflight = ExecutionPreflightResult.preview(
                        result.preflightMessage,
                        unresolvedVariables,
                        originalResolvedUrl,
                        resolvedUrl,
                        originalOrigin,
                        effectiveOrigin,
                        List.of()
                );
                result.success = true;
            }
            return result;
        }

        boolean confirmationRequired = false;
        List<ExecutionPreflightStatus> reasons = new ArrayList<>();

        if (targetChanged) {
            switch (effectivePolicy.targetChangeMode != null ? effectivePolicy.targetChangeMode : ExecutionPolicy.TargetChangeMode.REQUIRE_CONFIRMATION) {
                case ALLOW -> policyOverrides.add("Target change override");
                case REQUIRE_CONFIRMATION -> {
                    confirmationRequired = true;
                    reasons.add(ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE);
                }
                case ABORT -> reasons.add(ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE);
            }
        }
        if (!unresolvedVariables.isEmpty()) {
            switch (effectivePolicy.unresolvedVariableMode != null ? effectivePolicy.unresolvedVariableMode : ExecutionPolicy.UnresolvedVariableMode.REQUIRE_CONFIRMATION) {
                case ALLOW_WITH_WARNING -> policyOverrides.add("Unresolved variables override");
                case REQUIRE_CONFIRMATION -> {
                    confirmationRequired = true;
                    reasons.add(ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES);
                }
                case ABORT -> reasons.add(ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES);
            }
        }

        if (!reasons.isEmpty() && !confirmationRequired) {
            ExecutionPreflightStatus status = reasons.contains(ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE)
                    ? ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE
                    : ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES;
            String message = status == ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE
                    ? "Request not sent — script-driven destination change was not approved."
                    : "Request not sent — unresolved variables were not approved.";
            preflight = ExecutionPreflightResult.blocked(status, message, unresolvedVariables, originalResolvedUrl, resolvedUrl, originalOrigin, effectiveOrigin, targetChanged, oauth2Required, oauth2Ready, preRequestScriptFailed, preRequestScriptTimedOut, false, false, reasons, policyOverrides);
            if (result != null) {
                result.preflightStatus = status;
                result.preflightMessage = message;
                result.errorMessage = result.preflightMessage;
                result.preflight = preflight;
                result.success = false;
                result.requestSent = false;
                result.originalResolvedUrl = originalResolvedUrl;
                result.effectiveResolvedUrl = resolvedUrl;
                result.oauth2Required = oauth2Required;
                result.oauth2Ready = oauth2Ready;
                result.oauth2UsedStaleToken = oauth2UsedStaleToken;
                result.oauth2SentWithoutToken = oauth2SentWithoutToken;
            }
            return result;
        }

        if (confirmationRequired) {
            ExecutionPreflightResult confirmationPreflight = ExecutionPreflightResult.ready(
                    "Confirmation required for request " + (req != null && req.name != null ? req.name : ""),
                    unresolvedVariables,
                    originalResolvedUrl,
                    resolvedUrl,
                    originalOrigin,
                    effectiveOrigin,
                    targetChanged,
                    oauth2Required,
                    oauth2Ready,
                    preRequestScriptFailed,
                    preRequestScriptTimedOut,
                    true,
                    false,
                    reasons,
                    policyOverrides
            );
            boolean approved = preflightDecisionHandler != null && preflightDecisionHandler.confirm(confirmationPreflight);
            if (approved) {
                preflight = ExecutionPreflightResult.ready(
                        confirmationPreflight.safeMessage,
                        unresolvedVariables,
                        originalResolvedUrl,
                        resolvedUrl,
                        originalOrigin,
                        effectiveOrigin,
                        targetChanged,
                        oauth2Required,
                        oauth2Ready,
                        preRequestScriptFailed,
                        preRequestScriptTimedOut,
                        true,
                        true,
                        reasons,
                        policyOverrides
                );
            }
            if (!approved) {
                ExecutionPreflightStatus status = reasons.contains(ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE)
                        ? ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE
                        : ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES;
                String message = status == ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE
                        ? "Request not sent — script-driven destination change was not approved."
                        : "Request not sent — unresolved variables were not approved.";
                if (preflightDecisionHandler == null) {
                    status = ExecutionPreflightStatus.BLOCKED_POLICY;
                    message = "Request not sent — preflight confirmation was required but unavailable.";
                }
                if (result != null) {
                    result.preflightStatus = status;
                    result.preflightMessage = message;
                    result.errorMessage = result.preflightMessage;
                    result.preflight = ExecutionPreflightResult.blocked(status, message, unresolvedVariables, originalResolvedUrl, resolvedUrl, originalOrigin, effectiveOrigin, targetChanged, oauth2Required, oauth2Ready, preRequestScriptFailed, preRequestScriptTimedOut, true, false, reasons, policyOverrides);
                    result.success = false;
                    result.requestSent = false;
                }
                return result;
            }
        } else {
            preflight = ExecutionPreflightResult.ready(
                    "Ready to send request " + (req != null && req.name != null ? req.name : ""),
                    unresolvedVariables,
                    originalResolvedUrl,
                    resolvedUrl,
                    originalOrigin,
                    effectiveOrigin,
                    targetChanged,
                    oauth2Required,
                    oauth2Ready,
                    preRequestScriptFailed,
                    preRequestScriptTimedOut,
                    false,
                    false,
                    reasons,
                    policyOverrides
            );
        }

        if (result != null) {
            result.preflight = preflight;
            result.preflightStatus = ExecutionPreflightStatus.READY;
            result.preflightMessage = preflight.safeMessage;
            result.initialResolvedUrl = resolvedUrl;
            result.finalResolvedUrl = resolvedUrl;
            result.targetChangeAllowed = targetChanged && (effectivePolicy.targetChangeMode == ExecutionPolicy.TargetChangeMode.ALLOW || (preflight != null && preflight.confirmationAccepted));
            result.unresolvedVariablesAllowed = !unresolvedVariables.isEmpty() && (effectivePolicy.unresolvedVariableMode == ExecutionPolicy.UnresolvedVariableMode.ALLOW_WITH_WARNING || (preflight != null && preflight.confirmationAccepted));
            result.oauth2Required = oauth2Required;
            result.oauth2Ready = oauth2Ready;
            result.oauth2UsedStaleToken = oauth2UsedStaleToken;
            result.oauth2SentWithoutToken = oauth2SentWithoutToken;
            result.policyOverridesApplied.clear();
            result.policyOverridesApplied.addAll(policyOverrides);
            result.originalResolvedUrl = originalResolvedUrl;
            result.effectiveResolvedUrl = resolvedUrl;
            result.resolvedVariables = new LinkedHashMap<>(resolver.getVariables());
        }

        if (isInvalidEffectiveDestination(resolvedUrl)) {
            return blockInvalidEffectiveDestination(
                    result,
                    unresolvedVariables,
                    originalResolvedUrl,
                    resolvedUrl,
                    oauth2Required,
                    oauth2Ready,
                    preRequestScriptFailed,
                    preRequestScriptTimedOut,
                    policyOverrides,
                    resolver
            );
        }
        if (result != null) {
            commitScriptVariableMutations(scriptResult, runtimeOverlay, runtimeVariableSink, col, req, activeEnvironment, effectiveSource);
        }
        if (pendingOAuth2Entry != null) {
            Map<String, String> storedVars = null;
            if (oauth2TokenSink != null) {
                storedVars = oauth2TokenSink.store(col, pendingOAuth2Entry);
            } else if (col != null) {
                col.putRuntimeOAuth2("oauth2_access_token", pendingOAuth2Entry.accessToken);
                storedVars = new LinkedHashMap<>();
                storedVars.put("oauth2_access_token", pendingOAuth2Entry.accessToken);
                if (pendingOAuth2Entry.refreshToken != null && !pendingOAuth2Entry.refreshToken.isBlank()) {
                    col.putRuntimeOAuth2("oauth2_refresh_token", pendingOAuth2Entry.refreshToken);
                    storedVars.put("oauth2_refresh_token", pendingOAuth2Entry.refreshToken);
                }
            }
            if (storedVars != null && !storedVars.isEmpty()) {
                resolver.addAll(storedVars);
                try {
                    rawRequest = requestBuilder.buildRequest(requestForBuild, resolver);
                    rawRequestText = new String(rawRequest, java.nio.charset.StandardCharsets.UTF_8);
                    resolvedUrl = resolver.resolve(requestForBuild != null ? requestForBuild.url : null);
                    effectiveOrigin = originDisplay(resolvedUrl);
                    if (result != null) {
                        result.rawRequestBytes = rawRequest;
                        result.rawRequestText = rawRequestText;
                        result.resolvedVariables = new LinkedHashMap<>(resolver.getVariables());
                        result.requestHeaders = splitRawRequest(rawRequest)[0];
                        result.requestBody = splitRawRequest(rawRequest)[1];
                        result.resolvedUrl = resolvedUrl;
                        result.effectiveResolvedUrl = resolvedUrl;
                    }
                } catch (Exception rebuildError) {
                    if (result != null) {
                        result.success = false;
                        result.errorMessage = extractCleanError(rebuildError);
                        result.requestSent = false;
                    }
                    return result;
                }
            }
        }

        if (api == null) {
            result.success = false;
            result.errorMessage = "Montoya API unavailable";
            return result;
        }

        HttpUtils.ParsedTarget parsed;
        try {
            parsed = HttpUtils.parseTargetForRequest(resolvedUrl);
        } catch (Exception e) {
            result.success = false;
            result.errorMessage = extractCleanError(e);
            return result;
        }

        HttpRequest httpRequest;
        byte[] requestBytes = rawRequest;
        try {
            HttpService service = HttpService.httpService(parsed.host, parsed.port, parsed.useHttps);
            httpRequest = burp.api.montoya.http.message.requests.HttpRequest.httpRequest(
                    service, burp.api.montoya.core.ByteArray.byteArray(requestBytes));
        } catch (Throwable factoryError) {
            String fallbackRaw = rawRequestText;
            httpRequest = HistoryNativeHttpMessageFactory.request(fallbackRaw);
        }
        result.builtRequest = httpRequest;

        long startTime = System.currentTimeMillis();
        RedirectExecutor redirectExecutor = new RedirectExecutor();
        RedirectExecutor.RedirectRequest redirectRequest = new RedirectExecutor.RedirectRequest();
        redirectRequest.initialRequest = httpRequest;
        redirectRequest.initialUrl = resolvedUrl;
        redirectRequest.initialRawRequestBytes = requestBytes.clone();
        redirectRequest.followRedirects = followRedirects;
        redirectRequest.redirectPolicy = redirectPolicy != null ? redirectPolicy : RedirectPolicy.defaults();
        redirectRequest.responseTimeoutMillis = effectivePolicy.responseTimeoutMillis;
        RequestOptions requestOptions;
        try {
            requestOptions = createRequestOptions(effectivePolicy.responseTimeoutMillis);
        } catch (RuntimeException optionError) {
            result.success = false;
            result.errorMessage = extractCleanError(optionError);
            result.preflightStatus = ExecutionPreflightStatus.BLOCKED_POLICY;
            result.preflightMessage = "Request not sent — execution policy blocked transmission.";
            result.preflight = ExecutionPreflightResult.blocked(
                    ExecutionPreflightStatus.BLOCKED_POLICY,
                    result.preflightMessage,
                    unresolvedVariables,
                    originalResolvedUrl,
                    resolvedUrl,
                    originalOrigin,
                    effectiveOrigin,
                    targetChanged,
                    oauth2Required,
                    oauth2Ready,
                    preRequestScriptFailed,
                    preRequestScriptTimedOut,
                    false,
                    false,
                    reasons,
                    policyOverrides
            );
            return result;
        }
        redirectRequest.hopSender = request -> {
            result.requestSent = true;
            return api.http().sendRequest(request, requestOptions);
        };

        RedirectExecutor.RedirectResult redirectResult = redirectExecutor.execute(redirectRequest);
        long endTime = System.currentTimeMillis();
        result.elapsedMs = Math.max(0L, endTime - startTime);
        result.response = redirectResult.finalResponse;
        result.finalRequest = redirectResult.finalRequest;
        result.finalResolvedUrl = redirectResult.finalUrl;
        result.redirectTerminationReason = redirectResult.terminationReason;
        result.responseTimedOut = redirectResult.responseTimedOut;
        result.timeoutMillis = redirectResult.timeoutMillis > 0 ? redirectResult.timeoutMillis : effectivePolicy.responseTimeoutMillis;
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

        if (redirectResult.responseTimedOut) {
            result.preflightStatus = ExecutionPreflightStatus.READY;
            result.preflightMessage = "Request sent, but response timed out after " + result.timeoutMillis + " ms.";
            result.success = false;
            result.responseTimedOut = true;
        } else if (!redirectResult.success && result.errorMessage == null) {
            result.errorMessage = "No response received";
        }

        if (result.response != null && result.response.response() != null) {
            if (redirectResult.success && (shouldUseUnifiedRuntime() || scriptEngine != null) && col != null) {
                Map<String, String> postExecutionOverlay = new LinkedHashMap<>(result.resolvedVariables);
                String body = result.response.response().bodyToString();
                if (body == null) {
                    body = "";
                }
                int statusCode = result.response.response().statusCode();
                Map<String, List<String>> headersMap = new HashMap<>();
                for (var header : result.response.response().headers()) {
                    headersMap.computeIfAbsent(header.name().toLowerCase(), k -> new ArrayList<>()).add(header.value());
                }
                burp.models.RunnerResult scriptResultHolder = new burp.models.RunnerResult();
                scriptResultHolder.responseBody = body;
                scriptResultHolder.responseBodyPreview = body.length() > 500 ? body.substring(0, 500) + "..." : body;
                scriptResultHolder.statusCode = statusCode;
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
                            scriptResultHolder,
                            scriptDependentExecutor,
                            postExecutionOverlay
                    );
                    mergeScriptResult(result, postResult);
                    mergeScriptResult(scriptResultHolder, postResult);
                    recordScriptDiagnostic(effectiveSource, col, effectiveRequest, activeEnvironment, postResult, "Post-response completed");
                    if (!postResult.timedOut && !postResult.cancelled) {
                        commitScriptVariableMutations(postResult, result.resolvedVariables, runtimeVariableSink, col, req, activeEnvironment, effectiveSource);
                    }
                } else if (redirectResult.success && scriptEngine != null && scriptEngine.getScriptMode() == ScriptMode.LIMITED && col != null) {
                    scriptEngine.executePostResponse(effectiveRequest, resolver, new LinkedHashMap<>(resolver.getVariables()), scriptResultHolder, body, statusCode, headersMap);
                }
                if (result != null && !preRequestScriptFailed) {
                    result.success = redirectResult.success;
                }
            }
        } else if (result.success) {
            result.success = false;
            result.errorMessage = result.errorMessage != null ? result.errorMessage : "No response received";
        }

        if (preRequestScriptFailed) {
            continuedAfterScriptFailure = effectivePolicy.scriptFailureMode == ExecutionPolicy.ScriptFailureMode.CONTINUE;
            if (continuedAfterScriptFailure) {
                result.continuedAfterScriptFailure = true;
                result.success = false;
            }
        }

        if (result != null) {
            result.preflight = preflight != null ? preflight : result.preflight;
            if (result.preflightStatus == null) {
                result.preflightStatus = ExecutionPreflightStatus.READY;
            }
            result.continuedAfterScriptFailure = continuedAfterScriptFailure;
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

    private ApiRequest applyScriptResultToRequest(ApiRequest currentRequest, ScriptExecutionResult scriptResult) {
        if (scriptResult == null || scriptResult.mutatedRequest == null) {
            return currentRequest;
        }
        return scriptResult.mutatedRequest;
    }

    private RequestOptions createRequestOptions(int timeoutMillis) {
        if (requestOptionsFactory == null) {
            throw new IllegalStateException("Request options factory unavailable");
        }
        RequestOptions options = requestOptionsFactory.create(timeoutMillis);
        if (options == null) {
            throw new IllegalStateException("Request options factory returned no options");
        }
        return options;
    }

    private static RequestOptions defaultRequestOptions(int timeoutMillis) {
        return RequestOptions.requestOptions()
                .withRedirectionMode(RedirectionMode.NEVER)
                .withResponseTimeout(timeoutMillis);
    }

    private static boolean isInvalidEffectiveDestination(String resolvedUrl) {
        if (resolvedUrl == null || resolvedUrl.isBlank()) {
            return true;
        }

        if (originDisplay(resolvedUrl).isBlank()) {
            return true;
        }

        try {
            HttpUtils.ParsedTarget parsed =
                    HttpUtils.parseTargetForRequest(resolvedUrl);
            if (parsed == null
                    || parsed.host == null
                    || parsed.host.isBlank()) {
                return true;
            }

            return parsed.host.contains("{")
                    || parsed.host.contains("}");
        } catch (Exception ignored) {
            return true;
        }
    }

    private static ExecutionResult blockInvalidEffectiveDestination(
            ExecutionResult result,
            List<String> unresolvedVariables,
            String originalResolvedUrl,
            String effectiveResolvedUrl,
            boolean oauth2Required,
            boolean oauth2Ready,
            boolean preRequestScriptFailed,
            boolean preRequestScriptTimedOut,
            List<String> policyOverrides,
            VariableResolver resolver) {
        if (result == null) {
            return null;
        }

        String message =
                "Request not sent — execution policy blocked transmission.";
        String originalOrigin = originDisplay(originalResolvedUrl);
        String effectiveOrigin = originDisplay(effectiveResolvedUrl);
        boolean targetChanged = hasTargetChanged(
                originalResolvedUrl,
                effectiveResolvedUrl
        );
        List<String> safeUnresolvedVariables =
                unresolvedVariables != null
                        ? unresolvedVariables
                        : List.of();
        List<String> safePolicyOverrides =
                policyOverrides != null
                        ? policyOverrides
                        : List.of();

        result.success = false;
        result.requestSent = false;
        result.responseTimedOut = false;
        result.preflightStatus = ExecutionPreflightStatus.BLOCKED_POLICY;
        result.preflightMessage = message;
        result.errorMessage = message;
        result.originalResolvedUrl = originalResolvedUrl;
        result.effectiveResolvedUrl = effectiveResolvedUrl;
        result.oauth2Required = oauth2Required;
        result.oauth2Ready = oauth2Ready;
        result.targetChangeAllowed = false;
        result.unresolvedVariablesAllowed = false;
        result.policyOverridesApplied.clear();
        result.policyOverridesApplied.addAll(safePolicyOverrides);
        result.resolvedVariables = resolver != null
                ? new LinkedHashMap<>(resolver.getVariables())
                : new LinkedHashMap<>();
        result.preflight = ExecutionPreflightResult.blocked(
                ExecutionPreflightStatus.BLOCKED_POLICY,
                message,
                safeUnresolvedVariables,
                originalResolvedUrl,
                effectiveResolvedUrl,
                originalOrigin,
                effectiveOrigin,
                targetChanged,
                oauth2Required,
                oauth2Ready,
                preRequestScriptFailed,
                preRequestScriptTimedOut,
                false,
                false,
                List.of(ExecutionPreflightStatus.BLOCKED_POLICY),
                safePolicyOverrides
        );
        return result;
    }
    private static String originDisplay(String resolvedUrl) {
        try {
            HttpUtils.ParsedTarget parsed = HttpUtils.parseTargetForRequest(resolvedUrl);
            if (parsed == null || parsed.host == null || parsed.host.isBlank()) {
                return "";
            }
            String scheme = parsed.useHttps ? "https" : "http";
            String host = parsed.host.startsWith("[") && parsed.host.endsWith("]")
                    ? parsed.host.substring(1, parsed.host.length() - 1)
                    : parsed.host.toLowerCase(Locale.ROOT);
            int port = parsed.port > 0 ? parsed.port : (parsed.useHttps ? 443 : 80);
            return scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean hasTargetChanged(String originalResolvedUrl, String effectiveResolvedUrl) {
        String original = originDisplay(originalResolvedUrl);
        String effective = originDisplay(effectiveResolvedUrl);

        if (effective.isBlank()) {
            return false;
        }

        if (original.isBlank()) {
            return true;
        }

        return !original.equalsIgnoreCase(effective);
    }

    private String findStaleOAuth2Token(ApiRequest request, ApiCollection collection, EnvironmentProfile activeEnvironment, VariableResolver resolver) {
        try {
            OAuth2Config config = OAuth2Config.fromVariables(resolver != null ? resolver.getVariables() : Collections.emptyMap());
            if (config != null) {
                TokenStore.TokenEntry stored = TokenStore.get(TokenStore.makeKey(config));
                if (stored != null && stored.accessToken != null && !stored.accessToken.isBlank()) {
                    return stored.accessToken;
                }
            }
        } catch (Exception ignored) {
        }
        if (resolver != null) {
            String resolved = resolver.getVariables().get("oauth2_access_token");
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        if (collection != null && collection.runtimeOAuth2 != null) {
            String runtime = collection.runtimeOAuth2.get("oauth2_access_token");
            if (runtime != null && !runtime.isBlank()) {
                return runtime;
            }
        }
        return null;
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
        boolean persistedEnvironmentChanged = false;
        boolean runtimeEnvironmentChanged = false;
        Set<String> warnedGlobalKeys = new LinkedHashSet<>();
        Set<String> warnedEnvironmentKeys = new LinkedHashSet<>();
        Set<String> warnedFolderKeys = new LinkedHashSet<>();
        Map<String, String> persistedEnvironmentBefore = null;
        Map<String, String> persistedEnvironmentAfter = null;
        Map<String, String> runtimeEnvironmentBefore = null;
        Map<String, String> runtimeEnvironmentAfter = null;

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
                        if (persistedEnvironmentBefore == null) {
                            persistedEnvironmentBefore = new LinkedHashMap<>(activeEnvironment.variables);
                            persistedEnvironmentAfter = new LinkedHashMap<>(persistedEnvironmentBefore);
                        }
                    } else if (runtimeEnvironmentBefore == null) {
                        runtimeEnvironmentBefore = new LinkedHashMap<>(activeEnvironment.runtimeVariables);
                        runtimeEnvironmentAfter = new LinkedHashMap<>(runtimeEnvironmentBefore);
                    }
                    if (mutation.persistent) {
                        if (mutation.newValue == null) {
                            persistedEnvironmentAfter.remove(mutation.key);
                        } else {
                            persistedEnvironmentAfter.put(mutation.key, mutation.newValue);
                        }
                    } else if (mutation.newValue == null) {
                        runtimeEnvironmentAfter.remove(mutation.key);
                    } else {
                        runtimeEnvironmentAfter.put(mutation.key, mutation.newValue);
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
                        if (mutation.newValue == null) {
                            boolean existed = col.runtimeVars.containsKey(mutation.key);
                            col.runtimeVars.remove(mutation.key);
                            if (existed) {
                                collectionChanged = true;
                            }
                        } else {
                            String current = col.runtimeVars.put(mutation.key, mutation.newValue);
                            if (!Objects.equals(current, mutation.newValue)) {
                                collectionChanged = true;
                            }
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
                        String current = folder.put(mutation.key, mutation.newValue);
                        if (!Objects.equals(current, mutation.newValue)) {
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

        if (activeEnvironment != null && persistedEnvironmentBefore != null && persistedEnvironmentAfter != null
                && !Objects.equals(persistedEnvironmentBefore, persistedEnvironmentAfter)) {
            persistedEnvironmentChanged = true;
            for (String key : persistedEnvironmentBefore.keySet()) {
                if (!persistedEnvironmentAfter.containsKey(key)) {
                    activeEnvironment.variables.remove(key);
                }
            }
            for (Map.Entry<String, String> entry : persistedEnvironmentAfter.entrySet()) {
                if (!Objects.equals(persistedEnvironmentBefore.get(entry.getKey()), entry.getValue())) {
                    activeEnvironment.variables.put(entry.getKey(), entry.getValue());
                }
            }
        }

        Map<String, String> runtimeEnvironmentChanges = new LinkedHashMap<>();
        Set<String> runtimeEnvironmentRemoved = new LinkedHashSet<>();
        if (activeEnvironment != null && runtimeEnvironmentBefore != null && runtimeEnvironmentAfter != null
                && !Objects.equals(runtimeEnvironmentBefore, runtimeEnvironmentAfter)) {
            for (String key : runtimeEnvironmentBefore.keySet()) {
                if (!runtimeEnvironmentAfter.containsKey(key)) {
                    runtimeEnvironmentRemoved.add(key);
                }
            }
            for (Map.Entry<String, String> entry : runtimeEnvironmentAfter.entrySet()) {
                if (!Objects.equals(runtimeEnvironmentBefore.get(entry.getKey()), entry.getValue())) {
                    runtimeEnvironmentChanges.put(entry.getKey(), entry.getValue());
                }
            }
            runtimeEnvironmentChanged = !runtimeEnvironmentChanges.isEmpty() || !runtimeEnvironmentRemoved.isEmpty();
            if (runtimeEnvironmentChanged) {
                activeEnvironment.ensureDefaults();
                if (runtimeVariableSink != null) {
                    runtimeVariableSink.apply(col, runtimeEnvironmentChanges, runtimeEnvironmentRemoved);
                } else {
                    for (String key : runtimeEnvironmentRemoved) {
                        activeEnvironment.runtimeVariables.remove(key);
                    }
                    for (Map.Entry<String, String> entry : runtimeEnvironmentChanges.entrySet()) {
                        activeEnvironment.runtimeVariables.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
                    }
                }
            }
        }

        if (activeEnvironment != null
                && runtimeVariableSink != null
                && (persistedEnvironmentChanged || runtimeEnvironmentChanged)) {
            runtimeVariableSink.environmentMutationCommitted(activeEnvironment, persistedEnvironmentChanged, runtimeEnvironmentChanged);
        }

        if (collectionChanged || persistedEnvironmentChanged || runtimeEnvironmentChanged
                || !warnedGlobalKeys.isEmpty() || !warnedEnvironmentKeys.isEmpty() || !warnedFolderKeys.isEmpty()) {
            recordDiagnostic(DiagnosticOperation.VARIABLE_RESOLUTION, DiagnosticSeverity.INFO, effectiveSource,
                    col, req, activeEnvironment, "Script variable mutations committed",
                    "collectionChanged=" + collectionChanged
                            + " persistedEnvironmentChanged=" + persistedEnvironmentChanged
                            + " runtimeEnvironmentChanged=" + runtimeEnvironmentChanged
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
