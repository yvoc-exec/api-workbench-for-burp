package burp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UniversalImporterEnvFileTest {

    @Test
    void loadEnvFileIntoMapSupportsPostmanAndBrunoEnvironmentFormats() throws Exception {
        Path postman = Files.createTempFile(Path.of("target"), "postman-env-", ".json").toAbsolutePath().normalize();
        Files.writeString(postman, """
                {
                  "values": [
                    {"key": "baseUrl", "value": "https://example.test"},
                    {"key": "token", "value": "abc"}
                  ]
                }
                """, StandardCharsets.UTF_8);

        Map<String, String> postmanTarget = new LinkedHashMap<>();
        UniversalImporter.EnvLoadResult postmanResult = UniversalImporter.loadEnvFileIntoMap(postman.toFile(), postmanTarget);
        assertThat(postmanResult.isSuccess()).isTrue();
        assertThat(postmanResult.loadedCount).isEqualTo(2);
        assertThat(postmanTarget).containsEntry("baseUrl", "https://example.test");
        assertThat(postmanTarget).containsEntry("token", "abc");

        Path brunoBru = Files.createTempFile(Path.of("target"), "bruno-env-", ".bru").toAbsolutePath().normalize();
        Files.writeString(brunoBru, """
                vars {
                  host: http://localhost:8787
                  token: abc:def:ghi
                  blank:
                  # comment
                  : ignored
                }
                """, StandardCharsets.UTF_8);

        Map<String, String> brunoBruTarget = new LinkedHashMap<>();
        UniversalImporter.EnvLoadResult brunoBruResult = UniversalImporter.loadEnvFileIntoMap(brunoBru.toFile(), brunoBruTarget);
        assertThat(brunoBruResult.isSuccess()).isTrue();
        assertThat(brunoBruResult.loadedCount).isEqualTo(3);
        assertThat(brunoBruTarget).containsEntry("host", "http://localhost:8787");
        assertThat(brunoBruTarget).containsEntry("token", "abc:def:ghi");
        assertThat(brunoBruTarget).containsEntry("blank", "");
        assertThat(brunoBruTarget).doesNotContainKey("");

        Path brunoJson = Files.createTempFile(Path.of("target"), "bruno-env-", ".json").toAbsolutePath().normalize();
        Files.writeString(brunoJson, """
                {
                  "name": "Local",
                  "variables": [
                    {"name": "host", "value": "https://api.example.test", "enabled": true},
                    {"name": "disabled_token", "value": "skip-me", "enabled": false},
                    {"name": "blank", "value": "", "enabled": true},
                    {"value": "ignored"},
                    {"name": "", "value": "ignored"}
                  ]
                }
                """, StandardCharsets.UTF_8);

        Map<String, String> brunoJsonTarget = new LinkedHashMap<>();
        UniversalImporter.EnvLoadResult brunoJsonResult = UniversalImporter.loadEnvFileIntoMap(brunoJson.toFile(), brunoJsonTarget);
        assertThat(brunoJsonResult.isSuccess()).isTrue();
        assertThat(brunoJsonResult.loadedCount).isEqualTo(2);
        assertThat(brunoJsonTarget).containsEntry("host", "https://api.example.test");
        assertThat(brunoJsonTarget).containsEntry("blank", "");
        assertThat(brunoJsonTarget).doesNotContainKey("disabled_token");
    }
}
