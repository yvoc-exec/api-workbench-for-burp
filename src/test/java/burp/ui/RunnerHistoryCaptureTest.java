package burp.ui;

import burp.history.HistoryEntry;
import burp.history.HistoryResult;
import burp.history.HistorySource;
import burp.models.ApiCollection;
import burp.models.RunnerResult;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;

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
        assertThat(entries.get(1).attemptNumber).isEqualTo(1);
        assertThat(entries.get(1).result).isEqualTo(HistoryResult.ASSERTION_FAILURE);
        assertThat(entries.get(1).errorMessage).contains("Missing variable");
    }
}
