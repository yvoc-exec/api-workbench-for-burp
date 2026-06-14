package burp.ui;

import burp.models.ApiRequest;
import burp.utils.RequestBuilder;
import burp.utils.RuntimeResolverFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    private boolean runtimeVariablesExplicit = false;
    private final Set<String> materializedAutoHeaders = new LinkedHashSet<>();

    private ApiRequest currentRequest;
    private burp.models.ApiCollection currentCollection;
    private burp.utils.RequestBuilder requestBuilder;
    private boolean loadingRequest = false;
    private boolean syncingDerivedHeaders = false;
    private boolean authorizationHeaderMaterialized = false;
    private boolean contentTypeHeaderMaterialized = false;
    private Runnable trackedHeaderStateChangeListener;
    private boolean dirty = false;

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

    public void setTrackedHeaderStateChangeListener(Runnable listener) {
        this.trackedHeaderStateChangeListener = listener;
    }

    public void setSendEnabled(boolean enabled) {
        sendBtn.setEnabled(enabled);
        sendDropdownBtn.setEnabled(enabled);
    }

    public void setSendControlsEnabled(boolean enabled) {
        setSendEnabled(enabled);
    }

    public boolean isSendEnabled() {
        return sendBtn != null && sendBtn.isEnabled() && sendDropdownBtn != null && sendDropdownBtn.isEnabled();
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
            handleAuthUiChangedIfReady();
        });
        panel.add(authTypeBox, BorderLayout.NORTH);
        JPanel authFieldsPanel = new JPanel(new GridLayout(0, 2, 5, 5));
        authUi = new RequestEditorAuthSupport.AuthUi(authFieldsPanel, this::handleAuthUiChangedIfReady);
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
        headersModel = new DefaultTableModel(new Object[]{"Key", "Value"}, 0);
        headersTable = RequestEditorTableSupport.createEditableTable(headersModel);
        panel.add(new JScrollPane(headersTable), BorderLayout.CENTER);
        panel.add(RequestEditorTableSupport.createAddRemovePanel(headersTable, headersModel, () -> new Object[]{"", ""}), BorderLayout.SOUTH);
        RequestEditorStateMapper.ensureStarterRow(headersModel);
        return panel;
    }

    private JPanel createBodyPanel() {
        bodyUi = RequestEditorBodySupport.createBodyUi(this::handleBodyUiChangedIfReady);
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
        headersModel.addTableModelListener(e -> {
            try {
                refreshResolvedMirrorIfReady();
            } finally {
                notifyTrackedHeaderStateChangedIfReady();
            }
        });
        paramsModel.addTableModelListener(e -> refreshAllIfReady());
        bodyFormModel.addTableModelListener(e -> handleBodyUiChangedIfReady());
        bodyRawArea.getDocument().addDocumentListener(new SimpleDocumentListener(this::handleBodyUiChangedIfReady));
        preScriptArea.getDocument().addDocumentListener(new SimpleDocumentListener(this::refreshResolvedMirrorIfReady));
        postScriptArea.getDocument().addDocumentListener(new SimpleDocumentListener(this::refreshResolvedMirrorIfReady));
    }

    private void refreshAllIfReady() {
        if (!loadingRequest) {
            markDirty();
            refreshAll();
        }
    }

    private void refreshResolvedMirrorIfReady() {
        if (!loadingRequest) {
            markDirty();
            refreshResolvedMirror();
        }
    }

    private void notifyTrackedHeaderStateChangedIfReady() {
        if (loadingRequest || syncingDerivedHeaders) {
            return;
        }
        markDirty();
        if (trackedHeaderStateChangeListener != null) {
            trackedHeaderStateChangeListener.run();
        }
    }

    private void handleAuthUiChangedIfReady() {
        if (!loadingRequest && headersModel != null) {
            markDirty();
            syncAuthorizationHeaderFromCurrentAuth();
            refreshResolvedMirror();
        }
    }

    private void handleBodyUiChangedIfReady() {
        if (!loadingRequest && headersModel != null) {
            markDirty();
            syncContentTypeHeaderFromCurrentBody();
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
        resetDerivedHeaderMaterializationState();
        materializedAutoHeaders.clear();
        loadingRequest = true;
        try {
            RequestEditorStateMapper.Context ctx = createStateMapperContext();
            RequestEditorStateMapper.loadRequest(req, ctx);
        } finally {
            loadingRequest = false;
        }
        captureMaterializedDefaultHeaders(req);
        refreshAll();
        markClean();
    }

    public void clearRequest() {
        currentCollection = null;
        currentRequest = null;
        resetDerivedHeaderMaterializationState();
        materializedAutoHeaders.clear();
        loadingRequest = true;
        try {
            clearAll();
        } finally {
            loadingRequest = false;
        }
        refreshAll();
        setSendControlsEnabled(false);
        markClean();
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
        boolean explicit = vars != null;
        if (Objects.equals(runtimeVariables, next) && runtimeVariablesExplicit == explicit) {
            return;
        }
        runtimeVariables = next;
        runtimeVariablesExplicit = explicit;
        refreshAll();
    }

    private void refreshAll() {
        syncAuthorizationHeaderFromCurrentAuth();
        syncContentTypeHeaderFromCurrentBody();
        refreshResolvedMirror();
    }

    public void commitAllEdits() {
        RequestEditorTableSupport.commitAllEdits(paramsTable, headersTable, bodyFormTable);
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    public void markDirty() {
        if (!loadingRequest) {
            dirty = true;
        }
    }

    public ApiRequest buildRequestFromUI() {
        ApiRequest built = RequestEditorStateMapper.buildRequest(createStateMapperContext());
        if (built != null) {
            built.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
            built.editorMaterialized = true;
            if (built.suppressedAutoHeaders == null) {
                built.suppressedAutoHeaders = new LinkedHashSet<>();
            }
            built.normalizeSuppressedAutoHeaders();
            if (currentRequest != null && currentRequest.suppressedAutoHeaders != null) {
                built.suppressedAutoHeaders.addAll(currentRequest.suppressedAutoHeaders);
            }
            syncSuppressedAutoHeadersWithCurrentEditorHeaders(built);
            built.normalizeSuppressedAutoHeaders();
        }
        return built;
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

    private void syncAuthorizationHeaderFromCurrentAuth() {
        if (syncingDerivedHeaders) {
            return;
        }
        String selectedType = (String) authTypeBox.getSelectedItem();
        ApiRequest.Auth auth = "inherit".equals(selectedType)
                ? (currentRequest != null ? currentRequest.auth : null)
                : buildAuthFromFields(selectedType);
        String existingAuthorization = findHeaderValue("Authorization");

        if (currentRequest != null && currentRequest.isAutoHeaderSuppressed("authorization") && existingAuthorization == null) {
            return;
        }
        if (existingAuthorization == null && materializedAutoHeaders.contains("authorization")) {
            return;
        }

        if (auth == null || auth.type == null || "none".equalsIgnoreCase(auth.type)) {
            if (authorizationHeaderMaterialized) {
                syncingDerivedHeaders = true;
                try {
                    removeHeaderRow("Authorization");
                    authorizationHeaderMaterialized = false;
                    RequestEditorStateMapper.ensureStarterRow(headersModel);
                } finally {
                    syncingDerivedHeaders = false;
                }
            }
            return;
        }

        if (!authorizationHeaderMaterialized && existingAuthorization != null) {
            return;
        }

        String authorization = buildAuthorizationHeaderValue(auth);
        if (authorization == null || authorization.isBlank()) {
            if (authorizationHeaderMaterialized) {
                syncingDerivedHeaders = true;
                try {
                    removeHeaderRow("Authorization");
                    authorizationHeaderMaterialized = false;
                    RequestEditorStateMapper.ensureStarterRow(headersModel);
                } finally {
                    syncingDerivedHeaders = false;
                }
            }
            return;
        }

        syncingDerivedHeaders = true;
        try {
            upsertHeaderRow("Authorization", authorization);
            authorizationHeaderMaterialized = true;
            markMaterializedAutoHeader("authorization");
            RequestEditorStateMapper.ensureStarterRow(headersModel);
        } finally {
            syncingDerivedHeaders = false;
        }
    }

    private String buildAuthorizationHeaderValue(ApiRequest.Auth auth) {
        if (auth == null || auth.type == null) {
            return null;
        }
        switch (auth.type.toLowerCase(Locale.ROOT)) {
            case "bearer":
                String token = auth.properties.getOrDefault("token", auth.properties.get("value"));
                if (token == null || token.isBlank()) {
                    return null;
                }
                String prefix = auth.properties.getOrDefault("prefix", "Bearer");
                return prefix + " " + token;
            case "basic":
                String username = auth.properties.getOrDefault("username", auth.properties.get("user"));
                String password = auth.properties.getOrDefault("password", auth.properties.get("pass"));
                if ((username == null || username.isBlank()) && (password == null || password.isBlank())) {
                    return null;
                }
                String credentials = (username != null ? username : "") + ":" + (password != null ? password : "");
                return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            case "oauth2":
                String accessToken = auth.properties.get("accessToken");
                if (accessToken == null || accessToken.isBlank()) {
                    return null;
                }
                return "Bearer " + accessToken;
            default:
                return null;
        }
    }

    private void syncContentTypeHeaderFromCurrentBody() {
        if (syncingDerivedHeaders) {
            return;
        }
        String existingContentType = findHeaderValue("Content-Type");
        if (currentRequest != null && currentRequest.isAutoHeaderSuppressed("content-type") && existingContentType == null) {
            return;
        }
        if (!contentTypeHeaderMaterialized && existingContentType != null) {
            return;
        }
        if (existingContentType == null && materializedAutoHeaders.contains("content-type")) {
            return;
        }
        String bodyMode = getBodyModeInternal();
        String contentType = null;
        if ("raw".equals(bodyMode)) {
            String raw = bodyRawArea.getText() != null ? bodyRawArea.getText().trim() : "";
            String configured = currentRequest != null && currentRequest.body != null ? currentRequest.body.contentType : null;
            if (configured != null && !configured.isBlank()) {
                contentType = configured;
            } else if (!raw.isEmpty()) {
                contentType = (raw.startsWith("{") || raw.startsWith("[")) ? "application/json" : "text/plain";
            }
        } else if ("graphql".equals(bodyMode)) {
            contentType = "application/json";
        } else if ("urlencoded".equals(bodyMode)) {
            contentType = "application/x-www-form-urlencoded";
        } else if ("formdata".equals(bodyMode)) {
            contentType = (existingContentType != null && existingContentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data"))
                    ? existingContentType
                    : "multipart/form-data";
        } else if ("file".equals(bodyMode)) {
            String configured = currentRequest != null && currentRequest.body != null ? currentRequest.body.contentType : null;
            if (configured != null && !configured.isBlank()) {
                contentType = configured;
            }
        }

        if (contentType == null || contentType.isBlank()) {
            if (contentTypeHeaderMaterialized) {
                syncingDerivedHeaders = true;
                try {
                    removeHeaderRow("Content-Type");
                    contentTypeHeaderMaterialized = false;
                    RequestEditorStateMapper.ensureStarterRow(headersModel);
                } finally {
                    syncingDerivedHeaders = false;
                }
            }
            return;
        }

        syncingDerivedHeaders = true;
        try {
            upsertHeaderRow("Content-Type", contentType);
            contentTypeHeaderMaterialized = true;
            markMaterializedAutoHeader("content-type");
            RequestEditorStateMapper.ensureStarterRow(headersModel);
        } finally {
            syncingDerivedHeaders = false;
        }
    }

    private void resetDerivedHeaderMaterializationState() {
        authorizationHeaderMaterialized = false;
        contentTypeHeaderMaterialized = false;
        materializedAutoHeaders.clear();
    }

    private void upsertHeaderRow(String key, String value) {
        int blankRow = -1;
        for (int i = 0; i < headersModel.getRowCount(); i++) {
            String existingKey = (String) headersModel.getValueAt(i, 0);
            if (existingKey == null || existingKey.trim().isEmpty()) {
                if (blankRow < 0) {
                    blankRow = i;
                }
                continue;
            }
            if (existingKey.equalsIgnoreCase(key)) {
                headersModel.setValueAt(key, i, 0);
                headersModel.setValueAt(value, i, 1);
                return;
            }
        }
        if (blankRow >= 0) {
            headersModel.setValueAt(key, blankRow, 0);
            headersModel.setValueAt(value, blankRow, 1);
        } else {
            headersModel.addRow(new Object[]{key, value});
        }
    }

    private void removeHeaderRow(String key) {
        for (int i = headersModel.getRowCount() - 1; i >= 0; i--) {
            String existingKey = (String) headersModel.getValueAt(i, 0);
            if (existingKey != null && existingKey.equalsIgnoreCase(key)) {
                headersModel.removeRow(i);
            }
        }
    }

    private String findHeaderValue(String key) {
        for (int i = 0; i < headersModel.getRowCount(); i++) {
            String existingKey = (String) headersModel.getValueAt(i, 0);
            if (existingKey != null && existingKey.equalsIgnoreCase(key)) {
                return (String) headersModel.getValueAt(i, 1);
            }
        }
        return null;
    }

    private void refreshResolvedMirror() {
        if (resolvedViewArea == null) return;
        if (currentRequest == null) {
            resolvedViewArea.setText("");
            return;
        }
        var vr = RuntimeResolverFactory.build(
                currentCollection,
                currentRequest,
                runtimeVariablesExplicit
                        ? RuntimeResolverFactory.Options.withRuntimeVariableOverlay(runtimeVariables)
                        : RuntimeResolverFactory.Options.defaultOptions()
        );

        StringBuilder out = new StringBuilder();
        out.append("Resolved URL\n");
        out.append("------------\n");
        out.append(vr.resolve(RequestEditorStateMapper.rebuildUrlWithParams(urlField.getText(), paramsModel))).append("\n\n");

        appendBuildPolicyDiagnostics(out, currentRequest);

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
                if (key != null && !key.trim().isEmpty()) {
                    out.append(vr.resolve(key)).append(": ").append(vr.resolve(value != null ? value : "")).append("\n");
                }
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

    private void appendBuildPolicyDiagnostics(StringBuilder out, ApiRequest req) {
        if (req == null) {
            return;
        }

        List<String> suppressed = new ArrayList<>();
        if (req.suppressedAutoHeaders != null) {
            for (String headerName : req.suppressedAutoHeaders) {
                String normalized = normalizeTrackedHeaderName(headerName);
                if (normalized != null) {
                    suppressed.add(normalized);
                }
            }
        }
        Collections.sort(suppressed);

        out.append("Request Build Policy\n");
        out.append("--------------------\n");
        out.append("mode=").append(req.resolveBuildMode()).append("\n");
        out.append("suppressedAutoHeaders=")
                .append(suppressed.isEmpty() ? "(none)" : String.join(", ", suppressed))
                .append("\n");
        if (req.isManualPreserveMode()) {
            out.append("note=Manual preserve mode keeps tester-deleted auto headers deleted.\n");
        } else {
            out.append("note=Auto-compatible mode may synthesize defaults/auth/body Content-Type.\n");
        }
        out.append("\n");
    }

    public JTextField getUrlField() { return urlField; }
    public JComboBox<String> getMethodBox() { return methodBox; }
    public JTabbedPane getTabs() { return tabs; }

    private void clearAll() {
        resetDerivedHeaderMaterializationState();
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
        return ctx;
    }

    private void captureMaterializedDefaultHeaders(ApiRequest req) {
        if (req == null || req.headers == null || headersModel == null) {
            return;
        }
        if (!req.isAutoCompatibleMode()) {
            return;
        }
        Set<String> requestHeaderNames = new LinkedHashSet<>();
        for (ApiRequest.Header header : req.headers) {
            if (header == null || header.disabled || header.key == null) {
                continue;
            }
            String normalized = normalizeTrackedHeaderName(header.key);
            if (normalized != null) {
                requestHeaderNames.add(normalized);
            }
        }
        for (String headerName : List.of("accept", "user-agent", "cache-control")) {
            if (!req.isAutoHeaderSuppressed(headerName) && hasHeaderRow(headerName) && !requestHeaderNames.contains(headerName)) {
                markMaterializedAutoHeader(headerName);
            }
        }
    }

    private void syncSuppressedAutoHeadersWithCurrentEditorHeaders(ApiRequest built) {
        if (built == null) {
            return;
        }
        Set<String> currentHeaders = new LinkedHashSet<>();
        for (int i = 0; i < headersModel.getRowCount(); i++) {
            String key = (String) headersModel.getValueAt(i, 0);
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            String normalized = normalizeTrackedHeaderName(key);
            if (normalized != null && isTrackedAutoHeader(normalized)) {
                currentHeaders.add(normalized);
            }
        }

        for (String tracked : TRACKED_AUTO_HEADER_NAMES) {
            if (currentHeaders.contains(tracked)) {
                built.clearSuppressedAutoHeader(tracked);
            }
        }

        for (String tracked : materializedAutoHeaders) {
            if (!currentHeaders.contains(tracked)) {
                built.suppressAutoHeader(tracked);
            }
        }

        if (currentRequest != null && currentRequest.suppressedAutoHeaders != null) {
            for (String suppressed : currentRequest.suppressedAutoHeaders) {
                String normalized = normalizeTrackedHeaderName(suppressed);
                if (normalized != null) {
                    built.suppressAutoHeader(normalized);
                }
            }
            for (String tracked : currentHeaders) {
                built.clearSuppressedAutoHeader(tracked);
            }
        }
    }

    private static final List<String> TRACKED_AUTO_HEADER_NAMES = List.of(
            "authorization",
            "content-type",
            "accept",
            "user-agent",
            "cache-control"
    );

    private boolean isTrackedAutoHeader(String headerName) {
        return TRACKED_AUTO_HEADER_NAMES.contains(headerName);
    }

    private static String normalizeTrackedHeaderName(String headerName) {
        if (headerName == null) {
            return null;
        }
        String normalized = headerName.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private void markMaterializedAutoHeader(String headerName) {
        String normalized = normalizeTrackedHeaderName(headerName);
        if (normalized != null) {
            materializedAutoHeaders.add(normalized);
        }
    }

    private boolean hasHeaderRow(String headerName) {
        String normalized = normalizeTrackedHeaderName(headerName);
        if (normalized == null || headersModel == null) {
            return false;
        }
        for (int i = 0; i < headersModel.getRowCount(); i++) {
            String key = (String) headersModel.getValueAt(i, 0);
            if (key != null && normalized.equals(normalizeTrackedHeaderName(key))) {
                return true;
            }
        }
        return false;
    }
}
