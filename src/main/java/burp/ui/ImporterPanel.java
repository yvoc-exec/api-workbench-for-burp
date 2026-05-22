package burp.ui;

import burp.models.*;
import burp.parser.*;
import burp.runner.CollectionRunner;
import burp.auth.OAuth2Manager;
import burp.auth.OAuth2Config;
import burp.auth.TokenStore;
import burp.utils.RuntimeVariablesJson;
import burp.UniversalImporter;
import burp.utils.OAuth2BearerAliasDetector;
import burp.utils.UnresolvedVariableAnalyzer;
import burp.ui.tree.CollectionTreeNode;
import burp.ui.tree.BurpLikeTreeCellRenderer;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

import javax.swing.*;
import javax.swing.plaf.basic.BasicTreeUI;
import javax.swing.plaf.TreeUI;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class ImporterPanel {
    private static final Logger LOGGER = Logger.getLogger(ImporterPanel.class.getName());
    private static final char WORKSPACE_KEY_DELIMITER = '\u001F';
    private static final String TREE_SHOW_INITIALIZER_KEY = "apiWorkbench.treeShowInitializer";
    private static final String TREE_SHOW_INITIALIZER_LISTENER_KEY = "apiWorkbench.treeShowInitializerListener";
    private static final String MAIN_TREE_RESTORE_INITIALIZER_KEY = "apiWorkbench.mainTreeRestoreInitializer";
    private static final String MAIN_TREE_RESTORE_INITIALIZER_LISTENER_KEY = "apiWorkbench.mainTreeRestoreInitializerListener";
    private static final String WORKSPACE_KEY_DELIMITER_ESCAPED_UPPER = "\\u001F";
    private static final String WORKSPACE_KEY_DELIMITER_ESCAPED_LOWER = "\\u001f";
    private static final int MAIN_TREE_MIN_LEFT_CHILD_INDENT = 7;
    private static final int MAIN_TREE_MIN_RIGHT_CHILD_INDENT = 13;

    private final UniversalImporter importer;
    private final CollectionRunner runner;
    private final OAuth2Manager oauth2Manager;
    private final burp.utils.RequestBuilder requestBuilder;
    private final JPanel mainPanel;
    private JTabbedPane tabbedPane;

    // Multi-collection support
    private final List<ApiCollection> loadedCollections = new ArrayList<>();
    private final IdentityHashMap<ApiRequest, ApiCollection> requestToCollectionMap = new IdentityHashMap<>();
    private OAuth2Panel oauth2Panel;
    private File selectedEnv;

    // Workbench tab
    private JTree requestTree;
    private JScrollPane requestTreeScrollPane;
    private DefaultTreeModel treeModel;
    private JProgressBar importProgress;
    private JCheckBox repeaterBtn, sitemapBtn, intruderBtn;
    private JSpinner delaySpinner;
    private JButton importBtn, sendToRunnerBtn, addCollectionBtn, removeCollectionBtn;
    private JButton actionsBtn;
    private JCheckBox debugRawRequestBox;
    private JTextField envField;
    private JButton envBrowseBtn, envApplyCheckedBtn, envApplyCheckedCollectionsBtn, envApplyAllBtn;
    private RequestEditorPanel requestEditor;
    private JTabbedPane workbenchDetailTabs;
    private HttpRequestEditor workbenchRequestEditor;
    private HttpResponseEditor workbenchResponseEditor;
    private JTextArea workbenchMetaText;
    private JTextArea importLog;

    // Runner tab
    private JTextArea runnerLog;
    private JProgressBar runnerProgress;
    private JTable resultTable;
    private RunnerResultTableModel resultModel;
    private JTable timelineTable;
    private RunnerTimelineTableModel timelineModel;
    private JTabbedPane runnerDetailTabs;
    private JSpinner runnerDelaySpinner;
    private JSpinner runnerRetriesSpinner;
    private JCheckBox stopOnErrorBox;
    private JCheckBox stopOnAssertionFailureBox;
    private JCheckBox stopOnStatusAtLeast400Box;
    private JCheckBox stopOnMissingVariableBox;
    private JSpinner stopAfterFailuresSpinner;
    private JCheckBox followRedirectsBox;
    private JCheckBox runnerDebugRawRequestBox;
    private JButton pauseRunnerBtn, resumeRunnerBtn, stepRunnerBtn, startRunnerBtn, cancelRunnerBtn;
    private RunnerPreviewTableModel runnerPreviewModel;
    private javax.swing.Timer runnerCancelPollTimer;

    // Runner detail pane
    private HttpRequestEditor detailRequestEditor;
    private HttpResponseEditor detailResponseEditor;
    private JTextArea detailVarsText;

    // Variables tab
    private JTextArea envVarsArea;
    private JTable varsTable;
    private DefaultTableModel varsTableModel;
    private JPanel varsEditorCardPanel;
    private JRadioButton varsRawViewBtn;
    private JRadioButton varsTableViewBtn;
    private JComboBox<CollectionRef> varsCollectionCombo;
    private JButton bindVarsBtn;
    private JLabel varsHintLabel;
    private JLabel varsAutosaveStatusLabel;
    private burp.utils.DebouncedSwingAction variablesAutosave;
    private boolean suppressVariablesAutosave = false;
    private String varsBaseLayerText = "";

    // OAuth2 tab
    private JComboBox<CollectionRef> oauth2CollectionCombo;
    private JButton bindOAuth2Btn;
    private JLabel oauth2HintLabel;
    private JLabel oauth2AutosaveStatusLabel;
    private final Map<ApiCollection, OAuthAutoRefreshState> oauthAutoStates = new IdentityHashMap<>();
    private final ScheduledExecutorService oauthAutoExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "oauth2-auto-refresh");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean shuttingDown = false;

    // Runner listener deduplication
    private CollectionRunner.RunnerListener activeRunnerListener;

    // Workspace persistence callback
    private Runnable workspaceChangeListener;
    private boolean suppressWorkspaceChangeNotifications = false;
    private Map<String, String> pendingWorkspaceRequestTreePaths = Collections.emptyMap();
    // Send mode is tracked by the RequestEditorPanel send button label
    private final burp.utils.ScriptMode scriptMode;
    private final List<ApiRequest> runnerQueuedRequests = new ArrayList<>();

    private static class OAuthAutoRefreshState {
        boolean enabled;
        int intervalSeconds = 300;
        ScheduledFuture<?> future;
        String lastStatus;
    }

    public ImporterPanel(UniversalImporter importer, CollectionRunner runner, OAuth2Manager oauth2Manager, burp.utils.ScriptMode scriptMode) {
        this.scriptMode = scriptMode;
        this.oauth2Manager = oauth2Manager;
        this.requestBuilder = new burp.utils.RequestBuilder(importer.getApi(), oauth2Manager);
        this.oauth2Panel = new OAuth2Panel(oauth2Manager);
        this.oauth2Panel.setTokenAcquiredCollectionSupplier(this::getSelectedOAuth2Collection);
        this.oauth2Panel.setTokenAcquiredListener(this::handleOAuth2TokenAcquired);
        this.importer = importer;
        this.runner = runner;
        this.mainPanel = createUI();
        if (oauth2Panel.getPopulateButton() != null) {
            oauth2Panel.getPopulateButton().setText("Populate from Request");
            oauth2Panel.getPopulateButton().addActionListener(e -> populateOAuth2FromRequest());
        }
    }

    public void setWorkspaceChangeListener(Runnable listener) {
        this.workspaceChangeListener = listener;
    }

    private void notifyWorkspaceChanged() {
        if (shuttingDown || suppressWorkspaceChangeNotifications) {
            return;
        }
        if (workspaceChangeListener != null) {
            workspaceChangeListener.run();
        }
    }

    private void runWithWorkspaceChangeNotificationsSuppressed(Runnable action) {
        boolean previous = suppressWorkspaceChangeNotifications;
        suppressWorkspaceChangeNotifications = true;
        try {
            if (action != null) {
                action.run();
            }
        } finally {
            suppressWorkspaceChangeNotifications = previous;
        }
    }

    private JPanel createUI() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Workbench", createWorkbenchTab());
        tabbedPane.addTab("Variables", createVariablesTab());
        tabbedPane.addTab("OAuth2", createOAuth2Tab());
        tabbedPane.addTab("Collection Runner", createRunnerTab());

        panel.add(tabbedPane, BorderLayout.CENTER);

        // Script mode status bar
        if (scriptMode != null) {
            JLabel scriptModeLabel = new JLabel("Script mode: " + scriptMode.label);
            scriptModeLabel.setToolTipText(scriptMode.description);
            scriptModeLabel.setFont(new Font(Font.DIALOG, Font.ITALIC, 11));
            scriptModeLabel.setForeground(Color.GRAY);
            panel.add(scriptModeLabel, BorderLayout.SOUTH);
        }

        return panel;
    }

    // ========================================================================
    // Workbench Tab
    // ========================================================================
    private JPanel createWorkbenchTab() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));

        // Main horizontal split: left (tree+controls) | right (editor/response)
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplit.setLeftComponent(createLeftWorkbenchPanel());
        mainSplit.setRightComponent(createRightWorkbenchPanel());
        mainSplit.setResizeWeight(0.30);
        mainSplit.setOneTouchExpandable(true);
        mainSplit.setContinuousLayout(true);
        mainSplit.setDividerSize(8);
        panel.add(mainSplit, BorderLayout.CENTER);

        // Bottom full-width workbench log.
        panel.add(createWorkbenchLogRow(), BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createCollectionControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createTitledBorder("Collections"));
        addCollectionBtn = new JButton("+ Add Collection");
        addCollectionBtn.addActionListener(e -> addCollection());
        removeCollectionBtn = new JButton("- Remove Collection");
        removeCollectionBtn.addActionListener(e -> showRemoveCollectionsDialog());
        removeCollectionBtn.setEnabled(false);
        panel.add(addCollectionBtn);
        panel.add(removeCollectionBtn);
        return panel;
    }

    private JPanel createLeftWorkbenchPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setMinimumSize(new Dimension(220, 300));
        panel.setPreferredSize(new Dimension(280, 500));

        panel.add(createCollectionControls(), BorderLayout.NORTH);

        // Tree
        treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("Collections"));
        requestTreeScrollPane = new JScrollPane();
        mountMainRequestTree(buildMainRequestTree());
        requestTreeScrollPane.setBorder(BorderFactory.createTitledBorder("Request Tree"));
        panel.add(requestTreeScrollPane, BorderLayout.CENTER);

        JPanel lowerControls = new JPanel();
        lowerControls.setLayout(new BoxLayout(lowerControls, BoxLayout.Y_AXIS));
        lowerControls.add(createEnvBindingRow());
        lowerControls.add(createActionsRow());
        panel.add(lowerControls, BorderLayout.SOUTH);

        return panel;
    }

    private void mountMainRequestTree(JTree tree) {
        if (requestTree != null) {
            clearTreeShowInitializer(requestTree);
        }
        requestTree = tree;
        if (requestTreeScrollPane != null) {
            requestTreeScrollPane.setViewportView(tree);
            requestTreeScrollPane.revalidate();
            requestTreeScrollPane.repaint();
        }
    }

    private JTree buildMainRequestTree() {
        JTree tree = new MainRequestTree(treeModel);
        tree.setRootVisible(false);
        tree.setCellRenderer(new BurpLikeTreeCellRenderer(false));
        tree.setRowHeight(20);
        tree.setScrollsOnExpand(false);
        tree.setShowsRootHandles(true);
        configureMainTreeUi(tree);
        tree.addMouseListener(new TreeMouseListener());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(e -> {
            persistCurrentRequestEditorState();
            TreePath path = requestTree.getSelectionPath();
            if (path != null) {
                Object node = path.getLastPathComponent();
                if (node instanceof CollectionTreeNode) {
                    CollectionTreeNode ctn = (CollectionTreeNode) node;
                    if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST && ctn.request != null) {
                        ApiCollection selectedCollection = findCollectionForNode(ctn);
                        requestEditor.setCurrentCollection(selectedCollection);
                        syncRequestEditorRuntimeContext(ctn.request, selectedCollection);
                        requestEditor.loadRequest(ctn.request);
                    }
                }
            }
            updateScopeControlState();
        });
        return tree;
    }

    private JComponent createRightWorkbenchPanel() {
        requestEditor = new RequestEditorPanel();
        requestEditor.setRequestBuilder(requestBuilder);

        requestEditor.setSendActionListener(() -> executeWorkbenchSend());

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        split.setTopComponent(requestEditor);
        split.setBottomComponent(createWorkbenchDetailTabs());
        split.setResizeWeight(0.68);
        split.setOneTouchExpandable(true);
        split.setContinuousLayout(true);
        split.setDividerSize(8);
        return split;
    }

    private JTabbedPane createWorkbenchDetailTabs() {
        workbenchDetailTabs = new JTabbedPane();
        workbenchRequestEditor = importer.getApi().userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        workbenchDetailTabs.addTab("Request", workbenchRequestEditor.uiComponent());

        workbenchResponseEditor = importer.getApi().userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        workbenchDetailTabs.addTab("Response", workbenchResponseEditor.uiComponent());

        workbenchMetaText = new JTextArea();
        workbenchMetaText.setEditable(false);
        workbenchMetaText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        workbenchDetailTabs.addTab("Meta", new JScrollPane(workbenchMetaText));
        return workbenchDetailTabs;
    }

    private JPanel createEnvBindingRow() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        panel.setBorder(BorderFactory.createTitledBorder("Environment Binding"));
        envField = new JTextField(20);
        envField.setEditable(false);
        envBrowseBtn = new JButton("Browse...");
        envBrowseBtn.addActionListener(e -> selectEnvironment());
        envApplyCheckedBtn = new JButton("Apply to Checked Requests");
        envApplyCheckedBtn.setEnabled(false);
        envApplyCheckedBtn.addActionListener(e -> applyEnvToCheckedRequests());
        envApplyCheckedCollectionsBtn = new JButton("Apply to Checked Collections");
        envApplyCheckedCollectionsBtn.setEnabled(false);
        envApplyCheckedCollectionsBtn.addActionListener(e -> applyEnvToCheckedCollections());
        envApplyAllBtn = new JButton("Apply to All Collections");
        envApplyAllBtn.setEnabled(false);
        envApplyAllBtn.addActionListener(e -> applyEnvToAllCollections());
        panel.add(new JLabel("Env:"));
        panel.add(envField);
        panel.add(envBrowseBtn);
        return panel;
    }

    private void ensureWorkbenchActionDefaultsInitialized() {
        if (repeaterBtn != null && sitemapBtn != null && intruderBtn != null && delaySpinner != null && debugRawRequestBox != null) {
            return;
        }
        // Persisted defaults for the Actions popup.
        repeaterBtn = new JCheckBox("Repeater", true);
        sitemapBtn = new JCheckBox("Sitemap (Live)");
        intruderBtn = new JCheckBox("Intruder");
        delaySpinner = new JSpinner(new SpinnerNumberModel(200, 0, 5000, 50));
        delaySpinner.setPreferredSize(new Dimension(70, 22));
        debugRawRequestBox = new JCheckBox("Debug final raw request");
    }

    private JPanel createActionsRow() {
        ensureWorkbenchActionDefaultsInitialized();
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        panel.setBorder(BorderFactory.createTitledBorder("Options"));

        importBtn = new JButton("Actions");
        importBtn.setEnabled(false);
        importBtn.addActionListener(e -> showActionsDialog());
        actionsBtn = importBtn;
        sendToRunnerBtn = new JButton("Run Checked");
        sendToRunnerBtn.setEnabled(false);
        panel.add(importBtn);
        return panel;
    }

    private JPanel createWorkbenchLogRow() {
        JPanel logPanel = new JPanel(new BorderLayout(5, 5));
        importProgress = null;
        importLog = new JTextArea(3, 50);
        importLog.setEditable(false);
        importLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(importLog);
        logScroll.setBorder(BorderFactory.createTitledBorder("Workbench Log"));
        logScroll.setPreferredSize(new Dimension(400, 100));
        logPanel.add(logScroll, BorderLayout.CENTER);
        return logPanel;
    }

    private void executeWorkbenchSend() {
        if (requestEditor != null) {
            requestEditor.commitAllEdits();
        }
        ApiRequest liveRequest = requestEditor != null ? requestEditor.getCurrentRequest() : null;
        ApiCollection col = requestEditor != null ? requestEditor.getCurrentCollection() : null;
        requestEditor.commitAllEdits();
        ApiRequest edited = requestEditor.buildRequestFromUI();
        if (edited == null || liveRequest == null) {
            appendImportLog("No request loaded in editor.");
            return;
        }
        if (col == null) {
            appendImportLog("Send failed: no collection context is bound to the current request.");
            return;
        }
        applyEditedRequestToLiveRequest(col, liveRequest, edited);
        syncRequestEditorRuntimeContext(liveRequest, col);
        notifyWorkspaceChanged();

        final ApiCollection resolvedCol = col;
        final ApiRequest requestToSend = liveRequest;
        final String sendModeLabel = requestEditor.getSendModeLabel();
        List<UnresolvedVariableIssue> issues = collectUnresolvedVariableIssues(List.of(resolvedCol), List.of(requestToSend));
        if (!issues.isEmpty()) {
            UnresolvedVariablesDialog.Action action = showUnresolvedVariablesDialog(issues, List.of(resolvedCol));
            if (action == UnresolvedVariablesDialog.Action.CANCEL) {
                appendImportLog("Send cancelled due to unresolved variables.");
                return;
            }
        }
        requestEditor.setSendEnabled(false);
        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    publish("Sending: " + requestToSend.method + " " + requestToSend.url);
                    boolean follow = followRedirectsBox != null && followRedirectsBox.isSelected();

                    var result = importer.sendSingleRequestWithBuiltRequest(requestToSend, resolvedCol, follow);
                    var rr = result.response;

                    if (rr != null && rr.response() != null) {
                        var resp = rr.response();
                        byte[] bodyBytes = resp.body().getBytes();
                        SwingUtilities.invokeLater(() -> updateWorkbenchDetailPaneSuccess(requestToSend, result, sendModeLabel));
                        publish("Response: " + resp.statusCode() + " (" + bodyBytes.length + " bytes, " + result.elapsedMs + " ms)");
                    } else {
                        publish("No response received.");
                    }

                    if ("Send + Repeater".equals(sendModeLabel) && result.builtRequest != null) {
                        String tabName = importer.generateRepeaterTabName(requestToSend.name,
                            requestToSend.sourceCollection != null ? requestToSend.sourceCollection : "Unknown");
                        importer.sendToRepeater(result.builtRequest, tabName);
                        publish("Sent to Repeater: " + tabName);
                    }
                } catch (Exception e) {
                    String failureReason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    SwingUtilities.invokeLater(() -> updateWorkbenchDetailPaneFailure(requestToSend, failureReason, sendModeLabel));
                    publish("Send failed: " + failureReason);
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) appendImportLog(msg);
            }

            @Override
            protected void done() {
                requestEditor.setSendEnabled(true);
            }
        };
        worker.execute();
    }

    static void applyEditedRequestToLiveRequest(ApiCollection collection, ApiRequest liveRequest, ApiRequest edited) {
        if (liveRequest == null || edited == null) {
            return;
        }

        liveRequest.method = edited.method;
        liveRequest.url = edited.url;
        liveRequest.editorMaterialized = edited.editorMaterialized;
        liveRequest.headers = copyHeaders(edited.headers);
        liveRequest.body = copyBody(edited.body);
        liveRequest.preRequestScripts = copyScripts(edited.preRequestScripts);
        liveRequest.postResponseScripts = copyScripts(edited.postResponseScripts);
        liveRequest.authOverrideMode = edited.authOverrideMode != null ? edited.authOverrideMode : "inherit";
        liveRequest.explicitAuth = burp.utils.AuthInheritanceResolver.copyAuth(edited.explicitAuth);
        burp.utils.AuthInheritanceResolver.resolveRequestAuth(collection, liveRequest);

        if (collection != null) {
            collection.fireChanged();
        }
    }

    private static List<ApiRequest.Header> copyHeaders(List<ApiRequest.Header> headers) {
        List<ApiRequest.Header> out = new ArrayList<>();
        if (headers == null) {
            return out;
        }
        for (ApiRequest.Header header : headers) {
            if (header == null) {
                out.add(null);
                continue;
            }
            out.add(new ApiRequest.Header(header.key, header.value, header.disabled));
        }
        return out;
    }

    private static ApiRequest.Body copyBody(ApiRequest.Body body) {
        if (body == null) {
            return null;
        }
        ApiRequest.Body copy = new ApiRequest.Body();
        copy.mode = body.mode;
        copy.raw = body.raw;
        copy.contentType = body.contentType;
        copy.formdata = copyBodyFields(body.formdata);
        copy.urlencoded = copyBodyFields(body.urlencoded);
        if (body.graphql != null) {
            copy.graphql = new ApiRequest.Body.GraphQL();
            copy.graphql.query = body.graphql.query;
            copy.graphql.variables = body.graphql.variables;
        }
        return copy;
    }

    private static List<ApiRequest.Body.FormField> copyBodyFields(List<ApiRequest.Body.FormField> fields) {
        List<ApiRequest.Body.FormField> out = new ArrayList<>();
        if (fields == null) {
            return out;
        }
        for (ApiRequest.Body.FormField field : fields) {
            if (field == null) {
                out.add(null);
                continue;
            }
            ApiRequest.Body.FormField copy = new ApiRequest.Body.FormField(field.key, field.value);
            copy.type = field.type;
            copy.fileUpload = field.fileUpload;
            copy.filePath = field.filePath;
            copy.disabled = field.disabled;
            out.add(copy);
        }
        return out;
    }

    private static List<ApiRequest.Script> copyScripts(List<ApiRequest.Script> scripts) {
        List<ApiRequest.Script> out = new ArrayList<>();
        if (scripts == null) {
            return out;
        }
        for (ApiRequest.Script script : scripts) {
            if (script == null) {
                out.add(null);
                continue;
            }
            out.add(new ApiRequest.Script(script.type, script.exec));
        }
        return out;
    }

    private void updateWorkbenchDetailPaneSuccess(ApiRequest edited, UniversalImporter.SingleSendResult result, String sendModeLabel) {
        if (workbenchRequestEditor != null) {
            workbenchRequestEditor.setRequest(result.builtRequest != null ? result.builtRequest : HttpRequest.httpRequest());
        }
        if (workbenchResponseEditor != null) {
            workbenchResponseEditor.setResponse(result.response != null ? result.response.response() : HttpResponse.httpResponse());
        }
        if (workbenchMetaText != null) {
            workbenchMetaText.setText(buildWorkbenchMetaText(edited, result, sendModeLabel, null));
            workbenchMetaText.setCaretPosition(0);
        }
    }

    private void updateWorkbenchDetailPaneFailure(ApiRequest edited, String reason, String sendModeLabel) {
        if (workbenchRequestEditor != null) {
            workbenchRequestEditor.setRequest(HttpRequest.httpRequest());
        }
        if (workbenchResponseEditor != null) {
            workbenchResponseEditor.setResponse(HttpResponse.httpResponse());
        }
        if (workbenchMetaText != null) {
            workbenchMetaText.setText(buildWorkbenchMetaText(edited, null, sendModeLabel, reason));
            workbenchMetaText.setCaretPosition(0);
        }
    }

    private String buildWorkbenchMetaText(ApiRequest edited, UniversalImporter.SingleSendResult result, String sendModeLabel, String failureReason) {
        StringBuilder meta = new StringBuilder();
        if (failureReason != null && !failureReason.isEmpty()) {
            meta.append("Send failed: ").append(failureReason).append("\n");
            return meta.toString();
        }
        String method = edited != null && edited.method != null ? edited.method : "GET";
        String requestName = edited != null && edited.name != null ? edited.name : "(unnamed)";
        meta.append("Request: ").append(requestName).append(" [").append(method).append("]\n");
        String authLine = buildAuthMetaLine(edited);
        if (authLine != null) {
            meta.append(authLine);
        }
        meta.append("Resolved URL: ").append(result != null && result.resolvedUrl != null ? result.resolvedUrl : "").append("\n");
        int statusCode = 0;
        int responseBytes = 0;
        if (result != null && result.response != null && result.response.response() != null) {
            var response = result.response.response();
            statusCode = response.statusCode();
            responseBytes = response.body() != null ? response.body().getBytes().length : 0;
        }
        meta.append("Status: ").append(statusCode).append("\n");
        meta.append("Elapsed: ").append(result != null ? result.elapsedMs : 0L).append(" ms\n");
        meta.append("Response bytes: ").append(responseBytes).append("\n");
        meta.append("Send mode: ").append(sendModeLabel != null ? sendModeLabel : "").append("\n");
        if (result != null && result.rawRequestText != null) {
            Set<String> unresolved = burp.utils.RequestBuilder.findUnresolvedTokens(result.rawRequestText.getBytes(StandardCharsets.UTF_8));
            if (!unresolved.isEmpty()) {
                meta.append("Unresolved tokens: ").append(String.join(", ", unresolved)).append("\n");
            }
        }
        return meta.toString();
    }

    static boolean isRunnerPreviewMissingAuth(RunnerPreviewRow row) {
        if (row == null) {
            return true;
        }
        String status = row.authStatus;
        if (status == null || status.isBlank()) {
            return true;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return "none".equals(normalized) || normalized.startsWith("no auth");
    }

    static String buildAuthMetaLine(ApiRequest edited) {
        if (edited == null || edited.auth == null || edited.auth.type == null) {
            return null;
        }
        StringBuilder meta = new StringBuilder();
        meta.append("Auth: ").append(edited.auth.type);
        if (edited.authSource != null && !edited.authSource.isBlank()) {
            meta.append(" (").append(edited.authSource).append(")");
        }
        meta.append("\n");
        return meta.toString();
    }



    // ========================================================================
    // Variables Tab
    // ========================================================================
    private JPanel createVariablesTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createTitledBorder("Environment Variables (JSON or key=value per line)"));

        envVarsArea = new JTextArea(20, 60);
        envVarsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        envVarsArea.setText("# Example:\n# base_url=http://localhost:8080\n# api_key=your_key_here\n# token={{auth_token}}");
        variablesAutosave = new burp.utils.DebouncedSwingAction(500, this::autosaveVariablesToSelectedCollection);
        envVarsArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { scheduleVariablesAutosave(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { scheduleVariablesAutosave(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { scheduleVariablesAutosave(); }
        });

        varsTableModel = new DefaultTableModel(new Object[]{"Key", "Value"}, 0);
        varsTable = new JTable(varsTableModel);
        varsTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        varsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        varsTableModel.addTableModelListener(e -> scheduleVariablesAutosave());

        varsEditorCardPanel = new JPanel(new CardLayout());
        varsEditorCardPanel.add(new JScrollPane(envVarsArea), "raw");

        JPanel tablePanel = new JPanel(new BorderLayout(5, 5));
        tablePanel.add(new JScrollPane(varsTable), BorderLayout.CENTER);
        JPanel tableBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addVarBtn = new JButton("+");
        addVarBtn.addActionListener(e -> {
            commitTableEdit(varsTable);
            varsTableModel.addRow(new Object[]{"", ""});
            syncRawFromVarsTable();
            scheduleVariablesAutosave();
        });
        JButton delVarBtn = new JButton("-");
        delVarBtn.addActionListener(e -> {
            commitTableEdit(varsTable);
            int row = resolveTargetRow(varsTable);
            if (row >= 0) varsTableModel.removeRow(row);
            syncRawFromVarsTable();
            scheduleVariablesAutosave();
        });
        tableBtnPanel.add(addVarBtn);
        tableBtnPanel.add(delVarBtn);
        tablePanel.add(tableBtnPanel, BorderLayout.SOUTH);
        varsEditorCardPanel.add(tablePanel, "table");

        JPanel varsTopBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        varsRawViewBtn = new JRadioButton("Raw", true);
        varsTableViewBtn = new JRadioButton("Table");
        ButtonGroup varsViewGroup = new ButtonGroup();
        varsViewGroup.add(varsRawViewBtn);
        varsViewGroup.add(varsTableViewBtn);
        varsRawViewBtn.addActionListener(e -> switchVarsView(false));
        varsTableViewBtn.addActionListener(e -> switchVarsView(true));
        varsTopBar.add(new JLabel("View:"));
        varsTopBar.add(varsRawViewBtn);
        varsTopBar.add(varsTableViewBtn);

        JPanel centerWrap = new JPanel(new BorderLayout(5, 5));
        centerWrap.add(varsTopBar, BorderLayout.NORTH);
        centerWrap.add(varsEditorCardPanel, BorderLayout.CENTER);
        panel.add(centerWrap, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));

        // Hint label
        varsHintLabel = new JLabel("Select a collection to edit scoped variables.");
        varsHintLabel.setForeground(Color.GRAY);
        varsAutosaveStatusLabel = new JLabel("Autosave idle.");
        varsAutosaveStatusLabel.setForeground(Color.GRAY);
        JPanel varsStatusPanel = new JPanel(new GridLayout(2, 1));
        varsStatusPanel.add(varsHintLabel);
        varsStatusPanel.add(varsAutosaveStatusLabel);
        bottomPanel.add(varsStatusPanel, BorderLayout.CENTER);

        JPanel bindPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        varsCollectionCombo = new JComboBox<>();
        varsCollectionCombo.setPrototypeDisplayValue(new CollectionRef(null, "Select collection..."));
        varsCollectionCombo.addActionListener(e -> {
            if (variablesAutosave != null) {
                variablesAutosave.stop();
            }
            renderEffectiveVariablesForSelectedCollection();
            updateScopeControlState();
            if (varsCollectionCombo.getSelectedItem() != null) {
                setVarsAutosaveStatus("Autosave idle.", Color.GRAY);
            } else {
                setVarsAutosaveStatus("Select a collection to autosave variables.", Color.GRAY);
            }
        });
        bindVarsBtn = new JButton("Save Now");
        bindVarsBtn.addActionListener(e -> bindVarsToSelectedCollection());
        JButton bindAllBtn = new JButton("Bind to All Collections");
        bindAllBtn.addActionListener(e -> bindVarsToAllCollections());
        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> clearVariablesEditorOnly());
        bindPanel.add(new JLabel("Target:"));
        bindPanel.add(varsCollectionCombo);
        bindPanel.add(bindVarsBtn);
        bindPanel.add(bindAllBtn);
        JButton exportRuntimeBtn = new JButton("Export Runtime JSON");
        exportRuntimeBtn.addActionListener(e -> exportSelectedCollectionRuntimeJson());
        JButton importRuntimeBtn = new JButton("Import Runtime JSON");
        importRuntimeBtn.addActionListener(e -> importSelectedCollectionRuntimeJson());
        bindPanel.add(exportRuntimeBtn);
        bindPanel.add(importRuntimeBtn);
        bindPanel.add(clearBtn);
        bottomPanel.add(bindPanel, BorderLayout.NORTH);

        panel.add(bottomPanel, BorderLayout.SOUTH);
        updateScopeControlState();
        return panel;
    }

    // ========================================================================
    // OAuth2 Tab (wrapped with collection binding)
    // ========================================================================
    private JPanel createOAuth2Tab() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));

        JPanel bindPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        oauth2CollectionCombo = new JComboBox<>();
        oauth2CollectionCombo.setPrototypeDisplayValue(new CollectionRef(null, "Select collection..."));
        oauth2HintLabel = new JLabel("Select a collection to bind OAuth2 settings.");
        oauth2HintLabel.setForeground(Color.GRAY);
        oauth2AutosaveStatusLabel = new JLabel("Autosave idle.");
        oauth2AutosaveStatusLabel.setForeground(Color.GRAY);
        bindPanel.add(new JLabel("Target:"));
        bindPanel.add(oauth2CollectionCombo);
        bindOAuth2Btn = new JButton("Save Now");
        bindOAuth2Btn.addActionListener(e -> {
            CollectionRef ref = (CollectionRef) oauth2CollectionCombo.getSelectedItem();
            if (ref == null) {
                appendImportLog("OAuth2: No collection selected for binding.");
                setOAuth2AutosaveStatus("Select a collection to autosave OAuth2 settings.", Color.GRAY);
                return;
            }
            ApiCollection col = ref.collection;
            Map<String, String> vars = oauth2Panel.getVariables();
            col.replaceRuntimeOAuth2(vars);
            appendImportLog("OAuth2 bound to \"" + ref.label + "\": " + vars.size() + " var(s).");
            setOAuth2AutosaveStatus("Saved to " + ref.label + " (" + vars.size() + " var(s)).", new Color(0, 128, 0));
        });
        bindPanel.add(bindOAuth2Btn);
        JButton bindAllBtn = new JButton("Bind OAuth2 to All");
        bindAllBtn.addActionListener(e -> {
            if (loadedCollections.isEmpty()) {
                appendImportLog("OAuth2: No collections loaded.");
                return;
            }
            int confirm = JOptionPane.showConfirmDialog(mainPanel,
                "This will overwrite OAuth2 settings in ALL " + loadedCollections.size() + " collection(s). Continue?",
                "Confirm Apply to All Collections", JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
            Map<String, String> vars = oauth2Panel.getVariables();
            for (ApiCollection col : loadedCollections) {
                col.replaceRuntimeOAuth2(vars);
            }
            appendImportLog("OAuth2 bound to all " + loadedCollections.size() + " collection(s).");
            setOAuth2AutosaveStatus("Saved to all " + loadedCollections.size() + " collection(s).", new Color(0, 128, 0));
        });
        bindPanel.add(bindAllBtn);
        panel.add(bindPanel, BorderLayout.NORTH);
        panel.add(oauth2Panel, BorderLayout.CENTER);
        JPanel oauth2StatusPanel = new JPanel(new GridLayout(2, 1));
        oauth2StatusPanel.add(oauth2HintLabel);
        oauth2StatusPanel.add(oauth2AutosaveStatusLabel);
        panel.add(oauth2StatusPanel, BorderLayout.SOUTH);

        // Keep combo in sync with loaded collections
        tabbedPane.addChangeListener(e -> {
            if (tabbedPane.getSelectedIndex() == 2) { // OAuth2 tab
                CollectionRef prev = oauth2CollectionCombo.getSelectedItem() != null ? (CollectionRef) oauth2CollectionCombo.getSelectedItem() : null;
                oauth2CollectionCombo.removeAllItems();
                List<CollectionRef> refs = buildCollectionRefs();
                for (CollectionRef ref : refs) {
                    oauth2CollectionCombo.addItem(ref);
                }
                if (prev != null) {
                    for (int i = 0; i < oauth2CollectionCombo.getItemCount(); i++) {
                        if (prev.collection == oauth2CollectionCombo.getItemAt(i).collection) {
                            oauth2CollectionCombo.setSelectedIndex(i);
                            break;
                        }
                    }
                }
                updateScopeControlState();
            }
        });
        oauth2CollectionCombo.addActionListener(e -> {
            CollectionRef ref = (CollectionRef) oauth2CollectionCombo.getSelectedItem();
            if (ref != null && ref.collection != null) {
                refreshOAuth2PanelForCollection(ref.collection);
                setOAuth2AutosaveStatus("Autosave idle.", Color.GRAY);
            } else {
                setOAuth2AutosaveStatus("Select a collection to autosave OAuth2 settings.", Color.GRAY);
            }
            applyAutoRefreshUiForSelectedCollection();
            updateScopeControlState();
        });
        oauth2Panel.setVariablesChangeListener((vars, replaceMode) -> {
            CollectionRef ref = (CollectionRef) oauth2CollectionCombo.getSelectedItem();
            if (ref == null || ref.collection == null) {
                setOAuth2AutosaveStatus("Select a collection to autosave OAuth2 settings.", Color.GRAY);
                return;
            }
            if (replaceMode) {
                ref.collection.replaceRuntimeOAuth2(vars);
            } else {
                ref.collection.putAllRuntimeOAuth2(vars);
            }
            setOAuth2AutosaveStatus("Autosaved to " + ref.label + " (" + vars.size() + " var(s)).", new Color(0, 128, 0));
        });
        oauth2Panel.setAutoRefreshToggleListener(this::toggleAutoRefreshForSelectedCollection);
        oauth2Panel.setAutoRefreshIntervalListener(seconds -> {
            ApiCollection col = getSelectedOAuth2Collection();
            if (col != null) {
                getAutoState(col).intervalSeconds = Math.max(30, seconds);
            }
        });
        applyAutoRefreshUiForSelectedCollection();
        updateScopeControlState();

        return panel;
    }

    // ========================================================================
    // Runner Tab
    // ========================================================================
    private JPanel createRunnerTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));

        JPanel configPanel = new JPanel(new GridBagLayout());
        configPanel.setBorder(BorderFactory.createTitledBorder("Runner Configuration"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0; gbc.gridy = 0;
        configPanel.add(new JLabel("Delay (ms):"), gbc);
        gbc.gridx = 1;
        runnerDelaySpinner = new JSpinner(new SpinnerNumberModel(200, 0, 5000, 50));
        configPanel.add(runnerDelaySpinner, gbc);

        gbc.gridx = 2;
        configPanel.add(new JLabel("Retries:"), gbc);

        gbc.gridx = 3;
        runnerRetriesSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 20, 1));
        configPanel.add(runnerRetriesSpinner, gbc);

        gbc.gridx = 4;
        stopOnErrorBox = new JCheckBox("Stop on error");
        configPanel.add(stopOnErrorBox, gbc);

        gbc.gridx = 5;
        stopOnAssertionFailureBox = new JCheckBox("Stop on assertion failure");
        configPanel.add(stopOnAssertionFailureBox, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        stopOnStatusAtLeast400Box = new JCheckBox("Stop on status >= 400");
        configPanel.add(stopOnStatusAtLeast400Box, gbc);

        gbc.gridx = 1;
        stopOnMissingVariableBox = new JCheckBox("Stop when variable missing");
        configPanel.add(stopOnMissingVariableBox, gbc);

        gbc.gridx = 2;
        configPanel.add(new JLabel("Stop after failures:"), gbc);

        gbc.gridx = 3;
        stopAfterFailuresSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100, 1));
        configPanel.add(stopAfterFailuresSpinner, gbc);

        gbc.gridx = 4;
        followRedirectsBox = new JCheckBox("Follow redirects", true);
        configPanel.add(followRedirectsBox, gbc);

        gbc.gridx = 5;
        runnerDebugRawRequestBox = new JCheckBox("Debug final raw request");
        configPanel.add(runnerDebugRawRequestBox, gbc);

        panel.add(configPanel, BorderLayout.NORTH);

        resultModel = new RunnerResultTableModel();
        resultTable = new JTable(resultModel);
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane tableScroll = new JScrollPane(resultTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Runner Results"));
        tableScroll.setPreferredSize(new Dimension(350, 250));
        tableScroll.setMinimumSize(new Dimension(200, 150));

        timelineModel = new RunnerTimelineTableModel();
        timelineTable = new JTable(timelineModel);
        timelineTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        timelineTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane timelineScroll = new JScrollPane(timelineTable);
        timelineScroll.setBorder(BorderFactory.createTitledBorder("Runner Timeline"));
        timelineScroll.setPreferredSize(new Dimension(350, 180));
        timelineScroll.setMinimumSize(new Dimension(200, 120));

        runnerDetailTabs = new JTabbedPane();
        detailRequestEditor = importer.getApi().userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        runnerDetailTabs.addTab("Request", detailRequestEditor.uiComponent());

        detailResponseEditor = importer.getApi().userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        runnerDetailTabs.addTab("Response", detailResponseEditor.uiComponent());

        detailVarsText = new JTextArea();
        detailVarsText.setEditable(false);
        detailVarsText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        runnerDetailTabs.addTab("Vars", new JScrollPane(detailVarsText));

        JSplitPane resultsSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, timelineScroll);
        resultsSplit.setResizeWeight(0.70);
        resultsSplit.setOneTouchExpandable(true);
        resultsSplit.setContinuousLayout(true);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, resultsSplit, runnerDetailTabs);
        splitPane.setResizeWeight(0.5);
        splitPane.setOneTouchExpandable(true);
        splitPane.setContinuousLayout(true);
        panel.add(splitPane, BorderLayout.CENTER);

        resultTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && resultTable.getSelectedRow() >= 0) {
                RunnerResult r = resultModel.getResultAt(resultTable.getSelectedRow());
                updateRunnerDetailPane(r);
            }
        });

        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));

        JPanel actionRow = new JPanel(new BorderLayout(10, 0));
        actionRow.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));

        runnerProgress = new JProgressBar(0, 100);
        runnerProgress.setStringPainted(true);
        runnerProgress.setPreferredSize(new Dimension(180, 20));
        actionRow.add(runnerProgress, BorderLayout.WEST);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton clearBtn = new JButton("Clear Results");
        clearBtn.addActionListener(e -> {
            resultModel.clear();
            timelineModel.clear();
            runnerLog.setText("");
        });
        pauseRunnerBtn = new JButton("Pause");
        pauseRunnerBtn.setEnabled(false);
        pauseRunnerBtn.addActionListener(e -> pauseRunnerFromUi());
        resumeRunnerBtn = new JButton("Resume");
        resumeRunnerBtn.setEnabled(false);
        resumeRunnerBtn.addActionListener(e -> resumeRunnerFromUi());
        stepRunnerBtn = new JButton("Step");
        stepRunnerBtn.setEnabled(false);
        stepRunnerBtn.addActionListener(e -> stepRunnerFromUi());
        startRunnerBtn = new JButton("Start Collection Runner");
        startRunnerBtn.setEnabled(false);
        startRunnerBtn.addActionListener(e -> startRunner(true));
        cancelRunnerBtn = new JButton("Cancel");
        cancelRunnerBtn.setEnabled(false);
        cancelRunnerBtn.addActionListener(e -> cancelRunnerFromUi());
        btnPanel.add(clearBtn);
        btnPanel.add(pauseRunnerBtn);
        btnPanel.add(resumeRunnerBtn);
        btnPanel.add(stepRunnerBtn);
        btnPanel.add(startRunnerBtn);
        btnPanel.add(cancelRunnerBtn);
        actionRow.add(btnPanel, BorderLayout.EAST);
        bottomPanel.add(actionRow);

        runnerLog = new JTextArea(3, 50);
        runnerLog.setEditable(false);
        runnerLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(runnerLog);
        logScroll.setBorder(BorderFactory.createTitledBorder("Runner Log"));
        logScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        logScroll.setPreferredSize(new Dimension(400, 70));
        bottomPanel.add(logScroll);

        panel.add(bottomPanel, BorderLayout.SOUTH);
        return panel;
    }

    // ========================================================================
    // Tree Helpers
    // ========================================================================
    private class TreeMouseListener extends MouseAdapter {
        @Override
        public void mouseClicked(MouseEvent e) {
            TreePath path = requestTree.getPathForLocation(e.getX(), e.getY());
            if (path == null) return;
            Object node = path.getLastPathComponent();
            if (SwingUtilities.isRightMouseButton(e) && node instanceof CollectionTreeNode) {
                requestTree.setSelectionPath(path);
                showTreeContextMenu(e, (CollectionTreeNode) node);
            }
        }
    }

    private void showTreeContextMenu(MouseEvent e, CollectionTreeNode node) {
        if (node == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem authItem = new JMenuItem("Auth Settings...");
        authItem.addActionListener(ev -> editAuthForNode(node));
        menu.add(authItem);
        menu.show(requestTree, e.getX(), e.getY());
    }

    private void editAuthForNode(CollectionTreeNode node) {
        ApiCollection collection = findCollectionForNode(node);
        if (collection == null) {
            return;
        }

        boolean allowInherit = node.getNodeType() != CollectionTreeNode.Type.COLLECTION;
        String initialMode = determineInitialAuthMode(node, collection);
        ApiRequest.Auth initialAuth = determineInitialAuth(node, collection, initialMode);
        AuthSettingsDialog.Result result = AuthSettingsDialog.showDialog(
                mainPanel,
                "Auth Settings - " + describeAuthScope(node),
                allowInherit,
                initialMode,
                initialAuth
        );
        if (result == null) {
            return;
        }

        if (node.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
            burp.utils.AuthInheritanceResolver.setCollectionAuth(collection, result.auth);
        } else if (node.getNodeType() == CollectionTreeNode.Type.FOLDER) {
            burp.utils.AuthInheritanceResolver.setFolderAuth(collection, node.folderPath, result.mode, result.auth);
        } else if (node.getNodeType() == CollectionTreeNode.Type.REQUEST && node.request != null) {
            node.request.authOverrideMode = result.mode;
            node.request.explicitAuth = burp.utils.AuthInheritanceResolver.copyAuth(result.auth);
            burp.utils.AuthInheritanceResolver.resolveRequestAuth(collection, node.request);
        }

        refreshCollectionCombos();
        requestTree.repaint();
        if (requestEditor != null && requestEditor.getCurrentCollection() == collection && requestEditor.getCurrentRequest() != null) {
            requestEditor.setCurrentCollection(collection);
            syncRequestEditorRuntimeContext(requestEditor.getCurrentRequest(), collection);
            requestEditor.loadRequest(requestEditor.getCurrentRequest());
        }
        notifyWorkspaceChanged();
    }

    private String describeAuthScope(CollectionTreeNode node) {
        if (node == null) {
            return "Auth";
        }
        return switch (node.getNodeType()) {
            case COLLECTION -> "Collection";
            case FOLDER -> "Folder " + (node.folderPath != null ? node.folderPath : "");
            case REQUEST -> "Request " + (node.request != null ? node.request.name : "");
        };
    }

    private String determineInitialAuthMode(CollectionTreeNode node, ApiCollection collection) {
        if (node == null) {
            return "inherit";
        }
        if (node.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
            if (collection == null || collection.auth == null || collection.auth.type == null) {
                return "none";
            }
            return "none".equalsIgnoreCase(collection.auth.type)
                    ? "none"
                    : collection.auth.type.toLowerCase(Locale.ROOT);
        }
        if (node.getNodeType() == CollectionTreeNode.Type.FOLDER) {
            String normalized = burp.utils.AuthInheritanceResolver.normalizeFolderPath(node.folderPath);
            String mode = collection != null && collection.folderAuthModes != null ? collection.folderAuthModes.get(normalized) : null;
            if (mode == null) {
                return "inherit";
            }
            if ("explicit".equalsIgnoreCase(mode) || "none".equalsIgnoreCase(mode) || "inherit".equalsIgnoreCase(mode)) {
                if ("explicit".equalsIgnoreCase(mode) && collection.folderAuth != null && collection.folderAuth.get(normalized) != null
                        && collection.folderAuth.get(normalized).type != null) {
                    return collection.folderAuth.get(normalized).type.toLowerCase(Locale.ROOT);
                }
                return mode.toLowerCase(Locale.ROOT);
            }
            return "inherit";
        }
        if (node.request == null) {
            return "inherit";
        }
        String mode = node.request.authOverrideMode != null ? node.request.authOverrideMode.trim().toLowerCase(Locale.ROOT) : "";
        if ("inherit".equals(mode)) {
            return "inherit";
        }
        if ("none".equals(mode)) {
            return "none";
        }
        if ("explicit".equals(mode)) {
            if (node.request.explicitAuth != null && node.request.explicitAuth.type != null) {
                return node.request.explicitAuth.type.toLowerCase(Locale.ROOT);
            }
            if (node.request.auth != null && node.request.auth.type != null) {
                return node.request.auth.type.toLowerCase(Locale.ROOT);
            }
            return "none";
        }
        if (node.request.explicitAuth != null && node.request.explicitAuth.type != null) {
            return node.request.explicitAuth.type.toLowerCase(Locale.ROOT);
        }
        if (node.request.auth != null && node.request.auth.type != null) {
            if ("none".equalsIgnoreCase(node.request.auth.type)) {
                return node.request.authExplicitlyDisabled ? "none" : "inherit";
            }
            return node.request.auth.type.toLowerCase(Locale.ROOT);
        }
        return "inherit";
    }

    private ApiRequest.Auth determineInitialAuth(CollectionTreeNode node, ApiCollection collection, String initialMode) {
        if (node == null) {
            return null;
        }
        if (node.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
            return collection != null ? burp.utils.AuthInheritanceResolver.copyAuth(collection.auth) : null;
        }
        if (node.getNodeType() == CollectionTreeNode.Type.FOLDER) {
            String normalized = burp.utils.AuthInheritanceResolver.normalizeFolderPath(node.folderPath);
            if (collection != null && collection.folderAuth != null) {
                return burp.utils.AuthInheritanceResolver.copyAuth(collection.folderAuth.get(normalized));
            }
            return null;
        }
        if (node.request == null) {
            return null;
        }
        if ("inherit".equals(initialMode)) {
            return burp.utils.AuthInheritanceResolver.copyAuth(node.request.auth);
        }
        if ("none".equals(initialMode)) {
            return burp.utils.AuthInheritanceResolver.copyAuth(node.request.explicitAuth != null ? node.request.explicitAuth : node.request.auth);
        }
        return burp.utils.AuthInheritanceResolver.copyAuth(node.request.explicitAuth != null ? node.request.explicitAuth : node.request.auth);
    }

    private void rebuildTree() {
        rebuildTree(pendingWorkspaceRequestTreePaths, Collections.emptyList());
    }

    private void rebuildTree(Map<String, String> requestTreePaths) {
        rebuildTree(requestTreePaths, Collections.emptyList());
    }

    private void rebuildTree(Map<String, String> requestTreePaths, List<String> expandedTreePathKeys) {
        loadRequestTreeModel(requestTreePaths);
        if (expandedTreePathKeys == null || expandedTreePathKeys.isEmpty()) {
            for (int i = 0; i < requestTree.getRowCount(); i++) {
                requestTree.expandRow(i);
            }
        } else {
            restoreExpandedTreePathKeys(expandedTreePathKeys);
        }
        refreshTreePresentation(requestTree);
    }

    private void loadRequestTreeModel(Map<String, String> requestTreePaths) {
        requestToCollectionMap.clear();
        DefaultMutableTreeNode root = buildRequestTreeRoot(loadedCollections, requestTreePaths, requestToCollectionMap);
        treeModel.setRoot(root);
    }

    static DefaultMutableTreeNode buildRequestTreeRoot(List<ApiCollection> collections, Map<String, String> requestTreePaths) {
        return buildRequestTreeRoot(collections, requestTreePaths, null);
    }

    static DefaultMutableTreeNode buildRequestTreeRoot(List<ApiCollection> collections,
                                                       Map<String, String> requestTreePaths,
                                                       Map<ApiRequest, ApiCollection> requestToCollectionMap) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Collections");
        if (collections == null) {
            return root;
        }
        for (int collectionIndex = 0; collectionIndex < collections.size(); collectionIndex++) {
            ApiCollection col = collections.get(collectionIndex);
            if (col == null) {
                continue;
            }
            CollectionTreeNode colNode = new CollectionTreeNode(col);
            root.add(colNode);
            if (col.requests == null) {
                continue;
            }
            for (int requestIndex = 0; requestIndex < col.requests.size(); requestIndex++) {
                ApiRequest req = col.requests.get(requestIndex);
                if (req == null) {
                    continue;
                }
                if (requestToCollectionMap != null) {
                    requestToCollectionMap.put(req, col);
                }
                String path = lookupWorkspaceRequestTreeFolderPath(requestTreePaths, collectionIndex, col, req, requestIndex);
                if (path == null) {
                    path = req.path != null ? req.path : "";
                } else if (path.isBlank() && isNestedRequestPath(req.path, req.name)) {
                    path = req.path;
                }
                String[] parts = path.split("/");
                java.util.List<String> segments = new java.util.ArrayList<>();
                for (String p : parts) {
                    if (!p.isEmpty()) {
                        segments.add(p);
                    }
                }
                boolean lastIsRequestName = !segments.isEmpty() && segments.get(segments.size() - 1).equals(req.name);
                int folderCount = lastIsRequestName ? segments.size() - 1 : segments.size();

                CollectionTreeNode parent = colNode;
                StringBuilder cumulative = new StringBuilder();
                for (int i = 0; i < folderCount; i++) {
                    if (cumulative.length() > 0) {
                        cumulative.append("/");
                    }
                    cumulative.append(segments.get(i));
                    parent = getOrCreateFolderNode(parent, cumulative.toString());
                }
                parent.add(new CollectionTreeNode(req));
            }
        }
        return root;
    }

    static String lookupWorkspaceRequestTreeFolderPath(Map<String, String> requestTreePaths,
                                                       ApiCollection collection,
                                                       ApiRequest request,
                                                       int requestIndex) {
        return lookupWorkspaceRequestTreeFolderPath(requestTreePaths, -1, collection, request, requestIndex);
    }

    static String lookupWorkspaceRequestTreeFolderPath(Map<String, String> requestTreePaths,
                                                       int collectionIndex,
                                                       ApiCollection collection,
                                                       ApiRequest request,
                                                       int requestIndex) {
        if (requestTreePaths == null || request == null) {
            return null;
        }
        String collectionName = collection != null ? collection.name : request.sourceCollection;
        String path = lookupWorkspaceRequestTreePathFamily(
                requestTreePaths,
                workspaceRequestTreePathKey(collectionName, collectionIndex, request, requestIndex),
                requestIndex
        );
        if (path != null) {
            return path;
        }
        path = lookupWorkspaceRequestTreePathFamily(
                requestTreePaths,
                workspaceRequestTreePathKeyLegacy(collectionName, collectionIndex, request, requestIndex),
                requestIndex
        );
        if (path != null) {
            return path;
        }
        path = lookupWorkspaceRequestTreePathFamily(
                requestTreePaths,
                workspaceRequestIdentityKey(collectionName, request, requestIndex),
                requestIndex
        );
        if (path != null) {
            return path;
        }
        path = lookupWorkspaceRequestTreePathFamily(
                requestTreePaths,
                workspaceRequestIdentityKey(collectionName, request),
                requestIndex
        );
        if (path != null) {
            return path;
        }
        return lookupWorkspaceRequestTreePathFamily(
                requestTreePaths,
                workspaceRequestKey(collectionName, request),
                requestIndex
        );
    }

    private static String lookupWorkspaceRequestTreePathFamily(Map<String, String> requestTreePaths,
                                                               String baseKey,
                                                               int requestIndex) {
        if (requestTreePaths == null || baseKey == null || baseKey.isBlank()) {
            return null;
        }
        String normalizedBaseKey = normalizeWorkspaceStateKeyDelimiters(baseKey);
        List<DuplicateTreePathCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, String> entry : requestTreePaths.entrySet()) {
            String key = normalizeWorkspaceStateKeyDelimiters(entry.getKey());
            if (key == null || key.isBlank()) {
                continue;
            }
            if (normalizedBaseKey.equals(key)) {
                candidates.add(new DuplicateTreePathCandidate(1, key, entry.getValue()));
                continue;
            }
            String prefix = normalizedBaseKey + WORKSPACE_KEY_DELIMITER + "duplicate=";
            if (key.startsWith(prefix)) {
                int ordinal = parseDuplicateOrdinal(key.substring(prefix.length()));
                if (ordinal < 0) {
                    continue;
                }
                candidates.add(new DuplicateTreePathCandidate(ordinal, key, entry.getValue()));
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingInt(DuplicateTreePathCandidate::ordinal)
                .thenComparing(DuplicateTreePathCandidate::key));
        int candidateIndex = requestIndex >= 0 ? Math.min(requestIndex, candidates.size() - 1) : 0;
        return candidates.get(candidateIndex).value();
    }

    private static int parseDuplicateOrdinal(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String workspaceRequestTreePathKeyLegacy(String collectionName,
                                                            int collectionIndex,
                                                            ApiRequest request,
                                                            int requestIndex) {
        return "collectionIndex=" + collectionIndex
                + WORKSPACE_KEY_DELIMITER + workspaceRequestIdentityKey(collectionName, request, requestIndex);
    }

    static String normalizeWorkspaceStateKeyDelimiters(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        return key
                .replace(WORKSPACE_KEY_DELIMITER_ESCAPED_UPPER, String.valueOf(WORKSPACE_KEY_DELIMITER))
                .replace(WORKSPACE_KEY_DELIMITER_ESCAPED_LOWER, String.valueOf(WORKSPACE_KEY_DELIMITER));
    }

    static Map<String, String> normalizeWorkspaceRequestTreePaths(Map<String, String> requestTreePaths) {
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        if (requestTreePaths == null || requestTreePaths.isEmpty()) {
            return normalized;
        }
        for (Map.Entry<String, String> entry : requestTreePaths.entrySet()) {
            String key = normalizeWorkspaceStateKeyDelimiters(entry.getKey());
            if (key == null || key.isBlank()) {
                continue;
            }
            String resolvedKey = key;
            if (normalized.containsKey(resolvedKey)) {
                int duplicateOrdinal = 2;
                String duplicateKey = resolvedKey + WORKSPACE_KEY_DELIMITER + "duplicate=" + duplicateOrdinal;
                while (normalized.containsKey(duplicateKey)) {
                    duplicateOrdinal++;
                    duplicateKey = resolvedKey + WORKSPACE_KEY_DELIMITER + "duplicate=" + duplicateOrdinal;
                }
                resolvedKey = duplicateKey;
            }
            normalized.put(resolvedKey, entry.getValue());
        }
        return normalized;
    }

    /**
     * Legacy snapshots may have saved duplicate request tree entries as baseKey + "\u001Fduplicate=N".
     * The base key is ordinal 1 and duplicate suffixes are ordinals N, then the family is sorted
     * deterministically so restore does not depend on map iteration order.
     */
    private static final class DuplicateTreePathCandidate {
        private final int ordinal;
        private final String key;
        private final String value;

        private DuplicateTreePathCandidate(int ordinal, String key, String value) {
            this.ordinal = ordinal;
            this.key = key;
            this.value = value;
        }

        private int ordinal() {
            return ordinal;
        }

        private String key() {
            return key;
        }

        private String value() {
            return value;
        }
    }

    private static CollectionTreeNode getOrCreateFolderNode(CollectionTreeNode parent, String cumulativePath) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Object child = parent.getChildAt(i);
            if (child instanceof CollectionTreeNode) {
                CollectionTreeNode ctn = (CollectionTreeNode) child;
                if (ctn.getNodeType() == CollectionTreeNode.Type.FOLDER && cumulativePath.equals(ctn.folderPath)) {
                    return ctn;
                }
            }
        }
        CollectionTreeNode folder = new CollectionTreeNode(cumulativePath);
        parent.add(folder);
        return folder;
    }

    private List<ApiRequest> getCheckedRequestsFromTree() {
        return collectCheckedRequests((DefaultMutableTreeNode) treeModel.getRoot());
    }

    private List<ApiCollection> getCheckedCollectionsFromTree() {
        return new ArrayList<>(collectCheckedCollections((DefaultMutableTreeNode) treeModel.getRoot()));
    }

    private List<ApiRequest> collectCheckedRequests(DefaultMutableTreeNode root) {
        List<ApiRequest> selected = new ArrayList<>();
        enumerateCheckedRequests(root, selected);
        return selected;
    }

    private Set<ApiCollection> collectCheckedCollections(DefaultMutableTreeNode root) {
        LinkedHashSet<ApiCollection> checked = new LinkedHashSet<>();
        enumerateCheckedCollections(root, checked);
        return checked;
    }

    private void enumerateCheckedRequests(DefaultMutableTreeNode node, List<ApiRequest> out) {
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST && ctn.isChecked() && ctn.request != null) {
                out.add(ctn.request);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            enumerateCheckedRequests((DefaultMutableTreeNode) node.getChildAt(i), out);
        }
    }

    private void enumerateCheckedCollections(DefaultMutableTreeNode node, Set<ApiCollection> out) {
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION && ctn.isChecked() && ctn.collection != null) {
                out.add(ctn.collection);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            enumerateCheckedCollections((DefaultMutableTreeNode) node.getChildAt(i), out);
        }
    }

    private Set<ApiCollection> resolveCheckedRequestCollections(List<ApiRequest> checkedRequests) {
        LinkedHashSet<ApiCollection> affected = new LinkedHashSet<>();
        if (checkedRequests == null || checkedRequests.isEmpty()) {
            return affected;
        }
        for (ApiRequest request : checkedRequests) {
            if (request == null) {
                continue;
            }
            ApiCollection collection = requestToCollectionMap.get(request);
            if (collection == null) {
                collection = findCollectionByName(request.sourceCollection);
            }
            if (collection != null) {
                affected.add(collection);
            }
        }
        return affected;
    }

    private DefaultMutableTreeNode cloneRequestTreeRootForSelection() {
        if (treeModel == null || treeModel.getRoot() == null) {
            return new DefaultMutableTreeNode("Collections");
        }
        return cloneTreeNodeForSelection((DefaultMutableTreeNode) treeModel.getRoot());
    }

    private DefaultMutableTreeNode cloneTreeNodeForSelection(DefaultMutableTreeNode node) {
        DefaultMutableTreeNode copy;
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode source = (CollectionTreeNode) node;
            if (source.getNodeType() == CollectionTreeNode.Type.COLLECTION && source.collection != null) {
                copy = new CollectionTreeNode(source.collection);
            } else if (source.getNodeType() == CollectionTreeNode.Type.FOLDER && source.folderPath != null) {
                copy = new CollectionTreeNode(source.folderPath);
            } else if (source.getNodeType() == CollectionTreeNode.Type.REQUEST && source.request != null) {
                copy = new CollectionTreeNode(source.request);
            } else {
                copy = new DefaultMutableTreeNode(source.getUserObject(), source.getAllowsChildren());
            }
        } else {
            copy = new DefaultMutableTreeNode(node.getUserObject(), node.getAllowsChildren());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            copy.add(cloneTreeNodeForSelection((DefaultMutableTreeNode) node.getChildAt(i)));
        }
        return copy;
    }

    private JTree buildPopupSelectionTree(DefaultMutableTreeNode selectionRoot, JLabel selectedCountLabel) {
        DefaultTreeModel model = new DefaultTreeModel(selectionRoot);
        JTree tree = new JTree(model);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new BurpLikeTreeCellRenderer(true));
        tree.setRowHeight(20);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) {
                    return;
                }
                Rectangle bounds = tree.getPathBounds(path);
                if (bounds == null || e.getX() >= bounds.x + 20) {
                    return;
                }
                Object node = path.getLastPathComponent();
                if (!(node instanceof CollectionTreeNode)) {
                    return;
                }
                CollectionTreeNode ctn = (CollectionTreeNode) node;
                ctn.propagateCheck(!ctn.isChecked());
                TreeNode parent = ctn.getParent();
                if (parent instanceof CollectionTreeNode) {
                    ((CollectionTreeNode) parent).updateParentCheckState();
                }
                tree.repaint();
                if (selectedCountLabel != null) {
                    selectedCountLabel.setText(collectCheckedRequests((DefaultMutableTreeNode) tree.getModel().getRoot()).size() + " requests selected");
                }
            }
        });
        expandAllTreeRows(tree);
        refreshTreePresentation(tree);
        scheduleTreeInitializationAfterShowing(tree, () -> {
            if (tree.getModel() instanceof DefaultTreeModel) {
                ((DefaultTreeModel) tree.getModel()).reload();
            }
            expandAllTreeRows(tree);
            refreshTreePresentation(tree);
        });
        if (selectedCountLabel != null) {
            selectedCountLabel.setText("0 requests selected");
        }
        return tree;
    }

    private void expandAllTreeRows(JTree tree) {
        if (tree == null) {
            return;
        }
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    private void refreshTreePresentation(JTree tree) {
        if (tree == null) {
            return;
        }
        tree.treeDidChange();
        tree.revalidate();
        tree.repaint();
    }

    private void configureMainTreeUi(JTree tree) {
        if (tree == null) {
            return;
        }
        if (!(tree.getUI() instanceof BasicTreeUI)) {
            return;
        }
        BasicTreeUI treeUi = (BasicTreeUI) tree.getUI();
        if (treeUi.getLeftChildIndent() < MAIN_TREE_MIN_LEFT_CHILD_INDENT) {
            treeUi.setLeftChildIndent(MAIN_TREE_MIN_LEFT_CHILD_INDENT);
        }
        if (treeUi.getRightChildIndent() < MAIN_TREE_MIN_RIGHT_CHILD_INDENT) {
            treeUi.setRightChildIndent(MAIN_TREE_MIN_RIGHT_CHILD_INDENT);
        }
    }

    private final class MainRequestTree extends JTree {
        private MainRequestTree(TreeModel model) {
            super(model);
        }

        @Override
        public void setUI(TreeUI ui) {
            super.setUI(ui);
            configureMainTreeUi(this);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            configureMainTreeUi(this);
        }
    }

    private void resetRequestTreeHorizontalViewport() {
        if (requestTreeScrollPane == null) {
            return;
        }
        JViewport viewport = requestTreeScrollPane.getViewport();
        if (viewport == null) {
            return;
        }
        Point viewPosition = viewport.getViewPosition();
        if (viewPosition == null || viewPosition.x == 0) {
            return;
        }
        viewport.setViewPosition(new Point(0, Math.max(0, viewPosition.y)));
    }

    private void scheduleRequestTreeHorizontalViewportReset() {
        SwingUtilities.invokeLater(this::resetRequestTreeHorizontalViewport);
    }

    private void scheduleTreeInitializationAfterShowing(JTree tree, Runnable initializer) {
        if (tree == null) {
            return;
        }
        if (initializer == null) {
            return;
        }
        if (tree.isShowing()) {
            SwingUtilities.invokeLater(initializer);
            return;
        }
        Runnable pendingInitializer = initializer;
        Object existingInitializer = tree.getClientProperty(TREE_SHOW_INITIALIZER_KEY);
        if (existingInitializer instanceof Runnable) {
            Runnable priorInitializer = (Runnable) existingInitializer;
            pendingInitializer = () -> {
                priorInitializer.run();
                initializer.run();
            };
        }
        tree.putClientProperty(TREE_SHOW_INITIALIZER_KEY, pendingInitializer);

        Object listener = tree.getClientProperty(TREE_SHOW_INITIALIZER_LISTENER_KEY);
        if (listener instanceof HierarchyListener) {
            return;
        }
        HierarchyListener hierarchyListener = new HierarchyListener() {
            @Override
            public void hierarchyChanged(HierarchyEvent e) {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0 || !tree.isShowing()) {
                    return;
                }
                clearTreeShowInitializer(tree);
                Runnable shownInitializer = (Runnable) tree.getClientProperty(TREE_SHOW_INITIALIZER_KEY);
                tree.putClientProperty(TREE_SHOW_INITIALIZER_KEY, null);
                if (shownInitializer != null) {
                    SwingUtilities.invokeLater(shownInitializer);
                }
            }
        };
        tree.putClientProperty(TREE_SHOW_INITIALIZER_LISTENER_KEY, hierarchyListener);
        tree.addHierarchyListener(hierarchyListener);
    }

    private void clearTreeShowInitializer(JTree tree) {
        if (tree == null) {
            return;
        }
        Object listener = tree.getClientProperty(TREE_SHOW_INITIALIZER_LISTENER_KEY);
        if (listener instanceof HierarchyListener) {
            tree.removeHierarchyListener((HierarchyListener) listener);
            tree.putClientProperty(TREE_SHOW_INITIALIZER_LISTENER_KEY, null);
        }
    }

    private ApiCollection findCollectionForNode(CollectionTreeNode node) {
        if (node == null) return null;
        if (node.getNodeType() == CollectionTreeNode.Type.COLLECTION) return node.collection;
        TreeNode parent = node.getParent();
        while (parent instanceof CollectionTreeNode) {
            if (((CollectionTreeNode) parent).getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                return ((CollectionTreeNode) parent).collection;
            }
            parent = parent.getParent();
        }
        return null;
    }



    /**
     * Wrapper for combo items that binds by object identity instead of name,
     * eliminating ambiguity when duplicate collection names exist.
     */
    private static class CollectionRef {
        final ApiCollection collection;
        final String label;
        CollectionRef(ApiCollection collection, String label) {
            this.collection = collection;
            this.label = label;
        }
        @Override public String toString() { return label; }
    }

    private List<CollectionRef> buildCollectionRefs() {
        Map<String, Integer> nameCounts = new HashMap<>();
        for (ApiCollection c : loadedCollections) {
            nameCounts.merge(c.name, 1, Integer::sum);
        }
        Map<String, Integer> seen = new HashMap<>();
        List<CollectionRef> refs = new ArrayList<>();
        for (ApiCollection c : loadedCollections) {
            String label = c.name;
            if (nameCounts.get(c.name) > 1) {
                int count = seen.merge(c.name, 1, Integer::sum);
                label = c.name + " (#" + count + ")";
            }
            refs.add(new CollectionRef(c, label));
        }
        return refs;
    }

    private UnresolvedVariablesDialog.Action showUnresolvedVariablesDialog(List<UnresolvedVariableIssue> issues,
                                                                           List<ApiCollection> targetCollections) {
        if (issues == null || issues.isEmpty()) {
            return UnresolvedVariablesDialog.Action.CONTINUE_WITHOUT_APPLYING;
        }
        Window owner = SwingUtilities.getWindowAncestor(mainPanel);
        UnresolvedVariablesDialog dialog = new UnresolvedVariablesDialog(owner, issues, targetCollections);
        return dialog.showDialog();
    }

    private void handleOAuth2TokenAcquired(TokenStore.TokenEntry entry,
                                           ApiCollection collection,
                                           Map<String, String> oauth2Vars) {
        if (entry == null || entry.accessToken == null || entry.accessToken.isBlank()) {
            return;
        }

        if (collection == null) {
            appendImportLog("OAuth2 acquire completed but no target collection was captured.");
            return;
        }

        applyAcquiredOAuth2Runtime(collection, entry, oauth2Vars);
        List<BearerTokenAliasCandidate> candidates = OAuth2BearerAliasDetector.detect(collection, entry.accessToken);
        if (candidates.isEmpty()) {
            refreshRuntimeViewsForCollection(collection);
            setOAuth2AutosaveStatus("Token values saved to " + collection.name + ".", new Color(0, 128, 0));
            return;
        }

        Window owner = SwingUtilities.getWindowAncestor(mainPanel);
        BearerTokenAliasDialog dialog = new BearerTokenAliasDialog(owner, candidates);
        if (dialog.showDialog() != BearerTokenAliasDialog.Action.BIND_SELECTED) {
            refreshRuntimeViewsForCollection(collection);
            setOAuth2AutosaveStatus("Token values saved to " + collection.name + ".", new Color(0, 128, 0));
            return;
        }

        Set<String> selectedAliases = dialog.getSelectedAliases();
        if (selectedAliases.isEmpty()) {
            refreshRuntimeViewsForCollection(collection);
            setOAuth2AutosaveStatus("Token values saved to " + collection.name + ".", new Color(0, 128, 0));
            return;
        }

        OAuth2BearerAliasDetector.bindSelectedAliases(collection, candidates, selectedAliases, entry.accessToken);
        refreshRuntimeViewsForCollection(collection);
        appendImportLog("OAuth2 bearer aliases bound to \"" + collection.name + "\": " + String.join(", ", selectedAliases));
        setOAuth2AutosaveStatus("Token values saved to " + collection.name + ".", new Color(0, 128, 0));
    }

    static Map<String, String> buildOAuth2RuntimeSnapshot(TokenStore.TokenEntry entry, Map<String, String> panelVars) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        if (panelVars != null && !panelVars.isEmpty()) {
            snapshot.putAll(panelVars);
        }
        if (entry == null) {
            return snapshot;
        }

        if (entry.accessToken != null && !entry.accessToken.isBlank()) {
            snapshot.put("oauth2_access_token", entry.accessToken);
        }
        if (entry.refreshToken != null && !entry.refreshToken.isBlank()) {
            snapshot.put("oauth2_refresh_token", entry.refreshToken);
        }
        if (entry.tokenType != null && !entry.tokenType.isBlank()) {
            snapshot.put("oauth2_token_type", entry.tokenType);
        }
        if (entry.scope != null && !entry.scope.isBlank()) {
            snapshot.put("oauth2_scope", entry.scope);
        }
        if (entry.expiresAt > 0) {
            long expiresInSeconds = Math.max(0, (entry.expiresAt - System.currentTimeMillis()) / 1000);
            snapshot.put("oauth2_expires_in", String.valueOf(expiresInSeconds));
        }
        return snapshot;
    }

    static void applyAcquiredOAuth2Runtime(ApiCollection collection,
                                           TokenStore.TokenEntry entry,
                                           Map<String, String> panelVars) {
        if (collection == null || entry == null) {
            return;
        }
        collection.replaceRuntimeOAuth2(buildOAuth2RuntimeSnapshot(entry, panelVars));
    }

    static void clearVariablesEditorOnly(JTextArea envVarsArea,
                                         DefaultTableModel varsTableModel,
                                         burp.utils.DebouncedSwingAction variablesAutosave,
                                         Runnable beginSuppress,
                                         Runnable endSuppress,
                                         Runnable clearBaseLayerText,
                                         Consumer<String> statusUpdater) {
        if (variablesAutosave != null) {
            variablesAutosave.stop();
        }
        if (beginSuppress != null) {
            beginSuppress.run();
        }
        try {
            if (envVarsArea != null) {
                setTextPreservingView(envVarsArea, "");
            }
            if (clearBaseLayerText != null) {
                clearBaseLayerText.run();
            }
            if (varsTableModel != null) {
                varsTableModel.setRowCount(0);
            }
        } finally {
            if (endSuppress != null) {
                endSuppress.run();
            }
        }
        if (statusUpdater != null) {
            statusUpdater.accept("Editor cleared. Click Save Now to update the selected collection.");
        }
    }

    private void clearVariablesEditorOnly() {
        clearVariablesEditorOnly(
                envVarsArea,
                varsTableModel,
                variablesAutosave,
                () -> suppressVariablesAutosave = true,
                () -> suppressVariablesAutosave = false,
                () -> varsBaseLayerText = "",
                msg -> setVarsAutosaveStatus(msg, Color.GRAY)
        );
    }

    private void exportSelectedCollectionRuntimeJson() {
        ApiCollection collection = getSelectedVariablesCollection();
        if (collection == null) {
            appendImportLog("Runtime JSON export: no collection selected.");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export Runtime Variables JSON");
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files", "json"));
        String defaultName = (collection.name != null && !collection.name.isBlank()) ? collection.name : "runtime-variables";
        chooser.setSelectedFile(new File(defaultName.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json"));
        if (chooser.showSaveDialog(mainPanel) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File target = chooser.getSelectedFile();
        if (target.exists()) {
            int confirm = JOptionPane.showConfirmDialog(mainPanel,
                    "Overwrite existing file?\n" + target.getAbsolutePath(),
                    "Confirm Export Overwrite",
                    JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
        }

        try {
            Files.writeString(target.toPath(), RuntimeVariablesJson.toJson(collection), StandardCharsets.UTF_8);
            appendImportLog("Exported runtime variables for " + collection.name + ".");
        } catch (IOException ex) {
            appendImportLog("Runtime JSON export failed: " + ex.getMessage());
            JOptionPane.showMessageDialog(mainPanel,
                    "Failed to export runtime JSON:\n" + ex.getMessage(),
                    "Export Failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void importSelectedCollectionRuntimeJson() {
        ApiCollection collection = getSelectedVariablesCollection();
        if (collection == null) {
            appendImportLog("Runtime JSON import: no collection selected.");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import Runtime Variables JSON");
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files", "json"));
        if (chooser.showOpenDialog(mainPanel) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File source = chooser.getSelectedFile();
        try {
            String json = Files.readString(source.toPath(), StandardCharsets.UTF_8);
            RuntimeVariablesJson.RuntimeVariableBundle bundle = RuntimeVariablesJson.fromJson(json);
            Object[] options = {"Merge", "Replace", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                    mainPanel,
                    "Import runtime variables into \"" + collection.name + "\"?\n" +
                            "Merge keeps existing values. Replace overwrites runtime vars and OAuth2 runtime vars.",
                    "Confirm Runtime JSON Import",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    options,
                    options[0]);
            if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
                return;
            }

            RuntimeVariablesJson.applyToCollection(collection, bundle, choice == 1);
            appendImportLog("Imported runtime variables for " + collection.name + " from " + source.getName() + ".");
            renderEffectiveVariablesForSelectedCollection();
            refreshOAuth2PanelForCollection(collection);
            if (requestEditor != null && requestEditor.getCurrentCollection() == collection) {
                syncRequestEditorRuntimeContext(requestEditor.getCurrentRequest(), collection);
            }
        } catch (Exception ex) {
            appendImportLog("Runtime JSON import failed: " + ex.getMessage());
            JOptionPane.showMessageDialog(mainPanel,
                    "Failed to import runtime JSON:\n" + ex.getMessage(),
                    "Import Failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private ApiCollection getSelectedVariablesCollection() {
        CollectionRef ref = varsCollectionCombo != null ? (CollectionRef) varsCollectionCombo.getSelectedItem() : null;
        return ref != null ? ref.collection : null;
    }

    static List<UnresolvedVariableIssue> collectUnresolvedVariableIssues(List<ApiCollection> sourceCollections,
                                                                         List<ApiRequest> selectedRequests) {
        List<UnresolvedVariableIssue> issues = new ArrayList<>();
        if (selectedRequests == null || selectedRequests.isEmpty()) {
            return issues;
        }

        UnresolvedVariableAnalyzer analyzer = new UnresolvedVariableAnalyzer();
        Map<ApiRequest, ApiCollection> requestToCollection = new IdentityHashMap<>();
        Map<String, ApiCollection> collectionsByName = new HashMap<>();
        if (sourceCollections != null) {
            for (ApiCollection collection : sourceCollections) {
                if (collection == null) {
                    continue;
                }
                if (collection.name != null) {
                    collectionsByName.put(collection.name, collection);
                }
                if (collection.requests != null) {
                    for (ApiRequest request : collection.requests) {
                        requestToCollection.put(request, collection);
                    }
                }
            }
        }

        for (ApiRequest request : selectedRequests) {
            if (request == null) {
                continue;
            }
            ApiCollection collection = requestToCollection.get(request);
            if (collection == null && request.sourceCollection != null) {
                collection = collectionsByName.get(request.sourceCollection);
            }
            issues.addAll(analyzer.analyze(collection, request));
        }

        return issues;
    }

    static List<ApiCollection> collectCollectionsForRequests(List<ApiCollection> sourceCollections,
                                                             List<ApiRequest> selectedRequests) {
        List<ApiCollection> collections = new ArrayList<>();
        if (selectedRequests == null || selectedRequests.isEmpty()) {
            return collections;
        }

        Map<ApiRequest, ApiCollection> requestToCollection = new IdentityHashMap<>();
        Map<String, ApiCollection> collectionsByName = new HashMap<>();
        if (sourceCollections != null) {
            for (ApiCollection collection : sourceCollections) {
                if (collection == null) {
                    continue;
                }
                if (collection.name != null) {
                    collectionsByName.put(collection.name, collection);
                }
                if (collection.requests != null) {
                    for (ApiRequest request : collection.requests) {
                        requestToCollection.put(request, collection);
                    }
                }
            }
        }

        Set<ApiCollection> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ApiRequest request : selectedRequests) {
            if (request == null) {
                continue;
            }
            ApiCollection collection = requestToCollection.get(request);
            if (collection == null && request.sourceCollection != null) {
                collection = collectionsByName.get(request.sourceCollection);
            }
            if (collection != null && unique.add(collection)) {
                collections.add(collection);
            }
        }
        return collections;
    }

    private void refreshOAuth2PanelForCollection(ApiCollection col) {
        if (col != null && col.runtimeOAuth2 != null) {
            oauth2Panel.populateFromOAuth2Map(col.runtimeOAuth2);
        }
        applyAutoRefreshUiForSelectedCollection();
    }

    private void refreshRuntimeViewsForCollection(ApiCollection col) {
        if (col == null) {
            return;
        }
        CollectionRef varsRef = varsCollectionCombo != null && varsCollectionCombo.getSelectedItem() != null
                ? (CollectionRef) varsCollectionCombo.getSelectedItem() : null;
        if (varsRef != null && varsRef.collection == col) {
            renderEffectiveVariablesForSelectedCollection();
        }
        CollectionRef oauthRef = oauth2CollectionCombo != null && oauth2CollectionCombo.getSelectedItem() != null
                ? (CollectionRef) oauth2CollectionCombo.getSelectedItem() : null;
        if (oauthRef != null && oauthRef.collection == col) {
            refreshOAuth2PanelForCollection(col);
        }
        if (requestEditor != null && requestEditor.getCurrentCollection() == col) {
            syncRequestEditorRuntimeContext(requestEditor.getCurrentRequest(), col);
        }
    }

    private void renderEffectiveVariablesForSelectedCollection() {
        suppressVariablesAutosave = true;
        try {
            CollectionRef ref = (CollectionRef) varsCollectionCombo.getSelectedItem();
            if (ref != null) {
                ApiCollection col = ref.collection;
                StringBuilder base = new StringBuilder();
                StringBuilder sb = new StringBuilder();
                boolean hasAny = false;
                // Layer 1: environment
                if (col.environment != null && !col.environment.isEmpty()) {
                    base.append("# From collection environment (read-only base)\n");
                    for (Map.Entry<String, String> entry : new TreeMap<>(col.environment).entrySet()) {
                        base.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
                    }
                    base.append("\n");
                    hasAny = true;
                }
                // Layer 2: collection variables
                if (col.variables != null && !col.variables.isEmpty()) {
                    base.append("# From collection definition (read-only base)\n");
                    List<ApiRequest.Variable> sorted = new ArrayList<>(col.variables);
                    sorted.sort(Comparator.comparing(v -> v.key));
                    for (ApiRequest.Variable v : sorted) {
                        if (v.value != null) {
                            base.append(v.key).append("=").append(v.value).append("\n");
                        }
                    }
                    base.append("\n");
                    hasAny = true;
                }
                // Layer 3: scoped OAuth2 runtime (managed by OAuth2 tab / runtime refresh)
                if (col.runtimeOAuth2 != null && !col.runtimeOAuth2.isEmpty()) {
                    base.append("# Scoped OAuth2 runtime (managed by OAuth2 tab)\n");
                    for (Map.Entry<String, String> entry : new TreeMap<>(col.runtimeOAuth2).entrySet()) {
                        base.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
                    }
                    base.append("\n");
                    hasAny = true;
                }
                // Layer 4: runtime overrides (editable layer)
                sb.append(base);
                varsBaseLayerText = base.toString().trim();
                if (!varsBaseLayerText.isEmpty()) {
                    sb.append("\n");
                }
                if (col.runtimeVars != null && !col.runtimeVars.isEmpty()) {
                    sb.append("# Runtime overrides (edits apply here)\n");
                    for (Map.Entry<String, String> entry : new TreeMap<>(col.runtimeVars).entrySet()) {
                        sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
                    }
                    hasAny = true;
                } else if (hasAny) {
                    sb.append("# Runtime overrides (edits apply here)\n");
                }
                setVariablesEditorTextPreservingView(hasAny ? sb.toString() : "");
                if (isVarsTableViewActive()) {
                    renderVarsTableFromRaw();
                }
            } else {
                setVariablesEditorTextPreservingView("");
                varsBaseLayerText = "";
                if (varsTableModel != null) varsTableModel.setRowCount(0);
            }
        } finally {
            suppressVariablesAutosave = false;
        }
    }

    // ========================================================================
    // Collection Management
    // ========================================================================
    private void addCollection() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
            "API Collections (JSON, YAML, YML, HAR, BRU folder)",
            "json", "yaml", "yml", "har"
        ));
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (chooser.showOpenDialog(mainPanel) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            loadCollection(selected);
        }
    }

    private void loadCollection(File file) {
        appendImportLog("Loading collection: " + file.getName() + "...");
        SwingWorker<ApiCollection, String> worker = new SwingWorker<>() {
            @Override
            protected ApiCollection doInBackground() throws Exception {
                publish("Detecting format...");
                ParserRegistry registry = new ParserRegistry();
                CollectionParser parser = registry.detectParser(file);
                if (parser == null) {
                    throw new Exception("Unknown collection format. Supported: Postman, Bruno, OpenAPI, Insomnia, HAR");
                }
                publish("Detected: " + parser.getFormatName());
                publish("Parsing...");
                return parser.parse(file);
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) appendImportLog(msg);
            }

            @Override
            protected void done() {
                try {
                    ApiCollection collection = get();
                    String normName = collection.name != null ? collection.name.trim() : "";
                    boolean duplicate = false;
                    for (ApiCollection existing : loadedCollections) {
                        String existingNorm = existing.name != null ? existing.name.trim() : "";
                        if (existingNorm.equalsIgnoreCase(normName)) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (duplicate) {
                        JOptionPane.showMessageDialog(mainPanel,
                            "A collection named \"" + collection.name + "\" is already loaded.\nImport rejected to prevent ambiguity.",
                            "Duplicate Collection Name", JOptionPane.WARNING_MESSAGE);
                        appendImportLog("Rejected duplicate collection name: \"" + collection.name + "\"");
                        return;
                    }
                    registerCollectionRuntimeListener(collection);
                    loadedCollections.add(collection);
                    rebuildTree();
                    refreshCollectionCombos();
                    appendImportLog("Loaded \"" + collection.name + "\" (" + collection.requests.size() + " requests)");
                    importBtn.setEnabled(true);
                    sendToRunnerBtn.setEnabled(true);
                    startRunnerBtn.setEnabled(true);
                    removeCollectionBtn.setEnabled(true);
                    if (envApplyAllBtn != null) {
                        envApplyAllBtn.setEnabled(selectedEnv != null);
                    }
                    notifyWorkspaceChanged();
                } catch (Exception e) {
                    appendImportLog("Error loading collection: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void showRemoveCollectionsDialog() {
        if (loadedCollections.isEmpty()) {
            appendImportLog("No collections loaded.");
            return;
        }
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(mainPanel), "Remove Collection", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(8, 8));
        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        List<JCheckBox> checkboxes = new ArrayList<>();
        for (CollectionRef ref : buildCollectionRefs()) {
            JCheckBox box = new JCheckBox(ref.label);
            box.putClientProperty("collection", ref.collection);
            checkboxes.add(box);
            listPanel.add(box);
        }
        JScrollPane scrollPane = new JScrollPane(listPanel);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Select collection(s) to remove"));
        dialog.add(scrollPane, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        JButton remove = new JButton("Remove Collection");
        cancel.addActionListener(e -> dialog.dispose());
        remove.addActionListener(e -> {
            List<ApiCollection> selected = new ArrayList<>();
            for (JCheckBox box : checkboxes) {
                if (box.isSelected()) {
                    Object value = box.getClientProperty("collection");
                    if (value instanceof ApiCollection) {
                        selected.add((ApiCollection) value);
                    }
                }
            }
            if (selected.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Select at least one collection to remove.", "No Selection", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            removeCollections(selected);
            dialog.dispose();
        });
        buttons.add(cancel);
        buttons.add(remove);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(420, 380);
        dialog.setLocationRelativeTo(mainPanel);
        dialog.setVisible(true);
    }

    private void removeCollections(List<ApiCollection> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (ApiCollection target : targets) {
            stopAutoRefreshForCollection(target, "Collection removed");
            oauthAutoStates.remove(target);
            target.clearChangeListeners();
            requestToCollectionMap.entrySet().removeIf(entry -> entry.getValue() == target);
            loadedCollections.remove(target);
            appendImportLog("Removed collection: " + target.name);
        }
        rebuildTree();
        refreshCollectionCombos();
        notifyWorkspaceChanged();
        if (loadedCollections.isEmpty()) {
            importBtn.setEnabled(false);
            sendToRunnerBtn.setEnabled(false);
            startRunnerBtn.setEnabled(false);
            removeCollectionBtn.setEnabled(false);
        }
        runnerQueuedRequests.removeIf(req -> requestToCollectionMap.get(req) == null);
    }

    private void registerCollectionRuntimeListener(ApiCollection col) {
        if (col == null) {
            return;
        }
        col.addChangeListener(() -> SwingUtilities.invokeLater(() -> {
            if (shuttingDown) {
                return;
            }
            refreshRuntimeViewsForCollection(col);
            notifyWorkspaceChanged();
        }));
    }

    public List<ApiCollection> getLoadedCollectionsSnapshot() {
        return WorkspaceState.fromCollections(loadedCollections).collections;
    }

    public WorkspaceState getWorkspaceStateSnapshot() {
        runWithWorkspaceChangeNotificationsSuppressed(this::persistVariablesEditorStateSilently);
        runWithWorkspaceChangeNotificationsSuppressed(this::persistCurrentRequestEditorState);

        WorkspaceState state = WorkspaceState.fromCollections(loadedCollections);
        state.requestTreePaths = collectRequestTreePaths();
        state.expandedTreePathKeys = collectExpandedTreePathKeys();
        if (tabbedPane != null) {
            state.selectedTabIndex = tabbedPane.getSelectedIndex();
        }
        state.selectedVariablesCollectionName = getSelectedCollectionName(varsCollectionCombo);
        state.selectedOAuth2CollectionName = getSelectedCollectionName(oauth2CollectionCombo);
        state.selectedRequestIdentityKey = getSelectedRequestIdentityKey();

        CollectionTreeNode selectedNode = getSelectedRequestTreeNode();
        if (selectedNode != null && selectedNode.request != null) {
            ApiCollection selectedCollection = findCollectionForNode(selectedNode);
            state.selectedRequestCollectionName = selectedCollection != null ? selectedCollection.name : selectedNode.request.sourceCollection;
            state.selectedRequestName = selectedNode.request.name;
            state.selectedRequestPath = selectedNode.request.path;
        }

        state.checkedRequestIdentityKeys = collectCheckedRequestIdentityKeys();
        state.checkedRequestKeys = collectCheckedRequestKeys();
        captureWorkbenchSettings(state);
        captureRunnerSettings(state);
        captureRunnerDetailState(state);
        captureOAuthAutoRefreshState(state);
        return state;
    }

    public void restoreWorkspaceState(WorkspaceState state) {
        if (state == null || state.collections == null || state.collections.isEmpty()) {
            return;
        }
        PendingMainRequestTreeRestore pendingRestore = new PendingMainRequestTreeRestore(state);
        runWithWorkspaceChangeNotificationsSuppressed(() -> {
            pendingWorkspaceRequestTreePaths = pendingRestore.requestTreePaths;
            try {
                restoreWorkspaceCollections(state.collections);
                selectCollectionByName(varsCollectionCombo, state.selectedVariablesCollectionName);
                selectCollectionByName(oauth2CollectionCombo, state.selectedOAuth2CollectionName);
                restoreWorkbenchSettings(state);
                restoreRunnerSettings(state);
                restoreRunnerDetailState(state);
                restoreOAuthAutoRefreshState(state.oauthAutoRefreshByCollection);
                if (tabbedPane != null && tabbedPane.getTabCount() > 0) {
                    int index = Math.max(0, Math.min(state.selectedTabIndex, tabbedPane.getTabCount() - 1));
                    tabbedPane.setSelectedIndex(index);
                }
                scheduleMainRequestTreeRestoreAfterWorkbenchVisible(() -> remountRestoredMainRequestTree(pendingRestore));
            } finally {
                pendingWorkspaceRequestTreePaths = Collections.emptyMap();
            }
        });
    }

    private static final class PendingMainRequestTreeRestore {
        final Map<String, String> requestTreePaths;
        final List<String> expandedTreePathKeys;
        final List<String> checkedRequestIdentityKeys;
        final List<String> checkedRequestKeys;
        final String selectedRequestCollectionName;
        final String selectedRequestIdentityKey;
        final String selectedRequestPath;
        final String selectedRequestName;

        private PendingMainRequestTreeRestore(WorkspaceState state) {
            this.requestTreePaths = state.requestTreePaths != null
                    ? normalizeWorkspaceRequestTreePaths(state.requestTreePaths)
                    : Collections.emptyMap();
            this.expandedTreePathKeys = state.expandedTreePathKeys != null
                    ? new ArrayList<>(state.expandedTreePathKeys)
                    : Collections.emptyList();
            this.checkedRequestIdentityKeys = state.checkedRequestIdentityKeys != null
                    ? new ArrayList<>(state.checkedRequestIdentityKeys)
                    : Collections.emptyList();
            this.checkedRequestKeys = state.checkedRequestKeys != null
                    ? new ArrayList<>(state.checkedRequestKeys)
                    : Collections.emptyList();
            this.selectedRequestCollectionName = state.selectedRequestCollectionName;
            this.selectedRequestIdentityKey = state.selectedRequestIdentityKey;
            this.selectedRequestPath = state.selectedRequestPath;
            this.selectedRequestName = state.selectedRequestName;
        }
    }

    private void scheduleMainRequestTreeRestoreAfterWorkbenchVisible(Runnable initializer) {
        if (requestTreeScrollPane == null) {
            return;
        }
        if (initializer == null) {
            return;
        }
        if (requestTreeScrollPane.isShowing()) {
            SwingUtilities.invokeLater(initializer);
            return;
        }
        Runnable pendingInitializer = initializer;
        Object existingInitializer = requestTreeScrollPane.getClientProperty(MAIN_TREE_RESTORE_INITIALIZER_KEY);
        if (existingInitializer instanceof Runnable) {
            Runnable priorInitializer = (Runnable) existingInitializer;
            pendingInitializer = () -> {
                priorInitializer.run();
                initializer.run();
            };
        }
        requestTreeScrollPane.putClientProperty(MAIN_TREE_RESTORE_INITIALIZER_KEY, pendingInitializer);

        Object listener = requestTreeScrollPane.getClientProperty(MAIN_TREE_RESTORE_INITIALIZER_LISTENER_KEY);
        if (listener instanceof HierarchyListener) {
            return;
        }
        JScrollPane scrollPane = requestTreeScrollPane;
        HierarchyListener hierarchyListener = new HierarchyListener() {
            @Override
            public void hierarchyChanged(HierarchyEvent e) {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0 || !scrollPane.isShowing()) {
                    return;
                }
                scrollPane.removeHierarchyListener(this);
                Object shownInitializer = scrollPane.getClientProperty(MAIN_TREE_RESTORE_INITIALIZER_KEY);
                scrollPane.putClientProperty(MAIN_TREE_RESTORE_INITIALIZER_KEY, null);
                scrollPane.putClientProperty(MAIN_TREE_RESTORE_INITIALIZER_LISTENER_KEY, null);
                if (shownInitializer instanceof Runnable) {
                    SwingUtilities.invokeLater((Runnable) shownInitializer);
                }
            }
        };
        requestTreeScrollPane.putClientProperty(MAIN_TREE_RESTORE_INITIALIZER_LISTENER_KEY, hierarchyListener);
        requestTreeScrollPane.addHierarchyListener(hierarchyListener);
    }

    private void remountRestoredMainRequestTree(PendingMainRequestTreeRestore pendingRestore) {
        JTree liveTree = buildMainRequestTree();
        mountMainRequestTree(liveTree);
        rebuildTree(pendingRestore.requestTreePaths, Collections.emptyList());
        expandMainRequestTreeRows();
        applySavedMainTreeExpansionShape(pendingRestore.expandedTreePathKeys);
        if (!pendingRestore.checkedRequestIdentityKeys.isEmpty()) {
            restoreCheckedRequestIdentityKeys(pendingRestore.checkedRequestIdentityKeys);
        } else {
            restoreCheckedRequestKeys(pendingRestore.checkedRequestKeys);
        }
        restoreSelectedRequest(
                pendingRestore.selectedRequestCollectionName,
                pendingRestore.selectedRequestIdentityKey,
                pendingRestore.selectedRequestPath,
                pendingRestore.selectedRequestName
        );
        resetRequestTreeHorizontalViewport();
    }

    private void expandMainRequestTreeRows() {
        if (requestTree == null) {
            return;
        }
        for (int i = 0; i < requestTree.getRowCount(); i++) {
            requestTree.expandRow(i);
        }
    }

    private void applySavedMainTreeExpansionShape(List<String> expandedTreePathKeys) {
        if (requestTree == null || treeModel == null || expandedTreePathKeys == null || expandedTreePathKeys.isEmpty()) {
            return;
        }
        Set<String> savedExpandedKeys = new HashSet<>(expandedTreePathKeys);
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        collapseUnsavedMainTreePaths(root, null, savedExpandedKeys);
    }

    private void collapseUnsavedMainTreePaths(DefaultMutableTreeNode node,
                                              String currentCollectionName,
                                              Set<String> savedExpandedKeys) {
        if (node == null) {
            return;
        }
        String nextCollectionName = currentCollectionName;
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode collectionNode = (CollectionTreeNode) node;
            if (collectionNode.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                nextCollectionName = collectionNode.collection != null ? collectionNode.collection.name : currentCollectionName;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collapseUnsavedMainTreePaths((DefaultMutableTreeNode) node.getChildAt(i), nextCollectionName, savedExpandedKeys);
        }
        if (!(node instanceof CollectionTreeNode)) {
            return;
        }
        CollectionTreeNode treeNode = (CollectionTreeNode) node;
        if (treeNode.getNodeType() != CollectionTreeNode.Type.COLLECTION && treeNode.getNodeType() != CollectionTreeNode.Type.FOLDER) {
            return;
        }
        String collectionName = treeNode.getNodeType() == CollectionTreeNode.Type.COLLECTION
                ? (treeNode.collection != null ? treeNode.collection.name : currentCollectionName)
                : currentCollectionName;
        if (collectionName == null || collectionName.isBlank()) {
            return;
        }
        String folderPath = treeNode.getNodeType() == CollectionTreeNode.Type.COLLECTION
                ? ""
                : (treeNode.folderPath != null ? treeNode.folderPath : "");
        String treePathKey = workspaceTreePathKey(collectionName, folderPath);
        if (shouldKeepMainTreePathExpanded(treeNode.getNodeType(), treePathKey, savedExpandedKeys)) {
            return;
        }
        requestTree.collapsePath(new TreePath(treeNode.getPath()));
    }

    private boolean shouldKeepMainTreePathExpanded(CollectionTreeNode.Type nodeType,
                                                   String treePathKey,
                                                   Set<String> savedExpandedKeys) {
        if (savedExpandedKeys.contains(treePathKey)) {
            return true;
        }
        String descendantPrefix = nodeType == CollectionTreeNode.Type.COLLECTION ? treePathKey : treePathKey + "/";
        for (String savedKey : savedExpandedKeys) {
            if (savedKey.startsWith(descendantPrefix)) {
                return true;
            }
        }
        return false;
    }

    static void applyWorkspaceRequestTreePathsToRequests(List<ApiCollection> collections, Map<String, String> requestTreePaths) {
        if (collections == null || collections.isEmpty()) {
            return;
        }
        for (int collectionIndex = 0; collectionIndex < collections.size(); collectionIndex++) {
            ApiCollection collection = collections.get(collectionIndex);
            if (collection == null || collection.requests == null || collection.requests.isEmpty()) {
                continue;
            }
            for (int requestIndex = 0; requestIndex < collection.requests.size(); requestIndex++) {
                ApiRequest request = collection.requests.get(requestIndex);
                if (request == null) {
                    continue;
                }
                String folderPath = lookupWorkspaceRequestTreeFolderPath(requestTreePaths, collectionIndex, collection, request, requestIndex);
                if (folderPath == null) {
                    continue;
                }
                String requestName = request.name != null ? request.name : "";
                if (folderPath.isBlank()) {
                    if (isNestedRequestPath(request.path, requestName)) {
                        continue;
                    }
                    request.path = requestName;
                } else if (requestName.isBlank()) {
                    request.path = folderPath;
                } else {
                    request.path = folderPath + "/" + requestName;
                }
            }
        }
    }

    static boolean isNestedRequestPath(String requestPath, String requestName) {
        if (requestPath == null || requestPath.isBlank()) {
            return false;
        }
        String normalized = requestPath.replace('\\', '/').trim();
        if (!normalized.contains("/")) {
            return false;
        }
        String name = requestName != null ? requestName.trim() : "";
        if (name.isEmpty()) {
            return true;
        }
        return normalized.equals(name) || normalized.endsWith("/" + name);
    }

    public void restoreWorkspaceCollections(List<ApiCollection> collections) {
        for (ApiCollection existing : new ArrayList<>(loadedCollections)) {
            existing.clearChangeListeners();
        }
        loadedCollections.clear();
        runnerQueuedRequests.clear();
        requestToCollectionMap.clear();
        if (collections != null) {
            for (ApiCollection col : collections) {
            if (col == null) {
                continue;
            }
            loadedCollections.add(col);
            registerCollectionRuntimeListener(col);
                if (col.requests != null) {
                    for (ApiRequest req : col.requests) {
                        requestToCollectionMap.put(req, col);
                        req.sourceCollection = col.name;
                    }
                }
            }
        }
        loadRequestTreeModel(pendingWorkspaceRequestTreePaths);
        if (requestTree != null) {
            requestTree.clearSelection();
        }
        refreshCollectionCombos();
        renderEffectiveVariablesForSelectedCollection();
        updateScopeControlState();
        refreshSessionActionControls();
    }

    private void stabilizeRestoredRequestTreePresentation(WorkspaceState state) {
        if (requestTree == null || treeModel == null || treeModel.getRoot() == null) {
            return;
        }
        Map<String, String> requestTreePaths = state != null && state.requestTreePaths != null
                ? normalizeWorkspaceRequestTreePaths(state.requestTreePaths)
                : Collections.emptyMap();
        List<String> expandedTreePathKeys = state != null && state.expandedTreePathKeys != null
                ? new ArrayList<>(state.expandedTreePathKeys)
                : Collections.emptyList();

        rebuildTree(requestTreePaths, expandedTreePathKeys);

        if (state != null) {
            if (state.checkedRequestIdentityKeys != null && !state.checkedRequestIdentityKeys.isEmpty()) {
                restoreCheckedRequestIdentityKeys(state.checkedRequestIdentityKeys);
            } else {
                restoreCheckedRequestKeys(state.checkedRequestKeys);
            }
            restoreSelectedRequest(state.selectedRequestCollectionName, state.selectedRequestIdentityKey, state.selectedRequestPath, state.selectedRequestName);
        }
        resetRequestTreeHorizontalViewport();
        scheduleRequestTreeHorizontalViewportReset();
    }

    /**
     * Recomputes primary action controls from live session state.
     * This is used after restore so a re-opened project is immediately runnable.
     */
    private void refreshSessionActionControls() {
        boolean hasCollections = !loadedCollections.isEmpty();

        if (importBtn != null) {
            importBtn.setEnabled(hasCollections);
        }
        if (sendToRunnerBtn != null) {
            sendToRunnerBtn.setEnabled(hasCollections);
        }
        if (removeCollectionBtn != null) {
            removeCollectionBtn.setEnabled(hasCollections);
        }
        if (envApplyAllBtn != null) {
            envApplyAllBtn.setEnabled(hasCollections && selectedEnv != null);
        }

        // Runner controls are stateful; derive from actual runner status.
        if (runner != null) {
            setRunnerControlsRunning(runner.isRunning());
        } else {
            if (startRunnerBtn != null) {
                startRunnerBtn.setEnabled(hasCollections);
            }
            if (cancelRunnerBtn != null) {
                cancelRunnerBtn.setEnabled(false);
            }
            if (pauseRunnerBtn != null) {
                pauseRunnerBtn.setEnabled(false);
            }
            if (resumeRunnerBtn != null) {
                resumeRunnerBtn.setEnabled(false);
            }
            if (stepRunnerBtn != null) {
                stepRunnerBtn.setEnabled(false);
            }
        }
    }

    static String workspaceRequestIdentityKey(String collectionName, ApiRequest request, int requestIndex) {
        StringBuilder builder = new StringBuilder();
        builder.append(collectionName != null ? collectionName : "");
        builder.append('\u001F');
        if (request != null) {
            if (request.id != null && !request.id.isBlank()) {
                builder.append("id=").append(request.id.trim());
            } else {
                builder.append("index=").append(requestIndex);
                builder.append('\u001F').append("method=").append(request.method != null ? request.method : "");
                builder.append('\u001F').append("name=").append(request.name != null ? request.name : "");
                builder.append('\u001F').append("url=").append(request.url != null ? request.url : "");
            }
        }
        return builder.toString();
    }

    static String workspaceRequestIdentityKey(String collectionName, ApiRequest request) {
        return workspaceRequestIdentityKey(collectionName, request, request != null ? request.sequenceOrder : -1);
    }

    static String workspaceRequestTreePathKey(String collectionName, int collectionIndex, ApiRequest request, int requestIndex) {
        StringBuilder builder = new StringBuilder();
        builder.append("collectionIndex=").append(collectionIndex);
        builder.append('\u001F');
        builder.append("requestIndex=").append(requestIndex);
        builder.append('\u001F');
        builder.append(workspaceRequestIdentityKey(collectionName, request, requestIndex));
        return builder.toString();
    }

    static String workspaceRequestKey(String collectionName, ApiRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append(collectionName != null ? collectionName : "");
        builder.append('\u001F');
        if (request != null) {
            builder.append(request.path != null ? request.path : "");
            builder.append('\u001F');
            builder.append(request.name != null ? request.name : "");
            builder.append('\u001F');
            builder.append(request.method != null ? request.method : "");
            builder.append('\u001F');
            builder.append(request.sequenceOrder);
        }
        return builder.toString();
    }

    private Map<String, String> collectRequestTreePaths() {
        Map<String, String> out = new LinkedHashMap<>();
        if (treeModel == null || treeModel.getRoot() == null) {
            return out;
        }
        collectRequestTreePaths((DefaultMutableTreeNode) treeModel.getRoot(), null, -1, "", out);
        return out;
    }

    private void collectRequestTreePaths(DefaultMutableTreeNode node,
                                         ApiCollection collection,
                                         int collectionIndex,
                                         String folderPath,
                                         Map<String, String> out) {
        ApiCollection currentCollection = collection;
        int currentCollectionIndex = collectionIndex;
        String currentFolderPath = folderPath != null ? folderPath : "";
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                currentCollection = ctn.collection != null ? ctn.collection : collection;
                currentCollectionIndex = findCollectionIndex(currentCollection);
                currentFolderPath = "";
            } else if (ctn.getNodeType() == CollectionTreeNode.Type.FOLDER) {
                currentFolderPath = ctn.folderPath != null ? ctn.folderPath : currentFolderPath;
            } else if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST && ctn.request != null) {
                int requestIndex = currentCollection != null && currentCollection.requests != null
                        ? currentCollection.requests.indexOf(ctn.request)
                        : -1;
                String collectionName = currentCollection != null ? currentCollection.name : ctn.request.sourceCollection;
                String key = workspaceRequestTreePathKey(collectionName, currentCollectionIndex, ctn.request, requestIndex);
                putRequestTreePath(out, key, currentFolderPath, collectionName, currentCollectionIndex, ctn.request, requestIndex);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectRequestTreePaths((DefaultMutableTreeNode) node.getChildAt(i), currentCollection, currentCollectionIndex, currentFolderPath, out);
        }
    }

    private int findCollectionIndex(ApiCollection collection) {
        if (collection == null) {
            return -1;
        }
        for (int i = 0; i < loadedCollections.size(); i++) {
            if (loadedCollections.get(i) == collection) {
                return i;
            }
        }
        return -1;
    }

    private void putRequestTreePath(Map<String, String> out,
                                    String key,
                                    String folderPath,
                                    String collectionName,
                                    int collectionIndex,
                                    ApiRequest request,
                                    int requestIndex) {
        String resolvedKey = key;
        if (out.containsKey(resolvedKey)) {
            String duplicateKey = resolvedKey + '\u001F' + "duplicate=2";
            int duplicateOrdinal = 2;
            while (out.containsKey(duplicateKey)) {
                duplicateOrdinal++;
                duplicateKey = resolvedKey + '\u001F' + "duplicate=" + duplicateOrdinal;
            }
            LOGGER.warning("Unexpected duplicate requestTreePaths key collision for collectionIndex="
                    + collectionIndex
                    + ", collection="
                    + (collectionName != null ? collectionName : "")
                    + ", requestIndex="
                    + requestIndex
                    + "; preserving entry with key "
                    + duplicateKey);
            resolvedKey = duplicateKey;
        }
        out.put(resolvedKey, folderPath);
    }

    private List<String> collectCheckedRequestIdentityKeys() {
        List<String> keys = new ArrayList<>();
        if (treeModel == null || treeModel.getRoot() == null) {
            return keys;
        }
        collectCheckedRequestIdentityKeys((DefaultMutableTreeNode) treeModel.getRoot(), null, keys);
        return keys;
    }

    private void collectCheckedRequestIdentityKeys(DefaultMutableTreeNode node, String collectionName, List<String> out) {
        String currentCollectionName = collectionName;
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                currentCollectionName = ctn.collection != null ? ctn.collection.name : collectionName;
            } else if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST && ctn.isChecked() && ctn.request != null) {
                int requestIndex = findRequestIndex(currentCollectionName, ctn.request);
                out.add(requestIndex >= 0
                        ? workspaceRequestIdentityKey(currentCollectionName != null ? currentCollectionName : ctn.request.sourceCollection, ctn.request, requestIndex)
                        : workspaceRequestIdentityKey(currentCollectionName != null ? currentCollectionName : ctn.request.sourceCollection, ctn.request));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectCheckedRequestIdentityKeys((DefaultMutableTreeNode) node.getChildAt(i), currentCollectionName, out);
        }
    }

    private String getSelectedCollectionName(JComboBox<CollectionRef> combo) {
        CollectionRef ref = combo != null && combo.getSelectedItem() != null ? (CollectionRef) combo.getSelectedItem() : null;
        return ref != null && ref.collection != null ? ref.collection.name : null;
    }

    private boolean selectCollectionByName(JComboBox<CollectionRef> combo, String collectionName) {
        if (combo == null || collectionName == null) {
            return false;
        }
        for (int i = 0; i < combo.getItemCount(); i++) {
            CollectionRef ref = combo.getItemAt(i);
            if (ref != null && ref.collection != null && Objects.equals(collectionName, ref.collection.name)) {
                combo.setSelectedIndex(i);
                return true;
            }
        }
        return false;
    }

    private String getSelectedRequestIdentityKey() {
        CollectionTreeNode selectedNode = getSelectedRequestTreeNode();
        if (selectedNode == null || selectedNode.request == null) {
            return null;
        }
        ApiCollection selectedCollection = findCollectionForNode(selectedNode);
        String collectionName = selectedCollection != null ? selectedCollection.name : selectedNode.request.sourceCollection;
        int requestIndex = findRequestIndex(collectionName, selectedNode.request);
        return requestIndex >= 0
                ? workspaceRequestIdentityKey(collectionName, selectedNode.request, requestIndex)
                : workspaceRequestIdentityKey(collectionName, selectedNode.request);
    }

    private int findRequestIndex(String collectionName, ApiRequest request) {
        ApiCollection collection = collectionName != null ? findCollectionByName(collectionName) : null;
        if (collection == null || collection.requests == null || request == null) {
            return -1;
        }
        return collection.requests.indexOf(request);
    }

    private void restoreCheckedRequestIdentityKeys(List<String> checkedKeys) {
        if (treeModel == null || treeModel.getRoot() == null || checkedKeys == null || checkedKeys.isEmpty()) {
            return;
        }
        Set<String> keySet = new LinkedHashSet<>(checkedKeys);
        applyCheckedRequestIdentityKeys((DefaultMutableTreeNode) treeModel.getRoot(), null, keySet);
        updateCheckedParentStates();
    }

    private void applyCheckedRequestIdentityKeys(DefaultMutableTreeNode node, String collectionName, Set<String> checkedKeys) {
        String currentCollectionName = collectionName;
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                currentCollectionName = ctn.collection != null ? ctn.collection.name : collectionName;
            } else if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST && ctn.request != null) {
                int requestIndex = findRequestIndex(currentCollectionName, ctn.request);
                String key = requestIndex >= 0
                        ? workspaceRequestIdentityKey(currentCollectionName != null ? currentCollectionName : ctn.request.sourceCollection, ctn.request, requestIndex)
                        : workspaceRequestIdentityKey(currentCollectionName != null ? currentCollectionName : ctn.request.sourceCollection, ctn.request);
                ctn.setChecked(checkedKeys.contains(key));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            applyCheckedRequestIdentityKeys((DefaultMutableTreeNode) node.getChildAt(i), currentCollectionName, checkedKeys);
        }
    }

    private CollectionTreeNode getSelectedRequestTreeNode() {
        if (requestTree == null) {
            return null;
        }
        TreePath selection = requestTree.getSelectionPath();
        if (selection == null) {
            return null;
        }
        Object node = selection.getLastPathComponent();
        return node instanceof CollectionTreeNode ? (CollectionTreeNode) node : null;
    }

    private List<String> collectCheckedRequestKeys() {
        List<String> keys = new ArrayList<>();
        if (treeModel == null || treeModel.getRoot() == null) {
            return keys;
        }
        collectCheckedRequestKeys((DefaultMutableTreeNode) treeModel.getRoot(), null, keys);
        return keys;
    }

    private void collectCheckedRequestKeys(DefaultMutableTreeNode node, String collectionName, List<String> out) {
        String currentCollectionName = collectionName;
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                currentCollectionName = ctn.collection != null ? ctn.collection.name : collectionName;
            } else if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST && ctn.isChecked() && ctn.request != null) {
                out.add(workspaceRequestKey(currentCollectionName != null ? currentCollectionName : ctn.request.sourceCollection, ctn.request));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectCheckedRequestKeys((DefaultMutableTreeNode) node.getChildAt(i), currentCollectionName, out);
        }
    }

    private void restoreCheckedRequestKeys(List<String> checkedKeys) {
        if (treeModel == null || treeModel.getRoot() == null) {
            return;
        }
        Set<String> keySet = checkedKeys != null ? new LinkedHashSet<>(checkedKeys) : Collections.emptySet();
        applyCheckedRequestKeys((DefaultMutableTreeNode) treeModel.getRoot(), null, keySet);
        updateCheckedParentStates();
    }

    private void applyCheckedRequestKeys(DefaultMutableTreeNode node, String collectionName, Set<String> checkedKeys) {
        String currentCollectionName = collectionName;
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                currentCollectionName = ctn.collection != null ? ctn.collection.name : collectionName;
            } else if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST && ctn.request != null) {
                String key = workspaceRequestKey(currentCollectionName != null ? currentCollectionName : ctn.request.sourceCollection, ctn.request);
                ctn.setChecked(checkedKeys.contains(key));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            applyCheckedRequestKeys((DefaultMutableTreeNode) node.getChildAt(i), currentCollectionName, checkedKeys);
        }
    }

    private void updateCheckedParentStates() {
        if (treeModel == null || treeModel.getRoot() == null) {
            return;
        }
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            Object child = root.getChildAt(i);
            if (child instanceof CollectionTreeNode) {
                ((CollectionTreeNode) child).updateParentCheckState();
            }
        }
        requestTree.repaint();
        updateScopeControlState();
    }

    private void restoreSelectedRequest(String collectionName, String requestIdentityKey, String requestPath, String requestName) {
        if (requestTree == null || treeModel == null || treeModel.getRoot() == null) {
            return;
        }
        TreePath path = null;
        if (requestIdentityKey != null && !requestIdentityKey.isBlank()) {
            path = findRequestTreePathByIdentity((DefaultMutableTreeNode) treeModel.getRoot(), collectionName, requestIdentityKey, null);
        }
        if (path == null) {
            path = findRequestTreePath((DefaultMutableTreeNode) treeModel.getRoot(), collectionName, requestPath, requestName, null);
        }
        if (path != null) {
            expandTreePath(path);
            requestTree.setSelectionPath(path);
        }
    }

    private TreePath findRequestTreePathByIdentity(DefaultMutableTreeNode node,
                                                   String collectionName,
                                                   String requestIdentityKey,
                                                   String currentCollectionName) {
        String nextCollectionName = currentCollectionName;
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                nextCollectionName = ctn.collection != null ? ctn.collection.name : currentCollectionName;
            } else if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST && ctn.request != null) {
                int requestIndex = findRequestIndex(nextCollectionName, ctn.request);
                String key = requestIndex >= 0
                        ? workspaceRequestIdentityKey(nextCollectionName != null ? nextCollectionName : ctn.request.sourceCollection, ctn.request, requestIndex)
                        : workspaceRequestIdentityKey(nextCollectionName != null ? nextCollectionName : ctn.request.sourceCollection, ctn.request);
                if ((collectionName == null || Objects.equals(collectionName, nextCollectionName)) && Objects.equals(requestIdentityKey, key)) {
                    return new TreePath(ctn.getPath());
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TreePath path = findRequestTreePathByIdentity((DefaultMutableTreeNode) node.getChildAt(i), collectionName, requestIdentityKey, nextCollectionName);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private List<String> collectExpandedTreePathKeys() {
        List<String> keys = new ArrayList<>();
        if (requestTree == null || treeModel == null || treeModel.getRoot() == null) {
            return keys;
        }
        collectExpandedTreePathKeys((DefaultMutableTreeNode) treeModel.getRoot(), null, "", new TreePath(treeModel.getRoot()), keys);
        return keys;
    }

    private void collectExpandedTreePathKeys(DefaultMutableTreeNode node,
                                             ApiCollection collection,
                                             String folderPath,
                                             TreePath path,
                                             List<String> out) {
        ApiCollection currentCollection = collection;
        String currentFolderPath = folderPath != null ? folderPath : "";
        TreePath currentPath = path;
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                currentCollection = ctn.collection != null ? ctn.collection : collection;
                currentFolderPath = "";
            } else if (ctn.getNodeType() == CollectionTreeNode.Type.FOLDER) {
                currentFolderPath = ctn.folderPath != null ? ctn.folderPath : currentFolderPath;
            }
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION || ctn.getNodeType() == CollectionTreeNode.Type.FOLDER) {
                if (requestTree.isExpanded(currentPath)) {
                    out.add(workspaceTreePathKey(currentCollection != null ? currentCollection.name : null, currentFolderPath));
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectExpandedTreePathKeys((DefaultMutableTreeNode) node.getChildAt(i), currentCollection, currentFolderPath, currentPath.pathByAddingChild(node.getChildAt(i)), out);
        }
    }

    private void restoreExpandedTreePathKeys(List<String> expandedKeys) {
        if (requestTree == null || treeModel == null || treeModel.getRoot() == null || expandedKeys == null || expandedKeys.isEmpty()) {
            return;
        }
        Set<String> keySet = new LinkedHashSet<>(expandedKeys);
        restoreExpandedTreePathKeys((DefaultMutableTreeNode) treeModel.getRoot(), null, "", new TreePath(treeModel.getRoot()), keySet);
    }

    private boolean restoreExpandedTreePathKeys(DefaultMutableTreeNode node,
                                                ApiCollection collection,
                                                String folderPath,
                                                TreePath path,
                                                Set<String> expandedKeys) {
        ApiCollection currentCollection = collection;
        String currentFolderPath = folderPath != null ? folderPath : "";
        boolean descendantExpanded = false;
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                currentCollection = ctn.collection != null ? ctn.collection : collection;
                currentFolderPath = "";
            } else if (ctn.getNodeType() == CollectionTreeNode.Type.FOLDER) {
                currentFolderPath = ctn.folderPath != null ? ctn.folderPath : currentFolderPath;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            descendantExpanded |= restoreExpandedTreePathKeys((DefaultMutableTreeNode) node.getChildAt(i), currentCollection, currentFolderPath, path.pathByAddingChild(node.getChildAt(i)), expandedKeys);
        }
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION || ctn.getNodeType() == CollectionTreeNode.Type.FOLDER) {
                boolean selfExpanded = expandedKeys.contains(workspaceTreePathKey(currentCollection != null ? currentCollection.name : null, currentFolderPath));
                if (selfExpanded || descendantExpanded) {
                    requestTree.expandPath(path);
                    return true;
                }
            }
        }
        return descendantExpanded;
    }

    private void expandTreePath(TreePath path) {
        if (requestTree == null || path == null) {
            return;
        }
        TreePath current = path.getParentPath();
        if (current != null) {
            expandTreePath(current);
        }
        requestTree.expandPath(path);
    }

    static String workspaceTreePathKey(String collectionName, String folderPath) {
        return (collectionName != null ? collectionName : "") + '\u001F' + (folderPath != null ? folderPath : "");
    }

    private void captureWorkbenchSettings(WorkspaceState state) {
        if (state == null) {
            return;
        }
        if (repeaterBtn != null) state.workbenchRepeaterSelected = repeaterBtn.isSelected();
        if (sitemapBtn != null) state.workbenchSitemapSelected = sitemapBtn.isSelected();
        if (intruderBtn != null) state.workbenchIntruderSelected = intruderBtn.isSelected();
        if (delaySpinner != null) state.workbenchDelayMs = spinnerIntValue(delaySpinner);
        if (debugRawRequestBox != null) state.workbenchDebugRawRequest = debugRawRequestBox.isSelected();
        if (workbenchDetailTabs != null) state.workbenchDetailTabIndex = workbenchDetailTabs.getSelectedIndex();
    }

    private void captureRunnerSettings(WorkspaceState state) {
        if (state == null) {
            return;
        }
        if (runnerDelaySpinner != null) state.runnerDelayMs = spinnerIntValue(runnerDelaySpinner);
        if (runnerRetriesSpinner != null) state.runnerRetries = spinnerIntValue(runnerRetriesSpinner);
        if (stopOnErrorBox != null) state.runnerStopOnError = stopOnErrorBox.isSelected();
        if (stopOnAssertionFailureBox != null) state.runnerStopOnAssertionFailure = stopOnAssertionFailureBox.isSelected();
        if (stopOnStatusAtLeast400Box != null) state.runnerStopOnStatusAtLeast400 = stopOnStatusAtLeast400Box.isSelected();
        if (stopOnMissingVariableBox != null) state.runnerStopOnMissingVariable = stopOnMissingVariableBox.isSelected();
        if (stopAfterFailuresSpinner != null) state.runnerStopAfterFailures = spinnerIntValue(stopAfterFailuresSpinner);
        if (followRedirectsBox != null) state.runnerFollowRedirects = followRedirectsBox.isSelected();
        if (runnerDebugRawRequestBox != null) state.runnerDebugRawRequest = runnerDebugRawRequestBox.isSelected();
        if (runnerDetailTabs != null) state.runnerDetailTabIndex = runnerDetailTabs.getSelectedIndex();
    }

    private void captureRunnerDetailState(WorkspaceState state) {
        if (state == null || runnerDetailTabs == null) {
            return;
        }
        state.runnerDetailTabIndex = runnerDetailTabs.getSelectedIndex();
    }

    private void captureOAuthAutoRefreshState(WorkspaceState state) {
        if (state == null) {
            return;
        }
        state.oauthAutoRefreshByCollection = new LinkedHashMap<>();
        for (ApiCollection collection : loadedCollections) {
            if (collection == null || collection.name == null) {
                continue;
            }
            OAuthAutoRefreshState autoState = oauthAutoStates.get(collection);
            if (autoState == null) {
                continue;
            }
            WorkspaceState.OAuthAutoRefreshSnapshot snapshot = new WorkspaceState.OAuthAutoRefreshSnapshot();
            snapshot.enabled = autoState.enabled;
            snapshot.intervalSeconds = autoState.intervalSeconds;
            snapshot.lastStatus = autoState.lastStatus;
            state.oauthAutoRefreshByCollection.put(collection.name, snapshot);
        }
    }

    private void restoreWorkbenchSettings(WorkspaceState state) {
        if (state == null) {
            return;
        }
        applyCheckboxState(repeaterBtn, state.workbenchRepeaterSelected, true);
        applyCheckboxState(sitemapBtn, state.workbenchSitemapSelected, false);
        applyCheckboxState(intruderBtn, state.workbenchIntruderSelected, false);
        applySpinnerState(delaySpinner, state.workbenchDelayMs, 200);
        applyCheckboxState(debugRawRequestBox, state.workbenchDebugRawRequest, false);
        applyTabIndex(workbenchDetailTabs, state.workbenchDetailTabIndex);
    }

    private void restoreRunnerSettings(WorkspaceState state) {
        if (state == null) {
            return;
        }
        applySpinnerState(runnerDelaySpinner, state.runnerDelayMs, 200);
        applySpinnerState(runnerRetriesSpinner, state.runnerRetries, 1);
        applyCheckboxState(stopOnErrorBox, state.runnerStopOnError, false);
        applyCheckboxState(stopOnAssertionFailureBox, state.runnerStopOnAssertionFailure, false);
        applyCheckboxState(stopOnStatusAtLeast400Box, state.runnerStopOnStatusAtLeast400, false);
        applyCheckboxState(stopOnMissingVariableBox, state.runnerStopOnMissingVariable, false);
        applySpinnerState(stopAfterFailuresSpinner, state.runnerStopAfterFailures, 0);
        applyCheckboxState(followRedirectsBox, state.runnerFollowRedirects, true);
        applyCheckboxState(runnerDebugRawRequestBox, state.runnerDebugRawRequest, false);
    }

    private void restoreRunnerDetailState(WorkspaceState state) {
        if (state == null) {
            return;
        }
        applyTabIndex(runnerDetailTabs, state.runnerDetailTabIndex);
    }

    private void restoreOAuthAutoRefreshState(Map<String, WorkspaceState.OAuthAutoRefreshSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        for (ApiCollection collection : loadedCollections) {
            if (collection == null || collection.name == null) {
                continue;
            }
            WorkspaceState.OAuthAutoRefreshSnapshot snapshot = snapshots.get(collection.name);
            if (snapshot == null) {
                continue;
            }
            OAuthAutoRefreshState state = getAutoState(collection);
            state.intervalSeconds = Math.max(30, snapshot.intervalSeconds != null ? snapshot.intervalSeconds : 300);
            state.lastStatus = snapshot.lastStatus;
            if (Boolean.TRUE.equals(snapshot.enabled)) {
                startAutoRefreshForCollection(collection, state.intervalSeconds);
            } else {
                if (state.future != null) {
                    state.future.cancel(false);
                    state.future = null;
                }
                state.enabled = false;
                applyAutoRefreshUiForSelectedCollection();
            }
        }
    }

    private static Integer spinnerIntValue(JSpinner spinner) {
        if (spinner == null) {
            return null;
        }
        Object value = spinner.getValue();
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static void applyCheckboxState(JCheckBox box, Boolean value, boolean defaultValue) {
        if (box != null) {
            box.setSelected(value != null ? value : defaultValue);
        }
    }

    private static void applySpinnerState(JSpinner spinner, Integer value, int defaultValue) {
        if (spinner == null) {
            return;
        }
        SpinnerNumberModel model = spinner.getModel() instanceof SpinnerNumberModel ? (SpinnerNumberModel) spinner.getModel() : null;
        int result = value != null ? value : defaultValue;
        if (model != null) {
            Object minimum = model.getMinimum();
            Object maximum = model.getMaximum();
            if (minimum instanceof Number) {
                result = Math.max(result, ((Number) minimum).intValue());
            }
            if (maximum instanceof Number) {
                result = Math.min(result, ((Number) maximum).intValue());
            }
        }
        spinner.setValue(result);
    }

    private static void applyTabIndex(JTabbedPane tabs, Integer index) {
        if (tabs == null || tabs.getTabCount() == 0) {
            return;
        }
        int clamped = index != null ? index : 0;
        clamped = Math.max(0, Math.min(clamped, tabs.getTabCount() - 1));
        tabs.setSelectedIndex(clamped);
    }

    private TreePath findRequestTreePath(DefaultMutableTreeNode node,
                                         String collectionName,
                                         String requestPath,
                                         String requestName,
                                         String currentCollectionName) {
        String nextCollectionName = currentCollectionName;
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                nextCollectionName = ctn.collection != null ? ctn.collection.name : currentCollectionName;
            } else if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST && ctn.request != null
                    && matchesRequestSelection(nextCollectionName, ctn.request, collectionName, requestPath, requestName)) {
                return new TreePath(ctn.getPath());
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TreePath path = findRequestTreePath((DefaultMutableTreeNode) node.getChildAt(i), collectionName, requestPath, requestName, nextCollectionName);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private boolean matchesRequestSelection(String nodeCollectionName,
                                            ApiRequest request,
                                            String collectionName,
                                            String requestPath,
                                            String requestName) {
        if (request == null) {
            return false;
        }
        if (collectionName != null && !Objects.equals(collectionName, nodeCollectionName)) {
            return false;
        }
        if (requestPath != null && !Objects.equals(requestPath, request.path)) {
            return false;
        }
        if (requestName != null && !Objects.equals(requestName, request.name)) {
            return false;
        }
        return true;
    }

    private void refreshCollectionCombos() {
        CollectionRef prevVars = varsCollectionCombo.getSelectedItem() != null ? (CollectionRef) varsCollectionCombo.getSelectedItem() : null;
        varsCollectionCombo.removeAllItems();
        List<CollectionRef> varRefs = buildCollectionRefs();
        for (CollectionRef ref : varRefs) {
            varsCollectionCombo.addItem(ref);
        }
        if (prevVars != null) {
            for (int i = 0; i < varsCollectionCombo.getItemCount(); i++) {
                if (prevVars.collection == varsCollectionCombo.getItemAt(i).collection) {
                    varsCollectionCombo.setSelectedIndex(i);
                    break;
                }
            }
        }

        CollectionRef prevOAuth2 = oauth2CollectionCombo.getSelectedItem() != null ? (CollectionRef) oauth2CollectionCombo.getSelectedItem() : null;
        oauth2CollectionCombo.removeAllItems();
        List<CollectionRef> oauthRefs = buildCollectionRefs();
        for (CollectionRef ref : oauthRefs) {
            oauth2CollectionCombo.addItem(ref);
        }
        if (prevOAuth2 != null) {
            for (int i = 0; i < oauth2CollectionCombo.getItemCount(); i++) {
                if (prevOAuth2.collection == oauth2CollectionCombo.getItemAt(i).collection) {
                    oauth2CollectionCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        updateScopeControlState();
        renderEffectiveVariablesForSelectedCollection();
        applyAutoRefreshUiForSelectedCollection();
        if (requestEditor != null && requestEditor.getCurrentCollection() != null) {
            syncRequestEditorRuntimeContext(requestEditor.getCurrentRequest(), requestEditor.getCurrentCollection());
        }
        if (varsCollectionCombo != null && varsCollectionCombo.getSelectedItem() != null) {
            setVarsAutosaveStatus("Autosave idle.", Color.GRAY);
        }
        if (oauth2CollectionCombo != null && oauth2CollectionCombo.getSelectedItem() != null) {
            setOAuth2AutosaveStatus("Autosave idle.", Color.GRAY);
        }
    }

    private void updateScopeControlState() {
        // Variables tab
        if (varsCollectionCombo != null && envVarsArea != null) {
            CollectionRef varsRef = varsCollectionCombo.getSelectedItem() != null ? (CollectionRef) varsCollectionCombo.getSelectedItem() : null;
            boolean varsHasTarget = varsRef != null;
            envVarsArea.setEnabled(varsHasTarget);
            envVarsArea.setEditable(varsHasTarget);
            if (varsTable != null) varsTable.setEnabled(varsHasTarget);
            if (varsRawViewBtn != null) varsRawViewBtn.setEnabled(varsHasTarget);
            if (varsTableViewBtn != null) varsTableViewBtn.setEnabled(varsHasTarget);
            if (bindVarsBtn != null) bindVarsBtn.setEnabled(varsHasTarget);
            if (varsHintLabel != null) {
                if (varsHasTarget) {
                    varsHintLabel.setText("Editing runtime overrides for: " + varsRef.label);
                    varsHintLabel.setForeground(Color.BLACK);
                } else {
                    varsHintLabel.setText("Select a collection to edit scoped variables.");
                    varsHintLabel.setForeground(Color.GRAY);
                }
            }
            if (!varsHasTarget) {
                setVarsAutosaveStatus("Select a collection to autosave variables.", Color.GRAY);
            }
        }

        // OAuth2 tab
        if (oauth2CollectionCombo != null && oauth2Panel != null) {
            CollectionRef oauth2Ref = oauth2CollectionCombo.getSelectedItem() != null ? (CollectionRef) oauth2CollectionCombo.getSelectedItem() : null;
            boolean oauth2HasTarget = oauth2Ref != null;
            oauth2Panel.setEditable(oauth2HasTarget);
            if (bindOAuth2Btn != null) bindOAuth2Btn.setEnabled(oauth2HasTarget);
            if (oauth2HintLabel != null) {
                if (oauth2HasTarget) {
                    oauth2HintLabel.setText("Binding OAuth2 to: " + oauth2Ref.label);
                    oauth2HintLabel.setForeground(Color.BLACK);
                } else {
                    oauth2HintLabel.setText("Select a collection to bind OAuth2 settings.");
                    oauth2HintLabel.setForeground(Color.GRAY);
                }
            }
            if (!oauth2HasTarget) {
                setOAuth2AutosaveStatus("Select a collection to autosave OAuth2 settings.", Color.GRAY);
            }
        }

        if (actionsBtn != null) {
            actionsBtn.setEnabled(!loadedCollections.isEmpty());
        }
        if (requestTree != null && envApplyCheckedBtn != null) {
            envApplyCheckedBtn.setEnabled(!getCheckedRequestsFromTree().isEmpty() && !loadedCollections.isEmpty());
        }
        if (requestTree != null && envApplyCheckedCollectionsBtn != null) {
            boolean hasSelectedEnv = selectedEnv != null;
            boolean hasCheckedRequests = !getCheckedRequestsFromTree().isEmpty();
            envApplyCheckedCollectionsBtn.setEnabled(hasSelectedEnv && hasCheckedRequests && !loadedCollections.isEmpty());
        }
        if (envApplyAllBtn != null) {
            envApplyAllBtn.setEnabled(selectedEnv != null && !loadedCollections.isEmpty());
        }
    }

    // ========================================================================
    // Environment Binding
    // ========================================================================
    private void selectEnvironment() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Environment files", "json", "bru"));
        if (chooser.showOpenDialog(mainPanel) == JFileChooser.APPROVE_OPTION) {
            selectedEnv = chooser.getSelectedFile();
            envField.setText(selectedEnv.getAbsolutePath());
            updateScopeControlState();
            showEnvironmentBindingDialog();
        }
    }

    private void showEnvironmentBindingDialog() {
        if (selectedEnv == null) {
            appendImportLog("No environment file selected. Browse first.");
            return;
        }
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(mainPanel), "Environment Binding", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(8, 8));

        JLabel selectedCountLabel = new JLabel("0 requests selected");
        DefaultMutableTreeNode selectionRoot = cloneRequestTreeRootForSelection();
        JTree selectionTree = buildPopupSelectionTree(selectionRoot, selectedCountLabel);
        JScrollPane treeScroll = new JScrollPane(selectionTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Select collections / requests"));
        dialog.add(treeScroll, BorderLayout.CENTER);

        JLabel envLabel = new JLabel("Env: " + selectedEnv.getAbsolutePath());
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(envLabel, BorderLayout.CENTER);
        topPanel.add(selectedCountLabel, BorderLayout.EAST);
        dialog.add(topPanel, BorderLayout.NORTH);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancelBtn = new JButton("Cancel");
        JButton applyRequestBtn = new JButton("Apply to Checked Request");
        JButton applyCollectionBtn = new JButton("Apply to Checked Collection");
        JButton applyAllBtn = new JButton("Apply to All Collection");
        cancelBtn.addActionListener(e -> dialog.dispose());
        applyRequestBtn.addActionListener(e -> {
            List<ApiRequest> checkedRequests = collectCheckedRequests((DefaultMutableTreeNode) selectionTree.getModel().getRoot());
            applyEnvToRequests(checkedRequests);
            dialog.dispose();
        });
        applyCollectionBtn.addActionListener(e -> {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) selectionTree.getModel().getRoot();
            Set<ApiCollection> selectedCollections = new LinkedHashSet<>(collectCheckedCollections(root));
            selectedCollections.addAll(resolveCheckedRequestCollections(collectCheckedRequests(root)));
            applyEnvToCollections(selectedCollections);
            dialog.dispose();
        });
        applyAllBtn.addActionListener(e -> {
            applyEnvToAllCollectionsFromPopup();
            dialog.dispose();
        });
        buttonPanel.add(cancelBtn);
        buttonPanel.add(applyRequestBtn);
        buttonPanel.add(applyCollectionBtn);
        buttonPanel.add(applyAllBtn);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setSize(720, 520);
        dialog.setLocationRelativeTo(mainPanel);
        dialog.setVisible(true);
    }

    static int applyEnvVarsToRequestVariables(ApiRequest request, Map<String, String> envVars) {
        if (request == null || envVars == null || envVars.isEmpty()) {
            return 0;
        }
        if (request.variables == null) {
            request.variables = new ArrayList<>();
        }

        Map<String, ApiRequest.Variable> byKey = new LinkedHashMap<>();
        for (ApiRequest.Variable variable : request.variables) {
            if (variable != null && variable.key != null) {
                byKey.put(variable.key, variable);
            }
        }

        int changed = 0;
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            ApiRequest.Variable variable = byKey.get(key);
            if (variable == null) {
                variable = new ApiRequest.Variable();
                variable.key = key;
                request.variables.add(variable);
                byKey.put(key, variable);
            }
            variable.value = entry.getValue();
            variable.enabled = true;
            changed++;
        }
        return changed;
    }

    private void applyEnvToRequests(List<ApiRequest> targets) {
        if (targets == null || targets.isEmpty()) {
            appendImportLog("No requests selected. Select one or more requests to bind env.");
            return;
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        UniversalImporter.EnvLoadResult result = importer.loadEnvFileIntoMap(selectedEnv, parsed);
        if (!result.isSuccess()) {
            appendImportLog("Env bind FAILED for selected requests: " + result.errorMessage);
            return;
        }
        Set<ApiCollection> affectedCollections = new LinkedHashSet<>();
        int totalApplied = 0;
        for (ApiRequest request : targets) {
            totalApplied += applyEnvVarsToRequestVariables(request, parsed);
            ApiCollection collection = requestToCollectionMap.get(request);
            if (collection == null) {
                collection = findCollectionByName(request.sourceCollection);
            }
            if (collection != null) {
                affectedCollections.add(collection);
            }
        }
        appendImportLog("Env bound to " + targets.size() + " request(s): " + totalApplied + " var(s) total.");
        for (ApiCollection collection : affectedCollections) {
            refreshRuntimeViewsForCollection(collection);
        }
        notifyWorkspaceChanged();
        renderEffectiveVariablesForSelectedCollection();
    }

    private void applyEnvToCheckedRequests() {
        if (selectedEnv == null) {
            appendImportLog("No environment file selected. Browse first.");
            return;
        }
        List<ApiRequest> targets = getCheckedRequestsFromTree();
        if (targets.isEmpty()) {
            appendImportLog("No checked request nodes. Check one or more requests, folders, or collections to bind env.");
            return;
        }
        applyEnvToRequests(targets);
    }

    private void applyEnvToCollections(Set<ApiCollection> affectedCollections) {
        if (affectedCollections.isEmpty()) {
            appendImportLog("No collections selected. Nothing to apply.");
            return;
        }
        int totalLoaded = 0;
        List<String> errors = new ArrayList<>();
        for (ApiCollection collection : affectedCollections) {
            UniversalImporter.EnvLoadResult result = importer.loadEnvFileIntoMap(selectedEnv, collection.runtimeVars);
            if (result.isSuccess()) {
                totalLoaded += result.loadedCount;
            } else {
                errors.add("\"" + collection.name + "\": " + result.errorMessage);
            }
            collection.fireChanged();
            refreshRuntimeViewsForCollection(collection);
        }
        appendImportLog("Env bound to " + affectedCollections.size() + " collection(s): " + totalLoaded + " var(s) total.");
        for (String err : errors) {
            appendImportLog("  Env bind error - " + err);
        }
        notifyWorkspaceChanged();
        renderEffectiveVariablesForSelectedCollection();
    }

    private void applyEnvToCheckedCollections() {
        if (selectedEnv == null) {
            appendImportLog("No environment file selected. Browse first.");
            return;
        }
        List<ApiRequest> targets = getCheckedRequestsFromTree();
        if (targets.isEmpty()) {
            appendImportLog("No checked request nodes. Check one or more requests, folders, or collections to bind env.");
            return;
        }
        Set<ApiCollection> affectedCollections = resolveCheckedRequestCollections(targets);
        if (affectedCollections.isEmpty()) {
            appendImportLog("No checked request nodes resolved to collections. Nothing to apply.");
            return;
        }
        applyEnvToCollections(affectedCollections);
    }

    private void applyEnvToAllCollectionsFromPopup() {
        if (selectedEnv == null) {
            appendImportLog("No environment file selected. Browse first.");
            return;
        }
        if (loadedCollections.isEmpty()) {
            appendImportLog("No collections loaded.");
            return;
        }
        int totalLoaded = 0;
        List<String> errors = new ArrayList<>();
        for (ApiCollection col : loadedCollections) {
            UniversalImporter.EnvLoadResult result = importer.loadEnvFileIntoMap(selectedEnv, col.runtimeVars);
            if (result.isSuccess()) {
                totalLoaded += result.loadedCount;
            } else {
                errors.add("\"" + col.name + "\": " + result.errorMessage);
            }
            col.fireChanged();
        }
        appendImportLog("Env bound to all " + loadedCollections.size() + " collection(s): " + totalLoaded + " var(s) total.");
        for (String err : errors) {
            appendImportLog("  Env bind error - " + err);
        }
        notifyWorkspaceChanged();
        renderEffectiveVariablesForSelectedCollection();
    }

    private void applyEnvToAllCollections() {
        if (selectedEnv == null) {
            appendImportLog("No environment file selected. Browse first.");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(mainPanel,
            "This will bind the selected environment file to ALL " + loadedCollections.size() + " collection(s). Continue?",
            "Confirm Apply to All Collections", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        applyEnvToAllCollectionsFromPopup();
    }

    private void switchVarsView(boolean tableView) {
        if (varsEditorCardPanel == null) return;
        if (tableView) {
            renderVarsTableFromRaw();
        } else {
            syncRawFromVarsTable();
            scheduleVariablesAutosave();
        }
        CardLayout cl = (CardLayout) varsEditorCardPanel.getLayout();
        cl.show(varsEditorCardPanel, tableView ? "table" : "raw");
    }

    private boolean isVarsTableViewActive() {
        return varsTableViewBtn != null && varsTableViewBtn.isSelected();
    }

    private void renderVarsTableFromRaw() {
        if (varsTableModel == null) return;
        suppressVariablesAutosave = true;
        try {
            varsTableModel.setRowCount(0);
            Map<String, String> vars = parseRuntimeOverrideFromRawText();
            List<String> keys = new ArrayList<>(vars.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                varsTableModel.addRow(new Object[]{key, vars.get(key)});
            }
        } finally {
            suppressVariablesAutosave = false;
        }
    }

    private Map<String, String> parseVarsTableMap() {
        Map<String, String> vars = new LinkedHashMap<>();
        if (varsTableModel == null) return vars;
        commitTableEdit(varsTable);
        for (int i = 0; i < varsTableModel.getRowCount(); i++) {
            String key = varsTableModel.getValueAt(i, 0) != null ? varsTableModel.getValueAt(i, 0).toString().trim() : "";
            String value = varsTableModel.getValueAt(i, 1) != null ? varsTableModel.getValueAt(i, 1).toString() : "";
            if (!key.isEmpty()) {
                vars.put(key, value);
            }
        }
        return vars;
    }

    private void syncRawFromVarsTable() {
        if (envVarsArea == null || varsTableModel == null) return;
        suppressVariablesAutosave = true;
        try {
            Map<String, String> vars = parseVarsTableMap();
            StringBuilder sb = new StringBuilder();
            if (varsBaseLayerText != null && !varsBaseLayerText.isEmpty()) {
                sb.append(varsBaseLayerText);
                if (!varsBaseLayerText.endsWith("\n")) sb.append("\n");
                sb.append("\n");
            }
            sb.append("# Runtime overrides (edits apply here)\n");
            for (Map.Entry<String, String> e : new TreeMap<>(vars).entrySet()) {
                sb.append(e.getKey()).append("=").append(e.getValue()).append("\n");
            }
            setVariablesEditorTextPreservingView(sb.toString());
        } finally {
            suppressVariablesAutosave = false;
        }
    }

    private boolean setVariablesEditorTextPreservingView(String text) {
        return setTextPreservingView(envVarsArea, text);
    }

    private static boolean setTextPreservingView(JTextArea area, String text) {
        if (area == null) {
            return false;
        }

        String nextText = text != null ? text : "";
        String currentText = area.getText();
        if (Objects.equals(currentText, nextText)) {
            return false;
        }

        int caret = Math.max(0, Math.min(area.getCaretPosition(), nextText.length()));
        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, area);
        Point viewPosition = viewport != null ? viewport.getViewPosition() : null;

        area.setText(nextText);
        area.setCaretPosition(caret);

        if (viewport != null && viewPosition != null) {
            viewport.setViewPosition(new Point(Math.max(0, viewPosition.x), Math.max(0, viewPosition.y)));
        }
        return true;
    }

    private int resolveTargetRow(JTable table) {
        if (table == null) return -1;
        int row = table.getSelectedRow();
        if (row < 0 && table.isEditing()) row = table.getEditingRow();
        return row;
    }

    private void commitTableEdit(JTable table) {
        if (table != null && table.isEditing() && table.getCellEditor() != null) {
            try {
                table.getCellEditor().stopCellEditing();
            } catch (Exception ignored) {
                try {
                    table.getCellEditor().cancelCellEditing();
                } catch (Exception ignored2) {}
            }
        }
    }

    // ========================================================================
    // Variables Tab Binding
    // ========================================================================
    private void scheduleVariablesAutosave() {
        if (suppressVariablesAutosave || shuttingDown) {
            return;
        }
        if (variablesAutosave != null) {
            variablesAutosave.restart();
        }
    }

    private void autosaveVariablesToSelectedCollection() {
        if (suppressVariablesAutosave || shuttingDown || varsCollectionCombo == null) {
            return;
        }
        CollectionRef ref = (CollectionRef) varsCollectionCombo.getSelectedItem();
        if (ref == null || ref.collection == null) {
            setVarsAutosaveStatus("Select a collection to autosave variables.", Color.GRAY);
            return;
        }
        try {
            if (isVarsTableViewActive()) {
                syncRawFromVarsTable();
            }
            Map<String, String> vars = parseRuntimeOverrideSection();
            ref.collection.replaceRuntimeVars(vars);
            setVarsAutosaveStatus("Autosaved to " + ref.label + " (" + vars.size() + " var(s)).", new Color(0, 128, 0));
        } catch (Exception ex) {
            setVarsAutosaveStatus("Autosave failed: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()), Color.RED);
        }
    }

    private void setVarsAutosaveStatus(String text, Color color) {
        if (varsAutosaveStatusLabel != null) {
            varsAutosaveStatusLabel.setText(text);
            varsAutosaveStatusLabel.setForeground(color != null ? color : Color.GRAY);
        }
    }

    private void setOAuth2AutosaveStatus(String text, Color color) {
        if (oauth2AutosaveStatusLabel != null) {
            oauth2AutosaveStatusLabel.setText(text);
            oauth2AutosaveStatusLabel.setForeground(color != null ? color : Color.GRAY);
        }
    }

    private void bindVarsToSelectedCollection() {
        CollectionRef ref = (CollectionRef) varsCollectionCombo.getSelectedItem();
        if (ref == null) {
            appendImportLog("Variables: No collection selected for binding.");
            setVarsAutosaveStatus("Select a collection to autosave variables.", Color.GRAY);
            return;
        }
        ApiCollection col = ref.collection;
        if (variablesAutosave != null) {
            variablesAutosave.stop();
        }
        Map<String, String> vars = parseRuntimeOverrideSection();
        col.replaceRuntimeVars(vars);
        appendImportLog("Variables bound to \"" + ref.label + "\": " + vars.size() + " var(s).");
        setVarsAutosaveStatus("Saved to " + ref.label + " (" + vars.size() + " var(s)).", new Color(0, 128, 0));
        renderEffectiveVariablesForSelectedCollection();
        syncRequestEditorRuntimeContext(requestEditor.getCurrentRequest(), requestEditor.getCurrentCollection());
    }

    private void bindVarsToAllCollections() {
        if (loadedCollections.isEmpty()) {
            appendImportLog("Variables: No collections loaded.");
            return;
        }
        if (variablesAutosave != null) {
            variablesAutosave.stop();
        }
        int confirm = JOptionPane.showConfirmDialog(mainPanel,
            "This will overwrite scoped variables in ALL " + loadedCollections.size() + " collection(s) with the current text. Continue?",
            "Confirm Apply to All Collections", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        Map<String, String> vars = parseRuntimeOverrideSection();
        for (ApiCollection col : loadedCollections) {
            col.replaceRuntimeVars(vars);
        }
        appendImportLog("Variables bound to all " + loadedCollections.size() + " collection(s): " + vars.size() + " var(s).");
        renderEffectiveVariablesForSelectedCollection();
        syncRequestEditorRuntimeContext(requestEditor.getCurrentRequest(), requestEditor.getCurrentCollection());
        setVarsAutosaveStatus("Saved to all " + loadedCollections.size() + " collection(s).", new Color(0, 128, 0));
    }

    // ========================================================================
    // Import / Destination Flow
    // ========================================================================
    private void showActionsDialog() {
        if (loadedCollections.isEmpty()) {
            appendImportLog("No collections loaded.");
            return;
        }
        persistCurrentRequestEditorState();
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(mainPanel), "Options", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(8, 8));

        JLabel selectedCountLabel = new JLabel("0 requests selected");
        DefaultMutableTreeNode selectionRoot = cloneRequestTreeRootForSelection();
        JTree selectionTree = buildPopupSelectionTree(selectionRoot, selectedCountLabel);
        JScrollPane treeScroll = new JScrollPane(selectionTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Select requests"));
        dialog.add(treeScroll, BorderLayout.CENTER);

        JCheckBox popupRepeater = new JCheckBox("Repeater", repeaterBtn != null && repeaterBtn.isSelected());
        JCheckBox popupSitemap = new JCheckBox("Sitemap (Live)", sitemapBtn != null && sitemapBtn.isSelected());
        JCheckBox popupIntruder = new JCheckBox("Intruder", intruderBtn != null && intruderBtn.isSelected());
        JSpinner popupDelay = new JSpinner(new SpinnerNumberModel(delaySpinner != null ? spinnerIntValue(delaySpinner) : 200, 0, 5000, 50));
        popupDelay.setPreferredSize(new Dimension(80, 22));

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT));
        options.add(popupRepeater);
        options.add(popupSitemap);
        options.add(popupIntruder);
        options.add(Box.createHorizontalStrut(10));
        options.add(new JLabel("Delay (ms):"));
        options.add(popupDelay);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(options, BorderLayout.CENTER);
        topPanel.add(selectedCountLabel, BorderLayout.EAST);
        dialog.add(topPanel, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        JButton importChecked = new JButton("Import Checked");
        JButton runChecked = new JButton("Run Checked");
        importChecked.setEnabled(false);
        runChecked.setEnabled(false);

        Runnable updateActionsState = () -> {
            int checkedRequests = collectCheckedRequests((DefaultMutableTreeNode) selectionTree.getModel().getRoot()).size();
            boolean hasRequest = checkedRequests > 0;
            boolean hasDestination = popupRepeater.isSelected() || popupSitemap.isSelected() || popupIntruder.isSelected();
            importChecked.setEnabled(hasRequest && hasDestination);
            runChecked.setEnabled(hasRequest);
        };
        popupRepeater.addActionListener(e -> updateActionsState.run());
        popupSitemap.addActionListener(e -> updateActionsState.run());
        popupIntruder.addActionListener(e -> updateActionsState.run());
        selectionTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                updateActionsState.run();
            }
        });
        updateActionsState.run();

        cancel.addActionListener(e -> dialog.dispose());
        importChecked.addActionListener(e -> {
            List<ApiRequest> selected = collectCheckedRequests((DefaultMutableTreeNode) selectionTree.getModel().getRoot());
            List<String> destinations = new ArrayList<>();
            if (popupRepeater.isSelected()) destinations.add("repeater");
            if (popupSitemap.isSelected()) destinations.add("sitemap");
            if (popupIntruder.isSelected()) destinations.add("intruder");
            if (repeaterBtn != null) repeaterBtn.setSelected(popupRepeater.isSelected());
            if (sitemapBtn != null) sitemapBtn.setSelected(popupSitemap.isSelected());
            if (intruderBtn != null) intruderBtn.setSelected(popupIntruder.isSelected());
            if (delaySpinner != null) delaySpinner.setValue(popupDelay.getValue());
            startImport(selected, destinations, (Integer) popupDelay.getValue());
            dialog.dispose();
        });
        runChecked.addActionListener(e -> {
            List<ApiRequest> selected = collectCheckedRequests((DefaultMutableTreeNode) selectionTree.getModel().getRoot());
            queueRunnerRequests(selected);
            dialog.dispose();
        });
        buttons.add(cancel);
        buttons.add(importChecked);
        buttons.add(runChecked);
        dialog.add(buttons, BorderLayout.SOUTH);

        dialog.setSize(780, 560);
        dialog.setLocationRelativeTo(mainPanel);
        dialog.setVisible(true);
    }

    private void startImport(List<ApiRequest> selected, List<String> destinations, int delay) {
        if (requestEditor != null) {
            requestEditor.commitAllEdits();
        }
        if (selected.isEmpty()) {
            appendImportLog("No requests selected.");
            return;
        }
        if (destinations.isEmpty()) {
            appendImportLog("No destination selected.");
            return;
        }
        List<UnresolvedVariableIssue> issues = collectUnresolvedVariableIssues(loadedCollections, selected);
        if (!issues.isEmpty()) {
            List<ApiCollection> targetCollections = collectCollectionsForRequests(loadedCollections, selected);
            UnresolvedVariablesDialog.Action action = showUnresolvedVariablesDialog(issues, targetCollections);
            if (action == UnresolvedVariablesDialog.Action.CANCEL) {
                appendImportLog("Import cancelled due to unresolved variables.");
                return;
            }
        }
        importer.setDebugRawRequest(debugRawRequestBox.isSelected());

        // Build deterministic queue preserving collection order + request order
        List<UniversalImporter.QueuedRequest> queue = new ArrayList<>();
        for (ApiRequest req : selected) {
            ApiCollection col = requestToCollectionMap.get(req);
            if (col != null) {
                queue.add(new UniversalImporter.QueuedRequest(col, req));
            }
        }

        importer.importRequestsSequential(queue, destinations, delay,
            this::appendImportLog,
            result -> SwingUtilities.invokeLater(() -> {
                if (importProgress != null) {
                    importProgress.setValue(100);
                }
                appendImportLog("Import complete: " + result.successCount + "/" + result.totalRequests + " succeeded.");
            })
        );
    }

    private void queueRunnerRequests(List<ApiRequest> selected) {
        if (requestEditor != null) {
            requestEditor.commitAllEdits();
        }
        if (selected.isEmpty()) {
            appendImportLog("No requests selected to run.");
            return;
        }
        runnerQueuedRequests.clear();
        runnerQueuedRequests.addAll(selected);
        switchToTabByName("Collection Runner");
        appendRunnerLog(selected.size() + " requests queued in runner. Configure settings and press Start.");
    }

    private void switchToTabByName(String name) {
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (name.equals(tabbedPane.getTitleAt(i))) {
                tabbedPane.setSelectedIndex(i);
                return;
            }
        }
    }

    // ========================================================================
    // Runner
    // ========================================================================
    private void startRunner(boolean showPreviewDialog) {
        List<ApiRequest> selected = new ArrayList<>(runnerQueuedRequests);
        if (selected.isEmpty() || loadedCollections.isEmpty()) {
            appendRunnerLog("No requests queued. Use Workbench > Actions > Run Checked first.");
            return;
        }

        List<UnresolvedVariableIssue> issues = collectUnresolvedVariableIssues(loadedCollections, selected);
        if (!issues.isEmpty()) {
            List<ApiCollection> targetCollections = collectCollectionsForRequests(loadedCollections, selected);
            UnresolvedVariablesDialog.Action action = showUnresolvedVariablesDialog(issues, targetCollections);
            if (action == UnresolvedVariablesDialog.Action.CANCEL) {
                appendRunnerLog("Runner cancelled due to unresolved variables.");
                return;
            }
        }

        List<RunnerPreviewRow> previewRows = runner.buildRunPreview(loadedCollections, selected);
        if (previewRows.isEmpty()) {
            appendRunnerLog("No runnable requests found.");
            return;
        }

        if (showPreviewDialog || hasRunnerPreviewWarnings(previewRows)) {
            if (!showRunnerPreviewDialog(previewRows, showPreviewDialog)) {
                appendRunnerLog("Runner preview cancelled.");
                return;
            }
        }

        startRunnerExecution(selected);
    }

    private void startRunnerExecution(List<ApiRequest> selected) {
        runner.setDelayMs((Integer) runnerDelaySpinner.getValue());
        runner.setMaxRetries((Integer) runnerRetriesSpinner.getValue());
        runner.setStopConditions(buildRunnerStopConditionsFromUi());
        runner.setFollowRedirects(followRedirectsBox.isSelected());
        runner.setDebugRawRequest(runnerDebugRawRequestBox.isSelected());

        resultModel.clear();
        timelineModel.clear();
        runnerLog.setText("");
        runnerProgress.setValue(0);

        if (activeRunnerListener != null) {
            runner.removeListener(activeRunnerListener);
        }
        activeRunnerListener = new CollectionRunner.RunnerListener() {
            @Override public void onStart(String name, int total) {
                SwingUtilities.invokeLater(() -> {
                    appendRunnerLog("Starting runner (" + total + " requests from " +
                        loadedCollections.size() + " collection(s))");
                    setRunnerControlsRunning(true);
                    startRunnerBtn.setEnabled(false);
                    cancelRunnerBtn.setEnabled(true);
                    runnerProgress.setMaximum(total);
                });
            }
            @Override public void onSkip(String name, String reason) {
                SwingUtilities.invokeLater(() -> appendRunnerLog("Skipped: " + name + " (" + reason + ")"));
            }
            @Override public void onRequestComplete(RunnerResult result) {
                SwingUtilities.invokeLater(() -> {
                    resultModel.addResult(result);
                    runnerProgress.setValue(resultModel.getRowCount());
                    String status = result.success ? "OK " + result.statusCode : "FAIL " + result.errorMessage;
                    appendRunnerLog((resultModel.getRowCount()) + ". " + result.requestName + " -> " + status);
                    if (!result.extractedVariables.isEmpty()) {
                        appendRunnerLog("   Extracted: " + result.extractedVariables);
                    }
                    setRunnerControlsRunning(runner.isRunning());
                });
            }
            @Override public void onTimeline(RunnerTimelineRow row) {
                SwingUtilities.invokeLater(() -> timelineModel.addRow(row));
            }
            @Override public void onComplete(List<RunnerResult> results) {
                SwingUtilities.invokeLater(() -> {
                    long success = results.stream().filter(r -> r.success).count();
                    appendRunnerLog("\n=== Runner Complete ===");
                    appendRunnerLog("Success: " + success + "/" + results.size());
                    appendRunnerLog("Total extracted vars: " + runner.getExtractedVariables().size());
                    setRunnerControlsRunning(false);
                    startRunnerBtn.setEnabled(true);
                    cancelRunnerBtn.setEnabled(false);
                });
            }
            @Override public void onDebug(String message) {
                SwingUtilities.invokeLater(() -> appendRunnerLog(message));
            }
            @Override public void onError(String message) {
                SwingUtilities.invokeLater(() -> {
                    appendRunnerLog("ERROR: " + message);
                    setRunnerControlsRunning(false);
                    startRunnerBtn.setEnabled(true);
                    cancelRunnerBtn.setEnabled(false);
                });
            }
        };
        runner.addListener(activeRunnerListener);

        runner.runCollections(loadedCollections, selected);
    }

    private RunnerStopConditions buildRunnerStopConditionsFromUi() {
        RunnerStopConditions stopConditions = new RunnerStopConditions();
        stopConditions.stopOnError = stopOnErrorBox != null && stopOnErrorBox.isSelected();
        stopConditions.stopOnAssertionFailure = stopOnAssertionFailureBox != null && stopOnAssertionFailureBox.isSelected();
        stopConditions.stopOnStatusAtLeast400 = stopOnStatusAtLeast400Box != null && stopOnStatusAtLeast400Box.isSelected();
        stopConditions.stopOnMissingVariable = stopOnMissingVariableBox != null && stopOnMissingVariableBox.isSelected();
        stopConditions.stopAfterFailureCount = stopAfterFailuresSpinner != null
            ? (Integer) stopAfterFailuresSpinner.getValue()
            : 0;
        return stopConditions;
    }

    private void setRunnerControlsRunning(boolean running) {
        boolean hasCollections = !loadedCollections.isEmpty();
        boolean paused = running && runner != null && runner.isPaused();
        if (startRunnerBtn != null) {
            startRunnerBtn.setEnabled(!running && hasCollections);
        }
        if (cancelRunnerBtn != null) {
            cancelRunnerBtn.setEnabled(running);
        }
        if (pauseRunnerBtn != null) {
            pauseRunnerBtn.setEnabled(running && !paused);
        }
        if (resumeRunnerBtn != null) {
            resumeRunnerBtn.setEnabled(running && paused);
        }
        if (stepRunnerBtn != null) {
            stepRunnerBtn.setEnabled(running && paused);
        }
    }

    private void pauseRunnerFromUi() {
        appendRunnerLog("Runner will pause after current request.");
        runner.pauseAfterCurrent();
        setRunnerControlsRunning(runner.isRunning());
    }

    private void resumeRunnerFromUi() {
        appendRunnerLog("Runner resumed.");
        runner.resume();
        setRunnerControlsRunning(runner.isRunning());
    }

    private void stepRunnerFromUi() {
        appendRunnerLog("Runner stepping one request.");
        runner.runNextOnly();
        setRunnerControlsRunning(runner.isRunning());
    }

    private void cancelRunnerFromUi() {
        if (cancelRunnerBtn != null) {
            cancelRunnerBtn.setEnabled(false);
        }
        appendRunnerLog("Runner cancellation requested.");
        runner.cancel();

        if (runnerCancelPollTimer != null) {
            runnerCancelPollTimer.stop();
        }
        runnerCancelPollTimer = new javax.swing.Timer(150, e -> {
            if (runner.isRunning()) {
                return;
            }
            javax.swing.Timer timer = (javax.swing.Timer) e.getSource();
            timer.stop();
            if (runnerCancelPollTimer == timer) {
                runnerCancelPollTimer = null;
            }
            restoreRunnerControlsAfterCancellation();
        });
        runnerCancelPollTimer.setRepeats(true);
        runnerCancelPollTimer.start();
    }

    private void restoreRunnerControlsAfterCancellation() {
        setRunnerControlsRunning(false);
        boolean hasCollections = !loadedCollections.isEmpty();
        if (startRunnerBtn != null) {
            startRunnerBtn.setEnabled(hasCollections);
        }
    }

    private boolean hasRunnerPreviewWarnings(List<RunnerPreviewRow> previewRows) {
        for (RunnerPreviewRow row : previewRows) {
            if (row != null && row.unresolvedVariables != null && !row.unresolvedVariables.isEmpty()) {
                return true;
            }
            if (isRunnerPreviewMissingAuth(row)) {
                return true;
            }
        }
        return false;
    }

    private boolean showRunnerPreviewDialog(List<RunnerPreviewRow> previewRows, boolean previewRequested) {
        if (previewRows == null || previewRows.isEmpty()) {
            return true;
        }

        if (runnerPreviewModel == null) {
            runnerPreviewModel = new RunnerPreviewTableModel();
        }
        runnerPreviewModel.setRows(previewRows);

        JTable previewTable = new JTable(runnerPreviewModel);
        previewTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        previewTable.setFillsViewportHeight(true);
        previewTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane previewScroll = new JScrollPane(previewTable);
        previewScroll.setPreferredSize(new Dimension(900, 280));

        int unresolvedCount = 0;
        int missingAuthCount = 0;
        for (RunnerPreviewRow row : previewRows) {
            if (row != null && row.unresolvedVariables != null && !row.unresolvedVariables.isEmpty()) {
                unresolvedCount++;
            }
            if (isRunnerPreviewMissingAuth(row)) {
                missingAuthCount++;
            }
        }

        String message;
        if (previewRequested) {
            message = "Review the runner preview before starting.";
        } else if (unresolvedCount > 0 || missingAuthCount > 0) {
            message = "Runner preview found " + unresolvedCount + " request(s) with unresolved variables and " +
                missingAuthCount + " request(s) with missing auth.";
        } else {
            message = "Runner preview is ready. Continue to start the run?";
        }

        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(mainPanel), "Runner Preview", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel messageLabel = new JLabel("<html>" + message + "</html>");
        content.add(messageLabel, BorderLayout.NORTH);
        content.add(previewScroll, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton continueBtn = new JButton("Start Runner");
        JButton cancelBtn = new JButton("Cancel");
        final boolean[] accepted = {false};
        continueBtn.addActionListener(e -> {
            accepted[0] = true;
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());
        buttonPanel.add(cancelBtn);
        buttonPanel.add(continueBtn);
        content.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(mainPanel);
        dialog.setVisible(true);
        return accepted[0];
    }

    // ========================================================================
    // Populate OAuth2 from selected request
    // ========================================================================
    private void populateOAuth2FromRequest() {
        if (loadedCollections.isEmpty()) {
            appendImportLog("Populate OAuth2: No collections loaded.");
            return;
        }
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(mainPanel), "Populate OAuth2 from Request", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(8, 8));
        JLabel selectedCountLabel = new JLabel("0 requests selected");
        DefaultMutableTreeNode selectionRoot = cloneRequestTreeRootForSelection();
        JTree selectionTree = buildPopupSelectionTree(selectionRoot, selectedCountLabel);
        JScrollPane treeScroll = new JScrollPane(selectionTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Select exactly one request"));
        dialog.add(treeScroll, BorderLayout.CENTER);
        dialog.add(selectedCountLabel, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        JButton populate = new JButton("Populate");
        cancel.addActionListener(e -> dialog.dispose());
        populate.addActionListener(e -> {
            List<ApiRequest> selected = collectCheckedRequests((DefaultMutableTreeNode) selectionTree.getModel().getRoot());
            if (selected.size() != 1) {
                JOptionPane.showMessageDialog(dialog, "Select exactly one request.", "Selection Required", JOptionPane.WARNING_MESSAGE);
                return;
            }
            populateOAuth2FromRequest(selected.get(0));
            dialog.dispose();
        });
        buttons.add(cancel);
        buttons.add(populate);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(700, 520);
        dialog.setLocationRelativeTo(mainPanel);
        dialog.setVisible(true);
    }

    private void populateOAuth2FromRequest(ApiRequest req) {
        if (req == null) {
            appendImportLog("Populate OAuth2: Request selection is empty.");
            return;
        }
        ApiCollection owningCollection = requestToCollectionMap.get(req);
        if (owningCollection == null) {
            owningCollection = findCollectionByName(req.sourceCollection);
        }
        if (owningCollection != null) {
            selectOAuth2Collection(owningCollection);
        }

        VariableResolver populateResolver = buildOAuth2PopulateResolver(owningCollection, req);
        Map<String, String> extracted = burp.utils.OAuth2PopulateHelper.extractOAuth2Fields(req, populateResolver);
        if (extracted.isEmpty()) {
            appendImportLog("Populate OAuth2: Selected request has no OAuth2-relevant data.");
            return;
        }

        Map<String, String> existing = owningCollection != null
                ? buildOAuth2PopulateExistingVars(owningCollection)
                : parseEnvVarsMap();
        Map<String, String> merged = burp.utils.OAuth2PopulateHelper.mergeWithExisting(extracted, existing);
        oauth2Panel.populateFromOAuth2Map(merged);

        String collectionName = owningCollection != null && owningCollection.name != null ? owningCollection.name : "unknown collection";
        appendImportLog("Populate OAuth2: Filled " + extracted.size() + " field(s) from request \"" + req.name
                + "\" using collection \"" + collectionName + "\".");

        List<String> unresolved = collectUnresolvedOAuth2PopulateVariables(extracted);
        if (!unresolved.isEmpty()) {
            appendImportLog("Populate OAuth2: Unresolved variable(s) remain: " + String.join(", ", unresolved) + ".");
        }
    }

    private boolean selectOAuth2Collection(ApiCollection collection) {
        if (oauth2CollectionCombo == null || collection == null) {
            return false;
        }
        for (int i = 0; i < oauth2CollectionCombo.getItemCount(); i++) {
            CollectionRef ref = oauth2CollectionCombo.getItemAt(i);
            if (ref != null && ref.collection == collection) {
                oauth2CollectionCombo.setSelectedIndex(i);
                return true;
            }
        }
        return false;
    }

    static VariableResolver buildOAuth2PopulateResolver(ApiCollection collection, ApiRequest request) {
        VariableResolver resolver = new VariableResolver();
        if (collection != null) {
            resolver.addEnvironmentVariables(collection);
            resolver.addCollectionVariables(collection);
            resolver.addFolderVariables(collection, request);
            if (collection.runtimeOAuth2 != null) {
                resolver.addAll(collection.runtimeOAuth2);
            }
            if (collection.runtimeVars != null) {
                resolver.addAll(collection.runtimeVars);
            }
        }
        if (request != null) {
            resolver.addRequestVariables(request);
        }
        return resolver;
    }

    static List<String> collectUnresolvedOAuth2PopulateVariables(Map<String, String> fields) {
        Set<String> unresolved = new TreeSet<>();
        if (fields == null || fields.isEmpty()) {
            return new ArrayList<>();
        }
        VariableResolver resolver = new VariableResolver();
        for (String value : fields.values()) {
            if (value == null || value.isBlank()) {
                continue;
            }
            unresolved.addAll(resolver.findUnresolvedVariables(value));
        }
        return new ArrayList<>(unresolved);
    }

    static Map<String, String> buildOAuth2PopulateExistingVars(ApiCollection collection) {
        Map<String, String> existing = new LinkedHashMap<>();
        if (collection == null) {
            return existing;
        }
        if (collection.environment != null) {
            existing.putAll(collection.environment);
        }
        if (collection.variables != null) {
            for (ApiRequest.Variable variable : collection.variables) {
                if (variable != null && variable.enabled && variable.key != null && variable.value != null) {
                    existing.put(variable.key, variable.value);
                }
            }
        }
        if (collection.runtimeOAuth2 != null) {
            existing.putAll(collection.runtimeOAuth2);
        }
        if (collection.runtimeVars != null) {
            existing.putAll(collection.runtimeVars);
        }
        return existing;
    }

    private ApiCollection findCollectionByName(String name) {
        if (name == null) {
            return null;
        }
        for (ApiCollection collection : loadedCollections) {
            if (collection != null && Objects.equals(name, collection.name)) {
                return collection;
            }
        }
        return null;
    }

    private OAuthAutoRefreshState getAutoState(ApiCollection col) {
        OAuthAutoRefreshState state = oauthAutoStates.get(col);
        if (state == null) {
            state = new OAuthAutoRefreshState();
            oauthAutoStates.put(col, state);
        }
        return state;
    }

    private ApiCollection getSelectedOAuth2Collection() {
        CollectionRef ref = (CollectionRef) oauth2CollectionCombo.getSelectedItem();
        return ref != null ? ref.collection : null;
    }

    private void applyAutoRefreshUiForSelectedCollection() {
        ApiCollection col = getSelectedOAuth2Collection();
        if (col == null) {
            oauth2Panel.setAutoRefreshIntervalSeconds(300);
            oauth2Panel.setAutoRefreshActive(false);
            return;
        }
        OAuthAutoRefreshState state = getAutoState(col);
        oauth2Panel.setAutoRefreshIntervalSeconds(state.intervalSeconds);
        oauth2Panel.setAutoRefreshActive(state.enabled);
    }

    private void toggleAutoRefreshForSelectedCollection() {
        ApiCollection col = getSelectedOAuth2Collection();
        if (col == null) {
            appendImportLog("OAuth2 auto-refresh: no target collection selected.");
            oauth2Panel.appendStatus("OAuth2 auto-refresh: no target collection selected.");
            return;
        }
        OAuthAutoRefreshState state = getAutoState(col);
        if (state.enabled) {
            stopAutoRefreshForCollection(col, "Stopped by user");
            return;
        }
        int interval = Math.max(30, oauth2Panel.getAutoRefreshIntervalSeconds());
        startAutoRefreshForCollection(col, interval);
    }

    private void startAutoRefreshForCollection(ApiCollection col, int intervalSeconds) {
        OAuthAutoRefreshState state = getAutoState(col);
        if (state.future != null && !state.future.isDone()) {
            state.future.cancel(false);
        }

        Map<String, String> vars = col.runtimeOAuth2 != null ? new HashMap<>(col.runtimeOAuth2) : new HashMap<>();
        OAuth2Config cfg = OAuth2Config.fromVariables(vars);
        if (cfg.tokenUrl == null || cfg.tokenUrl.isEmpty() || cfg.clientId == null || cfg.clientId.isEmpty()) {
            String msg = "OAuth2 auto-refresh not started for \"" + col.name + "\": missing oauth2_token_url or oauth2_client_id.";
            appendImportLog(msg);
            oauth2Panel.appendStatus(msg);
            state.enabled = false;
            applyAutoRefreshUiForSelectedCollection();
            return;
        }

        String refresh = vars.get("oauth2_refresh_token");
        if (refresh == null || refresh.isEmpty()) {
            String key = TokenStore.makeKey(cfg);
            TokenStore.TokenEntry entry = TokenStore.get(key);
            if (entry != null && entry.refreshToken != null && !entry.refreshToken.isEmpty()) {
                refresh = entry.refreshToken;
                col.putRuntimeOAuth2("oauth2_refresh_token", refresh);
            }
        }
        if (refresh == null || refresh.isEmpty()) {
            String msg = "OAuth2 auto-refresh not started for \"" + col.name + "\": missing refresh token.";
            appendImportLog(msg);
            oauth2Panel.appendStatus(msg);
            state.enabled = false;
            applyAutoRefreshUiForSelectedCollection();
            return;
        }

        state.intervalSeconds = intervalSeconds;
        state.enabled = true;
        state.lastStatus = "Running";
        state.future = oauthAutoExecutor.scheduleWithFixedDelay(
            () -> runAutoRefreshTick(col), intervalSeconds, intervalSeconds, TimeUnit.SECONDS);

        String msg = "OAuth2 auto-refresh started for \"" + col.name + "\" (" + intervalSeconds + "s interval).";
        appendImportLog(msg);
        oauth2Panel.appendStatus(msg);
        applyAutoRefreshUiForSelectedCollection();
    }

    private void runAutoRefreshTick(ApiCollection col) {
        OAuthAutoRefreshState state = getAutoState(col);
        if (!state.enabled) return;
        try {
            Map<String, String> vars = new HashMap<>();
            if (col.runtimeOAuth2 != null) vars.putAll(col.runtimeOAuth2);
            OAuth2Config cfg = OAuth2Config.fromVariables(vars);

            String refresh = vars.get("oauth2_refresh_token");
            if ((refresh == null || refresh.isEmpty()) && cfg.clientId != null && cfg.tokenUrl != null) {
                TokenStore.TokenEntry existing = TokenStore.get(TokenStore.makeKey(cfg));
                if (existing != null && existing.refreshToken != null && !existing.refreshToken.isEmpty()) {
                    refresh = existing.refreshToken;
                }
            }
            if (refresh == null || refresh.isEmpty()) {
                state.lastStatus = "Refresh token unavailable";
                logAutoRefreshMessage(col, "Auto-refresh skipped: refresh token unavailable.");
                return;
            }

            cfg.refreshToken = refresh;
            cfg.grantType = OAuth2Config.GrantType.REFRESH_TOKEN;
            if (!cfg.isValid()) {
                state.lastStatus = "Invalid config for refresh";
                logAutoRefreshMessage(col, "Auto-refresh skipped: invalid config for refresh_token grant.");
                return;
            }

            TokenStore.TokenEntry entry = oauth2Manager.acquireToken(cfg);
            Map<String, String> update = new HashMap<>();
            if (entry.accessToken != null && !entry.accessToken.isEmpty()) {
                update.put("oauth2_access_token", entry.accessToken);
            }
            if (entry.refreshToken != null && !entry.refreshToken.isEmpty()) {
                update.put("oauth2_refresh_token", entry.refreshToken);
            }
            if (!update.isEmpty()) {
                col.putAllRuntimeOAuth2(update);
            }
            state.lastStatus = "Last refresh OK";
            logAutoRefreshMessage(col, "Auto-refresh success. Next run in ~" + state.intervalSeconds + "s.");
        } catch (Exception ex) {
            state.lastStatus = "Refresh failed: " + ex.getMessage();
            logAutoRefreshMessage(col, "Auto-refresh failed: " + ex.getMessage());
        }
    }

    private void logAutoRefreshMessage(ApiCollection col, String message) {
        SwingUtilities.invokeLater(() -> {
            String msg = "OAuth2 [" + col.name + "]: " + message;
            appendImportLog(msg);
            CollectionRef ref = (CollectionRef) oauth2CollectionCombo.getSelectedItem();
            if (ref != null && ref.collection == col) {
                oauth2Panel.appendStatus(msg);
            }
        });
    }

    private void stopAutoRefreshForCollection(ApiCollection col, String reason) {
        OAuthAutoRefreshState state = getAutoState(col);
        state.enabled = false;
        if (state.future != null) {
            state.future.cancel(false);
            state.future = null;
        }
        state.lastStatus = reason;
        String msg = "OAuth2 auto-refresh stopped for \"" + col.name + "\" (" + reason + ").";
        appendImportLog(msg);
        oauth2Panel.appendStatus(msg);
        applyAutoRefreshUiForSelectedCollection();
    }

    public void cleanup() {
        shuttingDown = true;
        if (variablesAutosave != null) {
            variablesAutosave.stop();
        }
        suppressVariablesAutosave = true;
        try {
            persistVariablesEditorStateSilently();
        } finally {
            suppressVariablesAutosave = false;
        }
        persistCurrentRequestEditorState();
        for (ApiCollection col : new ArrayList<>(oauthAutoStates.keySet())) {
            OAuthAutoRefreshState state = oauthAutoStates.get(col);
            if (state != null && state.future != null) {
                state.future.cancel(false);
            }
        }
        oauthAutoStates.clear();
        oauthAutoExecutor.shutdownNow();
    }

    private void persistVariablesEditorStateSilently() {
        if (varsCollectionCombo == null) {
            return;
        }
        CollectionRef ref = (CollectionRef) varsCollectionCombo.getSelectedItem();
        if (ref == null || ref.collection == null) {
            return;
        }
        Map<String, String> vars = parseRuntimeOverrideSection();
        silentlyReplaceRuntimeVars(ref.collection, vars);
    }

    private void persistCurrentRequestEditorState() {
        if (requestEditor == null) {
            return;
        }
        ApiRequest liveRequest = requestEditor.getCurrentRequest();
        ApiCollection collection = requestEditor.getCurrentCollection();
        if (liveRequest == null || collection == null) {
            return;
        }
        ApiRequest edited = requestEditor.buildRequestFromUI();
        if (edited == null) {
            return;
        }
        applyEditedRequestToLiveRequest(collection, liveRequest, edited);
        syncRequestEditorRuntimeContext(liveRequest, collection);
    }

    static void silentlyReplaceRuntimeVars(ApiCollection collection, Map<String, String> vars) {
        if (collection == null) {
            return;
        }
        Map<String, String> current = collection.runtimeVars;
        Map<String, String> next = vars != null ? new LinkedHashMap<>(vars) : new LinkedHashMap<>();
        if (current == null) {
            collection.runtimeVars = new LinkedHashMap<>(next);
            return;
        }
        if (current.equals(next)) {
            return;
        }
        current.clear();
        current.putAll(next);
    }

    // ========================================================================
    // Utility
    // ========================================================================
    private void syncRequestEditorRuntimeContext(ApiRequest req, ApiCollection col) {
        if (requestEditor == null || req == null || col == null) {
            if (requestEditor != null) {
                requestEditor.setRuntimeVariables(Collections.emptyMap());
            }
            return;
        }
        VariableResolver vr = new VariableResolver();
        vr.addEnvironmentVariables(col);
        vr.addCollectionVariables(col);
        vr.addFolderVariables(col, req);
        if (col.runtimeOAuth2 != null) vr.addAll(col.runtimeOAuth2);
        if (col.runtimeVars != null) vr.addAll(col.runtimeVars);
        vr.addRequestVariables(req);
        if (req.hasAuth()) {
            Map<String, String> authMapped = burp.utils.OAuth2RuntimeMapper.mapAuthToVars(req.auth, vr.getVariables(), true);
            if (!authMapped.isEmpty()) {
                vr.addAll(authMapped);
            }
        }
        requestEditor.setRuntimeVariables(vr.getVariables());
    }

    private Map<String, String> parseEnvVarsMap() {
        if (isVarsTableViewActive()) {
            syncRawFromVarsTable();
        }
        Map<String, String> vars = new HashMap<>();
        String text = envVarsArea.getText();
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : obj.entrySet()) {
                vars.put(entry.getKey(), entry.getValue().getAsString());
            }
            return vars;
        } catch (Exception e) {
            // Not JSON, parse as key=value lines
        }
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eqIdx = line.indexOf('=');
            if (eqIdx > 0) {
                vars.put(line.substring(0, eqIdx).trim(), line.substring(eqIdx + 1).trim());
            }
        }
        return vars;
    }

    private Map<String, String> parseRuntimeOverrideFromRawText() {
        Map<String, String> vars = new HashMap<>();
        String text = envVarsArea.getText();
        String marker = "# Runtime overrides (edits apply here)";
        int idx = text.indexOf(marker);
        if (idx >= 0) {
            text = text.substring(idx + marker.length());
        }
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : obj.entrySet()) {
                vars.put(entry.getKey(), entry.getValue().getAsString());
            }
            return vars;
        } catch (Exception e) {
            // Not JSON, parse as key=value lines
        }
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eqIdx = line.indexOf('=');
            if (eqIdx > 0) {
                vars.put(line.substring(0, eqIdx).trim(), line.substring(eqIdx + 1).trim());
            }
        }
        return vars;
    }

    /**
     * Parses only the "# Runtime overrides" section from the Variables tab.
     * If the marker is absent, falls back to full-text parsing for backward compatibility.
     */
    private Map<String, String> parseRuntimeOverrideSection() {
        if (isVarsTableViewActive()) {
            return parseVarsTableMap();
        }
        return parseRuntimeOverrideFromRawText();
    }

    public void appendImportLog(String msg) {
        SwingUtilities.invokeLater(() -> {
            importLog.append(msg + "\n");
            importLog.setCaretPosition(importLog.getDocument().getLength());
        });
    }

    public void appendRunnerLog(String msg) {
        SwingUtilities.invokeLater(() -> {
            runnerLog.append(msg + "\n");
            runnerLog.setCaretPosition(runnerLog.getDocument().getLength());
        });
    }

    private void updateRunnerDetailPane(RunnerResult r) {
        if (r == null) {
            if (detailRequestEditor != null) detailRequestEditor.setRequest(HttpRequest.httpRequest());
            if (detailResponseEditor != null) detailResponseEditor.setResponse(HttpResponse.httpResponse());
            detailVarsText.setText("");
            return;
        }
        StringBuilder req = new StringBuilder();
        req.append(r.method != null ? r.method : "GET").append(" ").append(r.path != null ? r.path : "/").append(" HTTP/1.1\r\n");
        req.append("Host: ").append(r.host != null ? r.host : "").append("\r\n");
        if (r.requestHeaders != null) {
            String[] lines = r.requestHeaders.split("\n");
            for (int i = 1; i < lines.length; i++) {
                req.append(lines[i]).append("\r\n");
            }
        }
        if (r.requestBody != null && !r.requestBody.isEmpty()) {
            req.append("\r\n").append(r.requestBody);
        }
        if (detailRequestEditor != null) {
            detailRequestEditor.setRequest(HttpRequest.httpRequest(req.toString()));
        }

        StringBuilder resp = new StringBuilder();
        if (r.responseHeaders != null && !r.responseHeaders.trim().isEmpty()) {
            resp.append(r.responseHeaders.trim());
        } else {
            int code = r.statusCode > 0 ? r.statusCode : 0;
            resp.append("HTTP/1.1 ").append(code);
        }
        resp.append("\r\n\r\n");
        if (r.responseBody != null && !r.responseBody.isEmpty()) {
            resp.append(r.responseBody);
        }
        if (detailResponseEditor != null) {
            detailResponseEditor.setResponse(HttpResponse.httpResponse(resp.toString()));
        }

        StringBuilder vars = new StringBuilder();
        if (r.extractedVariables != null && !r.extractedVariables.isEmpty()) {
            for (Map.Entry<String, String> entry : r.extractedVariables.entrySet()) {
                vars.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
            }
        } else {
            vars.append("No variables extracted.");
        }
        detailVarsText.setText(vars.toString());
        detailVarsText.setCaretPosition(0);
    }

    public JPanel getPanel() { return mainPanel; }
    public JTabbedPane getTabbedPane() { return tabbedPane; }
}
