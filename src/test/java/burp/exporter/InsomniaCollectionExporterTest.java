package burp.exporter;

import burp.models.ApiCollection;
import burp.models.EnvironmentProfile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InsomniaCollectionExporterTest {

    @Test
    void writesWorkspaceFolderRequestAndEnvironmentResources() {
        ApiCollection collection = ExportTestFixtures.sampleCollection();
        EnvironmentProfile activeEnvironment = ExportTestFixtures.activeEnvironment();
        JsonObject root = InsomniaCollectionExporter.build(collection, new CollectionExportOptions(
                CollectionExportFormat.INSOMNIA_JSON,
                null,
                true,
                activeEnvironment,
                Map.of()
        ), new java.util.ArrayList<>());

        assertThat(root.getAsJsonArray("resources")).isNotEmpty();
        JsonObject workspace = resource(root, "workspace", "APIM");
        assertThat(workspace.get("_id").getAsString()).isEqualTo(ExportIds.workspaceId(collection));

        JsonObject environment = resource(root, "environment", "APIM Environment");
        assertThat(environment.getAsJsonObject("data").get("base_url").getAsString()).isEqualTo("https://api.example.test");

        JsonObject authFolder = resource(root, "request_group", "Auth");
        assertThat(authFolder.get("parentId").getAsString()).isEqualTo(ExportIds.workspaceId(collection));
        assertThat(authFolder.has("authentication")).isTrue();

        JsonObject oauthFolder = resource(root, "request_group", "OAuth");
        assertThat(oauthFolder.get("parentId").getAsString()).isEqualTo(ExportIds.folderId("Auth"));

        JsonObject loginRequest = resource(root, "request", "Auth");
        assertThat(loginRequest.get("parentId").getAsString()).isEqualTo(ExportIds.folderId("Auth"));
        assertThat(loginRequest.getAsJsonObject("body").get("mimeType").getAsString()).isEqualTo("application/json");
        assertThat(loginRequest.getAsJsonObject("authentication").get("type").getAsString()).isEqualTo("bearer");

        JsonObject usersFolder = resource(root, "request_group", "Users");
        assertThat(usersFolder.get("parentId").getAsString()).isEqualTo(ExportIds.workspaceId(collection));
    }

    private static JsonObject resource(JsonObject root, String type, String name) {
        for (int i = 0; i < root.getAsJsonArray("resources").size(); i++) {
            JsonObject resource = root.getAsJsonArray("resources").get(i).getAsJsonObject();
            if (type.equals(resource.get("_type").getAsString()) && name.equals(resource.get("name").getAsString())) {
                return resource;
            }
        }
        throw new AssertionError("Resource not found: " + type + " " + name);
    }
}
