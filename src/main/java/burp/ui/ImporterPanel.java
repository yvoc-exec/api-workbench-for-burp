package burp.ui;

import burp.models.*;
import burp.parser.*;
import burp.runner.CollectionRunner;
import burp.auth.OAuth2Manager;
import burp.auth.OAuth2Config;
import burp.auth.TokenStore;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class ImporterPanel {
    private static final Logger LOGGER = Logger.getLogger(ImporterPanel.class.getName());
    private static final String DIAGNOSTICS_TAB_NAME = "Diagnostics";
    private static final String DIAGNOSTICS_PLACEHOLDER_TEXT = "Click \"Refresh Snapshot\" to generate a diagnostics snapshot.";
    private static final DateTimeFormatter DIAGNOSTICS_TIMESTAMP_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
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
    private final List<EnvironmentProfile> environmentProfiles = new ArrayList<>();
    private String activeEnvironmentId;
    private OAuth2Panel oauth2Panel;

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
    private RequestEditorPanel requestEditor;
    private JTabbedPane workbenchDetailTabs;
    private HttpRequestEditor workbenchRequestEditor;
    private HttpResponseEditor workbenchResponseEditor;
    private JTextArea workbenchMetaText;
    private JTextArea importLog;
    private JTextArea diagnosticsArea;

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
    private CollectionRunner.RunnerListener activeRunnerListener;

    // Runner detail pane
    private HttpRequestEditor detailRequestEditor;
    private HttpResponseEditor detailResponseEditor;
    private JTextArea detailVarsText;

    // Workbench environment selector
    private JComboBox<EnvironmentRef> workbenchEnvironmentCombo;
    private JButton workbenchEnvironmentImportBtn;
    private boolean suppressWorkbenchEnvironmentEvents = false;

    // Environment tab
    private JComboBox<EnvironmentRef> environmentCombo;
    private JButton environmentImportBtn, environmentNewBtn, environmentDuplicateBtn,
            environmentDeleteBtn, environmentSetActiveBtn, environmentExportBtn, environmentSaveBtn;
    private JLabel environmentHintLabel;
    private JLabel environmentStatusLabel;
    private JTextArea environmentRawArea;
    private JTable environmentTable;
    private DefaultTableModel environmentTableModel;
    private JPanel environmentEditorCardPanel;
    private JRadioButton environmentRawViewBtn;
    private JRadioButton environmentTableViewBtn;
    private boolean environmentDirty = false;
    private boolean suppressEnvironmentEditorEvents = false;

    // Legacy scoped variables UI state retained for internal compatibility only.
    private JTextArea envVarsArea;
    private JTable varsTable;
    private DefaultTableModel varsTableModel;
    private JPanel varsEditorCardPanel;
    private JRadioButton varsRawViewBtn;
    private JRadioButton varsTableViewBtn;
    private JComboBox<CollectionRef> varsCollectionCombo = new JComboBox<>();
    private JButton bindVarsBtn;
    private JLabel varsHintLabel;
    private JLabel varsAutosaveStatusLabel;
    private boolean suppressVariablesAutosave = false;
    private boolean variablesDirty = false;
    private boolean suppressVariablesCollectionSelectionPrompt = false;
    private CollectionRef activeVariablesCollectionRef = null;
    private boolean variablesRawEditingActive = false;
    private boolean variablesRawRefreshPending = false;
    private ApiCollection variablesRawRefreshPendingCollection = null;
    private boolean variablesTableEditingActive = false;
    private boolean variablesTableRefreshPending = false;
    private ApiCollection variablesTableRefreshPendingCollection = null;
    private boolean variablesTableAutosavePending = false;
    private ApiCollection variablesTableAutosavePendingCollection = null;
    private long variablesRawLastEditAt = 0L;
    private long variablesTableLastEditAt = 0L;
    private javax.swing.Timer variablesRawEditIdleTimer;
    private javax.swing.Timer variablesTableEditIdleTimer;
    private String varsBaseLayerText = "";

    // OAuth2 tab
    private JComboBox<CollectionRef> oauth2CollectionCombo = new JComboBox<>();
    private JButton bindOAuth2Btn;
    private JLabel oauth2HintLabel;
    private JLabel oauth2AutosaveStatusLabel;
    private JLabel oauth2ActiveEnvironmentLabel;
    private JLabel oauth2StatusLabel;
    private JComboBox<String> oauth2AccessTokenBindingCombo;
    private JComboBox<String> oauth2RefreshTokenBindingCombo;
    private JComboBox<String> oauth2TokenTypeBindingCombo;
    private JComboBox<String> oauth2ExpiresInBindingCombo;
    private JLabel oauth2BindingHintLabel;
    private volatile boolean shuttingDown = false;

    // Workspace persistence callback
    private Runnable workspaceChangeListener;
    private boolean suppressWorkspaceChangeNotifications = false;
    private Map<String, String> pendingWorkspaceRequestTreePaths = Collections.emptyMap();
    // Send mode is tracked by the RequestEditorPanel send button label
    private final burp.utils.ScriptMode scriptMode;
    private final List<ApiRequest> runnerQueuedRequests = new ArrayList<>();

    public ImporterPanel(UniversalImporter importer, CollectionRunner runner, OAuth2Manager oauth2Manager, burp.utils.ScriptMode scriptMode) {
        this.scriptMode = scriptMode;
        this.oauth2Manager = oauth2Manager;
        this.requestBuilder = new burp.utils.RequestBuilder(importer.getApi(), oauth2Manager);
        this.oauth2Panel = new OAuth2Panel(oauth2Manager);
        this.oauth2Panel.setTokenAcquiredCollectionSupplier(() -> null);
        this.oauth2Panel.setTokenAcquiredListener(this::handleOAuth2TokenAcquired);
        this.importer = importer;
        this.runner = runner;
        if (this.runner != null) {
            this.runner.setRuntimeOverlayProvider(collection -> hasActiveEnvironment() ? activeEnvironmentOverlay() : null);
            this.runner.setOAuth2TokenSink(ImporterPanel.this::storeOAuth2TokenInActiveEnvironment);
        }
        this.mainPanel = createUI();
        if (oauth2Panel.getPopulateButton() != null) {
            oauth2Panel.getPopulateButton().setText("Populate from Request");
            oauth2Panel.getPopulateButton().addActionListener(e -> populateOAuth2FromRequest());
        }
        this.oauth2Panel.setClearTokensListener(this::clearActiveEnvironmentOAuth2TokenOutputs);
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

    private void notifyWorkspaceChangedImmediately() {
        notifyWorkspaceChanged();
        if (shuttingDown || suppressWorkspaceChangeNotifications) {
            return;
        }
        if (importer != null) {
            importer.requestWorkspaceStateSaveNow();
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
        tabbedPane.addTab("Environment", createVariablesTab());
        tabbedPane.addTab("OAuth2", createOAuth2Tab());
        tabbedPane.addTab("Collection Runner", createRunnerTab());
        tabbedPane.addTab(DIAGNOSTICS_TAB_NAME, createDiagnosticsTab());

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
        tree.addTreeExpansionListener(new javax.swing.event.TreeExpansionListener() {
            @Override
            public void treeExpanded(javax.swing.event.TreeExpansionEvent event) {
                notifyWorkspaceChangedImmediately();
            }

            @Override
            public void treeCollapsed(javax.swing.event.TreeExpansionEvent event) {
                notifyWorkspaceChangedImmediately();
            }
        });
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
        requestEditor.setTrackedHeaderStateChangeListener(() -> {
            runWithWorkspaceChangeNotificationsSuppressed(this::persistCurrentRequestEditorState);
            notifyWorkspaceChangedImmediately();
        });

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
        panel.setBorder(BorderFactory.createTitledBorder("Environment"));

        panel.add(new JLabel("Active Environment:"));
        workbenchEnvironmentCombo = new JComboBox<>();
        workbenchEnvironmentCombo.setPrototypeDisplayValue(new EnvironmentRef(null, "No Environment"));
        workbenchEnvironmentCombo.addActionListener(e -> handleWorkbenchEnvironmentSelectionChanged());
        panel.add(workbenchEnvironmentCombo);

        workbenchEnvironmentImportBtn = new JButton("Import");
        workbenchEnvironmentImportBtn.addActionListener(e -> handleEnvironmentImport());
        panel.add(workbenchEnvironmentImportBtn);

        syncWorkbenchEnvironmentControls();
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
        applyActiveVariablesDraftToCollection(col);
        syncRequestEditorRuntimeContext(liveRequest, col);
        notifyWorkspaceChanged();

        final ApiCollection resolvedCol = col;
        final ApiRequest requestToSend = liveRequest;
        final String sendModeLabel = requestEditor.getSendModeLabel();
        Map<String, String> runtimeOverlay = hasActiveEnvironment() ? activeEnvironmentOverlay() : null;
        List<UnresolvedVariableIssue> issues = collectUnresolvedVariableIssues(
                List.of(resolvedCol),
                List.of(requestToSend),
                runtimeOverlay);
        if (!issues.isEmpty()) {
            UnresolvedVariablesDialog.Action action = showUnresolvedVariablesDialog(issues, List.of(resolvedCol));
            if (action == UnresolvedVariablesDialog.Action.CANCEL) {
                appendImportLog("Send cancelled due to unresolved variables.");
                return;
            }
            runtimeOverlay = hasActiveEnvironment() ? activeEnvironmentOverlay() : null;
        }
        final Map<String, String> runtimeOverlayForSend = runtimeOverlay;
        requestEditor.setSendEnabled(false);
        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    publish("Sending: " + requestToSend.method + " " + requestToSend.url);
                    boolean follow = followRedirectsBox != null && followRedirectsBox.isSelected();
                    var result = runtimeOverlayForSend == null
                            ? importer.sendSingleRequestWithBuiltRequest(requestToSend, resolvedCol, follow)
                            : importer.sendSingleRequestWithBuiltRequest(
                                    requestToSend,
                                    resolvedCol,
                                    follow,
                                    runtimeOverlayForSend,
                                    ImporterPanel.this::storeOAuth2TokenInActiveEnvironment);
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
        liveRequest.buildMode = edited.buildMode;
        liveRequest.suppressedAutoHeaders = edited.suppressedAutoHeaders != null
                ? new LinkedHashSet<>(edited.suppressedAutoHeaders)
                : new LinkedHashSet<>();
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
    // Environment Tab
    // ========================================================================
    private JPanel createVariablesTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createTitledBorder("Environment"));

        environmentRawArea = new JTextArea(18, 60);
        environmentRawArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        environmentRawArea.setText("# key=value per line\n# base_url=http://localhost:8080\n# token=abc123");
        environmentRawArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { markEnvironmentDirty(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { markEnvironmentDirty(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { markEnvironmentDirty(); }
        });

        environmentTableModel = new DefaultTableModel(new Object[]{"Key", "Value"}, 0);
        environmentTableModel.addTableModelListener(e -> markEnvironmentDirty());
        environmentTable = RequestEditorTableSupport.createEditableTable(environmentTableModel);
        RequestEditorStateMapper.ensureStarterRow(environmentTableModel);

        environmentEditorCardPanel = new JPanel(new CardLayout());
        environmentEditorCardPanel.add(new JScrollPane(environmentRawArea), "raw");

        JPanel tablePanel = new JPanel(new BorderLayout(5, 5));
        tablePanel.add(new JScrollPane(environmentTable), BorderLayout.CENTER);
        tablePanel.add(RequestEditorTableSupport.createAddRemovePanel(environmentTable, environmentTableModel, () -> new Object[]{"", ""}), BorderLayout.SOUTH);
        environmentEditorCardPanel.add(tablePanel, "table");

        environmentRawViewBtn = new JRadioButton("Raw", true);
        environmentTableViewBtn = new JRadioButton("Table");
        ButtonGroup viewGroup = new ButtonGroup();
        viewGroup.add(environmentRawViewBtn);
        viewGroup.add(environmentTableViewBtn);
        environmentRawViewBtn.addActionListener(e -> switchEnvironmentView(false));
        environmentTableViewBtn.addActionListener(e -> switchEnvironmentView(true));

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        environmentCombo = new JComboBox<>();
        environmentCombo.setPrototypeDisplayValue(new EnvironmentRef(null, "No Environment"));
        environmentCombo.addActionListener(e -> handleEnvironmentSelectionChanged());
        environmentImportBtn = new JButton("Import");
        environmentImportBtn.addActionListener(e -> handleEnvironmentImport());
        environmentNewBtn = new JButton("New");
        environmentNewBtn.addActionListener(e -> handleEnvironmentNew());
        environmentDuplicateBtn = new JButton("Duplicate");
        environmentDuplicateBtn.addActionListener(e -> handleEnvironmentDuplicate());
        environmentDeleteBtn = new JButton("Delete");
        environmentDeleteBtn.addActionListener(e -> handleEnvironmentDelete());
        environmentSetActiveBtn = new JButton("Set Active");
        environmentSetActiveBtn.addActionListener(e -> handleEnvironmentSetActive());
        environmentExportBtn = new JButton("Export");
        environmentExportBtn.addActionListener(e -> handleEnvironmentExport());
        environmentSaveBtn = new JButton("Save");
        environmentSaveBtn.addActionListener(e -> commitEnvironmentEditorToSelectedProfile());

        topBar.add(new JLabel("Active Environment:"));
        topBar.add(environmentCombo);
        topBar.add(environmentImportBtn);
        topBar.add(environmentNewBtn);
        topBar.add(environmentDuplicateBtn);
        topBar.add(environmentDeleteBtn);
        topBar.add(environmentSetActiveBtn);
        topBar.add(environmentExportBtn);
        topBar.add(environmentSaveBtn);

        JPanel centerWrap = new JPanel(new BorderLayout(5, 5));
        JPanel toggleBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        toggleBar.add(new JLabel("View:"));
        toggleBar.add(environmentRawViewBtn);
        toggleBar.add(environmentTableViewBtn);
        centerWrap.add(toggleBar, BorderLayout.NORTH);
        centerWrap.add(environmentEditorCardPanel, BorderLayout.CENTER);

        environmentHintLabel = new JLabel("Active Environment values apply to previews, sends, runner, Repeater, Intruder, and Sitemap.");
        environmentHintLabel.setForeground(Color.DARK_GRAY);
        environmentStatusLabel = new JLabel("Saved.");
        environmentStatusLabel.setForeground(Color.GRAY);
        JPanel statusPanel = new JPanel(new GridLayout(2, 1));
        statusPanel.add(environmentHintLabel);
        statusPanel.add(environmentStatusLabel);

        // Legacy runtime-vars field aliases retained for compatibility with
        // existing tests and internal helper methods.
        envVarsArea = environmentRawArea;
        varsTable = environmentTable;
        varsTableModel = environmentTableModel;
        varsEditorCardPanel = environmentEditorCardPanel;
        varsRawViewBtn = environmentRawViewBtn;
        varsTableViewBtn = environmentTableViewBtn;
        varsHintLabel = environmentHintLabel;
        if (varsAutosaveStatusLabel == null) {
            varsAutosaveStatusLabel = new JLabel("Saved.");
            varsAutosaveStatusLabel.setVisible(false);
        }

        panel.add(topBar, BorderLayout.NORTH);
        panel.add(centerWrap, BorderLayout.CENTER);
        panel.add(statusPanel, BorderLayout.SOUTH);

        installEnvironmentSaveShortcut(panel);
        installEnvironmentSaveShortcut(environmentEditorCardPanel);
        updateEnvironmentComboModel();
        updateEnvironmentUiState();
        return panel;
    }

    // ========================================================================
    // OAuth2 Tab
    // ========================================================================
    private JPanel createOAuth2Tab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));

        JPanel top = new JPanel(new GridLayout(0, 1, 4, 4));
        oauth2ActiveEnvironmentLabel = new JLabel("Active Environment: No Environment");
        oauth2ActiveEnvironmentLabel.setForeground(Color.DARK_GRAY);
        oauth2BindingHintLabel = new JLabel("Create or select an Active Environment before configuring OAuth2.");
        oauth2BindingHintLabel.setForeground(Color.GRAY);
        top.add(oauth2ActiveEnvironmentLabel);
        top.add(oauth2BindingHintLabel);
        panel.add(top, BorderLayout.NORTH);

        panel.add(oauth2Panel, BorderLayout.CENTER);

        JPanel bindings = new JPanel(new GridBagLayout());
        bindings.setBorder(BorderFactory.createTitledBorder("Bind OAuth2 Output"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridy = 0;

        oauth2AccessTokenBindingCombo = createEditableBindingCombo("oauth2_access_token");
        oauth2RefreshTokenBindingCombo = createEditableBindingCombo("oauth2_refresh_token");
        oauth2TokenTypeBindingCombo = createEditableBindingCombo("oauth2_token_type");
        oauth2ExpiresInBindingCombo = createEditableBindingCombo("oauth2_expires_in");

        addBindingRow(bindings, gbc, "Access Token variable:", oauth2AccessTokenBindingCombo);
        addBindingRow(bindings, gbc, "Refresh Token variable:", oauth2RefreshTokenBindingCombo);
        addBindingRow(bindings, gbc, "Token Type variable:", oauth2TokenTypeBindingCombo);
        addBindingRow(bindings, gbc, "Expires In variable:", oauth2ExpiresInBindingCombo);

        oauth2StatusLabel = new JLabel("Saved.");
        oauth2StatusLabel.setForeground(Color.GRAY);
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        bindings.add(oauth2StatusLabel, gbc);

        panel.add(bindings, BorderLayout.SOUTH);
        syncOAuth2UiState();
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

    private EnvironmentProfile getActiveEnvironment() {
        if (environmentProfiles.isEmpty()) {
            return null;
        }
        if (activeEnvironmentId == null) {
            return null;
        }
        for (EnvironmentProfile profile : environmentProfiles) {
            if (profile != null && Objects.equals(activeEnvironmentId, profile.id)) {
                return profile;
            }
        }
        return null;
    }

    private boolean hasActiveEnvironment() {
        return getActiveEnvironment() != null;
    }

    private Map<String, String> activeEnvironmentOverlay() {
        EnvironmentProfile active = getActiveEnvironment();
        return active != null ? active.toRuntimeOverlay() : Collections.emptyMap();
    }

    public List<EnvironmentProfile> getEnvironmentProfilesSnapshot() {
        List<EnvironmentProfile> copy = new ArrayList<>();
        for (EnvironmentProfile profile : environmentProfiles) {
            if (profile != null) {
                copy.add(profile.copy());
            }
        }
        return copy;
    }

    public void replaceEnvironmentProfiles(List<EnvironmentProfile> profiles) {
        environmentProfiles.clear();
        if (profiles != null) {
            for (EnvironmentProfile profile : profiles) {
                if (profile == null) {
                    continue;
                }
                profile.ensureDefaults();
                profile.ensureId();
                environmentProfiles.add(profile);
            }
        }
        if (activeEnvironmentId != null && environmentProfiles.stream().noneMatch(profile -> profile != null && Objects.equals(profile.id, activeEnvironmentId))) {
            activeEnvironmentId = null;
        }
        updateEnvironmentComboModel();
        renderSelectedEnvironmentIntoEditor();
        updateEnvironmentUiState();
        syncOAuth2UiState();
        notifyWorkspaceChangedImmediately();
    }

    public String getActiveEnvironmentId() {
        return activeEnvironmentId;
    }

    public void setActiveEnvironmentId(String environmentId) {
        if (environmentDirty) {
            commitEnvironmentEditorToSelectedProfile();
        }
        activeEnvironmentId = environmentId;
        if (activeEnvironmentId != null && environmentProfiles.stream().noneMatch(profile -> profile != null && Objects.equals(profile.id, activeEnvironmentId))) {
            activeEnvironmentId = null;
        }
        updateEnvironmentComboModel();
        if (activeEnvironmentId != null) {
            selectEnvironmentById(activeEnvironmentId);
        }
        updateEnvironmentUiState();
        syncOAuth2UiState();
        syncActiveEnvironmentToEditors();
        notifyWorkspaceChangedImmediately();
    }

    private void syncActiveEnvironmentToEditors() {
        if (requestEditor != null && requestEditor.getCurrentRequest() != null) {
            requestEditor.setRuntimeVariables(activeEnvironmentOverlay());
        }
    }

    private Map<String, String> storeOAuth2TokenInActiveEnvironment(ApiCollection collection, burp.auth.TokenStore.TokenEntry entry) {
        EnvironmentProfile active = getActiveEnvironment();
        Map<String, String> stored = new LinkedHashMap<>();
        if (active == null || entry == null) {
            return stored;
        }
        String accessBinding = active.oauth2.outputBindings != null ? active.oauth2.outputBindings.get("accessToken") : null;
        String refreshBinding = active.oauth2.outputBindings != null ? active.oauth2.outputBindings.get("refreshToken") : null;
        String tokenTypeBinding = active.oauth2.outputBindings != null ? active.oauth2.outputBindings.get("tokenType") : null;
        String expiresInBinding = active.oauth2.outputBindings != null ? active.oauth2.outputBindings.get("expiresIn") : null;

        if (entry.accessToken != null && !entry.accessToken.isBlank()) {
            String key = accessBinding != null && !accessBinding.isBlank() ? accessBinding : "oauth2_access_token";
            active.variables.put(key, entry.accessToken);
            stored.put(key, entry.accessToken);
        }
        if (entry.refreshToken != null && !entry.refreshToken.isBlank()) {
            String key = refreshBinding != null && !refreshBinding.isBlank() ? refreshBinding : "oauth2_refresh_token";
            active.variables.put(key, entry.refreshToken);
            stored.put(key, entry.refreshToken);
        }
        if (entry.tokenType != null && !entry.tokenType.isBlank()) {
            String key = tokenTypeBinding != null && !tokenTypeBinding.isBlank() ? tokenTypeBinding : "oauth2_token_type";
            active.variables.put(key, entry.tokenType);
            stored.put(key, entry.tokenType);
        }
        if (entry.expiresAt > 0) {
            long expiresInSeconds = Math.max(0, (entry.expiresAt - System.currentTimeMillis()) / 1000);
            String key = expiresInBinding != null && !expiresInBinding.isBlank() ? expiresInBinding : "oauth2_expires_in";
            active.variables.put(key, String.valueOf(expiresInSeconds));
            stored.put(key, String.valueOf(expiresInSeconds));
        }
        if (!stored.isEmpty()) {
            active.ensureDefaults();
            notifyWorkspaceChangedImmediately();
        }
        return stored;
    }

    private void clearActiveEnvironmentOAuth2TokenOutputs() {
        EnvironmentProfile active = getActiveEnvironment();
        if (active == null) {
            setOAuth2AutosaveStatus("Tokens cleared. No Active Environment selected.", Color.GRAY);
            syncActiveEnvironmentToEditors();
            return;
        }

        active.ensureDefaults();
        Map<String, String> bindings = active.oauth2 != null ? active.oauth2.outputBindings : Collections.emptyMap();
        if (bindings == null || bindings.isEmpty()) {
            bindings = readOAuth2OutputBindingsFromUi();
        }

        LinkedHashSet<String> keysToRemove = new LinkedHashSet<>();
        addBindingTarget(keysToRemove, bindings.get("accessToken"));
        addBindingTarget(keysToRemove, bindings.get("refreshToken"));
        addBindingTarget(keysToRemove, bindings.get("tokenType"));
        addBindingTarget(keysToRemove, bindings.get("expiresIn"));

        for (String key : keysToRemove) {
            active.variables.remove(key);
        }

        syncOAuth2PanelFromActiveEnvironment();
        syncOAuth2BindingUiFromActiveEnvironment();
        renderSelectedEnvironmentIntoEditor();
        syncActiveEnvironmentToEditors();
        updateEnvironmentUiState();
        notifyWorkspaceChangedImmediately();
        setOAuth2AutosaveStatus("Cleared OAuth2 token variables from Active Environment \"" + active.displayName() + "\".", Color.GRAY);
    }

    private void addBindingTarget(Set<String> keys, String value) {
        if (keys != null && value != null && !value.isBlank()) {
            keys.add(value);
        }
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
        return buildPopupSelectionTree(selectionRoot, selectedCountLabel, false);
    }

    private JTree buildPopupSelectionTree(DefaultMutableTreeNode selectionRoot, JLabel selectedCountLabel, boolean singleRequestMode) {
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
                if (singleRequestMode) {
                    toggleSingleRequestPopupSelection((DefaultMutableTreeNode) tree.getModel().getRoot(), ctn);
                } else {
                    ctn.propagateCheck(!ctn.isChecked());
                    TreeNode parent = ctn.getParent();
                    if (parent instanceof CollectionTreeNode) {
                        ((CollectionTreeNode) parent).updateParentCheckState();
                    }
                }
                refreshPopupSelectionParentState((DefaultMutableTreeNode) tree.getModel().getRoot());
                tree.repaint();
                updatePopupSelectionCountLabel((DefaultMutableTreeNode) tree.getModel().getRoot(), selectedCountLabel);
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
            updatePopupSelectionCountLabel((DefaultMutableTreeNode) tree.getModel().getRoot(), selectedCountLabel);
        });
        updatePopupSelectionCountLabel((DefaultMutableTreeNode) tree.getModel().getRoot(), selectedCountLabel);
        return tree;
    }

    private void toggleSingleRequestPopupSelection(DefaultMutableTreeNode root, CollectionTreeNode clickedNode) {
        if (root == null || clickedNode == null) {
            return;
        }

        CollectionTreeNode currentCheckedRequest = findCheckedRequestNode(root);
        CollectionTreeNode targetRequest = null;

        if (clickedNode.getNodeType() == CollectionTreeNode.Type.REQUEST) {
            if (clickedNode.request != null && currentCheckedRequest != null && currentCheckedRequest.request == clickedNode.request) {
                targetRequest = currentCheckedRequest;
            } else {
                targetRequest = clickedNode;
            }
        } else {
            if (currentCheckedRequest != null && isDescendant(clickedNode, currentCheckedRequest)) {
                targetRequest = currentCheckedRequest;
            } else {
                targetRequest = findFirstRequestNode(clickedNode);
            }
        }

        if (targetRequest == null) {
            targetRequest = currentCheckedRequest;
        }
        if (targetRequest == null) {
            clearCheckedState(root);
            return;
        }

        clearCheckedState(root);
        markCheckedPath(targetRequest);
    }

    private void clearCheckedState(DefaultMutableTreeNode node) {
        if (node == null) {
            return;
        }
        if (node instanceof CollectionTreeNode) {
            ((CollectionTreeNode) node).setChecked(false);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            clearCheckedState((DefaultMutableTreeNode) node.getChildAt(i));
        }
    }

    private void markCheckedPath(CollectionTreeNode node) {
        if (node == null) {
            return;
        }
        node.setChecked(true);
        TreeNode parent = node.getParent();
        while (parent instanceof CollectionTreeNode) {
            ((CollectionTreeNode) parent).setChecked(true);
            parent = parent.getParent();
        }
    }

    private CollectionTreeNode findCheckedRequestNode(DefaultMutableTreeNode node) {
        if (node == null) {
            return null;
        }
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST && ctn.isChecked()) {
                return ctn;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            CollectionTreeNode found = findCheckedRequestNode((DefaultMutableTreeNode) node.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private CollectionTreeNode findFirstRequestNode(DefaultMutableTreeNode node) {
        if (node == null) {
            return null;
        }
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST) {
                return ctn;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            CollectionTreeNode found = findFirstRequestNode((DefaultMutableTreeNode) node.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean isDescendant(DefaultMutableTreeNode ancestor, DefaultMutableTreeNode node) {
        if (ancestor == null || node == null) {
            return false;
        }
        TreeNode current = node;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private void refreshPopupSelectionParentState(DefaultMutableTreeNode node) {
        if (node == null) {
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            refreshPopupSelectionParentState((DefaultMutableTreeNode) node.getChildAt(i));
        }
        if (node instanceof CollectionTreeNode) {
            ((CollectionTreeNode) node).updateParentCheckState();
        }
    }

    private void updatePopupSelectionCountLabel(DefaultMutableTreeNode root, JLabel selectedCountLabel) {
        if (selectedCountLabel != null) {
            selectedCountLabel.setText(collectCheckedRequests(root).size() + " requests selected");
        }
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

    private static class EnvironmentRef {
        final EnvironmentProfile environment;
        final String label;

        EnvironmentRef(EnvironmentProfile environment, String label) {
            this.environment = environment;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
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

    private void switchEnvironmentView(boolean tableView) {
        if (environmentEditorCardPanel == null) {
            return;
        }
        if (tableView) {
            syncEnvironmentTableFromRaw();
        } else {
            syncEnvironmentRawFromTable();
        }
        CardLayout cl = (CardLayout) environmentEditorCardPanel.getLayout();
        cl.show(environmentEditorCardPanel, tableView ? "table" : "raw");
    }

    private void installEnvironmentSaveShortcut(JComponent component) {
        if (component == null) {
            return;
        }
        InputMap inputMap = component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actionMap = component.getActionMap();
        KeyStroke saveKey = KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(saveKey, "environment-save");
        actionMap.put("environment-save", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                commitEnvironmentEditorToSelectedProfile();
            }
        });
    }

    private void commitEnvironmentEditorToSelectedProfile() {
        EnvironmentProfile selected = getSelectedEnvironmentProfile();
        if (selected == null) {
            return;
        }
        if (environmentTable != null && environmentTable.isEditing()) {
            javax.swing.table.TableCellEditor editor = environmentTable.getCellEditor();
            if (editor != null) {
                editor.stopCellEditing();
            }
        }
        Map<String, String> parsed = parseEnvironmentEditorVariables();
        selected.variables.clear();
        selected.variables.putAll(parsed);
        selected.ensureDefaults();
        environmentDirty = false;
        renderSelectedEnvironmentIntoEditor();
        updateEnvironmentUiState();
        syncActiveEnvironmentToEditors();
        notifyWorkspaceChangedImmediately();
        appendImportLog("Saved environment \"" + selected.displayName() + "\".");
    }

    private void renderSelectedEnvironmentIntoEditor() {
        EnvironmentProfile selected = getSelectedEnvironmentProfile();
        if (selected == null) {
            suppressEnvironmentEditorEvents = true;
            try {
                if (environmentRawArea != null) {
                    environmentRawArea.setText("");
                }
                if (environmentTableModel != null) {
                    environmentTableModel.setRowCount(0);
                }
                environmentDirty = false;
            } finally {
                suppressEnvironmentEditorEvents = false;
            }
            return;
        }
        selected.ensureDefaults();
        suppressEnvironmentEditorEvents = true;
        try {
            if (environmentRawArea != null) {
                environmentRawArea.setText(renderEnvironmentVariablesAsText(selected.variables));
            }
            if (environmentTableModel != null) {
                environmentTableModel.setRowCount(0);
                for (Map.Entry<String, String> entry : selected.variables.entrySet()) {
                    environmentTableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
                }
                RequestEditorStateMapper.ensureStarterRow(environmentTableModel);
            }
            environmentDirty = false;
        } finally {
            suppressEnvironmentEditorEvents = false;
        }
        updateEnvironmentUiState();
    }

    private void syncEnvironmentTableFromRaw() {
        if (environmentTableModel == null || environmentRawArea == null) {
            return;
        }
        Map<String, String> parsed = parseEnvironmentEditorVariablesFromText(environmentRawArea.getText());
        suppressEnvironmentEditorEvents = true;
        try {
            environmentTableModel.setRowCount(0);
            for (Map.Entry<String, String> entry : parsed.entrySet()) {
                environmentTableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
            }
            RequestEditorStateMapper.ensureStarterRow(environmentTableModel);
        } finally {
            suppressEnvironmentEditorEvents = false;
        }
    }

    private void syncEnvironmentRawFromTable() {
        if (environmentTableModel == null || environmentRawArea == null) {
            return;
        }
        if (environmentTable != null && environmentTable.isEditing()) {
            javax.swing.table.TableCellEditor editor = environmentTable.getCellEditor();
            if (editor != null) {
                editor.stopCellEditing();
            }
        }
        Map<String, String> parsed = parseEnvironmentTableVariables();
        suppressEnvironmentEditorEvents = true;
        try {
            environmentRawArea.setText(renderEnvironmentVariablesAsText(parsed));
        } finally {
            suppressEnvironmentEditorEvents = false;
        }
    }

    private Map<String, String> parseEnvironmentTableVariables() {
        Map<String, String> vars = new LinkedHashMap<>();
        if (environmentTableModel == null) {
            return vars;
        }
        if (environmentTable != null && environmentTable.isEditing()) {
            javax.swing.table.TableCellEditor editor = environmentTable.getCellEditor();
            if (editor != null) {
                editor.stopCellEditing();
            }
        }
        for (int i = 0; i < environmentTableModel.getRowCount(); i++) {
            Object keyObj = environmentTableModel.getValueAt(i, 0);
            Object valueObj = environmentTableModel.getValueAt(i, 1);
            String key = keyObj != null ? keyObj.toString().trim() : "";
            if (key.isEmpty()) {
                continue;
            }
            vars.put(key, valueObj != null ? valueObj.toString() : "");
        }
        return vars;
    }

    private Map<String, String> parseEnvironmentEditorVariables() {
        if (environmentTableViewBtn != null && environmentTableViewBtn.isSelected()) {
            return parseEnvironmentTableVariables();
        }
        return parseEnvironmentEditorVariablesFromText(environmentRawArea != null ? environmentRawArea.getText() : "");
    }

    private Map<String, String> parseEnvironmentEditorVariablesFromText(String text) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return vars;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(trimmed).getAsJsonObject();
                for (Map.Entry<String, com.google.gson.JsonElement> entry : obj.entrySet()) {
                    if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null && !entry.getValue().isJsonNull()) {
                        vars.put(entry.getKey(), entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : entry.getValue().toString());
                    }
                }
                return vars;
            } catch (Exception ignored) {
                // Fall through to key=value parsing
            }
        }
        for (String line : text.split("\\R")) {
            String normalized = line.trim();
            if (normalized.isEmpty() || normalized.startsWith("#")) {
                continue;
            }
            int eqIdx = normalized.indexOf('=');
            if (eqIdx <= 0) {
                continue;
            }
            String key = normalized.substring(0, eqIdx).trim();
            if (key.isEmpty()) {
                continue;
            }
            vars.put(key, normalized.substring(eqIdx + 1).trim());
        }
        return vars;
    }

    private String renderEnvironmentVariablesAsText(Map<String, String> vars) {
        StringBuilder sb = new StringBuilder();
        if (vars != null) {
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                sb.append(entry.getKey()).append('=').append(entry.getValue() != null ? entry.getValue() : "").append('\n');
            }
        }
        return sb.toString();
    }

    private JComboBox<String> createEditableBindingCombo(String defaultValue) {
        JComboBox<String> combo = new JComboBox<>();
        combo.setEditable(true);
        combo.setPrototypeDisplayValue(defaultValue);
        combo.setSelectedItem(defaultValue);
        combo.addActionListener(e -> {
            if (!suppressEnvironmentEditorEvents) {
                markEnvironmentDirty();
            }
        });
        return combo;
    }

    private void addBindingRow(JPanel panel, GridBagConstraints gbc, String label, JComboBox<String> combo) {
        gbc.gridx = 0;
        gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(combo, gbc);
        gbc.gridy++;
    }

    private void setBindingCombosEnabled(boolean enabled) {
        if (oauth2AccessTokenBindingCombo != null) oauth2AccessTokenBindingCombo.setEnabled(enabled);
        if (oauth2RefreshTokenBindingCombo != null) oauth2RefreshTokenBindingCombo.setEnabled(enabled);
        if (oauth2TokenTypeBindingCombo != null) oauth2TokenTypeBindingCombo.setEnabled(enabled);
        if (oauth2ExpiresInBindingCombo != null) oauth2ExpiresInBindingCombo.setEnabled(enabled);
    }

    private void refreshOAuth2BindingCandidates() {
        List<String> candidates = collectOAuth2BindingCandidates();
        updateBindingComboChoices(oauth2AccessTokenBindingCombo, candidates, "oauth2_access_token");
        updateBindingComboChoices(oauth2RefreshTokenBindingCombo, candidates, "oauth2_refresh_token");
        updateBindingComboChoices(oauth2TokenTypeBindingCombo, candidates, "oauth2_token_type");
        updateBindingComboChoices(oauth2ExpiresInBindingCombo, candidates, "oauth2_expires_in");
        syncOAuth2BindingUiFromActiveEnvironment();
    }

    private void updateBindingComboChoices(JComboBox<String> combo, List<String> candidates, String defaultValue) {
        if (combo == null) {
            return;
        }
        Object current = combo.getSelectedItem();
        suppressEnvironmentEditorEvents = true;
        try {
            combo.removeAllItems();
            combo.addItem(defaultValue);
            for (String candidate : candidates) {
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }
                if (!containsComboItem(combo, candidate)) {
                    combo.addItem(candidate);
                }
            }
            combo.setSelectedItem(current != null ? current.toString() : defaultValue);
        } finally {
            suppressEnvironmentEditorEvents = false;
        }
    }

    private boolean containsComboItem(JComboBox<String> combo, String value) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (Objects.equals(combo.getItemAt(i), value)) {
                return true;
            }
        }
        return false;
    }

    private List<String> collectOAuth2BindingCandidates() {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        EnvironmentProfile active = getActiveEnvironment();
        if (active != null && active.variables != null) {
            candidates.addAll(active.variables.keySet());
        }
        for (ApiCollection collection : loadedCollections) {
            if (collection == null) continue;
            if (collection.environment != null) candidates.addAll(collection.environment.keySet());
            if (collection.variables != null) {
                for (ApiRequest.Variable variable : collection.variables) {
                    if (variable != null && variable.key != null && !variable.key.isBlank()) {
                        candidates.add(variable.key);
                    }
                }
            }
            if (collection.folderVars != null) {
                for (Map<String, String> folder : collection.folderVars.values()) {
                    if (folder != null) candidates.addAll(folder.keySet());
                }
            }
            if (collection.requests != null) {
                for (ApiRequest request : collection.requests) {
                    collectPlaceholdersFromRequest(candidates, request);
                }
            }
        }
        if (requestEditor != null && requestEditor.getCurrentRequest() != null) {
            collectPlaceholdersFromRequest(candidates, requestEditor.getCurrentRequest());
        }
        return new ArrayList<>(candidates);
    }

    private void collectPlaceholdersFromRequest(Set<String> out, ApiRequest request) {
        if (out == null || request == null) {
            return;
        }
        Set<String> placeholders = burp.utils.RequestBuilder.findUnresolvedTokens(
                request.url != null ? request.url.getBytes(StandardCharsets.UTF_8) : new byte[0]);
        out.addAll(placeholders);
        if (request.headers != null) {
            for (ApiRequest.Header header : request.headers) {
                if (header != null && header.value != null) {
                    out.addAll(burp.utils.RequestBuilder.findUnresolvedTokens(header.value.getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
        if (request.body != null && request.body.raw != null) {
            out.addAll(burp.utils.RequestBuilder.findUnresolvedTokens(request.body.raw.getBytes(StandardCharsets.UTF_8)));
        }
        if (request.auth != null && request.auth.properties != null) {
            for (String value : request.auth.properties.values()) {
                if (value != null) {
                    out.addAll(burp.utils.RequestBuilder.findUnresolvedTokens(value.getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
    }

    private Map<String, String> readOAuth2OutputBindingsFromUi() {
        Map<String, String> bindings = new LinkedHashMap<>();
        bindings.put("accessToken", readComboValue(oauth2AccessTokenBindingCombo, "oauth2_access_token"));
        bindings.put("refreshToken", readComboValue(oauth2RefreshTokenBindingCombo, "oauth2_refresh_token"));
        bindings.put("tokenType", readComboValue(oauth2TokenTypeBindingCombo, "oauth2_token_type"));
        bindings.put("expiresIn", readComboValue(oauth2ExpiresInBindingCombo, "oauth2_expires_in"));
        return bindings;
    }

    private void syncOAuth2BindingUiFromActiveEnvironment() {
        EnvironmentProfile active = getActiveEnvironment();
        if (active == null) {
            setBindingComboValue(oauth2AccessTokenBindingCombo, "oauth2_access_token");
            setBindingComboValue(oauth2RefreshTokenBindingCombo, "oauth2_refresh_token");
            setBindingComboValue(oauth2TokenTypeBindingCombo, "oauth2_token_type");
            setBindingComboValue(oauth2ExpiresInBindingCombo, "oauth2_expires_in");
            return;
        }
        setBindingComboValue(oauth2AccessTokenBindingCombo, active.oauth2.outputBindings.get("accessToken"));
        setBindingComboValue(oauth2RefreshTokenBindingCombo, active.oauth2.outputBindings.get("refreshToken"));
        setBindingComboValue(oauth2TokenTypeBindingCombo, active.oauth2.outputBindings.get("tokenType"));
        setBindingComboValue(oauth2ExpiresInBindingCombo, active.oauth2.outputBindings.get("expiresIn"));
    }

    private void setBindingComboValue(JComboBox<String> combo, String value) {
        if (combo == null) {
            return;
        }
        suppressEnvironmentEditorEvents = true;
        try {
            combo.setSelectedItem(value != null && !value.isBlank() ? value : combo.getItemCount() > 0 ? combo.getItemAt(0) : "");
        } finally {
            suppressEnvironmentEditorEvents = false;
        }
    }

    private String readComboValue(JComboBox<String> combo, String fallback) {
        if (combo == null) {
            return fallback;
        }
        Object selected = combo.getEditor() != null ? combo.getEditor().getItem() : combo.getSelectedItem();
        if (selected == null) {
            selected = combo.getSelectedItem();
        }
        String value = selected != null ? selected.toString().trim() : "";
        return value.isEmpty() ? fallback : value;
    }

    private UnresolvedVariablesDialog.Action showUnresolvedVariablesDialog(List<UnresolvedVariableIssue> issues,
                                                                           List<ApiCollection> targetCollections) {
        if (issues == null || issues.isEmpty()) {
            return UnresolvedVariablesDialog.Action.CONTINUE_WITHOUT_APPLYING;
        }
        UnresolvedVariablesDialog dialog = createUnresolvedVariablesDialog(issues, targetCollections);
        UnresolvedVariablesDialog.Action action = dialog.showDialog();
        if (action == UnresolvedVariablesDialog.Action.APPLY_AND_CONTINUE) {
            EnvironmentProfile active = getActiveEnvironment();
            if (active == null) {
                return UnresolvedVariablesDialog.Action.CONTINUE_WITHOUT_APPLYING;
            }
            Map<String, String> entered = dialog.getEnteredValues();
            if (entered != null && !entered.isEmpty()) {
                for (UnresolvedVariableIssue issue : issues) {
                    if (issue == null || issue.variableName == null || issue.variableName.isBlank()) {
                        continue;
                    }
                    String value = entered.get(issue.variableName);
                    if (value == null || value.isBlank()) {
                        continue;
                    }
                    active.variables.put(issue.variableName, value);
                }
                active.ensureDefaults();
                renderSelectedEnvironmentIntoEditor();
                syncActiveEnvironmentToEditors();
                updateEnvironmentUiState();
                notifyWorkspaceChangedImmediately();
                appendImportLog("Saved unresolved variables into active environment \"" + active.displayName() + "\".");
            }
        }
        return action;
    }

    static final class UnresolvedDialogConfig {
        final boolean canApply;
        final String applyButtonText;
        final String hintText;

        UnresolvedDialogConfig(boolean canApply, String applyButtonText, String hintText) {
            this.canApply = canApply;
            this.applyButtonText = applyButtonText;
            this.hintText = hintText;
        }
    }

    UnresolvedDialogConfig buildUnresolvedDialogConfig() {
        EnvironmentProfile active = getActiveEnvironment();
        boolean canApply = active != null;
        String hintText = canApply
                ? "Values will be saved to Active Environment: " + active.displayName()
                : "No Active Environment selected. You may continue without applying, or cancel and create/import an environment.";
        return new UnresolvedDialogConfig(
                canApply,
                "Apply to Active Environment",
                hintText);
    }

    UnresolvedVariablesDialog createUnresolvedVariablesDialog(List<UnresolvedVariableIssue> issues,
                                                              List<ApiCollection> targetCollections) {
        UnresolvedDialogConfig config = buildUnresolvedDialogConfig();
        Window owner = SwingUtilities.getWindowAncestor(mainPanel);
        return new UnresolvedVariablesDialog(
                owner,
                issues,
                targetCollections,
                config.canApply,
                config.applyButtonText,
                config.hintText);
    }

    private void handleOAuth2TokenAcquired(TokenStore.TokenEntry entry,
                                           ApiCollection collection,
                                           Map<String, String> oauth2Vars) {
        if (entry == null || entry.accessToken == null || entry.accessToken.isBlank()) {
            return;
        }

        EnvironmentProfile activeEnvironment = getActiveEnvironment();
        if (activeEnvironment == null) {
            appendImportLog("OAuth2 token fetch requires an active environment.");
            setOAuth2AutosaveStatus("Create or select an Active Environment before fetching tokens.", Color.GRAY);
            return;
        }

        activeEnvironment.oauth2.config.clear();
        activeEnvironment.oauth2.config.putAll(filterOAuth2ConfigVars(oauth2Vars));
        activeEnvironment.oauth2.ensureDefaults();
        activeEnvironment.oauth2.outputBindings.clear();
        activeEnvironment.oauth2.outputBindings.putAll(readOAuth2OutputBindingsFromUi());
        storeOAuth2TokenInActiveEnvironment(collection, entry);
        syncOAuth2BindingUiFromActiveEnvironment();
        renderSelectedEnvironmentIntoEditor();
        syncActiveEnvironmentToEditors();
        updateEnvironmentUiState();
        setOAuth2AutosaveStatus("Token values saved to " + activeEnvironment.displayName() + ".", new Color(0, 128, 0));
        appendImportLog("OAuth2 token saved to active environment \"" + activeEnvironment.displayName() + "\".");
    }

    private Map<String, String> filterOAuth2ConfigVars(Map<String, String> oauth2Vars) {
        Map<String, String> filtered = new LinkedHashMap<>();
        if (oauth2Vars == null) {
            return filtered;
        }
        for (Map.Entry<String, String> entry : oauth2Vars.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey();
            if (key.startsWith("oauth2_access_token")
                    || key.startsWith("oauth2_refresh_token")
                    || key.startsWith("oauth2_token_type")
                    || key.startsWith("oauth2_expires_in")) {
                continue;
            }
            filtered.put(key, entry.getValue());
        }
        return filtered;
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
                                         Runnable beginSuppress,
                                         Runnable endSuppress,
                                         Runnable clearBaseLayerText,
                                         Consumer<String> statusUpdater) {
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
            statusUpdater.accept("Editor cleared. Click Save to update the selected environment.");
        }
    }

    private void clearVariablesEditorOnly() {
        resetVariablesRawEditingSession();
        resetVariablesTableEditingSession();
        clearVariablesEditorOnly(
                envVarsArea,
                varsTableModel,
                () -> suppressVariablesAutosave = true,
                () -> suppressVariablesAutosave = false,
                () -> varsBaseLayerText = "",
                msg -> setVarsAutosaveStatus(msg, Color.GRAY)
        );
        markVariablesDirty();
    }

    static List<UnresolvedVariableIssue> collectUnresolvedVariableIssues(List<ApiCollection> sourceCollections,
                                                                         List<ApiRequest> selectedRequests) {
        return collectUnresolvedVariableIssues(sourceCollections, selectedRequests, Collections.emptyMap());
    }

    static List<UnresolvedVariableIssue> collectUnresolvedVariableIssues(List<ApiCollection> sourceCollections,
                                                                         List<ApiRequest> selectedRequests,
                                                                         Map<String, String> runtimeOverlay) {
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
            issues.addAll(analyzer.analyze(collection, request, runtimeOverlay));
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
        syncOAuth2PanelFromActiveEnvironment();
        syncOAuth2BindingUiFromActiveEnvironment();
    }

    private void refreshRuntimeViewsForCollection(ApiCollection col) {
        if (col == null) {
            return;
        }
        CollectionRef varsRef = varsCollectionCombo != null && varsCollectionCombo.getSelectedItem() != null
                ? (CollectionRef) varsCollectionCombo.getSelectedItem() : null;
        if (varsRef != null && varsRef.collection == col) {
            if (!variablesDirty) {
                renderEffectiveVariablesForSelectedCollection();
            }
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
                // Layer 4: environment overrides (editable layer)
                sb.append(base);
                varsBaseLayerText = base.toString().trim();
                if (!varsBaseLayerText.isEmpty()) {
                    sb.append("\n");
                }
                if (col.runtimeVars != null && !col.runtimeVars.isEmpty()) {
                    sb.append("# Environment variables\n");
                    for (Map.Entry<String, String> entry : new TreeMap<>(col.runtimeVars).entrySet()) {
                        sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
                    }
                    hasAny = true;
                } else if (hasAny) {
                    sb.append("# Environment variables\n");
                }
                setVariablesEditorTextPreservingView(hasAny ? sb.toString() : "");
                if (isVarsTableViewActive()) {
                    renderVarsTableFromRaw();
                }
                activeVariablesCollectionRef = ref;
                clearVariablesDirty();
            } else {
                setVariablesEditorTextPreservingView("");
                varsBaseLayerText = "";
                if (varsTableModel != null) varsTableModel.setRowCount(0);
                activeVariablesCollectionRef = null;
                clearVariablesDirty();
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
                    runWithWorkspaceChangeNotificationsSuppressed(ImporterPanel.this::rebuildTree);
                    refreshCollectionCombos();
                    appendImportLog("Loaded \"" + collection.name + "\" (" + collection.requests.size() + " requests)");
                    importBtn.setEnabled(true);
                    sendToRunnerBtn.setEnabled(true);
                    startRunnerBtn.setEnabled(true);
                    removeCollectionBtn.setEnabled(true);
                    updateEnvironmentUiState();
                    notifyWorkspaceChangedImmediately();
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
            target.clearChangeListeners();
            requestToCollectionMap.entrySet().removeIf(entry -> entry.getValue() == target);
            loadedCollections.remove(target);
            appendImportLog("Removed collection: " + target.name);
        }
        runWithWorkspaceChangeNotificationsSuppressed(this::rebuildTree);
        refreshCollectionCombos();
        notifyWorkspaceChangedImmediately();
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
        runWithWorkspaceChangeNotificationsSuppressed(this::persistCurrentRequestEditorState);

        WorkspaceState state = WorkspaceState.fromCollections(loadedCollections);
        state.environments = getEnvironmentProfilesSnapshot();
        state.activeEnvironmentId = activeEnvironmentId;
        Map<String, String> uiTreePaths = collectRequestTreePaths();
        Map<String, String> modelTreePaths = collectRequestTreePathsFromRequestModels();
        state.requestTreePaths = mergeRequestTreePaths(uiTreePaths, modelTreePaths);
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
                    pendingRestore.repairedRequestPathCount = applyWorkspaceRequestTreePathsToRequests(state.collections, pendingRestore.requestTreePaths);
                    restoreWorkspaceCollections(state.collections);
                    replaceEnvironmentProfiles(state.environments);
                    setActiveEnvironmentId(state.activeEnvironmentId);
                    selectCollectionByName(varsCollectionCombo, state.selectedVariablesCollectionName);
                    selectCollectionByName(oauth2CollectionCombo, state.selectedOAuth2CollectionName);
                    restoreWorkbenchSettings(state);
                    restoreRunnerSettings(state);
                    restoreRunnerDetailState(state);
                    syncOAuth2UiState();
                if (tabbedPane != null && tabbedPane.getTabCount() > 0) {
                    int index = Math.max(0, Math.min(state.selectedTabIndex, tabbedPane.getTabCount() - 1));
                    tabbedPane.setSelectedIndex(index);
                }
                scheduleMainRequestTreeRestoreAfterWorkbenchVisible(() -> finalizeRestoredMainRequestTree(pendingRestore));
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
        int repairedRequestPathCount;

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

    private void finalizeRestoredMainRequestTree(PendingMainRequestTreeRestore pendingRestore) {
        if (pendingRestore == null) {
            return;
        }
        runWithWorkspaceChangeNotificationsSuppressed(() -> {
            remountRestoredMainRequestTree(pendingRestore);
            refreshRestoredMainRequestTreePresentation();
        });

        SwingUtilities.invokeLater(() -> {
            Runnable visibleRebuild = () -> rebuildVisibleMainTreeAfterRestore(pendingRestore);
            if (requestTreeScrollPane != null && requestTreeScrollPane.isShowing()) {
                visibleRebuild.run();
            } else {
                scheduleMainRequestTreeRestoreAfterWorkbenchVisible(visibleRebuild);
            }
            if (pendingRestore.repairedRequestPathCount > 0) {
                notifyWorkspaceChangedImmediately();
            }
        });
    }

    private void rebuildVisibleMainTreeAfterRestore(PendingMainRequestTreeRestore pendingRestore) {
        if (pendingRestore == null || requestTree == null || treeModel == null) {
            return;
        }
        runWithWorkspaceChangeNotificationsSuppressed(() -> {
            rebuildTree(pendingRestore.requestTreePaths, pendingRestore.expandedTreePathKeys);
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
            refreshRestoredMainRequestTreePresentation();
        });
    }

    private void refreshRestoredMainRequestTreePresentation() {
        if (requestTree != null) {
            requestTree.revalidate();
            requestTree.repaint();
        }
        if (requestTreeScrollPane != null) {
            requestTreeScrollPane.revalidate();
            requestTreeScrollPane.repaint();
        }
        resetRequestTreeHorizontalViewport();
        scheduleRequestTreeHorizontalViewportReset();
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

    static int applyWorkspaceRequestTreePathsToRequests(List<ApiCollection> collections, Map<String, String> requestTreePaths) {
        if (collections == null || collections.isEmpty()) {
            return 0;
        }
        int repairedCount = 0;
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
                String previousPath = request.path;
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
                if (!Objects.equals(previousPath, request.path)) {
                    repairedCount++;
                }
            }
        }
        return repairedCount;
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
        updateEnvironmentUiState();

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

    private Map<String, String> collectRequestTreePathsFromRequestModels() {
        Map<String, String> out = new LinkedHashMap<>();
        for (int collectionIndex = 0; collectionIndex < loadedCollections.size(); collectionIndex++) {
            ApiCollection collection = loadedCollections.get(collectionIndex);
            if (collection == null || collection.requests == null || collection.requests.isEmpty()) {
                continue;
            }
            for (int requestIndex = 0; requestIndex < collection.requests.size(); requestIndex++) {
                ApiRequest request = collection.requests.get(requestIndex);
                if (request == null) {
                    continue;
                }
                String folderPath = folderPathFromRequestPath(request.path, request.name);
                if (folderPath == null) {
                    continue;
                }
                folderPath = folderPath.trim();
                if (folderPath.isBlank()) {
                    continue;
                }
                String collectionName = collection.name != null ? collection.name : request.sourceCollection;
                int resolvedRequestIndex = requestIndex;
                String key = workspaceRequestTreePathKey(collectionName, collectionIndex, request, resolvedRequestIndex);
                putRequestTreePath(out, key, folderPath, collectionName, collectionIndex, request, resolvedRequestIndex);
            }
        }
        return out;
    }

    private static String folderPathFromRequestPath(String requestPath, String requestName) {
        if (requestPath == null) {
            return "";
        }
        String normalizedPath = requestPath.replace('\\', '/').trim();
        if (normalizedPath.isEmpty()) {
            return "";
        }
        String normalizedName = requestName != null ? requestName.replace('\\', '/').trim() : "";
        if (!normalizedName.isEmpty()) {
            String suffix = "/" + normalizedName;
            if (normalizedPath.equals(normalizedName)) {
                return "";
            }
            if (normalizedPath.endsWith(suffix)) {
                return normalizedPath.substring(0, normalizedPath.length() - suffix.length());
            }
        }
        int lastSlash = normalizedPath.lastIndexOf('/');
        if (lastSlash < 0) {
            return "";
        }
        if (normalizedName.isEmpty()) {
            return normalizedPath.substring(0, lastSlash);
        }
        return normalizedPath;
    }

    private static Map<String, String> mergeRequestTreePaths(Map<String, String> uiTreePaths, Map<String, String> modelTreePaths) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        if (modelTreePaths != null) {
            for (Map.Entry<String, String> entry : modelTreePaths.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    continue;
                }
                merged.put(key, entry.getValue());
            }
        }
        if (uiTreePaths == null || uiTreePaths.isEmpty()) {
            return merged;
        }
        for (Map.Entry<String, String> entry : uiTreePaths.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            String uiPath = entry.getValue();
            if (uiPath != null && !uiPath.isBlank()) {
                merged.put(key, uiPath);
            } else if (!merged.containsKey(key)) {
                merged.put(key, uiPath);
            }
        }
        return merged;
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

    private void restoreWorkbenchSettings(WorkspaceState state) {
        if (state == null) {
            return;
        }
        applyCheckboxState(repeaterBtn, state.workbenchRepeaterSelected, false);
        applyCheckboxState(sitemapBtn, state.workbenchSitemapSelected, false);
        applyCheckboxState(intruderBtn, state.workbenchIntruderSelected, false);
        applySpinnerState(delaySpinner, state.workbenchDelayMs, 0);
        applyCheckboxState(debugRawRequestBox, state.workbenchDebugRawRequest, false);
        applyTabIndex(workbenchDetailTabs, state.workbenchDetailTabIndex);
    }

    private void restoreRunnerSettings(WorkspaceState state) {
        if (state == null) {
            return;
        }
        applySpinnerState(runnerDelaySpinner, state.runnerDelayMs, 0);
        applySpinnerState(runnerRetriesSpinner, state.runnerRetries, 0);
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
        CollectionRef prevVars = getSelectedCollectionRef(varsCollectionCombo);
        suppressVariablesCollectionSelectionPrompt = true;
        try {
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
        } finally {
            suppressVariablesCollectionSelectionPrompt = false;
        }
        activeVariablesCollectionRef = getSelectedCollectionRef(varsCollectionCombo);

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
        if (!variablesDirty) {
            renderEffectiveVariablesForSelectedCollection();
        } else {
            updateVariablesSaveStatusForSelection();
        }
        if (requestEditor != null && requestEditor.getCurrentCollection() != null) {
            syncRequestEditorRuntimeContext(requestEditor.getCurrentRequest(), requestEditor.getCurrentCollection());
        }
        if (varsCollectionCombo != null && varsCollectionCombo.getSelectedItem() != null) {
            updateVariablesSaveStatusForSelection();
        }
        if (oauth2CollectionCombo != null && oauth2CollectionCombo.getSelectedItem() != null) {
            setOAuth2AutosaveStatus("Autosave idle.", Color.GRAY);
        }
    }

    private void updateScopeControlState() {
        updateEnvironmentUiState();
        syncOAuth2UiState();
        if (actionsBtn != null) {
            actionsBtn.setEnabled(!loadedCollections.isEmpty());
        }
    }

    private void updateEnvironmentUiState() {
        EnvironmentProfile selected = getSelectedEnvironmentProfile();
        boolean hasSelection = selected != null;
        if (environmentRawArea != null) {
            environmentRawArea.setEnabled(hasSelection);
            environmentRawArea.setEditable(hasSelection);
        }
        if (environmentTable != null) {
            environmentTable.setEnabled(hasSelection);
        }
        if (environmentRawViewBtn != null) {
            environmentRawViewBtn.setEnabled(hasSelection);
        }
        if (environmentTableViewBtn != null) {
            environmentTableViewBtn.setEnabled(hasSelection);
        }
        if (environmentSaveBtn != null) {
            environmentSaveBtn.setEnabled(hasSelection && environmentDirty);
        }
        if (environmentDuplicateBtn != null) {
            environmentDuplicateBtn.setEnabled(hasSelection);
        }
        if (environmentDeleteBtn != null) {
            environmentDeleteBtn.setEnabled(hasSelection);
        }
        if (environmentSetActiveBtn != null) {
            environmentSetActiveBtn.setEnabled(hasSelection && !Objects.equals(activeEnvironmentId, selected.id));
        }
        if (environmentExportBtn != null) {
            environmentExportBtn.setEnabled(hasSelection);
        }
        if (environmentStatusLabel != null) {
            environmentStatusLabel.setText(hasSelection
                    ? (Objects.equals(activeEnvironmentId, selected.id) ? "Active: " : "Selected: ") + selected.displayName() + (environmentDirty ? " | Unsaved changes" : " | Saved")
                    : "No Environment selected.");
            environmentStatusLabel.setForeground(environmentDirty ? new Color(150, 90, 0) : Color.GRAY);
        }
        if (environmentHintLabel != null) {
            environmentHintLabel.setText("Active Environment values apply to previews, sends, runner, Repeater, Intruder, and Sitemap.");
        }
        if (environmentCombo != null && environmentProfiles.isEmpty() && environmentCombo.getItemCount() > 0) {
            environmentCombo.setSelectedIndex(0);
        }
        syncWorkbenchEnvironmentControls();
    }

    private void syncWorkbenchEnvironmentControls() {
        if (workbenchEnvironmentCombo == null) {
            return;
        }
        suppressWorkbenchEnvironmentEvents = true;
        try {
            EnvironmentProfile active = getActiveEnvironment();
            String selectedIdBefore = active != null ? active.id : null;
            workbenchEnvironmentCombo.removeAllItems();
            workbenchEnvironmentCombo.addItem(new EnvironmentRef(null, "No Environment"));
            for (EnvironmentProfile profile : environmentProfiles) {
                if (profile == null) {
                    continue;
                }
                profile.ensureDefaults();
                profile.ensureId();
                workbenchEnvironmentCombo.addItem(new EnvironmentRef(profile, profile.displayName()));
            }
            if (selectedIdBefore != null && selectWorkbenchEnvironmentById(selectedIdBefore)) {
                // selected active env
            } else {
                workbenchEnvironmentCombo.setSelectedIndex(0);
            }
        } finally {
            suppressWorkbenchEnvironmentEvents = false;
        }
        if (workbenchEnvironmentImportBtn != null) {
            workbenchEnvironmentImportBtn.setEnabled(true);
        }
    }

    private boolean selectWorkbenchEnvironmentById(String environmentId) {
        if (workbenchEnvironmentCombo == null) {
            return false;
        }
        for (int i = 0; i < workbenchEnvironmentCombo.getItemCount(); i++) {
            EnvironmentRef ref = workbenchEnvironmentCombo.getItemAt(i);
            if (ref != null && ref.environment != null && Objects.equals(ref.environment.id, environmentId)) {
                workbenchEnvironmentCombo.setSelectedIndex(i);
                return true;
            }
        }
        if (workbenchEnvironmentCombo.getItemCount() > 0) {
            workbenchEnvironmentCombo.setSelectedIndex(0);
        }
        return false;
    }

    private void handleWorkbenchEnvironmentSelectionChanged() {
        if (suppressWorkbenchEnvironmentEvents || workbenchEnvironmentCombo == null) {
            return;
        }
        EnvironmentRef ref = (EnvironmentRef) workbenchEnvironmentCombo.getSelectedItem();
        String nextId = ref != null && ref.environment != null ? ref.environment.id : null;
        if (Objects.equals(activeEnvironmentId, nextId)) {
            return;
        }
        setActiveEnvironmentId(nextId);
    }

    private void syncOAuth2UiState() {
        EnvironmentProfile active = getActiveEnvironment();
        boolean hasActive = active != null;
        if (oauth2ActiveEnvironmentLabel != null) {
            oauth2ActiveEnvironmentLabel.setText("Active Environment: " + (hasActive ? active.displayName() : "No Environment"));
        }
        if (oauth2BindingHintLabel != null) {
            oauth2BindingHintLabel.setText(hasActive
                    ? "OAuth2 config and token outputs are stored in the active environment."
                    : "Create or select an Active Environment before configuring OAuth2.");
            oauth2BindingHintLabel.setForeground(hasActive ? Color.DARK_GRAY : Color.GRAY);
        }
        if (oauth2StatusLabel != null && !hasActive) {
            oauth2StatusLabel.setText("Create or select an Active Environment before fetching tokens.");
            oauth2StatusLabel.setForeground(Color.GRAY);
        }
        if (oauth2Panel != null) {
            oauth2Panel.setEditable(hasActive);
        }
        syncOAuth2PanelFromActiveEnvironment();
        setBindingCombosEnabled(hasActive);
        refreshOAuth2BindingCandidates();
    }

    private void syncOAuth2PanelFromActiveEnvironment() {
        EnvironmentProfile active = getActiveEnvironment();
        if (oauth2Panel == null) {
            return;
        }
        if (active == null) {
            oauth2Panel.populateFromOAuth2Map(Collections.emptyMap());
            return;
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (active.oauth2 != null && active.oauth2.config != null) {
            values.putAll(active.oauth2.config);
        }
        oauth2Panel.populateFromOAuth2Map(values);
    }

    private void handleEnvironmentSelectionChanged() {
        if (suppressEnvironmentEditorEvents) {
            return;
        }
        if (environmentDirty) {
            commitEnvironmentEditorToSelectedProfile();
        }
        renderSelectedEnvironmentIntoEditor();
        updateEnvironmentUiState();
        syncOAuth2UiState();
        syncActiveEnvironmentToEditors();
    }

    private EnvironmentProfile getSelectedEnvironmentProfile() {
        EnvironmentRef ref = environmentCombo != null && environmentCombo.getSelectedItem() != null
                ? (EnvironmentRef) environmentCombo.getSelectedItem()
                : null;
        return ref != null ? ref.environment : null;
    }

    private boolean selectEnvironmentById(String environmentId) {
        if (environmentCombo == null) {
            return false;
        }
        for (int i = 0; i < environmentCombo.getItemCount(); i++) {
            EnvironmentRef ref = environmentCombo.getItemAt(i);
            if (ref != null && ref.environment != null && Objects.equals(ref.environment.id, environmentId)) {
                environmentCombo.setSelectedIndex(i);
                return true;
            }
        }
        if (environmentCombo.getItemCount() > 0) {
            environmentCombo.setSelectedIndex(0);
        }
        return false;
    }

    private void updateEnvironmentComboModel() {
        if (environmentCombo == null) {
            return;
        }
        String selectedId = getSelectedEnvironmentProfile() != null ? getSelectedEnvironmentProfile().id : null;
        suppressEnvironmentEditorEvents = true;
        try {
            environmentCombo.removeAllItems();
            environmentCombo.addItem(new EnvironmentRef(null, "No Environment"));
            for (EnvironmentProfile profile : environmentProfiles) {
                if (profile != null) {
                    profile.ensureDefaults();
                    profile.ensureId();
                    environmentCombo.addItem(new EnvironmentRef(profile, profile.displayName()));
                }
            }
            String idToSelect = activeEnvironmentId != null ? activeEnvironmentId : selectedId;
            if (idToSelect != null) {
                if (!selectEnvironmentById(idToSelect)) {
                    environmentCombo.setSelectedIndex(0);
                }
            } else {
                environmentCombo.setSelectedIndex(0);
            }
        } finally {
            suppressEnvironmentEditorEvents = false;
        }
    }

    private void handleEnvironmentImport() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import Environment");
        chooser.setFileFilter(new FileNameExtensionFilter("Environment files", "json", "bru", "env"));
        if (chooser.showOpenDialog(mainPanel) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (file == null) {
            return;
        }
        try {
            List<EnvironmentProfile> imported = burp.utils.EnvironmentImportService.importEnvironment(file);
            if (imported == null || imported.isEmpty()) {
                appendImportLog("No environment profiles found in " + file.getName() + ".");
                return;
            }
            addImportedEnvironmentProfiles(imported, file.getName());
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            appendImportLog("Environment import failed: " + message);
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(mainPanel,
                        "Environment import failed:\n" + message,
                        "Import Failed",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    void addImportedEnvironmentProfiles(List<EnvironmentProfile> imported, String sourceName) {
        if (imported == null || imported.isEmpty()) {
            return;
        }
        for (EnvironmentProfile profile : imported) {
            if (profile == null) {
                continue;
            }
            profile.ensureDefaults();
            profile.ensureId();
            environmentProfiles.add(profile);
        }
        if (activeEnvironmentId == null && imported.get(0) != null) {
            activeEnvironmentId = imported.get(0).id;
        }
        updateEnvironmentComboModel();
        selectEnvironmentById(imported.get(0).id);
        renderSelectedEnvironmentIntoEditor();
        updateEnvironmentUiState();
        syncWorkbenchEnvironmentControls();
        syncOAuth2UiState();
        syncActiveEnvironmentToEditors();
        notifyWorkspaceChangedImmediately();
        appendImportLog("Imported " + imported.size() + " environment profile(s) from " + sourceName + ".");
        for (EnvironmentProfile profile : imported) {
            if (profile == null) {
                continue;
            }
            int count = profile.variables != null ? profile.variables.size() : 0;
            appendImportLog("Environment \"" + profile.displayName() + "\" variables: " + count + ".");
            if (count == 0) {
                appendImportLog("Environment \"" + profile.displayName() + "\" imported with 0 variables.");
                if (!GraphicsEnvironment.isHeadless()) {
                    JOptionPane.showMessageDialog(mainPanel,
                            "Environment imported but contains 0 variables.",
                            "Import Warning",
                            JOptionPane.WARNING_MESSAGE);
                }
            }
        }
    }

    private void handleEnvironmentNew() {
        String name = JOptionPane.showInputDialog(mainPanel, "Environment name:", "New Environment", JOptionPane.PLAIN_MESSAGE);
        if (name == null) {
            return;
        }
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = name.trim().isEmpty() ? "Environment" : name.trim();
        profile.sourceFormat = "manual";
        profile.sourceFileName = null;
        profile.ensureId();
        profile.ensureDefaults();
        environmentProfiles.add(profile);
        if (activeEnvironmentId == null) {
            activeEnvironmentId = profile.id;
        }
        updateEnvironmentComboModel();
        selectEnvironmentById(profile.id);
        renderSelectedEnvironmentIntoEditor();
        updateEnvironmentUiState();
        syncOAuth2UiState();
        syncActiveEnvironmentToEditors();
        notifyWorkspaceChangedImmediately();
        appendImportLog("Created environment \"" + profile.displayName() + "\".");
    }

    private void handleEnvironmentDuplicate() {
        EnvironmentProfile selected = getSelectedEnvironmentProfile();
        if (selected == null) {
            return;
        }
        EnvironmentProfile duplicate = selected.copy();
        duplicate.id = null;
        duplicate.ensureId();
        duplicate.name = selected.displayName() + " Copy";
        duplicate.ensureDefaults();
        environmentProfiles.add(duplicate);
        updateEnvironmentComboModel();
        selectEnvironmentById(duplicate.id);
        renderSelectedEnvironmentIntoEditor();
        updateEnvironmentUiState();
        syncOAuth2UiState();
        notifyWorkspaceChangedImmediately();
        appendImportLog("Duplicated environment \"" + selected.displayName() + "\".");
    }

    private void handleEnvironmentDelete() {
        EnvironmentProfile selected = getSelectedEnvironmentProfile();
        if (selected == null) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(mainPanel,
                "Delete environment \"" + selected.displayName() + "\"?",
                "Delete Environment", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        String removedId = selected.id;
        environmentProfiles.removeIf(profile -> profile != null && Objects.equals(profile.id, removedId));
        if (Objects.equals(activeEnvironmentId, removedId)) {
            activeEnvironmentId = null;
        }
        updateEnvironmentComboModel();
        renderSelectedEnvironmentIntoEditor();
        updateEnvironmentUiState();
        syncOAuth2UiState();
        syncActiveEnvironmentToEditors();
        notifyWorkspaceChangedImmediately();
        appendImportLog("Deleted environment \"" + selected.displayName() + "\".");
    }

    private void handleEnvironmentSetActive() {
        EnvironmentProfile selected = getSelectedEnvironmentProfile();
        if (selected == null) {
            return;
        }
        setActiveEnvironmentId(selected.id);
        appendImportLog("Active environment set to \"" + selected.displayName() + "\".");
    }

    private void handleEnvironmentExport() {
        EnvironmentProfile selected = getSelectedEnvironmentProfile();
        if (selected == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export Environment");
        chooser.setSelectedFile(new File(selected.displayName().replaceAll("[^a-zA-Z0-9._-]+", "_") + ".json"));
        if (chooser.showSaveDialog(mainPanel) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (file == null) {
            return;
        }
        try {
            EnvironmentProfile copy = selected.copy();
            copy.ensureDefaults();
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            root.addProperty("name", copy.displayName());
            com.google.gson.JsonObject values = new com.google.gson.JsonObject();
            for (Map.Entry<String, String> entry : copy.variables.entrySet()) {
                values.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("variables", values);
            com.google.gson.JsonObject oauth2 = new com.google.gson.JsonObject();
            com.google.gson.JsonObject config = new com.google.gson.JsonObject();
            for (Map.Entry<String, String> entry : copy.oauth2.config.entrySet()) {
                config.addProperty(entry.getKey(), entry.getValue());
            }
            oauth2.add("config", config);
            com.google.gson.JsonObject bindings = new com.google.gson.JsonObject();
            for (Map.Entry<String, String> entry : copy.oauth2.outputBindings.entrySet()) {
                bindings.addProperty(entry.getKey(), entry.getValue());
            }
            oauth2.add("outputBindings", bindings);
            root.add("oauth2", oauth2);
            Files.writeString(file.toPath(), new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
            appendImportLog("Exported environment \"" + copy.displayName() + "\" to " + file.getName() + ".");
        } catch (Exception e) {
            appendImportLog("Environment export failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private void markEnvironmentDirty() {
        if (suppressEnvironmentEditorEvents) {
            return;
        }
        environmentDirty = true;
        if (environmentStatusLabel != null) {
            environmentStatusLabel.setText("Unsaved changes");
            environmentStatusLabel.setForeground(new Color(150, 90, 0));
        }
        if (environmentSaveBtn != null) {
            environmentSaveBtn.setEnabled(true);
        }
    }

    private void switchVarsView(boolean tableView) {

        if (varsEditorCardPanel == null) return;
        if (tableView) {
            resetVariablesRawEditingSession();
            renderVarsTableFromRaw();
        } else {
            resetVariablesTableEditingSession();
            syncRawFromVarsTable();
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
            RequestEditorStateMapper.ensureStarterRow(varsTableModel);
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
            sb.append("# Environment variables\n");
            for (Map.Entry<String, String> e : new TreeMap<>(vars).entrySet()) {
                sb.append(e.getKey()).append("=").append(e.getValue()).append("\n");
            }
            setVariablesEditorTextPreservingView(sb.toString());
        } finally {
            suppressVariablesAutosave = false;
        }
    }

    private void handleVariablesRawDocumentEdit() {
        if (suppressVariablesAutosave) {
            return;
        }
        markVariablesRawEditingActive();
        markVariablesDirty();
        refreshActiveRequestEditorRuntimeContextFromVariablesDraft();
    }

    private void handleVariablesTableModelEdit() {
        if (suppressVariablesAutosave) {
            return;
        }
        markVariablesTableEditingActive();
        markVariablesDirty();
        refreshActiveRequestEditorRuntimeContextFromVariablesDraft();
    }

    private void refreshActiveRequestEditorRuntimeContextFromVariablesDraft() {
        if (requestEditor == null) {
            return;
        }
        ApiRequest req = requestEditor.getCurrentRequest();
        ApiCollection col = requestEditor.getCurrentCollection();
        if (req == null || col == null) {
            return;
        }
        CollectionRef varsRef = getSelectedCollectionRef(varsCollectionCombo);
        if (varsRef == null || varsRef.collection != col) {
            return;
        }
        syncRequestEditorRuntimeContext(req, col);
    }

    private void markVariablesDirty() {
        variablesDirty = true;
        if (varsCollectionCombo != null && varsCollectionCombo.getSelectedItem() != null) {
            setVarsAutosaveStatus("Unsaved changes.", new Color(184, 134, 11));
        }
    }

    private void clearVariablesDirty() {
        variablesDirty = false;
    }

    private void updateVariablesSaveStatusForSelection() {
        if (varsCollectionCombo == null) {
            return;
        }
        CollectionRef ref = getSelectedCollectionRef(varsCollectionCombo);
        if (ref == null) {
            setVarsAutosaveStatus("Select a collection to edit variables.", Color.GRAY);
        } else if (variablesDirty) {
            setVarsAutosaveStatus("Unsaved changes.", new Color(184, 134, 11));
        } else {
            setVarsAutosaveStatus("Saved.", Color.GRAY);
        }
    }

    private void handleVariablesCollectionSelectionChange() {
        if (suppressVariablesCollectionSelectionPrompt) {
            return;
        }
        CollectionRef selected = getSelectedCollectionRef(varsCollectionCombo);
        if (Objects.equals(selected != null ? selected.collection : null, activeVariablesCollectionRef != null ? activeVariablesCollectionRef.collection : null)) {
            updateScopeControlState();
            return;
        }
        CollectionRef previous = activeVariablesCollectionRef;
        if (variablesDirty && previous != null) {
            int choice = promptVariablesCollectionSwitch(previous, selected);
            applyVariablesCollectionSwitchDecision(previous, selected, choice);
            return;
        }
        acceptVariablesCollectionSelection(selected, true);
    }

    private void acceptVariablesCollectionSelection(CollectionRef selected, boolean render) {
        activeVariablesCollectionRef = selected;
        resetVariablesRawEditingSession();
        resetVariablesTableEditingSession();
        if (render) {
            renderEffectiveVariablesForSelectedCollection();
        } else {
            updateScopeControlState();
            updateVariablesSaveStatusForSelection();
        }
    }

    private void revertVariablesCollectionSelection(CollectionRef previous) {
        suppressVariablesCollectionSelectionPrompt = true;
        try {
            if (previous == null) {
                varsCollectionCombo.setSelectedIndex(-1);
            } else {
                for (int i = 0; i < varsCollectionCombo.getItemCount(); i++) {
                    if (varsCollectionCombo.getItemAt(i).collection == previous.collection) {
                        varsCollectionCombo.setSelectedIndex(i);
                        break;
                    }
                }
            }
        } finally {
            suppressVariablesCollectionSelectionPrompt = false;
        }
        activeVariablesCollectionRef = previous;
        updateScopeControlState();
        updateVariablesSaveStatusForSelection();
    }

    private int promptVariablesCollectionSwitch(CollectionRef previous, CollectionRef next) {
        String nextLabel = next != null ? next.label : "no collection";
        String previousLabel = previous != null ? previous.label : "the current collection";
        return JOptionPane.showOptionDialog(
                mainPanel,
                "You have unsaved changes in " + previousLabel + ".\nDo you want to save them before switching to " + nextLabel + "?",
                "Unsaved Variables",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                new Object[]{"Save", "Discard", "Cancel"},
                "Save");
    }

    void applyVariablesCollectionSwitchDecision(CollectionRef previous, CollectionRef next, int choice) {
        if (choice == 2 || choice < 0) {
            revertVariablesCollectionSelection(previous);
            return;
        }
        if (choice == 0 && previous != null && previous.collection != null) {
            commitVariablesDraftToCollection(previous.collection, previous.label);
        } else if (choice == 1) {
            variablesDirty = false;
        }
        acceptVariablesCollectionSelection(next, true);
    }

    private void installVariablesSaveShortcut(JComponent component) {
        if (component == null) {
            return;
        }
        KeyStroke saveKey = KeyStroke.getKeyStroke(KeyEvent.VK_S, variablesSaveShortcutMask());
        component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(saveKey, "saveVariablesDraft");
        component.getActionMap().put("saveVariablesDraft", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                bindVarsToSelectedCollection();
            }
        });
    }

    private int variablesSaveShortcutMask() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("mac") ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
    }

    private CollectionRef getSelectedCollectionRef(JComboBox<CollectionRef> combo) {
        return combo != null && combo.getSelectedItem() != null ? (CollectionRef) combo.getSelectedItem() : null;
    }

    private boolean commitVariablesDraftToCollection(ApiCollection col, String label) {
        if (col == null) {
            setVarsAutosaveStatus("Select a collection to edit variables.", Color.GRAY);
            return false;
        }
        try {
            if (isVarsTableViewActive()) {
                syncRawFromVarsTable();
            }
            Map<String, String> vars = parseRuntimeOverrideSection();
            if (Objects.equals(col.runtimeVars, vars)) {
                clearVariablesDirty();
                setVarsAutosaveStatus("No changes to save.", Color.GRAY);
                syncRequestEditorRuntimeContext(requestEditor != null ? requestEditor.getCurrentRequest() : null,
                        requestEditor != null ? requestEditor.getCurrentCollection() : null);
                return false;
            }
            col.replaceRuntimeVars(vars);
            clearVariablesDirty();
            appendImportLog("Variables saved to \"" + label + "\": " + vars.size() + " var(s).");
            setVarsAutosaveStatus("Saved to " + label + " (" + vars.size() + " var(s)).", new Color(0, 128, 0));
            syncRequestEditorRuntimeContext(requestEditor != null ? requestEditor.getCurrentRequest() : null,
                    requestEditor != null ? requestEditor.getCurrentCollection() : null);
            return true;
        } catch (Exception ex) {
            setVarsAutosaveStatus("Save failed: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()), Color.RED);
            return false;
        }
    }

    private void markVariablesRawEditingActive() {
        variablesRawEditingActive = true;
        variablesRawLastEditAt = System.currentTimeMillis();
        if (variablesRawEditIdleTimer != null) {
            variablesRawEditIdleTimer.restart();
        }
    }

    private void markVariablesTableEditingActive() {
        variablesTableEditingActive = true;
        variablesTableLastEditAt = System.currentTimeMillis();
        if (variablesTableEditIdleTimer != null) {
            variablesTableEditIdleTimer.restart();
        }
    }

    private void resetVariablesRawEditingSession() {
        variablesRawEditingActive = false;
        variablesRawRefreshPending = false;
        variablesRawRefreshPendingCollection = null;
        variablesRawLastEditAt = 0L;
        if (variablesRawEditIdleTimer != null) {
            variablesRawEditIdleTimer.stop();
        }
    }

    private void resetVariablesTableEditingSession() {
        variablesTableEditingActive = false;
        variablesTableRefreshPending = false;
        variablesTableRefreshPendingCollection = null;
        variablesTableAutosavePending = false;
        variablesTableAutosavePendingCollection = null;
        variablesTableLastEditAt = 0L;
        if (variablesTableEditIdleTimer != null) {
            variablesTableEditIdleTimer.stop();
        }
    }

    private void expireVariablesRawEditingSession() {
        if (!variablesRawEditingActive) {
            return;
        }
        variablesRawEditingActive = false;
        variablesRawRefreshPending = false;
        variablesRawRefreshPendingCollection = null;
    }

    private void expireVariablesTableEditingSession() {
        if (!variablesTableEditingActive) {
            return;
        }
        variablesTableEditingActive = false;
        variablesTableRefreshPending = false;
        variablesTableRefreshPendingCollection = null;
        variablesTableAutosavePending = false;
        variablesTableAutosavePendingCollection = null;
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
    // Legacy runtime vars compatibility
    // ========================================================================
    private void scheduleVariablesAutosave() {
        // Legacy runtime vars now save only through the environment editor.
    }

    void expireVariablesRawEditingForTests() {
        expireVariablesRawEditingSession();
    }

    void expireVariablesTableEditingForTests() {
        expireVariablesTableEditingSession();
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
        if (oauth2StatusLabel != null) {
            oauth2StatusLabel.setText(text);
            oauth2StatusLabel.setForeground(color != null ? color : Color.GRAY);
        }
    }

    private void bindVarsToSelectedCollection() {
        CollectionRef ref = (CollectionRef) varsCollectionCombo.getSelectedItem();
        if (ref == null) {
            appendImportLog("Variables: No collection selected for binding.");
            setVarsAutosaveStatus("Select a collection to edit variables.", Color.GRAY);
            return;
        }
        commitVariablesDraftToCollection(ref.collection, ref.label);
    }

    private void bindVarsToAllCollections() {
        if (loadedCollections.isEmpty()) {
            appendImportLog("Variables: No collections loaded.");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(mainPanel,
            "This will overwrite scoped variables in ALL " + loadedCollections.size() + " collection(s) with the current text. Continue?",
            "Confirm Save to All Environments", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        Map<String, String> vars = parseRuntimeOverrideSection();
        for (ApiCollection col : loadedCollections) {
            col.replaceRuntimeVars(vars);
        }
        appendImportLog("Variables bound to all " + loadedCollections.size() + " collection(s): " + vars.size() + " var(s).");
        clearVariablesDirty();
        syncRequestEditorRuntimeContext(requestEditor != null ? requestEditor.getCurrentRequest() : null,
                requestEditor != null ? requestEditor.getCurrentCollection() : null);
        setVarsAutosaveStatus("Saved to all " + loadedCollections.size() + " collection(s).", new Color(0, 128, 0));
    }

    private JPanel createDiagnosticsTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        diagnosticsArea = new JTextArea(24, 100);
        diagnosticsArea.setEditable(false);
        diagnosticsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        diagnosticsArea.setText(DIAGNOSTICS_PLACEHOLDER_TEXT);
        diagnosticsArea.setLineWrap(false);
        diagnosticsArea.setWrapStyleWord(false);

        JScrollPane scrollPane = new JScrollPane(diagnosticsArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Snapshot"));
        panel.add(scrollPane, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton refreshButton = new JButton("Refresh Snapshot");
        refreshButton.addActionListener(e -> refreshDiagnosticsSnapshot());
        JButton exportButton = new JButton("Export");
        exportButton.addActionListener(e -> exportDiagnosticsSnapshot());
        buttons.add(refreshButton);
        buttons.add(exportButton);
        panel.add(buttons, BorderLayout.NORTH);

        return panel;
    }

    void refreshDiagnosticsSnapshot() {
        if (diagnosticsArea == null) {
            return;
        }
        diagnosticsArea.setText(buildDiagnosticsSnapshot());
        diagnosticsArea.setCaretPosition(0);
    }

    String buildDiagnosticsSnapshot() {
        StringBuilder sb = new StringBuilder();
        appendDiagnosticsSectionHeader(sb, "Extension / Runtime Info");
        appendDiagnosticsLine(sb, "snapshot.timestamp", DIAGNOSTICS_TIMESTAMP_FORMAT.format(ZonedDateTime.now()));
        appendDiagnosticsLine(sb, "extension.class", getClass().getName());
        appendDiagnosticsLine(sb, "extension.version", safePackageVersion());
        appendDiagnosticsLine(sb, "java.version", System.getProperty("java.version", "unknown"));
        appendDiagnosticsLine(sb, "java.vm.name", System.getProperty("java.vm.name", "unknown"));
        appendDiagnosticsLine(sb, "os.name", System.getProperty("os.name", "unknown"));
        appendDiagnosticsLine(sb, "os.version", System.getProperty("os.version", "unknown"));
        sb.append('\n');

        appendDiagnosticsSectionHeader(sb, "UI State Summary");
        appendDiagnosticsLine(sb, "selectedTopLevelTab", safeSelectedTabTitle());
        appendDiagnosticsLine(sb, "selectedVariablesCollection", safeCollectionRefLabel(getSelectedCollectionRef(varsCollectionCombo)));
        appendDiagnosticsLine(sb, "selectedOAuth2Collection", safeCollectionRefLabel(getSelectedCollectionRef(oauth2CollectionCombo)));
        ApiCollection selectedWorkbenchCollection = requestEditor != null ? requestEditor.getCurrentCollection() : null;
        ApiRequest selectedWorkbenchRequest = requestEditor != null ? requestEditor.getCurrentRequest() : null;
        appendDiagnosticsLine(sb, "selectedWorkbenchRequestCollection", safeCollectionName(selectedWorkbenchCollection));
        appendDiagnosticsLine(sb, "selectedWorkbenchRequestName", safeRequestName(selectedWorkbenchRequest));
        appendDiagnosticsLine(sb, "loadedCollections.count", loadedCollections.size());
        sb.append('\n');

        appendDiagnosticsSectionHeader(sb, "Main Request Tree Diagnostics");
        appendMainRequestTreeDiagnostics(sb);
        sb.append('\n');

        appendDiagnosticsSectionHeader(sb, "Collection Summary");
        appendCollectionSummary(sb);
        sb.append('\n');

        appendDiagnosticsSectionHeader(sb, "Variables / OAuth2 Summary");
        appendVariablesOAuth2Summary(sb);
        sb.append('\n');

        appendDiagnosticsSectionHeader(sb, "Warnings / Notes");
        appendWarnings(sb);
        return sb.toString();
    }

    void writeDiagnosticsSnapshot(File file, String snapshotText) throws IOException {
        if (file == null) {
            throw new IOException("No export file selected.");
        }
        Files.writeString(file.toPath(), snapshotText != null ? snapshotText : "", StandardCharsets.UTF_8);
    }

    private void exportDiagnosticsSnapshot() {
        String snapshot = buildDiagnosticsSnapshot();
        if (diagnosticsArea != null) {
            diagnosticsArea.setText(snapshot);
            diagnosticsArea.setCaretPosition(0);
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export Diagnostics Snapshot");
        chooser.setSelectedFile(new File("diagnostics-snapshot.txt"));
        if (chooser.showSaveDialog(mainPanel) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selected = chooser.getSelectedFile();
        if (selected == null) {
            return;
        }
        if (selected.exists()) {
            int confirm = JOptionPane.showConfirmDialog(
                    mainPanel,
                    "Overwrite existing file?\n" + selected.getAbsolutePath(),
                    "Confirm Export",
                    JOptionPane.YES_NO_OPTION
            );
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
        }
        try {
            writeDiagnosticsSnapshot(selected, snapshot);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                    mainPanel,
                    "Failed to export diagnostics: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
                    "Export Diagnostics",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void appendDiagnosticsSectionHeader(StringBuilder sb, String title) {
        sb.append("=== ").append(title).append(" ===\n");
    }

    private void appendDiagnosticsLine(StringBuilder sb, String key, Object value) {
        sb.append(key).append('=').append(value != null ? value : "null").append('\n');
    }

    private void appendMainRequestTreeDiagnostics(StringBuilder sb) {
        boolean treeExists = requestTree != null;
        appendDiagnosticsLine(sb, "requestTree.exists", treeExists);
        appendDiagnosticsLine(sb, "requestTree.showing", treeExists && requestTree.isShowing());
        appendDiagnosticsLine(sb, "requestTree.valid", treeExists && requestTree.isValid());
        appendDiagnosticsLine(sb, "requestTree.displayable", treeExists && requestTree.isDisplayable());
        appendDiagnosticsLine(sb, "requestTree.visible", treeExists && requestTree.isVisible());
        appendDiagnosticsLine(sb, "requestTree.rootVisible", treeExists && requestTree.isRootVisible());
        appendDiagnosticsLine(sb, "requestTree.showsRootHandles", treeExists && requestTree.getShowsRootHandles());
        appendDiagnosticsLine(sb, "requestTree.rowHeight", treeExists ? requestTree.getRowHeight() : "absent");
        appendDiagnosticsLine(sb, "requestTree.scrollsOnExpand", treeExists && requestTree.getScrollsOnExpand());

        TreeUI ui = treeExists ? requestTree.getUI() : null;
        appendDiagnosticsLine(sb, "treeUI", ui != null ? ui.getClass().getName() : "absent");
        if (ui instanceof BasicTreeUI) {
            BasicTreeUI basicTreeUI = (BasicTreeUI) ui;
            appendDiagnosticsLine(sb, "treeUI.basicTreeUI.leftChildIndent", basicTreeUI.getLeftChildIndent());
            appendDiagnosticsLine(sb, "treeUI.basicTreeUI.rightChildIndent", basicTreeUI.getRightChildIndent());
        }

        int rowCount = treeExists ? requestTree.getRowCount() : 0;
        appendDiagnosticsLine(sb, "requestTree.rowCount", treeExists ? rowCount : "absent");
        appendDiagnosticsLine(sb, "requestTree.selectedRow", treeExists ? requestTree.getLeadSelectionRow() : "absent");
        appendDiagnosticsLine(sb, "requestTree.selectedPath", treeExists ? safeTreePath(requestTree.getSelectionPath()) : "absent");
        JViewport viewport = requestTreeScrollPane != null ? requestTreeScrollPane.getViewport() : null;
        appendDiagnosticsLine(sb, "requestTree.viewport.position", viewport != null ? viewport.getViewPosition() : "absent");
        appendDiagnosticsLine(sb, "requestTree.viewport.extent", viewport != null ? viewport.getExtentSize() : "absent");
        for (int row = 0; row < 4; row++) {
            appendDiagnosticsLine(sb, "requestTree.rowBounds[" + row + "]", treeExists && row < rowCount ? requestTree.getRowBounds(row) : "absent");
        }
    }

    private void appendCollectionSummary(StringBuilder sb) {
        appendDiagnosticsLine(sb, "loadedCollections.count", loadedCollections.size());
        if (loadedCollections.isEmpty()) {
            appendDiagnosticsLine(sb, "loadedCollections", "none");
            return;
        }
        for (int i = 0; i < loadedCollections.size(); i++) {
            ApiCollection collection = loadedCollections.get(i);
            appendDiagnosticsLine(sb, "loadedCollections[" + i + "].name", safeCollectionName(collection));
            appendDiagnosticsLine(sb, "loadedCollections[" + i + "].requestCount", collection != null && collection.requests != null ? collection.requests.size() : 0);
            appendDiagnosticsLine(sb, "loadedCollections[" + i + "].runtimeVars.count", safeMapSize(collection != null ? collection.runtimeVars : null));
            appendDiagnosticsLine(sb, "loadedCollections[" + i + "].runtimeOAuth2.count", safeMapSize(collection != null ? collection.runtimeOAuth2 : null));
        }
    }

    private void appendVariablesOAuth2Summary(StringBuilder sb) {
        CollectionRef varsRef = getSelectedCollectionRef(varsCollectionCombo);
        CollectionRef oauthRef = getSelectedCollectionRef(oauth2CollectionCombo);
        appendDiagnosticsLine(sb, "selectedVariablesCollection", safeCollectionRefLabel(varsRef));
        appendDiagnosticsLine(sb, "selectedVariables.runtimeVars.count", varsRef != null && varsRef.collection != null ? safeMapSize(varsRef.collection.runtimeVars) : 0);
        appendDiagnosticsLine(sb, "selectedVariables.runtimeOAuth2.count", varsRef != null && varsRef.collection != null ? safeMapSize(varsRef.collection.runtimeOAuth2) : 0);
        appendDiagnosticsLine(sb, "selectedOAuth2Collection", safeCollectionRefLabel(oauthRef));
        appendDiagnosticsLine(sb, "selectedOAuth2.runtimeVars.count", oauthRef != null && oauthRef.collection != null ? safeMapSize(oauthRef.collection.runtimeVars) : 0);
        appendDiagnosticsLine(sb, "selectedOAuth2.runtimeOAuth2.count", oauthRef != null && oauthRef.collection != null ? safeMapSize(oauthRef.collection.runtimeOAuth2) : 0);
    }

    private void appendWarnings(StringBuilder sb) {
        List<String> warnings = new ArrayList<>();
        if (loadedCollections.isEmpty()) {
            warnings.add("No collections loaded.");
        }
        if (requestTree == null) {
            warnings.add("Main request tree is unavailable.");
        } else {
            if (!requestTree.isShowing()) {
                warnings.add("Main request tree is not showing.");
            }
            if (requestTree.getRowCount() == 0 && !loadedCollections.isEmpty()) {
                warnings.add("Main request tree has zero rows while collections are loaded.");
            }
        }
        if (varsCollectionCombo != null && getSelectedCollectionRef(varsCollectionCombo) == null) {
            warnings.add("No Variables collection selected.");
        }
        if (oauth2CollectionCombo != null && getSelectedCollectionRef(oauth2CollectionCombo) == null) {
            warnings.add("No OAuth2 collection selected.");
        }
        if (warnings.isEmpty()) {
            appendDiagnosticsLine(sb, "notes", "none");
            return;
        }
        for (int i = 0; i < warnings.size(); i++) {
            appendDiagnosticsLine(sb, "note[" + i + "]", warnings.get(i));
        }
    }

    private String safeSelectedTabTitle() {
        if (tabbedPane == null || tabbedPane.getTabCount() == 0) {
            return "none";
        }
        int index = tabbedPane.getSelectedIndex();
        if (index < 0 || index >= tabbedPane.getTabCount()) {
            return "none";
        }
        return tabbedPane.getTitleAt(index);
    }

    private String safeCollectionRefLabel(CollectionRef ref) {
        if (ref == null || ref.collection == null) {
            return "none";
        }
        return ref.label != null ? ref.label : safeCollectionName(ref.collection);
    }

    private String safeCollectionName(ApiCollection collection) {
        if (collection == null || collection.name == null || collection.name.isBlank()) {
            return "none";
        }
        return collection.name;
    }

    private String safeRequestName(ApiRequest request) {
        if (request == null || request.name == null || request.name.isBlank()) {
            return "none";
        }
        return request.name;
    }

    private int safeMapSize(Map<?, ?> map) {
        return map != null ? map.size() : 0;
    }

    private int safeCollectionCount(List<ApiCollection> collections) {
        return collections != null ? collections.size() : 0;
    }

    private int safeRequestCount(List<ApiCollection> collections) {
        if (collections == null) {
            return 0;
        }
        int count = 0;
        for (ApiCollection collection : collections) {
            if (collection != null && collection.requests != null) {
                count += collection.requests.size();
            }
        }
        return count;
    }


    private String sampleRowOffsets(JTree tree, int limit) {
        if (tree == null || limit <= 0) {
            return "[]";
        }
        List<String> samples = new ArrayList<>();
        int rowCount = tree.getRowCount();
        for (int row = 0; row < rowCount && row < limit; row++) {
            Rectangle bounds = tree.getRowBounds(row);
            samples.add(row + ":" + (bounds != null ? bounds.x : -1));
        }
        return samples.toString();
    }

    private String safeTreePath(TreePath path) {
        if (path == null) {
            return "none";
        }
        Object[] parts = path.getPath();
        List<String> labels = new ArrayList<>();
        for (Object part : parts) {
            labels.add(part != null ? part.toString() : "null");
        }
        return labels.toString();
    }

    private String safePackageVersion() {
        Package pkg = getClass().getPackage();
        if (pkg == null) {
            return "unknown";
        }
        String version = pkg.getImplementationVersion();
        return version != null ? version : "unknown";
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
        Map<String, String> runtimeOverlay = hasActiveEnvironment() ? activeEnvironmentOverlay() : null;
        List<UnresolvedVariableIssue> issues = collectUnresolvedVariableIssues(loadedCollections, selected, runtimeOverlay);
        if (!issues.isEmpty()) {
            List<ApiCollection> targetCollections = collectCollectionsForRequests(loadedCollections, selected);
            UnresolvedVariablesDialog.Action action = showUnresolvedVariablesDialog(issues, targetCollections);
            if (action == UnresolvedVariablesDialog.Action.CANCEL) {
                appendImportLog("Import cancelled due to unresolved variables.");
                return;
            }
            runtimeOverlay = hasActiveEnvironment() ? activeEnvironmentOverlay() : null;
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
            runtimeOverlay,
            this::storeOAuth2TokenInActiveEnvironment,
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

        Map<String, String> runtimeOverlay = hasActiveEnvironment() ? activeEnvironmentOverlay() : null;
        List<UnresolvedVariableIssue> issues = collectUnresolvedVariableIssues(loadedCollections, selected, runtimeOverlay);
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
        JTree selectionTree = buildPopupSelectionTree(selectionRoot, selectedCountLabel, true);
        JScrollPane treeScroll = new JScrollPane(selectionTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Select exactly one request"));
        dialog.add(treeScroll, BorderLayout.CENTER);
        dialog.add(selectedCountLabel, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        JButton populate = new JButton("Populate");
        cancel.addActionListener(e -> dialog.dispose());
        populate.addActionListener(e -> {
            List<ApiRequest> selected = collectOAuth2PopulateRequests((DefaultMutableTreeNode) selectionTree.getModel().getRoot());
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
        EnvironmentProfile activeEnvironment = getActiveEnvironment();
        if (activeEnvironment == null) {
            String message = "Create or select an Active Environment before populating OAuth2 settings.";
            if (GraphicsEnvironment.isHeadless()) {
                appendImportLog(message);
            } else {
                JOptionPane.showMessageDialog(mainPanel,
                        message,
                        "Active Environment Required",
                        JOptionPane.WARNING_MESSAGE);
            }
            return;
        }

        ApiCollection owningCollection = requestToCollectionMap.get(req);
        if (owningCollection == null) {
            owningCollection = findCollectionByName(req.sourceCollection);
        }

        VariableResolver populateResolver = buildOAuth2PopulateResolver(owningCollection, req, activeEnvironmentOverlay());
        Map<String, String> extracted = burp.utils.OAuth2PopulateHelper.extractOAuth2Fields(req, populateResolver);
        if (extracted.isEmpty()) {
            appendImportLog("Populate OAuth2: Selected request has no OAuth2-relevant data.");
            return;
        }

        Map<String, String> existing = buildOAuth2PopulateExistingVars(owningCollection, req, activeEnvironment);
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

    private List<ApiRequest> collectOAuth2PopulateRequests(DefaultMutableTreeNode root) {
        List<ApiRequest> selected = collectCheckedRequests(root);
        return selected.size() == 1 ? selected : Collections.emptyList();
    }

    static VariableResolver buildOAuth2PopulateResolver(ApiCollection collection, ApiRequest request, Map<String, String> activeOverlay) {
        VariableResolver resolver = new VariableResolver();
        if (collection != null) {
            resolver.addEnvironmentVariables(collection);
            resolver.addCollectionVariables(collection);
            resolver.addFolderVariables(collection, request);
        }
        if (activeOverlay != null && !activeOverlay.isEmpty()) {
            resolver.addAll(activeOverlay);
        }
        if (request != null) {
            resolver.addRequestVariables(request);
        }
        return resolver;
    }

    static VariableResolver buildOAuth2PopulateResolver(ApiCollection collection, ApiRequest request) {
        return buildOAuth2PopulateResolver(collection, request, Collections.emptyMap());
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

    Map<String, String> buildOAuth2PopulateExistingVars(ApiCollection collection, ApiRequest request, EnvironmentProfile activeEnvironment) {
        Map<String, String> existing = new LinkedHashMap<>();
        if (collection != null && collection.environment != null) {
            existing.putAll(collection.environment);
        }
        if (collection != null && collection.variables != null) {
            for (ApiRequest.Variable variable : collection.variables) {
                if (variable != null && variable.enabled && variable.key != null && variable.value != null) {
                    existing.put(variable.key, variable.value);
                }
            }
        }
        if (collection != null && request != null) {
            String folderPath = burp.utils.AuthInheritanceResolver.getRequestFolderPath(request);
            if (folderPath != null && !folderPath.isBlank() && collection.folderVars != null) {
                String[] parts = folderPath.split("/");
                StringBuilder current = new StringBuilder();
                for (String part : parts) {
                    if (part == null || part.isBlank()) {
                        continue;
                    }
                    if (current.length() > 0) {
                        current.append("/");
                    }
                    current.append(part.trim());
                    Map<String, String> vars = collection.folderVars.get(current.toString());
                    if (vars != null) {
                        existing.putAll(vars);
                    }
                }
            }
        }
        if (activeEnvironment != null) {
            if (activeEnvironment.oauth2 != null && activeEnvironment.oauth2.config != null) {
                existing.putAll(activeEnvironment.oauth2.config);
            }
            if (activeEnvironment.variables != null) {
                existing.putAll(activeEnvironment.variables);
            }
        }
        if (request != null && request.variables != null) {
            for (ApiRequest.Variable variable : request.variables) {
                if (variable != null && variable.key != null && variable.value != null && !variable.key.isBlank()) {
                    existing.put(variable.key, variable.value);
                }
            }
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

    public void cleanup() {
        shuttingDown = true;
        persistCurrentRequestEditorState();
    }

    private void persistVariablesEditorStateSilently() {
        // Environment editor uses explicit save only; workspace snapshots should
        // reflect committed runtime vars without mutating the live draft.
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
        EnvironmentProfile active = getActiveEnvironment();
        requestEditor.setRuntimeVariables(active != null ? active.toRuntimeOverlay() : getEffectiveRuntimeVarsForRequestContext(col));
    }

    private Map<String, String> getEffectiveRuntimeVarsForRequestContext(ApiCollection col) {
        if (col == null) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, String> runtime = new LinkedHashMap<>();
        if (col.runtimeOAuth2 != null) {
            runtime.putAll(col.runtimeOAuth2);
        }
        if (col.runtimeVars != null) {
            runtime.putAll(col.runtimeVars);
        }
        return runtime;
    }

    private void applyActiveVariablesDraftToCollection(ApiCollection col) {
        // Legacy no-op. Active Environment owns the primary runtime layer.
    }

    private Map<String, String> parseRuntimeOverrideFromRawText() {
        Map<String, String> vars = new HashMap<>();
        String text = envVarsArea.getText();
        String marker = "# Environment variables";
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
     * Parses the environment editor text into key/value pairs.
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




