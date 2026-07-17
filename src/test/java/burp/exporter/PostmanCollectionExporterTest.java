package burp.exporter;

import burp.models.ApiCollection;
import burp.models.EnvironmentProfile;
import burp.models.ApiRequest;
import burp.parser.PostmanParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PostmanCollectionExporterTest {
    @TempDir
    Path tempDir;

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

    @Test
    void resolveModePreservesModeledPathTemplateWhileResolvingBaseQueryHeadersAndBody() {
        ApiCollection collection = new ApiCollection();
        collection.name = "C";
        ApiRequest request = new ApiRequest();
        request.name = "R";
        request.method = "POST";
        request.url = "{{baseUrl}}/users/{{id}}";
        ApiRequest.Parameter path = new ApiRequest.Parameter("path", "id", "42");
        path.valuePresent = true;
        ApiRequest.Parameter query = new ApiRequest.Parameter("query", "filter", "{{filter}}");
        query.valuePresent = true;
        request.parameters.add(path);
        request.parameters.add(query);
        request.headers.add(new ApiRequest.Header("X-ID", "{{id}}"));
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"id\":\"{{id}}\"}";
        collection.requests.add(request);
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.variables.put("baseUrl", "https://api.example.test");
        environment.variables.put("id", "99");
        environment.variables.put("filter", "active");

        JsonObject root = PostmanCollectionExporter.build(collection, new CollectionExportOptions(
                CollectionExportFormat.POSTMAN_JSON, null, true, environment, Map.of()), new ArrayList<>());
        JsonObject exported = root.getAsJsonArray("item").get(0).getAsJsonObject()
                .getAsJsonObject("request");

        assertThat(exported.getAsJsonObject("url").get("raw").getAsString())
                .isEqualTo("https://api.example.test/users/{{id}}?filter=active");
        assertThat(exported.getAsJsonObject("url").getAsJsonArray("query")
                .get(0).getAsJsonObject().get("value").getAsString()).isEqualTo("active");
        assertThat(exported.getAsJsonArray("header").get(0).getAsJsonObject().get("value").getAsString())
                .isEqualTo("99");
        assertThat(exported.getAsJsonObject("body").get("raw").getAsString()).contains("99");
    }

    @Test
    void pathAndQueryRowsAndCollectionVariableMetadataRoundTripThroughPostman() throws Exception {
        ApiCollection collection = new ApiCollection();
        collection.name = "C";
        collection.environment.put("collision", "environment");
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = "collision";
        variable.value = "collection";
        variable.type = "secret";
        variable.enabled = false;
        collection.variables.add(variable);
        ApiRequest request = new ApiRequest();
        request.name = "R";
        request.method = "GET";
        request.url = "https://example.test/users/:id";
        ApiRequest.Parameter query = new ApiRequest.Parameter("query", "flag", "");
        query.valuePresent = false;
        query.description = "bare query";
        ApiRequest.Parameter path = new ApiRequest.Parameter("path", "id", "42");
        path.valuePresent = true;
        path.disabled = true;
        path.description = "identifier";
        path.type = "string";
        request.parameters.add(query);
        request.parameters.add(path);
        collection.requests.add(request);

        JsonObject root = PostmanCollectionExporter.build(collection, new CollectionExportOptions(
                CollectionExportFormat.POSTMAN_JSON, null, false, null, Map.of()), new ArrayList<>());
        JsonObject url = root.getAsJsonArray("item").get(0).getAsJsonObject()
                .getAsJsonObject("request").getAsJsonObject("url");
        assertThat(url.get("raw").getAsString()).isEqualTo("https://example.test/users/:id?flag");
        assertThat(url.getAsJsonArray("query")).hasSize(1);
        assertThat(url.getAsJsonArray("variable")).hasSize(1);
        assertThat(url.getAsJsonArray("variable").get(0).getAsJsonObject().get("disabled").getAsBoolean()).isTrue();
        assertThat(root.getAsJsonArray("variable")).hasSize(1);
        assertThat(root.getAsJsonArray("variable").get(0).getAsJsonObject().get("value").getAsString())
                .isEqualTo("collection");

        Path file = tempDir.resolve("round-trip.json");
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        ApiCollection reparsed = new PostmanParser().parse(file.toFile());
        assertThat(reparsed.requests.get(0).parameters).extracting(p -> p.location)
                .containsExactly("query", "path");
        assertThat(reparsed.requests.get(0).url).contains("/users/:id");
        assertThat(reparsed.variables.get(0).type).isEqualTo("secret");
        assertThat(reparsed.variables.get(0).enabled).isFalse();
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
