package burp.ui;

import burp.UniversalImporter;
import burp.auth.OAuth2Manager;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.runner.CollectionRunner;
import burp.ui.tree.CollectionTreeNode;
import burp.ui.tree.RequestTreeDragPayload;
import burp.ui.tree.RequestTreePathService;
import burp.ui.tree.TreeDropRequest;
import burp.utils.ScriptMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ImporterPanelRequestTreeStateTest {
    @TempDir
    Path tempDir;

    @Test
    void collapsedCollectionRemainsCollapsedAfterImportingAnotherCollection() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection a = collection("A", request("req-a", "A", "", "GET", "https://api.example.test/a"));
        ApiCollection b = collection("B", request("req-b", "B", "", "GET", "https://api.example.test/b"));
        restoreCollections(panel, List.of(a, b));

        JTree tree = requestTree(panel);
        CollectionTreeNode aNode = collectionNode(tree, "A");
        CollectionTreeNode bNode = collectionNode(tree, "B");
        expandNode(tree, aNode);
        collapseNode(tree, aNode);
        expandNode(tree, bNode);
        collapseNode(tree, bNode);
        drainEdt();

        File imported = exportCollectionFile(collection("C", request("req-c", "C", "", "GET", "https://api.example.test/c")),
                "imported.api-workbench.collection.json");
        invokeOnEdt(() -> panel.importCollectionFilesDroppedOnRequestTree(List.of(imported)));
        drainEdt();

        JTree refreshedTree = requestTree(panel);
        assertThat(isExpanded(refreshedTree, collectionNode(refreshedTree, "A"))).isFalse();
        assertThat(isExpanded(refreshedTree, collectionNode(refreshedTree, "B"))).isFalse();
        assertThat(collectionNode(refreshedTree, "C")).isNotNull();
    }

    @Test
    void collapsedFolderRemainsCollapsedAfterTreeRefresh() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection a = collection("A", request("req-auth", "Auth Request", "Auth", "GET", "https://api.example.test/auth"));
        a.folderPaths.add("Auth");
        ApiCollection b = collection("B", request("req-b", "B", "", "GET", "https://api.example.test/b"));
        restoreCollections(panel, List.of(a, b));

        JTree tree = requestTree(panel);
        CollectionTreeNode aNode = collectionNode(tree, "A");
        CollectionTreeNode authNode = folderNodeByPath(tree, "Auth");
        expandNode(tree, aNode);
        expandNode(tree, authNode);
        collapseNode(tree, authNode);
        collapseNode(tree, aNode);
        drainEdt();

        File imported = exportCollectionFile(collection("C", request("req-c", "C", "", "GET", "https://api.example.test/c")),
                "refresh-trigger.api-workbench.collection.json");
        invokeOnEdt(() -> panel.importCollectionFilesDroppedOnRequestTree(List.of(imported)));
        drainEdt();

        JTree refreshedTree = requestTree(panel);
        assertThat(isExpanded(refreshedTree, collectionNode(refreshedTree, "A"))).isFalse();
        assertThat(isExpanded(refreshedTree, folderNodeByPath(refreshedTree, "Auth"))).isFalse();
    }

    @Test
    void expandedNestedFolderRemainsExpandedAfterRequestMove() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection source = collection("Source",
                request("req-root", "Root", "", "GET", "https://api.example.test/root"),
                request("req-move", "Move Me", "", "POST", "https://api.example.test/move"),
                request("req-oauth", "OAuth Child", "Auth/OAuth", "GET", "https://api.example.test/oauth"));
        source.folderPaths.add("Auth");
        source.folderPaths.add("Auth/OAuth");
        ApiCollection target = collection("Target", request("req-target", "Target", "", "GET", "https://api.example.test/target"));
        restoreCollections(panel, List.of(source, target));

        JTree tree = requestTree(panel);
        expandNode(tree, collectionNode(tree, "Source"));
        expandNode(tree, folderNodeByPath(tree, "Auth"));
        expandNode(tree, folderNodeByPath(tree, "Auth/OAuth"));
        drainEdt();

        CollectionTreeNode targetNode = collectionNode(tree, "Target");
        ApiRequest moved = requestNode(tree, "req-move").request;
        boolean movedOk = invokeOnEdt(() -> panel.handleRequestTreeDrop(new TreeDropRequest(
                RequestTreeDragPayload.forRequest(source, moved),
                target,
                targetNode,
                "",
                0,
                TreeDropRequest.DropPosition.ON_COLLECTION)));
        assertThat(movedOk).isTrue();
        drainEdt();

        JTree refreshedTree = requestTree(panel);
        assertThat(isExpanded(refreshedTree, folderNodeByPath(refreshedTree, "Auth"))).isTrue();
        assertThat(isExpanded(refreshedTree, folderNodeByPath(refreshedTree, "Auth/OAuth"))).isTrue();
    }

    @Test
    void selectedRequestRemainsSelectedAfterReorderOrMove() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection source = collection("Source",
                request("req-selected", "Selected", "", "GET", "https://api.example.test/selected"),
                request("req-moved", "Moved", "", "POST", "https://api.example.test/moved"));
        ApiCollection target = collection("Target", request("req-target", "Target", "", "GET", "https://api.example.test/target"));
        restoreCollections(panel, List.of(source, target));

        JTree tree = requestTree(panel);
        selectTreeNode(panel, requestNode(tree, "req-selected"));
        drainEdt();

        CollectionTreeNode targetNode = collectionNode(tree, "Target");
        ApiRequest moved = requestNode(tree, "req-moved").request;
        boolean movedOk = invokeOnEdt(() -> panel.handleRequestTreeDrop(new TreeDropRequest(
                RequestTreeDragPayload.forRequest(source, moved),
                target,
                targetNode,
                "",
                0,
                TreeDropRequest.DropPosition.ON_COLLECTION)));
        assertThat(movedOk).isTrue();
        drainEdt();

        JTree refreshedTree = requestTree(panel);
        CollectionTreeNode selected = selectedNode(refreshedTree);
        assertThat(selected).isNotNull();
        assertThat(selected.request).isNotNull();
        assertThat(selected.request.id).isEqualTo("req-selected");
        assertThat(requestEditor(panel).getCurrentRequest()).isSameAs(selected.request);
    }

    @Test
    void selectionClearsSafelyWhenSelectedRequestIsDeleted() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection source = collection("Source",
                request("req-delete", "Delete Me", "", "GET", "https://api.example.test/delete"),
                request("req-survive", "Survive", "", "GET", "https://api.example.test/survive"));
        restoreCollections(panel, List.of(source));

        JTree tree = requestTree(panel);
        CollectionTreeNode deleteNode = requestNode(tree, "req-delete");
        selectTreeNode(panel, deleteNode);
        drainEdt();

        invokeDeleteRequestNode(panel, deleteNode);
        drainEdt();

        JTree refreshedTree = requestTree(panel);
        assertThat(selectedNode(refreshedTree)).isNull();
        assertThat(requestEditor(panel).getCurrentRequest()).isNull();
        assertThat(requestNode(refreshedTree, "req-delete")).isNull();
        assertThat(requestNode(refreshedTree, "req-survive")).isNotNull();
    }

    @Test
    void crossCollectionMovePreservesUnrelatedExpansion() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection source = collection("Source",
                request("req-move", "Move Me", "Auth", "POST", "https://api.example.test/move"),
                request("req-source-other", "Source Other", "", "GET", "https://api.example.test/source-other"));
        source.folderPaths.add("Auth");
        ApiCollection target = collection("Target",
                request("req-selected", "Selected", "Archive", "GET", "https://api.example.test/selected"));
        target.folderPaths.add("Archive");
        restoreCollections(panel, List.of(source, target));

        JTree tree = requestTree(panel);
        collapseNode(tree, collectionNode(tree, "Source"));
        expandNode(tree, collectionNode(tree, "Target"));
        expandNode(tree, folderNodeByPath(tree, "Archive"));
        selectTreeNode(panel, requestNode(tree, "req-selected"));
        drainEdt();

        CollectionTreeNode targetFolderNode = folderNodeByPath(tree, "Archive");
        ApiRequest moved = requestNode(tree, "req-move").request;
        boolean movedOk = invokeOnEdt(() -> panel.handleRequestTreeDrop(new TreeDropRequest(
                RequestTreeDragPayload.forRequest(source, moved),
                target,
                targetFolderNode,
                "Archive",
                0,
                TreeDropRequest.DropPosition.ON_FOLDER)));
        assertThat(movedOk).isTrue();
        drainEdt();

        JTree refreshedTree = requestTree(panel);
        assertThat(isExpanded(refreshedTree, collectionNode(refreshedTree, "Source"))).isFalse();
        assertThat(isExpanded(refreshedTree, folderNodeByPath(refreshedTree, "Auth"))).isFalse();
        assertThat(isExpanded(refreshedTree, collectionNode(refreshedTree, "Target"))).isTrue();
        assertThat(isExpanded(refreshedTree, folderNodeByPath(refreshedTree, "Archive"))).isTrue();
        CollectionTreeNode selected = selectedNode(refreshedTree);
        assertThat(selected).isNotNull();
        assertThat(selected.request).isNotNull();
        assertThat(selected.request.id).isEqualTo("req-selected");
        assertThat(requestEditor(panel).getCurrentRequest()).isSameAs(selected.request);
    }

    private File exportCollectionFile(ApiCollection collection, String fileName) throws Exception {
        Path output = tempDir.resolve(fileName);
        new burp.exporter.CollectionExportService().exportCollection(
                collection,
                new burp.exporter.CollectionExportOptions(burp.exporter.CollectionExportFormat.API_WORKBENCH_JSON, output, false, null, Map.of())
        );
        return output.toFile();
    }

    private static ImporterPanel newPanel() {
        UniversalImporter importer = Mockito.mock(UniversalImporter.class);
        MontoyaApi api = Mockito.mock(MontoyaApi.class);
        UserInterface userInterface = Mockito.mock(UserInterface.class);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        when(requestEditor.uiComponent()).thenReturn(new JPanel());
        when(responseEditor.uiComponent()).thenReturn(new JPanel());
        when(userInterface.createHttpRequestEditor(Mockito.any())).thenReturn(requestEditor);
        when(userInterface.createHttpResponseEditor(Mockito.any())).thenReturn(responseEditor);
        when(api.userInterface()).thenReturn(userInterface);
        when(importer.getApi()).thenReturn(api);
        CollectionRunner runner = Mockito.mock(CollectionRunner.class);
        OAuth2Manager oauth2Manager = Mockito.mock(OAuth2Manager.class);
        return new TestImporterPanel(importer, runner, oauth2Manager, ScriptMode.FULL_JS);
    }

    private static void restoreCollections(ImporterPanel panel, List<ApiCollection> collections) throws Exception {
        SwingUtilities.invokeAndWait(() -> panel.restoreWorkspaceCollections(collections));
    }

    private static <T> T invokeOnEdt(Callable<T> callable) throws Exception {
        java.util.concurrent.atomic.AtomicReference<T> ref = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                ref.set(callable.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() != null) {
            if (error.get() instanceof Exception e) {
                throw e;
            }
            if (error.get() instanceof Error e) {
                throw e;
            }
            throw new RuntimeException(error.get());
        }
        return ref.get();
    }

    private static void drainEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    private static void selectTreeNode(ImporterPanel panel, CollectionTreeNode node) throws Exception {
        if (node == null) {
            return;
        }
        SwingUtilities.invokeAndWait(() -> {
            try {
                requestTree(panel).setSelectionPath(new TreePath(node.getPath()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void expandNode(JTree tree, CollectionTreeNode node) throws Exception {
        if (tree == null || node == null) {
            return;
        }
        SwingUtilities.invokeAndWait(() -> tree.expandPath(new TreePath(node.getPath())));
    }

    private static void collapseNode(JTree tree, CollectionTreeNode node) throws Exception {
        if (tree == null || node == null) {
            return;
        }
        SwingUtilities.invokeAndWait(() -> tree.collapsePath(new TreePath(node.getPath())));
    }

    private static boolean isExpanded(JTree tree, CollectionTreeNode node) {
        return tree != null && node != null && tree.isExpanded(new TreePath(node.getPath()));
    }

    private static void invokeDeleteRequestNode(ImporterPanel panel, CollectionTreeNode node) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("deleteRequestNode", CollectionTreeNode.class);
        method.setAccessible(true);
        invokeOnEdt(() -> {
            method.invoke(panel, node);
            return null;
        });
    }

    private static CollectionTreeNode selectedNode(JTree tree) {
        if (tree == null || tree.getSelectionPath() == null) {
            return null;
        }
        Object last = tree.getSelectionPath().getLastPathComponent();
        return last instanceof CollectionTreeNode ? (CollectionTreeNode) last : null;
    }

    private static JTree requestTree(ImporterPanel panel) throws Exception {
        return privateField(panel, "requestTree");
    }

    private static RequestEditorPanel requestEditor(ImporterPanel panel) throws Exception {
        return privateField(panel, "requestEditor");
    }

    private static List<ApiCollection> loadedCollections(ImporterPanel panel) throws Exception {
        return privateField(panel, "loadedCollections");
    }

    private static CollectionTreeNode collectionNode(JTree tree, String collectionName) {
        return findNode(tree, node -> node.getNodeType() == CollectionTreeNode.Type.COLLECTION
                && node.collection != null
                && Objects.equals(node.collection.name, collectionName));
    }

    private static CollectionTreeNode folderNodeByPath(JTree tree, String folderPath) {
        String normalized = RequestTreePathService.normalizeFolderPath(folderPath);
        return findNode(tree, node -> node.getNodeType() == CollectionTreeNode.Type.FOLDER
                && Objects.equals(RequestTreePathService.normalizeFolderPath(node.folderPath), normalized));
    }

    private static CollectionTreeNode requestNode(JTree tree, String requestId) {
        return findNode(tree, node -> node.getNodeType() == CollectionTreeNode.Type.REQUEST
                && node.request != null
                && Objects.equals(node.request.id, requestId));
    }

    private static CollectionTreeNode findNode(JTree tree, java.util.function.Predicate<CollectionTreeNode> predicate) {
        if (tree == null || tree.getModel() == null || tree.getModel().getRoot() == null) {
            return null;
        }
        return findNode((DefaultMutableTreeNode) tree.getModel().getRoot(), predicate);
    }

    private static CollectionTreeNode findNode(DefaultMutableTreeNode node, java.util.function.Predicate<CollectionTreeNode> predicate) {
        if (node instanceof CollectionTreeNode ctn && predicate.test(ctn)) {
            return ctn;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            Object child = node.getChildAt(i);
            if (child instanceof DefaultMutableTreeNode dmtn) {
                CollectionTreeNode found = findNode(dmtn, predicate);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static ApiCollection collection(String name, ApiRequest... requests) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        collection.requests = new ArrayList<>();
        collection.folderPaths = new ArrayList<>();
        collection.variables = new ArrayList<>();
        collection.folderVars = new LinkedHashMap<>();
        collection.environment = new LinkedHashMap<>();
        collection.folderAuthModes = new LinkedHashMap<>();
        collection.folderAuth = new LinkedHashMap<>();
        collection.runtimeVars = new LinkedHashMap<>();
        collection.runtimeOAuth2 = new LinkedHashMap<>();
        if (requests != null) {
            for (ApiRequest request : requests) {
                if (request == null) {
                    continue;
                }
                request.sourceCollection = name;
                collection.requests.add(request);
            }
        }
        return collection;
    }

    private static ApiRequest request(String id, String name, String path, String method, String url) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.path = path;
        request.method = method;
        request.url = url;
        request.headers = new ArrayList<>();
        request.variables = new ArrayList<>();
        request.preRequestScripts = new ArrayList<>();
        request.postResponseScripts = new ArrayList<>();
        request.suppressedAutoHeaders = new java.util.LinkedHashSet<>();
        return request;
    }

    private static <T> T privateField(ImporterPanel panel, String fieldName) throws Exception {
        Field field = ImporterPanel.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        T value = (T) field.get(panel);
        return value;
    }

    private static final class TestImporterPanel extends ImporterPanel {
        private TestImporterPanel(UniversalImporter importer, CollectionRunner runner, OAuth2Manager oauth2Manager, ScriptMode scriptMode) {
            super(importer, runner, oauth2Manager, scriptMode);
        }

        @Override
        protected boolean confirmDelete(String message) {
            return true;
        }
    }
}
