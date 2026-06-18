package burp.diagnostics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticStoreTest {

    @Test
    void sanitizedReportSkipsDebugWhenNotIncluded() {
        DiagnosticStore store = DiagnosticStore.getInstance();
        store.clear();
        store.record(DiagnosticEvent.of(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.DEBUG, "test", "debug detail")
                .withDetails("token=secret"));
        store.record(DiagnosticEvent.of(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, "test", "info detail")
                .withDetails("Authorization: Bearer secret"));

        String report = store.sanitizedReport(false);

        assertThat(report).doesNotContain("debug detail");
        assertThat(report).contains("info detail");
        assertThat(report).doesNotContain("secret");
        assertThat(report).contains("AUTHORIZATION: ***");
        store.clear();
    }

    @Test
    void clearRemovesRecordedEvents() {
        DiagnosticStore store = DiagnosticStore.getInstance();
        store.clear();
        store.record(DiagnosticEvent.of(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, "test", "hello"));
        assertThat(store.snapshot()).isNotEmpty();
        store.clear();
        assertThat(store.snapshot()).isEmpty();
    }
}
