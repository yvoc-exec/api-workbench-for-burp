package burp.ui.history;

import burp.history.HistoryDiffService;
import burp.history.HistoryEntry;
import burp.history.HistoryEntrySummary;
import burp.history.HistoryExportService;
import burp.history.HistoryFilterCriteria;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistoryResponseSnapshot;
import burp.history.HistoryStore;
import burp.history.HistorySource;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryLightweightOwnershipTest {
    @Test
    void summaryAndTableModelContainNoHeavyHistoryGraphs() throws Exception {
        for (Field field : HistoryEntrySummary.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertThat(field.getType()).isNotEqualTo(HistoryEntry.class);
            assertThat(field.getType()).isNotEqualTo(byte[].class);
            assertThat(java.util.Collection.class.isAssignableFrom(field.getType())).isFalse();
            assertThat(java.util.Map.class.isAssignableFrom(field.getType())).isFalse();
        }
        Field rows = HistoryTableModel.class.getDeclaredField("summaries");
        assertThat(rows.getGenericType().getTypeName()).contains("HistoryEntrySummary").doesNotContain("HistoryEntry>");
    }

    @Test
    void thousandEntryRefreshUsesSummariesAndSelectionOwnsAtMostOneFullEntry() throws Exception {
        CountingHistoryStore store = new CountingHistoryStore();
        store.addAll(largeEntries(1_000));
        long before = store.getRetentionStats().canonicalRetainedBytes();

        HistoryPanel[] holder = new HistoryPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new HistoryPanel(
                store, new HistoryExportService(), new HistoryDiffService(), new AlwaysConfirmNotifier()));
        HistoryPanel panel = holder[0];

        assertThat(panel.getHistoryTable().getRowCount()).isEqualTo(1_000);
        assertThat(store.fullFetches.get()).isZero();
        assertThat(store.getRetentionStats().canonicalRetainedBytes()).isEqualTo(before);
        assertThat(((HistoryTableModel) panel.getHistoryTable().getModel()).getSummaries()).hasSize(1_000);
        assertThat(((HistoryTableModel) panel.getHistoryTable().getModel()).getSummaries())
                .allSatisfy(summary -> {
                    assertThat(summary.collectionName()).hasSize(HistoryEntrySummary.LABEL_LIMIT);
                    assertThat(summary.urlTemplate().length()).isLessThanOrEqualTo(HistoryEntrySummary.URL_LIMIT);
                });

        SwingUtilities.invokeAndWait(() -> panel.getHistoryTable().setRowSelectionInterval(0, 0));
        assertThat(store.fullFetches.get()).isEqualTo(1);
        assertThat(panel.getDetailPanel().getCurrentEntry()).isNotNull();
        SwingUtilities.invokeAndWait(panel::refreshFromStore);
        assertThat(store.fullFetches.get()).isEqualTo(1);

        SwingUtilities.invokeAndWait(() -> panel.getHistoryTable().setRowSelectionInterval(1, 1));
        assertThat(store.fullFetches.get()).isEqualTo(2);
        assertThat(panel.getDetailPanel().getCurrentEntry().id).isEqualTo("entry-998");

        SwingUtilities.invokeAndWait(panel.getHistoryTable()::clearSelection);
        assertThat(panel.getDetailPanel().getCurrentEntry()).isNull();
        assertThat(store.fullFetches.get()).isEqualTo(2);
    }

    @Test
    void filterPreservesBodySearchWithoutFetchingAndMultiSelectionLoadsOnlyRequestedEntries() throws Exception {
        CountingHistoryStore store = new CountingHistoryStore();
        List<HistoryEntry> entries = largeEntries(3);
        entries.get(1).requestSnapshot.bodyAsAuthored = "unique-body-marker".getBytes(StandardCharsets.UTF_8);
        store.addAll(entries);
        HistoryPanel[] holder = new HistoryPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new HistoryPanel(
                store, new HistoryExportService(), new HistoryDiffService(), new AlwaysConfirmNotifier()));
        HistoryPanel panel = holder[0];

        HistoryFilterCriteria criteria = new HistoryFilterCriteria();
        criteria.freeText = "unique-body-marker";
        SwingUtilities.invokeAndWait(() -> {
            panel.getFilterPanel().setCriteria(criteria);
            panel.applyCurrentFilter();
        });
        assertThat(panel.getHistoryTable().getRowCount()).isOne();
        assertThat(store.fullFetches.get()).isZero();

        SwingUtilities.invokeAndWait(() -> {
            panel.getFilterPanel().clear();
            panel.applyCurrentFilter();
            panel.getHistoryTable().setRowSelectionInterval(0, 1);
        });
        store.fullFetches.set(0);
        store.batchFetches.set(0);
        assertThat(panel.getSelectedEntries()).hasSize(2);
        assertThat(store.fullFetches.get()).isZero();
        assertThat(store.batchFetches.get()).isEqualTo(2);
    }

    @Test
    void clearDropsStoreSummariesSelectionAndCurrentDetail() throws Exception {
        CountingHistoryStore store = new CountingHistoryStore();
        store.addAll(largeEntries(2));
        HistoryPanel[] holder = new HistoryPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new HistoryPanel(
                store, new HistoryExportService(), new HistoryDiffService(), new AlwaysConfirmNotifier()));
        HistoryPanel panel = holder[0];
        SwingUtilities.invokeAndWait(() -> {
            panel.getHistoryTable().setRowSelectionInterval(0, 0);
            panel.clearHistory();
        });

        assertThat(store.size()).isZero();
        assertThat(panel.getHistoryTable().getRowCount()).isZero();
        assertThat(panel.getSelectedIds()).isEmpty();
        assertThat(panel.getDetailPanel().getCurrentEntry()).isNull();
        assertThat(panel.getDetailPanel().getRequestArea().getText()).isEmpty();
        assertThat(panel.getDetailPanel().getResponseArea().getText()).isEmpty();
    }

    private static List<HistoryEntry> largeEntries(int count) {
        List<HistoryEntry> entries = new ArrayList<>(count);
        byte[] request = rawRequest(8 * 1024);
        byte[] response = "r".repeat(8 * 1024).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < count; i++) {
            HistoryEntry entry = new HistoryEntry();
            entry.id = "entry-" + i;
            entry.timestamp = Instant.parse("2026-08-04T00:00:00Z").plusSeconds(i);
            entry.source = HistorySource.WORKBENCH;
            entry.collectionName = "c".repeat(HistoryEntrySummary.LABEL_LIMIT + 128);
            entry.folderPath = "/folder/" + i;
            entry.requestName = "request-" + i;
            entry.environmentName = "environment";
            entry.requestSnapshot = new HistoryRequestSnapshot();
            entry.requestSnapshot.method = "POST";
            entry.requestSnapshot.urlTemplate = "https://api.example.test/items/" + i;
            entry.requestSnapshot.bodyAsAuthored = request.clone();
            entry.requestSnapshot.rawRequestSent = request.clone();
            entry.requestSnapshot.rawRequestSentText = new String(request, StandardCharsets.UTF_8);
            entry.responseSnapshot = new HistoryResponseSnapshot();
            entry.responseSnapshot.statusCode = 200;
            entry.responseSnapshot.body = response.clone();
            entry.statusCode = 200;
            entries.add(entry);
        }
        return entries;
    }

    private static byte[] rawRequest(int targetBytes) {
        String headers = "POST /items HTTP/1.1\r\nHost: api.example.test\r\n\r\n";
        return (headers + "q".repeat(Math.max(0, targetBytes - headers.length()))).getBytes(StandardCharsets.UTF_8);
    }

    private static final class CountingHistoryStore extends HistoryStore {
        private final AtomicInteger fullFetches = new AtomicInteger();
        private final AtomicInteger batchFetches = new AtomicInteger();

        @Override
        public synchronized HistoryEntry getById(String id) {
            HistoryEntry entry = super.getById(id);
            if (entry != null) {
                fullFetches.incrementAndGet();
            }
            return entry;
        }

        @Override
        public synchronized List<HistoryEntry> getByIds(java.util.Collection<String> ids) {
            List<HistoryEntry> entries = super.getByIds(ids);
            batchFetches.addAndGet(entries.size());
            return entries;
        }
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
