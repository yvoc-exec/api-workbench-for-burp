package burp.runner;

import burp.api.montoya.MontoyaApi;
import burp.diagnostics.DiagnosticEvent;
import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.RunnerResult;
import burp.models.RunnerTerminationResult;
import burp.models.RunnerTerminationType;
import burp.models.RunnerStopConditions;
import burp.testsupport.RunnerScriptTestFixtures;
import burp.utils.ExecutionResult;
import burp.utils.ExecutionPreflightStatus;
import burp.utils.SharedRequestPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionRunnerTerminationTest {

    @BeforeEach
    void enableDiagnostics() {
        DiagnosticStore.getInstance().setCaptureEnabled(true);
        DiagnosticStore.getInstance().clear();
    }

    @AfterEach
    void clearDiagnostics() {
        DiagnosticStore.getInstance().clear();
        DiagnosticStore.getInstance().setCaptureEnabled(false);
    }

    @Test
    void normalExecutionEmitsCompletedOnce() throws Exception {
        CollectionRunner runner = newRunner(exec -> {
            exec.success = true;
            exec.response = RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain");
            exec.requestHeaders = "GET /ok HTTP/1.1\r\nHost: example.com\r\n\r\n";
            exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
        });
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);

        ApiCollection collection = collection("Normal", request("One", 1));
        runner.runCollections(List.of(collection), collection.requests);
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(listener.completedRuns).hasSize(1);
        assertThat(listener.terminalResults).hasSize(1);
        assertThat(listener.terminalResults.get(0).type).isEqualTo(RunnerTerminationType.COMPLETED);
        assertThat(listener.errors).isEmpty();
        assertThat(runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.COMPLETED);
        assertThat(runner.getResults()).hasSize(1);
        assertThat(lastRunnerDiagnostic()).isNotNull();
        assertThat(lastRunnerDiagnostic().severity).isEqualTo(DiagnosticSeverity.INFO);
        assertThat(lastRunnerDiagnostic().attributes.get("terminationType")).isEqualTo(RunnerTerminationType.COMPLETED.name());
    }

    @Test
    void cancelWhilePausedBeforeAnyRequestExecutesEmitsCancelledOnce() throws Exception {
        CollectionRunner runner = newRunner(exec -> {
            exec.success = true;
            exec.response = RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain");
            exec.requestHeaders = "GET /cancel HTTP/1.1\r\nHost: example.com\r\n\r\n";
            exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
        });
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);

        ApiCollection collection = collection("Cancel", request("One", 1));
        runner.runCollections(List.of(collection), collection.requests, true);
        runner.cancel();
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(listener.completedRuns).isEmpty();
        assertThat(listener.terminalResults).hasSize(1);
        assertThat(listener.terminalResults.get(0).type).isEqualTo(RunnerTerminationType.CANCELLED);
        assertThat(runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.CANCELLED);
        assertThat(listener.errors).hasSize(1);
        assertThat(runner.getResults()).isEmpty();
        assertThat(lastRunnerDiagnostic().severity).isEqualTo(DiagnosticSeverity.WARNING);
    }

    @Test
    void cancelDuringInterRequestDelayKeepsFirstCompletedResultOnly() throws Exception {
        CountDownLatch firstCompleted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CollectionRunner runner = newRunner(exec -> {
            int call = calls.incrementAndGet();
            exec.success = true;
            exec.response = RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain");
            exec.requestHeaders = "GET /delay" + call + " HTTP/1.1\r\nHost: example.com\r\n\r\n";
            exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
        });
        runner.setDelayMs(1000);
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);
        runner.addListener(new CollectionRunner.RunnerListener() {
            @Override public void onStart(String collectionName, int totalRequests) { }
            @Override public void onSkip(String requestName, String reason) { }
            @Override public void onRequestComplete(RunnerResult result) {
                if (result != null && "One".equals(result.requestName)) {
                    firstCompleted.countDown();
                }
            }
            @Override public void onComplete(List<RunnerResult> results) { }
            @Override public void onError(String message) { }
        });

        ApiCollection collection = collection("Delay", request("One", 1), request("Two", 2));
        runner.runCollections(List.of(collection), collection.requests);
        assertThat(firstCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        runner.cancel();
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(runner.getResults()).hasSize(1);
        assertThat(runner.getResults().get(0).requestName).isEqualTo("One");
        assertThat(listener.terminalResults).hasSize(1);
        assertThat(listener.terminalResults.get(0).type).isEqualTo(RunnerTerminationType.CANCELLED);
        assertThat(listener.terminalResults.get(0).completedCount).isEqualTo(1);
        assertThat(listener.terminalResults.get(0).totalQueuedCount).isEqualTo(2);
        assertThat(listener.errors).hasSize(1);
    }

    @Test
    void immediateCancellationBeforeWorkerStartsStillEmitsCancelledOnceAndAllowsSecondRun() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CollectionRunner runner = newRunner(exec -> {
            calls.incrementAndGet();
            exec.success = true;
            exec.response = RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain");
            exec.requestHeaders = "GET /immediate HTTP/1.1\r\nHost: example.com\r\n\r\n";
            exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
        });
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);

        ApiCollection collection = collection("Immediate", request("One", 1));
        runner.runCollections(List.of(collection), collection.requests);
        runner.cancel();
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(listener.terminalResults).hasSize(1);
        assertThat(listener.terminalResults.get(0).type).isEqualTo(RunnerTerminationType.CANCELLED);
        assertThat(listener.completedRuns).isEmpty();
        assertThat(listener.errors).hasSize(1);
        assertThat(runner.getResults()).isEmpty();

        runner.runCollections(List.of(collection), collection.requests);
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(runner.getResults()).hasSize(1);
    }

    @Test
    void cancelDuringRetryDelayDoesNotFabricateFailedQueuedResult() throws Exception {
        CountDownLatch firstAttemptCompleted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CollectionRunner runner = new CollectionRunner(null, new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
                int call = calls.incrementAndGet();
                ExecutionResult exec = new ExecutionResult();
                exec.requestHeaders = "GET /retry HTTP/1.1\r\nHost: example.com\r\n\r\n";
                exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
                if (call == 1) {
                    exec.success = false;
                    exec.errorMessage = "temporary failure";
                } else {
                    exec.success = true;
                    exec.response = RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain");
                }
                return exec;
            }
        }, null);
        runner.setDelayMs(1000);
        runner.setMaxRetries(1);
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);
        runner.addListener(new CollectionRunner.RunnerListener() {
            @Override public void onStart(String collectionName, int totalRequests) { }
            @Override public void onSkip(String requestName, String reason) { }
            @Override public void onRequestComplete(RunnerResult result) { }
            @Override public void onAttemptComplete(RunnerResult result) {
                if (result != null && result.attemptNumber == 1) {
                    firstAttemptCompleted.countDown();
                }
            }
            @Override public void onComplete(List<RunnerResult> results) { }
            @Override public void onError(String message) { }
        });

        ApiCollection collection = collection("Retry", request("One", 1));
        runner.runCollections(List.of(collection), collection.requests);
        assertThat(firstAttemptCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        runner.cancel();
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);
        waitUntil(() -> listener.terminalResults.size() == 1, 2000);
        assertThat(listener.terminalResults).hasSize(1);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(listener.terminalResults.get(0).type)
                .isIn(RunnerTerminationType.COMPLETED, RunnerTerminationType.CANCELLED);
        assertThat(listener.terminalResults.get(0).completedCount).isIn(0, 1);
        assertThat(listener.terminalResults.get(0).totalQueuedCount).isEqualTo(1);
        assertThat(runner.getResults()).hasSize(1);
        assertThat(runner.getResults().get(0).preflightStatus)
                .isIn(ExecutionPreflightStatus.READY, ExecutionPreflightStatus.CANCELLED);
    }

    @Test
    void cancelDuringInterruptedInflightRequestRetainsPriorCompletedResult() throws Exception {
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CollectionRunner runner = newRunner(exec -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                exec.success = true;
                exec.response = RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain");
            } else {
                secondStarted.countDown();
                try {
                    while (true) {
                        Thread.sleep(50);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted", ie);
                }
            }
            exec.requestHeaders = "GET /inflight" + call + " HTTP/1.1\r\nHost: example.com\r\n\r\n";
            exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
        });
        runner.setDelayMs(1000);
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);

        ApiCollection collection = collection("Inflight", request("One", 1), request("Two", 2));
        runner.runCollections(List.of(collection), collection.requests);
        assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
        runner.cancel();
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(listener.terminalResults).hasSize(1);
        assertThat(listener.terminalResults.get(0).type).isEqualTo(RunnerTerminationType.CANCELLED);
        assertThat(listener.terminalResults.get(0).completedCount).isEqualTo(1);
        assertThat(listener.terminalResults.get(0).totalQueuedCount).isEqualTo(2);
        assertThat(runner.getResults()).hasSize(2);
        assertThat(runner.getResults().get(0).requestName).isEqualTo("One");
        assertThat(runner.getResults().get(1).requestName).isEqualTo("Two");
        assertThat(runner.getResults().get(1).preflightStatus).isEqualTo(ExecutionPreflightStatus.READY);
    }

    @Test
    void legacyListenerWithoutOnTerminalStillReceivesCompleteAndCancellationSignals() throws Exception {
        AtomicInteger completeCount = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();
        CollectionRunner.RunnerListener legacyListener = new CollectionRunner.RunnerListener() {
            @Override public void onStart(String collectionName, int totalRequests) { }
            @Override public void onSkip(String requestName, String reason) { }
            @Override public void onRequestComplete(RunnerResult result) { }
            @Override public void onComplete(List<RunnerResult> results) { completeCount.incrementAndGet(); }
            @Override public void onError(String message) { errorCount.incrementAndGet(); }
        };

        CollectionRunner completingRunner = newRunner(exec -> {
            exec.success = true;
            exec.response = RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain");
        });
        completingRunner.addListener(legacyListener);
        ApiCollection completeCollection = collection("Complete", request("One", 1));
        completingRunner.runCollections(List.of(completeCollection), completeCollection.requests);
        RunnerScriptTestFixtures.waitForRunnerToStop(completingRunner);

        assertThat(completeCount.get()).isEqualTo(1);
        assertThat(errorCount.get()).isZero();

        completeCount.set(0);
        errorCount.set(0);
        CollectionRunner cancelledRunner = newRunner(exec -> {
            exec.success = true;
            exec.response = RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain");
        });
        cancelledRunner.addListener(legacyListener);
        ApiCollection cancelledCollection = collection("CancelLegacy", request("One", 1));
        cancelledRunner.runCollections(List.of(cancelledCollection), cancelledCollection.requests, true);
        cancelledRunner.cancel();
        RunnerScriptTestFixtures.waitForRunnerToStop(cancelledRunner);

        assertThat(completeCount.get()).isZero();
        assertThat(errorCount.get()).isEqualTo(1);
    }

    @Test
    void unexpectedExceptionEmitsInternalErrorOnce() throws Exception {
        CollectionRunner runner = newRunner(exec -> {
            exec.success = true;
            exec.response = RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain");
        });
        runner.setRuntimeOverlayProvider(collection -> {
            throw new IllegalStateException("boom token=secret");
        });
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);

        ApiCollection collection = collection("Internal", request("One", 1));
        runner.runCollections(List.of(collection), collection.requests);
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(listener.completedRuns).isEmpty();
        assertThat(listener.terminalResults).hasSize(1);
        RunnerTerminationResult termination = listener.terminalResults.get(0);
        assertThat(termination.type).isEqualTo(RunnerTerminationType.INTERNAL_ERROR);
        assertThat(termination.reason).doesNotContain("secret");
        assertThat(runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.INTERNAL_ERROR);
        assertThat(runner.getResults()).isEmpty();
        assertThat(lastRunnerDiagnostic().severity).isEqualTo(DiagnosticSeverity.ERROR);
    }

    @Test
    void eachRunEmitsExactlyOneTerminalOutcome() throws Exception {
        CollectionRunner runner = newRunner(exec -> {
            exec.success = true;
            exec.response = RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain");
        });
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);

        ApiCollection collection = collection("SingleTerminal", request("One", 1));
        runner.runCollections(List.of(collection), collection.requests);
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(listener.terminalResults).hasSize(1);
        assertThat(listener.completedRuns).hasSize(1);
        assertThat(runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.COMPLETED);
    }

    @Test
    void terminalDiagnosticsUseExpectedSeverityForStoppedOnError() throws Exception {
        CollectionRunner runner = new CollectionRunner(null, new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
                ExecutionResult exec = new ExecutionResult();
                exec.success = false;
                exec.errorMessage = "boom";
                exec.requestHeaders = "GET /stop HTTP/1.1\r\nHost: example.com\r\n\r\n";
                exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
                return exec;
            }
        }, null);
        runner.setStopConditions(stopOnError());
        runner.addListener(new RunnerScriptTestFixtures.RecordingRunnerListener());

        ApiCollection collection = collection("StopOnError", request("One", 1));
        runner.runCollections(List.of(collection), collection.requests);
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        DiagnosticEvent event = lastRunnerDiagnostic();
        assertThat(event).isNotNull();
        assertThat(event.severity).isEqualTo(DiagnosticSeverity.WARNING);
        assertThat(event.attributes.get("terminationType")).isEqualTo(RunnerTerminationType.STOPPED_ON_ERROR.name());
        assertThat(event.attributes.get("configuredCondition")).isEqualTo("stopOnError");
    }

    private static CollectionRunner newRunner(java.util.function.Consumer<ExecutionResult> executionCustomizer) {
        MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        AtomicInteger calls = new AtomicInteger();
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
                calls.incrementAndGet();
                ExecutionResult exec = new ExecutionResult();
                executionCustomizer.accept(exec);
                if (exec.requestHeaders == null) {
                    exec.requestHeaders = "GET /runner HTTP/1.1\r\nHost: example.com\r\n\r\n";
                    exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
                }
                return exec;
            }
        };
        CollectionRunner runner = new CollectionRunner(api, pipeline, null);
        runner.setDelayMs(0);
        runner.setMaxRetries(0);
        return runner;
    }

    private static RunnerStopConditions stopOnError() {
        RunnerStopConditions conditions = new RunnerStopConditions();
        conditions.stopOnError = true;
        return conditions;
    }

    private static ApiCollection collection(String name, ApiRequest... requests) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        if (requests != null) {
            for (ApiRequest request : requests) {
                if (request != null) {
                    request.sourceCollection = name;
                    collection.requests.add(request);
                }
            }
        }
        return collection;
    }

    private static ApiRequest request(String name, int sequenceOrder) {
        ApiRequest request = new ApiRequest();
        request.name = name;
        request.sequenceOrder = sequenceOrder;
        request.method = "GET";
        request.url = "http://example.com/" + name.toLowerCase();
        return request;
    }

    private static DiagnosticEvent lastRunnerDiagnostic() {
        return DiagnosticStore.getInstance().snapshot().stream()
                .filter(event -> event.operation == DiagnosticOperation.RUNNER_RUN)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
