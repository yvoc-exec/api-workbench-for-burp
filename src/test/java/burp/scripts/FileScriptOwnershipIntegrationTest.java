package burp.scripts;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.BrunoParser;
import burp.parser.InsomniaParser;
import burp.parser.PostmanParser;
import burp.utils.RawRequestParser;
import burp.utils.RequestBuilder;
import burp.utils.ScriptMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

class FileScriptOwnershipIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void postmanFileImportSurvivesImportedScriptAndBuildsExactFileBytes() throws Exception {
        Path payload = payload("postman.bin");
        Path collectionFile = tempDir.resolve("postman.json");
        Files.writeString(collectionFile, """
                {
                  "info": {"name": "Postman file script"},
                  "event": [{"listen": "prerequest", "script": {"exec": [
                    "pm.request.headers.upsert('X-Imported-Script', 'postman');"
                  ]}}],
                  "item": [{"name": "Upload", "request": {
                    "method": "POST",
                    "url": "https://example.test/upload",
                    "body": {"mode": "file", "file": {"src": "%s"}}
                  }}]
                }
                """.formatted(jsonPath(payload)), StandardCharsets.UTF_8);

        assertImportedFileRoundTrip(new PostmanParser().parse(collectionFile.toFile()), payload, "postman");
    }

    @Test
    void brunoFileImportSurvivesImportedScriptAndBuildsExactFileBytes() throws Exception {
        Path payload = payload("bruno.bin");
        Path root = Files.createDirectory(tempDir.resolve("bruno"));
        Files.writeString(root.resolve("upload.bru"), """
                meta {
                  name: Upload
                  type: http
                  seq: 1
                }

                post {
                  url: https://example.test/upload
                  body: file
                }

                body:file {
                  file: @file(%s) @contentType(application/octet-stream)
                }

                script:pre-request {
                  req.headers.upsert('X-Imported-Script', 'bruno');
                }
                """.formatted(portablePath(payload)), StandardCharsets.UTF_8);

        assertImportedFileRoundTrip(new BrunoParser().parse(root.toFile()), payload, "bruno");
    }

    @Test
    void insomniaFileImportSurvivesImportedScriptAndBuildsExactFileBytes() throws Exception {
        Path payload = payload("insomnia.bin");
        Path export = tempDir.resolve("insomnia.json");
        Files.writeString(export, """
                {
                  "__type": "export",
                  "__export_format": "Insomnia v4",
                  "resources": [{
                    "_type": "request",
                    "_id": "req_file",
                    "name": "Upload",
                    "method": "POST",
                    "url": "https://example.test/upload",
                    "body": {"mimeType": "application/octet-stream", "fileName": "%s"},
                    "preRequestScript": "insomnia.request.headers.upsert('X-Imported-Script', 'insomnia');"
                  }]
                }
                """.formatted(jsonPath(payload)), StandardCharsets.UTF_8);

        assertImportedFileRoundTrip(new InsomniaParser().parse(export.toFile()), payload, "insomnia");
    }

    private void assertImportedFileRoundTrip(ApiCollection collection, Path payload, String dialect) throws Exception {
        ApiRequest request = collection.requests.get(0);
        assertThat(request.body.mode).isEqualTo("file");
        assertThat(Path.of(request.body.filePath).toRealPath()).isEqualTo(payload.toRealPath());
        assertThat(request.body.raw).isNull();

        ScriptExecutionResult result;
        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        try {
            result = runtime.executePreRequest(
                    collection, request, null, ExecutionSource.WORKBENCH_SEND, 1);
        } finally {
            runtime.close();
        }

        assertThat(result.success).isTrue();
        assertThat(Path.of(result.mutatedRequest.body.filePath).toRealPath()).isEqualTo(payload.toRealPath());
        assertThat(result.mutatedRequest.body.raw).isNull();
        assertThat(result.mutatedRequest.headers)
                .anySatisfy(header -> {
                    assertThat(header.key).isEqualToIgnoringCase("X-Imported-Script");
                    assertThat(header.value).isEqualTo(dialect);
                });

        byte[] expected = Files.readAllBytes(payload);
        byte[] sentBody = RawRequestParser.parse(
                new RequestBuilder(null).buildRequest(result.mutatedRequest, null)).body;
        assertThat(sentBody).containsExactly(expected);
        assertThat(sha256(sentBody)).isEqualTo(sha256(expected));
    }

    private Path payload(String name) throws Exception {
        Files.createDirectories(Path.of("target"));
        Path payload = Files.createTempFile(Path.of("target"), name + "-", ".bin").toAbsolutePath();
        Files.write(payload, new byte[]{0x00, (byte) 0xFF, 0x41, 0x42, 0x43});
        return payload;
    }

    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static String portablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
