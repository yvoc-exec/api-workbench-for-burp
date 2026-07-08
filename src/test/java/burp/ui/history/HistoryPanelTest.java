package burp.ui.history;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.history.*;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryPanelTest {
    @TempDir
    Path tempDir;

    @Test
    void tableLayoutRemainsVisibleWithZeroEntries() {
        HistoryPanel panel = new HistoryPanel(new HistoryStore(), new HistoryExportService(), new HistoryDiffService(), new RecordingNotifier());
        JSplitPane splitPane = (JSplitPane) panel.getComponent(1);

        assertThat(panel.getHistoryTable().getRowCount()).isZero();
        assertThat(panel.getHistoryTable().getColumnCount()).isEqualTo(14);
        assertThat(panel.getHistoryTable().getTableHeader()).isNotNull();
        assertThat(panel.getHistoryTable().getColumnName(0)).isEqualTo("Pin");
        assertThat(panel.getHistoryTable().getColumnName(1)).isEqualTo("Time");
        assertThat(panel.getHistoryTable().getColumnName(8)).isEqualTo("URL Template");
        assertThat(panel.getHistoryTable().getColumnName(13)).isEqualTo("Result");
        assertThat(panel.getTableScrollPane().getMinimumSize().width).isGreaterThanOrEqualTo(460);
        assertThat(panel.getTableScrollPane().getMinimumSize().height).isGreaterThanOrEqualTo(220);
        assertThat(panel.getTableScrollPane().getPreferredSize().width).isGreaterThanOrEqualTo(820);
        assertThat(panel.getTableScrollPane().getPreferredSize().height).isGreaterThanOrEqualTo(280);
        assertThat(panel.getDetailPanel().getMinimumSize().width).isGreaterThanOrEqualTo(320);
        assertThat(panel.getDetailPanel().getMinimumSize().height).isGreaterThanOrEqualTo(140);
        assertThat(panel.getDetailPanel().getPreferredSize().width).isGreaterThanOrEqualTo(520);
        assertThat(splitPane.getOrientation()).isEqualTo(JSplitPane.HORIZONTAL_SPLIT);
        assertThat(splitPane.getLeftComponent()).isSameAs(panel.getTableScrollPane());
        assertThat(splitPane.getRightComponent()).isSameAs(panel.getDetailPanel());
        JPanel topPanel = (JPanel) panel.getComponent(0);
        assertThat(topPanel.getComponentCount()).isEqualTo(3);
        assertThat(topPanel.getComponent(1)).isInstanceOf(JLabel.class);
        assertThat(((JLabel) topPanel.getComponent(1)).getText())
                .contains("History retention:")
                .contains("0/1000 entries")
                .contains("request body limit")
                .contains("response body limit")
                .contains("truncated 0")
                .contains("over budget: no");
        assertThat(panel.getUsageLabel().getText()).contains("pinned 0");
        assertThat(panel.getFilterPanel().getPreferredSize().height).isLessThanOrEqualTo(90);
        assertThat(panel.getFilterPanel().getMinimumSize().height).isLessThanOrEqualTo(72);
        assertThat(panel.getFilterPanel().getComponentCount()).isGreaterThan(0);
        assertThat(panel.getDetailPanel().isRequestNativeViewerAvailable()).isFalse();
        assertThat(panel.getDetailPanel().isResponseNativeViewerAvailable()).isFalse();
        assertThat(panel.getDetailPanel().getTabbedPane().indexOfTab("Evidence")).isGreaterThanOrEqualTo(0);
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
        assertThat(panel.getDetailPanel().getRequestArea().getText()).contains("POST /login HTTP/1.1");
        assertThat(panel.getDetailPanel().getRequestArea().getText()).contains("Host: api.example.test");
        assertThat(panel.getDetailPanel().getRequestArea().getText()).contains("Authorization: Bearer {{token}}");
        assertThat(panel.getDetailPanel().getResponseArea().getText()).contains("HTTP/1.1 200");
        assertThat(panel.getDetailPanel().getResponseArea().getText()).contains("Content-Type: application/json");
        assertThat(panel.getActionsPanel().getPinButton().isEnabled()).isTrue();
        assertThat(panel.getActionsPanel().getPinButton().getText()).isEqualTo("Pin");
        assertThat(panel.getDetailPanel().getPinnedCheckBox().isSelected()).isFalse();
        assertThat(panel.getDetailPanel().getSaveMetadataButton().isEnabled()).isTrue();

        panel.getActionsPanel().getLoadButton().doClick();
        panel.getActionsPanel().getReplayButton().doClick();
        panel.getActionsPanel().getRepeaterButton().doClick();
        ImporterPanelTestSupport.awaitEdt();

        assertThat(load.get().id).isEqualTo("first");
        assertThat(replay.get().id).isEqualTo("first");
        assertThat(repeater.get().id).isEqualTo("first");

        panel.getDetailPanel().getPinnedCheckBox().setSelected(true);
        panel.getDetailPanel().getAnalystNotesArea().setText("Reviewed");
        panel.getDetailPanel().getTagsField().setText("Auth, auth, Evidence");
        panel.getDetailPanel().getSaveMetadataButton().doClick();
        ImporterPanelTestSupport.awaitEdt();
        assertThat(store.getById("first").pinned).isTrue();
        assertThat(store.getById("first").analystNotes).isEqualTo("Reviewed");
        assertThat(store.getById("first").tags).containsExactly("Auth", "Evidence");
        assertThat(panel.getActionsPanel().getPinButton().getText()).isEqualTo("Unpin");
        assertThat(changeCount.get()).isGreaterThanOrEqualTo(1);

        HistoryFilterCriteria criteria = new HistoryFilterCriteria();
        criteria.source = HistorySource.RUNNER;
        panel.getFilterPanel().setCriteria(criteria);
        panel.refreshFromStore("second");
        assertThat(panel.getHistoryTable().getRowCount()).isEqualTo(1);
        assertThat(panel.getHistoryTable().getValueAt(0, 6)).isEqualTo(HistoryTestFixtures.REQUEST_NAME);
        panel.getHistoryTable().setRowSelectionInterval(0, 0);

        panel.deleteSelectedEntries();
        assertThat(store.snapshot()).extracting(entry -> entry.id).containsExactly("first");

        panel.clearHistory();
        assertThat(store.isEmpty()).isTrue();
        assertThat(notifier.confirmClearCount).isGreaterThan(0);
        assertThat(changeCount.get()).isGreaterThan(0);
    }

    @Test
    void nativeDetailViewersRenderTemplatedRequestAndResponseMessages() {
        HistoryStore store = new HistoryStore();
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "native-entry", Instant.parse("2026-06-15T02:00:00Z"));
        store.addEntry(entry);

        MontoyaApi api = Mockito.mock(MontoyaApi.class);
        UserInterface userInterface = Mockito.mock(UserInterface.class);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        when(requestEditor.uiComponent()).thenReturn(new JPanel());
        when(responseEditor.uiComponent()).thenReturn(new JPanel());
        when(api.userInterface()).thenReturn(userInterface);
        when(userInterface.createHttpRequestEditor(EditorOptions.READ_ONLY)).thenReturn(requestEditor);
        when(userInterface.createHttpResponseEditor(EditorOptions.READ_ONLY)).thenReturn(responseEditor);

        HistoryPanel panel = new HistoryPanel(store, new HistoryExportService(), new HistoryDiffService(), new RecordingNotifier(), api);
        reset(requestEditor, responseEditor);

        panel.getHistoryTable().setRowSelectionInterval(0, 0);
        ImporterPanelTestSupport.awaitEdt();
        assertThat(panel.getDetailPanel().isRequestNativeViewerAvailable()).isTrue();
        assertThat(panel.getDetailPanel().isResponseNativeViewerAvailable()).isTrue();

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(requestEditor).setRequest(requestCaptor.capture());
        verify(responseEditor).setResponse(responseCaptor.capture());

        assertThat(requestCaptor.getValue().method()).isEqualTo("POST");
        assertThat(requestCaptor.getValue().headerValue("Authorization")).isEqualTo("Bearer {{token}}");
        assertThat(requestCaptor.getValue().headerValue("Content-Type")).isEqualTo("application/json");
        assertThat(requestCaptor.getValue().bodyToString()).contains("{\"username\":\"demo\",\"password\":\"{{password}}\"}");
        assertThat(panel.getDetailPanel().getRequestArea().getText()).contains("POST /login HTTP/1.1");
        assertThat(panel.getDetailPanel().getRequestArea().getText()).contains("Authorization: Bearer {{token}}");

        assertThat(responseCaptor.getValue().statusCode()).isEqualTo((short) 200);
        assertThat(responseCaptor.getValue().headerValue("Content-Type")).isEqualTo("application/json");
        assertThat(responseCaptor.getValue().bodyToString()).contains("{\"ok\":true}");
        assertThat(panel.getDetailPanel().getResponseArea().getText()).contains("HTTP/1.1 200");
        assertThat(entry.requestSnapshot.urlTemplate).isEqualTo("{{base_url}}/login");
        assertThat(entry.requestSnapshot.displayBodyText()).contains("{{password}}");
        assertThat(panel.getDetailPanel().getEvidenceStatusLabel().getText()).contains("Selected entry");
        assertThat(store.snapshot()).hasSize(1);

        panel.getHistoryTable().clearSelection();
        ImporterPanelTestSupport.awaitEdt();
        assertThat(panel.getDetailPanel().getRequestArea().getText()).isBlank();
        assertThat(panel.getDetailPanel().getResponseArea().getText()).isBlank();
        assertThat(store.snapshot()).hasSize(1);
    }

    @Test
    void historyExportWritesJsonCsvAndHarOffEdt() throws Exception {
        HistoryEntry entry = HistoryTestFixtures.sampleWorkbenchEntry();
        for (String format : List.of("json", "csv", "har")) {
            RecordingExportService exportService = new RecordingExportService();
            HistoryPanel panel = new HistoryPanel(new HistoryStore(), exportService, new HistoryDiffService(), new RecordingNotifier());
            Path output = tempDir.resolve("history-" + format);

            SwingWorker<Path, Void> worker = panel.startHistoryExportWorker(format, List.of(HistoryEntry.copyOf(entry)), output);

            assertThat(worker.get(5, TimeUnit.SECONDS)).isEqualTo(output);
            ImporterPanelTestSupport.awaitEdt();
            assertThat(exportService.calledOnEdt.get())
                    .as(format + " export should write outside the EDT")
                    .isFalse();
        }
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

    private static final class RecordingExportService extends HistoryExportService {
        final AtomicReference<Boolean> calledOnEdt = new AtomicReference<>();

        @Override
        public void writeJson(Collection<HistoryEntry> entries, Path output) throws IOException {
            calledOnEdt.set(SwingUtilities.isEventDispatchThread());
        }

        @Override
        public void writeCsv(Collection<HistoryEntry> entries, Path output) throws IOException {
            calledOnEdt.set(SwingUtilities.isEventDispatchThread());
        }

        @Override
        public void writeHar(Collection<HistoryEntry> entries, Path output) throws IOException {
            calledOnEdt.set(SwingUtilities.isEventDispatchThread());
        }
    }
}
