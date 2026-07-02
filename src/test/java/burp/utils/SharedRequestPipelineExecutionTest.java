package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptFlowControl;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.scripts.ScriptExecutionResult;
import burp.scripts.ScriptVariableMutation;
import burp.testsupport.RunnerScriptTestFixtures;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SharedRequestPipelineExecutionTest {

    @Test
    void liveScriptRuntimeUnsetRevealsAuthoredCollectionValue() throws Exception {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> capturedRequests = new CopyOnWriteArrayList<>();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(
                sendCount,
                capturedRequests,
                () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")
        );

        SharedRequestPipeline pipeline = pipeline(api, ScriptMode.DISABLED);

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        ApiRequest.Variable authored = new ApiRequest.Variable();
        authored.key = "token";
        authored.value = "persisted";
        collection.variables.add(authored);
        collection.runtimeVars.put("token", "temporary");
        ApiRequest request = new ApiRequest();
        request.name = "Unset";
        request.method = "GET";
        request.url = "https://api.example.test/{{token}}";

        ScriptExecutionResult scriptResult = new ScriptExecutionResult();
        scriptResult.success = true;
        scriptResult.engineName = "GraalJS";
        ScriptVariableMutation mutation = new ScriptVariableMutation();
        mutation.key = "token";
        mutation.newValue = null;
        mutation.scope = "collection";
        mutation.persistent = false;
        scriptResult.variableMutations.add(mutation);

        Method method = SharedRequestPipeline.class.getDeclaredMethod(
                "commitScriptVariableMutations",
                ScriptExecutionResult.class,
                Map.class,
                SharedRequestPipeline.RuntimeVariableSink.class,
                ApiCollection.class,
                ApiRequest.class,
                EnvironmentProfile.class,
                burp.scripts.ExecutionSource.class
        );
        method.setAccessible(true);
        method.invoke(pipeline, scriptResult, null, null, collection, request, new EnvironmentProfile(), burp.scripts.ExecutionSource.WORKBENCH_SEND);

        ExecutionResult result = pipeline.execute(request, collection, false);

        assertThat(sendCount).hasValue(1);
        assertThat(result.success).isTrue();
        assertThat(result.rawRequestText).contains("GET /persisted HTTP/1.1");
        assertThat(result.resolvedVariables).containsEntry("token", "persisted");
        assertThat(collection.runtimeVars).doesNotContainKey("token");
        assertThat(collection.runtimeVars).doesNotContainValue(null);
        assertThat(collection.variables)
                .extracting(variable -> variable.key + "=" + variable.value)
                .contains("token=persisted");
    }

    @Test
    void executeCapturesBuiltRequestResponseAndPostResponseArtifacts() {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> capturedRequests = new CopyOnWriteArrayList<>();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(
                sendCount,
                capturedRequests,
                () -> RunnerScriptTestFixtures.mockResponse(201, "{\"token\":\"resp-123\"}", "application/json")
        );
        SharedRequestPipeline pipeline = pipeline(api, ScriptMode.FULL_JS);

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.format = "api-workbench";

        ApiRequest request = scriptedRequest(
                "https://api.example.test/items",
                """
                        pm.environment.set('token', 'pre-token');
                        pm.request.headers.upsert('Authorization', 'Bearer ' + pm.environment.get('token'));
                        """,
                """
                        pm.test('status is 201', function () { pm.expect(pm.response.code).to.equal(201); });
                        pm.environment.set('response_token', pm.response.json().get('token'));
                        console.log('post-response');
                        """
        );

        ExecutionResult result = pipeline.execute(request, collection, true);

        assertThat(sendCount).hasValue(1);
        assertThat(capturedRequests).hasSize(1);
        assertThat(result.success).isTrue();
        assertThat(result.response.response().statusCode()).isEqualTo((short) 201);
        assertThat(result.rawRequestText).contains("Authorization: Bearer pre-token");
        assertThat(result.requestHeaders).contains("Authorization: Bearer pre-token");
        assertThat(result.assertions).hasSize(1);
        assertThat(result.assertions.get(0).passed).isTrue();
        assertThat(result.scriptLogs).anySatisfy(log -> assertThat(log.message).contains("post-response"));
        assertThat(collection.runtimeVars)
                .doesNotContainKey("token")
                .doesNotContainKey("response_token");
        assertThat(result.scriptVariableMutations).extracting(m -> m.scope).contains("environment");
    }

    @Test
    void executeInWorkbenchSendModeIgnoresRunnerOnlySkipAndStillSends() {
        AtomicInteger sendCount = new AtomicInteger();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(
                sendCount,
                new CopyOnWriteArrayList<>(),
                () -> RunnerScriptTestFixtures.mockResponse(200, "ignored", "text/plain")
        );
        SharedRequestPipeline pipeline = pipeline(api, ScriptMode.FULL_JS);

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.format = "api-workbench";

        ApiRequest request = new ApiRequest();
        request.id = "req-skip";
        request.name = "Skip";
        request.method = "GET";
        request.url = "https://api.example.test/items";
        request.headers = new ArrayList<>();
        request.scriptBlocks = new ArrayList<>();
        request.scriptBlocks.add(scriptBlock("pre-skip", ScriptDialect.POSTMAN, ScriptPhase.PRE_REQUEST, "pm.execution.skipRequest();"));

        ExecutionResult result = pipeline.execute(request, collection, true);

        assertThat(sendCount).hasValue(1);
        assertThat(result.success).isTrue();
        assertThat(result.scriptFlowControl).isEqualTo(ScriptFlowControl.CONTINUE);
        assertThat(result.scriptWarnings).anySatisfy(warning -> assertThat(warning).contains("ignored in single Send mode"));
        assertThat(result.response).isNotNull();
    }

    @Test
    void executeMapsTransportFailuresToFriendlyMessages() {
        MontoyaApi api = mock(MontoyaApi.class, RETURNS_DEEP_STUBS);
        when(api.http().sendRequest(any(burp.api.montoya.http.message.requests.HttpRequest.class), any(burp.api.montoya.http.RequestOptions.class)))
                .thenThrow(new RuntimeException("java.net.ConnectException: Connection refused"));
        when(api.http().sendRequest(any(burp.api.montoya.http.message.requests.HttpRequest.class)))
                .thenThrow(new RuntimeException("java.net.ConnectException: Connection refused"));

        SharedRequestPipeline pipeline = pipeline(api, ScriptMode.DISABLED);
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest request = new ApiRequest();
        request.name = "Fail";
        request.method = "GET";
        request.url = "https://api.example.test/fail";

        ExecutionResult result = pipeline.execute(request, collection, true);

        assertThat(result.success).isFalse();
        assertThat(result.errorMessage).isEqualTo("Connection refused - service may be down or firewalled");
        assertThat(result.rawRequestBytes).isNotNull();
    }

    @Test
    void executeTreatsRemote5xxAsResponseNotTransportFailure() {
        AtomicInteger sendCount = new AtomicInteger();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(
                sendCount,
                new CopyOnWriteArrayList<>(),
                () -> RunnerScriptTestFixtures.mockResponse(503, "{\"error\":\"busy\"}", "application/json")
        );
        SharedRequestPipeline pipeline = pipeline(api, ScriptMode.DISABLED);

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest request = new ApiRequest();
        request.name = "Busy";
        request.method = "GET";
        request.url = "https://api.example.test/busy";

        ExecutionResult result = pipeline.execute(request, collection, true);

        assertThat(sendCount).hasValue(1);
        assertThat(result.success).isTrue();
        assertThat(result.errorMessage).isNull();
        assertThat(result.response.response().statusCode()).isEqualTo((short) 503);
    }

    @Test
    void executeMarksMissingResponseAsFailure() {
        HttpRequestResponse emptyResponse = mock(HttpRequestResponse.class);
        MontoyaApi api = mock(MontoyaApi.class, RETURNS_DEEP_STUBS);
        when(api.http().sendRequest(any(burp.api.montoya.http.message.requests.HttpRequest.class), any(burp.api.montoya.http.RequestOptions.class)))
                .thenReturn(emptyResponse);
        when(api.http().sendRequest(any(burp.api.montoya.http.message.requests.HttpRequest.class)))
                .thenReturn(emptyResponse);

        SharedRequestPipeline pipeline = pipeline(api, ScriptMode.DISABLED);
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest request = new ApiRequest();
        request.name = "Missing";
        request.method = "GET";
        request.url = "https://api.example.test/missing";

        ExecutionResult result = pipeline.execute(request, collection, true);

        assertThat(result.success).isFalse();
        assertThat(result.errorMessage).isEqualTo("No response received");
    }

    private static ApiRequest scriptedRequest(String url, String preScriptSource, String postScriptSource) {
        ApiRequest request = new ApiRequest();
        request.id = "req-scripted";
        request.name = "Scripted";
        request.method = "POST";
        request.url = url;
        request.headers = new ArrayList<>();
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"ok\":true}";
        request.body.contentType = "application/json";
        request.scriptBlocks = new ArrayList<>();
        if (preScriptSource != null && !preScriptSource.isBlank()) {
            request.scriptBlocks.add(scriptBlock("pre", ScriptDialect.POSTMAN, ScriptPhase.PRE_REQUEST, preScriptSource));
        }
        if (postScriptSource != null && !postScriptSource.isBlank()) {
            request.scriptBlocks.add(scriptBlock("post", ScriptDialect.POSTMAN, ScriptPhase.POST_RESPONSE, postScriptSource));
        }
        return request;
    }


    private static SharedRequestPipeline pipeline(MontoyaApi api, ScriptMode scriptMode) {
        return SharedRequestPipeline.withRequestOptionsFactory(
                api,
                new RequestBuilder(null),
                new ScriptEngine(null, scriptMode),
                null,
                null,
                timeout -> {
                    RequestOptions options = mock(RequestOptions.class);
                    when(options.withRedirectionMode(any())).thenReturn(options);
                    when(options.withResponseTimeout(anyInt())).thenReturn(options);
                    return options;
                }
        );
    }

    private static ScriptBlock scriptBlock(String id, ScriptDialect dialect, ScriptPhase phase, String source) {
        ScriptBlock block = new ScriptBlock();
        block.id = id;
        block.dialect = dialect;
        block.phase = phase;
        block.scope = ScriptScope.REQUEST;
        block.source = source;
        block.enabled = true;
        return block;
    }
}
