package burp.ui;

import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.util.List;

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
    void loadingRequestWithNoHeadersLeavesOneBlankStarterRow() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        panel.loadRequest(req);

        DefaultTableModel model = headersModel(panel);
        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getValueAt(0, 0)).isEqualTo("");
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
    void loadingRequestWithExistingHeadersPreservesRowsWithoutAddingExtraBlank() throws Exception {
        RequestEditorPanel panel = new RequestEditorPanel();
        ApiRequest req = minimalRequest();
        req.headers = List.of(new ApiRequest.Header("Authorization", "Bearer token", false));
        panel.loadRequest(req);

        DefaultTableModel model = headersModel(panel);
        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getValueAt(0, 0)).isEqualTo("Authorization");
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
    void deletingLastRowRestoresBlankStarterRow() throws Exception {
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
        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getValueAt(0, 0)).isEqualTo("");
        assertThat(model.getValueAt(0, 2)).isEqualTo(true);
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
