package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class UnresolvedVariablePreflightTest {

    @Test
    void finalRawRequestUnresolvedVariablesAreReturnedStructurally() {
        Harness harness = harness(scriptResult(true, Map.of(), null));
        harness.request.url = "https://example.test/{{first}}/mid/{{second}}?q={{third}}";
        ExecutionPolicy policy = ExecutionPolicy.runnerDefaults(true);
        ExecutionResult result = harness.pipeline.execute(
                harness.request,
                harness.collection,
                false,
                null,
                null,
                null,
                harness.environment,
                ExecutionSource.RUNNER,
                null,
                RedirectPolicy.defaults(),
                policy,
                null
        );

        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES);
        assertThat(result.preflight.unresolvedVariables).containsExactly("first", "second", "third");
    }

    @Test
    void scriptCreatedVariablePreventsFalseMissingVariableBlock() {
        Harness harness = harness(scriptResult(true, Map.of("token", "resolved"), null));
        harness.request.url = "https://example.test/{{token}}";
        ExecutionResult result = harness.pipeline.execute(
                harness.request,
                harness.collection,
                false,
                null,
                null,
                null,
                harness.environment
        );

        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.READY);
        assertThat(harness.sendCount.get()).isEqualTo(1);
        assertThat(result.preflight.unresolvedVariables).isEmpty();
    }

    @Test
    void scriptIntroducedMissingVariableIsDetected() {
        Harness harness = harness(scriptResult(true, Map.of(), null));
        harness.request.url = "https://example.test/{{script_only}}";
        ExecutionPolicy policy = ExecutionPolicy.runnerDefaults(true);
        ExecutionResult result = harness.pipeline.execute(
                harness.request,
                harness.collection,
                false,
                null,
                null,
                null,
                harness.environment,
                ExecutionSource.RUNNER,
                null,
                RedirectPolicy.defaults(),
                policy,
                null
        );

        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES);
        assertThat(result.isBlockedBeforeSend()).isTrue();
        assertThat(harness.sendCount.get()).isZero();
    }

    @Test
    void workbenchConfirmationDeniedBlocksSend() {
        Harness harness = harness(scriptResult(true, Map.of(), null));
        harness.request.url = "https://example.test/{{missing}}";
        AtomicInteger handlerCalls = new AtomicInteger();
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.unresolvedVariableMode = ExecutionPolicy.UnresolvedVariableMode.REQUIRE_CONFIRMATION;
        policy.normalize();

        ExecutionResult result = harness.pipeline.execute(
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
                preflight -> {
                    handlerCalls.incrementAndGet();
                    return false;
                }
        );

        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(harness.sendCount.get()).isZero();
        assertThat(result.isBlockedBeforeSend()).isTrue();
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES);
    }

    @Test
    void workbenchConfirmationAcceptedSendsWithoutRerunningScript() {
        Harness harness = harness(scriptResult(true, Map.of(), null));
        harness.request.url = "https://example.test/{{missing}}";
        AtomicInteger handlerCalls = new AtomicInteger();
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.unresolvedVariableMode = ExecutionPolicy.UnresolvedVariableMode.REQUIRE_CONFIRMATION;
        policy.normalize();

        ExecutionResult result = harness.pipeline.execute(
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
                preflight -> {
                    handlerCalls.incrementAndGet();
                    return true;
                }
        );

        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(harness.runtime.calls.get()).isEqualTo(1);
        assertThat(harness.sendCount.get()).isEqualTo(1);
        assertThat(result.requestSent).isTrue();
    }

    @Test
    void confirmationHandlerCalledExactlyOnceForMultipleMissingVariables() {
        Harness harness = harness(scriptResult(true, Map.of(), null));
        harness.request.url = "https://example.test/{{first}}/{{second}}";
        AtomicInteger handlerCalls = new AtomicInteger();
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.unresolvedVariableMode = ExecutionPolicy.UnresolvedVariableMode.REQUIRE_CONFIRMATION;
        policy.normalize();

        harness.pipeline.execute(
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
                preflight -> {
                    handlerCalls.incrementAndGet();
                    return true;
                }
        );

        assertThat(handlerCalls.get()).isEqualTo(1);
    }

    @Test
    void previewNeverPromptsOrSends() {
        Harness harness = harness(scriptResult(true, Map.of(), null));
        harness.request.url = "https://example.test/{{missing}}";
        AtomicInteger handlerCalls = new AtomicInteger();

        ExecutionResult result = harness.pipeline.build(
                harness.request,
                harness.collection,
                null,
                null,
                null,
                harness.environment
        );

        assertThat(handlerCalls.get()).isZero();
        assertThat(harness.sendCount.get()).isZero();
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.PREVIEW_ONLY);
    }

    @Test
    void runnerAbortModeBlocksWithoutModalHandler() {
        Harness harness = harness(scriptResult(true, Map.of(), null));
        harness.request.url = "https://example.test/{{missing}}";
        ExecutionPolicy policy = ExecutionPolicy.runnerDefaults(true);

        ExecutionResult result = harness.pipeline.execute(
                harness.request,
                harness.collection,
                false,
                null,
                null,
                null,
                harness.environment,
                ExecutionSource.RUNNER,
                null,
                RedirectPolicy.defaults(),
                policy,
                null
        );

        assertThat(harness.sendCount.get()).isZero();
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES);
    }

    @Test
    void runnerAllowModeSendsWithVisibleWarning() {
        Harness harness = harness(scriptResult(true, Map.of(), null));
        harness.request.url = "https://example.test/{{missing}}";
        ExecutionPolicy policy = ExecutionPolicy.runnerDefaults(false);

        ExecutionResult result = harness.pipeline.execute(
                harness.request,
                harness.collection,
                false,
                null,
                null,
                null,
                harness.environment,
                ExecutionSource.RUNNER,
                null,
                RedirectPolicy.defaults(),
                policy,
                null
        );

        assertThat(harness.sendCount.get()).isEqualTo(1);
        assertThat(result.unresolvedVariablesAllowed).isTrue();
        assertThat(result.policyOverridesApplied).contains("Unresolved variables override");
    }

    @Test
    void blockedUnresolvedRequestCommitsNoScriptMutation() {
        Harness harness = harness(scriptResult(true, Map.of(), scriptMutation("token", "value")));
        harness.request.url = "https://example.test/{{missing}}";
        ExecutionResult result = harness.pipeline.execute(
                harness.request,
                harness.collection,
                false,
                null,
                null,
                null,
                harness.environment
        );

        assertThat(result.isBlockedBeforeSend()).isTrue();
        assertThat(harness.environment.variables).doesNotContainKey("token");
        assertThat(harness.environment.runtimeVariables).doesNotContainKey("token");
        assertThat(harness.sendCount.get()).isZero();
    }

    private static Harness harness(ScriptExecutionResult scriptResult) {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> captured = new CopyOnWriteArrayList<>();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(sendCount, captured, () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain"));
        StubRuntime runtime = new StubRuntime(api, scriptResult);
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test/{{missing}}";
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Env";
        SharedRequestPipeline pipeline = new SharedRequestPipeline(
                api,
                new RequestBuilder(null),
                new ScriptEngine(null, ScriptMode.FULL_JS),
                Mockito.mock(burp.auth.OAuth2Manager.class),
                runtime,
                timeout -> Mockito.mock(RequestOptions.class)
        );
        return new Harness(pipeline, collection, request, environment, sendCount, runtime);
    }

    private static ScriptExecutionResult scriptResult(boolean success,
                                                      Map<String, String> effectiveVariables,
                                                      ScriptVariableMutation mutation) {
        ScriptExecutionResult result = new ScriptExecutionResult();
        result.success = success;
        result.effectiveVariables.putAll(effectiveVariables);
        if (mutation != null) {
            result.variableMutations.add(mutation);
        }
        return result;
    }

    private static ScriptVariableMutation scriptMutation(String key, String value) {
        ScriptVariableMutation mutation = new ScriptVariableMutation();
        mutation.scope = "environment";
        mutation.key = key;
        mutation.newValue = value;
        mutation.persistent = true;
        return mutation;
    }

    private record Harness(SharedRequestPipeline pipeline,
                           ApiCollection collection,
                           ApiRequest request,
                           EnvironmentProfile environment,
                           AtomicInteger sendCount,
                           StubRuntime runtime) {
    }

    private static final class StubRuntime extends UnifiedScriptRuntime {
        private final ScriptExecutionResult result;
        private final AtomicInteger calls = new AtomicInteger();

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
            calls.incrementAndGet();
            return result;
        }
    }
}
