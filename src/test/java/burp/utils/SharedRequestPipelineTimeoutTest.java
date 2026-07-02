package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.RedirectionMode;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RedirectPolicy;
import burp.scripts.ExecutionSource;
import burp.testsupport.RedirectTestSupport;
import burp.testsupport.RunnerScriptTestFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SharedRequestPipelineTimeoutTest {

    @Test
    void requestOptionsFactoryReceivesConfiguredTimeout() {
        AtomicInteger capturedTimeout = new AtomicInteger();
        SharedRequestPipeline pipeline = pipeline(capturedTimeout, mockApi(() -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")));
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.responseTimeoutMillis = 12_345;
        policy.normalize();

        pipeline.execute(request("https://example.test/"), collection(), false, null, null, null, environment(),
                ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), policy, null);

        assertThat(capturedTimeout.get()).isEqualTo(12_345);
    }

    @Test
    void responseTimeoutIsAppliedToEveryRedirectHop() {
        RedirectTestSupport.withHttpFactories(() -> {
            AtomicInteger capturedTimeout = new AtomicInteger();
            AtomicReference<RequestOptions> firstOptions = new AtomicReference<>();
            AtomicReference<RequestOptions> secondOptions = new AtomicReference<>();
            AtomicInteger callCount = new AtomicInteger();
            MontoyaApi api = mockApi(() -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain"));
            when(api.http().sendRequest(any(), any(RequestOptions.class))).thenAnswer(invocation -> {
                int call = callCount.incrementAndGet();
                RequestOptions options = invocation.getArgument(1);
                if (call == 1) {
                    firstOptions.set(options);
                    return responseWithLocation(302, "/next");
                }
                secondOptions.set(options);
                return RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain");
            });
            SharedRequestPipeline pipeline = pipeline(capturedTimeout, api);
            ExecutionResult result = pipeline.execute(request("https://example.test/start"), collection(), true, null, null, null, environment(),
                    ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), ExecutionPolicy.workbenchDefaults(), null);

            assertThat(result.success).isTrue();
            assertThat(capturedTimeout.get()).isEqualTo(30_000);
            assertThat(firstOptions.get()).isNotNull();
            assertThat(firstOptions.get()).isSameAs(secondOptions.get());
            return null;
        });
    }

    @Test
    void responseTimeoutIsClassifiedSeparately() {
        MontoyaApi api = mockApi(() -> {
            throw new RuntimeException(new SocketTimeoutException("timed out"));
        });
        SharedRequestPipeline pipeline = pipeline(new AtomicInteger(), api);
        ExecutionResult result = pipeline.execute(request("https://example.test/"), collection(), false, null, null, null, environment(),
                ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), ExecutionPolicy.workbenchDefaults(), null);

        assertThat(result.responseTimedOut).isTrue();
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.READY);
        assertThat(result.requestSent).isTrue();
    }

    @Test
    void timeoutSetsRequestSentTrue() {
        MontoyaApi api = mockApi(() -> {
            throw new RuntimeException(new SocketTimeoutException("timed out"));
        });
        SharedRequestPipeline pipeline = pipeline(new AtomicInteger(), api);
        ExecutionResult result = pipeline.execute(request("https://example.test/"), collection(), false, null, null, null, environment(),
                ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), ExecutionPolicy.workbenchDefaults(), null);

        assertThat(result.requestSent).isTrue();
        assertThat(result.responseTimedOut).isTrue();
    }

    @Test
    void timeoutClampedToMinimum() {
        AtomicInteger capturedTimeout = new AtomicInteger();
        SharedRequestPipeline pipeline = pipeline(capturedTimeout, mockApi(() -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")));
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.responseTimeoutMillis = 1;
        policy.normalize();

        pipeline.execute(request("https://example.test/"), collection(), false, null, null, null, environment(),
                ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), policy, null);

        assertThat(capturedTimeout.get()).isEqualTo(1_000);
    }

    @Test
    void timeoutClampedToMaximum() {
        AtomicInteger capturedTimeout = new AtomicInteger();
        SharedRequestPipeline pipeline = pipeline(capturedTimeout, mockApi(() -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")));
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.responseTimeoutMillis = 999_999;
        policy.normalize();

        pipeline.execute(request("https://example.test/"), collection(), false, null, null, null, environment(),
                ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), policy, null);

        assertThat(capturedTimeout.get()).isEqualTo(300_000);
    }

    @Test
    void nonPositiveTimeoutUsesDefault() {
        AtomicInteger capturedTimeout = new AtomicInteger();
        SharedRequestPipeline pipeline = pipeline(capturedTimeout, mockApi(() -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")));
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.responseTimeoutMillis = 0;
        policy.normalize();

        pipeline.execute(request("https://example.test/"), collection(), false, null, null, null, environment(),
                ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), policy, null);

        assertThat(capturedTimeout.get()).isEqualTo(30_000);
    }

    @Test
    void noRedirectModeIsAlwaysConfigured() {
        try (MockedStatic<RequestOptions> optionsFactory = Mockito.mockStatic(RequestOptions.class)) {
            RequestOptions options = Mockito.mock(RequestOptions.class);
            optionsFactory.when(RequestOptions::requestOptions).thenReturn(options);
            when(options.withRedirectionMode(RedirectionMode.NEVER)).thenReturn(options);
            when(options.withResponseTimeout(any(Integer.class))).thenReturn(options);
            AtomicInteger timeout = new AtomicInteger();
            MontoyaApi api = mockApi(() -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain"));
            SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);
            pipeline.execute(request("https://example.test/"), collection(), false, null, null, null, environment(),
                    ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), ExecutionPolicy.workbenchDefaults(), null);

            Mockito.verify(options).withRedirectionMode(RedirectionMode.NEVER);
        }
    }

    private static SharedRequestPipeline pipeline(AtomicInteger timeoutCapture, MontoyaApi api) {
        return new SharedRequestPipeline(
                api,
                new RequestBuilder(null),
                new ScriptEngine(null, ScriptMode.DISABLED),
                null,
                null,
                timeout -> {
                    timeoutCapture.set(timeout);
                    return Mockito.mock(RequestOptions.class);
                }
        );
    }

    private static MontoyaApi mockApi(java.util.function.Supplier<burp.api.montoya.http.message.HttpRequestResponse> responseSupplier) {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> requests = new CopyOnWriteArrayList<>();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(sendCount, requests, responseSupplier);
        return api;
    }

    private static ApiRequest request(String url) {
        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Request";
        request.method = "GET";
        request.url = url;
        return request;
    }

    private static ApiCollection collection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        return collection;
    }

    private static EnvironmentProfile environment() {
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Env";
        return environment;
    }

    private static HttpRequestResponse responseWithLocation(int statusCode, String location) {
        burp.api.montoya.http.message.responses.HttpResponse response = Mockito.mock(burp.api.montoya.http.message.responses.HttpResponse.class);
        when(response.statusCode()).thenReturn((short) statusCode);
        when(response.reasonPhrase()).thenReturn("Found");
        burp.api.montoya.core.ByteArray body = Mockito.mock(burp.api.montoya.core.ByteArray.class);
        when(body.getBytes()).thenReturn(new byte[0]);
        when(body.length()).thenReturn(0);
        when(response.body()).thenReturn(body);
        when(response.bodyToString()).thenReturn("");
        HttpHeader header = Mockito.mock(HttpHeader.class);
        when(header.name()).thenReturn("Location");
        when(header.value()).thenReturn(location);
        when(header.toString()).thenReturn("Location: " + location);
        when(response.headers()).thenReturn(java.util.List.of(header));
        HttpRequestResponse wrapper = Mockito.mock(HttpRequestResponse.class);
        when(wrapper.response()).thenReturn(response);
        return wrapper;
    }
}
