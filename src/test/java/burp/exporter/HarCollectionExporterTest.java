package burp.exporter;

import burp.models.ApiCollection;
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
