package burp.utils;

import burp.history.HistoryEntry;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.ui.tree.RequestTreeMutationService;
import burp.testsupport.HistoryTestFixtures;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceStateJsonTest {

    @Test
    void environmentProfilesPersistAndRestore() {
        WorkspaceState state = new WorkspaceState();
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = "UAT";
        profile.sourceFormat = "postman";
        profile.sourceFileName = "uat.postman_environment.json";
        profile.variables.put("baseUrl", "https://uat.example.test");
        profile.variables.put("token", "uat-token");
        profile.oauth2.config.put("oauth2_client_id", "uat-client");
        profile.oauth2.outputBindings.put("accessToken", "token");
        state.environments.add(profile);

        WorkspaceState parsed = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));

        assertThat(parsed.environments).hasSize(1);
        assertThat(parsed.environments.get(0).name).isEqualTo("UAT");
        assertThat(parsed.environments.get(0).sourceFormat).isEqualTo("postman");
        assertThat(parsed.environments.get(0).sourceFileName).isEqualTo("uat.postman_environment.json");
        assertThat(parsed.environments.get(0).variables)
                .containsEntry("baseUrl", "https://uat.example.test")
                .containsEntry("token", "uat-token");
        assertThat(parsed.environments.get(0).oauth2.config).containsEntry("oauth2_client_id", "uat-client");
        assertThat(parsed.environments.get(0).oauth2.outputBindings).containsEntry("accessToken", "token");
    }

    @Test
    void activeEnvironmentIdPersistsAndRestores() {
        WorkspaceState state = new WorkspaceState();
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = "PRD";
        profile.ensureId();
        state.environments.add(profile);
        state.activeEnvironmentId = profile.id;

        WorkspaceState parsed = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));

        assertThat(parsed.activeEnvironmentId).isEqualTo(profile.id);
        assertThat(parsed.environments).hasSize(1);
    }

    @Test
    void missingEnvironmentFieldsDefaultSafely() {
        WorkspaceState parsed = WorkspaceStateJson.fromJson("{}");

        assertThat(parsed.environments).isNotNull().isEmpty();
        assertThat(parsed.activeEnvironmentId).isNull();
    }

    @Test
    void oauth2EnvironmentStateDefaultsSafely() {
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.oauth2 = null;
        profile.variables = null;

        WorkspaceState state = new WorkspaceState();
        state.environments.add(profile);

        WorkspaceState parsed = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));

        assertThat(parsed.environments.get(0).variables).isNotNull().isEmpty();
        assertThat(parsed.environments.get(0).oauth2).isNotNull();
        assertThat(parsed.environments.get(0).oauth2.config).isNotNull().isEmpty();
        assertThat(parsed.environments.get(0).oauth2.outputBindings)
                .containsEntry("accessToken", "oauth2_access_token")
                .containsEntry("refreshToken", "oauth2_refresh_token")
                .containsEntry("tokenType", "oauth2_token_type")
                .containsEntry("expiresIn", "oauth2_expires_in");
    }

    @Test
    void legacyWorkspaceWithoutEnvironmentsStillLoads() {
        String json = """
                {
                  "version": 1,
                  "collections": [{
                    "name": "Legacy",
                    "requests": []
                  }]
                }
                """;

        WorkspaceState parsed = WorkspaceStateJson.fromJson(json);

        assertThat(parsed.collections).hasSize(1);
        assertThat(parsed.environments).isEmpty();
        assertThat(parsed.activeEnvironmentId).isNull();
    }

    @Test
    void roundTripsLoadedCollectionsAndRuntimeVars() {
        ApiCollection collection = new ApiCollection();
        collection.id = "col-demo";
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
        assertThat(parsed.collections.get(0).id).isEqualTo("col-demo");
        assertThat(parsed.collections.get(0).runtimeVars).containsEntry("baseUrl", "https://api.example.test");
        assertThat(parsed.collections.get(0).runtimeOAuth2).containsEntry("oauth2_token_url", "https://auth.example.test/token");
        assertThat(parsed.collections.get(0).folderVars).containsKey("Admin");
        assertThat(parsed.collections.get(0).folderVars.get("Admin")).containsEntry("role", "admin");
    }

    @Test
    void workspaceJsonAssignsMissingCollectionIdBeforeWriteAndPreservesItOnRestore() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        String json = WorkspaceStateJson.toJson(state);
        String savedId = JsonParser.parseString(json)
                .getAsJsonObject()
                .getAsJsonArray("collections")
                .get(0)
                .getAsJsonObject()
                .get("id")
                .getAsString();

        assertThat(savedId).isNotBlank();
        assertThat(WorkspaceStateJson.fromJson(json).collections.get(0).id).isEqualTo(savedId);
    }

    @Test
    void legacyWorkspaceHistoryEntriesWithoutCollectionIdentityStillLoad() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Legacy";

        WorkspaceState state = new WorkspaceState();
        state.collections.add(collection);
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "legacy-history", java.time.Instant.parse("2026-06-15T01:00:30Z"));
        entry.collectionId = null;
        entry.collectionName = "Legacy";
        state.historyEntries.add(entry);

        WorkspaceState parsed = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));

        assertThat(parsed.collections).hasSize(1);
        assertThat(parsed.collections.get(0).id).isNotBlank();
        assertThat(parsed.historyEntries).hasSize(1);
        assertThat(parsed.historyEntries.get(0).collectionName).isEqualTo("Legacy");
        assertThat(parsed.historyEntries.get(0).collectionId).isNull();
    }

    @Test
    void normalizeFolderPathsTrimsDeduplicatesAndNormalizesSlashes() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        collection.folderPaths.add(" Auth ");
        collection.folderPaths.add("Auth");
        collection.folderPaths.add("Auth//OAuth");
        collection.folderPaths.add("");
        collection.folderPaths.add("  ");
        collection.folderPaths.add("Auth\\Token");

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        WorkspaceState parsed = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));

        assertThat(parsed.collections.get(0).folderPaths)
                .containsExactly("Auth", "Auth/OAuth", "Auth/Token");
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
    void workspaceRoundTripPreservesRequestBuildModeAndSuppressedAutoHeaders() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Build Demo";

        ApiRequest request = new ApiRequest();
        request.name = "Edited";
        request.path = "Edited";
        request.sourceCollection = "Build Demo";
        request.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        request.suppressedAutoHeaders.add("authorization");
        request.suppressedAutoHeaders.add("content-type");
        collection.requests.add(request);

        WorkspaceState parsed = WorkspaceStateJson.fromJson(
                WorkspaceStateJson.toJson(WorkspaceState.fromCollections(List.of(collection)))
        );

        ApiRequest restored = parsed.collections.get(0).requests.get(0);
        assertThat(restored.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(restored.suppressedAutoHeaders).containsExactly("authorization", "content-type");
    }

    @Test
    void legacyWorkspaceDefaultsBuildModeAndSuppressedAutoHeadersSafely() {
        String json = """
                {
                  "version": 1,
                  "collections": [{
                    "name": "Legacy",
                    "requests": [{
                      "name": "Imported",
                      "editorMaterialized": false,
                      "method": "GET",
                      "url": "https://api.example.test"
                    }, {
                      "name": "Edited",
                      "editorMaterialized": true,
                      "method": "GET",
                      "url": "https://api.example.test"
                    }]
                  }]
                }
                """;

        WorkspaceState parsed = WorkspaceStateJson.fromJson(json);

        assertThat(parsed.collections).hasSize(1);
        assertThat(parsed.collections.get(0).requests).hasSize(2);
        assertThat(parsed.collections.get(0).requests.get(0).buildMode).isEqualTo(ApiRequest.BuildMode.AUTO_COMPATIBLE);
        assertThat(parsed.collections.get(0).requests.get(1).buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(parsed.collections.get(0).requests.get(0).suppressedAutoHeaders).isEmpty();
        assertThat(parsed.collections.get(0).requests.get(1).suppressedAutoHeaders).isEmpty();
    }

    @Test
    void workspaceNormalizeLowercasesSuppressedAutoHeaders() {
        String json = """
                {
                  "version": 1,
                  "collections": [{
                    "name": "Legacy",
                    "requests": [{
                      "name": "Imported",
                      "editorMaterialized": true,
                      "method": "POST",
                      "url": "https://api.example.test",
                      "suppressedAutoHeaders": [" Authorization ", "CONTENT-TYPE", ""]
                    }]
                  }]
                }
                """;

        WorkspaceState parsed = WorkspaceStateJson.fromJson(json);

        assertThat(parsed.collections.get(0).requests.get(0).suppressedAutoHeaders)
                .containsExactly("authorization", "content-type");
    }

    @Test
    void normalizeRemovesSuppressedAuthorizationHeader() {
        String json = """
                {
                  "version": 1,
                  "collections": [{
                    "name": "AuthTest",
                    "requests": [{
                      "name": "Secure",
                      "editorMaterialized": true,
                      "buildMode": "MANUAL_PRESERVE",
                      "method": "GET",
                      "url": "https://api.example.test",
                      "suppressedAutoHeaders": ["authorization"],
                      "headers": [
                        {"key": "Authorization", "value": "Bearer stale-token"},
                        {"key": "X-Custom", "value": "keep-me"}
                      ]
                    }]
                  }]
                }
                """;

        WorkspaceState parsed = WorkspaceStateJson.fromJson(json);
        ApiRequest restored = parsed.collections.get(0).requests.get(0);

        assertThat(restored.suppressedAutoHeaders).containsExactly("authorization");
        assertThat(restored.headers).extracting(h -> h.key).doesNotContain("Authorization");
        assertThat(restored.headers).extracting(h -> h.key).contains("X-Custom");
    }

    @Test
    void normalizeRemovesSuppressedContentTypeHeader() {
        String json = """
                {
                  "version": 1,
                  "collections": [{
                    "name": "ContentTypeTest",
                    "requests": [{
                      "name": "PostIt",
                      "editorMaterialized": true,
                      "buildMode": "MANUAL_PRESERVE",
                      "method": "POST",
                      "url": "https://api.example.test",
                      "suppressedAutoHeaders": ["content-type"],
                      "headers": [
                        {"key": "Content-Type", "value": "application/json"},
                        {"key": "X-Custom", "value": "keep-me"}
                      ]
                    }]
                  }]
                }
                """;

        WorkspaceState parsed = WorkspaceStateJson.fromJson(json);
        ApiRequest restored = parsed.collections.get(0).requests.get(0);

        assertThat(restored.suppressedAutoHeaders).containsExactly("content-type");
        assertThat(restored.headers).extracting(h -> h.key).doesNotContain("Content-Type");
        assertThat(restored.headers).extracting(h -> h.key).contains("X-Custom");
    }

    @Test
    void workspaceStateMigratorPreservesCurrentVersionState() {
        WorkspaceState state = new WorkspaceState();
        state.version = 1;

        ApiCollection collection = new ApiCollection();
        ApiRequest request = new ApiRequest();
        request.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        request.suppressedAutoHeaders.add("authorization");
        collection.requests.add(request);
        state.collections.add(collection);

        WorkspaceState migrated = WorkspaceStateMigrator.migrate(state);

        assertThat(migrated.version).isEqualTo(1);
        assertThat(migrated.collections.get(0).requests.get(0).buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(migrated.collections.get(0).requests.get(0).suppressedAutoHeaders).containsExactly("authorization");
    }

    @Test
    void workspaceStateMigratorDefaultsMissingOrZeroVersionToOne() {
        WorkspaceState state = new WorkspaceState();
        state.version = 0;

        WorkspaceState migrated = WorkspaceStateMigrator.migrate(state);

        assertThat(migrated.version).isEqualTo(1);
    }

    @Test
    void workspaceStateMigratorAllowsFutureVersionBestEffort() {
        WorkspaceState state = new WorkspaceState();
        state.version = 999;

        WorkspaceState migrated = WorkspaceStateMigrator.migrate(state);

        assertThat(migrated.version).isEqualTo(999);
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
    void roundTripsMovedCollectionOrderAndMovedRequestPaths() {
        RequestTreeMutationService mutationService = new RequestTreeMutationService();

        ApiCollection archive = new ApiCollection();
        archive.name = "Archive";

        ApiCollection apim = new ApiCollection();
        apim.name = "APIM";
        apim.folderPaths.add("Admin");
        apim.folderPaths.add("Auth");
        apim.folderPaths.add("Auth/OAuth");

        ApiRequest login = new ApiRequest();
        login.id = "req-login";
        login.name = "Login";
        login.path = "Auth/OAuth";
        login.sourceCollection = "APIM";
        login.method = "POST";
        login.url = "https://api.example.test/login";

        ApiRequest audit = new ApiRequest();
        audit.id = "req-audit";
        audit.name = "Audit";
        audit.path = "Auth/OAuth";
        audit.sourceCollection = "APIM";
        audit.method = "GET";
        audit.url = "https://api.example.test/audit";

        apim.requests.add(login);
        apim.requests.add(audit);

        mutationService.moveFolder(apim, "Auth", apim, "", 0);
        mutationService.moveRequest(apim, audit, apim, "Auth/OAuth", 0);

        WorkspaceState parsed = WorkspaceStateJson.fromJson(
                WorkspaceStateJson.toJson(WorkspaceState.fromCollections(List.of(archive, apim)))
        );

        assertThat(parsed.collections).extracting(col -> col.name)
                .containsExactly("Archive", "APIM");

        ApiCollection restoredApim = parsed.collections.get(1);
        assertThat(restoredApim.folderPaths).containsExactly("Auth", "Auth/OAuth", "Admin");
        assertThat(restoredApim.requests).extracting(req -> req.id)
                .containsExactly("req-audit", "req-login");
        assertThat(restoredApim.requests.get(0).path).isEqualTo("Auth/OAuth");
        assertThat(restoredApim.requests.get(1).path).isEqualTo("Auth/OAuth");
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

    @Test
    void workspaceJsonRoundTripsNativeScriptBlocks() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        ScriptBlock block = new ScriptBlock();
        block.dialect = ScriptDialect.POSTMAN;
        block.phase = ScriptPhase.PRE_REQUEST;
        block.scope = ScriptScope.REQUEST;
        block.source = "pm.environment.set('token', 'abc123');";
        block.enabled = true;
        block.order = 2;
        collection.scriptBlocks.add(block);

        WorkspaceState parsed = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(WorkspaceState.fromCollections(List.of(collection))));

        assertThat(parsed.collections).hasSize(1);
        assertThat(parsed.collections.get(0).scriptBlocks).hasSize(1);
        assertThat(parsed.collections.get(0).scriptBlocks.get(0).dialect).isEqualTo(ScriptDialect.POSTMAN);
        assertThat(parsed.collections.get(0).scriptBlocks.get(0).phase).isEqualTo(ScriptPhase.PRE_REQUEST);
        assertThat(parsed.collections.get(0).scriptBlocks.get(0).scope).isEqualTo(ScriptScope.REQUEST);
        assertThat(parsed.collections.get(0).scriptBlocks.get(0).source).contains("pm.environment.set");
    }
}
