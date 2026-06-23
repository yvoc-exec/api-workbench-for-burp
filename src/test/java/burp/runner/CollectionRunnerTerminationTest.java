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
        assertThat(runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.COMPLETED);
        assertThat(runner.getResults()).hasSize(1);
        assertThat(lastRunnerDiagnostic()).isNotNull();
        assertThat(lastRunnerDiagnostic().severity).isEqualTo(DiagnosticSeverity.INFO);
        assertThat(lastRunnerDiagnostic().attributes.get("terminationType")).isEqualTo(RunnerTerminationType.COMPLETED.name());
    }

    @Test
    void cancellationEmitsCancelledOnceAndPreservesCompletedResults() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CollectionRunner runner = newRunner(exec -> {
            started.countDown();
            try {
                while (!Thread.currentThread().isInterrupted() && release.getCount() > 0) {
                    Thread.sleep(10);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exec.success = true;
            exec.response = RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain");
            exec.requestHeaders = "GET /cancel HTTP/1.1\r\nHost: example.com\r\n\r\n";
            exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
        });
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);

        ApiCollection collection = collection("Cancel", request("One", 1));
        runner.runCollections(List.of(collection), collection.requests);
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        runner.cancel();
        release.countDown();
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(listener.completedRuns).isEmpty();
        assertThat(listener.terminalResults).hasSize(1);
        assertThat(listener.terminalResults.get(0).type).isEqualTo(RunnerTerminationType.CANCELLED);
        assertThat(runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.CANCELLED);
        assertThat(runner.getResults()).hasSize(1);
        assertThat(lastRunnerDiagnostic().severity).isEqualTo(DiagnosticSeverity.WARNING);
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
}
