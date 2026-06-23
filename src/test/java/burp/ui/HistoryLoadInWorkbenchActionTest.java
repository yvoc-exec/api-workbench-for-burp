package burp.ui;

import burp.history.HistoryEntry;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistorySource;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspaceState;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import burp.ui.history.HistoryPanel;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryLoadInWorkbenchActionTest {

    @Test
    void loadingHistoryIntoWorkbenchReplacesDirtyRequestAndShowsConfirmation() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(HistoryTestFixtures.sampleCollection())));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        ApiRequest liveRequest = liveCollection.requests.get(0);

        RequestEditorPanel requestEditor = ImporterPanelTestSupport.getField(bundle.panel, "requestEditor");
        requestEditor.markDirty();

        ApiRequest changedRequest = HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest());
        changedRequest.body.raw = "{\"username\":\"loaded\"}";
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "load-entry", Instant.parse("2026-06-15T01:40:00Z"));
        entry.requestSnapshot = HistoryRequestSnapshot.from(changedRequest);
        entry.collectionName = HistoryTestFixtures.COLLECTION_NAME;
        entry.requestId = liveRequest.id;
        entry.requestName = HistoryTestFixtures.REQUEST_NAME;
        entry.folderPath = HistoryTestFixtures.REQUEST_FOLDER;
        entry.source = HistorySource.WORKBENCH;

        clickLoadHistoryButton(bundle, entry);

        assertThat(notifier.confirmCalls).isEqualTo(1);
        assertThat(notifier.loadedOriginalCalls).isEqualTo(1);
        assertThat(requestEditor.isDirty()).isFalse();
        assertThat(requestEditor.getCurrentRequest()).isSameAs(liveRequest);
        assertThat(liveRequest.body.raw).isEqualTo("{\"username\":\"loaded\"}");
        assertThat(liveRequest.path).isEqualTo(HistoryTestFixtures.REQUEST_FOLDER);
        assertThat(collectionNames(loadedCollections)).doesNotContain("History Replays");
    }

    @Test
    void loadingHistoryIntoWorkbenchUsesUniqueFallbackWhenRequestIdMissing() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(HistoryTestFixtures.sampleCollection())));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        ApiRequest liveRequest = liveCollection.requests.get(0);
        liveRequest.id = null;

        ApiRequest changedRequest = HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest());
        changedRequest.body.raw = "{\"username\":\"fallback-loaded\"}";
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "load-fallback", Instant.parse("2026-06-15T01:41:00Z"));
        entry.requestSnapshot = HistoryRequestSnapshot.from(changedRequest);
        entry.requestSnapshot.authoredRequest.id = null;
        entry.collectionName = HistoryTestFixtures.COLLECTION_NAME;
        entry.requestId = null;
        entry.requestName = HistoryTestFixtures.REQUEST_NAME;
        entry.folderPath = HistoryTestFixtures.REQUEST_FOLDER;
        entry.source = HistorySource.WORKBENCH;

        clickLoadHistoryButton(bundle, entry);

        assertThat(notifier.loadedOriginalCalls).isEqualTo(1);
        assertThat(liveRequest.body.raw).isEqualTo("{\"username\":\"fallback-loaded\"}");
        assertThat(collectionNames(loadedCollections)).doesNotContain("History Replays");
    }

    @Test
    void loadingHistoryIntoWorkbenchResolvesStableCollectionIdentityAfterRename() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(HistoryTestFixtures.sampleCollection())));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        liveCollection.name = "Petstore Renamed";
        ApiRequest liveRequest = liveCollection.requests.get(0);

        ApiRequest changedRequest = HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest());
        changedRequest.body.raw = "{\"username\":\"stable-loaded\"}";
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "stable-entry", Instant.parse("2026-06-15T01:41:30Z"));
        entry.collectionId = HistoryTestFixtures.COLLECTION_ID;
        entry.collectionName = HistoryTestFixtures.COLLECTION_NAME;
        entry.requestSnapshot = HistoryRequestSnapshot.from(changedRequest);
        entry.requestId = liveRequest.id;
        entry.requestName = liveRequest.name;
        entry.folderPath = HistoryTestFixtures.REQUEST_FOLDER;

        clickLoadHistoryButton(bundle, entry);

        assertThat(notifier.loadedOriginalCalls).isEqualTo(1);
        assertThat(liveRequest.body.raw).isEqualTo("{\"username\":\"stable-loaded\"}");
        assertThat(collectionNames(loadedCollections)).doesNotContain("History Replays");
    }

    @Test
    void loadingHistoryIntoWorkbenchResolvesLegacyCanonicalFolderPathWithoutRequestId() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = new ApiCollection();
        collection.id = "col-admin";
        collection.name = "Admin Collection";
        ApiRequest request = HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest());
        request.id = "req-admin";
        request.name = "List Users";
        request.path = "Admin";
        request.sourceCollection = collection.name;
        request.body.raw = "{\"username\":\"legacy\"}";
        collection.requests.add(request);
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        ApiRequest liveRequest = liveCollection.requests.get(0);

        ApiRequest changedRequest = HistoryTestFixtures.copyRequest(request);
        changedRequest.body = new ApiRequest.Body();
        changedRequest.body.mode = request.body.mode;
        changedRequest.body.contentType = request.body.contentType;
        changedRequest.body.raw = "{\"username\":\"canonical-loaded\"}";
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "canonical-folder", Instant.parse("2026-06-15T01:41:45Z"));
        entry.collectionId = collection.id;
        entry.collectionName = collection.name;
        entry.requestSnapshot = HistoryRequestSnapshot.from(changedRequest);
        entry.requestSnapshot.authoredRequest.id = null;
        entry.requestId = null;
        entry.requestName = request.name;
        entry.folderPath = "Admin/List Users";

        clickLoadHistoryButton(bundle, entry);

        assertThat(notifier.loadedOriginalCalls).isEqualTo(1);
        assertThat(liveRequest.body.raw).isEqualTo("{\"username\":\"canonical-loaded\"}");
        assertThat(collectionNames(loadedCollections)).doesNotContain("History Replays");
    }

    @Test
    void loadingHistoryIntoWorkbenchResolvesSameNamedFolderWhenCollectionProvesItExists() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = new ApiCollection();
        collection.id = "col-users";
        collection.name = "Users API";
        collection.folderPaths = new java.util.ArrayList<>(List.of("Users"));

        ApiRequest request = new ApiRequest();
        request.id = "req-users";
        request.name = "Users";
        request.path = "Users";
        request.sourceCollection = collection.name;
        request.method = "GET";
        request.url = "https://api.example.test/users";
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"username\":\"same-folder\"}";
        collection.requests.add(request);

        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        ApiRequest liveRequest = liveCollection.requests.get(0);

        ApiRequest changedRequest = HistoryTestFixtures.copyRequest(request);
        changedRequest.body.raw = "{\"username\":\"same-folder-loaded\"}";
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "same-folder-entry", Instant.parse("2026-06-15T01:41:15Z"));
        entry.collectionId = collection.id;
        entry.collectionName = collection.name;
        entry.requestSnapshot = HistoryRequestSnapshot.from(changedRequest);
        entry.requestSnapshot.authoredRequest.id = null;
        entry.requestId = null;
        entry.requestName = request.name;
        entry.folderPath = "Users";

        clickLoadHistoryButton(bundle, entry);

        assertThat(notifier.loadedOriginalCalls).isEqualTo(1);
        assertThat(liveRequest.body.raw).isEqualTo("{\"username\":\"same-folder-loaded\"}");
        assertThat(collectionNames(loadedCollections)).doesNotContain("History Replays");
    }

    @Test
    void loadingHistoryIntoWorkbenchResolvesRootRequestWithLegacySameNameFolderPath() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = new ApiCollection();
        collection.id = "col-root-users";
        collection.name = "Users API";

        ApiRequest request = new ApiRequest();
        request.id = "req-root-users";
        request.name = "Users";
        request.path = "";
        request.sourceCollection = collection.name;
        request.method = "GET";
        request.url = "https://api.example.test/users";
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"username\":\"root\"}";
        collection.requests.add(request);

        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        ApiRequest liveRequest = liveCollection.requests.get(0);

        ApiRequest changedRequest = HistoryTestFixtures.copyRequest(request);
        changedRequest.body.raw = "{\"username\":\"root-loaded\"}";
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "root-legacy", Instant.parse("2026-06-15T01:41:20Z"));
        entry.collectionId = collection.id;
        entry.collectionName = collection.name;
        entry.requestSnapshot = HistoryRequestSnapshot.from(changedRequest);
        entry.requestSnapshot.authoredRequest.id = null;
        entry.requestId = null;
        entry.requestName = request.name;
        entry.folderPath = "Users";

        clickLoadHistoryButton(bundle, entry);

        assertThat(notifier.loadedOriginalCalls).isEqualTo(1);
        assertThat(liveRequest.body.raw).isEqualTo("{\"username\":\"root-loaded\"}");
        assertThat(collectionNames(loadedCollections)).doesNotContain("History Replays");
    }

    @Test
    void loadingHistoryIntoWorkbenchCreatesHistoryReplaysWhenOriginalMissing() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(HistoryTestFixtures.sampleCollection())));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        ApiRequest removedRequest = liveCollection.requests.remove(0);

        ApiRequest changedRequest = HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest());
        changedRequest.body.raw = "{\"username\":\"fallback-request\"}";
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "load-missing", Instant.parse("2026-06-15T01:42:00Z"));
        entry.requestSnapshot = HistoryRequestSnapshot.from(changedRequest);
        entry.collectionName = HistoryTestFixtures.COLLECTION_NAME;
        entry.requestId = removedRequest.id;
        entry.requestName = HistoryTestFixtures.REQUEST_NAME;
        entry.folderPath = HistoryTestFixtures.REQUEST_FOLDER;
        entry.source = HistorySource.WORKBENCH;

        clickLoadHistoryButton(bundle, entry);

        assertThat(notifier.loadedReplayCalls).isEqualTo(1);
        assertThat(collectionNames(loadedCollections)).contains("History Replays");
        ApiCollection historyReplays = loadedCollections.stream()
                .filter(collection -> "History Replays".equals(collection.name))
                .findFirst()
                .orElseThrow();
        assertThat(historyReplays.requests).hasSize(1);
        ApiRequest recreated = historyReplays.requests.get(0);
        assertThat(recreated.name).isEqualTo(HistoryTestFixtures.REQUEST_NAME);
        assertThat(recreated.path).isEqualTo(HistoryTestFixtures.REQUEST_FOLDER);
        assertThat(recreated.body.raw).isEqualTo("{\"username\":\"fallback-request\"}");
        assertThat(liveCollection.requests).hasSize(1);
        assertThat(liveCollection.requests).doesNotContain(removedRequest);
    }

    @Test
    void ambiguousFallbackMatchDoesNotSilentlyChooseARequest() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        ApiRequest duplicate = HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest());
        duplicate.id = "duplicate-request";
        collection.requests.add(duplicate);
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));

        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "ambiguous", Instant.parse("2026-06-15T01:43:00Z"));
        entry.requestSnapshot = HistoryRequestSnapshot.from(HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest()));
        entry.requestSnapshot.authoredRequest.id = null;
        entry.collectionName = HistoryTestFixtures.COLLECTION_NAME;
        entry.requestId = null;
        entry.requestName = HistoryTestFixtures.REQUEST_NAME;
        entry.folderPath = HistoryTestFixtures.REQUEST_FOLDER;

        Object context = ImporterPanelTestSupport.invoke(
                bundle.panel,
                "resolveHistoryRequestContext",
                new Class<?>[]{HistoryEntry.class, boolean.class},
                entry,
                false);

        assertThat(context).isNotNull();
        assertThat((Boolean) ImporterPanelTestSupport.getField(context, "originalRequestExists")).isFalse();
        assertThat((Boolean) ImporterPanelTestSupport.getField(context, "ambiguousResolution")).isTrue();
        assertThat((Object) ImporterPanelTestSupport.getField(context, "request")).isNull();
        assertThat(collectionNames(ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections")))
                .doesNotContain("History Replays");
    }

    private static List<String> collectionNames(List<ApiCollection> collections) {
        return collections.stream()
                .map(collection -> collection != null ? collection.name : null)
                .toList();
    }

    private static void clickLoadHistoryButton(ImporterPanelTestSupport.PanelBundle bundle, HistoryEntry entry) throws Exception {
        HistoryPanel historyPanel = bundle.panel.getHistoryPanelForTests();
        historyPanel.getHistoryStore().addEntry(entry);
        historyPanel.refreshFromStore(entry.id);
        ImporterPanelTestSupport.awaitEdt();
        SwingUtilities.invokeAndWait(() -> {
            historyPanel.getHistoryTable().setRowSelectionInterval(0, 0);
            historyPanel.getActionsPanel().getLoadButton().doClick();
        });
        ImporterPanelTestSupport.awaitEdt();
    }

    private static final class RecordingNotifier extends burp.ui.history.HistoryLoadResultNotifier {
        int confirmCalls;
        int loadedOriginalCalls;
        int loadedReplayCalls;

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
            loadedReplayCalls++;
        }

        @Override
        public void showError(java.awt.Component parent, String message) {
        }

        @Override
        public void showInfo(java.awt.Component parent, String message) {
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
