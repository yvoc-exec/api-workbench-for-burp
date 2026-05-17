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
