package burp.scripts;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;
import burp.utils.RequestBuilder;
import burp.utils.RuntimeResolverFactory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VariableScopeStoreResolutionTest {

    @Test
    void localSetThenUnsetRevealsOuterCollectionValue() throws Exception {
        ApiCollection collection = collection();
        collection.runtimeVars.put("token", "collection");
        ApiRequest request = request();

        VariableScopeStore store = new VariableScopeStore(collection, request, null);
        store.setLocal("token", "temp", null, null);
        store.unsetLocal("token", null, null);

        assertThat(store.get("token")).isEqualTo("collection");
        assertThat(store.effectiveVariablesSnapshot()).containsEntry("token", "collection");
    }

    @Test
    void requestSetThenUnsetRevealsAuthoredRequestValue() throws Exception {
        ApiCollection collection = collection();
        ApiRequest request = request();
        request.variables.add(variable("token", "authored"));

        VariableScopeStore store = new VariableScopeStore(collection, request, null);
        store.setRequest("token", "temp", null, null);
        store.unsetRequest("token", null, null);

        assertThat(store.get("token")).isEqualTo("authored");
        assertThat(store.effectiveVariablesSnapshot()).containsEntry("token", "authored");

        String raw = new String(new RequestBuilder(null).buildRequest(request, store.resolver()), StandardCharsets.UTF_8);
        assertThat(raw).contains("GET /authored HTTP/1.1");
        assertThat(raw).doesNotContain("temp");
    }

    @Test
    void environmentRuntimeUnsetRevealsPersistedEnvironmentValue() {
        ApiCollection collection = collection();
        ApiRequest request = request();
        EnvironmentProfile environment = environment();
        environment.variables.put("token", "persisted");
        environment.runtimeVariables.put("token", "runtime");

        VariableScopeStore store = new VariableScopeStore(collection, request, environment);
        store.unsetEnvironment("token", false, null, null);

        assertThat(store.get("token")).isEqualTo("persisted");
        assertThat(store.effectiveVariablesSnapshot()).containsEntry("token", "persisted");
    }

    @Test
    void childFolderRuntimeUnsetRevealsChildAuthoredOrParentValue() {
        ApiCollection childAuthoredOnlyCollection = collection();
        childAuthoredOnlyCollection.folderVars.put("Parent/Child", new LinkedHashMap<>(Map.of("token", "child-authored")));
        childAuthoredOnlyCollection.runtimeFolderVars.put("Parent/Child", new LinkedHashMap<>(Map.of("token", "child-runtime")));
        ApiRequest childAuthoredOnlyRequest = request();
        childAuthoredOnlyRequest.path = "Parent/Child/Request";

        VariableScopeStore childAuthoredOnlyStore = new VariableScopeStore(childAuthoredOnlyCollection, childAuthoredOnlyRequest, null);
        childAuthoredOnlyStore.unsetFolder("token", false, null, null);

        assertThat(childAuthoredOnlyStore.get("token")).isEqualTo("child-authored");

        ApiCollection parentOnlyCollection = collection();
        parentOnlyCollection.folderVars.put("Parent", new LinkedHashMap<>(Map.of("token", "parent-authored")));
        parentOnlyCollection.folderVars.put("Parent/Child", new LinkedHashMap<>(Map.of("token", "child-authored")));
        parentOnlyCollection.runtimeFolderVars.put("Parent", new LinkedHashMap<>(Map.of("token", "parent-runtime")));
        parentOnlyCollection.runtimeFolderVars.put("Parent/Child", new LinkedHashMap<>(Map.of("token", "child-runtime")));
        ApiRequest parentOnlyRequest = request();
        parentOnlyRequest.path = "Parent/Child/Request";

        VariableScopeStore parentOnlyStore = new VariableScopeStore(parentOnlyCollection, parentOnlyRequest, null);
        parentOnlyStore.unsetFolder("token", false, null, null);

        assertThat(parentOnlyStore.get("token")).isEqualTo("parent-runtime");
    }

    @Test
    void sameKeyAcrossAllScopesUsesRequiredPrecedence() {
        ApiCollection collection = collection();
        collection.environment.put("shared", "collection-default");
        collection.variables.add(variable("shared", "collection-authored"));
        collection.folderVars.put("Parent", new LinkedHashMap<>(Map.of("shared", "folder-authored")));
        collection.runtimeFolderVars.put("Parent", new LinkedHashMap<>(Map.of("shared", "folder-runtime")));
        collection.runtimeOAuth2.put("shared", "collection-oauth2");
        collection.runtimeVars.put("shared", "collection-runtime");

        EnvironmentProfile environment = environment();
        environment.variables.put("shared", "environment-persisted");
        environment.runtimeVariables.put("shared", "environment-runtime");

        ApiRequest request = request();
        request.path = "Parent/Child/Request";
        request.variables.add(variable("shared", "request-authored"));
        request.auth = new ApiRequest.Auth();
        request.auth.type = "oauth2";
        request.auth.properties.put("accessToken", "auth-mapped");

        VariableScopeStore store = new VariableScopeStore(collection, request, environment, Map.of("shared", "overlay"));
        store.setRequest("shared", "request-runtime", null, null);
        store.setLocal("shared", "local-runtime", null, null);
        store.setGlobal("shared", "global-runtime", null, null);

        assertThat(store.get("shared")).isEqualTo("global-runtime");
    }

    @Test
    void explicitRuntimeOverlayRetainsItsRequiredPrecedence() {
        ApiCollection collection = collection();
        collection.runtimeVars.put("token", "collection-runtime");
        EnvironmentProfile environment = environment();
        environment.variables.put("token", "environment-persisted");
        ApiRequest request = request();

        VariableScopeStore store = new VariableScopeStore(collection, request, environment, Map.of("token", "overlay"));

        assertThat(store.get("token")).isEqualTo("overlay");
    }

    @Test
    void authRuntimeMappingRemainsHighest() {
        ApiCollection collection = collection();
        collection.runtimeVars.put("oauth2_access_token", "collection-runtime");
        EnvironmentProfile environment = environment();
        environment.runtimeVariables.put("oauth2_access_token", "environment-runtime");

        ApiRequest request = request();
        request.variables.add(variable("oauth2_access_token", "request-authored"));
        request.auth = new ApiRequest.Auth();
        request.auth.type = "oauth2";
        request.auth.properties.put("accessToken", "mapped-token");

        VariableScopeStore store = new VariableScopeStore(collection, request, environment, Map.of("oauth2_access_token", "overlay"));
        store.setLocal("oauth2_access_token", "local-runtime", null, null);
        store.setGlobal("oauth2_access_token", "global-runtime", null, null);

        assertThat(store.get("oauth2_access_token")).isEqualTo("mapped-token");
    }

    @Test
    void effectiveVariablesSnapshotMatchesResolver() {
        ApiCollection collection = collection();
        collection.environment.put("shared", "collection-default");
        collection.variables.add(variable("shared", "collection-authored"));
        collection.runtimeVars.put("shared", "collection-runtime");
        EnvironmentProfile environment = environment();
        environment.variables.put("shared", "environment-persisted");
        ApiRequest request = request();
        request.variables.add(variable("shared", "request-authored"));
        VariableScopeStore store = new VariableScopeStore(collection, request, environment, Map.of("shared", "overlay"));
        store.setLocal("shared", "local-runtime", null, null);

        VariableResolver resolver = store.resolver();

        assertThat(store.effectiveVariablesSnapshot()).isEqualTo(resolver.getVariables());
        assertThat(resolver.resolve("{{shared}}")).isEqualTo("local-runtime");
    }

    private ApiCollection collection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        collection.requests = new ArrayList<>();
        collection.scriptBlocks = new ArrayList<>();
        return collection;
    }

    private ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.id = "req";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test/{{token}}";
        request.path = "Parent/Child/Request";
        return request;
    }

    private EnvironmentProfile environment() {
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Environment";
        return environment;
    }

    private ApiRequest.Variable variable(String key, String value) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        return variable;
    }
}
