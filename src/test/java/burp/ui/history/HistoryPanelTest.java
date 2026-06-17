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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.swing.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryPanelTest {

    @Test
    void tableLayoutRemainsVisibleWithZeroEntries() {
        HistoryPanel panel = new HistoryPanel(new HistoryStore(), new HistoryExportService(), new HistoryDiffService(), new RecordingNotifier());
        JSplitPane splitPane = (JSplitPane) panel.getComponent(1);

        assertThat(panel.getHistoryTable().getRowCount()).isZero();
        assertThat(panel.getHistoryTable().getColumnCount()).isEqualTo(13);
        assertThat(panel.getHistoryTable().getTableHeader()).isNotNull();
        assertThat(panel.getHistoryTable().getColumnName(0)).isEqualTo("Time");
        assertThat(panel.getHistoryTable().getColumnName(7)).isEqualTo("URL Template");
        assertThat(panel.getHistoryTable().getColumnName(12)).isEqualTo("Result");
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
                .isEqualTo("History keeps the latest 1000 entries; older entries are automatically removed. Stored history may contain sensitive request/response data.");
        assertThat(panel.getFilterPanel().getPreferredSize().height).isLessThanOrEqualTo(90);
        assertThat(panel.getFilterPanel().getMinimumSize().height).isLessThanOrEqualTo(72);
        assertThat(panel.getFilterPanel().getComponentCount()).isGreaterThan(0);
        assertThat(panel.getDetailPanel().isRequestNativeViewerAvailable()).isFalse();
        assertThat(panel.getDetailPanel().isResponseNativeViewerAvailable()).isFalse();
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
        assertThat(store.snapshot()).hasSize(1);

        panel.getHistoryTable().clearSelection();
        ImporterPanelTestSupport.awaitEdt();
        assertThat(panel.getDetailPanel().getRequestArea().getText()).isBlank();
        assertThat(panel.getDetailPanel().getResponseArea().getText()).isBlank();
        assertThat(store.snapshot()).hasSize(1);
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
