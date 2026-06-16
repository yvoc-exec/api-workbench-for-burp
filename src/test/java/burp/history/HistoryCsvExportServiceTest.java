package burp.history;

import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryCsvExportServiceTest {

    @Test
    void exportProducesSummaryRowWithTemplatedUrl() {
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "csv-entry", java.time.Instant.parse("2026-06-15T01:30:00Z"));
        entry.requestName = "Login, Primary";

        HistoryCsvExportService service = new HistoryCsvExportService();
        String csv = service.export(List.of(entry));

        assertThat(csv).contains("Time,Source,Attempt,Collection,Folder,Request,Method,URL Template,Status,Duration,Size,Environment,Result,Error");
        assertThat(csv).contains("\"Login, Primary\"");
        assertThat(csv).contains("{{base_url}}/login");
    }
}
