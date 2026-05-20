package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
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
        collection.folderVars.put("Admin", new java.util.LinkedHashMap<>(java.util.Map.of("role", "admin")));

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        String json = WorkspaceStateJson.toJson(state);
        WorkspaceState parsed = WorkspaceStateJson.fromJson(json);

        assertThat(parsed.version).isEqualTo(1);
        assertThat(parsed.collections).hasSize(1);
        assertThat(parsed.collections.get(0).name).isEqualTo("Demo");
        assertThat(parsed.collections.get(0).runtimeVars).containsEntry("baseUrl", "https://api.example.test");
        assertThat(parsed.collections.get(0).runtimeOAuth2).containsEntry("oauth2_token_url", "https://auth.example.test/token");
        assertThat(parsed.collections.get(0).folderVars).containsKey("Admin");
        assertThat(parsed.collections.get(0).folderVars.get("Admin")).containsEntry("role", "admin");
    }

    @Test
    void roundTripsEditableAuthInheritanceMetadata() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Auth Demo";
        collection.auth = new ApiRequest.Auth();
        collection.auth.type = "bearer";
        collection.auth.properties.put("token", "{{collectionToken}}");
        collection.folderAuthModes.put("Admin", "explicit");
        ApiRequest.Auth folderAuth = new ApiRequest.Auth();
        folderAuth.type = "bearer";
        folderAuth.properties.put("token", "{{folderToken}}");
        collection.folderAuth.put("Admin", folderAuth);

        ApiRequest request = new ApiRequest();
        request.name = "Special";
        request.path = "Admin/Special";
        request.sourceCollection = "Auth Demo";
        request.authOverrideMode = "explicit";
        request.explicitAuth = new ApiRequest.Auth();
        request.explicitAuth.type = "basic";
        request.explicitAuth.properties.put("username", "u");
        request.explicitAuth.properties.put("password", "p");
        request.auth = request.explicitAuth;
        request.authInherited = false;
        request.authSource = "request: Special";
        collection.requests.add(request);

        WorkspaceState parsed = WorkspaceStateJson.fromJson(
                WorkspaceStateJson.toJson(WorkspaceState.fromCollections(List.of(collection)))
        );

        ApiCollection restored = parsed.collections.get(0);
        assertThat(restored.auth.type).isEqualTo("bearer");
        assertThat(restored.auth.properties).containsEntry("token", "{{collectionToken}}");
        assertThat(restored.folderAuthModes).containsEntry("Admin", "explicit");
        assertThat(restored.folderAuth.get("Admin").type).isEqualTo("bearer");
        assertThat(restored.folderAuth.get("Admin").properties).containsEntry("token", "{{folderToken}}");

        ApiRequest restoredRequest = restored.requests.get(0);
        assertThat(restoredRequest.authOverrideMode).isEqualTo("explicit");
        assertThat(restoredRequest.explicitAuth.type).isEqualTo("basic");
        assertThat(restoredRequest.explicitAuth.properties).containsEntry("username", "u");
        assertThat(restoredRequest.auth.type).isEqualTo("basic");
    }

    @Test
    void roundTripsRequestPathHierarchyAndCoreFields() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest token = new ApiRequest();
        token.id = "req-token";
        token.name = "Get Token";
        token.path = "Auth/OAuth/Get Token";
        token.sourceCollection = "APIM";
        token.method = "POST";
        token.url = "https://auth.example.test/token";

        ApiRequest users = new ApiRequest();
        users.id = "req-users";
        users.name = "List Users";
        users.path = "Users/List Users";
        users.sourceCollection = "APIM";
        users.method = "GET";
        users.url = "https://api.example.test/users";

        collection.requests.add(token);
        collection.requests.add(users);

        WorkspaceState parsed = WorkspaceStateJson.fromJson(
                WorkspaceStateJson.toJson(WorkspaceState.fromCollections(List.of(collection)))
        );

        assertThat(parsed.collections).hasSize(1);
        assertThat(parsed.collections.get(0).requests).hasSize(2);

        ApiRequest restoredToken = parsed.collections.get(0).requests.get(0);
        ApiRequest restoredUsers = parsed.collections.get(0).requests.get(1);

        assertThat(restoredToken.path).isEqualTo("Auth/OAuth/Get Token");
        assertThat(restoredUsers.path).isEqualTo("Users/List Users");
        assertThat(restoredToken.sourceCollection).isEqualTo("APIM");
        assertThat(restoredUsers.sourceCollection).isEqualTo("APIM");
        assertThat(restoredToken.name).isEqualTo("Get Token");
        assertThat(restoredUsers.name).isEqualTo("List Users");
        assertThat(restoredToken.method).isEqualTo("POST");
        assertThat(restoredUsers.method).isEqualTo("GET");
        assertThat(restoredToken.url).isEqualTo("https://auth.example.test/token");
        assertThat(restoredUsers.url).isEqualTo("https://api.example.test/users");
    }

    @Test
    void workspaceSnapshotPersistsSensitiveRuntimeAndOAuthValuesByDefault() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        collection.runtimeVars.put("password", "runtime-password");
        collection.runtimeVars.put("api_key", "secret-key");
        collection.runtimeVars.put("baseUrl", "https://api.example.test");
        collection.runtimeOAuth2.put("oauth2_access_token", "access");
        collection.runtimeOAuth2.put("oauth2_refresh_token", "refresh");
        collection.runtimeOAuth2.put("oauth2_client_secret", "client-secret");
        collection.runtimeOAuth2.put("oauth2_password", "oauth-password");
        collection.runtimeOAuth2.put("oauth2_client_id", "client-id");
        collection.runtimeOAuth2.put("oauth2_token_url", "https://auth.example.test/token");

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));

        ApiCollection saved = state.collections.get(0);
        assertThat(saved.runtimeVars)
                .containsEntry("password", "runtime-password")
                .containsEntry("api_key", "secret-key")
                .containsEntry("baseUrl", "https://api.example.test");
        assertThat(saved.runtimeOAuth2)
                .containsEntry("oauth2_access_token", "access")
                .containsEntry("oauth2_refresh_token", "refresh")
                .containsEntry("oauth2_client_secret", "client-secret")
                .containsEntry("oauth2_password", "oauth-password")
                .containsEntry("oauth2_client_id", "client-id")
                .containsEntry("oauth2_token_url", "https://auth.example.test/token");
    }

    @Test
    void copyOfDeepCopiesWorkspaceSnapshotState() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        collection.runtimeVars.put("api_key", "secret-key");
        collection.runtimeOAuth2.put("oauth2_access_token", "access");
        collection.runtimeOAuth2.put("oauth2_refresh_token", "refresh");
        collection.runtimeOAuth2.put("oauth2_client_secret", "client-secret");
        collection.runtimeOAuth2.put("oauth2_client_id", "client-id");
        collection.runtimeOAuth2.put("oauth2_token_url", "https://auth.example.test/token");
        collection.folderVars.put("Admin", new java.util.LinkedHashMap<>(java.util.Map.of("role", "admin")));

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.selectedTabIndex = 2;
        state.selectedVariablesCollectionName = "Demo";
        state.selectedOAuth2CollectionName = "Demo";
        state.selectedRequestCollectionName = "Demo";
        state.selectedRequestName = "Get Me";
        state.selectedRequestPath = "Folder/Get Me";
        state.checkedRequestKeys = new java.util.ArrayList<>(List.of("Demo\u001FFolder/Get Me\u001FGet Me\u001FGET\u001F7"));
        state.expandedTreePathKeys = new java.util.ArrayList<>(List.of("Demo\u001FFolder"));

        WorkspaceState copy = WorkspaceState.copyOf(state);

        assertThat(copy).isNotSameAs(state);
        assertThat(copy.collections.get(0)).isNotSameAs(state.collections.get(0));
        assertThat(copy.collections.get(0).runtimeVars).containsEntry("api_key", "secret-key");
        assertThat(copy.collections.get(0).runtimeOAuth2).containsEntry("oauth2_access_token", "access");
        assertThat(copy.collections.get(0).folderVars).containsKey("Admin");
        assertThat(copy.collections.get(0).folderVars.get("Admin")).containsEntry("role", "admin");
        assertThat(copy.selectedTabIndex).isEqualTo(2);
        assertThat(copy.selectedVariablesCollectionName).isEqualTo("Demo");
        assertThat(copy.selectedOAuth2CollectionName).isEqualTo("Demo");
        assertThat(copy.selectedRequestCollectionName).isEqualTo("Demo");
        assertThat(copy.selectedRequestName).isEqualTo("Get Me");
        assertThat(copy.selectedRequestPath).isEqualTo("Folder/Get Me");
        assertThat(copy.checkedRequestKeys).containsExactly("Demo\u001FFolder/Get Me\u001FGet Me\u001FGET\u001F7");
        assertThat(copy.expandedTreePathKeys).containsExactly("Demo\u001FFolder");

        state.collections.get(0).runtimeVars.put("later", "mutation");
        state.collections.get(0).folderVars.get("Admin").put("later", "mutation");
        state.checkedRequestKeys.add("another");

        assertThat(copy.collections.get(0).runtimeVars).doesNotContainKey("later");
        assertThat(copy.collections.get(0).folderVars.get("Admin")).doesNotContainKey("later");
        assertThat(copy.checkedRequestKeys).containsExactly("Demo\u001FFolder/Get Me\u001FGet Me\u001FGET\u001F7");
    }

    @Test
    void roundTripsWorkspaceRestoreMetadataState() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.selectedRequestIdentityKey = "Demo\u001Fid=req-1";
        state.checkedRequestIdentityKeys = new java.util.ArrayList<>(List.of("Demo\u001Fid=req-1", "Demo\u001Findex=1"));
        state.expandedTreePathKeys = new java.util.ArrayList<>(List.of("Demo\u001F", "Demo\u001FAdmin"));

        state.workbenchRepeaterSelected = Boolean.FALSE;
        state.workbenchSitemapSelected = Boolean.TRUE;
        state.workbenchIntruderSelected = Boolean.TRUE;
        state.workbenchDelayMs = 275;
        state.workbenchDebugRawRequest = Boolean.TRUE;
        state.workbenchDetailTabIndex = 2;

        state.runnerDelayMs = 410;
        state.runnerRetries = 3;
        state.runnerStopOnError = Boolean.TRUE;
        state.runnerStopOnAssertionFailure = Boolean.TRUE;
        state.runnerStopOnStatusAtLeast400 = Boolean.TRUE;
        state.runnerStopOnMissingVariable = Boolean.TRUE;
        state.runnerStopAfterFailures = 7;
        state.runnerFollowRedirects = Boolean.FALSE;
        state.runnerDebugRawRequest = Boolean.TRUE;
        state.runnerDetailTabIndex = 1;

        WorkspaceState.OAuthAutoRefreshSnapshot autoRefresh = new WorkspaceState.OAuthAutoRefreshSnapshot();
        autoRefresh.enabled = Boolean.TRUE;
        autoRefresh.intervalSeconds = 180;
        autoRefresh.lastStatus = "Running";
        state.oauthAutoRefreshByCollection = new java.util.LinkedHashMap<>();
        state.oauthAutoRefreshByCollection.put("Demo", autoRefresh);

        WorkspaceState copy = WorkspaceState.copyOf(state);
        WorkspaceState parsed = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));

        assertThat(copy.selectedRequestIdentityKey).isEqualTo("Demo\u001Fid=req-1");
        assertThat(copy.checkedRequestIdentityKeys).containsExactly("Demo\u001Fid=req-1", "Demo\u001Findex=1");
        assertThat(copy.expandedTreePathKeys).containsExactly("Demo\u001F", "Demo\u001FAdmin");
        assertThat(copy.workbenchRepeaterSelected).isFalse();
        assertThat(copy.workbenchSitemapSelected).isTrue();
        assertThat(copy.workbenchIntruderSelected).isTrue();
        assertThat(copy.workbenchDelayMs).isEqualTo(275);
        assertThat(copy.workbenchDebugRawRequest).isTrue();
        assertThat(copy.workbenchDetailTabIndex).isEqualTo(2);
        assertThat(copy.runnerDelayMs).isEqualTo(410);
        assertThat(copy.runnerRetries).isEqualTo(3);
        assertThat(copy.runnerStopOnError).isTrue();
        assertThat(copy.runnerStopOnAssertionFailure).isTrue();
        assertThat(copy.runnerStopOnStatusAtLeast400).isTrue();
        assertThat(copy.runnerStopOnMissingVariable).isTrue();
        assertThat(copy.runnerStopAfterFailures).isEqualTo(7);
        assertThat(copy.runnerFollowRedirects).isFalse();
        assertThat(copy.runnerDebugRawRequest).isTrue();
        assertThat(copy.runnerDetailTabIndex).isEqualTo(1);
        assertThat(copy.oauthAutoRefreshByCollection).containsKey("Demo");
        assertThat(copy.oauthAutoRefreshByCollection.get("Demo").enabled).isTrue();
        assertThat(copy.oauthAutoRefreshByCollection.get("Demo").intervalSeconds).isEqualTo(180);
        assertThat(copy.oauthAutoRefreshByCollection.get("Demo").lastStatus).isEqualTo("Running");

        assertThat(parsed.selectedRequestIdentityKey).isEqualTo("Demo\u001Fid=req-1");
        assertThat(parsed.checkedRequestIdentityKeys).containsExactly("Demo\u001Fid=req-1", "Demo\u001Findex=1");
        assertThat(parsed.expandedTreePathKeys).containsExactly("Demo\u001F", "Demo\u001FAdmin");
        assertThat(parsed.workbenchRepeaterSelected).isFalse();
        assertThat(parsed.workbenchSitemapSelected).isTrue();
        assertThat(parsed.workbenchIntruderSelected).isTrue();
        assertThat(parsed.workbenchDelayMs).isEqualTo(275);
        assertThat(parsed.workbenchDebugRawRequest).isTrue();
        assertThat(parsed.workbenchDetailTabIndex).isEqualTo(2);
        assertThat(parsed.runnerDelayMs).isEqualTo(410);
        assertThat(parsed.runnerRetries).isEqualTo(3);
        assertThat(parsed.runnerStopOnError).isTrue();
        assertThat(parsed.runnerStopOnAssertionFailure).isTrue();
        assertThat(parsed.runnerStopOnStatusAtLeast400).isTrue();
        assertThat(parsed.runnerStopOnMissingVariable).isTrue();
        assertThat(parsed.runnerStopAfterFailures).isEqualTo(7);
        assertThat(parsed.runnerFollowRedirects).isFalse();
        assertThat(parsed.runnerDebugRawRequest).isTrue();
        assertThat(parsed.runnerDetailTabIndex).isEqualTo(1);
        assertThat(parsed.oauthAutoRefreshByCollection).containsKey("Demo");
        assertThat(parsed.oauthAutoRefreshByCollection.get("Demo").enabled).isTrue();
        assertThat(parsed.oauthAutoRefreshByCollection.get("Demo").intervalSeconds).isEqualTo(180);
        assertThat(parsed.oauthAutoRefreshByCollection.get("Demo").lastStatus).isEqualTo("Running");
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
        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));

        ApiRequest snapshot = state.collections.get(0).requests.get(0);
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

    @Test
    void copyOfPreservesWorkspaceSnapshotState() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        collection.runtimeVars.put("api_key", "secret-key");
        collection.runtimeVars.put("baseUrl", "https://api.example.test");
        collection.runtimeOAuth2.put("oauth2_access_token", "access");
        collection.runtimeOAuth2.put("oauth2_refresh_token", "refresh");
        collection.runtimeOAuth2.put("oauth2_client_secret", "client-secret");
        collection.runtimeOAuth2.put("oauth2_client_id", "client-id");
        collection.runtimeOAuth2.put("oauth2_token_url", "https://auth.example.test/token");

        WorkspaceState loaded = WorkspaceState.fromCollections(List.of(collection));
        WorkspaceState preserved = WorkspaceState.copyOf(loaded);

        assertThat(preserved.collections.get(0).runtimeVars).containsEntry("api_key", "secret-key");
        assertThat(preserved.collections.get(0).runtimeOAuth2).containsEntry("oauth2_access_token", "access");
        assertThat(preserved.collections.get(0).runtimeOAuth2).containsEntry("oauth2_refresh_token", "refresh");
        assertThat(preserved.collections.get(0).runtimeOAuth2).containsEntry("oauth2_client_secret", "client-secret");
    }
}
