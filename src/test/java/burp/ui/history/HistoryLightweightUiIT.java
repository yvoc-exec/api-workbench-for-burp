package burp.ui.history;

import burp.history.HistoryDiffService;
import burp.history.HistoryEntry;
import burp.history.HistoryExportService;
import burp.history.HistoryFilterCriteria;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistoryResponseSnapshot;
import burp.history.HistoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class HistoryLightweightUiIT {
    @Test
    void genuineFrameSupportsSelectionReplacementFilteringAndFiveClearCycles() throws Exception {
        assertThat(GraphicsEnvironment.isHeadless()).isFalse();
        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<HistoryPanel> panelRef = new AtomicReference<>();
        HistoryStore store = new HistoryStore();

        SwingUtilities.invokeAndWait(() -> {
            HistoryPanel panel = new HistoryPanel(
                    store, new HistoryExportService(), new HistoryDiffService(), new AlwaysConfirmNotifier());
            JFrame frame = new JFrame("History lightweight ownership");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setContentPane(panel);
            frame.setSize(1_200, 700);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            panelRef.set(panel);
            frameRef.set(frame);
        });

        try {
            HistoryPanel panel = panelRef.get();
            for (int cycle = 0; cycle < 5; cycle++) {
                int currentCycle = cycle;
                store.addAll(entries(currentCycle));
                SwingUtilities.invokeAndWait(panel::refreshFromStore);
                assertThat(panel.getHistoryTable().getRowCount()).isEqualTo(3);
                assertThat(panel.getDetailPanel().getCurrentEntry()).isNull();

                SwingUtilities.invokeAndWait(() -> panel.getHistoryTable().setRowSelectionInterval(0, 0));
                String firstId = panel.getDetailPanel().getCurrentEntry().id;
                SwingUtilities.invokeAndWait(() -> panel.getHistoryTable().setRowSelectionInterval(1, 1));
                assertThat(panel.getDetailPanel().getCurrentEntry()).isNotNull();
                assertThat(panel.getDetailPanel().getCurrentEntry().id).isNotEqualTo(firstId);

                HistoryFilterCriteria criteria = new HistoryFilterCriteria();
                criteria.requestName = "request-1";
                SwingUtilities.invokeAndWait(() -> {
                    panel.getFilterPanel().setCriteria(criteria);
                    panel.applyCurrentFilter();
                });
                assertThat(panel.getHistoryTable().getRowCount()).isOne();

                SwingUtilities.invokeAndWait(panel::clearHistory);
                assertThat(store.size()).isZero();
                assertThat(panel.getHistoryTable().getRowCount()).isZero();
                assertThat(panel.getSelectedIds()).isEmpty();
                assertThat(panel.getDetailPanel().getCurrentEntry()).isNull();

                SwingUtilities.invokeAndWait(() -> {
                    panel.getFilterPanel().clear();
                    panel.applyCurrentFilter();
                });
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> frameRef.get().dispose());
        }
    }

    private static List<HistoryEntry> entries(int cycle) {
        List<HistoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            HistoryEntry entry = new HistoryEntry();
            entry.id = "cycle-" + cycle + "-entry-" + i;
            entry.timestamp = Instant.parse("2026-08-04T00:00:00Z").plusSeconds(i);
            entry.collectionName = "collection";
            entry.requestName = "request-" + i;
            entry.requestSnapshot = new HistoryRequestSnapshot();
            entry.requestSnapshot.method = "GET";
            entry.requestSnapshot.urlTemplate = "https://api.example.test/" + i;
            entry.requestSnapshot.rawRequestSent = ("GET /" + i + " HTTP/1.1\r\nHost: api.example.test\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            entry.responseSnapshot = new HistoryResponseSnapshot();
            entry.responseSnapshot.statusCode = 200;
            entry.responseSnapshot.body = ("response-" + i).getBytes(StandardCharsets.UTF_8);
            entry.statusCode = 200;
            entries.add(entry);
        }
        return entries;
    }

    private static final class AlwaysConfirmNotifier extends HistoryLoadResultNotifier {
        @Override
        public boolean confirmClearHistory(java.awt.Component parent) { return true; }
        @Override
        public boolean confirmExportSensitiveData(java.awt.Component parent) { return true; }
        @Override
        public void showError(java.awt.Component parent, String message) { }
        @Override
        public void showInfo(java.awt.Component parent, String message) { }
    }
}
