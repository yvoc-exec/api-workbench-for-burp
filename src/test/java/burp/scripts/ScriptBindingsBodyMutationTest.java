package burp.scripts;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.payload.ManagedPayloadRef;
import burp.payload.PayloadSliceRef;
import burp.utils.RawRequestParser;
import burp.utils.RequestBuilder;
import burp.utils.ScriptMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptBindingsBodyMutationTest {
    @Test
    void canonicalFilePathSurvivesNoOpScript() {
        ApiRequest request = requestWithFileBody("payload.bin", null);

        ScriptExecutionResult result = run(request, "console.log('keep');");

        assertThat(result.success).isTrue();
        assertThat(result.mutatedRequest.body.filePath).isEqualTo("payload.bin");
        assertThat(result.mutatedRequest.body.raw).isNull();
    }

    @Test
    void headerOnlyScriptPreservesCanonicalFileAndRequestBuilderSendsReferencedBytes() throws Exception {
        byte[] payload = new byte[]{0x00, (byte) 0xFF, 0x41, 0x42};
        Path file = Files.createTempFile(Path.of("target"), "header-file-", ".bin");
        Files.write(file, payload);
        ApiRequest request = requestWithFileBody(file.toString(), null);

        ScriptExecutionResult result = run(request, "awb.request.headers.upsert('X-Test', 'one');");
        byte[] built = new RequestBuilder(null).buildRequest(result.mutatedRequest, null);

        assertThat(result.mutatedRequest.body.filePath).isEqualTo(file.toString());
        assertThat(result.mutatedRequest.body.raw).isNull();
        assertThat(RawRequestParser.parse(built).body).containsExactly(payload);
    }

    @Test
    void variableOnlyScriptPreservesCanonicalFilePath() {
        ApiRequest request = requestWithFileBody("variable.bin", null);

        ScriptExecutionResult result = run(request, "awb.variables.set('token', 'value');");

        assertThat(result.success).isTrue();
        assertThat(result.mutatedRequest.body.filePath).isEqualTo("variable.bin");
        assertThat(result.mutatedRequest.body.raw).isNull();
    }

    @Test
    void noOpAndHeaderOnlyScriptsPreserveManagedBodySlice() {
        ApiRequest request = requestWithManagedBody();

        ScriptExecutionResult noOp = run(request, "console.log('keep');");
        ScriptExecutionResult headerOnly = run(request,
                "awb.request.headers.upsert('X-Test', 'one');");

        assertThat(noOp.mutatedRequest.body.managedPayload).isEqualTo(request.body.managedPayload);
        assertThat(headerOnly.mutatedRequest.body.managedPayload).isEqualTo(request.body.managedPayload);
        assertThat(noOp.mutatedRequest.body.raw).isNull();
        assertThat(headerOnly.mutatedRequest.body.raw).isNull();
    }

    @Test
    void scriptBodyReplacementReleasesManagedBodySlice() {
        ApiRequest request = requestWithManagedBody();

        ScriptExecutionResult result = run(request, "awb.request.body.raw='replacement';");

        assertThat(result.mutatedRequest.body.raw).isEqualTo("replacement");
        assertThat(result.mutatedRequest.body.managedPayload).isNull();
        assertThat(request.body.managedPayload).isNotNull();
    }

    @Test
    void legacyRawFilePathStillBuildsAfterScript() throws Exception {
        byte[] payload = "legacy-file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file = Files.createTempFile(Path.of("target"), "legacy-file-", ".txt");
        Files.write(file, payload);
        ApiRequest request = requestWithFileBody(null, file.toString());

        ScriptExecutionResult result = run(request, "console.log('legacy');");
        byte[] built = new RequestBuilder(null).buildRequest(result.mutatedRequest, null);

        assertThat(result.mutatedRequest.body.filePath).isNull();
        assertThat(result.mutatedRequest.body.raw).isEqualTo(file.toString());
        assertThat(RawRequestParser.parse(built).body).containsExactly(payload);
    }
    @Test
    void loggingScriptPreservesMultipartFields() {
        ApiRequest request = requestWithMultipart();
        ScriptExecutionResult result = run(request, "console.log('keep');");
        assertThat(result.mutatedRequest.body.formdata).hasSize(2);
        assertThat(result.mutatedRequest.body.formdata.get(0).key).isEqualTo("a");
        assertCompleteFileMetadata(result.mutatedRequest);
    }

    @Test
    void headerOnlyMutationPreservesCompleteMultipartMetadata() {
        ScriptExecutionResult result = run(requestWithMultipart(),
                "awb.request.headers.upsert('X-Test', 'one');");

        assertThat(result.success).isTrue();
        assertCompleteFileMetadata(result.mutatedRequest);
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

    @Test
    void nonEmptyGuestFormdataReplacementIsCopiedBeforeContextClose() {
        ApiRequest request = requestWithMultipart();
        ScriptExecutionResult result = run(request, """
                awb.request.body.mode = 'formdata';
                awb.request.body.formdata = [
                    { key: 'a', value: '1', type: 'text', disabled: false },
                    { key: 'a', value: '2', type: 'file', fileUpload: true, filePath: 'upload.txt', disabled: true }
                ];
                """);

        assertThat(result.mutatedRequest.body.formdata).extracting(field -> field.key).containsExactly("a", "a");
        assertThat(result.mutatedRequest.body.formdata).extracting(field -> field.value).containsExactly("1", "2");
        assertThat(result.mutatedRequest.body.formdata.get(1).fileUpload).isTrue();
        assertThat(result.mutatedRequest.body.formdata.get(1).type).isEqualTo("file");
        assertThat(result.mutatedRequest.body.formdata.get(1).filePath).isEqualTo("upload.txt");
        assertThat(result.mutatedRequest.body.formdata.get(1).disabled).isTrue();
    }

    @Test
    void nonEmptyGuestUrlencodedReplacementIsCopiedBeforeContextClose() {
        ApiRequest request = requestWithUrlEncoded();
        ScriptExecutionResult result = run(request, """
                awb.request.body.mode = 'urlencoded';
                awb.request.body.urlencoded = [
                    { key: 'a', value: '1' },
                    { key: 'a', value: '2' }
                ];
                """);

        assertThat(result.mutatedRequest.body.urlencoded).extracting(field -> field.key).containsExactly("a", "a");
        assertThat(result.mutatedRequest.body.urlencoded).extracting(field -> field.value).containsExactly("1", "2");
    }

    @Test
    void scriptCanExplicitlyClearExistingBodyWithNull() {
        ApiRequest request = requestWithMultipart();
        ScriptExecutionResult result = run(request, "awb.request.body = null;");

        assertThat(result.success).isTrue();
        assertThat(result.mutatedRequest.body).isNull();
        assertThat(request.body).isNotNull();
    }

    @Test
    void scriptLeavingAbsentBodyUntouchedKeepsItAbsent() {
        ApiRequest request = baseRequest();
        ScriptExecutionResult result = run(request, "console.log('noop');");

        assertThat(result.success).isTrue();
        assertThat(result.mutatedRequest.body).isNull();
    }

    @Test
    void guestBodyConversionFailureRollsBackOriginalBody() {
        ApiRequest request = requestWithMultipart();
        ScriptExecutionResult result = run(request, """
                awb.request.body.mode = 'formdata';
                awb.request.body.formdata = [
                    {
                        get key() { throw new Error('boom'); },
                        value: '1'
                    }
                ];
                """);

        assertThat(result.success).isFalse();
        assertThat(result.errors).isNotEmpty();
        assertThat(result.mutatedRequest.body).isNotNull();
        assertThat(result.mutatedRequest.body.formdata).hasSize(2);
        assertThat(result.mutatedRequest.body.formdata).extracting(field -> field.key).containsExactly("a", "a");
        assertThat(request.body.formdata).hasSize(2);
        assertCompleteFileMetadata(result.mutatedRequest);
    }

    private ScriptExecutionResult run(ApiRequest request, String source) {
        ApiCollection collection = new ApiCollection();
        collection.scriptBlocks = new ArrayList<>();
        collection.scriptBlocks.add(ScriptBlock.of(source, ScriptDialect.API_WORKBENCH, ScriptPhase.PRE_REQUEST, ScriptScope.COLLECTION));
        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        try {
            return runtime.executePreRequest(collection, request, null, ExecutionSource.WORKBENCH_SEND, 1);
        } finally {
            runtime.close();
        }
    }

    private ApiRequest requestWithMultipart() {
        ApiRequest request = baseRequest();
        request.body = new ApiRequest.Body();
        request.body.mode = "formdata";
        request.body.contentType = "multipart/form-data";
        request.body.required = true;
        request.body.description = "upload body";
        request.body.filePath = "body-source.bin";
        request.body.source = "openapi";
        request.body.sourceMetadata.put("body-origin", "fixture");
        ApiRequest.Body.FormField one = new ApiRequest.Body.FormField("a", "1");
        one.type = "text";
        ApiRequest.Body.FormField two = new ApiRequest.Body.FormField("a", "2");
        two.type = "file";
        two.fileUpload = true;
        two.filePath = "upload.txt";
        two.disabled = true;
        two.required = true;
        two.description = "attachment";
        two.contentType = "text/plain";
        two.style = "form";
        two.explode = Boolean.TRUE;
        two.allowReserved = true;
        two.source = "openapi";
        two.sourceMetadata.put("field-origin", "fixture");
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

    private ApiRequest requestWithFileBody(String filePath, String legacyRawPath) {
        ApiRequest request = baseRequest();
        request.body = new ApiRequest.Body();
        request.body.mode = "file";
        request.body.filePath = filePath;
        request.body.raw = legacyRawPath;
        return request;
    }

    private ApiRequest requestWithManagedBody() {
        ApiRequest request = baseRequest();
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        ManagedPayloadRef payload = new ManagedPayloadRef("a".repeat(64), 32L, "a".repeat(64));
        request.body.managedPayload = new PayloadSliceRef(payload, 8L, 24L);
        return request;
    }

    private ApiRequest baseRequest() {
        ApiRequest request = new ApiRequest();
        request.method = "POST";
        request.url = "https://example.test";
        return request;
    }

    private static void assertCompleteFileMetadata(ApiRequest request) {
        assertThat(request.body.required).isTrue();
        assertThat(request.body.description).isEqualTo("upload body");
        assertThat(request.body.filePath).isEqualTo("body-source.bin");
        assertThat(request.body.source).isEqualTo("openapi");
        assertThat(request.body.sourceMetadata).containsEntry("body-origin", "fixture");
        ApiRequest.Body.FormField field = request.body.formdata.get(1);
        assertThat(field.filePath).isEqualTo("upload.txt");
        assertThat(field.required).isTrue();
        assertThat(field.description).isEqualTo("attachment");
        assertThat(field.contentType).isEqualTo("text/plain");
        assertThat(field.style).isEqualTo("form");
        assertThat(field.explode).isTrue();
        assertThat(field.allowReserved).isTrue();
        assertThat(field.source).isEqualTo("openapi");
        assertThat(field.sourceMetadata).containsEntry("field-origin", "fixture");
    }
}
