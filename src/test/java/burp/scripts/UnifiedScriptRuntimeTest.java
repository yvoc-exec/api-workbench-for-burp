package burp.scripts;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerResult;
import burp.utils.ScriptMode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedScriptRuntimeTest {

    @Test
    void resolveBlocksReturnsCollectionFolderRequestAndLegacyScriptsInOrder() {
        ApiCollection collection = collection();
        ApiRequest request = request();

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        List<ScriptBlock> blocks = runtime.resolveBlocks(collection, request, ScriptPhase.PRE_REQUEST);

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).id).isEqualTo("collection-pre");
        assertThat(blocks.get(1).id).isEqualTo("folder-pre");
        assertThat(blocks.get(2).id).isEqualTo("request-pre");
    }

    @Test
    void requestLevelScriptBlocksDoNotExecuteLegacyDuplicates() {
        ApiCollection collection = collection();
        ApiRequest request = request();
        EnvironmentProfile activeEnvironment = new EnvironmentProfile();
        activeEnvironment.name = "Dev";
        activeEnvironment.variables.put("base_url", "https://api.example.test");

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        ScriptExecutionResult result = runtime.executePreRequest(collection, request, activeEnvironment, "Send", 1);

        assertThat(result.success).isTrue();
        assertThat(result.logs).hasSize(1);
        assertThat(result.logs.get(0).message).contains("collection pre");
        assertThat(result.effectiveVariables).containsEntry("token", "collection-token");
        assertThat(result.effectiveVariables).containsEntry("bruno_scope", "folder-value");
        assertThat(result.effectiveVariables).containsEntry("collection_value", "collection-value");
        assertThat(activeEnvironment.variables).containsEntry("base_url", "https://api.example.test");
        assertThat(activeEnvironment.variables).doesNotContainKey("token");
        assertThat(collection.runtimeVars).isEmpty();
    }

    @Test
    void executePreRequestAppliesMultiDialectBindingsAndRequestMutation() {
        ApiCollection collection = collection();
        ApiRequest request = request();
        EnvironmentProfile activeEnvironment = new EnvironmentProfile();
        activeEnvironment.name = "Dev";
        activeEnvironment.variables.put("base_url", "https://api.example.test");

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        ScriptExecutionResult result = runtime.executePreRequest(collection, request, activeEnvironment, "Send", 1);

        assertThat(result.success).isTrue();
        assertThat(result.mutatedRequest).isNotNull();
        assertThat(result.mutatedRequest.url).isEqualTo("https://api.example.test/login?token=collection-token");
        assertThat(result.mutatedRequest.headers).anySatisfy(header -> {
            assertThat(header.key).isEqualToIgnoringCase("Authorization");
            assertThat(header.value).isEqualTo("Bearer collection-token");
        });
        assertThat(result.effectiveVariables).containsEntry("token", "collection-token");
        assertThat(result.effectiveVariables).containsEntry("bruno_scope", "folder-value");
        assertThat(result.effectiveVariables).containsEntry("collection_value", "collection-value");
        assertThat(activeEnvironment.variables).containsEntry("base_url", "https://api.example.test");
        assertThat(activeEnvironment.variables).doesNotContainKey("token");
        assertThat(collection.runtimeVars).isEmpty();
        assertThat(result.logs).hasSize(1);
        assertThat(result.logs.get(0).message).contains("collection pre");
    }

    @Test
    void executePostResponseCapturesAssertionsLogsAndNativeEnvironmentWrites() {
        ApiCollection collection = collection();
        ApiRequest request = request();
        EnvironmentProfile activeEnvironment = new EnvironmentProfile();
        activeEnvironment.name = "Dev";
        activeEnvironment.variables.put("base_url", "https://api.example.test");

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        RunnerResult runnerResult = new RunnerResult();
        ScriptExecutionResult result = runtime.executePostResponse(
                collection,
                request,
                activeEnvironment,
                "Runner",
                1,
                "{\"token\":\"resp-123\"}",
                201,
                Map.of("content-type", List.of("application/json")),
                42L,
                runnerResult);

        assertThat(result.success).isTrue();
        assertThat(result.assertions).hasSize(1);
        assertThat(result.assertions.get(0).passed).isTrue();
        assertThat(result.logs).hasSize(1);
        assertThat(result.logs.get(0).message).contains("post-response");
        assertThat(result.effectiveVariables).containsEntry("response_token", "resp-123");
        assertThat(result.effectiveVariables).containsEntry("native_token", "resp-123");
        assertThat(activeEnvironment.variables).containsEntry("base_url", "https://api.example.test");
        assertThat(activeEnvironment.variables).doesNotContainKey("response_token");
    }

    @Test
    void postmanScriptCannotAccessBrunoBindings() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.format = "api-workbench";

        ApiRequest request = new ApiRequest();
        request.id = "req-cross";
        request.name = "Cross";
        request.sourceCollection = "APIM";
        request.method = "GET";
        request.url = "https://example.test";
        request.scriptBlocks = new ArrayList<>();
        request.scriptBlocks.add(scriptBlock(
                "request-pre",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST,
                "pm.environment.set('ok', 'yes'); bru.setEnvVar('cross', 'no');",
                1));

        EnvironmentProfile activeEnvironment = new EnvironmentProfile();
        activeEnvironment.name = "Dev";

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        ScriptExecutionResult result = runtime.executePreRequest(collection, request, activeEnvironment, "Send", 1);

        assertThat(result.success).isFalse();
        assertThat(result.errors).isNotEmpty();
        assertThat(result.errors.get(0)).contains("bru");
        assertThat(result.effectiveVariables).doesNotContainKey("ok");
        assertThat(activeEnvironment.variables).doesNotContainKey("ok");
        assertThat(activeEnvironment.variables).doesNotContainKey("cross");
    }

    @Test
    void legacyOnlyRequestScriptsStillExecuteOnce() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.format = "api-workbench";

        ApiRequest request = new ApiRequest();
        request.id = "req-legacy";
        request.name = "Legacy";
        request.sourceCollection = "APIM";
        request.method = "GET";
        request.url = "https://example.test";
        request.preRequestScripts = new ArrayList<>();
        request.preRequestScripts.add(new ApiRequest.Script("js", "console.log('legacy only'); pm.environment.set('legacy_count', (pm.environment.get('legacy_count') || '') + '1');"));

        EnvironmentProfile activeEnvironment = new EnvironmentProfile();
        activeEnvironment.name = "Dev";

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        ScriptExecutionResult result = runtime.executePreRequest(collection, request, activeEnvironment, "Send", 1);

        assertThat(result.success).isTrue();
        assertThat(result.logs).hasSize(1);
        assertThat(result.logs.get(0).message).contains("legacy only");
        assertThat(result.effectiveVariables).containsEntry("legacy_count", "1");
        assertThat(activeEnvironment.variables).doesNotContainKey("legacy_count");
    }

    @Test
    void disabledCanonicalPreRequestBlockSuppressesLegacyFallback() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        ApiRequest request = new ApiRequest();
        request.id = "req-disabled-native";
        request.name = "Disabled Native";
        request.sourceCollection = "APIM";
        request.method = "GET";
        request.url = "https://example.test";
        request.preRequestScripts = new ArrayList<>(List.of(
                new ApiRequest.Script("js", "console.log('legacy fallback');")
        ));
        request.scriptBlocks = new ArrayList<>(List.of(scriptBlock(
                "request-pre-disabled",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST,
                "console.log('native');",
                1)));
        request.scriptBlocks.get(0).enabled = false;

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        ScriptExecutionResult result = runtime.executePreRequest(collection, request, null, "Send", 1);

        assertThat(result.success).isTrue();
        assertThat(result.logs).isEmpty();
        assertThat(result.errors).isEmpty();
    }

    @Test
    void scriptMutationInvalidatesExactSnapshot() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        ApiRequest request = new ApiRequest();
        request.id = "req-exact";
        request.name = "Exact";
        request.sourceCollection = "APIM";
        request.method = "GET";
        request.url = "https://example.test";
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.exactHttpRequest = new burp.models.ExactHttpRequestSnapshot();
        request.exactHttpRequest.rawRequestBytes = "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        request.exactHttpRequest.pristine = true;
        request.exactHttpRequest.semanticFingerprint = request.computeSemanticFingerprint();
        request.scriptBlocks = new ArrayList<>(List.of(scriptBlock(
                "request-pre",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST,
                "pm.request.url = 'https://example.test/changed';",
                1)));

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        ScriptExecutionResult result = runtime.executePreRequest(collection, request, null, "Send", 1);

        assertThat(result.mutatedRequest.exactHttpRequest).isNotNull();
        assertThat(result.mutatedRequest.exactHttpRequest.pristine).isFalse();
        assertThat(result.mutatedRequest.exactHttpRequest.invalidationReason).isEqualTo("SCRIPT_MUTATION");
    }

    @Test
    void consoleErrorIsCapturedAsErrorLevelLogAndScriptError() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.format = "api-workbench";

        ApiRequest request = new ApiRequest();
        request.id = "req-console-error";
        request.name = "Console Error";
        request.sourceCollection = "APIM";
        request.method = "GET";
        request.url = "https://example.test";
        request.scriptBlocks = new ArrayList<>();
        request.scriptBlocks.add(scriptBlock(
                "request-pre",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST,
                "console.error('bad thing');",
                1));

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        ScriptExecutionResult result = runtime.executePreRequest(collection, request, null, "Send", 1);

        assertThat(result.success).isFalse();
        assertThat(result.errors).contains("bad thing");
        assertThat(result.logs).hasSize(1);
        assertThat(result.logs.get(0).level).isEqualTo("error");
        assertThat(result.logs.get(0).message).contains("bad thing");
        assertThat(result.engineName).isIn("Sandboxed JavaScript", "Legacy JavaScript fallback", "Unavailable");
    }

    @Test
    void brunoRunRequestProducesVisibleWarningWithoutFailing() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.format = "api-workbench";

        ApiRequest request = new ApiRequest();
        request.id = "req-bruno-run";
        request.name = "Bruno Run";
        request.sourceCollection = "APIM";
        request.method = "GET";
        request.url = "https://example.test";
        request.scriptBlocks = new ArrayList<>();
        request.scriptBlocks.add(scriptBlock(
                "request-pre",
                ScriptDialect.BRUNO,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST,
                "bru.runRequest('Child');",
                1));

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        ScriptExecutionResult result = runtime.executePreRequest(collection, request, null, "Runner", 1);

        assertThat(result.success).isTrue();
        assertThat(result.warnings).contains("runRequest is recognized but no dependent executor is available.");
        assertThat(result.flowControl).isEqualTo(ScriptFlowControl.CONTINUE);
        assertThat(result.message).isNull();
    }

    @Test
    void nativeSendAdHocRequestProducesVisibleWarningWithoutFailing() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.format = "api-workbench";

        ApiRequest request = new ApiRequest();
        request.id = "req-native-send";
        request.name = "Native Send";
        request.sourceCollection = "APIM";
        request.method = "GET";
        request.url = "https://example.test";
        request.scriptBlocks = new ArrayList<>();
        request.scriptBlocks.add(scriptBlock(
                "request-pre",
                ScriptDialect.API_WORKBENCH,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST,
                "awb.execution.sendAdHocRequest('Child');",
                1));

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        ScriptExecutionResult result = runtime.executePreRequest(collection, request, null, "Runner", 1);

        assertThat(result.success).isTrue();
        assertThat(result.warnings).contains("sendAdHocRequest is recognized but no dependent executor is available.");
        assertThat(result.flowControl).isEqualTo(ScriptFlowControl.CONTINUE);
        assertThat(result.message).isNull();
    }

    @Test
    void scriptSandboxEngineExecutesSimpleScriptAndBlocksHostClassAccess() throws Exception {
        SandboxedJavaScriptEngine engine = new SandboxedJavaScriptEngine();

        assertThat(engine.isRuntimeAvailable()).isTrue();
        assertThat(engine.isLegacyFallbackAvailable()).isFalse();
        assertThat(engine.getEngineName()).isEqualTo("Sandboxed JavaScript");
        assertThat(engine.getInitializationFailure()).isNull();
        Object value = engine.execute("1 + 1", Map.of());
        assertThat(value).isNotNull();
        assertThat(((Number) value).intValue()).isEqualTo(2);

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> engine.execute("Java.type('java.lang.System');", Map.of()));
    }

    private static ApiCollection collection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.format = "api-workbench";
        collection.folderPaths = new ArrayList<>(List.of("Auth"));
        collection.scriptBlocks = new ArrayList<>();
        collection.scriptBlocks.add(scriptBlock(
                "collection-pre",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.COLLECTION,
                "pm.environment.set('token', 'collection-token'); console.log('collection pre');",
                1));
        collection.folderScriptBlocks = new LinkedHashMap<>();
        collection.folderScriptBlocks.put("Auth", new ArrayList<>(List.of(
                scriptBlock(
                        "folder-pre",
                        ScriptDialect.BRUNO,
                        ScriptPhase.PRE_REQUEST,
                        ScriptScope.FOLDER,
                        "bru.setEnvVar('bruno_scope', 'folder-value', { persist: true });",
                        2)
        )));
        collection.requests = new ArrayList<>();
        collection.requests.add(request());
        return collection;
    }

    private static ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.id = "req-login";
        request.name = "Login";
        request.path = "Auth";
        request.sourceCollection = "APIM";
        request.method = "POST";
        request.url = "{{base_url}}/login";
        request.headers = new ArrayList<>();
        request.headers.add(new ApiRequest.Header("X-Test", "workflow"));
        request.preRequestScripts = new ArrayList<>();
        request.preRequestScripts.add(new ApiRequest.Script("js", "// legacy pre\nconsole.log('legacy pre');"));
        request.postResponseScripts = new ArrayList<>();
        request.postResponseScripts.add(new ApiRequest.Script("js", "console.log('legacy post');"));
        request.scriptBlocks = new ArrayList<>();
        request.scriptBlocks.add(scriptBlock(
                "request-pre",
                ScriptDialect.INSOMNIA,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST,
                "insomnia.collectionVariables.set('collection_value', 'collection-value'); insomnia.request.headers.upsert('Authorization', 'Bearer ' + insomnia.environment.get('token')); insomnia.request.url = insomnia.request.url.replace('{{base_url}}', 'https://api.example.test') + '?token=' + insomnia.environment.get('token');",
                3));
        request.scriptBlocks.add(scriptBlock(
                "request-post",
                ScriptDialect.API_WORKBENCH,
                ScriptPhase.POST_RESPONSE,
                ScriptScope.REQUEST,
                "awb.test('status is 201', function () { awb.expect(awb.response.code).to.equal(201); }); awb.environment.set('response_token', awb.response.json().get('token')); awb.environment.set('native_token', awb.response.json().get('token')); console.log('post-response');",
                4));
        return request;
    }

    private static ScriptBlock scriptBlock(String id,
                                           ScriptDialect dialect,
                                           ScriptPhase phase,
                                           ScriptScope scope,
                                           String source,
                                           int order) {
        ScriptBlock block = new ScriptBlock();
        block.id = id;
        block.dialect = dialect;
        block.phase = phase;
        block.scope = scope;
        block.source = source;
        block.order = order;
        block.enabled = true;
        block.sourceFormat = "api-workbench";
        block.metadata.put("type", "js");
        return block;
    }
}
