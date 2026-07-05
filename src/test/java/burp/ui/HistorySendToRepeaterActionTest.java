package burp.ui;

import burp.history.HistoryDiffService;
import burp.history.HistoryEntry;
import burp.history.HistoryExportService;
import burp.history.HistoryStore;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.models.ApiCollection;
import burp.models.WorkspaceState;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import burp.ui.history.HistoryLoadResultNotifier;
import burp.ui.history.HistoryPanel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

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

    @Test
    void sendToRepeaterFromHistoryDoesNotCreateHistoryReplaysWhenOriginalRequestMissing() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        doReturn("History Replay")
                .when(bundle.importer)
                .generateRepeaterTabName(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());

        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(HistoryTestFixtures.sampleCollection())));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", new NoOpNotifier());
        ImporterPanelTestSupport.awaitEdt();

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        liveCollection.requests.remove(0);

        ImporterPanelTestSupport.invokeVoid(
                bundle.panel,
                "sendHistoryEntryToRepeater",
                new Class<?>[]{HistoryEntry.class},
                HistoryTestFixtures.sampleWorkbenchEntry());

        assertThat(((NoOpNotifier) ImporterPanelTestSupport.getField(bundle.panel, "historyLoadResultNotifier")).lastError)
                .as("sendHistoryEntryToRepeater should not surface an error dialog")
                .isNull();
        verify(bundle.importer, timeout(10000)).sendToRepeater(ArgumentMatchers.any(HttpRequest.class), ArgumentMatchers.anyString());
        ImporterPanelTestSupport.awaitEdt();

        assertThat(bundle.panel.getWorkspaceStateSnapshot().historyEntries).isEmpty();
        assertThat(collectionNames(loadedCollections)).doesNotContain("History Replays");
        assertThat(liveCollection.requests).hasSize(1);
    }

    @Test
    void sendToRepeaterFromHistoryPreservesExactHttpHeaders() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(HistoryTestFixtures.sampleCollection())));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", new NoOpNotifier());

        HistoryEntry entry = HistoryTestFixtures.sampleWorkbenchEntry();
        entry.requestSnapshot.authoredRequest.buildMode = burp.models.ApiRequest.BuildMode.EXACT_HTTP;
        entry.requestSnapshot.authoredRequest.headers.add(new burp.models.ApiRequest.Header("Host", "alt.example.test", false));
        entry.requestSnapshot.authoredRequest.headers.add(new burp.models.ApiRequest.Header("Authorization", "Bearer first", false));
        entry.requestSnapshot.authoredRequest.headers.add(new burp.models.ApiRequest.Header("Authorization", "Bearer second", false));

        ArgumentCaptor<HttpRequest> captured = ArgumentCaptor.forClass(HttpRequest.class);

        ImporterPanelTestSupport.invokeVoid(
                bundle.panel,
                "sendHistoryEntryToRepeater",
                new Class<?>[]{HistoryEntry.class},
                entry);

        verify(bundle.importer, timeout(10000)).sendToRepeater(captured.capture(), ArgumentMatchers.nullable(String.class));
        HttpRequest replayed = captured.getValue();
        assertThat(replayed.headerValue("Host")).isEqualTo("alt.example.test");
        assertThat(replayed.headerValue("User-Agent")).isNull();
        assertThat(replayed.headerValue("Accept")).isNull();
        assertThat(replayed.bodyToString()).contains("username");
    }

    private static List<String> collectionNames(List<ApiCollection> collections) {
        return collections.stream()
                .map(collection -> collection != null ? collection.name : null)
                .toList();
    }

    private static final class NoOpNotifier extends HistoryLoadResultNotifier {
        String lastError;

        @Override public boolean confirmReplaceCurrentRequest(java.awt.Component parent) { return true; }
        @Override public void showLoadedIntoOriginalRequest(java.awt.Component parent, HistoryEntry entry) { }
        @Override public void showLoadedUnderHistoryReplays(java.awt.Component parent, String requestName) { }
        @Override public boolean confirmClearHistory(java.awt.Component parent) { return true; }
        @Override public boolean confirmExportSensitiveData(java.awt.Component parent) { return true; }
        @Override public void showError(java.awt.Component parent, String message) { lastError = message; }
        @Override public void showInfo(java.awt.Component parent, String message) { }
    }
}
