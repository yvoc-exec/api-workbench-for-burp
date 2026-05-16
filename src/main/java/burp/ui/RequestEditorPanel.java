package burp.ui;

import burp.models.ApiRequest;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Postman-like request editor panel for the Workbench.
 */
public class RequestEditorPanel extends JPanel {
    private JComboBox<String> methodBox;
    private JTextField urlField;
    private JTabbedPane tabs;

    // Params
    private DefaultTableModel paramsModel;
    private JTable paramsTable;

    // Auth
    private JComboBox<String> authTypeBox;
    private JPanel authFieldsPanel;
    private Map<String, JTextField> authFields = new HashMap<>();

    // Headers
    private DefaultTableModel headersModel;
    private JTable headersTable;

    // Body
    private JComboBox<String> bodyModeBox;
    private JTextArea bodyRawArea;
    private JTable bodyFormTable;
    private DefaultTableModel bodyFormModel;

    // Scripts
    private JTextArea preScriptArea;
    private JTextArea postScriptArea;

    private ApiRequest currentRequest;
    private burp.models.ApiCollection currentCollection;

    // Send action callback
    public interface SendActionListener {
        void onSend();
    }
    private SendActionListener sendActionListener;
    private JButton sendBtn;
    private JButton sendDropdownBtn;

    public RequestEditorPanel() {
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createTitledBorder("Request Editor"));
        add(createTopBar(), BorderLayout.NORTH);
        add(createTabs(), BorderLayout.CENTER);
    }

    private JPanel createTopBar() {
        JPanel panel = new JPanel(new BorderLayout(5, 0));
        methodBox = new JComboBox<>(new String[]{"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"});
        urlField = new JTextField();
        urlField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        // Send split button
        sendBtn = new JButton("Send");
        sendBtn.setToolTipText("Send current edited request directly");
        sendBtn.addActionListener(e -> {
            if (sendActionListener != null) {
                sendActionListener.onSend();
            }
        });
        sendDropdownBtn = new JButton("\u25BC"); // Black down-pointing triangle
        sendDropdownBtn.setToolTipText("Select send mode");
        sendDropdownBtn.setPreferredSize(new Dimension(22, sendBtn.getPreferredSize().height));
        sendDropdownBtn.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem sendOnlyItem = new JMenuItem("Send");
            JMenuItem sendRepeaterItem = new JMenuItem("Send + Repeater");
            sendOnlyItem.addActionListener(ev -> setSendModeLabel("Send"));
            sendRepeaterItem.addActionListener(ev -> setSendModeLabel("Send + Repeater"));
            menu.add(sendOnlyItem);
            menu.add(sendRepeaterItem);
            menu.show(sendDropdownBtn, 0, sendDropdownBtn.getHeight());
        });

        JPanel sendPanel = new JPanel(new BorderLayout(0, 0));
        sendPanel.add(sendBtn, BorderLayout.CENTER);
        sendPanel.add(sendDropdownBtn, BorderLayout.EAST);

        panel.add(methodBox, BorderLayout.WEST);
        panel.add(urlField, BorderLayout.CENTER);
        panel.add(sendPanel, BorderLayout.EAST);
        return panel;
    }

    public void setSendActionListener(SendActionListener listener) {
        this.sendActionListener = listener;
    }

    public void setSendEnabled(boolean enabled) {
        sendBtn.setEnabled(enabled);
        sendDropdownBtn.setEnabled(enabled);
    }

    public String getSendModeLabel() {
        return sendBtn.getText();
    }

    public void setSendModeLabel(String label) {
        sendBtn.setText(label);
        if ("Send + Repeater".equals(label)) {
            sendBtn.setToolTipText("Send request and also create Repeater tab");
        } else {
            sendBtn.setToolTipText("Send current edited request directly");
        }
    }

    private JTabbedPane createTabs() {
        tabs = new JTabbedPane();
        tabs.addTab("Params", createParamsPanel());
        tabs.addTab("Auth", createAuthPanel());
        tabs.addTab("Headers", createHeadersPanel());
        tabs.addTab("Body", createBodyPanel());
        tabs.addTab("Scripts", createScriptsPanel());
        return tabs;
    }

    private JPanel createParamsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        paramsModel = new DefaultTableModel(new Object[]{"Key", "Value"}, 0);
        paramsTable = new JTable(paramsModel);
        panel.add(new JScrollPane(paramsTable), BorderLayout.CENTER);
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addBtn = new JButton("+");
        addBtn.addActionListener(e -> paramsModel.addRow(new Object[]{"", ""}));
        JButton delBtn = new JButton("-");
        delBtn.addActionListener(e -> {
            int row = paramsTable.getSelectedRow();
            if (row >= 0) paramsModel.removeRow(row);
        });
        btnPanel.add(addBtn);
        btnPanel.add(delBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createAuthPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        authTypeBox = new JComboBox<>(new String[]{"none", "bearer", "basic", "apikey", "oauth2"});
        authTypeBox.addActionListener(e -> rebuildAuthFields());
        panel.add(authTypeBox, BorderLayout.NORTH);
        authFieldsPanel = new JPanel(new GridLayout(0, 2, 5, 5));
        panel.add(authFieldsPanel, BorderLayout.CENTER);
        rebuildAuthFields();
        return panel;
    }

    private void rebuildAuthFields() {
        authFieldsPanel.removeAll();
        authFields.clear();
        String type = (String) authTypeBox.getSelectedItem();
        if ("bearer".equals(type)) {
            addAuthField("token", "Token");
        } else if ("basic".equals(type)) {
            addAuthField("username", "Username");
            addAuthField("password", "Password");
        } else if ("apikey".equals(type)) {
            addAuthField("key", "Key Name");
            addAuthField("value", "Key Value");
            addAuthField("in", "In (header/query/cookie)");
        } else if ("oauth2".equals(type)) {
            addAuthField("grantType", "Grant Type");
            addAuthField("accessTokenUrl", "Token URL");
            addAuthField("clientId", "Client ID");
            addAuthField("clientSecret", "Client Secret");
            addAuthField("scope", "Scope");
            addAuthField("accessToken", "Access Token");
        }
        authFieldsPanel.revalidate();
        authFieldsPanel.repaint();
    }

    private void addAuthField(String key, String label) {
        authFieldsPanel.add(new JLabel(label));
        JTextField field = new JTextField();
        authFields.put(key, field);
        authFieldsPanel.add(field);
    }

    private JPanel createHeadersPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        headersModel = new DefaultTableModel(new Object[]{"Key", "Value", "Enabled"}, 0) {
            @Override public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 2 ? Boolean.class : String.class;
            }
        };
        headersTable = new JTable(headersModel);
        panel.add(new JScrollPane(headersTable), BorderLayout.CENTER);
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addBtn = new JButton("+");
        addBtn.addActionListener(e -> headersModel.addRow(new Object[]{"", "", true}));
        JButton delBtn = new JButton("-");
        delBtn.addActionListener(e -> {
            int row = headersTable.getSelectedRow();
            if (row >= 0) headersModel.removeRow(row);
        });
        btnPanel.add(addBtn);
        btnPanel.add(delBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createBodyPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        bodyModeBox = new JComboBox<>(new String[]{"none", "raw", "urlencoded", "formdata"});
        bodyModeBox.addActionListener(e -> updateBodyMode());
        panel.add(bodyModeBox, BorderLayout.NORTH);

        JPanel bodyContent = new JPanel(new CardLayout());
        // Raw
        bodyRawArea = new JTextArea(8, 40);
        bodyRawArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        bodyContent.add(new JScrollPane(bodyRawArea), "raw");
        // Form
        bodyFormModel = new DefaultTableModel(new Object[]{"Key", "Value"}, 0);
        bodyFormTable = new JTable(bodyFormModel);
        JPanel formPanel = new JPanel(new BorderLayout());
        formPanel.add(new JScrollPane(bodyFormTable), BorderLayout.CENTER);
        JPanel formBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addFormBtn = new JButton("+");
        addFormBtn.addActionListener(e -> bodyFormModel.addRow(new Object[]{"", ""}));
        JButton delFormBtn = new JButton("-");
        delFormBtn.addActionListener(e -> {
            int row = bodyFormTable.getSelectedRow();
            if (row >= 0) bodyFormModel.removeRow(row);
        });
        formBtnPanel.add(addFormBtn);
        formBtnPanel.add(delFormBtn);
        formPanel.add(formBtnPanel, BorderLayout.SOUTH);
        bodyContent.add(formPanel, "form");
        // Empty placeholder for none
        bodyContent.add(new JLabel("No body"), "none");
        panel.add(bodyContent, BorderLayout.CENTER);
        return panel;
    }

    private void updateBodyMode() {
        String mode = (String) bodyModeBox.getSelectedItem();
        CardLayout cl = (CardLayout) ((JPanel) bodyModeBox.getParent().getComponent(1)).getLayout();
        if ("raw".equals(mode)) cl.show((JPanel) bodyModeBox.getParent().getComponent(1), "raw");
        else if ("none".equals(mode)) cl.show((JPanel) bodyModeBox.getParent().getComponent(1), "none");
        else cl.show((JPanel) bodyModeBox.getParent().getComponent(1), "form");
    }

    private JPanel createScriptsPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 1, 5, 5));
        preScriptArea = new JTextArea(5, 40);
        preScriptArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        preScriptArea.setBorder(BorderFactory.createTitledBorder("Pre-request Script"));
        panel.add(new JScrollPane(preScriptArea));
        postScriptArea = new JTextArea(5, 40);
        postScriptArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        postScriptArea.setBorder(BorderFactory.createTitledBorder("Post-response Script"));
        panel.add(new JScrollPane(postScriptArea));
        return panel;
    }

    public void loadRequest(ApiRequest req) {
        this.currentRequest = req;
        if (req == null) {
            methodBox.setSelectedItem("GET");
            urlField.setText("");
            clearAll();
            return;
        }
        methodBox.setSelectedItem(req.method != null ? req.method.toUpperCase() : "GET");
        urlField.setText(req.url != null ? req.url : "");

        // Params from URL
        paramsModel.setRowCount(0);
        parseQueryToTable(req.url);

        // Auth
        if (req.auth != null && req.auth.type != null) {
            authTypeBox.setSelectedItem(req.auth.type.toLowerCase());
            rebuildAuthFields();
            for (Map.Entry<String, String> e : req.auth.properties.entrySet()) {
                JTextField f = authFields.get(e.getKey());
                if (f != null) f.setText(e.getValue());
            }
        } else {
            authTypeBox.setSelectedItem("none");
            rebuildAuthFields();
        }
        // Headers
        headersModel.setRowCount(0);
        if (req.headers != null) {
            for (ApiRequest.Header h : req.headers) {
                headersModel.addRow(new Object[]{h.key, h.value, !h.disabled});
            }
        }
        // Body
        if (req.body != null) {
            bodyModeBox.setSelectedItem(req.body.mode != null ? req.body.mode : "none");
            updateBodyMode();
            if ("raw".equals(req.body.mode) && req.body.raw != null) {
                bodyRawArea.setText(req.body.raw);
            }
            bodyFormModel.setRowCount(0);
            if (req.body.urlencoded != null) {
                for (ApiRequest.Body.FormField f : req.body.urlencoded) {
                    bodyFormModel.addRow(new Object[]{f.key, f.value});
                }
            }
            if (req.body.formdata != null) {
                for (ApiRequest.Body.FormField f : req.body.formdata) {
                    bodyFormModel.addRow(new Object[]{f.key, f.value});
                }
            }
        } else {
            bodyModeBox.setSelectedItem("none");
            updateBodyMode();
        }
        // Scripts
        preScriptArea.setText("");
        postScriptArea.setText("");
        if (req.preRequestScripts != null) {
            for (ApiRequest.Script s : req.preRequestScripts) {
                preScriptArea.append(s.exec != null ? s.exec : "");
            }
        }
        if (req.postResponseScripts != null) {
            for (ApiRequest.Script s : req.postResponseScripts) {
                postScriptArea.append(s.exec != null ? s.exec : "");
            }
        }
    }

    public burp.models.ApiCollection getCurrentCollection() { return currentCollection; }
    public void setCurrentCollection(burp.models.ApiCollection col) { this.currentCollection = col; }

    public ApiRequest buildRequestFromUI() {
        if (currentRequest == null) return null;
        ApiRequest req = new ApiRequest();
        req.name = currentRequest.name;
        req.path = currentRequest.path;
        req.sourceCollection = currentRequest.sourceCollection;
        req.id = currentRequest.id;
        req.sequenceOrder = currentRequest.sequenceOrder;
        req.method = (String) methodBox.getSelectedItem();
        req.url = rebuildUrlWithParams(urlField.getText(), paramsModel);

        // Auth
        String authType = (String) authTypeBox.getSelectedItem();
        if (!"none".equals(authType)) {
            req.auth = new ApiRequest.Auth();
            req.auth.type = authType;
            for (Map.Entry<String, JTextField> e : authFields.entrySet()) {
                String val = e.getValue().getText().trim();
                if (!val.isEmpty()) req.auth.properties.put(e.getKey(), val);
            }
        }
        // Headers
        for (int i = 0; i < headersModel.getRowCount(); i++) {
            String key = (String) headersModel.getValueAt(i, 0);
            String value = (String) headersModel.getValueAt(i, 1);
            Boolean enabled = (Boolean) headersModel.getValueAt(i, 2);
            if (key != null && !key.trim().isEmpty()) {
                req.headers.add(new ApiRequest.Header(key, value != null ? value : "", enabled == null || !enabled));
            }
        }
        // Body
        String bodyMode = (String) bodyModeBox.getSelectedItem();
        if (!"none".equals(bodyMode)) {
            req.body = new ApiRequest.Body();
            req.body.mode = bodyMode;
            if ("raw".equals(bodyMode)) {
                req.body.raw = bodyRawArea.getText();
            } else if ("urlencoded".equals(bodyMode) || "formdata".equals(bodyMode)) {
                List<ApiRequest.Body.FormField> fields = new ArrayList<>();
                for (int i = 0; i < bodyFormModel.getRowCount(); i++) {
                    String k = (String) bodyFormModel.getValueAt(i, 0);
                    String v = (String) bodyFormModel.getValueAt(i, 1);
                    if (k != null && !k.trim().isEmpty()) {
                        fields.add(new ApiRequest.Body.FormField(k, v != null ? v : ""));
                    }
                }
                if ("urlencoded".equals(bodyMode)) req.body.urlencoded = fields;
                else req.body.formdata = fields;
            }
        }
        // Scripts
        String preScript = preScriptArea.getText().trim();
        if (!preScript.isEmpty()) req.preRequestScripts.add(new ApiRequest.Script("js", preScript));
        String postScript = postScriptArea.getText().trim();
        if (!postScript.isEmpty()) req.postResponseScripts.add(new ApiRequest.Script("js", postScript));

        return req;
    }

    public JTextField getUrlField() { return urlField; }
    public JComboBox<String> getMethodBox() { return methodBox; }

    private void parseQueryToTable(String url) {
        if (url == null) return;
        int q = url.indexOf('?');
        if (q < 0 || q + 1 >= url.length()) return;
        String query = url.substring(q + 1);
        // Preserve fragment if present in query portion (shouldn't happen normally)
        int frag = query.indexOf('#');
        if (frag >= 0) query = query.substring(0, frag);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) {
                paramsModel.addRow(new Object[]{pair, ""});
            } else {
                String key = pair.substring(0, eq);
                String val = eq + 1 < pair.length() ? pair.substring(eq + 1) : "";
                try {
                    key = java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8);
                    val = java.net.URLDecoder.decode(val, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    // keep raw if decoding fails
                }
                paramsModel.addRow(new Object[]{key, val});
            }
        }
    }

    private String rebuildUrlWithParams(String urlBase, DefaultTableModel model) {
        if (urlBase == null) urlBase = "";
        // Strip existing query and fragment from urlField
        String fragment = "";
        int frag = urlBase.indexOf('#');
        if (frag >= 0) {
            fragment = urlBase.substring(frag);
            urlBase = urlBase.substring(0, frag);
        }
        int q = urlBase.indexOf('?');
        if (q >= 0) {
            urlBase = urlBase.substring(0, q);
        }
        StringBuilder sb = new StringBuilder(urlBase);
        boolean first = true;
        for (int i = 0; i < model.getRowCount(); i++) {
            String key = (String) model.getValueAt(i, 0);
            String value = (String) model.getValueAt(i, 1);
            if (key == null || key.trim().isEmpty()) continue;
            if (first) {
                sb.append('?');
                first = false;
            } else {
                sb.append('&');
            }
            try {
                sb.append(java.net.URLEncoder.encode(key.trim(), java.nio.charset.StandardCharsets.UTF_8));
                if (value != null && !value.isEmpty()) {
                    sb.append('=');
                    sb.append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                // fallback raw
                sb.append(key.trim());
                if (value != null && !value.isEmpty()) {
                    sb.append('=').append(value);
                }
            }
        }
        sb.append(fragment);
        return sb.toString();
    }

    private void clearAll() {
        paramsModel.setRowCount(0);
        authTypeBox.setSelectedItem("none");
        rebuildAuthFields();
        headersModel.setRowCount(0);
        bodyModeBox.setSelectedItem("none");
        updateBodyMode();
        bodyRawArea.setText("");
        bodyFormModel.setRowCount(0);
        preScriptArea.setText("");
        postScriptArea.setText("");
    }
}
