package burp.ui;

import burp.UniversalImporter;
import burp.auth.OAuth2Manager;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspaceState;
import burp.runner.CollectionRunner;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Workflow4MainTreeRestoreTraceTest {

    @Test
    void workflow4MainTreeMatchesPopupCloneAfterRestore() throws Exception {
        ImporterPanel panel = newPanel();
        WorkspaceState state = nestedWorkspaceState();

        panel.restoreWorkspaceState(state);
        drainEdt();

        DefaultMutableTreeNode mainRoot = treeRoot(panel);
        DefaultMutableTreeNode popupRoot = popupCloneRoot(panel);

        int mainFolderCount = countFolderNodes(mainRoot);
        int popupFolderCount = countFolderNodes(popupRoot);

        assertThat(mainFolderCount).isGreaterThan(0);
        assertThat(popupFolderCount).isGreaterThan(0);
        assertThat(mainFolderCount).isEqualTo(popupFolderCount);
        assertThat(countRootRequestNodes(mainRoot)).isZero();
        assertThat(countNestedRequestNodes(mainRoot)).isGreaterThan(0);
    }

    @Test
    void workflow4VisibleMainTreeBoundToRestoredTreeModelAfterRestore() throws Exception {
        ImporterPanel panel = newPanel();
        WorkspaceState state = nestedWorkspaceState();

        panel.restoreWorkspaceState(state);
        drainEdt();

        JTree requestTree = requestTree(panel);
        DefaultTreeModel treeModel = treeModel(panel);
        JScrollPane scrollPane = scrollPane(panel);

        assertThat(requestTree.getModel()).isSameAs(treeModel);
        assertThat(scrollPane.getViewport().getView()).isSameAs(requestTree);
    }

    @Test
    void workflow4RestoreDoesNotRequireAddRemoveToShowNestedMainTree() throws Exception {
        ImporterPanel panel = newPanel();
        WorkspaceState state = nestedWorkspaceState();

        panel.restoreWorkspaceState(state);
        drainEdt();

        DefaultMutableTreeNode mainRoot = treeRoot(panel);

        assertThat(countFolderNodes(mainRoot)).isGreaterThan(0);
        assertThat(countNestedRequestNodes(mainRoot)).isGreaterThan(0);
        assertThat(countRootRequestNodes(mainRoot)).isZero();
    }

    private static WorkspaceState nestedWorkspaceState() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest request = new ApiRequest();
        request.id = "req-workflow4";
        request.name = "Get Token";
        request.method = "GET";
        request.url = "https://auth.example.test/token";
        request.sequenceOrder = 0;
        request.sourceCollection = "APIM";
        request.path = "Get Token";
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(
                ImporterPanel.workspaceRequestTreePathKey("APIM", 0, state.collections.get(0).requests.get(0), 0),
                "Auth/OAuth"
        );
        state.expandedTreePathKeys = new ArrayList<>(List.of(
                ImporterPanel.workspaceTreePathKey("APIM", ""),
                ImporterPanel.workspaceTreePathKey("APIM", "Auth"),
                ImporterPanel.workspaceTreePathKey("APIM", "Auth/OAuth")
        ));
        return state;
    }

    private static ImporterPanel newPanel() {
        UniversalImporter importer = Mockito.mock(UniversalImporter.class, Mockito.RETURNS_DEEP_STUBS);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpRequestEditor(Mockito.any())).thenReturn(requestEditor);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpResponseEditor(Mockito.any())).thenReturn(responseEditor);
        CollectionRunner runner = Mockito.mock(CollectionRunner.class, Mockito.RETURNS_DEEP_STUBS);
        OAuth2Manager oauth2Manager = Mockito.mock(OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS);
        return new ImporterPanel(importer, runner, oauth2Manager, burp.utils.ScriptMode.DISABLED);
    }

    private static void drainEdt() throws Exception {
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    private static JTree requestTree(ImporterPanel panel) throws Exception {
        return (JTree) privateField(panel, "requestTree");
    }

    private static JScrollPane scrollPane(ImporterPanel panel) throws Exception {
        return (JScrollPane) privateField(panel, "requestTreeScrollPane");
    }

    private static DefaultTreeModel treeModel(ImporterPanel panel) throws Exception {
        return (DefaultTreeModel) privateField(panel, "treeModel");
    }

    private static DefaultMutableTreeNode treeRoot(ImporterPanel panel) throws Exception {
        return (DefaultMutableTreeNode) treeModel(panel).getRoot();
    }

    private static DefaultMutableTreeNode popupCloneRoot(ImporterPanel panel) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("cloneRequestTreeRootForSelection");
        method.setAccessible(true);
        return (DefaultMutableTreeNode) method.invoke(panel);
    }

    private static int countFolderNodes(DefaultMutableTreeNode root) {
        if (root == null) {
            return 0;
        }
        int count = root instanceof burp.ui.tree.CollectionTreeNode
                && ((burp.ui.tree.CollectionTreeNode) root).getNodeType() == burp.ui.tree.CollectionTreeNode.Type.FOLDER ? 1 : 0;
        for (int i = 0; i < root.getChildCount(); i++) {
            count += countFolderNodes((DefaultMutableTreeNode) root.getChildAt(i));
        }
        return count;
    }

    private static int countRootRequestNodes(DefaultMutableTreeNode root) {
        return countRootRequestNodes(root, 0);
    }

    private static int countRootRequestNodes(DefaultMutableTreeNode node, int folderDepth) {
        if (node == null) {
            return 0;
        }
        int nextDepth = folderDepth;
        if (node instanceof burp.ui.tree.CollectionTreeNode
                && ((burp.ui.tree.CollectionTreeNode) node).getNodeType() == burp.ui.tree.CollectionTreeNode.Type.FOLDER) {
            nextDepth = folderDepth + 1;
        }
        int count = node instanceof burp.ui.tree.CollectionTreeNode
                && ((burp.ui.tree.CollectionTreeNode) node).getNodeType() == burp.ui.tree.CollectionTreeNode.Type.REQUEST
                && folderDepth == 0 ? 1 : 0;
        for (int i = 0; i < node.getChildCount(); i++) {
            count += countRootRequestNodes((DefaultMutableTreeNode) node.getChildAt(i), nextDepth);
        }
        return count;
    }

    private static int countNestedRequestNodes(DefaultMutableTreeNode root) {
        return countNestedRequestNodes(root, 0);
    }

    private static int countNestedRequestNodes(DefaultMutableTreeNode node, int folderDepth) {
        if (node == null) {
            return 0;
        }
        int nextDepth = folderDepth;
        if (node instanceof burp.ui.tree.CollectionTreeNode
                && ((burp.ui.tree.CollectionTreeNode) node).getNodeType() == burp.ui.tree.CollectionTreeNode.Type.FOLDER) {
            nextDepth = folderDepth + 1;
        }
        int count = node instanceof burp.ui.tree.CollectionTreeNode
                && ((burp.ui.tree.CollectionTreeNode) node).getNodeType() == burp.ui.tree.CollectionTreeNode.Type.REQUEST
                && folderDepth > 0 ? 1 : 0;
        for (int i = 0; i < node.getChildCount(); i++) {
            count += countNestedRequestNodes((DefaultMutableTreeNode) node.getChildAt(i), nextDepth);
        }
        return count;
    }

    private static Object privateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
