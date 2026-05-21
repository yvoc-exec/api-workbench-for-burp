package burp.ui;

import burp.models.ApiRequest;
import burp.parser.VariableResolver;
import burp.utils.RequestBuilder;

import javax.swing.*;
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
    private RequestEditorAuthSupport.AuthUi authUi;

    // Headers
    private DefaultTableModel headersModel;
    private JTable headersTable;

    // Body
    private RequestEditorBodySupport.BodyUi bodyUi;
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
    private burp.utils.RequestBuilder requestBuilder;
    private Set<String> editorSynthesizedKeys = new LinkedHashSet<>();
    private Map<String, String> editorSynthesizedValues = new LinkedHashMap<>();
    private boolean refreshingHeaders = false;
    private boolean loadingRequest = false;

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

    public void setRequestBuilder(burp.utils.RequestBuilder requestBuilder) {
        this.requestBuilder = requestBuilder;
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
        paramsTable = RequestEditorTableSupport.createEditableTable(paramsModel);
        panel.add(new JScrollPane(paramsTable), BorderLayout.CENTER);
        panel.add(RequestEditorTableSupport.createAddRemovePanel(paramsTable, paramsModel, () -> new Object[]{"", ""}), BorderLayout.SOUTH);
        RequestEditorStateMapper.ensureStarterRow(paramsModel);
        return panel;
    }

    private JPanel createAuthPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        authTypeBox = new JComboBox<>(new String[]{"inherit", "none", "bearer", "basic", "apikey", "oauth2"});
        authTypeBox.addActionListener(e -> {
            rebuildAuthFields();
            refreshAll();
        });
        panel.add(authTypeBox, BorderLayout.NORTH);
        JPanel authFieldsPanel = new JPanel(new GridLayout(0, 2, 5, 5));
        authUi = new RequestEditorAuthSupport.AuthUi(authFieldsPanel, this::refreshAll);
        panel.add(authFieldsPanel, BorderLayout.CENTER);
        rebuildAuthFields();
        return panel;
    }

    private void rebuildAuthFields() {
        String selectedType = (String) authTypeBox.getSelectedItem();
        ApiRequest.Auth sourceAuth = resolveAuthSourceForEditor(selectedType, selectedType);
        RequestEditorAuthSupport.rebuildAuthFields(authUi, selectedType, currentRequest, sourceAuth);
    }

    private JPanel createHeadersPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        headersModel = new DefaultTableModel(new Object[]{"Key", "Value", "Enabled"}, 0) {
            @Override public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 2 ? Boolean.class : String.class;
            }
        };
        headersTable = RequestEditorTableSupport.createEditableTable(headersModel);
        panel.add(new JScrollPane(headersTable), BorderLayout.CENTER);
        panel.add(RequestEditorTableSupport.createAddRemovePanel(headersTable, headersModel, () -> new Object[]{"", "", true}), BorderLayout.SOUTH);
        RequestEditorStateMapper.ensureStarterRow(headersModel);
        return panel;
    }

    private JPanel createBodyPanel() {
        bodyUi = RequestEditorBodySupport.createBodyUi(this::refreshAll);
        bodyRawArea = bodyUi.bodyRawArea;
        bodyFormTable = bodyUi.bodyFormTable;
        bodyFormModel = bodyUi.bodyFormModel;
        return (JPanel) RequestEditorBodySupport.panel(bodyUi);
    }

    private String getBodyModeInternal() {
        return RequestEditorBodySupport.getBodyModeInternal(bodyUi);
    }

    private void setBodyModeInternal(String mode) {
        RequestEditorBodySupport.setBodyModeInternal(bodyUi, mode);
    }

    private void updateBodyMode() {
        RequestEditorBodySupport.updateBodyMode(bodyUi);
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
        methodBox.addActionListener(e -> refreshAllIfReady());
        urlField.getDocument().addDocumentListener(new SimpleDocumentListener(this::refreshAllIfReady));
        headersModel.addTableModelListener(e -> refreshResolvedMirrorIfReady());
        paramsModel.addTableModelListener(e -> refreshAllIfReady());
        bodyFormModel.addTableModelListener(e -> refreshAllIfReady());
        bodyRawArea.getDocument().addDocumentListener(new SimpleDocumentListener(this::refreshAllIfReady));
        preScriptArea.getDocument().addDocumentListener(new SimpleDocumentListener(this::refreshResolvedMirrorIfReady));
        postScriptArea.getDocument().addDocumentListener(new SimpleDocumentListener(this::refreshResolvedMirrorIfReady));
    }

    private void refreshAllIfReady() {
        if (!loadingRequest) {
            refreshAll();
        }
    }

    private void refreshResolvedMirrorIfReady() {
        if (!loadingRequest) {
            refreshResolvedMirror();
        }
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
        loadingRequest = true;
        try {
            RequestEditorStateMapper.Context ctx = createStateMapperContext();
            RequestEditorStateMapper.loadRequest(req, ctx);
            this.editorSynthesizedKeys = new LinkedHashSet<>(ctx.synthesizedKeys);
            this.editorSynthesizedValues = new LinkedHashMap<>(ctx.synthesizedValues);
        } finally {
            loadingRequest = false;
        }
        refreshAll();
    }

    public ApiRequest getCurrentRequest() { return currentRequest; }
    public burp.models.ApiCollection getCurrentCollection() { return currentCollection; }
    public void setCurrentCollection(burp.models.ApiCollection col) {
        if (this.currentCollection == col) {
            return;
        }
        this.currentCollection = col;
        refreshAll();
    }

    public void setRuntimeVariables(Map<String, String> vars) {
        Map<String, String> next = vars != null ? new HashMap<>(vars) : new HashMap<>();
        if (Objects.equals(runtimeVariables, next)) {
            return;
        }
        runtimeVariables = next;
        refreshAll();
    }

    private void refreshAll() {
        refreshEffectiveHeaders();
        refreshResolvedMirror();
    }

    public void commitAllEdits() {
        RequestEditorTableSupport.commitAllEdits(paramsTable, headersTable, bodyFormTable);
    }

    public ApiRequest buildRequestFromUI() {
        commitAllEdits();
        return RequestEditorStateMapper.buildRequest(createStateMapperContext());
    }

    static void applyAuthMetadata(ApiRequest target, ApiRequest source, String authType) {
        if (target == null) {
            return;
        }

        String normalizedSelection = RequestEditorAuthSupport.normalizeEditorSelection(authType);
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

    static String selectionToOverrideMode(String authType) {
        String normalized = RequestEditorAuthSupport.normalizeEditorSelection(authType);
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

    private ApiRequest.Auth buildAuthFromFields(String authType) {
        return RequestEditorAuthSupport.buildAuthFromFields(authUi, authType);
    }

    static boolean isMeaningfulAuthSource(String source) {
        return source != null && !source.isBlank() && !"none".equalsIgnoreCase(source.trim());
    }

    private void refreshEffectiveHeaders() {
        if (currentRequest == null) return;
        if (refreshingHeaders) return;
        refreshingHeaders = true;
        try {
            // 1. Capture operator intent from current model
            Set<String> suppressedKeys = new HashSet<>();
            Map<String, String> editedSynthValues = new HashMap<>();
            Map<String, Integer> rowIndexByKey = new HashMap<>();

            for (int i = 0; i < headersModel.getRowCount(); i++) {
                String key = (String) headersModel.getValueAt(i, 0);
                String value = (String) headersModel.getValueAt(i, 1);
                Boolean enabled = (Boolean) headersModel.getValueAt(i, 2);
                if (key == null || key.trim().isEmpty()) continue;

                String lowerKey = key.toLowerCase(Locale.ROOT);
                rowIndexByKey.put(lowerKey, i);

                boolean isSynth = editorSynthesizedKeys.contains(lowerKey);
                String synthValue = editorSynthesizedValues.get(lowerKey);
                boolean isEdited = isSynth && (synthValue == null || !synthValue.equals(value));

                if (isSynth && !isEdited && !Boolean.TRUE.equals(enabled)) {
                    suppressedKeys.add(lowerKey);
                } else if (isSynth && isEdited) {
                    editedSynthValues.put(lowerKey, value);
                }
            }

            // 2. Build temporary request from current UI state while preserving
            // request metadata required for folder vars and body-mode-specific synthesis.
            ApiRequest temp = new ApiRequest();
            if (currentRequest != null) {
                temp.name = currentRequest.name;
                temp.path = currentRequest.path;
                temp.sourceCollection = currentRequest.sourceCollection;
                temp.id = currentRequest.id;
                temp.sequenceOrder = currentRequest.sequenceOrder;
            }
            temp.method = (String) methodBox.getSelectedItem();
            temp.url = RequestEditorStateMapper.rebuildUrlWithParams(urlField.getText(), paramsModel);

            String selectedAuthType = (String) authTypeBox.getSelectedItem();
            if ("inherit".equals(selectedAuthType) && currentRequest != null && currentRequest.auth != null) {
                temp.auth = currentRequest.auth;
            } else {
                temp.auth = buildAuthFromFields(selectedAuthType);
            }

            String bodyMode = getBodyModeInternal();
            if (!"none".equals(bodyMode)) {
                temp.body = new ApiRequest.Body();
                temp.body.mode = bodyMode;
                if ("raw".equals(bodyMode)) {
                    temp.body.raw = bodyRawArea.getText();
                } else if ("graphql".equals(bodyMode)) {
                    ApiRequest.Body.GraphQL graphQL = new ApiRequest.Body.GraphQL();
                    if (currentRequest != null && currentRequest.body != null && currentRequest.body.graphql != null) {
                        graphQL.variables = currentRequest.body.graphql.variables;
                    }
                    graphQL.query = bodyRawArea.getText();
                    temp.body.graphql = graphQL;
                    if (currentRequest != null && currentRequest.body != null) {
                        temp.body.contentType = currentRequest.body.contentType;
                    }
                } else if ("file".equals(bodyMode)) {
                    temp.body.raw = bodyRawArea.getText();
                    if (currentRequest != null && currentRequest.body != null) {
                        temp.body.contentType = currentRequest.body.contentType;
                        temp.body.formdata = currentRequest.body.formdata != null ? new ArrayList<>(currentRequest.body.formdata) : new ArrayList<>();
                        temp.body.urlencoded = currentRequest.body.urlencoded != null ? new ArrayList<>(currentRequest.body.urlencoded) : new ArrayList<>();
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
                    if ("urlencoded".equals(bodyMode)) {
                        temp.body.urlencoded = fields;
                    } else {
                        temp.body.formdata = fields;
                    }
                }
            }

            temp.headers = new ArrayList<>();
            for (int i = 0; i < headersModel.getRowCount(); i++) {
                String key = (String) headersModel.getValueAt(i, 0);
                String value = (String) headersModel.getValueAt(i, 1);
                Boolean enabled = (Boolean) headersModel.getValueAt(i, 2);
                if (key == null || key.trim().isEmpty()) continue;
                String lowerKey = key.toLowerCase(Locale.ROOT);
                boolean isSynth = editorSynthesizedKeys.contains(lowerKey);
                String synthValue = editorSynthesizedValues.get(lowerKey);
                boolean isEdited = isSynth && (synthValue == null || !synthValue.equals(value));
                if (isSynth && !isEdited) {
                    continue; // unmodified synthesized rows are recomputed live
                }
                temp.headers.add(new ApiRequest.Header(key, value != null ? value : "", !Boolean.TRUE.equals(enabled)));
            }

            // 3. Compute effective headers with current collection context
            VariableResolver vr = new VariableResolver();
            if (currentCollection != null) {
                vr.addEnvironmentVariables(currentCollection);
                vr.addCollectionVariables(currentCollection);
                vr.addFolderVariables(currentCollection, temp);
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
            vr.addRequestVariables(temp);

            RequestBuilder builder = requestBuilder != null ? requestBuilder : new RequestBuilder(null, null);
            List<Map.Entry<String, String>> effective = builder.buildEffectiveHeaders(temp, vr);

            // 4. Update existing rows and track what needs to be added
            Set<String> newSynthKeys = new LinkedHashSet<>();
            Map<String, String> newSynthValues = new LinkedHashMap<>();
            Set<String> seenInEffective = new HashSet<>();

            for (Map.Entry<String, String> e : effective) {
                String key = e.getKey();
                String lowerKey = key.toLowerCase(Locale.ROOT);
                seenInEffective.add(lowerKey);

                if (suppressedKeys.contains(lowerKey)) {
                    Integer idx = rowIndexByKey.get(lowerKey);
                    if (idx != null) {
                        headersModel.setValueAt(e.getValue(), idx, 1);
                    }
                    newSynthKeys.add(lowerKey);
                    newSynthValues.put(lowerKey, e.getValue());
                    continue;
                }

                if (editedSynthValues.containsKey(lowerKey)) {
                    continue; // edited - keep as-is
                }

                Integer idx = rowIndexByKey.get(lowerKey);
                if (idx != null) {
                    if (editorSynthesizedKeys.contains(lowerKey)) {
                        headersModel.setValueAt(e.getValue(), idx, 1);
                    }
                } else {
                    headersModel.addRow(new Object[]{key, e.getValue(), true});
                }
                newSynthKeys.add(lowerKey);
                newSynthValues.put(lowerKey, e.getValue());
            }

            // 5. Remove obsolete synthesized rows (no longer in effective set and not edited/suppressed)
            for (int i = headersModel.getRowCount() - 1; i >= 0; i--) {
                String key = (String) headersModel.getValueAt(i, 0);
                if (key == null || key.trim().isEmpty()) continue;
                String lowerKey = key.toLowerCase(Locale.ROOT);

                boolean isSynth = editorSynthesizedKeys.contains(lowerKey);
                boolean isEdited = editedSynthValues.containsKey(lowerKey);
                boolean isSuppressed = suppressedKeys.contains(lowerKey);

                if (isSynth && !isEdited && !seenInEffective.contains(lowerKey)) {
                    headersModel.removeRow(i);
                }
            }

            editorSynthesizedKeys = newSynthKeys;
            editorSynthesizedValues = newSynthValues;
            RequestEditorStateMapper.ensureStarterRow(headersModel);
        } catch (Exception ex) {
            // Effective headers are best-effort; fall back to current model state.
        } finally {
            refreshingHeaders = false;
        }
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
        out.append(vr.resolve(RequestEditorStateMapper.rebuildUrlWithParams(urlField.getText(), paramsModel))).append("\n\n");

        out.append("Resolved Auth\n");
        out.append("-------------\n");
        String authType = (String) authTypeBox.getSelectedItem();
        out.append("type=").append(authType).append("\n");
        for (Map.Entry<String, JTextField> e : authUi.authFields.entrySet()) {
            String v = e.getValue().getText();
            if (v != null && !v.isEmpty()) {
                out.append(e.getKey()).append("=").append(vr.resolve(v)).append("\n");
            }
        }

        out.append("\nResolved Headers (Effective)\n");
        out.append("-----------------------------\n");
        boolean usedEffective = false;
        if (requestBuilder != null) {
            try {
                ApiRequest built = buildRequestFromUI();
                if (built != null) {
                    List<Map.Entry<String, String>> effective = requestBuilder.buildEffectiveHeaders(built, vr);
                    for (Map.Entry<String, String> e : effective) {
                        out.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                    }
                    usedEffective = true;
                }
            } catch (Exception ex) {
                // Fallback to explicit-only view
            }
        }
        if (!usedEffective) {
            for (int i = 0; i < headersModel.getRowCount(); i++) {
                String key = (String) headersModel.getValueAt(i, 0);
                String value = (String) headersModel.getValueAt(i, 1);
                Boolean enabled = (Boolean) headersModel.getValueAt(i, 2);
                if (key != null && !key.trim().isEmpty() && (enabled == null || enabled)) {
                    out.append(vr.resolve(key)).append(": ").append(vr.resolve(value != null ? value : "")).append("\n");
                }
            }
        }
        // Show disabled explicit headers as suppressions
        boolean hasDisabled = false;
        for (int i = 0; i < headersModel.getRowCount(); i++) {
            String key = (String) headersModel.getValueAt(i, 0);
            String value = (String) headersModel.getValueAt(i, 1);
            Boolean enabled = (Boolean) headersModel.getValueAt(i, 2);
            if (key != null && !key.trim().isEmpty() && Boolean.FALSE.equals(enabled)) {
                if (!hasDisabled) {
                    out.append("\nDisabled (suppressed)\n");
                    out.append("---------------------\n");
                    hasDisabled = true;
                }
                out.append("(disabled) ").append(vr.resolve(key)).append(": ").append(vr.resolve(value != null ? value : "")).append("\n");
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

    private void clearAll() {
        editorSynthesizedKeys.clear();
        editorSynthesizedValues.clear();
        RequestEditorStateMapper.clearEditor(createStateMapperContext());
    }

    private RequestEditorStateMapper.Context createStateMapperContext() {
        RequestEditorStateMapper.Context ctx = new RequestEditorStateMapper.Context(
                methodBox,
                urlField,
                paramsModel,
                authTypeBox,
                this::rebuildAuthFields,
                headersModel,
                bodyRawArea,
                bodyFormModel,
                this::setBodyModeInternal,
                this::getBodyModeInternal,
                preScriptArea,
                postScriptArea,
                () -> currentRequest,
                () -> currentCollection,
                this::resolveEditorAuthMode,
                this::buildAuthFromFields,
                this::refreshResolvedMirror,
                requestBuilder
        );
        ctx.synthesizedKeys = new LinkedHashSet<>(editorSynthesizedKeys);
        ctx.synthesizedValues = new LinkedHashMap<>(editorSynthesizedValues);
        return ctx;
    }
}
