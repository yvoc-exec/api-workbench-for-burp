package burp.exporter;

import burp.models.EnvironmentProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportWriteFailureTest {

    @TempDir
    Path tempDir;

    @Test
    void exportFailureDoesNotOverwriteExistingDirectoryState() throws Exception {
        EnvironmentProfile profile = ExportTestFixtures.activeEnvironment();
        EnvironmentExportService service = new EnvironmentExportService();
        Path outputDir = tempDir.resolve("blocked-export");
        Files.createDirectories(outputDir);
        Path marker = outputDir.resolve("existing.txt");
        Files.writeString(marker, "keep-me");

        assertThatThrownBy(() -> service.exportEnvironment(
                profile,
                new EnvironmentExportOptions(EnvironmentExportFormat.API_WORKBENCH_JSON, outputDir)))
                .isInstanceOf(ExportException.class);

        assertThat(Files.readString(marker)).isEqualTo("keep-me");
        try (var stream = Files.list(outputDir)) {
            assertThat(stream.count()).isEqualTo(1);
        }
    }
}
