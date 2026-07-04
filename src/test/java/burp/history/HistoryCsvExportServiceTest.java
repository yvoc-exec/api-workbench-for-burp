package burp.history;

import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryCsvExportServiceTest {

    @Test
    void csvIncludesSafeAnalystAndTruncationFields() {
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "csv-entry", java.time.Instant.parse("2026-06-15T01:30:00Z"));
        entry.requestName = "Login, Primary";
        entry.pinned = true;
        entry.analystNotes = "Reviewed";
        entry.tags = new LinkedHashSet<>(List.of("Auth", "Evidence"));

        HistoryCsvExportService service = new HistoryCsvExportService();
        String csv = service.export(List.of(entry));

        assertThat(csv).contains("Time,Source,Attempt,Collection,Folder,Request,Method,URL Template,Status,Duration,Size,Environment,Result,Pinned,Tags,Notes,Request Body Truncated,Request Original Body Bytes,Request Stored Body Bytes,Request Body SHA-256,Response Body Truncated,Response Original Body Bytes,Response Stored Body Bytes,Response Body SHA-256,Error");
        assertThat(csv).contains("\"Login, Primary\"");
        assertThat(csv).contains("{{base_url}}/login");
        assertThat(csv).contains("Yes");
        assertThat(csv).contains("Reviewed");
    }

    @Test
    void csvPreventsFormulaInjection() {
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "csv-formula", java.time.Instant.parse("2026-06-15T01:31:00Z"));
        entry.requestName = "=Login";
        entry.analystNotes = "+Reviewed";
        entry.tags = new LinkedHashSet<>(List.of("@Auth"));

        String csv = new HistoryCsvExportService().export(List.of(entry));

        assertThat(csv).contains("'=Login");
        assertThat(csv).contains("'+Reviewed");
        assertThat(csv).contains("'@Auth");
    }
}
