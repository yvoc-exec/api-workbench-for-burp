package burp.utils;

import burp.models.EnvironmentProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentImportServiceTest {

    @Test
    void importsPostmanValuesAsEnvironmentProfile() throws Exception {
        Path file = tempFile(".json", """
                {
                  "name": "UAT",
                  "values": [
                    {"key": "baseUrl", "value": "https://uat.example.test", "enabled": true},
                    {"key": "token", "value": "abc123", "enabled": true}
                  ]
                }
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).name).isEqualTo("UAT");
        assertThat(profiles.get(0).sourceFormat).isEqualTo("postman");
        assertThat(profiles.get(0).variables)
                .containsEntry("baseUrl", "https://uat.example.test")
                .containsEntry("token", "abc123");
    }

    @Test
    void importsWrappedPostmanEnvironmentValues() throws Exception {
        Path file = tempFile(".json", """
                {
                  "environment": {
                    "name": "UAT",
                    "values": [
                      {"key": "base_url", "value": "https://uat.example.test", "enabled": true}
                    ]
                  }
                }
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).name).isEqualTo("UAT");
        assertThat(profiles.get(0).variables).containsEntry("base_url", "https://uat.example.test");
    }

    @Test
    void importsPostmanVariableSingularArray() throws Exception {
        Path file = tempFile(".json", """
                {
                  "name": "UAT",
                  "variable": [
                    {"key": "base_url", "value": "https://uat.example.test"}
                  ]
                }
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).variables).containsEntry("base_url", "https://uat.example.test");
    }

    @Test
    void importsCurrentValueWhenValueMissing() throws Exception {
        Path file = tempFile(".json", """
                {
                  "name": "UAT",
                  "values": [
                    {"key": "token", "currentValue": "current-token"}
                  ]
                }
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles.get(0).variables).containsEntry("token", "current-token");
    }

    @Test
    void importsInitialValueWhenValueAndCurrentValueMissing() throws Exception {
        Path file = tempFile(".json", """
                {
                  "name": "UAT",
                  "values": [
                    {"key": "token", "initialValue": "initial-token"}
                  ]
                }
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles.get(0).variables).containsEntry("token", "initial-token");
    }

    @Test
    void skipsDisabledPostmanValues() throws Exception {
        Path file = tempFile(".json", """
                {
                  "name": "UAT",
                  "values": [
                    {"key": "baseUrl", "value": "https://uat.example.test", "enabled": true},
                    {"key": "token", "value": "abc123", "enabled": false}
                  ]
                }
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).variables).containsEntry("baseUrl", "https://uat.example.test");
        assertThat(profiles.get(0).variables).doesNotContainKey("token");
    }

    @Test
    void skipsEnabledFalseString() throws Exception {
        Path file = tempFile(".json", """
                {
                  "name": "UAT",
                  "values": [
                    {"key": "baseUrl", "value": "https://uat.example.test", "enabled": "false"},
                    {"key": "token", "value": "abc123", "enabled": "true"}
                  ]
                }
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles.get(0).variables)
                .containsEntry("token", "abc123")
                .doesNotContainKey("baseUrl");
    }

    @Test
    void skipsDisabledTrueBoolean() throws Exception {
        Path file = tempFile(".json", """
                {
                  "name": "UAT",
                  "values": [
                    {"key": "baseUrl", "value": "https://uat.example.test", "disabled": true},
                    {"key": "token", "value": "abc123"}
                  ]
                }
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles.get(0).variables)
                .containsEntry("token", "abc123")
                .doesNotContainKey("baseUrl");
    }

    @Test
    void importsJsonVariablesArrayAndSkipsDisabled() throws Exception {
        Path file = tempFile(".json", """
                {
                  "name": "QA",
                  "variables": [
                    {"name": "baseUrl", "value": "https://qa.example.test", "enabled": true},
                    {"name": "secret", "value": "hidden", "enabled": false}
                  ]
                }
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).name).isEqualTo("QA");
        assertThat(profiles.get(0).variables).containsEntry("baseUrl", "https://qa.example.test");
        assertThat(profiles.get(0).variables).doesNotContainKey("secret");
    }

    @Test
    void importsGenericJsonObject() throws Exception {
        Path dir = Files.createTempDirectory("environment-json-");
        Path file = dir.resolve("dev-env.json");
        Files.writeString(file, """
                {
                  "baseUrl": "https://dev.example.test",
                  "token": "dev-token",
                  "nested": {"a": 1}
                }
                """, StandardCharsets.UTF_8);
        file.toFile().deleteOnExit();

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).name).isEqualTo("dev-env");
        assertThat(profiles.get(0).variables)
                .containsEntry("baseUrl", "https://dev.example.test")
                .containsEntry("token", "dev-token")
                .containsEntry("nested", "{\"a\":1}");
    }

    @Test
    void doesNotImportPostmanMetadataAsVariables() throws Exception {
        Path file = tempFile(".json", """
                {
                  "name": "UAT",
                  "_postman_variable_scope": "environment",
                  "_postman_exported_at": "2026-06-07T00:00:00Z",
                  "_postman_exported_using": "Postman/11.0.0",
                  "token": "abc123"
                }
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles.get(0).variables)
                .containsEntry("token", "abc123")
                .doesNotContainKeys("_postman_variable_scope", "_postman_exported_at", "_postman_exported_using", "name");
    }

    @Test
    void throwsClearMessageForMetadataOnlyPostmanFile() throws Exception {
        Path file = tempFile(".json", """
                {
                  "name": "UAT",
                  "_postman_variable_scope": "environment",
                  "_postman_exported_at": "2026-06-07T00:00:00Z",
                  "_postman_exported_using": "Postman/11.0.0"
                }
                """);

        assertThatThrownBy(() -> EnvironmentImportService.importEnvironment(file.toFile()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No environment variables found")
                .hasMessageContaining(file.getFileName().toString());
    }

    @Test
    void importsDotEnvFile() throws Exception {
        Path file = tempFile(".env", """
                # comment
                base_url=https://uat.example.test
                token=abc=123
                blank=
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).name).startsWith("environment-");
        assertThat(profiles.get(0).sourceFormat).isEqualTo("dotenv");
        assertThat(profiles.get(0).variables)
                .containsEntry("base_url", "https://uat.example.test")
                .containsEntry("token", "abc=123")
                .containsEntry("blank", "");
    }

    @Test
    void importsDotEnvWithExportAndQuotedValues() throws Exception {
        Path file = tempFile(".env", """
                # comment
                BASE_URL=https://api.example.test
                export TOKEN=abc123
                QUOTED_DOUBLE="hello world"
                QUOTED_SINGLE='hello single'
                EMPTY=
                URL_WITH_EQUALS=https://example.test/callback?a=1=b
                LITERAL_REF=${OTHER_KEY}
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles).hasSize(1);
        Map<String, String> vars = profiles.get(0).variables;
        assertThat(vars).containsEntry("BASE_URL", "https://api.example.test");
        assertThat(vars).containsEntry("TOKEN", "abc123");
        assertThat(vars).containsEntry("QUOTED_DOUBLE", "hello world");
        assertThat(vars).containsEntry("QUOTED_SINGLE", "hello single");
        assertThat(vars).containsEntry("EMPTY", "");
        assertThat(vars).containsEntry("URL_WITH_EQUALS", "https://example.test/callback?a=1=b");
        assertThat(vars).containsEntry("LITERAL_REF", "${OTHER_KEY}");
    }

    @Test
    void importsDotEnvSkipsCommentsBlankLinesAndInvalidKeys() throws Exception {
        Path file = tempFile(".env", """
                # comment

                NO_EQUALS
                =missingKey
                VALID=value
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).variables)
                .containsEntry("VALID", "value")
                .doesNotContainKey("NO_EQUALS")
                .doesNotContainKey("");
    }

    @Test
    void importsBrunoVarsBlock() throws Exception {
        Path file = tempFile(".bru", """
                vars {
                  base_url: https://bruno.example.test
                  token: bruno-token
                }
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).name).startsWith("environment-");
        assertThat(profiles.get(0).sourceFormat).isEqualTo("bruno");
        assertThat(profiles.get(0).variables)
                .containsEntry("base_url", "https://bruno.example.test")
                .containsEntry("token", "bruno-token");
    }

    @Test
    void importsMultipleInsomniaEnvironmentResources() throws Exception {
        Path file = tempFile(".json", """
                {
                  "resources": [
                    {
                      "_type": "environment",
                      "_id": "env_uat",
                      "name": "UAT",
                      "data": {"baseUrl": "https://uat.example.test"}
                    },
                    {
                      "_type": "environment",
                      "_id": "env_prd",
                      "data": {"baseUrl": "https://prd.example.test"}
                    },
                    {
                      "_type": "workspace",
                      "_id": "ws_1"
                    }
                  ]
                }
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles).hasSize(2);
        assertThat(profiles.get(0).name).isEqualTo("UAT");
        assertThat(profiles.get(0).sourceFormat).isEqualTo("insomnia");
        assertThat(profiles.get(0).variables).containsEntry("baseUrl", "https://uat.example.test");
        assertThat(profiles.get(1).name).isEqualTo("env_prd");
        assertThat(profiles.get(1).variables).containsEntry("baseUrl", "https://prd.example.test");
    }

    @Test
    void importsApiWorkbenchExportedEnvironmentShape() throws Exception {
        Path file = tempFile(".json", """
                {
                  "name": "UAT",
                  "variables": {
                    "baseUrl": "https://uat.example.test",
                    "token": "uat-token"
                  },
                  "oauth2": {
                    "config": {
                      "oauth2_grant": "client_credentials",
                      "oauth2_client_id": "client-123"
                    },
                    "outputBindings": {
                      "accessToken": "token",
                      "refreshToken": "refresh_token"
                    }
                  }
                }
                """);

        List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(file.toFile());

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).name).isEqualTo("UAT");
        assertThat(profiles.get(0).sourceFormat).isEqualTo("api-workbench");
        assertThat(profiles.get(0).variables)
                .containsEntry("baseUrl", "https://uat.example.test")
                .containsEntry("token", "uat-token");
        assertThat(profiles.get(0).oauth2.config)
                .containsEntry("oauth2_grant", "client_credentials")
                .containsEntry("oauth2_client_id", "client-123");
        assertThat(profiles.get(0).oauth2.outputBindings)
                .containsEntry("accessToken", "token")
                .containsEntry("refreshToken", "refresh_token");
    }

    @Test
    void rejectsUnsupportedOrEmptyFileWithClearMessage() throws Exception {
        Path file = Files.createTempFile("environment-empty-", ".txt");
        file.toFile().deleteOnExit();

        assertThatThrownBy(() -> EnvironmentImportService.importEnvironment(file.toFile()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("empty")
                .hasMessageContaining(file.getFileName().toString());
    }

    private static Path tempFile(String suffix, String content) throws IOException {
        Path file = Files.createTempFile("environment-", suffix);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        file.toFile().deleteOnExit();
        return file;
    }
}
