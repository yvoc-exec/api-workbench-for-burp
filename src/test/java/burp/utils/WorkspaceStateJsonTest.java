package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspacePersistenceOptions;
import burp.models.WorkspaceState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceStateJsonTest {

    @Test
    void roundTripsLoadedCollectionsAndRuntimeVars() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        collection.format = "postman";
        collection.runtimeVars.put("baseUrl", "https://api.example.test");
        collection.runtimeOAuth2.put("oauth2_token_url", "https://auth.example.test/token");

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection), WorkspacePersistenceOptions.defaults());
        String json = WorkspaceStateJson.toJson(state);
        WorkspaceState parsed = WorkspaceStateJson.fromJson(json);

        assertThat(parsed.version).isEqualTo(1);
        assertThat(parsed.collections).hasSize(1);
        assertThat(parsed.collections.get(0).name).isEqualTo("Demo");
        assertThat(parsed.collections.get(0).runtimeVars).containsEntry("baseUrl", "https://api.example.test");
        assertThat(parsed.collections.get(0).runtimeOAuth2).containsEntry("oauth2_token_url", "https://auth.example.test/token");
    }

    @Test
    void defaultOptionsDoNotPersistSensitiveTokenOrSecretValues() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        collection.runtimeVars.put("api_key", "secret-key");
        collection.runtimeVars.put("baseUrl", "https://api.example.test");
        collection.runtimeOAuth2.put("oauth2_access_token", "access");
        collection.runtimeOAuth2.put("oauth2_refresh_token", "refresh");
        collection.runtimeOAuth2.put("oauth2_client_secret", "client-secret");
        collection.runtimeOAuth2.put("oauth2_client_id", "client-id");
        collection.runtimeOAuth2.put("oauth2_token_url", "https://auth.example.test/token");

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection), WorkspacePersistenceOptions.defaults());

        ApiCollection saved = state.collections.get(0);
        assertThat(saved.runtimeVars).containsEntry("baseUrl", "https://api.example.test");
        assertThat(saved.runtimeVars).doesNotContainKey("api_key");
        assertThat(saved.runtimeOAuth2).containsEntry("oauth2_client_id", "client-id");
        assertThat(saved.runtimeOAuth2).containsEntry("oauth2_token_url", "https://auth.example.test/token");
        assertThat(saved.runtimeOAuth2).doesNotContainKeys("oauth2_access_token", "oauth2_refresh_token", "oauth2_client_secret");
    }

    @Test
    void explicitOptionsCanPersistSensitiveRuntimeValues() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        collection.runtimeVars.put("api_key", "secret-key");
        collection.runtimeOAuth2.put("oauth2_access_token", "access");
        collection.runtimeOAuth2.put("oauth2_client_secret", "client-secret");

        WorkspacePersistenceOptions options = WorkspacePersistenceOptions.defaults();
        options.persistSensitiveRuntimeValues = true;
        options.persistOAuthTokens = true;
        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection), options);

        ApiCollection saved = state.collections.get(0);
        assertThat(saved.runtimeVars).containsEntry("api_key", "secret-key");
        assertThat(saved.runtimeOAuth2).containsEntry("oauth2_access_token", "access");
        assertThat(saved.runtimeOAuth2).containsEntry("oauth2_client_secret", "client-secret");
    }

    @Test
    void snapshotDeepCopiesRequestsAndNestedState() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";

        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Get Me";
        request.path = "Folder/Get Me";
        request.sourceCollection = "Demo";
        request.method = "POST";
        request.url = "https://api.example.test/me";
        request.description = "desc";
        request.disabled = true;
        request.sequenceOrder = 7;
        request.authInherited = true;
        request.authExplicitlyDisabled = false;
        request.authSource = "collection: Demo";

        request.auth = new ApiRequest.Auth();
        request.auth.type = "bearer";
        request.auth.properties.put("token", "{{accessToken}}");

        request.headers.add(new ApiRequest.Header("X-Test", "one", false));

        request.body = new ApiRequest.Body();
        request.body.mode = "urlencoded";
        request.body.raw = "ignored";
        request.body.contentType = "application/x-www-form-urlencoded";
        ApiRequest.Body.FormField formField = new ApiRequest.Body.FormField("grant_type", "client_credentials");
        formField.type = "text";
        formField.fileUpload = false;
        formField.filePath = "/tmp/file.txt";
        formField.disabled = false;
        request.body.urlencoded.add(formField);
        request.body.graphql = new ApiRequest.Body.GraphQL();
        request.body.graphql.query = "{ me }";
        request.body.graphql.variables = "{\"x\":1}";

        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = "baseUrl";
        variable.value = "https://api.example.test";
        variable.type = "string";
        variable.enabled = true;
        request.variables.add(variable);

        request.preRequestScripts.add(new ApiRequest.Script("js", "pm.collectionVariables.set('a', '1');"));
        request.postResponseScripts.add(new ApiRequest.Script("js", "pm.test('ok', () => {});"));

        collection.requests.add(request);
        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection), WorkspacePersistenceOptions.defaults());

        ApiRequest snapshot = state.collections.get(0).requests.get(0);
        ApiRequest snapshotHeaderRequest = snapshot;
        ApiRequest.Body.FormField snapshotFormField = snapshot.body.urlencoded.get(0);
        ApiRequest.Variable snapshotVariable = snapshot.variables.get(0);
        ApiRequest.Script snapshotPreScript = snapshot.preRequestScripts.get(0);

        request.url = "https://mutated.example.test/me";
        request.auth.type = "basic";
        request.auth.properties.put("token", "mutated");
        request.headers.get(0).value = "two";
        request.body.urlencoded.get(0).key = "client_id";
        request.body.urlencoded.get(0).value = "mutated";
        request.body.graphql.query = "{ mutated }";
        request.variables.get(0).value = "https://mutated.example.test";
        request.preRequestScripts.get(0).exec = "mutated";
        request.postResponseScripts.get(0).exec = "mutated";

        assertThat(snapshot).isNotSameAs(request);
        assertThat(snapshot.headers.get(0)).isNotSameAs(request.headers.get(0));
        assertThat(snapshot.body).isNotSameAs(request.body);
        assertThat(snapshotFormField).isNotSameAs(request.body.urlencoded.get(0));
        assertThat(snapshot.body.graphql).isNotSameAs(request.body.graphql);
        assertThat(snapshotVariable).isNotSameAs(request.variables.get(0));
        assertThat(snapshotPreScript).isNotSameAs(request.preRequestScripts.get(0));
        assertThat(snapshot.url).isEqualTo("https://api.example.test/me");
        assertThat(snapshot.auth.type).isEqualTo("bearer");
        assertThat(snapshot.auth.properties.get("token")).isEqualTo("{{accessToken}}");
        assertThat(snapshot.headers.get(0).value).isEqualTo("one");
        assertThat(snapshot.body.urlencoded.get(0).key).isEqualTo("grant_type");
        assertThat(snapshot.body.urlencoded.get(0).value).isEqualTo("client_credentials");
        assertThat(snapshot.body.graphql.query).isEqualTo("{ me }");
        assertThat(snapshot.variables.get(0).value).isEqualTo("https://api.example.test");
        assertThat(snapshot.preRequestScripts.get(0).exec).contains("pm.collectionVariables.set");
        assertThat(snapshot.postResponseScripts.get(0).exec).contains("pm.test");
        assertThat(snapshot.authInherited).isTrue();
        assertThat(snapshot.authExplicitlyDisabled).isFalse();
        assertThat(snapshot.authSource).isEqualTo("collection: Demo");
    }
}
