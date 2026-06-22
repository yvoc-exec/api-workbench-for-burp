package burp.exporter;

import burp.models.EnvironmentProfile;
import burp.utils.EnvironmentImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentExportRoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    void postmanAndBrunoEnvironmentExportsRoundTripThroughImporter() throws Exception {
        EnvironmentProfile profile = ExportTestFixtures.activeEnvironment();
        EnvironmentExportService service = new EnvironmentExportService();

        Path postman = tempDir.resolve("uat.postman_environment.json");
        Path bruno = tempDir.resolve("uat.bru");
        service.exportEnvironment(profile, new EnvironmentExportOptions(EnvironmentExportFormat.POSTMAN_JSON, postman));
        service.exportEnvironment(profile, new EnvironmentExportOptions(EnvironmentExportFormat.BRUNO_BRU, bruno));

        List<EnvironmentProfile> postmanImported = EnvironmentImportService.importEnvironment(postman.toFile());
        List<EnvironmentProfile> brunoImported = EnvironmentImportService.importEnvironment(bruno.toFile());

        assertThat(postmanImported).singleElement().satisfies(imported -> {
            assertThat(imported.name).isEqualTo("UAT");
            assertThat(imported.sourceFormat).isEqualTo("postman");
            assertThat(imported.variables).containsEntry("base_url", "https://api.example.test");
            assertThat(imported.variables).containsEntry("missing_password", "resolved-password");
            assertThat(imported.variables).doesNotContainKey("");
        });
        assertThat(brunoImported).singleElement().satisfies(imported -> {
            assertThat(imported.name).isEqualTo("uat");
            assertThat(imported.sourceFormat).isEqualTo("bruno");
            assertThat(imported.variables).containsEntry("base_url", "https://api.example.test");
            assertThat(imported.variables).containsEntry("missing_password", "resolved-password");
            assertThat(imported.variables).doesNotContainKey("");
        });
    }

    @Test
    void environmentExportRejectsDirectoryDestinationWithoutLeavingFilesBehind() throws Exception {
        EnvironmentProfile profile = ExportTestFixtures.activeEnvironment();
        Path directoryTarget = Files.createDirectory(tempDir.resolve("environment-export-dir"));

        assertThatThrownBy(() -> new EnvironmentExportService().exportEnvironment(
                profile,
                new EnvironmentExportOptions(EnvironmentExportFormat.POSTMAN_JSON, directoryTarget)
        ))
                .isInstanceOf(ExportException.class)
                .hasMessageContaining("Environment export failed");

        assertThat(directoryTarget).isDirectory();
        try (Stream<Path> children = Files.list(directoryTarget)) {
            assertThat(children).isEmpty();
        }
    }
}
