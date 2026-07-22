package burp.runner;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.sitemap.SiteMap;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.RedirectHop;
import burp.models.RunnerResult;
import burp.utils.ExecutionResult;
import burp.utils.SharedRequestPipeline;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class RunnerProjectStorageAttributionTest {

    @Test
    void successfulFailureRetryRedirectDependentAdHocCancellationAndConsecutiveRunsAreAttributed()
            throws Exception {
        try (MockedStatic<Annotations> annotations = mockStatic(Annotations.class)) {
            annotations.when(() -> Annotations.annotations("[Runner] Storage request", HighlightColor.CYAN))
                    .thenReturn(mock(Annotations.class));

            Attribution one = execute(List.of(success(200, 1024, false, false)), 0, false, 1);
            Attribution httpFailure = execute(List.of(success(500, 2048, false, false)), 0, false, 1);
            Attribution retry = execute(List.of(
                    success(503, 512, false, false),
                    success(200, 1024, false, false)), 1, false, 1);
            Attribution redirect = execute(List.of(redirectSuccess()), 0, false, 1);
            Attribution dependent = execute(List.of(success(200, 256, true, false)), 0, false, 1);
            Attribution adHoc = execute(List.of(success(200, 256, false, true)), 0, false, 1);
            Attribution cancelled = execute(List.of(success(200, 128, false, false)), 0, true, 1);
            Attribution consecutive = execute(List.of(
                    success(200, 128, false, false),
                    success(200, 128, false, false)), 0, false, 2);

            assertThat(one.siteMapAdds).isEqualTo(1);
            assertThat(httpFailure.siteMapAdds).isEqualTo(1);
            assertThat(retry.attempts).isEqualTo(2);
            assertThat(retry.siteMapAdds).isEqualTo(2);
            assertThat(redirect.siteMapAdds).isEqualTo(1);
            assertThat(redirect.redirectHops).isEqualTo(1);
            assertThat(dependent.dependentResults).isEqualTo(1);
            assertThat(adHoc.adHocResults).isEqualTo(1);
            assertThat(cancelled.siteMapAdds).isZero();
            assertThat(consecutive.attempts).isEqualTo(2);
            assertThat(consecutive.siteMapAdds).isEqualTo(2);
            assertThat(one.approximateResponseBytes).isEqualTo(1024);
            assertThat(one.approximateRequestBytes).isPositive();
            assertThat(one.runnerResults).isEqualTo(1);
            assertThat(one.requestCompleteCallbacks).isEqualTo(1);
        }
    }

    private static Attribution execute(List<ExecutionResult> executions,
                                       int retries,
                                       boolean cancelBeforeRun,
                                       int runCount) throws Exception {
        MontoyaApi api = mock(MontoyaApi.class);
        SiteMap siteMap = mock(SiteMap.class);
        when(api.siteMap()).thenReturn(siteMap);
        AtomicInteger siteMapAdds = new AtomicInteger();
        AtomicInteger siteMapRequestBytes = new AtomicInteger();
        AtomicInteger siteMapResponseBytes = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            HttpRequestResponse value = invocation.getArgument(0);
            siteMapAdds.incrementAndGet();
            if (value != null && value.response() != null && value.response().body() != null) {
                siteMapResponseBytes.addAndGet(value.response().body().length());
            }
            siteMapRequestBytes.addAndGet(64);
            return null;
        }).when(siteMap).add(nullable(HttpRequestResponse.class));

        Queue<ExecutionResult> queue = new ArrayDeque<>(executions);
        AtomicInteger attempts = new AtomicInteger();
        SharedRequestPipeline pipeline = new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest request, ApiCollection collection, boolean followRedirects) {
                attempts.incrementAndGet();
                ExecutionResult result = queue.poll();
                return result != null ? result : success(200, 128, false, false);
            }
        };
        CollectionRunner runner = new CollectionRunner(api, pipeline, null);
        runner.setDelayMs(0);
        if (!cancelBeforeRun) {
            runner.setExecutorServiceFactory(RunnerProjectStorageAttributionTest::directExecutorService);
        }
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = retries;
        policy.retryableStatusCodes.add(503);
        policy.normalize();
        runner.setRetryPolicy(policy);

        List<RunnerResult> completed = new ArrayList<>();
        AtomicInteger history = new AtomicInteger();
        runner.addListener(new CollectionRunner.RunnerListener() {
            public void onStart(String name, int total) { }
            public void onSkip(String name, String reason) { }
            public void onRequestComplete(RunnerResult result) {
                completed.add(result);
                history.incrementAndGet();
            }
            public void onComplete(List<RunnerResult> results) { }
            public void onError(String message) { }
        });

        ApiCollection collection = new ApiCollection();
        collection.name = "Storage attribution";
        ApiRequest request = new ApiRequest();
        request.id = "request";
        request.name = "Storage request";
        request.method = "GET";
        request.url = "https://example.test/storage";
        request.sourceCollection = collection.name;
        collection.requests.add(request);

        if (cancelBeforeRun) {
            runner.runCollections(List.of(collection), List.of(request), true);
            runner.cancel();
            waitForRunner(runner);
        } else {
            for (int i = 0; i < runCount; i++) {
                runner.runCollections(List.of(collection), List.of(request));
                waitForRunner(runner);
            }
        }
        javax.swing.SwingUtilities.invokeAndWait(() -> { });

        List<RunnerResult> finalResults = runner.getResults();
        int dependent = completed.stream().mapToInt(value -> value.dependentExecution ? 1 : 0).sum();
        int adHoc = completed.stream().mapToInt(value -> value.adHocExecution ? 1 : 0).sum();
        int hops = completed.stream().mapToInt(value -> value.redirectHops != null ? value.redirectHops.size() : 0).sum();
        return new Attribution(attempts.get(), siteMapAdds.get(), siteMapRequestBytes.get(),
                siteMapResponseBytes.get(), finalResults.size(), completed.size(), dependent, adHoc, hops,
                history.get());
    }

    private static ExecutionResult success(int status, int responseBytes,
                                           boolean dependent, boolean adHoc) {
        ExecutionResult result = new ExecutionResult();
        result.success = true;
        result.requestSent = true;
        result.rawRequestBytes = "GET /storage HTTP/1.1\r\nHost: example.test\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        result.rawRequestText = new String(result.rawRequestBytes, StandardCharsets.US_ASCII);
        result.response = response(status, responseBytes);
        result.dependentExecution = dependent;
        result.adHocExecution = adHoc;
        return result;
    }

    private static ExecutionResult redirectSuccess() {
        ExecutionResult result = success(200, 512, false, false);
        RedirectHop hop = new RedirectHop();
        hop.hopNumber = 1;
        hop.statusCode = 302;
        hop.sourceUrl = "https://example.test/start";
        hop.targetUrl = "https://example.test/storage";
        hop.followed = true;
        result.redirectHops.add(hop);
        return result;
    }

    private static HttpRequestResponse response(int status, int bytes) {
        HttpResponse response = mock(HttpResponse.class);
        ByteArray body = mock(ByteArray.class);
        when(body.length()).thenReturn(bytes);
        when(body.getBytes()).thenReturn(new byte[bytes]);
        when(response.statusCode()).thenReturn((short) status);
        when(response.body()).thenReturn(body);
        when(response.bodyToString()).thenReturn("r".repeat(bytes));
        HttpRequestResponse wrapper = mock(HttpRequestResponse.class);
        when(wrapper.response()).thenReturn(response);
        when(wrapper.withAnnotations(nullable(Annotations.class))).thenReturn(wrapper);
        return wrapper;
    }

    private static void waitForRunner(CollectionRunner runner) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (runner.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(runner.isRunning()).isFalse();
    }

    private static ExecutorService directExecutorService() {
        return new AbstractExecutorService() {
            private volatile boolean shutdown;
            public void shutdown() { shutdown = true; }
            public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
            public boolean isShutdown() { return shutdown; }
            public boolean isTerminated() { return shutdown; }
            public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
            public void execute(Runnable command) { command.run(); }
        };
    }

    private record Attribution(int attempts, int siteMapAdds, int approximateRequestBytes,
                               int approximateResponseBytes, int runnerResults, int completedResults,
                               int dependentResults, int adHocResults, int redirectHops,
                               int requestCompleteCallbacks) { }
}
