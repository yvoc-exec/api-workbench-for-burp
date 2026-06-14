package burp.history;

import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryExportServiceTest {

    @Test
    void exportCoordinatorDelegatesToConcreteExporters() throws Exception {
        HistoryExportService service = new HistoryExportService();
        List<HistoryEntry> entries = List.of(HistoryTestFixtures.sampleWorkbenchEntry());

        assertThat(service.exportJson(entries)).contains("\"entries\"");
        assertThat(service.exportCsv(entries)).contains("URL Template");
        assertThat(service.exportHar(entries)).contains("\"creator\"");

        Path tempDir = Files.createTempDirectory("history-export");
        Path json = tempDir.resolve("history.json");
        Path csv = tempDir.resolve("history.csv");
        Path har = tempDir.resolve("history.har");

        service.writeJson(entries, json);
        service.writeCsv(entries, csv);
        service.writeHar(entries, har);

        assertThat(Files.readString(json)).contains("{{base_url}}/login");
        assertThat(Files.readString(csv)).contains("{{base_url}}/login");
        assertThat(Files.readString(har)).contains("{{base_url}}/login");
    }
}
