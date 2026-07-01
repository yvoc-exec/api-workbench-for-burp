package burp.ui;

import burp.models.ApiRequest;
import burp.utils.RequestBuilder;
import burp.utils.RuntimeResolverFactory;
import burp.ui.SwingShortcutSupport;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Postman-like request editor panel for the Workbench.
 */
public class RequestEditorPanel extends JPanel {
    private JComboBox<String> methodBox;
    private JTextComponent urlField;
    private JTabbedPane tabs;
    private boolean exactTransportHeadersSelected;
    private JLabel exactTransportIndicator;
    private JPanel exactTransportIndicatorWrapper;

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
    private JTextComponent bodyRawArea;
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
    private ApiRequest.BuildMode lastNonExactBuildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
    private boolean exactTransportWarningAcknowledged = false;
    private ExactTransportWarningProvider exactTransportWarningProvider = new SwingExactTransportWarningProvider();
    private Runnable requestBuildModeChangeListener;
    private Runnable trackedHeaderStateChangeListener;
    private boolean dirty = false;
    private VariableActionBridge variableActionBridge;
    private VariableDialogProvider variableDialogProvider = new SwingVariableDialogProvider();
    private final Map<JTextComponent, VariableHoverSupport> variableHoverSupports = new IdentityHashMap<>();
    private static final String VARIABLE_HOVER_INSTALLED_PROPERTY = "awb.variable.hover.installed";
    private static final String VARIABLE_POPUP_PROPERTY = "awb.variable.popup";
    private static final int HEADER_DISABLED_MODEL_COLUMN = 2;
    private static final int VARIABLE_HOVER_DELAY_MS = 650;
    private static final int VARIABLE_HOVER_HIDE_DELAY_MS = 850;
    private boolean updatingVariableStyles = false;
    private HeaderVariableHoverSupport headerVariableHoverSupport;

    // Send action callback
    public interface SendActionListener {
        void onSend();
    }
    private SendActionListener sendActionListener;
    private JButton sendBtn;
    private JButton sendDropdownBtn;
    private boolean followRedirectsSelected = true;
    private Consumer<Boolean> followRedirectsChangeListener;
    private Runnable redirectPolicyAction;

    interface ExactTransportWarningProvider {
        boolean confirmEnable(Component parent, String title, String message);
    }

    private static final class SwingExactTransportWarningProvider implements ExactTransportWarningProvider {
        @Override
        public boolean confirmEnable(Component parent, String title, String message) {
            return JOptionPane.showConfirmDialog(parent, message, title,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
        }
    }

    public interface VariableActionBridge {
        VariableHoverInfo inspect(String key);

        boolean hasActiveEnvironment();

        String activeEnvironmentName();

        boolean updateActiveEnvironment(String key, String value, boolean createIfMissing, boolean persist);

        void refreshEnvironmentUi();
    }

    public interface VariableDialogProvider {
        String prompt(Component parent, String title, String message, String initialValue);

        boolean confirm(Component parent, String title, String message);

        void info(Component parent, String title, String message);
    }

    public static final class VariableHoverInfo {
        public String key;
        public boolean resolved;
        public String value;
        public String scope;
        public String source;
        public String shadowedSource;
        public String shadowedValue;
        public String activeEnvironmentName;
        public boolean canEdit;
        public boolean canCreate;
        public String message;

