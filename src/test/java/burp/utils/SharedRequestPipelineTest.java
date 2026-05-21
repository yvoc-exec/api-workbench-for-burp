package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SharedRequestPipelineTest {

    @Test
    void buildAppliesPreRequestScriptMutationsToRawRequest() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        ScriptEngine scriptEngine = new ScriptEngine(null, ScriptMode.DISABLED) {
            @Override
            public void executePreRequest(ApiRequest request, burp.parser.VariableResolver resolver, java.util.Map<String, String> context) {
                resolver.addCustomVariable("token", "123");
                context.put("token", "123");
            }
        };
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), scriptEngine, null);

        ApiCollection col = new ApiCollection();
        col.name = "Collection";

        ApiRequest req = new ApiRequest();
        req.name = "Request";
        req.method = "GET";
        req.url = "http://example.com/{{token}}";
        ApiRequest.Script script = new ApiRequest.Script("js", "pm.collectionVariables.set('token', '123');");
        req.preRequestScripts.add(script);

        ExecutionResult exec = pipeline.build(req, col);

        assertThat(exec.errorMessage).as(exec.errorMessage).isNull();
        assertThat(exec.success).isTrue();
        assertThat(exec.builtRequest).isNull();
        assertThat(exec.rawRequestBytes).isNotNull();
        assertThat(exec.requestHeaders).contains("/123");
        assertThat(exec.resolvedUrl).isEqualTo("http://example.com/123");
        assertThat(exec.resolvedVariables).containsEntry("token", "123");
        assertThat(col.runtimeVars).containsEntry("token", "123");
    }

    @Test
    void buildDoesNotOverwriteRuntimeVarsAddedDuringExecution() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        ApiCollection col = new ApiCollection();
        col.name = "Collection";
        col.runtimeVars.put("existing", "old");

        ScriptEngine scriptEngine = new ScriptEngine(null, ScriptMode.DISABLED) {
            @Override
            public void executePreRequest(ApiRequest request, burp.parser.VariableResolver resolver, java.util.Map<String, String> context) {
                col.putRuntimeVar("external", "keep");
                context.put("scripted", "value");
                resolver.addCustomVariable("scripted", "value");
            }
        };
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), scriptEngine, null);

        ApiRequest req = new ApiRequest();
        req.name = "Request";
        req.method = "GET";
        req.url = "http://example.com/{{scripted}}";
        req.preRequestScripts.add(new ApiRequest.Script("js", "pm.collectionVariables.set('scripted', 'value');"));

        ExecutionResult exec = pipeline.build(req, col);

        assertThat(exec.success).isTrue();
        assertThat(col.runtimeVars)
                .containsEntry("existing", "old")
                .containsEntry("external", "keep")
                .containsEntry("scripted", "value");
    }

    @Test
    void preRequestScriptCanReadCollectionVariablesThroughPostmanApi() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        ScriptEngine scriptEngine = new ScriptEngine(null, ScriptMode.DISABLED) {
            @Override
            public void executePreRequest(ApiRequest request, burp.parser.VariableResolver resolver, java.util.Map<String, String> context) {
                ScriptEngine.PostmanApi pm = new ScriptEngine.PostmanApi(resolver, context, null);
                String token = pm.collectionVariables.get("collection_token");
                pm.environment.set("header_token", token);
            }
        };
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), scriptEngine, null);

        ApiCollection col = new ApiCollection();
        col.name = "Collection";
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = "collection_token";
        variable.value = "abc123";
        col.variables.add(variable);

        ApiRequest req = new ApiRequest();
        req.name = "Request";
        req.method = "GET";
        req.url = "http://example.com/{{header_token}}";

        ExecutionResult exec = pipeline.build(req, col);

        assertThat(exec.success).isTrue();
        assertThat(exec.requestHeaders).contains("/abc123");
        assertThat(col.runtimeVars).containsEntry("header_token", "abc123");
    }

    @Test
    void sharedPipelineInjectsOauth2TokenBeforeRequestBuilderBuildsAuthorization() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        burp.auth.OAuth2Manager manager = org.mockito.Mockito.mock(burp.auth.OAuth2Manager.class);
        burp.auth.TokenStore.TokenEntry entry = new burp.auth.TokenStore.TokenEntry();
        entry.accessToken = "pipeline-token";
        entry.expiresAt = System.currentTimeMillis() + 60_000;
        org.mockito.Mockito.when(manager.getValidToken(org.mockito.Mockito.any())).thenReturn(entry);

        SharedRequestPipeline pipeline = new SharedRequestPipeline(
                api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), manager);

        ApiCollection col = new ApiCollection();
        col.name = "OAuth Collection";
        col.runtimeOAuth2.put("oauth2_token_url", "https://auth.example.test/token");
        col.runtimeOAuth2.put("oauth2_client_id", "client");
        col.runtimeOAuth2.put("oauth2_client_secret", "secret");
        col.runtimeOAuth2.put("oauth2_grant", "client_credentials");

        ApiRequest req = new ApiRequest();
        req.name = "OAuth Request";
        req.method = "GET";
        req.url = "http://example.com/api";
        req.auth = new ApiRequest.Auth();
        req.auth.type = "oauth2";
        req.auth.properties.put("accessToken", "{{oauth2_access_token}}");

        ExecutionResult exec = pipeline.build(req, col);

        assertThat(exec.success).isTrue();
        assertThat(exec.requestHeaders).contains("Authorization: Bearer pipeline-token");
        assertThat(col.runtimeOAuth2).containsEntry("oauth2_access_token", "pipeline-token");
    }

    @Test
    void buildPreservesMultipartFileBytes() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);

        Path tempFile = Files.createTempFile(Path.of("target"), "pipeline-binary-", ".bin");
        byte[] fileBytes = new byte[] {0x00, (byte) 0xff, (byte) 0xfe, 0x41};
        Files.write(tempFile, fileBytes);
        tempFile.toFile().deleteOnExit();

        ApiCollection col = new ApiCollection();
        col.name = "Collection";

        ApiRequest req = new ApiRequest();
        req.name = "Upload";
        req.method = "POST";
        req.url = "http://example.com/upload";
        req.body = new ApiRequest.Body();
        req.body.mode = "formdata";
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField("file", "");
        field.fileUpload = true;
        field.filePath = tempFile.toString();
        req.body.formdata.add(field);

        ExecutionResult exec = pipeline.build(req, col);

        assertThat(exec.success).isTrue();
        assertThat(exec.builtRequest).isNull();
        assertThat(exec.rawRequestBytes).isNotNull();
        assertThat(containsSubArray(exec.rawRequestBytes, fileBytes)).isTrue();
    }

    @Test
    void buildSplitsHeadersAndUrlEncodedBody() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);

        ApiCollection col = new ApiCollection();
        col.name = "Collection";

        ApiRequest req = new ApiRequest();
        req.name = "Submit";
        req.method = "POST";
        req.url = "http://example.com/form";
        req.body = new ApiRequest.Body();
        req.body.mode = "urlencoded";
        req.body.urlencoded.add(new ApiRequest.Body.FormField("a", "1"));
        req.body.urlencoded.add(new ApiRequest.Body.FormField("b", "2"));

        ExecutionResult exec = pipeline.build(req, col);

        assertThat(exec.success).isTrue();
        assertThat(exec.requestHeaders).contains("POST /form HTTP/1.1");
        assertThat(exec.requestHeaders).contains("Content-Type: application/x-www-form-urlencoded");
        assertThat(exec.requestHeaders).doesNotContain("a=1&b=2");
        assertThat(exec.requestBody).isEqualTo("a=1&b=2");
    }

    private static boolean containsSubArray(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
