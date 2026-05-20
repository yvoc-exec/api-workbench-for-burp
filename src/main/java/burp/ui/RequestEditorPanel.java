package burp.ui;

import burp.models.ApiRequest;
import burp.parser.VariableResolver;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
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
    private String bodyModeInternal = "none";
    private final Map<String, JRadioButton> bodyModeButtons = new LinkedHashMap<>();
    private JPanel bodyContentPanel;
    private JTextArea bodyRawArea;
    private JTable bodyFormTable;
    private DefaultTableModel bodyFormModel;

    // Scripts
    private JTextArea preScriptArea;
    private JTextArea postScriptArea;

    // Resolved mirror
    private JTextArea resolvedViewArea;
    private Map<String, String> runtimeVariables = new HashMap<>();

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
        attachLiveRefreshListeners();
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
            commitAllEdits();
            if (sendActionListener != null) {
                sendActionListener.onSend();
            }
        });
        sendDropdownBtn = new JButton("\u25BC");
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
        tabs.addTab("Resolved", createResolvedPanel());
        return tabs;
    }

    private JPanel createParamsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        paramsModel = new DefaultTableModel(new Object[]{"Key", "Value"}, 0);
        paramsTable = new JTable(paramsModel);
        paramsTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        paramsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JScrollPane(paramsTable), BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addBtn = new JButton("+");
        addBtn.addActionListener(e -> {
            commitAllEdits();
            paramsModel.addRow(new Object[]{"", ""});
        });
        JButton delBtn = new JButton("-");
        delBtn.addActionListener(e -> {
            commitAllEdits();
            int row = resolveTargetRow(paramsTable);
            if (row >= 0) paramsModel.removeRow(row);
        });
        btnPanel.add(addBtn);
        btnPanel.add(delBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createAuthPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        authTypeBox = new JComboBox<>(new String[]{"inherit", "none", "bearer", "basic", "apikey", "oauth2"});
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
        String selectedType = (String) authTypeBox.getSelectedItem();
        boolean readOnly = "inherit".equals(selectedType);
        String displayType = selectedType;
        if (readOnly) {
            displayType = currentRequest != null && currentRequest.auth != null && currentRequest.auth.type != null
                    ? currentRequest.auth.type.toLowerCase()
                    : null;
        }
        ApiRequest.Auth sourceAuth = resolveAuthSourceForEditor(selectedType, displayType);
        if ("bearer".equals(displayType)) {
            addAuthField("token", "Token", readOnly);
        } else if ("basic".equals(displayType)) {
            addAuthField("username", "Username", readOnly);
            addAuthField("password", "Password", readOnly);
        } else if ("apikey".equals(displayType)) {
            addAuthField("key", "Key Name", readOnly);
            addAuthField("value", "Key Value", readOnly);
            addAuthField("in", "In (header/query/cookie)", readOnly);
        } else if ("oauth2".equals(displayType)) {
            addAuthField("grantType", "Grant Type", readOnly);
            addAuthField("accessTokenUrl", "Token URL", readOnly);
            addAuthField("authorizationUrl", "Authorization URL", readOnly);
            addAuthField("redirectUri", "Redirect URI", readOnly);
            addAuthField("clientId", "Client ID", readOnly);
            addAuthField("clientSecret", "Client Secret", readOnly);
            addAuthField("username", "Username", readOnly);
            addAuthField("password", "Password", readOnly);
            addAuthField("scope", "Scope", readOnly);
            addAuthField("refreshToken", "Refresh Token", readOnly);
            addAuthField("code", "Authorization Code", readOnly);
            addAuthField("codeVerifier", "Code Verifier", readOnly);
            addAuthField("clientAuth", "Client Auth (body/basic/none)", readOnly);
            addAuthField("accessToken", "Access Token", readOnly);
        }
        populateAuthFields(sourceAuth);
        authFieldsPanel.revalidate();
        authFieldsPanel.repaint();
        refreshResolvedMirror();
    }

    private void addAuthField(String key, String label, boolean readOnly) {
        authFieldsPanel.add(new JLabel(label));
        JTextField field = new JTextField();
        field.setEnabled(!readOnly);
        field.getDocument().addDocumentListener(new SimpleDocumentListener(this::refreshResolvedMirror));
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
        headersTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        headersTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JScrollPane(headersTable), BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addBtn = new JButton("+");
        addBtn.addActionListener(e -> {
            commitAllEdits();
            headersModel.addRow(new Object[]{"", "", true});
        });
        JButton delBtn = new JButton("-");
        delBtn.addActionListener(e -> {
            commitAllEdits();
            int row = resolveTargetRow(headersTable);
            if (row >= 0) headersModel.removeRow(row);
        });
        btnPanel.add(addBtn);
        btnPanel.add(delBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createBodyPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.add(createBodyModeRadioPanel(), BorderLayout.NORTH);

        bodyContentPanel = new JPanel(new CardLayout());

        // Raw
        bodyRawArea = new JTextArea(8, 40);
        bodyRawArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        bodyContentPanel.add(new JScrollPane(bodyRawArea), "raw");

        // Form
        bodyFormModel = new DefaultTableModel(new Object[]{"Key", "Value"}, 0);
        bodyFormTable = new JTable(bodyFormModel);
        bodyFormTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        bodyFormTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel formPanel = new JPanel(new BorderLayout());
        formPanel.add(new JScrollPane(bodyFormTable), BorderLayout.CENTER);
        JPanel formBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addFormBtn = new JButton("+");
        addFormBtn.addActionListener(e -> {
            commitAllEdits();
            bodyFormModel.addRow(new Object[]{"", ""});
        });
        JButton delFormBtn = new JButton("-");
        delFormBtn.addActionListener(e -> {
            commitAllEdits();
            int row = resolveTargetRow(bodyFormTable);
            if (row >= 0) bodyFormModel.removeRow(row);
        });
        formBtnPanel.add(addFormBtn);
        formBtnPanel.add(delFormBtn);
        formPanel.add(formBtnPanel, BorderLayout.SOUTH);
        bodyContentPanel.add(formPanel, "form");

        // Empty placeholder
        JLabel noBodyLabel = new JLabel("No body");
        noBodyLabel.setHorizontalAlignment(SwingConstants.LEFT);
        Border margin = BorderFactory.createEmptyBorder(8, 8, 8, 8);
        noBodyLabel.setBorder(margin);
        bodyContentPanel.add(noBodyLabel, "none");

        panel.add(bodyContentPanel, BorderLayout.CENTER);
        setBodyModeInternal("none");
        return panel;
    }

    private JPanel createBodyModeRadioPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));

        LinkedHashMap<String, String> uiToInternal = new LinkedHashMap<>();
        uiToInternal.put("none", "none");
        uiToInternal.put("form-data", "formdata");
        uiToInternal.put("x-www-form-urlencoded", "urlencoded");
        uiToInternal.put("raw", "raw");
        uiToInternal.put("binary", "file");
        uiToInternal.put("GraphQL", "graphql");

        ButtonGroup group = new ButtonGroup();
        for (Map.Entry<String, String> entry : uiToInternal.entrySet()) {
            String label = entry.getKey();
            String mode = entry.getValue();
            JRadioButton btn = new JRadioButton(label);
            btn.addActionListener(e -> {
                bodyModeInternal = mode;
                updateBodyMode();
            });
            group.add(btn);
            panel.add(btn);
            bodyModeButtons.put(mode, btn);
        }
        return panel;
    }

    private String getBodyModeInternal() {
        return bodyModeInternal != null ? bodyModeInternal : "none";
    }

    private void setBodyModeInternal(String mode) {
        if (mode == null || mode.isEmpty()) mode = "none";
        if (!bodyModeButtons.containsKey(mode)) mode = "none";
        bodyModeInternal = mode;
        JRadioButton btn = bodyModeButtons.get(mode);
        if (btn != null && !btn.isSelected()) {
            btn.setSelected(true);
        }
        updateBodyMode();
    }

    private void updateBodyMode() {
        if (bodyContentPanel == null) return;
        String mode = getBodyModeInternal();
        CardLayout cl = (CardLayout) bodyContentPanel.getLayout();
        if ("raw".equals(mode)) {
            cl.show(bodyContentPanel, "raw");
        } else if ("none".equals(mode)) {
            cl.show(bodyContentPanel, "none");
        } else if ("graphql".equals(mode) || "file".equals(mode)) {
            cl.show(bodyContentPanel, "raw");
        } else {
            cl.show(bodyContentPanel, "form");
        }
        refreshResolvedMirror();
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

    private JPanel createResolvedPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        resolvedViewArea = new JTextArea(12, 40);
        resolvedViewArea.setEditable(false);
        resolvedViewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(resolvedViewArea), BorderLayout.CENTER);
        return panel;
    }

    private void attachLiveRefreshListeners() {
        methodBox.addActionListener(e -> refreshResolvedMirror());
        urlField.getDocument().addDocumentListener(new SimpleDocumentListener(this::refreshResolvedMirror));
        headersModel.addTableModelListener(e -> refreshResolvedMirror());
        paramsModel.addTableModelListener(e -> refreshResolvedMirror());
        bodyFormModel.addTableModelListener(e -> refreshResolvedMirror());
        bodyRawArea.getDocument().addDocumentListener(new SimpleDocumentListener(this::refreshResolvedMirror));
        preScriptArea.getDocument().addDocumentListener(new SimpleDocumentListener(this::refreshResolvedMirror));
        postScriptArea.getDocument().addDocumentListener(new SimpleDocumentListener(this::refreshResolvedMirror));
    }

    private static class SimpleDocumentListener implements DocumentListener {
        private final Runnable onChange;
        SimpleDocumentListener(Runnable onChange) { this.onChange = onChange; }
        @Override public void insertUpdate(DocumentEvent e) { onChange.run(); }
        @Override public void removeUpdate(DocumentEvent e) { onChange.run(); }
        @Override public void changedUpdate(DocumentEvent e) { onChange.run(); }
    }

    public void loadRequest(ApiRequest req) {
        this.currentRequest = req;
        if (req == null) {
            methodBox.setSelectedItem("GET");
            urlField.setText("");
            clearAll();
            refreshResolvedMirror();
            return;
        }
        methodBox.setSelectedItem(req.method != null ? req.method.toUpperCase() : "GET");
        urlField.setText(req.url != null ? req.url : "");

        // Params from URL
        paramsModel.setRowCount(0);
        parseQueryToTable(req.url);

        // Auth
        authTypeBox.setSelectedItem(resolveEditorAuthMode(req));
        rebuildAuthFields();

        // Headers
        headersModel.setRowCount(0);
        if (req.headers != null) {
            for (ApiRequest.Header h : req.headers) {
                headersModel.addRow(new Object[]{h.key, h.value, !h.disabled});
            }
        }

        // Body
        bodyRawArea.setText("");
        bodyFormModel.setRowCount(0);
        if (req.body != null) {
            setBodyModeInternal(req.body.mode != null ? req.body.mode : "none");
            if ("raw".equals(req.body.mode) && req.body.raw != null) {
                bodyRawArea.setText(req.body.raw);
            }
            if ("graphql".equals(req.body.mode) && req.body.graphql != null) {
                bodyRawArea.setText(req.body.graphql.query != null ? req.body.graphql.query : "");
            }
            if ("file".equals(req.body.mode) && req.body.raw != null) {
                bodyRawArea.setText(req.body.raw);
            }
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
            setBodyModeInternal("none");
        }

        // Scripts
        preScriptArea.setText("");
        postScriptArea.setText("");
        if (req.preRequestScripts != null) {
            for (int i = 0; i < req.preRequestScripts.size(); i++) {
                ApiRequest.Script s = req.preRequestScripts.get(i);
                if (s.exec != null) {
                    if (i > 0 && !preScriptArea.getText().endsWith("\n")) preScriptArea.append("\n");
                    preScriptArea.append(s.exec);
                }
            }
        }
        if (req.postResponseScripts != null) {
            for (int i = 0; i < req.postResponseScripts.size(); i++) {
                ApiRequest.Script s = req.postResponseScripts.get(i);
                if (s.exec != null) {
                    if (i > 0 && !postScriptArea.getText().endsWith("\n")) postScriptArea.append("\n");
                    postScriptArea.append(s.exec);
                }
            }
        }
        refreshResolvedMirror();
    }

    public ApiRequest getCurrentRequest() { return currentRequest; }
    public burp.models.ApiCollection getCurrentCollection() { return currentCollection; }
    public void setCurrentCollection(burp.models.ApiCollection col) {
        this.currentCollection = col;
        refreshResolvedMirror();
    }

    public void setRuntimeVariables(Map<String, String> vars) {
        runtimeVariables = vars != null ? new HashMap<>(vars) : new HashMap<>();
        refreshResolvedMirror();
    }

    public void commitAllEdits() {
        commitTableEdit(paramsTable);
        commitTableEdit(headersTable);
        commitTableEdit(bodyFormTable);
    }

    private void commitTableEdit(JTable table) {
        if (table != null && table.isEditing() && table.getCellEditor() != null) {
            try {
                table.getCellEditor().stopCellEditing();
            } catch (Exception ignored) {
                try {
                    table.getCellEditor().cancelCellEditing();
                } catch (Exception ignored2) {
                    // no-op
                }
            }
        }
    }

    private int resolveTargetRow(JTable table) {
        if (table == null) return -1;
        int row = table.getSelectedRow();
        if (row < 0 && table.isEditing()) {
            row = table.getEditingRow();
        }
        return row;
    }

    public ApiRequest buildRequestFromUI() {
        commitAllEdits();
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
        String authMode = (String) authTypeBox.getSelectedItem();
        req.authOverrideMode = selectionToOverrideMode(authMode);
        if ("inherit".equals(req.authOverrideMode)) {
            req.explicitAuth = null;
        } else {
            req.explicitAuth = buildAuthFromFields(authMode);
        }
        burp.utils.AuthInheritanceResolver.resolveRequestAuth(currentCollection, req);

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
        String bodyMode = getBodyModeInternal();
        if (!"none".equals(bodyMode)) {
            req.body = new ApiRequest.Body();
            req.body.mode = bodyMode;
            if ("raw".equals(bodyMode)) {
                req.body.raw = bodyRawArea.getText();
            } else if ("graphql".equals(bodyMode)) {
                ApiRequest.Body.GraphQL graphQL = new ApiRequest.Body.GraphQL();
                if (currentRequest.body != null && currentRequest.body.graphql != null) {
                    graphQL.variables = currentRequest.body.graphql.variables;
                }
                graphQL.query = bodyRawArea.getText();
                req.body.graphql = graphQL;
                if (currentRequest.body != null) {
                    req.body.contentType = currentRequest.body.contentType;
                }
            } else if ("file".equals(bodyMode)) {
                if (currentRequest.body != null) {
                    req.body.raw = bodyRawArea.getText();
                    req.body.contentType = currentRequest.body.contentType;
                    req.body.formdata = currentRequest.body.formdata != null ? new ArrayList<>(currentRequest.body.formdata) : new ArrayList<>();
                    req.body.urlencoded = currentRequest.body.urlencoded != null ? new ArrayList<>(currentRequest.body.urlencoded) : new ArrayList<>();
                }
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

    static void applyAuthMetadata(ApiRequest target, ApiRequest source, String authType) {
        if (target == null) {
            return;
        }

        String normalizedSelection = normalizeEditorSelection(authType);
        String overrideMode = selectionToOverrideMode(normalizedSelection);
        if (source != null) {
            target.authSource = source.authSource;
            target.authInherited = source.authInherited;
            target.authExplicitlyDisabled = source.authExplicitlyDisabled;
        }

        target.authOverrideMode = overrideMode;
        if ("inherit".equals(overrideMode)) {
            target.explicitAuth = null;
            if (source != null && source.auth != null) {
                target.auth = burp.utils.AuthInheritanceResolver.copyAuth(source.auth);
            }
            return;
        }

        if (!"none".equalsIgnoreCase(overrideMode)) {
            boolean sameAuthType = source != null
                && source.auth != null
                && source.auth.type != null
                && normalizedSelection.equalsIgnoreCase(source.auth.type);
            if (!sameAuthType) {
                target.authInherited = false;
                target.authExplicitlyDisabled = false;
                target.authSource = "request: " + target.name;
            }
            target.explicitAuth = target.auth != null ? burp.utils.AuthInheritanceResolver.copyAuth(target.auth) : null;
            return;
        }

        boolean preserveNoAuth = source != null
            && isMeaningfulAuthSource(source.authSource)
            && (source.auth == null || source.auth.type == null || "none".equalsIgnoreCase(source.auth.type));
        if (!preserveNoAuth) {
            target.authInherited = false;
            target.authExplicitlyDisabled = true;
            target.authSource = "request: " + target.name;
        }
        target.explicitAuth = target.auth != null ? burp.utils.AuthInheritanceResolver.copyAuth(target.auth) : null;
    }

    private static String normalizeEditorSelection(String authType) {
        if (authType == null || authType.isBlank()) {
            return "inherit";
        }
        String normalized = authType.trim().toLowerCase(Locale.ROOT);
        if ("inherit".equals(normalized) || "none".equals(normalized) || "bearer".equals(normalized)
                || "basic".equals(normalized) || "apikey".equals(normalized) || "oauth2".equals(normalized)) {
            return normalized;
        }
        return "inherit";
    }

    private static String selectionToOverrideMode(String authType) {
        String normalized = normalizeEditorSelection(authType);
        if ("inherit".equals(normalized) || "none".equals(normalized)) {
            return normalized;
        }
        return "explicit";
    }

    private String resolveEditorAuthMode(ApiRequest req) {
        if (req == null) {
            return "inherit";
        }
        String mode = req.authOverrideMode != null ? req.authOverrideMode.trim().toLowerCase(Locale.ROOT) : "";
        if ("inherit".equals(mode) || "explicit".equals(mode) || "none".equals(mode)) {
            if ("explicit".equals(mode) && req.explicitAuth != null && req.explicitAuth.type != null) {
                return req.explicitAuth.type.toLowerCase(Locale.ROOT);
            }
            if ("none".equals(mode)) {
                return "none";
            }
            if ("inherit".equals(mode)) {
                return "inherit";
            }
        }
        if (req.explicitAuth != null && req.explicitAuth.type != null) {
            return req.explicitAuth.type.toLowerCase(Locale.ROOT);
        }
        if (req.auth != null && req.auth.type != null) {
            if ("none".equalsIgnoreCase(req.auth.type)) {
                return req.authExplicitlyDisabled ? "none" : "inherit";
            }
            return req.authInherited ? "inherit" : req.auth.type.toLowerCase(Locale.ROOT);
        }
        return "inherit";
    }

    private ApiRequest.Auth resolveAuthSourceForEditor(String selectedMode, String displayType) {
        if (currentRequest == null) {
            return null;
        }
        if ("inherit".equals(selectedMode)) {
            return currentRequest.auth;
        }
        if ("none".equals(displayType)) {
            if (currentRequest.explicitAuth != null && "none".equalsIgnoreCase(currentRequest.explicitAuth.type)) {
                return currentRequest.explicitAuth;
            }
            return currentRequest.auth;
        }
        if (currentRequest.explicitAuth != null && displayType != null
                && displayType.equalsIgnoreCase(currentRequest.explicitAuth.type)) {
            return currentRequest.explicitAuth;
        }
        if (currentRequest.auth != null && displayType != null
                && displayType.equalsIgnoreCase(currentRequest.auth.type)) {
            return currentRequest.auth;
        }
        return currentRequest.explicitAuth != null ? currentRequest.explicitAuth : currentRequest.auth;
    }

    private void populateAuthFields(ApiRequest.Auth sourceAuth) {
        if (sourceAuth == null || sourceAuth.properties == null || sourceAuth.properties.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : sourceAuth.properties.entrySet()) {
            JTextField f = authFields.get(e.getKey());
            if (f != null) {
                f.setText(e.getValue());
            }
        }
    }

    private ApiRequest.Auth buildAuthFromFields(String authType) {
        String normalized = normalizeEditorSelection(authType);
        if ("inherit".equals(normalized)) {
            return null;
        }
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = normalized;
        for (Map.Entry<String, JTextField> e : authFields.entrySet()) {
            String val = e.getValue().getText().trim();
            if (!val.isEmpty()) {
                auth.properties.put(e.getKey(), val);
            }
        }
        return auth;
    }

    static boolean isMeaningfulAuthSource(String source) {
        return source != null && !source.isBlank() && !"none".equalsIgnoreCase(source.trim());
    }

    private void refreshResolvedMirror() {
        if (resolvedViewArea == null) return;
        if (currentRequest == null) {
            resolvedViewArea.setText("");
            return;
        }
        VariableResolver vr = new VariableResolver();
        if (currentCollection != null) {
            vr.addEnvironmentVariables(currentCollection);
            vr.addCollectionVariables(currentCollection);
            vr.addFolderVariables(currentCollection, currentRequest);
            if (currentCollection.runtimeOAuth2 != null && !currentCollection.runtimeOAuth2.isEmpty()) {
                vr.addAll(currentCollection.runtimeOAuth2);
            }
            if (currentCollection.runtimeVars != null && !currentCollection.runtimeVars.isEmpty()) {
                vr.addAll(currentCollection.runtimeVars);
            }
        }
        if (runtimeVariables != null && !runtimeVariables.isEmpty()) {
            vr.addAll(runtimeVariables);
        }
        vr.addRequestVariables(currentRequest);

        StringBuilder out = new StringBuilder();
        out.append("Resolved URL\n");
        out.append("------------\n");
        out.append(vr.resolve(rebuildUrlWithParams(urlField.getText(), paramsModel))).append("\n\n");

        out.append("Resolved Auth\n");
        out.append("-------------\n");
        String authType = (String) authTypeBox.getSelectedItem();
        out.append("type=").append(authType).append("\n");
        for (Map.Entry<String, JTextField> e : authFields.entrySet()) {
            String v = e.getValue().getText();
            if (v != null && !v.isEmpty()) {
                out.append(e.getKey()).append("=").append(vr.resolve(v)).append("\n");
            }
        }

        out.append("\nResolved Headers\n");
        out.append("----------------\n");
        for (int i = 0; i < headersModel.getRowCount(); i++) {
            String key = (String) headersModel.getValueAt(i, 0);
            String value = (String) headersModel.getValueAt(i, 1);
            Boolean enabled = (Boolean) headersModel.getValueAt(i, 2);
            if (key != null && !key.trim().isEmpty() && (enabled == null || enabled)) {
                out.append(vr.resolve(key)).append(": ").append(vr.resolve(value != null ? value : "")).append("\n");
            }
        }

        out.append("\nResolved Body\n");
        out.append("-------------\n");
        String bodyMode = getBodyModeInternal();
        out.append("mode=").append(bodyMode).append("\n");
        if ("raw".equals(bodyMode) || "graphql".equals(bodyMode) || "file".equals(bodyMode)) {
            out.append(vr.resolve(bodyRawArea.getText())).append("\n");
        } else if ("urlencoded".equals(bodyMode) || "formdata".equals(bodyMode)) {
            for (int i = 0; i < bodyFormModel.getRowCount(); i++) {
                String k = (String) bodyFormModel.getValueAt(i, 0);
                String v = (String) bodyFormModel.getValueAt(i, 1);
                if (k != null && !k.trim().isEmpty()) {
                    out.append(vr.resolve(k)).append("=").append(vr.resolve(v != null ? v : "")).append("\n");
                }
            }
        }

        resolvedViewArea.setText(out.toString());
        resolvedViewArea.setCaretPosition(0);
    }

    public JTextField getUrlField() { return urlField; }
    public JComboBox<String> getMethodBox() { return methodBox; }

    private void parseQueryToTable(String url) {
        if (url == null) return;
        int q = url.indexOf('?');
        if (q < 0 || q + 1 >= url.length()) return;
        String query = url.substring(q + 1);
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
        setBodyModeInternal("none");
        bodyRawArea.setText("");
        bodyFormModel.setRowCount(0);
        preScriptArea.setText("");
        postScriptArea.setText("");
        refreshResolvedMirror();
    }
}
