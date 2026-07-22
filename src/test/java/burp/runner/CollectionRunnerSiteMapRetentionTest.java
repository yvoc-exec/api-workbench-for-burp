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
import burp.utils.ExecutionPreflightStatus;
import burp.utils.ExecutionResult;
import burp.utils.SharedRequestPipeline;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class CollectionRunnerSiteMapRetentionTest {

    @Test
    void freshAndExplicitFalseNeverRetainQualifyingResponses() throws Exception {
        try (Harness fresh = new Harness(false, response(200), response(404), response(500));
             Harness explicitFalse = new Harness(false, response(200))) {
            assertThat(fresh.runner.isAddResponsesToSiteMap()).isFalse();
            fresh.run(3);
            explicitFalse.runner.setAddResponsesToSiteMap(false);
            explicitFalse.run(1);

            assertThat(fresh.siteMapAdds()).isZero();
            assertThat(fresh.attempts()).isEqualTo(3);
            assertThat(fresh.completed()).hasSize(3);
            assertThat(explicitFalse.siteMapAdds()).isZero();
            assertThat(explicitFalse.completed()).hasSize(1);
        }
    }

    @Test
    void optInRetainsEveryQualifyingHttpResponseWithoutChangingResults() throws Exception {
        try (Harness harness = new Harness(true, response(200), response(404), response(500))) {
            assertThat(harness.runner.isAddResponsesToSiteMap()).isTrue();
            harness.run(3);

            assertThat(harness.siteMapAdds()).isEqualTo(3);
            assertThat(harness.completed()).extracting(result -> result.statusCode)
                    .containsExactly(200, 404, 500);
            assertThat(harness.completed()).hasSize(3);
        }
    }

    @Test
    void retryAddsEachAttemptOnlyWhenEnabled() throws Exception {
        for (boolean enabled : List.of(false, true)) {
            try (Harness harness = new Harness(enabled, response(503), response(200))) {
                RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
                policy.maxRetries = 1;
                policy.retryableStatusCodes.add(503);
                policy.normalize();
                harness.runner.setRetryPolicy(policy);

                harness.run(1);

                assertThat(harness.attempts()).isEqualTo(2);
                assertThat(harness.siteMapAdds()).isEqualTo(enabled ? 2 : 0);
                assertThat(harness.completed()).singleElement().satisfies(result -> {
                    assertThat(result.attemptNumber).isEqualTo(2);
                    assertThat(result.totalAttempts).isEqualTo(2);
                });
            }
        }
    }

    @Test
    void redirectDependentAdHocAndConsecutiveRunsPreserveAttemptLevelSemantics() throws Exception {
        for (boolean enabled : List.of(false, true)) {
            ExecutionResult redirect = response(200);
            RedirectHop hop = new RedirectHop();
            hop.hopNumber = 1;
            hop.statusCode = 302;
            hop.sourceUrl = "https://example.test/start";
            hop.targetUrl = "https://example.test/runner";
            hop.followed = true;
            redirect.redirectHops.add(hop);
            ExecutionResult dependent = response(200);
            dependent.dependentExecution = true;
            ExecutionResult adHoc = response(200);
            adHoc.adHocExecution = true;

            try (Harness harness = new Harness(enabled, redirect, dependent, adHoc,
                    response(200), response(200))) {
                harness.run(3);
                harness.run(1);
                harness.run(1);

                assertThat(harness.siteMapAdds()).isEqualTo(enabled ? 5 : 0);
                assertThat(harness.completed()).hasSize(5);
                assertThat(harness.completed().get(0).redirectHops).hasSize(1);
                assertThat(harness.completed().get(1).dependentExecution).isTrue();
                assertThat(harness.completed().get(2).adHocExecution).isTrue();
            }
        }
    }

    @Test
    void responseLessCancelledAndBlockedAttemptsNeverReachSiteMap() throws Exception {
        ExecutionResult cancelled = noResponse("Cancelled");
        cancelled.cancellationRequested = true;
        ExecutionResult blocked = noResponse("Unresolved variable");
        blocked.preflightStatus = ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES;
        ExecutionResult transportFailure = noResponse("No response received");

        for (ExecutionResult execution : List.of(cancelled, blocked, transportFailure)) {
            try (Harness harness = new Harness(true, execution)) {
                harness.run(1);
                assertThat(harness.siteMapAdds()).isZero();
                assertThat(harness.completed()).hasSize(1);
            }
        }
    }

    @Test
    void nullApiAndAnnotationFailureRemainNonFatal() throws Exception {
        try (Harness nullApi = new Harness(true, (MontoyaApi) null, response(200))) {
            nullApi.run(1);
            assertThat(nullApi.completed()).hasSize(1);
        }

        try (Harness failingAnnotations = new Harness(true, response(200));
             MockedStatic<Annotations> annotations = mockStatic(Annotations.class)) {
            annotations.when(() -> Annotations.annotations(
                            "[Runner] Site map request", HighlightColor.CYAN))
                    .thenThrow(new IllegalStateException("annotation unavailable"));
            failingAnnotations.runWithoutAnnotationStub(1);
            assertThat(failingAnnotations.completed()).hasSize(1);
            assertThat(failingAnnotations.siteMapAdds()).isZero();
        }
    }

    private static ExecutionResult response(int status) {
        ExecutionResult result = new ExecutionResult();
        result.success = true;
        result.requestSent = true;
        result.rawRequestBytes = "GET /runner HTTP/1.1\r\nHost: example.test\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        result.rawRequestText = new String(result.rawRequestBytes, StandardCharsets.US_ASCII);
        HttpResponse response = mock(HttpResponse.class);
        ByteArray body = mock(ByteArray.class);
        when(body.length()).thenReturn(2);
        when(body.getBytes()).thenReturn(new byte[]{'o', 'k'});
        when(response.statusCode()).thenReturn((short) status);
        when(response.body()).thenReturn(body);
        when(response.bodyToString()).thenReturn("ok");
        HttpRequestResponse wrapper = mock(HttpRequestResponse.class);
        when(wrapper.response()).thenReturn(response);
        when(wrapper.withAnnotations(nullable(Annotations.class))).thenReturn(wrapper);
        result.response = wrapper;
        return result;
    }

    private static ExecutionResult noResponse(String error) {
        ExecutionResult result = new ExecutionResult();
        result.success = false;
        result.requestSent = false;
        result.errorMessage = error;
        return result;
    }

    private static final class Harness implements AutoCloseable {
        final CollectionRunner runner;
        private final Queue<ExecutionResult> executions;
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger siteMapAdds = new AtomicInteger();
        private final List<RunnerResult> completed = new ArrayList<>();
        private final ApiCollection collection = new ApiCollection();
        private MockedStatic<Annotations> annotations;

        Harness(boolean enabled, ExecutionResult... executions) {
            this(enabled, mockedApi(), executions);
        }

        Harness(boolean enabled, MontoyaApi api, ExecutionResult... executions) {
            this.executions = new ArrayDeque<>(List.of(executions));
            if (api != null) {
                SiteMap siteMap = api.siteMap();
                org.mockito.Mockito.doAnswer(invocation -> {
                    siteMapAdds.incrementAndGet();
                    return null;
                }).when(siteMap).add(nullable(HttpRequestResponse.class));
            }
            SharedRequestPipeline pipeline = new SharedRequestPipeline(null, null, null, null) {
                @Override
                public ExecutionResult execute(ApiRequest request, ApiCollection source, boolean followRedirects) {
                    attempts.incrementAndGet();
                    return Harness.this.executions.remove();
                }
            };
            runner = new CollectionRunner(api, pipeline, null);
            runner.setAddResponsesToSiteMap(enabled);
            runner.setDelayMs(0);
            runner.setExecutorServiceFactory(CollectionRunnerSiteMapRetentionTest::directExecutorService);
            runner.addListener(new CollectionRunner.RunnerListener() {
                public void onStart(String name, int total) { }
                public void onSkip(String name, String reason) { }
                public void onRequestComplete(RunnerResult result) { completed.add(result); }
                public void onComplete(List<RunnerResult> results) { }
                public void onError(String message) { }
            });
            collection.name = "Site map collection";
        }

        void run(int count) throws Exception {
            annotations = mockStatic(Annotations.class);
            annotations.when(() -> Annotations.annotations(
                            "[Runner] Site map request", HighlightColor.CYAN))
                    .thenReturn(mock(Annotations.class));
            try {
                runWithoutAnnotationStub(count);
            } finally {
                annotations.close();
                annotations = null;
            }
        }

        void runWithoutAnnotationStub(int count) throws Exception {
            List<ApiRequest> requests = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ApiRequest request = new ApiRequest();
                request.id = "request-" + attempts.get() + '-' + i;
                request.name = "Site map request";
                request.method = "GET";
                request.url = "https://example.test/runner";
                request.sourceCollection = collection.name;
                collection.requests.add(request);
                requests.add(request);
            }
            runner.runCollections(List.of(collection), requests);
            assertThat(runner.isRunning()).isFalse();
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
        }

        int attempts() { return attempts.get(); }
        int siteMapAdds() { return siteMapAdds.get(); }
        List<RunnerResult> completed() { return completed; }

        @Override public void close() {
            if (annotations != null) {
                annotations.close();
            }
        }

        private static MontoyaApi mockedApi() {
            MontoyaApi api = mock(MontoyaApi.class);
            SiteMap siteMap = mock(SiteMap.class);
            when(api.siteMap()).thenReturn(siteMap);
            return api;
        }
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
}