        public String statusText() {
            return resolved ? "Resolved" : "Unresolved";
        }
    }

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
        urlField = new JTextPane();
        urlField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        SwingShortcutSupport.installTextComponentShortcuts(urlField);
        JScrollPane urlScroll = new JScrollPane(urlField);
        Color fieldBorderColor = UIManager.getColor("TextField.shadow");
        if (fieldBorderColor == null) {
            fieldBorderColor = UIManager.getColor("Component.shadow");
        }
        if (fieldBorderColor == null) {
            fieldBorderColor = new Color(160, 160, 160);
        }
        urlScroll.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(fieldBorderColor, 1),
                BorderFactory.createEmptyBorder(0, 2, 0, 2)
        ));
        urlScroll.setPreferredSize(new Dimension(320, Math.max(26, urlField.getFontMetrics(urlField.getFont()).getHeight() + 10)));
        urlScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        urlScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);

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
            JPopupMenu menu = createSendDropdownMenu();
            menu.show(sendDropdownBtn, 0, sendDropdownBtn.getHeight());
        });

        JPanel sendPanel = new JPanel(new BorderLayout(0, 0));
        sendPanel.add(sendBtn, BorderLayout.CENTER);
        sendPanel.add(sendDropdownBtn, BorderLayout.EAST);

        exactTransportIndicator = new JLabel(Character.toString((char) 0x26A0) + " Exact transport headers");
        exactTransportIndicator.setFont(exactTransportIndicator.getFont().deriveFont(Font.BOLD));
        exactTransportIndicator.setOpaque(false);
        exactTransportIndicator.setForeground(resolveWarningForeground());
        exactTransportIndicator.setToolTipText("<html>Exact transport headers may emit authored Host, Content-Length, Transfer-Encoding, Connection, and related transport headers.<br/>Burp, proxies, HTTP/2 conversion, or servers can still normalize or reject them.</html>");
        exactTransportIndicatorWrapper = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        exactTransportIndicatorWrapper.setOpaque(false);
        exactTransportIndicatorWrapper.add(exactTransportIndicator);
        updateExactTransportIndicator();

        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        modePanel.add(methodBox);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightPanel.add(exactTransportIndicatorWrapper);
        rightPanel.add(sendPanel);

        panel.add(modePanel, BorderLayout.WEST);
        panel.add(urlScroll, BorderLayout.CENTER);
        panel.add(rightPanel, BorderLayout.EAST);
        return panel;
    }

    private JPopupMenu createSendDropdownMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem sendOnlyItem = new JMenuItem("Send");
        JMenuItem sendRepeaterItem = new JMenuItem("Send + Repeater");
        JCheckBoxMenuItem followRedirectsItem = new JCheckBoxMenuItem("Follow redirects", followRedirectsSelected);
        JMenuItem redirectPolicyItem = new JMenuItem("Redirect security policy...");
        JCheckBoxMenuItem exactTransportItem = new JCheckBoxMenuItem("Exact transport headers \u2014 Advanced", exactTransportHeadersSelected);
        exactTransportItem.setEnabled(currentRequest != null);
        sendOnlyItem.addActionListener(ev -> setSendModeLabel("Send"));
        sendRepeaterItem.addActionListener(ev -> setSendModeLabel("Send + Repeater"));
        followRedirectsItem.addActionListener(ev -> setFollowRedirectsSelected(followRedirectsItem.isSelected()));
        redirectPolicyItem.addActionListener(ev -> {
            if (redirectPolicyAction != null) {
                redirectPolicyAction.run();
            }
        });
        exactTransportItem.addActionListener(ev -> requestExactTransportModeChange(exactTransportItem.isSelected(), true));
        menu.add(sendOnlyItem);
        menu.add(sendRepeaterItem);
        menu.addSeparator();
        menu.add(followRedirectsItem);
        menu.add(redirectPolicyItem);
        menu.addSeparator();
        menu.add(exactTransportItem);
        return menu;
    }

    private Color resolveWarningForeground() {
        Color color = UIManager.getColor("Actions.Yellow");
        if (color == null) color = UIManager.getColor("Component.warning.focusedBorderColor");
        if (color == null) color = UIManager.getColor("OptionPane.warningDialog.titlePane.foreground");
        return color != null ? color : new Color(176, 112, 0);
    }

    public void setSendActionListener(SendActionListener listener) {
        this.sendActionListener = listener;
    }

    public boolean isFollowRedirectsSelected() {
        return followRedirectsSelected;
    }

    public void setFollowRedirectsSelected(boolean selected) {
        this.followRedirectsSelected = selected;
        if (followRedirectsChangeListener != null) {
            followRedirectsChangeListener.accept(selected);
        }
    }

    public void setFollowRedirectsChangeListener(Consumer<Boolean> listener) {
        this.followRedirectsChangeListener = listener;
    }

    public void setRedirectPolicyAction(Runnable action) {
        this.redirectPolicyAction = action;
    }

    public void setRequestBuilder(burp.utils.RequestBuilder requestBuilder) {
        this.requestBuilder = requestBuilder;
    }

    public void setVariableActionBridge(VariableActionBridge bridge) {
        this.variableActionBridge = bridge;
        refreshAll();
    }

    public void setVariableDialogProvider(VariableDialogProvider provider) {
        this.variableDialogProvider = provider != null ? provider : new SwingVariableDialogProvider();
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
        headersModel = new DefaultTableModel(new Object[]{"Key", "Value", "Disabled"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == HEADER_DISABLED_MODEL_COLUMN ? Boolean.class : String.class;
            }
        };
        headersTable = RequestEditorTableSupport.createEditableTable(headersModel);
        headersTable.removeColumn(headersTable.getColumnModel().getColumn(HEADER_DISABLED_MODEL_COLUMN));
        installHeaderVariableSupport();
        panel.add(new JScrollPane(headersTable), BorderLayout.CENTER);
        panel.add(RequestEditorTableSupport.createAddRemovePanel(headersTable, headersModel, () -> new Object[]{"", "", Boolean.FALSE}), BorderLayout.SOUTH);
        RequestEditorStateMapper.ensureStarterRow(headersModel);
        return panel;
    }

    private JPanel createBodyPanel() {
        bodyUi = RequestEditorBodySupport.createBodyUi(this::handleBodyUiChangedIfReady);
        bodyRawArea = bodyUi.bodyRawArea;
        bodyFormTable = bodyUi.bodyFormTable;
        bodyFormModel = bodyUi.bodyFormModel;
        SwingShortcutSupport.installTextComponentShortcuts(bodyRawArea);
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
        SwingShortcutSupport.installTextComponentShortcuts(preScriptArea);
        preScriptArea.setBorder(BorderFactory.createTitledBorder("Pre-request Script"));
        panel.add(new JScrollPane(preScriptArea));
        postScriptArea = new JTextArea(5, 40);
        postScriptArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        SwingShortcutSupport.installTextComponentShortcuts(postScriptArea);
        postScriptArea.setBorder(BorderFactory.createTitledBorder("Post-response Script"));
        panel.add(new JScrollPane(postScriptArea));
        return panel;
    }

    private JPanel createResolvedPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        resolvedViewArea = new JTextArea(12, 40);
        resolvedViewArea.setEditable(false);
        resolvedViewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        SwingShortcutSupport.installTextComponentShortcuts(resolvedViewArea);
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
        installVariableHoverSupport(urlField);
        installVariableHoverSupport(bodyRawArea);
    }

    private void refreshAllIfReady() {
        if (!loadingRequest && !updatingVariableStyles) {
            markDirty();
            refreshAll();
        }
    }

    private void refreshResolvedMirrorIfReady() {
        if (!loadingRequest && !updatingVariableStyles) {
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
        if (!loadingRequest && !updatingVariableStyles && headersModel != null) {
            markDirty();
            syncAuthorizationHeaderFromCurrentAuth();
            refreshResolvedMirror();
        }
    }

    private void handleBodyUiChangedIfReady() {
        if (!loadingRequest && !updatingVariableStyles && headersModel != null) {
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
        lastNonExactBuildMode = req != null && !req.isExactHttpMode()
                ? req.resolveBuildMode()
                : ApiRequest.BuildMode.MANUAL_PRESERVE;
        resetDerivedHeaderMaterializationState();
        materializedAutoHeaders.clear();
        loadingRequest = true;
        try {
            setExactHttpModeSelected(req != null && req.isExactHttpMode());
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
        lastNonExactBuildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        hideAllVariablePopups();
        resetDerivedHeaderMaterializationState();
        materializedAutoHeaders.clear();
        loadingRequest = true;
        try {
            setExactHttpModeSelected(false);
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

    public Map<String, String> getRuntimeVariablesSnapshot() {
        return new HashMap<>(runtimeVariables);
    }

    private void refreshAll() {
        hideAllVariablePopups();
        syncAuthorizationHeaderFromCurrentAuth();
        syncContentTypeHeaderFromCurrentBody();
        refreshVariableHighlights();
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
            built.buildMode = isExactHttpModeSelected()
                    ? ApiRequest.BuildMode.EXACT_HTTP
                    : ApiRequest.BuildMode.MANUAL_PRESERVE;
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

    private void handleExactHttpModeSelectionChanged() {
        requestExactTransportModeChange(!exactTransportHeadersSelected, true);
    }

    private boolean requestExactTransportModeChange(boolean enable, boolean userInitiated) {
        if (loadingRequest || updatingVariableStyles || currentRequest == null) {
            return false;
        }
        if (enable == exactTransportHeadersSelected && currentRequest.isExactHttpMode() == enable) {
            return true;
        }
        commitAllEdits();
        if (enable && userInitiated && !exactTransportWarningAcknowledged) {
            boolean accepted = exactTransportWarningProvider.confirmEnable(
                    this,
                    "Exact transport headers",
                    "Exact transport mode can send conflicting or malformed transport headers such as Host, Content-Length, Transfer-Encoding, and Connection. Use it only for advanced protocol testing. Burp, proxies, HTTP/2 conversion, and servers may still normalize or reject the request.");
            if (!accepted) {
                return false;
            }
            exactTransportWarningAcknowledged = true;
        }
        applyExactTransportMode(enable);
        return true;
    }

    private void applyExactTransportMode(boolean enabled) {
        ApiRequest draft = buildRequestFromUI();
        if (draft == null || currentRequest == null) {
            return;
        }
        ApiRequest.BuildMode targetMode;
        if (enabled) {
            ApiRequest.BuildMode currentMode = currentRequest.resolveBuildMode();
            if (currentMode != null && currentMode != ApiRequest.BuildMode.EXACT_HTTP) {
                lastNonExactBuildMode = currentMode;
            }
            targetMode = ApiRequest.BuildMode.EXACT_HTTP;
        } else {
            targetMode = lastNonExactBuildMode != null && lastNonExactBuildMode != ApiRequest.BuildMode.EXACT_HTTP
                    ? lastNonExactBuildMode
                    : ApiRequest.BuildMode.MANUAL_PRESERVE;
        }
        draft.buildMode = targetMode;
        applyEditorOwnedStatePreservingMetadata(draft, currentRequest);
        currentRequest.buildMode = targetMode;
        currentRequest.editorMaterialized = true;
        exactTransportHeadersSelected = enabled;
        updateExactTransportIndicator();
        refreshResolvedMirror();
        if (currentCollection != null) {
            currentCollection.fireChanged();
        }
        markDirty();
        if (requestBuildModeChangeListener != null) {
            requestBuildModeChangeListener.run();
        }
    }

    private void applyEditorOwnedStatePreservingMetadata(ApiRequest draft, ApiRequest target) {
        if (draft == null || target == null) {
            return;
        }
        target.method = draft.method;
        target.url = draft.url;
        target.headers = editorHeadersPreservingMetadata(draft.headers, target.headers);
        target.body = draft.body;
        target.auth = draft.auth;
        target.explicitAuth = draft.explicitAuth;
        target.authOverrideMode = draft.authOverrideMode;
        target.preRequestScripts = draft.preRequestScripts != null
                ? new ArrayList<>(draft.preRequestScripts)
                : new ArrayList<>();
        target.postResponseScripts = draft.postResponseScripts != null
                ? new ArrayList<>(draft.postResponseScripts)
                : new ArrayList<>();
        target.scriptBlocks = draft.scriptBlocks != null
                ? new ArrayList<>(draft.scriptBlocks)
                : new ArrayList<>();
        target.editorMaterialized = draft.editorMaterialized;
        target.suppressedAutoHeaders = draft.suppressedAutoHeaders != null
                ? new LinkedHashSet<>(draft.suppressedAutoHeaders)
                : new LinkedHashSet<>();
        target.buildMode = draft.buildMode;
    }

    private List<ApiRequest.Header> editorHeadersPreservingMetadata(List<ApiRequest.Header> draftHeaders,
                                                                    List<ApiRequest.Header> existingHeaders) {
        List<ApiRequest.Header> out = new ArrayList<>();
        if (draftHeaders == null) {
            return out;
        }
        for (ApiRequest.Header header : draftHeaders) {
            if (header == null) {
                continue;
            }
            String normalized = normalizeTrackedHeaderName(header.key);
            if (normalized != null
                    && materializedAutoHeaders.contains(normalized)
                    && !hasHeader(existingHeaders, normalized)) {
                continue;
            }
            out.add(new ApiRequest.Header(header.key, header.value, header.disabled));
        }
        return out;
    }

    private static boolean hasHeader(List<ApiRequest.Header> headers, String normalizedName) {
        if (headers == null || normalizedName == null) {
            return false;
        }
        for (ApiRequest.Header header : headers) {
            if (header != null && normalizedName.equals(normalizeTrackedHeaderName(header.key))) {
                return true;
            }
        }
        return false;
    }

    static boolean isMeaningfulAuthSource(String source) {
        return source != null && !source.isBlank() && !"none".equalsIgnoreCase(source.trim());
    }

    private void syncAuthorizationHeaderFromCurrentAuth() {
        if (isExactHttpModeSelected()) {
            return;
        }
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
        if (isExactHttpModeSelected()) {
            return;
        }
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

    private static List<ApiRequest.Header> copyHeaders(List<ApiRequest.Header> headers) {
        List<ApiRequest.Header> out = new ArrayList<>();
        if (headers == null) {
            return out;
        }
        for (ApiRequest.Header header : headers) {
            if (header == null) {
                out.add(null);
            } else {
                out.add(new ApiRequest.Header(header.key, header.value, header.disabled));
            }
        }
        return out;
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
        var vr = buildCurrentResolver();
        ApiRequest builtForPreview = null;
        try {
            builtForPreview = buildRequestFromUI();
        } catch (Exception ignored) {
            builtForPreview = null;
        }

        StringBuilder out = new StringBuilder();
        out.append("Resolved URL\n");
        out.append("------------\n");
        out.append(vr.resolve(RequestEditorStateMapper.rebuildUrlWithParams(urlField.getText(), paramsModel))).append("\n\n");

        appendBuildPolicyDiagnostics(out, builtForPreview != null ? builtForPreview : currentRequest);

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
        if (requestBuilder != null && builtForPreview != null) {
            try {
                List<Map.Entry<String, String>> effective = requestBuilder.buildEffectiveHeaders(builtForPreview, vr);
                for (Map.Entry<String, String> e : effective) {
                    out.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                }
                usedEffective = true;
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
        refreshVariableHighlights(vr.getVariables());
    }

    private void refreshVariableHighlights() {
        if (currentRequest == null) {
            updateVariableTextStyles(urlField, List.of());
            updateVariableTextStyles(bodyRawArea, List.of());
            refreshVariableAwareTables();
            return;
        }
        var vr = buildCurrentResolver();
        refreshVariableHighlights(vr.getVariables());
    }

    private void refreshVariableHighlights(Map<String, String> resolverVariables) {
        updateVariableTextStyles(urlField, VariableTokenScanner.scan(textOf(urlField), resolverVariables));
        updateVariableTextStyles(bodyRawArea, VariableTokenScanner.scan(textOf(bodyRawArea), resolverVariables));
        refreshVariableAwareTables();
    }

    private void updateVariableTextStyles(JTextComponent component, List<VariableTokenScanner.VariableToken> tokens) {
        if (component == null) {
            return;
        }
        boolean previous = updatingVariableStyles;
        updatingVariableStyles = true;
        try {
            VariableHighlightStyler.apply(component, tokens);
        } finally {
            updatingVariableStyles = previous;
        }
    }

    private String textOf(JTextComponent component) {
        return component != null && component.getText() != null ? component.getText() : "";
    }

    private void installVariableHoverSupport(JTextComponent component) {
        if (component == null) {
            return;
        }
        component.putClientProperty(VARIABLE_HOVER_INSTALLED_PROPERTY, Boolean.TRUE);
        VariableHoverSupport support = variableHoverSupports.computeIfAbsent(component, c -> new VariableHoverSupport(c));
        component.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                support.handleMouseMoved(e);
            }
        });
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                support.scheduleHidePopup();
            }
        });
    }

    private void updateVariableHoverState(JTextComponent component, MouseEvent e, VariableHoverSupport support) {
        if (component == null || e == null || currentRequest == null) {
            if (support != null) {
                support.hidePopup();
            }
            return;
        }
        VariableHoverInfo info = resolveVariableHoverInfo(component, e);
        if (info == null) {
            if (support != null) {
                if (isPointerInsideVisiblePopup(component)) {
                    support.cancelHidePopup();
                } else if (popupForComponent(component) != null) {
                    support.scheduleHidePopup();
                } else {
                    support.hidePopup();
                }
            }
            return;
        }
        if (support != null) {
            support.setCurrentInfo(info, e.getPoint());
        }
    }

    private VariableHoverInfo resolveVariableHoverInfo(JTextComponent component, MouseEvent e) {
        if (component == null || e == null || currentRequest == null) {
            return null;
        }
        VariableTokenScanner.VariableToken token = findVariableToken(component, e);
        if (token == null) {
            return null;
        }
        VariableHoverInfo info = null;
        if (variableActionBridge != null) {
            try {
                info = variableActionBridge.inspect(token.key);
            } catch (Exception ignored) {
                info = null;
            }
        }
        if (info == null) {
            info = createFallbackHoverInfo(token);
        }
        normalizeHoverInfo(info, token);
        return info;
    }

    private VariableHoverInfo createFallbackHoverInfo(VariableTokenScanner.VariableToken token) {
        VariableHoverInfo info = new VariableHoverInfo();
        info.key = token != null ? token.key : null;
        info.resolved = token != null && token.isResolved();
        info.value = token != null ? token.value : null;
        info.activeEnvironmentName = variableActionBridge != null ? variableActionBridge.activeEnvironmentName() : null;
        info.canEdit = variableActionBridge != null && variableActionBridge.hasActiveEnvironment() && info.resolved;
        info.canCreate = variableActionBridge != null && variableActionBridge.hasActiveEnvironment() && !info.resolved;
        return info;
    }

    private void normalizeHoverInfo(VariableHoverInfo info, VariableTokenScanner.VariableToken token) {
        if (info == null) {
            return;
        }
        if (info.key == null || info.key.isBlank()) {
            info.key = token != null ? token.key : "";
        }
        boolean tokenResolved = token != null && token.isResolved();
        if (!info.resolved) {
            info.resolved = tokenResolved;
        }
        if (info.value == null && token != null && token.value != null) {
            info.value = token.value;
        }
        if (info.activeEnvironmentName == null && variableActionBridge != null) {
            info.activeEnvironmentName = variableActionBridge.activeEnvironmentName();
        }
        if (info.resolved) {
            if (info.scope == null || info.scope.isBlank() || "unknown".equalsIgnoreCase(info.scope)) {
                info.scope = normalizeResolvedScope(info);
            }
            if (info.source == null || info.source.isBlank() || "unresolved".equalsIgnoreCase(info.source)) {
                info.source = normalizeResolvedSource(info);
            }
            if (info.message == null || info.message.isBlank()) {
                info.message = buildResolvedHoverMessage(info);
            }
        } else {
            info.scope = normalizeUnresolvedScope(info);
            info.source = normalizeUnresolvedSource(info);
            if (info.message == null || info.message.isBlank()) {
                info.message = info.activeEnvironmentName != null && !info.activeEnvironmentName.isBlank()
                        ? "Create target: Active Environment (persisted variable)."
                        : "No Active Environment selected. Select or import an environment to edit or create persisted variables.";
            }
        }
        if (variableActionBridge != null && variableActionBridge.hasActiveEnvironment()) {
            info.canEdit = info.canEdit || info.resolved;
            info.canCreate = info.canCreate || !info.resolved;
        } else {
            info.canEdit = false;
            info.canCreate = false;
        }
    }

    private String normalizeResolvedScope(VariableHoverInfo info) {
        if (info == null) {
            return "resolved";
        }
        if (info.scope != null && !info.scope.isBlank() && !"unknown".equalsIgnoreCase(info.scope)) {
            return info.scope;
        }
        if (info.activeEnvironmentName != null && !info.activeEnvironmentName.isBlank()) {
            return "active environment";
        }
        return "resolved value";
    }

    private String normalizeResolvedSource(VariableHoverInfo info) {
        if (info == null) {
            return "resolved value";
        }
        if (info.source != null && !info.source.isBlank() && !"unresolved".equalsIgnoreCase(info.source)) {
            return info.source;
        }
        if (info.activeEnvironmentName != null && !info.activeEnvironmentName.isBlank()) {
            return "Active Environment";
        }
        return "resolved value";
    }

    private String normalizeUnresolvedScope(VariableHoverInfo info) {
        if (info != null && info.scope != null && !info.scope.isBlank() && !"unknown".equalsIgnoreCase(info.scope)) {
            return info.scope;
        }
        return "not found";
    }

    private String normalizeUnresolvedSource(VariableHoverInfo info) {
        if (info != null && info.source != null && !info.source.isBlank() && !"unresolved".equalsIgnoreCase(info.source)) {
            return info.source;
        }
        if (info != null && info.activeEnvironmentName != null && !info.activeEnvironmentName.isBlank()) {
            return "Active Environment";
        }
        return "unresolved";
    }

    private String buildResolvedHoverMessage(VariableHoverInfo info) {
        StringBuilder message = new StringBuilder();
        if (info != null && info.source != null && !info.source.isBlank()) {
            message.append("Resolved from ").append(info.source);
        } else {
            message.append("Resolved");
        }
        if (info != null && info.canEdit) {
            message.append(". Edit target: Active Environment (persisted variable).");
        } else if (info != null && info.activeEnvironmentName == null) {
            message.append(". No Active Environment selected. Select or import an environment to edit or create persisted variables.");
        }
        return message.toString();
    }

    private VariableTokenScanner.VariableToken findVariableToken(JTextComponent component, MouseEvent e) {
        if (component == null || e == null || currentRequest == null) {
            return null;
        }
        var vr = buildCurrentResolver();
        int offset = component.viewToModel2D(e.getPoint());
        return VariableTokenScanner.tokenAt(component.getText(), offset, vr.getVariables());
    }

    private burp.parser.VariableResolver buildCurrentResolver() {
        return RuntimeResolverFactory.build(
                currentCollection,
                currentRequest,
                runtimeVariablesExplicit
                        ? RuntimeResolverFactory.Options.withRuntimeVariableOverlay(runtimeVariables)
                        : RuntimeResolverFactory.Options.defaultOptions()
        );
    }

    private void refreshVariableAwareTables() {
        if (headersTable != null) {
            headersTable.repaint();
        }
    }

    private void installHeaderVariableSupport() {
        if (headersTable == null) {
            return;
        }
        headerVariableHoverSupport = new HeaderVariableHoverSupport(headersTable);
        javax.swing.table.DefaultTableCellRenderer renderer = new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                String text = value != null ? value.toString() : "";
                applyHeaderVariableStyle(label, text, row, isSelected);
                return label;
            }
        };
        headersTable.setDefaultRenderer(String.class, renderer);
        headersTable.setDefaultRenderer(Object.class, renderer);
        headersTable.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateHeaderTooltip(e);
                if (headerVariableHoverSupport != null) {
                    updateHeaderHoverState(e, headerVariableHoverSupport);
                }
            }
        });
        headersTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                headersTable.setToolTipText(null);
                if (headerVariableHoverSupport != null) {
                    headerVariableHoverSupport.scheduleHidePopup();
                }
            }
        });
    }

    private void applyHeaderVariableStyle(JLabel label, String text, int row, boolean isSelected) {
        if (label == null) {
            return;
        }
        Font tableFont = UIManager.getFont("Table.font");
        if (tableFont != null) {
            label.setFont(tableFont);
        }
        if (isSelected) {
            return;
        }
        if (headersTable != null && isHeaderRowDisabled(headersTable.convertRowIndexToModel(row))) {
            label.setForeground(VariableStatusColors.disabled(headersTable));
            Font disabledFont = VariableStatusColors.disabledFont(label.getFont());
            if (disabledFont != null) {
                label.setFont(disabledFont);
            }
            return;
        }
        var resolver = buildCurrentResolver();
        List<VariableTokenScanner.VariableToken> tokens = VariableTokenScanner.scan(text, resolver.getVariables());
        if (tokens.isEmpty()) {
            Color foreground = headersTable != null ? headersTable.getForeground() : UIManager.getColor("Table.foreground");
            if (foreground != null) {
                label.setForeground(foreground);
            }
            return;
        }
        boolean unresolved = tokens.stream().anyMatch(token -> token == null || !token.isResolved());
        if (unresolved) {
            label.setForeground(VariableStatusColors.unresolved(headersTable));
        } else {
            label.setForeground(VariableStatusColors.resolved(headersTable));
        }
    }

    private void updateHeaderHoverState(MouseEvent e, HeaderVariableHoverSupport support) {
        if (headersTable == null || e == null || currentRequest == null) {
            if (support != null) {
                support.hidePopup();
            }
            return;
        }
        HeaderVariableHover hover = resolveHeaderVariableHover(e);
        if (hover == null) {
            if (support != null) {
                if (isPointerInsideVisiblePopup(headersTable)) {
                    support.cancelHidePopup();
                } else if (popupForComponent(headersTable) != null) {
                    support.scheduleHidePopup();
                } else {
                    support.hidePopup();
                }
            }
            return;
        }
        if (support != null) {
            support.setCurrentInfo(hover.info, hover.anchorPoint);
        }
    }

    private void updateHeaderTooltip(MouseEvent e) {
        if (headersTable == null || e == null || currentRequest == null) {
            return;
        }
        HeaderVariableHover hover = resolveHeaderVariableHover(e);
        if (hover == null) {
            headersTable.setToolTipText(null);
            return;
        }
        VariableTokenScanner.VariableToken token = hover.token;
        VariableHoverInfo info = hover.info;
        StringBuilder tooltip = new StringBuilder();
        tooltip.append("{{").append(token.key).append("}}");
        tooltip.append(" - ").append(info.resolved ? "Resolved" : "Unresolved");
        if (info.source != null && !info.source.isBlank()) {
            tooltip.append(" from ").append(info.source);
        }
        if (info.value != null && !info.value.isBlank()) {
            tooltip.append(" = ").append(info.value);
        }
        if (info.shadowedSource != null && !info.shadowedSource.isBlank()) {
            tooltip.append(" (shadowed ").append(info.shadowedSource).append(')');
        }
        if (hover.disabled) {
            tooltip.append(" [Disabled header]");
        }
        headersTable.setToolTipText(tooltip.toString());
    }

    private VariableTokenScanner.VariableToken firstVariableToken(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var resolver = buildCurrentResolver();
        List<VariableTokenScanner.VariableToken> tokens = VariableTokenScanner.scan(text, resolver.getVariables());
        return tokens.isEmpty() ? null : tokens.get(0);
    }

    private HeaderVariableHover resolveHeaderVariableHover(MouseEvent e) {
        if (headersTable == null || e == null || currentRequest == null) {
            return null;
        }
        int row = headersTable.rowAtPoint(e.getPoint());
        int col = headersTable.columnAtPoint(e.getPoint());
        if (row < 0 || col < 0) {
            return null;
        }
        int modelRow = headersTable.convertRowIndexToModel(row);
        int modelCol = headersTable.convertColumnIndexToModel(col);
        Object value = headersTable.getModel().getValueAt(modelRow, modelCol);
        String text = value != null ? value.toString() : "";
        VariableTokenScanner.VariableToken token = firstVariableToken(text);
        if (token == null) {
            return null;
        }
        VariableHoverInfo info = null;
        if (variableActionBridge != null) {
            try {
                info = variableActionBridge.inspect(token.key);
            } catch (Exception ignored) {
                info = null;
            }
        }
        if (info == null) {
            info = createFallbackHoverInfo(token);
        }
        normalizeHoverInfo(info, token);
        boolean disabled = isHeaderRowDisabled(modelRow);
        if (disabled) {
            info.message = (info.message != null && !info.message.isBlank() ? info.message + " " : "")
                    + "Header is disabled and will be omitted from the final request.";
        }
        Rectangle cellBounds = headersTable.getCellRect(row, col, true);
        Point anchorPoint = new Point(cellBounds.x + Math.max(8, Math.min(cellBounds.width - 8, cellBounds.width / 2)),
                cellBounds.y + cellBounds.height - 2);
        return new HeaderVariableHover(token, info, anchorPoint, disabled);
    }

    private boolean isHeaderRowDisabled(int modelRow) {
        if (headersModel == null || modelRow < 0 || modelRow >= headersModel.getRowCount()) {
            return false;
        }
        Object disabled = headersModel.getValueAt(modelRow, HEADER_DISABLED_MODEL_COLUMN);
        return Boolean.TRUE.equals(disabled);
    }

    boolean promptAndApplyVariableEdit(VariableHoverInfo info) {
        return promptAndApplyVariableMutation(info, false);
    }

    boolean promptAndApplyVariableCreate(VariableHoverInfo info) {
        return promptAndApplyVariableMutation(info, true);
    }

    private boolean promptAndApplyVariableMutation(VariableHoverInfo info, boolean createIfMissing) {
        if (info == null || info.key == null || info.key.isBlank()) {
            return false;
        }
        if (variableActionBridge == null || !variableActionBridge.hasActiveEnvironment()) {
            if (variableDialogProvider != null) {
                variableDialogProvider.info(this, "Variable Editor", "No Active Environment selected. Select or import an environment to edit or create persisted variables.");
            }
            return false;
        }
        String actionTitle = createIfMissing ? "Create Variable" : "Edit Variable";
        String currentValue = info.value != null ? info.value : "";
        String initial = currentValue;
        String prompt = createIfMissing
                ? "Enter a value for " + info.key + " in the active environment."
                : "Enter a new value for " + info.key + ".";
        String newValue = variableDialogProvider != null
                ? variableDialogProvider.prompt(this, actionTitle, prompt, initial)
                : initial;
        if (newValue == null) {
            return false;
        }
        String envName = info.activeEnvironmentName != null && !info.activeEnvironmentName.isBlank()
                ? info.activeEnvironmentName
                : variableActionBridge.activeEnvironmentName();
        String confirmMessage = (createIfMissing ? "Create" : "Update")
                + " variable '" + info.key + "' in active environment '" + (envName != null ? envName : "Active Environment") + "'?\n"
                + "Old value: " + (currentValue.isBlank() ? "(empty)" : currentValue) + "\n"
                + "New value: " + (newValue.isBlank() ? "(empty)" : newValue) + "\n"
                + "Target scope: active environment (persisted)";
        boolean confirmed = variableDialogProvider == null
                || variableDialogProvider.confirm(this, actionTitle + " Confirmation", confirmMessage);
        if (!confirmed) {
            return false;
        }
        boolean applied = variableActionBridge.updateActiveEnvironment(info.key, newValue, createIfMissing, true);
        if (applied) {
            refreshAll();
            variableActionBridge.refreshEnvironmentUi();
        }
        return applied;
    }

    private JPopupMenu buildVariablePopup(JTextComponent component, VariableHoverInfo info) {
        return buildVariablePopup((Component) component, info);
    }

    private JPopupMenu buildVariablePopup(Component component, VariableHoverInfo info) {
        JPopupMenu popup = new JPopupMenu();
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel header = new JPanel(new GridLayout(0, 1, 0, 2));
        header.add(new JLabel("{{" + (info != null && info.key != null ? info.key : "") + "}}"));
        header.add(new JLabel("Status: " + (info != null ? info.statusText() : "Unknown")));
        header.add(new JLabel("Value: " + valuePreview(info)));
        header.add(new JLabel("Scope: " + scopePreview(info)));
        header.add(new JLabel("Source: " + sourcePreview(info)));
        if (info != null && info.shadowedSource != null && !info.shadowedSource.isBlank()) {
            String shadowed = info.shadowedSource;
            if (info.shadowedValue != null && !info.shadowedValue.isBlank()) {
                shadowed += " = " + info.shadowedValue;
            }
            header.add(new JLabel("Shadowed: " + shadowed));
        }
        header.add(new JLabel("Editable: " + editablePreview(info)));
        if (info != null && info.activeEnvironmentName != null && !info.activeEnvironmentName.isBlank()) {
            header.add(new JLabel("Active Env: " + info.activeEnvironmentName));
        }
        if (info != null && info.message != null && !info.message.isBlank()) {
            header.add(new JLabel(info.message));
        }
        panel.add(header, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton copyButton = new JButton("Copy Value");
        copyButton.addActionListener(e -> {
            if (info != null && info.value != null) {
                try {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(info.value), null);
                } catch (HeadlessException ignored) {
                }
            }
            popup.setVisible(false);
        });
        actions.add(copyButton);

        JButton editButton = new JButton(info != null && info.resolved
                ? "Edit in Active Env"
                : "Create in Active Env");
        editButton.setEnabled(info != null && variableActionBridge != null && variableActionBridge.hasActiveEnvironment()
                && (info.resolved ? info.canEdit : info.canCreate));
        editButton.setToolTipText(editButton.isEnabled()
                ? (info != null && info.resolved
                ? "Edit the value in the Active Environment after confirmation."
                : "Create the variable in the Active Environment after confirmation.")
                : "No Active Environment selected. Select or import one to edit or create persisted variables.");
        editButton.addActionListener(e -> {
            if (info != null && info.resolved) {
                promptAndApplyVariableEdit(info);
            } else {
                promptAndApplyVariableCreate(info);
            }
            popup.setVisible(false);
        });
        actions.add(editButton);

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> popup.setVisible(false));
        actions.add(closeButton);
        panel.add(actions, BorderLayout.SOUTH);
        popup.add(panel);
        return popup;
    }

    private String valuePreview(VariableHoverInfo info) {
        if (info == null || info.value == null || info.value.isBlank()) {
            return "(empty)";
        }
        return info.value;
    }

    private String scopePreview(VariableHoverInfo info) {
        if (info == null || info.scope == null || info.scope.isBlank()) {
            return "not found";
        }
        return info.scope;
    }

    private String sourcePreview(VariableHoverInfo info) {
        if (info == null || info.source == null || info.source.isBlank()) {
            return "unresolved";
        }
        return info.source;
    }

    private String editablePreview(VariableHoverInfo info) {
        if (variableActionBridge == null || !variableActionBridge.hasActiveEnvironment()) {
            return "No Active Environment selected";
        }
        if (info == null) {
            return "Unknown";
        }
        if (info.resolved) {
            return info.canEdit ? "Edit in Active Environment after confirmation" : "Read-only resolved value";
        }
        return info.canCreate ? "Create in Active Environment after confirmation" : "No permission to create";
    }

    private void showVariablePopup(JTextComponent component, VariableHoverInfo info, Point point, VariableHoverSupport support) {
        showVariablePopup((JComponent) component, info, point, support);
    }

    private void showVariablePopup(JComponent component, VariableHoverInfo info, Point point, HeaderVariableHoverSupport support) {
        Runnable cancelHideAction = support != null ? () -> support.cancelHidePopup() : null;
        showVariablePopup(component, info, point, cancelHideAction,
                popup -> {
                    if (support != null) {
                        support.trackPopup(popup);
                    }
                });
    }

    private void showVariablePopup(JComponent component, VariableHoverInfo info, Point point, VariableHoverSupport support) {
        Runnable cancelHideAction = support != null ? () -> support.cancelHidePopup() : null;
        showVariablePopup(component, info, point, cancelHideAction,
                popup -> {
                    if (support != null) {
                        support.trackPopup(popup);
                    }
                });
    }

    private void showVariablePopup(JComponent component,
                                   VariableHoverInfo info,
                                   Point point,
                                   Runnable cancelHideAction,
                                   java.util.function.Consumer<JPopupMenu> tracker) {
        if (component == null || info == null || info.key == null || info.key.isBlank() || point == null) {
            return;
        }
        hidePopupForComponent(component);
        JPopupMenu popup = buildVariablePopup(component, info);
        component.putClientProperty(VARIABLE_POPUP_PROPERTY, popup);
        if (tracker != null) {
            tracker.accept(popup);
        }
        if (cancelHideAction != null) {
            cancelHideAction.run();
        }
        if (component.isShowing()) {
            popup.show(component, Math.max(0, point.x), Math.max(0, point.y + 18));
        }
    }

    private void hideAllVariablePopups() {
        for (JTextComponent component : variableHoverSupports.keySet()) {
            hidePopupForComponent(component);
        }
        if (headersTable != null) {
            hidePopupForComponent(headersTable);
        }
    }

    private void hidePopupForComponent(JComponent component) {
        if (component == null) {
            return;
        }
        Object popup = component.getClientProperty(VARIABLE_POPUP_PROPERTY);
        if (popup instanceof JPopupMenu menu) {
            menu.setVisible(false);
        }
        component.putClientProperty(VARIABLE_POPUP_PROPERTY, null);
    }

    private final class VariableHoverSupport {
        private final JTextComponent component;
        private final javax.swing.Timer timer;
        private final javax.swing.Timer hideTimer;
        private MouseEvent lastEvent;
        private VariableHoverInfo lastInfo;
        private boolean hoveringPopup = false;

        private VariableHoverSupport(JTextComponent component) {
            this.component = component;
            this.timer = new javax.swing.Timer(VARIABLE_HOVER_DELAY_MS, e -> {
                if (lastInfo != null) {
                    showVariablePopup(component, lastInfo, lastEvent != null ? lastEvent.getPoint() : new Point(0, 0), this);
                }
            });
            this.timer.setRepeats(false);
            this.hideTimer = new javax.swing.Timer(VARIABLE_HOVER_HIDE_DELAY_MS, e -> hidePopup());
            this.hideTimer.setRepeats(false);
        }

        private void handleMouseMoved(MouseEvent e) {
            lastEvent = e;
            updateVariableHoverState(component, e, this);
        }

        private void setCurrentInfo(VariableHoverInfo info, Point point) {
            this.lastInfo = info;
            if (info == null || info.key == null || info.key.isBlank()) {
                timer.stop();
                hidePopup();
                return;
            }
            cancelHidePopup();
            timer.restart();
        }

        private void scheduleHidePopup() {
            if (hoveringPopup) {
                return;
            }
            hideTimer.restart();
        }

        private void cancelHidePopup() {
            hideTimer.stop();
        }

        private void trackPopup(JPopupMenu popup) {
            if (popup == null) {
                return;
            }
            attachPopupHoverListeners(popup);
        }

        private void attachPopupHoverListeners(Component root) {
            if (root == null) {
                return;
            }
            MouseAdapter listener = new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hoveringPopup = true;
                    cancelHidePopup();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hoveringPopup = false;
                    scheduleHidePopup();
                }
            };
            root.addMouseListener(listener);
            if (root instanceof Container container) {
                for (Component child : container.getComponents()) {
                    attachPopupHoverListeners(child);
                }
            }
        }

        private void hidePopup() {
            timer.stop();
            hideTimer.stop();
            hidePopupForComponent(component);
            lastInfo = null;
            lastEvent = null;
            hoveringPopup = false;
        }
    }

    private final class HeaderVariableHoverSupport {
        private final JComponent component;
        private final javax.swing.Timer timer;
        private final javax.swing.Timer hideTimer;
        private Point lastPoint;
        private VariableHoverInfo lastInfo;
        private boolean hoveringPopup = false;

        private HeaderVariableHoverSupport(JComponent component) {
            this.component = component;
            this.timer = new javax.swing.Timer(VARIABLE_HOVER_DELAY_MS, e -> {
                if (lastInfo != null) {
                    showVariablePopup(component, lastInfo, lastPoint != null ? lastPoint : new Point(0, 0), this);
                }
            });
            this.timer.setRepeats(false);
            this.hideTimer = new javax.swing.Timer(VARIABLE_HOVER_HIDE_DELAY_MS, e -> hidePopup());
            this.hideTimer.setRepeats(false);
        }

        private void setCurrentInfo(VariableHoverInfo info, Point point) {
            this.lastInfo = info;
            this.lastPoint = point;
            if (info == null || info.key == null || info.key.isBlank()) {
                timer.stop();
                hidePopup();
                return;
            }
            cancelHidePopup();
            timer.restart();
        }

        private void scheduleHidePopup() {
            if (hoveringPopup) {
                return;
            }
            hideTimer.restart();
        }

        private void cancelHidePopup() {
            hideTimer.stop();
        }

        private void trackPopup(JPopupMenu popup) {
            if (popup == null) {
                return;
            }
            attachPopupHoverListeners(popup);
        }

        private void attachPopupHoverListeners(Component root) {
            if (root == null) {
                return;
            }
            MouseAdapter listener = new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hoveringPopup = true;
                    cancelHidePopup();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hoveringPopup = false;
                    scheduleHidePopup();
                }
            };
            root.addMouseListener(listener);
            if (root instanceof Container container) {
                for (Component child : container.getComponents()) {
                    attachPopupHoverListeners(child);
                }
            }
        }

        private void hidePopup() {
            timer.stop();
            hideTimer.stop();
            hidePopupForComponent(component);
            lastInfo = null;
            lastPoint = null;
            hoveringPopup = false;
        }
    }

    private static final class HeaderVariableHover {
        private final VariableTokenScanner.VariableToken token;
        private final VariableHoverInfo info;
        private final Point anchorPoint;
        private final boolean disabled;

        private HeaderVariableHover(VariableTokenScanner.VariableToken token,
                                    VariableHoverInfo info,
                                    Point anchorPoint,
                                    boolean disabled) {
            this.token = token;
            this.info = info;
            this.anchorPoint = anchorPoint;
            this.disabled = disabled;
        }
    }

    private static final class SwingVariableDialogProvider implements VariableDialogProvider {
        @Override
        public String prompt(Component parent, String title, String message, String initialValue) {
            return JOptionPane.showInputDialog(parent, message, initialValue);
        }

        @Override
        public boolean confirm(Component parent, String title, String message) {
            int result = JOptionPane.showConfirmDialog(parent, message, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            return result == JOptionPane.OK_OPTION;
        }

        @Override
        public void info(Component parent, String title, String message) {
            JOptionPane.showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE);
        }
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
        if (req.isExactHttpMode()) {
            out.append("note=Advanced exact transport mode preserves authored transport/framing headers and does not synthesize defaults.\n");
        } else {
            out.append("note=Ordinary authored headers are preserved. Transport framing is regenerated safely.\n");
        }
        out.append("\n");
    }

    public JTextComponent getUrlField() { return urlField; }
    public JComboBox<String> getMethodBox() { return methodBox; }
    public JTabbedPane getTabs() { return tabs; }
    JButton getSendButtonForTests() { return sendBtn; }
    JButton getSendDropdownButtonForTests() { return sendDropdownBtn; }
    boolean isExactTransportHeadersSelectedForTests() { return exactTransportHeadersSelected; }
    JLabel getExactTransportIndicatorForTests() { return exactTransportIndicator; }
    JPopupMenu createSendDropdownMenuForTests() { return createSendDropdownMenu(); }
    boolean isExactTransportWarningAcknowledgedForTests() { return exactTransportWarningAcknowledged; }
    void setExactTransportWarningProviderForTests(ExactTransportWarningProvider provider) { this.exactTransportWarningProvider = provider; }
    JTable getHeadersTableForTests() { return headersTable; }
    JTextComponent getBodyRawAreaForTests() { return bodyRawArea; }
    JTextArea getResolvedViewAreaForTests() { return resolvedViewArea; }
    JPopupMenu getVisibleVariablePopupForTests() {
        JPopupMenu popup = popupForComponent(urlField);
        if (popup != null) {
            return popup;
        }
        popup = popupForComponent(bodyRawArea);
        if (popup != null) {
            return popup;
        }
        return popupForComponent(headersTable);
    }

    Rectangle getUrlVariableTokenBoundsForTests(String key) {
        return variableTokenBounds(urlField, key);
    }

    Rectangle getBodyVariableTokenBoundsForTests(String key) {
        return variableTokenBounds(bodyRawArea, key);
    }

    Rectangle getHeaderVariableCellBoundsForTests(String key) {
        if (headersTable == null || key == null || key.isBlank()) {
            return null;
        }
        for (int row = 0; row < headersTable.getRowCount(); row++) {
            for (int col = 0; col < Math.min(2, headersTable.getColumnCount()); col++) {
                Object value = headersTable.getValueAt(row, col);
                VariableTokenScanner.VariableToken token = firstVariableToken(value != null ? value.toString() : null);
                if (token != null && key.equals(token.key)) {
                    return headersTable.getCellRect(row, col, true);
                }
            }
        }
        return null;
    }

    private Rectangle variableTokenBounds(JTextComponent component, String key) {
        if (component == null || key == null || key.isBlank()) {
            return null;
        }
        var resolver = buildCurrentResolver();
        List<VariableTokenScanner.VariableToken> tokens = VariableTokenScanner.scan(textOf(component), resolver.getVariables());
        for (VariableTokenScanner.VariableToken token : tokens) {
            if (token == null || !key.equals(token.key)) {
                continue;
            }
            try {
                Shape startShape = component.modelToView2D(token.start);
                Shape endShape = component.modelToView2D(Math.max(token.start, token.end - 1));
                if (startShape == null || endShape == null) {
                    return null;
                }
                Rectangle start = startShape.getBounds();
                Rectangle end = endShape.getBounds();
                return start.union(end);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private JPopupMenu popupForComponent(JComponent component) {
        if (component == null) {
            return null;
        }
        Object popup = component.getClientProperty(VARIABLE_POPUP_PROPERTY);
        if (popup instanceof JPopupMenu menu && menu.isVisible()) {
            return menu;
        }
        return null;
    }

    private boolean isPointerInsideVisiblePopup(JComponent component) {
        JPopupMenu popup = popupForComponent(component);
        if (popup == null || !popup.isShowing()) {
            return false;
        }
        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
        if (pointerInfo == null || pointerInfo.getLocation() == null) {
            return false;
        }
        try {
            Point popupLocation = popup.getLocationOnScreen();
            Rectangle popupBounds = new Rectangle(popupLocation, popup.getSize());
            return popupBounds.contains(pointerInfo.getLocation());
        } catch (IllegalComponentStateException ignored) {
            return false;
        }
    }

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
                this::isExactHttpModeSelected,
                this::resolveEditorAuthMode,
                this::buildAuthFromFields,
                this::refreshResolvedMirror,
                requestBuilder
        );
        return ctx;
    }

    private boolean isExactHttpModeSelected() {
        return exactTransportHeadersSelected;
    }

    private void setExactHttpModeSelected(boolean exactHttpMode) {
        exactTransportHeadersSelected = exactHttpMode;
        updateExactTransportIndicator();
    }

    private void updateExactTransportIndicator() {
        boolean visible = exactTransportHeadersSelected;
        if (exactTransportIndicator != null) {
            exactTransportIndicator.setForeground(resolveWarningForeground());
            exactTransportIndicator.setVisible(visible);
        }
        if (exactTransportIndicatorWrapper != null) {
            exactTransportIndicatorWrapper.setVisible(visible);
            exactTransportIndicatorWrapper.revalidate();
            exactTransportIndicatorWrapper.repaint();
        }
    }

    void setRequestBuildModeChangeListener(Runnable listener) {
        this.requestBuildModeChangeListener = listener;
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
