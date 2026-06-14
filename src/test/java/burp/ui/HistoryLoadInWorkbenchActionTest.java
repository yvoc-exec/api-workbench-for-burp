package burp.ui;

import burp.history.HistoryEntry;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistorySource;
import burp.models.ApiRequest;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryLoadInWorkbenchActionTest {

    @Test
    void loadingHistoryIntoWorkbenchReplacesDirtyRequestAndShowsConfirmation() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        bundle.panel.restoreWorkspaceState(burp.models.WorkspaceState.fromCollections(List.of(HistoryTestFixtures.sampleCollection())));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        RequestEditorPanel requestEditor = ImporterPanelTestSupport.getField(bundle.panel, "requestEditor");
        requestEditor.markDirty();

        ApiRequest changedRequest = HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest());
        changedRequest.url = "{{base_url}}/login/v2";
        changedRequest.body.raw = "{\"username\":\"loaded\"}";
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "load-entry", Instant.parse("2026-06-15T01:40:00Z"));
        entry.requestSnapshot = HistoryRequestSnapshot.from(changedRequest);
        entry.collectionName = HistoryTestFixtures.COLLECTION_NAME;
        entry.requestId = HistoryTestFixtures.REQUEST_ID;
        entry.requestName = HistoryTestFixtures.REQUEST_NAME;
        entry.folderPath = HistoryTestFixtures.REQUEST_FOLDER;
        entry.source = HistorySource.WORKBENCH;

        ImporterPanelTestSupport.invokeVoid(
                bundle.panel,
                "loadHistoryEntryIntoWorkbench",
                new Class<?>[]{HistoryEntry.class},
                entry);

        assertThat(notifier.confirmCalls).isEqualTo(1);
        assertThat(notifier.loadedOriginalCalls).isEqualTo(1);
        assertThat(requestEditor.isDirty()).isFalse();
        assertThat(requestEditor.getCurrentRequest().url).isEqualTo("{{base_url}}/login/v2");
        assertThat(requestEditor.getCurrentRequest().body.raw).isEqualTo("{\"username\":\"loaded\"}");
        assertThat(bundle.panel.getWorkspaceStateSnapshot().collections.get(0).requests.get(0).url)
                .isEqualTo("{{base_url}}/login/v2");
    }

    private static final class RecordingNotifier extends burp.ui.history.HistoryLoadResultNotifier {
        int confirmCalls;
        int loadedOriginalCalls;

        @Override
        public boolean confirmReplaceCurrentRequest(java.awt.Component parent) {
            confirmCalls++;
            return true;
        }

        @Override
        public void showLoadedIntoOriginalRequest(java.awt.Component parent, HistoryEntry entry) {
            loadedOriginalCalls++;
        }

        @Override
        public void showLoadedUnderHistoryReplays(java.awt.Component parent, String requestName) {
        }

        @Override
        public void showError(java.awt.Component parent, String message) {
        }

        @Override
        public boolean confirmClearHistory(java.awt.Component parent) {
            return true;
        }

        @Override
        public boolean confirmExportSensitiveData(java.awt.Component parent) {
            return true;
        }
    }
}
