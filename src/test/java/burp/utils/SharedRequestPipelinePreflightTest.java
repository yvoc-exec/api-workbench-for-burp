package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.auth.OAuth2Manager;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RedirectPolicy;
import burp.scripts.ExecutionSource;
import burp.scripts.ScriptDependentRequestExecutor;
import burp.scripts.ScriptExecutionResult;
import burp.utils.ScriptMode;
import burp.scripts.ScriptVariableMutation;
import burp.scripts.UnifiedScriptRuntime;
import burp.testsupport.RunnerScriptTestFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SharedRequestPipelinePreflightTest {

    @Test
    void preRequestScriptErrorAbortsByDefault() {
        Harness harness = harness(scriptResult(false, false, false));
        ExecutionResult result = harness.pipeline.execute(
                harness.request,
                harness.collection,
                false,
                null,
                null,
                null,
                harness.environment
        );

        assertThat(harness.sendCount.get()).isZero();
        assertThat(result.isBlockedBeforeSend()).isTrue();
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR);
        assertThat(result.scriptErrors).isNotEmpty();
        assertThat(harness.environment.variables).doesNotContainKey("token");
    }

    @Test
    void directlyThrownPreRequestExceptionProducesStructuredBlockedResult() {
        AtomicInteger scriptInvocations = new AtomicInteger();
        Harness harness = throwingHarness(scriptInvocations);
        AtomicInteger tokenSinkCalls = new AtomicInteger();
        AtomicInteger mutationSinkCalls = new AtomicInteger();

        ExecutionResult result = harness.pipeline.execute(
                harness.request,
                harness.collection,
                false,
                null,
                (collection, entry) -> {
                    tokenSinkCalls.incrementAndGet();
                    return Map.of();
                },
                (collection, changedVars, removedKeys) ->
                        mutationSinkCalls.incrementAndGet(),
                harness.environment
        );

        assertThat(scriptInvocations.get()).isEqualTo(1);
        assertThat(harness.sendCount.get()).isZero();
        assertThat(tokenSinkCalls.get()).isZero();
        assertThat(mutationSinkCalls.get()).isZero();

        assertThat(result.success).isFalse();
        assertThat(result.requestSent).isFalse();
        assertThat(result.rawRequestBytes).isNull();
        assertThat(result.preflightStatus)
                .isEqualTo(ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR);
        assertThat(result.preflightMessage)
                .isEqualTo("Request not sent — pre-request script failed.");
        assertThat(result.errorMessage)
                .isEqualTo("Request not sent — pre-request script failed.");
        assertThat(result.isBlockedBeforeSend()).isTrue();

        assertThat(result.preflight).isNotNull();
        assertThat(result.preflight.maySend).isFalse();
        assertThat(result.preflight.scriptFailed).isTrue();
        assertThat(result.preflight.scriptTimedOut).isFalse();
        assertThat(result.preflight.reasons)
                .containsExactly(ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR);
        assertThat(result.scriptErrors).contains("boom");
    }

    @Test
    void explicitContinuePolicyAllowsSend() {
        Harness harness = harness(scriptResult(true, false, false));
        harness.request.url = "https://example.test/ok";
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.scriptFailureMode = ExecutionPolicy.ScriptFailureMode.CONTINUE;
        policy.normalize();

        ExecutionResult result;
        try (MockedStatic<RequestOptions> mocked = Mockito.mockStatic(RequestOptions.class)) {
            RequestOptions options = Mockito.mock(RequestOptions.class);
            mocked.when(RequestOptions::requestOptions).thenReturn(options);
            Mockito.when(options.withRedirectionMode(Mockito.any())).thenReturn(options);
            Mockito.when(options.withResponseTimeout(Mockito.anyInt())).thenReturn(options);
            result = harness.pipeline.execute(
                    harness.request,
                    harness.collection,
                    false,
                    null,
                    null,
                    null,
                    harness.environment,
                    ExecutionSource.WORKBENCH_SEND,
                    null,
                    RedirectPolicy.defaults(),
                    policy,
                    null
            );
        }

        assertThat(harness.sendCount.get()).isEqualTo(1);
        assertThat(result.requestSent).isTrue();
        assertThat(harness.environment.variables).containsEntry("token", "retained");
    }

    @Test
    void scriptTimeoutAbortsBeforeNetwork() {
        Harness harness = harness(scriptResult(false, true, false));
        ExecutionResult result = harness.pipeline.execute(
                harness.request,
                harness.collection,
                false,
                null,
                null,
                null,
                harness.environment
        );

        assertThat(harness.sendCount.get()).isZero();
        assertThat(result.isBlockedBeforeSend()).isTrue();
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_SCRIPT_TIMEOUT);
    }

    @Test
    void cancelledScriptDoesNotSend() {
        Harness harness = harness(scriptResult(false, false, true));
        ExecutionResult result = harness.pipeline.execute(
                harness.request,
                harness.collection,
                false,
                null,
                null,
                null,
                harness.environment
        );

        assertThat(harness.sendCount.get()).isZero();
        assertThat(result.isBlockedBeforeSend()).isTrue();
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.CANCELLED);
    }

    @Test
    void previewReturnsPreviewOnlyAndNeverSends() {
        Harness harness = harness(scriptResult(true, false, false));
        ExecutionResult result = harness.pipeline.build(
                harness.request,
                harness.collection,
                null,
                null,
                null,
                harness.environment
        );

        assertThat(harness.sendCount.get()).isZero();
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.PREVIEW_ONLY);
        assertThat(result.requestSent).isFalse();
    }

    @Test
    void blockedExecutionDoesNotCallOAuth2TokenSink() {
        Harness harness = harness(scriptResult(false, false, false));
        AtomicInteger tokenSinkCalls = new AtomicInteger();
        ExecutionResult result = harness.pipeline.execute(
                harness.request,
                harness.collection,
                false,
                null,
                (collection, entry) -> {
                    tokenSinkCalls.incrementAndGet();
                    return Map.of();
                },
                null,
                harness.environment
        );

        assertThat(result.isBlockedBeforeSend()).isTrue();
        assertThat(tokenSinkCalls.get()).isZero();
    }

    private static Harness harness(ScriptExecutionResult scriptResult) {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> captured = new CopyOnWriteArrayList<>();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(sendCount, captured, () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain"));
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test/{{token}}";
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Env";
        SharedRequestPipeline pipeline = new SharedRequestPipeline(
                api,
                new RequestBuilder(null),
                new ScriptEngine(null, ScriptMode.FULL_JS),
                Mockito.mock(OAuth2Manager.class),
                new StubRuntime(api, scriptResult),
                timeout -> Mockito.mock(RequestOptions.class)
        );
        return new Harness(pipeline, collection, request, environment, sendCount);
    }

    private static Harness throwingHarness(AtomicInteger scriptInvocations) {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> captured =
                new CopyOnWriteArrayList<>();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(
                sendCount,
                captured,
                () -> RunnerScriptTestFixtures.mockResponse(
                        200,
                        "OK",
                        "text/plain"
                )
        );

        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";

        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test/";

        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Env";

        SharedRequestPipeline pipeline = new SharedRequestPipeline(
                api,
                new RequestBuilder(null),
                new ScriptEngine(null, ScriptMode.FULL_JS),
                Mockito.mock(OAuth2Manager.class),
                new ThrowingRuntime(api, scriptInvocations),
                timeout -> Mockito.mock(RequestOptions.class)
        );

        return new Harness(
                pipeline,
                collection,
                request,
                environment,
                sendCount
        );
    }

    private static ScriptExecutionResult scriptResult(boolean success, boolean timedOut, boolean cancelled) {
        ScriptExecutionResult result = new ScriptExecutionResult();
        result.success = success;
        result.timedOut = timedOut;
        result.cancelled = cancelled;
        if (!success || timedOut || cancelled) {
            result.errors.add("boom");
        }
        result.effectiveVariables.put("token", "retained");
        ScriptVariableMutation mutation = new ScriptVariableMutation();
        mutation.scope = "environment";
        mutation.key = "token";
        mutation.newValue = "retained";
        mutation.persistent = true;
        result.variableMutations.add(mutation);
        return result;
    }

    private record Harness(SharedRequestPipeline pipeline,
                           ApiCollection collection,
                           ApiRequest request,
                           EnvironmentProfile environment,
                           AtomicInteger sendCount) {
    }

    private static final class StubRuntime extends UnifiedScriptRuntime {
        private final ScriptExecutionResult result;

        StubRuntime(MontoyaApi api, ScriptExecutionResult result) {
            super(api, ScriptMode.FULL_JS);
            this.result = result;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public ScriptExecutionResult executePreRequest(ApiCollection collection,
                                                       ApiRequest request,
                                                       EnvironmentProfile activeEnvironment,
                                                       ExecutionSource executionSource,
                                                       int attemptNumber,
                                                       ScriptDependentRequestExecutor dependentRequestExecutor,
                                                       Map<String, String> runtimeOverlay) {
            return result;
        }
    }

    private static final class ThrowingRuntime extends UnifiedScriptRuntime {
        private final AtomicInteger invocations;

        ThrowingRuntime(MontoyaApi api, AtomicInteger invocations) {
            super(api, ScriptMode.FULL_JS);
            this.invocations = invocations;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public ScriptExecutionResult executePreRequest(
                ApiCollection collection,
                ApiRequest request,
                EnvironmentProfile activeEnvironment,
                ExecutionSource executionSource,
                int attemptNumber,
                ScriptDependentRequestExecutor dependentRequestExecutor,
                Map<String, String> runtimeOverlay) {
            invocations.incrementAndGet();
            throw new IllegalStateException("boom");
        }
    }
}
