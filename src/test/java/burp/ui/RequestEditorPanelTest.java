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
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestEditorPanelTest {

    @Test
    void newPanelExposesBlankStarterRows() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();

        assertThat(paramsModel(panel).getRowCount()).isEqualTo(1);
        assertThat(headersModel(panel).getRowCount()).isEqualTo(1);
        assertThat(bodyFormModel(panel).getRowCount()).isEqualTo(1);
        assertThat(headersModel(panel).getColumnCount()).isEqualTo(3);
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
                .contains("mode=MANUAL_PRESERVE")
                .contains("suppressedAutoHeaders=(none)")
                .contains("Manual preserve mode keeps tester-deleted auto headers deleted.");
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
    void normalizeHoverInfoFillsResolvedAndUnresolvedDefaultsFromCurrentContext() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setVariableActionBridge(testVariableBridge(true));

        RequestEditorPanel.VariableHoverInfo resolvedInfo = new RequestEditorPanel.VariableHoverInfo();
        VariableTokenScanner.VariableToken resolvedToken = VariableTokenScanner.tokenAt("{{token}}", 2, Map.of("token", "abc123"));
        invokeHidden(panel, "normalizeHoverInfo",
                new Class[]{RequestEditorPanel.VariableHoverInfo.class, VariableTokenScanner.VariableToken.class},
                resolvedInfo, resolvedToken);

        assertThat(resolvedInfo.key).isEqualTo("token");
        assertThat(resolvedInfo.resolved).isTrue();
        assertThat(resolvedInfo.value).isEqualTo("abc123");
        assertThat(resolvedInfo.scope).isEqualTo("active environment");
        assertThat(resolvedInfo.source).isEqualTo("Active Environment");
        assertThat(resolvedInfo.activeEnvironmentName).isEqualTo("Dev");
        assertThat(resolvedInfo.message).contains("Resolved from Active Environment");
        assertThat(resolvedInfo.canEdit).isTrue();
        assertThat(resolvedInfo.canCreate).isFalse();

        panel.setVariableActionBridge(testVariableBridge(false));
        RequestEditorPanel.VariableHoverInfo unresolvedInfo = new RequestEditorPanel.VariableHoverInfo();
        VariableTokenScanner.VariableToken unresolvedToken = VariableTokenScanner.tokenAt("{{missing}}", 2, Map.of());
        invokeHidden(panel, "normalizeHoverInfo",
                new Class[]{RequestEditorPanel.VariableHoverInfo.class, VariableTokenScanner.VariableToken.class},
                unresolvedInfo, unresolvedToken);

        assertThat(unresolvedInfo.key).isEqualTo("missing");
        assertThat(unresolvedInfo.resolved).isFalse();
        assertThat(unresolvedInfo.scope).isEqualTo("not found");
        assertThat(unresolvedInfo.source).isEqualTo("unresolved");
        assertThat(unresolvedInfo.message).contains("No Active Environment selected");
        assertThat(unresolvedInfo.canEdit).isFalse();
        assertThat(unresolvedInfo.canCreate).isFalse();
    }

    @Test
    void promptAndApplyVariableMutationWithoutActiveEnvironmentShowsInfoDialog() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setVariableActionBridge(testVariableBridge(false));
        AtomicReference<String> titleRef = new AtomicReference<>();
        AtomicReference<String> messageRef = new AtomicReference<>();
        AtomicInteger promptCalls = new AtomicInteger();
        AtomicInteger confirmCalls = new AtomicInteger();
        panel.setVariableDialogProvider(new RequestEditorPanel.VariableDialogProvider() {
            @Override
            public String prompt(Component parent, String title, String message, String initialValue) {
                promptCalls.incrementAndGet();
                return "unused";
            }

            @Override
            public boolean confirm(Component parent, String title, String message) {
                confirmCalls.incrementAndGet();
                return true;
            }

            @Override
            public void info(Component parent, String title, String message) {
                titleRef.set(title);
                messageRef.set(message);
            }
        });

        boolean applied = invokePromptAndApply(panel, "promptAndApplyVariableCreate", unresolvedHoverInfo("missing", null));

        assertThat(applied).isFalse();
        assertThat(promptCalls.get()).isZero();
        assertThat(confirmCalls.get()).isZero();
        assertThat(titleRef.get()).isEqualTo("Variable Editor");
        assertThat(messageRef.get()).contains("No Active Environment selected");
    }

    @Test
    void headerVariableTooltipAndRendererReflectResolvedUnresolvedAndDisabledStates() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));
        RequestEditorPanel.VariableActionBridge bridge = new RequestEditorPanel.VariableActionBridge() {
            @Override
            public RequestEditorPanel.VariableHoverInfo inspect(String key) {
                if (Objects.equals(key, "api_key")) {
                    RequestEditorPanel.VariableHoverInfo info = resolvedHoverInfo("api_key", "", "", "Dev", "live-key");
                    info.source = "";
                    info.scope = "";
                    info.shadowedSource = "Collection";
                    info.shadowedValue = "collection-key";
                    return info;
                }
                if (Objects.equals(key, "disabled_value")) {
                    RequestEditorPanel.VariableHoverInfo info = resolvedHoverInfo("disabled_value", "", "", "Dev", "disabled-live");
                    info.source = "";
                    info.scope = "";
                    return info;
                }
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
                return true;
            }

            @Override
            public void refreshEnvironmentUi() {
            }
        };
        panel.setVariableActionBridge(bridge);

        ApiCollection collection = new ApiCollection();
        collection.environment.put("api_key", "live-key");

        ApiRequest request = minimalRequest();
        request.headers = List.of(
                new ApiRequest.Header("X-Api-Key", "{{api_key}}", false),
                new ApiRequest.Header("X-User", "{{user_id}}", false)
        );

        panel.setCurrentCollection(collection);
        panel.loadRequest(request);

        JTable table = headersTable(panel);
        Rectangle resolvedBounds = panel.getHeaderVariableCellBoundsForTests("api_key");
        assertThat(resolvedBounds).isNotNull();

        invokeHidden(panel, "updateHeaderTooltip", new Class[]{MouseEvent.class},
                mouseMoved(table, center(resolvedBounds)));
        assertThat(table.getToolTipText())
                .contains("{{api_key}} - Resolved")
                .contains("from Active Environment")
                .contains("= live-key")
                .contains("shadowed Collection");

        int resolvedRow = findHeaderRow(table, "X-Api-Key");
        int unresolvedRow = findHeaderRow(table, "X-User");
        java.awt.Color resolvedColor = ((JLabel) table.getCellRenderer(resolvedRow, 1)
                .getTableCellRendererComponent(table, table.getValueAt(resolvedRow, 1), false, false, resolvedRow, 1))
                .getForeground();
        java.awt.Color unresolvedColor = ((JLabel) table.getCellRenderer(unresolvedRow, 1)
                .getTableCellRendererComponent(table, table.getValueAt(unresolvedRow, 1), false, false, unresolvedRow, 1))
                .getForeground();

        assertThat(resolvedColor).isEqualTo(VariableStatusColors.resolved(table));
        assertThat(unresolvedColor).isEqualTo(VariableStatusColors.unresolved(table));
        assertThat(resolvedColor).isNotEqualTo(unresolvedColor);

        JLabel selectedResolvedLabel = (JLabel) table.getCellRenderer(resolvedRow, 1)
                .getTableCellRendererComponent(table, table.getValueAt(resolvedRow, 1), true, false, resolvedRow, 1);
        assertThat(selectedResolvedLabel.getForeground()).isEqualTo(table.getSelectionForeground());

        RequestEditorPanel disabledPanel = new RequestEditorPanel();
        disabledPanel.setRequestBuilder(new RequestBuilder(null));
        disabledPanel.setVariableActionBridge(bridge);
        ApiCollection disabledCollection = new ApiCollection();
        disabledCollection.environment.put("disabled_value", "disabled-live");
        ApiRequest disabledRequest = minimalRequest();
        disabledRequest.headers = List.of(new ApiRequest.Header("X-Disabled", "{{disabled_value}}", true));
        disabledPanel.setCurrentCollection(disabledCollection);
        disabledPanel.loadRequest(disabledRequest);

        JTable disabledTable = headersTable(disabledPanel);
        Rectangle disabledBounds = disabledPanel.getHeaderVariableCellBoundsForTests("disabled_value");
        assertThat(disabledBounds).isNotNull();
        invokeHidden(disabledPanel, "updateHeaderTooltip", new Class[]{MouseEvent.class},
                mouseMoved(disabledTable, center(disabledBounds)));
        assertThat(disabledTable.getToolTipText())
                .contains("{{disabled_value}} - Resolved")
                .contains("[Disabled header]");

        int disabledRow = findHeaderRow(disabledTable, "X-Disabled");
        JLabel disabledLabel = (JLabel) disabledTable.getCellRenderer(disabledRow, 1)
                .getTableCellRendererComponent(disabledTable, disabledTable.getValueAt(disabledRow, 1), false, false, disabledRow, 1);
        assertThat(disabledLabel.getForeground()).isEqualTo(VariableStatusColors.disabled(disabledTable));
        assertThat(disabledLabel.getForeground()).isNotEqualTo(resolvedColor);
        assertThat(disabledLabel.getForeground()).isNotEqualTo(unresolvedColor);
        assertThat(disabledLabel.getFont().isItalic()).isTrue();
    }

    @Test
    void semanticHeaderVariableColorsRemainAvailableAcrossThemeDefaults() {
        withUiColors(Map.of(
                "Actions.Green", new java.awt.Color(0x12, 0x8A, 0x30),
                "Actions.Red", new java.awt.Color(0xC4, 0x2B, 0x1C),
                "Label.disabledForeground", new java.awt.Color(0x7A, 0x7A, 0x7A),
                "Table.background", java.awt.Color.WHITE,
                "Table.foreground", java.awt.Color.BLACK
        ), () -> {
            JTable lightTable = new JTable();
            assertThat(VariableStatusColors.resolved(lightTable)).isEqualTo(new java.awt.Color(0x12, 0x8A, 0x30));
            assertThat(VariableStatusColors.unresolved(lightTable)).isEqualTo(new java.awt.Color(0xC4, 0x2B, 0x1C));
            assertThat(VariableStatusColors.disabled(lightTable)).isEqualTo(new java.awt.Color(0x7A, 0x7A, 0x7A));
        });

        Map<String, java.awt.Color> darkThemeColors = new LinkedHashMap<>();
        darkThemeColors.put("Actions.Green", null);
        darkThemeColors.put("Actions.Red", null);
        darkThemeColors.put("Label.disabledForeground", null);
        darkThemeColors.put("Label.disabledText", null);
        darkThemeColors.put("TextField.inactiveForeground", null);
        darkThemeColors.put("Table.background", new java.awt.Color(0x2B, 0x2B, 0x2B));
        darkThemeColors.put("Table.foreground", new java.awt.Color(0xE6, 0xE6, 0xE6));
        withUiColors(darkThemeColors, () -> {
            JTable darkTable = new JTable();
            java.awt.Color resolved = VariableStatusColors.resolved(darkTable);
            java.awt.Color unresolved = VariableStatusColors.unresolved(darkTable);
            java.awt.Color disabled = VariableStatusColors.disabled(darkTable);
            assertThat(resolved).isNotNull();
            assertThat(unresolved).isNotNull();
            assertThat(disabled).isNotNull();
            assertThat(resolved).isNotEqualTo(unresolved);
            assertThat(disabled).isNotEqualTo(resolved);
            assertThat(disabled).isNotEqualTo(unresolved);
            assertThat(VariableStatusColors.disabledFont(darkTable.getFont())).isNotNull();
            assertThat(VariableStatusColors.disabledFont(darkTable.getFont()).isItalic()).isTrue();
        });
    }

    @Test
    void visibleVariablePopupSeamReturnsPopupAndClearRequestHidesIt() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.loadRequest(minimalRequest());
        AtomicBoolean visible = new AtomicBoolean(true);
        JPopupMenu popup = new JPopupMenu() {
            @Override
            public boolean isVisible() {
                return visible.get();
            }

            @Override
            public void setVisible(boolean b) {
                visible.set(b);
            }
        };
        urlField(panel).putClientProperty("awb.variable.popup", popup);

        assertThat(panel.getVisibleVariablePopupForTests()).isSameAs(popup);

        panel.clearRequest();

        assertThat(visible.get()).isFalse();
        assertThat(panel.getVisibleVariablePopupForTests()).isNull();
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
    void exactHttpToggleMarksEditorDirtyAndPersistsExactBuildMode() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));
        panel.loadRequest(minimalRequest());
        panel.markClean();

        SwingUtilities.invokeAndWait(() -> panel.getExactHttpToggleForTests().doClick());

        assertThat(panel.isDirty()).isTrue();
        ApiRequest built = panel.buildRequestFromUI();
        assertThat(built.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
    }

    @Test
    void exactHttpTogglePreservesLiveObjectAndDraftEditsBothDirections() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiCollection collection = new ApiCollection();
        ApiRequest request = minimalRequest();
        request.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        request.headers = List.of(
                new ApiRequest.Header("Host", "authored.example.test", false),
                new ApiRequest.Header("Content-Length", "77", false),
                new ApiRequest.Header("Transfer-Encoding", "chunked", false)
        );
        request.auth = new ApiRequest.Auth();
        request.auth.type = "bearer";
        request.auth.properties.put("token", "initial-token");
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"initial\":true}";
        collection.requests.add(request);

        panel.setCurrentCollection(collection);
        panel.loadRequest(request);

        ApiRequest before = panel.getCurrentRequest();
        assertThat(before).isSameAs(request);
        assertThat(collection.requests.get(0)).isSameAs(request);

        JComboBox<String> methods = methodBox(panel);
        JTextComponent url = urlField(panel);
        DefaultTableModel params = paramsModel(panel);
        JComboBox<String> authTypes = authTypeBox(panel);
        JTextField authToken = authField(panel, "token");
        JTextComponent body = bodyRawArea(panel);
        JTextArea preScripts = preScriptArea(panel);
        JTextArea postScripts = postScriptArea(panel);
        DefaultTableModel headers = headersModel(panel);

        SwingUtilities.invokeAndWait(() -> {
            methods.setSelectedItem("POST");
            url.setText("https://api.example.test/users");
            params.setValueAt("active", 0, 0);
            params.setValueAt("true", 0, 1);
            authTypes.setSelectedItem("bearer");
            authToken.setText("draft-token");
            body.setText("{\"edited\":true}");
            preScripts.setText("console.log('pre');");
            postScripts.setText("console.log('post');");
        });
        SwingUtilities.invokeAndWait(() -> headers.addRow(new Object[]{"X-Draft", "yes"}));

        SwingUtilities.invokeAndWait(() -> panel.getExactHttpToggleForTests().doClick());

        assertThat(panel.getCurrentRequest()).isSameAs(before);
        assertThat(collection.requests.get(0)).isSameAs(before);
        assertThat(panel.getCurrentRequest().buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(methods.getSelectedItem()).isEqualTo("POST");
        assertThat(url.getText()).isEqualTo("https://api.example.test/users");
        assertThat(panel.getCurrentRequest().url).isEqualTo("https://api.example.test/users?active=true");
        assertThat(body.getText()).isEqualTo("{\"edited\":true}");
        assertThat(preScripts.getText()).isEqualTo("console.log('pre');");
        assertThat(postScripts.getText()).isEqualTo("console.log('post');");
        assertThat(headerValues(headers))
                .containsEntry("Host", "authored.example.test")
                .containsEntry("Content-Length", "77")
                .containsEntry("Transfer-Encoding", "chunked")
                .containsEntry("X-Draft", "yes");

        WorkspaceState roundTrip = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(WorkspaceState.fromCollections(List.of(collection))));
        ApiRequest restored = roundTrip.collections.get(0).requests.get(0);
        assertThat(restored.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(restored.method).isEqualTo("POST");
        assertThat(restored.url).isEqualTo("https://api.example.test/users?active=true");

        panel.loadRequest(request);
        assertThat(panel.getExactHttpToggleForTests()).isNotNull();
        assertThat(panel.getExactHttpToggleForTests().isSelected()).isTrue();

        SwingUtilities.invokeAndWait(() -> panel.getExactHttpToggleForTests().doClick());

        assertThat(panel.getCurrentRequest()).isSameAs(before);
        assertThat(collection.requests.get(0)).isSameAs(before);
        assertThat(panel.getCurrentRequest().buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(methods.getSelectedItem()).isEqualTo("POST");
        assertThat(url.getText()).isEqualTo("https://api.example.test/users?active=true");
        assertThat(panel.getCurrentRequest().url).isEqualTo("https://api.example.test/users?active=true");
        assertThat(body.getText()).isEqualTo("{\"edited\":true}");
        assertThat(preScripts.getText()).isEqualTo("console.log('pre');");
        assertThat(postScripts.getText()).isEqualTo("console.log('post');");
        assertThat(headerValues(headers))
                .doesNotContainKey("Host")
                .doesNotContainKey("Content-Length")
                .doesNotContainKey("Transfer-Encoding")
                .containsEntry("X-Draft", "yes");

        panel.loadRequest(request);
        assertThat(panel.getExactHttpToggleForTests().isSelected()).isFalse();
        assertThat(panel.getCurrentRequest()).isSameAs(before);
    }

    @Test
    void exactHttpToggleRestoresAuthoredTransportHeadersAndPreviewShowsExactFraming() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        req.headers = List.of(
                new ApiRequest.Header("Host", "alt.example.test", false),
                new ApiRequest.Header("Content-Length", "9999", false),
                new ApiRequest.Header("Transfer-Encoding", "chunked", false)
        );

        panel.loadRequest(req);
        assertThat(headerValues(headersModel(panel))).doesNotContainKey("Host");

        SwingUtilities.invokeAndWait(() -> panel.getExactHttpToggleForTests().doClick());

        assertThat(panel.getExactHttpToggleForTests().isSelected()).isTrue();
        assertThat(headerValues(headersModel(panel)))
                .containsEntry("Host", "alt.example.test")
                .containsEntry("Content-Length", "9999")
                .containsEntry("Transfer-Encoding", "chunked");
        assertThat(resolvedView(panel))
                .contains("mode=EXACT_HTTP")
                .contains("Host: alt.example.test")
                .contains("Content-Length: 9999")
                .contains("Transfer-Encoding: chunked");

        byte[] raw = new RequestBuilder(null).buildRequest(panel.buildRequestFromUI(), new VariableResolver());
        String rawText = new String(raw, StandardCharsets.UTF_8);
        assertThat(rawText)
                .contains("Host: alt.example.test")
                .contains("Content-Length: 9999")
                .contains("Transfer-Encoding: chunked");
    }

    @Test
    void exactHttpToggleRemovesSyntheticDefaultsThatWereNotAuthored() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        panel.loadRequest(req);
        assertThat(headerValues(headersModel(panel)))
                .containsEntry("Accept", "application/json, text/plain, */*")
                .containsEntry("User-Agent", "BurpExtensionRuntime")
                .containsEntry("Cache-Control", "no-cache");

        SwingUtilities.invokeAndWait(() -> panel.getExactHttpToggleForTests().doClick());

        assertThat(headerValues(headersModel(panel)))
                .doesNotContainKeys("Accept", "User-Agent", "Cache-Control");
    }

    @Test
    void exactHttpToggleKeepsAuthoredDefaultsAndDropsOnlyDerivedAuthHeaders() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        req.headers = List.of(
                new ApiRequest.Header("Accept", "application/xml", false),
                new ApiRequest.Header("User-Agent", "TestAgent/1.0", false),
                new ApiRequest.Header("Authorization", "Bearer authored", false),
                new ApiRequest.Header("Content-Type", "text/plain", false)
        );
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "derived-secret");
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "{\"hello\":true}";

        panel.loadRequest(req);
        assertThat(headerValues(headersModel(panel))).containsEntry("Authorization", "Bearer authored");

        SwingUtilities.invokeAndWait(() -> panel.getExactHttpToggleForTests().doClick());

        assertThat(headerValues(headersModel(panel)))
                .containsEntry("Accept", "application/xml")
                .containsEntry("User-Agent", "TestAgent/1.0")
                .containsEntry("Authorization", "Bearer authored")
                .containsEntry("Content-Type", "text/plain");
        assertThat(headerValues(headersModel(panel))).doesNotContainEntry("Authorization", "Bearer derived-secret");
    }

    @Test
    void exactHttpToggleDropsDerivedAuthorizationAndContentTypeHeaders() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "derived-secret");
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "{\"hello\":true}";

        panel.loadRequest(req);
        assertThat(headerValues(headersModel(panel)))
                .containsEntry("Authorization", "Bearer derived-secret")
                .containsEntry("Content-Type", "application/json");

        SwingUtilities.invokeAndWait(() -> panel.getExactHttpToggleForTests().doClick());

        assertThat(headerValues(headersModel(panel))).doesNotContainKeys("Authorization", "Content-Type");
    }

    @Test
    void exactHttpTogglePreservesDuplicateHeaderOrder() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        req.headers = List.of(
                new ApiRequest.Header("X-Dupe", "one", false),
                new ApiRequest.Header("X-Dupe", "two", false),
                new ApiRequest.Header("x-dupe", "three", false),
                new ApiRequest.Header("Host", "alt.example.test", false)
        );

        panel.loadRequest(req);
        SwingUtilities.invokeAndWait(() -> panel.getExactHttpToggleForTests().doClick());

        assertThat(headerNames(headersModel(panel))).containsExactly("X-Dupe", "X-Dupe", "x-dupe", "Host");
    }

    @Test
    void exactHttpToggleBackToNormalizedRestoresDefaultEditorBehavior() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        panel.loadRequest(req);
        SwingUtilities.invokeAndWait(() -> panel.getExactHttpToggleForTests().doClick());
        SwingUtilities.invokeAndWait(() -> panel.getExactHttpToggleForTests().doClick());

        assertThat(panel.getExactHttpToggleForTests().isSelected()).isFalse();
        assertThat(headerValues(headersModel(panel)))
                .containsEntry("Accept", "application/json, text/plain, */*")
                .containsEntry("User-Agent", "BurpExtensionRuntime")
                .containsEntry("Cache-Control", "no-cache")
                .doesNotContainKey("Host");
        assertThat(resolvedView(panel)).contains("mode=MANUAL_PRESERVE");
    }

    @Test
    void exactHttpTogglePreservesLatestTransportHeaderEditsAcrossHideRestoreAndWorkspaceRoundTrip() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiCollection collection = new ApiCollection();
        ApiRequest request = minimalRequest();
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.headers = new java.util.ArrayList<>(List.of(
                new ApiRequest.Header("Host", "authored.example.test", false),
                new ApiRequest.Header("Content-Length", "77", false),
                new ApiRequest.Header("Transfer-Encoding", "chunked", false),
                new ApiRequest.Header("Connection", "keep-alive", false)
        ));
        collection.requests.add(request);

        panel.setCurrentCollection(collection);
        panel.loadRequest(request);

        assertThat(panel.getExactHttpToggleForTests().isSelected()).isTrue();
        DefaultTableModel headers = headersModel(panel);

        SwingUtilities.invokeAndWait(() -> {
            headers.setValueAt("changed.example.test", 0, 1);
            headers.setValueAt("321", 1, 1);
            headers.setValueAt("gzip", 2, 1);
            headers.removeRow(3);
            headers.addRow(new Object[]{"Proxy-Connection", "keep-alive", Boolean.FALSE});
        });

        SwingUtilities.invokeAndWait(() -> panel.getExactHttpToggleForTests().doClick());

        assertThat(panel.getCurrentRequest()).isSameAs(request);
        assertThat(collection.requests.get(0)).isSameAs(request);
        assertThat(headerValues(headersModel(panel)))
                .doesNotContainKeys("Host", "Content-Length", "Transfer-Encoding")
                .containsEntry("Proxy-Connection", "keep-alive");
        assertThat(headerRows(panel.getCurrentRequest().headers))
                .containsExactly(
                        "Host=changed.example.test|false",
                        "Content-Length=321|false",
                        "Transfer-Encoding=gzip|false",
                        "Proxy-Connection=keep-alive|false"
                );

        WorkspaceState restoredState = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(WorkspaceState.fromCollections(List.of(collection))));
        ApiRequest restored = restoredState.collections.get(0).requests.get(0);
        assertThat(headerRows(restored.headers))
                .containsExactly(
                        "Host=changed.example.test|false",
                        "Content-Length=321|false",
                        "Transfer-Encoding=gzip|false",
                        "Proxy-Connection=keep-alive|false"
                );

        panel.loadRequest(request);
        assertThat(panel.getExactHttpToggleForTests().isSelected()).isFalse();

        SwingUtilities.invokeAndWait(() -> panel.getExactHttpToggleForTests().doClick());

        assertThat(headerValues(headersModel(panel)))
                .containsEntry("Host", "changed.example.test")
                .containsEntry("Content-Length", "321")
                .containsEntry("Transfer-Encoding", "gzip")
                .containsEntry("Proxy-Connection", "keep-alive");
    }

    @Test
    void exactHttpTransportDuplicatesPreserveOrderCasingAndDisabledStateAcrossModeSwitch() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest request = minimalRequest();
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.headers = new java.util.ArrayList<>(List.of(
                new ApiRequest.Header("Host", "FirstHost.example.test", false),
                new ApiRequest.Header("host", "SecondHost.example.test", true),
                new ApiRequest.Header("Connection", "close", false),
                new ApiRequest.Header("connection", "keep-alive", true),
                new ApiRequest.Header("Transfer-Encoding", "chunked", false)
        ));

        panel.loadRequest(request);
        assertThat(panel.getExactHttpToggleForTests().isSelected()).isTrue();
        assertThat(headerRows(headersModel(panel)))
                .containsExactly(
                        "Host=FirstHost.example.test|false",
                        "host=SecondHost.example.test|true",
                        "Connection=close|false",
                        "connection=keep-alive|true",
                        "Transfer-Encoding=chunked|false"
                );

        SwingUtilities.invokeAndWait(() -> panel.getExactHttpToggleForTests().doClick());
        assertThat(headerValues(headersModel(panel))).doesNotContainKeys("Host", "Transfer-Encoding");

        SwingUtilities.invokeAndWait(() -> panel.getExactHttpToggleForTests().doClick());
        assertThat(headerRows(headersModel(panel)))
                .containsExactly(
                        "Host=FirstHost.example.test|false",
                        "host=SecondHost.example.test|true",
                        "Connection=close|false",
                        "connection=keep-alive|true",
                        "Transfer-Encoding=chunked|false"
                );
    }

    @Test
    void exactHttpPolicyTextUpdatesImmediatelyAfterCheckboxSelection() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));
        panel.loadRequest(minimalRequest());

        assertThat(resolvedView(panel)).contains("mode=MANUAL_PRESERVE");
        SwingUtilities.invokeAndWait(() -> panel.getExactHttpToggleForTests().doClick());
        assertThat(resolvedView(panel)).contains("mode=EXACT_HTTP");
    }

    @Test
    void loadingExactHttpRequestRefreshesControlAndShowsAuthoredTransportHeaders() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));

        ApiRequest req = minimalRequest();
        req.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        req.headers = List.of(
                new ApiRequest.Header("Host", "alt.example.test", false),
                new ApiRequest.Header("Content-Length", "9999", false),
                new ApiRequest.Header("Transfer-Encoding", "chunked", false)
        );

        panel.loadRequest(req);

        assertThat(panel.getExactHttpToggleForTests().isSelected()).isTrue();
        assertThat(headerValues(headersModel(panel)))
                .containsEntry("Host", "alt.example.test")
                .containsEntry("Content-Length", "9999")
                .containsEntry("Transfer-Encoding", "chunked");
        assertThat(resolvedView(panel)).contains("mode=EXACT_HTTP");
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

    private static JComboBox<String> methodBox(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("methodBox");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        JComboBox<String> combo = (JComboBox<String>) f.get(panel);
        return combo;
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

    private static void withUiColors(Map<String, java.awt.Color> replacements, Runnable assertions) {
        Map<String, Object> previous = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, java.awt.Color> entry : replacements.entrySet()) {
                previous.put(entry.getKey(), UIManager.get(entry.getKey()));
                if (entry.getValue() == null) {
                    UIManager.getDefaults().remove(entry.getKey());
                } else {
                    UIManager.put(entry.getKey(), entry.getValue());
                }
            }
            assertions.run();
        } finally {
            for (Map.Entry<String, Object> entry : previous.entrySet()) {
                if (entry.getValue() == null) {
                    UIManager.getDefaults().remove(entry.getKey());
                } else {
                    UIManager.put(entry.getKey(), entry.getValue());
                }
            }
        }
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

    private static JTextComponent bodyRawArea(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("bodyRawArea");
        f.setAccessible(true);
        return (JTextComponent) f.get(panel);
    }

    private static JTextArea preScriptArea(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("preScriptArea");
        f.setAccessible(true);
        return (JTextArea) f.get(panel);
    }

    private static JTextArea postScriptArea(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("postScriptArea");
        f.setAccessible(true);
        return (JTextArea) f.get(panel);
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

    private static List<String> headerNames(DefaultTableModel model) {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            String key = (String) model.getValueAt(i, 0);
            if (key != null && !key.isBlank()) {
                out.add(key);
            }
        }
        return out;
    }

    private static List<String> headerRows(DefaultTableModel model) {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            String key = (String) model.getValueAt(i, 0);
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = (String) model.getValueAt(i, 1);
            Object disabledValue = model.getColumnCount() > 2 ? model.getValueAt(i, 2) : Boolean.FALSE;
            boolean disabled = disabledValue instanceof Boolean
                    ? (Boolean) disabledValue
                    : Boolean.parseBoolean(String.valueOf(disabledValue));
            out.add(key + "=" + (value != null ? value : "") + "|" + disabled);
        }
        return out;
    }

    private static List<String> headerRows(List<ApiRequest.Header> headers) {
        List<String> out = new java.util.ArrayList<>();
        if (headers == null) {
            return out;
        }
        for (ApiRequest.Header header : headers) {
            if (header == null || header.key == null || header.key.isBlank()) {
                continue;
            }
            out.add(header.key + "=" + (header.value != null ? header.value : "") + "|" + header.disabled);
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

    private static Point center(Rectangle rectangle) {
        return new Point(rectangle.x + Math.max(1, rectangle.width / 2),
                rectangle.y + Math.max(1, rectangle.height / 2));
    }

    private static int findHeaderRow(JTable table, String headerName) {
        for (int row = 0; row < table.getRowCount(); row++) {
            Object value = table.getValueAt(row, 0);
            if (headerName.equals(value)) {
                return row;
            }
        }
        throw new AssertionError("Header row not found: " + headerName);
    }

    private static MouseEvent mouseMoved(Component component, Point point) {
        return new MouseEvent(component,
                MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(),
                0,
                point.x,
                point.y,
                0,
                false,
                MouseEvent.NOBUTTON);
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

    private static Object invokeHidden(RequestEditorPanel panel, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = RequestEditorPanel.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(panel, args);
    }
}
