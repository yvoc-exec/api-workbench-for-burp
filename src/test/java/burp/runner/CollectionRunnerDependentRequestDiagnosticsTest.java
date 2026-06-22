package burp.runner;

import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;
import burp.history.HistoryEntry;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerResult;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.testsupport.RunnerScriptTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionRunnerDependentRequestDiagnosticsTest {

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        DiagnosticStore diagnostics = DiagnosticStore.getInstance();
        diagnostics.setCaptureEnabled(false);
        diagnostics.clear();
    }

    @Test
    void dependentRequestExecutionEmitsRunnerAndHistoryDiagnostics() {
        DiagnosticStore diagnostics = DiagnosticStore.getInstance();
        diagnostics.setCaptureEnabled(true);
        diagnostics.clear();

        AtomicInteger sendCount = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")));

        ApiRequest child = RunnerScriptTestFixtures.request(
                "diag-child",
                "Diag Child",
                2,
                "Diagnostics Collection",
                "https://api.example.test/diag-child",
                """
                        console.log('diag child');
                        """,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiRequest parent = RunnerScriptTestFixtures.request(
                "diag-parent",
                "Diag Parent",
                1,
                "Diagnostics Collection",
                "https://api.example.test/diag-parent",
                """
                        pm.execution.runRequest('Diag Child');
                        """,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiCollection collection = RunnerScriptTestFixtures.collection("Diagnostics Collection", parent, child);

        runner.runCollections(List.of(collection), List.of(parent));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(sendCount.get()).isEqualTo(2);
        assertThat(runner.getResults()).hasSize(2);
        assertThat(runner.getResults().get(0).dependentExecution).isTrue();
        assertThat(runner.getResults().get(0).triggeredByScript).isTrue();
        assertThat(runner.getResults().get(1).dependentRequestCount).isEqualTo(1);

        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Dev";
        environment.variables.put("base_url", "https://api.example.test");
        HistoryEntry history = HistoryEntry.fromRunnerAttempt(collection, child, environment, runner.getResults().get(0));
        assertThat(history.result).isEqualTo(burp.history.HistoryResult.SUCCESS);
        assertThat(history.requestSnapshot.hasRawRequestSent()).isTrue();
        assertThat(history.scriptLogs).anySatisfy(log -> assertThat(log.message).contains("diag child"));

        assertThat(diagnostics.snapshot())
                .filteredOn(event -> event.operation == DiagnosticOperation.SCRIPT_EXECUTION)
                .isNotEmpty();
        assertThat(diagnostics.snapshot())
                .filteredOn(event -> event.operation == DiagnosticOperation.HISTORY_CAPTURE)
                .anySatisfy(event -> {
                    assertThat(event.severity).isEqualTo(DiagnosticSeverity.INFO);
                    assertThat(event.message).contains("history captured");
                });
        assertThat(diagnostics.compactSummary()).contains("events=");
    }
}
