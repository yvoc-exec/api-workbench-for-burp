package burp.ui;

import burp.UniversalImporter;
import burp.auth.OAuth2Manager;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspaceState;
import burp.runner.CollectionRunner;
import burp.ui.tree.CollectionTreeNode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.table.DefaultTableModel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelRequestTreeCreateFlowTest {

    @Test
    void rootContextMenuIncludesNewCollectionOnlyForCreateFlow() {
        ImporterPanel panel = newPanel();

        JPopupMenu menu = panel.buildRequestTreeContextMenu(null);

        assertThat(menuLabels(menu)).containsExactly("New Collection");
    }

    @Test
    void collectionContextMenuStacksCreateLifecycleAndAuthSettings() {
        ImporterPanel panel = newPanel();
        CollectionTreeNode collectionNode = new CollectionTreeNode(collection("APIM"));

        JPopupMenu menu = panel.buildRequestTreeContextMenu(collectionNode);

        assertThat(menuLabels(menu)).containsExactly(
                "New Folder",
                "New Request",
                "Rename",
                "Duplicate",
                "Delete",
                "Auth Settings..."
        );
    }

    @Test
    void folderContextMenuStacksCreateLifecycleAndAuthSettings() {
        ImporterPanel panel = newPanel();
        CollectionTreeNode folderNode = new CollectionTreeNode("Auth");
        folderNode.folderPath = "Auth";
        folderNode.setUserObject("Auth");

        JPopupMenu menu = panel.buildRequestTreeContextMenu(folderNode);

        assertThat(menuLabels(menu)).containsExactly(
                "New Folder",
                "New Request",
                "Rename",
                "Duplicate",
                "Delete",
                "Auth Settings..."
        );
    }

    @Test
    void requestContextMenuHasLifecycleAndAuthSettingsButNoCreateActions() {
        ImporterPanel panel = newPanel();
        CollectionTreeNode requestNode = new CollectionTreeNode(request("req-1", "Get Token", "POST", "https://auth.example.test/token", 0));

        JPopupMenu menu = panel.buildRequestTreeContextMenu(requestNode);

        assertThat(menuLabels(menu)).containsExactly(
                "Rename",
                "Duplicate",
                "Delete",
                "Auth Settings..."
        );
        assertThat(menuLabels(menu)).doesNotContain("New Folder", "New Request");
    }

    @Test
    void newCollectionCreatesEditableCollection() throws Exception {
        ImporterPanel panel = newPanel();
        AtomicInteger dirtyCount = watchWorkspaceChanges(panel);

        invokeOnEdt(panel, "createNewCollectionFromTree");
        drainEdt();

        assertThat(dirtyCount.get()).isGreaterThan(0);
        assertThat(loadedCollections(panel)).hasSize(1);
        assertThat(loadedCollections(panel).get(0).name).isEqualTo("Untitled Collection");
        assertThat(requestTree(panel).isEditable()).isTrue();
        assertThat(requestTree(panel).getSelectionPath()).isNotNull();
        assertThat(((CollectionTreeNode) requestTree(panel).getSelectionPath().getLastPathComponent()).collection.name)
                .isEqualTo("Untitled Collection");
    }

    @Test
    void duplicateDefaultNamesGetSuffix() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("Untitled Collection");
        ApiRequest untitled = request("req-untitled", "Untitled Request", "GET", "", 0);
        collection.requests.add(untitled);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        invokeOnEdt(panel, "createNewCollectionFromTree");
        drainEdt();
        assertThat(loadedCollections(panel)).extracting(col -> col.name)
                .contains("Untitled Collection 2");

        CollectionTreeNode originalCollectionNode = collectionNode(requestTree(panel), "Untitled Collection");
        invokeOnEdt(panel, "createNewRequestFromTree", new Class<?>[]{CollectionTreeNode.class}, originalCollectionNode);
        drainEdt();

        CollectionTreeNode newRequest = requestNode(requestTree(panel), "Untitled Request 2");
        assertThat(originalCollectionNode).isNotNull();
        assertThat(newRequest).isNotNull();
        assertThat(newRequest.request.name).isEqualTo("Untitled Request 2");
    }

    @Test
    void newFolderUnderCollectionCreatesFolderNode() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();
        CollectionTreeNode collectionNode = collectionNode(requestTree(panel), "APIM");

        invokeOnEdt(panel, "createNewFolderFromTree", new Class<?>[]{CollectionTreeNode.class}, collectionNode);
        drainEdt();

        CollectionTreeNode createdFolder = folderNodeByPath(requestTree(panel), "Untitled Folder");
        assertThat(createdFolder).isNotNull();
        assertThat(createdFolder.folderPath).isEqualTo("Untitled Folder");
        assertThat(requestTree(panel).getSelectionPath()).isNotNull();
        assertThat(((CollectionTreeNode) requestTree(panel).getSelectionPath().getLastPathComponent()).getNodeType())
                .isEqualTo(CollectionTreeNode.Type.FOLDER);
    }

    @Test
    void newFolderUnderFolderCreatesNestedFolderNode() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();
        CollectionTreeNode parentFolder = folderNodeByPath(requestTree(panel), "Auth");

        invokeOnEdt(panel, "createNewFolderFromTree", new Class<?>[]{CollectionTreeNode.class}, parentFolder);
        drainEdt();

        CollectionTreeNode createdFolder = folderNodeByPath(requestTree(panel), "Auth/Untitled Folder");
        assertThat(createdFolder).isNotNull();
        assertThat(createdFolder.folderPath).isEqualTo("Auth/Untitled Folder");
    }

    @Test
    void selectingCollectionClearsStaleRequestEditor() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        ApiRequest request = request("req-1", "Login", "POST", "https://auth.example.test/login", 0);
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        openRequestInEditor(panel, collection, request);
        selectTreeNode(panel, collectionNode(requestTree(panel), "APIM"));
        drainEdt();

        assertThat(requestEditor(panel).getCurrentRequest()).isNull();
        assertThat(requestEditor(panel).getCurrentCollection()).isNull();
        assertThat(requestEditor(panel).isSendEnabled()).isFalse();
    }

    @Test
    void selectingFolderClearsStaleRequestEditor() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        ApiRequest request = request("req-1", "Login", "POST", "https://auth.example.test/login", 0);
        request.path = "Auth";
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        openRequestInEditor(panel, collection, request);
        selectTreeNode(panel, folderNodeByPath(requestTree(panel), "Auth"));
        drainEdt();

        assertThat(requestEditor(panel).getCurrentRequest()).isNull();
        assertThat(requestEditor(panel).getCurrentCollection()).isNull();
        assertThat(requestEditor(panel).isSendEnabled()).isFalse();
    }

    @Test
    void clearingTreeSelectionClearsStaleRequestEditor() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        ApiRequest request = request("req-1", "Login", "POST", "https://auth.example.test/login", 0);
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        openRequestInEditor(panel, collection, request);
        selectTreeNode(panel, requestNode(requestTree(panel), "req-1"));
        JTree tree = requestTree(panel);
        SwingUtilities.invokeAndWait(tree::clearSelection);
        drainEdt();

        assertThat(requestEditor(panel).getCurrentRequest()).isNull();
        assertThat(requestEditor(panel).getCurrentCollection()).isNull();
        assertThat(requestEditor(panel).isSendEnabled()).isFalse();
    }

    @Test
    void sameSelectedRequestReenablesSendAfterCompletion() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        ApiRequest request = request("req-1", "Login", "POST", "https://auth.example.test/login", 0);
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        openRequestInEditor(panel, collection, request);
        requestEditor(panel).setSendEnabled(false);

        panel.applyWorkbenchSendCompletionState(request, collection);

        assertThat(requestEditor(panel).isSendEnabled()).isTrue();
        assertThat(requestEditor(panel).getCurrentRequest()).isSameAs(request);
        assertThat(requestEditor(panel).getCurrentCollection()).isSameAs(collection);
    }

    @Test
    void clearedEditorStaysDisabledAfterCompletion() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        ApiRequest request = request("req-1", "Login", "POST", "https://auth.example.test/login", 0);
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        openRequestInEditor(panel, collection, request);
        selectTreeNode(panel, collectionNode(requestTree(panel), "APIM"));
        drainEdt();

        panel.applyWorkbenchSendCompletionState(request, collection);

        assertThat(requestEditor(panel).getCurrentRequest()).isNull();
        assertThat(requestEditor(panel).getCurrentCollection()).isNull();
        assertThat(requestEditor(panel).isSendEnabled()).isFalse();
    }

    @Test
    void oldSendCompletionDoesNotOverrideDifferentSelectedRequest() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        ApiRequest requestA = request("req-a", "Login", "POST", "https://auth.example.test/login", 0);
        ApiRequest requestB = request("req-b", "Refresh", "POST", "https://auth.example.test/refresh", 1);
        collection.requests.add(requestA);
        collection.requests.add(requestB);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        openRequestInEditor(panel, collection, requestA);
        requestEditor(panel).setSendEnabled(false);
        selectTreeNode(panel, requestNode(requestTree(panel), "req-b"));
        drainEdt();

        panel.applyWorkbenchSendCompletionState(requestA, collection);

        assertThat(requestEditor(panel).getCurrentRequest()).isSameAs(requestB);
        assertThat(requestEditor(panel).getCurrentCollection()).isSameAs(collection);
        assertThat(requestEditor(panel).isSendEnabled()).isTrue();
    }

    @Test
    void newRequestUnderCollectionCreatesBlankGetRequestAndOpensEditor() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();
        CollectionTreeNode collectionNode = collectionNode(requestTree(panel), "APIM");

        invokeOnEdt(panel, "createNewRequestFromTree", new Class<?>[]{CollectionTreeNode.class}, collectionNode);
        drainEdt();

        CollectionTreeNode createdRequest = requestNode(requestTree(panel), "Untitled Request");
        assertThat(createdRequest).isNotNull();
        assertThat(createdRequest.request.method).isEqualTo("GET");
        assertThat(createdRequest.request.url).isEqualTo("");
        assertThat(createdRequest.request.path).isEqualTo("");
        assertThat(createdRequest.request.headers).isEmpty();
        assertThat(createdRequest.request.body).isNull();
        assertThat(createdRequest.request.editorMaterialized).isFalse();
        assertThat(createdRequest.request.buildMode).isEqualTo(ApiRequest.BuildMode.AUTO_COMPATIBLE);
        assertThat(createdRequest.request.suppressedAutoHeaders).isEmpty();
        assertThat(createdRequest.request.authOverrideMode).isEqualTo("inherit");
        assertThat(createdRequest.request.auth).isNull();
        assertThat(headerValues(headersModel(requestEditor(panel))))
                .containsEntry("Accept", "application/json, text/plain, */*")
                .containsEntry("User-Agent", "BurpExtensionRuntime")
                .containsEntry("Cache-Control", "no-cache");
        assertThat(requestEditor(panel).getCurrentRequest()).isSameAs(createdRequest.request);
        assertThat(requestEditor(panel).getCurrentCollection()).isSameAs(collection);
        assertThat(requestEditor(panel).isSendEnabled()).isTrue();
        assertThat(folderPaths(requestTree(panel))).isEmpty();
        assertThat(collection.runtimeVars).isEmpty();
        assertThat(collection.runtimeOAuth2).isEmpty();
    }

    @Test
    void createNewCollectionClearsStaleRequestEditor() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        ApiRequest request = request("req-1", "Login", "POST", "https://auth.example.test/login", 0);
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        openRequestInEditor(panel, collection, request);
        invokeOnEdt(panel, "createNewCollectionFromTree");
        drainEdt();

        assertThat(requestEditor(panel).getCurrentRequest()).isNull();
        assertThat(requestEditor(panel).getCurrentCollection()).isNull();
        assertThat(requestEditor(panel).isSendEnabled()).isFalse();
        assertThat(collectionNode(requestTree(panel), "Untitled Collection")).isNotNull();
    }

    @Test
    void sameNameRequestStaysNestedUnderMatchingFolder() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        ApiRequest request = request("req-1", "Auth", "POST", "https://auth.example.test/auth", 0);
        request.path = "Auth";
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        CollectionTreeNode apimNode = collectionNode(requestTree(panel), "APIM");
        CollectionTreeNode authNode = folderNodeByPath(requestTree(panel), "Auth");

        assertThat(directRequestNames(apimNode)).isEmpty();
        assertThat(leafRequestNames(authNode)).containsExactly("Auth");
        assertThat(requestNode(requestTree(panel), "req-1")).isNotNull();
    }

    @Test
    void nestedSameLeafRequestStaysNestedUnderMatchingFolder() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        collection.folderPaths.add("Auth/OAuth");
        ApiRequest request = request("req-1", "OAuth", "POST", "https://auth.example.test/oauth", 0);
        request.path = "Auth/OAuth";
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        CollectionTreeNode apimNode = collectionNode(requestTree(panel), "APIM");
        CollectionTreeNode authNode = folderNodeByPath(requestTree(panel), "Auth");
        CollectionTreeNode oauthNode = folderNodeByPath(requestTree(panel), "Auth/OAuth");

        assertThat(directRequestNames(apimNode)).isEmpty();
        assertThat(directRequestNames(authNode)).isEmpty();
        assertThat(leafRequestNames(oauthNode)).containsExactly("OAuth");
        assertThat(requestNode(requestTree(panel), "req-1")).isNotNull();
    }

    @Test
    void createNewFolderClearsStaleRequestEditor() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        ApiRequest request = request("req-1", "Login", "POST", "https://auth.example.test/login", 0);
        request.path = "Auth";
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        openRequestInEditor(panel, collection, request);
        invokeOnEdt(panel, "createNewFolderFromTree", new Class<?>[]{CollectionTreeNode.class}, collectionNode(requestTree(panel), "APIM"));
        drainEdt();

        assertThat(requestEditor(panel).getCurrentRequest()).isNull();
        assertThat(requestEditor(panel).getCurrentCollection()).isNull();
        assertThat(requestEditor(panel).isSendEnabled()).isFalse();
        assertThat(folderNodeByPath(requestTree(panel), "Untitled Folder")).isNotNull();
    }

    @Test
    void newRequestUnderFolderCreatesBlankGetRequestAndOpensEditor() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();
        CollectionTreeNode folderNode = folderNodeByPath(requestTree(panel), "Auth");

        invokeOnEdt(panel, "createNewRequestFromTree", new Class<?>[]{CollectionTreeNode.class}, folderNode);
        drainEdt();

        CollectionTreeNode createdRequest = requestNode(requestTree(panel), "Untitled Request");
        assertThat(createdRequest).isNotNull();
        assertThat(createdRequest.request.path).isEqualTo("Auth");
        assertThat(createdRequest.request.method).isEqualTo("GET");
        assertThat(createdRequest.request.url).isEqualTo("");
        assertThat(((CollectionTreeNode) createdRequest.getParent()).folderPath).isEqualTo("Auth");
        assertThat(headerValues(headersModel(requestEditor(panel))))
                .containsEntry("Accept", "application/json, text/plain, */*")
                .containsEntry("User-Agent", "BurpExtensionRuntime")
                .containsEntry("Cache-Control", "no-cache");
        assertThat(folderPaths(requestTree(panel))).containsExactly("Auth");
        assertThat(requestEditor(panel).getCurrentRequest()).isSameAs(createdRequest.request);
        assertThat(requestEditor(panel).getCurrentCollection()).isSameAs(collection);
        assertThat(requestEditor(panel).isSendEnabled()).isTrue();
    }

    @Test
    void renameCollectionRejectsDuplicateNameAndLeavesModelUnchanged() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection apim = collection("APIM");
        ApiCollection admin = collection("Admin");
        panel.restoreWorkspaceCollections(List.of(apim, admin));
        drainEdt();

        CollectionTreeNode adminNode = collectionNode(requestTree(panel), "Admin");
        treeModel(panel).valueForPathChanged(new TreePath(adminNode.getPath()), "APIM");
        drainEdt();

        assertThat(admin.name).isEqualTo("Admin");
        assertThat(adminNode.getUserObject()).isEqualTo("Admin");
        assertThat(loadedCollections(panel)).extracting(col -> col.name).containsExactly("APIM", "Admin");
    }

    @Test
    void renameFolderRejectsSiblingRequestCollisionAndLeavesModelUnchanged() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        collection.requests.add(request("req-1", "Login", "POST", "https://auth.example.test/login", 0));
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        CollectionTreeNode folderNode = folderNodeByPath(requestTree(panel), "Auth");
        treeModel(panel).valueForPathChanged(new TreePath(folderNode.getPath()), "Login");
        drainEdt();

        assertThat(collection.folderPaths).containsExactly("Auth");
        assertThat(folderNode.folderPath).isEqualTo("Auth");
        assertThat(folderNode.getUserObject()).isEqualTo("Auth");
    }

    @Test
    void renameRequestRejectsSiblingFolderCollisionAndLeavesModelUnchanged() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        ApiRequest request = request("req-1", "Login", "POST", "https://auth.example.test/login", 0);
        request.path = "";
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        CollectionTreeNode requestNode = requestNode(requestTree(panel), "req-1");
        treeModel(panel).valueForPathChanged(new TreePath(requestNode.getPath()), "Auth");
        drainEdt();

        assertThat(request.name).isEqualTo("Login");
        assertThat(request.path).isEqualTo("");
        assertThat(requestNode.getUserObject()).isEqualTo("Login");
    }

    @Test
    void inlineRenameRequestUpdatesUnderlyingRequestName() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        ApiRequest request = request("req-1", "Get Token", "POST", "https://auth.example.test/token", 0);
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        CollectionTreeNode requestNode = requestNode(requestTree(panel), "req-1");
        DefaultTreeModel model = treeModel(panel);
        model.valueForPathChanged(new TreePath(requestNode.getPath()), "Renamed Token");
        drainEdt();

        assertThat(request.name).isEqualTo("Renamed Token");
        assertThat(request.path).isEqualTo("");
        assertThat(requestNode.getUserObject()).isEqualTo("Renamed Token");
    }

    @Test
    void renameCollectionUpdatesModelTreeAndMarksDirty() throws Exception {
        ImporterPanel panel = newPanel();
        AtomicInteger dirtyCount = watchWorkspaceChanges(panel);
        ApiCollection collection = collection("APIM");
        ApiRequest request = request("req-1", "Get Token", "POST", "https://auth.example.test/token", 0);
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        CollectionTreeNode collectionNode = collectionNode(requestTree(panel), "APIM");
        treeModel(panel).valueForPathChanged(new TreePath(collectionNode.getPath()), "APIM Renamed");
        drainEdt();

        assertThat(collection.name).isEqualTo("APIM Renamed");
        assertThat(request.sourceCollection).isEqualTo("APIM Renamed");
        assertThat(collectionNode.getUserObject()).isEqualTo("APIM Renamed");
        assertThat(dirtyCount.get()).isGreaterThan(0);
    }

    @Test
    void renameFolderUpdatesChildRequestFolderPathAndMarksDirty() throws Exception {
        ImporterPanel panel = newPanel();
        AtomicInteger dirtyCount = watchWorkspaceChanges(panel);
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        ApiRequest request = request("req-1", "Get Token", "POST", "https://auth.example.test/token", 0);
        request.path = "Auth";
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        CollectionTreeNode folderNode = folderNodeByPath(requestTree(panel), "Auth");
        treeModel(panel).valueForPathChanged(new TreePath(folderNode.getPath()), "OAuth");
        drainEdt();

        assertThat(collection.folderPaths).contains("OAuth");
        assertThat(request.path).isEqualTo("OAuth");
        assertThat(folderNode.folderPath).isEqualTo("OAuth");
        assertThat(dirtyCount.get()).isGreaterThan(0);
    }

    @Test
    void renameRequestUpdatesModelTreeAndEditorSelection() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        ApiRequest request = request("req-1", "Get Token", "POST", "https://auth.example.test/token", 0);
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        openRequestInEditor(panel, collection, request);
        CollectionTreeNode requestNode = requestNode(requestTree(panel), "req-1");
        treeModel(panel).valueForPathChanged(new TreePath(requestNode.getPath()), "Renamed Token");
        drainEdt();

        assertThat(request.name).isEqualTo("Renamed Token");
        assertThat(requestEditor(panel).getCurrentRequest()).isSameAs(request);
        assertThat(requestEditor(panel).getCurrentCollection()).isSameAs(collection);
        assertThat(requestNode.getUserObject()).isEqualTo("Renamed Token");
    }

    @Test
    void duplicateCollectionCopiesFoldersRequestsAndUsesCopySuffix() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection source = collection("APIM");
        source.folderPaths.add("Auth");
        source.folderPaths.add("Auth/OAuth");
        ApiRequest token = request("req-token", "Get Token", "POST", "https://auth.example.test/token", 0);
        token.path = "Auth/OAuth";
        token.headers.add(new ApiRequest.Header("X-Test", "1"));
        ApiRequest refresh = request("req-refresh", "Refresh Token", "POST", "https://auth.example.test/refresh", 1);
        refresh.path = "Auth/OAuth";
        source.requests.add(token);
        source.requests.add(refresh);
        panel.restoreWorkspaceCollections(List.of(source));
        drainEdt();

        CollectionTreeNode sourceNode = collectionNode(requestTree(panel), "APIM");
        invokeOnEdt(panel, "duplicateCollectionNode", new Class<?>[]{CollectionTreeNode.class}, sourceNode);
        drainEdt();

        assertThat(loadedCollections(panel)).hasSize(2);
        ApiCollection duplicate = loadedCollections(panel).get(1);
        assertThat(duplicate.name).isEqualTo("APIM Copy");
        assertThat(duplicate.folderPaths).contains("Auth", "Auth/OAuth");
        assertThat(duplicate.requests).hasSize(2);
        assertThat(duplicate.requests.get(0).id).isNotEqualTo(token.id);
        assertThat(duplicate.requests.get(0).name).isEqualTo("Get Token");
        assertThat(duplicate.requests.get(0).path).isEqualTo("Auth/OAuth");
        assertThat(collectionNode(requestTree(panel), "APIM Copy")).isNotNull();
    }

    @Test
    void duplicateFolderCopiesSubtreeAndUsesCopySuffix() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        collection.folderPaths.add("Auth/OAuth");
        ApiRequest token = request("req-token", "Get Token", "POST", "https://auth.example.test/token", 0);
        token.path = "Auth/OAuth";
        ApiRequest refresh = request("req-refresh", "Refresh Token", "POST", "https://auth.example.test/refresh", 1);
        refresh.path = "Auth";
        collection.requests.add(token);
        collection.requests.add(refresh);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        CollectionTreeNode folderNode = folderNodeByPath(requestTree(panel), "Auth");
        invokeOnEdt(panel, "duplicateFolderNode", new Class<?>[]{CollectionTreeNode.class}, folderNode);
        drainEdt();

        CollectionTreeNode duplicateFolder = folderNodeByPath(requestTree(panel), "Auth Copy");
        assertThat(duplicateFolder).isNotNull();
        assertThat(leafRequestNames(duplicateFolder)).contains("Get Token", "Refresh Token");
        assertThat(folderNodeByPath(requestTree(panel), "Auth Copy/OAuth")).isNotNull();
    }

    @Test
    void duplicateRequestCopiesRequestFieldsAndOpensDuplicateInEditor() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        ApiRequest request = request("req-1", "Login", "POST", "https://api.example.test/login", 0);
        request.headers.add(new ApiRequest.Header("X-Test", "1"));
        request.preRequestScripts.add(new ApiRequest.Script("js", "console.log('pre');"));
        request.postResponseScripts.add(new ApiRequest.Script("js", "console.log('post');"));
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"login\":true}";
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        CollectionTreeNode requestNode = requestNode(requestTree(panel), "req-1");
        invokeOnEdt(panel, "duplicateRequestNode", new Class<?>[]{CollectionTreeNode.class}, requestNode);
        drainEdt();

        assertThat(collection.requests).hasSize(2);
        ApiRequest duplicate = collection.requests.get(1);
        assertThat(duplicate.name).isEqualTo("Login Copy");
        assertThat(duplicate.method).isEqualTo("POST");
        assertThat(duplicate.url).isEqualTo("https://api.example.test/login");
        assertThat(duplicate.headers).extracting(header -> ((ApiRequest.Header) header).key).contains("X-Test");
        assertThat(duplicate.preRequestScripts).hasSize(1);
        assertThat(duplicate.postResponseScripts).hasSize(1);
        assertThat(duplicate.body).isNotNull();
        assertThat(duplicate.body.raw).isEqualTo("{\"login\":true}");
        assertThat(duplicate.id).isNotEqualTo(request.id);
        assertThat(requestEditor(panel).getCurrentRequest()).isSameAs(duplicate);
    }

    @Test
    void renamingRequestToSlashLabelDoesNotCreatePhantomFolders() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        CollectionTreeNode collectionNode = collectionNode(requestTree(panel), "APIM");
        invokeOnEdt(panel, "createNewRequestFromTree", new Class<?>[]{CollectionTreeNode.class}, collectionNode);
        drainEdt();

        CollectionTreeNode requestNode = requestNode(requestTree(panel), "Untitled Request");
        treeModel(panel).valueForPathChanged(new TreePath(requestNode.getPath()), "GET /users");
        drainEdt();

        CollectionTreeNode renamed = requestNode(requestTree(panel), "GET /users");
        assertThat(renamed).isNotNull();
        assertThat(renamed.request.name).isEqualTo("GET /users");
        assertThat(renamed.request.path).isEqualTo("");
        assertThat(((CollectionTreeNode) renamed.getParent()).getNodeType())
                .isEqualTo(CollectionTreeNode.Type.COLLECTION);
        assertThat(requestEditor(panel).getCurrentRequest()).isSameAs(renamed.request);
        assertThat(requestEditor(panel).getCurrentCollection()).isSameAs(collection);
        assertThat(folderPaths(requestTree(panel))).isEmpty();
    }

    @Test
    void duplicatingRootSlashRequestDoesNotCreatePhantomFolders() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        CollectionTreeNode collectionNode = collectionNode(requestTree(panel), "APIM");
        invokeOnEdt(panel, "createNewRequestFromTree", new Class<?>[]{CollectionTreeNode.class}, collectionNode);
        drainEdt();

        CollectionTreeNode requestNode = requestNode(requestTree(panel), "Untitled Request");
        treeModel(panel).valueForPathChanged(new TreePath(requestNode.getPath()), "GET /users");
        drainEdt();

        CollectionTreeNode slashRequestNode = requestNode(requestTree(panel), "GET /users");
        invokeOnEdt(panel, "duplicateRequestNode", new Class<?>[]{CollectionTreeNode.class}, slashRequestNode);
        drainEdt();

        CollectionTreeNode duplicate = requestNode(requestTree(panel), "GET /users Copy");
        assertThat(duplicate).isNotNull();
        assertThat(duplicate.request.name).isEqualTo("GET /users Copy");
        assertThat(duplicate.request.path).isEqualTo("");
        assertThat(((CollectionTreeNode) duplicate.getParent()).getNodeType())
                .isEqualTo(CollectionTreeNode.Type.COLLECTION);
        assertThat(requestEditor(panel).getCurrentRequest()).isSameAs(duplicate.request);
        assertThat(requestEditor(panel).getCurrentCollection()).isSameAs(collection);
        assertThat(folderPaths(requestTree(panel))).isEmpty();
    }

    @Test
    void duplicatingFolderRequestWithSlashNameStaysInSameFolder() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        ApiRequest request = request("req-1", "POST /token", "POST", "https://auth.example.test/token", 0);
        request.path = "Auth";
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        CollectionTreeNode requestNode = requestNode(requestTree(panel), "req-1");
        invokeOnEdt(panel, "duplicateRequestNode", new Class<?>[]{CollectionTreeNode.class}, requestNode);
        drainEdt();

        CollectionTreeNode duplicate = requestNode(requestTree(panel), "POST /token Copy");
        assertThat(duplicate).isNotNull();
        assertThat(duplicate.request.name).isEqualTo("POST /token Copy");
        assertThat(duplicate.request.path).isEqualTo("Auth");
        assertThat(((CollectionTreeNode) duplicate.getParent()).folderPath).isEqualTo("Auth");
        assertThat(requestEditor(panel).getCurrentRequest()).isSameAs(duplicate.request);
        assertThat(requestEditor(panel).getCurrentCollection()).isSameAs(collection);
        assertThat(folderPaths(requestTree(panel))).containsExactly("Auth");
    }

    @Test
    void renameFolderRejectsSlashAndBackslashLabels() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        CollectionTreeNode folderNode = folderNodeByPath(requestTree(panel), "Auth");
        treeModel(panel).valueForPathChanged(new TreePath(folderNode.getPath()), "Auth/OAuth");
        drainEdt();
        treeModel(panel).valueForPathChanged(new TreePath(folderNode.getPath()), "Auth\\OAuth");
        drainEdt();

        assertThat(collection.folderPaths).containsExactly("Auth");
        assertThat(folderPaths(requestTree(panel))).containsExactly("Auth");
        assertThat(folderNode.folderPath).isEqualTo("Auth");
        assertThat(folderNode.getUserObject()).isEqualTo("Auth");
    }

    @Test
    void requestRenameCollisionStillRejectsSlashLabels() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        ApiRequest slashRequest = request("req-1", "GET /users", "GET", "", 0);
        slashRequest.path = "";
        ApiRequest otherRequest = request("req-2", "Other", "GET", "", 1);
        otherRequest.path = "";
        collection.requests.add(slashRequest);
        collection.requests.add(otherRequest);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        CollectionTreeNode otherNode = requestNode(requestTree(panel), "req-2");
        treeModel(panel).valueForPathChanged(new TreePath(otherNode.getPath()), "GET /users");
        drainEdt();

        assertThat(otherRequest.name).isEqualTo("Other");
        assertThat(otherRequest.path).isEqualTo("");
        assertThat(otherNode.getUserObject()).isEqualTo("Other");
        assertThat(folderPaths(requestTree(panel))).isEmpty();
    }

    @Test
    void sameSlashRequestNameAllowedInDifferentFolders() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        collection.folderPaths.add("Users");
        ApiRequest authRequest = request("req-1", "GET /users", "GET", "", 0);
        authRequest.path = "Auth";
        ApiRequest usersRequest = request("req-2", "GET /users", "GET", "", 1);
        usersRequest.path = "Users";
        collection.requests.add(authRequest);
        collection.requests.add(usersRequest);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        assertThat(requestNode(requestTree(panel), "req-1")).isNotNull();
        assertThat(requestNode(requestTree(panel), "req-2")).isNotNull();
        assertThat(leafRequestNames(folderNodeByPath(requestTree(panel), "Auth"))).containsExactly("GET /users");
        assertThat(leafRequestNames(folderNodeByPath(requestTree(panel), "Users"))).containsExactly("GET /users");
        assertThat(folderPaths(requestTree(panel))).containsExactly("Auth", "Users");
    }

    @Test
    void deleteCollectionRemovesCollectionMappingsAndQueuedRequests() throws Exception {
        ImporterPanel panel = newPanelWithDeleteDecision(null, true);
        ApiCollection collection = collection("APIM");
        ApiRequest request = request("req-1", "Get Token", "POST", "https://auth.example.test/token", 0);
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();
        queueRunnerRequests(panel, List.of(request));
        assertThat(runnerQueuedRequests(panel)).containsExactly(request);

        CollectionTreeNode collectionNode = collectionNode(requestTree(panel), "APIM");
        invokeOnEdt(panel, "deleteCollectionNode", new Class<?>[]{CollectionTreeNode.class}, collectionNode);
        drainEdt();

        assertThat(loadedCollections(panel)).isEmpty();
        assertThat(runnerQueuedRequests(panel)).isEmpty();
        assertThat(requestEditor(panel).getCurrentRequest()).isNull();
    }

    @Test
    void deleteFolderRemovesSubtreeMappingsAndQueuedRequests() throws Exception {
        ImporterPanel panel = newPanelWithDeleteDecision(null, true);
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        collection.folderPaths.add("Auth/OAuth");
        ApiRequest token = request("req-token", "Get Token", "POST", "https://auth.example.test/token", 0);
        token.path = "Auth/OAuth";
        ApiRequest refresh = request("req-refresh", "Refresh Token", "POST", "https://auth.example.test/refresh", 1);
        refresh.path = "Auth";
        collection.requests.add(token);
        collection.requests.add(refresh);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();
        queueRunnerRequests(panel, List.of(token, refresh));

        openRequestInEditor(panel, collection, token);
        CollectionTreeNode folderNode = folderNodeByPath(requestTree(panel), "Auth");
        invokeOnEdt(panel, "deleteFolderNode", new Class<?>[]{CollectionTreeNode.class}, folderNode);
        drainEdt();

        assertThat(collection.requests).isEmpty();
        assertThat(collection.folderPaths).isEmpty();
        assertThat(runnerQueuedRequests(panel)).isEmpty();
        assertThat(requestEditor(panel).getCurrentRequest()).isNull();
    }

    @Test
    void deleteRequestRemovesMappingQueueAndClearsEditorWhenSelected() throws Exception {
        ImporterPanel panel = newPanelWithDeleteDecision(null, true);
        ApiCollection collection = collection("APIM");
        ApiRequest request = request("req-1", "Get Token", "POST", "https://auth.example.test/token", 0);
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();
        openRequestInEditor(panel, collection, request);
        queueRunnerRequests(panel, List.of(request));

        CollectionTreeNode requestNode = requestNode(requestTree(panel), "req-1");
        invokeOnEdt(panel, "deleteRequestNode", new Class<?>[]{CollectionTreeNode.class}, requestNode);
        drainEdt();

        assertThat(collection.requests).isEmpty();
        assertThat(runnerQueuedRequests(panel)).isEmpty();
        assertThat(requestEditor(panel).getCurrentRequest()).isNull();
    }

    @Test
    void deleteActionsRequireConfirmation() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        ImporterPanel panel = newPanelWithDeleteDecision(captured, false);
        ApiCollection collection = collection("APIM");
        ApiRequest request = request("req-1", "Get Token", "POST", "https://auth.example.test/token", 0);
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        CollectionTreeNode requestNode = requestNode(requestTree(panel), "req-1");
        invokeOnEdt(panel, "deleteRequestNode", new Class<?>[]{CollectionTreeNode.class}, requestNode);
        drainEdt();

        assertThat(captured.get()).isEqualTo("Delete request 'Get Token'?");
        assertThat(collection.requests).hasSize(1);
        assertThat(requestEditor(panel).getCurrentRequest()).isNull();
    }

    @Test
    void duplicateDoesNotCopyRunnerQueueOrRuntimeExecutionState() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        ApiRequest request = request("req-1", "Login", "POST", "https://api.example.test/login", 0);
        collection.requests.add(request);
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();
        queueRunnerRequests(panel, List.of(request));

        CollectionTreeNode requestNode = requestNode(requestTree(panel), "req-1");
        invokeOnEdt(panel, "duplicateRequestNode", new Class<?>[]{CollectionTreeNode.class}, requestNode);
        drainEdt();

        assertThat(runnerQueuedRequests(panel)).containsExactly(request);
        assertThat(collection.runtimeVars).isEmpty();
        assertThat(collection.runtimeOAuth2).isEmpty();
    }

    @Test
    void manuallyCreatedRequestAppearsInActionsSelection() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();
        CollectionTreeNode collectionNode = collectionNode(requestTree(panel), "APIM");

        invokeOnEdt(panel, "createNewRequestFromTree", new Class<?>[]{CollectionTreeNode.class}, collectionNode);
        drainEdt();

        ApiRequest created = requestEditor(panel).getCurrentRequest();
        queueRunnerRequests(panel, List.of(created));
        assertThat(runnerQueuedRequests(panel)).containsExactly(created);
    }

    @Test
    void manuallyCreatedCollectionSurvivesWorkspaceRestore() throws Exception {
        ImporterPanel panel = newPanel();
        invokeOnEdt(panel, "createNewCollectionFromTree");
        drainEdt();

        ApiCollection collection = loadedCollections(panel).get(0);
        collection.folderPaths.add("Auth");
        ApiRequest request = request("req-1", "Get Token", "GET", "", 0);
        request.path = "Auth";
        request.headers.add(new ApiRequest.Header("X-Test", "1"));
        collection.requests.add(request);

        WorkspaceState snapshot = panel.getWorkspaceStateSnapshot();
        String json = burp.utils.WorkspaceStateJson.toJson(snapshot);
        WorkspaceState restored = burp.utils.WorkspaceStateJson.fromJson(json);

        ImporterPanel restoredPanel = newPanel();
        restoredPanel.restoreWorkspaceState(restored);
        drainEdt();

        assertThat(collectionNode(requestTree(restoredPanel), "Untitled Collection")).isNotNull();
        assertThat(folderNodeByPath(requestTree(restoredPanel), "Auth")).isNotNull();
        assertThat(requestNode(requestTree(restoredPanel), "req-1")).isNotNull();
    }

    @Test
    void authSettingsStillAvailableForExistingNodes() {
        ImporterPanel panel = newPanel();
        CollectionTreeNode collectionNode = new CollectionTreeNode(collection("APIM"));
        CollectionTreeNode folderNode = new CollectionTreeNode("Auth");
        folderNode.folderPath = "Auth";
        CollectionTreeNode requestNode = new CollectionTreeNode(request("req-1", "Get Token", "POST", "https://auth.example.test/token", 0));

        assertThat(menuLabels(panel.buildRequestTreeContextMenu(collectionNode))).contains("Auth Settings...");
        assertThat(menuLabels(panel.buildRequestTreeContextMenu(folderNode))).contains("Auth Settings...");
        assertThat(menuLabels(panel.buildRequestTreeContextMenu(requestNode))).contains("Auth Settings...");
    }

    private static ImporterPanel newPanel() {
        UniversalImporter importer = Mockito.mock(UniversalImporter.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(importer.getApi().userInterface().createHttpRequestEditor(Mockito.any())).thenReturn(Mockito.mock(burp.api.montoya.ui.editor.HttpRequestEditor.class, Mockito.RETURNS_DEEP_STUBS));
        Mockito.when(importer.getApi().userInterface().createHttpResponseEditor(Mockito.any())).thenReturn(Mockito.mock(burp.api.montoya.ui.editor.HttpResponseEditor.class, Mockito.RETURNS_DEEP_STUBS));
        Mockito.when(importer.getApi().userInterface().createHttpRequestEditor(Mockito.any()).uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpResponseEditor(Mockito.any()).uiComponent()).thenReturn(new JPanel());
        CollectionRunner runner = Mockito.mock(CollectionRunner.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(runner.isRunning()).thenReturn(false);
        OAuth2Manager oauth2Manager = Mockito.mock(OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS);
        return new ImporterPanel(importer, runner, oauth2Manager, burp.utils.ScriptMode.DISABLED);
    }

    private static ImporterPanel newPanelWithDeleteDecision(AtomicReference<String> captured, boolean approve) {
        UniversalImporter importer = Mockito.mock(UniversalImporter.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(importer.getApi().userInterface().createHttpRequestEditor(Mockito.any())).thenReturn(Mockito.mock(burp.api.montoya.ui.editor.HttpRequestEditor.class, Mockito.RETURNS_DEEP_STUBS));
        Mockito.when(importer.getApi().userInterface().createHttpResponseEditor(Mockito.any())).thenReturn(Mockito.mock(burp.api.montoya.ui.editor.HttpResponseEditor.class, Mockito.RETURNS_DEEP_STUBS));
        Mockito.when(importer.getApi().userInterface().createHttpRequestEditor(Mockito.any()).uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpResponseEditor(Mockito.any()).uiComponent()).thenReturn(new JPanel());
        CollectionRunner runner = Mockito.mock(CollectionRunner.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(runner.isRunning()).thenReturn(false);
        OAuth2Manager oauth2Manager = Mockito.mock(OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS);
        return new ImporterPanel(importer, runner, oauth2Manager, burp.utils.ScriptMode.DISABLED) {
            @Override
            protected boolean confirmDelete(String message) {
                if (captured != null) {
                    captured.set(message);
                }
                return approve;
            }
        };
    }

    private static void openRequestInEditor(ImporterPanel panel, ApiCollection collection, ApiRequest request) throws Exception {
        Field editorField = ImporterPanel.class.getDeclaredField("requestEditor");
        editorField.setAccessible(true);
        RequestEditorPanel editor = (RequestEditorPanel) editorField.get(panel);
        editor.setCurrentCollection(collection);
        editor.loadRequest(request);
    }

    private static void selectTreeNode(ImporterPanel panel, CollectionTreeNode node) throws Exception {
        if (node == null) {
            return;
        }
        JTree tree = requestTree(panel);
        SwingUtilities.invokeAndWait(() -> tree.setSelectionPath(new TreePath(node.getPath())));
    }

    private static void queueRunnerRequests(ImporterPanel panel, List<ApiRequest> selected) throws Exception {
        invokePrivate(panel, "queueRunnerRequests", new Class<?>[]{List.class}, selected);
        drainEdt();
    }

    private static void invokeOnEdt(ImporterPanel panel, String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                invokePrivate(panel, methodName, paramTypes, args);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void invokeOnEdt(ImporterPanel panel, String methodName) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                invokePrivate(panel, methodName, new Class<?>[0]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void invokePrivate(ImporterPanel panel, String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        method.invoke(panel, args);
    }

    private static AtomicInteger watchWorkspaceChanges(ImporterPanel panel) throws Exception {
        AtomicInteger counter = new AtomicInteger();
        panel.setWorkspaceChangeListener(counter::incrementAndGet);
        return counter;
    }

    private static List<ApiCollection> loadedCollections(ImporterPanel panel) throws Exception {
        return privateField(panel, "loadedCollections");
    }

    private static List<ApiRequest> runnerQueuedRequests(ImporterPanel panel) throws Exception {
        return privateField(panel, "runnerQueuedRequests");
    }

    private static JTree requestTree(ImporterPanel panel) throws Exception {
        return privateField(panel, "requestTree");
    }

    private static DefaultTreeModel treeModel(ImporterPanel panel) throws Exception {
        return privateField(panel, "treeModel");
    }

    private static RequestEditorPanel requestEditor(ImporterPanel panel) throws Exception {
        return privateField(panel, "requestEditor");
    }

    private static DefaultTableModel headersModel(RequestEditorPanel editor) throws Exception {
        return privateField(editor, "headersModel");
    }

    private static Map<String, String> headerValues(DefaultTableModel model) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            String key = (String) model.getValueAt(i, 0);
            String value = (String) model.getValueAt(i, 1);
            if (key != null && !key.isBlank()) {
                out.put(key, value);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T> T privateField(Object target, String fieldName) throws Exception {
        Class<?> type = target.getClass();
        Field field = null;
        while (type != null) {
            try {
                field = type.getDeclaredField(fieldName);
                break;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        if (field == null) {
            throw new NoSuchFieldException(fieldName);
        }
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static ApiCollection collection(String name) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        return collection;
    }

    private static ApiRequest request(String id, String name, String method, String url, int sequenceOrder) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.method = method;
        request.url = url;
        request.sequenceOrder = sequenceOrder;
        request.path = name;
        request.sourceCollection = "APIM";
        return request;
    }

    private static CollectionTreeNode collectionNode(JTree tree, String collectionName) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        return collectionNode(root, collectionName);
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
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        return folderNodeByPath(root, folderPath);
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
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        return requestNode(root, requestId);
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

    private static List<String> leafRequestNames(CollectionTreeNode node) {
        List<String> names = new ArrayList<>();
        if (node == null) {
            return names;
        }
        collectRequestNames(node, names);
        return names;
    }

    private static List<String> directRequestNames(CollectionTreeNode node) {
        List<String> names = new ArrayList<>();
        if (node == null) {
            return names;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            Object child = node.getChildAt(i);
            if (child instanceof CollectionTreeNode) {
                CollectionTreeNode ctn = (CollectionTreeNode) child;
                if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST && ctn.request != null) {
                    names.add(ctn.request.name);
                }
            }
        }
        return names;
    }

    private static void collectRequestNames(DefaultMutableTreeNode node, List<String> names) {
        if (node == null) {
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            Object child = node.getChildAt(i);
            if (child instanceof CollectionTreeNode) {
                CollectionTreeNode ctn = (CollectionTreeNode) child;
                if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST && ctn.request != null) {
                    names.add(ctn.request.name);
                } else {
                    collectRequestNames(ctn, names);
                }
            }
        }
    }

    private static List<String> folderPaths(JTree tree) {
        List<String> paths = new ArrayList<>();
        if (tree == null || tree.getModel() == null) {
            return paths;
        }
        collectFolderPaths((DefaultMutableTreeNode) tree.getModel().getRoot(), paths);
        return paths;
    }

    private static void collectFolderPaths(DefaultMutableTreeNode node, List<String> paths) {
        if (node == null) {
            return;
        }
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.FOLDER && ctn.folderPath != null) {
                paths.add(ctn.folderPath);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectFolderPaths((DefaultMutableTreeNode) node.getChildAt(i), paths);
        }
    }

    private static List<String> menuLabels(JPopupMenu menu) {
        List<String> labels = new ArrayList<>();
        if (menu == null) {
            return labels;
        }
        for (java.awt.Component component : menu.getComponents()) {
            if (component instanceof JMenuItem) {
                labels.add(((JMenuItem) component).getText());
            }
        }
        return labels;
    }

    private static void drainEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
        SwingUtilities.invokeAndWait(() -> {});
    }
}
