package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.VariableResolver;
import burp.utils.RequestBuilder;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
    void buildRequestFromUiPersistsMaterializedHeadersAndMarksRequestEditorOwned() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));
        panel.loadRequest(minimalRequest());

        ApiRequest built = panel.buildRequestFromUI();

        assertThat(built.editorMaterialized).isTrue();
        assertThat(built.headers)
                .extracting(h -> h.key)
                .containsExactly("Accept", "User-Agent", "Cache-Control");
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

        byte[] raw = new RequestBuilder(null).buildRequest(built, new VariableResolver());
        String text = new String(raw, StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("Authorization: Bearer tok123");
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

    private static JTextField urlField(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("urlField");
        f.setAccessible(true);
        return (JTextField) f.get(panel);
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
}
