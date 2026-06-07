package burp.ui;

import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.auth.OAuth2Manager;
import burp.auth.TokenStore;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.parser.VariableResolver;
import burp.runner.CollectionRunner;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelOAuth2PopulateTest {

    @Test
    void oauth2PopulateResolverUsesActiveEnvironmentOverlay() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.environment.put("base_url", "https://env.example.test");
        collection.variables.add(variable("collection_only", "from-collection"));
        collection.runtimeOAuth2.put("oauth2_access_token", "legacy-token");
        collection.runtimeVars.put("client_id", "legacy-client");

        ApiRequest request = new ApiRequest();
        request.variables.add(variable("request_only", "from-request"));

        VariableResolver resolver = ImporterPanel.buildOAuth2PopulateResolver(
                collection,
                request,
                Map.of(
                        "client_id", "active-client",
                        "token_url", "https://active.example.test/token"
                ));

        assertThat(resolver.resolve("{{base_url}}")).isEqualTo("https://env.example.test");
        assertThat(resolver.resolve("{{collection_only}}")).isEqualTo("from-collection");
        assertThat(resolver.resolve("{{client_id}}")).isEqualTo("active-client");
        assertThat(resolver.resolve("{{token_url}}")).isEqualTo("https://active.example.test/token");
        assertThat(resolver.resolve("{{request_only}}")).isEqualTo("from-request");
        assertThat(resolver.resolve("{{oauth2_access_token}}")).isEqualTo("{{oauth2_access_token}}");
    }

    @Test
    void oauth2PopulateExistingValuesPreferActiveEnvironmentConfig() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        active.oauth2.config.put("oauth2_client_id", "active-client");
        active.oauth2.config.put("oauth2_token_url", "https://active.example.test/token");
        active.variables.put("scope", "active-scope");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.environment.put("oauth2_client_id", "collection-client");
        collection.variables.add(variable("scope", "collection-scope"));
        collection.runtimeOAuth2.put("oauth2_token_url", "https://legacy.example.test/token");

        ApiRequest request = new ApiRequest();
        request.sourceCollection = collection.name;
        request.variables.add(variable("request_only", "request-value"));

        Map<String, String> existing = invokePopulateExistingVars(panel, collection, request, active);

        assertThat(existing)
                .containsEntry("oauth2_client_id", "active-client")
                .containsEntry("oauth2_token_url", "https://active.example.test/token")
                .containsEntry("scope", "active-scope")
                .containsEntry("request_only", "request-value")
                .doesNotContainEntry("oauth2_token_url", "https://legacy.example.test/token");
    }

    @Test
    void oauth2PopulateRequiresActiveEnvironment() throws Exception {
        ImporterPanel panel = newPanel();
        ApiRequest req = request("Populate");

        invokePrivate(panel, "populateOAuth2FromRequest", new Class<?>[]{ApiRequest.class}, req);

        OAuth2Panel oauth2Panel = oauth2Panel(panel);
        assertThat(oauth2Panel.getVariables()).isEmpty();
    }

    @Test
    void oauth2TabUsesExpectedButtonLabels() throws Exception {
        ImporterPanel panel = newPanel();
        OAuth2Panel oauth2Panel = oauth2Panel(panel);

        assertThat(((JButton) privateField(oauth2Panel, "populateBtn")).getText()).isEqualTo("Populate from Request");
        assertThat(((JButton) privateField(oauth2Panel, "acquireBtn")).getText()).isEqualTo("Acquire Token");
        assertThat(((JButton) privateField(oauth2Panel, "bindBtn")).getText()).isEqualTo("Bind Token");
        assertThat(((JButton) privateField(oauth2Panel, "clearBtn")).getText()).isEqualTo("Clear Tokens");
    }

    @Test
    void oauth2ButtonsAreDisabledWithoutActiveEnvironment() throws Exception {
        ImporterPanel panel = newPanel();
        OAuth2Panel oauth2Panel = oauth2Panel(panel);

        assertThat(((JButton) privateField(oauth2Panel, "populateBtn")).isEnabled()).isFalse();
        assertThat(((JButton) privateField(oauth2Panel, "acquireBtn")).isEnabled()).isFalse();
        assertThat(((JButton) privateField(oauth2Panel, "bindBtn")).isEnabled()).isFalse();
        assertThat(((JButton) privateField(oauth2Panel, "clearBtn")).isEnabled()).isFalse();
    }

    @Test
    void oauth2ActiveEnvironmentDropdownSyncsWithWorkbench() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile uat = environment("UAT");
        EnvironmentProfile prd = environment("PRD");
        panel.replaceEnvironmentProfiles(List.of(uat, prd));
        panel.setActiveEnvironmentId(prd.id);
        drainEdt();

        JComboBox<?> oauth2Combo = (JComboBox<?>) privateField(panel, "oauth2EnvironmentCombo");
        JComboBox<?> workbenchCombo = (JComboBox<?>) privateField(panel, "workbenchEnvironmentCombo");
        assertThat(oauth2Combo.getSelectedItem()).hasToString("PRD");
        assertThat(workbenchCombo.getSelectedItem()).hasToString("PRD");

        SwingUtilities.invokeAndWait(() -> oauth2Combo.setSelectedIndex(1));
        drainEdt();

        assertThat(panel.getActiveEnvironmentId()).isEqualTo(uat.id);
        assertThat(workbenchCombo.getSelectedItem()).hasToString("UAT");
        assertThat(oauth2Combo.getSelectedItem()).hasToString("UAT");
    }

    @Test
    void oauth2PopulateUsesActiveEnvironmentOverlay() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        active.oauth2.config.put("oauth2_client_id", "active-client");
        active.oauth2.config.put("oauth2_client_secret", "active-secret");
        active.oauth2.config.put("oauth2_token_url", "https://active.example.test/token");
        active.variables.put("scope", "active-scope");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.runtimeOAuth2.put("oauth2_client_id", "legacy-client");
        collection.runtimeVars.put("oauth2_client_secret", "legacy-secret");

        ApiRequest request = request("Token Request");
        request.sourceCollection = collection.name;
        request.auth = new ApiRequest.Auth();
        request.auth.type = "oauth2";
        request.auth.properties.put("grantType", "client_credentials");
        request.auth.properties.put("clientId", "{{oauth2_client_id}}");
        request.auth.properties.put("clientSecret", "{{oauth2_client_secret}}");
        request.auth.properties.put("accessTokenUrl", "{{oauth2_token_url}}");
        request.auth.properties.put("scope", "{{scope}}");
        collection.requests.add(request);

        panel.restoreWorkspaceCollections(List.of(collection));
        invokePrivate(panel, "populateOAuth2FromRequest", new Class<?>[]{ApiRequest.class}, request);
        drainEdt();

        Map<String, String> vars = oauth2Panel(panel).getVariables();
        assertThat(vars)
                .containsEntry("oauth2_grant", "client_credentials")
                .containsEntry("oauth2_client_id", "active-client")
                .containsEntry("oauth2_client_secret", "active-secret")
                .containsEntry("oauth2_token_url", "https://active.example.test/token")
                .containsEntry("oauth2_scope", "active-scope")
                .doesNotContainEntry("oauth2_client_id", "legacy-client")
                .doesNotContainEntry("oauth2_client_secret", "legacy-secret");
    }

    @Test
    void oauth2PopulateFromRequestPersistsConfigToActiveEnvironment() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest request = request("Token Request");
        request.sourceCollection = collection.name;
        request.auth = new ApiRequest.Auth();
        request.auth.type = "oauth2";
        request.auth.properties.put("grantType", "client_credentials");
        request.auth.properties.put("clientId", "client-id-from-request");
        request.auth.properties.put("clientSecret", "client-secret-from-request");
        request.auth.properties.put("accessTokenUrl", "https://auth.example.test/token");
        request.auth.properties.put("scope", "api.read");
        collection.requests.add(request);

        panel.restoreWorkspaceCollections(List.of(collection));
        invokePrivate(panel, "populateOAuth2FromRequest", new Class<?>[]{ApiRequest.class}, request);
        drainEdt();

        assertThat(active.oauth2.config)
                .containsEntry("oauth2_grant", "client_credentials")
                .containsEntry("oauth2_client_id", "client-id-from-request")
                .containsEntry("oauth2_client_secret", "client-secret-from-request")
                .containsEntry("oauth2_token_url", "https://auth.example.test/token")
                .containsEntry("oauth2_scope", "api.read");

        Map<String, String> vars = oauth2Panel(panel).getVariables();
        assertThat(vars)
                .containsEntry("oauth2_grant", "client_credentials")
                .containsEntry("oauth2_client_id", "client-id-from-request")
                .containsEntry("oauth2_client_secret", "client-secret-from-request")
                .containsEntry("oauth2_token_url", "https://auth.example.test/token")
                .containsEntry("oauth2_scope", "api.read");

        WorkspaceState snapshot = panel.getWorkspaceStateSnapshot();
        assertThat(snapshot.environments).hasSize(1);
        assertThat(snapshot.environments.get(0).oauth2.config)
                .containsEntry("oauth2_grant", "client_credentials")
                .containsEntry("oauth2_client_id", "client-id-from-request")
                .containsEntry("oauth2_client_secret", "client-secret-from-request")
                .containsEntry("oauth2_token_url", "https://auth.example.test/token")
                .containsEntry("oauth2_scope", "api.read");
    }

    @Test
    void oauth2PopulateFormSurvivesPassiveSync() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest request = request("Token Request");
        request.sourceCollection = collection.name;
        request.auth = new ApiRequest.Auth();
        request.auth.type = "oauth2";
        request.auth.properties.put("grantType", "client_credentials");
        request.auth.properties.put("clientId", "client-id-from-request");
        request.auth.properties.put("clientSecret", "client-secret-from-request");
        request.auth.properties.put("accessTokenUrl", "https://auth.example.test/token");
        request.auth.properties.put("scope", "api.read");
        collection.requests.add(request);

        panel.restoreWorkspaceCollections(List.of(collection));
        invokePrivate(panel, "populateOAuth2FromRequest", new Class<?>[]{ApiRequest.class}, request);
        drainEdt();

        invokePrivate(panel, "syncOAuth2UiState");
        invokePrivate(panel, "syncWorkbenchEnvironmentControls");
        invokePrivate(panel, "updateEnvironmentUiState");
        invokePrivate(panel, "syncActiveEnvironmentToEditors");
        drainEdt();

        Map<String, String> vars = oauth2Panel(panel).getVariables();
        assertThat(vars)
                .containsEntry("oauth2_grant", "client_credentials")
                .containsEntry("oauth2_client_id", "client-id-from-request")
                .containsEntry("oauth2_client_secret", "client-secret-from-request")
                .containsEntry("oauth2_token_url", "https://auth.example.test/token")
                .containsEntry("oauth2_scope", "api.read");
        assertThat(active.variables).doesNotContainKey("oauth2_access_token");
    }

    @Test
    void dirtyOAuth2FormIsNotOverwrittenByPassiveSync() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        active.oauth2.config.put("oauth2_client_id", "saved-client");
        active.oauth2.config.put("oauth2_client_secret", "saved-secret");
        active.oauth2.config.put("oauth2_token_url", "https://saved.example.test/token");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);
        drainEdt();

        OAuth2Panel oauth2Panel = oauth2Panel(panel);
        SwingUtilities.invokeAndWait(() -> {
            ((JTextField) privateFieldUnchecked(oauth2Panel, "clientIdField")).setText("draft-client");
            ((JPasswordField) privateFieldUnchecked(oauth2Panel, "clientSecretField")).setText("draft-secret");
            ((JTextField) privateFieldUnchecked(oauth2Panel, "tokenUrlField")).setText("https://draft.example.test/token");
        });
        drainEdt();

        invokePrivate(panel, "syncOAuth2UiState");
        drainEdt();

        Map<String, String> vars = oauth2Panel(panel).getVariables();
        assertThat(vars)
                .containsEntry("oauth2_client_id", "draft-client")
                .containsEntry("oauth2_client_secret", "draft-secret")
                .containsEntry("oauth2_token_url", "https://draft.example.test/token");
        assertThat(active.oauth2.config)
                .containsEntry("oauth2_client_id", "saved-client")
                .containsEntry("oauth2_client_secret", "saved-secret")
                .containsEntry("oauth2_token_url", "https://saved.example.test/token");
    }

    @Test
    void oauth2PopulateDoesNotMutateEnvironmentUntilTokenFetch() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        active.oauth2.config.put("oauth2_client_id", "active-client");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);

        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        ApiRequest request = request("Token Request");
        request.sourceCollection = collection.name;
        request.auth = new ApiRequest.Auth();
        request.auth.type = "oauth2";
        request.auth.properties.put("clientId", "{{oauth2_client_id}}");
        collection.requests.add(request);

        panel.restoreWorkspaceCollections(List.of(collection));
        invokePrivate(panel, "populateOAuth2FromRequest", new Class<?>[]{ApiRequest.class}, request);
        drainEdt();

        assertThat(active.variables).doesNotContainKey("oauth2_client_id");
        assertThat(active.variables).doesNotContainKey("oauth2_access_token");
    }

    @Test
    void clearTokensRemovesAccessTokenBindingFromActiveEnvironment() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        active.oauth2.outputBindings.put("accessToken", "token");
        active.variables.put("token", "stale-token");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);

        clearTokens(panel);

        assertThat(active.variables).doesNotContainKey("token");
    }

    @Test
    void clearTokensRemovesRefreshTokenBindingFromActiveEnvironment() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        active.oauth2.outputBindings.put("refreshToken", "refresh_token");
        active.variables.put("refresh_token", "stale-refresh");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);

        clearTokens(panel);

        assertThat(active.variables).doesNotContainKey("refresh_token");
    }

    @Test
    void clearTokensDoesNotWriteCollectionRuntimeOAuth2() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        active.oauth2.outputBindings.put("accessToken", "token");
        active.variables.put("token", "stale-token");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);

        ApiCollection collection = new ApiCollection();
        collection.runtimeOAuth2.put("oauth2_access_token", "legacy");
        panel.restoreWorkspaceCollections(List.of(collection));

        clearTokens(panel);

        assertThat(collection.runtimeOAuth2).containsEntry("oauth2_access_token", "legacy");
    }

    @Test
    void clearTokensWithoutActiveEnvironmentIsSafe() throws Exception {
        ImporterPanel panel = newPanel();
        clearTokens(panel);
        assertThat(oauth2Panel(panel).getVariables()).isEmpty();
    }

    @Test
    void environmentSavePersistsOAuth2OutputBindingEdits() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        active.variables.put("baseUrl", "https://uat.example.test");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);

        TokenStore.TokenEntry entry = token("access-token");
        invokePrivate(panel, "applyOAuth2TokenBindingSelection",
                new Class<?>[]{TokenStore.TokenEntry.class, Map.class},
                entry,
                Map.of("accessToken", "access_token"));

        invokePrivate(panel, "commitEnvironmentEditorToSelectedProfile");
        drainEdt();

        WorkspaceState snapshot = panel.getWorkspaceStateSnapshot();
        assertThat(snapshot.environments).hasSize(1);
        assertThat(snapshot.environments.get(0).variables).containsEntry("access_token", "access-token");
        assertThat(snapshot.environments.get(0).oauth2.outputBindings)
                .containsEntry("accessToken", "access_token");
    }

    @Test
    void runtimeUseCommitsLatestOAuth2BindingBeforeOverlay() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        active.variables.put("baseUrl", "https://uat.example.test");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);

        TokenStore.TokenEntry entry = token("runtime-access-token");
        invokePrivate(panel, "applyOAuth2TokenBindingSelection",
                new Class<?>[]{TokenStore.TokenEntry.class, Map.class},
                entry,
                Map.of("accessToken", "access_token"));

        @SuppressWarnings("unchecked")
        Map<String, String> overlay = (Map<String, String>) invokePrivateReturning(panel, "activeEnvironmentOverlayForRuntimeUse");
        assertThat(overlay).isNotNull();
        assertThat(overlay).containsEntry("access_token", "runtime-access-token");
        assertThat(active.oauth2.outputBindings).containsEntry("accessToken", "access_token");
    }

    @Test
    void autoBindUncheckedDoesNotWriteTokenOnAcquire() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);
        drainEdt();

        OAuth2Panel oauth2Panel = oauth2Panel(panel);
        SwingUtilities.invokeAndWait(() -> oauth2Panel.getAutoBindCheckBox().setSelected(false));

        TokenStore.TokenEntry entry = token("auto-bind-off-token");
        invokePrivate(panel, "handleOAuth2TokenAcquired",
                new Class<?>[]{TokenStore.TokenEntry.class, ApiCollection.class, Map.class},
                entry,
                null,
                Map.of("oauth2_client_id", "client-id", "oauth2_token_url", "https://auth.example.test/token"));
        drainEdt();

        assertThat(active.variables).doesNotContainKey("oauth2_access_token");
        assertThat(oauth2Panel.getBindTokenButton().isEnabled()).isTrue();
    }

    @Test
    void autoBindCheckedWithExistingBindingUpdatesActiveEnvironment() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        active.oauth2.outputBindings.put("accessToken", "token");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);
        drainEdt();

        OAuth2Panel oauth2Panel = oauth2Panel(panel);
        SwingUtilities.invokeAndWait(() -> oauth2Panel.getAutoBindCheckBox().setSelected(true));

        TokenStore.TokenEntry entry = token("auto-bind-on-token");
        invokePrivate(panel, "handleOAuth2TokenAcquired",
                new Class<?>[]{TokenStore.TokenEntry.class, ApiCollection.class, Map.class},
                entry,
                null,
                Map.of("oauth2_client_id", "client-id", "oauth2_token_url", "https://auth.example.test/token"));
        drainEdt();

        assertThat(active.variables).containsEntry("token", "auto-bind-on-token");
        assertThat(active.oauth2.outputBindings).containsEntry("accessToken", "token");
    }

    @Test
    void bindTokenWritesAccessTokenToSelectedExistingEnvironmentVariable() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        active.variables.put("token", "");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);
        drainEdt();

        TokenStore.TokenEntry entry = token("full-access-token");
        invokePrivate(panel, "applyOAuth2TokenBindingSelection",
                new Class<?>[]{TokenStore.TokenEntry.class, Map.class},
                entry,
                Map.of("accessToken", "token"));

        assertThat(active.variables).containsEntry("token", "full-access-token");
        assertThat(active.oauth2.outputBindings).containsEntry("accessToken", "token");
    }

    @Test
    void bindTokenCanCreateNewVariable() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);
        drainEdt();

        TokenStore.TokenEntry entry = token("new-access-token");
        invokePrivate(panel, "applyOAuth2TokenBindingSelection",
                new Class<?>[]{TokenStore.TokenEntry.class, Map.class},
                entry,
                Map.of("accessToken", "access_token"));

        assertThat(active.variables).containsEntry("access_token", "new-access-token");
        assertThat(active.oauth2.outputBindings).containsEntry("accessToken", "access_token");
    }

    @Test
    void tokenStatusShowsFullToken() throws Exception {
        OAuth2Manager manager = Mockito.mock(OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS);
        TokenStore.TokenEntry entry = token("full-visible-token");
        Mockito.when(manager.acquireToken(Mockito.any())).thenReturn(entry);

        OAuth2Panel oauth2Panel = new OAuth2Panel(manager);
        SwingUtilities.invokeAndWait(() -> oauth2Panel.populateFromOAuth2Map(Map.of(
                "oauth2_grant", "client_credentials",
                "oauth2_client_id", "client-id",
                "oauth2_client_secret", "client-secret",
                "oauth2_token_url", "https://auth.example.test/token",
                "oauth2_scope", "api.read"
        )));
        drainEdt();

        JButton acquireBtn = (JButton) privateField(oauth2Panel, "acquireBtn");
        SwingUtilities.invokeAndWait(acquireBtn::doClick);
        waitForStatusText(oauth2Panel, "Access Token: full-visible-token");

        JTextArea statusArea = (JTextArea) privateField(oauth2Panel, "statusArea");
        assertThat(statusArea.getText()).contains("OAuth2 Request:");
        assertThat(statusArea.getText()).contains("OAuth2 Response:");
        assertThat(statusArea.getText()).contains("Access Token: full-visible-token");
        assertThat(statusArea.getText()).contains("Refresh Token: refresh-full-visible-token");
        assertThat(statusArea.getText()).contains("Token Type: Bearer");
    }

    @Test
    void runtimeUseCommitsDirtyOAuth2ConfigBeforeOverlay() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT");
        active.oauth2.config.put("oauth2_client_id", "saved-client");
        active.oauth2.config.put("oauth2_client_secret", "saved-secret");
        active.oauth2.config.put("oauth2_token_url", "https://saved.example.test/token");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);
        drainEdt();

        OAuth2Panel oauth2Panel = oauth2Panel(panel);
        SwingUtilities.invokeAndWait(() -> {
            ((JTextField) privateFieldUnchecked(oauth2Panel, "clientIdField")).setText("draft-client");
            ((JPasswordField) privateFieldUnchecked(oauth2Panel, "clientSecretField")).setText("draft-secret");
            ((JTextField) privateFieldUnchecked(oauth2Panel, "tokenUrlField")).setText("https://draft.example.test/token");
        });
        drainEdt();

        @SuppressWarnings("unchecked")
        Map<String, String> overlay = (Map<String, String>) invokePrivateReturning(panel, "activeEnvironmentOverlayForRuntimeUse");
        assertThat(overlay)
                .containsEntry("oauth2_client_id", "draft-client")
                .containsEntry("oauth2_client_secret", "draft-secret")
                .containsEntry("oauth2_token_url", "https://draft.example.test/token");
        assertThat(active.oauth2.config)
                .containsEntry("oauth2_client_id", "draft-client")
                .containsEntry("oauth2_client_secret", "draft-secret")
                .containsEntry("oauth2_token_url", "https://draft.example.test/token");
    }

    private ImporterPanel newPanel() throws Exception {
        burp.UniversalImporter importer = Mockito.mock(burp.UniversalImporter.class, Mockito.RETURNS_DEEP_STUBS);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpRequestEditor(Mockito.any(EditorOptions.class))).thenReturn(requestEditor);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpResponseEditor(Mockito.any(EditorOptions.class))).thenReturn(responseEditor);
        OAuth2Manager oauth2Manager = Mockito.mock(OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS);
        CollectionRunner runner = new CollectionRunner(null);
        return new ImporterPanel(importer, runner, oauth2Manager, burp.utils.ScriptMode.DISABLED);
    }

    private static EnvironmentProfile environment(String name) {
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = name;
        profile.ensureId();
        profile.ensureDefaults();
        return profile;
    }

    private static ApiRequest request(String name) {
        ApiRequest request = new ApiRequest();
        request.name = name;
        request.method = "POST";
        request.url = "https://example.test/token";
        return request;
    }

    private static ApiRequest.Variable variable(String key, String value) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        return variable;
    }

    private static TokenStore.TokenEntry token(String accessToken) {
        TokenStore.TokenEntry entry = new TokenStore.TokenEntry();
        entry.accessToken = accessToken;
        entry.refreshToken = "refresh-" + accessToken;
        entry.tokenType = "Bearer";
        entry.scope = "api.read";
        entry.expiresAt = System.currentTimeMillis() + 3600_000L;
        return entry;
    }

    private static Map<String, String> invokePopulateExistingVars(ImporterPanel panel,
                                                                  ApiCollection collection,
                                                                  ApiRequest request,
                                                                  EnvironmentProfile active) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(
                "buildOAuth2PopulateExistingVars",
                ApiCollection.class,
                ApiRequest.class,
                EnvironmentProfile.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> existing = (Map<String, String>) method.invoke(panel, collection, request, active);
        return existing;
    }

    private static void invokePrivate(ImporterPanel panel, String methodName, Class<?>[] parameterTypes, Object arg) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        method.invoke(panel, arg);
    }

    private static void invokePrivate(ImporterPanel panel, String methodName, Class<?>[] parameterTypes, Object arg1, Object arg2) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        method.invoke(panel, arg1, arg2);
    }

    private static void invokePrivate(ImporterPanel panel, String methodName, Class<?>[] parameterTypes, Object arg1, Object arg2, Object arg3) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        method.invoke(panel, arg1, arg2, arg3);
    }

    private static void invokePrivate(ImporterPanel panel, String methodName) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(panel);
    }

    private static void clearTokens(ImporterPanel panel) throws Exception {
        OAuth2Panel oauth2Panel = oauth2Panel(panel);
        JButton clearBtn = (JButton) privateField(oauth2Panel, "clearBtn");
        SwingUtilities.invokeAndWait(clearBtn::doClick);
        drainEdt();
    }

    private static OAuth2Panel oauth2Panel(ImporterPanel panel) throws Exception {
        return (OAuth2Panel) privateField(panel, "oauth2Panel");
    }

    private static Object privateField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object privateFieldUnchecked(Object target, String name) {
        try {
            return privateField(target, name);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object invokePrivateReturning(ImporterPanel panel, String methodName) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(panel);
    }

    private static void drainEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static void waitForStatusText(OAuth2Panel panel, String expected) throws Exception {
        JTextArea statusArea = (JTextArea) privateField(panel, "statusArea");
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            drainEdt();
            if (statusArea.getText().contains(expected)) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Timed out waiting for status text: " + expected + "\nCurrent text:\n" + statusArea.getText());
    }
}
