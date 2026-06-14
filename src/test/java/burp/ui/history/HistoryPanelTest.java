package burp.ui.history;

import burp.history.*;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryPanelTest {

    @Test
    void tableLayoutRemainsVisibleWithZeroEntries() {
        HistoryPanel panel = new HistoryPanel(new HistoryStore(), new HistoryExportService(), new HistoryDiffService(), new RecordingNotifier());

        assertThat(panel.getHistoryTable().getRowCount()).isZero();
        assertThat(panel.getHistoryTable().getColumnCount()).isEqualTo(13);
        assertThat(panel.getHistoryTable().getTableHeader()).isNotNull();
        assertThat(panel.getHistoryTable().getColumnName(0)).isEqualTo("Time");
        assertThat(panel.getHistoryTable().getColumnName(7)).isEqualTo("URL Template");
        assertThat(panel.getHistoryTable().getColumnName(12)).isEqualTo("Result");
        assertThat(panel.getTableScrollPane().getMinimumSize().height).isGreaterThanOrEqualTo(220);
        assertThat(panel.getTableScrollPane().getPreferredSize().height).isGreaterThanOrEqualTo(280);
        assertThat(panel.getFilterScrollPane().getPreferredSize().height).isLessThanOrEqualTo(155);
        assertThat(panel.getFilterScrollPane().getMinimumSize().height).isLessThanOrEqualTo(120);
    }

    @Test
    void panelForwardsActionsAndPreservesSelectionWhenFiltering() {
        HistoryStore store = new HistoryStore();
        HistoryEntry first = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "first", Instant.parse("2026-06-15T01:00:00Z"));
        HistoryEntry second = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleRunnerEntry(),
                "second", Instant.parse("2026-06-15T01:01:00Z"));
        store.addAll(List.of(first, second));

        RecordingNotifier notifier = new RecordingNotifier();
        HistoryPanel panel = new HistoryPanel(store, new HistoryExportService(), new HistoryDiffService(), notifier);
        AtomicReference<HistoryEntry> load = new AtomicReference<>();
        AtomicReference<HistoryEntry> replay = new AtomicReference<>();
        AtomicReference<HistoryEntry> repeater = new AtomicReference<>();
        AtomicInteger changeCount = new AtomicInteger();
        panel.setLoadInWorkbenchAction(load::set);
        panel.setReplayFromHistoryAction(replay::set);
        panel.setSendToRepeaterAction(repeater::set);
        panel.setWorkspaceChangeListener(changeCount::incrementAndGet);

        panel.getHistoryTable().setRowSelectionInterval(1, 1);
        ImporterPanelTestSupport.awaitEdt();
        assertThat(panel.getSelectedEntry().id).isEqualTo("first");

        panel.loadSelectedInWorkbench();
        panel.replaySelectedFromHistory();
        panel.sendSelectedToRepeater();

        assertThat(load.get().id).isEqualTo("first");
        assertThat(replay.get().id).isEqualTo("first");
        assertThat(repeater.get().id).isEqualTo("first");

        HistoryFilterCriteria criteria = new HistoryFilterCriteria();
        criteria.source = HistorySource.RUNNER;
        panel.getFilterPanel().setCriteria(criteria);
        panel.refreshFromStore("second");
        assertThat(panel.getHistoryTable().getRowCount()).isEqualTo(1);
        assertThat(panel.getHistoryTable().getValueAt(0, 5)).isEqualTo(HistoryTestFixtures.REQUEST_NAME);
        panel.getHistoryTable().setRowSelectionInterval(0, 0);

        panel.deleteSelectedEntries();
        assertThat(store.snapshot()).extracting(entry -> entry.id).containsExactly("first");

        panel.clearHistory();
        assertThat(store.isEmpty()).isTrue();
        assertThat(notifier.confirmClearCount).isGreaterThan(0);
        assertThat(changeCount.get()).isGreaterThan(0);
    }

    private static final class RecordingNotifier extends HistoryLoadResultNotifier {
        int confirmClearCount;

        @Override
        public boolean confirmClearHistory(java.awt.Component parent) {
            confirmClearCount++;
            return true;
        }

        @Override
        public boolean confirmExportSensitiveData(java.awt.Component parent) {
            return true;
        }

        @Override
        public void showError(java.awt.Component parent, String message) {
        }

        @Override
        public void showInfo(java.awt.Component parent, String message) {
        }

        @Override
        public boolean confirmReplaceCurrentRequest(java.awt.Component parent) {
            return true;
        }

        @Override
        public void showLoadedIntoOriginalRequest(java.awt.Component parent, HistoryEntry entry) {
        }

        @Override
        public void showLoadedUnderHistoryReplays(java.awt.Component parent, String requestName) {
        }
    }
}
