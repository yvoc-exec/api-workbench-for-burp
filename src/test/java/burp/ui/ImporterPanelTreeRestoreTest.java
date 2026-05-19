package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspaceState;
import burp.ui.tree.CollectionTreeNode;
import org.junit.jupiter.api.Test;

import javax.swing.tree.DefaultMutableTreeNode;
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
