package burp.runner;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.RunnerResult;
import burp.models.RunnerTerminationType;
import burp.scripts.ScriptLogEntry;
import burp.testsupport.RunnerScriptTestFixtures;
import burp.utils.ExecutionResult;
import burp.utils.ExecutionPolicy;
import burp.utils.PreflightDecisionHandler;
import burp.utils.SharedRequestPipeline;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionRunnerRetryIntegrationTest {

    @Test
    void timedOutPostRunsOnceByDefault() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CollectionRunner runner = runner(new StubPipeline(call -> {
            calls.incrementAndGet();
            return timeoutResult("POST");
        }));

        ApiCollection collection = collection("Retry Collection", request("post-1", "TimedOut", "POST"));
        run(runner, collection);

        assertThat(calls).hasValue(1);
        RunnerResult result = runner.getResults().get(0);
        assertThat(result.retryDecision).isEqualTo("NO_RETRY");
        assertThat(result.retryFailureType).isEqualTo(RetryFailureType.RESPONSE_TIMEOUT);
        assertThat(result.requestMayHaveBeenProcessed).isTrue();
        assertThat(result.attemptNumber).isEqualTo(1);
        assertThat(result.totalAttempts).isEqualTo(1);
    }

    @Test
    void timedOutGetRetriesWhenEnabled() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CollectionRunner runner = runner(new StubPipeline(call -> {
            calls.incrementAndGet();
            return call == 1 ? timeoutResult("GET") : successResult("GET", 200);
        }));
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryTimeouts = true;
        policy.normalize();
        runner.setRetryPolicy(policy);

        ApiCollection collection = collection("Retry Collection", request("get-1", "TimedOut", "GET"));
        run(runner, collection);

        assertThat(calls).hasValue(2);
        RunnerResult result = runner.getResults().get(0);
        assertThat(result.success).isTrue();
        assertThat(result.attemptNumber).isEqualTo(2);
        assertThat(result.totalAttempts).isEqualTo(2);
    }

    @Test
    void connectionFailureUsesConfiguredBackoff() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CollectionRunner runner = runner(new StubPipeline(call -> {
            calls.incrementAndGet();
            return call == 1 ? connectionFailureResult("GET") : successResult("GET", 200);
        }));
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryConnectionFailures = true;
        policy.baseDelayMillis = 25;
        policy.maxDelayMillis = 25;
        policy.normalize();
        runner.setRetryPolicy(policy);

        ApiCollection collection = collection("Retry Collection", request("get-1", "Retry", "GET"));
        run(runner, collection);

        assertThat(calls).hasValue(2);
        RunnerResult result = runner.getResults().get(0);
        assertThat(result.success).isTrue();
        assertThat(result.retryDecision).isEqualTo("NO_RETRY");
    }

    @Test
    void configuredStatusCodeRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CollectionRunner runner = runner(new StubPipeline(call -> {
            calls.incrementAndGet();
            return call == 1 ? statusFailureResult("GET", 503) : successResult("GET", 200);
        }));
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryableStatusCodes = new java.util.LinkedHashSet<>(List.of(503));
        policy.normalize();
        runner.setRetryPolicy(policy);

        ApiCollection collection = collection("Retry Collection", request("get-1", "Retry", "GET"));
        run(runner, collection);

        assertThat(calls).hasValue(2);
        assertThat(runner.getResults().get(0).success).isTrue();
    }

    @Test
    void unconfiguredStatusCodeDoesNotRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CollectionRunner runner = runner(new StubPipeline(call -> {
            calls.incrementAndGet();
            return statusFailureResult("GET", 500);
        }));
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryableStatusCodes = new java.util.LinkedHashSet<>(List.of(503));
        policy.normalize();
        runner.setRetryPolicy(policy);

        ApiCollection collection = collection("Retry Collection", request("get-1", "Retry", "GET"));
        run(runner, collection);

        assertThat(calls).hasValue(1);
        RunnerResult result = runner.getResults().get(0);
        assertThat(result.retryDecision).isEqualTo("NO_RETRY");
        assertThat(result.retryFailureType).isNull();
    }

    @Test
    void cancellationDuringBackoffStopsImmediately() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CollectionRunner runner = runner(new StubPipeline(call -> {
            calls.incrementAndGet();
            return call == 1 ? connectionFailureResult("GET") : successResult("GET", 200);
        }));
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryConnectionFailures = true;
        policy.baseDelayMillis = 1_000;
        policy.maxDelayMillis = 1_000;
        policy.normalize();
        runner.setRetryPolicy(policy);

        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);

        ApiCollection collection = collection("Retry Collection", request("get-1", "Retry", "GET"));
        runner.runCollections(List.of(collection), collection.requests);
        await(() -> listener.attemptResults.size() == 1, 2_000L);
        runner.cancel();
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(calls).hasValue(1);
        assertThat(runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.CANCELLED);
    }

    @Test
    void resultContainsRetryDecisionMetadata() throws Exception {
        CollectionRunner runner = runner(new StubPipeline(call -> call == 1 ? connectionFailureResult("GET") : successResult("GET", 200)));
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryConnectionFailures = true;
        policy.normalize();
        runner.setRetryPolicy(policy);

        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);
        ApiCollection collection = collection("Retry Collection", request("get-1", "Retry", "GET"));
        run(runner, collection);

        assertThat(listener.attemptResults).hasSize(2);
        RunnerResult first = listener.attemptResults.get(0);
        assertThat(first.retryDecision).isEqualTo("RETRY");
        assertThat(first.retryFailureType).isEqualTo(RetryFailureType.CONNECTION_FAILURE);
        assertThat(first.requestMayHaveBeenProcessed).isTrue();
        assertThat(first.retryReason).contains("Retrying connection failure");
    }

    @Test
    void attemptMetadataDoesNotBleedBetweenRetries() throws Exception {
        CollectionRunner runner = runner(new StubPipeline(call -> {
            ExecutionResult exec = call == 1 ? connectionFailureResult("GET") : successResult("GET", 200);
            if (call == 1) {
                exec.scriptLogs.add(new ScriptLogEntry("info", "first attempt", null, null));
                exec.extractedVars.put("first", "1");
            }
            return exec;
        }));
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryConnectionFailures = true;
        policy.normalize();
        runner.setRetryPolicy(policy);

        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);
        ApiCollection collection = collection("Retry Collection", request("get-1", "Retry", "GET"));
        run(runner, collection);

        assertThat(listener.attemptResults).hasSize(2);
        assertThat(listener.attemptResults.get(0).scriptLogs).hasSize(1);
        assertThat(listener.attemptResults.get(1).scriptLogs).isEmpty();
        assertThat(listener.attemptResults.get(1).extractedVariables).isEmpty();
    }

    @Test
    void eachAttemptPublishesOneTimelineRow() throws Exception {
        CollectionRunner runner = runner(new StubPipeline(call -> call == 1 ? connectionFailureResult("GET") : successResult("GET", 200)));
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryConnectionFailures = true;
        policy.normalize();
        runner.setRetryPolicy(policy);

        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);
        ApiCollection collection = collection("Retry Collection", request("get-1", "Retry", "GET"));
        run(runner, collection);

        assertThat(listener.timelineRows).hasSize(2);
        assertThat(listener.timelineRows.get(0).attemptNumber).isEqualTo(1);
        assertThat(listener.timelineRows.get(1).attemptNumber).isEqualTo(2);
    }

    private static CollectionRunner runner(StubPipeline pipeline) {
        CollectionRunner runner = new CollectionRunner(null, pipeline, null);
        runner.setDelayMs(0);
        return runner;
    }

    private static void run(CollectionRunner runner, ApiCollection collection) throws Exception {
        runner.runCollections(List.of(collection), collection.requests);
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);
    }

    private static ApiCollection collection(String name, ApiRequest request) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        collection.requests = new java.util.ArrayList<>(List.of(request));
        return collection;
    }

    private static ApiRequest request(String id, String name, String method) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.method = method;
        request.url = "https://example.test/" + id;
        request.sequenceOrder = 1;
        request.sourceCollection = "Retry Collection";
        return request;
    }

    private static ExecutionResult successResult(String method, int statusCode) {
        ExecutionResult exec = new ExecutionResult();
        exec.success = true;
        exec.requestSent = true;
        exec.response = response(statusCode);
        exec.requestHeaders = method + " / HTTP/1.1\r\nHost: example.test\r\n\r\n";
        exec.rawRequestBytes = exec.requestHeaders.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exec.elapsedMs = 10;
        return exec;
    }

    private static ExecutionResult timeoutResult(String method) {
        ExecutionResult exec = new ExecutionResult();
        exec.success = false;
        exec.requestSent = true;
        exec.responseTimedOut = true;
        exec.errorMessage = "timed out";
        exec.requestHeaders = method + " / HTTP/1.1\r\nHost: example.test\r\n\r\n";
        exec.rawRequestBytes = exec.requestHeaders.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return exec;
    }

    private static ExecutionResult connectionFailureResult(String method) {
        ExecutionResult exec = new ExecutionResult();
        exec.success = false;
        exec.requestSent = true;
        exec.errorMessage = "No response received";
        exec.requestHeaders = method + " / HTTP/1.1\r\nHost: example.test\r\n\r\n";
        exec.rawRequestBytes = exec.requestHeaders.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return exec;
    }

    private static ExecutionResult statusFailureResult(String method, int statusCode) {
        ExecutionResult exec = new ExecutionResult();
        exec.success = false;
        exec.requestSent = true;
        exec.response = response(statusCode);
        exec.errorMessage = "HTTP " + statusCode;
        exec.requestHeaders = method + " / HTTP/1.1\r\nHost: example.test\r\n\r\n";
        exec.rawRequestBytes = exec.requestHeaders.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return exec;
    }

    private static HttpRequestResponse response(int statusCode) {
        return RunnerScriptTestFixtures.mockResponse(statusCode, "body", "text/plain");
    }

    private static void await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Condition not met in " + timeoutMillis + " ms");
    }

    private static final class StubPipeline extends SharedRequestPipeline {
        private final java.util.function.Function<Integer, ExecutionResult> responder;
        private final AtomicInteger calls = new AtomicInteger();

        private StubPipeline(java.util.function.Function<Integer, ExecutionResult> responder) {
            super(null, new burp.utils.RequestBuilder(null), new burp.utils.ScriptEngine(null, burp.utils.ScriptMode.DISABLED), null);
            this.responder = responder;
        }

        @Override
        public ExecutionResult execute(ApiRequest req,
                                       ApiCollection col,
                                       boolean followRedirects,
                                       Map<String, String> runtimeOverlay,
                                       SharedRequestPipeline.OAuth2TokenSink oauth2TokenSink,
                                       SharedRequestPipeline.RuntimeVariableSink runtimeVariableSink,
                                       burp.models.EnvironmentProfile activeEnvironment,
                                       burp.scripts.ExecutionSource executionSource,
                                       burp.scripts.ScriptDependentRequestExecutor dependentRequestExecutor,
                                       burp.models.RedirectPolicy redirectPolicy,
                                       ExecutionPolicy executionPolicy,
                                       PreflightDecisionHandler preflightDecisionHandler,
                                       BooleanSupplier cancellationRequested) {
            int call = calls.incrementAndGet();
            ExecutionResult exec = responder.apply(call);
            return exec != null ? exec : new ExecutionResult();
        }
    }
}
