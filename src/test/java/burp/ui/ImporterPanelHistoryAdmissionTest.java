package burp.ui;

import burp.history.HistoryAdmissionResult;
import burp.history.HistoryEntry;
import burp.history.HistoryRetentionPolicy;
import burp.history.HistoryRetentionStats;
import burp.history.HistorySource;
import burp.models.RunnerResult;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import burp.ui.history.HistoryLoadResultNotifier;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelHistoryAdmissionTest {

    @Test
    void workbenchCaptureAcceptedAssignsStoredEvidenceAndNotifiesOnce() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        AtomicInteger workspaceChanges = new AtomicInteger();
        bundle.panel.setWorkspaceChangeListener(workspaceChanges::incrementAndGet);
        HistoryEntry entry = HistoryTestFixtures.sampleWorkbenchEntry();
        entry.id = "accepted-workbench";
        entry.source = HistorySource.WORKBENCH;

        HistoryEntry stored = ImporterPanelTestSupport.invoke(
                bundle.panel,
                "recordHistoryEntry",
                new Class<?>[]{HistoryEntry.class},
                entry);
        ImporterPanelTestSupport.awaitEdt();

        assertThat(stored).isNotNull();
        assertThat(stored.id).isEqualTo("accepted-workbench");
        assertThat(bundle.panel.getHistoryStoreForTests().getById("accepted-workbench")).isNotNull();
        assertThat(bundle.panel.getHistoryPanelForTests().getHistoryTable().getRowCount()).isEqualTo(1);
        assertThat(bundle.panel.getWorkspaceStateSnapshotFromModel().historyRetentionPolicyVersion)
                .isEqualTo(HistoryRetentionPolicy.CURRENT_POLICY_VERSION);
        assertThat(bundle.panel.getWorkspaceStateSnapshotFromModel().historyEntries)
                .extracting(history -> history.id)
                .containsExactly("accepted-workbench");
        assertThat(workspaceChanges).hasValue(1);
    }

    @Test
    void workbenchCaptureRejectionIsNonBlockingSanitizedAndDoesNotMutateWorkspace() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        bundle.panel.getHistoryStoreForTests().setRetentionPolicy(
                new HistoryRetentionPolicy(10, 1L, 1L, 1L, true));
        AtomicInteger workspaceChanges = new AtomicInteger();
        bundle.panel.setWorkspaceChangeListener(workspaceChanges::incrementAndGet);
        RecordingNotifier notifier = new RecordingNotifier();
        bundle.panel.setHistoryLoadResultNotifierForTests(notifier);
        HistoryEntry entry = HistoryTestFixtures.sampleWorkbenchEntry();
        entry.id = "rejected-workbench";
        entry.source = HistorySource.WORKBENCH;
        entry.analystNotes = "WAVE3_ANALYST_SECRET";
        entry.tags.add("WAVE3_TAG_SECRET");
        entry.requestSnapshot.urlTemplate = "https://example.test/WAVE3_URL_SECRET";
        entry.responseSnapshot.body = "WAVE3_BODY_SECRET".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        HistoryEntry stored = ImporterPanelTestSupport.invoke(
                bundle.panel,
                "recordHistoryEntry",
                new Class<?>[]{HistoryEntry.class},
                entry);
        ImporterPanelTestSupport.awaitEdt();

        assertThat(stored).isNull();
        assertThat(bundle.panel.getHistoryStoreForTests().snapshot()).isEmpty();
        assertThat(workspaceChanges).hasValue(0);
        assertThat(notifier.modalCount).isZero();
        assertThat(bundle.panel.getImportLogAreaForTests().getText())
                .contains("Automatic History capture was not retained")
                .contains("ENTRY_EXCEEDS_POLICY")
                .doesNotContain("WAVE3_ANALYST_SECRET", "WAVE3_TAG_SECRET", "WAVE3_URL_SECRET", "WAVE3_BODY_SECRET");
    }

    @Test
    void runnerCaptureRejectionPreservesAttemptMetadataWithoutHistoryIdOrModalStorm() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        bundle.panel.getHistoryStoreForTests().setRetentionPolicy(
                new HistoryRetentionPolicy(10, 1L, 1L, 1L, true));
        RecordingNotifier notifier = new RecordingNotifier();
        bundle.panel.setHistoryLoadResultNotifierForTests(notifier);
        RunnerResult result = new RunnerResult();
        result.requestId = "runner-rejected";
        result.requestName = "WAVE3_REQUEST_SECRET";
        result.requestUrl = "https://example.test/WAVE3_RUNNER_URL_SECRET";
        result.method = "POST";
        result.requestBody = "WAVE3_RUNNER_BODY_SECRET";
        result.responseBody = "x".repeat(8_192);
        result.responseBodyLength = result.responseBody.length();
        result.responseSize = result.responseBody.length();
        result.statusCode = 503;
        result.success = false;
        result.attemptNumber = 2;
        result.totalAttempts = 3;
        result.retryReason = "status 503";

        HistoryEntry stored = ImporterPanelTestSupport.invoke(
                bundle.panel,
                "recordRunnerHistoryAttempt",
                new Class<?>[]{RunnerResult.class},
                result);
        HistoryEntry duplicateCallback = ImporterPanelTestSupport.invoke(
                bundle.panel,
                "recordRunnerHistoryAttempt",
                new Class<?>[]{RunnerResult.class},
                result);
        ImporterPanelTestSupport.awaitEdt();

        assertThat(stored).isNull();
        assertThat(duplicateCallback).isNull();
        assertThat(result.historyEntryId).isNull();
        assertThat(result.attemptNumber).isEqualTo(2);
        assertThat(result.totalAttempts).isEqualTo(3);
        assertThat(result.retryReason).isEqualTo("status 503");
        assertThat(bundle.panel.getHistoryStoreForTests().snapshot()).isEmpty();
        assertThat(notifier.modalCount).isZero();
        String runnerLog = bundle.panel.getRunnerLogAreaForTests().getText();
        assertThat(runnerLog)
                .contains("History capture")
                .doesNotContain("WAVE3_REQUEST_SECRET", "WAVE3_RUNNER_URL_SECRET", "WAVE3_RUNNER_BODY_SECRET");
        assertThat(countOccurrences(runnerLog, "Automatic History capture was not retained")).isEqualTo(1);
    }

    private static final class RecordingNotifier extends HistoryLoadResultNotifier {
        int modalCount;

        @Override
        public void showAddRejected(java.awt.Component parent,
                                    HistoryAdmissionResult result,
                                    HistoryRetentionStats stats) {
            modalCount++;
        }

        @Override
        public void showPinRejected(java.awt.Component parent,
                                    HistoryAdmissionResult result,
                                    HistoryRetentionStats stats) {
            modalCount++;
        }

        @Override
        public void showMetadataRejected(java.awt.Component parent,
                                         HistoryAdmissionResult result,
                                         HistoryRetentionStats stats) {
            modalCount++;
        }

        @Override
        public void showLegacyPayloadCompacted(java.awt.Component parent, HistoryRetentionStats stats) {
            modalCount++;
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while (text != null && needle != null && !needle.isEmpty()
                && (index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
