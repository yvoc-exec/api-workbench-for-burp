package burp.runner;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.RedirectCrossOriginMode;
import burp.models.RedirectHop;
import burp.models.RedirectPolicy;
import burp.models.RedirectTerminationReason;
import burp.models.RunnerStopConditions;
import burp.testsupport.RunnerScriptTestFixtures;
import burp.testsupport.RedirectTestSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionRunnerRedirectTest {

    @Test
    void runnerPassesPolicyThroughToRedirectExecutionAndKeepsHopsWithFinalResult() throws Exception {
            RedirectTestSupport.withHttpFactories(() -> {
            CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> captured = new CopyOnWriteArrayList<>();
            AtomicInteger sendCount = new AtomicInteger();
            AtomicInteger responseIndex = new AtomicInteger();
            HttpRequestResponse firstResponse = responseWithLocation(302, "https://other.example.test/next");
            HttpRequestResponse secondResponse = RunnerScriptTestFixtures.mockResponse(200, "{\"ok\":true}", "application/json");
            MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(sendCount, captured, () -> {
                int index = responseIndex.getAndIncrement();
                return index == 0 ? firstResponse : secondResponse;
            });

            CollectionRunner runner = RunnerScriptTestFixtures.newRunner(api);
            runner.setExecutorServiceFactory(CollectionRunnerRedirectTest::directExecutorService);
            runner.setFollowRedirects(true);
            RedirectPolicy policy = RedirectPolicy.defaults();
            policy.crossOriginMode = RedirectCrossOriginMode.PRESERVE_ANY_HTTPS_TARGET;
            runner.setRedirectPolicy(policy);

            ApiCollection collection = new ApiCollection();
            collection.name = "APIM";
            ApiRequest request = new ApiRequest();
            request.name = "Login";
            request.method = "POST";
            request.url = "https://api.example.test/login";
            request.sequenceOrder = 1;
            request.sourceCollection = collection.name;
            request.headers = new ArrayList<>();
            request.headers.add(new ApiRequest.Header("Authorization", "Bearer secret", false));
            request.headers.add(new ApiRequest.Header("Cookie", "session=abc", false));
            request.headers.add(new ApiRequest.Header("Proxy-Authorization", "Basic proxy", false));
            request.headers.add(new ApiRequest.Header("X-Custom", "keep", false));
            request.body = new ApiRequest.Body();
            request.body.mode = "raw";
            request.body.raw = "{\"ok\":true}";
            collection.requests.add(request);

            runner.runCollections(List.of(collection), List.of(request));
            RunnerScriptTestFixtures.waitForRunnerToStop(runner);

            assertThat(sendCount).hasValue(2);
            assertThat(runner.getResults()).hasSize(1);
            assertThat(runner.getResults().get(0).redirectsEnabled).isTrue();
            assertThat(runner.getResults().get(0).initialResolvedUrl).isEqualTo("https://api.example.test/login");
            assertThat(runner.getResults().get(0).finalResolvedUrl).isEqualTo("https://other.example.test/next");
            assertThat(runner.getResults().get(0).redirectTerminationReason).isEqualTo(RedirectTerminationReason.FINAL_RESPONSE);
            assertThat(runner.getResults().get(0).redirectHops).hasSize(1);
            RedirectHop hop = runner.getResults().get(0).redirectHops.get(0);
            assertThat(hop.forwardedSensitiveHeaderNames).contains("Authorization", "Cookie");
            assertThat(hop.strippedSensitiveHeaderNames).contains("Proxy-Authorization");
            assertThat(rawRequest(captured.get(1))).contains("Authorization: Bearer secret").contains("Cookie: session=abc");
            assertThat(rawRequest(captured.get(1))).doesNotContain("Proxy-Authorization:");
            return null;
        });
    }

    @Test
    void stopOnStatusAtLeast400UsesFinalResponseNotIntermediateRedirect() throws Exception {
        RedirectTestSupport.withHttpFactories(() -> {
            CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> captured = new CopyOnWriteArrayList<>();
            AtomicInteger sendCount = new AtomicInteger();
            AtomicInteger responseIndex = new AtomicInteger();
            HttpRequestResponse firstResponse = responseWithLocation(302, "/next");
            HttpRequestResponse secondResponse = RunnerScriptTestFixtures.mockResponse(200, "{\"ok\":true}", "application/json");
            HttpRequestResponse finalFailureResponse = RunnerScriptTestFixtures.mockResponse(404, "{\"error\":true}", "application/json");
            MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(sendCount, captured, () -> {
                int index = responseIndex.getAndIncrement();
                if (index == 0) {
                    return firstResponse;
                }
                if (index == 1) {
                    return secondResponse;
                }
                return finalFailureResponse;
            });

            CollectionRunner runner = RunnerScriptTestFixtures.newRunner(api);
            runner.setExecutorServiceFactory(CollectionRunnerRedirectTest::directExecutorService);
            RunnerStopConditions stopConditions = new RunnerStopConditions();
            stopConditions.stopOnStatusAtLeast400 = true;
            runner.setStopConditions(stopConditions);

            ApiCollection collection = new ApiCollection();
            collection.name = "APIM";

            ApiRequest first = new ApiRequest();
            first.name = "Redirecting";
            first.method = "GET";
            first.url = "https://api.example.test/start";
            first.sequenceOrder = 1;
            first.sourceCollection = collection.name;
            first.headers = new ArrayList<>();
            first.body = new ApiRequest.Body();
            first.body.mode = "raw";
            first.body.raw = "";
            collection.requests.add(first);

            ApiRequest second = new ApiRequest();
            second.name = "Fails";
            second.method = "GET";
            second.url = "https://api.example.test/fail";
            second.sequenceOrder = 2;
            second.sourceCollection = collection.name;
            second.headers = new ArrayList<>();
            second.body = new ApiRequest.Body();
            second.body.mode = "raw";
            second.body.raw = "";
            collection.requests.add(second);

            runner.runCollections(List.of(collection), List.of(first, second));
            RunnerScriptTestFixtures.waitForRunnerToStop(runner);

            assertThat(runner.getResults()).hasSize(2);
            assertThat(runner.getResults().get(0).statusCode).isEqualTo(200);
            assertThat(runner.getResults().get(0).redirectHops).hasSize(1);
            assertThat(runner.getResults().get(1).statusCode).isEqualTo(404);
            assertThat(runner.getResults().get(1).redirectHops).isEmpty();
            assertThat(sendCount.get()).isEqualTo(3);
            return null;
        });
    }

    @Test
    void retriesRestartTheRedirectChainAndExposeOnlyFinalAttemptEvidence() throws Exception {
        RedirectTestSupport.withHttpFactories(() -> {
            CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> captured = new CopyOnWriteArrayList<>();
            AtomicInteger sendCount = new AtomicInteger();
            AtomicInteger responseIndex = new AtomicInteger();
            HttpRequestResponse firstResponse = responseWithLocation(302, "/loop");
            HttpRequestResponse secondResponse = responseWithLocation(302, "/start");
            HttpRequestResponse thirdResponse = responseWithLocation(302, "/next");
            HttpRequestResponse finalResponse = RunnerScriptTestFixtures.mockResponse(200, "{\"ok\":true}", "application/json");
            MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(sendCount, captured, () -> {
                int index = responseIndex.getAndIncrement();
                if (index == 0) {
                    return firstResponse;
                }
                if (index == 1) {
                    return secondResponse;
                }
                if (index == 2) {
                    return thirdResponse;
                }
                return finalResponse;
            });

            CollectionRunner runner = RunnerScriptTestFixtures.newRunner(api);
            runner.setExecutorServiceFactory(CollectionRunnerRedirectTest::directExecutorService);
            runner.setMaxRetries(1);

            ApiCollection collection = new ApiCollection();
            collection.name = "APIM";
            ApiRequest request = new ApiRequest();
            request.name = "Retry";
            request.method = "GET";
            request.url = "https://api.example.test/start";
            request.sequenceOrder = 1;
            request.sourceCollection = collection.name;
            request.headers = new ArrayList<>();
            request.body = new ApiRequest.Body();
            request.body.mode = "raw";
            request.body.raw = "";
            collection.requests.add(request);

            runner.runCollections(List.of(collection), List.of(request));
            RunnerScriptTestFixtures.waitForRunnerToStop(runner);

            assertThat(sendCount).hasValue(4);
            assertThat(runner.getResults()).hasSize(1);
            assertThat(runner.getResults().get(0).success).isTrue();
            assertThat(runner.getResults().get(0).attemptNumber).isEqualTo(2);
            assertThat(runner.getResults().get(0).redirectHops).hasSize(1);
            assertThat(runner.getResults().get(0).redirectTerminationReason).isEqualTo(RedirectTerminationReason.FINAL_RESPONSE);
            return null;
        });
    }

    private static HttpHeader locationHeader(String location) {
        HttpHeader header = org.mockito.Mockito.mock(HttpHeader.class);
        when(header.name()).thenReturn("Location");
        when(header.value()).thenReturn(location);
        when(header.toString()).thenReturn("Location: " + location);
        return header;
    }

    private static HttpRequestResponse responseWithLocation(int statusCode, String location) {
        burp.api.montoya.http.message.responses.HttpResponse httpResponse = mock(burp.api.montoya.http.message.responses.HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn((short) statusCode);
        when(httpResponse.reasonPhrase()).thenReturn("Found");
        burp.api.montoya.core.ByteArray body = mock(burp.api.montoya.core.ByteArray.class);
        when(body.getBytes()).thenReturn(new byte[0]);
        when(body.length()).thenReturn(0);
        when(httpResponse.body()).thenReturn(body);
        when(httpResponse.bodyToString()).thenReturn("");
        HttpHeader header = locationHeader(location);
        when(httpResponse.headers()).thenReturn(List.of(header));
        HttpRequestResponse response = mock(HttpRequestResponse.class);
        when(response.response()).thenReturn(httpResponse);
        return response;
    }

    private static ExecutorService directExecutorService() {
        return new AbstractExecutorService() {
            private volatile boolean shutdown;

            @Override
            public void shutdown() {
                shutdown = true;
            }

            @Override
            public List<Runnable> shutdownNow() {
                shutdown = true;
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                return shutdown;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }

            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
    }

    private static String rawRequest(burp.api.montoya.http.message.requests.HttpRequest request) {
        return request != null && request.toByteArray() != null
                ? new String(request.toByteArray().getBytes(), StandardCharsets.UTF_8)
                : "";
    }
}
