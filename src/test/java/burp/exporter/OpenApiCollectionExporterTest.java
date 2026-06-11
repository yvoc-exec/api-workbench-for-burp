package burp.exporter;

import burp.models.ApiCollection;
import burp.models.EnvironmentProfile;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiCollectionExporterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesParseableOpenApiJsonWithServersPathsBodiesAndSecuritySchemes() throws Exception {
        ApiCollection collection = ExportTestFixtures.sampleCollection();
        EnvironmentProfile activeEnvironment = ExportTestFixtures.activeEnvironment();
        Path output = tempDir.resolve("apim.openapi.json");

        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            OpenApiCollectionExporter.writeJson(collection, new CollectionExportOptions(
                    CollectionExportFormat.OPENAPI_JSON,
                    output,
                    true,
                    activeEnvironment,
                    Map.of()
            ), writer, new java.util.ArrayList<>());
        }

        JsonObject root = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertThat(root.get("openapi").getAsString()).isEqualTo("3.0.3");
        assertThat(root.getAsJsonObject("info").get("title").getAsString()).isEqualTo("APIM");
        assertThat(root.getAsJsonArray("servers").get(0).getAsJsonObject().get("url").getAsString())
                .isEqualTo("https://api.example.test");

        JsonObject paths = root.getAsJsonObject("paths");
        assertThat(paths.has("/login")).isTrue();
        assertThat(paths.has("/oauth/token")).isTrue();
        assertThat(paths.has("/users")).isTrue();
        assertThat(paths.has("/users/42")).isTrue();

        JsonObject loginOperation = paths.getAsJsonObject("/login").getAsJsonObject("post");
        assertThat(loginOperation.get("operationId").getAsString()).contains("auth");
        assertThat(loginOperation.getAsJsonObject("requestBody").getAsJsonObject("content").has("application/json")).isTrue();
        assertThat(loginOperation.getAsJsonObject("responses").has("default")).isTrue();

        JsonObject oauthOperation = paths.getAsJsonObject("/oauth/token").getAsJsonObject("post");
        assertThat(oauthOperation.getAsJsonObject("requestBody").getAsJsonObject("content").has("application/x-www-form-urlencoded")).isTrue();

        JsonObject usersOperation = paths.getAsJsonObject("/users").getAsJsonObject("get");
        assertThat(usersOperation.getAsJsonArray("parameters").size()).isGreaterThan(0);

        JsonObject components = root.getAsJsonObject("components");
        assertThat(components.getAsJsonObject("securitySchemes").entrySet()).isNotEmpty();
    }

    @Test
    void writesParseableOpenApiYaml() throws Exception {
        ApiCollection collection = ExportTestFixtures.sampleCollection();
        EnvironmentProfile activeEnvironment = ExportTestFixtures.activeEnvironment();
        Path output = tempDir.resolve("apim.openapi.yaml");

        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            OpenApiCollectionExporter.writeYaml(collection, new CollectionExportOptions(
                    CollectionExportFormat.OPENAPI_YAML,
                    output,
                    true,
                    activeEnvironment,
                    Map.of()
            ), writer, new java.util.ArrayList<>());
        }

        Object parsed = new Yaml().load(Files.readString(output));
        assertThat(parsed).isInstanceOf(Map.class);
        Map<?, ?> root = (Map<?, ?>) parsed;
        assertThat(root.get("openapi")).isEqualTo("3.0.3");
        assertThat(((Map<?, ?>) root.get("info")).get("title")).isEqualTo("APIM");
        assertThat(((Map<?, ?>) root.get("paths")).containsKey("/login")).isTrue();
    }
}
