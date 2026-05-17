package burp.runner;

import burp.models.RunnerResult;
import burp.models.RunnerTimelineRow;
import burp.utils.ExecutionResult;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.SharedRequestPipeline;
import org.mockito.Mockito;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionRunnerTest {

    @Test
    void mergeExecutionVariablesRemovesBeforeAddingNewValues() {
        Map<String, String> runnerExtractedVars = new HashMap<>();
        runnerExtractedVars.put("stale", "old");
        runnerExtractedVars.put("keep", "yes");

        RunnerResult result = new RunnerResult();
        result.extractedVariables.put("stale", "old");
        result.extractedVariables.put("keep", "yes");

        ExecutionResult exec = new ExecutionResult();
        exec.removedVars.add("stale");
        exec.extractedVars.put("fresh", "new");
        exec.extractedVars.put("keep", "updated");

        CollectionRunner.mergeExecutionVariables(runnerExtractedVars, runnerExtractedVars, result, exec);

        assertThat(runnerExtractedVars).doesNotContainKey("stale");
        assertThat(result.extractedVariables).doesNotContainKey("stale");
        assertThat(runnerExtractedVars).containsEntry("keep", "updated");
        assertThat(result.extractedVariables).containsEntry("keep", "updated");
        assertThat(runnerExtractedVars).containsEntry("fresh", "new");
        assertThat(result.extractedVariables).containsEntry("fresh", "new");
    }

    @Test
    void extractedVariablesStayScopedToTheirSourceCollection() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        RecordingPipeline pipeline = new RecordingPipeline();
        CollectionRunner runner = new CollectionRunner(api, pipeline, null);

        ApiCollection collectionA = new ApiCollection();
        collectionA.name = "Collection A";
        ApiRequest requestA = new ApiRequest();
        requestA.name = "Request A";
        requestA.url = "http://example.com/a";
        requestA.sequenceOrder = 1;
        requestA.sourceCollection = collectionA.name;
        collectionA.requests.add(requestA);

        ApiCollection collectionB = new ApiCollection();
        collectionB.name = "Collection B";
        ApiRequest requestB = new ApiRequest();
        requestB.name = "Request B";
        requestB.url = "http://example.com/b";
        requestB.sequenceOrder = 2;
        requestB.sourceCollection = collectionB.name;
        collectionB.requests.add(requestB);

        runner.runCollections(List.of(collectionA, collectionB), List.of(requestA, requestB));
        waitForRunnerToStop(runner);
        drainEdt();

        assertThat(pipeline.contexts.get("Request B")).doesNotContainKey("shared_token");
    }

    @Test
    void retriesAreCountedAfterTheFirstAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CopyOnWriteArrayList<RunnerTimelineRow> timelineRows = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> debugMessages = new CopyOnWriteArrayList<>();
        CollectionRunner runner = new CollectionRunner(null, new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
                int attempt = calls.incrementAndGet();
                ExecutionResult exec = new ExecutionResult();
                exec.requestHeaders = "GET /retry HTTP/1.1\r\nHost: example.com\r\n\r\n";
                exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
                if (attempt == 1) {
                    exec.success = false;
                    exec.errorMessage = "temporary failure";
                } else {
                    exec.success = true;
                    exec.elapsedMs = 123;
                    exec.extractedVars.put("token", "abc");
                    exec.assertions.add(new RunnerResult.AssertionResult("status", true, "200", "200"));
                    exec.assertions.add(new RunnerResult.AssertionResult("body", false, "OK", "ERR"));
                    exec.response = mockResponse();
                }
                return exec;
            }
        }, null);
        runner.setDelayMs(0);
        runner.setMaxRetries(1);
        runner.addListener(new CollectionRunner.RunnerListener() {
            @Override public void onStart(String collectionName, int totalRequests) { }
            @Override public void onSkip(String requestName, String reason) { }
            @Override public void onRequestComplete(RunnerResult result) { }
            @Override public void onTimeline(RunnerTimelineRow row) { timelineRows.add(row); }
            @Override public void onComplete(List<RunnerResult> results) { }
            @Override public void onError(String message) { }
            @Override public void onDebug(String message) { debugMessages.add(message); }
        });

        ApiCollection collection = new ApiCollection();
        collection.name = "Retry Collection";
        ApiRequest request = new ApiRequest();
        request.name = "Retry Request";
        request.url = "http://example.com/retry";
        request.sequenceOrder = 1;
        request.sourceCollection = collection.name;
        collection.requests.add(request);

        runner.runCollections(List.of(collection), List.of(request));
        waitForRunnerToStop(runner);
        drainEdt();

        assertThat(calls.get()).isEqualTo(2);
        assertThat(runner.getResults()).hasSize(1);
        assertThat(runner.getResults().get(0).success)
                .as("error=%s", runner.getResults().get(0).errorMessage)
                .isTrue();
        assertThat(timelineRows).hasSize(1);
        assertThat(timelineRows.get(0).retries).isEqualTo(1);
        assertThat(timelineRows.get(0).varsChanged).isEqualTo(1);
        assertThat(timelineRows.get(0).assertions).isEqualTo("1/2");
        assertThat(timelineRows.get(0).status).isEqualTo("200");
        assertThat(timelineRows.get(0).timeMs).isEqualTo(123);
        assertThat(debugMessages).containsExactly(
                "Attempt 1/2 failed: temporary failure",
                "Retrying in 0ms",
                "Attempt 2/2 passed");
    }

    @Test
    void debugRawRequestEmitsRawRequestText() throws Exception {
        CopyOnWriteArrayList<String> debugMessages = new CopyOnWriteArrayList<>();
        CollectionRunner runner = new CollectionRunner(null, new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
                ExecutionResult exec = new ExecutionResult();
                exec.success = true;
                exec.requestHeaders = "GET /debug HTTP/1.1\r\nHost: example.com\r\n\r\n";
                exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
                exec.response = mockResponse();
                return exec;
            }
        }, null);
        runner.setDebugRawRequest(true);
        runner.addListener(new CollectionRunner.RunnerListener() {
            @Override public void onStart(String collectionName, int totalRequests) { }
            @Override public void onSkip(String requestName, String reason) { }
            @Override public void onRequestComplete(RunnerResult result) { }
            @Override public void onTimeline(RunnerTimelineRow row) { }
            @Override public void onComplete(List<RunnerResult> results) { }
            @Override public void onError(String message) { }
            @Override public void onDebug(String message) { debugMessages.add(message); }
        });

        ApiCollection collection = new ApiCollection();
        collection.name = "Debug Collection";
        ApiRequest request = new ApiRequest();
        request.name = "Debug Request";
        request.url = "http://example.com/debug";
        request.sequenceOrder = 1;
        request.sourceCollection = collection.name;
        collection.requests.add(request);

        runner.runCollections(List.of(collection), List.of(request));
        waitForRunnerToStop(runner);
        drainEdt();

        assertThat(debugMessages)
                .as("runnerResults=%s", runner.getResults())
                .anyMatch(msg -> msg.contains("GET /debug HTTP/1.1"));
    }

    @Test
    void cancelInterruptsActiveRun() throws Exception {
        AtomicInteger pipelineCalls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch cleanupGate = new CountDownLatch(1);
        AtomicBoolean interruptedFlag = new AtomicBoolean(false);
        AtomicInteger requestCompleteCount = new AtomicInteger();
        AtomicInteger completeCount = new AtomicInteger();
        CopyOnWriteArrayList<String> debugMessages = new CopyOnWriteArrayList<>();
        CollectionRunner runner = new CollectionRunner(null, new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
                started.countDown();
                pipelineCalls.incrementAndGet();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    interruptedFlag.set(true);
                }
                interrupted.countDown();
                try {
                    Thread.interrupted();
                    cleanupGate.await(250, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ExecutionResult exec = new ExecutionResult();
                exec.success = false;
                exec.errorMessage = "cancelled";
                exec.requestHeaders = "GET /slow HTTP/1.1\r\nHost: example.com\r\n\r\n";
                exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
                return exec;
            }
        }, null);

        ApiCollection collection = new ApiCollection();
        collection.name = "Cancel Collection";
        ApiRequest request = new ApiRequest();
        request.name = "Cancel Request";
        request.url = "http://example.com/slow";
        request.sequenceOrder = 1;
        request.sourceCollection = collection.name;
        collection.requests.add(request);

        runner.addListener(new CollectionRunner.RunnerListener() {
            @Override public void onStart(String collectionName, int totalRequests) { }
            @Override public void onSkip(String requestName, String reason) { }
            @Override public void onRequestComplete(RunnerResult result) { requestCompleteCount.incrementAndGet(); }
            @Override public void onComplete(List<RunnerResult> results) { completeCount.incrementAndGet(); }
            @Override public void onError(String message) { }
            @Override public void onDebug(String message) { debugMessages.add(message); }
        });

        runner.runCollections(List.of(collection), List.of(request));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        runner.cancel();
        assertThat(runner.isRunning()).isTrue();

        runner.runCollections(List.of(collection), List.of(request));

        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        waitForRunnerToStop(runner);
        drainEdt();

        assertThat(interruptedFlag.get()).isTrue();
        assertThat(pipelineCalls.get()).isEqualTo(1);
        assertThat(requestCompleteCount.get()).isZero();
        assertThat(completeCount.get()).isZero();
        assertThat(debugMessages).anyMatch(msg -> msg.contains("Runner cancelled."));
        assertThat(runner.getResults()).isEmpty();
        assertThat(runner.isRunning()).isFalse();
    }

    private void waitForRunnerToStop(CollectionRunner runner) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (runner.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(runner.isRunning()).isFalse();
    }

    private void drainEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static HttpRequestResponse mockResponse() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn((short) 200);
        ByteArray body = mock(ByteArray.class);
        when(body.length()).thenReturn(2);
        when(response.body()).thenReturn(body);
        when(response.bodyToString()).thenReturn("OK");
        when(response.headers()).thenReturn(Collections.emptyList());

        HttpRequestResponse responseWrapper = mock(HttpRequestResponse.class);
        when(responseWrapper.response()).thenReturn(response);
        when(responseWrapper.withAnnotations(any(Annotations.class))).thenReturn(responseWrapper);
        return responseWrapper;
    }

    private static class RecordingPipeline extends SharedRequestPipeline {
        private final Map<String, Map<String, String>> contexts = new ConcurrentHashMap<>();
        private final Map<String, Integer> invocations = new ConcurrentHashMap<>();

        RecordingPipeline() {
            super(null, null, null, null);
        }

        @Override
        public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
            contexts.put(req.name, col != null ? new HashMap<>(col.runtimeVars) : new HashMap<>());
            int count = invocations.merge(req.name, 1, Integer::sum);

            ExecutionResult exec = new ExecutionResult();
            exec.success = true;
            exec.response = mockResponse();
            exec.builtRequest = mock(HttpRequest.class);
            if (count == 1) {
                exec.extractedVars.put("shared_token", "from-a");
            }
            return exec;
        }
    }
}
