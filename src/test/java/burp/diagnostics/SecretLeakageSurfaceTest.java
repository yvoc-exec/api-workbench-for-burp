package burp.diagnostics;

import burp.history.HistoryEntry;
import burp.history.HistoryRequestSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SecretLeakageSurfaceTest {

    @AfterEach
    void tearDown() {
        DiagnosticStore store = DiagnosticStore.getInstance();
        store.setCaptureEnabled(false);
        store.clear();
    }

    @Test
    void rawHistoryEvidenceRemainsExactWhileDiagnosticsAreSanitized() {
        HistoryEntry entry = new HistoryEntry();
        entry.requestSnapshot = new HistoryRequestSnapshot();
        entry.requestSnapshot.rawRequestSentText = """
                POST /login HTTP/1.1
                Authorization: Bearer raw-secret

                """;
        entry.requestSnapshot.rawRequestSent = entry.requestSnapshot.rawRequestSentText.getBytes(StandardCharsets.UTF_8);
        assertThat(entry.requestSnapshot.hasRawRequestSent()).isTrue();
        assertThat(entry.requestSnapshot.preferredRawRequestText())
                .contains("POST /login HTTP/1.1")
                .contains("Authorization: Bearer raw-secret");

        DiagnosticStore store = DiagnosticStore.getInstance();
        store.setCaptureEnabled(true);
        store.record(DiagnosticEvent.of(
                DiagnosticOperation.REQUEST_BUILD,
                DiagnosticSeverity.ERROR,
                "surface-test",
                "Authorization: Bearer access-token"
        ).withDetails("client_secret=client-secret\nrefresh_token=refresh-secret"));

        String report = store.sanitizedReport(true);
        assertThat(report).contains("Diagnostics Events");
        assertThat(report).doesNotContain("access-token");
        assertThat(report).doesNotContain("client-secret");
        assertThat(report).doesNotContain("refresh-secret");
    }
}
