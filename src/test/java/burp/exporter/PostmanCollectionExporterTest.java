package burp.exporter;

import burp.models.ApiCollection;
import burp.models.EnvironmentProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PostmanCollectionExporterTest {

    @Test
    void buildsNestedFoldersRequestsAuthBodyAndScriptsWithoutResolvingPlaceholders() {
        ApiCollection collection = ExportTestFixtures.sampleCollection();
        JsonObject root = PostmanCollectionExporter.build(collection, new CollectionExportOptions(
                CollectionExportFormat.POSTMAN_JSON,
                null,
                false,
                null,
                Map.of()
        ), new java.util.ArrayList<>());

        assertThat(root.getAsJsonObject("info").get("name").getAsString()).isEqualTo("APIM");
        assertThat(root.getAsJsonObject("auth").get("type").getAsString()).isEqualTo("basic");
        assertThat(root.getAsJsonArray("variable")).isNotEmpty();

        JsonObject authFolder = postmanFolder(root.getAsJsonArray("item"), "Auth");
        assertThat(authFolder.getAsJsonObject("auth").get("type").getAsString()).isEqualTo("bearer");
        JsonObject loginRequest = postmanRequest(authFolder.getAsJsonArray("item"), "Auth");
        assertThat(loginRequest.getAsJsonObject("request").get("method").getAsString()).isEqualTo("POST");
        assertThat(loginRequest.getAsJsonObject("request").getAsJsonObject("body").get("raw").getAsString())
                .contains("{{missing_password}}");
        assertThat(loginRequest.getAsJsonArray("event")).hasSize(2);

        JsonObject oauthRequest = postmanRequest(authFolder.getAsJsonArray("item"), "OAuth");
        assertThat(oauthRequest.getAsJsonObject("request").getAsJsonObject("body").get("mode").getAsString())
                .isEqualTo("urlencoded");
        assertThat(oauthRequest.getAsJsonObject("request").getAsJsonObject("auth").get("type").getAsString())
                .isEqualTo("noauth");

        JsonObject rootUsers = postmanRequest(root.getAsJsonArray("item"), "GET /users");
        assertThat(rootUsers.getAsJsonObject("request").getAsJsonPrimitive("url").getAsString())
                .isEqualTo("{{base_url}}/users?role={{role}}&page=1");

        JsonObject usersFolder = postmanFolder(root.getAsJsonArray("item"), "Users");
        JsonObject backslashRequest = postmanRequest(usersFolder.getAsJsonArray("item"), "users\\{id}");
        assertThat(backslashRequest.getAsJsonObject("request").getAsJsonPrimitive("url").getAsString())
                .isEqualTo("{{base_url}}/users/{{user_id}}");
    }

    @Test
    void resolvesPostmanRequestWhenActiveEnvironmentIsProvided() {
        ApiCollection collection = ExportTestFixtures.sampleCollection();
        EnvironmentProfile activeEnvironment = ExportTestFixtures.activeEnvironment();
        JsonObject root = PostmanCollectionExporter.build(collection, new CollectionExportOptions(
                CollectionExportFormat.POSTMAN_JSON,
                null,
                true,
                activeEnvironment,
                Map.of()
        ), new java.util.ArrayList<>());

        JsonObject loginRequest = postmanRequest(postmanFolder(root.getAsJsonArray("item"), "Auth").getAsJsonArray("item"), "Auth");
        assertThat(loginRequest.getAsJsonObject("request").getAsJsonPrimitive("url").getAsString())
                .isEqualTo("https://api.example.test/login");
        assertThat(loginRequest.getAsJsonObject("request").getAsJsonObject("body").getAsJsonPrimitive("raw").getAsString())
                .contains("resolved-password");
    }

    private static JsonObject postmanFolder(JsonArray items, String name) {
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            if (name.equals(item.get("name").getAsString()) && item.has("item") && !item.has("request")) {
                return item;
            }
            if (item.has("item")) {
                try {
                    return postmanFolder(item.getAsJsonArray("item"), name);
                } catch (AssertionError ignored) {
                    // continue
                }
            }
        }
        throw new AssertionError("Folder not found: " + name);
    }

    private static JsonObject postmanRequest(JsonArray items, String name) {
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            if (name.equals(item.get("name").getAsString()) && item.has("request")) {
                return item;
            }
            if (item.has("item")) {
                try {
                    return postmanRequest(item.getAsJsonArray("item"), name);
                } catch (AssertionError ignored) {
                    // continue
                }
            }
        }
        throw new AssertionError("Request not found: " + name);
    }
}
