package burp.runner;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ByteArray;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.ExecutionResult;
import burp.utils.SharedRequestPipeline;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionRunnerControlTest {

    @Test
    void pauseAfterCurrentBlocksNextRequestUntilResume() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CopyOnWriteArrayList<String> started = new CopyOnWriteArrayList<>();

        CollectionRunner runner = new CollectionRunner(null, new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
                started.add(req.name);
                if ("Request 1".equals(req.name)) {
                    firstStarted.countDown();
                    awaitLatch(allowFirstToFinish);
                } else if ("Request 2".equals(req.name)) {
                    secondStarted.countDown();
                }

                ExecutionResult exec = new ExecutionResult();
                exec.success = true;
                exec.requestHeaders = "GET /" + req.name + " HTTP/1.1\r\nHost: example.com\r\n\r\n";
                exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
                exec.response = mockResponse();
                return exec;
            }
        }, null);
        runner.setDelayMs(0);

        ApiCollection collection = new ApiCollection();
        collection.name = "Control Collection";
        ApiRequest request1 = new ApiRequest();
        request1.name = "Request 1";
        request1.url = "http://example.com/1";
        request1.sequenceOrder = 1;
        request1.sourceCollection = collection.name;
        collection.requests.add(request1);

        ApiRequest request2 = new ApiRequest();
        request2.name = "Request 2";
        request2.url = "http://example.com/2";
        request2.sequenceOrder = 2;
        request2.sourceCollection = collection.name;
        collection.requests.add(request2);

        runner.runCollections(List.of(collection), List.of(request1, request2));

        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
        runner.pauseAfterCurrent();
        assertThat(runner.isPaused()).isTrue();
        allowFirstToFinish.countDown();

        assertThat(waitForCondition(runner::isPaused, 2000)).isTrue();
        assertThat(secondStarted.await(250, TimeUnit.MILLISECONDS)).isFalse();

        runner.resume();

        assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
        waitForRunnerToStop(runner);

        assertThat(started).containsExactly("Request 1", "Request 2");
        assertThat(runner.getResults()).hasSize(2);
        assertThat(runner.isPaused()).isFalse();
    }

    @Test
    void runNextOnlyRunsOneQueuedRequestThenPausesAgain() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch allowSecondToFinish = new CountDownLatch(1);
        CountDownLatch thirdStarted = new CountDownLatch(1);
        CopyOnWriteArrayList<String> started = new CopyOnWriteArrayList<>();
        AtomicInteger executionCount = new AtomicInteger();

        CollectionRunner runner = new CollectionRunner(null, new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
                started.add(req.name);
                executionCount.incrementAndGet();
                if ("Request 1".equals(req.name)) {
                    firstStarted.countDown();
                    awaitLatch(allowFirstToFinish);
                } else if ("Request 2".equals(req.name)) {
                    secondStarted.countDown();
                    awaitLatch(allowSecondToFinish);
                } else if ("Request 3".equals(req.name)) {
                    thirdStarted.countDown();
                }

                ExecutionResult exec = new ExecutionResult();
                exec.success = true;
                exec.requestHeaders = "GET /" + req.name + " HTTP/1.1\r\nHost: example.com\r\n\r\n";
                exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
                exec.response = mockResponse();
                return exec;
            }
        }, null);
        runner.setDelayMs(0);

        ApiCollection collection = new ApiCollection();
        collection.name = "Step Collection";
        ApiRequest request1 = new ApiRequest();
        request1.name = "Request 1";
        request1.url = "http://example.com/1";
        request1.sequenceOrder = 1;
        request1.sourceCollection = collection.name;
        collection.requests.add(request1);

        ApiRequest request2 = new ApiRequest();
        request2.name = "Request 2";
        request2.url = "http://example.com/2";
        request2.sequenceOrder = 2;
        request2.sourceCollection = collection.name;
        collection.requests.add(request2);

        ApiRequest request3 = new ApiRequest();
        request3.name = "Request 3";
        request3.url = "http://example.com/3";
        request3.sequenceOrder = 3;
        request3.sourceCollection = collection.name;
        collection.requests.add(request3);

        runner.runCollections(List.of(collection), List.of(request1, request2, request3));

        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
        runner.pauseAfterCurrent();
        allowFirstToFinish.countDown();
        assertThat(waitForCondition(runner::isPaused, 2000)).isTrue();
        assertThat(secondStarted.getCount()).isEqualTo(1);
        assertThat(thirdStarted.getCount()).isEqualTo(1);

        runner.runNextOnly();
        assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();

        allowSecondToFinish.countDown();
        assertThat(waitForCondition(runner::isPaused, 2000)).isTrue();
        assertThat(thirdStarted.getCount()).isEqualTo(1);

        runner.resume();
        assertThat(thirdStarted.await(2, TimeUnit.SECONDS)).isTrue();
        waitForRunnerToStop(runner);

        assertThat(started).containsExactly("Request 1", "Request 2", "Request 3");
        assertThat(executionCount.get()).isEqualTo(3);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for latch", e);
        }
    }

    private static boolean waitForCondition(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static void waitForRunnerToStop(CollectionRunner runner) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (runner.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(runner.isRunning()).isFalse();
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
}
