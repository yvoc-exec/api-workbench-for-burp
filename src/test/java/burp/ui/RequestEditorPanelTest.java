package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspaceState;
import burp.parser.VariableResolver;
import burp.utils.RequestBuilder;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestEditorPanelTest {

    @Test
    void newPanelExposesBlankStarterRows() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();

        assertThat(paramsModel(panel).getRowCount()).isEqualTo(1);
        assertThat(headersModel(panel).getRowCount()).isEqualTo(1);
        assertThat(bodyFormModel(panel).getRowCount()).isEqualTo(1);
        assertThat(headersModel(panel).getColumnCount()).isEqualTo(2);
    }

    @Test
    void loadingRequestMaterializesEditorOwnedHeadersOnce() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));
        panel.loadRequest(minimalRequest());

        DefaultTableModel model = headersModel(panel);
        assertThat(model.getRowCount()).isEqualTo(4);
        assertThat(headerValues(model))
                .containsEntry("Accept", "application/json, text/plain, */*")
                .containsEntry("User-Agent", "BurpExtensionRuntime")
                .containsEntry("Cache-Control", "no-cache")
                .doesNotContainKey("Host");
        assertThat(model.getValueAt(3, 0)).isEqualTo("");
    }

    @Test
    void autoCompatibleRequestDefaultHeadersCanBeEditedRemovedAndReloaded() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        req.editorMaterialized = false;

        panel.loadRequest(req);

        DefaultTableModel model = headersModel(panel);
        assertThat(headerValues(model))
                .containsEntry("Accept", "application/json, text/plain, */*")
                .containsEntry("User-Agent", "BurpExtensionRuntime")
                .containsEntry("Cache-Control", "no-cache");

        for (int i = 0; i < model.getRowCount(); i++) {
            String key = (String) model.getValueAt(i, 0);
            if ("User-Agent".equalsIgnoreCase(key)) {
                model.setValueAt("MyCustomAgent/1.0", i, 1);
                break;
            }
        }
        removeHeaderRow(panel, "Accept");

        ApiRequest built = panel.buildRequestFromUI();
        assertThat(built.headers)
                .anySatisfy(header -> {
                    assertThat(header.key).isEqualTo("User-Agent");
                    assertThat(header.value).isEqualTo("MyCustomAgent/1.0");
                });
        assertThat(built.headers).extracting(h -> h.key).doesNotContain("Accept");
        assertThat(built.suppressedAutoHeaders).contains("accept");

        panel.loadRequest(built);
        assertThat(headerValues(headersModel(panel)))
                .containsEntry("User-Agent", "MyCustomAgent/1.0")
                .doesNotContainKey("Accept");
    }

    @Test
    void loadingRequestMaterializesAuthAndBodyHeadersButNotTransportHeaders() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiCollection collection = new ApiCollection();
        collection.environment.put("token", "abc123");

        ApiRequest req = minimalRequest();
        req.method = "POST";
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "{{token}}");
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "{}";

        panel.setCurrentCollection(collection);
        panel.loadRequest(req);

        assertThat(headerValues(headersModel(panel)))
                .containsEntry("Authorization", "Bearer {{token}}")
                .containsEntry("Content-Type", "application/json")
                .doesNotContainKey("Host")
                .doesNotContainKey("Content-Length");
        assertThat(resolvedView(panel)).contains("Authorization: Bearer abc123");
    }

    @Test
    void oauth2BackedAuthorizationShowsVariableReferenceInEditorAndResolvedTokenInMirror() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiCollection collection = new ApiCollection();
        collection.runtimeOAuth2.put("oauth2_access_token", "live-oauth-token");

        ApiRequest req = minimalRequest();
        req.auth = new ApiRequest.Auth();
        req.auth.type = "oauth2";
        req.auth.properties.put("accessToken", "{{oauth2_access_token}}");

        panel.setCurrentCollection(collection);
        panel.loadRequest(req);

        assertThat(headerValues(headersModel(panel)))
                .containsEntry("Authorization", "Bearer {{oauth2_access_token}}");
        assertThat(resolvedView(panel)).contains("Authorization: Bearer live-oauth-token");
    }

    @Test
    void runtimeVariableChangesKeepDerivedAuthorizationHeaderOnVariableReference() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiCollection collection = new ApiCollection();
        collection.runtimeVars.put("token", "first-secret");

        ApiRequest req = minimalRequest();
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "{{token}}");

        panel.setCurrentCollection(collection);
        panel.loadRequest(req);
        panel.setRuntimeVariables(Map.of("token", "second-secret"));

        assertThat(headerValues(headersModel(panel)))
                .containsEntry("Authorization", "Bearer {{token}}");
        assertThat(resolvedView(panel)).contains("Authorization: Bearer second-secret");
    }

    @Test
    void requestEditorResolvedMirrorUsesRuntimeResolverFactoryOverlay() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiCollection collection = new ApiCollection();
        collection.runtimeVars.put("token", "saved-secret");

        ApiRequest req = minimalRequest();
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "{{token}}");

        panel.setCurrentCollection(collection);
        panel.loadRequest(req);
        panel.setRuntimeVariables(Map.of("token", "draft-secret"));

        assertThat(resolvedView(panel)).contains("Authorization: Bearer draft-secret");
    }

    @Test
    void resolvedMirrorShowsAutoCompatibleBuildMode() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;

        panel.loadRequest(req);

        assertThat(resolvedView(panel))
                .contains("Request Build Policy")
                .contains("mode=AUTO_COMPATIBLE")
                .contains("suppressedAutoHeaders=(none)")
                .contains("Auto-compatible mode may synthesize defaults/auth/body Content-Type.");
    }

    @Test
    void resolvedMirrorShowsManualPreserveBuildModeAndSuppressedHeaders() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        req.editorMaterialized = true;
        req.suppressedAutoHeaders.add("content-type");
        req.suppressedAutoHeaders.add("authorization");

        panel.loadRequest(req);

        assertThat(resolvedView(panel))
                .contains("Request Build Policy")
                .contains("mode=MANUAL_PRESERVE")
                .contains("suppressedAutoHeaders=authorization, content-type")
                .contains("Manual preserve mode keeps tester-deleted auto headers deleted.");
    }

    @Test
    void resolvedMirrorDoesNotLeakHeaderValuesInSuppressedHeaderDiagnostics() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        req.editorMaterialized = true;
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "secret-token");
        req.suppressedAutoHeaders.add("authorization");

        panel.loadRequest(req);

        String resolved = resolvedView(panel);
        int start = resolved.indexOf("Request Build Policy");
        int end = resolved.indexOf("Resolved Auth");
        String policySection = start >= 0 && end > start ? resolved.substring(start, end) : resolved;
        assertThat(policySection).contains("suppressedAutoHeaders=authorization");
        assertThat(policySection).doesNotContain("secret-token");
    }

    @Test
    void variablePopupLabelMatchesActualActiveEnvironmentEditTarget() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setVariableActionBridge(testVariableBridge(true));

        RequestEditorPanel.VariableHoverInfo info = resolvedHoverInfo("token", "runtime overlay", "Runtime Overlay", "Dev", "abc123");
        JPopupMenu popup = invokePopup(panel, info);

        JButton editButton = findButton((Container) popup.getComponent(0), "Edit in Active Env");
        assertThat(editButton).isNotNull();
        assertThat(editButton.isEnabled()).isTrue();
        assertThat(findLabel((Container) popup.getComponent(0), "Status: Resolved")).isNotNull();
        assertThat(findLabel((Container) popup.getComponent(0), "Scope: runtime overlay")).isNotNull();
        assertThat(findLabel((Container) popup.getComponent(0), "Source: Runtime Overlay")).isNotNull();
        assertThat(findLabel((Container) popup.getComponent(0), "Source: unresolved")).isNull();
    }

    @Test
    void variablePopupConfirmationControlsMutations() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        AtomicInteger updates = new AtomicInteger();
        AtomicReference<String> lastKey = new AtomicReference<>();
        AtomicReference<String> lastValue = new AtomicReference<>();
        panel.setVariableActionBridge(new RequestEditorPanel.VariableActionBridge() {
            @Override
            public RequestEditorPanel.VariableHoverInfo inspect(String key) {
                return null;
            }

            @Override
            public boolean hasActiveEnvironment() {
                return true;
            }

            @Override
            public String activeEnvironmentName() {
                return "Dev";
            }

            @Override
            public boolean updateActiveEnvironment(String key, String value, boolean createIfMissing, boolean persist) {
                updates.incrementAndGet();
                lastKey.set(key);
                lastValue.set(value);
                return true;
            }

            @Override
            public void refreshEnvironmentUi() {
            }
        });
        panel.setVariableDialogProvider(new RequestEditorPanel.VariableDialogProvider() {
            @Override
            public String prompt(Component parent, String title, String message, String initialValue) {
                return "new-value";
            }

            @Override
            public boolean confirm(Component parent, String title, String message) {
                return false;
            }

            @Override
            public void info(Component parent, String title, String message) {
            }
        });

        RequestEditorPanel.VariableHoverInfo editInfo = resolvedHoverInfo("token", "active environment", "Active Environment", "Dev", "old-value");
        boolean editApplied = invokePromptAndApply(panel, "promptAndApplyVariableEdit", editInfo);
        assertThat(editApplied).isFalse();
        assertThat(updates.get()).isZero();

        RequestEditorPanel.VariableHoverInfo createInfo = unresolvedHoverInfo("missing", "Dev");
        boolean createApplied = invokePromptAndApply(panel, "promptAndApplyVariableCreate", createInfo);
        assertThat(createApplied).isFalse();
        assertThat(updates.get()).isZero();
        assertThat(lastKey.get()).isNull();
        assertThat(lastValue.get()).isNull();

        panel.setVariableDialogProvider(new RequestEditorPanel.VariableDialogProvider() {
            @Override
            public String prompt(Component parent, String title, String message, String initialValue) {
                return "confirmed-value";
            }

            @Override
            public boolean confirm(Component parent, String title, String message) {
                return true;
            }

            @Override
            public void info(Component parent, String title, String message) {
            }
        });

        assertThat(invokePromptAndApply(panel, "promptAndApplyVariableEdit", editInfo)).isTrue();
        assertThat(invokePromptAndApply(panel, "promptAndApplyVariableCreate", createInfo)).isTrue();
        assertThat(updates.get()).isEqualTo(2);
        assertThat(lastKey.get()).isEqualTo("missing");
        assertThat(lastValue.get()).isEqualTo("confirmed-value");
    }

    @Test
    void variablePopupHoverAndCopyDoNotDirtyTheEditor() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setVariableActionBridge(testVariableBridge(true));
        panel.markClean();
        RequestEditorPanel.VariableHoverInfo info = resolvedHoverInfo("token", "runtime overlay", "Runtime Overlay", "Dev", "abc123");

        assertThat(panel.isDirty()).isFalse();
        JPopupMenu popup = invokePopup(panel, info);
        assertThat(panel.isDirty()).isFalse();

        JButton copyButton = findButton((Container) popup.getComponent(0), "Copy Value");
        assertThat(copyButton).isNotNull();
        copyButton.doClick();
        assertThat(panel.isDirty()).isFalse();
    }

    @Test
    void variableHoverSupportDoesNotInstallNativeTooltip() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        JTextPane field = new JTextPane();
        field.setText("{{token}}");

        Method install = RequestEditorPanel.class.getDeclaredMethod("installVariableHoverSupport", javax.swing.text.JTextComponent.class);
        install.setAccessible(true);
        install.invoke(panel, field);

        assertThat(field.getToolTipText()).isNull();
        assertThat(field.getClientProperty("awb.variable.hover.installed")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void variablePopupTracksMouseOnPopupContent() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        JTextPane field = new JTextPane();
        field.setText("{{token}}");
        field.setSize(240, 28);
        panel.setVariableActionBridge(testVariableBridge(true));

        Method install = RequestEditorPanel.class.getDeclaredMethod("installVariableHoverSupport", javax.swing.text.JTextComponent.class);
        install.setAccessible(true);
        install.invoke(panel, field);

        Field mapField = RequestEditorPanel.class.getDeclaredField("variableHoverSupports");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<JTextComponent, ?> supportMap = (Map<JTextComponent, ?>) mapField.get(panel);
        Object support = supportMap.get(field);
        assertThat(support).isNotNull();

        RequestEditorPanel.VariableHoverInfo info = resolvedHoverInfo("token", "active environment", "Active Environment", "Dev", "abc123");
        Method show = RequestEditorPanel.class.getDeclaredMethod("showVariablePopup", javax.swing.text.JTextComponent.class, RequestEditorPanel.VariableHoverInfo.class, java.awt.Point.class, support.getClass());
        show.setAccessible(true);
        show.invoke(panel, field, info, new java.awt.Point(8, 8), support);

        JPopupMenu popup = (JPopupMenu) field.getClientProperty("awb.variable.popup");
        assertThat(popup).isNotNull();
        assertThat(((Container) popup.getComponent(0)).getMouseListeners()).isNotEmpty();
    }

    @Test
    void variablePopupWithoutActiveEnvironmentDisablesMutationActions() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setVariableActionBridge(testVariableBridge(false));

        RequestEditorPanel.VariableHoverInfo info = unresolvedHoverInfo("missing", null);
        JPopupMenu popup = invokePopup(panel, info);

        JButton editButton = findButton((Container) popup.getComponent(0), "Create in Active Env");
        assertThat(editButton).isNotNull();
        assertThat(editButton.isEnabled()).isFalse();
        assertThat(editButton.getToolTipText()).contains("No Active Environment selected");
        assertThat(findLabel((Container) popup.getComponent(0), "Source: No Active Environment selected")).isNotNull();
        assertThat(findLabel((Container) popup.getComponent(0), "Editable: No Active Environment selected")).isNotNull();
        assertThat(findLabel((Container) popup.getComponent(0), "No Active Environment selected. Select or import an environment"))
                .isNotNull();
    }

    @Test
    void buildRequestFromUiPersistsMaterializedHeadersAndMarksRequestEditorOwned() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));
        panel.loadRequest(minimalRequest());

        ApiRequest built = panel.buildRequestFromUI();

        assertThat(built.editorMaterialized).isTrue();
        assertThat(built.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(built.headers)
                .extracting(h -> h.key)
                .containsExactly("Accept", "User-Agent", "Cache-Control");
        assertThat(built.suppressedAutoHeaders).isEmpty();
    }

    @Test
    void deletingMaterializedHeaderKeepsItGoneEvenWhenAuthStillExists() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "tok123");
        panel.loadRequest(req);

        removeHeaderRow(panel, "Authorization");

        ApiRequest built = panel.buildRequestFromUI();
        assertThat(built.headers).extracting(h -> h.key).doesNotContain("Authorization");
        assertThat(built.suppressedAutoHeaders).contains("authorization");

        byte[] raw = new RequestBuilder(null).buildRequest(built, new VariableResolver());
        String text = new String(raw, StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("Authorization: Bearer tok123");
    }

    @Test
    void deletingMaterializedContentTypeAddsSuppressedAutoHeader() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.method = "POST";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "{\"a\":1}";
        panel.loadRequest(req);

        removeHeaderRow(panel, "Content-Type");

        ApiRequest built = panel.buildRequestFromUI();

        assertThat(built.headers).extracting(h -> h.key).doesNotContain("Content-Type");
        assertThat(built.suppressedAutoHeaders).contains("content-type");
    }

    @Test
    void deletingAuthorizationNotifiesTrackedHeaderStateListener() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "tok123");
        panel.loadRequest(req);

        AtomicInteger notifications = new AtomicInteger();
        panel.setTrackedHeaderStateChangeListener(notifications::incrementAndGet);

        removeHeaderRow(panel, "Authorization");

        assertThat(notifications.get()).isGreaterThan(0);
    }

    @Test
    void loadRequestDoesNotShowSuppressedAuthorizationHeader() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "tok123");
        req.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        req.editorMaterialized = true;
        req.suppressedAutoHeaders.add("authorization");
        req.headers.add(new ApiRequest.Header("Authorization", "Bearer stale-token"));
        req.headers.add(new ApiRequest.Header("X-Custom", "keep-me"));

        panel.loadRequest(req);

        assertThat(headerValues(headersModel(panel))).doesNotContainKey("Authorization");
        assertThat(headerValues(headersModel(panel))).containsEntry("X-Custom", "keep-me");
        assertThat(resolvedView(panel))
                .contains("mode=MANUAL_PRESERVE")
                .contains("suppressedAutoHeaders=authorization");
    }

    @Test
    void reAddingAuthorizationClearsSuppressedAutoHeader() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "tok123");
        req.suppressedAutoHeaders.add("authorization");
        panel.loadRequest(req);

        assertThat(headerValues(headersModel(panel))).doesNotContainKey("Authorization");
        headersModel(panel).addRow(new Object[]{"Authorization", "Bearer tok123"});

        ApiRequest built = panel.buildRequestFromUI();

        assertThat(built.headers)
                .anySatisfy(header -> {
                    assertThat(header.key).isEqualTo("Authorization");
                    assertThat(header.value).isEqualTo("Bearer tok123");
                });
        assertThat(built.suppressedAutoHeaders).doesNotContain("authorization");
    }

    @Test
    void workspaceRoundTripDeletedAuthorizationDoesNotRestoreHeader() throws Exception {
        ApiCollection collection = new ApiCollection();
        collection.name = "AuthTest";

        ApiRequest req = minimalRequest();
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "tok123");
        req.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        req.editorMaterialized = true;
        req.suppressedAutoHeaders.add("authorization");
        collection.requests.add(req);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        String json = WorkspaceStateJson.toJson(state);
        WorkspaceState restored = WorkspaceStateJson.fromJson(json);
        ApiRequest roundTripped = restored.collections.get(0).requests.get(0);

        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));
        panel.loadRequest(roundTripped);

        assertThat(headerValues(headersModel(panel))).doesNotContainKey("Authorization");
        assertThat(resolvedView(panel))
                .contains("mode=MANUAL_PRESERVE")
                .contains("suppressedAutoHeaders=authorization");
    }

    @Test
    void buildRequestFromUiSetsManualPreserveBuildMode() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));
        panel.loadRequest(minimalRequest());

        ApiRequest built = panel.buildRequestFromUI();

        assertThat(built.editorMaterialized).isTrue();
        assertThat(built.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
    }

    @Test
    void buildRequestFromUiStillResolvesBearerVariableBackedAuthorizationAtExecutionTime() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiCollection collection = new ApiCollection();
        collection.runtimeVars.put("token", "live-secret");

        ApiRequest req = minimalRequest();
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "{{token}}");

        panel.setCurrentCollection(collection);
        panel.loadRequest(req);

        ApiRequest built = panel.buildRequestFromUI();
        VariableResolver resolver = new VariableResolver();
        resolver.addAll(Map.of("token", "live-secret"));
        byte[] raw = new RequestBuilder(null).buildRequest(built, resolver);
        String text = new String(raw, StandardCharsets.UTF_8);

        assertThat(text).contains("Authorization: Bearer live-secret");
        assertThat(headerValues(headersModel(panel))).containsEntry("Authorization", "Bearer {{token}}");
    }

    @Test
    void runtimeVariableUpdatesDoNotRewriteHeaderTableAfterLoad() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.headers = List.of(new ApiRequest.Header("X-Token", "{{token}}", false));
        panel.loadRequest(req);

        panel.setRuntimeVariables(Map.of("token", "live123"));

        DefaultTableModel model = headersModel(panel);
        assertThat(headerValues(model)).containsEntry("X-Token", "{{token}}");
        assertThat(resolvedView(panel)).contains("X-Token: live123");
    }

    @Test
    void changingAuthTypeMaterializesAuthorizationHeaderInEditorAndBuiltRequest() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));
        panel.loadRequest(minimalRequest());

        authTypeBox(panel).setSelectedItem("bearer");
        authField(panel, "token").setText("mytoken");

        assertThat(headerValues(headersModel(panel))).containsEntry("Authorization", "Bearer mytoken");

        ApiRequest built = panel.buildRequestFromUI();
        assertThat(built.headers)
                .anySatisfy(header -> {
                    assertThat(header.key).isEqualTo("Authorization");
                    assertThat(header.value).isEqualTo("Bearer mytoken");
                });
    }

    @Test
    void urlEditsStillDriveHostAtSendTimeWithoutShowingHostRow() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.method = "POST";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "hello";
        panel.loadRequest(req);

        urlField(panel).setText("https://other.example.test/path");

        ApiRequest built = panel.buildRequestFromUI();
        assertThat(built.headers).extracting(h -> h.key).doesNotContain("Host", "Content-Length");

        byte[] raw = new RequestBuilder(null).buildRequest(built, new VariableResolver());
        String text = new String(raw, StandardCharsets.UTF_8);
        assertThat(text).contains("Host: other.example.test");
        assertThat(text).contains("Content-Length: 5");
    }

    @Test
    void switchingBodyModesMaterializesContentTypeAgain() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));
        panel.loadRequest(minimalRequest());

        Container bodyPanel = (Container) tabs(panel).getComponentAt(3);
        JRadioButton btn = findRadioButton(bodyPanel, "x-www-form-urlencoded");
        assertThat(btn).isNotNull();
        btn.doClick();

        DefaultTableModel formModel = bodyFormModel(panel);
        formModel.setValueAt("grant_type", 0, 0);
        formModel.setValueAt("client_credentials", 0, 1);

        assertThat(headerValues(headersModel(panel))).containsEntry("Content-Type", "application/x-www-form-urlencoded");

        ApiRequest built = panel.buildRequestFromUI();
        assertThat(built.headers)
                .anySatisfy(header -> {
                    assertThat(header.key).isEqualTo("Content-Type");
                    assertThat(header.value).isEqualTo("application/x-www-form-urlencoded");
                });
    }

    private static ApiRequest minimalRequest() {
        ApiRequest req = new ApiRequest();
        req.name = "Test";
        req.method = "GET";
        req.url = "https://api.example.test";
        return req;
    }

    private static DefaultTableModel paramsModel(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("paramsModel");
        f.setAccessible(true);
        return (DefaultTableModel) f.get(panel);
    }

    private static DefaultTableModel headersModel(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("headersModel");
        f.setAccessible(true);
        return (DefaultTableModel) f.get(panel);
    }

    private static DefaultTableModel bodyFormModel(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("bodyFormModel");
        f.setAccessible(true);
        return (DefaultTableModel) f.get(panel);
    }

    private static JTabbedPane tabs(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("tabs");
        f.setAccessible(true);
        return (JTabbedPane) f.get(panel);
    }

    private static JTextComponent urlField(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("urlField");
        f.setAccessible(true);
        return (JTextComponent) f.get(panel);
    }

    private static String resolvedView(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("resolvedViewArea");
        f.setAccessible(true);
        return ((JTextArea) f.get(panel)).getText();
    }

    private static void removeHeaderRow(RequestEditorPanel panel, String key) throws Exception {
        JTable table = headersTable(panel);
        DefaultTableModel model = headersModel(panel);
        for (int i = 0; i < model.getRowCount(); i++) {
            if (key.equalsIgnoreCase((String) model.getValueAt(i, 0))) {
                table.setRowSelectionInterval(i, i);
                JButton delBtn = findButton((Container) tabs(panel).getComponentAt(2), "-");
                assertThat(delBtn).isNotNull();
                delBtn.doClick();
                return;
            }
        }
    }

    private static JTable headersTable(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("headersTable");
        f.setAccessible(true);
        return (JTable) f.get(panel);
    }

    private static JComboBox<String> authTypeBox(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("authTypeBox");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        JComboBox<String> combo = (JComboBox<String>) f.get(panel);
        return combo;
    }

    private static JTextField authField(RequestEditorPanel panel, String name) throws Exception {
        Field authUiField = RequestEditorPanel.class.getDeclaredField("authUi");
        authUiField.setAccessible(true);
        Object authUi = authUiField.get(panel);
        Field authFieldsField = authUi.getClass().getDeclaredField("authFields");
        authFieldsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, JTextField> authFields = (Map<String, JTextField>) authFieldsField.get(authUi);
        return authFields.get(name);
    }

    private static Map<String, String> headerValues(DefaultTableModel model) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            String key = (String) model.getValueAt(i, 0);
            String value = (String) model.getValueAt(i, 1);
            if (key != null && !key.isBlank()) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static JButton findButton(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton && text.equals(((JButton) c).getText())) {
                return (JButton) c;
            }
            if (c instanceof Container) {
                JButton found = findButton((Container) c, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JRadioButton findRadioButton(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JRadioButton && text.equals(((JRadioButton) c).getText())) {
                return (JRadioButton) c;
            }
            if (c instanceof Container) {
                JRadioButton found = findRadioButton((Container) c, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JLabel findLabel(Container root, String textFragment) {
        for (Component c : root.getComponents()) {
            if (c instanceof JLabel label && label.getText() != null && label.getText().contains(textFragment)) {
                return label;
            }
            if (c instanceof Container) {
                JLabel found = findLabel((Container) c, textFragment);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static RequestEditorPanel.VariableActionBridge testVariableBridge(boolean hasActiveEnvironment) {
        return new RequestEditorPanel.VariableActionBridge() {
            @Override
            public RequestEditorPanel.VariableHoverInfo inspect(String key) {
                return null;
            }

            @Override
            public boolean hasActiveEnvironment() {
                return hasActiveEnvironment;
            }

            @Override
            public String activeEnvironmentName() {
                return hasActiveEnvironment ? "Dev" : null;
            }

            @Override
            public boolean updateActiveEnvironment(String key, String value, boolean createIfMissing, boolean persist) {
                return hasActiveEnvironment;
            }

            @Override
            public void refreshEnvironmentUi() {
            }
        };
    }

    private static RequestEditorPanel.VariableHoverInfo resolvedHoverInfo(String key,
                                                                         String scope,
                                                                         String source,
                                                                         String envName,
                                                                         String value) {
        RequestEditorPanel.VariableHoverInfo info = new RequestEditorPanel.VariableHoverInfo();
        info.key = key;
        info.resolved = true;
        info.value = value;
        info.scope = scope;
        info.source = source;
        info.activeEnvironmentName = envName;
        info.canEdit = true;
        info.canCreate = true;
        info.message = "Resolved from " + source + ". Edit target: Active Environment (persisted variable).";
        return info;
    }

    private static RequestEditorPanel.VariableHoverInfo unresolvedHoverInfo(String key, String envName) {
        RequestEditorPanel.VariableHoverInfo info = new RequestEditorPanel.VariableHoverInfo();
        info.key = key;
        info.resolved = false;
        info.value = null;
        info.scope = "not found";
        info.source = envName != null ? "Active Environment" : "No Active Environment selected";
        info.activeEnvironmentName = envName;
        info.canEdit = envName != null;
        info.canCreate = envName != null;
        info.message = envName != null
                ? "No value found. Create target: Active Environment (persisted variable)."
                : "No Active Environment selected. Select or import an environment to edit or create persisted variables.";
        return info;
    }

    private static JPopupMenu invokePopup(RequestEditorPanel panel, RequestEditorPanel.VariableHoverInfo info) throws Exception {
        Method method = RequestEditorPanel.class.getDeclaredMethod("buildVariablePopup", javax.swing.text.JTextComponent.class, RequestEditorPanel.VariableHoverInfo.class);
        method.setAccessible(true);
        JTextField field = new JTextField("{{" + info.key + "}}");
        return (JPopupMenu) method.invoke(panel, field, info);
    }

    private static boolean invokePromptAndApply(RequestEditorPanel panel, String methodName, RequestEditorPanel.VariableHoverInfo info) throws Exception {
        Method method = RequestEditorPanel.class.getDeclaredMethod(methodName, RequestEditorPanel.VariableHoverInfo.class);
        method.setAccessible(true);
        return (boolean) method.invoke(panel, info);
    }
}
