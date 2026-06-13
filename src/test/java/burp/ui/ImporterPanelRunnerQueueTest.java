package burp.ui;

import burp.UniversalImporter;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerPreviewRow;
import burp.models.RunnerResult;
import burp.models.WorkspaceState;
import burp.runner.CollectionRunner;
import burp.ui.dnd.EnvironmentDragPayload;
import burp.utils.ScriptMode;
import burp.utils.WorkspaceStateService;
import burp.ui.tree.CollectionTreeNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.HeadlessException;
import java.awt.Window;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelRunnerQueueTest {

    @Test
    void clearRunnerClearsResultsTimelineLogAndQueuedRequests() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("Checkout", request("Queued 1"), request("Queued 2"));
        panel.restoreWorkspaceCollections(List.of(collection));

        List<ApiRequest> queued = queue(panel, request("Queued 1"), request("Queued 2"));
        assertThat(queued).hasSize(2);

        RunnerResultHelper.addDummyRunnerData(panel);

        invokePrivate(panel, "clearRunnerFromUi");
        drainEdt();

        assertThat(queue(panel)).isEmpty();
        assertThat(resultModel(panel).getRowCount()).isZero();
        assertThat(timelineModel(panel).getRowCount()).isZero();
        assertThat(runnerLog(panel).getText()).isEmpty();
        assertThat(((JButton) privateField(panel, "startRunnerBtn")).isEnabled()).isFalse();
    }

    @Test
    void queueRunnerRequestsReplacesPriorQueueAndEnablesStart() throws Exception {
        ImporterPanel panel = newPanel();
        panel.restoreWorkspaceCollections(List.of(collection("Checkout", request("One"), request("Two"), request("Three"))));

        queue(panel, request("One"), request("Two"), request("Three"));
        assertThat(queue(panel)).extracting(r -> ((ApiRequest) r).name).containsExactly("One", "Two", "Three");

        queue(panel, request("Four"), request("Five"));
        assertThat(queue(panel)).extracting(r -> ((ApiRequest) r).name).containsExactly("Four", "Five");
        assertThat(((JButton) privateField(panel, "startRunnerBtn")).isEnabled()).isTrue();
    }

    @Test
    void startRunnerDoesNotRunWhenQueueCleared() throws Exception {
        CollectionRunner runner = Mockito.mock(CollectionRunner.class, Mockito.RETURNS_DEEP_STUBS);
        ImporterPanel panel = newPanel(runner);
        panel.restoreWorkspaceCollections(List.of(collection("Checkout", request("One"))));

        queue(panel, request("One"));
        invokePrivate(panel, "clearRunnerFromUi");
        drainEdt();

        invokePrivate(panel, "startRunner", new Class<?>[]{boolean.class}, false);
        drainEdt();

        Mockito.verify(runner, Mockito.never()).runCollections(Mockito.anyList(), Mockito.anyList());
        assertThat(runnerLog(panel).getText()).contains("No requests queued");
        assertThat(((JButton) privateField(panel, "startRunnerBtn")).isEnabled()).isFalse();
    }

    @Test
    void runnerPreviewRemoveSelectedRequestsUpdatesQueue() {
        List<ApiRequest> selected = new ArrayList<>(List.of(request("One"), request("Two"), request("Three")));
        List<RunnerPreviewRow> previewRows = new ArrayList<>(List.of(previewRow("One"), previewRow("Two"), previewRow("Three")));
        List<ApiRequest> queued = new ArrayList<>(selected);

        ImporterPanel.removeRunnerPreviewRows(selected, previewRows, queued, 1);

        assertThat(selected).extracting(r -> r.name).containsExactly("One", "Three");
        assertThat(previewRows).extracting(r -> r.requestName).containsExactly("One", "Three");
        assertThat(queued).extracting(r -> r.name).containsExactly("One", "Three");
    }

    @Test
    void runnerPreviewClearQueueClearsQueuedRequests() {
        List<ApiRequest> selected = new ArrayList<>(List.of(request("One"), request("Two")));
        List<RunnerPreviewRow> previewRows = new ArrayList<>(List.of(previewRow("One"), previewRow("Two")));
        List<ApiRequest> queued = new ArrayList<>(selected);

        ImporterPanel.clearRunnerPreviewQueue(selected, previewRows, queued);

        assertThat(selected).isEmpty();
        assertThat(previewRows).isEmpty();
        assertThat(queued).isEmpty();
    }

    @Test
    void oauth2AutoBindDefaultsUnchecked() throws Exception {
        ImporterPanel panel = newPanel();
        assertThat(oauth2Panel(panel).getAutoBindCheckBox().isSelected()).isFalse();

        EnvironmentProfile active = environment("UAT");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);
        drainEdt();

        assertThat(oauth2Panel(panel).getAutoBindCheckBox().isSelected()).isFalse();
    }

    @Test
    void runnerStartUsesActiveEnvironmentOverlayAndSelectedRequest() throws Exception {
        AtomicReference<Function<ApiCollection, Map<String, String>>> overlayProvider = new AtomicReference<>();
        CollectionRunner runner = Mockito.mock(CollectionRunner.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.doAnswer(inv -> {
            overlayProvider.set(inv.getArgument(0));
            return null;
        }).when(runner).setRuntimeOverlayProvider(Mockito.any());
        Mockito.when(runner.isRunning()).thenReturn(false);
        RunnerPreviewRow previewRow = previewRow("Login");
        previewRow.authStatus = "bearer";
        Mockito.when(runner.buildRunPreview(Mockito.anyList(), Mockito.anyList())).thenReturn(List.of(previewRow));
        ImporterPanel panel = newPanel(runner);

        EnvironmentProfile active = environment("UAT");
        active.variables.put("base_url", "https://uat.example.test");
        active.variables.put("token", "uat-token");

        ApiRequest request = request("Login");
        request.method = "POST";
        request.url = "{{base_url}}/users";
        request.path = "Auth";
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"login\":true}";
        request.auth = new ApiRequest.Auth();
        request.auth.type = "bearer";
        request.auth.properties.put("token", "{{token}}");
        ApiCollection collection = collection("APIM", request);

        edt(() -> panel.restoreWorkspaceCollections(List.of(collection)));
        drainEdt();
        edt(() -> {
            panel.replaceEnvironmentProfiles(List.of(active));
            panel.setActiveEnvironmentId(active.id);
        });
        drainEdt();
        assertThat(panel.getActiveEnvironmentId()).isEqualTo(active.id);
        assertThat(activeEnvironmentOverlay(panel)).containsEntry("base_url", "https://uat.example.test");
        assertThat(activeEnvironmentOverlay(panel)).containsEntry("token", "uat-token");

        invokeOnEdt(panel, "queueRunnerRequests", new Class<?>[]{List.class}, List.of(request));
        invokeOnEdt(panel, "startRunner", new Class<?>[]{boolean.class}, false);

        ArgumentCaptor<List<ApiCollection>> collectionsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ApiRequest>> requestsCaptor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(runner).runCollections(collectionsCaptor.capture(), requestsCaptor.capture());
        assertThat(requestsCaptor.getValue()).containsExactly(request);
        assertThat(collectionsCaptor.getValue()).containsExactly(collection);
        assertThat(overlayProvider.get()).isNotNull();
        assertThat(overlayProvider.get().apply(collection)).containsEntry("base_url", "https://uat.example.test");
        assertThat(overlayProvider.get().apply(collection)).containsEntry("token", "uat-token");
    }

    @Test
    void reorderRunnerQueueMovesFirstItemToLastAndPreservesIdentity() throws Exception {
        ImporterPanel panel = newPanel();
        ApiRequest one = request("One");
        ApiRequest two = request("Two");
        ApiRequest three = request("Three");
        panel.restoreWorkspaceCollections(List.of(collection("Checkout", one, two, three)));
        queue(panel, one, two, three);
        ApiRequest moved = queue(panel).get(0);

        boolean reordered = panel.reorderRunnerQueue(0, 3);
        drainEdt();

        assertThat(reordered).isTrue();
        assertThat(queue(panel)).extracting(req -> req.name).containsExactly("Two", "Three", "One");
        assertThat(queue(panel).get(2)).isSameAs(moved);
        assertThat(queueList(panel).getSelectedValue()).isSameAs(moved);
    }

    @Test
    void reorderRunnerQueueMovesLastItemToFirst() throws Exception {
        ImporterPanel panel = newPanel();
        ApiRequest one = request("One");
        ApiRequest two = request("Two");
        ApiRequest three = request("Three");
        panel.restoreWorkspaceCollections(List.of(collection("Checkout", one, two, three)));
        queue(panel, one, two, three);
        ApiRequest moved = queue(panel).get(2);

        boolean reordered = panel.reorderRunnerQueue(2, 0);
        drainEdt();

        assertThat(reordered).isTrue();
        assertThat(queue(panel)).extracting(req -> req.name).containsExactly("Three", "One", "Two");
        assertThat(queue(panel).get(0).name).isEqualTo(moved.name);
        assertThat(((ApiRequest) queueList(panel).getSelectedValue()).name).isEqualTo(moved.name);
    }

    @Test
    void reorderRunnerQueueMovesMiddleItemUpAndDown() throws Exception {
        ImporterPanel panel = newPanel();
        ApiRequest one = request("One");
        ApiRequest two = request("Two");
        ApiRequest three = request("Three");
        panel.restoreWorkspaceCollections(List.of(collection("Checkout", one, two, three)));
        queue(panel, one, two, three);

        ApiRequest movedUp = queue(panel).get(1);
        assertThat(panel.reorderRunnerQueue(1, 0)).isTrue();
        drainEdt();
        assertThat(queue(panel)).extracting(req -> req.name).containsExactly("Two", "One", "Three");
        assertThat(queue(panel).get(0)).isSameAs(movedUp);

        ApiRequest movedDown = queue(panel).get(1);
        assertThat(panel.reorderRunnerQueue(1, 3)).isTrue();
        drainEdt();
        assertThat(queue(panel)).extracting(req -> req.name).containsExactly("Two", "Three", "One");
        assertThat(queue(panel).get(2)).isSameAs(movedDown);
    }

    @Test
    void reorderRunnerQueueNoOpKeepsQueueUnchanged() throws Exception {
        ImporterPanel panel = newPanel();
        ApiRequest one = request("One");
        ApiRequest two = request("Two");
        ApiRequest three = request("Three");
        panel.restoreWorkspaceCollections(List.of(collection("Checkout", one, two, three)));
        queue(panel, one, two, three);

        assertThat(panel.reorderRunnerQueue(1, 2)).isTrue();
        drainEdt();
        assertThat(queue(panel)).extracting(req -> req.name).containsExactly("One", "Two", "Three");
    }

    @Test
    void reorderRunnerQueueRejectsInvalidSourceIndex() throws Exception {
        ImporterPanel panel = newPanel();
        queue(panel, request("One"), request("Two"));

        assertThat(panel.reorderRunnerQueue(-1, 1)).isFalse();
        assertThat(panel.reorderRunnerQueue(5, 0)).isFalse();
        assertThat(queue(panel)).extracting(req -> req.name).containsExactly("One", "Two");
    }

    @Test
    void reorderRunnerQueueClampsLargeTargetIndexToEnd() throws Exception {
        ImporterPanel panel = newPanel();
        ApiRequest one = request("One");
        ApiRequest two = request("Two");
        ApiRequest three = request("Three");
        panel.restoreWorkspaceCollections(List.of(collection("Checkout", one, two, three)));
        queue(panel, one, two, three);

        assertThat(panel.reorderRunnerQueue(0, 99)).isTrue();
        drainEdt();
        assertThat(queue(panel)).extracting(req -> req.name).containsExactly("Two", "Three", "One");
    }

    @Test
    void reorderRunnerQueueRejectedWhileRunnerRunning() throws Exception {
        CollectionRunner runner = Mockito.mock(CollectionRunner.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(runner.isRunning()).thenReturn(true);
        ImporterPanel panel = newPanel(runner);
        ApiRequest one = request("One");
        ApiRequest two = request("Two");
        panel.restoreWorkspaceCollections(List.of(collection("Checkout", one, two)));
        queue(panel, one, two);

        assertThat(panel.reorderRunnerQueue(0, 1)).isFalse();
        drainEdt();
        assertThat(queue(panel)).extracting(req -> req.name).containsExactly("One", "Two");
        assertThat(runnerLog(panel).getText()).contains("Runner queue cannot be reordered while running.");
    }

    @Test
    void runnerQueueOrderPersistsInWorkspaceStateSnapshotAndRestore() throws Exception {
        ImporterPanel panel = newPanel();
        ApiRequest one = request("One");
        ApiRequest two = request("Two");
        ApiRequest three = request("Three");
        panel.restoreWorkspaceCollections(List.of(collection("Checkout", one, two, three)));
        queue(panel, one, two, three);
        panel.reorderRunnerQueue(0, 3);
        drainEdt();

        WorkspaceState snapshot = panel.getWorkspaceStateSnapshot();
        assertThat(snapshot.runnerQueuedRequestIdentityKeys).hasSize(3);
        assertThat(snapshot.runnerQueuedRequestIdentityKeys.get(0)).contains("Two");
        assertThat(snapshot.runnerQueuedRequestIdentityKeys.get(2)).contains("One");

        ImporterPanel restored = newPanel();
        restored.restoreWorkspaceState(snapshot);
        drainEdt();

        assertThat(queue(restored)).extracting(req -> req.name).containsExactly("Two", "Three", "One");
        assertThat(queueList(restored).getSelectedValue()).isSameAs(queue(restored).get(0));
    }

    @Test
    void runnerQueueRestoreSkipsMissingRequestIdentityKeysSafely() throws Exception {
        ImporterPanel panel = newPanel();
        ApiRequest one = request("One");
        ApiRequest two = request("Two");
        panel.restoreWorkspaceCollections(List.of(collection("Checkout", one, two)));
        queue(panel, one, two);

        WorkspaceState snapshot = panel.getWorkspaceStateSnapshot();
        ApiRequest missing = request("Missing");
        missing.id = "missing-request";
        snapshot.runnerQueuedRequestIdentityKeys = new ArrayList<>(snapshot.runnerQueuedRequestIdentityKeys);
        snapshot.runnerQueuedRequestIdentityKeys.add(ImporterPanel.workspaceRequestIdentityKey("Checkout", missing, 99));

        ImporterPanel restored = newPanel();
        restored.restoreWorkspaceState(snapshot);
        drainEdt();

        assertThat(queue(restored)).extracting(req -> req.name).containsExactly("One", "Two");
        assertThat(((JButton) privateField(restored, "startRunnerBtn")).isEnabled()).isTrue();
    }

    @Test
    void runnerQueueRestoreWithOnlyMissingRequestIdentityKeysLeavesStartDisabled() throws Exception {
        ImporterPanel panel = newPanel();
        ApiRequest one = request("One");
        panel.restoreWorkspaceCollections(List.of(collection("Checkout", one)));

        WorkspaceState snapshot = panel.getWorkspaceStateSnapshot();
        ApiRequest missing = request("Missing");
        missing.id = "missing-request";
        snapshot.runnerQueuedRequestIdentityKeys = List.of(
                ImporterPanel.workspaceRequestIdentityKey("Checkout", missing, 0)
        );

        ImporterPanel restored = newPanel();
        restored.restoreWorkspaceState(snapshot);
        drainEdt();

        assertThat(queue(restored)).isEmpty();
        assertThat(((JButton) privateField(restored, "startRunnerBtn")).isEnabled()).isFalse();
    }

    @Test
    void environmentDropActiveEnvironmentAndRunnerReorderWorkflowUsesExpectedQueueAndOverlay() throws Exception {
        AtomicReference<Function<ApiCollection, Map<String, String>>> overlayProvider = new AtomicReference<>();
        CollectionRunner runner = Mockito.mock(CollectionRunner.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.doAnswer(invocation -> {
            overlayProvider.set(invocation.getArgument(0));
            return null;
        }).when(runner).setRuntimeOverlayProvider(Mockito.any());
        Mockito.when(runner.isRunning()).thenReturn(false);
        Mockito.when(runner.buildRunPreview(Mockito.anyList(), Mockito.anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ApiRequest> selected = invocation.getArgument(1);
            List<RunnerPreviewRow> previewRows = new ArrayList<>();
            for (ApiRequest request : selected) {
                RunnerPreviewRow row = new RunnerPreviewRow();
                row.requestName = request.name;
                row.collectionName = request.sourceCollection;
                row.method = request.method;
                row.urlPreview = request.url;
                row.authStatus = "bearer";
                previewRows.add(row);
            }
            return previewRows;
        });
        ImporterPanel panel = newPanel(runner);

        ApiRequest one = request("One");
        one.url = "{{base_url}}/one";
        ApiRequest two = request("Two");
        two.url = "{{base_url}}/two";
        ApiRequest three = request("Three");
        three.url = "{{base_url}}/three";
        ApiCollection collection = collection("Checkout", one, two, three);
        edt(() -> panel.restoreWorkspaceCollections(List.of(collection)));
        drainEdt();

        Path envFile = tempFile("UAT.env", """
                base_url=https://uat.example.test
                token=uat-token
                """);
        panel.importEnvironmentFilesDropped(List.of(envFile.toFile()));
        drainEdt();

        assertThat(panel.getEnvironmentProfilesSnapshot()).hasSize(1);
        EnvironmentProfile imported = panel.getEnvironmentProfilesSnapshot().get(0);
        boolean activated = panel.activateEnvironmentFromDrop(new EnvironmentDragPayload(imported.id, imported.displayName()));
        drainEdt();

        assertThat(activated).isTrue();
        assertThat(panel.getActiveEnvironmentId()).isEqualTo(imported.id);
        assertThat(panel.getEnvironmentProfilesSnapshot()).hasSize(1);

        queue(panel, one, two, three);
        assertThat(panel.reorderRunnerQueue(0, 3)).isTrue();
        drainEdt();
        assertThat(queue(panel)).extracting(req -> req.name).containsExactly("Two", "Three", "One");

        invokePrivate(panel, "startRunner", new Class<?>[]{boolean.class}, false);

        ArgumentCaptor<List<ApiCollection>> collectionsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ApiRequest>> requestsCaptor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(runner).runCollections(collectionsCaptor.capture(), requestsCaptor.capture());
        assertThat(collectionsCaptor.getValue()).containsExactly(collection);
        assertThat(requestsCaptor.getValue()).extracting(req -> req.name).containsExactly("Two", "Three", "One");

        assertThat(overlayProvider.get()).isNotNull();
        assertThat(overlayProvider.get().apply(collection))
                .containsEntry("base_url", "https://uat.example.test")
                .containsEntry("token", "uat-token");
        assertThat(panel.getActiveEnvironmentId()).isEqualTo(imported.id);
    }

    @Test
    void blankUrlRunnerFailsSafelyWithoutCorruptingState() throws Exception {
        MontoyaApi api = mockApi(true);
        ImporterPanel panel = newRealPanel(api);

        ApiRequest request = request("Blank");
        request.url = "";
        request.path = "Auth";
        request.auth = new ApiRequest.Auth();
        request.auth.type = "bearer";
        ApiCollection collection = collection("APIM", request);

        edt(() -> panel.restoreWorkspaceCollections(List.of(collection)));
        drainEdt();
        invokeOnEdt(panel, "queueRunnerRequests", new Class<?>[]{List.class}, List.of(request));
        invokeOnEdt(panel, "startRunner", new Class<?>[]{boolean.class}, false);

        awaitCondition("blank-url runner result", () -> {
            try {
                return resultModel(panel).getRowCount() == 1;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        drainEdt();

        RunnerResult result = resultModel(panel).getResultAt(0);
        assertThat(result.success).isFalse();
        assertThat(result.errorMessage).contains("Request URL cannot be null or empty");
        assertThat(runnerLog(panel).getText()).contains("FAIL").contains("Request URL cannot be null or empty");
        assertThat(request.url).isEmpty();
        assertThat(request.method).isEqualTo("GET");
        assertThat(requestNode(requestTree(panel), "Blank")).isNotNull();
        assertThat(queue(panel)).containsExactly(request);
    }

    @Test
    void blankUrlImportCheckedFailsSafelyWithoutCorruptingState() throws Exception {
        MontoyaApi api = mockApi(true);
        ImporterPanel panel = newRealPanel(api);

        ApiRequest request = request("Blank");
        request.url = "";
        request.path = "Auth";
        ApiCollection collection = collection("APIM", request);

        edt(() -> panel.restoreWorkspaceCollections(List.of(collection)));
        drainEdt();
        invokeOnEdt(panel, "startImport", new Class<?>[]{List.class, List.class, int.class},
                List.of(request), List.of("sitemap"), 0);

        awaitCondition("blank-url import failure log", () -> {
            try {
                return importLog(panel).getText().contains("[FAIL]")
                        || importLog(panel).getText().contains("Request URL cannot be null or empty");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        drainEdt();

        assertThat(importLog(panel).getText()).contains("Request URL cannot be null or empty");
        assertThat(request.url).isEmpty();
        assertThat(request.method).isEqualTo("GET");
        assertThat(folderNodeByPath(requestTree(panel), "Auth")).isNotNull();
        assertThat(requestNode(requestTree(panel), "Blank")).isNotNull();
    }

    @Test
    void deleteOperationsAreRejectedWhileRunnerIsRunning() throws Exception {
        CollectionRunner runner = Mockito.mock(CollectionRunner.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(runner.isRunning()).thenReturn(true);
        ImporterPanel panel = newPanel(runner);

        ApiRequest request = request("Login");
        request.path = "Auth";
        ApiCollection collection = collection("APIM", request);
        edt(() -> panel.restoreWorkspaceCollections(List.of(collection)));
        drainEdt();

        JTree tree = requestTree(panel);
        CollectionTreeNode collectionNode = collectionNode(tree, "APIM");
        CollectionTreeNode folderNode = folderNodeByPath(tree, "Auth");
        CollectionTreeNode requestNode = requestNode(tree, "Login");

        assertThat(collectionNode).isNotNull();
        assertThat(folderNode).isNotNull();
        assertThat(requestNode).isNotNull();

        invokeDeleteWhileWarningDialogOpen(panel, "deleteRequestNode", requestNode);
        invokeDeleteWhileWarningDialogOpen(panel, "deleteFolderNode", folderNode);
        invokeDeleteWhileWarningDialogOpen(panel, "deleteCollectionNode", collectionNode);
        drainEdt();

        assertThat(collectionNode(requestTree(panel), "APIM")).isNotNull();
        assertThat(folderNodeByPath(requestTree(panel), "Auth")).isNotNull();
        assertThat(requestNode(requestTree(panel), "Login")).isNotNull();
        assertThat(queue(panel)).isEmpty();
    }

    private static ImporterPanel newPanel() {
        return newPanel(Mockito.mock(CollectionRunner.class, Mockito.RETURNS_DEEP_STUBS));
    }

    private static ImporterPanel newPanel(CollectionRunner runner) {
        burp.UniversalImporter importer = Mockito.mock(burp.UniversalImporter.class, Mockito.RETURNS_DEEP_STUBS);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpRequestEditor(Mockito.any(EditorOptions.class))).thenReturn(requestEditor);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpResponseEditor(Mockito.any(EditorOptions.class))).thenReturn(responseEditor);
        return new ImporterPanel(importer, runner, Mockito.mock(burp.auth.OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS), burp.utils.ScriptMode.DISABLED);
    }

    private static ImporterPanel newRealPanel(MontoyaApi api) {
        return new UniversalImporter(api, ScriptMode.DISABLED, new WorkspaceStateService(Mockito.mock(PersistedObject.class))).getUI();
    }

    private static MontoyaApi mockApi(boolean stubSendRequest) {
        MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(api.userInterface().createHttpRequestEditor(Mockito.any(EditorOptions.class))).thenReturn(requestEditor);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(api.userInterface().createHttpResponseEditor(Mockito.any(EditorOptions.class))).thenReturn(responseEditor);
        if (stubSendRequest) {
            HttpRequestResponse response = Mockito.mock(HttpRequestResponse.class, Mockito.RETURNS_DEEP_STUBS);
            Mockito.when(response.response().bodyToString()).thenReturn("");
            Mockito.when(api.http().sendRequest(Mockito.any(), Mockito.any(RequestOptions.class))).thenReturn(response);
        }
        return api;
    }

    private static ApiCollection collection(String name, ApiRequest... requests) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        for (ApiRequest req : requests) {
            if (req != null) {
                req.sourceCollection = name;
                collection.requests.add(req);
            }
        }
        return collection;
    }

    private static ApiRequest request(String name) {
        ApiRequest request = new ApiRequest();
        request.name = name;
        request.method = "GET";
        request.url = "https://example.test/" + name.toLowerCase();
        return request;
    }

    private static Path tempFile(String fileName, String content) throws IOException {
        Path dir = Files.createTempDirectory("runner-dnd-");
        Path file = dir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        file.toFile().deleteOnExit();
        dir.toFile().deleteOnExit();
        return file;
    }

    private static RunnerPreviewRow previewRow(String name) {
        RunnerPreviewRow row = new RunnerPreviewRow();
        row.requestName = name;
        row.collectionName = "Checkout";
        row.method = "GET";
        row.urlPreview = "https://example.test/" + name.toLowerCase();
        return row;
    }

    @SuppressWarnings("unchecked")
    private static List<ApiRequest> queue(ImporterPanel panel, ApiRequest... requests) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("queueRunnerRequests", List.class);
        method.setAccessible(true);
        List<ApiRequest> selected = new ArrayList<>();
        for (ApiRequest request : requests) {
            selected.add(request);
        }
        method.invoke(panel, selected);
        drainEdt();
        return (List<ApiRequest>) privateField(panel, "runnerQueuedRequests");
    }

    @SuppressWarnings("unchecked")
    private static List<ApiRequest> queue(ImporterPanel panel) throws Exception {
        return (List<ApiRequest>) privateField(panel, "runnerQueuedRequests");
    }

    private static JList<?> queueList(ImporterPanel panel) throws Exception {
        return (JList<?>) privateField(panel, "runnerQueueList");
    }

    private static RunnerResultTableModel resultModel(ImporterPanel panel) throws Exception {
        return (RunnerResultTableModel) privateField(panel, "resultModel");
    }

    private static RunnerTimelineTableModel timelineModel(ImporterPanel panel) throws Exception {
        return (RunnerTimelineTableModel) privateField(panel, "timelineModel");
    }

    private static JTextArea runnerLog(ImporterPanel panel) throws Exception {
        return (JTextArea) privateField(panel, "runnerLog");
    }

    private static OAuth2Panel oauth2Panel(ImporterPanel panel) throws Exception {
        return (OAuth2Panel) privateField(panel, "oauth2Panel");
    }

    private static JTextArea importLog(ImporterPanel panel) throws Exception {
        return (JTextArea) privateField(panel, "importLog");
    }

    private static JTree requestTree(ImporterPanel panel) throws Exception {
        return (JTree) privateField(panel, "requestTree");
    }

    private static CollectionTreeNode collectionNode(JTree tree, String collectionName) {
        return collectionNode((DefaultMutableTreeNode) tree.getModel().getRoot(), collectionName);
    }

    private static CollectionTreeNode collectionNode(DefaultMutableTreeNode node, String collectionName) {
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION
                    && ctn.collection != null
                    && collectionName.equals(ctn.collection.name)) {
                return ctn;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            CollectionTreeNode found = collectionNode((DefaultMutableTreeNode) node.getChildAt(i), collectionName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static CollectionTreeNode folderNodeByPath(JTree tree, String folderPath) {
        return folderNodeByPath((DefaultMutableTreeNode) tree.getModel().getRoot(), folderPath);
    }

    private static CollectionTreeNode folderNodeByPath(DefaultMutableTreeNode node, String folderPath) {
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.FOLDER && folderPath.equals(ctn.folderPath)) {
                return ctn;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            CollectionTreeNode found = folderNodeByPath((DefaultMutableTreeNode) node.getChildAt(i), folderPath);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static CollectionTreeNode requestNode(JTree tree, String requestId) {
        return requestNode((DefaultMutableTreeNode) tree.getModel().getRoot(), requestId);
    }

    private static CollectionTreeNode requestNode(DefaultMutableTreeNode node, String requestId) {
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST
                    && ctn.request != null
                    && (requestId.equals(ctn.request.id)
                    || requestId.equals(ctn.request.name)
                    || requestId.equals(ctn.request.path))) {
                return ctn;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            CollectionTreeNode found = requestNode((DefaultMutableTreeNode) node.getChildAt(i), requestId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static EnvironmentProfile environment(String name) {
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = name;
        profile.ensureId();
        profile.ensureDefaults();
        return profile;
    }

    private static Map<String, String> activeEnvironmentOverlay(ImporterPanel panel) throws Exception {
        String activeId = (String) privateField(panel, "activeEnvironmentId");
        @SuppressWarnings("unchecked")
        List<EnvironmentProfile> profiles = (List<EnvironmentProfile>) privateField(panel, "environmentProfiles");
        for (EnvironmentProfile profile : profiles) {
            if (profile != null && Objects.equals(profile.id, activeId)) {
                return profile.toRuntimeOverlay();
            }
        }
        return Collections.emptyMap();
    }

    private static void invokePrivate(ImporterPanel panel, String methodName) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(panel);
    }

    private static void invokePrivate(ImporterPanel panel, String methodName, Class<?>[] parameterTypes, Object arg) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        method.invoke(panel, arg);
    }

    private static void invokePrivateArgs(ImporterPanel panel, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        method.invoke(panel, args);
    }

    private static void invokeOnEdt(ImporterPanel panel, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                invokePrivateArgs(panel, methodName, parameterTypes, args);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void edt(ThrowingRunnable action) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                action.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static Object privateField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void invokeDeleteWhileWarningDialogOpen(ImporterPanel panel, String methodName, CollectionTreeNode node) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> {
                try {
                    invokePrivateArgs(panel, methodName, new Class<?>[]{CollectionTreeNode.class}, node);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (!(cause instanceof HeadlessException)) {
                        throw new RuntimeException(cause != null ? cause : e);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            awaitCondition("runner warning dialog or delete completion", () -> future.isDone() || findRunnerWarningDialog() != null);
            JDialog dialog = findRunnerWarningDialog();
            if (dialog != null) {
                edt(dialog::dispose);
            }
            future.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static JDialog findRunnerWarningDialog() {
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog dialog && dialog.isShowing() && "Runner Running".equals(dialog.getTitle())) {
                return dialog;
            }
        }
        return null;
    }

    private static void awaitCondition(String description, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            drainEdt();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void drainEdt() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
    }

    private static final class RunnerResultHelper {
        static void addDummyRunnerData(ImporterPanel panel) throws Exception {
            RunnerResultTableModel resultTableModel = resultModel(panel);
            burp.models.RunnerResult result = new burp.models.RunnerResult();
            result.requestName = "Queued 1";
            result.method = "GET";
            result.path = "/queued-1";
            result.host = "example.test";
            result.success = true;
            result.statusCode = 200;
            resultTableModel.addResult(result);

            RunnerTimelineTableModel timelineTableModel = timelineModel(panel);
            burp.models.RunnerTimelineRow row = new burp.models.RunnerTimelineRow();
            row.requestName = "Queued 1";
            row.collectionName = "Checkout";
            row.status = "OK";
            timelineTableModel.addRow(row);

            runnerLog(panel).setText("old log");
        }
    }
}
