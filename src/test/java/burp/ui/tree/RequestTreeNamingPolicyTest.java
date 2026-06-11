package burp.ui.tree;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTreeNamingPolicyTest {

    @Test
    void newCollectionNamesIncrementPredictably() {
        List<ApiCollection> collections = new ArrayList<>(List.of(
                collection("Untitled Collection"),
                collection("Untitled Collection 2")
        ));

        assertThat(RequestTreeNamingPolicy.uniqueCollectionName(collections, "Untitled Collection"))
                .isEqualTo("Untitled Collection 3");
    }

    @Test
    void duplicateCollectionNamesIncrementPredictably() {
        List<ApiCollection> collections = new ArrayList<>(List.of(
                collection("APIM"),
                collection("APIM Copy"),
                collection("APIM Copy 2")
        ));

        assertThat(RequestTreeNamingPolicy.uniqueCollectionCopyName(collections, "APIM"))
                .isEqualTo("APIM Copy 3");
    }

    @Test
    void duplicateFolderNamesIncrementPredictably() {
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        collection.folderPaths.add("Auth Copy");
        collection.folderPaths.add("Auth Copy 2");

        assertThat(RequestTreeNamingPolicy.uniqueChildCopyName(collection, "", "Auth"))
                .isEqualTo("Auth Copy 3");
    }

    @Test
    void duplicateRequestNamesIncrementPredictably() {
        ApiCollection collection = collection("APIM");
        collection.requests.add(request("1", "Login", "Login"));
        collection.requests.add(request("2", "Login Copy", "Login Copy"));
        collection.requests.add(request("3", "Login Copy 2", "Login Copy 2"));

        assertThat(RequestTreeNamingPolicy.uniqueChildCopyName(collection, "", "Login"))
                .isEqualTo("Login Copy 3");
    }

    @Test
    void collectionRenameRejectsDuplicateCollectionName() {
        List<ApiCollection> collections = new ArrayList<>(List.of(collection("APIM"), collection("Admin")));
        RequestTreeNamingPolicy.RenameValidation validation =
                RequestTreeNamingPolicy.validateCollectionRename(collections, collections.get(1), "APIM");

        assertThat(validation.valid).isFalse();
        assertThat(validation.message).isEqualTo("A collection named 'APIM' already exists.");
    }

    @Test
    void folderRenameRejectsSiblingFolderCollision() {
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        collection.folderPaths.add("Users");

        RequestTreeNamingPolicy.RenameValidation validation =
                RequestTreeNamingPolicy.validateFolderRename(collection, "Users", "Auth");

        assertThat(validation.valid).isFalse();
        assertThat(validation.message).isEqualTo("A folder or request named 'Auth' already exists in this location.");
    }

    @Test
    void folderRenameRejectsSiblingRequestCollision() {
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        collection.requests.add(request("1", "Login", "Login"));

        RequestTreeNamingPolicy.RenameValidation validation =
                RequestTreeNamingPolicy.validateFolderRename(collection, "Auth", "Login");

        assertThat(validation.valid).isFalse();
        assertThat(validation.message).isEqualTo("A folder or request named 'Login' already exists in this location.");
    }

    @Test
    void folderRenameRejectsPathSeparators() {
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");

        RequestTreeNamingPolicy.RenameValidation slashValidation =
                RequestTreeNamingPolicy.validateFolderRename(collection, "Auth", "Auth/OAuth");
        RequestTreeNamingPolicy.RenameValidation backslashValidation =
                RequestTreeNamingPolicy.validateFolderRename(collection, "Auth", "Auth\\OAuth");

        assertThat(slashValidation.valid).isFalse();
        assertThat(backslashValidation.valid).isFalse();
        assertThat(slashValidation.message).isEqualTo("Folder names cannot contain '/' or '\\'. Create a nested folder instead.");
        assertThat(backslashValidation.message).isEqualTo("Folder names cannot contain '/' or '\\'. Create a nested folder instead.");
    }

    @Test
    void requestRenameRejectsSiblingRequestCollision() {
        ApiCollection collection = collection("APIM");
        collection.requests.add(request("1", "Login", "Login"));
        collection.requests.add(request("2", "Refresh", "Refresh"));

        RequestTreeNamingPolicy.RenameValidation validation =
                RequestTreeNamingPolicy.validateRequestRename(collection, collection.requests.get(1), "Login");

        assertThat(validation.valid).isFalse();
        assertThat(validation.message).isEqualTo("A request or folder named 'Login' already exists in this location.");
    }

    @Test
    void requestRenameRejectsSiblingFolderCollision() {
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        collection.requests.add(request("1", "Login", "Login"));

        RequestTreeNamingPolicy.RenameValidation validation =
                RequestTreeNamingPolicy.validateRequestRename(collection, collection.requests.get(0), "Auth");

        assertThat(validation.valid).isFalse();
        assertThat(validation.message).isEqualTo("A request or folder named 'Auth' already exists in this location.");
    }

    @Test
    void requestRenameAllowsParentFolderNameWhenRequestIsInsideThatFolder() {
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        collection.requests.add(request("1", "Login", "Auth"));

        RequestTreeNamingPolicy.RenameValidation validation =
                RequestTreeNamingPolicy.validateRequestRename(collection, collection.requests.get(0), "Auth");

        assertThat(validation.valid).isTrue();
        assertThat(validation.normalizedName).isEqualTo("Auth");
    }

    @Test
    void requestRenameAllowsSlashLabelWhenUnique() {
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        collection.requests.add(request("1", "Login", "Auth"));

        RequestTreeNamingPolicy.RenameValidation validation =
                RequestTreeNamingPolicy.validateRequestRename(collection, collection.requests.get(0), "GET /users");

        assertThat(validation.valid).isTrue();
        assertThat(validation.normalizedName).isEqualTo("GET /users");
    }

    @Test
    void sameRequestNameAllowedInDifferentFoldersAndCollections() {
        ApiCollection collectionA = collection("Collection A");
        collectionA.folderPaths.add("Auth");
        collectionA.folderPaths.add("Users");
        collectionA.requests.add(request("1", "Login", "Auth/Login"));
        collectionA.requests.add(request("2", "Refresh", "Users/Refresh"));

        ApiCollection collectionB = collection("Collection B");

        assertThat(RequestTreeNamingPolicy.uniqueChildName(collectionA, "Users", "Login")).isEqualTo("Login");
        assertThat(RequestTreeNamingPolicy.uniqueChildName(collectionB, "", "Login")).isEqualTo("Login");
    }

    @Test
    void sameFolderNameAllowedInDifferentCollectionsAndParents() {
        ApiCollection collectionA = collection("Collection A");
        collectionA.folderPaths.add("Admin/Auth");
        collectionA.folderPaths.add("Public/Other");

        ApiCollection collectionB = collection("Collection B");

        assertThat(RequestTreeNamingPolicy.uniqueChildName(collectionA, "Public", "Auth")).isEqualTo("Auth");
        assertThat(RequestTreeNamingPolicy.uniqueChildName(collectionB, "", "Auth")).isEqualTo("Auth");
    }

    private static ApiCollection collection(String name) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        return collection;
    }

    private static ApiRequest request(String id, String name, String path) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.path = path;
        request.sourceCollection = "APIM";
        return request;
    }
}
