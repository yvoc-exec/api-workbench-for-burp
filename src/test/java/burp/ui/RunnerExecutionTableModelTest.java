package burp.ui;

import burp.history.HistoryEntry;
import burp.models.RedirectHop;
import burp.models.RunnerResult;
import burp.models.RunnerResultSummary;
import burp.models.RunnerTimelineRow;
import burp.scripts.ScriptFlowControl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerExecutionTableModelTest {
    @Test
    void preservesSixteenColumnsAndOwnsOnlySummaries() {
        RunnerExecutionTableModel model = new RunnerExecutionTableModel();
        RunnerResult result = runnerResult("req-1", "Login", true, 201, null);
        result.responseBody = "secret-response";
        result.rawRequestBytes = "secret-request".getBytes(StandardCharsets.UTF_8);
        result.responseTimeMs = 42L;

        model.addResult(result);

        assertThat(model.getColumnCount()).isEqualTo(16);
        assertThat(model.getColumnName(0)).isEqualTo("#");
        assertThat(model.getColumnName(15)).isEqualTo("Message");
        assertThat(model.getValueAt(0, 2)).isEqualTo("REQUEST_COMPLETED");
        assertThat(model.getValueAt(0, 4)).isEqualTo("Login");
        assertThat(model.getValueAt(0, 7)).isEqualTo("201");
        assertThat(model.getValueAt(0, 12)).isEqualTo("OK 201");
        assertThat(model.getValueAt(0, 13)).isEqualTo("42 ms");
        assertThat(model.getSummaryAt(0)).isInstanceOf(RunnerResultSummary.class);
        assertThat(model.getResultAt(0)).isNotSameAs(result);
        assertThat(model.getResultAt(0).responseBody).isNull();
        assertThat(model.getResultAt(0).rawRequestBytes).isNull();
        assertThat(RunnerExecutionTableModel.Entry.class.getDeclaredFields())
                .extracting(Field::getType)
                .doesNotContain(HistoryEntry.class, RunnerResult.class, RedirectHop.class, byte[].class);
    }

    @Test
    void eventRowsDoNotPolluteRequestSummaries() {
        RunnerExecutionTableModel model = new RunnerExecutionTableModel();
        RunnerTimelineRow timeline = new RunnerTimelineRow();
        model.addEntry(new RunnerExecutionTableModel.Entry(
                7, Instant.parse("2026-06-19T01:02:03Z"), "RUNNER_EVENT", "PAUSED",
                "Runner Event", "APIM", "", "", "", "", "",
                "Paused", null, null, timeline, null, "APIM"));
        model.addResult(runnerResult("one", "One", true, 200, null));

        assertThat(model.getRowCount()).isEqualTo(2);
        assertThat(model.getRequestResultCount()).isEqualTo(1);
        assertThat(model.getRequestSummaries()).extracting(RunnerResultSummary::requestId).containsExactly("one");
        assertThat(model.getResultAt(0)).isNull();
    }

    @Test
    void redirectRowsRetainOnlyParentHistoryIdHopIndexAndLightweightMetadata() {
        RunnerExecutionTableModel model = new RunnerExecutionTableModel();
        RunnerResult result = runnerResult("req-1", "Login", true, 200, null);
        result.historyEntryId = "history-1";
        result.redirectHops = List.of(redirectHop(1, true, null), redirectHop(2, false, "blocked"));
        RunnerResultSummary summary = result.toSummary();

        RunnerExecutionTableModel.Entry followed = model.addEntry(
                RunnerExecutionTableModel.fromRedirectSummary(summary, summary.redirectSummaries().get(0), 0));
        RunnerExecutionTableModel.Entry blocked = model.addEntry(
                RunnerExecutionTableModel.fromRedirectSummary(summary, summary.redirectSummaries().get(1), 1));

        assertThat(followed.historyEntryId).isEqualTo("history-1");
        assertThat(followed.redirectHopIndex).isZero();
        assertThat(followed.redirectSummary).isNotNull();
        assertThat(blocked.redirectHopIndex).isEqualTo(1);
        assertThat(followed.message).contains("forwarded=[Authorization]");
        assertThat(blocked.message).contains("blocked").doesNotContain("Bearer secret");
    }

    @Test
    void completedRowsAreBoundedAndClearResetsRemovalCount() {
        RunnerExecutionTableModel model = new RunnerExecutionTableModel(5_000);
        for (int i = 0; i < 5_001; i++) {
            model.addResult(runnerResult("req-" + i, "Request " + i, true, 200, null));
        }

        assertThat(model.getRowCount()).isEqualTo(5_000);
        assertThat(model.getRemovedCompletedRowCount()).isEqualTo(1);
        assertThat(model.getRequestSummaries()).extracting(RunnerResultSummary::requestId)
                .doesNotContain("req-0").contains("req-5000");

        model.clear();
        assertThat(model.getRowCount()).isZero();
        assertThat(model.getRemovedCompletedRowCount()).isZero();
    }

    @Test
    void activeStartRowsMayOverflowUntilTheirTerminalRowMakesCompactionSafe() {
        RunnerExecutionTableModel model = new RunnerExecutionTableModel(100);
        for (int i = 0; i < 101; i++) {
            RunnerResult active = runnerResult("active-" + i, "Active " + i, true, 0, null);
            model.addEntry(startEntry(active));
        }
        assertThat(model.getRowCount()).isEqualTo(101);
        assertThat(model.getRemovedCompletedRowCount()).isZero();

        model.addResult(runnerResult("active-0", "Active 0", true, 200, null));

        assertThat(model.getRowCount()).isEqualTo(100);
        assertThat(model.getRemovedCompletedRowCount()).isEqualTo(2);
        assertThat(model.getEntries()).filteredOn(row -> "REQUEST_STARTED".equals(row.type)).hasSize(100);
    }

    private static RunnerExecutionTableModel.Entry startEntry(RunnerResult result) {
        return new RunnerExecutionTableModel.Entry(
                0, Instant.now(), "REQUEST_STARTED", "RUNNING", result.requestName,
                result.collectionName, result.method, "", "Starting", "", "", "Request started",
                null, result, null, result.requestId, result.collectionName);
    }

    private static RunnerResult runnerResult(String id, String name, boolean success, int status, String error) {
        RunnerResult result = new RunnerResult();
        result.requestId = id;
        result.requestName = name;
        result.collectionName = "APIM";
        result.method = "GET";
        result.success = success;
        result.statusCode = status;
        result.errorMessage = error;
        result.scriptFlowControl = ScriptFlowControl.CONTINUE;
        return result;
    }

    private static RedirectHop redirectHop(int number, boolean followed, String failure) {
        RedirectHop hop = new RedirectHop();
        hop.hopNumber = number;
        hop.sourceMethod = "GET";
        hop.sourceUrl = "https://api.example.test/" + number;
        hop.targetUrl = "https://api.example.test/" + (number + 1);
        hop.statusCode = 302;
        hop.followed = followed;
        hop.failureReason = failure;
        hop.rawRequestBytes = "Bearer secret".getBytes(StandardCharsets.UTF_8);
        hop.responseBody = "session=abc".getBytes(StandardCharsets.UTF_8);
        hop.forwardedSensitiveHeaderNames = new java.util.ArrayList<>(List.of("Authorization"));
        hop.strippedSensitiveHeaderNames = new java.util.ArrayList<>(List.of("Proxy-Authorization"));
        return hop;
    }
}
