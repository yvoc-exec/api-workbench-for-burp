package burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
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
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void cleanupPersistsRequestEditorStateBeforeFinalWorkspaceSave() throws Exception {
        WorkspaceSaveFixture fixture = newFixtureWithBearerRequest();
        fixture.writeCount.set(0);
        fixture.lastJson.set(null);

        AtomicReference<Boolean> shutdownSeenDuringSave = new AtomicReference<>(Boolean.FALSE);
        Mockito.reset(fixture.persistedObject);
        Mockito.doAnswer(inv -> {
            fixture.writeCount.incrementAndGet();
            fixture.lastJson.set(inv.getArgument(1));
            shutdownSeenDuringSave.set((Boolean) privateField(fixture.ui, "shuttingDown"));
            return null;
        }).when(fixture.persistedObject).setString(Mockito.anyString(), Mockito.anyString());

        fixture.importer.cleanup();
        awaitWriteCount(fixture.writeCount, 1);
        assertThat(shutdownSeenDuringSave.get()).isTrue();
    }

    @Test
    void structuralCollectionChangesPersistImmediatelyIncludingExpandedTreeState() throws Exception {
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
        setDebounceDelay(importer, 5000);
        ImporterPanel ui = importer.getUI();

        Path tempJson = Files.createTempFile(Path.of("target"), "nested-postman-", ".json").toAbsolutePath().normalize();
        Files.writeString(tempJson, """
                {
                  "info": {
                    "name": "Nested Demo",
                    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
                  },
                  "item": [
                    {
                      "name": "Auth",
                      "item": [
                        {
                          "name": "OAuth",
                          "item": [
                            {
                              "name": "Get Token",
                              "request": {
                                "method": "GET",
                                "url": "https://auth.example.test/token"
                              }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);
        tempJson.toFile().deleteOnExit();

        invokePrivateLoadCollection(ui, tempJson.toFile());
        awaitWriteCount(writeCount, 1);

        WorkspaceState imported = WorkspaceStateJson.fromJson(lastJson.get());
        assertThat(imported.collections).hasSize(1);
        assertThat(imported.collections.get(0).requests).hasSize(1);
        assertThat(imported.collections.get(0).requests.get(0).path).isEqualTo("Auth/OAuth/Get Token");
        assertThat(imported.requestTreePaths).containsValue("Auth/OAuth");

        JTree tree = requestTree(ui);
        TreePath oauthPath = findFolderPath(tree, "Nested Demo", "Auth", "OAuth");
        assertThat(oauthPath).isNotNull();

        SwingUtilities.invokeAndWait(() -> tree.collapsePath(oauthPath));
        awaitWriteCount(writeCount, 2);

        SwingUtilities.invokeAndWait(() -> tree.expandPath(oauthPath));
        awaitWriteCount(writeCount, 3);

        WorkspaceState afterExpansion = WorkspaceStateJson.fromJson(lastJson.get());
        assertThat(afterExpansion.expandedTreePathKeys).contains(
                workspaceTreePathKey("Nested Demo", "Auth/OAuth")
        );

        @SuppressWarnings("unchecked")
        List<ApiCollection> liveCollections = (List<ApiCollection>) privateField(ui, "loadedCollections");
        invokePrivateRemoveCollections(ui, List.of(liveCollections.get(0)));
        awaitWriteCount(writeCount, 4);

        WorkspaceState afterRemoval = WorkspaceStateJson.fromJson(lastJson.get());
        assertThat(afterRemoval.collections).isEmpty();
    }

    @Test
    void restoredNormalizedTreePathsArePersistedOnceAfterFinalization() throws Exception {
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
        setDebounceDelay(importer, 5000);
        ImporterPanel ui = importer.getUI();

        WorkspaceState state = nestedWorkspaceState();
        applyWorkspaceRequestTreePathsToRequests(state.collections, state.requestTreePaths);
        ui.restoreWorkspaceCollections(state.collections);

        Object pendingRestore = createPendingRestore(ui, state);
        setField(pendingRestore, "repairedRequestPathCount", 1);

        Method finalizeMethod = ImporterPanel.class.getDeclaredMethod("finalizeRestoredMainRequestTree",
                pendingRestore.getClass());
        finalizeMethod.setAccessible(true);
        finalizeMethod.invoke(ui, pendingRestore);

        awaitWriteCount(writeCount, 1);

        WorkspaceState saved = WorkspaceStateJson.fromJson(lastJson.get());
        assertThat(saved.collections).hasSize(1);
        assertThat(saved.collections.get(0).requests).hasSize(1);
        assertThat(saved.collections.get(0).requests.get(0).path).isEqualTo("Auth/OAuth/Get Token");
        assertThat(saved.requestTreePaths).containsEntry(
                workspaceRequestTreePathKey("APIM", 0, saved.collections.get(0).requests.get(0), 0),
                "Auth/OAuth"
        );
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
    void workspaceSnapshotIncludesEnvironmentProfilesAndActiveSelection() throws Exception {
        ImporterPanel ui = newImporterUi();
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = "UAT";
        profile.variables.put("baseUrl", "https://uat.example.test");
        profile.oauth2.config.put("oauth2_client_id", "client-id");
        ui.replaceEnvironmentProfiles(List.of(profile));
        ui.setActiveEnvironmentId(profile.id);

        WorkspaceState snapshot = ui.getWorkspaceStateSnapshot();

        assertThat(snapshot.environments).hasSize(1);
        assertThat(snapshot.activeEnvironmentId).isEqualTo(profile.id);
        assertThat(snapshot.environments.get(0).variables).containsEntry("baseUrl", "https://uat.example.test");
        assertThat(snapshot.environments.get(0).oauth2.config).containsEntry("oauth2_client_id", "client-id");
    }

    @Test
    void workspaceRestoreRestoresActiveEnvironmentSelection() throws Exception {
        ImporterPanel ui = newImporterUi();
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = "PRD";
        profile.variables.put("baseUrl", "https://prd.example.test");
        profile.ensureId();
        WorkspaceState state = new WorkspaceState();
        state.collections = List.of(new ApiCollection());
        state.environments = List.of(profile);
        state.activeEnvironmentId = profile.id;

        ui.restoreWorkspaceState(state);
        SwingUtilities.invokeAndWait(() -> { });

        WorkspaceState snapshot = ui.getWorkspaceStateSnapshot();
        assertThat(snapshot.activeEnvironmentId).isEqualTo(profile.id);
        assertThat(snapshot.environments).hasSize(1);
        assertThat(snapshot.environments.get(0).variables).containsEntry("baseUrl", "https://prd.example.test");
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

        return new WorkspaceSaveFixture(importer, ui, requestEditor, persistedObject, writeCount, lastJson);
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

    private static void invokePrivateLoadCollection(ImporterPanel ui, File file) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("loadCollection", File.class);
        method.setAccessible(true);
        method.invoke(ui, file);
    }

    private static void invokePrivateRemoveCollections(ImporterPanel ui, List<ApiCollection> targets) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("removeCollections", List.class);
        method.setAccessible(true);
        method.invoke(ui, targets);
    }

    private static Object createPendingRestore(ImporterPanel ui, WorkspaceState state) throws Exception {
        Class<?> pendingClass = Class.forName("burp.ui.ImporterPanel$PendingMainRequestTreeRestore");
        java.lang.reflect.Constructor<?> ctor = pendingClass.getDeclaredConstructor(WorkspaceState.class);
        ctor.setAccessible(true);
        return ctor.newInstance(state);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void applyWorkspaceRequestTreePathsToRequests(List<ApiCollection> collections,
                                                                 java.util.Map<String, String> requestTreePaths) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("applyWorkspaceRequestTreePathsToRequests", List.class, java.util.Map.class);
        method.setAccessible(true);
        method.invoke(null, collections, requestTreePaths);
    }

    private static String workspaceTreePathKey(String collectionName, String folderPath) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("workspaceTreePathKey", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, collectionName, folderPath);
    }

    private static String workspaceRequestTreePathKey(String collectionName, int collectionIndex, ApiRequest request, int requestIndex) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("workspaceRequestTreePathKey", String.class, int.class, ApiRequest.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(null, collectionName, collectionIndex, request, requestIndex);
    }

    private static WorkspaceState nestedWorkspaceState() throws Exception {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Get Token";
        request.path = "Get Token";
        request.sourceCollection = collection.name;
        request.method = "GET";
        request.url = "https://auth.example.test/token";
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new java.util.LinkedHashMap<>();
        state.requestTreePaths.put(
                workspaceRequestTreePathKey("APIM", 0, state.collections.get(0).requests.get(0), 0),
                "Auth/OAuth"
        );
        return state;
    }

    private static JTree requestTree(ImporterPanel ui) throws Exception {
        Field field = ImporterPanel.class.getDeclaredField("requestTree");
        field.setAccessible(true);
        return (JTree) field.get(ui);
    }

    private static TreePath findFolderPath(JTree tree, String collectionName, String... folders) {
        if (tree == null || tree.getModel() == null || tree.getModel().getRoot() == null) {
            return null;
        }
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            Object child = root.getChildAt(i);
            if (!(child instanceof burp.ui.tree.CollectionTreeNode)) {
                continue;
            }
            burp.ui.tree.CollectionTreeNode collectionNode = (burp.ui.tree.CollectionTreeNode) child;
            if (!collectionName.equals(collectionNode.collection != null ? collectionNode.collection.name : null)) {
                continue;
            }
            return findFolderPathRecursive(new TreePath(collectionNode.getPath()), collectionNode, folders, 0);
        }
        return null;
    }

    private static TreePath findFolderPathRecursive(TreePath currentPath,
                                                    burp.ui.tree.CollectionTreeNode currentNode,
                                                    String[] folders,
                                                    int depth) {
        if (depth >= folders.length) {
            return currentPath;
        }
        String nextFolder = folders[depth];
        for (int i = 0; i < currentNode.getChildCount(); i++) {
            Object child = currentNode.getChildAt(i);
            if (!(child instanceof burp.ui.tree.CollectionTreeNode)) {
                continue;
            }
            burp.ui.tree.CollectionTreeNode folderNode = (burp.ui.tree.CollectionTreeNode) child;
            if (folderNode.getNodeType() != burp.ui.tree.CollectionTreeNode.Type.FOLDER) {
                continue;
            }
            String folderLeafName = folderNode.folderPath != null
                    ? folderNode.folderPath.substring(folderNode.folderPath.lastIndexOf('/') + 1)
                    : null;
            if (!nextFolder.equals(folderLeafName)) {
                continue;
            }
            TreePath childPath = currentPath.pathByAddingChild(folderNode);
            TreePath match = findFolderPathRecursive(childPath, folderNode, folders, depth + 1);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static Object privateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private record WorkspaceSaveFixture(UniversalImporter importer,
                                        ImporterPanel ui,
                                        RequestEditorPanel requestEditor,
                                        PersistedObject persistedObject,
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

    private static ImporterPanel newImporterUi() throws Exception {
        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, new WorkspaceStateService(Mockito.mock(PersistedObject.class)));
        return importer.getUI();
    }
}
