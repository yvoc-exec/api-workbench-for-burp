package burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspaceState;
import burp.utils.DebouncedSwingAction;
import burp.utils.WorkspaceStateJson;
import burp.utils.WorkspaceStateService;
import burp.ui.ImporterPanel;
import burp.ui.RequestEditorPanel;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class UniversalImporterWorkspaceSaveTest {

    @Test
    void rapidWorkspaceChangeRequestsCollapseIntoSingleWrite() throws Exception {
        AtomicInteger writeCount = new AtomicInteger(0);
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            writeCount.incrementAndGet();
            return null;
        }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);

        // Speed up debounce for testing
        setDebounceDelay(importer, 100);

        // Trigger multiple rapid save requests
        for (int i = 0; i < 10; i++) {
            importer.requestWorkspaceStateSave();
        }

        assertThat(writeCount.get()).isZero();

        // Wait for debounce to fire
        Thread.sleep(200);

        assertThat(writeCount.get()).isEqualTo(1);
    }

    @Test
    void unchangedWorkspaceStateDoesNotWriteAgain() throws Exception {
        AtomicInteger writeCount = new AtomicInteger(0);
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            writeCount.incrementAndGet();
            return null;
        }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);

        // First save should write
        importer.saveWorkspaceState();
        assertThat(writeCount.get()).isEqualTo(1);

        // Second save with identical state should skip
        importer.saveWorkspaceState();
        assertThat(writeCount.get()).isEqualTo(1);
    }

    @Test
    void cleanupFlushesPendingWorkspaceSave() throws Exception {
        AtomicInteger writeCount = new AtomicInteger(0);
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            writeCount.incrementAndGet();
            return null;
        }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);

        // Speed up debounce for testing
        setDebounceDelay(importer, 5000);

        // Request a save but don't let the timer fire
        importer.requestWorkspaceStateSave();
        assertThat(writeCount.get()).isZero();

        // Cleanup should flush immediately
        importer.cleanup();
        assertThat(writeCount.get()).isEqualTo(1);

        // Timer should no longer fire
        Thread.sleep(100);
        assertThat(writeCount.get()).isEqualTo(1);
    }

    @Test
    void workspaceRestoreWaitsUntilUiRegistrationHook() throws Exception {
        AtomicInteger readCount = new AtomicInteger(0);
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            readCount.incrementAndGet();
            return null;
        }).when(persistedObject).getString(Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);

        assertThat(readCount.get()).isZero();

        Method restoreWorkspaceStateAfterUiRegistration = UniversalImporter.class.getDeclaredMethod("restoreWorkspaceStateAfterUiRegistration");
        restoreWorkspaceStateAfterUiRegistration.setAccessible(true);
        restoreWorkspaceStateAfterUiRegistration.invoke(importer);
        SwingUtilities.invokeAndWait(() -> { });

        assertThat(readCount.get()).isEqualTo(1);
    }

    @Test
    void deletingAuthorizationTriggersImmediateWorkspaceSave() throws Exception {
        WorkspaceSaveFixture fixture = newFixtureWithBearerRequest();
        fixture.writeCount.set(0);
        fixture.lastJson.set(null);

        removeHeaderRow(fixture.requestEditor, "Authorization");
        awaitWriteCount(fixture.writeCount, 1);

        WorkspaceState saved = WorkspaceStateJson.fromJson(fixture.lastJson.get());
        ApiRequest savedRequest = saved.collections.get(0).requests.get(0);
        assertThat(savedRequest.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(savedRequest.editorMaterialized).isTrue();
        assertThat(savedRequest.suppressedAutoHeaders).containsExactly("authorization");
        assertThat(savedRequest.headers).extracting(h -> h.key).doesNotContain("Authorization");
    }

    @Test
    void workspaceJsonAfterDeletingAuthorizationHasSuppressionAndNoAuthorization() throws Exception {
        WorkspaceSaveFixture fixture = newFixtureWithBearerRequest();
        fixture.writeCount.set(0);
        fixture.lastJson.set(null);

        removeHeaderRow(fixture.requestEditor, "Authorization");
        awaitWriteCount(fixture.writeCount, 1);

        assertThat(fixture.lastJson.get()).contains("\"suppressedAutoHeaders\"");
        assertThat(fixture.lastJson.get()).contains("\"authorization\"");
        assertThat(fixture.lastJson.get()).doesNotContain("\"Authorization\"");
    }

    @Test
    void manualReaddedAuthorizationTriggersImmediateWorkspaceSaveAndClearsSuppression() throws Exception {
        WorkspaceSaveFixture fixture = newFixtureWithBearerRequest();
        fixture.writeCount.set(0);
        fixture.lastJson.set(null);

        removeHeaderRow(fixture.requestEditor, "Authorization");
        awaitWriteCount(fixture.writeCount, 1);

        headersModel(fixture.requestEditor).addRow(new Object[]{"Authorization", "Bearer tok123"});
        awaitWriteCount(fixture.writeCount, 2);

        WorkspaceState saved = WorkspaceStateJson.fromJson(fixture.lastJson.get());
        ApiRequest savedRequest = saved.collections.get(0).requests.get(0);
        assertThat(savedRequest.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(savedRequest.editorMaterialized).isTrue();
        assertThat(savedRequest.suppressedAutoHeaders).doesNotContain("authorization");
        assertThat(savedRequest.headers).extracting(h -> h.key).contains("Authorization");
    }

    private static WorkspaceSaveFixture newFixtureWithBearerRequest() throws Exception {
        AtomicInteger writeCount = new AtomicInteger(0);
        AtomicReference<String> lastJson = new AtomicReference<>();
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            writeCount.incrementAndGet();
            lastJson.set(inv.getArgument(1));
            return null;
        }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);
        ImporterPanel ui = importer.getUI();

        ApiCollection collection = bearerCollection();
        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        ui.restoreWorkspaceState(state);
        SwingUtilities.invokeAndWait(() -> { });

        RequestEditorPanel requestEditor = requestEditor(ui);
        ApiCollection liveCollection = liveCollection(ui);
        ApiRequest liveRequest = liveCollection.requests.get(0);
        requestEditor.setCurrentCollection(liveCollection);
        requestEditor.loadRequest(liveRequest);
        SwingUtilities.invokeAndWait(() -> { });

        return new WorkspaceSaveFixture(importer, ui, requestEditor, writeCount, lastJson);
    }

    private static ApiCollection bearerCollection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "AuthTest";

        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Get Secret";
        request.path = "Auth/OAuth/Get Secret";
        request.sourceCollection = collection.name;
        request.method = "GET";
        request.url = "https://api.example.test/secret";
        request.auth = new ApiRequest.Auth();
        request.auth.type = "bearer";
        request.auth.properties.put("token", "tok123");
        collection.requests.add(request);
        return collection;
    }

    private static ApiCollection liveCollection(ImporterPanel ui) throws Exception {
        Field f = ImporterPanel.class.getDeclaredField("loadedCollections");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<ApiCollection> collections = (java.util.List<ApiCollection>) f.get(ui);
        return collections.get(0);
    }

    private static RequestEditorPanel requestEditor(ImporterPanel ui) throws Exception {
        Field f = ImporterPanel.class.getDeclaredField("requestEditor");
        f.setAccessible(true);
        return (RequestEditorPanel) f.get(ui);
    }

    private static DefaultTableModel headersModel(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("headersModel");
        f.setAccessible(true);
        return (DefaultTableModel) f.get(panel);
    }

    private static void removeHeaderRow(RequestEditorPanel panel, String key) throws Exception {
        DefaultTableModel model = headersModel(panel);
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < model.getRowCount(); i++) {
                String current = (String) model.getValueAt(i, 0);
                if (current != null && current.equalsIgnoreCase(key)) {
                    model.removeRow(i);
                    return;
                }
            }
        });
    }

    private static void awaitWriteCount(AtomicInteger writeCount, int expected) throws Exception {
        for (int i = 0; i < 20 && writeCount.get() < expected; i++) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(25);
        }
        assertThat(writeCount.get()).isEqualTo(expected);
    }

    private record WorkspaceSaveFixture(UniversalImporter importer,
                                        ImporterPanel ui,
                                        RequestEditorPanel requestEditor,
                                        AtomicInteger writeCount,
                                        AtomicReference<String> lastJson) {
    }

    private static MontoyaApi mockApi() {
        MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(api.userInterface().createHttpRequestEditor(Mockito.any(EditorOptions.class))).thenReturn(requestEditor);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(api.userInterface().createHttpResponseEditor(Mockito.any(EditorOptions.class))).thenReturn(responseEditor);
        return api;
    }

    private static void setDebounceDelay(UniversalImporter importer, int delayMs) throws Exception {
        Field debouncedField = UniversalImporter.class.getDeclaredField("debouncedWorkspaceSave");
        debouncedField.setAccessible(true);
        DebouncedSwingAction debounced = (DebouncedSwingAction) debouncedField.get(importer);
        Field timerField = DebouncedSwingAction.class.getDeclaredField("timer");
        timerField.setAccessible(true);
        Timer timer = (Timer) timerField.get(debounced);
        timer.setInitialDelay(delayMs);
        timer.setDelay(delayMs);
    }
}
