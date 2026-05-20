package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspaceState;
import burp.runner.CollectionRunner;
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
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

        DefaultMutableTreeNode root = ImporterPanel.buildRequestTreeRoot(state.collections, null);
        CollectionTreeNode apimNode = (CollectionTreeNode) root.getChildAt(0);
        CollectionTreeNode authNode = childFolder(apimNode, "Auth");
        CollectionTreeNode oauthNode = childFolder(authNode, "OAuth");

        assertThat(directRequestNames(apimNode)).isEmpty();
        assertThat(requestNames(oauthNode)).containsExactly("Get Token");
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

        DefaultMutableTreeNode root = ImporterPanel.buildRequestTreeRoot(state.collections, null);
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

        assertThat(checkedNode.isChecked()).isTrue();
        assertThat(selectedNode.request.path).isEqualTo("FolderA/Select Me");
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

}
