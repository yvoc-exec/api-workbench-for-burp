package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class R7CorrectiveExportTest {
    @Test
    void standaloneFilePathIsEmittedByPostmanAndInsomniaWithoutRawFallback() {
        ApiRequest request = request();
        request.body.mode = "file";
        request.body.filePath = "C:/payloads/body.bin";
        ApiCollection collection = collection(request);

        JsonObject postman = PostmanCollectionExporter.build(collection, options(CollectionExportFormat.POSTMAN_JSON), new ArrayList<>());
        JsonObject insomnia = InsomniaCollectionExporter.build(collection, options(CollectionExportFormat.INSOMNIA_JSON), new ArrayList<>());

        assertThat(postman.toString()).contains("\"file\":{\"src\":\"C:/payloads/body.bin\"}");
        assertThat(insomnia.toString()).contains("\"fileName\":\"C:/payloads/body.bin\"");
        assertThat(request.body.raw).isNull();
    }

    @Test
    void pristineExactTextIsDerivedForEveryModeledTextExporterWithoutMutatingSource() throws Exception {
        ApiRequest request = request();
        request.body.mode = "raw";
        request.exactHttpRequest = new ExactHttpRequestSnapshot();
        request.exactHttpRequest.rawRequestBytes = (
                "POST /exact HTTP/1.1\r\nHost: example.test\r\n\r\nexact-export-body")
                .getBytes(StandardCharsets.ISO_8859_1);
        request.exactHttpRequest.pristine = true;
        ApiCollection collection = collection(request);

        String postman = PostmanCollectionExporter.build(collection, options(CollectionExportFormat.POSTMAN_JSON), new ArrayList<>()).toString();
        String insomnia = InsomniaCollectionExporter.build(collection, options(CollectionExportFormat.INSOMNIA_JSON), new ArrayList<>()).toString();
        String openApi = OpenApiCollectionExporter.build(collection, options(CollectionExportFormat.OPENAPI_JSON), new ArrayList<>()).toString();
        String har = HarCollectionExporter.build(collection, options(CollectionExportFormat.HAR_JSON), new ArrayList<>()).toString();
        ByteArrayOutputStream brunoBytes = new ByteArrayOutputStream();
        BrunoCollectionExporter.write(collection, options(CollectionExportFormat.BRUNO_ZIP), brunoBytes, new ArrayList<>());
        String bruno = unzipText(brunoBytes.toByteArray());

        assertThat(postman).contains("exact-export-body");
        assertThat(insomnia).contains("exact-export-body");
        assertThat(openApi).contains("exact-export-body");
        assertThat(har).contains("exact-export-body");
        assertThat(bruno).contains("exact-export-body");
        assertThat(request.body.raw).isNull();
        assertThat(request.exactHttpRequest.pristine).isTrue();
    }

    private static ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.id = "request-id";
        request.name = "Request";
        request.method = "POST";
        request.url = "https://example.test/exact";
        request.body = new ApiRequest.Body();
        return request;
    }

    private static ApiCollection collection(ApiRequest request) {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        collection.requests.add(request);
        return collection;
    }

    private static CollectionExportOptions options(CollectionExportFormat format) {
        return new CollectionExportOptions(format, null, false, null, Map.of());
    }

    private static String unzipText(byte[] zipBytes) throws Exception {
        StringBuilder out = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            while (zip.getNextEntry() != null) {
                out.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out.toString();
    }
}
