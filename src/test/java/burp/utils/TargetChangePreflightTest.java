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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TargetChangePreflightTest {

    @Test
    void sameHostPathMutationDoesNotTriggerTargetChange() {
        Harness harness = harness(mutation("https://example.test/other/path?q=1"));
        ExecutionResult result = harness.pipeline.execute(
                harness.request, harness.collection, false, null, null, null, harness.environment
        );

        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.READY);
        assertThat(result.targetChangeAllowed).isFalse();
        assertThat(harness.sendCount.get()).isEqualTo(1);
    }

    @Test
    void queryOnlyMutationDoesNotTriggerTargetChange() {
        Harness harness = harness(mutation("https://example.test/start?q=2"));
        ExecutionResult result = harness.pipeline.execute(harness.request, harness.collection, false, null, null, null, harness.environment);

        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.READY);
        assertThat(result.targetChangeAllowed).isFalse();
        assertThat(harness.sendCount.get()).isEqualTo(1);
    }

    @Test
    void hostChangeTriggersTargetChange() {
        Harness harness = harness(mutation("https://other.example.test/start"));
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

        assertThat(result.preflight.targetChanged).isTrue();
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE);
        assertThat(harness.sendCount.get()).isZero();
    }

    @Test
    void schemeChangeTriggersTargetChange() {
        Harness harness = harness(mutation("http://example.test/start"));
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

        assertThat(result.preflight.targetChanged).isTrue();
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE);
    }

    @Test
    void portChangeTriggersTargetChange() {
        Harness harness = harness(mutation("https://example.test:8443/start"));
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

        assertThat(result.preflight.targetChanged).isTrue();
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE);
    }

    @Test
    void hostnameComparisonIsCaseInsensitive() {
        Harness harness = harness(mutation("https://EXAMPLE.TEST/start"));
        ExecutionResult result = harness.pipeline.execute(harness.request, harness.collection, false, null, null, null, harness.environment);

        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.READY);
        assertThat(result.preflight.targetChanged).isFalse();
    }

    @Test
    void defaultPortsNormalizeCorrectly() {
        Harness harness = harness(mutation("https://example.test/start"));
        harness.request.url = "https://example.test:443/start";
        ExecutionResult result = harness.pipeline.execute(harness.request, harness.collection, false, null, null, null, harness.environment);

        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.READY);
    }

    @Test
    void unverifiableOriginalOriginRequiresWorkbenchConfirmationAndDenialBlocks() {
        Harness harness = harness(mutation("https://resolved.example/start"));
        harness.request.url = "https://{{host}}/start";
        AtomicInteger confirmationCalls = new AtomicInteger();

        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
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
                    confirmationCalls.incrementAndGet();
                    return false;
                }
        );

        assertThat(confirmationCalls.get()).isEqualTo(1);
        assertThat(result.preflightStatus)
                .isEqualTo(ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE);
        assertThat(result.preflight).isNotNull();
        assertThat(result.preflight.targetChanged).isTrue();
        assertThat(result.requestSent).isFalse();
        assertThat(harness.sendCount.get()).isZero();
    }

    @Test
    void unverifiableOriginalOriginCanProceedOnlyAfterWorkbenchApproval() {
        Harness harness = harness(mutation("https://resolved.example/start"));
        harness.request.url = "https://{{host}}/start";
        AtomicInteger confirmationCalls = new AtomicInteger();

        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
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
                    confirmationCalls.incrementAndGet();
                    return true;
                }
        );

        assertThat(confirmationCalls.get()).isEqualTo(1);
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.READY);
        assertThat(result.preflight).isNotNull();
        assertThat(result.preflight.targetChanged).isTrue();
        assertThat(result.preflight.confirmationAccepted).isTrue();
        assertThat(result.targetChangeAllowed).isTrue();
        assertThat(result.requestSent).isTrue();
        assertThat(harness.sendCount.get()).isEqualTo(1);
    }

    @Test
    void unverifiableOriginalOriginIsBlockedByRunnerDefaults() {
        Harness harness = harness(mutation("https://resolved.example/start"));
        harness.request.url = "https://{{host}}/start";

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
                ExecutionPolicy.runnerDefaults(false),
                null
        );

        assertThat(result.preflightStatus)
                .isEqualTo(ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE);
        assertThat(result.preflight).isNotNull();
        assertThat(result.preflight.targetChanged).isTrue();
        assertThat(result.requestSent).isFalse();
        assertThat(harness.sendCount.get()).isZero();
    }

    @Test
    void explicitAllowCanProceedToValidEffectiveOrigin() {
        Harness harness = harness(mutation("https://resolved.example/start"));
        harness.request.url = "https://{{host}}/start";

        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.targetChangeMode = ExecutionPolicy.TargetChangeMode.ALLOW;
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
                null
        );

        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.READY);
        assertThat(result.targetChangeAllowed).isTrue();
        assertThat(result.policyOverridesApplied).contains("Target change override");
        assertThat(result.requestSent).isTrue();
        assertThat(harness.sendCount.get()).isEqualTo(1);
    }

    @Test
    void unresolvedPathOnVerifiableOriginDoesNotTriggerTargetChange() {
        Harness harness = harness(mutation("https://example.test/final"));
        harness.request.url = "https://example.test/{{path}}";

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
        assertThat(result.preflight).isNotNull();
        assertThat(result.preflight.targetChanged).isFalse();
        assertThat(result.targetChangeAllowed).isFalse();
        assertThat(result.requestSent).isTrue();
        assertThat(harness.sendCount.get()).isEqualTo(1);
    }

    @Test
    void invalidEffectiveOriginRemainsBlockedWhenTargetChangePolicyAllows() {
        Harness harness = harness(mutation("https://{{missing}}/start"));
        harness.request.url = "https://example.test/start";
        harness.runtime.result.variableMutations.add(
                mutationRecord("environment", "should_not_commit", "value", true)
        );

        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.targetChangeMode = ExecutionPolicy.TargetChangeMode.ALLOW;
        policy.unresolvedVariableMode =
                ExecutionPolicy.UnresolvedVariableMode.ALLOW_WITH_WARNING;
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
                null
        );

        assertThat(result.preflightStatus)
                .isEqualTo(ExecutionPreflightStatus.BLOCKED_POLICY);
        assertThat(result.isBlockedBeforeSend()).isTrue();
        assertThat(result.requestSent).isFalse();
        assertThat(harness.sendCount.get()).isZero();
        assertThat(harness.environment.variables)
                .doesNotContainKey("should_not_commit");
    }

    @Test
    void workbenchTargetChangeDeniedBlocksSend() {
        Harness harness = harness(mutation("https://other.example.test/start"));
        AtomicInteger handlerCalls = new AtomicInteger();
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.targetChangeMode = ExecutionPolicy.TargetChangeMode.REQUIRE_CONFIRMATION;
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
        assertThat(result.isBlockedBeforeSend()).isTrue();
        assertThat(harness.sendCount.get()).isZero();
    }

    @Test
    void workbenchTargetChangeAcceptedSendsOnce() {
        Harness harness = harness(mutation("https://other.example.test/start"));
        AtomicInteger handlerCalls = new AtomicInteger();
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.targetChangeMode = ExecutionPolicy.TargetChangeMode.REQUIRE_CONFIRMATION;
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
        assertThat(harness.sendCount.get()).isEqualTo(1);
        assertThat(result.requestSent).isTrue();
    }

    @Test
    void runnerTargetChangeBlocksByDefault() {
        Harness harness = harness(mutation("https://other.example.test/start"));
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

        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE);
        assertThat(harness.sendCount.get()).isZero();
    }

    @Test
    void explicitAllowPolicyProceedsWithWarning() {
        Harness harness = harness(mutation("https://other.example.test/start"));
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.targetChangeMode = ExecutionPolicy.TargetChangeMode.ALLOW;
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
                null
        );

        assertThat(harness.sendCount.get()).isEqualTo(1);
        assertThat(result.targetChangeAllowed).isTrue();
        assertThat(result.policyOverridesApplied).contains("Target change override");
    }

    @Test
    void targetBlockCommitsNoScriptMutation() {
        Harness harness = harness(mutation("https://other.example.test/start"));
        harness.runtime.result.variableMutations.add(mutationRecord("environment", "token", "value", true));

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
        assertThat(harness.sendCount.get()).isZero();
    }

    @Test
    void combinedTargetAndUnresolvedIssuesUseOneConfirmation() {
        Harness harness = harness(mutation("https://other.example.test/{{missing}}"));
        AtomicInteger handlerCalls = new AtomicInteger();
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.targetChangeMode = ExecutionPolicy.TargetChangeMode.REQUIRE_CONFIRMATION;
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
        assertThat(result.preflight.reasons)
                .contains(ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE, ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES);
    }

    private static Harness harness(ApiRequest mutatedRequest) {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> requests = new CopyOnWriteArrayList<>();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(sendCount, requests, () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain"));
        StubRuntime runtime = new StubRuntime(api, mutationResult(mutatedRequest));
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test/start";
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

    private static ScriptExecutionResult mutationResult(ApiRequest mutatedRequest) {
        ScriptExecutionResult result = new ScriptExecutionResult();
        result.success = true;
        result.mutatedRequest = mutatedRequest;
        return result;
    }

    private static ApiRequest mutation(String url) {
        ApiRequest request = new ApiRequest();
        request.id = "mut";
        request.name = "Mutated";
        request.method = "GET";
        request.url = url;
        request.headers = new ArrayList<>();
        request.auth = new ApiRequest.Auth();
        request.auth.properties = new LinkedHashMap<>();
        return request;
    }

    private static ScriptVariableMutation mutationRecord(String scope, String key, String value, boolean persistent) {
        ScriptVariableMutation mutation = new ScriptVariableMutation();
        mutation.scope = scope;
        mutation.key = key;
        mutation.newValue = value;
        mutation.persistent = persistent;
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
}
