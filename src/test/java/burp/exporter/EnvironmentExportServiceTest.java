package burp.exporter;

import burp.models.EnvironmentProfile;
import burp.utils.EnvironmentImportService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentExportServiceTest {
    @TempDir
    Path tempDir;

    private final EnvironmentExportService service = new EnvironmentExportService();

    @Test
    void exportsNativeEnvironmentJsonWithOauth2MetadataAndWarnings() throws Exception {
        EnvironmentProfile profile = ExportTestFixtures.activeEnvironment();
        Path output = tempDir.resolve("uat.api-workbench.environment.json");

        ExportResult result = service.exportEnvironment(
                profile,
                new EnvironmentExportOptions(EnvironmentExportFormat.API_WORKBENCH_JSON, output)
        );

        assertThat(result.outputPath).isEqualTo(output.toAbsolutePath().normalize());
        assertThat(result.variableCount).isEqualTo(profile.variables.size());
        assertThat(result.warnings).anyMatch(message -> message.contains("blank environment variable key"));

        JsonObject root = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertThat(root.get("format").getAsString()).isEqualTo("api-workbench-environment");
        assertThat(root.get("name").getAsString()).isEqualTo("UAT");
        assertThat(root.get("sourceFormat").getAsString()).isEqualTo("api-workbench");
        assertThat(root.get("sourceFileName").getAsString()).isEqualTo("uat.env");
        assertThat(root.getAsJsonObject("variables").get("base_url").getAsString()).isEqualTo("https://api.example.test");
        assertThat(root.getAsJsonObject("oauth2").getAsJsonObject("config").get("accessTokenUrl").getAsString())
                .isEqualTo("https://auth.example.test/token");
        assertThat(root.getAsJsonObject("oauth2").getAsJsonObject("outputBindings").get("accessToken").getAsString())
                .isEqualTo("env_access_token");
    }

    @Test
    void roundTripsApiWorkbenchEnvironmentThroughImporter() throws Exception {
        EnvironmentProfile profile = ExportTestFixtures.activeEnvironment();
        Path output = tempDir.resolve("uat.api-workbench.environment.json");

        service.exportEnvironment(
                profile,
                new EnvironmentExportOptions(EnvironmentExportFormat.API_WORKBENCH_JSON, output)
        );

        List<EnvironmentProfile> imported = EnvironmentImportService.importEnvironment(output.toFile());
        assertThat(imported).hasSize(1);
        EnvironmentProfile roundTrip = imported.get(0);
        assertThat(roundTrip.name).isEqualTo("UAT");
        assertThat(roundTrip.sourceFormat).isEqualTo("api-workbench");
        assertThat(roundTrip.sourceFileName).isEqualTo("uat.env");
        assertThat(roundTrip.variables).containsEntry("base_url", "https://api.example.test");
        assertThat(roundTrip.oauth2.config).containsEntry("accessTokenUrl", "https://auth.example.test/token");
        assertThat(roundTrip.oauth2.outputBindings).containsEntry("accessToken", "env_access_token");
    }

    @Test
    void exportsPostmanEnvironmentValuesArray() throws Exception {
        EnvironmentProfile profile = ExportTestFixtures.activeEnvironment();
        Path output = tempDir.resolve("uat.postman_environment.json");

        service.exportEnvironment(profile, new EnvironmentExportOptions(EnvironmentExportFormat.POSTMAN_JSON, output));

        JsonObject root = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertThat(root.get("_postman_variable_scope").getAsString()).isEqualTo("environment");
        assertThat(root.getAsJsonArray("values")).hasSizeGreaterThan(0);
        JsonObject first = root.getAsJsonArray("values").get(0).getAsJsonObject();
        assertThat(first.get("key").getAsString()).isEqualTo("base_url");
        assertThat(first.get("value").getAsString()).isEqualTo("https://api.example.test");
        assertThat(first.get("type").getAsString()).isEqualTo("default");
        assertThat(first.get("enabled").getAsBoolean()).isTrue();
    }

    @Test
    void exportsDotEnvWithEscapingAndDeterministicOrder() throws Exception {
        EnvironmentProfile profile = ExportTestFixtures.activeEnvironment();
        profile.variables.put("quoted", "hello world");
        profile.variables.put("path_value", "C:\\temp\\file");
        Path output = tempDir.resolve("uat.env");

        service.exportEnvironment(profile, new EnvironmentExportOptions(EnvironmentExportFormat.DOTENV, output));

        List<String> lines = Files.readAllLines(output);
        assertThat(lines.get(0)).isEqualTo("base_url=https://api.example.test");
        assertThat(String.join("\n", lines)).contains("quoted=\"hello world\"");
        assertThat(String.join("\n", lines)).contains("path_value=\"C:\\\\temp\\\\file\"");
    }

    @Test
    void exportsGenericJsonObject() throws Exception {
        EnvironmentProfile profile = ExportTestFixtures.activeEnvironment();
        Path output = tempDir.resolve("uat.env.json");

        service.exportEnvironment(profile, new EnvironmentExportOptions(EnvironmentExportFormat.JSON_OBJECT, output));

        JsonObject root = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertThat(root.get("base_url").getAsString()).isEqualTo("https://api.example.test");
        assertThat(root.has("")).isFalse();
    }

    @Test
    void exportsInsomniaEnvironmentResources() throws Exception {
        EnvironmentProfile profile = ExportTestFixtures.activeEnvironment();
        profile.runtimeVariables.put("runtime", "DO_NOT_EXPORT_RUNTIME_TOKEN");
        profile.oauth2.config.put("accessToken", "DO_NOT_EXPORT_RUNTIME_TOKEN");
        Path output = tempDir.resolve("uat.insomnia.environment.json");

        service.exportEnvironment(profile, new EnvironmentExportOptions(EnvironmentExportFormat.INSOMNIA_JSON, output));

        JsonObject root = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertThat(root.getAsJsonArray("resources")).hasSize(2);
        JsonObject workspace = root.getAsJsonArray("resources").get(0).getAsJsonObject();
        JsonObject env = root.getAsJsonArray("resources").get(1).getAsJsonObject();
        assertThat(workspace.get("_type").getAsString()).isEqualTo("workspace");
        assertThat(env.get("parentId").getAsString()).isEqualTo(workspace.get("_id").getAsString());
        assertThat(env.get("_type").getAsString()).isEqualTo("environment");
        assertThat(env.getAsJsonObject("data").get("base_url").getAsString()).isEqualTo("https://api.example.test");
        assertThat(root.toString()).doesNotContain("DO_NOT_EXPORT_RUNTIME_TOKEN");
    }

    @Test
    void exportsBrunoEnvironmentVarsBlock() throws Exception {
        EnvironmentProfile profile = ExportTestFixtures.activeEnvironment();
        profile.variables.put("quoted", "hello world");
        profile.variables.put("multiline", " line one\n  line two ");
        profile.runtimeVariables.put("runtime", "DO_NOT_EXPORT_RUNTIME_TOKEN");
        Path output = tempDir.resolve("uat.bru");

        service.exportEnvironment(profile, new EnvironmentExportOptions(EnvironmentExportFormat.BRUNO_BRU, output));

        String text = Files.readString(output);
        assertThat(text).contains("vars {");
        assertThat(text).contains("base_url: https://api.example.test");
        assertThat(text).contains("quoted: hello world");
        assertThat(text).contains("multiline: '''", "    line one", "     line two ", "  '''")
                .doesNotContain("DO_NOT_EXPORT_RUNTIME_TOKEN");
    }
}
