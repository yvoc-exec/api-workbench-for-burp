package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.parser.ParserRegistry;
import burp.parser.ApiWorkbenchCollectionParser;
import burp.utils.RequestBuilder;
import burp.utils.RequestPathResolver;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionExportRoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    void postmanRoundTripPreservesHierarchySlashLabelsDisabledHeadersAndScripts() throws Exception {
        ApiCollection collection = ExportTestFixtures.sampleCollection();
        collection.requests.get(0).headers.add(new ApiRequest.Header("X-Disabled", "no", true));
        Path output = tempDir.resolve("apim.postman_collection.json");

        new CollectionExportService().exportCollection(
                collection,
                new CollectionExportOptions(CollectionExportFormat.POSTMAN_JSON, output, false, null, Map.of())
        );

        ApiCollection imported = new ParserRegistry().detectParser(output.toFile()).parse(output.toFile());

        assertThat(imported.name).isEqualTo("APIM");
        assertThat(imported.requests).hasSize(collection.requests.size());
        ApiRequest login = requestById(imported, "req-login");
        assertThat(RequestPathResolver.getRequestFolderPath(imported, login)).isEqualTo("Auth");
        assertThat(login.headers).anySatisfy(header -> {
            assertThat(header.key).isEqualTo("X-Disabled");
            assertThat(header.value).isEqualTo("no");
            assertThat(header.disabled).isTrue();
        });
        assertThat(login.preRequestScripts).hasSize(1);
        assertThat(login.postResponseScripts).hasSize(1);
        ApiRequest usersRoot = requestById(imported, "req-users-root");
        assertThat(usersRoot.name).isEqualTo("GET /users");
        assertThat(usersRoot.url).isEqualTo("{{base_url}}/users?role={{role}}&page=1");
    }

    @Test
    void exportRejectsDirectoryDestinationWithoutLeavingFilesBehind() throws Exception {
        ApiCollection collection = ExportTestFixtures.sampleCollection();
        Path directoryTarget = Files.createDirectory(tempDir.resolve("collection-export-dir"));

        assertThatThrownBy(() -> new CollectionExportService().exportCollection(
                collection,
                new CollectionExportOptions(CollectionExportFormat.POSTMAN_JSON, directoryTarget, false, null, Map.of())
        ))
                .isInstanceOf(ExportException.class)
                .hasMessageContaining("Collection export failed");

        assertThat(directoryTarget).isDirectory();
        try (Stream<Path> children = Files.list(directoryTarget)) {
            assertThat(children).isEmpty();
        }
    }

    @Test
    void nativeRoundTripPreservesExactSnapshotWithoutAliasing() throws Exception {
        ApiCollection collection = ExportTestFixtures.sampleCollection();
        ApiRequest request = collection.requests.get(0);
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.exactHttpRequest = new ExactHttpRequestSnapshot();
        request.exactHttpRequest.rawRequestBytes = "GET /users HTTP/1.1\r\nHost: api.example.test\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        request.exactHttpRequest.serviceHost = "api.example.test";
        request.exactHttpRequest.servicePort = 443;
        request.exactHttpRequest.secure = true;
        request.exactHttpRequest.httpVersion = "HTTP/1.0";
        request.exactHttpRequest.pristine = true;
        request.exactHttpRequest.semanticFingerprint = request.computeSemanticFingerprint();
        Path output = tempDir.resolve("apim.api-workbench.json");

        new CollectionExportService().exportCollection(
                collection,
                new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON, output, false, null, Map.of())
        );

        ApiCollection imported = new ParserRegistry().detectParser(output.toFile()).parse(output.toFile());
        ApiRequest restored = requestById(imported, request.id);

        assertThat(restored.exactHttpRequest).isNotNull();
        assertThat(restored.exactHttpRequest.httpVersion).isEqualTo("HTTP/1.0");
        assertThat(restored.exactHttpRequest.rawRequestBytes).isEqualTo(request.exactHttpRequest.rawRequestBytes);
        restored.exactHttpRequest.rawRequestBytes[0] = 'X';
        assertThat(request.exactHttpRequest.rawRequestBytes[0]).isEqualTo((byte) 'G');
    }

    @Test
    void nativeImportMigratesPreWave4ExactFingerprint() throws Exception {
        byte[] trusted = "GET /legacy HTTP/1.0\r\nHost: example.test\r\n\r\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ApiRequest request = legacyExactRequest(trusted);
        ApiCollection collection = new ApiCollection();
        collection.name = "Legacy";
        collection.requests.add(request);
        Path output = tempDir.resolve("legacy.api-workbench.json");
        new CollectionExportService().exportCollection(collection,
                new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON,
                        output, false, null, Map.of()));

        JsonObject serialized = com.google.gson.JsonParser.parseString(Files.readString(output))
                .getAsJsonObject().getAsJsonObject("collection").getAsJsonArray("requests")
                .get(0).getAsJsonObject().getAsJsonObject("exactHttpRequest");
        assertThat(serialized.has("httpVersion")).isFalse();

        ApiRequest restored = new ApiWorkbenchCollectionParser().parse(output.toFile()).requests.get(0);
        assertThat(restored.exactHttpRequest.rawRequestBytes).isEqualTo(trusted);
        assertThat(restored.exactHttpRequest.pristine).isTrue();
        assertThat(restored.exactHttpRequest.invalidationReason == null
                || restored.exactHttpRequest.invalidationReason.isBlank()).isTrue();
        assertThat(restored.exactHttpRequest.httpVersion).isEqualTo("HTTP/1.0");
        assertThat(restored.exactHttpRequest.semanticFingerprint)
                .isEqualTo(restored.computeSemanticFingerprint())
                .isNotEqualTo(restored.computeLegacySemanticFingerprintV1());
        assertThat(new RequestBuilder(null).buildRequest(restored, null)).isEqualTo(trusted);

        byte[] malformed = "not an HTTP request".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ApiRequest malformedRequest = legacyExactRequest(malformed);
        ApiCollection malformedCollection = new ApiCollection();
        malformedCollection.name = "Malformed Legacy";
        malformedCollection.requests.add(malformedRequest);
        Path malformedOutput = tempDir.resolve("malformed-legacy.api-workbench.json");
        new CollectionExportService().exportCollection(malformedCollection,
                new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON,
                        malformedOutput, false, null, Map.of()));
        ApiRequest malformedRestored = new ApiWorkbenchCollectionParser()
                .parse(malformedOutput.toFile()).requests.get(0);
        assertThat(malformedRestored.exactHttpRequest.httpVersion).isNull();
        assertThat(malformedRestored.exactHttpRequest.semanticFingerprint)
                .isEqualTo(malformedRestored.computeSemanticFingerprint());
        assertThat(malformedRestored.exactHttpRequest.rawRequestBytes).isEqualTo(malformed);
        assertThat(malformedRestored.exactHttpRequest.pristine).isTrue();
    }

    private static ApiRequest legacyExactRequest(byte[] raw) {
        ApiRequest request = new ApiRequest();
        request.id = java.util.UUID.randomUUID().toString();
        request.name = "Legacy";
        request.method = "GET";
        request.url = "https://example.test/legacy";
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.exactHttpRequest = new ExactHttpRequestSnapshot();
        request.exactHttpRequest.rawRequestBytes = raw.clone();
        request.exactHttpRequest.serviceHost = "example.test";
        request.exactHttpRequest.servicePort = 443;
        request.exactHttpRequest.secure = true;
        request.exactHttpRequest.pristine = true;
        request.exactHttpRequest.httpVersion = null;
        request.exactHttpRequest.semanticFingerprint = request.computeLegacySemanticFingerprintV1();
        return request;
    }

    private static ApiRequest requestById(ApiCollection collection, String id) {
        return collection.requests.stream()
                .filter(request -> request != null && id.equals(request.id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Request not found: " + id));
    }
}
