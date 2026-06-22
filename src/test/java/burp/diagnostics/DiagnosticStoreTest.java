package burp.diagnostics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosticStoreTest {

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        DiagnosticStore store = DiagnosticStore.getInstance();
        store.setCaptureEnabled(false);
        store.clear();
    }

    @Test
    void sanitizedReportSkipsDebugWhenNotIncluded() {
        DiagnosticStore store = DiagnosticStore.getInstance();
        store.clear();
        store.setCaptureEnabled(true);
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
        store.setCaptureEnabled(true);
        store.record(DiagnosticEvent.of(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, "test", "hello"));
        assertThat(store.snapshot()).isNotEmpty();
        store.clear();
        assertThat(store.snapshot()).isEmpty();
    }

    @Test
    void snapshotIsUnmodifiableAndReportsGroupedSanitizedEvents() {
        DiagnosticStore store = DiagnosticStore.getInstance();
        store.clear();
        store.setCaptureEnabled(true);

        store.record(null);
        store.record(DiagnosticEvent.of(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, "builder", "Request build complete")
                .withDetails("Authorization: Bearer secret-token\nclient_secret=abc123")
                .withAttribute("detail", "access_token=abc123"));
        store.record(DiagnosticEvent.of(DiagnosticOperation.ENVIRONMENT_SWITCH, DiagnosticSeverity.WARNING, "env", "Environment switch")
                .withDetails("refresh_token=refresh-123"));
        store.record(DiagnosticEvent.of(DiagnosticOperation.OAUTH2_TOKEN_FETCH, DiagnosticSeverity.ERROR, "oauth", null));
        store.record(DiagnosticEvent.of(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.DEBUG, "debug", "debug line"));

        assertThatThrownBy(() -> store.snapshot().add(DiagnosticEvent.of(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, "x", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.compactSummary()).contains("events=4").contains("warnings=1").contains("errors=1").contains("debug=1");

        String reportNoDebug = store.sanitizedReport(false);
        assertThat(reportNoDebug).contains("=== REQUEST_BUILD (1) ===");
        assertThat(reportNoDebug).contains("=== ENVIRONMENT_SWITCH (1) ===");
        assertThat(reportNoDebug).contains("=== OAUTH2_TOKEN_FETCH (1) ===");
        assertThat(reportNoDebug).doesNotContain("debug line");
        assertThat(reportNoDebug).contains("AUTHORIZATION: ***");
        assertThat(reportNoDebug).contains("client_secret=***");
        assertThat(reportNoDebug).contains("refresh_token=***");
        assertThat(reportNoDebug).contains("detail=access_token=***");

        String reportWithDebug = store.sanitizedReport(true);
        assertThat(reportWithDebug).contains("debug line");
        assertThat(reportWithDebug).contains("Summary: events=4 warnings=1 errors=1 debug=1");
    }
}
