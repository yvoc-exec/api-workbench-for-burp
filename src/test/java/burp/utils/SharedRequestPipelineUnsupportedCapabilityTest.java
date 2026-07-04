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
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptExecutionResult;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.scripts.ScriptUnsupportedCapability;
import burp.scripts.UnifiedScriptRuntime;
import burp.scripts.UnsupportedScriptCapabilityException;
import burp.scripts.capabilities.ScriptRiskLevel;
import burp.testsupport.RunnerScriptTestFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SharedRequestPipelineUnsupportedCapabilityTest {
    @Test
    void unsupportedCapabilityBlocksNetworkEvenWhenScriptErrorPolicyContinues() {
        AtomicInteger sendCount = new AtomicInteger();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(
                sendCount,
                new CopyOnWriteArrayList<>(),
                () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain"));
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        ApiRequest request = new ApiRequest();
        request.id = "request-id";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.invalid/";
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Environment";

        SharedRequestPipeline pipeline = new SharedRequestPipeline(
                api,
                new RequestBuilder(null),
                new ScriptEngine(null, ScriptMode.FULL_JS),
                Mockito.mock(OAuth2Manager.class),
                new UnsupportedRuntime(api),
                timeout -> Mockito.mock(RequestOptions.class));
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.scriptFailureMode = ExecutionPolicy.ScriptFailureMode.CONTINUE;
        policy.normalize();

        ExecutionResult result = pipeline.execute(
                request,
                collection,
                false,
                null,
                null,
                null,
                environment,
                ExecutionSource.WORKBENCH_SEND,
                null,
                RedirectPolicy.defaults(),
                policy,
                null);

        assertThat(sendCount.get()).isZero();
        assertThat(result.requestSent).isFalse();
        assertThat(result.success).isFalse();
        assertThat(result.isBlockedBeforeSend()).isTrue();
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR);
        assertThat(result.scriptErrors)
                .anySatisfy(error -> assertThat(error)
                        .contains("UNSUPPORTED_SCRIPT_CAPABILITY")
                        .contains("sendRequest"));
        assertThat(result.continuedAfterScriptFailure).isFalse();
    }

    private static final class UnsupportedRuntime extends UnifiedScriptRuntime {
        UnsupportedRuntime(MontoyaApi api) {
            super(api, ScriptMode.FULL_JS);
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
            ScriptExecutionResult result = new ScriptExecutionResult();
            result.success = false;
            result.unsupportedCapabilities.add(new ScriptUnsupportedCapability(
                    "block-id",
                    ScriptDialect.POSTMAN,
                    ScriptPhase.PRE_REQUEST,
                    ScriptScope.REQUEST,
                    "sendRequest",
                    "Ad-hoc network requests are not supported by the sandbox.",
                    ScriptRiskLevel.CRITICAL,
                    "fixture"));
            throw new UnsupportedScriptCapabilityException(result);
        }
    }
}
