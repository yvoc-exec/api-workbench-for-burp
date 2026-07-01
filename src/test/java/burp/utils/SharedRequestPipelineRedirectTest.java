package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.RedirectTerminationReason;
import burp.scripts.ExecutionSource;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptFlowControl;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.testsupport.RunnerScriptTestFixtures;
import burp.testsupport.RedirectTestSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SharedRequestPipelineRedirectTest {

    @Test
    void booleanOnlyOverloadRunsScriptsOnceAcrossRedirectChain() {
            RedirectTestSupport.withHttpFactories(() -> {
            CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> captured = new CopyOnWriteArrayList<>();
            AtomicInteger sendCount = new AtomicInteger();
            AtomicInteger responseIndex = new AtomicInteger();
            HttpRequestResponse firstResponse = responseWithLocation(302, "/next");
            HttpRequestResponse secondResponse = RunnerScriptTestFixtures.mockResponse(200, "{\"ok\":true}", "application/json");
            MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(sendCount, captured, () -> {
                int index = responseIndex.getAndIncrement();
                return index == 0 ? firstResponse : secondResponse;
            });

            SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.FULL_JS), null);
            ApiCollection collection = new ApiCollection();
            collection.name = "APIM";
            collection.format = "api-workbench";

            ApiRequest request = new ApiRequest();
            request.name = "Login";
            request.method = "POST";
            request.url = "https://api.example.test/login";
            request.headers = new ArrayList<>();
            request.body = new ApiRequest.Body();
            request.body.mode = "raw";
            request.body.raw = "{\"ok\":true}";
            request.body.contentType = "application/json";
            request.scriptBlocks = new ArrayList<>();
            request.scriptBlocks.add(script("pre", ScriptPhase.PRE_REQUEST, "console.log('pre-script');"));
            request.scriptBlocks.add(script("post", ScriptPhase.POST_RESPONSE, "console.log('post-script');"));

            ExecutionResult result = pipeline.execute(request, collection, true);

            assertThat(sendCount).hasValue(2);
            assertThat(result.success).isTrue();
            assertThat(result.redirectTerminationReason).isEqualTo(RedirectTerminationReason.FINAL_RESPONSE);
            assertThat(result.initialResolvedUrl).isEqualTo("https://api.example.test/login");
            assertThat(result.finalResolvedUrl).isEqualTo("https://api.example.test/next");
            assertThat(result.builtRequest).isNotNull();
            assertThat(result.finalRequest).isNotNull();
            assertThat(result.response.response().statusCode()).isEqualTo((short) 200);
            assertThat(result.redirectHops).hasSize(1);
            assertThat(result.scriptLogs.stream()
                    .filter(log -> log != null && log.message != null && log.message.contains("pre-script"))
                    .toList()).hasSize(1);
            assertThat(result.scriptLogs.stream()
                    .filter(log -> log != null && log.message != null && log.message.contains("post-script"))
                    .toList()).hasSize(1);
            assertThat(result.scriptFlowControl).isEqualTo(ScriptFlowControl.CONTINUE);
            return null;
        });
    }

    @Test
    void redirectFailureRetainsHopEvidenceAndSkipsPostResponseScript() {
        RedirectTestSupport.withHttpFactories(() -> {
            CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> captured = new CopyOnWriteArrayList<>();
            AtomicInteger responseIndex = new AtomicInteger();
            HttpRequestResponse firstResponse = responseWithLocation(302, "/next");
            HttpRequestResponse loopResponse = responseWithLocation(302, "/start");
            MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(new AtomicInteger(), captured, () -> {
                int index = responseIndex.getAndIncrement();
                return index == 0 ? firstResponse : loopResponse;
            });

            SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.FULL_JS), null);
            ApiCollection collection = new ApiCollection();
            collection.name = "APIM";
            collection.format = "api-workbench";

            ApiRequest request = new ApiRequest();
            request.name = "Loop";
            request.method = "GET";
            request.url = "https://api.example.test/start";
            request.headers = new ArrayList<>();
            request.scriptBlocks = new ArrayList<>();
            request.scriptBlocks.add(script("pre", ScriptPhase.PRE_REQUEST, "console.log('pre-loop');"));
            request.scriptBlocks.add(script("post", ScriptPhase.POST_RESPONSE, "console.log('post-loop');"));

            ExecutionResult result = pipeline.execute(request, collection, true);

            assertThat(result.success).isFalse();
            assertThat(result.redirectTerminationReason).isEqualTo(RedirectTerminationReason.LOOP_DETECTED);
            assertThat(result.errorMessage).contains("loop");
            assertThat(result.redirectHops).hasSize(2);
            assertThat(result.scriptLogs.stream()
                    .filter(log -> log != null && log.message != null && log.message.contains("pre-loop"))
                    .toList()).hasSize(1);
            assertThat(result.scriptLogs.stream()
                    .filter(log -> log != null && log.message != null && log.message.contains("post-loop"))
                    .toList()).isEmpty();
            assertThat(result.response).isNotNull();
            return null;
        });
    }

    private static ScriptBlock script(String id, ScriptPhase phase, String source) {
        ScriptBlock block = new ScriptBlock();
        block.id = id;
        block.dialect = ScriptDialect.POSTMAN;
        block.phase = phase;
        block.scope = ScriptScope.REQUEST;
        block.source = source;
        block.enabled = true;
        return block;
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
}
