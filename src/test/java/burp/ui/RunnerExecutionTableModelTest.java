package burp.ui;

import burp.history.HistoryEntry;
import burp.models.RedirectHop;
import burp.models.RedirectTerminationReason;
import burp.models.RunnerResult;
import burp.models.RunnerTimelineRow;
import burp.scripts.ScriptFlowControl;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerExecutionTableModelTest {

    @Test
    void exposesExpectedColumnsAndStringClasses() {
        RunnerExecutionTableModel model = new RunnerExecutionTableModel();

        assertThat(model.getColumnCount()).isEqualTo(12);
        assertThat(model.getColumnName(0)).isEqualTo("#");
        assertThat(model.getColumnName(1)).isEqualTo("Time");
        assertThat(model.getColumnName(2)).isEqualTo("Type");
        assertThat(model.getColumnName(3)).isEqualTo("State");
        assertThat(model.getColumnName(4)).isEqualTo("Request");
        assertThat(model.getColumnName(5)).isEqualTo("Source");
        assertThat(model.getColumnName(6)).isEqualTo("Method");
        assertThat(model.getColumnName(7)).isEqualTo("Status");
        assertThat(model.getColumnName(8)).isEqualTo("Result");
        assertThat(model.getColumnName(9)).isEqualTo("Duration");
        assertThat(model.getColumnName(10)).isEqualTo("Flow");
        assertThat(model.getColumnName(11)).isEqualTo("Message");
        assertThat(model.getColumnClass(0)).isEqualTo(String.class);
        assertThat(model.getRowCount()).isZero();
    }

    @Test
    void addResultMapsRunnerResultIntoRequestRowAndDetailEntry() {
        RunnerExecutionTableModel model = new RunnerExecutionTableModel();
        RunnerResult result = runnerResult("req-1", "Login", true, 201, null);
        result.collectionName = "APIM";
        result.folderPath = "Auth";
        result.requestUrl = "https://api.example.test/login";
        result.responseTimeMs = 42L;
        result.scriptEngineName = "GraalJS";

        model.addResult(result);

        RunnerExecutionTableModel.Entry entry = model.getEntryAt(0);
        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getRequestResultCount()).isEqualTo(1);
        assertThat(model.getRequestResults()).containsExactly(result);
        assertThat(model.getResultAt(0)).isSameAs(result);
        assertThat(model.getValueAt(0, 0)).isEqualTo(1);
        assertThat(model.getValueAt(0, 2)).isEqualTo("REQUEST_COMPLETED");
        assertThat(model.getValueAt(0, 3)).isEqualTo("COMPLETED");
        assertThat(model.getValueAt(0, 4)).isEqualTo("Login");
        assertThat(model.getValueAt(0, 5)).isEqualTo("APIM");
        assertThat(model.getValueAt(0, 6)).isEqualTo("GET");
        assertThat(model.getValueAt(0, 7)).isEqualTo("201");
        assertThat(model.getValueAt(0, 8)).isEqualTo("OK 201");
        assertThat(model.getValueAt(0, 9)).isEqualTo("42 ms");
        assertThat(model.getValueAt(0, 10)).isEqualTo("CONTINUE");
        assertThat(model.getValueAt(0, 11)).isEqualTo("201");

        HistoryEntry detailEntry = entry.detailEntry;
        assertThat(detailEntry).isNotNull();
        assertThat(detailEntry.requestName).isEqualTo("Login");
        assertThat(detailEntry.requestId).isEqualTo("req-1");
        assertThat(detailEntry.collectionName).isEqualTo("APIM");
        assertThat(detailEntry.folderPath).isEqualTo("Auth");
        assertThat(detailEntry.finalResolvedUrl).isEqualTo("https://api.example.test/login");
        assertThat(entry.requestId).isEqualTo("req-1");
        assertThat(entry.collectionName).isEqualTo("APIM");
    }

    @Test
    void eventRowsDoNotPolluteRequestResultsAndPreserveExplicitSequence() {
        RunnerExecutionTableModel model = new RunnerExecutionTableModel();
        RunnerTimelineRow timelineRow = new RunnerTimelineRow();
        timelineRow.requestName = "Runner Event";

        RunnerExecutionTableModel.Entry event = new RunnerExecutionTableModel.Entry(
                7,
                Instant.parse("2026-06-19T01:02:03Z"),
                "RUNNER_EVENT",
                "PAUSED",
                "Runner Event",
                "APIM",
                "",
                "",
                "",
                "",
                "",
                "Paused after current request",
                null,
                null,
                timelineRow,
                null,
                "APIM"
        );

        model.addEntry(event);
        model.addResult(runnerResult("dup-1", "Duplicate", false, 0, "boom"));
        model.addResult(runnerResult("dup-2", "Duplicate", true, 200, null));

        assertThat(model.getRowCount()).isEqualTo(3);
        assertThat(model.getRequestResultCount()).isEqualTo(2);
        assertThat(model.getRequestResults())
                .extracting(result -> result.requestId)
                .containsExactly("dup-1", "dup-2");
        assertThat(model.getValueAt(0, 0)).isEqualTo(7);
        assertThat(model.getValueAt(0, 2)).isEqualTo("RUNNER_EVENT");
        assertThat(model.getValueAt(0, 3)).isEqualTo("PAUSED");
        assertThat(model.getValueAt(0, 4)).isEqualTo("Runner Event");
        assertThat(model.getValueAt(0, 11)).isEqualTo("Paused after current request");
        assertThat(model.getEntryAt(1).requestId).isEqualTo("dup-1");
        assertThat(model.getEntryAt(2).requestId).isEqualTo("dup-2");

        model.clear();
        assertThat(model.getRowCount()).isZero();
        assertThat(model.getResults()).isEmpty();
    }

    @Test
    void redirectChildRowsStayNestedAndDoNotLeakSecrets() {
        RunnerExecutionTableModel model = new RunnerExecutionTableModel();
        RunnerResult result = runnerResult("req-1", "Login", true, 200, null);
        result.collectionName = "APIM";
        result.folderPath = "Auth";
        result.requestUrl = "https://api.example.test/login";
        result.initialResolvedUrl = "https://api.example.test/login";
        result.finalResolvedUrl = "https://api.example.test/final";
        result.redirectsEnabled = true;
        result.redirectTerminationReason = RedirectTerminationReason.FINAL_RESPONSE;
        result.redirectHops = List.of(
                redirectHop(1, "https://api.example.test/login", "https://api.example.test/next", true, null),
                redirectHop(2, "https://api.example.test/next", "https://api.example.test/final", false, "blocked")
        );

        RunnerExecutionTableModel.Entry requestRow = model.addEntry(RunnerExecutionTableModel.fromRequestResult(result));
        RunnerExecutionTableModel.Entry followedRow = model.addEntry(RunnerExecutionTableModel.fromRedirectHop(result, result.redirectHops.get(0)));
        RunnerExecutionTableModel.Entry blockedRow = model.addEntry(RunnerExecutionTableModel.fromRedirectHop(result, result.redirectHops.get(1)));

        assertThat(model.getRowCount()).isEqualTo(3);
        assertThat(model.getRequestResultCount()).isEqualTo(1);
        assertThat(model.getResults()).containsExactly(result);
        assertThat(requestRow.requestResult).isSameAs(result);
        assertThat(followedRow.requestResult).isNull();
        assertThat(blockedRow.requestResult).isNull();
        assertThat(followedRow.type).isEqualTo("REDIRECT_HOP");
        assertThat(blockedRow.type).isEqualTo("REDIRECT_BLOCKED");
        assertThat(followedRow.state).isEqualTo("FOLLOWED");
        assertThat(blockedRow.state).isEqualTo("BLOCKED");
        assertThat(followedRow.message).contains("forwarded=[Authorization]").contains("stripped=[Proxy-Authorization]");
        assertThat(blockedRow.message).contains("blocked").contains("Authorization").doesNotContain("Bearer secret");
        assertThat(followedRow.detailEntry.redirectHops).hasSize(1);
        assertThat(requestRow.detailEntry.redirectHops).hasSize(2);
        assertThat(requestRow.detailEntry.redirectHops.get(0)).isNotSameAs(result.redirectHops.get(0));
        assertThat(requestRow.detailEntry.toMetadataText()).contains("Redirect Hop Evidence:");
        assertThat(requestRow.detailEntry.toMetadataText()).contains("Authorization");
        assertThat(requestRow.detailEntry.toMetadataText()).contains("Proxy-Authorization");
        assertThat(requestRow.detailEntry.toMetadataText()).doesNotContain("Bearer secret");
        assertThat(requestRow.detailEntry.toMetadataText()).doesNotContain("session=abc");
    }

    private static RunnerResult runnerResult(String requestId, String requestName, boolean success, int statusCode, String errorMessage) {
        RunnerResult result = new RunnerResult();
        result.requestId = requestId;
        result.requestName = requestName;
        result.collectionName = "APIM";
        result.method = "GET";
        result.success = success;
        result.statusCode = statusCode;
        result.errorMessage = errorMessage;
        result.scriptFlowControl = ScriptFlowControl.CONTINUE;
        result.assertions = List.of();
        return result;
    }

    private static RedirectHop redirectHop(int hopNumber, String sourceUrl, String targetUrl, boolean followed, String failureReason) {
        RedirectHop hop = new RedirectHop();
        hop.hopNumber = hopNumber;
        hop.sourceUrl = sourceUrl;
        hop.targetUrl = targetUrl;
        hop.location = targetUrl != null ? targetUrl : "";
        hop.followed = followed;
        hop.failureReason = failureReason;
        hop.statusCode = followed ? 302 : 307;
        hop.rawRequestBytes = (sourceUrl + "\r\n").getBytes(StandardCharsets.UTF_8);
        hop.responseBody = (targetUrl != null ? targetUrl : "").getBytes(StandardCharsets.UTF_8);
        hop.forwardedSensitiveHeaderNames = new java.util.ArrayList<>(List.of("Authorization"));
        hop.strippedSensitiveHeaderNames = new java.util.ArrayList<>(List.of("Proxy-Authorization"));
        return hop;
    }
}
