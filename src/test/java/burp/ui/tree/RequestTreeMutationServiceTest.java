package burp.ui.tree;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTreeMutationServiceTest {

    private final RequestTreeMutationService service = new RequestTreeMutationService();

    @Test
    void createBlankManualRequestUsesBlankGetDefaults() {
        ApiCollection collection = collection("APIM");
        collection.runtimeVars.put("baseUrl", "https://api.example.test");
        collection.runtimeOAuth2.put("oauth2_access_token", "access");

        ApiRequest created = service.createBlankManualRequest(collection, "Auth");

        assertThat(created).isNotNull();
        assertThat(created.name).isEqualTo("Untitled Request");
        assertThat(created.path).isEqualTo("Auth");
        assertThat(created.sourceCollection).isEqualTo("APIM");
        assertThat(created.method).isEqualTo("GET");
        assertThat(created.url).isEqualTo("");
        assertThat(created.headers).isEmpty();
        assertThat(created.body).isNull();
        assertThat(created.variables).isEmpty();
        assertThat(created.preRequestScripts).isEmpty();
        assertThat(created.postResponseScripts).isEmpty();
        assertThat(created.editorMaterialized).isTrue();
        assertThat(created.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(created.authOverrideMode).isEqualTo("inherit");
        assertThat(created.explicitAuth).isNull();
        assertThat(created.auth).isNull();
        assertThat(created.authInherited).isFalse();
        assertThat(created.authExplicitlyDisabled).isFalse();
        assertThat(created.authSource).isEqualTo("none");
        assertThat(collection.requests).contains(created);
        assertThat(collection.runtimeVars).containsEntry("baseUrl", "https://api.example.test");
        assertThat(collection.runtimeOAuth2).containsEntry("oauth2_access_token", "access");
    }

    @Test
    void duplicateCollectionCopiesPersistentStateAndSkipsRuntimeMaps() {
        ApiCollection source = collection("APIM");
        source.folderPaths.add("Auth");
        source.folderPaths.add("Auth/OAuth");
        source.variables.add(variable("env", "uat"));
        source.environment.put("baseUrl", "https://api.example.test");
        source.folderVars.put("Auth", new LinkedHashMap<>(java.util.Map.of("role", "admin")));
        source.folderAuthModes.put("Auth", "explicit");
        source.folderAuth.put("Auth", auth("bearer", "token", "{{collectionToken}}"));
        source.auth = auth("bearer", "token", "{{collectionToken}}");
        source.runtimeVars.put("baseUrl", "https://runtime.example.test");
        source.runtimeOAuth2.put("oauth2_access_token", "runtime-access");

        ApiRequest request = request("req-1", "Login", "Auth");
        request.method = "POST";
        request.url = "https://api.example.test/login";
        request.headers.add(new ApiRequest.Header("X-Test", "one"));
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"login\":true}";
        request.preRequestScripts.add(new ApiRequest.Script("js", "console.log('pre');"));
        request.postResponseScripts.add(new ApiRequest.Script("js", "console.log('post');"));
        request.authOverrideMode = "explicit";
        request.explicitAuth = auth("basic", "username", "demo");
        request.auth = request.explicitAuth;
        source.requests.add(request);

        List<ApiCollection> loaded = new ArrayList<>(List.of(source));
        ApiCollection duplicate = service.duplicateCollection(loaded, source);

        assertThat(loaded).containsExactly(source, duplicate);
        assertThat(duplicate.name).isEqualTo("APIM Copy");
        assertThat(duplicate.folderPaths).containsExactly("Auth", "Auth/OAuth");
        assertThat(duplicate.variables).hasSize(1);
        assertThat(duplicate.environment).containsEntry("baseUrl", "https://api.example.test");
        assertThat(duplicate.folderVars).containsKey("Auth");
        assertThat(duplicate.folderVars.get("Auth")).containsEntry("role", "admin");
        assertThat(duplicate.folderAuthModes).containsEntry("Auth", "explicit");
        assertThat(duplicate.folderAuth.get("Auth").type).isEqualTo("bearer");
        assertThat(duplicate.auth.type).isEqualTo("bearer");
        assertThat(duplicate.runtimeVars).isEmpty();
        assertThat(duplicate.runtimeOAuth2).isEmpty();
        assertThat(duplicate.requests).hasSize(1);

        ApiRequest copied = duplicate.requests.get(0);
        assertThat(copied.id).isNotEqualTo(request.id);
        assertThat(copied.name).isEqualTo("Login");
        assertThat(copied.path).isEqualTo("Auth");
        assertThat(copied.sourceCollection).isEqualTo("APIM Copy");
        assertThat(copied.method).isEqualTo("POST");
        assertThat(copied.url).isEqualTo("https://api.example.test/login");
        assertThat(copied.headers).hasSize(1);
        assertThat(copied.headers.get(0).key).isEqualTo("X-Test");
        assertThat(copied.body).isNotNull();
        assertThat(copied.body.raw).isEqualTo("{\"login\":true}");
        assertThat(copied.preRequestScripts).hasSize(1);
        assertThat(copied.postResponseScripts).hasSize(1);
        assertThat(copied.authOverrideMode).isEqualTo("explicit");
        assertThat(copied.explicitAuth.type).isEqualTo("basic");
    }

    @Test
    void duplicateFolderCopiesSubtreeWithIncrementalName() {
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        collection.folderPaths.add("Auth Copy");
        collection.folderPaths.add("Auth Copy 2");
        collection.folderPaths.add("Auth/OAuth");
        collection.folderAuthModes.put("Auth", "explicit");
        collection.folderAuth.put("Auth", auth("bearer", "token", "{{collectionToken}}"));
        collection.folderVars.put("Auth", new LinkedHashMap<>(java.util.Map.of("role", "admin")));

        ApiRequest request = request("req-1", "Login", "Auth/OAuth");
        request.method = "POST";
        request.url = "https://api.example.test/login";
        collection.requests.add(request);

        String copiedPath = service.duplicateFolder(collection, "Auth");

        assertThat(copiedPath).isEqualTo("Auth Copy 3");
        assertThat(collection.folderPaths).contains("Auth Copy 3", "Auth Copy 3/OAuth");
        assertThat(collection.folderAuthModes).containsEntry("Auth Copy 3", "explicit");
        assertThat(collection.folderAuth.get("Auth Copy 3").type).isEqualTo("bearer");
        assertThat(collection.folderVars).containsKey("Auth Copy 3");
        assertThat(collection.requests).hasSize(2);
        ApiRequest copied = collection.requests.get(1);
        assertThat(copied.id).isNotEqualTo(request.id);
        assertThat(copied.name).isEqualTo("Login");
        assertThat(copied.path).isEqualTo("Auth Copy 3/OAuth");
        assertThat(copied.sourceCollection).isEqualTo("APIM");
    }

    @Test
    void duplicateRequestCopiesFieldsWithIncrementalName() {
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");

        ApiRequest request = request("req-1", "Login", "Auth");
        request.method = "POST";
        request.url = "https://api.example.test/login";
        request.headers.add(new ApiRequest.Header("X-Test", "one"));
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"login\":true}";
        request.preRequestScripts.add(new ApiRequest.Script("js", "console.log('pre');"));
        request.postResponseScripts.add(new ApiRequest.Script("js", "console.log('post');"));
        collection.requests.add(request);

        ApiRequest copy1 = service.duplicateRequest(collection, request);
        ApiRequest copy2 = service.duplicateRequest(collection, request);
        ApiRequest copy3 = service.duplicateRequest(collection, request);

        assertThat(copy1.name).isEqualTo("Login Copy");
        assertThat(copy2.name).isEqualTo("Login Copy 2");
        assertThat(copy3.name).isEqualTo("Login Copy 3");
        assertThat(copy1.path).isEqualTo("Auth");
        assertThat(copy1.method).isEqualTo("POST");
        assertThat(copy1.url).isEqualTo("https://api.example.test/login");
        assertThat(copy1.headers).hasSize(1);
        assertThat(copy1.body).isNotNull();
        assertThat(copy1.body.raw).isEqualTo("{\"login\":true}");
        assertThat(copy1.preRequestScripts).hasSize(1);
        assertThat(copy1.postResponseScripts).hasSize(1);
        assertThat(copy1.id).isNotEqualTo(request.id);
        assertThat(collection.requests).extracting(req -> req.name)
                .contains("Login", "Login Copy", "Login Copy 2", "Login Copy 3");
    }

    @Test
    void duplicateRequestWithSlashNameKeepsParentFolderPathOnly() {
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");

        ApiRequest request = request("req-1", "GET /users", "Auth");
        request.method = "GET";
        request.url = "https://api.example.test/users";
        collection.requests.add(request);

        ApiRequest duplicate = service.duplicateRequest(collection, request);

        assertThat(duplicate.name).isEqualTo("GET /users Copy");
        assertThat(duplicate.path).isEqualTo("Auth");
        assertThat(duplicate.sourceCollection).isEqualTo("APIM");
        assertThat(collection.requests).extracting(req -> req.name)
                .contains("GET /users", "GET /users Copy");
    }

    @Test
    void duplicateRequestWithBackslashNameKeepsParentFolderPathOnly() {
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");

        ApiRequest request = request("req-1", "users\\{id}", "Auth");
        request.method = "GET";
        request.url = "https://api.example.test/users/123";
        collection.requests.add(request);

        ApiRequest duplicate = service.duplicateRequest(collection, request);

        assertThat(duplicate.name).isEqualTo("users\\{id} Copy");
        assertThat(duplicate.path).isEqualTo("Auth");
        assertThat(duplicate.sourceCollection).isEqualTo("APIM");
        assertThat(collection.requests).extracting(req -> req.name)
                .contains("users\\{id}", "users\\{id} Copy");
    }

    @Test
    void renameCollectionUpdatesRequestSourceCollection() {
        ApiCollection collection = collection("APIM");
        ApiRequest request = request("req-1", "Login", "Login");
        collection.requests.add(request);

        String renamed = service.renameCollection(collection, "APIM Renamed");

        assertThat(renamed).isEqualTo("APIM Renamed");
        assertThat(collection.name).isEqualTo("APIM Renamed");
        assertThat(request.sourceCollection).isEqualTo("APIM Renamed");
    }

    @Test
    void renameRequestWithSlashNameKeepsParentFolderPathOnly() {
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        ApiRequest request = request("req-1", "Other", "Auth");
        collection.requests.add(request);

        String renamed = service.renameRequest(collection, request, "GET /users");

        assertThat(renamed).isEqualTo("GET /users");
        assertThat(request.name).isEqualTo("GET /users");
        assertThat(request.path).isEqualTo("Auth");
        assertThat(request.sourceCollection).isEqualTo("APIM");
    }

    @Test
    void renameFolderRewritesChildRequestPathsAndFolderMetadata() {
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        collection.folderPaths.add("Auth/OAuth");
        collection.folderAuthModes.put("Auth", "explicit");
        collection.folderAuth.put("Auth", auth("bearer", "token", "{{collectionToken}}"));
        collection.folderVars.put("Auth", new LinkedHashMap<>(java.util.Map.of("role", "admin")));

        ApiRequest request = request("req-1", "Login", "Auth/OAuth");
        request.method = "POST";
        collection.requests.add(request);

        String renamed = service.renameFolder(collection, "Auth", "Security");

        assertThat(renamed).isEqualTo("Security");
        assertThat(collection.folderPaths).contains("Security", "Security/OAuth");
        assertThat(collection.folderPaths).doesNotContain("Auth", "Auth/OAuth");
        assertThat(collection.folderAuthModes).containsEntry("Security", "explicit");
        assertThat(collection.folderAuth).containsKey("Security");
        assertThat(collection.folderVars).containsKey("Security");
        assertThat(request.path).isEqualTo("Security/OAuth");
    }

    @Test
    void removeFolderSubtreeRemovesNestedRequestsAndMetadata() {
        ApiCollection collection = collection("APIM");
        collection.folderPaths.add("Auth");
        collection.folderPaths.add("Auth/OAuth");
        collection.folderPaths.add("Public");
        collection.folderAuthModes.put("Auth", "explicit");
        collection.folderAuth.put("Auth", auth("bearer", "token", "{{collectionToken}}"));
        collection.folderVars.put("Auth", new LinkedHashMap<>(java.util.Map.of("role", "admin")));

        ApiRequest removed = request("req-1", "Login", "Auth/OAuth");
        ApiRequest kept = request("req-2", "Ping", "Public");
        collection.requests.add(removed);
        collection.requests.add(kept);

        List<ApiRequest> removedRequests = service.removeFolderSubtree(collection, "Auth");

        assertThat(removedRequests).containsExactly(removed);
        assertThat(collection.folderPaths).containsExactly("Public");
        assertThat(collection.folderAuthModes).doesNotContainKey("Auth");
        assertThat(collection.folderAuth).doesNotContainKey("Auth");
        assertThat(collection.folderVars).doesNotContainKey("Auth");
        assertThat(collection.requests).containsExactly(kept);
    }

    @Test
    void removeRequestRemovesSingleRequest() {
        ApiCollection collection = collection("APIM");
        ApiRequest removed = request("req-1", "Login", "Login");
        ApiRequest kept = request("req-2", "Ping", "Ping");
        collection.requests.add(removed);
        collection.requests.add(kept);

        List<ApiRequest> removedRequests = service.removeRequest(collection, removed);

        assertThat(removedRequests).containsExactly(removed);
        assertThat(collection.requests).containsExactly(kept);
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

    private static ApiRequest.Auth auth(String type, String key, String value) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = type;
        auth.properties.put(key, value);
        return auth;
    }

    private static ApiRequest.Variable variable(String key, String value) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        return variable;
    }
}
