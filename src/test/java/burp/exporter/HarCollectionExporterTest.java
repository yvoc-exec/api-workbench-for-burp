package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HarCollectionExporterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesHarWithRequestEntriesAndPlaceholderResponses() throws Exception {
        ApiCollection collection = ExportTestFixtures.sampleCollection();
        Path output = tempDir.resolve("apim.har");

        new CollectionExportService().exportCollection(
                collection,
                new CollectionExportOptions(CollectionExportFormat.HAR_JSON, output, true, ExportTestFixtures.activeEnvironment(), Map.of())
        );

        JsonObject root = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        JsonObject log = root.getAsJsonObject("log");
        assertThat(log.get("version").getAsString()).isEqualTo("1.2");
        assertThat(log.getAsJsonObject("creator").get("name").getAsString()).isEqualTo("API Workbench for Burp");
        assertThat(log.getAsJsonArray("entries")).hasSize(collection.requests.size());

        JsonObject login = entryByRequestMethod(log.getAsJsonArray("entries"), "POST");
        assertThat(login.getAsJsonObject("request").get("url").getAsString()).contains("https://api.example.test/login");
        assertThat(login.getAsJsonObject("request").getAsJsonArray("headers")).isNotEmpty();
        assertThat(login.getAsJsonObject("request").getAsJsonObject("postData").get("mimeType").getAsString())
                .isEqualTo("application/json");
        assertThat(login.getAsJsonObject("response").get("status").getAsInt()).isEqualTo(0);
        assertThat(login.getAsJsonObject("response").getAsJsonArray("headers")).isEmpty();
        assertThat(login.getAsJsonObject("response").getAsJsonObject("content").get("mimeType").getAsString()).isEqualTo("");

        JsonObject users = entryByRequestName(log.getAsJsonArray("entries"), "GET /users");
        assertThat(users.getAsJsonObject("request").getAsJsonArray("queryString")).hasSizeGreaterThan(0);
        assertThat(users.getAsJsonObject("request").getAsJsonArray("cookies")).hasSizeGreaterThan(0);
    }

    @Test
    void doesNotEmitInternalFilePathProperty() {
        ApiCollection collection = new ApiCollection();
        ApiRequest request = new ApiRequest();
        request.method = "POST";
        request.url = "https://example.test/upload";
        request.body = new ApiRequest.Body();
        request.body.mode = "formdata";
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField("upload", null);
        field.type = "file";
        field.fileUpload = true;
        field.filePath = "C:\\Users\\tester\\secret\\payload.bin";
        request.body.formdata.add(field);
        collection.requests.add(request);

        String serialized = HarCollectionExporter.build(collection, null, new java.util.ArrayList<>()).toString();
        JsonObject param = JsonParser.parseString(serialized).getAsJsonObject()
                .getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject()
                .getAsJsonObject("request").getAsJsonObject("postData")
                .getAsJsonArray("params").get(0).getAsJsonObject();

        assertThat(serialized).doesNotContain("\"filePath\"");
        assertThat(serialized).doesNotContain("C:\\\\Users\\\\tester\\\\secret");
        assertThat(param.get("fileName").getAsString()).isEqualTo("payload.bin");
    }

    private static JsonObject entryByRequestMethod(JsonArray entries, String method) {
        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.get(i).getAsJsonObject();
            if (method.equalsIgnoreCase(entry.getAsJsonObject("request").get("method").getAsString())) {
                return entry;
            }
        }
        throw new AssertionError("Entry not found for method: " + method);
    }

    private static JsonObject entryByRequestName(JsonArray entries, String requestName) {
        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.get(i).getAsJsonObject();
            JsonObject request = entry.getAsJsonObject("request");
            String url = request.get("url").getAsString();
            if (requestName.equals("GET /users") && url.contains("/users?")) {
                return entry;
            }
        }
        throw new AssertionError("Entry not found for name: " + requestName);
    }
}
