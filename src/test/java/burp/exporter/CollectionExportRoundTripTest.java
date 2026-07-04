package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.parser.ParserRegistry;
import burp.utils.RequestPathResolver;
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
        assertThat(restored.exactHttpRequest.rawRequestBytes).isEqualTo(request.exactHttpRequest.rawRequestBytes);
        restored.exactHttpRequest.rawRequestBytes[0] = 'X';
        assertThat(request.exactHttpRequest.rawRequestBytes[0]).isEqualTo((byte) 'G');
    }

    private static ApiRequest requestById(ApiCollection collection, String id) {
        return collection.requests.stream()
                .filter(request -> request != null && id.equals(request.id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Request not found: " + id));
    }
}
