package burp.runner;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.history.HistoryEntry;
import burp.history.HistoryResult;
import burp.scripts.ExecutionSource;
import burp.scripts.ScriptDependentRequestExecutor;
import burp.scripts.ScriptExecutionResult;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.scripts.UnifiedScriptRuntime;
import burp.testsupport.RunnerScriptTestFixtures;
import burp.utils.ExecutionPolicy;
import burp.utils.ExecutionPreflightStatus;
import burp.utils.RequestBuilder;
import burp.utils.ScriptEngine;
import burp.utils.ScriptMode;
import burp.utils.SharedRequestPipeline;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class CollectionRunnerPreflightTest {

    @Test
    void scriptFailureStopsWithoutNetworkSend() throws Exception {
        Harness harness = harness("throw new Error('boom');");
        runAndWait(harness.runner, harness.collection, harness.request);

        assertThat(harness.sendCount.get()).isZero();
        assertThat(harness.runner.getResults()).hasSize(1);
        assertThat(harness.runner.getResults().get(0).preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR);
        assertThat(harness.runner.getResults().get(0).requestSent).isFalse();
    }

    @Test
    void oauth2FailureStopsWithoutNetworkSend() throws Exception {
        Harness harness = harness(null, null, true);
        runAndWait(harness.runner, harness.collection, harness.request);

        assertThat(harness.sendCount.get()).isZero();
        assertThat(harness.runner.getResults().get(0).preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE);
    }

    @Test
    void unresolvedStopsWhenStopOnMissingEnabled() throws Exception {
        Harness harness = harness(null, null);
        harness.request.url = "https://example.test/{{missing}}";
        harness.runner.setExecutionPolicy(ExecutionPolicy.runnerDefaults(true));
        runAndWait(harness.runner, harness.collection, harness.request);

        assertThat(harness.sendCount.get()).isZero();
        assertThat(harness.runner.getResults().get(0).preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES);
    }

    @Test
    void unresolvedProceedsWithWarningWhenStopOnMissingDisabled() throws Exception {
        Harness harness = harness(null, null);
        harness.request.url = "https://example.test/{{missing}}";
        harness.runner.setExecutionPolicy(ExecutionPolicy.runnerDefaults(false));
        runAndWait(harness.runner, harness.collection, harness.request);
        System.out.println("UNRESOLVED_ALLOW status=" + harness.runner.getResults().get(0).preflightStatus
                + " msg=" + harness.runner.getResults().get(0).preflightMessage
                + " sent=" + harness.sendCount.get()
                + " success=" + harness.runner.getResults().get(0).success);

        assertThat(harness.sendCount.get()).isEqualTo(1);
        assertThat(harness.runner.getResults().get(0).unresolvedVariables).contains("missing");
        assertThat(harness.runner.getResults().get(0).preflightStatus).isEqualTo(ExecutionPreflightStatus.READY);
    }

    @Test
    void scriptGeneratedVariableAvoidsFalseMissingVariableStop() throws Exception {
        Harness harness = harness("awb.collection.set('token', 'value', { persist: false });", null);
        harness.request.url = "https://example.test/{{token}}";
        runAndWait(harness.runner, harness.collection, harness.request);
        System.out.println("SCRIPT_GENERATED status=" + harness.runner.getResults().get(0).preflightStatus
                + " msg=" + harness.runner.getResults().get(0).preflightMessage
                + " sent=" + harness.sendCount.get()
                + " success=" + harness.runner.getResults().get(0).success);

        assertThat(harness.sendCount.get()).isEqualTo(1);
        assertThat(harness.runner.getResults().get(0).unresolvedVariables).isEmpty();
    }

    @Test
    void targetChangeStopsByDefault() throws Exception {
        Harness harness = harness("awb.request.url = 'https://other.example.test/';", null);
        runAndWait(harness.runner, harness.collection, harness.request);

        assertThat(harness.sendCount.get()).isZero();
        assertThat(harness.runner.getResults().get(0).preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE);
    }

    @Test
    void runnerNeverInvokesModalConfirmationHandler() throws Exception {
        Harness harness = harness("awb.request.url = 'https://other.example.test/';", null);
        harness.runner.setExecutionPolicy(ExecutionPolicy.workbenchDefaults());
        runAndWait(harness.runner, harness.collection, harness.request);

        assertThat(harness.sendCount.get()).isZero();
        assertThat(harness.runner.getResults().get(0).preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_POLICY);
    }

    @Test
    void blockedAttemptIsNotRetried() throws Exception {
        Harness harness = harness("throw new Error('boom');", null);
        harness.runner.setMaxRetries(3);
        runAndWait(harness.runner, harness.collection, harness.request);

        assertThat(harness.sendCount.get()).isZero();
        assertThat(harness.runner.getResults()).hasSize(1);
    }

    @Test
    void directlyThrownScriptExceptionIsBlockedAndNotRetried() throws Exception {
        AtomicInteger scriptInvocations = new AtomicInteger();
        Harness harness = throwingRuntimeHarness(scriptInvocations);
        harness.runner.setMaxRetries(3);

        runAndWait(harness.runner, harness.collection, harness.request);

        assertThat(scriptInvocations.get()).isEqualTo(1);
        assertThat(harness.sendCount.get()).isZero();
        assertThat(harness.runner.getResults()).hasSize(1);

        burp.models.RunnerResult result = harness.runner.getResults().get(0);
        assertThat(result.attemptNumber).isEqualTo(1);
        assertThat(result.success).isFalse();
        assertThat(result.requestSent).isFalse();
        assertThat(result.preflightStatus)
                .isEqualTo(ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR);
        assertThat(result.preflightMessage)
                .isEqualTo("Request not sent — pre-request script failed.");

        HistoryEntry history = HistoryEntry.fromRunnerAttempt(
                harness.collection,
                harness.request,
                null,
                result
        );

        assertThat(history.result).isEqualTo(HistoryResult.BLOCKED);
        assertThat(history.requestSent).isFalse();
        assertThat(history.requestSnapshot.hasRawRequestSent()).isFalse();
    }

    @Test
    void responseTimeoutProducesTimedOutRunnerResult() throws Exception {
        Harness harness = harness(null, () -> {
            throw new RuntimeException(new SocketTimeoutException("timed out"));
        });
        runAndWait(harness.runner, harness.collection, harness.request);
        System.out.println("TIMEOUT status=" + harness.runner.getResults().get(0).preflightStatus
                + " msg=" + harness.runner.getResults().get(0).preflightMessage
                + " sent=" + harness.sendCount.get()
                + " success=" + harness.runner.getResults().get(0).success
                + " timedOut=" + harness.runner.getResults().get(0).responseTimedOut);

        assertThat(harness.runner.getResults().get(0).responseTimedOut).isTrue();
        assertThat(harness.runner.getResults().get(0).requestSent).isTrue();
    }

    @Test
    void runnerResultCopiesAllPreflightMetadata() throws Exception {
        Harness harness = harness("awb.request.url = 'https://other.example.test/';", null);
        runAndWait(harness.runner, harness.collection, harness.request);

        assertThat(harness.runner.getResults().get(0).preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE);
        assertThat(harness.runner.getResults().get(0).originalResolvedUrl).isEqualTo("https://example.test/");
        assertThat(harness.runner.getResults().get(0).effectiveResolvedUrl).isEqualTo("https://other.example.test/");
        assertThat(harness.runner.getResults().get(0).targetChanged).isTrue();
    }

    private static Harness harness(String scriptSource) throws Exception {
        return harness(scriptSource, null, false);
    }

    private static Harness harness(String scriptSource, java.util.function.Supplier<burp.api.montoya.http.message.HttpRequestResponse> responseSupplier) throws Exception {
        return harness(scriptSource, responseSupplier, false);
    }

    private static Harness harness(String scriptSource, java.util.function.Supplier<burp.api.montoya.http.message.HttpRequestResponse> responseSupplier, boolean oauth2) throws Exception {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> sentRequests = new CopyOnWriteArrayList<>();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(sendCount, sentRequests, responseSupplier != null ? responseSupplier : () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain"));
        if (responseSupplier != null) {
            when(api.http().sendRequest(any(burp.api.montoya.http.message.requests.HttpRequest.class), any(RequestOptions.class))).thenAnswer(invocation -> {
                sendCount.incrementAndGet();
                sentRequests.add(invocation.getArgument(0));
                return responseSupplier.get();
            });
        }
        SharedRequestPipeline pipeline = SharedRequestPipeline.withRequestOptionsFactory(
                api,
                new RequestBuilder(null),
                new ScriptEngine(null, ScriptMode.FULL_JS),
                null,
                null,
                timeout -> {
                    RequestOptions options = Mockito.mock(RequestOptions.class);
                    when(options.withRedirectionMode(Mockito.any())).thenReturn(options);
                    when(options.withResponseTimeout(Mockito.anyInt())).thenReturn(options);
                    return options;
                }
        );
        CollectionRunner runner = new CollectionRunner(api, pipeline, null);
        runner.setDelayMs(0);
        runner.setMaxRetries(0);
        runner.setExecutionPolicy(ExecutionPolicy.runnerDefaults(false));

        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test/";
        request.headers = new ArrayList<>();
        if (scriptSource != null) {
            request.scriptBlocks = new ArrayList<>();
            request.scriptBlocks.add(block(scriptSource));
        }
        collection.requests = new ArrayList<>(List.of(request));
        if (oauth2) {
            request.auth = oauth2Request();
            collection.environment.put("oauth2_token_url", "https://oauth2.test/token");
            collection.environment.put("oauth2_client_id", "client-id");
            collection.environment.put("oauth2_client_secret", "client-secret");
        }

        return new Harness(runner, collection, request, sendCount);
    }

    private static Harness throwingRuntimeHarness(
            AtomicInteger scriptInvocations) {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest>
                sentRequests = new CopyOnWriteArrayList<>();

        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(
                sendCount,
                sentRequests,
                () -> RunnerScriptTestFixtures.mockResponse(
                        200,
                        "OK",
                        "text/plain"
                )
        );

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(
                api,
                ScriptMode.FULL_JS
        ) {
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
                scriptInvocations.incrementAndGet();
                throw new IllegalStateException("boom");
            }
        };

        SharedRequestPipeline pipeline =
                SharedRequestPipeline.withRequestOptionsFactory(
                        api,
                        new RequestBuilder(null),
                        new ScriptEngine(null, ScriptMode.FULL_JS),
                        null,
                        runtime,
                        timeout -> Mockito.mock(RequestOptions.class)
                );

        CollectionRunner runner = new CollectionRunner(api, pipeline, null);
        runner.setDelayMs(0);
        runner.setMaxRetries(0);
        runner.setExecutionPolicy(ExecutionPolicy.runnerDefaults(false));

        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";

        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test/";
        request.headers = new ArrayList<>();
        collection.requests = new ArrayList<>(List.of(request));

        return new Harness(runner, collection, request, sendCount);
    }

    private static ApiRequest.Auth oauth2Request() {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "oauth2";
        auth.properties = new LinkedHashMap<>();
        auth.properties.put("token_url", "https://oauth2.test/token");
        auth.properties.put("client_id", "client-id");
        auth.properties.put("client_secret", "client-secret");
        return auth;
    }

    private static ScriptBlock block(String source) {
        ScriptBlock block = new ScriptBlock();
        block.id = "script";
        block.dialect = ScriptDialect.API_WORKBENCH;
        block.phase = ScriptPhase.PRE_REQUEST;
        block.scope = ScriptScope.REQUEST;
        block.source = source;
        block.enabled = true;
        return block;
    }

    private static void runAndWait(CollectionRunner runner, ApiCollection collection, ApiRequest request) {
        runner.runCollections(List.of(collection), List.of(request));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);
    }

    private record Harness(CollectionRunner runner,
                           ApiCollection collection,
                           ApiRequest request,
                           AtomicInteger sendCount) {
    }
}
