package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestEditorPanelTest {

    @Test
    void newPanelExposesBlankStarterRowInParams() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        DefaultTableModel model = paramsModel(panel);
        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getValueAt(0, 0)).isEqualTo("");
        assertThat(model.getValueAt(0, 1)).isEqualTo("");
    }

    @Test
    void newPanelExposesBlankStarterRowInHeaders() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        DefaultTableModel model = headersModel(panel);
        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getValueAt(0, 0)).isEqualTo("");
        assertThat(model.getValueAt(0, 1)).isEqualTo("");
        assertThat(model.getValueAt(0, 2)).isEqualTo(true);
    }

    @Test
    void newPanelExposesBlankStarterRowInBodyFormTable() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        DefaultTableModel model = bodyFormModel(panel);
        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getValueAt(0, 0)).isEqualTo("");
        assertThat(model.getValueAt(0, 1)).isEqualTo("");
    }

    @Test
    void loadingRequestWithNoParamsLeavesOneBlankStarterRow() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        panel.loadRequest(req);

        DefaultTableModel model = paramsModel(panel);
        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getValueAt(0, 0)).isEqualTo("");
    }

    @Test
    void loadingRequestWithNoHeadersShowsSynthesizedHeadersAndBlankStarterRow() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        panel.loadRequest(req);

        DefaultTableModel model = headersModel(panel);
        // Effective headers (Accept, User-Agent, Cache-Control, Host) + blank starter
        assertThat(model.getRowCount()).isEqualTo(5);
        assertThat(model.getValueAt(4, 0)).isEqualTo("");
        assertThat(model.getValueAt(4, 2)).isEqualTo(true);
    }

    @Test
    void loadingRequestWithNoBodyFormLeavesOneBlankStarterRow() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        req.body = new ApiRequest.Body();
        req.body.mode = "formdata";
        panel.loadRequest(req);

        DefaultTableModel model = bodyFormModel(panel);
        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getValueAt(0, 0)).isEqualTo("");
    }

    @Test
    void loadingRequestWithExistingParamsPreservesRowsWithoutAddingExtraBlank() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        req.url = "https://api.example.test?foo=bar&baz=qux";
        panel.loadRequest(req);

        DefaultTableModel model = paramsModel(panel);
        assertThat(model.getRowCount()).isEqualTo(2);
        assertThat(model.getValueAt(0, 0)).isEqualTo("foo");
        assertThat(model.getValueAt(1, 0)).isEqualTo("baz");
    }

    @Test
    void loadingRequestWithExistingHeadersPreservesRowsAndShowsEffectiveHeaders() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        req.headers = List.of(new ApiRequest.Header("Authorization", "Bearer token", false));
        panel.loadRequest(req);

        DefaultTableModel model = headersModel(panel);
        // Explicit + effective headers (Accept, User-Agent, Cache-Control, Host) + blank starter
        assertThat(model.getRowCount()).isEqualTo(6);
        assertThat(model.getValueAt(0, 0)).isEqualTo("Authorization");
        assertThat(model.getValueAt(5, 0)).isEqualTo("");
    }

    @Test
    void loadingRequestWithExistingBodyFormPreservesRowsWithoutAddingExtraBlank() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        req.body = new ApiRequest.Body();
        req.body.mode = "formdata";
        req.body.formdata = List.of(new ApiRequest.Body.FormField("key1", "val1"));
        panel.loadRequest(req);

        DefaultTableModel model = bodyFormModel(panel);
        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getValueAt(0, 0)).isEqualTo("key1");
    }

    @Test
    void clearAllRestoresBlankStarterRows() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        req.url = "https://api.example.test?a=1";
        req.headers = List.of(new ApiRequest.Header("H", "V", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "urlencoded";
        req.body.urlencoded = List.of(new ApiRequest.Body.FormField("k", "v"));
        panel.loadRequest(req);

        // clear via loading null
        panel.loadRequest(null);

        assertThat(paramsModel(panel).getRowCount()).isEqualTo(1);
        assertThat(paramsModel(panel).getValueAt(0, 0)).isEqualTo("");

        assertThat(headersModel(panel).getRowCount()).isEqualTo(1);
        assertThat(headersModel(panel).getValueAt(0, 0)).isEqualTo("");

        assertThat(bodyFormModel(panel).getRowCount()).isEqualTo(1);
        assertThat(bodyFormModel(panel).getValueAt(0, 0)).isEqualTo("");
    }

    @Test
    void buildRequestDoesNotSerializeUntouchedBlankStarterRows() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        panel.loadRequest(req);

        ApiRequest built = panel.buildRequestFromUI();
        assertThat(built).isNotNull();
        assertThat(built.headers).isEmpty();
        assertThat(built.body).isNull();
    }

    @Test
    void buildRequestDoesNotSerializeUnmodifiedSynthesizedHeaders() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        panel.loadRequest(req);

        ApiRequest built = panel.buildRequestFromUI();
        assertThat(built).isNotNull();
        // All 4 synthesized headers (Accept, User-Agent, Cache-Control, Host) are unmodified
        // and checked, so they should NOT be persisted as explicit headers.
        assertThat(built.headers).isEmpty();
    }

    @Test
    void disablingSynthesizedHeaderPersistsAsSuppression() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        panel.loadRequest(req);

        DefaultTableModel model = headersModel(panel);
        // Find the Accept row (first synthesized header) and disable it
        int acceptRow = -1;
        for (int i = 0; i < model.getRowCount(); i++) {
            if ("Accept".equalsIgnoreCase((String) model.getValueAt(i, 0))) {
                acceptRow = i;
                break;
            }
        }
        assertThat(acceptRow).isGreaterThanOrEqualTo(0);
        model.setValueAt(false, acceptRow, 2);

        ApiRequest built = panel.buildRequestFromUI();
        assertThat(built).isNotNull();
        assertThat(built.headers).hasSize(1);
        assertThat(built.headers.get(0).key).isEqualToIgnoringCase("Accept");
        assertThat(built.headers.get(0).disabled).isTrue();
    }

    @Test
    void editingSynthesizedHeaderPersistsAsExplicit() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        panel.loadRequest(req);

        DefaultTableModel model = headersModel(panel);
        // Find the Accept row and change its value
        int acceptRow = -1;
        for (int i = 0; i < model.getRowCount(); i++) {
            if ("Accept".equalsIgnoreCase((String) model.getValueAt(i, 0))) {
                acceptRow = i;
                break;
            }
        }
        assertThat(acceptRow).isGreaterThanOrEqualTo(0);
        model.setValueAt("text/html", acceptRow, 1);

        ApiRequest built = panel.buildRequestFromUI();
        assertThat(built).isNotNull();
        assertThat(built.headers).hasSize(1);
        assertThat(built.headers.get(0).key).isEqualToIgnoringCase("Accept");
        assertThat(built.headers.get(0).value).isEqualTo("text/html");
        assertThat(built.headers.get(0).disabled).isFalse();
    }

    @Test
    void deletingExplicitHeaderLeavesEffectiveHeadersAndBlankStarterRow() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        req.headers = List.of(new ApiRequest.Header("X", "Y", false));
        panel.loadRequest(req);

        JTable table = headersTable(panel);
        table.setRowSelectionInterval(0, 0);

        Container headersPanel = (Container) tabs(panel).getComponentAt(2);
        JButton delBtn = findButton(headersPanel, "-");
        assertThat(delBtn).isNotNull();
        delBtn.doClick();

        DefaultTableModel model = headersModel(panel);
        // Effective headers remain + blank starter
        assertThat(model.getRowCount()).isEqualTo(5);
        assertThat(model.getValueAt(4, 0)).isEqualTo("");
        assertThat(model.getValueAt(4, 2)).isEqualTo(true);
    }

    @Test
    void switchingToFormDataModeSeedsStarterRowWhenEmpty() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        panel.loadRequest(req);

        Container bodyPanel = (Container) tabs(panel).getComponentAt(3);
        JRadioButton btn = findRadioButton(bodyPanel, "form-data");
        assertThat(btn).isNotNull();
        btn.doClick();

        DefaultTableModel model = bodyFormModel(panel);
        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getValueAt(0, 0)).isEqualTo("");
    }

    @Test
    void switchingToUrlEncodedModeSeedsStarterRowWhenEmpty() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        panel.loadRequest(req);

        Container bodyPanel = (Container) tabs(panel).getComponentAt(3);
        JRadioButton btn = findRadioButton(bodyPanel, "x-www-form-urlencoded");
        assertThat(btn).isNotNull();
        btn.doClick();

        DefaultTableModel model = bodyFormModel(panel);
        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getValueAt(0, 0)).isEqualTo("");
    }

    // ------------------------------------------------------------------------
    // Live effective-header refresh
    // ------------------------------------------------------------------------

    @Test
    void loadRequestWithCollectionContextComputesEffectiveHeadersCorrectly() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();

        ApiCollection collection = new ApiCollection();
        collection.environment.put("token", "abc");

        ApiRequest req = minimalRequest();
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "{{token}}");

        // Collection context must be set BEFORE loadRequest for effective headers to resolve correctly
        panel.setCurrentCollection(collection);
        panel.loadRequest(req);

        DefaultTableModel model = headersModel(panel);
        boolean found = false;
        for (int i = 0; i < model.getRowCount(); i++) {
            String key = (String) model.getValueAt(i, 0);
            String value = (String) model.getValueAt(i, 1);
            if ("Authorization".equalsIgnoreCase(key) && "Bearer abc".equals(value)) {
                found = true;
                break;
            }
        }
        assertThat(found).as("Authorization header should be resolved with collection env var").isTrue();
    }

    @Test
    void changingRuntimeVarsUpdatesHeadersTabEffectiveRows() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();

        ApiCollection collection = new ApiCollection();
        ApiRequest req = minimalRequest();
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "{{token}}");

        panel.setCurrentCollection(collection);
        panel.loadRequest(req);

        // Before runtime vars update, Authorization should be unresolved
        DefaultTableModel modelBefore = headersModel(panel);
        boolean unresolvedBefore = false;
        for (int i = 0; i < modelBefore.getRowCount(); i++) {
            String key = (String) modelBefore.getValueAt(i, 0);
            String value = (String) modelBefore.getValueAt(i, 1);
            if ("Authorization".equalsIgnoreCase(key) && value != null && value.contains("{{token}}")) {
                unresolvedBefore = true;
                break;
            }
        }
        assertThat(unresolvedBefore).as("Authorization should be unresolved before runtime vars").isTrue();

        // Update runtime vars - this should trigger live header refresh
        Map<String, String> vars = new HashMap<>();
        vars.put("token", "live123");
        panel.setRuntimeVariables(vars);

        DefaultTableModel modelAfter = headersModel(panel);
        boolean resolvedAfter = false;
        for (int i = 0; i < modelAfter.getRowCount(); i++) {
            String key = (String) modelAfter.getValueAt(i, 0);
            String value = (String) modelAfter.getValueAt(i, 1);
            if ("Authorization".equalsIgnoreCase(key) && "Bearer live123".equals(value)) {
                resolvedAfter = true;
                break;
            }
        }
        assertThat(resolvedAfter).as("Authorization should update live when runtime vars change").isTrue();
    }

    @Test
    void changingUrlUpdatesHeadersTabHostLive() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        panel.loadRequest(req);

        urlField(panel).setText("https://other.example.test/path");

        DefaultTableModel model = headersModel(panel);
        boolean found = false;
        for (int i = 0; i < model.getRowCount(); i++) {
            String key = (String) model.getValueAt(i, 0);
            String value = (String) model.getValueAt(i, 1);
            if ("Host".equalsIgnoreCase(key) && "other.example.test".equals(value)) {
                found = true;
                break;
            }
        }
        assertThat(found).as("Host should update live when URL changes").isTrue();
    }

    @Test
    void folderScopedVariablesSurviveLiveHeaderRecompute() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();

        ApiCollection collection = new ApiCollection();
        collection.folderVars.put("Auth/OAuth", Map.of("token", "folder123"));

        ApiRequest req = minimalRequest();
        req.name = "Get Token";
        req.path = "Auth/OAuth/Get Token";
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "{{token}}");

        panel.setCurrentCollection(collection);
        panel.loadRequest(req);

        urlField(panel).setText("https://api.example.test/other");

        DefaultTableModel model = headersModel(panel);
        boolean found = false;
        for (int i = 0; i < model.getRowCount(); i++) {
            String key = (String) model.getValueAt(i, 0);
            String value = (String) model.getValueAt(i, 1);
            if ("Authorization".equalsIgnoreCase(key) && "Bearer folder123".equals(value)) {
                found = true;
                break;
            }
        }
        assertThat(found).as("Folder-scoped variables should still resolve after live recompute").isTrue();
    }

    @Test
    void changingAuthTypeUpdatesHeadersTabEffectiveRows() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();

        ApiRequest req = minimalRequest();
        panel.loadRequest(req);

        // Initially no auth → no Authorization header
        DefaultTableModel modelBefore = headersModel(panel);
        int authRowBefore = -1;
        for (int i = 0; i < modelBefore.getRowCount(); i++) {
            if ("Authorization".equalsIgnoreCase((String) modelBefore.getValueAt(i, 0))) {
                authRowBefore = i;
                break;
            }
        }
        assertThat(authRowBefore).as("No Authorization row before auth is set").isLessThan(0);

        // Change auth type to bearer via the auth combo box
        Field authTypeBoxField = RequestEditorPanel.class.getDeclaredField("authTypeBox");
        authTypeBoxField.setAccessible(true);
        JComboBox<String> authTypeBox = (JComboBox<String>) authTypeBoxField.get(panel);
        authTypeBox.setSelectedItem("bearer");

        // Set token value in auth field
        Field authUiField = RequestEditorPanel.class.getDeclaredField("authUi");
        authUiField.setAccessible(true);
        Object authUi = authUiField.get(panel);
        Field authFieldsField = authUi.getClass().getDeclaredField("authFields");
        authFieldsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, JTextField> authFields = (Map<String, JTextField>) authFieldsField.get(authUi);
        authFields.get("token").setText("mytoken");

        // Trigger refresh
        Field refreshingField = RequestEditorPanel.class.getDeclaredField("refreshingHeaders");
        refreshingField.setAccessible(true);
        boolean refreshing = (boolean) refreshingField.get(panel);
        if (!refreshing) {
            java.lang.reflect.Method refreshMethod = RequestEditorPanel.class.getDeclaredMethod("refreshAll");
            refreshMethod.setAccessible(true);
            refreshMethod.invoke(panel);
        }

        DefaultTableModel modelAfter = headersModel(panel);
        boolean found = false;
        for (int i = 0; i < modelAfter.getRowCount(); i++) {
            String key = (String) modelAfter.getValueAt(i, 0);
            String value = (String) modelAfter.getValueAt(i, 1);
            if ("Authorization".equalsIgnoreCase(key) && "Bearer mytoken".equals(value)) {
                found = true;
                break;
            }
        }
        assertThat(found).as("Authorization should appear live after auth type change").isTrue();
    }

    @Test
    void operatorSuppressionsSurviveLiveRecompute() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();

        ApiCollection collection = new ApiCollection();
        ApiRequest req = minimalRequest();

        panel.setCurrentCollection(collection);
        panel.loadRequest(req);

        // Find Accept row and disable it (suppression)
        DefaultTableModel model = headersModel(panel);
        int acceptRow = -1;
        for (int i = 0; i < model.getRowCount(); i++) {
            if ("Accept".equalsIgnoreCase((String) model.getValueAt(i, 0))) {
                acceptRow = i;
                break;
            }
        }
        assertThat(acceptRow).isGreaterThanOrEqualTo(0);
        model.setValueAt(false, acceptRow, 2);

        // Trigger live recompute by changing runtime vars
        panel.setRuntimeVariables(Map.of("x", "y"));

        // Accept should still be disabled
        DefaultTableModel modelAfter = headersModel(panel);
        boolean stillSuppressed = false;
        for (int i = 0; i < modelAfter.getRowCount(); i++) {
            String key = (String) modelAfter.getValueAt(i, 0);
            Boolean enabled = (Boolean) modelAfter.getValueAt(i, 2);
            if ("Accept".equalsIgnoreCase(key) && Boolean.FALSE.equals(enabled)) {
                stillSuppressed = true;
                break;
            }
        }
        assertThat(stillSuppressed).as("Accept suppression should survive live recompute").isTrue();
    }

    @Test
    void suppressedSynthesizedHeaderTracksLatestValueAcrossMultipleRecomputes() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();

        ApiCollection collection = new ApiCollection();
        ApiRequest req = minimalRequest();
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "{{token}}");

        panel.setCurrentCollection(collection);
        panel.loadRequest(req);

        DefaultTableModel model = headersModel(panel);
        int authRow = -1;
        for (int i = 0; i < model.getRowCount(); i++) {
            if ("Authorization".equalsIgnoreCase((String) model.getValueAt(i, 0))) {
                authRow = i;
                break;
            }
        }
        assertThat(authRow).isGreaterThanOrEqualTo(0);
        model.setValueAt(false, authRow, 2);

        panel.setRuntimeVariables(Map.of("token", "one"));
        panel.setRuntimeVariables(Map.of("token", "two"));

        DefaultTableModel modelAfter = headersModel(panel);
        boolean updated = false;
        for (int i = 0; i < modelAfter.getRowCount(); i++) {
            String key = (String) modelAfter.getValueAt(i, 0);
            String value = (String) modelAfter.getValueAt(i, 1);
            Boolean enabled = (Boolean) modelAfter.getValueAt(i, 2);
            if ("Authorization".equalsIgnoreCase(key) && "Bearer two".equals(value) && Boolean.FALSE.equals(enabled)) {
                updated = true;
                break;
            }
        }
        assertThat(updated).as("Suppressed synthesized Authorization should stay disabled and keep the latest value").isTrue();
    }

    @Test
    void operatorEditedSynthesizedHeaderSurvivesLiveRecompute() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();

        ApiCollection collection = new ApiCollection();
        ApiRequest req = minimalRequest();

        panel.setCurrentCollection(collection);
        panel.loadRequest(req);

        // Find Accept row and edit its value
        DefaultTableModel model = headersModel(panel);
        int acceptRow = -1;
        for (int i = 0; i < model.getRowCount(); i++) {
            if ("Accept".equalsIgnoreCase((String) model.getValueAt(i, 0))) {
                acceptRow = i;
                break;
            }
        }
        assertThat(acceptRow).isGreaterThanOrEqualTo(0);
        model.setValueAt("text/html", acceptRow, 1);

        // Trigger live recompute by changing runtime vars
        panel.setRuntimeVariables(Map.of("x", "y"));

        // Accept should still have the edited value
        DefaultTableModel modelAfter = headersModel(panel);
        boolean stillEdited = false;
        for (int i = 0; i < modelAfter.getRowCount(); i++) {
            String key = (String) modelAfter.getValueAt(i, 0);
            String value = (String) modelAfter.getValueAt(i, 1);
            if ("Accept".equalsIgnoreCase(key) && "text/html".equals(value)) {
                stillEdited = true;
                break;
            }
        }
        assertThat(stillEdited).as("Edited Accept should survive live recompute").isTrue();
    }

    @Test
    void changingBodyModeUpdatesHeadersTabContentType() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();

        ApiRequest req = minimalRequest();
        req.method = "POST";
        panel.loadRequest(req);

        // No body → no Content-Type initially
        DefaultTableModel modelBefore = headersModel(panel);
        int ctRowBefore = -1;
        for (int i = 0; i < modelBefore.getRowCount(); i++) {
            if ("Content-Type".equalsIgnoreCase((String) modelBefore.getValueAt(i, 0))) {
                ctRowBefore = i;
                break;
            }
        }
        assertThat(ctRowBefore).as("No Content-Type row for GET with no body").isLessThan(0);

        // Change body mode to raw and type JSON
        Field bodyUiField = RequestEditorPanel.class.getDeclaredField("bodyUi");
        bodyUiField.setAccessible(true);
        Object bodyUi = bodyUiField.get(panel);
        java.lang.reflect.Method setBodyModeMethod = bodyUi.getClass().getDeclaredMethod("setBodyModeInternal", String.class);
        setBodyModeMethod.setAccessible(true);
        setBodyModeMethod.invoke(bodyUi, "raw");

        Field bodyRawAreaField = RequestEditorPanel.class.getDeclaredField("bodyRawArea");
        bodyRawAreaField.setAccessible(true);
        JTextArea bodyRawArea = (JTextArea) bodyRawAreaField.get(panel);
        bodyRawArea.setText("{}");

        DefaultTableModel modelAfter = headersModel(panel);
        boolean found = false;
        for (int i = 0; i < modelAfter.getRowCount(); i++) {
            String key = (String) modelAfter.getValueAt(i, 0);
            String value = (String) modelAfter.getValueAt(i, 1);
            if ("Content-Type".equalsIgnoreCase(key) && "application/json".equals(value)) {
                found = true;
                break;
            }
        }
        assertThat(found).as("Content-Type should appear as application/json after raw JSON body is set").isTrue();
    }

    @Test
    void changingUrlEncodedBodyUpdatesHeadersTabContentType() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();

        ApiRequest req = minimalRequest();
        req.method = "POST";
        panel.loadRequest(req);

        Container bodyPanel = (Container) tabs(panel).getComponentAt(3);
        JRadioButton btn = findRadioButton(bodyPanel, "x-www-form-urlencoded");
        assertThat(btn).isNotNull();
        btn.doClick();

        DefaultTableModel formModel = bodyFormModel(panel);
        formModel.setValueAt("grant_type", 0, 0);
        formModel.setValueAt("client_credentials", 0, 1);

        DefaultTableModel headers = headersModel(panel);
        boolean found = false;
        for (int i = 0; i < headers.getRowCount(); i++) {
            String key = (String) headers.getValueAt(i, 0);
            String value = (String) headers.getValueAt(i, 1);
            if ("Content-Type".equalsIgnoreCase(key) && "application/x-www-form-urlencoded".equals(value)) {
                found = true;
                break;
            }
        }
        assertThat(found).as("Content-Type should update live for x-www-form-urlencoded bodies").isTrue();
    }

    @Test
    void changingFormDataBodyUpdatesHeadersTabContentType() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();

        ApiRequest req = minimalRequest();
        req.method = "POST";
        panel.loadRequest(req);

        Container bodyPanel = (Container) tabs(panel).getComponentAt(3);
        JRadioButton btn = findRadioButton(bodyPanel, "form-data");
        assertThat(btn).isNotNull();
        btn.doClick();

        DefaultTableModel formModel = bodyFormModel(panel);
        formModel.setValueAt("file", 0, 0);
        formModel.setValueAt("sample", 0, 1);

        DefaultTableModel headers = headersModel(panel);
        boolean found = false;
        for (int i = 0; i < headers.getRowCount(); i++) {
            String key = (String) headers.getValueAt(i, 0);
            String value = (String) headers.getValueAt(i, 1);
            if ("Content-Type".equalsIgnoreCase(key) && value != null && value.startsWith("multipart/form-data; boundary=")) {
                found = true;
                break;
            }
        }
        assertThat(found).as("Content-Type should update live for form-data bodies").isTrue();
    }

    @Test
    void loadRequestUsesConfiguredRequestBuilderForInitialEffectiveHeaders() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new burp.utils.RequestBuilder(null, null) {
            @Override
            public List<Map.Entry<String, String>> buildEffectiveHeaders(ApiRequest request, burp.parser.VariableResolver resolver) {
                return List.of(new AbstractMap.SimpleEntry<>("X-Test-Builder", "configured"));
            }
        });

        panel.loadRequest(minimalRequest());

        DefaultTableModel model = headersModel(panel);
        boolean found = false;
        for (int i = 0; i < model.getRowCount(); i++) {
            String key = (String) model.getValueAt(i, 0);
            String value = (String) model.getValueAt(i, 1);
            if ("X-Test-Builder".equalsIgnoreCase(key) && "configured".equals(value)) {
                found = true;
                break;
            }
        }
        assertThat(found).as("Initial effective headers should use the configured RequestBuilder").isTrue();
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

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

    private static JTable headersTable(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("headersTable");
        f.setAccessible(true);
        return (JTable) f.get(panel);
    }

    private static JTextField urlField(RequestEditorPanel panel) throws Exception {
        Field f = RequestEditorPanel.class.getDeclaredField("urlField");
        f.setAccessible(true);
        return (JTextField) f.get(panel);
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
