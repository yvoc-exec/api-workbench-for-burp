package burp.ui;

import burp.history.HistoryDiffService;
import burp.history.HistoryEntry;
import burp.history.HistoryExportService;
import burp.history.HistoryStore;
import burp.testsupport.HistoryTestFixtures;
import burp.ui.history.HistoryLoadResultNotifier;
import burp.ui.history.HistoryPanel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HistorySendToRepeaterActionTest {

    @Test
    void sendToRepeaterActionForwardsSelectedHistoryEntry() {
        HistoryStore store = new HistoryStore();
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "repeater", Instant.parse("2026-06-15T01:50:00Z"));
        store.addEntry(entry);

        HistoryPanel panel = new HistoryPanel(store, new HistoryExportService(), new HistoryDiffService(), new NoOpNotifier());
        AtomicReference<HistoryEntry> forwarded = new AtomicReference<>();
        panel.setSendToRepeaterAction(forwarded::set);

        panel.getHistoryTable().setRowSelectionInterval(0, 0);
        panel.sendSelectedToRepeater();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().id).isEqualTo("repeater");
    }

    private static final class NoOpNotifier extends HistoryLoadResultNotifier {
        @Override public boolean confirmReplaceCurrentRequest(java.awt.Component parent) { return true; }
        @Override public void showLoadedIntoOriginalRequest(java.awt.Component parent, HistoryEntry entry) { }
        @Override public void showLoadedUnderHistoryReplays(java.awt.Component parent, String requestName) { }
        @Override public boolean confirmClearHistory(java.awt.Component parent) { return true; }
        @Override public boolean confirmExportSensitiveData(java.awt.Component parent) { return true; }
        @Override public void showError(java.awt.Component parent, String message) { }
        @Override public void showInfo(java.awt.Component parent, String message) { }
    }
}
