package burp.ui;

import burp.history.HistoryEntry;
import burp.history.HistoryResult;
import burp.history.HistorySource;
import burp.models.ApiCollection;
import burp.models.RedirectHop;
import burp.models.RedirectTerminationReason;
import burp.models.RunnerResult;
import burp.scripts.ScriptFlowControl;
import burp.scripts.ScriptLogEntry;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerHistoryCaptureTest {

    @Test
    void runnerRetriesCreateSeparateHistoryRows() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        bundle.panel.restoreWorkspaceState(burp.models.WorkspaceState.fromCollections(List.of(collection)));

        RunnerResult attemptOne = HistoryTestFixtures.sampleRunnerResult(1, 3, false, 500, "Missing variable: base_url");
        RunnerResult attemptTwo = HistoryTestFixtures.sampleRunnerResult(2, 3, true, 200, null);
        attemptOne.redirectsEnabled = true;
        attemptOne.initialResolvedUrl = "https://api.example.test/login";
        attemptOne.finalResolvedUrl = "https://api.example.test/next";
        attemptOne.redirectTerminationReason = RedirectTerminationReason.FINAL_RESPONSE;
        attemptOne.redirectHops = new java.util.ArrayList<>(List.of(redirectHop(1, "https://api.example.test/login", "https://api.example.test/next", true, null)));
        attemptTwo.redirectsEnabled = true;
        attemptTwo.initialResolvedUrl = "https://api.example.test/login";
        attemptTwo.finalResolvedUrl = "https://api.example.test/final";
        attemptTwo.redirectTerminationReason = RedirectTerminationReason.FINAL_RESPONSE;
        attemptTwo.redirectHops = new java.util.ArrayList<>(List.of(
                redirectHop(1, "https://api.example.test/login", "https://api.example.test/next", true, null),
                redirectHop(2, "https://api.example.test/next", "https://api.example.test/final", true, null)
        ));

        ImporterPanelTestSupport.invokeVoid(
                bundle.panel,
                "recordRunnerHistoryAttempt",
                new Class<?>[]{RunnerResult.class},
                attemptOne);
        ImporterPanelTestSupport.invokeVoid(
                bundle.panel,
                "recordRunnerHistoryAttempt",
                new Class<?>[]{RunnerResult.class},
                attemptTwo);

        ImporterPanelTestSupport.awaitCondition(
                () -> bundle.panel.getWorkspaceStateSnapshot().historyEntries.size() == 2,
                Duration.ofSeconds(3));

        List<HistoryEntry> entries = bundle.panel.getWorkspaceStateSnapshot().historyEntries;
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).attemptNumber).isEqualTo(2);
        assertThat(entries.get(0).totalAttempts).isEqualTo(3);
        assertThat(entries.get(0).source).isEqualTo(HistorySource.RUNNER);
        assertThat(entries.get(0).result).isEqualTo(HistoryResult.SUCCESS);
        assertThat(entries.get(0).redirectHops).hasSize(2);
        assertThat(entries.get(0).redirectHops.get(0).targetUrl).isEqualTo("https://api.example.test/next");
        assertThat(entries.get(1).attemptNumber).isEqualTo(1);
        assertThat(entries.get(1).result).isEqualTo(HistoryResult.ASSERTION_FAILURE);
        assertThat(entries.get(1).errorMessage).contains("Missing variable");
        assertThat(entries.get(1).redirectHops).hasSize(1);
        assertThat(entries.get(1).redirectHops.get(0).targetUrl).isEqualTo("https://api.example.test/next");

        attemptOne.redirectHops.get(0).targetUrl = "https://mutated.example.test/next";
        attemptTwo.redirectHops.get(1).targetUrl = "https://mutated.example.test/final";
        assertThat(entries.get(0).redirectHops.get(0).targetUrl).isEqualTo("https://api.example.test/next");
        assertThat(entries.get(1).redirectHops.get(0).targetUrl).isEqualTo("https://api.example.test/next");
    }

    @Test
    void runnerHistoryCapturesScriptOutputAndFlowControlLabels() {
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        RunnerResult skipped = HistoryTestFixtures.sampleRunnerResult(1, 1, true, 0, null);
        skipped.scriptFlowControl = ScriptFlowControl.SKIP_REQUEST;
        skipped.scriptFlowMessage = "skipRequest";
        skipped.responseSize = 0;
        skipped.responseBodyLength = 0;
        skipped.responseBody = null;
        skipped.responseHeaders = null;
        skipped.scriptLogs.add(new ScriptLogEntry("info", "skip log", "script-1", "skip"));
        skipped.scriptWarnings.add("skip warning");
        skipped.scriptErrors.add("skip error");

        HistoryEntry skippedEntry = HistoryEntry.fromRunnerAttempt(collection, HistoryTestFixtures.sampleRequest(), HistoryTestFixtures.sampleEnvironment(), skipped);
        assertThat(skippedEntry.result).isEqualTo(HistoryResult.SKIPPED);
        assertThat(skippedEntry.resultDisplayName()).isEqualTo("Skipped by Script");
        assertThat(skippedEntry.scriptLogs).hasSize(1);
        assertThat(skippedEntry.scriptWarnings).contains("skip warning");
        assertThat(skippedEntry.scriptErrors).contains("skip error");

        RunnerResult stopped = HistoryTestFixtures.sampleRunnerResult(1, 1, true, 0, null);
        stopped.scriptFlowControl = ScriptFlowControl.STOP_RUN;
        stopped.scriptFlowMessage = "stopExecution";
        stopped.responseSize = 0;
        stopped.responseBodyLength = 0;
        stopped.responseBody = null;
        stopped.responseHeaders = null;
        stopped.scriptLogs.add(new ScriptLogEntry("error", "stop log", "script-2", "stop"));

        HistoryEntry stoppedEntry = HistoryEntry.fromRunnerAttempt(collection, HistoryTestFixtures.sampleRequest(), HistoryTestFixtures.sampleEnvironment(), stopped);
        assertThat(stoppedEntry.result).isEqualTo(HistoryResult.STOPPED);
        assertThat(stoppedEntry.resultDisplayName()).isEqualTo("Stopped by Script");
        assertThat(stoppedEntry.scriptLogs).hasSize(1);
        assertThat(stoppedEntry.scriptLogs.get(0).level).isEqualTo("error");
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
