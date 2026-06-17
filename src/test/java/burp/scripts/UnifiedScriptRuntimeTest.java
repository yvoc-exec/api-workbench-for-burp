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

        assertThat(blocks).hasSize(4);
        assertThat(blocks.get(0).id).isEqualTo("collection-pre");
        assertThat(blocks.get(1).id).isEqualTo("folder-pre");
        assertThat(blocks.get(2).id).isEqualTo("request-pre");
        assertThat(blocks.get(3).dialect).isEqualTo(ScriptDialect.LEGACY_NASHORN);
        assertThat(blocks.get(3).source).contains("legacy pre");
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
        assertThat(activeEnvironment.variables).containsEntry("token", "collection-token");
        assertThat(activeEnvironment.variables).containsEntry("bruno_scope", "folder-value");
        assertThat(collection.runtimeVars).containsEntry("collection_value", "collection-value");
        assertThat(result.logs).anySatisfy(log -> assertThat(log.message).contains("collection pre"));
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
        assertThat(result.logs).anySatisfy(log -> assertThat(log.message).contains("post-response"));
        assertThat(activeEnvironment.variables).containsEntry("response_token", "resp-123");
        assertThat(activeEnvironment.variables).containsEntry("native_token", "resp-123");
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
                "insomnia.collectionVariables.set('collection_value', 'collection-value'); pm.request.headers.upsert('Authorization', 'Bearer ' + pm.environment.get('token')); pm.request.url = pm.request.url.replace('{{base_url}}', 'https://api.example.test') + '?token=' + pm.environment.get('token');",
                3));
        request.scriptBlocks.add(scriptBlock(
                "request-post",
                ScriptDialect.API_WORKBENCH,
                ScriptPhase.POST_RESPONSE,
                ScriptScope.REQUEST,
                "pm.test('status is 201', function () { pm.expect(pm.response.code).to.equal(201); }); awb.environment.set('response_token', awb.response.json().get('token')); awb.environment.set('native_token', awb.response.json().get('token')); console.log('post-response');",
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
