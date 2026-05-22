package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspaceState;
import burp.runner.CollectionRunner;
import burp.ui.tree.BurpLikeTreeCellRenderer;
import burp.ui.tree.CollectionTreeNode;
import burp.auth.OAuth2Manager;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import javax.swing.plaf.basic.BasicTreeUI;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.MatteBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImporterPanelTreeRestoreTest {

    @Test
    void rebuildRequestTreeKeepsNestedFoldersFromWorkspaceState() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest getToken = request("req-token", "Get Token", "POST", "https://auth.example.test/token", 1);
        ApiRequest refreshToken = request("req-refresh", "Refresh Token", "POST", "https://auth.example.test/refresh", 2);
        ApiRequest listUsers = request("req-users", "List Users", "GET", "https://api.example.test/users", 3);

        collection.requests.add(getToken);
        collection.requests.add(refreshToken);
        collection.requests.add(listUsers);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(0), 0), "Auth/OAuth");
        state.requestTreePaths.put(ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(1), 1), "Auth/OAuth");
        state.requestTreePaths.put(ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(2), 2), "Users");

        DefaultMutableTreeNode root = ImporterPanel.buildRequestTreeRoot(state.collections, state.requestTreePaths);
        assertThat(root.getChildCount()).isEqualTo(1);

        CollectionTreeNode apimNode = (CollectionTreeNode) root.getChildAt(0);
        assertThat(apimNode.getNodeType()).isEqualTo(CollectionTreeNode.Type.COLLECTION);
        assertThat(apimNode.collection.name).isEqualTo("APIM");
        assertThat(directRequestNames(apimNode)).isEmpty();

        CollectionTreeNode authNode = childFolder(apimNode, "Auth");
        CollectionTreeNode oauthNode = childFolder(authNode, "OAuth");
        CollectionTreeNode usersNode = childFolder(apimNode, "Users");

        assertThat(childFolderNames(apimNode)).containsExactly("Auth", "Users");
        assertThat(requestNames(oauthNode)).containsExactlyInAnyOrder("Get Token", "Refresh Token");
        assertThat(requestNames(usersNode)).containsExactly("List Users");
        assertThat(requestNames(apimNode)).isEmpty();
    }

    @Test
    void restoredRequestTreePathsAreWrittenBackToRequestsAndSurviveLaterRebuilds() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest first = flatRequest("Get");
        ApiRequest second = flatRequest("Get");
        ApiRequest third = flatRequest("Get");

        collection.requests.add(first);
        collection.requests.add(second);
        collection.requests.add(third);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(0), 0), "FolderA");
        state.requestTreePaths.put(ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(1), 1), "FolderB");
        state.requestTreePaths.put(ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(2), 2), "FolderC");

        DefaultMutableTreeNode firstBuild = ImporterPanel.buildRequestTreeRoot(state.collections, state.requestTreePaths);
        CollectionTreeNode apimNode = (CollectionTreeNode) firstBuild.getChildAt(0);
        assertThat(childFolderNames(apimNode)).containsExactly("FolderA", "FolderB", "FolderC");
        assertThat(requestNames(childFolder(apimNode, "FolderA"))).containsExactly("Get");
        assertThat(requestNames(childFolder(apimNode, "FolderB"))).containsExactly("Get");
        assertThat(requestNames(childFolder(apimNode, "FolderC"))).containsExactly("Get");

        ImporterPanel.applyWorkspaceRequestTreePathsToRequests(state.collections, state.requestTreePaths);

        assertThat(state.collections.get(0).requests).extracting(req -> req.path)
                .containsExactly("FolderA/Get", "FolderB/Get", "FolderC/Get");

        DefaultMutableTreeNode rebuilt = ImporterPanel.buildRequestTreeRoot(state.collections, null);
        CollectionTreeNode rebuiltApimNode = (CollectionTreeNode) rebuilt.getChildAt(0);
        assertThat(childFolderNames(rebuiltApimNode)).containsExactly("FolderA", "FolderB", "FolderC");
        assertThat(requestNames(childFolder(rebuiltApimNode, "FolderA"))).containsExactly("Get");
        assertThat(requestNames(childFolder(rebuiltApimNode, "FolderB"))).containsExactly("Get");
        assertThat(requestNames(childFolder(rebuiltApimNode, "FolderC"))).containsExactly("Get");
        assertThat(requestNames(rebuiltApimNode)).isEmpty();
    }

    @Test
    void jsonRoundTripRestoreKeepsNestedRequestPathsWhenTreePathOverlayIsEmpty() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest getToken = request("req-token", "Get Token", "POST", "https://auth.example.test/token", 1);
        getToken.path = "Auth/OAuth/Get Token";
        ApiRequest listUsers = request("req-users", "List Users", "GET", "https://api.example.test/users", 2);
        listUsers.path = "Users/List Users";
        collection.requests.add(getToken);
        collection.requests.add(listUsers);

        WorkspaceState roundTripped = WorkspaceStateJson.fromJson(
                WorkspaceStateJson.toJson(WorkspaceState.fromCollections(List.of(collection)))
        );

        ImporterPanel.applyWorkspaceRequestTreePathsToRequests(roundTripped.collections, roundTripped.requestTreePaths);
        DefaultMutableTreeNode root = ImporterPanel.buildRequestTreeRoot(roundTripped.collections, roundTripped.requestTreePaths);

        assertThat(roundTripped.collections.get(0).requests).extracting(req -> req.path)
                .containsExactly("Auth/OAuth/Get Token", "Users/List Users");

        CollectionTreeNode apimNode = (CollectionTreeNode) root.getChildAt(0);
        CollectionTreeNode authNode = childFolder(apimNode, "Auth");
        CollectionTreeNode oauthNode = childFolder(authNode, "OAuth");
        CollectionTreeNode usersNode = childFolder(apimNode, "Users");

        assertThat(directRequestNames(apimNode)).isEmpty();
        assertThat(requestNames(oauthNode)).containsExactly("Get Token");
        assertThat(requestNames(usersNode)).containsExactly("List Users");
    }

    @Test
    void emptyWorkspaceTreePathDoesNotFlattenExistingNestedRequestPath() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest getToken = request("req-token", "Get Token", "POST", "https://auth.example.test/token", 1);
        getToken.path = "Auth/OAuth/Get Token";
        collection.requests.add(getToken);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(0), 0), "");

        ImporterPanel.applyWorkspaceRequestTreePathsToRequests(state.collections, state.requestTreePaths);

        assertThat(state.collections.get(0).requests.get(0).path).isEqualTo("Auth/OAuth/Get Token");

        DefaultMutableTreeNode root = ImporterPanel.buildRequestTreeRoot(state.collections, state.requestTreePaths);
        CollectionTreeNode apimNode = (CollectionTreeNode) root.getChildAt(0);
        CollectionTreeNode authNode = childFolder(apimNode, "Auth");
        CollectionTreeNode oauthNode = childFolder(authNode, "OAuth");

        assertThat(directRequestNames(apimNode)).isEmpty();
        assertThat(requestNames(oauthNode)).containsExactly("Get Token");
    }

    @Test
    void legacyWorkspaceTreePathOnlyRestoresNestedFoldersAndRequestPaths() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest request = request("req-legacy-tree", "Get Token", "POST", "https://auth.example.test/token", 1);
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(
                ImporterPanel.workspaceRequestKey("APIM", state.collections.get(0).requests.get(0)),
                "Auth/OAuth"
        );

        ImporterPanel.applyWorkspaceRequestTreePathsToRequests(state.collections, state.requestTreePaths);
        assertThat(state.collections.get(0).requests.get(0).path).isEqualTo("Auth/OAuth/Get Token");

        DefaultMutableTreeNode root = ImporterPanel.buildRequestTreeRoot(state.collections, state.requestTreePaths);
        CollectionTreeNode apimNode = (CollectionTreeNode) root.getChildAt(0);
        CollectionTreeNode authNode = childFolder(apimNode, "Auth");
        CollectionTreeNode oauthNode = childFolder(authNode, "OAuth");

        assertThat(directRequestNames(apimNode)).isEmpty();
        assertThat(requestNames(oauthNode)).containsExactly("Get Token");
    }

    @Test
    void identityKeyWorkspaceTreePathWinsOverLegacyWorkspaceRequestKey() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest request = request("req-mixed-tree", "Get Token", "POST", "https://auth.example.test/token", 1);
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(
                ImporterPanel.workspaceRequestKey("APIM", state.collections.get(0).requests.get(0)),
                "Legacy/Auth"
        );
        state.requestTreePaths.put(
                ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(0), 0),
                "Preferred/Auth"
        );

        ImporterPanel.applyWorkspaceRequestTreePathsToRequests(state.collections, state.requestTreePaths);
        assertThat(state.collections.get(0).requests.get(0).path).isEqualTo("Preferred/Auth/Get Token");

        DefaultMutableTreeNode root = ImporterPanel.buildRequestTreeRoot(state.collections, state.requestTreePaths);
        CollectionTreeNode apimNode = (CollectionTreeNode) root.getChildAt(0);
        CollectionTreeNode preferredNode = childFolder(apimNode, "Preferred");
        CollectionTreeNode authNode = childFolder(preferredNode, "Auth");

        assertThat(childFolderNames(apimNode)).containsExactly("Preferred");
        assertThat(requestNames(authNode)).containsExactly("Get Token");
    }

    @Test
    void nonIndexedIdentityWorkspaceTreePathFallbackRestoresNestedFoldersAndRequestPath() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest request = request("", "Get Token", "POST", "https://auth.example.test/token", 1);
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        String nonIndexedKey = ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(0));
        String indexedKey = ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(0), 0);
        String legacyKey = ImporterPanel.workspaceRequestKey("APIM", state.collections.get(0).requests.get(0));

        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(nonIndexedKey, "Auth/OAuth");

        assertThat(state.requestTreePaths).containsOnlyKeys(nonIndexedKey);
        assertThat(state.requestTreePaths).doesNotContainKey(indexedKey);
        assertThat(state.requestTreePaths).doesNotContainKey(legacyKey);

        ImporterPanel.applyWorkspaceRequestTreePathsToRequests(state.collections, state.requestTreePaths);
        assertThat(state.collections.get(0).requests.get(0).path).isEqualTo("Auth/OAuth/Get Token");

        DefaultMutableTreeNode root = ImporterPanel.buildRequestTreeRoot(state.collections, state.requestTreePaths);
        CollectionTreeNode apimNode = (CollectionTreeNode) root.getChildAt(0);
        CollectionTreeNode authNode = childFolder(apimNode, "Auth");
        CollectionTreeNode oauthNode = childFolder(authNode, "OAuth");

        assertThat(directRequestNames(apimNode)).isEmpty();
        assertThat(requestNames(oauthNode)).containsExactly("Get Token");
    }

    @Test
    void restoreWorkspaceStateResolvesEscapedUppercaseTreePathKeys() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest request = request("req-escaped-upper", "Get Token", "POST", "https://auth.example.test/token", 0);
        request.path = "Get Token";
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        String canonicalKey = ImporterPanel.workspaceRequestTreePathKey("APIM", 0, state.collections.get(0).requests.get(0), 0);
        String escapedKey = canonicalKey.replace("\u001F", "\\u001F");
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(escapedKey, "Auth/OAuth");

        panel.restoreWorkspaceState(state);

        JTree tree = requestTree(panel);
        CollectionTreeNode apimNode = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);

        assertThat(directRequestNames(apimNode)).isEmpty();
        assertThat(requestNames(childFolder(childFolder(apimNode, "Auth"), "OAuth"))).containsExactly("Get Token");
    }

    @Test
    void restoreWorkspaceStateResolvesEscapedLowercaseTreePathKeys() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest request = request("req-escaped-lower", "Get Token", "POST", "https://auth.example.test/token", 0);
        request.path = "Get Token";
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        String canonicalKey = ImporterPanel.workspaceRequestTreePathKey("APIM", 0, state.collections.get(0).requests.get(0), 0);
        String escapedKey = canonicalKey.replace("\u001F", "\\u001f");
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(escapedKey, "Auth/OAuth");

        panel.restoreWorkspaceState(state);

        JTree tree = requestTree(panel);
        CollectionTreeNode apimNode = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);

        assertThat(directRequestNames(apimNode)).isEmpty();
        assertThat(requestNames(childFolder(childFolder(apimNode, "Auth"), "OAuth"))).containsExactly("Get Token");
    }

    @Test
    void emptyWorkspaceTreePathKeepsRootLevelRequestAtRoot() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest ping = request("req-ping", "Ping", "GET", "https://api.example.test/ping", 1);
        ping.path = "Ping";
        collection.requests.add(ping);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(0), 0), "");

        ImporterPanel.applyWorkspaceRequestTreePathsToRequests(state.collections, state.requestTreePaths);

        assertThat(state.collections.get(0).requests.get(0).path).isEqualTo("Ping");

        DefaultMutableTreeNode root = ImporterPanel.buildRequestTreeRoot(state.collections, state.requestTreePaths);
        CollectionTreeNode apimNode = (CollectionTreeNode) root.getChildAt(0);

        assertThat(childFolderNames(apimNode)).isEmpty();
        assertThat(directRequestNames(apimNode)).containsExactly("Ping");
    }

    @Test
    void restoreWorkspaceStatePrefersIdentityKeysForCheckedAndSelectedRequestsAfterPathRepair() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest checked = request("req-checked", "Check Me", "GET", "https://api.example.test/check", 0);
        checked.path = "Check Me";
        ApiRequest selected = request("req-selected", "Select Me", "POST", "https://api.example.test/select", 1);
        selected.path = "Select Me";
        collection.requests.add(checked);
        collection.requests.add(selected);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(0), 0), "FolderA");
        state.requestTreePaths.put(ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(1), 1), "FolderA");
        state.checkedRequestIdentityKeys = new ArrayList<>(List.of(
                ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(0), 0)
        ));
        state.checkedRequestKeys = new ArrayList<>(List.of("APIM\u001Fwrong/path\u001Fwrong\u001FGET\u001F0"));
        state.selectedRequestIdentityKey = ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(1), 1);
        state.selectedRequestCollectionName = "APIM";
        state.selectedRequestPath = "wrong/path";
        state.selectedRequestName = "wrong";

        panel.restoreWorkspaceState(state);

        JTree tree = requestTree(panel);
        CollectionTreeNode checkedNode = findRequestNode(tree, "req-checked");
        CollectionTreeNode selectedNode = findRequestNode(tree, "req-selected");
        CollectionTreeNode apimNode = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);
        CollectionTreeNode folderNode = childFolder(apimNode, "FolderA");

        assertThat(checkedNode.isChecked()).isTrue();
        assertThat(selectedNode.request.path).isEqualTo("Select Me");
        assertThat(requestNames(folderNode)).containsExactlyInAnyOrder("Check Me", "Select Me");
        assertThat(tree.getSelectionPath()).isNotNull();
        assertThat(((CollectionTreeNode) tree.getSelectionPath().getLastPathComponent()).request.id).isEqualTo("req-selected");
    }

    @Test
    void restoreWorkspaceStateFallsBackToLegacyPathBasedRequestRestore() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest request = request("req-legacy", "Get Me", "GET", "https://api.example.test/get", 0);
        request.path = "Folder/Get Me";
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.checkedRequestKeys = new ArrayList<>(List.of(
                ImporterPanel.workspaceRequestKey("APIM", state.collections.get(0).requests.get(0))
        ));
        state.selectedRequestCollectionName = "APIM";
        state.selectedRequestPath = "Folder/Get Me";
        state.selectedRequestName = "Get Me";

        panel.restoreWorkspaceState(state);

        JTree tree = requestTree(panel);
        CollectionTreeNode node = findRequestNode(tree, "req-legacy");

        assertThat(node.isChecked()).isTrue();
        assertThat(tree.getSelectionPath()).isNotNull();
        assertThat(((CollectionTreeNode) tree.getSelectionPath().getLastPathComponent()).request.id).isEqualTo("req-legacy");
    }

    @Test
    void sameCollectionSameIdentityRequestsDoNotCollideInSavedRequestTreePaths() throws Exception {
        ImporterPanel panel = newPanel();

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        ApiRequest firstRequest = request("req-shared", "Get Token", "POST", "https://auth.example.test/token", 0);
        ApiRequest secondRequest = request("req-shared", "Get Token", "POST", "https://auth.example.test/token", 0);
        collection.requests.add(firstRequest);
        collection.requests.add(secondRequest);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(
                ImporterPanel.workspaceRequestTreePathKey("APIM", 0, state.collections.get(0).requests.get(0), 0),
                "Auth/OAuth"
        );
        state.requestTreePaths.put(
                ImporterPanel.workspaceRequestTreePathKey("APIM", 0, state.collections.get(0).requests.get(1), 1),
                "Billing/Soap"
        );

        panel.restoreWorkspaceState(state);

        JTree tree = requestTree(panel);
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        CollectionTreeNode apimNode = (CollectionTreeNode) root.getChildAt(0);

        assertThat(root.getChildCount()).isEqualTo(1);
        assertThat(childFolderNames(apimNode)).containsExactly("Auth", "Billing");
        assertThat(requestNames(childFolder(childFolder(apimNode, "Auth"), "OAuth"))).containsExactly("Get Token");
        assertThat(requestNames(childFolder(childFolder(apimNode, "Billing"), "Soap"))).containsExactly("Get Token");
        assertThat(directRequestNames(apimNode)).isEmpty();

        WorkspaceState snapshot = panel.getWorkspaceStateSnapshot();
        assertThat(snapshot.requestTreePaths).hasSize(2);
        assertThat(snapshot.requestTreePaths.keySet()).anyMatch(key -> key.contains("collectionIndex=0") && key.contains("requestIndex=0"));
        assertThat(snapshot.requestTreePaths.keySet()).anyMatch(key -> key.contains("collectionIndex=0") && key.contains("requestIndex=1"));
        assertThat(snapshot.requestTreePaths).containsEntry(
                ImporterPanel.workspaceRequestTreePathKey("APIM", 0, snapshot.collections.get(0).requests.get(0), 0),
                "Auth/OAuth"
        );
        assertThat(snapshot.requestTreePaths).containsEntry(
                ImporterPanel.workspaceRequestTreePathKey("APIM", 0, snapshot.collections.get(0).requests.get(1), 1),
                "Billing/Soap"
        );
    }

    @Test
    void legacyDuplicateWorkspaceTreePathsResolveBaseAndDuplicateOrdinalsDeterministically() throws Exception {
        ImporterPanel panel = newPanel();

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        ApiRequest firstRequest = request("req-shared", "Get Token", "POST", "https://auth.example.test/token", 0);
        ApiRequest secondRequest = request("req-shared", "Get Token", "POST", "https://auth.example.test/token", 0);
        collection.requests.add(firstRequest);
        collection.requests.add(secondRequest);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        String legacyBaseKey = ImporterPanel.workspaceRequestKey("APIM", state.collections.get(0).requests.get(0));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(legacyBaseKey + "\u001Fduplicate=2", "Billing/Soap");
        state.requestTreePaths.put(legacyBaseKey, "Auth/OAuth");

        panel.restoreWorkspaceState(state);

        JTree tree = requestTree(panel);
        CollectionTreeNode apimNode = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);
        CollectionTreeNode authNode = childFolder(apimNode, "Auth");
        CollectionTreeNode billingNode = childFolder(apimNode, "Billing");

        assertThat(state.collections.get(0).requests).extracting(req -> req.path)
                .containsExactly("Get Token", "Get Token");
        assertThat(directRequestNames(apimNode)).isEmpty();
        assertThat(requestNames(childFolder(authNode, "OAuth"))).containsExactly("Get Token");
        assertThat(requestNames(childFolder(billingNode, "Soap"))).containsExactly("Get Token");

        WorkspaceState reopened = panel.getWorkspaceStateSnapshot();
        assertThat(reopened.requestTreePaths).containsEntry(
                ImporterPanel.workspaceRequestTreePathKey("APIM", 0, reopened.collections.get(0).requests.get(0), 0),
                "Auth/OAuth"
        );
        assertThat(reopened.requestTreePaths).containsEntry(
                ImporterPanel.workspaceRequestTreePathKey("APIM", 0, reopened.collections.get(0).requests.get(1), 1),
                "Billing/Soap"
        );
    }

    @Test
    void restoreWorkspaceStateRoundTripKeepsNestedTreeWithoutMutatingRequestPath() throws Exception {
        ImporterPanel panel = newPanel();

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        ApiRequest request = request("req-round-trip", "Get Token", "POST", "https://auth.example.test/token", 0);
        request.path = "Get Token";
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(
                ImporterPanel.workspaceRequestTreePathKey("APIM", 0, state.collections.get(0).requests.get(0), 0),
                "Auth/OAuth"
        );

        panel.restoreWorkspaceState(state);

        assertThat(state.collections.get(0).requests.get(0).path).isEqualTo("Get Token");

        JTree tree = requestTree(panel);
        CollectionTreeNode apimNode = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);
        CollectionTreeNode authNode = childFolder(apimNode, "Auth");
        CollectionTreeNode oauthNode = childFolder(authNode, "OAuth");

        assertThat(directRequestNames(apimNode)).isEmpty();
        assertThat(requestNames(oauthNode)).containsExactly("Get Token");

        WorkspaceState reopened = panel.getWorkspaceStateSnapshot();
        assertThat(reopened.collections.get(0).requests.get(0).path).isEqualTo("Get Token");
        assertThat(reopened.requestTreePaths).containsEntry(
                ImporterPanel.workspaceRequestTreePathKey("APIM", 0, reopened.collections.get(0).requests.get(0), 0),
                "Auth/OAuth"
        );

        ImporterPanel reopenedPanel = newPanel();
        reopenedPanel.restoreWorkspaceState(reopened);
        JTree reopenedTree = requestTree(reopenedPanel);
        CollectionTreeNode reopenedApimNode = (CollectionTreeNode) ((DefaultMutableTreeNode) reopenedTree.getModel().getRoot()).getChildAt(0);
        assertThat(requestNames(childFolder(childFolder(reopenedApimNode, "Auth"), "OAuth"))).containsExactly("Get Token");
    }

    @Test
    void restoreWorkspaceStateAppliesSavedExpandedTreePaths() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest admin = request("req-admin", "Admin", "GET", "https://api.example.test/admin", 0);
        admin.path = "Admin/Admin";
        ApiRequest publicReq = request("req-public", "Public", "GET", "https://api.example.test/public", 1);
        publicReq.path = "Public/Public";
        collection.requests.add(admin);
        collection.requests.add(publicReq);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.expandedTreePathKeys = new ArrayList<>(List.of(
                "APIM\u001F",
                "APIM\u001FAdmin"
        ));

        panel.restoreWorkspaceState(state);

        JTree tree = requestTree(panel);
        TreePath apimPath = findPathByFolder(tree, "APIM", null);
        TreePath adminPath = findPathByFolder(tree, "APIM", "Admin");
        TreePath publicPath = findPathByFolder(tree, "APIM", "Public");

        assertThat(tree.isExpanded(apimPath)).isTrue();
        assertThat(tree.isExpanded(adminPath)).isTrue();
        assertThat(tree.isExpanded(publicPath)).isFalse();
    }

    @Test
    void restoreWorkspaceStateExpandsAllWhenNoExpandedTreePathsAreSaved() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest admin = request("req-admin", "Admin", "GET", "https://api.example.test/admin", 0);
        admin.path = "Admin/Admin";
        ApiRequest publicReq = request("req-public", "Public", "GET", "https://api.example.test/public", 1);
        publicReq.path = "Public/Public";
        collection.requests.add(admin);
        collection.requests.add(publicReq);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.expandedTreePathKeys = new ArrayList<>();

        panel.restoreWorkspaceState(state);

        JTree tree = requestTree(panel);
        TreePath apimPath = findPathByFolder(tree, "APIM", null);
        TreePath adminPath = findPathByFolder(tree, "APIM", "Admin");
        TreePath publicPath = findPathByFolder(tree, "APIM", "Public");

        assertThat(tree.isExpanded(apimPath)).isTrue();
        assertThat(tree.isExpanded(adminPath)).isTrue();
        assertThat(tree.isExpanded(publicPath)).isTrue();
    }

    @Test
    void snapshotAndRestoreWorkbenchRunnerSettingsAndOAuthAutoRefreshState() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.runtimeOAuth2.put("oauth2_token_url", "https://auth.example.test/token");
        collection.runtimeOAuth2.put("oauth2_client_id", "client-id");
        collection.runtimeOAuth2.put("oauth2_refresh_token", "refresh-token");
        panel.restoreWorkspaceCollections(List.of(collection));

        setCheckbox(panel, "repeaterBtn", false);
        setCheckbox(panel, "sitemapBtn", true);
        setCheckbox(panel, "intruderBtn", true);
        setSpinner(panel, "delaySpinner", 375);
        setCheckbox(panel, "debugRawRequestBox", true);
        setTabIndex(panel, "workbenchDetailTabs", 0);

        setSpinner(panel, "runnerDelaySpinner", 480);
        setSpinner(panel, "runnerRetriesSpinner", 4);
        setCheckbox(panel, "stopOnErrorBox", true);
        setCheckbox(panel, "stopOnAssertionFailureBox", true);
        setCheckbox(panel, "stopOnStatusAtLeast400Box", true);
        setCheckbox(panel, "stopOnMissingVariableBox", true);
        setSpinner(panel, "stopAfterFailuresSpinner", 6);
        setCheckbox(panel, "followRedirectsBox", false);
        setCheckbox(panel, "runnerDebugRawRequestBox", true);
        setTabIndex(panel, "runnerDetailTabs", 1);

        WorkspaceState snapshot = panel.getWorkspaceStateSnapshot();
        assertThat(snapshot.workbenchRepeaterSelected).isFalse();
        assertThat(snapshot.workbenchSitemapSelected).isTrue();
        assertThat(snapshot.workbenchIntruderSelected).isTrue();
        assertThat(snapshot.workbenchDelayMs).isEqualTo(375);
        assertThat(snapshot.workbenchDebugRawRequest).isTrue();
        assertThat(snapshot.workbenchDetailTabIndex).isEqualTo(0);
        assertThat(snapshot.runnerDelayMs).isEqualTo(480);
        assertThat(snapshot.runnerRetries).isEqualTo(4);
        assertThat(snapshot.runnerStopOnError).isTrue();
        assertThat(snapshot.runnerStopOnAssertionFailure).isTrue();
        assertThat(snapshot.runnerStopOnStatusAtLeast400).isTrue();
        assertThat(snapshot.runnerStopOnMissingVariable).isTrue();
        assertThat(snapshot.runnerStopAfterFailures).isEqualTo(6);
        assertThat(snapshot.runnerFollowRedirects).isFalse();
        assertThat(snapshot.runnerDebugRawRequest).isTrue();
        assertThat(snapshot.runnerDetailTabIndex).isEqualTo(1);

        WorkspaceState.OAuthAutoRefreshSnapshot autoRefresh = new WorkspaceState.OAuthAutoRefreshSnapshot();
        autoRefresh.enabled = Boolean.FALSE;
        autoRefresh.intervalSeconds = 90;
        autoRefresh.lastStatus = "Paused";
        snapshot.oauthAutoRefreshByCollection = new LinkedHashMap<>();
        snapshot.oauthAutoRefreshByCollection.put("APIM", autoRefresh);

        setCheckbox(panel, "repeaterBtn", true);
        setCheckbox(panel, "sitemapBtn", false);
        setCheckbox(panel, "intruderBtn", false);
        setSpinner(panel, "delaySpinner", 200);
        setCheckbox(panel, "debugRawRequestBox", false);
        setTabIndex(panel, "workbenchDetailTabs", 0);
        setSpinner(panel, "runnerDelaySpinner", 200);
        setSpinner(panel, "runnerRetriesSpinner", 1);
        setCheckbox(panel, "stopOnErrorBox", false);
        setCheckbox(panel, "stopOnAssertionFailureBox", false);
        setCheckbox(panel, "stopOnStatusAtLeast400Box", false);
        setCheckbox(panel, "stopOnMissingVariableBox", false);
        setSpinner(panel, "stopAfterFailuresSpinner", 0);
        setCheckbox(panel, "followRedirectsBox", true);
        setCheckbox(panel, "runnerDebugRawRequestBox", false);
        setTabIndex(panel, "runnerDetailTabs", 0);

        panel.restoreWorkspaceState(snapshot);

        assertThat(isCheckboxSelected(panel, "repeaterBtn")).isFalse();
        assertThat(isCheckboxSelected(panel, "sitemapBtn")).isTrue();
        assertThat(isCheckboxSelected(panel, "intruderBtn")).isTrue();
        assertThat(spinnerValue(panel, "delaySpinner")).isEqualTo(375);
        assertThat(isCheckboxSelected(panel, "debugRawRequestBox")).isTrue();
        assertThat(tabIndex(panel, "workbenchDetailTabs")).isEqualTo(0);
        assertThat(spinnerValue(panel, "runnerDelaySpinner")).isEqualTo(480);
        assertThat(spinnerValue(panel, "runnerRetriesSpinner")).isEqualTo(4);
        assertThat(isCheckboxSelected(panel, "stopOnErrorBox")).isTrue();
        assertThat(isCheckboxSelected(panel, "stopOnAssertionFailureBox")).isTrue();
        assertThat(isCheckboxSelected(panel, "stopOnStatusAtLeast400Box")).isTrue();
        assertThat(isCheckboxSelected(panel, "stopOnMissingVariableBox")).isTrue();
        assertThat(spinnerValue(panel, "stopAfterFailuresSpinner")).isEqualTo(6);
        assertThat(isCheckboxSelected(panel, "followRedirectsBox")).isFalse();
        assertThat(isCheckboxSelected(panel, "runnerDebugRawRequestBox")).isTrue();
        assertThat(tabIndex(panel, "runnerDetailTabs")).isEqualTo(1);

        Map<?, ?> autoStates = (Map<?, ?>) privateField(panel, "oauthAutoStates");
        Object restoredAutoState = autoStates.get(snapshot.collections.get(0));
        assertThat(restoredAutoState).isNotNull();
        assertThat((Boolean) privateField(restoredAutoState, "enabled")).isFalse();
        assertThat((Integer) privateField(restoredAutoState, "intervalSeconds")).isEqualTo(90);
    }

    @Test
    void restoreWorkspaceCollectionsLeavesPrimaryActionsReadyForNextSession() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.requests.add(request("req-ready", "Ready", "GET", "https://api.example.test/ready", 0));

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        panel.restoreWorkspaceState(state);

        assertThat(isButtonEnabled(panel, "importBtn")).isTrue();
        assertThat(isButtonEnabled(panel, "sendToRunnerBtn")).isTrue();
        assertThat(isButtonEnabled(panel, "removeCollectionBtn")).isTrue();
        assertThat(isButtonEnabled(panel, "envApplyAllBtn")).isFalse();
        assertThat(isButtonEnabled(panel, "startRunnerBtn")).isTrue();
        assertThat(isButtonEnabled(panel, "cancelRunnerBtn")).isFalse();
    }

    @Test
    void previewRunnerButtonIsRemoved() throws Exception {
        ImporterPanel panel = newPanel();
        assertThatThrownBy(() -> privateField(panel, "previewRunnerBtn"))
                .isInstanceOf(NoSuchFieldException.class);
    }

    @Test
    void selectingRequestInitializesEffectiveHeadersWithCollectionContext() throws Exception {
        ImporterPanel panel = newPanel();

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.environment.put("token", "resolved123");

        ApiRequest req = new ApiRequest();
        req.id = "req-auth";
        req.name = "AuthTest";
        req.method = "GET";
        req.url = "https://api.example.test/auth";
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "{{token}}");
        collection.requests.add(req);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        panel.restoreWorkspaceState(state);

        JTree tree = requestTree(panel);
        // Find and select the request node
        CollectionTreeNode requestNode = null;
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath path = tree.getPathForRow(i);
            Object node = path.getLastPathComponent();
            if (node instanceof CollectionTreeNode) {
                CollectionTreeNode ctn = (CollectionTreeNode) node;
                if (ctn.request != null && "req-auth".equals(ctn.request.id)) {
                    requestNode = ctn;
                    break;
                }
            }
        }
        assertThat(requestNode).isNotNull();

        // Select the request (triggers the tree selection listener)
        tree.setSelectionPath(new TreePath(requestNode.getPath()));
        SwingUtilities.invokeAndWait(() -> { });

        // Access the request editor and verify effective headers
        RequestEditorPanel editor = (RequestEditorPanel) privateField(panel, "requestEditor");
        Field headersModelField = RequestEditorPanel.class.getDeclaredField("headersModel");
        headersModelField.setAccessible(true);
        javax.swing.table.DefaultTableModel model = (javax.swing.table.DefaultTableModel) headersModelField.get(editor);

        boolean foundResolvedAuth = false;
        for (int i = 0; i < model.getRowCount(); i++) {
            String key = (String) model.getValueAt(i, 0);
            String value = (String) model.getValueAt(i, 1);
            if ("Authorization".equalsIgnoreCase(key) && "Bearer resolved123".equals(value)) {
                foundResolvedAuth = true;
                break;
            }
        }
        assertThat(foundResolvedAuth)
                .as("Authorization header should be resolved using collection environment on first selection")
                .isTrue();
    }

    @Test
    void envApplyCheckedCollectionsButtonDependsOnEnvSelectionCheckedRequestsAndLoadedCollections() throws Exception {
        ImporterPanel panel = newPanel();
        WorkspaceState state = WorkspaceState.fromCollections(List.of(collectionWithRequests("APIM", "req-a", "Alpha", "req-b", "Beta")));
        panel.restoreWorkspaceState(state);

        assertThat(isButtonEnabled(panel, "envApplyCheckedCollectionsBtn")).isFalse();
        assertThat(isButtonEnabled(panel, "envApplyAllBtn")).isFalse();

        setPrivateField(panel, "selectedEnv", tempEnvFile("baseUrl", "https://api.example.test", "token", "abc"));
        invokePrivateMethod(panel, "updateScopeControlState");
        assertThat(isButtonEnabled(panel, "envApplyCheckedCollectionsBtn")).isFalse();
        assertThat(isButtonEnabled(panel, "envApplyAllBtn")).isTrue();

        CollectionTreeNode alphaNode = findRequestNode(requestTree(panel), "req-a");
        alphaNode.setChecked(true);
        invokePrivateMethod(panel, "updateScopeControlState");
        assertThat(isButtonEnabled(panel, "envApplyCheckedCollectionsBtn")).isTrue();

        setPrivateField(panel, "selectedEnv", null);
        invokePrivateMethod(panel, "updateScopeControlState");
        assertThat(isButtonEnabled(panel, "envApplyCheckedCollectionsBtn")).isFalse();
        assertThat(isButtonEnabled(panel, "envApplyAllBtn")).isFalse();
    }

    @Test
    void applyEnvToCheckedCollectionsUpdatesOnlyOwningCollectionsAndRefreshesVariablesTab() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection alpha = collectionWithRequests("Alpha", "req-a1", "Alpha One", "req-a2", "Alpha Two");
        ApiCollection beta = collectionWithRequests("Beta", "req-b1", "Beta One", null, null);
        ApiCollection gamma = collectionWithRequests("Gamma", "req-c1", "Gamma One", null, null);
        alpha.runtimeVars.put("stale", "keep-alpha");
        beta.runtimeVars.put("stale", "keep-beta");
        gamma.runtimeVars.put("stale", "keep-gamma");

        WorkspaceState state = WorkspaceState.fromCollections(List.of(alpha, beta, gamma));
        panel.restoreWorkspaceState(state);
        ApiCollection restoredAlpha = state.collections.get(0);
        ApiCollection restoredBeta = state.collections.get(1);
        ApiCollection restoredGamma = state.collections.get(2);
        setPrivateField(panel, "selectedEnv", tempEnvFile("baseUrl", "https://api.example.test", "token", "abc"));

        JTree tree = requestTree(panel);
        findRequestNode(tree, "req-a1").setChecked(true);
        findRequestNode(tree, "req-a2").setChecked(true);
        findRequestNode(tree, "req-b1").setChecked(true);
        invokePrivateMethod(panel, "updateScopeControlState");

        ((JComboBox<?>) privateField(panel, "varsCollectionCombo")).setSelectedItem(((JComboBox<?>) privateField(panel, "varsCollectionCombo")).getItemAt(0));
        AtomicInteger notifications = new AtomicInteger();
        panel.setWorkspaceChangeListener(notifications::incrementAndGet);

        invokePrivateMethod(panel, "applyEnvToCheckedCollections");

        assertThat(restoredAlpha.runtimeVars).containsEntry("stale", "keep-alpha");
        assertThat(restoredAlpha.runtimeVars).containsEntry("baseUrl", "https://api.example.test");
        assertThat(restoredAlpha.runtimeVars).containsEntry("token", "abc");
        assertThat(restoredBeta.runtimeVars).containsEntry("stale", "keep-beta");
        assertThat(restoredBeta.runtimeVars).containsEntry("baseUrl", "https://api.example.test");
        assertThat(restoredBeta.runtimeVars).containsEntry("token", "abc");
        assertThat(restoredGamma.runtimeVars).containsEntry("stale", "keep-gamma");
        assertThat(restoredGamma.runtimeVars).doesNotContainKey("baseUrl");
        assertThat(restoredGamma.runtimeVars).doesNotContainKey("token");
        assertThat(notifications.get()).isGreaterThan(0);
        assertThat(importLogText(panel)).contains("Env bound to 2 collection(s): 4 var(s) total.");
        assertThat(envVarsText(panel)).contains("baseUrl=https://api.example.test");
        assertThat(envVarsText(panel)).contains("token=abc");
    }

    @Test
    void applyEnvToCheckedCollectionsNoCheckedRequestsDoesNotMutateRuntimeVarsAndLogsExpectedMessage() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collectionWithRequests("APIM", "req-a", "Alpha", null, null);
        collection.runtimeVars.put("stale", "keep");
        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        panel.restoreWorkspaceState(state);
        ApiCollection restored = state.collections.get(0);
        setPrivateField(panel, "selectedEnv", tempEnvFile("baseUrl", "https://api.example.test"));

        AtomicInteger notifications = new AtomicInteger();
        panel.setWorkspaceChangeListener(notifications::incrementAndGet);

        invokePrivateMethod(panel, "applyEnvToCheckedCollections");

        assertThat(restored.runtimeVars).containsEntry("stale", "keep");
        assertThat(restored.runtimeVars).doesNotContainKey("baseUrl");
        assertThat(notifications.get()).isZero();
        assertThat(importLogText(panel)).contains("No checked request nodes. Check one or more requests, folders, or collections to bind env.");
    }

    @Test
    void applyEnvToCheckedCollectionsWithoutSelectedEnvDoesNothing() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collectionWithRequests("APIM", "req-a", "Alpha", null, null);
        collection.runtimeVars.put("stale", "keep");
        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        panel.restoreWorkspaceState(state);
        ApiCollection restored = state.collections.get(0);
        findRequestNode(requestTree(panel), "req-a").setChecked(true);
        invokePrivateMethod(panel, "updateScopeControlState");

        invokePrivateMethod(panel, "applyEnvToCheckedCollections");

        assertThat(restored.runtimeVars).containsEntry("stale", "keep");
        assertThat(restored.runtimeVars).doesNotContainKey("baseUrl");
        assertThat(importLogText(panel)).contains("No environment file selected. Browse first.");
    }

    @Test
    void applyEnvToCheckedCollectionsWithCheckedRequestsButNoResolvedCollectionsDoesNothing() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collectionWithRequests("APIM", "req-a", "Alpha", null, null);
        collection.runtimeVars.put("stale", "keep");
        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        panel.restoreWorkspaceState(state);
        ApiCollection restored = state.collections.get(0);
        setPrivateField(panel, "selectedEnv", tempEnvFile("baseUrl", "https://api.example.test"));
        CollectionTreeNode requestNode = findRequestNode(requestTree(panel), "req-a");
        requestNode.setChecked(true);
        requestNode.request.sourceCollection = null;
        ((Map<?, ?>) privateField(panel, "requestToCollectionMap")).clear();
        invokePrivateMethod(panel, "updateScopeControlState");

        clickButton(panel, "envApplyCheckedCollectionsBtn");

        assertThat(restored.runtimeVars).containsEntry("stale", "keep");
        assertThat(restored.runtimeVars).doesNotContainKey("baseUrl");
        assertThat(importLogText(panel)).contains("No checked request nodes resolved to collections. Nothing to apply.");
    }

    private static ApiRequest request(String id, String name, String method, String url, int sequenceOrder) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.method = method;
        request.url = url;
        request.sequenceOrder = sequenceOrder;
        request.sourceCollection = "APIM";
        request.path = name;
        return request;
    }

    private static ImporterPanel newPanel() {
        burp.UniversalImporter importer = Mockito.mock(burp.UniversalImporter.class, Mockito.RETURNS_DEEP_STUBS);
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

    private static ApiCollection collectionWithRequests(String name,
                                                         String firstId,
                                                         String firstName,
                                                         String secondId,
                                                         String secondName) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        collection.requests.add(request(firstId, firstName, "GET", "https://api.example.test/" + firstName.toLowerCase().replace(' ', '-'), 0));
        if (secondId != null && secondName != null) {
            collection.requests.add(request(secondId, secondName, "GET", "https://api.example.test/" + secondName.toLowerCase().replace(' ', '-'), 1));
        }
        return collection;
    }

    private static File tempEnvFile(String... keyValues) throws Exception {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must contain an even number of entries");
        }
        Path path = Files.createTempFile(Path.of("target"), "env-", ".json").toAbsolutePath().normalize();
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"values\": [\n");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                json.append(",\n");
            }
            json.append("    {\"key\": \"").append(keyValues[i]).append("\", \"value\": \"").append(keyValues[i + 1]).append("\"}");
        }
        json.append("\n  ]\n}\n");
        Files.writeString(path, json.toString(), StandardCharsets.UTF_8);
        return path.toFile();
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invokePrivateMethod(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static void clickButton(ImporterPanel panel, String fieldName) throws Exception {
        ((JButton) privateField(panel, fieldName)).doClick();
    }

    private static String importLogText(ImporterPanel panel) throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        return ((JTextArea) privateField(panel, "importLog")).getText();
    }

    private static String envVarsText(ImporterPanel panel) throws Exception {
        return ((JTextArea) privateField(panel, "envVarsArea")).getText();
    }

    private static JTree requestTree(ImporterPanel panel) throws Exception {
        Field field = ImporterPanel.class.getDeclaredField("requestTree");
        field.setAccessible(true);
        return (JTree) field.get(panel);
    }

    private static Object privateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setCheckbox(ImporterPanel panel, String fieldName, boolean value) throws Exception {
        ((JCheckBox) privateField(panel, fieldName)).setSelected(value);
    }

    private static boolean isCheckboxSelected(ImporterPanel panel, String fieldName) throws Exception {
        return ((JCheckBox) privateField(panel, fieldName)).isSelected();
    }

    private static void setSpinner(ImporterPanel panel, String fieldName, int value) throws Exception {
        ((JSpinner) privateField(panel, fieldName)).setValue(value);
    }

    private static int spinnerValue(ImporterPanel panel, String fieldName) throws Exception {
        return ((Number) ((JSpinner) privateField(panel, fieldName)).getValue()).intValue();
    }

    private static void setTabIndex(ImporterPanel panel, String fieldName, int index) throws Exception {
        ((JTabbedPane) privateField(panel, fieldName)).setSelectedIndex(index);
    }

    private static int tabIndex(ImporterPanel panel, String fieldName) throws Exception {
        return ((JTabbedPane) privateField(panel, fieldName)).getSelectedIndex();
    }

    private static boolean isButtonEnabled(ImporterPanel panel, String fieldName) throws Exception {
        return ((JButton) privateField(panel, fieldName)).isEnabled();
    }

    private static CollectionTreeNode findRequestNode(JTree tree, String requestId) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        CollectionTreeNode found = findRequestNode(root, requestId);
        if (found == null) {
            throw new AssertionError("Missing request node: " + requestId);
        }
        return found;
    }

    private static CollectionTreeNode findRequestNode(DefaultMutableTreeNode node, String requestId) {
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST && ctn.request != null && requestId.equals(ctn.request.id)) {
                return ctn;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            CollectionTreeNode found = findRequestNode((DefaultMutableTreeNode) node.getChildAt(i), requestId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static TreePath findPathByFolder(JTree tree, String collectionName, String folderName) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        TreePath path = findPathByFolder(root, collectionName, folderName, null);
        if (path == null) {
            throw new AssertionError("Missing folder path: " + collectionName + "/" + folderName);
        }
        return path;
    }

    private static TreePath findPathByFolder(DefaultMutableTreeNode node,
                                             String collectionName,
                                             String folderName,
                                             String currentCollectionName) {
        String nextCollectionName = currentCollectionName;
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                nextCollectionName = ctn.collection != null ? ctn.collection.name : currentCollectionName;
                if (folderName == null && collectionName.equals(nextCollectionName)) {
                    return new TreePath(ctn.getPath());
                }
            } else if (ctn.getNodeType() == CollectionTreeNode.Type.FOLDER
                    && collectionName.equals(nextCollectionName)
                    && folderName != null
                    && folderName.equals(ctn.folderPath.substring(ctn.folderPath.lastIndexOf('/') + 1))) {
                return new TreePath(ctn.getPath());
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TreePath path = findPathByFolder((DefaultMutableTreeNode) node.getChildAt(i), collectionName, folderName, nextCollectionName);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private static ApiRequest flatRequest(String name) {
        ApiRequest request = new ApiRequest();
        request.name = name;
        request.method = "GET";
        request.url = "https://api.example.test/shared";
        request.sequenceOrder = 0;
        request.sourceCollection = "APIM";
        request.path = name;
        return request;
    }

    private static CollectionTreeNode childFolder(CollectionTreeNode parent, String folderName) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Object child = parent.getChildAt(i);
            if (child instanceof CollectionTreeNode) {
                CollectionTreeNode node = (CollectionTreeNode) child;
                if (node.getNodeType() == CollectionTreeNode.Type.FOLDER && folderName.equals(node.getUserObject())) {
                    return node;
                }
            }
        }
        throw new AssertionError("Missing folder node: " + folderName);
    }

    private static List<String> childFolderNames(CollectionTreeNode parent) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            Object child = parent.getChildAt(i);
            if (child instanceof CollectionTreeNode) {
                CollectionTreeNode node = (CollectionTreeNode) child;
                if (node.getNodeType() == CollectionTreeNode.Type.FOLDER) {
                    names.add(String.valueOf(node.getUserObject()));
                }
            }
        }
        return names;
    }

    private static List<String> requestNames(CollectionTreeNode parent) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            Object child = parent.getChildAt(i);
            if (child instanceof CollectionTreeNode) {
                CollectionTreeNode node = (CollectionTreeNode) child;
                if (node.getNodeType() == CollectionTreeNode.Type.REQUEST && node.request != null) {
                    names.add(node.request.name);
                }
            }
        }
        return names;
    }

    private static List<String> directRequestNames(CollectionTreeNode parent) {
        return requestNames(parent);
    }

    @Test
    void mainRequestTreeUsesBurpLikeRendererInNonCheckboxMode() throws Exception {
        ImporterPanel panel = newPanel();
        JTree tree = requestTree(panel);
        assertThat(tree.getCellRenderer()).isInstanceOf(BurpLikeTreeCellRenderer.class);
        assertThat(((BurpLikeTreeCellRenderer) tree.getCellRenderer()).isCheckboxMode()).isFalse();
    }

    @Test
    void popupSelectionTreeUsesBurpLikeRendererInCheckboxMode() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collectionWithRequests("APIM", "req-a", "Alpha", null, null);
        panel.restoreWorkspaceCollections(List.of(collection));

        Method buildTree = ImporterPanel.class.getDeclaredMethod("buildPopupSelectionTree", DefaultMutableTreeNode.class, JLabel.class);
        buildTree.setAccessible(true);

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        JTree popupTree = (JTree) buildTree.invoke(panel, root, new JLabel());

        assertThat(popupTree.getCellRenderer()).isInstanceOf(BurpLikeTreeCellRenderer.class);
        assertThat(((BurpLikeTreeCellRenderer) popupTree.getCellRenderer()).isCheckboxMode()).isTrue();
    }

    @Test
    void restoredNestedTreeUsesNativeTreeGeometryWithoutRendererOwnedIndentation() throws Exception {
        ImporterPanel panel = newPanel();

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        ApiRequest request = new ApiRequest();
        request.name = "Get Token";
        request.method = "POST";
        request.url = "https://auth.example.test/token";
        request.path = "Auth/OAuth/Get Token";
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(
                ImporterPanel.workspaceRequestTreePathKey("APIM", 0, state.collections.get(0).requests.get(0), 0),
                "Auth/OAuth"
        );

        panel.restoreWorkspaceState(state);
        JTree tree = requestTree(panel);

        CollectionTreeNode apimNode = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);
        CollectionTreeNode authNode = childFolder(apimNode, "Auth");
        CollectionTreeNode oauthNode = childFolder(authNode, "OAuth");
        CollectionTreeNode requestNode = (CollectionTreeNode) oauthNode.getChildAt(0);

        assertThat(rowXOf(tree, apimNode)).isLessThan(rowXOf(tree, authNode));
        assertThat(rowXOf(tree, authNode)).isLessThan(rowXOf(tree, oauthNode));
        assertThat(rowXOf(tree, oauthNode)).isLessThan(rowXOf(tree, requestNode));

        TreeCellRenderer renderer = tree.getCellRenderer();

        int insetCollection = leftInsetOf(renderer.getTreeCellRendererComponent(tree, apimNode, false, false, false, 0, false));
        int insetFolder = leftInsetOf(renderer.getTreeCellRendererComponent(tree, authNode, false, false, false, 1, false));
        int insetSubfolder = leftInsetOf(renderer.getTreeCellRendererComponent(tree, oauthNode, false, false, false, 2, false));
        int insetRequest = leftInsetOf(renderer.getTreeCellRendererComponent(tree, requestNode, false, false, true, 3, false));

        assertThat(insetFolder).isEqualTo(insetCollection);
        assertThat(insetSubfolder).isEqualTo(insetFolder);
        assertThat(insetRequest).isEqualTo(insetSubfolder);

        assertThat(hasGuideCue(renderer.getTreeCellRendererComponent(tree, apimNode, false, false, false, 0, false))).isFalse();
        assertThat(hasGuideCue(renderer.getTreeCellRendererComponent(tree, authNode, false, false, false, 1, false))).isFalse();
        assertThat(hasGuideCue(renderer.getTreeCellRendererComponent(tree, oauthNode, false, false, false, 2, false))).isFalse();
        assertThat(hasGuideCue(renderer.getTreeCellRendererComponent(tree, requestNode, false, false, true, 3, false))).isFalse();
    }

    @Test
    void restoreStabilizationKeepsHierarchyGeometryWhenUiDefaultsChange() throws Exception {
        Object originalLeftIndent = UIManager.get("Tree.leftChildIndent");
        Object originalRightIndent = UIManager.get("Tree.rightChildIndent");
        try {
            UIManager.put("Tree.leftChildIndent", 0);
            UIManager.put("Tree.rightChildIndent", 0);

            ImporterPanel panel = newPanel();

            ApiCollection collection = new ApiCollection();
            collection.name = "APIM";
            ApiRequest request = request("req-flat-ui", "Get Token", "POST", "https://auth.example.test/token", 0);
            request.path = "Auth/OAuth/Get Token";
            collection.requests.add(request);

            WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
            state.requestTreePaths = new LinkedHashMap<>();
            state.requestTreePaths.put(
                    ImporterPanel.workspaceRequestTreePathKey("APIM", 0, state.collections.get(0).requests.get(0), 0),
                    "Auth/OAuth"
            );

            panel.restoreWorkspaceState(state);
            JTree tree = requestTree(panel);

            CollectionTreeNode apimNode = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);
            CollectionTreeNode authNode = childFolder(apimNode, "Auth");
            CollectionTreeNode oauthNode = childFolder(authNode, "OAuth");
            CollectionTreeNode requestNode = (CollectionTreeNode) oauthNode.getChildAt(0);

            assertThat(rowXOf(tree, apimNode)).isLessThan(rowXOf(tree, authNode));
            assertThat(rowXOf(tree, authNode)).isLessThan(rowXOf(tree, oauthNode));
            assertThat(rowXOf(tree, oauthNode)).isLessThan(rowXOf(tree, requestNode));

            UIManager.put("Tree.leftChildIndent", 7);
            UIManager.put("Tree.rightChildIndent", 13);

            Method stabilizeRestoredRequestTreePresentation = ImporterPanel.class.getDeclaredMethod(
                    "stabilizeRestoredRequestTreePresentation",
                    WorkspaceState.class
            );
            stabilizeRestoredRequestTreePresentation.setAccessible(true);
            stabilizeRestoredRequestTreePresentation.invoke(panel, state);

            assertThat(rowXOf(tree, apimNode)).isLessThan(rowXOf(tree, authNode));
            assertThat(rowXOf(tree, authNode)).isLessThan(rowXOf(tree, oauthNode));
            assertThat(rowXOf(tree, oauthNode)).isLessThan(rowXOf(tree, requestNode));
        } finally {
            restoreUiManagerValue("Tree.leftChildIndent", originalLeftIndent);
            restoreUiManagerValue("Tree.rightChildIndent", originalRightIndent);
        }
    }

    @Test
    void restoreStabilizationForcesMainTreeChildIndentWhenUiDefaultsRemainFlat() throws Exception {
        Object originalLeftIndent = UIManager.get("Tree.leftChildIndent");
        Object originalRightIndent = UIManager.get("Tree.rightChildIndent");
        try {
            UIManager.put("Tree.leftChildIndent", 0);
            UIManager.put("Tree.rightChildIndent", 0);

            ImporterPanel panel = newPanel();

            ApiCollection collection = new ApiCollection();
            collection.name = "APIM";
            ApiRequest request = request("req-force-indent", "Get Token", "POST", "https://auth.example.test/token", 0);
            request.path = "Auth/OAuth/Get Token";
            collection.requests.add(request);

            WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
            state.requestTreePaths = new LinkedHashMap<>();
            state.requestTreePaths.put(
                    ImporterPanel.workspaceRequestTreePathKey("APIM", 0, state.collections.get(0).requests.get(0), 0),
                    "Auth/OAuth"
            );

            panel.restoreWorkspaceState(state);
            JTree tree = requestTree(panel);
            assertThat(tree.getUI()).isInstanceOf(BasicTreeUI.class);
            BasicTreeUI treeUi = (BasicTreeUI) tree.getUI();
            assertThat(treeUi.getLeftChildIndent()).isGreaterThan(0);
            assertThat(treeUi.getRightChildIndent()).isGreaterThan(0);
        } finally {
            restoreUiManagerValue("Tree.leftChildIndent", originalLeftIndent);
            restoreUiManagerValue("Tree.rightChildIndent", originalRightIndent);
        }
    }

    @Test
    void restoreStabilizationRecreatesMainTreeWhenGeometryRemainsFlat() throws Exception {
        ImporterPanel panel = newPanel();

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        ApiRequest request = request("req-recreate-flat", "Get Token", "POST", "https://auth.example.test/token", 0);
        request.path = "Auth/OAuth/Get Token";
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(
                ImporterPanel.workspaceRequestTreePathKey("APIM", 0, state.collections.get(0).requests.get(0), 0),
                "Auth/OAuth"
        );

        panel.restoreWorkspaceState(state);

        JTree existingTree = requestTree(panel);
        FlatGeometryTree flatTree = new FlatGeometryTree(existingTree.getModel());
        flatTree.setRootVisible(existingTree.isRootVisible());
        flatTree.setShowsRootHandles(existingTree.getShowsRootHandles());
        flatTree.setCellRenderer(existingTree.getCellRenderer());
        flatTree.setRowHeight(existingTree.getRowHeight());

        JScrollPane scrollPane = (JScrollPane) privateField(panel, "requestTreeScrollPane");
        scrollPane.setViewportView(flatTree);
        setPrivateField(panel, "requestTree", flatTree);

        Method stabilizeRestoredRequestTreePresentation = ImporterPanel.class.getDeclaredMethod(
                "stabilizeRestoredRequestTreePresentation",
                WorkspaceState.class
        );
        stabilizeRestoredRequestTreePresentation.setAccessible(true);
        stabilizeRestoredRequestTreePresentation.invoke(panel, state);

        JTree recreatedTree = requestTree(panel);

        assertThat(recreatedTree).isNotSameAs(flatTree);

        CollectionTreeNode apimNode = (CollectionTreeNode) ((DefaultMutableTreeNode) recreatedTree.getModel().getRoot()).getChildAt(0);
        CollectionTreeNode authNode = childFolder(apimNode, "Auth");
        CollectionTreeNode oauthNode = childFolder(authNode, "OAuth");
        CollectionTreeNode requestNode = (CollectionTreeNode) oauthNode.getChildAt(0);

        assertThat(rowXOf(recreatedTree, apimNode)).isLessThan(rowXOf(recreatedTree, authNode));
        assertThat(rowXOf(recreatedTree, authNode)).isLessThan(rowXOf(recreatedTree, oauthNode));
        assertThat(rowXOf(recreatedTree, oauthNode)).isLessThan(rowXOf(recreatedTree, requestNode));
    }

    @Test
    void restoreWorkspaceStateResetsMainTreeHorizontalViewportAfterSelectionRestore() throws Exception {
        ImporterPanel panel = newPanel();

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        ApiRequest request = request("req-scroll-reset", "Get Token", "POST", "https://auth.example.test/token", 0);
        request.path = "Auth/OAuth/Deep/Nested/Get Token";
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(
                ImporterPanel.workspaceRequestTreePathKey("APIM", 0, state.collections.get(0).requests.get(0), 0),
                "Auth/OAuth/Deep/Nested"
        );
        state.selectedRequestCollectionName = "APIM";
        state.selectedRequestIdentityKey = ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(0), 0);
        state.selectedRequestPath = request.path;
        state.selectedRequestName = request.name;

        JTree existingTree = requestTree(panel);
        HorizontalShiftOnScrollTree shiftedTree = new HorizontalShiftOnScrollTree(existingTree.getModel());
        shiftedTree.setRootVisible(existingTree.isRootVisible());
        shiftedTree.setShowsRootHandles(existingTree.getShowsRootHandles());
        shiftedTree.setCellRenderer(existingTree.getCellRenderer());
        shiftedTree.setRowHeight(existingTree.getRowHeight());

        JScrollPane scrollPane = (JScrollPane) privateField(panel, "requestTreeScrollPane");
        scrollPane.setViewportView(shiftedTree);
        setPrivateField(panel, "requestTree", shiftedTree);

        panel.restoreWorkspaceState(state);

        assertThat(scrollPane.getViewport().getViewPosition().x).isZero();
    }

    @Test
    void restoreWorkspaceStateResetsMainTreeHorizontalViewportAfterDeferredSelectionScroll() throws Exception {
        ImporterPanel panel = newPanel();

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        ApiRequest request = request("req-scroll-deferred", "Get Token", "POST", "https://auth.example.test/token", 0);
        request.path = "Auth/OAuth/Deep/Nested/Get Token";
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(
                ImporterPanel.workspaceRequestTreePathKey("APIM", 0, state.collections.get(0).requests.get(0), 0),
                "Auth/OAuth/Deep/Nested"
        );
        state.selectedRequestCollectionName = "APIM";
        state.selectedRequestIdentityKey = ImporterPanel.workspaceRequestIdentityKey("APIM", state.collections.get(0).requests.get(0), 0);
        state.selectedRequestPath = request.path;
        state.selectedRequestName = request.name;

        JTree existingTree = requestTree(panel);
        DeferredHorizontalShiftOnScrollTree shiftedTree = new DeferredHorizontalShiftOnScrollTree(existingTree.getModel());
        shiftedTree.setRootVisible(existingTree.isRootVisible());
        shiftedTree.setShowsRootHandles(existingTree.getShowsRootHandles());
        shiftedTree.setCellRenderer(existingTree.getCellRenderer());
        shiftedTree.setRowHeight(existingTree.getRowHeight());

        JScrollPane scrollPane = (JScrollPane) privateField(panel, "requestTreeScrollPane");
        scrollPane.setViewportView(shiftedTree);
        setPrivateField(panel, "requestTree", shiftedTree);

        panel.restoreWorkspaceState(state);
        drainEdt();
        drainEdt();

        assertThat(scrollPane.getViewport().getViewPosition().x).isZero();
    }

    @Test
    void rebuildTreeRefreshesMainRequestTreePresentation() throws Exception {
        ImporterPanel panel = newPanel();
        SpyTree spyTree = installSpyRequestTree(panel);

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        ApiRequest request = request("req-refresh-main", "Get Token", "POST", "https://auth.example.test/token", 0);
        request.path = "Auth/OAuth/Get Token";
        collection.requests.add(request);

        @SuppressWarnings("unchecked")
        List<ApiCollection> loadedCollections = (List<ApiCollection>) privateField(panel, "loadedCollections");
        loadedCollections.clear();
        loadedCollections.add(collection);

        Method rebuildTree = ImporterPanel.class.getDeclaredMethod("rebuildTree", Map.class, List.class);
        rebuildTree.setAccessible(true);

        spyTree.resetRefreshCounters();
        rebuildTree.invoke(panel, Collections.emptyMap(), Collections.emptyList());

        assertThat(spyTree.revalidateCount).isGreaterThan(0);
        assertThat(spyTree.repaintCount).isGreaterThan(0);
    }

    @Test
    void buildPopupSelectionTreeRefreshesPresentationAfterExpansion() throws Exception {
        ImporterPanel panel = newPanel();

        Method refreshTreePresentation = ImporterPanel.class.getDeclaredMethod("refreshTreePresentation", JTree.class);
        refreshTreePresentation.setAccessible(true);

        SpyTree spyTree = new SpyTree(new DefaultTreeModel(new DefaultMutableTreeNode("root")));
        spyTree.resetRefreshCounters();

        refreshTreePresentation.invoke(panel, spyTree);

        assertThat(spyTree.treeDidChangeCount).isGreaterThan(0);
        assertThat(spyTree.revalidateCount).isGreaterThan(0);
        assertThat(spyTree.repaintCount).isGreaterThan(0);
    }

    @Test
    void restoreWorkspaceStateReinitializesRequestTreeWhenTreeBecomesShowing() throws Exception {
        ImporterPanel panel = newPanel();
        SpyTree spyTree = installSpyRequestTree(panel);

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        ApiRequest request = request("req-showing-refresh", "Get Token", "POST", "https://auth.example.test/token", 0);
        request.path = "Auth/OAuth/Get Token";
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new LinkedHashMap<>();
        state.requestTreePaths.put(
                ImporterPanel.workspaceRequestTreePathKey("APIM", 0, state.collections.get(0).requests.get(0), 0),
                "Auth/OAuth"
        );

        panel.restoreWorkspaceState(state);

        int initialExpandRowCount = spyTree.expandRowCount;
        int initialTreeDidChangeCount = spyTree.treeDidChangeCount;

        spyTree.setShowingForTest(true);
        spyTree.fireShowingChanged();
        drainEdt();

        assertThat(spyTree.expandRowCount).isGreaterThan(initialExpandRowCount);
        assertThat(spyTree.treeDidChangeCount).isGreaterThan(initialTreeDidChangeCount);
    }

    @Test
    void scheduleTreeInitializationAfterShowingRunsInitializerOnceForHiddenTree() throws Exception {
        ImporterPanel panel = newPanel();

        Method scheduleTreeInitializationAfterShowing = ImporterPanel.class.getDeclaredMethod("scheduleTreeInitializationAfterShowing", JTree.class, Runnable.class);
        scheduleTreeInitializationAfterShowing.setAccessible(true);

        SpyTree spyTree = new SpyTree(new DefaultTreeModel(new DefaultMutableTreeNode("root")));
        AtomicInteger initializerCount = new AtomicInteger();

        scheduleTreeInitializationAfterShowing.invoke(panel, spyTree, (Runnable) initializerCount::incrementAndGet);

        assertThat(initializerCount.get()).isZero();

        spyTree.setShowingForTest(true);
        spyTree.fireShowingChanged();
        drainEdt();

        assertThat(initializerCount.get()).isEqualTo(1);

        spyTree.fireShowingChanged();
        drainEdt();

        assertThat(initializerCount.get()).isEqualTo(1);
    }

    @Test
    void scheduleTreeInitializationAfterShowingRunsInitializerOnNextEventCycleForShowingTree() throws Exception {
        ImporterPanel panel = newPanel();

        Method scheduleTreeInitializationAfterShowing = ImporterPanel.class.getDeclaredMethod("scheduleTreeInitializationAfterShowing", JTree.class, Runnable.class);
        scheduleTreeInitializationAfterShowing.setAccessible(true);

        SpyTree spyTree = new SpyTree(new DefaultTreeModel(new DefaultMutableTreeNode("root")));
        spyTree.setShowingForTest(true);
        AtomicInteger initializerCount = new AtomicInteger();

        SwingUtilities.invokeAndWait(() -> {
            try {
                scheduleTreeInitializationAfterShowing.invoke(panel, spyTree, (Runnable) initializerCount::incrementAndGet);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            assertThat(initializerCount.get()).isZero();
        });

        drainEdt();

        assertThat(initializerCount.get()).isEqualTo(1);
    }

    private static int leftInsetOf(Component c) {
        Border border = extractBorder(c);
        if (border == null) return 0;
        return border.getBorderInsets(c).left;
    }

    private static boolean hasGuideCue(Component c) {
        Border border = extractBorder(c);
        return border != null && containsMatteBorder(border);
    }

    private static Border extractBorder(Component c) {
        if (!(c instanceof JComponent)) return null;
        if (c instanceof JPanel) {
            for (Component child : ((JPanel) c).getComponents()) {
                if (child instanceof JLabel && ((JLabel) child).getBorder() != null) {
                    return ((JLabel) child).getBorder();
                }
            }
            return ((JComponent) c).getBorder();
        }
        return ((JComponent) c).getBorder();
    }

    private static boolean containsMatteBorder(Border border) {
        if (border instanceof MatteBorder) return true;
        if (border instanceof CompoundBorder) {
            CompoundBorder cb = (CompoundBorder) border;
            return containsMatteBorder(cb.getOutsideBorder()) || containsMatteBorder(cb.getInsideBorder());
        }
        return false;
    }

    private static int rowXOf(JTree tree, CollectionTreeNode node) {
        TreePath path = new TreePath(node.getPath());
        Rectangle bounds = tree.getPathBounds(path);
        assertThat(bounds).as("row bounds for %s", node).isNotNull();
        return bounds.x;
    }

    private static void restoreUiManagerValue(String key, Object value) {
        if (value == null) {
            UIManager.getDefaults().remove(key);
            return;
        }
        UIManager.put(key, value);
    }

    private static void drainEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static SpyTree installSpyRequestTree(ImporterPanel panel) throws Exception {
        JTree existing = requestTree(panel);
        SpyTree spyTree = new SpyTree(existing.getModel());
        spyTree.setRootVisible(existing.isRootVisible());
        spyTree.setShowsRootHandles(existing.getShowsRootHandles());
        spyTree.setCellRenderer(existing.getCellRenderer());
        spyTree.setRowHeight(existing.getRowHeight());
        spyTree.setSelectionModel(existing.getSelectionModel());

        Field requestTreeField = ImporterPanel.class.getDeclaredField("requestTree");
        requestTreeField.setAccessible(true);
        requestTreeField.set(panel, spyTree);
        return spyTree;
    }

    private static final class SpyTree extends JTree {
        private int repaintCount;
        private int revalidateCount;
        private int treeDidChangeCount;
        private int expandRowCount;
        private boolean showingForTest;

        private SpyTree(javax.swing.tree.TreeModel model) {
            super(model);
        }

        @Override
        public void repaint() {
            repaintCount++;
            super.repaint();
        }

        @Override
        public void repaint(long tm, int x, int y, int width, int height) {
            repaintCount++;
            super.repaint(tm, x, y, width, height);
        }

        @Override
        public void revalidate() {
            revalidateCount++;
            super.revalidate();
        }

        @Override
        public void treeDidChange() {
            treeDidChangeCount++;
            super.treeDidChange();
        }

        @Override
        public void expandRow(int row) {
            expandRowCount++;
            super.expandRow(row);
        }

        @Override
        public Graphics getGraphics() {
            return null;
        }

        @Override
        public boolean isShowing() {
            return showingForTest || super.isShowing();
        }

        private void setShowingForTest(boolean showingForTest) {
            this.showingForTest = showingForTest;
        }

        private void fireShowingChanged() {
            HierarchyEvent event = new HierarchyEvent(this, HierarchyEvent.HIERARCHY_CHANGED, this, this, HierarchyEvent.SHOWING_CHANGED);
            for (HierarchyListener listener : getHierarchyListeners()) {
                listener.hierarchyChanged(event);
            }
        }

        private void resetRefreshCounters() {
            repaintCount = 0;
            revalidateCount = 0;
            treeDidChangeCount = 0;
            expandRowCount = 0;
        }
    }

    private static final class FlatGeometryTree extends JTree {
        private FlatGeometryTree(javax.swing.tree.TreeModel model) {
            super(model);
        }

        @Override
        public Rectangle getPathBounds(TreePath path) {
            if (path == null) {
                return null;
            }
            return new Rectangle(0, 0, 120, 20);
        }
    }

    private static final class HorizontalShiftOnScrollTree extends JTree {
        private HorizontalShiftOnScrollTree(javax.swing.tree.TreeModel model) {
            super(model);
        }

        @Override
        public void scrollPathToVisible(TreePath path) {
            if (getParent() instanceof JViewport) {
                ((JViewport) getParent()).setViewPosition(new Point(72, 18));
            }
        }
    }

    private static final class DeferredHorizontalShiftOnScrollTree extends JTree {
        private DeferredHorizontalShiftOnScrollTree(javax.swing.tree.TreeModel model) {
            super(model);
        }

        @Override
        public void scrollPathToVisible(TreePath path) {
            if (getParent() instanceof JViewport) {
                JViewport viewport = (JViewport) getParent();
                SwingUtilities.invokeLater(() -> viewport.setViewPosition(new Point(72, 18)));
            }
        }
    }
}
