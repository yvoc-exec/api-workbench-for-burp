package burp.scripts;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.ScriptMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptBindingsBodyMutationTest {
    @Test
    void loggingScriptPreservesMultipartFields() {
        ApiRequest request = requestWithMultipart();
        ScriptExecutionResult result = run(request, "console.log('keep');");
        assertThat(result.mutatedRequest.body.formdata).hasSize(2);
        assertThat(result.mutatedRequest.body.formdata.get(0).key).isEqualTo("a");
    }

    @Test
    void loggingScriptPreservesUrlencodedFields() {
        ApiRequest request = requestWithUrlEncoded();
        ScriptExecutionResult result = run(request, "console.log('keep');");
        assertThat(result.mutatedRequest.body.urlencoded).extracting(f -> f.key).containsExactly("a", "a");
    }

    @Test
    void multipartDuplicateKeysAndOrderArePreserved() {
        ScriptExecutionResult result = run(requestWithMultipart(), "console.log('keep');");
        assertThat(result.mutatedRequest.body.formdata).extracting(f -> f.key).containsExactly("a", "a");
        assertThat(result.mutatedRequest.body.formdata).extracting(f -> f.value).containsExactly("1", "2");
    }

    @Test
    void fileUploadMetadataIsPreserved() {
        ScriptExecutionResult result = run(requestWithMultipart(), "console.log('keep');");
        ApiRequest.Body.FormField file = result.mutatedRequest.body.formdata.get(1);
        assertThat(file.fileUpload).isTrue();
        assertThat(file.type).isEqualTo("file");
        assertThat(file.filePath).isEqualTo("upload.txt");
        assertThat(file.disabled).isTrue();
    }

    @Test
    void scriptCanDeliberatelyReplaceBodyWithoutAliasing() {
        ApiRequest request = requestWithMultipart();
        ScriptExecutionResult result = run(request, "awb.request.body.mode='raw'; awb.request.body.raw='changed'; awb.request.body.formdata = [];");
        assertThat(result.mutatedRequest.body.mode).isEqualTo("raw");
        assertThat(result.mutatedRequest.body.raw).isEqualTo("changed");
        assertThat(result.mutatedRequest.body.formdata).isEmpty();
        assertThat(request.body.formdata).hasSize(2);
    }

    private ScriptExecutionResult run(ApiRequest request, String source) {
        ApiCollection collection = new ApiCollection();
        collection.scriptBlocks = new ArrayList<>();
        collection.scriptBlocks.add(ScriptBlock.of(source, ScriptDialect.API_WORKBENCH, ScriptPhase.PRE_REQUEST, ScriptScope.COLLECTION));
        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        return runtime.executePreRequest(collection, request, null, ExecutionSource.WORKBENCH_SEND, 1);
    }

    private ApiRequest requestWithMultipart() {
        ApiRequest request = baseRequest();
        request.body = new ApiRequest.Body();
        request.body.mode = "formdata";
        ApiRequest.Body.FormField one = new ApiRequest.Body.FormField("a", "1");
        one.type = "text";
        ApiRequest.Body.FormField two = new ApiRequest.Body.FormField("a", "2");
        two.type = "file";
        two.fileUpload = true;
        two.filePath = "upload.txt";
        two.disabled = true;
        request.body.formdata.add(one);
        request.body.formdata.add(two);
        return request;
    }

    private ApiRequest requestWithUrlEncoded() {
        ApiRequest request = baseRequest();
        request.body = new ApiRequest.Body();
        request.body.mode = "urlencoded";
        request.body.urlencoded.add(new ApiRequest.Body.FormField("a", "1"));
        request.body.urlencoded.add(new ApiRequest.Body.FormField("a", "2"));
        return request;
    }

    private ApiRequest baseRequest() {
        ApiRequest request = new ApiRequest();
        request.method = "POST";
        request.url = "https://example.test";
        return request;
    }
}
