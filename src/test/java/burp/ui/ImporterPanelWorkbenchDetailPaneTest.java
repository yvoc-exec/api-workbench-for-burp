package burp.ui;

import burp.UniversalImporter;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.auth.OAuth2Manager;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.runner.CollectionRunner;
import burp.utils.ScriptMode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImporterPanelWorkbenchDetailPaneTest {

    @Test
    void requestSelectionRestoresLatestSendDetailPerRequest() {
        TestHarness harness = newHarness();
        ApiCollection collection = collection("APIM",
                request("req-a", "Request A"),
                request("req-b", "Request B"));
        harness.panel.restoreWorkspaceCollections(List.of(collection));
        reset(harness.workbenchRequestEditor, harness.workbenchResponseEditor);

        ImporterPanel.WorkbenchSendSnapshot snapshotA = snapshot("A");
        ImporterPanel.WorkbenchSendSnapshot snapshotB = snapshot("B");

        harness.panel.openRequestInEditor(collection.requests.get(0), collection);
        assertThat(harness.panel.getWorkbenchDetailMetaTextForTest()).contains("Not yet sent");
        assertThat(harness.panel.getWorkbenchSendSnapshot(collection.requests.get(0))).isNull();

        harness.panel.applyWorkbenchSendSnapshot(collection.requests.get(0), collection, snapshotA);
        assertThat(harness.panel.getWorkbenchDetailMetaTextForTest()).startsWith("META A");
        verify(harness.workbenchRequestEditor, Mockito.atLeastOnce()).setRequest(any());
        verify(harness.workbenchResponseEditor, Mockito.atLeastOnce()).setResponse(any());

        reset(harness.workbenchRequestEditor, harness.workbenchResponseEditor);
        harness.panel.openRequestInEditor(collection.requests.get(1), collection);
        assertThat(harness.panel.getWorkbenchDetailMetaTextForTest()).contains("Not yet sent");
        harness.panel.applyWorkbenchSendSnapshot(collection.requests.get(1), collection, snapshotB);
        assertThat(harness.panel.getWorkbenchDetailMetaTextForTest()).startsWith("META B");
        verify(harness.workbenchRequestEditor, Mockito.atLeastOnce()).setRequest(any());
        verify(harness.workbenchResponseEditor, Mockito.atLeastOnce()).setResponse(any());

        reset(harness.workbenchRequestEditor, harness.workbenchResponseEditor);
        harness.panel.openRequestInEditor(collection.requests.get(0), collection);
        assertThat(harness.panel.getWorkbenchDetailMetaTextForTest()).startsWith("META A");
        verify(harness.workbenchRequestEditor, Mockito.atLeastOnce()).setRequest(any());
        verify(harness.workbenchResponseEditor, Mockito.atLeastOnce()).setResponse(any());
    }

    @Test
    void unsentAndNonRequestSelectionsClearWorkbenchDetailPane() {
        TestHarness harness = newHarness();
        ApiRequest requestA = request("req-a", "Request A");
        ApiRequest requestC = request("req-c", "Request C");
        ApiCollection collection = collection("APIM", requestA, requestC);
        harness.panel.restoreWorkspaceCollections(List.of(collection));
        reset(harness.workbenchRequestEditor, harness.workbenchResponseEditor);

        ImporterPanel.WorkbenchSendSnapshot snapshotA = snapshot("A");
        harness.panel.openRequestInEditor(requestA, collection);
        harness.panel.applyWorkbenchSendSnapshot(requestA, collection, snapshotA);
        assertThat(harness.panel.getWorkbenchDetailMetaTextForTest()).startsWith("META A");

        reset(harness.workbenchRequestEditor, harness.workbenchResponseEditor);
        harness.panel.openRequestInEditor(requestC, collection);
        assertThat(harness.panel.getWorkbenchDetailMetaTextForTest()).contains("Not yet sent");
        assertThat(harness.panel.getWorkbenchSendSnapshot(requestC)).isNull();

        reset(harness.workbenchRequestEditor, harness.workbenchResponseEditor);
        harness.panel.openRequestInEditor(requestA, collection);
        assertThat(harness.panel.getWorkbenchDetailMetaTextForTest()).startsWith("META A");

        harness.panel.clearRequestEditorForNonRequestSelection();
        assertThat(harness.panel.getWorkbenchDetailMetaTextForTest()).isBlank();
    }

    @Test
    void inFlightCompletionDoesNotOverwriteUnrelatedCurrentSelection() {
        TestHarness harness = newHarness();
        ApiRequest requestA = request("req-a", "Request A");
        ApiRequest requestB = request("req-b", "Request B");
        ApiCollection collection = collection("APIM", requestA, requestB);
        harness.panel.restoreWorkspaceCollections(List.of(collection));
        reset(harness.workbenchRequestEditor, harness.workbenchResponseEditor);

        ImporterPanel.WorkbenchSendSnapshot snapshotA = snapshot("A");
        ImporterPanel.WorkbenchSendSnapshot snapshotB = snapshot("B");

        harness.panel.openRequestInEditor(requestB, collection);
        harness.panel.applyWorkbenchSendSnapshot(requestB, collection, snapshotB);
        assertThat(harness.panel.getWorkbenchDetailMetaTextForTest()).startsWith("META B");

        String before = harness.panel.getWorkbenchDetailMetaTextForTest();
        reset(harness.workbenchRequestEditor, harness.workbenchResponseEditor);
        harness.panel.applyWorkbenchSendSnapshot(requestA, collection, snapshotA);

        assertThat(harness.panel.getWorkbenchDetailMetaTextForTest()).isEqualTo(before);
        verifyNoInteractions(harness.workbenchRequestEditor, harness.workbenchResponseEditor);
        assertThat(harness.panel.getWorkbenchSendSnapshot(requestA)).isNotNull();
    }

    @Test
    void snapshotsCanBeRemovedForRequestFolderAndCollectionDeletionAndAreNotCopiedToDuplicates() {
        TestHarness harness = newHarness();
        ApiRequest requestA = request("req-a", "Request A");
        ApiRequest requestB = request("req-b", "Request B");
        ApiRequest requestC = request("req-c", "Request C");
        ApiCollection collection = collection("APIM", requestA, requestB, requestC);
        harness.panel.restoreWorkspaceCollections(List.of(collection));
        reset(harness.workbenchRequestEditor, harness.workbenchResponseEditor);

        ImporterPanel.WorkbenchSendSnapshot snapshotA = snapshot("A");
        ImporterPanel.WorkbenchSendSnapshot snapshotB = snapshot("B");
        ImporterPanel.WorkbenchSendSnapshot snapshotC = snapshot("C");

        harness.panel.openRequestInEditor(requestA, collection);
        harness.panel.applyWorkbenchSendSnapshot(requestA, collection, snapshotA);
        harness.panel.openRequestInEditor(requestB, collection);
        harness.panel.applyWorkbenchSendSnapshot(requestB, collection, snapshotB);
        harness.panel.openRequestInEditor(requestC, collection);
        harness.panel.applyWorkbenchSendSnapshot(requestC, collection, snapshotC);

        ApiRequest duplicate = request("req-a-copy", "Request A Copy");
        harness.panel.openRequestInEditor(duplicate, collection);
        assertThat(harness.panel.getWorkbenchDetailMetaTextForTest()).contains("Not yet sent");
        assertThat(harness.panel.getWorkbenchSendSnapshot(duplicate)).isNull();
        assertThat(harness.panel.getWorkbenchSendSnapshot(requestA)).isNotNull();

        harness.panel.removeWorkbenchSendSnapshot(requestA);
        assertThat(harness.panel.getWorkbenchSendSnapshot(requestA)).isNull();

        harness.panel.removeWorkbenchSendSnapshotsForRequests(List.of(requestB));
        assertThat(harness.panel.getWorkbenchSendSnapshot(requestB)).isNull();

        harness.panel.removeWorkbenchSendSnapshotsForRequests(collection.requests);
        assertThat(harness.panel.getWorkbenchSendSnapshot(requestC)).isNull();
    }

    @Test
    void retainedSnapshotOwnsNoHistoryPayloadEvidence() {
        TestHarness harness = newHarness();
        ApiRequest request = request("req-heavy", "Heavy Request");
        ApiCollection collection = collection("APIM", request);
        harness.panel.restoreWorkspaceCollections(List.of(collection));
        harness.panel.openRequestInEditor(request, collection);
        ImporterPanel.WorkbenchSendSnapshot snapshot = snapshot("HEAVY");

        harness.panel.applyWorkbenchSendSnapshot(request, collection, snapshot);

        ImporterPanel.WorkbenchSendSnapshot retained = harness.panel.getWorkbenchSendSnapshot(request);
        assertThat(ImporterPanel.WorkbenchSendSnapshot.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getType)
                .doesNotContain(
                        burp.api.montoya.http.message.requests.HttpRequest.class,
                        burp.api.montoya.http.message.responses.HttpResponse.class,
                        burp.history.HistoryEntry.class,
                        byte[].class);
        assertThat(retained.historyEntryId).isNull();
        assertThat(harness.panel.getWorkbenchDetailMetaTextForTest())
                .contains("Payload evidence is no longer retained in History.");
    }

    @Test
    void twoHundredFiftySnapshotsRetainOnlyHistoryIdsAndSurviveHistoryClear() {
        TestHarness harness = newHarness();
        List<ApiRequest> requests = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            requests.add(request("req-" + i, "Request " + i));
        }
        ApiCollection collection = collection("APIM", requests.toArray(ApiRequest[]::new));
        harness.panel.restoreWorkspaceCollections(List.of(collection));

        for (int i = 0; i < requests.size(); i++) {
            burp.history.HistoryEntry entry = new burp.history.HistoryEntry();
            entry.id = "history-" + i;
            entry.source = burp.history.HistorySource.WORKBENCH;
            entry.requestId = requests.get(i).id;
            entry.requestSnapshot = new burp.history.HistoryRequestSnapshot();
            entry.requestSnapshot.rawRequestSent = new byte[]{1, 2, 3};
            entry.ensureDefaults();
            String historyId = harness.panel.getHistoryStoreForTests().admitEntry(entry).storedEntryId();
            ImporterPanel.WorkbenchSendSnapshot snapshot = new ImporterPanel.WorkbenchSendSnapshot(
                    historyId, "META " + i, "", "", null, "Send", i);
            harness.panel.applyWorkbenchSendSnapshot(requests.get(i), collection, snapshot);
        }

        assertThat(requests).allSatisfy(request -> {
            ImporterPanel.WorkbenchSendSnapshot snapshot = harness.panel.getWorkbenchSendSnapshot(request);
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.historyEntryId).isNotBlank();
        });
        assertThat(ImporterPanel.WorkbenchSendSnapshot.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getType)
                .doesNotContain(burp.history.HistoryEntry.class, byte[].class);

        harness.panel.getHistoryStoreForTests().clear();
        harness.panel.openRequestInEditor(requests.get(249), collection);
        assertThat(harness.panel.getWorkbenchDetailMetaTextForTest())
                .contains("Payload evidence is no longer retained in History.");
    }

    private static TestHarness newHarness() {
        UniversalImporter importer = mock(UniversalImporter.class);
        MontoyaApi api = mock(MontoyaApi.class);
        UserInterface userInterface = mock(UserInterface.class);
        HttpRequestEditor workbenchRequestEditor = mock(HttpRequestEditor.class);
        HttpResponseEditor workbenchResponseEditor = mock(HttpResponseEditor.class);
        when(workbenchRequestEditor.uiComponent()).thenReturn(new JPanel());
        when(workbenchResponseEditor.uiComponent()).thenReturn(new JPanel());
        when(userInterface.createHttpRequestEditor(Mockito.any())).thenReturn(workbenchRequestEditor);
        when(userInterface.createHttpResponseEditor(Mockito.any())).thenReturn(workbenchResponseEditor);
        when(api.userInterface()).thenReturn(userInterface);
        when(importer.getApi()).thenReturn(api);
        CollectionRunner runner = mock(CollectionRunner.class);
        OAuth2Manager oauth2Manager = mock(OAuth2Manager.class);
        ImporterPanel panel = new ImporterPanel(importer, runner, oauth2Manager, ScriptMode.FULL_JS);
        return new TestHarness(panel, workbenchRequestEditor, workbenchResponseEditor);
    }

    private static ApiCollection collection(String name, ApiRequest... requests) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        collection.requests = new ArrayList<>(List.of(requests));
        for (ApiRequest request : collection.requests) {
            request.sourceCollection = name;
        }
        return collection;
    }

    private static ApiRequest request(String id, String name) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.method = "GET";
        request.url = "https://api.example.test/" + id;
        request.path = "";
        request.sourceCollection = "APIM";
        return request;
    }

    private static ImporterPanel.WorkbenchSendSnapshot snapshot(String label) {
        return new ImporterPanel.WorkbenchSendSnapshot(
                "META " + label, null, "Send", 123L);
    }

    private record TestHarness(ImporterPanel panel,
                               HttpRequestEditor workbenchRequestEditor,
                               HttpResponseEditor workbenchResponseEditor) {
    }
}
