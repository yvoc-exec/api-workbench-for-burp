package burp.exporter;

import burp.models.ApiCollection;
import burp.models.EnvironmentProfile;
import burp.models.UnresolvedVariableIssue;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionExportServiceTest {
    @TempDir
    Path tempDir;

    private final CollectionExportService service = new CollectionExportService();

    @Test
    void exportsNativeCollectionJsonWithRoundTripMetadataAndNoRuntimeState() throws Exception {
        ApiCollection collection = ExportTestFixtures.sampleCollection();
        Path output = tempDir.resolve("apim.api-workbench.collection.json");

        ExportResult result = service.exportCollection(
                collection,
                new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON, output, false, null, Map.of())
        );

        assertThat(result.outputPath).isEqualTo(output.toAbsolutePath().normalize());
        assertThat(result.formatLabel).isEqualTo(CollectionExportFormat.API_WORKBENCH_JSON.displayName());
        assertThat(result.requestCount).isEqualTo(collection.requests.size());
        JsonObject root = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertThat(root.get("format").getAsString()).isEqualTo("api-workbench-collection");
        JsonObject exported = root.getAsJsonObject("collection");
        assertThat(exported.get("name").getAsString()).isEqualTo("APIM");
        assertThat(exported.get("description").getAsString()).isEqualTo("API collection for exports");
        assertThat(jsonArrayStrings(exported.getAsJsonArray("folderPaths")))
                .contains("Auth", "Auth/OAuth", "Users");
        assertThat(exported.toString()).doesNotContain("runtime_only", "runtime-token");

        JsonObject login = requestByName(exported.getAsJsonArray("requests"), "Auth");
        assertThat(login.get("path").getAsString()).isEqualTo("Auth");
        assertThat(login.get("editorMaterialized").getAsBoolean()).isTrue();
        assertThat(login.get("buildMode").getAsString()).isEqualTo("MANUAL_PRESERVE");
        assertThat(jsonArrayStrings(login.getAsJsonArray("suppressedAutoHeaders")))
                .contains("accept");
        assertThat(login.getAsJsonObject("body").get("raw").getAsString()).contains("{{missing_password}}");
        assertThat(login.getAsJsonObject("auth").get("type").getAsString()).isEqualTo("bearer");
    }

    @Test
    void resolvesVariablesUsingActiveEnvironmentAndExportOnlyOverlay() throws Exception {
        ApiCollection collection = ExportTestFixtures.sampleCollectionWithMissingVariable();
        EnvironmentProfile activeEnvironment = ExportTestFixtures.activeEnvironmentWithoutMissingPassword();
        Path output = tempDir.resolve("apim.postman_collection.json");

        ExportResult result = service.exportCollection(
                collection,
                new CollectionExportOptions(
                        CollectionExportFormat.POSTMAN_JSON,
                        output,
                        true,
                        activeEnvironment,
                        Map.of("missing_password", "quick-entry-password")
                )
        );

        assertThat(result.unresolvedVariableCount).isEqualTo(0);
        JsonObject root = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        JsonObject login = postmanRequestByName(root.getAsJsonArray("item"), "Auth");
        assertThat(login.getAsJsonObject("request").getAsJsonPrimitive("url").getAsString())
                .isEqualTo("https://api.example.test/login");
        assertThat(login.getAsJsonObject("request").getAsJsonObject("body").getAsJsonPrimitive("raw").getAsString())
                .contains("quick-entry-password");
    }

    @Test
    void unresolvedExportIssuesAreDetectedWithoutExportOnlyOverlay() {
        ApiCollection collection = ExportTestFixtures.sampleCollectionWithMissingVariable();
        EnvironmentProfile activeEnvironment = ExportTestFixtures.activeEnvironmentWithoutMissingPassword();

        List<UnresolvedVariableIssue> issues = ExportVariableResolutionService.collectUnresolvedIssues(collection, activeEnvironment);

        assertThat(issues).isNotEmpty();
        assertThat(issues).anySatisfy(issue -> assertThat(issue.variableName).isEqualTo("missing_password"));
    }

    private static JsonObject requestByName(JsonArray requests, String name) {
        for (int i = 0; i < requests.size(); i++) {
            JsonObject request = requests.get(i).getAsJsonObject();
            if (name.equals(request.get("name").getAsString())) {
                return request;
            }
        }
        throw new AssertionError("Request not found: " + name);
    }

    private static JsonObject postmanRequestByName(JsonArray items, String name) {
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            if (item.has("request") && name.equals(item.get("name").getAsString())) {
                return item;
            }
            if (item.has("item")) {
                try {
                    return postmanRequestByName(item.getAsJsonArray("item"), name);
                } catch (AssertionError ignored) {
                    // continue search
                }
            }
        }
        throw new AssertionError("Postman item not found: " + name);
    }

    private static List<String> jsonArrayStrings(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            values.add(array.get(i).getAsString());
        }
        return values;
    }
}
