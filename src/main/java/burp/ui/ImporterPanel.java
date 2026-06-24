package burp.ui;

import burp.models.*;
import burp.exporter.*;
import burp.parser.*;
import burp.runner.CollectionRunner;
import burp.auth.OAuth2Manager;
import burp.auth.OAuth2Config;
import burp.auth.TokenStore;
import burp.UniversalImporter;
import burp.history.HistoryEntry;
import burp.history.HistoryExportService;
import burp.history.HistoryPersistenceService;
import burp.history.HistoryStore;
import burp.history.HistoryDiffService;
import burp.history.HistoryResult;
import burp.history.HistorySource;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistoryResponseSnapshot;
import burp.diagnostics.DiagnosticEvent;
import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticSanitizer;
import burp.diagnostics.DiagnosticStore;
import burp.utils.OAuth2BearerAliasDetector;
import burp.utils.ExecutionResult;
import burp.utils.UnresolvedVariableAnalyzer;
import burp.utils.SharedRequestPipeline;
import burp.scripts.ScriptVariableMutation;
import burp.ui.tree.CollectionTreeNode;
import burp.ui.tree.BurpLikeTreeCellRenderer;
import burp.ui.tree.RequestTreeDragPayload;
import burp.ui.tree.RequestTreeMutationService;
import burp.ui.tree.RequestTreeNamingPolicy;
import burp.ui.tree.RequestTreePathService;
import burp.ui.tree.RequestTreeTransferHandler;
import burp.ui.tree.TreeDropRequest;
import burp.ui.dnd.ActiveEnvironmentDropTransferHandler;
import burp.ui.dnd.EnvironmentDragPayload;
import burp.ui.dnd.EnvironmentProfileDragSourceTransferHandler;
import burp.ui.dnd.EnvironmentTransferHandler;
import burp.ui.dnd.RunnerQueueDragPayload;
import burp.ui.dnd.RunnerQueueTransferHandler;
import burp.ui.history.HistoryLoadResultNotifier;
import burp.ui.history.HistoryNativeHttpMessageFactory;
import burp.history.HistoryHeader;
import burp.ui.history.HistoryDetailPanel;
import burp.ui.history.HistoryPanel;
import burp.utils.RequestPathResolver;
import burp.utils.EnvironmentImportService;
import burp.utils.RuntimeResolverFactory;
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
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final String HISTORY_TAB_NAME = "History";
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
    private final RequestTreeMutationService requestTreeMutationService = new RequestTreeMutationService();
    private final CollectionExportService collectionExportService = new CollectionExportService();
    private final EnvironmentExportService environmentExportService = new EnvironmentExportService();
    private final HistoryStore historyStore = new HistoryStore();
    private final HistoryExportService historyExportService = new HistoryExportService();
    private final HistoryDiffService historyDiffService = new HistoryDiffService();
    private final HistoryPersistenceService historyPersistenceService = new HistoryPersistenceService();
    private final HistoryLoadResultNotifier historyLoadResultNotifier = new HistoryLoadResultNotifier();
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
    private HistoryDetailPanel workbenchDetailPanel;
    private JTabbedPane workbenchDetailTabs;
    private final IdentityHashMap<ApiRequest, WorkbenchSendSnapshot> workbenchSendSnapshots = new IdentityHashMap<>();
    private JTextArea importLog;
    private JTextArea diagnosticsArea;
    private JCheckBox diagnosticsIncludeDebugBox;
    private JCheckBox diagnosticsCaptureBox;
    private JButton diagnosticsRefreshButton;
    private JButton diagnosticsClearButton;
    private JButton diagnosticsCopyButton;
    private DefaultListModel<DiagnosticEvent> diagnosticsEventListModel;
    private JList<DiagnosticEvent> diagnosticsEventList;
    private JTextArea diagnosticsEventDetailArea;
    private HistoryPanel historyPanel;

    // Runner tab
    private JTextArea runnerLog;
    private JProgressBar runnerProgress;
    private JTable resultTable;
    private RunnerExecutionTableModel resultModel;
    private RunnerTimelineTableModel timelineModel;
    private JTable timelineTable;
    private JList<ApiRequest> runnerQueueList;
    private DefaultListModel<ApiRequest> runnerQueueListModel;
    private JScrollPane runnerQueueScrollPane;
    private HistoryDetailPanel runnerDetailPanel;
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
    private RunnerQueueTransferHandler runnerQueueTransferHandler;
    private int runnerExecutingQueueIndex = -1;
    private boolean runnerQueueFresh = false;
    private volatile boolean runnerTerminalHandled = false;
    private int runnerExecutionSequence = 0;
    private int runnerCompletedQueueCount = 0;
    private final Map<String, RunnerResult> runnerResultById = new HashMap<>();
    private final Map<String, RunnerResult> runnerResultByName = new HashMap<>();

    // Workbench environment selector
    private JComboBox<EnvironmentRef> workbenchEnvironmentCombo;
    private JButton workbenchEnvironmentImportBtn;
    private boolean suppressWorkbenchEnvironmentEvents = false;

    static final class WorkbenchSendSnapshot {
        final HttpRequest builtRequest;
        final HttpResponse response;
        final String metaText;
        final String scriptOutputText;
        final String assertionsText;
        final String failureReason;
        final String sendModeLabel;
        final long timestampMillis;
        HistoryEntry detailEntry;

        WorkbenchSendSnapshot(HttpRequest builtRequest,
                              HttpResponse response,
                              String metaText,
                              String failureReason,
                              String sendModeLabel,
                              long timestampMillis) {
            this(builtRequest, response, metaText, "", "", failureReason, sendModeLabel, timestampMillis);
        }

        WorkbenchSendSnapshot(HttpRequest builtRequest,
                              HttpResponse response,
                              String metaText,
                              String scriptOutputText,
                              String assertionsText,
                              String failureReason,
                              String sendModeLabel,
                              long timestampMillis) {
            this.builtRequest = builtRequest;
            this.response = response;
            this.metaText = metaText;
            this.scriptOutputText = scriptOutputText;
            this.assertionsText = assertionsText;
            this.failureReason = failureReason;
            this.sendModeLabel = sendModeLabel;
            this.timestampMillis = timestampMillis;
        }
    }

    static final class DropImportResult {
        final List<ApiCollection> importedCollections = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        int importedCount;
        int failedCount;
    }

    static final class EnvironmentDropImportResult {
        final List<EnvironmentProfile> importedProfiles = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        int importedCount;
        int failedCount;
    }

    // Environment tab
    private JComboBox<EnvironmentRef> environmentCombo;
    private JButton environmentImportBtn, environmentNewBtn, environmentDuplicateBtn,
            environmentDeleteBtn, environmentSetActiveBtn, environmentExportBtn, environmentSaveBtn;
    private JLabel environmentHintLabel;
    private JLabel environmentStatusLabel;
    private JPanel environmentTopBarPanel;
    private JTextArea environmentRawArea;
    private JTable environmentTable;
    private DefaultTableModel environmentTableModel;
    private JPanel environmentEditorCardPanel;
    private JRadioButton environmentRawViewBtn;
    private JRadioButton environmentTableViewBtn;
    private boolean environmentDirty = false;
    private String renderedEnvironmentEditorProfileId = null;
    private boolean suppressEnvironmentEditorEvents = false;
    private EnvironmentTransferHandler environmentFileDropHandler;
    private EnvironmentProfileDragSourceTransferHandler environmentProfileDragSourceHandler;
    private ActiveEnvironmentDropTransferHandler activeEnvironmentDropHandler;

    // Legacy scoped variables UI state retained for internal compatibility only.
    private JTextArea envVarsArea;
    private JTable varsTable;
    private DefaultTableModel varsTableModel;
    private JPanel varsEditorCardPanel;
    private JRadioButton varsRawViewBtn;
    private JRadioButton varsTableViewBtn;
    private JComboBox<CollectionRef> varsCollectionCombo = new JComboBox<>();
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
    private JLabel oauth2AutosaveStatusLabel;
    private JLabel oauth2ActiveEnvironmentLabel;
    private JComboBox<EnvironmentRef> oauth2EnvironmentCombo;
    private boolean suppressOAuth2EnvironmentEvents = false;
    private JLabel oauth2StatusLabel;
    private boolean oauth2ConfigDirty = false;
    private String renderedOAuth2ConfigEnvironmentId = null;
    private boolean oauth2ConfigRefreshPending = false;
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
        this.oauth2Panel.setTokenAcquiredEnvironmentIdSupplier(this::getActiveEnvironmentId);
        this.importer = importer;
        this.runner = runner;
        if (this.runner != null) {
            this.runner.setRuntimeOverlayProvider(collection -> hasActiveEnvironment() ? activeEnvironmentOverlay() : null);
            this.runner.setActiveEnvironmentProvider(collection -> getActiveEnvironment());
            this.runner.setOAuth2TokenSink(ImporterPanel.this::storeOAuth2TokenInActiveEnvironment);
            this.runner.setRuntimeVariableSink(ImporterPanel.this::applyRuntimeVariableDeltaToActiveEnvironment);
        }
        this.mainPanel = createUI();
        this.oauth2Panel.setVariablesChangeListener((vars, replaceMode) -> markOAuth2ConfigDirty());
        if (oauth2Panel.getPopulateButton() != null) {
            oauth2Panel.getPopulateButton().addActionListener(e -> populateOAuth2FromRequest());
        }
        if (oauth2Panel.getBindTokenButton() != null) {
            oauth2Panel.getBindTokenButton().addActionListener(e -> showOAuth2BindTokenDialog());
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
        tabbedPane.addTab(HISTORY_TAB_NAME, createHistoryTab());
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
        treeModel = new RequestTreeModel(new DefaultMutableTreeNode("Collections"));
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
            installRequestTreeTransferSupport(tree);
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
        tree.setEditable(true);
        tree.setInvokesStopCellEditing(true);
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
            handleRequestTreeSelectionChanged();
            updateScopeControlState();
        });
        installRequestTreeTransferSupport(tree);
        return tree;
    }

    private void installRequestTreeTransferSupport(JTree tree) {
        if (tree == null) {
            return;
        }
        RequestTreeTransferHandler handler = new RequestTreeTransferHandler(
                tree,
                this::importCollectionFilesDroppedOnRequestTreeAsync,
                this::canAcceptRequestTreeDrop,
                this::handleRequestTreeDrop,
                this::appendImportLog);
        tree.setTransferHandler(handler);
        tree.setDropMode(DropMode.ON_OR_INSERT);
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                tree.setDragEnabled(true);
            } catch (HeadlessException ignored) {
                // headless tests may still construct the tree; drag is best-effort.
            }
        }
        if (requestTreeScrollPane != null) {
            requestTreeScrollPane.setTransferHandler(handler);
            JViewport viewport = requestTreeScrollPane.getViewport();
            if (viewport != null) {
                viewport.setTransferHandler(handler);
            }
        }
    }

    private JComponent createRightWorkbenchPanel() {
        requestEditor = new RequestEditorPanel();
        requestEditor.setRequestBuilder(requestBuilder);
        requestEditor.setSendControlsEnabled(false);
        requestEditor.setVariableActionBridge(new RequestEditorPanel.VariableActionBridge() {
            @Override
            public RequestEditorPanel.VariableHoverInfo inspect(String key) {
                ApiCollection collection = requestEditor != null ? requestEditor.getCurrentCollection() : null;
                EnvironmentProfile active = getActiveEnvironment();
                Map<String, String> runtimeOverlay = active != null
                        ? Collections.emptyMap()
                        : (requestEditor != null ? requestEditor.getRuntimeVariablesSnapshot() : Collections.emptyMap());
                burp.utils.RuntimeResolverFactory.ResolutionTrace trace =
                        burp.utils.RuntimeResolverFactory.inspect(collection, requestEditor != null ? requestEditor.getCurrentRequest() : null, active, runtimeOverlay, key);
                RequestEditorPanel.VariableHoverInfo info = new RequestEditorPanel.VariableHoverInfo();
                info.key = key;
                info.resolved = trace.resolved;
                info.value = trace.value;
                info.scope = trace.scope != null ? trace.scope : (info.resolved ? "resolved" : "not found");
                info.source = trace.source != null ? trace.source : (active != null ? "Active Environment" : "No Active Environment selected");
                info.shadowedSource = trace.shadowedSource;
                info.shadowedValue = trace.shadowedValue;
                info.activeEnvironmentName = trace.activeEnvironmentName != null ? trace.activeEnvironmentName : (active != null ? active.displayName() : null);
                info.canEdit = active != null;
                info.canCreate = active != null;
                info.message = trace.message != null ? trace.message : (active != null
                        ? "No value found. Create target: Active Environment (persisted variable)."
                        : "No Active Environment selected. Select or import an environment to edit or create persisted variables.");
                return info;
            }

            @Override
            public boolean hasActiveEnvironment() {
                return getActiveEnvironment() != null;
            }

            @Override
            public String activeEnvironmentName() {
                EnvironmentProfile active = getActiveEnvironment();
                return active != null ? active.displayName() : null;
            }

            @Override
            public boolean updateActiveEnvironment(String key, String value, boolean createIfMissing, boolean persist) {
                EnvironmentProfile active = getActiveEnvironment();
                if (active == null || key == null || key.isBlank()) {
                    return false;
                }
                Map<String, String> changed = new LinkedHashMap<>();
                changed.put(key, value != null ? value : "");
                applyRuntimeVariableDeltaToActiveEnvironment(requestEditor != null ? requestEditor.getCurrentCollection() : null,
                        changed,
                        Collections.emptySet());
                return true;
            }

            @Override
            public void refreshEnvironmentUi() {
                syncActiveEnvironmentToEditors();
                updateEnvironmentUiState();
                notifyWorkspaceChangedImmediately();
            }
        });
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

    private HistoryDetailPanel createWorkbenchDetailTabs() {
        workbenchDetailPanel = new HistoryDetailPanel(importer != null ? importer.getApi() : null);
        workbenchDetailTabs = workbenchDetailPanel.getTabbedPane();
        return workbenchDetailPanel;
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
        SwingShortcutSupport.installTextComponentShortcuts(importLog);
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
        if (requestEditor == null) {
            appendImportLog("No request loaded in editor.");
            return;
        }
        ApiRequest liveRequest = requestEditor.getCurrentRequest();
        ApiCollection col = requestEditor.getCurrentCollection();
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
        List<ApiRequest.Header> hiddenTransportSnapshot = copyHeaders(liveRequest.headers);
        applyEditedRequestToLiveRequest(col, liveRequest, edited);
        if (!edited.isExactHttpMode()) {
            liveRequest.headers = mergeHiddenTransportHeaders(hiddenTransportSnapshot, liveRequest.headers);
        }
        syncRequestEditorRuntimeContext(liveRequest, col);
        if (requestEditor != null) {
            requestEditor.markClean();
        }
        notifyWorkspaceChanged();

        final ApiCollection resolvedCol = col;
        final ApiRequest requestToSend = liveRequest;
        final String sendModeLabel = requestEditor.getSendModeLabel();
        Map<String, String> runtimeOverlay = activeEnvironmentOverlayForRuntimeUse();
        EnvironmentProfile activeEnvironment = getActiveEnvironment();
        List<UnresolvedVariableIssue> issues = collectUnresolvedVariableIssues(
                List.of(resolvedCol),
                List.of(requestToSend),
                runtimeOverlay);
        final List<String> unresolvedVariableNames = issues.stream()
                .map(issue -> issue != null ? issue.variableName : null)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        if (!issues.isEmpty()) {
            UnresolvedVariablesDialog.Action action = showUnresolvedVariablesDialog(issues, List.of(resolvedCol));
            if (action == UnresolvedVariablesDialog.Action.CANCEL) {
                appendImportLog("Send cancelled due to unresolved variables.");
                return;
            }
            runtimeOverlay = activeEnvironmentOverlayForRuntimeUse();
        }
        final Map<String, String> runtimeOverlayForSend = runtimeOverlay;
        requestEditor.setSendEnabled(false);
        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                UniversalImporter.SingleSendResult result = null;
                String failureReason = null;
                try {
                    publish("Sending: " + requestToSend.method + " " + requestToSend.url);
                    boolean follow = followRedirectsBox != null && followRedirectsBox.isSelected();
                    result = sendSingleRequestWithBuiltRequest(
                            requestToSend,
                            resolvedCol,
                            follow,
                            runtimeOverlayForSend,
                            ImporterPanel.this::storeOAuth2TokenInActiveEnvironment,
                            ImporterPanel.this::applyRuntimeVariableDeltaToActiveEnvironment,
                            activeEnvironment,
                            null);
                    var rr = result.response;

                    final UniversalImporter.SingleSendResult sendResult = result;
                    SwingUtilities.invokeLater(() -> updateWorkbenchDetailPaneSuccess(requestToSend, resolvedCol, sendResult, sendModeLabel));

                    if (rr != null && rr.response() != null) {
                        var resp = rr.response();
                        byte[] bodyBytes = resp.body().getBytes();
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
                    failureReason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    final String failure = failureReason;
                    SwingUtilities.invokeLater(() -> updateWorkbenchDetailPaneFailure(requestToSend, resolvedCol, failure, sendModeLabel));
                    publish("Send failed: " + failureReason);
                }
                recordWorkbenchHistoryEntry(
                        requestToSend,
                        resolvedCol,
                        result,
                        failureReason,
                        unresolvedVariableNames,
                        runtimeOverlayForSend,
                        sendModeLabel);
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) appendImportLog(msg);
            }

            @Override
            protected void done() {
                applyWorkbenchSendCompletionState(requestToSend, resolvedCol);
            }
        };
        worker.execute();
    }

    static boolean shouldEnableSendAfterWorkbenchSend(RequestEditorPanel editor,
                                                      ApiRequest sentRequest,
                                                      ApiCollection sentCollection) {
        if (editor == null || sentRequest == null || sentCollection == null) {
            return false;
        }
        ApiRequest currentRequest = editor.getCurrentRequest();
        ApiCollection currentCollection = editor.getCurrentCollection();
        return currentRequest != null
                && currentCollection != null
                && currentRequest == sentRequest
                && currentCollection == sentCollection;
    }

    void applyWorkbenchSendCompletionState(ApiRequest sentRequest, ApiCollection sentCollection) {
        if (requestEditor == null) {
            return;
        }
        if (shouldEnableSendAfterWorkbenchSend(requestEditor, sentRequest, sentCollection)) {
            requestEditor.setSendControlsEnabled(true);
            return;
        }
        if (requestEditor.getCurrentRequest() == null || requestEditor.getCurrentCollection() == null) {
            requestEditor.setSendControlsEnabled(false);
        }
    }

    private void recordWorkbenchHistoryEntry(ApiRequest request,
                                             ApiCollection collection,
                                             UniversalImporter.SingleSendResult sendResult,
                                             String failureReason,
                                             List<String> unresolvedVariables,
                                             Map<String, String> runtimeOverlay,
                                             String sendModeLabel) {
        if (request == null) {
            return;
        }
        ensureRequestId(request);
        HistoryEntry entry = HistoryEntry.fromWorkbenchExecution(
                collection,
                request,
                getActiveEnvironment(),
                sendResult != null ? sendResult.executionResult : null,
                1,
                1,
                unresolvedVariables);
        if (entry == null) {
            return;
        }
        if (entry.requestSnapshot == null) {
            entry.requestSnapshot = HistoryRequestSnapshot.from(request);
        }
        if (entry.requestSizeBytes <= 0 && entry.requestSnapshot != null) {
            entry.requestSizeBytes = entry.requestSnapshot.approximateSizeBytes();
        }
        if (sendResult != null && sendResult.executionResult == null && sendResult.response != null && sendResult.response.response() != null) {
            entry.responseSnapshot = HistoryResponseSnapshot.from(sendResult.response.response());
            entry.statusCode = entry.responseSnapshot != null ? entry.responseSnapshot.statusCode : entry.statusCode;
            entry.responseSizeBytes = entry.responseSnapshot != null && entry.responseSnapshot.body != null ? entry.responseSnapshot.body.length : 0L;
            entry.durationMillis = sendResult.elapsedMs;
        }
        if (failureReason != null && !failureReason.isBlank()) {
            entry.errorMessage = failureReason;
            if (sendResult == null || sendResult.executionResult == null) {
                entry.result = HistoryResult.from(false, failureReason, entry.hasAssertionFailure(), unresolvedVariables != null && !unresolvedVariables.isEmpty());
            } else if (entry.result == HistoryResult.SUCCESS || entry.result == HistoryResult.UNKNOWN) {
                entry.result = HistoryResult.from(false, failureReason, entry.hasAssertionFailure(), unresolvedVariables != null && !unresolvedVariables.isEmpty());
            }
        }
        if (entry.result == HistoryResult.UNKNOWN) {
            entry.result = HistoryResult.from(sendResult != null && sendResult.executionResult != null && sendResult.executionResult.success,
                    failureReason,
                    entry.hasAssertionFailure(),
                    unresolvedVariables != null && !unresolvedVariables.isEmpty());
        }
        recordHistoryEntry(entry);
    }

    private void recordRunnerHistoryAttempt(RunnerResult result) {
        if (result == null) {
            return;
        }
        ApiCollection collection = findCollectionByName(result.collectionName);
        ApiRequest request = findRequestById(result.requestId);
        ensureRequestId(request);
        EnvironmentProfile active = getActiveEnvironment();
        HistoryEntry entry = HistoryEntry.fromRunnerAttempt(collection, request, active, result);
        if (entry == null) {
            return;
        }
        if (entry.collectionName == null) {
            entry.collectionName = result.collectionName != null ? result.collectionName : (request != null ? request.sourceCollection : null);
            entry.collectionId = entry.collectionName;
        }
        if (entry.folderPath == null) {
            entry.folderPath = resolveHistoryFolderPath(collection, request, entry);
        }
        if (entry.requestSnapshot == null && request != null) {
            entry.requestSnapshot = HistoryRequestSnapshot.from(request);
        }
        if (entry.requestSizeBytes <= 0 && entry.requestSnapshot != null) {
            entry.requestSizeBytes = entry.requestSnapshot.approximateSizeBytes();
        }
        if (entry.result == HistoryResult.UNKNOWN && result != null) {
            entry.result = HistoryResult.from(result.success, result.errorMessage, entry.hasAssertionFailure(), !entry.unresolvedVariables.isEmpty());
        }
        recordHistoryEntry(entry);
    }

    private static String ensureRequestId(ApiRequest request) {
        if (request == null) {
            return null;
        }
        if (request.id == null || request.id.isBlank()) {
            request.id = UUID.randomUUID().toString();
        }
        return request.id;
    }

    private static void ensureRequestIds(ApiCollection collection) {
        if (collection == null || collection.requests == null || collection.requests.isEmpty()) {
            return;
        }
        for (ApiRequest request : collection.requests) {
            ensureRequestId(request);
        }
    }

    private void recordHistoryEntry(HistoryEntry entry) {
        if (entry == null) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> recordHistoryEntry(entry));
            return;
        }
        entry.ensureDefaults();
        historyStore.addEntry(entry);
        recordDiagnostic(
                DiagnosticOperation.HISTORY_CAPTURE,
                DiagnosticSeverity.INFO,
                "ImporterPanel",
                "History entry captured",
                "historyId=" + entry.id + "\nrawRequestAvailable=" + (entry.requestSnapshot != null && entry.requestSnapshot.hasRawRequestSent()) +
                        "\nauthoredTemplateAvailable=" + (entry.requestSnapshot != null && entry.requestSnapshot.authoredRequest != null));
        if (historyPanel != null) {
            historyPanel.refreshFromStore(entry.id);
        }
        notifyWorkspaceChanged();
    }

    private void loadHistoryEntryIntoWorkbench(HistoryEntry entry) {
        if (entry == null || entry.requestSnapshot == null) {
            return;
        }
        recordDiagnostic(
                DiagnosticOperation.LOAD_IN_WORKBENCH,
                DiagnosticSeverity.INFO,
                "ImporterPanel",
                "Loading history entry into Workbench",
                "historyId=" + entry.id + "\nrequest=" + entry.requestDisplayName());
        if (requestEditor != null && requestEditor.isDirty() && !historyLoadResultNotifier.confirmReplaceCurrentRequest(mainPanel)) {
            return;
        }
        HistoryRequestContext existingContext = resolveExistingHistoryRequestContext(entry);
        if (existingContext != null && existingContext.originalRequestExists && existingContext.collection != null && existingContext.request != null) {
            ApiRequest snapshotRequest = entry.requestSnapshot.toApiRequest();
            applyEditedRequestToLiveRequest(existingContext.collection, existingContext.request, snapshotRequest);
            openRequestInEditor(existingContext.request, existingContext.collection);
            if (requestEditor != null) {
                requestEditor.markClean();
            }
            selectRequestInTree(existingContext.request);
            HistoryEntry loadedEntry = HistoryEntry.copyOf(entry);
            if (loadedEntry != null) {
                loadedEntry.collectionId = existingContext.collection.id != null && !existingContext.collection.id.isBlank()
                        ? existingContext.collection.id
                        : existingContext.collection.ensureId();
                loadedEntry.collectionName = existingContext.collection.name;
                loadedEntry.folderPath = RequestPathResolver.getRequestFolderPath(existingContext.collection, existingContext.request);
                loadedEntry.requestId = existingContext.request.id;
                loadedEntry.requestName = existingContext.request.name;
                historyLoadResultNotifier.showLoadedIntoOriginalRequest(mainPanel, loadedEntry);
            } else {
                historyLoadResultNotifier.showLoadedIntoOriginalRequest(mainPanel, entry);
            }
            notifyWorkspaceChangedImmediately();
            return;
        }
        HistoryRequestContext fallbackContext = resolveHistoryRequestContext(entry, true);
        if (fallbackContext == null || fallbackContext.collection == null || fallbackContext.request == null) {
            return;
        }
        ApiRequest snapshotRequest = entry.requestSnapshot.toApiRequest();
        snapshotRequest.path = resolveHistoryFolderPath(fallbackContext.collection, fallbackContext.request, entry);
        if (entry.requestName != null && !entry.requestName.isBlank()) {
            snapshotRequest.name = entry.requestName;
        }
        applyEditedRequestToLiveRequest(fallbackContext.collection, fallbackContext.request, snapshotRequest);
        refreshRequestTreeAfterMutation(() -> {
            openRequestInEditor(fallbackContext.request, fallbackContext.collection);
            if (requestEditor != null) {
                requestEditor.markClean();
            }
            selectRequestInTree(fallbackContext.request);
        });
        historyLoadResultNotifier.showLoadedUnderHistoryReplays(mainPanel, fallbackContext.request.name);
        notifyWorkspaceChangedImmediately();
    }

    private void replayHistoryEntry(HistoryEntry entry) {
        if (entry == null || entry.requestSnapshot == null) {
            return;
        }
        HistoryRequestContext context = resolveExistingHistoryRequestContext(entry);
        ApiRequest request = entry.requestSnapshot.toApiRequest();
        EnvironmentProfile activeEnvironment = getActiveEnvironment();
        recordReplayDiagnostic(
                DiagnosticSeverity.INFO,
                "Replay started",
                entry,
                context != null ? context.collection : null,
                request,
                activeEnvironment,
                "started");
        List<ApiCollection> collectionsForAnalysis = context != null && context.collection != null
                ? List.of(context.collection)
                : Collections.emptyList();
        Map<String, String> runtimeOverlay = activeEnvironmentOverlayForRuntimeUse();
        List<UnresolvedVariableIssue> issues = collectUnresolvedVariableIssues(
                collectionsForAnalysis,
                List.of(request),
                runtimeOverlay);
        if (!issues.isEmpty()) {
            UnresolvedVariablesDialog.Action action = showUnresolvedVariablesDialog(issues, collectionsForAnalysis);
            if (action == UnresolvedVariablesDialog.Action.CANCEL) {
                appendImportLog("Replay cancelled due to unresolved variables.");
                recordReplayDiagnostic(
                        DiagnosticSeverity.INFO,
                        "Replay cancelled",
                        entry,
                        context != null ? context.collection : null,
                        request,
                        activeEnvironment,
                        "unresolved variables / user cancelled");
                return;
            }
            runtimeOverlay = activeEnvironmentOverlayForRuntimeUse();
        }
        final ApiCollection resolvedCollection = context != null ? context.collection : null;
        final ApiRequest replayRequest = request;
        final Map<String, String> runtimeOverlayForReplay = runtimeOverlay;
        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    publish("Replaying: " + replayRequest.method + " " + replayRequest.url);
                    boolean follow = followRedirectsBox != null && followRedirectsBox.isSelected();
                    UniversalImporter.SingleSendResult result = sendSingleRequestWithBuiltRequest(
                            replayRequest,
                            resolvedCollection,
                            follow,
                            runtimeOverlayForReplay,
                            ImporterPanel.this::storeOAuth2TokenInActiveEnvironment,
                            ImporterPanel.this::applyRuntimeVariableDeltaToActiveEnvironment,
                            activeEnvironment,
                            burp.scripts.ExecutionSource.HISTORY_REPLAY);
                    publish("Replay complete: " + replayRequest.name);
                    recordWorkbenchHistoryEntry(
                            replayRequest,
                            resolvedCollection,
                            result,
                            null,
                            issues.stream().map(issue -> issue != null ? issue.variableName : null).filter(name -> name != null && !name.isBlank()).distinct().toList(),
                            runtimeOverlayForReplay,
                            "Replay from History");
                    recordReplayDiagnostic(
                            DiagnosticSeverity.INFO,
                            "Replay completed",
                            entry,
                            resolvedCollection,
                            replayRequest,
                            activeEnvironment,
                            "completed");
                } catch (Exception e) {
                    String failureReason = cleanReplayFailureReason(e);
                    publish("Replay failed: " + failureReason);
                    recordReplayDiagnostic(
                            DiagnosticSeverity.ERROR,
                            "Replay failed",
                            entry,
                            resolvedCollection,
                            replayRequest,
                            activeEnvironment,
                            failureReason);
                    recordWorkbenchHistoryEntry(
                            replayRequest,
                            resolvedCollection,
                            null,
                            failureReason,
                            issues.stream().map(issue -> issue != null ? issue.variableName : null).filter(name -> name != null && !name.isBlank()).distinct().toList(),
                            runtimeOverlayForReplay,
                            "Replay from History");
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) appendImportLog(msg);
            }
        };
        worker.execute();
    }

    private void sendHistoryEntryToRepeater(HistoryEntry entry) {
        if (entry == null || entry.requestSnapshot == null) {
            return;
        }
        HistoryRequestContext context = resolveExistingHistoryRequestContext(entry);
        ApiRequest replayRequest = entry.requestSnapshot.toApiRequest();
        List<ApiCollection> collectionsForAnalysis = context != null && context.collection != null
                ? List.of(context.collection)
                : Collections.emptyList();
        Map<String, String> runtimeOverlay = activeEnvironmentOverlayForRuntimeUse();
        List<UnresolvedVariableIssue> issues = collectUnresolvedVariableIssues(
                collectionsForAnalysis,
                List.of(replayRequest),
                runtimeOverlay);
        if (!issues.isEmpty()) {
            UnresolvedVariablesDialog.Action action = showUnresolvedVariablesDialog(issues, collectionsForAnalysis);
            if (action == UnresolvedVariablesDialog.Action.CANCEL) {
                appendImportLog("Send to Repeater cancelled due to unresolved variables.");
                return;
            }
            runtimeOverlay = activeEnvironmentOverlayForRuntimeUse();
        }
        try {
            boolean follow = followRedirectsBox != null && followRedirectsBox.isSelected();
            burp.parser.VariableResolver resolver = burp.utils.RuntimeResolverFactory.build(
                    context != null ? context.collection : null,
                    replayRequest,
                    runtimeOverlay != null
                            ? burp.utils.RuntimeResolverFactory.Options.withRuntimeVariableOverlay(runtimeOverlay)
                            : burp.utils.RuntimeResolverFactory.Options.defaultOptions()
            );
            String resolvedUrl = resolver.resolve(replayRequest.url);
            burp.utils.HttpUtils.ParsedTarget parsed = burp.utils.HttpUtils.parseTargetForRequest(resolvedUrl);
            byte[] rawRequest = requestBuilder.buildRequest(replayRequest, resolver);
            burp.api.montoya.http.message.requests.HttpRequest builtRequest;
            try {
                burp.api.montoya.http.HttpService service = burp.api.montoya.http.HttpService.httpService(
                        parsed.host, parsed.port, parsed.useHttps);
                builtRequest = burp.api.montoya.http.message.requests.HttpRequest.httpRequest(
                        service,
                        burp.api.montoya.core.ByteArray.byteArray(rawRequest)
                );
            } catch (Throwable serviceFailure) {
                builtRequest = HistoryNativeHttpMessageFactory.request(
                        new String(rawRequest != null ? rawRequest : new byte[0], StandardCharsets.UTF_8));
            }
            String tabName = importer.generateRepeaterTabName(
                    replayRequest.name,
                    context != null && context.collection != null ? context.collection.name : "");
            importer.sendToRepeater(builtRequest, tabName);
            appendImportLog("Sent history request to Repeater: " + tabName);
        } catch (Exception e) {
            String failureReason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            appendImportLog("Send to Repeater failed: " + failureReason);
            historyLoadResultNotifier.showError(mainPanel, "Send to Repeater failed: " + failureReason);
        }
    }

    private UniversalImporter.SingleSendResult sendSingleRequestWithBuiltRequest(
            ApiRequest request,
            ApiCollection collection,
            boolean followRedirects,
            Map<String, String> runtimeOverlay,
            SharedRequestPipeline.OAuth2TokenSink oauth2TokenSink,
            SharedRequestPipeline.RuntimeVariableSink runtimeVariableSink,
            EnvironmentProfile activeEnvironment,
            burp.scripts.ExecutionSource executionSource) throws Exception {
        if (runtimeOverlay == null && activeEnvironment == null && executionSource == null) {
            return importer.sendSingleRequestWithBuiltRequest(request, collection, followRedirects);
        }
        if (executionSource == null) {
            return importer.sendSingleRequestWithBuiltRequest(
                    request,
                    collection,
                    followRedirects,
                    runtimeOverlay,
                    oauth2TokenSink,
                    runtimeVariableSink);
        }
        return importer.sendSingleRequestWithBuiltRequest(
                request,
                collection,
                followRedirects,
                runtimeOverlay,
                oauth2TokenSink,
                runtimeVariableSink,
                activeEnvironment,
                executionSource);
    }

    private HistoryRequestContext resolveExistingHistoryRequestContext(HistoryEntry entry) {
        return resolveHistoryRequestContext(entry, false);
    }

    private HistoryRequestContext resolveHistoryRequestContext(HistoryEntry entry, boolean createFallbackRequest) {
        if (entry == null || entry.requestSnapshot == null) {
            return null;
        }
        HistoryRequestContext resolved = resolveHistoryRequestContextCore(entry);
        if (resolved != null && resolved.originalRequestExists) {
            return resolved;
        }
        if (!createFallbackRequest) {
            return resolved;
        }
        if (resolved != null && resolved.ambiguousResolution && resolved.resolutionNote != null) {
            historyLoadResultNotifier.showInfo(mainPanel, resolved.resolutionNote);
        }
        ApiCollection collection = ensureHistoryReplaysCollection();
        if (collection == null) {
            return null;
        }
        ApiRequest request = requestTreeMutationService.createBlankManualRequest(collection, "");
        if (request == null) {
            return null;
        }
        if (entry.requestName != null && !entry.requestName.isBlank()) {
            request.name = entry.requestName;
        }
        request.path = resolveHistoryFolderPath(entry);
        ApiRequest snapshotRequest = entry.requestSnapshot.toApiRequest();
        applyEditedRequestToLiveRequest(collection, request, snapshotRequest);
        return new HistoryRequestContext(collection, request, false, false, null);
    }

    private HistoryRequestContext resolveHistoryRequestContextCore(HistoryEntry entry) {
        if (entry == null || entry.requestSnapshot == null) {
            return null;
        }
        String requestId = resolveHistoryRequestId(entry);
        String collectionIdentity = resolveHistoryCollectionIdentity(entry);
        String collectionName = resolveHistoryCollectionName(entry);
        String requestName = resolveHistoryRequestName(entry);
        List<String> folderCandidates = resolveHistoryFolderPathCandidates(entry);
        String folderPath = resolveHistoryFolderPath(entry);
        String method = resolveHistoryMethod(entry);
        String urlTemplate = resolveHistoryUrlTemplate(entry);
        ApiCollection uniqueCollection = findUniqueCollectionByHistoryIdentity(collectionIdentity);

        if (requestId != null && !requestId.isBlank()) {
            List<HistoryRequestMatch> exactMatches = findRequestMatchesByCollectionIdentityAndId(collectionIdentity, requestId);
            if (exactMatches.size() == 1) {
                HistoryRequestMatch match = exactMatches.get(0);
                return new HistoryRequestContext(match.collection, match.request, true, false, null);
            }
            if (exactMatches.size() > 1) {
                return new HistoryRequestContext(
                        exactMatches.get(0).collection,
                        null,
                        false,
                        true,
                        buildAmbiguousHistoryMatchMessage(collectionName, requestName, folderPath, method, urlTemplate, exactMatches.size()));
            }

            List<HistoryRequestMatch> idMatches = findRequestMatchesById(requestId);
            if (idMatches.size() == 1) {
                HistoryRequestMatch match = idMatches.get(0);
                return new HistoryRequestContext(match.collection, match.request, true, false, null);
            }
            if (idMatches.size() > 1) {
                return new HistoryRequestContext(
                        idMatches.get(0).collection,
                        null,
                        false,
                        true,
                        buildAmbiguousHistoryMatchMessage(collectionName, requestName, folderPath, method, urlTemplate, idMatches.size()));
            }
        }

        List<HistoryRequestMatch> fallbackMatches = findFallbackRequestMatches(collectionIdentity, requestName, folderCandidates, method, urlTemplate);
        if (fallbackMatches.size() == 1) {
            HistoryRequestMatch match = fallbackMatches.get(0);
            return new HistoryRequestContext(match.collection, match.request, true, false, null);
        }
        if (fallbackMatches.size() > 1) {
            return new HistoryRequestContext(
                    uniqueCollection != null ? uniqueCollection : fallbackMatches.get(0).collection,
                    null,
                    false,
                    true,
                    buildAmbiguousHistoryMatchMessage(collectionName, requestName, folderPath, method, urlTemplate, fallbackMatches.size()));
        }

        if (uniqueCollection != null) {
            return new HistoryRequestContext(uniqueCollection, null, false, false, null);
        }
        return null;
    }

    private List<HistoryRequestMatch> findRequestMatchesById(String requestId) {
        List<HistoryRequestMatch> matches = new ArrayList<>();
        if (requestId == null || requestId.isBlank()) {
            return matches;
        }
        for (ApiCollection collection : loadedCollections) {
            if (collection == null || collection.requests == null) {
                continue;
            }
            for (ApiRequest request : collection.requests) {
                if (request != null && requestId.equals(request.id)) {
                    matches.add(new HistoryRequestMatch(collection, request));
                }
            }
        }
        return matches;
    }

    private List<HistoryRequestMatch> findRequestMatchesByCollectionIdentityAndId(String collectionIdentity, String requestId) {
        List<HistoryRequestMatch> matches = new ArrayList<>();
        if (collectionIdentity == null || collectionIdentity.isBlank() || requestId == null || requestId.isBlank()) {
            return matches;
        }
        for (ApiCollection collection : loadedCollections) {
            if (!matchesHistoryCollectionIdentity(collection, collectionIdentity) || collection == null || collection.requests == null) {
                continue;
            }
            for (ApiRequest request : collection.requests) {
                if (request != null && requestId.equals(request.id)) {
                    matches.add(new HistoryRequestMatch(collection, request));
                }
            }
        }
        return matches;
    }

    private List<HistoryRequestMatch> findFallbackRequestMatches(String collectionIdentity,
                                                                 String requestName,
                                                                 List<String> folderCandidates,
                                                                 String method,
                                                                 String urlTemplate) {
        List<HistoryRequestMatch> matches = new ArrayList<>();
        for (ApiCollection collection : loadedCollections) {
            if (collection == null || collection.requests == null) {
                continue;
            }
            if (collectionIdentity != null && !collectionIdentity.isBlank() && !matchesHistoryCollectionIdentity(collection, collectionIdentity)) {
                continue;
            }
            for (ApiRequest request : collection.requests) {
                if (request == null) {
                    continue;
                }
                if (requestName != null && !requestName.isBlank() && !Objects.equals(request.name, requestName)) {
                    continue;
                }
                if (folderCandidates != null && !folderCandidates.isEmpty()) {
                    List<String> requestFolderCandidates = resolveRequestFolderPathCandidates(collection, request);
                    if (!matchesAnyFolderCandidate(folderCandidates, requestFolderCandidates)) {
                        continue;
                    }
                }
                if (method != null && !method.isBlank() && (request.method == null || !request.method.equalsIgnoreCase(method))) {
                    continue;
                }
                if (urlTemplate != null && !urlTemplate.isBlank() && !Objects.equals(request.url, urlTemplate)) {
                    continue;
                }
                matches.add(new HistoryRequestMatch(collection, request));
            }
        }
        return matches;
    }

    private boolean matchesHistoryCollectionIdentity(ApiCollection collection, String identity) {
        if (collection == null || identity == null || identity.isBlank()) {
            return false;
        }
        if (Objects.equals(collection.id, identity)) {
            return true;
        }
        return Objects.equals(collection.name, identity);
    }

    private ApiCollection findUniqueCollectionByHistoryIdentity(String identity) {
        if (identity == null || identity.isBlank()) {
            return null;
        }
        ApiCollection match = null;
        for (ApiCollection collection : loadedCollections) {
            if (!matchesHistoryCollectionIdentity(collection, identity)) {
                continue;
            }
            if (match != null && match != collection) {
                return null;
            }
            match = collection;
        }
        return match;
    }

    private String resolveHistoryCollectionIdentity(HistoryEntry entry) {
        if (entry == null) {
            return null;
        }
        if (entry.collectionId != null && !entry.collectionId.isBlank()) {
            return entry.collectionId;
        }
        if (entry.requestSnapshot != null && entry.requestSnapshot.authoredRequest != null
                && entry.requestSnapshot.authoredRequest.sourceCollection != null
                && !entry.requestSnapshot.authoredRequest.sourceCollection.isBlank()) {
            return entry.requestSnapshot.authoredRequest.sourceCollection;
        }
        if (entry.collectionName != null && !entry.collectionName.isBlank()) {
            return entry.collectionName;
        }
        return null;
    }

    private String resolveHistoryCollectionName(HistoryEntry entry) {
        if (entry == null) {
            return null;
        }
        if (entry.collectionName != null && !entry.collectionName.isBlank()) {
            return entry.collectionName;
        }
        if (entry.requestSnapshot != null && entry.requestSnapshot.authoredRequest != null
                && entry.requestSnapshot.authoredRequest.sourceCollection != null
                && !entry.requestSnapshot.authoredRequest.sourceCollection.isBlank()) {
            return entry.requestSnapshot.authoredRequest.sourceCollection;
        }
        if (entry.collectionId != null && !entry.collectionId.isBlank()) {
            return entry.collectionId;
        }
        return null;
    }

    private String resolveHistoryRequestId(HistoryEntry entry) {
        if (entry == null) {
            return null;
        }
        if (entry.requestId != null && !entry.requestId.isBlank()) {
            return entry.requestId;
        }
        if (entry.requestSnapshot != null && entry.requestSnapshot.authoredRequest != null
                && entry.requestSnapshot.authoredRequest.id != null
                && !entry.requestSnapshot.authoredRequest.id.isBlank()) {
            return entry.requestSnapshot.authoredRequest.id;
        }
        return null;
    }

    private String resolveHistoryRequestName(HistoryEntry entry) {
        if (entry == null) {
            return null;
        }
        if (entry.requestName != null && !entry.requestName.isBlank()) {
            return entry.requestName;
        }
        if (entry.requestSnapshot != null && entry.requestSnapshot.authoredRequest != null
                && entry.requestSnapshot.authoredRequest.name != null
                && !entry.requestSnapshot.authoredRequest.name.isBlank()) {
            return entry.requestSnapshot.authoredRequest.name;
        }
        return null;
    }

    private String resolveHistoryFolderPath(HistoryEntry entry) {
        if (entry == null) {
            return "";
        }
        if (entry.folderPath != null && !entry.folderPath.isBlank()) {
            return RequestPathResolver.normalizeFolderPath(entry.folderPath);
        }
        if (entry.requestSnapshot != null && entry.requestSnapshot.authoredRequest != null) {
            ApiRequest authoredRequest = entry.requestSnapshot.authoredRequest;
            return RequestPathResolver.normalizeFolderPath(authoredRequest.path);
        }
        return "";
    }

    private String resolveHistoryFolderPath(ApiCollection collection, ApiRequest request, HistoryEntry entry) {
        if (collection != null && request != null) {
            return RequestPathResolver.getRequestFolderPath(collection, request);
        }
        return resolveHistoryFolderPath(entry);
    }

    private List<String> resolveHistoryFolderPathCandidates(HistoryEntry entry) {
        List<String> candidates = new ArrayList<>();
        if (entry == null) {
            return candidates;
        }
        String requestName = resolveHistoryRequestName(entry);
        addHistoryFolderCandidate(candidates, entry.folderPath);
        addLegacyRootFolderCandidate(candidates, entry.folderPath, requestName);
        if (entry.folderPath != null && !entry.folderPath.isBlank()) {
            addHistoryFolderCandidate(candidates, RequestPathResolver.getRequestFolderPath(entry.folderPath, requestName, false));
            addHistoryFolderCandidate(candidates, RequestPathResolver.getCanonicalFolderPath(entry.folderPath, requestName));
        }
        if (entry.requestSnapshot != null && entry.requestSnapshot.authoredRequest != null) {
            ApiRequest authoredRequest = entry.requestSnapshot.authoredRequest;
            addHistoryFolderCandidate(candidates, authoredRequest.path);
            addLegacyRootFolderCandidate(candidates, authoredRequest.path, authoredRequest.name);
            addHistoryFolderCandidate(candidates, RequestPathResolver.getRequestFolderPath(authoredRequest.path, authoredRequest.name, false));
            addHistoryFolderCandidate(candidates, RequestPathResolver.getCanonicalFolderPath(authoredRequest.path, authoredRequest.name));
        }
        return candidates;
    }

    private List<String> resolveRequestFolderPathCandidates(ApiCollection collection, ApiRequest request) {
        List<String> candidates = new ArrayList<>();
        if (request == null) {
            return candidates;
        }
        String collectionAwareFolder = RequestPathResolver.getRequestFolderPath(collection, request);
        if (collectionAwareFolder == null || collectionAwareFolder.isBlank()) {
            candidates.add("");
        } else {
            addHistoryFolderCandidate(candidates, collectionAwareFolder);
        }
        addHistoryFolderCandidate(candidates, RequestPathResolver.getRequestFolderPath(request.path, request.name, false));
        addHistoryFolderCandidate(candidates, RequestPathResolver.getCanonicalFolderPath(request.path, request.name));
        return candidates;
    }

    private boolean matchesAnyFolderCandidate(List<String> entryCandidates, List<String> requestCandidates) {
        if (entryCandidates == null || entryCandidates.isEmpty() || requestCandidates == null || requestCandidates.isEmpty()) {
            return false;
        }
        for (String entryCandidate : entryCandidates) {
            for (String requestCandidate : requestCandidates) {
                if (Objects.equals(entryCandidate, requestCandidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addHistoryFolderCandidate(List<String> candidates, String candidate) {
        String normalized = RequestPathResolver.normalizeFolderPath(candidate);
        if (!normalized.isBlank() && !candidates.contains(normalized)) {
            candidates.add(normalized);
        }
    }

    private void addLegacyRootFolderCandidate(List<String> candidates, String candidate, String requestName) {
        String normalizedCandidate = RequestPathResolver.normalizeFolderPath(candidate);
        String normalizedName = RequestPathResolver.normalizeFolderPath(requestName);
        if (!normalizedCandidate.isBlank()
                && !normalizedCandidate.contains("/")
                && Objects.equals(normalizedCandidate, normalizedName)
                && !candidates.contains("")) {
            candidates.add("");
        }
    }

    private String resolveHistoryMethod(HistoryEntry entry) {
        if (entry == null || entry.requestSnapshot == null) {
            return null;
        }
        if (entry.requestSnapshot.method != null && !entry.requestSnapshot.method.isBlank()) {
            return entry.requestSnapshot.method;
        }
        if (entry.requestSnapshot.authoredRequest != null && entry.requestSnapshot.authoredRequest.method != null
                && !entry.requestSnapshot.authoredRequest.method.isBlank()) {
            return entry.requestSnapshot.authoredRequest.method;
        }
        return null;
    }

    private String resolveHistoryUrlTemplate(HistoryEntry entry) {
        if (entry == null || entry.requestSnapshot == null) {
            return null;
        }
        if (entry.requestSnapshot.urlTemplate != null && !entry.requestSnapshot.urlTemplate.isBlank()) {
            return entry.requestSnapshot.urlTemplate;
        }
        if (entry.requestSnapshot.authoredRequest != null && entry.requestSnapshot.authoredRequest.url != null
                && !entry.requestSnapshot.authoredRequest.url.isBlank()) {
            return entry.requestSnapshot.authoredRequest.url;
        }
        return null;
    }

    private String buildAmbiguousHistoryMatchMessage(String collectionName,
                                                     String requestName,
                                                     String folderPath,
                                                     String method,
                                                     String urlTemplate,
                                                     int candidateCount) {
        StringBuilder sb = new StringBuilder("History request could not be uniquely resolved");
        if (collectionName != null && !collectionName.isBlank()) {
            sb.append(" in collection ").append(collectionName);
        }
        if (requestName != null && !requestName.isBlank()) {
            sb.append(" for request ").append(requestName);
        }
        if (folderPath != null && !folderPath.isBlank()) {
            sb.append(" [folder=").append(folderPath).append(']');
        }
        if (method != null && !method.isBlank()) {
            sb.append(" [method=").append(method).append(']');
        }
        if (urlTemplate != null && !urlTemplate.isBlank()) {
            sb.append(" [url=").append(urlTemplate).append(']');
        }
        if (candidateCount > 0) {
            sb.append(" (").append(candidateCount).append(" matches)");
        }
        sb.append(". Loading under History Replays instead.");
        return sb.toString();
    }

    private ApiCollection ensureHistoryReplaysCollection() {
        ApiCollection existing = findCollectionByName(HISTORY_TAB_NAME + " Replays");
        if (existing != null) {
            existing.ensureId();
            return existing;
        }
        ApiCollection collection = new ApiCollection();
        collection.ensureId();
        collection.name = HISTORY_TAB_NAME + " Replays";
        collection.description = "Auto-created collection for replaying history entries.";
        collection.requests = new ArrayList<>();
        collection.folderPaths = new ArrayList<>();
        collection.variables = new ArrayList<>();
        collection.folderVars = new LinkedHashMap<>();
        collection.environment = new LinkedHashMap<>();
        collection.folderAuthModes = new LinkedHashMap<>();
        collection.folderAuth = new LinkedHashMap<>();
        collection.runtimeVars = new LinkedHashMap<>();
        collection.runtimeOAuth2 = new LinkedHashMap<>();
        loadedCollections.add(collection);
        registerCollectionRuntimeListener(collection);
        refreshCollectionCombos();
        return collection;
    }

    private ApiRequest findRequestById(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        for (ApiCollection collection : loadedCollections) {
            ApiRequest request = findRequestByIdInCollection(collection, requestId);
            if (request != null) {
                return request;
            }
        }
        return null;
    }

    private ApiRequest findRequestByIdInCollection(ApiCollection collection, String requestId) {
        if (collection == null || requestId == null || requestId.isBlank() || collection.requests == null) {
            return null;
        }
        for (ApiRequest request : collection.requests) {
            if (request != null && requestId.equals(request.id)) {
                return request;
            }
        }
        return null;
    }

    private ApiCollection findCollectionContainingRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        for (ApiCollection collection : loadedCollections) {
            if (findRequestByIdInCollection(collection, requestId) != null) {
                return collection;
            }
        }
        return null;
    }

    private void selectRequestInTree(ApiRequest request) {
        if (requestTree == null || request == null) {
            return;
        }
        TreePath path = findRequestTreePathByRequest(request);
        if (path != null) {
            selectTreePath(path);
        }
    }

    private static final class HistoryRequestContext {
        final ApiCollection collection;
        final ApiRequest request;
        final boolean originalRequestExists;
        final boolean ambiguousResolution;
        final String resolutionNote;

        private HistoryRequestContext(ApiCollection collection, ApiRequest request, boolean originalRequestExists) {
            this(collection, request, originalRequestExists, false, null);
        }

        private HistoryRequestContext(ApiCollection collection,
                                      ApiRequest request,
                                      boolean originalRequestExists,
                                      boolean ambiguousResolution,
                                      String resolutionNote) {
            this.collection = collection;
            this.request = request;
            this.originalRequestExists = originalRequestExists;
            this.ambiguousResolution = ambiguousResolution;
            this.resolutionNote = resolutionNote;
        }
    }

    private static final class HistoryRequestMatch {
        final ApiCollection collection;
        final ApiRequest request;

        private HistoryRequestMatch(ApiCollection collection, ApiRequest request) {
            this.collection = collection;
            this.request = request;
        }
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
        liveRequest.scriptBlocks = copyScriptBlocks(edited.scriptBlocks);
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

    private static List<ApiRequest.Header> mergeHiddenTransportHeaders(List<ApiRequest.Header> templateHeaders, List<ApiRequest.Header> editedHeaders) {
        if (templateHeaders == null || templateHeaders.isEmpty()) {
            return copyHeaders(editedHeaders);
        }
        List<ApiRequest.Header> visibleHeaders = new ArrayList<>();
        if (editedHeaders != null) {
            for (ApiRequest.Header header : editedHeaders) {
                if (header == null || header.key == null || header.key.isBlank()) {
                    continue;
                }
                if (isHiddenTransportHeader(header.key)) {
                    continue;
                }
                visibleHeaders.add(header);
            }
        }

        List<ApiRequest.Header> merged = new ArrayList<>();
        int visibleIndex = 0;
        for (ApiRequest.Header header : templateHeaders) {
            if (header == null || header.key == null || header.key.isBlank()) {
                continue;
            }
            if (isHiddenTransportHeader(header.key)) {
                merged.add(new ApiRequest.Header(header.key, header.value, header.disabled));
            } else if (visibleIndex < visibleHeaders.size()) {
                ApiRequest.Header visible = visibleHeaders.get(visibleIndex++);
                merged.add(new ApiRequest.Header(visible.key, visible.value, visible.disabled));
            }
        }
        while (visibleIndex < visibleHeaders.size()) {
            ApiRequest.Header visible = visibleHeaders.get(visibleIndex++);
            merged.add(new ApiRequest.Header(visible.key, visible.value, visible.disabled));
        }
        return merged;
    }

    private static boolean isHiddenTransportHeader(String headerName) {
        if (headerName == null) {
            return false;
        }
        String normalized = headerName.trim().toLowerCase(Locale.ROOT);
        return "host".equals(normalized)
                || "content-length".equals(normalized)
                || "transfer-encoding".equals(normalized);
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

    private static List<burp.scripts.ScriptBlock> copyScriptBlocks(List<burp.scripts.ScriptBlock> scripts) {
        List<burp.scripts.ScriptBlock> out = new ArrayList<>();
        if (scripts == null) {
            return out;
        }
        for (burp.scripts.ScriptBlock block : scripts) {
            burp.scripts.ScriptBlock copy = burp.scripts.ScriptBlock.copyOf(block);
            if (copy != null) {
                out.add(copy);
            }
        }
        return out;
    }

    private void updateWorkbenchDetailPaneSuccess(ApiRequest sentRequest,
                                                  ApiCollection sentCollection,
                                                  UniversalImporter.SingleSendResult result,
                                                  String sendModeLabel) {
        EnvironmentProfile activeEnvironment = getActiveEnvironment();
        HistoryEntry detailEntry = buildWorkbenchExecutionEntry(sentCollection, sentRequest, result, sendModeLabel, null, activeEnvironment);
        WorkbenchSendSnapshot snapshot = new WorkbenchSendSnapshot(
                result != null ? result.builtRequest : null,
                result != null && result.response != null ? result.response.response() : null,
                buildWorkbenchMetaText(sentCollection, sentRequest, result, sendModeLabel, null, activeEnvironment),
                buildWorkbenchScriptOutputText(result != null ? result.executionResult : null),
                buildWorkbenchAssertionsText(result != null ? result.executionResult : null),
                null,
                sendModeLabel,
                System.currentTimeMillis());
        snapshot.detailEntry = detailEntry;
        applyWorkbenchSendSnapshot(sentRequest, sentCollection, snapshot);
    }

    private void updateWorkbenchDetailPaneFailure(ApiRequest sentRequest,
                                                  ApiCollection sentCollection,
                                                  String reason,
                                                  String sendModeLabel) {
        EnvironmentProfile activeEnvironment = getActiveEnvironment();
        HistoryEntry detailEntry = buildWorkbenchExecutionEntry(sentCollection, sentRequest, null, sendModeLabel, reason, activeEnvironment);
        WorkbenchSendSnapshot snapshot = new WorkbenchSendSnapshot(
                null,
                null,
                buildWorkbenchMetaText(sentCollection, sentRequest, null, sendModeLabel, reason, activeEnvironment),
                "No script output for this request.",
                "No assertions or extractions for this request.",
                reason,
                sendModeLabel,
                System.currentTimeMillis());
        snapshot.detailEntry = detailEntry;
        applyWorkbenchSendSnapshot(sentRequest, sentCollection, snapshot);
    }

    void applyWorkbenchSendSnapshot(ApiRequest sentRequest, ApiCollection sentCollection, WorkbenchSendSnapshot snapshot) {
        if (sentRequest == null || snapshot == null) {
            return;
        }
        if (findCollectionByRequest(sentRequest) == null) {
            return;
        }
        workbenchSendSnapshots.put(sentRequest, snapshot);
        if (isWorkbenchRequestSelection(sentRequest, sentCollection)) {
            displayWorkbenchSendSnapshot(snapshot);
        }
    }

    WorkbenchSendSnapshot getWorkbenchSendSnapshot(ApiRequest request) {
        return request != null ? workbenchSendSnapshots.get(request) : null;
    }

    void removeWorkbenchSendSnapshot(ApiRequest request) {
        if (request != null) {
            workbenchSendSnapshots.remove(request);
        }
    }

    void removeWorkbenchSendSnapshotsForRequests(Collection<ApiRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        for (ApiRequest request : requests) {
            removeWorkbenchSendSnapshot(request);
        }
    }

    String getWorkbenchDetailMetaTextForTest() {
        return workbenchDetailPanel != null ? workbenchDetailPanel.getMetadataArea().getText() : "";
    }

    private boolean isWorkbenchRequestSelection(ApiRequest sentRequest, ApiCollection sentCollection) {
        if (requestEditor == null || sentRequest == null) {
            return false;
        }
        ApiRequest currentRequest = requestEditor.getCurrentRequest();
        ApiCollection currentCollection = requestEditor.getCurrentCollection();
        if (currentRequest == null || currentCollection == null) {
            return false;
        }
        if (currentRequest != sentRequest) {
            return false;
        }
        return sentCollection == null || currentCollection == sentCollection;
    }

    void showWorkbenchSendSnapshotForSelection(ApiRequest request) {
        WorkbenchSendSnapshot snapshot = request != null ? workbenchSendSnapshots.get(request) : null;
        if (snapshot == null) {
            if (request != null && requestEditor != null) {
                displayWorkbenchPendingSelection(request, requestEditor.getCurrentCollection());
            } else {
                clearWorkbenchDetailPane();
            }
            return;
        }
        displayWorkbenchSendSnapshot(snapshot);
    }

    private void displayWorkbenchPendingSelection(ApiRequest request, ApiCollection collection) {
        if (workbenchDetailPanel == null) {
            return;
        }
        workbenchDetailPanel.showEntry(buildWorkbenchPreviewEntry(collection, request, requestEditor != null ? requestEditor.getSendModeLabel() : ""));
    }

    private void displayWorkbenchSendSnapshot(WorkbenchSendSnapshot snapshot) {
        if (snapshot == null) {
            clearWorkbenchDetailPane();
            return;
        }
        if (workbenchDetailPanel != null) {
            HistoryEntry detailEntry = snapshot.detailEntry != null
                    ? HistoryEntry.copyOf(snapshot.detailEntry)
                    : new HistoryEntry();
            detailEntry.metadataSummaryText = snapshot.metaText;
            detailEntry.scriptOutputSummaryText = snapshot.scriptOutputText;
            detailEntry.assertionsSummaryText = snapshot.assertionsText;
            detailEntry.errorMessage = snapshot.failureReason;
            if (detailEntry.source == null) {
                detailEntry.source = HistorySource.WORKBENCH;
            }
            if (detailEntry.timestamp == null) {
                detailEntry.timestamp = java.time.Instant.now();
            }
            if (detailEntry.id == null || detailEntry.id.isBlank()) {
                detailEntry.id = UUID.randomUUID().toString();
            }
            workbenchDetailPanel.showEntry(detailEntry);
            if (snapshot.builtRequest != null) {
                workbenchDetailPanel.setRequestMessage(snapshot.builtRequest);
            }
            if (snapshot.response != null) {
                workbenchDetailPanel.setResponseMessage(snapshot.response);
            }
        }
    }

    private void clearWorkbenchDetailPane() {
        if (workbenchDetailPanel != null) {
            workbenchDetailPanel.clear();
        }
    }

    private String buildWorkbenchMetaText(ApiCollection sentCollection,
                                          ApiRequest edited,
                                          UniversalImporter.SingleSendResult result,
                                          String sendModeLabel,
                                          String failureReason,
                                          EnvironmentProfile activeEnvironment) {
        StringBuilder meta = new StringBuilder();
        String collectionName = sentCollection != null && sentCollection.name != null ? sentCollection.name : (edited != null ? edited.sourceCollection : "");
        String folderPath = sentCollection != null && edited != null ? RequestPathResolver.getRequestFolderPath(sentCollection, edited) : (edited != null && edited.path != null ? edited.path : "");
        String requestName = edited != null && edited.name != null ? edited.name : "(unnamed)";
        String method = edited != null && edited.method != null ? edited.method : "GET";
        String urlTemplate = edited != null && edited.url != null ? edited.url : "";
        String finalResolvedUrl = result != null && result.resolvedUrl != null ? result.resolvedUrl : "";
        String authLine = buildAuthMetaLine(edited);
        String executionSource = result != null && result.executionResult != null && result.executionResult.executionSource != null
                ? result.executionResult.executionSource.name()
                : "Workbench Send";

        if (failureReason != null && !failureReason.isEmpty()) {
            meta.append("Send failed: ").append(failureReason).append("\n");
        }
        meta.append("Collection Name: ").append(collectionName != null ? collectionName : "").append("\n");
        meta.append("Folder Path: ").append(folderPath != null ? folderPath : "").append("\n");
        meta.append("Request Name: ").append(requestName).append("\n");
        meta.append("HTTP Method: ").append(method).append("\n");
        if (authLine != null) {
            meta.append(authLine);
        }
        meta.append("Build Mode: ").append(edited != null && edited.resolveBuildMode() != null ? edited.resolveBuildMode() : "Not yet sent").append("\n");
        meta.append("Active Environment Name: ").append(activeEnvironment != null ? activeEnvironment.displayName() : "No Environment").append("\n");
        meta.append("URL Template: ").append(urlTemplate).append("\n");
        meta.append("Final Resolved URL: ").append(finalResolvedUrl.isBlank() ? "Not yet sent" : finalResolvedUrl).append("\n");
        meta.append("Execution Source: ").append(executionSource).append("\n");
        meta.append("Attempt: ").append(result != null && result.executionResult != null ? "1/1" : "Not yet sent").append("\n");
        int statusCode = 0;
        int responseBytes = 0;
        if (result != null && result.response != null && result.response.response() != null) {
            var response = result.response.response();
            statusCode = response.statusCode();
            responseBytes = response.body() != null ? response.body().getBytes().length : 0;
        }
        meta.append("Duration: ").append(result != null ? result.elapsedMs : 0L).append(" ms\n");
        meta.append("Status: ").append(statusCode > 0 ? statusCode : "Not yet sent").append("\n");
        meta.append("Result Classification: ").append(statusCode > 0 ? (statusCode >= 400 ? "Failure" : "Success") : "Not yet sent").append("\n");
        meta.append("Script Engine: ").append(result != null && result.executionResult != null && result.executionResult.scriptEngineName != null ? result.executionResult.scriptEngineName : "Not yet sent").append("\n");
        meta.append("Script Mode: ").append(scriptMode != null ? scriptMode.label : "").append("\n");
        meta.append("Flow Control State: ").append(result != null && result.executionResult != null && result.executionResult.scriptFlowControl != null ? result.executionResult.scriptFlowControl : "CONTINUE").append("\n");
        meta.append("Flow Message: ").append(result != null && result.executionResult != null && result.executionResult.scriptFlowMessage != null ? result.executionResult.scriptFlowMessage : "").append("\n");
        meta.append("Raw Request Available: ").append(result != null && result.rawRequestText != null ? "yes" : "no").append("\n");
        meta.append("Response Available: ").append(result != null && result.response != null && result.response.response() != null ? "yes" : "no").append("\n");
        meta.append("Response bytes: ").append(responseBytes).append("\n");
        meta.append("Send mode: ").append(sendModeLabel != null ? sendModeLabel : "").append("\n");
        if (result != null && result.rawRequestText != null) {
            Set<String> unresolved = burp.utils.RequestBuilder.findUnresolvedTokens(result.rawRequestText.getBytes(StandardCharsets.UTF_8));
            if (!unresolved.isEmpty()) {
                meta.append("Unresolved tokens: ").append(String.join(", ", unresolved)).append("\n");
            }
        }
        if (result == null) {
            meta.append("Not yet sent\n");
        }
        return meta.toString();
    }

    private String buildWorkbenchScriptOutputText(ExecutionResult executionResult) {
        if (executionResult == null) {
            return "No script output for this request.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Execution Source: ").append(executionResult.executionSource != null ? executionResult.executionSource : "Workbench Send").append('\n');
        sb.append("Script Engine: ").append(executionResult.scriptEngineName != null ? executionResult.scriptEngineName : "").append('\n');
        sb.append("Flow Control: ").append(executionResult.scriptFlowControl != null ? executionResult.scriptFlowControl : "CONTINUE").append('\n');
        sb.append("Flow Message: ").append(executionResult.scriptFlowMessage != null ? executionResult.scriptFlowMessage : "").append('\n');
        sb.append('\n').append("Logs:").append('\n');
        if (executionResult.scriptLogs == null || executionResult.scriptLogs.isEmpty()) {
            sb.append("(none)");
        } else {
            for (var log : executionResult.scriptLogs) {
                if (log == null) {
                    continue;
                }
                sb.append('[').append(log.level != null ? log.level.toUpperCase(Locale.ROOT) : "INFO").append("] ")
                        .append(log.message != null ? log.message : "");
                if (log.scriptName != null && !log.scriptName.isBlank()) {
                    sb.append(" (script=").append(log.scriptName).append(')');
                }
                sb.append('\n');
            }
        }
        sb.append('\n').append("Warnings:").append('\n');
        if (executionResult.scriptWarnings == null || executionResult.scriptWarnings.isEmpty()) {
            sb.append("(none)");
        } else {
            for (String warning : executionResult.scriptWarnings) {
                sb.append(warning != null ? warning : "").append('\n');
            }
        }
        sb.append('\n').append("Errors:").append('\n');
        if (executionResult.scriptErrors == null || executionResult.scriptErrors.isEmpty()) {
            sb.append("(none)");
        } else {
            for (String error : executionResult.scriptErrors) {
                sb.append(error != null ? error : "").append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String buildWorkbenchAssertionsText(ExecutionResult executionResult) {
        if (executionResult == null) {
            return "No assertions or extractions for this request.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Assertions:").append('\n');
        if (executionResult.assertions == null || executionResult.assertions.isEmpty()) {
            sb.append("(none)");
        } else {
            for (RunnerResult.AssertionResult assertion : executionResult.assertions) {
                if (assertion == null) {
                    continue;
                }
                sb.append(assertion.passed ? "[PASS] " : "[FAIL] ")
                        .append(assertion.name != null ? assertion.name : "")
                        .append(" expected=").append(assertion.expected != null ? assertion.expected : "")
                        .append(" actual=").append(assertion.actual != null ? assertion.actual : "")
                        .append('\n');
            }
        }
        sb.append('\n').append("Variable Mutations:").append('\n');
        if (executionResult.scriptVariableMutations == null || executionResult.scriptVariableMutations.isEmpty()) {
            sb.append("(none)");
        } else {
            for (var mutation : executionResult.scriptVariableMutations) {
                if (mutation == null) {
                    continue;
                }
                sb.append(mutation.scope != null ? mutation.scope : "")
                        .append(": ")
                        .append(mutation.key != null ? mutation.key : "")
                        .append(" old=").append(mutation.oldValue != null ? mutation.oldValue : "")
                        .append(" new=").append(mutation.newValue != null ? mutation.newValue : "")
                        .append(" persistent=").append(mutation.persistent)
                        .append('\n');
            }
        }
        sb.append('\n').append("Extractions:").append('\n');
        if (executionResult.extractedVars == null || executionResult.extractedVars.isEmpty()) {
            sb.append("(none)");
        } else {
            for (Map.Entry<String, String> entry : executionResult.extractedVars.entrySet()) {
                sb.append(entry.getKey()).append(" = ").append(entry.getValue() != null ? entry.getValue() : "").append('\n');
            }
        }
        return sb.toString().trim();
    }

    private HistoryEntry buildWorkbenchExecutionEntry(ApiCollection sentCollection,
                                                      ApiRequest sentRequest,
                                                      UniversalImporter.SingleSendResult result,
                                                      String sendModeLabel,
                                                      String failureReason,
                                                      EnvironmentProfile activeEnvironment) {
        HistoryEntry entry = HistoryEntry.fromWorkbenchExecution(
                sentCollection,
                sentRequest,
                activeEnvironment,
                result != null ? result.executionResult : null,
                1,
                1,
                Collections.emptyList());
        if (entry == null) {
            return null;
        }
        VariableResolver resolver = RuntimeResolverFactory.build(sentCollection, sentRequest, activeEnvironment, null);
        String resolvedUrl = result != null && result.resolvedUrl != null && !result.resolvedUrl.isBlank()
                ? result.resolvedUrl
                : resolver.resolve(sentRequest != null ? sentRequest.url : null);
        if (entry.requestSnapshot != null) {
            entry.requestSnapshot.resolvedUrl = resolvedUrl;
            entry.requestSnapshot.resolvedVariables = result != null && result.executionResult != null && result.executionResult.resolvedVariables != null
                    ? new LinkedHashMap<>(result.executionResult.resolvedVariables)
                    : resolver.getVariables();
        }
        entry.finalResolvedUrl = resolvedUrl;
        entry.host = parseHost(resolvedUrl);
        entry.scriptMode = scriptMode != null ? scriptMode.label : null;
        entry.scriptDialect = result != null && result.executionResult != null ? result.executionResult.scriptEngineName : null;
        entry.variablesSummaryText = buildRuntimeVariableSummaryText(
                sentCollection,
                sentRequest,
                activeEnvironment,
                entry.requestSnapshot != null ? entry.requestSnapshot.resolvedVariables : Collections.emptyMap(),
                result != null && result.executionResult != null ? result.executionResult.scriptVariableMutations : Collections.emptyList(),
                "Workbench",
                false);
        entry.scriptOutputSummaryText = buildWorkbenchScriptOutputText(result != null ? result.executionResult : null);
        entry.assertionsSummaryText = buildWorkbenchAssertionsText(result != null ? result.executionResult : null);
        if (failureReason != null && !failureReason.isBlank()) {
            entry.errorMessage = failureReason;
        }
        entry.resultClassification = entry.result != null ? entry.result.displayName() : null;
        return entry;
    }

    private HistoryEntry buildWorkbenchPreviewEntry(ApiCollection collection, ApiRequest request, String sendModeLabel) {
        EnvironmentProfile activeEnvironment = getActiveEnvironment();
        HistoryEntry entry = HistoryEntry.fromWorkbenchExecution(collection, request, activeEnvironment, null, 1, 1, Collections.emptyList());
        if (entry == null) {
            return null;
        }
        VariableResolver resolver = RuntimeResolverFactory.build(collection, request, activeEnvironment, null);
        String resolvedUrl = resolver.resolve(request != null ? request.url : null);
        if (entry.requestSnapshot != null) {
            entry.requestSnapshot.resolvedUrl = resolvedUrl;
            entry.requestSnapshot.resolvedVariables = resolver.getVariables();
        }
        entry.finalResolvedUrl = resolvedUrl;
        entry.host = parseHost(resolvedUrl);
        entry.result = null;
        entry.ensureDefaults();
        entry.scriptMode = scriptMode != null ? scriptMode.label : null;
        entry.scriptDialect = sendModeLabel != null && !sendModeLabel.isBlank() ? sendModeLabel : null;
        entry.variablesSummaryText = buildRuntimeVariableSummaryText(
                collection,
                request,
                activeEnvironment,
                entry.requestSnapshot != null ? entry.requestSnapshot.resolvedVariables : Collections.emptyMap(),
                Collections.emptyList(),
                "Workbench preview",
                true);
        entry.scriptOutputSummaryText = "Not executed yet.";
        entry.assertionsSummaryText = "Not executed yet.";
        entry.resultClassification = "Not executed yet";
        return entry;
    }

    private HistoryEntry buildRunnerHistoryEntry(ApiCollection collection, ApiRequest request, RunnerResult result, boolean preview) {
        EnvironmentProfile activeEnvironment = getActiveEnvironment();
        HistoryEntry entry = HistoryEntry.fromRunnerAttempt(collection, request, activeEnvironment, result);
        if (entry == null) {
            return null;
        }
        VariableResolver resolver = RuntimeResolverFactory.build(collection, request, activeEnvironment, null);
        String resolvedUrl = result != null && result.requestUrl != null && !result.requestUrl.isBlank()
                ? result.requestUrl
                : resolver.resolve(request != null ? request.url : null);
        if (entry.requestSnapshot != null) {
            entry.requestSnapshot.resolvedUrl = resolvedUrl;
            entry.requestSnapshot.resolvedVariables = result != null && result.resolvedVariables != null
                    ? new LinkedHashMap<>(result.resolvedVariables)
                    : resolver.getVariables();
        }
        entry.finalResolvedUrl = resolvedUrl;
        entry.host = result != null && result.host != null && !result.host.isBlank() ? result.host : parseHost(resolvedUrl);
        entry.scriptMode = scriptMode != null ? scriptMode.label : null;
        entry.scriptDialect = result != null ? result.scriptEngineName : null;
        entry.variablesSummaryText = buildRuntimeVariableSummaryText(
                collection,
                request,
                activeEnvironment,
                entry.requestSnapshot != null ? entry.requestSnapshot.resolvedVariables : Collections.emptyMap(),
                result != null ? result.scriptVariableMutations : Collections.emptyList(),
                "Runner",
                preview);
        entry.scriptOutputSummaryText = buildRunnerScriptOutputText(result, preview);
        entry.assertionsSummaryText = buildRunnerAssertionsText(result, preview);
        if (preview) {
            entry.result = null;
            entry.ensureDefaults();
            entry.resultClassification = "Not executed yet";
        } else {
            entry.resultClassification = entry.result != null ? entry.result.displayName() : null;
        }
        return entry;
    }

    private HistoryEntry buildRunnerQueuePreviewEntry(ApiCollection collection, ApiRequest request) {
        return buildRunnerHistoryEntry(collection, request, null, true);
    }

    private HistoryEntry buildRunnerCompleteEntry(List<RunnerResult> results) {
        return buildRunnerTerminalEntry(
                new RunnerTerminationResult(
                        RunnerTerminationType.COMPLETED,
                        "Runner completed successfully.",
                        null,
                        null,
                        null,
                        results != null ? (int) results.stream().filter(r -> r != null && !r.dependentExecution && !r.adHocExecution).count() : 0,
                        results != null ? (int) results.stream().filter(r -> r != null && !r.dependentExecution && !r.adHocExecution).count() : 0,
                        results != null ? (int) results.stream().filter(r -> r != null && !r.success).count() : 0,
                        null,
                        "completed",
                        null),
                results);
    }

    private HistoryEntry buildRunnerTerminalEntry(RunnerTerminationResult termination, List<RunnerResult> results) {
        HistoryEntry entry = new HistoryEntry();
        entry.id = UUID.randomUUID().toString();
        entry.timestamp = java.time.Instant.now();
        entry.source = HistorySource.RUNNER;
        entry.requestName = "Runner " + (termination != null ? termination.displayLabel() : "Terminal");
        entry.collectionName = "Runner";
        entry.collectionId = "Runner";
        entry.result = termination != null && termination.isCompleted() ? HistoryResult.SUCCESS
                : termination != null && termination.isInternalError() ? HistoryResult.ERROR
                : HistoryResult.STOPPED;
        entry.resultClassification = termination != null ? termination.displayLabel() : HistoryResult.UNKNOWN.displayName();
        entry.scriptMode = scriptMode != null ? scriptMode.label : null;
        int count = terminalCompletedCount(termination, results);
        long failure = terminalFailureCount(termination, count, results);
        long success = Math.max(0, count - (int) failure);
        long extracted = results != null ? results.stream().mapToLong(r -> r != null && r.extractedVariables != null ? r.extractedVariables.size() : 0).sum() : 0L;
        String reason = termination != null && termination.reason != null && !termination.reason.isBlank()
                ? termination.reason
                : "No additional details.";
        entry.variablesSummaryText = "Runner " + (termination != null ? termination.displayLabel() : "Terminal") + ".\n"
                + "Total requests: " + count + "\n"
                + "Successful: " + success + "\n"
                + "Failed: " + failure + "\n"
                + "Extracted variables: " + extracted;
        entry.scriptOutputSummaryText = "Runner " + (termination != null ? termination.displayLabel() : "Terminal") + ".\n"
                + "Reason: " + reason
                + "\nCompleted: " + (termination != null ? termination.completedCount : count)
                + "/" + (termination != null ? termination.totalQueuedCount : count);
        entry.assertionsSummaryText = count > 0 ? "Final request count: " + count : "No runner requests executed.";
        entry.metadataSummaryText = "Runner termination: " + (termination != null ? termination.displayLabel() : "Unknown") + "\n"
                + "Reason: " + reason + "\n"
                + "Completed Requests: " + count + "\n"
                + "Queued Requests: " + (termination != null ? termination.totalQueuedCount : count) + "\n"
                + "Failure Count: " + failure;
        return entry;
    }

    private String buildRunnerScriptOutputText(RunnerResult result, boolean preview) {
        if (result == null) {
            return preview ? "Not executed yet." : "No script output for this request.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Execution Source: ").append(result.executionSource != null ? result.executionSource : "RUNNER").append('\n');
        sb.append("Script Engine: ").append(result.scriptEngineName != null ? result.scriptEngineName : "").append('\n');
        sb.append("Flow Control: ").append(result.scriptFlowControl != null ? result.scriptFlowControl : "CONTINUE").append('\n');
        sb.append("Flow Message: ").append(result.scriptFlowMessage != null ? result.scriptFlowMessage : "").append('\n');
        sb.append('\n').append("Logs:").append('\n');
        if (result.scriptLogs == null || result.scriptLogs.isEmpty()) {
            sb.append("(none)");
        } else {
            for (var log : result.scriptLogs) {
                if (log == null) {
                    continue;
                }
                sb.append('[').append(log.level != null ? log.level.toUpperCase(Locale.ROOT) : "INFO").append("] ")
                        .append(log.message != null ? log.message : "");
                if (log.scriptName != null && !log.scriptName.isBlank()) {
                    sb.append(" (script=").append(log.scriptName).append(')');
                }
                sb.append('\n');
            }
        }
        sb.append('\n').append("Warnings:").append('\n');
        if (result.scriptWarnings == null || result.scriptWarnings.isEmpty()) {
            sb.append("(none)");
        } else {
            for (String warning : result.scriptWarnings) {
                sb.append(warning != null ? warning : "").append('\n');
            }
        }
        sb.append('\n').append("Errors:").append('\n');
        if (result.scriptErrors == null || result.scriptErrors.isEmpty()) {
            sb.append("(none)");
        } else {
            for (String error : result.scriptErrors) {
                sb.append(error != null ? error : "").append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String buildRunnerAssertionsText(RunnerResult result, boolean preview) {
        if (result == null) {
            return preview ? "Not executed yet." : "No assertions or extractions for this request.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Assertions:").append('\n');
        if (result.assertions == null || result.assertions.isEmpty()) {
            sb.append("(none)");
        } else {
            for (RunnerResult.AssertionResult assertion : result.assertions) {
                if (assertion == null) {
                    continue;
                }
                sb.append(assertion.passed ? "[PASS] " : "[FAIL] ")
                        .append(assertion.name != null ? assertion.name : "")
                        .append(" expected=").append(assertion.expected != null ? assertion.expected : "")
                        .append(" actual=").append(assertion.actual != null ? assertion.actual : "")
                        .append('\n');
            }
        }
        sb.append('\n').append("Variable Mutations:").append('\n');
        if (result.scriptVariableMutations == null || result.scriptVariableMutations.isEmpty()) {
            sb.append("(none)");
        } else {
            for (ScriptVariableMutation mutation : result.scriptVariableMutations) {
                if (mutation == null) {
                    continue;
                }
                sb.append(mutation.scope != null ? mutation.scope : "")
                        .append(": ")
                        .append(mutation.key != null ? mutation.key : "")
                        .append(" old=").append(mutation.oldValue != null ? mutation.oldValue : "")
                        .append(" new=").append(mutation.newValue != null ? mutation.newValue : "")
                        .append(" persistent=").append(mutation.persistent)
                        .append(mutation.sourceScriptName != null ? " script=" + mutation.sourceScriptName : "")
                        .append('\n');
            }
        }
        sb.append('\n').append("Extractions:").append('\n');
        if (result.extractedVariables == null || result.extractedVariables.isEmpty()) {
            sb.append("(none)");
        } else {
            for (Map.Entry<String, String> entry : result.extractedVariables.entrySet()) {
                sb.append(entry.getKey()).append(" = ").append(entry.getValue() != null ? entry.getValue() : "").append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String buildRuntimeVariableSummaryText(ApiCollection collection,
                                                   ApiRequest request,
                                                   EnvironmentProfile activeEnvironment,
                                                   Map<String, String> resolvedVariables,
                                                   List<ScriptVariableMutation> mutations,
                                                   String contextLabel,
                                                   boolean preview) {
        StringBuilder sb = new StringBuilder();
        sb.append(contextLabel != null && !contextLabel.isBlank() ? contextLabel : "Variables / Environment").append('\n');
        sb.append("Active Environment: ").append(activeEnvironment != null ? activeEnvironment.displayName() : "No Environment").append('\n');
        if (preview) {
            sb.append("State: Not executed yet.\n");
        }
        if (resolvedVariables == null || resolvedVariables.isEmpty()) {
            sb.append(preview ? "No preview variables available." : "No resolved variables available.");
        } else {
            sb.append('\n').append("Resolved Variables / Environment:").append('\n');
            List<String> keys = new ArrayList<>(resolvedVariables.keySet());
            keys.sort(String.CASE_INSENSITIVE_ORDER);
            for (String key : keys) {
                if (key == null || key.isBlank()) {
                    continue;
                }
                RuntimeResolverFactory.ResolutionTrace trace = RuntimeResolverFactory.inspect(collection, request, activeEnvironment, null, key);
                String resolvedValue = maskVariablePreview(key, resolvedVariables.get(key));
                sb.append("- ").append(key).append('\n');
                sb.append("  resolved value / preview: ").append(resolvedValue != null && !resolvedValue.isBlank() ? resolvedValue : "").append('\n');
                sb.append("  winning source: ").append(trace.source != null ? trace.source : "unknown").append('\n');
                sb.append("  shadowed sources: ").append(trace.shadowedSource != null ? trace.shadowedSource : "none").append('\n');
                sb.append("  collection value: ").append(displaySummaryValue(candidateValue(trace, "collection environment", "collection variables"))).append('\n');
                sb.append("  folder value: ").append(displaySummaryValue(candidateValue(trace, "folder variables"))).append('\n');
                sb.append("  active environment value: ").append(displaySummaryValue(candidateValue(trace, "active environment"))).append('\n');
                sb.append("  runtime value: ").append(displaySummaryValue(candidateValue(trace, "collection runtime OAuth2", "collection runtime vars", "runtime overlay", "runtime/script", "runtime"))).append('\n');
                sb.append("  request value: ").append(displaySummaryValue(candidateValue(trace, "request variables"))).append('\n');
                sb.append("  auth/OAuth2 mapped value: ").append(displaySummaryValue(candidateValue(trace, "auth/runtime mapping"))).append('\n');
                sb.append("  persistent: ").append(isPersistentSource(trace.source)).append('\n');
            }
        }
        if (mutations != null && !mutations.isEmpty()) {
            sb.append('\n').append("Variable Mutations:").append('\n');
            for (ScriptVariableMutation mutation : mutations) {
                if (mutation == null) {
                    continue;
                }
                sb.append("- ").append(mutation.key != null ? mutation.key : "").append('\n');
                sb.append("  old value: ").append(displaySummaryValue(mutation.oldValue)).append('\n');
                sb.append("  new value: ").append(displaySummaryValue(mutation.newValue)).append('\n');
                sb.append("  scope: ").append(displaySummaryValue(mutation.scope)).append('\n');
                sb.append("  persistent: ").append(mutation.persistent).append('\n');
                sb.append("  mutation source: ").append(displaySummaryValue(mutation.sourceScriptName)).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String candidateValue(RuntimeResolverFactory.ResolutionTrace trace, String... sourceFragments) {
        if (trace == null || trace.candidates == null || trace.candidates.isEmpty()) {
            return null;
        }
        String match = null;
        for (RuntimeResolverFactory.ResolutionCandidate candidate : trace.candidates) {
            if (candidate == null) {
                continue;
            }
            if (sourceMatches(candidate.source, sourceFragments) || sourceMatches(candidate.scope, sourceFragments) || sourceMatches(candidate.layer, sourceFragments)) {
                match = candidate.value;
            }
        }
        return match;
    }

    private boolean sourceMatches(String source, String... fragments) {
        if (source == null || fragments == null || fragments.length == 0) {
            return false;
        }
        String normalizedSource = source.toLowerCase(Locale.ROOT);
        for (String fragment : fragments) {
            if (fragment == null || fragment.isBlank()) {
                continue;
            }
            if (normalizedSource.contains(fragment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String maskVariablePreview(String key, String value) {
        if (value == null) {
            return null;
        }
        String normalizedKey = key != null ? key.toLowerCase(Locale.ROOT) : "";
        if (normalizedKey.contains("token")
                || normalizedKey.contains("secret")
                || normalizedKey.contains("password")
                || normalizedKey.contains("auth")
                || normalizedKey.contains("credential")
                || normalizedKey.contains("key")
                || normalizedKey.contains("private")
                || normalizedKey.contains("passwd")
                || normalizedKey.contains("pwd")
                || normalizedKey.contains("apikey")) {
            if (value.length() <= 6) {
                return "***";
            }
            return value.substring(0, Math.min(6, value.length())) + "***";
        }
        if (value.length() > 200) {
            return value.substring(0, 200) + "... (" + value.length() + " chars)";
        }
        return value;
    }

    private boolean isPersistentSource(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String normalized = source.toLowerCase(Locale.ROOT);
        if (normalized.contains("request variables")
                || normalized.contains("request")
                || normalized.contains("runtime overlay")
                || normalized.contains("auth/runtime mapping")) {
            return false;
        }
        return true;
    }

    private String displaySummaryValue(String value) {
        return value != null && !value.isBlank() ? value : "none";
    }

    private String buildSummaryTextForRunner(RunnerResult result) {
        if (result == null) {
            return "No runner results yet.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Request: ").append(result.requestName != null ? result.requestName : "").append('\n');
        sb.append("Collection: ").append(result.collectionName != null ? result.collectionName : "").append('\n');
        sb.append("Status: ").append(result.displayLogStatusLabel()).append('\n');
        sb.append("Resolved URL: ").append(result.requestUrl != null ? result.requestUrl : "Not available").append('\n');
        sb.append("Host: ").append(result.host != null ? result.host : "Not available").append('\n');
        sb.append("Duration: ").append(result.responseTimeMs > 0 ? result.responseTimeMs + " ms" : "Not available").append('\n');
        sb.append("Extracted Variables: ").append(result.extractedVariables != null ? result.extractedVariables.size() : 0).append('\n');
        return sb.toString().trim();
    }

    private String htmlEscape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String parseHost(String resolvedUrl) {
        if (resolvedUrl == null || resolvedUrl.isBlank()) {
            return null;
        }
        try {
            return burp.utils.HttpUtils.parseTargetForRequest(resolvedUrl).host;
        } catch (Exception ignored) {
            return null;
        }
    }

    private int nextRunnerExecutionSequence() {
        return ++runnerExecutionSequence;
    }

    private RunnerExecutionTableModel.Entry createExecutionEntry(String type,
                                                                 String state,
                                                                 String requestName,
                                                                 String source,
                                                                 String method,
                                                                 String status,
                                                                 String resultLabel,
                                                                 String duration,
                                                                 String flow,
                                                                 String message,
                                                                 HistoryEntry detailEntry,
                                                                 RunnerResult requestResult,
                                                                 RunnerTimelineRow timelineRow,
                                                                 String requestId,
                                                                 String collectionName) {
        return new RunnerExecutionTableModel.Entry(
                nextRunnerExecutionSequence(),
                java.time.Instant.now(),
                type,
                state,
                requestName,
                source,
                method,
                status,
                resultLabel,
                duration,
                flow,
                message,
                detailEntry,
                requestResult,
                timelineRow,
                requestId,
                collectionName
        );
    }

    private void indexRunnerResult(RunnerResult result) {
        if (result == null) {
            return;
        }
        if (result.requestId != null && !result.requestId.isBlank()) {
            runnerResultById.put(result.requestId, result);
        }
        String nameKey = runnerResultKey(result.collectionName, result.requestName);
        if (!nameKey.isBlank()) {
            runnerResultByName.put(nameKey, result);
        }
        if (result.requestName != null && !result.requestName.isBlank()) {
            runnerResultByName.putIfAbsent(result.requestName, result);
        }
    }

    private String runnerResultKey(String collectionName, String requestName) {
        String collection = collectionName != null ? collectionName.trim() : "";
        String request = requestName != null ? requestName.trim() : "";
        return collection + "\u0000" + request;
    }

    private int resolveRunnerQueueIndex(RunnerResult result) {
        if (result == null) {
            return -1;
        }
        if (result.requestId != null && !result.requestId.isBlank()) {
            ApiRequest request = findRequestById(result.requestId);
            int index = indexOfRunnerQueueRequest(request);
            if (index >= 0) {
                return index;
            }
        }
        if (result.requestName != null && !result.requestName.isBlank()) {
            for (int i = 0; i < runnerQueuedRequests.size(); i++) {
                ApiRequest candidate = runnerQueuedRequests.get(i);
                if (candidate == null) {
                    continue;
                }
                boolean nameMatches = result.requestName.equals(candidate.name);
                boolean collectionMatches = result.collectionName == null
                        || result.collectionName.isBlank()
                        || result.collectionName.equals(candidate.sourceCollection);
                if (nameMatches && collectionMatches) {
                    return i;
                }
            }
        }
        return -1;
    }

    private RunnerResult findRunnerResultForTimeline(RunnerTimelineRow row) {
        if (row == null) {
            return null;
        }
        RunnerResult byName = runnerResultByName.get(runnerResultKey(row.collectionName, row.requestName));
        if (byName != null) {
            return byName;
        }
        if (row.requestName != null && !row.requestName.isBlank()) {
            byName = runnerResultByName.get(row.requestName);
            if (byName != null) {
                return byName;
            }
        }
        if (row.requestName != null && !row.requestName.isBlank()) {
            for (RunnerResult candidate : runnerResultById.values()) {
                if (candidate != null && row.requestName.equals(candidate.requestName)) {
                    if (row.collectionName == null || row.collectionName.isBlank() || row.collectionName.equals(candidate.collectionName)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private RunnerTimelineRow buildTimelineRow(RunnerResult result) {
        RunnerTimelineRow row = new RunnerTimelineRow();
        row.order = result != null ? Math.max(1, result.attemptNumber) : 0;
        row.collectionName = result != null && result.collectionName != null ? result.collectionName : "";
        row.requestName = result != null && result.requestName != null ? result.requestName : "";
        row.status = result != null ? result.displayStatusLabel() : "";
        row.timeMs = result != null ? result.responseTimeMs : 0L;
        row.retries = result != null ? Math.max(0, result.attemptNumber - 1) : 0;
        row.varsChanged = result != null && result.extractedVariables != null ? result.extractedVariables.size() : 0;
        row.assertions = formatRunnerAssertionSummary(result);
        return row;
    }

    private String formatRunnerAssertionSummary(RunnerResult result) {
        if (result == null || result.assertions == null || result.assertions.isEmpty()) {
            return "0/0";
        }
        int passed = 0;
        int total = 0;
        for (RunnerResult.AssertionResult assertion : result.assertions) {
            if (assertion == null) {
                continue;
            }
            total++;
            if (assertion.passed) {
                passed++;
            }
        }
        return passed + "/" + total;
    }

    private RunnerTimelineRow buildTimelineRowFromExecutionEntry(RunnerExecutionTableModel.Entry entry) {
        RunnerTimelineRow row = new RunnerTimelineRow();
        row.order = entry != null ? entry.sequence : 0;
        row.collectionName = entry != null && entry.collectionName != null ? entry.collectionName : "";
        row.requestName = entry != null && entry.requestName != null ? entry.requestName : "";
        row.status = entry != null && entry.result != null && !entry.result.isBlank() ? entry.result : (entry != null ? entry.state : "");
        row.timeMs = entry != null ? parseDurationText(entry.duration) : 0L;
        row.retries = entry != null && entry.requestResult != null ? Math.max(0, entry.requestResult.attemptNumber - 1) : 0;
        row.varsChanged = entry != null && entry.requestResult != null && entry.requestResult.extractedVariables != null
                ? entry.requestResult.extractedVariables.size()
                : 0;
        row.assertions = entry != null && entry.message != null ? entry.message : "";
        return row;
    }

    private long parseDurationText(String duration) {
        if (duration == null || duration.isBlank()) {
            return 0L;
        }
        String normalized = duration.trim().toLowerCase(Locale.ROOT).replace("ms", "").trim();
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private RunnerExecutionTableModel.Entry buildExecutionRowFromRequestStart(RunnerResult result) {
        ApiRequest request = result != null ? findRequestById(result.requestId) : null;
        ApiCollection collection = request != null ? findCollectionByRequest(request) : findCollectionByName(result != null ? result.collectionName : null);
        HistoryEntry detailEntry = buildRunnerHistoryEntry(collection, request, null, true);
        return createExecutionEntry(
                "REQUEST_STARTED",
                "RUNNING",
                result != null && result.requestName != null ? result.requestName : safeRequestName(request),
                result != null && result.collectionName != null ? result.collectionName : safeCollectionName(collection),
                result != null && result.method != null ? result.method : (request != null && request.method != null ? request.method : "GET"),
                "",
                "Starting",
                "",
                "",
                "Request started",
                detailEntry,
                result,
                null,
                result != null ? result.requestId : null,
                result != null && result.collectionName != null ? result.collectionName : safeCollectionName(collection)
        );
    }

    private RunnerExecutionTableModel.Entry buildExecutionRowFromRequestResult(RunnerResult result) {
        ApiRequest request = result != null ? findRequestById(result.requestId) : null;
        ApiCollection collection = request != null ? findCollectionByRequest(request) : findCollectionByName(result != null ? result.collectionName : null);
        HistoryEntry detailEntry = buildRunnerHistoryEntry(collection, request, result, false);
        if (result != null) {
            indexRunnerResult(result);
        }
        RunnerTimelineRow timelineRow = buildTimelineRow(result);
        return createExecutionEntry(
                "REQUEST_COMPLETED",
                result != null && result.success ? "SUCCESS" : "FAILED",
                result != null && result.requestName != null ? result.requestName : safeRequestName(request),
                result != null && result.collectionName != null ? result.collectionName : safeCollectionName(collection),
                result != null && result.method != null ? result.method : (request != null && request.method != null ? request.method : "GET"),
                result != null && result.statusCode > 0 ? String.valueOf(result.statusCode) : "",
                result != null ? result.displayLogStatusLabel() : "",
                result != null && result.responseTimeMs > 0 ? result.responseTimeMs + " ms" : "",
                result != null && result.scriptFlowControl != null ? result.scriptFlowControl.name() : "",
                result != null && result.errorMessage != null && !result.errorMessage.isBlank() ? result.errorMessage : (result != null ? result.displayLogStatusLabel() : ""),
                detailEntry,
                result,
                timelineRow,
                result != null ? result.requestId : null,
                result != null && result.collectionName != null ? result.collectionName : safeCollectionName(collection)
        );
    }

    private RunnerExecutionTableModel.Entry buildExecutionRowFromTimeline(RunnerTimelineRow row, RunnerResult associated) {
        ApiRequest request = associated != null ? findRequestById(associated.requestId) : null;
        ApiCollection collection = request != null ? findCollectionByRequest(request) : findCollectionByName(row != null ? row.collectionName : null);
        HistoryEntry detailEntry = associated != null
                ? buildRunnerHistoryEntry(collection, request, associated, false)
                : buildRunnerCompleteEntry(Collections.emptyList());
        return createExecutionEntry(
                "TIMELINE",
                row != null && row.status != null ? row.status : (associated != null && associated.success ? "SUCCESS" : "INFO"),
                row != null && row.requestName != null ? row.requestName : (associated != null && associated.requestName != null ? associated.requestName : ""),
                row != null && row.collectionName != null ? row.collectionName : (associated != null && associated.collectionName != null ? associated.collectionName : safeCollectionName(collection)),
                associated != null && associated.method != null ? associated.method : (request != null && request.method != null ? request.method : ""),
                row != null && row.status != null ? row.status : "",
                associated != null ? associated.displayLogStatusLabel() : (row != null && row.status != null ? row.status : ""),
                row != null && row.timeMs > 0 ? row.timeMs + " ms" : "",
                row != null && row.retries > 0 ? "retries=" + row.retries : "",
                row != null && row.assertions != null ? row.assertions : "",
                detailEntry,
                associated,
                row,
                associated != null ? associated.requestId : null,
                row != null && row.collectionName != null ? row.collectionName : (associated != null && associated.collectionName != null ? associated.collectionName : safeCollectionName(collection))
        );
    }

    private RunnerExecutionTableModel.Entry buildExecutionRowFromSkip(String requestName, String reason) {
        ApiRequest request = null;
        if (runnerExecutingQueueIndex >= 0 && runnerExecutingQueueIndex < runnerQueuedRequests.size()) {
            request = runnerQueuedRequests.get(runnerExecutingQueueIndex);
        }
        if (request == null && requestName != null && !requestName.isBlank()) {
            for (ApiRequest candidate : runnerQueuedRequests) {
                if (candidate != null && requestName.equals(candidate.name)) {
                    request = candidate;
                    break;
                }
            }
        }
        ApiCollection collection = request != null ? findCollectionByRequest(request) : findCollectionByName(request != null ? request.sourceCollection : null);
        RunnerResult synthetic = new RunnerResult();
        synthetic.requestName = requestName != null ? requestName : safeRequestName(request);
        synthetic.requestId = request != null ? request.id : null;
        synthetic.collectionName = request != null && request.sourceCollection != null ? request.sourceCollection : safeCollectionName(collection);
        synthetic.folderPath = request != null ? request.path : null;
        synthetic.method = request != null && request.method != null ? request.method : "GET";
        synthetic.path = request != null ? request.path : null;
        synthetic.host = request != null ? parseHost(request.url) : null;
        synthetic.requestUrl = request != null ? request.url : null;
        synthetic.success = true;
        synthetic.scriptFlowControl = burp.scripts.ScriptFlowControl.SKIP_REQUEST;
        synthetic.errorMessage = reason;
        synthetic.attemptNumber = 1;
        synthetic.totalAttempts = 1;
        indexRunnerResult(synthetic);
        HistoryEntry detailEntry = buildRunnerHistoryEntry(collection, request, synthetic, false);
        return createExecutionEntry(
                "SKIPPED",
                "SKIPPED",
                synthetic.requestName,
                synthetic.collectionName,
                synthetic.method,
                "SKIPPED",
                synthetic.displayLogStatusLabel(),
                "",
                "SKIP_REQUEST",
                reason != null && !reason.isBlank() ? reason : "Skipped by script",
                detailEntry,
                synthetic,
                null,
                synthetic.requestId,
                synthetic.collectionName
        );
    }

    private RunnerExecutionTableModel.Entry buildExecutionRowFromDebug(String message) {
        HistoryEntry detailEntry = new HistoryEntry();
        detailEntry.id = UUID.randomUUID().toString();
        detailEntry.timestamp = java.time.Instant.now();
        detailEntry.source = HistorySource.RUNNER;
        detailEntry.requestName = "Runner Debug";
        detailEntry.result = HistoryResult.UNKNOWN;
        detailEntry.resultClassification = "Debug";
        detailEntry.scriptOutputSummaryText = message != null ? message : "";
        detailEntry.assertionsSummaryText = "Debug event";
        detailEntry.variablesSummaryText = "Debug event";
        return createExecutionEntry(
                "RUN_DEBUG",
                "INFO",
                "Runner Debug",
                "Runner",
                "",
                "INFO",
                "Debug",
                "",
                "",
                message != null ? message : "",
                detailEntry,
                null,
                null,
                null,
                "Runner"
        );
    }

    private RunnerExecutionTableModel.Entry buildExecutionRowFromError(String message) {
        HistoryEntry detailEntry = new HistoryEntry();
        detailEntry.id = UUID.randomUUID().toString();
        detailEntry.timestamp = java.time.Instant.now();
        detailEntry.source = HistorySource.RUNNER;
        detailEntry.requestName = "Runner Error";
        detailEntry.errorMessage = message;
        detailEntry.result = HistoryResult.ERROR;
        detailEntry.resultClassification = HistoryResult.ERROR.displayName();
        detailEntry.scriptOutputSummaryText = message != null ? message : "";
        detailEntry.assertionsSummaryText = "Runner error";
        detailEntry.variablesSummaryText = "Runner error";
        return createExecutionEntry(
                "RUN_ERROR",
                "ERROR",
                "Runner Error",
                "Runner",
                "",
                "ERROR",
                HistoryResult.ERROR.displayName(),
                "",
                "",
                message != null ? message : "",
                detailEntry,
                null,
                null,
                null,
                "Runner"
        );
    }

    private RunnerExecutionTableModel.Entry buildExecutionRowFromRunnerTerminal(RunnerTerminationResult termination, List<RunnerResult> results) {
        HistoryEntry detailEntry = buildRunnerTerminalEntry(termination, results);
        String summary = detailEntry != null ? detailEntry.scriptOutputSummaryText : (termination != null ? termination.displayLabel() : "Runner terminal.");
        return createExecutionEntry(
                termination != null && termination.isCompleted() ? "RUN_COMPLETED" : "RUN_TERMINATED",
                termination != null ? termination.displayLabel() : "TERMINAL",
                "Runner " + (termination != null ? termination.displayLabel() : "Terminal"),
                "Runner",
                "",
                termination != null ? termination.displayLabel() : "DONE",
                termination != null ? termination.displayLabel() : HistoryResult.SUCCESS.displayName(),
                "",
                "",
                summary,
                detailEntry,
                null,
                null,
                null,
                "Runner"
        );
    }

    private int terminalCompletedCount(RunnerTerminationResult termination, List<RunnerResult> results) {
        if (termination != null) {
            return Math.max(0, termination.completedCount);
        }
        if (results == null) {
            return 0;
        }
        return (int) results.stream().filter(r -> r != null && !r.dependentExecution && !r.adHocExecution).count();
    }

    private int terminalFailureCount(RunnerTerminationResult termination, int completedCount, List<RunnerResult> results) {
        if (termination != null) {
            return Math.max(0, Math.min(termination.failureCount, completedCount));
        }
        if (results == null) {
            return 0;
        }
        long failure = results.stream().filter(r -> r != null && !r.dependentExecution && !r.adHocExecution && !r.success).count();
        return (int) Math.max(0, Math.min(failure, completedCount));
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
        SwingShortcutSupport.installTextComponentShortcuts(environmentRawArea);
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
        installEnvironmentSaveShortcut(environmentRawArea);
        installEnvironmentSaveShortcut(environmentTable);

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

        environmentTopBarPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
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

        environmentTopBarPanel.add(new JLabel("Active Environment:"));
        environmentTopBarPanel.add(environmentCombo);
        environmentTopBarPanel.add(environmentImportBtn);
        environmentTopBarPanel.add(environmentNewBtn);
        environmentTopBarPanel.add(environmentDuplicateBtn);
        environmentTopBarPanel.add(environmentDeleteBtn);
        environmentTopBarPanel.add(environmentSetActiveBtn);
        environmentTopBarPanel.add(environmentExportBtn);
        environmentTopBarPanel.add(environmentSaveBtn);

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

        initializeLegacyVariablesCompatibilityComponents();

        panel.add(environmentTopBarPanel, BorderLayout.NORTH);
        panel.add(centerWrap, BorderLayout.CENTER);
        panel.add(statusPanel, BorderLayout.SOUTH);

        installEnvironmentTransferSupport();
        installEnvironmentSaveShortcut(panel);
        installEnvironmentSaveShortcut(environmentEditorCardPanel);
        updateEnvironmentComboModel();
        updateEnvironmentUiState();
        return panel;
    }

    private void initializeLegacyVariablesCompatibilityComponents() {
        envVarsArea = new JTextArea();
        varsTableModel = new DefaultTableModel(new Object[]{"Key", "Value"}, 0);
        varsTable = RequestEditorTableSupport.createEditableTable(varsTableModel);
        RequestEditorStateMapper.ensureStarterRow(varsTableModel);

        varsEditorCardPanel = new JPanel(new CardLayout());
        varsEditorCardPanel.add(new JScrollPane(envVarsArea), "raw");

        JPanel tablePanel = new JPanel(new BorderLayout(5, 5));
        tablePanel.add(new JScrollPane(varsTable), BorderLayout.CENTER);
        varsEditorCardPanel.add(tablePanel, "table");

        varsRawViewBtn = new JRadioButton("Raw", true);
        varsTableViewBtn = new JRadioButton("Table");
        ButtonGroup group = new ButtonGroup();
        group.add(varsRawViewBtn);
        group.add(varsTableViewBtn);

        varsHintLabel = new JLabel("");
        if (varsAutosaveStatusLabel == null) {
            varsAutosaveStatusLabel = new JLabel("Saved.");
            varsAutosaveStatusLabel.setVisible(false);
        }
    }

    // ========================================================================
    // OAuth2 Tab
    // ========================================================================
    private JPanel createOAuth2Tab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));

        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints topGbc = new GridBagConstraints();
        topGbc.gridx = 0;
        topGbc.gridy = 0;
        topGbc.weightx = 0;
        topGbc.insets = new Insets(2, 2, 2, 8);
        topGbc.anchor = GridBagConstraints.WEST;
        oauth2ActiveEnvironmentLabel = new JLabel("Active Environment: No Environment");
        oauth2ActiveEnvironmentLabel.setForeground(Color.DARK_GRAY);
        top.add(oauth2ActiveEnvironmentLabel, topGbc);

        topGbc.gridx = 1;
        topGbc.weightx = 1;
        topGbc.fill = GridBagConstraints.HORIZONTAL;
        oauth2EnvironmentCombo = new JComboBox<>();
        oauth2EnvironmentCombo.setPrototypeDisplayValue(new EnvironmentRef(null, "No Environment"));
        oauth2EnvironmentCombo.addActionListener(e -> handleOAuth2EnvironmentSelectionChanged());
        top.add(oauth2EnvironmentCombo, topGbc);

        topGbc.gridx = 0;
        topGbc.gridy = 1;
        topGbc.gridwidth = 2;
        topGbc.weightx = 1;
        topGbc.fill = GridBagConstraints.HORIZONTAL;
        oauth2StatusLabel = new JLabel("Saved.");
        oauth2StatusLabel.setForeground(Color.GRAY);
        oauth2AutosaveStatusLabel = oauth2StatusLabel;
        top.add(oauth2StatusLabel, topGbc);

        panel.add(top, BorderLayout.NORTH);

        panel.add(oauth2Panel, BorderLayout.CENTER);

        installEnvironmentTransferSupport();
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

        resultModel = new RunnerExecutionTableModel();
        resultTable = new JTable(resultModel);
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultTable.setFillsViewportHeight(true);
        SwingShortcutSupport.installTableShortcuts(resultTable);
        JScrollPane tableScroll = new JScrollPane(resultTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Runner Execution Table"));
        tableScroll.setPreferredSize(new Dimension(520, 280));
        tableScroll.setMinimumSize(new Dimension(260, 160));
        tableScroll.getHorizontalScrollBar().setUnitIncrement(16);

        timelineModel = new RunnerTimelineTableModel();
        timelineTable = new JTable(timelineModel);

        runnerQueueListModel = new DefaultListModel<>();
        runnerQueueList = new JList<>(runnerQueueListModel);
        runnerQueueList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        runnerQueueList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = (JLabel) new DefaultListCellRenderer().getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            ApiRequest request = value;
            if (request != null) {
                label.setText(request.name != null && !request.name.isBlank() ? request.name : "Request");
                boolean executing = index == runnerExecutingQueueIndex;
                if (executing) {
                    label.setFont(label.getFont().deriveFont(Font.BOLD));
                    Border executionBorder = BorderFactory.createMatteBorder(0, 3, 0, 0,
                            isSelected ? list.getSelectionForeground() : list.getSelectionBackground());
                    label.setBorder(BorderFactory.createCompoundBorder(executionBorder,
                            BorderFactory.createEmptyBorder(0, 4, 0, 0)));
                    if (!isSelected) {
                        label.setBackground(list.getSelectionBackground());
                    }
                }
                String tooltip = buildRunnerQueueTooltip(request);
                label.setToolTipText(tooltip);
            }
            return label;
        });
        runnerQueueList.setToolTipText("");
        runnerQueueList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            ApiRequest selectedQueueRequest = runnerQueueList.getSelectedValue();
            showRunnerQueueSelection(selectedQueueRequest);
        });
        runnerQueueList.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateRunnerQueueTooltipForLocation(e.getPoint());
            }
        });
        runnerQueueScrollPane = new JScrollPane(runnerQueueList);
        runnerQueueScrollPane.setBorder(BorderFactory.createTitledBorder("Runner Queue"));
        runnerQueueScrollPane.setPreferredSize(new Dimension(320, 360));
        runnerQueueScrollPane.setMinimumSize(new Dimension(220, 180));

        runnerDetailPanel = new HistoryDetailPanel(importer != null ? importer.getApi() : null);
        runnerDetailTabs = runnerDetailPanel.getTabbedPane();

        JSplitPane queueSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, runnerQueueScrollPane, tableScroll);
        queueSplit.setResizeWeight(0.30);
        queueSplit.setOneTouchExpandable(true);
        queueSplit.setContinuousLayout(true);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, queueSplit, runnerDetailTabs);
        splitPane.setResizeWeight(0.5);
        splitPane.setOneTouchExpandable(true);
        splitPane.setContinuousLayout(true);
        panel.add(splitPane, BorderLayout.CENTER);

        installRunnerQueueTransferSupport();
        resultTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            int selectedRow = resultTable.getSelectedRow();
            if (selectedRow < 0) {
                updateRunnerDetailPane(null);
                return;
            }
            RunnerExecutionTableModel.Entry row = resultModel.getEntryAt(selectedRow);
            updateRunnerDetailPane(row != null ? row.detailEntry : null);
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
        JButton clearBtn = new JButton("Clear Runner");
        clearBtn.addActionListener(e -> clearRunnerFromUi());
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
        SwingShortcutSupport.installTextComponentShortcuts(runnerLog);
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
        public void mousePressed(MouseEvent e) {
            maybeClearTreeSelectionOnBackgroundClick(e);
            maybeShowTreeContextMenu(e);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            maybeClearTreeSelectionOnBackgroundClick(e);
            maybeShowTreeContextMenu(e);
        }
    }

    private void maybeClearTreeSelectionOnBackgroundClick(MouseEvent e) {
        if (requestTree == null || e == null || e.isPopupTrigger() || !SwingUtilities.isLeftMouseButton(e)) {
            return;
        }
        TreePath path = requestTree.getPathForLocation(e.getX(), e.getY());
        if (path == null && requestTree.getSelectionPath() != null) {
            requestTree.clearSelection();
        }
    }

    private void maybeShowTreeContextMenu(MouseEvent e) {
        if (requestTree == null || e == null || !e.isPopupTrigger()) {
            return;
        }
        TreePath path = requestTree.getPathForLocation(e.getX(), e.getY());
        Object node = path != null ? path.getLastPathComponent() : null;
        if (path != null) {
            requestTree.setSelectionPath(path);
        } else if (requestTree.getSelectionPath() != null) {
            requestTree.clearSelection();
        }
        JPopupMenu menu = buildRequestTreeContextMenu(node);
        if (menu != null && menu.getComponentCount() > 0) {
            menu.show(requestTree, e.getX(), e.getY());
        }
    }

    JPopupMenu buildRequestTreeContextMenu(Object node) {
        JPopupMenu menu = new JPopupMenu();
        if (!(node instanceof CollectionTreeNode)) {
            menu.add(menuItem("New Collection", e -> createNewCollectionFromTree()));
            return menu;
        }

        CollectionTreeNode treeNode = (CollectionTreeNode) node;
        switch (treeNode.getNodeType()) {
            case COLLECTION:
            case FOLDER:
                menu.add(menuItem("New Folder", e -> createNewFolderFromTree(treeNode)));
                menu.add(menuItem("New Request", e -> createNewRequestFromTree(treeNode)));
                menu.addSeparator();
                menu.add(menuItem("Rename", e -> renameNodeFromTree(treeNode)));
                menu.add(menuItem("Duplicate", e -> duplicateNodeFromTree(treeNode)));
                menu.add(menuItem("Delete", e -> deleteNodeFromTree(treeNode)));
                if (treeNode.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                    menu.add(menuItem("Export...", e -> handleCollectionExport(treeNode)));
                    menu.addSeparator();
                } else {
                    menu.addSeparator();
                }
                menu.add(menuItem("Auth Settings...", e -> editAuthForNode(treeNode)));
                break;
            case REQUEST:
                menu.add(menuItem("Rename", e -> renameNodeFromTree(treeNode)));
                menu.add(menuItem("Duplicate", e -> duplicateNodeFromTree(treeNode)));
                menu.add(menuItem("Delete", e -> deleteNodeFromTree(treeNode)));
                menu.addSeparator();
                menu.add(menuItem("Auth Settings...", e -> editAuthForNode(treeNode)));
                break;
        }
        return menu;
    }

    private JMenuItem menuItem(String label, ActionListener listener) {
        JMenuItem item = new JMenuItem(label);
        if (listener != null) {
            item.addActionListener(listener);
        }
        return item;
    }

    private void renameNodeFromTree(CollectionTreeNode node) {
        if (node == null || requestTree == null) {
            return;
        }
        persistCurrentRequestEditorState();
        TreePath path = new TreePath(node.getPath());
        expandTreePath(path);
        requestTree.setSelectionPath(path);
        startTreeRename(path);
    }

    private void duplicateNodeFromTree(CollectionTreeNode node) {
        if (node == null) {
            return;
        }
        switch (node.getNodeType()) {
            case COLLECTION:
                duplicateCollectionNode(node);
                break;
            case FOLDER:
                duplicateFolderNode(node);
                break;
            case REQUEST:
                duplicateRequestNode(node);
                break;
        }
    }

    private void deleteNodeFromTree(CollectionTreeNode node) {
        if (node == null) {
            return;
        }
        switch (node.getNodeType()) {
            case COLLECTION:
                deleteCollectionNode(node);
                break;
            case FOLDER:
                deleteFolderNode(node);
                break;
            case REQUEST:
                deleteRequestNode(node);
                break;
        }
    }

    private void createNewCollectionFromTree() {
        persistCurrentRequestEditorState();
        clearRequestEditorForNonRequestSelection();
        ApiCollection collection = requestTreeMutationService.createCollection(loadedCollections);
        registerCollectionRuntimeListener(collection);
        refreshRequestTreeAfterMutation(() -> {
            TreePath path = findCollectionTreePath(collection);
            if (path != null) {
                selectTreePath(path);
                startTreeRename(path);
            }
        });
    }

    private void createNewFolderFromTree(CollectionTreeNode node) {
        if (node == null) {
            return;
        }
        ApiCollection collection = findCollectionForNode(node);
        if (collection == null) {
            return;
        }
        persistCurrentRequestEditorState();
        clearRequestEditorForNonRequestSelection();
        String parentFolderPath = node.getNodeType() == CollectionTreeNode.Type.FOLDER
                ? RequestTreePathService.normalizeFolderPath(node.folderPath)
                : "";
        String folderPath = requestTreeMutationService.createFolder(collection, parentFolderPath);
        refreshRequestTreeAfterMutation(() -> {
            TreePath path = findFolderTreePath(collection, folderPath);
            if (path != null) {
                selectTreePath(path);
                startTreeRename(path);
            }
        });
    }

    private void createNewRequestFromTree(CollectionTreeNode node) {
        if (node == null) {
            return;
        }
        ApiCollection collection = findCollectionForNode(node);
        if (collection == null) {
            return;
        }
        persistCurrentRequestEditorState();
        String parentFolderPath = node.getNodeType() == CollectionTreeNode.Type.FOLDER
                ? RequestTreePathService.normalizeFolderPath(node.folderPath)
                : "";
        ApiRequest request = requestTreeMutationService.createBlankManualRequest(collection, parentFolderPath);
        refreshRequestTreeAfterMutation(() -> {
            TreePath path = findRequestTreePathByRequest(request);
            if (path != null) {
                selectTreePath(path);
                openRequestInEditor(request, collection);
                startTreeRename(path);
            }
        });
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
        rebuildTree(pendingWorkspaceRequestTreePaths, hasPopulatedRequestTree() ? collectExpandedTreePathKeys() : null);
    }

    private void rebuildTree(Map<String, String> requestTreePaths) {
        rebuildTree(requestTreePaths, hasPopulatedRequestTree() ? collectExpandedTreePathKeys() : null);
    }

    private void rebuildTree(Map<String, String> requestTreePaths, List<String> expandedTreePathKeys) {
        loadRequestTreeModel(requestTreePaths);
        if (expandedTreePathKeys == null) {
            for (int i = 0; i < requestTree.getRowCount(); i++) {
                requestTree.expandRow(i);
            }
        } else if (!expandedTreePathKeys.isEmpty()) {
            restoreExpandedTreePathKeys(expandedTreePathKeys);
        }
        refreshTreePresentation(requestTree);
    }

    private boolean hasPopulatedRequestTree() {
        return treeModel != null
                && treeModel.getRoot() instanceof DefaultMutableTreeNode
                && ((DefaultMutableTreeNode) treeModel.getRoot()).getChildCount() > 0;
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
            if (col.folderPaths != null && !col.folderPaths.isEmpty()) {
                LinkedHashSet<String> explicitFolderPaths = new LinkedHashSet<>();
                for (String folderPath : col.folderPaths) {
                    String normalized = RequestTreePathService.normalizeFolderPath(folderPath);
                    if (!normalized.isEmpty()) {
                        explicitFolderPaths.add(normalized);
                    }
                }
                for (String folderPath : explicitFolderPaths) {
                    CollectionTreeNode parent = colNode;
                    StringBuilder cumulative = new StringBuilder();
                    for (String segment : folderPath.split("/")) {
                        if (segment == null || segment.isBlank()) {
                            continue;
                        }
                        if (cumulative.length() > 0) {
                            cumulative.append('/');
                        }
                        cumulative.append(segment);
                        parent = getOrCreateFolderNode(parent, cumulative.toString());
                    }
                }
            }
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
                String folderPath;
                if (path == null) {
                    folderPath = RequestPathResolver.getRequestFolderPath(col, req);
                } else {
                    folderPath = RequestTreePathService.normalizeFolderPath(path);
                    if (folderPath.isBlank()) {
                        folderPath = RequestPathResolver.getRequestFolderPath(col, req);
                    }
                }

                CollectionTreeNode parent = colNode;
                StringBuilder cumulative = new StringBuilder();
                if (!folderPath.isBlank()) {
                    for (String segment : folderPath.split("/")) {
                        if (segment == null || segment.isBlank()) {
                            continue;
                        }
                        if (cumulative.length() > 0) {
                            cumulative.append("/");
                        }
                        cumulative.append(segment);
                        parent = getOrCreateFolderNode(parent, cumulative.toString());
                    }
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

    private EnvironmentProfile findEnvironmentById(String environmentId) {
        if (environmentId == null || environmentProfiles.isEmpty()) {
            return null;
        }
        for (EnvironmentProfile profile : environmentProfiles) {
            if (profile != null && Objects.equals(environmentId, profile.id)) {
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
        if (active == null) {
            return Collections.emptyMap();
        }
        synchronized (active) {
            return active.toRuntimeOverlay();
        }
    }

    private void commitDirtyActiveEnvironmentBeforeRuntimeUse() {
        if (!environmentDirty) {
            return;
        }
        EnvironmentProfile selected = getSelectedEnvironmentProfile();
        if (selected == null || activeEnvironmentId == null || !Objects.equals(activeEnvironmentId, selected.id)) {
            return;
        }
        commitEnvironmentEditorToSelectedProfile();
    }

    private Map<String, String> activeEnvironmentOverlayForRuntimeUse() {
        commitOAuth2ConfigUiToActiveEnvironment();
        commitOAuth2BindingUiToActiveEnvironment();
        commitDirtyActiveEnvironmentBeforeRuntimeUse();
        return hasActiveEnvironment() ? activeEnvironmentOverlay() : null;
    }

    private Map<String, String> activeEnvironmentOverlayForPreview() {
        return hasActiveEnvironment() ? activeEnvironmentOverlay() : null;
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
        syncWorkbenchEnvironmentControls();
        syncOAuth2EnvironmentControls();
        syncOAuth2UiState();
        notifyWorkspaceChanged();
    }

    public String getActiveEnvironmentId() {
        return activeEnvironmentId;
    }

    public void setActiveEnvironmentId(String environmentId) {
        if (environmentDirty) {
            commitEnvironmentEditorToSelectedProfile();
        }
        commitOAuth2ConfigUiToActiveEnvironment();
        String previousEnvironmentId = activeEnvironmentId;
        activeEnvironmentId = environmentId;
        if (activeEnvironmentId != null && environmentProfiles.stream().noneMatch(profile -> profile != null && Objects.equals(profile.id, activeEnvironmentId))) {
            activeEnvironmentId = null;
        }
        updateEnvironmentComboModel();
        if (activeEnvironmentId != null) {
            selectEnvironmentById(activeEnvironmentId);
        }
        updateEnvironmentUiState();
        syncWorkbenchEnvironmentControls();
        syncOAuth2EnvironmentControls();
        syncOAuth2UiState(true);
        syncActiveEnvironmentToEditors();
        if (!Objects.equals(previousEnvironmentId, activeEnvironmentId)) {
            recordDiagnostic(
                    DiagnosticOperation.ENVIRONMENT_SWITCH,
                    DiagnosticSeverity.INFO,
                    "ImporterPanel",
                    "Active environment switched",
                    "from=" + (previousEnvironmentId != null ? previousEnvironmentId : "none") +
                            "\nto=" + (activeEnvironmentId != null ? activeEnvironmentId : "none"));
        }
        SwingUtilities.invokeLater(this::notifyWorkspaceChangedImmediately);
    }

    private void syncActiveEnvironmentToEditors() {
        if (requestEditor != null && requestEditor.getCurrentRequest() != null) {
            Map<String, String> runtimeOverlay = activeEnvironmentOverlayForPreview();
            requestEditor.setRuntimeVariables(runtimeOverlay != null ? runtimeOverlay : Collections.emptyMap());
        }
    }

    public boolean hasUnsavedEnvironmentEditorChanges() {
        return environmentDirty;
    }

    private void applyRuntimeVariableDeltaToActiveEnvironment(ApiCollection collection,
                                                              Map<String, String> changedVars,
                                                              Set<String> removedKeys) {
        EnvironmentProfile active = getActiveEnvironment();
        if (active == null) {
            if (collection != null) {
                collection.applyRuntimeVarDelta(changedVars, removedKeys);
            }
            return;
        }

        active.ensureDefaults();
        boolean changed = false;
        synchronized (active) {
            if (removedKeys != null) {
                for (String key : removedKeys) {
                    if (key != null && active.variables.remove(key) != null) {
                        changed = true;
                    }
                }
            }
            if (changedVars != null) {
                for (Map.Entry<String, String> entry : changedVars.entrySet()) {
                    String key = entry.getKey();
                    if (key == null || key.isBlank()) {
                        continue;
                    }
                    String value = entry.getValue() != null ? entry.getValue() : "";
                    if (!Objects.equals(active.variables.get(key), value)) {
                        active.variables.put(key, value);
                        changed = true;
                    }
                }
            }
        }

        if (changed) {
            recordDiagnostic(
                    DiagnosticOperation.ENVIRONMENT_SWITCH,
                    DiagnosticSeverity.INFO,
                    "ImporterPanel",
                    "Runtime variables applied to active environment",
                    "collection=" + (collection != null && collection.name != null ? collection.name : "none") +
                            "\nchangedKeys=" + (changedVars != null ? String.join(", ", changedVars.keySet()) : "") +
                            "\nremovedKeys=" + (removedKeys != null ? String.join(", ", removedKeys) : ""));
            SwingUtilities.invokeLater(() -> {
                renderSelectedEnvironmentIntoEditor();
                syncActiveEnvironmentToEditors();
                updateEnvironmentUiState();
                notifyWorkspaceChangedImmediately();
            });
        }
    }

    private void commitOAuth2BindingUiToActiveEnvironment() {
        // Legacy bottom-panel binding UI removed; token binding now flows through
        // the Bind Token popup and active-environment variables directly.
        return;
    }

    private Map<String, String> normalizeOAuth2ConfigForComparison(Map<String, String> vars) {
        Map<String, String> normalized = filterOAuth2ConfigVars(vars);
        normalized.remove("oauth2_client_auth");
        return normalized;
    }

    private void applyOAuth2ConfigToEnvironment(EnvironmentProfile environment, Map<String, String> configVars) {
        if (environment == null) {
            return;
        }
        environment.ensureDefaults();
        String preservedClientAuth = environment.oauth2 != null && environment.oauth2.config != null
                ? environment.oauth2.config.get("oauth2_client_auth")
                : null;
        environment.oauth2.config.clear();
        if (configVars != null && !configVars.isEmpty()) {
            environment.oauth2.config.putAll(configVars);
        }
        if (preservedClientAuth != null && !preservedClientAuth.isBlank()) {
            environment.oauth2.config.put("oauth2_client_auth", preservedClientAuth);
        }
        environment.oauth2.ensureDefaults();
        oauth2ConfigDirty = false;
        renderedOAuth2ConfigEnvironmentId = environment.id;
    }

    private void applyOAuth2ConfigToActiveEnvironment(Map<String, String> configVars) {
        applyOAuth2ConfigToEnvironment(getActiveEnvironment(), configVars);
    }

    private void commitOAuth2ConfigUiToActiveEnvironment() {
        EnvironmentProfile active = getActiveEnvironment();
        if (active == null || oauth2Panel == null || oauth2ConfigRefreshPending) {
            return;
        }
        applyOAuth2ConfigToActiveEnvironment(filterOAuth2ConfigVars(oauth2Panel.getVariables()));
    }

    private void markOAuth2ConfigDirty() {
        if (shuttingDown || oauth2Panel == null || oauth2ConfigRefreshPending) {
            return;
        }
        EnvironmentProfile active = getActiveEnvironment();
        Map<String, String> current = normalizeOAuth2ConfigForComparison(oauth2Panel.getVariables());
        Map<String, String> saved = active != null
                ? normalizeOAuth2ConfigForComparison(active.oauth2 != null ? active.oauth2.config : Collections.emptyMap())
                : Collections.emptyMap();
        boolean dirty = active != null ? !saved.equals(current) : !current.isEmpty();
        oauth2ConfigDirty = dirty;
        renderedOAuth2ConfigEnvironmentId = active != null ? active.id : null;
        if (dirty) {
            setOAuth2AutosaveStatus("OAuth2 settings have unsaved changes.", new Color(150, 90, 0));
        }
    }

    private Map<String, String> storeOAuth2TokenInEnvironment(ApiCollection collection,
                                                              EnvironmentProfile environment,
                                                              burp.auth.TokenStore.TokenEntry entry) {
        Map<String, String> stored = new LinkedHashMap<>();
        if (environment == null || entry == null) {
            recordDiagnostic(
                    DiagnosticOperation.OAUTH2_TOKEN_FETCH,
                    DiagnosticSeverity.WARNING,
                    "ImporterPanel",
                    "OAuth2 token store skipped",
                    "activeEnvironment=" + (environment != null ? environment.displayName() : "none") +
                            "\nhasEntry=" + (entry != null));
            return stored;
        }
        String accessBinding = environment.oauth2.outputBindings != null ? environment.oauth2.outputBindings.get("accessToken") : null;
        String refreshBinding = environment.oauth2.outputBindings != null ? environment.oauth2.outputBindings.get("refreshToken") : null;
        String tokenTypeBinding = environment.oauth2.outputBindings != null ? environment.oauth2.outputBindings.get("tokenType") : null;
        String expiresInBinding = environment.oauth2.outputBindings != null ? environment.oauth2.outputBindings.get("expiresIn") : null;

        if (entry.accessToken != null && !entry.accessToken.isBlank()) {
            String key = accessBinding != null && !accessBinding.isBlank() ? accessBinding : "oauth2_access_token";
            environment.variables.put(key, entry.accessToken);
            stored.put(key, entry.accessToken);
        }
        if (entry.refreshToken != null && !entry.refreshToken.isBlank()) {
            String key = refreshBinding != null && !refreshBinding.isBlank() ? refreshBinding : "oauth2_refresh_token";
            environment.variables.put(key, entry.refreshToken);
            stored.put(key, entry.refreshToken);
        }
        if (entry.tokenType != null && !entry.tokenType.isBlank()) {
            String key = tokenTypeBinding != null && !tokenTypeBinding.isBlank() ? tokenTypeBinding : "oauth2_token_type";
            environment.variables.put(key, entry.tokenType);
            stored.put(key, entry.tokenType);
        }
        if (entry.expiresAt > 0) {
            long expiresInSeconds = Math.max(0, (entry.expiresAt - System.currentTimeMillis()) / 1000);
            String key = expiresInBinding != null && !expiresInBinding.isBlank() ? expiresInBinding : "oauth2_expires_in";
            environment.variables.put(key, String.valueOf(expiresInSeconds));
            stored.put(key, String.valueOf(expiresInSeconds));
        }
        if (!stored.isEmpty()) {
            environment.ensureDefaults();
            recordDiagnostic(
                    DiagnosticOperation.OAUTH2_TOKEN_FETCH,
                    DiagnosticSeverity.INFO,
                    "ImporterPanel",
                    "OAuth2 token stored in active environment",
                    "environment=" + environment.displayName() +
                            "\nstoredKeys=" + String.join(", ", stored.keySet()) +
                            "\ncollection=" + (collection != null && collection.name != null ? collection.name : "none"));
            notifyWorkspaceChangedImmediately();
        }
        return stored;
    }

    private Map<String, String> storeOAuth2TokenInActiveEnvironment(ApiCollection collection, burp.auth.TokenStore.TokenEntry entry) {
        return storeOAuth2TokenInEnvironment(collection, getActiveEnvironment(), entry);
    }

    private void clearActiveEnvironmentOAuth2TokenOutputs() {
        EnvironmentProfile active = getActiveEnvironment();
        if (active == null) {
            setOAuth2AutosaveStatus("Tokens cleared. No Active Environment selected.", Color.GRAY);
            syncActiveEnvironmentToEditors();
            if (oauth2Panel != null) {
                oauth2Panel.setLastAcquiredToken(null);
            }
            recordDiagnostic(
                    DiagnosticOperation.OAUTH2_TOKEN_FETCH,
                    DiagnosticSeverity.WARNING,
                    "ImporterPanel",
                    "OAuth2 token clear skipped",
                    "activeEnvironment=none");
            return;
        }

        commitOAuth2ConfigUiToActiveEnvironment();
        active.ensureDefaults();
        Map<String, String> bindings = active.oauth2 != null ? active.oauth2.outputBindings : Collections.emptyMap();

        LinkedHashSet<String> keysToRemove = new LinkedHashSet<>();
        addBindingTarget(keysToRemove, bindings.get("accessToken"));
        addBindingTarget(keysToRemove, bindings.get("refreshToken"));
        addBindingTarget(keysToRemove, bindings.get("tokenType"));
        addBindingTarget(keysToRemove, bindings.get("expiresIn"));

        for (String key : keysToRemove) {
            active.variables.remove(key);
        }

        syncOAuth2PanelFromActiveEnvironment(true);
        renderSelectedEnvironmentIntoEditor(true);
        syncActiveEnvironmentToEditors();
        updateEnvironmentUiState();
        SwingUtilities.invokeLater(this::notifyWorkspaceChangedImmediately);
        setOAuth2AutosaveStatus("Cleared OAuth2 token variables from Active Environment \"" + active.displayName() + "\".", Color.GRAY);
        recordDiagnostic(
                DiagnosticOperation.OAUTH2_TOKEN_FETCH,
                DiagnosticSeverity.INFO,
                "ImporterPanel",
                "OAuth2 token outputs cleared",
                "environment=" + active.displayName() +
                        "\nremovedKeys=" + String.join(", ", keysToRemove));
        if (oauth2Panel != null) {
            oauth2Panel.setLastAcquiredToken(null);
        }
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

    private final class RequestTreeModel extends DefaultTreeModel {
        private RequestTreeModel(TreeNode root) {
            super(root);
        }

        @Override
        public void valueForPathChanged(TreePath path, Object newValue) {
            if (path == null) {
                return;
            }
            Object last = path.getLastPathComponent();
            if (!(last instanceof CollectionTreeNode)) {
                super.valueForPathChanged(path, newValue);
                return;
            }
            CollectionTreeNode node = (CollectionTreeNode) last;
            String currentName = node.getUserObject() != null ? node.getUserObject().toString() : "";
            String requestedName = newValue != null ? newValue.toString().trim() : "";
            if (requestedName.isBlank()) {
                requestedName = currentName;
            }
            if (Objects.equals(currentName, requestedName)) {
                nodeChanged(node);
                return;
            }
            node.setUserObject(requestedName);
            handleTreeNodeRenamed(node, currentName, requestedName);
            nodeChanged(node);
        }
    }

    private void startTreeRename(TreePath path) {
        if (requestTree == null || path == null) {
            return;
        }
        expandTreePath(path);
        requestTree.setSelectionPath(path);
        requestTree.requestFocusInWindow();
        requestTree.startEditingAtPath(path);
    }

    private void selectTreePath(TreePath path) {
        if (requestTree == null || path == null) {
            return;
        }
        expandTreePath(path);
        requestTree.setSelectionPath(path);
        requestTree.scrollPathToVisible(path);
    }

    void openRequestInEditor(ApiRequest request, ApiCollection collection) {
        if (requestEditor == null) {
            return;
        }
        requestEditor.setCurrentCollection(collection);
        syncRequestEditorRuntimeContext(request, collection);
        requestEditor.loadRequest(request);
        requestEditor.setSendControlsEnabled(true);
        showWorkbenchSendSnapshotForSelection(request);
    }

    private void handleRequestTreeSelectionChanged() {
        persistCurrentRequestEditorState();
        CollectionTreeNode node = getSelectedRequestTreeNode();
        if (node != null && node.getNodeType() == CollectionTreeNode.Type.REQUEST && node.request != null) {
            ApiCollection selectedCollection = findCollectionForNode(node);
            openRequestInEditor(node.request, selectedCollection);
            return;
        }
        clearRequestEditorForNonRequestSelection();
    }

    void clearRequestEditorForNonRequestSelection() {
        clearRequestEditorSafely();
        if (requestEditor != null) {
            requestEditor.setSendControlsEnabled(false);
        }
        clearWorkbenchDetailPane();
    }

    private void refreshRequestTreeAfterMutation(Runnable afterRefresh) {
        if (treeModel == null || requestTree == null) {
            if (afterRefresh != null) {
                afterRefresh.run();
            }
            refreshCollectionCombos();
            updateScopeControlState();
            refreshSessionActionControls();
            notifyWorkspaceChangedImmediately();
            return;
        }

        CollectionTreeNode selectedNode = getSelectedRequestTreeNode();
        List<String> expandedTreePathKeys = collectExpandedTreePathKeys();
        Map<String, String> requestTreePaths = collectRequestTreePathsFromRequestModels();
        runWithWorkspaceChangeNotificationsSuppressed(() -> rebuildTree(requestTreePaths, expandedTreePathKeys));
        if (afterRefresh == null) {
            restoreTreeSelection(selectedNode);
        }
        if (afterRefresh != null) {
            afterRefresh.run();
        }
        refreshCollectionCombos();
        updateScopeControlState();
        refreshSessionActionControls();
        notifyWorkspaceChangedImmediately();
    }

    private void restoreTreeSelection(CollectionTreeNode selectedNode) {
        if (requestTree == null) {
            return;
        }
        if (selectedNode == null) {
            requestTree.clearSelection();
            return;
        }
        TreePath path = null;
        switch (selectedNode.getNodeType()) {
            case COLLECTION:
                path = findCollectionTreePath(selectedNode.collection);
                break;
            case FOLDER:
                ApiCollection folderCollection = findCollectionForNode(selectedNode);
                if (folderCollection != null) {
                    path = findFolderTreePath(folderCollection, selectedNode.folderPath);
                }
                break;
            case REQUEST:
                path = findRequestTreePathByRequest(selectedNode.request);
                break;
        }
        if (path != null) {
            requestTree.setSelectionPath(path);
        } else {
            requestTree.clearSelection();
        }
    }

    private TreePath findCollectionTreePath(ApiCollection collection) {
        if (treeModel == null || treeModel.getRoot() == null || collection == null) {
            return null;
        }
        return findCollectionTreePath((DefaultMutableTreeNode) treeModel.getRoot(), collection);
    }

    private TreePath findCollectionTreePath(DefaultMutableTreeNode node, ApiCollection collection) {
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION && ctn.collection == collection) {
                return new TreePath(ctn.getPath());
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TreePath path = findCollectionTreePath((DefaultMutableTreeNode) node.getChildAt(i), collection);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private TreePath findFolderTreePath(ApiCollection collection, String folderPath) {
        if (treeModel == null || treeModel.getRoot() == null || collection == null) {
            return null;
        }
        return findFolderTreePath((DefaultMutableTreeNode) treeModel.getRoot(), collection, RequestTreePathService.normalizeFolderPath(folderPath), null);
    }

    private TreePath findFolderTreePath(DefaultMutableTreeNode node,
                                       ApiCollection collection,
                                       String folderPath,
                                       String currentCollectionName) {
        String nextCollectionName = currentCollectionName;
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                nextCollectionName = ctn.collection != null ? ctn.collection.name : currentCollectionName;
            } else if (ctn.getNodeType() == CollectionTreeNode.Type.FOLDER
                    && Objects.equals(RequestTreePathService.normalizeFolderPath(ctn.folderPath), folderPath)) {
                if (collection == null || Objects.equals(nextCollectionName, collection.name)) {
                    return new TreePath(ctn.getPath());
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TreePath path = findFolderTreePath((DefaultMutableTreeNode) node.getChildAt(i), collection, folderPath, nextCollectionName);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private TreePath findRequestTreePathByRequest(ApiRequest request) {
        if (treeModel == null || treeModel.getRoot() == null || request == null) {
            return null;
        }
        ApiCollection collection = requestToCollectionMap.get(request);
        if (collection == null) {
            collection = findCollectionByRequest(request);
        }
        return findRequestTreePathByRequest((DefaultMutableTreeNode) treeModel.getRoot(), collection, request, null);
    }

    private TreePath findRequestTreePathByRequest(DefaultMutableTreeNode node,
                                                  ApiCollection collection,
                                                  ApiRequest request,
                                                  String currentCollectionName) {
        String nextCollectionName = currentCollectionName;
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            if (ctn.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                nextCollectionName = ctn.collection != null ? ctn.collection.name : currentCollectionName;
            } else if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST && ctn.request != null) {
                boolean sameRequest = ctn.request == request
                        || (request != null && request.id != null && Objects.equals(ctn.request.id, request.id));
                if (sameRequest && (collection == null || Objects.equals(nextCollectionName, collection.name))) {
                    return new TreePath(ctn.getPath());
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TreePath path = findRequestTreePathByRequest((DefaultMutableTreeNode) node.getChildAt(i), collection, request, nextCollectionName);
            if (path != null) {
                return path;
            }
        }
        return null;
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

    private ApiCollection findCollectionByRequest(ApiRequest request) {
        if (request == null) {
            return null;
        }
        ApiCollection mapped = requestToCollectionMap.get(request);
        if (mapped != null) {
            return mapped;
        }
        for (ApiCollection collection : loadedCollections) {
            if (collection != null && collection.requests != null && collection.requests.contains(request)) {
                return collection;
            }
        }
        return null;
    }

    private String normalizeTreeLabel(String label) {
        return RequestTreeNamingPolicy.normalizeTreeLabel(label);
    }

    private String uniqueCollectionName(String baseName) {
        return RequestTreeNamingPolicy.uniqueCollectionName(loadedCollections, baseName);
    }

    private boolean isLoadedCollectionNameInUse(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (ApiCollection collection : loadedCollections) {
            if (collection != null && collection.name != null && collection.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private String uniqueRequestName(String baseName) {
        return uniqueName(collectRequestNames(), baseName);
    }

    private String uniqueChildName(ApiCollection collection, String parentFolderPath, String baseName) {
        return RequestTreeNamingPolicy.uniqueChildName(collection, parentFolderPath, baseName);
    }

    private String uniqueName(Collection<String> existingNames, String baseName) {
        String normalizedBase = normalizeTreeLabel(baseName);
        if (normalizedBase.isEmpty()) {
            normalizedBase = "Untitled";
        }
        Set<String> existing = new LinkedHashSet<>();
        if (existingNames != null) {
            for (String name : existingNames) {
                String normalized = normalizeTreeLabel(name);
                if (!normalized.isEmpty()) {
                    existing.add(normalized);
                }
            }
        }
        if (!existing.contains(normalizedBase)) {
            return normalizedBase;
        }
        int suffix = 2;
        while (existing.contains(normalizedBase + " " + suffix)) {
            suffix++;
        }
        return normalizedBase + " " + suffix;
    }

    private Set<String> collectCollectionNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (ApiCollection collection : loadedCollections) {
            if (collection == null) {
                continue;
            }
            String name = normalizeTreeLabel(collection.name);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private Set<String> collectRequestNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (ApiCollection collection : loadedCollections) {
            if (collection == null || collection.requests == null) {
                continue;
            }
            for (ApiRequest request : collection.requests) {
                if (request == null) {
                    continue;
                }
                String name = normalizeTreeLabel(request.name);
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private Set<String> collectDirectChildNames(ApiCollection collection, String parentFolderPath) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (collection == null) {
            return names;
        }
        String normalizedParent = burp.utils.AuthInheritanceResolver.normalizeFolderPath(parentFolderPath);
        if (collection.folderPaths != null) {
            for (String folderPath : collection.folderPaths) {
                String normalized = burp.utils.AuthInheritanceResolver.normalizeFolderPath(folderPath);
                if (normalized.isEmpty()) {
                    continue;
                }
                if (Objects.equals(getParentFolderPath(normalized), normalizedParent)) {
                    String leaf = leafFolderName(normalized);
                    if (!leaf.isEmpty()) {
                        names.add(leaf);
                    }
                }
            }
        }
        if (collection.requests != null) {
            for (ApiRequest request : collection.requests) {
                if (request == null) {
                    continue;
                }
                String requestParent = RequestPathResolver.getRequestFolderPath(collection, request);
                if (!Objects.equals(requestParent, normalizedParent)) {
                    continue;
                }
                String name = normalizeTreeLabel(request.name);
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private String leafFolderName(String folderPath) {
        return RequestTreePathService.leafFolderName(folderPath);
    }

    private String getParentFolderPath(String folderPath) {
        return RequestTreePathService.getParentFolderPath(folderPath);
    }

    private String joinFolderPath(String parentFolderPath, String childName) {
        return RequestTreePathService.joinFolderPath(parentFolderPath, childName);
    }

    private int findCollectionIndexByReference(ApiCollection collection) {
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


    private void refreshRequestEditorForCollection(ApiCollection collection) {
        if (requestEditor == null || collection == null) {
            return;
        }
        ApiCollection currentCollection = requestEditor.getCurrentCollection();
        ApiRequest currentRequest = requestEditor.getCurrentRequest();
        if (currentCollection != collection || currentRequest == null) {
            return;
        }
        requestEditor.setCurrentCollection(collection);
        syncRequestEditorRuntimeContext(currentRequest, collection);
        requestEditor.loadRequest(currentRequest);
    }

    private void clearRequestEditorSafely() {
        if (requestEditor == null) {
            return;
        }
        requestEditor.clearRequest();
        syncRequestEditorRuntimeContext(null, null);
    }

    protected boolean confirmDelete(String message) {
        return JOptionPane.showConfirmDialog(mainPanel, message, "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private boolean isRequestInFolderSubtree(ApiCollection collection, ApiRequest request, String folderPrefix) {
        if (request == null) {
            return false;
        }
        String normalizedPrefix = RequestTreePathService.normalizeFolderPath(folderPrefix);
        if (normalizedPrefix.isEmpty()) {
            return true;
        }
        String requestFolderPath = RequestPathResolver.getRequestFolderPath(collection, request);
        return RequestTreePathService.isFolderPathInSubtree(requestFolderPath, normalizedPrefix);
    }

    private boolean isFolderPathInSubtree(String folderPath, String subtreePrefix) {
        return RequestTreePathService.isFolderPathInSubtree(folderPath, subtreePrefix);
    }

    private String rewriteFolderPathPrefix(String value, String sourcePrefix, String targetPrefix) {
        return RequestTreePathService.rewriteFolderPathPrefix(value, sourcePrefix, targetPrefix);
    }

    private void updateFolderTreeNodePaths(CollectionTreeNode node, String sourcePrefix, String targetPrefix) {
        if (node == null) {
            return;
        }
        if (node.getNodeType() == CollectionTreeNode.Type.FOLDER && node.folderPath != null) {
            node.folderPath = rewriteFolderPathPrefix(node.folderPath, sourcePrefix, targetPrefix);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            Object child = node.getChildAt(i);
            if (child instanceof CollectionTreeNode) {
                updateFolderTreeNodePaths((CollectionTreeNode) child, sourcePrefix, targetPrefix);
            }
        }
    }

    boolean moveCollection(ApiCollection collection, int targetIndex) {
        if (collection == null) {
            return false;
        }
        int sourceIndex = loadedCollections.indexOf(collection);
        if (sourceIndex < 0) {
            return false;
        }
        if (sourceIndex == targetIndex) {
            return true;
        }
        loadedCollections.remove(sourceIndex);
        if (targetIndex < 0 || targetIndex > loadedCollections.size()) {
            targetIndex = loadedCollections.size();
        } else if (sourceIndex < targetIndex) {
            targetIndex--;
        }
        loadedCollections.add(targetIndex, collection);
        refreshRequestTreeAfterMutation(null);
        appendImportLog("Reordered collection: " + normalizeTreeLabel(collection.name));
        return true;
    }

    boolean canAcceptRequestTreeDrop(TreeDropRequest dropRequest) {
        if (dropRequest == null || dropRequest.payload == null) {
            return false;
        }
        RequestTreeDragPayload payload = dropRequest.payload;
        if (payload.isCollection()) {
            return dropRequest.targetCollection == null
                    && (dropRequest.targetNode == null
                    || dropRequest.targetNode.getNodeType() != CollectionTreeNode.Type.COLLECTION
                    || dropRequest.targetNode.collection != payload.collection);
        }
        if (dropRequest.targetCollection == null) {
            return false;
        }
        if (payload.isFolder()) {
            if (payload.folderPath == null || payload.folderPath.isBlank()) {
                return false;
            }
            if (dropRequest.targetNode != null
                    && dropRequest.targetNode.getNodeType() == CollectionTreeNode.Type.FOLDER
                    && dropRequest.targetNode.collection == payload.collection
                    && RequestTreePathService.normalizeFolderPath(dropRequest.targetNode.folderPath).equals(payload.folderPath)) {
                return false;
            }
            if (RequestTreePathService.isFolderPathInSubtree(dropRequest.targetFolderPath, payload.folderPath)) {
                return false;
            }
            return true;
        }
        if (payload.isRequest()) {
            if (dropRequest.targetNode != null
                    && dropRequest.targetNode.getNodeType() == CollectionTreeNode.Type.REQUEST
                    && dropRequest.targetNode.request == payload.request) {
                return false;
            }
            return true;
        }
        return false;
    }

    boolean handleRequestTreeDrop(TreeDropRequest dropRequest) {
        if (!canAcceptRequestTreeDrop(dropRequest)) {
            return false;
        }
        persistCurrentRequestEditorState();

        RequestTreeDragPayload payload = dropRequest.payload;
        if (payload.isCollection()) {
            return moveCollection(payload.collection, dropRequest.targetIndex);
        }

        if (payload.isRequest()) {
            ApiRequest moved = requestTreeMutationService.moveRequest(
                    payload.collection,
                    payload.request,
                    dropRequest.targetCollection,
                    dropRequest.targetFolderPath,
                    dropRequest.targetIndex);
            if (moved == null) {
                return false;
            }
            refreshRequestTreeAfterMutation(null);
            appendImportLog("Moved request \"" + normalizeTreeLabel(moved.name) + "\" to \"" + dropRequest.targetFolderPath + "\"");
            return true;
        }

        CollectionTreeNode selectedNodeBeforeMove = getSelectedRequestTreeNode();
        boolean selectedFolderAffected = selectedNodeBeforeMove != null
                && selectedNodeBeforeMove.getNodeType() == CollectionTreeNode.Type.FOLDER
                && findCollectionForNode(selectedNodeBeforeMove) == payload.collection
                && isFolderPathInSubtree(selectedNodeBeforeMove.folderPath, payload.folderPath);

        List<ApiRequest> movedRequests = requestTreeMutationService.moveFolder(
                payload.collection,
                payload.folderPath,
                dropRequest.targetCollection,
                dropRequest.targetFolderPath,
                dropRequest.targetIndex);
        if (movedRequests == null) {
            return false;
        }
        String newFolderPath = RequestTreePathService.joinFolderPath(dropRequest.targetFolderPath, RequestTreePathService.leafFolderName(payload.folderPath));
        refreshRequestTreeAfterMutation(selectedFolderAffected ? () -> {
            String selectedFolderPath = rewriteFolderPathPrefix(selectedNodeBeforeMove.folderPath, payload.folderPath, newFolderPath);
            TreePath path = findFolderTreePath(dropRequest.targetCollection, selectedFolderPath);
            if (path != null) {
                selectTreePath(path);
            }
        } : null);
        appendImportLog("Moved folder \"" + normalizeTreeLabel(RequestTreePathService.leafFolderName(payload.folderPath))
                + "\" to \"" + newFolderPath + "\"");
        return true;
    }


    private void duplicateCollectionNode(CollectionTreeNode node) {
        if (node == null || node.collection == null) {
            return;
        }
        persistCurrentRequestEditorState();
        ApiCollection source = node.collection;
        ApiCollection copy = requestTreeMutationService.duplicateCollection(loadedCollections, source);
        registerCollectionRuntimeListener(copy);
        refreshRequestTreeAfterMutation(() -> {
            TreePath path = findCollectionTreePath(copy);
            if (path != null) {
                selectTreePath(path);
            }
        });
    }

    private void duplicateFolderNode(CollectionTreeNode node) {
        if (node == null || node.getNodeType() != CollectionTreeNode.Type.FOLDER) {
            return;
        }
        ApiCollection collection = findCollectionForNode(node);
        if (collection == null) {
            return;
        }
        persistCurrentRequestEditorState();
        String sourcePrefix = RequestTreePathService.normalizeFolderPath(node.folderPath);
        String targetPrefix = requestTreeMutationService.duplicateFolder(collection, sourcePrefix);
        refreshRequestTreeAfterMutation(() -> {
            TreePath path = findFolderTreePath(collection, targetPrefix);
            if (path != null) {
                selectTreePath(path);
            }
        });
    }

    private void duplicateRequestNode(CollectionTreeNode node) {
        if (node == null || node.getNodeType() != CollectionTreeNode.Type.REQUEST || node.request == null) {
            return;
        }
        ApiCollection collection = findCollectionForNode(node);
        if (collection == null) {
            return;
        }
        persistCurrentRequestEditorState();
        ApiRequest duplicate = requestTreeMutationService.duplicateRequest(collection, node.request);
        refreshRequestTreeAfterMutation(() -> {
            TreePath path = findRequestTreePathByRequest(duplicate);
            if (path != null) {
                selectTreePath(path);
                openRequestInEditor(duplicate, collection);
            }
        });
    }

    private void deleteCollectionNode(CollectionTreeNode node) {
        if (node == null || node.collection == null) {
            return;
        }
        persistCurrentRequestEditorState();
        if (runner != null && runner.isRunning()) {
            JOptionPane.showMessageDialog(mainPanel,
                    "Runner is running. Cancel it before deleting queued requests.",
                    "Runner Running",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        ApiCollection collection = node.collection;
        String name = normalizeTreeLabel(collection.name);
        if (!confirmDelete("Delete collection '" + name + "' and all contained requests?")) {
            return;
        }
        ApiRequest currentRequest = requestEditor != null ? requestEditor.getCurrentRequest() : null;
        boolean currentRequestRemoved = currentRequest != null && requestToCollectionMap.get(currentRequest) == collection;
        if (currentRequestRemoved) {
            clearRequestEditorSafely();
            if (requestEditor != null) {
                requestEditor.setSendControlsEnabled(false);
            }
            clearWorkbenchDetailPane();
        }
        if (collection.requests != null) {
            removeWorkbenchSendSnapshotsForRequests(collection.requests);
        }
        removeCollections(List.of(collection));
    }

    private void deleteFolderNode(CollectionTreeNode node) {
        if (node == null || node.getNodeType() != CollectionTreeNode.Type.FOLDER) {
            return;
        }
        ApiCollection collection = findCollectionForNode(node);
        if (collection == null) {
            return;
        }
        persistCurrentRequestEditorState();
        if (runner != null && runner.isRunning()) {
            JOptionPane.showMessageDialog(mainPanel,
                    "Runner is running. Cancel it before deleting queued requests.",
                    "Runner Running",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        String sourcePrefix = burp.utils.AuthInheritanceResolver.normalizeFolderPath(node.folderPath);
        String sourceLeaf = leafFolderName(sourcePrefix);
        if (!confirmDelete("Delete folder '" + sourceLeaf + "' and all contained requests?")) {
            return;
        }
        ApiRequest currentRequest = requestEditor != null ? requestEditor.getCurrentRequest() : null;
        boolean currentRequestRemoved = currentRequest != null && isRequestInFolderSubtree(findCollectionByRequest(currentRequest), currentRequest, sourcePrefix);
        if (currentRequestRemoved) {
            clearRequestEditorSafely();
            if (requestEditor != null) {
                requestEditor.setSendControlsEnabled(false);
            }
            clearWorkbenchDetailPane();
        }
        List<ApiRequest> removedRequests = requestTreeMutationService.removeFolderSubtree(collection, sourcePrefix);
        removeRequestsFromRunnerQueue(removedRequests);
        removeWorkbenchSendSnapshotsForRequests(removedRequests);
        refreshRequestTreeAfterMutation(null);
    }

    private void deleteRequestNode(CollectionTreeNode node) {
        if (node == null || node.getNodeType() != CollectionTreeNode.Type.REQUEST || node.request == null) {
            return;
        }
        ApiCollection collection = findCollectionForNode(node);
        if (collection == null) {
            return;
        }
        persistCurrentRequestEditorState();
        if (runner != null && runner.isRunning()) {
            JOptionPane.showMessageDialog(mainPanel,
                    "Runner is running. Cancel it before deleting queued requests.",
                    "Runner Running",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        String requestName = normalizeTreeLabel(node.request.name);
        if (!confirmDelete("Delete request '" + requestName + "'?")) {
            return;
        }
        ApiRequest currentRequest = requestEditor != null ? requestEditor.getCurrentRequest() : null;
        boolean currentRequestRemoved = currentRequest != null && currentRequest == node.request;
        if (currentRequestRemoved) {
            clearRequestEditorSafely();
            if (requestEditor != null) {
                requestEditor.setSendControlsEnabled(false);
            }
            clearWorkbenchDetailPane();
        }
        List<ApiRequest> removedRequests = requestTreeMutationService.removeRequest(collection, node.request);
        removeRequestsFromRunnerQueue(removedRequests);
        removeWorkbenchSendSnapshotsForRequests(removedRequests);
        refreshRequestTreeAfterMutation(null);
    }

    private void removeRequestsFromRunnerQueue(Collection<ApiRequest> removedRequests) {
        if (removedRequests == null || removedRequests.isEmpty()) {
            return;
        }
        runnerQueuedRequests.removeIf(removedRequests::contains);
        updateRunnerQueueUiState();
    }

    private void handleTreeNodeRenamed(CollectionTreeNode node, String oldName, String newName) {
        if (node == null || newName == null) {
            return;
        }

        String normalizedName = RequestTreeNamingPolicy.normalizeTreeLabel(newName);
        if (normalizedName.isBlank()) {
            rejectTreeRename(node, oldName, null);
            return;
        }

        ApiCollection collection = findCollectionForNode(node);
        switch (node.getNodeType()) {
            case COLLECTION: {
                RequestTreeNamingPolicy.RenameValidation validation = RequestTreeNamingPolicy.validateCollectionRename(loadedCollections, node.collection, normalizedName);
                if (!validation.valid) {
                    rejectTreeRename(node, oldName, validation.message);
                    return;
                }
                requestTreeMutationService.renameCollection(node.collection, validation.normalizedName);
                node.setUserObject(validation.normalizedName);
                refreshRequestEditorForCollection(node.collection);
                break;
            }
            case FOLDER: {
                if (collection == null || node.folderPath == null) {
                    return;
                }
                String oldFolderPath = RequestTreePathService.normalizeFolderPath(node.folderPath);
                RequestTreeNamingPolicy.RenameValidation validation = RequestTreeNamingPolicy.validateFolderRename(collection, oldFolderPath, normalizedName);
                if (!validation.valid) {
                    rejectTreeRename(node, oldName, validation.message);
                    return;
                }
                String newFolderPath = requestTreeMutationService.renameFolder(collection, oldFolderPath, validation.normalizedName);
                updateFolderTreeNodePaths(node, oldFolderPath, newFolderPath);
                node.folderPath = newFolderPath;
                node.setUserObject(leafFolderName(newFolderPath));
                refreshRequestEditorForCollection(collection);
                break;
            }
            case REQUEST: {
                if (collection == null || node.request == null) {
                    return;
                }
                RequestTreeNamingPolicy.RenameValidation validation = RequestTreeNamingPolicy.validateRequestRename(collection, node.request, normalizedName);
                if (!validation.valid) {
                    rejectTreeRename(node, oldName, validation.message);
                    return;
                }
                requestTreeMutationService.renameRequest(collection, node.request, validation.normalizedName);
                node.setUserObject(validation.normalizedName);
                refreshRequestEditorForCollection(collection);
                break;
            }
        }

        refreshCollectionCombos();
        updateScopeControlState();
        refreshSessionActionControls();
        if (treeModel != null) {
            treeModel.nodeChanged(node);
        }
        if (requestTree != null) {
            requestTree.repaint();
        }
        notifyWorkspaceChangedImmediately();
    }

    private void rejectTreeRename(CollectionTreeNode node, String oldName, String message) {
        if (node == null) {
            return;
        }
        node.setUserObject(oldName);
        if (treeModel != null) {
            treeModel.nodeChanged(node);
        }
        if (message != null && !message.isBlank()) {
            appendImportLog(message);
            LOGGER.warning(message);
        }
        if (requestTree != null) {
            requestTree.repaint();
        }
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
        int mask = environmentSaveShortcutMask();
        if (component instanceof JTextArea || component instanceof javax.swing.text.JTextComponent) {
            inputMap = component.getInputMap(JComponent.WHEN_FOCUSED);
        }
        KeyStroke saveKey = KeyStroke.getKeyStroke(KeyEvent.VK_S, mask);
        inputMap.put(saveKey, "environment-save");
        actionMap.put("environment-save", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                commitEnvironmentEditorToSelectedProfile();
            }
        });
    }

    private int environmentSaveShortcutMask() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("mac") ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
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
        commitOAuth2BindingUiToActiveEnvironment();
        environmentDirty = false;
        renderSelectedEnvironmentIntoEditor(true);
        updateEnvironmentUiState();
        syncActiveEnvironmentToEditors();
        notifyWorkspaceChangedImmediately();
        appendImportLog("Saved environment \"" + selected.displayName() + "\".");
    }

    private void renderSelectedEnvironmentIntoEditor() {
        renderSelectedEnvironmentIntoEditor(false);
    }

    private void renderSelectedEnvironmentIntoEditor(boolean force) {
        EnvironmentProfile selected = getSelectedEnvironmentProfile();
        String selectedId = selected != null ? selected.id : null;

        if (!force && environmentDirty && Objects.equals(renderedEnvironmentEditorProfileId, selectedId)) {
            updateEnvironmentUiState();
            return;
        }

        if (selected == null) {
            if (!force && !environmentProfiles.isEmpty() && activeEnvironmentId != null) {
                EnvironmentProfile active = getActiveEnvironment();
                if (active != null) {
                    suppressEnvironmentEditorEvents = true;
                    try {
                        selectEnvironmentById(active.id);
                    } finally {
                        suppressEnvironmentEditorEvents = false;
                    }
                    renderSelectedEnvironmentIntoEditor(false);
                    return;
                }
            }

            suppressEnvironmentEditorEvents = true;
            try {
                if (environmentRawArea != null) {
                    environmentRawArea.setText("");
                }
                if (environmentTableModel != null) {
                    environmentTableModel.setRowCount(0);
                }
                renderedEnvironmentEditorProfileId = null;
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
            renderedEnvironmentEditorProfileId = selected.id;
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
                renderSelectedEnvironmentIntoEditor(true);
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
        final boolean applyButtonEnabled;
        final String applyButtonText;
        final String hintText;

        UnresolvedDialogConfig(boolean canApply, boolean applyButtonEnabled, String applyButtonText, String hintText) {
            this.canApply = canApply;
            this.applyButtonEnabled = applyButtonEnabled;
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
                canApply,
                "Apply to Active Environment",
                hintText);
    }

    UnresolvedDialogConfig buildExportUnresolvedDialogConfig() {
        return new UnresolvedDialogConfig(
                false,
                true,
                "Use for Export",
                "Entered values apply only to this export. Continue without applying to export unresolved values as-is."
        );
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
                config.applyButtonEnabled,
                config.applyButtonText,
                config.hintText);
    }

    private void handleOAuth2TokenAcquired(TokenStore.TokenEntry entry,
                                           ApiCollection collection,
                                           Map<String, String> oauth2Vars,
                                           String environmentId,
                                           boolean autoBindRequested) {
        if (entry == null || entry.accessToken == null || entry.accessToken.isBlank()) {
            return;
        }

        EnvironmentProfile targetEnvironment = environmentId != null ? findEnvironmentById(environmentId) : getActiveEnvironment();
        if (targetEnvironment == null) {
            appendImportLog("OAuth2 token fetch requires an active environment.");
            setOAuth2AutosaveStatus("Create or select an Active Environment before fetching tokens.", Color.GRAY);
            recordDiagnostic(
                    DiagnosticOperation.OAUTH2_TOKEN_FETCH,
                    DiagnosticSeverity.WARNING,
                    "ImporterPanel",
                    "OAuth2 token fetch requires active environment",
                    "collection=" + (collection != null && collection.name != null ? collection.name : "none"));
            return;
        }

        applyOAuth2ConfigToEnvironment(targetEnvironment, filterOAuth2ConfigVars(oauth2Vars));
        oauth2Panel.setLastAcquiredToken(entry);

        boolean autoBind = autoBindRequested;
        Map<String, String> stored = Collections.emptyMap();
        if (autoBind) {
            stored = storeOAuth2TokenInEnvironment(collection, targetEnvironment, entry);
        }

        boolean targetStillActive = Objects.equals(activeEnvironmentId, targetEnvironment.id);
        if (targetStillActive) {
            syncActiveEnvironmentToEditors();
            updateEnvironmentUiState();
        }
        if (autoBind && stored != null && !stored.isEmpty()) {
            if (targetStillActive) {
                renderSelectedEnvironmentIntoEditor(true);
            }
            setOAuth2AutosaveStatus("Token values saved to " + targetEnvironment.displayName() + ".", new Color(0, 128, 0));
            appendImportLog("OAuth2 token auto-bound to active environment \"" + targetEnvironment.displayName() + "\".");
            recordDiagnostic(
                    DiagnosticOperation.OAUTH2_TOKEN_FETCH,
                    DiagnosticSeverity.INFO,
                    "ImporterPanel",
                    "OAuth2 token auto-bound to active environment",
                    "environment=" + targetEnvironment.displayName() +
                            "\ncollection=" + (collection != null && collection.name != null ? collection.name : "none") +
                            "\nstoredKeys=" + String.join(", ", stored.keySet()));
        } else {
            String statusMessage = targetStillActive
                    ? "Token acquired. Click Bind Token to choose target variables."
                    : "Token acquired for " + targetEnvironment.displayName() + ". Re-select that environment to bind token values.";
            setOAuth2AutosaveStatus(statusMessage, new Color(150, 90, 0));
            appendImportLog("OAuth2 token acquired for active environment \"" + targetEnvironment.displayName() + "\".");
            recordDiagnostic(
                    DiagnosticOperation.OAUTH2_TOKEN_FETCH,
                    DiagnosticSeverity.INFO,
                    "ImporterPanel",
                    "OAuth2 token acquired",
                    "environment=" + targetEnvironment.displayName() +
                            "\ncollection=" + (collection != null && collection.name != null ? collection.name : "none"));
        }
        notifyWorkspaceChangedImmediately();
    }

    private void showOAuth2BindTokenDialog() {
        EnvironmentProfile active = getActiveEnvironment();
        TokenStore.TokenEntry entry = oauth2Panel != null ? oauth2Panel.getLastAcquiredToken() : null;
        if (active == null || entry == null || entry.accessToken == null || entry.accessToken.isBlank()) {
            appendImportLog("Bind Token: acquire a token first and select an Active Environment.");
            recordDiagnostic(
                    DiagnosticOperation.OAUTH2_TOKEN_FETCH,
                    DiagnosticSeverity.WARNING,
                    "ImporterPanel",
                    "Bind token skipped",
                    "activeEnvironment=" + (active != null ? active.displayName() : "none"));
            return;
        }

        if (environmentDirty) {
            commitEnvironmentEditorToSelectedProfile();
        }

        active.ensureDefaults();
        List<String> variableNames = new ArrayList<>();
        if (active.variables != null) {
            variableNames.addAll(active.variables.keySet());
        }
        variableNames.addAll(List.of("oauth2_access_token", "oauth2_refresh_token", "oauth2_token_type", "oauth2_expires_in"));
        LinkedHashSet<String> uniqueNames = new LinkedHashSet<>();
        for (String value : variableNames) {
            if (value != null && !value.isBlank()) {
                uniqueNames.add(value);
            }
        }
        List<String> candidates = new ArrayList<>(uniqueNames);

        Window owner = SwingUtilities.getWindowAncestor(mainPanel);
        JDialog dialog = new JDialog(owner, "Bind OAuth2 Token", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JTextArea varsPreview = new JTextArea(renderEnvironmentVariablesAsText(active.variables));
        varsPreview.setEditable(false);
        varsPreview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        varsPreview.setRows(8);
        varsPreview.setLineWrap(false);
        root.add(new JScrollPane(varsPreview), BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 0;

        Map<String, JComboBox<String>> targetCombos = new LinkedHashMap<>();
        Map<String, JCheckBox> targetChecks = new LinkedHashMap<>();

        addOAuth2BindRow(form, gbc, "Access Token", "accessToken", candidates,
                active.oauth2.outputBindings.get("accessToken"), true, true, targetCombos, targetChecks);
        targetChecks.get("accessToken").setEnabled(false);
        addOAuth2BindRow(form, gbc, "Refresh Token", "refreshToken", candidates,
                active.oauth2.outputBindings.get("refreshToken"), entry.refreshToken != null && !entry.refreshToken.isBlank(),
                entry.refreshToken != null && !entry.refreshToken.isBlank(), targetCombos, targetChecks);
        addOAuth2BindRow(form, gbc, "Token Type", "tokenType", candidates,
                active.oauth2.outputBindings.get("tokenType"), entry.tokenType != null && !entry.tokenType.isBlank(),
                entry.tokenType != null && !entry.tokenType.isBlank(), targetCombos, targetChecks);
        addOAuth2BindRow(form, gbc, "Expires In", "expiresIn", candidates,
                active.oauth2.outputBindings.get("expiresIn"), entry.expiresAt > 0,
                entry.expiresAt > 0, targetCombos, targetChecks);

        root.add(form, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton bindButton = new JButton("Bind");
        bindButton.addActionListener(e -> {
            Map<String, String> selections = new LinkedHashMap<>();
            selections.put("accessToken", readOAuth2BindTarget(targetCombos.get("accessToken"), "oauth2_access_token"));
            if (targetChecks.get("refreshToken").isSelected()) {
                selections.put("refreshToken", readOAuth2BindTarget(targetCombos.get("refreshToken"), "oauth2_refresh_token"));
            }
            if (targetChecks.get("tokenType").isSelected()) {
                selections.put("tokenType", readOAuth2BindTarget(targetCombos.get("tokenType"), "oauth2_token_type"));
            }
            if (targetChecks.get("expiresIn").isSelected()) {
                selections.put("expiresIn", readOAuth2BindTarget(targetCombos.get("expiresIn"), "oauth2_expires_in"));
            }
            applyOAuth2TokenBindingSelection(entry, selections);
            dialog.dispose();
        });
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dialog.dispose());
        buttons.add(cancelButton);
        buttons.add(bindButton);

        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.setSize(860, 620);
        dialog.setLocationRelativeTo(mainPanel);
        dialog.setVisible(true);
    }

    private void addOAuth2BindRow(JPanel panel,
                                  GridBagConstraints gbc,
                                  String label,
                                  String key,
                                  List<String> candidates,
                                  String defaultValue,
                                  boolean available,
                                  boolean selected,
                                  Map<String, JComboBox<String>> targetCombos,
                                  Map<String, JCheckBox> targetChecks) {
        JCheckBox checkBox = new JCheckBox("Bind", selected);
        checkBox.setEnabled(available);
        targetChecks.put(key, checkBox);

        JComboBox<String> combo = new JComboBox<>();
        combo.setEditable(true);
        combo.setPrototypeDisplayValue(defaultValue != null && !defaultValue.isBlank() ? defaultValue : "oauth2_access_token");
        combo.addItem(defaultValue != null && !defaultValue.isBlank() ? defaultValue : "oauth2_access_token");
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (((DefaultComboBoxModel<String>) combo.getModel()).getIndexOf(candidate) < 0) {
                combo.addItem(candidate);
            }
        }
        combo.setSelectedItem(defaultValue != null && !defaultValue.isBlank() ? defaultValue : combo.getItemAt(0));
        combo.setEnabled(available);
        targetCombos.put(key, combo);

        gbc.gridx = 0;
        gbc.weightx = 0;
        panel.add(new JLabel(label + ":"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 0;
        panel.add(checkBox, gbc);

        gbc.gridx = 2;
        gbc.weightx = 1;
        panel.add(combo, gbc);
        gbc.gridy++;
    }

    private String readOAuth2BindTarget(JComboBox<String> combo, String fallback) {
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

    private void applyOAuth2TokenBindingSelection(TokenStore.TokenEntry entry, Map<String, String> selectedBindings) {
        EnvironmentProfile active = getActiveEnvironment();
        if (active == null || entry == null || entry.accessToken == null || entry.accessToken.isBlank()) {
            return;
        }

        if (environmentDirty) {
            commitEnvironmentEditorToSelectedProfile();
        }

        active.ensureDefaults();
        active.oauth2.ensureDefaults();

        String accessBinding = selectedBindings != null ? selectedBindings.get("accessToken") : null;
        if (accessBinding == null || accessBinding.isBlank()) {
            accessBinding = active.oauth2.outputBindings.get("accessToken");
        }
        if (accessBinding == null || accessBinding.isBlank()) {
            accessBinding = "oauth2_access_token";
        }
        active.oauth2.outputBindings.put("accessToken", accessBinding);
        active.variables.put(accessBinding, entry.accessToken);

        if (selectedBindings != null) {
            String refreshBinding = selectedBindings.get("refreshToken");
            if (refreshBinding != null && !refreshBinding.isBlank() && entry.refreshToken != null && !entry.refreshToken.isBlank()) {
                active.oauth2.outputBindings.put("refreshToken", refreshBinding);
                active.variables.put(refreshBinding, entry.refreshToken);
            }

            String tokenTypeBinding = selectedBindings.get("tokenType");
            if (tokenTypeBinding != null && !tokenTypeBinding.isBlank() && entry.tokenType != null && !entry.tokenType.isBlank()) {
                active.oauth2.outputBindings.put("tokenType", tokenTypeBinding);
                active.variables.put(tokenTypeBinding, entry.tokenType);
            }

            String expiresInBinding = selectedBindings.get("expiresIn");
            if (expiresInBinding != null && !expiresInBinding.isBlank() && entry.expiresAt > 0) {
                long expiresInSeconds = Math.max(0, (entry.expiresAt - System.currentTimeMillis()) / 1000);
                active.oauth2.outputBindings.put("expiresIn", expiresInBinding);
                active.variables.put(expiresInBinding, String.valueOf(expiresInSeconds));
            }
        }

        oauth2Panel.setLastAcquiredToken(entry);
        renderSelectedEnvironmentIntoEditor(true);
        syncActiveEnvironmentToEditors();
        updateEnvironmentUiState();
        notifyWorkspaceChangedImmediately();
        setOAuth2AutosaveStatus("OAuth2 token bound to active environment \"" + active.displayName() + "\".", new Color(0, 128, 0));
        appendImportLog("OAuth2 token bound to active environment \"" + active.displayName() + "\".");
        recordDiagnostic(
                DiagnosticOperation.OAUTH2_TOKEN_FETCH,
                DiagnosticSeverity.INFO,
                "ImporterPanel",
                "OAuth2 token bound to active environment",
                "environment=" + active.displayName() +
                        "\nbindings=" + String.join(", ", active.oauth2.outputBindings.values()));
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
            "API Collections (JSON, YAML, YML, HAR, BRU folder/ZIP)",
            "json", "yaml", "yml", "har", "zip"
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
                CollectionParser parser = detectCollectionParser(file);
                if (parser == null) {
                    throw new Exception("Unknown collection format. Supported: API Workbench, Postman, Bruno, OpenAPI, Insomnia, HAR");
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
                    ensureImportedCollectionId(collection, file.getName());
                    ensureRequestIds(collection);
                    registerCollectionRuntimeListener(collection);
                    loadedCollections.add(collection);
                    runWithWorkspaceChangeNotificationsSuppressed(ImporterPanel.this::rebuildTree);
                    refreshCollectionCombos();
                    boolean hasBrunoSummary = collection != null && "bruno".equalsIgnoreCase(collection.format);
                    if (hasBrunoSummary) {
                        appendImportLog("Loaded \"" + collection.name + "\" (" + collection.importedRequestCount + " imported, " + collection.skippedRequestCount + " skipped requests)");
                        if (collection.importWarnings != null) {
                            for (String warning : collection.importWarnings) {
                                if (warning != null && !warning.isBlank()) {
                                    appendImportLog("[WARN] " + warning);
                                }
                            }
                        }
                    } else {
                        appendImportLog("Loaded \"" + collection.name + "\" (" + collection.requests.size() + " requests)");
                    }
                    importBtn.setEnabled(true);
                    sendToRunnerBtn.setEnabled(true);
                    updateRunnerQueueUiState();
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

    private CollectionParser detectCollectionParser(File file) {
        ParserRegistry registry = new ParserRegistry();
        return registry.detectParser(file);
    }

    DropImportResult importCollectionFilesDroppedOnRequestTree(List<File> files) {
        DropImportResult result = parseCollectionFilesForDrop(files);
        applyCollectionDropImportResult(result);
        return result;
    }

    private void importCollectionFilesDroppedOnRequestTreeAsync(List<File> files) {
        recordDiagnostic(
                DiagnosticOperation.IMPORT,
                DiagnosticSeverity.INFO,
                "ImporterPanel",
                "Collection drop import started",
                "fileCount=" + (files != null ? files.size() : 0));
        SwingWorker<DropImportResult, String> worker = new SwingWorker<>() {
            @Override
            protected DropImportResult doInBackground() {
                return parseCollectionFilesForDrop(files);
            }

            @Override
            protected void done() {
                try {
                    applyCollectionDropImportResult(get());
                } catch (Exception e) {
                    appendImportLog("Drop import failed: " + e.getMessage());
                    recordDiagnostic(
                            DiagnosticOperation.IMPORT,
                            DiagnosticSeverity.ERROR,
                            "ImporterPanel",
                            "Collection drop import failed",
                            "error=" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                }
            }
        };
        worker.execute();
    }

    private DropImportResult parseCollectionFilesForDrop(List<File> files) {
        DropImportResult result = new DropImportResult();
        if (files == null || files.isEmpty()) {
            result.messages.add("No files were dropped.");
            recordDiagnostic(
                    DiagnosticOperation.IMPORT,
                    DiagnosticSeverity.WARNING,
                    "ImporterPanel",
                    "Collection drop import skipped",
                    "reason=empty file list");
            return result;
        }

        for (File file : files) {
            if (file == null) {
                result.failedCount++;
                result.messages.add("Skipped dropped file: null");
                continue;
            }
            try {
                CollectionParser parser = detectCollectionParser(file);
                if (parser == null) {
                    result.failedCount++;
                    result.messages.add("Skipped unsupported file: " + file.getName());
                    recordDiagnostic(
                            DiagnosticOperation.IMPORT,
                            DiagnosticSeverity.WARNING,
                            "ImporterPanel",
                            "Collection file skipped",
                            "file=" + file.getName() + "\nreason=unsupported");
                    continue;
                }
                ApiCollection collection = parser.parse(file);
                if (collection == null) {
                    result.failedCount++;
                    result.messages.add("Failed to import " + file.getName() + ": parser returned no collection.");
                    recordDiagnostic(
                            DiagnosticOperation.IMPORT,
                            DiagnosticSeverity.WARNING,
                            "ImporterPanel",
                            "Collection file produced no collection",
                            "file=" + file.getName());
                    continue;
                }
                result.importedCollections.add(collection);
                result.messages.add("Imported collection candidate from " + file.getName() + ": " + collection.name);
                recordDiagnostic(
                        DiagnosticOperation.IMPORT,
                        DiagnosticSeverity.INFO,
                        "ImporterPanel",
                        "Collection file parsed",
                        "file=" + file.getName() +
                                "\ncollection=" + (collection.name != null ? collection.name : ""));
            } catch (Exception e) {
                result.failedCount++;
                result.messages.add("Failed to import " + file.getName() + ": " + e.getMessage());
                recordDiagnostic(
                        DiagnosticOperation.IMPORT,
                        DiagnosticSeverity.ERROR,
                        "ImporterPanel",
                        "Collection file import failed",
                        "file=" + file.getName() + "\nerror=" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }
        return result;
    }

    private void applyCollectionDropImportResult(DropImportResult result) {
        if (result == null) {
            return;
        }

        for (String message : result.messages) {
            appendImportLog(message);
        }

        if (result.importedCollections.isEmpty()) {
            result.importedCount = 0;
            appendImportLog("Drop import complete: 0 imported, " + result.failedCount + " failed.");
            recordDiagnostic(
                    DiagnosticOperation.IMPORT,
                    result.failedCount > 0 ? DiagnosticSeverity.WARNING : DiagnosticSeverity.INFO,
                    "ImporterPanel",
                    "Collection drop import completed",
                    "imported=0 failed=" + result.failedCount);
            return;
        }

        List<ApiCollection> appended = new ArrayList<>();
        for (ApiCollection collection : result.importedCollections) {
            if (collection == null) {
                continue;
            }
            if (collection.name == null || collection.name.isBlank()) {
                collection.name = "Imported Collection";
            }
            if (isLoadedCollectionNameInUse(collection.name)) {
                collection.name = RequestTreeNamingPolicy.uniqueCollectionCopyName(loadedCollections, collection.name);
            }
            ensureImportedCollectionId(collection, "drop import");
            ensureRequestIds(collection);
            registerCollectionRuntimeListener(collection);
            loadedCollections.add(collection);
            appended.add(collection);
            appendImportLog("Imported collection: " + collection.name);
        }

        if (appended.isEmpty()) {
            result.importedCount = 0;
            appendImportLog("Drop import complete: 0 imported, " + result.failedCount + " failed.");
            recordDiagnostic(
                    DiagnosticOperation.IMPORT,
                    result.failedCount > 0 ? DiagnosticSeverity.WARNING : DiagnosticSeverity.INFO,
                    "ImporterPanel",
                    "Collection drop import completed",
                    "imported=0 failed=" + result.failedCount);
            return;
        }

        ApiCollection firstImported = appended.get(0);
        result.importedCount = appended.size();
        refreshRequestTreeAfterMutation(() -> {
            TreePath path = findCollectionTreePath(firstImported);
            if (path != null) {
                selectTreePath(path);
            }
        });
        appendImportLog("Drop import complete: " + appended.size() + " imported, " + result.failedCount + " failed.");
        recordDiagnostic(
                DiagnosticOperation.IMPORT,
                DiagnosticSeverity.INFO,
                "ImporterPanel",
                "Collection drop import completed",
                "imported=" + appended.size() + "\nfailed=" + result.failedCount);
    }

    private void ensureImportedCollectionId(ApiCollection collection, String importSource) {
        if (collection == null) {
            return;
        }
        String previousId = collection.id;
        collection.ensureId();
        if (collection.id != null && isLoadedCollectionIdInUse(collection.id, collection)) {
            collection.id = UUID.randomUUID().toString();
            String message = "Regenerated imported collection ID to avoid collision.";
            appendImportLog(message);
            recordDiagnostic(
                    DiagnosticOperation.IMPORT,
                    DiagnosticSeverity.WARNING,
                    "ImporterPanel",
                    "Imported collection ID collision resolved",
                    "source=" + importSource + "\ncollection=" + safeDiagnosticValue(collection.name) + "\npreviousId=" + safeDiagnosticValue(previousId));
        }
    }

    private boolean isLoadedCollectionIdInUse(String collectionId, ApiCollection excludedCollection) {
        if (collectionId == null || collectionId.isBlank()) {
            return false;
        }
        for (ApiCollection loaded : loadedCollections) {
            if (loaded == null || loaded == excludedCollection) {
                continue;
            }
            if (collectionId.equals(loaded.id)) {
                return true;
            }
        }
        return false;
    }

    private String safeDiagnosticValue(String value) {
        return value != null ? value : "";
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
        ApiRequest currentRequest = requestEditor != null ? requestEditor.getCurrentRequest() : null;
        ApiCollection currentCollection = requestEditor != null ? requestEditor.getCurrentCollection() : null;
        boolean currentRequestRemoved = currentRequest != null && currentCollection != null && targets.contains(currentCollection);
        if (currentRequestRemoved) {
            clearRequestEditorSafely();
            if (requestEditor != null) {
                requestEditor.setSendControlsEnabled(false);
            }
            clearWorkbenchDetailPane();
        }
        for (ApiCollection target : targets) {
            removeWorkbenchSendSnapshotsForRequests(target.requests);
            target.clearChangeListeners();
            requestToCollectionMap.entrySet().removeIf(entry -> entry.getValue() == target);
            loadedCollections.remove(target);
            appendImportLog("Removed collection: " + target.name);
        }
        refreshRequestTreeAfterMutation(null);
        if (loadedCollections.isEmpty()) {
            importBtn.setEnabled(false);
            sendToRunnerBtn.setEnabled(false);
            startRunnerBtn.setEnabled(false);
            removeCollectionBtn.setEnabled(false);
        }
        runnerQueuedRequests.removeIf(req -> requestToCollectionMap.get(req) == null);
        updateRunnerQueueUiState();
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
        runWithWorkspaceChangeNotificationsSuppressed(() -> {
            commitOAuth2ConfigUiToActiveEnvironment();
            persistCurrentRequestEditorState();
        });

        WorkspaceState state = WorkspaceState.fromCollections(loadedCollections);
        state.environments = getEnvironmentProfilesSnapshot();
        state.activeEnvironmentId = activeEnvironmentId;
        Map<String, String> uiTreePaths = collectRequestTreePaths();
        Map<String, String> modelTreePaths = collectRequestTreePathsFromRequestModels();
        state.requestTreePaths = mergeRequestTreePaths(uiTreePaths, modelTreePaths);
        state.expandedTreePathKeys = collectExpandedTreePathKeys();
        state.historyEntries = historyStore.snapshot();
        state.diagnosticsCaptureEnabled = DiagnosticStore.getInstance().isCaptureEnabled();
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
        captureRunnerQueueState(state);
        captureRunnerDetailState(state);
        return state;
    }

    public void restoreWorkspaceState(WorkspaceState state) {
        if (!hasRestorableWorkspaceState(state)) {
            return;
        }
        boolean hasCollections = state.collections != null && !state.collections.isEmpty();
        PendingMainRequestTreeRestore pendingRestore = hasCollections ? new PendingMainRequestTreeRestore(state) : null;
        runWithWorkspaceChangeNotificationsSuppressed(() -> {
            pendingWorkspaceRequestTreePaths = pendingRestore != null
                    ? pendingRestore.requestTreePaths
                    : Collections.emptyMap();
            try {
                if (pendingRestore != null) {
                    pendingRestore.repairedRequestPathCount = applyWorkspaceRequestTreePathsToRequests(state.collections, pendingRestore.requestTreePaths);
                }
                restoreWorkspaceCollections(state.collections != null ? state.collections : Collections.emptyList());
                replaceEnvironmentProfiles(state.environments);
                historyPersistenceService.restoreStore(historyStore, state);
                DiagnosticStore.getInstance().setCaptureEnabled(state.diagnosticsCaptureEnabled);
                if (historyPanel != null) {
                    historyPanel.refreshFromStore();
                }
                setActiveEnvironmentId(state.activeEnvironmentId);
                selectCollectionByName(varsCollectionCombo, state.selectedVariablesCollectionName);
                selectCollectionByName(oauth2CollectionCombo, state.selectedOAuth2CollectionName);
                restoreWorkbenchSettings(state);
                restoreRunnerSettings(state);
                restoreRunnerQueueState(state);
                restoreRunnerDetailState(state);
                syncOAuth2UiState();
                renderSelectedEnvironmentIntoEditor(true);
                updateEnvironmentUiState();
                syncDiagnosticsCaptureUi(DiagnosticStore.getInstance().isCaptureEnabled());
                syncWorkbenchEnvironmentControls();
                syncActiveEnvironmentToEditors();
                if (tabbedPane != null && tabbedPane.getTabCount() > 0) {
                    int index = Math.max(0, Math.min(state.selectedTabIndex, tabbedPane.getTabCount() - 1));
                    tabbedPane.setSelectedIndex(index);
                }
                if (pendingRestore != null) {
                    scheduleMainRequestTreeRestoreAfterWorkbenchVisible(() -> finalizeRestoredMainRequestTree(pendingRestore));
                }
            } finally {
                pendingWorkspaceRequestTreePaths = Collections.emptyMap();
            }
        });
    }

    private static boolean hasRestorableWorkspaceState(WorkspaceState state) {
        if (state == null) {
            return false;
        }
        boolean hasCollections = state.collections != null && !state.collections.isEmpty();
        boolean hasEnvironments = state.environments != null && !state.environments.isEmpty();
        boolean hasHistory = state.historyEntries != null && !state.historyEntries.isEmpty();
        return hasCollections || hasEnvironments || hasHistory;
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
            rebuildTree(pendingRestore.requestTreePaths,
                    pendingRestore.expandedTreePathKeys.isEmpty() ? null : pendingRestore.expandedTreePathKeys);
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
                boolean nameContainsPathSeparator = requestName.indexOf('/') >= 0 || requestName.indexOf('\\') >= 0;
                boolean manualTreeRequest = request.isManualPreserveMode();
                if (folderPath.isBlank()) {
                    if (manualTreeRequest || nameContainsPathSeparator) {
                        request.path = "";
                    } else {
                        if (isNestedRequestPath(request.path, requestName)) {
                            continue;
                        }
                        request.path = requestName;
                    }
                } else if (manualTreeRequest || requestName.isBlank() || nameContainsPathSeparator) {
                    request.path = folderPath;
                } else {
                    request.path = RequestTreePathService.joinFolderPath(folderPath, requestName);
                }
                if (!Objects.equals(previousPath, request.path)) {
                    repairedCount++;
                }
            }
        }
        return repairedCount;
    }

    static boolean isNestedRequestPath(String requestPath, String requestName) {
        return RequestTreePathService.isNestedRequestPath(requestPath, requestName);
    }

    public void restoreWorkspaceCollections(List<ApiCollection> collections) {
        for (ApiCollection existing : new ArrayList<>(loadedCollections)) {
            existing.clearChangeListeners();
        }
        loadedCollections.clear();
        runnerQueuedRequests.clear();
        requestToCollectionMap.clear();
        Set<String> seenCollectionIds = new LinkedHashSet<>();
        if (collections != null) {
            for (ApiCollection col : collections) {
                if (col == null) {
                    continue;
                }
                col.ensureId();
                while (col.id != null && seenCollectionIds.contains(col.id)) {
                    col.id = UUID.randomUUID().toString();
                }
                if (col.id != null) {
                    seenCollectionIds.add(col.id);
                }
                if (col.folderPaths != null) {
                    LinkedHashSet<String> normalizedFolderPaths = new LinkedHashSet<>();
                    for (String folderPath : col.folderPaths) {
                        String normalized = RequestTreePathService.normalizeFolderPath(folderPath);
                        if (!normalized.isEmpty()) {
                            normalizedFolderPaths.add(normalized);
                        }
                    }
                    col.folderPaths = new ArrayList<>(normalizedFolderPaths);
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

        rebuildTree(requestTreePaths, expandedTreePathKeys.isEmpty() ? null : expandedTreePathKeys);

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
            updateRunnerQueueUiState();
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
                String folderPath = RequestPathResolver.getRequestFolderPath(collection, request);
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
        return RequestPathResolver.getRequestFolderPath(requestPath, requestName, true);
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

    private void captureRunnerQueueState(WorkspaceState state) {
        if (state == null) {
            return;
        }
        state.runnerQueuedRequestIdentityKeys = new ArrayList<>();
        for (ApiRequest request : runnerQueuedRequests) {
            ApiCollection collection = requestToCollectionMap.get(request);
            String collectionName = collection != null ? collection.name : (request != null ? request.sourceCollection : null);
            int requestIndex = findRequestIndexInCollection(collection, request);
            state.runnerQueuedRequestIdentityKeys.add(workspaceRequestIdentityKey(collectionName, request, requestIndex));
        }
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

    private void restoreRunnerQueueState(WorkspaceState state) {
        if (state == null) {
            return;
        }
        List<ApiRequest> restoredQueue = restoreRunnerQueueFromIdentityKeys(state.runnerQueuedRequestIdentityKeys);
        runnerQueuedRequests.clear();
        runnerQueuedRequests.addAll(restoredQueue);
        runnerQueueFresh = !restoredQueue.isEmpty();
        refreshRunnerQueueList(restoredQueue.isEmpty() ? -1 : 0);
        updateRunnerQueueUiState();
    }

    private List<ApiRequest> restoreRunnerQueueFromIdentityKeys(List<String> identityKeys) {
        List<ApiRequest> restored = new ArrayList<>();
        if (identityKeys == null || identityKeys.isEmpty()) {
            return restored;
        }
        for (String identityKey : identityKeys) {
            ApiRequest request = findRequestByIdentityKey(identityKey);
            if (request != null && !restored.contains(request)) {
                restored.add(request);
            }
        }
        return restored;
    }

    private ApiRequest findRequestByIdentityKey(String identityKey) {
        if (identityKey == null || identityKey.isBlank()) {
            return null;
        }
        String[] parts = identityKey.split(String.valueOf(WORKSPACE_KEY_DELIMITER), -1);
        String collectionName = parts.length > 0 ? parts[0] : null;
        String requestToken = parts.length > 1 ? parts[1] : null;
        for (ApiCollection collection : loadedCollections) {
            if (collection == null || !Objects.equals(collectionName, collection.name) || collection.requests == null) {
                continue;
            }
            for (int i = 0; i < collection.requests.size(); i++) {
                ApiRequest request = collection.requests.get(i);
                if (request == null) {
                    continue;
                }
                String candidate = workspaceRequestIdentityKey(collection.name, request, i);
                if (Objects.equals(candidate, identityKey)) {
                    return request;
                }
                String fallback = workspaceRequestIdentityKey(collection.name, request);
                if (Objects.equals(fallback, identityKey)) {
                    return request;
                }
                if (requestToken != null && request.id != null && requestToken.startsWith("id=") && Objects.equals(requestToken, "id=" + request.id.trim())) {
                    return request;
                }
            }
        }
        return null;
    }

    private int findRequestIndexInCollection(ApiCollection collection, ApiRequest request) {
        if (collection == null || request == null || collection.requests == null) {
            return -1;
        }
        for (int i = 0; i < collection.requests.size(); i++) {
            if (collection.requests.get(i) == request) {
                return i;
            }
        }
        return request.sequenceOrder;
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
        syncOAuth2EnvironmentControls();
    }

    private void syncWorkbenchEnvironmentControls() {
        if (workbenchEnvironmentCombo == null) {
            return;
        }
        suppressWorkbenchEnvironmentEvents = true;
        try {
            EnvironmentProfile active = getActiveEnvironment();
            String selectedIdBefore = active != null ? active.id : null;
            Map<String, String> labelsById = buildEnvironmentDisplayLabelsById();
            workbenchEnvironmentCombo.removeAllItems();
            workbenchEnvironmentCombo.addItem(new EnvironmentRef(null, "No Environment"));
            for (EnvironmentProfile profile : environmentProfiles) {
                if (profile == null) {
                    continue;
                }
                profile.ensureDefaults();
                profile.ensureId();
                workbenchEnvironmentCombo.addItem(new EnvironmentRef(profile,
                        labelsById.getOrDefault(profile.id, profile.displayName())));
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

    private void syncOAuth2EnvironmentControls() {
        if (oauth2EnvironmentCombo == null) {
            return;
        }
        suppressOAuth2EnvironmentEvents = true;
        try {
            EnvironmentProfile active = getActiveEnvironment();
            String selectedIdBefore = active != null ? active.id : null;
            Map<String, String> labelsById = buildEnvironmentDisplayLabelsById();
            oauth2EnvironmentCombo.removeAllItems();
            oauth2EnvironmentCombo.addItem(new EnvironmentRef(null, "No Environment"));
            for (EnvironmentProfile profile : environmentProfiles) {
                if (profile == null) {
                    continue;
                }
                profile.ensureDefaults();
                profile.ensureId();
                oauth2EnvironmentCombo.addItem(new EnvironmentRef(profile,
                        labelsById.getOrDefault(profile.id, profile.displayName())));
            }
            if (selectedIdBefore != null && selectOAuth2EnvironmentById(selectedIdBefore)) {
                // selected active env
            } else {
                oauth2EnvironmentCombo.setSelectedIndex(0);
            }
        } finally {
            suppressOAuth2EnvironmentEvents = false;
        }
    }

    private boolean selectOAuth2EnvironmentById(String environmentId) {
        if (oauth2EnvironmentCombo == null) {
            return false;
        }
        for (int i = 0; i < oauth2EnvironmentCombo.getItemCount(); i++) {
            EnvironmentRef ref = oauth2EnvironmentCombo.getItemAt(i);
            if (ref != null && ref.environment != null && Objects.equals(ref.environment.id, environmentId)) {
                oauth2EnvironmentCombo.setSelectedIndex(i);
                return true;
            }
        }
        if (oauth2EnvironmentCombo.getItemCount() > 0) {
            oauth2EnvironmentCombo.setSelectedIndex(0);
        }
        return false;
    }

    private void handleOAuth2EnvironmentSelectionChanged() {
        if (suppressOAuth2EnvironmentEvents || oauth2EnvironmentCombo == null) {
            return;
        }
        EnvironmentRef ref = (EnvironmentRef) oauth2EnvironmentCombo.getSelectedItem();
        String nextId = ref != null && ref.environment != null ? ref.environment.id : null;
        if (Objects.equals(activeEnvironmentId, nextId)) {
            return;
        }
        setActiveEnvironmentId(nextId);
    }

    private void syncOAuth2UiState() {
        syncOAuth2UiState(false);
    }

    private void syncOAuth2UiState(boolean force) {
        EnvironmentProfile active = getActiveEnvironment();
        boolean hasActive = active != null;
        if (oauth2ActiveEnvironmentLabel != null) {
            oauth2ActiveEnvironmentLabel.setText("Active Environment: " + (hasActive ? active.displayName() : "No Environment"));
        }
        if (oauth2StatusLabel != null && !hasActive) {
            oauth2StatusLabel.setText("Create or select an Active Environment before acquiring tokens.");
            oauth2StatusLabel.setForeground(Color.GRAY);
        }
        if (oauth2Panel != null) {
            oauth2Panel.setEditable(hasActive);
            oauth2Panel.setBindTokenEnabled(hasActive && oauth2Panel.getLastAcquiredToken() != null);
        }
        syncOAuth2EnvironmentControls();
        syncOAuth2PanelFromActiveEnvironment(force);
    }

    private void syncOAuth2PanelFromActiveEnvironment() {
        syncOAuth2PanelFromActiveEnvironment(false);
    }

    private void syncOAuth2PanelFromActiveEnvironment(boolean force) {
        EnvironmentProfile active = getActiveEnvironment();
        if (oauth2Panel == null) {
            return;
        }
        String activeId = active != null ? active.id : null;
        if (!force && oauth2ConfigDirty && Objects.equals(renderedOAuth2ConfigEnvironmentId, activeId)) {
            return;
        }
        if (active == null) {
            if (!force && oauth2ConfigDirty) {
                return;
            }
            oauth2ConfigRefreshPending = true;
            oauth2Panel.populateFromOAuth2Map(Collections.emptyMap(), () -> {
                oauth2ConfigRefreshPending = false;
                renderedOAuth2ConfigEnvironmentId = null;
                oauth2ConfigDirty = false;
            });
            return;
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (active.oauth2 != null && active.oauth2.config != null) {
            values.putAll(active.oauth2.config);
        }
        oauth2ConfigRefreshPending = true;
        oauth2Panel.populateFromOAuth2Map(values, () -> {
            oauth2ConfigRefreshPending = false;
            renderedOAuth2ConfigEnvironmentId = active.id;
            oauth2ConfigDirty = false;
        });
    }

    private void handleEnvironmentSelectionChanged() {
        if (suppressEnvironmentEditorEvents) {
            return;
        }
        EnvironmentProfile selected = getSelectedEnvironmentProfile();
        if (selected == null && !environmentProfiles.isEmpty() && activeEnvironmentId != null) {
            EnvironmentProfile active = getActiveEnvironment();
            if (active != null) {
                suppressEnvironmentEditorEvents = true;
                try {
                    selectEnvironmentById(active.id);
                } finally {
                    suppressEnvironmentEditorEvents = false;
                }
                renderSelectedEnvironmentIntoEditor(false);
                updateEnvironmentUiState();
                syncOAuth2UiState();
                syncActiveEnvironmentToEditors();
                return;
            }
        }
        if (environmentDirty) {
            commitEnvironmentEditorToSelectedProfile();
        }
        renderSelectedEnvironmentIntoEditor(true);
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
            Map<String, String> labelsById = buildEnvironmentDisplayLabelsById();
            environmentCombo.removeAllItems();
            environmentCombo.addItem(new EnvironmentRef(null, "No Environment"));
            for (EnvironmentProfile profile : environmentProfiles) {
                if (profile != null) {
                    profile.ensureDefaults();
                    profile.ensureId();
                    environmentCombo.addItem(new EnvironmentRef(profile,
                            labelsById.getOrDefault(profile.id, profile.displayName())));
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

    private Map<String, String> buildEnvironmentDisplayLabelsById() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (EnvironmentProfile profile : environmentProfiles) {
            if (profile == null) {
                continue;
            }
            String base = profile.displayName();
            totals.put(base, totals.getOrDefault(base, 0) + 1);
        }

        Map<String, Integer> seen = new LinkedHashMap<>();
        Map<String, String> labelsById = new LinkedHashMap<>();
        for (EnvironmentProfile profile : environmentProfiles) {
            if (profile == null) {
                continue;
            }
            profile.ensureId();
            String base = profile.displayName();
            int ordinal = seen.getOrDefault(base, 0) + 1;
            seen.put(base, ordinal);

            String label = totals.getOrDefault(base, 0) > 1 && ordinal > 1
                    ? base + " (#" + ordinal + ")"
                    : base;
            labelsById.put(profile.id, label);
        }
        return labelsById;
    }

    private void installEnvironmentTransferSupport() {
        environmentFileDropHandler = new EnvironmentTransferHandler(this::importEnvironmentFilesDroppedAsync, this::appendImportLog);
        environmentProfileDragSourceHandler = new EnvironmentProfileDragSourceTransferHandler(this::getSelectedEnvironmentProfileForDrag, this::appendImportLog);
        activeEnvironmentDropHandler = new ActiveEnvironmentDropTransferHandler(this::activateEnvironmentFromDrop, this::appendImportLog);

        if (environmentEditorCardPanel != null) {
            environmentEditorCardPanel.setTransferHandler(environmentFileDropHandler);
        }
        if (environmentRawArea != null) {
            environmentRawArea.setTransferHandler(environmentFileDropHandler);
        }
        if (environmentTable != null) {
            environmentTable.setTransferHandler(environmentFileDropHandler);
        }
        if (environmentTopBarPanel != null) {
            environmentTopBarPanel.setTransferHandler(activeEnvironmentDropHandler);
        }
        if (environmentStatusLabel != null) {
            environmentStatusLabel.setTransferHandler(activeEnvironmentDropHandler);
        }
        if (environmentCombo != null) {
            environmentCombo.setTransferHandler(environmentProfileDragSourceHandler);
        }
        if (workbenchEnvironmentCombo != null) {
            workbenchEnvironmentCombo.setTransferHandler(environmentProfileDragSourceHandler);
        }
        if (oauth2EnvironmentCombo != null) {
            oauth2EnvironmentCombo.setTransferHandler(environmentProfileDragSourceHandler);
        }
        if (!GraphicsEnvironment.isHeadless()) {
            installComboDragGesture(environmentCombo);
            installComboDragGesture(workbenchEnvironmentCombo);
            installComboDragGesture(oauth2EnvironmentCombo);
        }
    }

    private void installComboDragGesture(JComboBox<EnvironmentRef> combo) {
        if (combo == null || combo.getClientProperty("environmentDragGestureInstalled") != null) {
            return;
        }
        combo.putClientProperty("environmentDragGestureInstalled", Boolean.TRUE);
        final Point[] pressPoint = new Point[1];
        combo.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                pressPoint[0] = e != null ? e.getPoint() : null;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                pressPoint[0] = null;
            }
        });
        combo.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressPoint[0] == null || combo.getTransferHandler() == null) {
                    return;
                }
                if (Math.abs(e.getX() - pressPoint[0].x) < 4 && Math.abs(e.getY() - pressPoint[0].y) < 4) {
                    return;
                }
                combo.getTransferHandler().exportAsDrag(combo, e, TransferHandler.COPY);
                pressPoint[0] = null;
            }
        });
    }

    private EnvironmentProfile getSelectedEnvironmentProfileForDrag() {
        EnvironmentProfile profile = getSelectedEnvironmentProfile();
        if (profile != null) {
            return profile;
        }
        if (environmentCombo != null) {
            Object selected = environmentCombo.getSelectedItem();
            if (selected instanceof EnvironmentRef ref) {
                return ref.environment;
            }
        }
        return null;
    }

    boolean activateEnvironmentFromDrop(EnvironmentDragPayload payload) {
        if (payload == null || payload.environmentId == null || payload.environmentId.isBlank()) {
            appendImportLog("Active environment drop rejected: missing environment id.");
            return false;
        }
        EnvironmentProfile profile = findEnvironmentProfileById(payload.environmentId);
        if (profile == null) {
            appendImportLog("Active environment drop rejected: environment not found.");
            return false;
        }
        setActiveEnvironmentId(profile.id);
        appendImportLog("Active environment set to: " + profile.displayName());
        return true;
    }

    private EnvironmentProfile findEnvironmentProfileById(String environmentId) {
        if (environmentId == null || environmentId.isBlank()) {
            return null;
        }
        for (EnvironmentProfile profile : environmentProfiles) {
            if (profile != null && Objects.equals(environmentId, profile.id)) {
                return profile;
            }
        }
        return null;
    }

    private void handleEnvironmentImport() {
        recordDiagnostic(
                DiagnosticOperation.IMPORT,
                DiagnosticSeverity.INFO,
                "ImporterPanel",
                "Environment import started",
                "activeEnvironment=" + (getActiveEnvironment() != null ? getActiveEnvironment().displayName() : "none"));
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
            List<EnvironmentProfile> imported = EnvironmentImportService.importEnvironment(file);
            if (imported == null || imported.isEmpty()) {
                appendImportLog("No environment profiles found in " + file.getName() + ".");
                recordDiagnostic(
                        DiagnosticOperation.IMPORT,
                        DiagnosticSeverity.WARNING,
                        "ImporterPanel",
                        "Environment import produced no profiles",
                        "file=" + file.getName());
                return;
            }
            addImportedEnvironmentProfiles(imported, file.getName());
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            appendImportLog("Environment import failed: " + message);
            recordDiagnostic(
                    DiagnosticOperation.IMPORT,
                    DiagnosticSeverity.ERROR,
                    "ImporterPanel",
                    "Environment import failed",
                    "file=" + file.getName() + "\nerror=" + message);
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(mainPanel,
                        "Environment import failed:\n" + message,
                        "Import Failed",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    void importEnvironmentFilesDropped(List<File> files) {
        EnvironmentDropImportResult result = parseEnvironmentFilesForDrop(files);
        applyEnvironmentDropImportResult(result);
    }

    private void importEnvironmentFilesDroppedAsync(List<File> files) {
        recordDiagnostic(
                DiagnosticOperation.IMPORT,
                DiagnosticSeverity.INFO,
                "ImporterPanel",
                "Environment drop import started",
                "fileCount=" + (files != null ? files.size() : 0));
        SwingWorker<EnvironmentDropImportResult, String> worker = new SwingWorker<>() {
            @Override
            protected EnvironmentDropImportResult doInBackground() {
                return parseEnvironmentFilesForDrop(files);
            }

            @Override
            protected void done() {
                try {
                    applyEnvironmentDropImportResult(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    appendImportLog("Environment drop import interrupted.");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                    appendImportLog("Environment drop import failed: " + message);
                }
            }
        };
        worker.execute();
    }

    private EnvironmentDropImportResult parseEnvironmentFilesForDrop(List<File> files) {
        EnvironmentDropImportResult result = new EnvironmentDropImportResult();
        if (files == null || files.isEmpty()) {
            result.failedCount++;
            result.messages.add("Skipped dropped file list: empty");
            return result;
        }
        for (File file : files) {
            if (file == null) {
                result.failedCount++;
                result.messages.add("Skipped dropped file: null");
                continue;
            }
            if (!file.exists() || !file.isFile() || !file.canRead()) {
                result.failedCount++;
                result.messages.add("Failed to import " + file.getName() + ": file not readable");
                continue;
            }
            try {
                List<EnvironmentProfile> imported = EnvironmentImportService.importEnvironment(file);
                if (imported == null || imported.isEmpty()) {
                    result.failedCount++;
                    result.messages.add("Skipped unsupported environment file: " + file.getName());
                    continue;
                }
                for (EnvironmentProfile profile : imported) {
                    if (profile == null) {
                        continue;
                    }
                    result.importedProfiles.add(profile);
                    result.importedCount++;
                    result.messages.add("Imported environment candidate from " + file.getName() + ": " + profile.displayName());
                }
            } catch (Exception e) {
                result.failedCount++;
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                String lowerMessage = message.toLowerCase(Locale.ROOT);
                if (lowerMessage.contains("unsupported")
                        || lowerMessage.contains("no environment variables found")
                        || lowerMessage.contains("no insomnia environment resources found")) {
                    result.messages.add("Skipped unsupported environment file: " + file.getName());
                } else {
                    result.messages.add("Failed to import " + file.getName() + ": " + message);
                }
            }
        }
        return result;
    }

    private void applyEnvironmentDropImportResult(EnvironmentDropImportResult result) {
        if (result == null) {
            appendImportLog("Environment drop import complete: 0 imported, 0 failed.");
            recordDiagnostic(
                    DiagnosticOperation.IMPORT,
                    DiagnosticSeverity.WARNING,
                    "ImporterPanel",
                    "Environment drop import completed without result",
                    "imported=0 failed=0");
            return;
        }
        for (String message : result.messages) {
            appendImportLog(message);
        }
        if (result.importedProfiles.isEmpty()) {
            appendImportLog("Environment drop import complete: 0 imported, " + result.failedCount + " failed.");
            recordDiagnostic(
                    DiagnosticOperation.IMPORT,
                    result.failedCount > 0 ? DiagnosticSeverity.WARNING : DiagnosticSeverity.INFO,
                    "ImporterPanel",
                    "Environment drop import finished",
                    "imported=0 failed=" + result.failedCount);
            return;
        }

        boolean hasActive = activeEnvironmentId != null && environmentProfiles.stream().anyMatch(profile -> profile != null && Objects.equals(profile.id, activeEnvironmentId));
        String firstImportedId = null;
        Set<String> usedNames = new LinkedHashSet<>();
        for (EnvironmentProfile existing : environmentProfiles) {
            if (existing != null) {
                usedNames.add(existing.displayName());
            }
        }
        for (EnvironmentProfile profile : result.importedProfiles) {
            if (profile == null) {
                continue;
            }
            profile.ensureDefaults();
            profile.ensureId();
            profile.name = uniqueEnvironmentNameForDrop(profile.displayName(), usedNames);
            usedNames.add(profile.displayName());
            if (firstImportedId == null) {
                firstImportedId = profile.id;
            }
            environmentProfiles.add(profile);
        }

        updateEnvironmentComboModel();
        if (!hasActive && firstImportedId != null) {
            suppressEnvironmentEditorEvents = true;
            try {
                selectEnvironmentById(firstImportedId);
            } finally {
                suppressEnvironmentEditorEvents = false;
            }
        }
        renderSelectedEnvironmentIntoEditor(true);
        updateEnvironmentUiState();
        syncWorkbenchEnvironmentControls();
        syncOAuth2UiState();
        syncActiveEnvironmentToEditors();
        notifyWorkspaceChangedImmediately();
        appendImportLog("Environment drop import complete: " + result.importedCount + " imported, " + result.failedCount + " failed.");
        recordDiagnostic(
                DiagnosticOperation.IMPORT,
                DiagnosticSeverity.INFO,
                "ImporterPanel",
                "Environment drop import completed",
                "imported=" + result.importedCount + "\nfailed=" + result.failedCount);
    }

    private String uniqueEnvironmentNameForDrop(String desiredName,
                                                Set<String> usedNames) {
        String baseName = desiredName != null && !desiredName.isBlank() ? desiredName.trim() : "Imported Environment";
        if (!environmentNameExists(baseName, usedNames)) {
            return baseName;
        }
        String root = stripEnvironmentCopySuffix(baseName);
        int copyIndex = 1;
        while (true) {
            String candidate = copyIndex == 1 ? root + " Copy" : root + " Copy " + copyIndex;
            if (!environmentNameExists(candidate, usedNames)) {
                return candidate;
            }
            copyIndex++;
        }
    }

    private static String stripEnvironmentCopySuffix(String name) {
        if (name == null || name.isBlank()) {
            return "Imported Environment";
        }
        String trimmed = name.trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(.*?)(?: Copy(?: \\d+)?)?$").matcher(trimmed);
        if (matcher.matches()) {
            String root = matcher.group(1);
            return root == null || root.isBlank() ? trimmed : root;
        }
        return trimmed;
    }

    private boolean environmentNameExists(String candidate, Set<String> usedNames) {
        return candidate != null && usedNames != null && usedNames.contains(candidate);
    }

    void addImportedEnvironmentProfiles(List<EnvironmentProfile> imported, String sourceName) {
        if (imported == null || imported.isEmpty()) {
            return;
        }
        EnvironmentProfile firstImported = imported.stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        for (EnvironmentProfile profile : imported) {
            if (profile == null) {
                continue;
            }
            profile.ensureDefaults();
            profile.ensureId();
            environmentProfiles.add(profile);
        }
        boolean activeChanged = firstImported != null;
        if (activeChanged) {
            commitOAuth2ConfigUiToActiveEnvironment();
            activeEnvironmentId = firstImported.id;
        }
        updateEnvironmentComboModel();
        if (firstImported != null) {
            selectEnvironmentById(firstImported.id);
        }
        renderSelectedEnvironmentIntoEditor(true);
        updateEnvironmentUiState();
        syncWorkbenchEnvironmentControls();
        syncOAuth2UiState(activeChanged);
        syncActiveEnvironmentToEditors();
        SwingUtilities.invokeLater(this::notifyWorkspaceChangedImmediately);
        appendImportLog("Imported " + imported.size() + " environment profile(s) from " + sourceName + ".");
        recordDiagnostic(
                DiagnosticOperation.IMPORT,
                DiagnosticSeverity.INFO,
                "ImporterPanel",
                "Environment profiles imported",
                "source=" + sourceName +
                        "\ncount=" + imported.size());
        if (firstImported != null) {
            appendImportLog("Active environment set to imported environment \"" + firstImported.displayName() + "\".");
        }
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
        boolean activeChanged = activeEnvironmentId == null;
        if (activeChanged) {
            activeEnvironmentId = profile.id;
        }
        updateEnvironmentComboModel();
        selectEnvironmentById(profile.id);
        renderSelectedEnvironmentIntoEditor(true);
        updateEnvironmentUiState();
        syncWorkbenchEnvironmentControls();
        syncOAuth2UiState(activeChanged);
        syncActiveEnvironmentToEditors();
        SwingUtilities.invokeLater(this::notifyWorkspaceChangedImmediately);
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
        renderSelectedEnvironmentIntoEditor(true);
        updateEnvironmentUiState();
        syncWorkbenchEnvironmentControls();
        syncOAuth2UiState();
        SwingUtilities.invokeLater(this::notifyWorkspaceChangedImmediately);
        appendImportLog("Duplicated environment \"" + selected.displayName() + "\".");
    }

    private void handleEnvironmentDelete() {
        EnvironmentProfile selected = getSelectedEnvironmentProfile();
        if (selected == null) {
            return;
        }
        boolean activeDeleted = Objects.equals(activeEnvironmentId, selected.id);
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
        renderSelectedEnvironmentIntoEditor(true);
        updateEnvironmentUiState();
        syncWorkbenchEnvironmentControls();
        syncOAuth2UiState(activeDeleted);
        syncActiveEnvironmentToEditors();
        SwingUtilities.invokeLater(this::notifyWorkspaceChangedImmediately);
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

    private void handleCollectionExport(CollectionTreeNode node) {
        ApiCollection collection = findCollectionForNode(node);
        if (collection == null) {
            return;
        }
        recordDiagnostic(
                DiagnosticOperation.EXPORT,
                DiagnosticSeverity.INFO,
                "ImporterPanel",
                "Collection export started",
                "collection=" + collectionDisplayName(collection));
        persistCurrentRequestEditorState();
        CollectionExportSelection selection = showCollectionExportDialog(collection);
        if (selection == null) {
            recordDiagnostic(
                    DiagnosticOperation.EXPORT,
                    DiagnosticSeverity.WARNING,
                    "ImporterPanel",
                    "Collection export cancelled",
                    "collection=" + collectionDisplayName(collection));
            return;
        }
        EnvironmentProfile activeEnvironment = getActiveEnvironment();
        Map<String, String> exportOnlyVariables = Collections.emptyMap();
        if (selection.resolveVariables && collection.requests != null && !collection.requests.isEmpty()) {
            List<UnresolvedVariableIssue> issues = ExportVariableResolutionService.collectUnresolvedIssues(collection, activeEnvironment);
            if (!issues.isEmpty()) {
                UnresolvedVariablesDialog dialog = createExportUnresolvedVariablesDialog(issues, List.of(collection));
                UnresolvedVariablesDialog.Action action = dialog.showDialog();
                if (action == UnresolvedVariablesDialog.Action.CANCEL) {
                    appendImportLog("Collection export cancelled due to unresolved variables.");
                    return;
                }
                if (action == UnresolvedVariablesDialog.Action.APPLY_AND_CONTINUE) {
                    exportOnlyVariables = dialog.getEnteredValues();
                }
            }
        }
        try {
            ExportResult result = performCollectionExport(
                    collection,
                    selection.format,
                    selection.outputPath,
                    selection.resolveVariables,
                    activeEnvironment,
                    exportOnlyVariables,
                    false
            );
            StringBuilder message = new StringBuilder();
            message.append("Exported collection \"").append(collectionDisplayName(collection)).append("\" to ").append(selection.outputPath.getFileName()).append(".");
            if (result != null && result.warnings != null && !result.warnings.isEmpty()) {
                message.append(" Warnings: ").append(String.join(" | ", result.warnings));
            }
            appendImportLog(message.toString());
            recordDiagnostic(
                    DiagnosticOperation.EXPORT,
                    DiagnosticSeverity.INFO,
                    "ImporterPanel",
                    "Collection export completed",
                    "collection=" + collectionDisplayName(collection) +
                            "\nformat=" + selection.format +
                            "\noutput=" + selection.outputPath);
        } catch (ExportException e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            appendImportLog("Collection export failed: " + reason);
            recordDiagnostic(
                    DiagnosticOperation.EXPORT,
                    DiagnosticSeverity.ERROR,
                    "ImporterPanel",
                    "Collection export failed",
                    "collection=" + collectionDisplayName(collection) +
                            "\nerror=" + reason);
            JOptionPane.showMessageDialog(mainPanel, "Collection export failed: " + reason, "Export Collection", JOptionPane.ERROR_MESSAGE);
        }
    }

    ExportResult performCollectionExport(ApiCollection collection,
                                         CollectionExportFormat format,
                                         Path outputPath,
                                         boolean resolveVariables,
                                         EnvironmentProfile activeEnvironment,
                                         Map<String, String> exportOnlyVariables,
                                         boolean cancelled) throws ExportException {
        if (cancelled) {
            return null;
        }
        return collectionExportService.exportCollection(
                collection,
                new CollectionExportOptions(
                        format,
                        outputPath,
                        resolveVariables,
                        activeEnvironment,
                        exportOnlyVariables
                )
        );
    }

    ExportResult performEnvironmentExport(EnvironmentProfile profile,
                                          EnvironmentExportFormat format,
                                          Path outputPath,
                                          boolean cancelled) throws ExportException {
        if (cancelled) {
            return null;
        }
        EnvironmentProfile copy = profile != null ? profile.copy() : null;
        if (copy == null) {
            throw new ExportException("Environment profile is required.");
        }
        copy.ensureDefaults();
        return environmentExportService.exportEnvironment(
                copy,
                new EnvironmentExportOptions(format, outputPath)
        );
    }

    private void handleEnvironmentExport() {
        EnvironmentProfile selected = getSelectedEnvironmentProfile();
        if (selected == null) {
            return;
        }
        recordDiagnostic(
                DiagnosticOperation.EXPORT,
                DiagnosticSeverity.INFO,
                "ImporterPanel",
                "Environment export started",
                "environment=" + selected.displayName());
        EnvironmentExportSelection selection = showEnvironmentExportDialog(selected);
        if (selection == null) {
            recordDiagnostic(
                    DiagnosticOperation.EXPORT,
                    DiagnosticSeverity.WARNING,
                    "ImporterPanel",
                    "Environment export cancelled",
                    "environment=" + selected.displayName());
            return;
        }
        try {
            ExportResult result = performEnvironmentExport(selected, selection.format, selection.outputPath, false);
            StringBuilder message = new StringBuilder();
            message.append("Exported environment \"").append(selected.displayName()).append("\" to ").append(selection.outputPath.getFileName()).append(".");
            if (result != null && result.warnings != null && !result.warnings.isEmpty()) {
                message.append(" Warnings: ").append(String.join(" | ", result.warnings));
            }
            appendImportLog(message.toString());
            recordDiagnostic(
                    DiagnosticOperation.EXPORT,
                    DiagnosticSeverity.INFO,
                    "ImporterPanel",
                    "Environment export completed",
                    "environment=" + selected.displayName() +
                            "\nformat=" + selection.format +
                            "\noutput=" + selection.outputPath);
        } catch (ExportException e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            appendImportLog("Environment export failed: " + reason);
            recordDiagnostic(
                    DiagnosticOperation.EXPORT,
                    DiagnosticSeverity.ERROR,
                    "ImporterPanel",
                    "Environment export failed",
                    "environment=" + selected.displayName() +
                            "\nerror=" + reason);
            JOptionPane.showMessageDialog(mainPanel, "Environment export failed: " + reason, "Export Environment", JOptionPane.ERROR_MESSAGE);
        }
    }

    private UnresolvedVariablesDialog createExportUnresolvedVariablesDialog(List<UnresolvedVariableIssue> issues,
                                                                            List<ApiCollection> targetCollections) {
        UnresolvedDialogConfig config = buildExportUnresolvedDialogConfig();
        Window owner = SwingUtilities.getWindowAncestor(mainPanel);
        return new UnresolvedVariablesDialog(
                owner,
                issues,
                targetCollections,
                config.canApply,
                config.applyButtonEnabled,
                config.applyButtonText,
                config.hintText
        );
    }

    private CollectionExportSelection showCollectionExportDialog(ApiCollection collection) {
        CollectionExportDialogConfig config = buildCollectionExportDialogConfig(collection);
        JDialog dialog = createExportDialog("Export Collection", config.panel);
        final Path[] output = new Path[1];
        config.saveAsButton.addActionListener(e -> {
            CollectionExportFormat selectedFormat = (CollectionExportFormat) config.formatCombo.getSelectedItem();
            Path chosen = chooseExportPath(
                    "Export Collection",
                    buildSuggestedCollectionExportFileName(collection, selectedFormat),
                    selectedFormat != null ? selectedFormat.defaultExtension() : null
            );
            if (chosen == null) {
                return;
            }
            if (!confirmOverwrite(chosen, "Export Collection")) {
                return;
            }
            output[0] = chosen;
            dialog.dispose();
        });
        config.cancelButton.addActionListener(e -> dialog.dispose());
        dialog.setVisible(true);
        if (output[0] == null) {
            return null;
        }
        return new CollectionExportSelection(
                (CollectionExportFormat) config.formatCombo.getSelectedItem(),
                output[0],
                config.resolveVariablesCheckbox.isSelected()
        );
    }

    private EnvironmentExportSelection showEnvironmentExportDialog(EnvironmentProfile selected) {
        EnvironmentExportDialogConfig config = buildEnvironmentExportDialogConfig(selected);
        JDialog dialog = createExportDialog("Export Environment", config.panel);
        final Path[] output = new Path[1];
        config.saveAsButton.addActionListener(e -> {
            EnvironmentExportFormat selectedFormat = (EnvironmentExportFormat) config.formatCombo.getSelectedItem();
            Path chosen = chooseExportPath(
                    "Export Environment",
                    buildSuggestedEnvironmentExportFileName(selected, selectedFormat),
                    selectedFormat != null ? selectedFormat.defaultExtension() : null
            );
            if (chosen == null) {
                return;
            }
            if (!confirmOverwrite(chosen, "Export Environment")) {
                return;
            }
            output[0] = chosen;
            dialog.dispose();
        });
        config.cancelButton.addActionListener(e -> dialog.dispose());
        dialog.setVisible(true);
        if (output[0] == null) {
            return null;
        }
        return new EnvironmentExportSelection((EnvironmentExportFormat) config.formatCombo.getSelectedItem(), output[0]);
    }

    CollectionExportDialogConfig buildCollectionExportDialogConfig(ApiCollection collection) {
        CollectionExportFormat[] formats = CollectionExportFormat.values();
        JComboBox<CollectionExportFormat> formatCombo = new JComboBox<>(formats);
        formatCombo.setSelectedItem(CollectionExportFormat.API_WORKBENCH_JSON);
        JCheckBox resolveVariablesCheckbox = new JCheckBox("Resolve variables using active environment");
        resolveVariablesCheckbox.setSelected(false);

        EnvironmentProfile activeEnvironment = getActiveEnvironment();
        JLabel activeEnvironmentValue = new JLabel(activeEnvironment != null ? activeEnvironment.displayName() : "No active environment selected");

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        panel.add(new JLabel("Collection:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(new JLabel(collectionDisplayName(collection)), gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        gbc.weightx = 0;
        panel.add(new JLabel("Active environment:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(activeEnvironmentValue, gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        gbc.weightx = 0;
        panel.add(new JLabel("Format:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(formatCombo, gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        panel.add(resolveVariablesCheckbox, gbc);
        gbc.gridwidth = 1;

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton saveAsButton = new JButton("Save As");
        JButton cancelButton = new JButton("Cancel");
        buttonRow.add(cancelButton);
        buttonRow.add(saveAsButton);

        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
        panel.add(buttonRow, gbc);

        return new CollectionExportDialogConfig(panel, formatCombo, resolveVariablesCheckbox, saveAsButton, cancelButton);
    }

    EnvironmentExportDialogConfig buildEnvironmentExportDialogConfig(EnvironmentProfile selected) {
        EnvironmentExportFormat[] formats = EnvironmentExportFormat.values();
        JComboBox<EnvironmentExportFormat> formatCombo = new JComboBox<>(formats);
        formatCombo.setSelectedItem(EnvironmentExportFormat.API_WORKBENCH_JSON);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        panel.add(new JLabel("Environment:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(new JLabel(selected != null && selected.displayName() != null ? selected.displayName() : "Untitled Environment"), gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        gbc.weightx = 0;
        panel.add(new JLabel("Format:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(formatCombo, gbc);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton saveAsButton = new JButton("Save As");
        JButton cancelButton = new JButton("Cancel");
        buttonRow.add(cancelButton);
        buttonRow.add(saveAsButton);

        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
        panel.add(buttonRow, gbc);

        return new EnvironmentExportDialogConfig(panel, formatCombo, saveAsButton, cancelButton);
    }

    private String buildSuggestedCollectionExportFileName(ApiCollection collection, CollectionExportFormat format) {
        String baseName = collectionDisplayName(collection);
        String extension = format != null ? format.defaultExtension() : ".json";
        return ExportFileNamePolicy.defaultFileName(baseName, extension);
    }

    private String collectionDisplayName(ApiCollection collection) {
        if (collection == null || collection.name == null || collection.name.isBlank()) {
            return "Untitled Collection";
        }
        return collection.name;
    }

    private String buildSuggestedEnvironmentExportFileName(EnvironmentProfile profile, EnvironmentExportFormat format) {
        String baseName = profile != null ? profile.displayName() : "Environment";
        String extension = format != null ? format.defaultExtension() : ".json";
        return ExportFileNamePolicy.defaultFileName(baseName, extension);
    }

    private Path chooseExportPath(String title, String suggestedFileName, String defaultExtension) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        if (suggestedFileName != null && !suggestedFileName.isBlank()) {
            chooser.setSelectedFile(new File(suggestedFileName));
        }
        if (chooser.showSaveDialog(mainPanel) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File file = chooser.getSelectedFile();
        if (file == null) {
            return null;
        }
        return resolveExportPath(file.getPath(), defaultExtension);
    }

    private Path resolveExportPath(String text, String defaultExtension) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Path path = Path.of(text.trim());
        path = ExportFileNamePolicy.ensureExtension(path, defaultExtension);
        return path.toAbsolutePath().normalize();
    }

    private boolean confirmOverwrite(Path output, String title) {
        if (output == null) {
            return false;
        }
        if (!Files.exists(output)) {
            return true;
        }
        int confirm = JOptionPane.showConfirmDialog(
                mainPanel,
                "Overwrite existing file?\n" + output,
                title,
                JOptionPane.YES_NO_OPTION
        );
        return confirm == JOptionPane.YES_OPTION;
    }

    private JDialog createExportDialog(String title, JComponent content) {
        Window owner = SwingUtilities.getWindowAncestor(mainPanel);
        JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(mainPanel);
        return dialog;
    }

    static final class CollectionExportDialogConfig {
        final JPanel panel;
        final JComboBox<CollectionExportFormat> formatCombo;
        final JCheckBox resolveVariablesCheckbox;
        final JButton saveAsButton;
        final JButton cancelButton;

        CollectionExportDialogConfig(JPanel panel,
                                     JComboBox<CollectionExportFormat> formatCombo,
                                     JCheckBox resolveVariablesCheckbox,
                                     JButton saveAsButton,
                                     JButton cancelButton) {
            this.panel = panel;
            this.formatCombo = formatCombo;
            this.resolveVariablesCheckbox = resolveVariablesCheckbox;
            this.saveAsButton = saveAsButton;
            this.cancelButton = cancelButton;
        }
    }

    static final class EnvironmentExportDialogConfig {
        final JPanel panel;
        final JComboBox<EnvironmentExportFormat> formatCombo;
        final JButton saveAsButton;
        final JButton cancelButton;

        EnvironmentExportDialogConfig(JPanel panel,
                                      JComboBox<EnvironmentExportFormat> formatCombo,
                                      JButton saveAsButton,
                                      JButton cancelButton) {
            this.panel = panel;
            this.formatCombo = formatCombo;
            this.saveAsButton = saveAsButton;
            this.cancelButton = cancelButton;
        }
    }

    private static final class CollectionExportSelection {
        final CollectionExportFormat format;
        final Path outputPath;
        final boolean resolveVariables;

        CollectionExportSelection(CollectionExportFormat format, Path outputPath, boolean resolveVariables) {
            this.format = format;
            this.outputPath = outputPath;
            this.resolveVariables = resolveVariables;
        }
    }

    private static final class EnvironmentExportSelection {
        final EnvironmentExportFormat format;
        final Path outputPath;

        EnvironmentExportSelection(EnvironmentExportFormat format, Path outputPath) {
            this.format = format;
            this.outputPath = outputPath;
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

    private JPanel createHistoryTab() {
        historyPanel = new HistoryPanel(historyStore, historyExportService, historyDiffService, historyLoadResultNotifier, importer != null ? importer.getApi() : null);
        historyPanel.setLoadInWorkbenchAction(this::loadHistoryEntryIntoWorkbench);
        historyPanel.setReplayFromHistoryAction(this::replayHistoryEntry);
        historyPanel.setSendToRepeaterAction(this::sendHistoryEntryToRepeater);
        historyPanel.setWorkspaceChangeListener(this::notifyWorkspaceChanged);
        return historyPanel;
    }

    private JPanel createDiagnosticsTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        diagnosticsEventListModel = new DefaultListModel<>();
        diagnosticsEventList = new JList<>(diagnosticsEventListModel);
        diagnosticsEventList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        diagnosticsEventList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = (JLabel) new DefaultListCellRenderer()
                    .getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value != null) {
                label.setText(DiagnosticSanitizer.sanitizeText(value.summaryLine()));
            }
            return label;
        });
        diagnosticsEventList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelectedDiagnosticEvent();
            }
        });
        JScrollPane eventScrollPane = new JScrollPane(diagnosticsEventList);
        eventScrollPane.setBorder(BorderFactory.createTitledBorder("Recorded Events"));

        diagnosticsEventDetailArea = new JTextArea(10, 42);
        diagnosticsEventDetailArea.setEditable(false);
        diagnosticsEventDetailArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        SwingShortcutSupport.installTextComponentShortcuts(diagnosticsEventDetailArea);
        JScrollPane detailScrollPane = new JScrollPane(diagnosticsEventDetailArea);
        detailScrollPane.setBorder(BorderFactory.createTitledBorder("Selected Event"));

        JSplitPane eventSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, eventScrollPane, detailScrollPane);
        eventSplit.setResizeWeight(0.45);
        eventSplit.setOneTouchExpandable(true);

        diagnosticsArea = new JTextArea(24, 100);
        diagnosticsArea.setEditable(false);
        diagnosticsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        SwingShortcutSupport.installTextComponentShortcuts(diagnosticsArea);
        diagnosticsArea.setText(DIAGNOSTICS_PLACEHOLDER_TEXT);
        diagnosticsArea.setLineWrap(false);
        diagnosticsArea.setWrapStyleWord(false);

        JScrollPane scrollPane = new JScrollPane(diagnosticsArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Snapshot"));

        JSplitPane diagnosticsSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, eventSplit, scrollPane);
        diagnosticsSplit.setResizeWeight(0.42);
        diagnosticsSplit.setOneTouchExpandable(true);
        diagnosticsSplit.setContinuousLayout(true);
        panel.add(diagnosticsSplit, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        diagnosticsCaptureBox = new JCheckBox();
        syncDiagnosticsCaptureUi(DiagnosticStore.getInstance().isCaptureEnabled());
        diagnosticsCaptureBox.addActionListener(e -> {
            DiagnosticStore.getInstance().setCaptureEnabled(diagnosticsCaptureBox.isSelected());
            syncDiagnosticsCaptureUi(diagnosticsCaptureBox.isSelected());
            refreshDiagnosticsSnapshot();
            notifyWorkspaceChangedImmediately();
        });
        diagnosticsRefreshButton = new JButton("Refresh Snapshot");
        diagnosticsRefreshButton.addActionListener(e -> refreshDiagnosticsSnapshot());
        diagnosticsClearButton = new JButton("Clear Session Diagnostics");
        diagnosticsClearButton.addActionListener(e -> clearDiagnosticsSnapshot());
        diagnosticsCopyButton = new JButton("Copy Sanitized Report");
        diagnosticsCopyButton.addActionListener(e -> copyDiagnosticsSnapshot());
        JButton exportButton = new JButton("Export Sanitized Report");
        exportButton.addActionListener(e -> exportDiagnosticsSnapshot());
        diagnosticsIncludeDebugBox = new JCheckBox("Include Debug");
        diagnosticsIncludeDebugBox.setToolTipText("Include debug diagnostics in the snapshot and exported report.");
        diagnosticsIncludeDebugBox.addActionListener(e -> refreshDiagnosticsSnapshot());
        buttons.add(diagnosticsCaptureBox);
        buttons.add(diagnosticsRefreshButton);
        buttons.add(diagnosticsClearButton);
        buttons.add(diagnosticsCopyButton);
        buttons.add(exportButton);
        buttons.add(diagnosticsIncludeDebugBox);
        panel.add(buttons, BorderLayout.NORTH);

        refreshDiagnosticsSnapshot();
        return panel;
    }

    private void syncDiagnosticsCaptureUi(boolean enabled) {
        if (diagnosticsCaptureBox == null) {
            return;
        }
        diagnosticsCaptureBox.setText("Diagnostics Capture: " + (enabled ? "ON" : "OFF"));
        diagnosticsCaptureBox.setSelected(enabled);
        diagnosticsCaptureBox.setToolTipText(enabled
                ? "Detailed diagnostic events are being recorded for this workspace."
                : "Detailed diagnostic events are not being recorded. Turn this on to capture variable, script, and runner diagnostics.");
    }

    void refreshDiagnosticsSnapshot() {
        if (diagnosticsArea == null) {
            return;
        }
        diagnosticsArea.setText(buildDiagnosticsSnapshot());
        diagnosticsArea.setCaretPosition(0);
        refreshDiagnosticsEventViews();
    }

    void clearDiagnosticsSnapshot() {
        DiagnosticStore.getInstance().clear();
        refreshDiagnosticsSnapshot();
    }

    void copyDiagnosticsSnapshot() {
        StringSelection selection = new StringSelection(buildSanitizedDiagnosticsReport());
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
    }

    private void refreshDiagnosticsEventViews() {
        if (diagnosticsEventListModel == null) {
            return;
        }
        String selectedId = null;
        DiagnosticEvent selectedEvent = diagnosticsEventList != null ? diagnosticsEventList.getSelectedValue() : null;
        if (selectedEvent != null) {
            selectedId = selectedEvent.operationId;
        }
        diagnosticsEventListModel.clear();
        List<DiagnosticEvent> filtered = filteredDiagnosticEvents();
        int selectionIndex = -1;
        for (int i = 0; i < filtered.size(); i++) {
            DiagnosticEvent event = filtered.get(i);
            diagnosticsEventListModel.addElement(event);
            if (selectedId != null && event != null && Objects.equals(selectedId, event.operationId)) {
                selectionIndex = i;
            }
        }
        if (diagnosticsEventList != null) {
            if (selectionIndex >= 0) {
                diagnosticsEventList.setSelectedIndex(selectionIndex);
                diagnosticsEventList.ensureIndexIsVisible(selectionIndex);
            } else {
                diagnosticsEventList.clearSelection();
            }
        }
        showSelectedDiagnosticEvent();
    }

    private List<DiagnosticEvent> filteredDiagnosticEvents() {
        boolean includeDebug = diagnosticsIncludeDebugBox != null && diagnosticsIncludeDebugBox.isSelected();
        List<DiagnosticEvent> filtered = new ArrayList<>();
        for (DiagnosticEvent event : DiagnosticStore.getInstance().snapshot()) {
            if (event == null) {
                continue;
            }
            if (!includeDebug && event.severity == DiagnosticSeverity.DEBUG) {
                continue;
            }
            filtered.add(event);
        }
        return filtered;
    }

    private void showSelectedDiagnosticEvent() {
        if (diagnosticsEventDetailArea == null) {
            return;
        }
        DiagnosticEvent event = diagnosticsEventList != null ? diagnosticsEventList.getSelectedValue() : null;
        diagnosticsEventDetailArea.setText(event != null ? buildDiagnosticEventDetail(event) : "");
        diagnosticsEventDetailArea.setCaretPosition(0);
    }

    private String buildDiagnosticEventDetail(DiagnosticEvent event) {
        if (event == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendDiagnosticsLine(sb, "timestamp", event.timestamp);
        appendDiagnosticsLine(sb, "severity", event.severity);
        appendDiagnosticsLine(sb, "operation", event.operation);
        appendDiagnosticsLine(sb, "sourceArea", event.sourceArea);
        appendDiagnosticsLine(sb, "collection", event.collectionName);
        appendDiagnosticsLine(sb, "request", event.requestName);
        appendDiagnosticsLine(sb, "requestId", event.requestId);
        appendDiagnosticsLine(sb, "environment", event.environmentName);
        appendDiagnosticsLine(sb, "message", DiagnosticSanitizer.sanitizeText(event.message != null ? event.message : ""));
        if (event.details != null && !event.details.isBlank()) {
            sb.append('\n').append("details").append("=\n")
                    .append(DiagnosticSanitizer.sanitizeText(event.details))
                    .append('\n');
        }
        if (event.attributes != null && !event.attributes.isEmpty()) {
            sb.append('\n').append("attributes").append("=\n");
            event.attributes.forEach((key, value) -> sb.append(key)
                    .append('=')
                    .append(DiagnosticSanitizer.sanitizeText(value))
                    .append('\n'));
        }
        return sb.toString().trim();
    }

    private String buildSanitizedDiagnosticsReport() {
        return DiagnosticStore.getInstance().sanitizedReport(
                diagnosticsIncludeDebugBox != null && diagnosticsIncludeDebugBox.isSelected());
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
        sb.append('\n');
        appendDiagnosticsSectionHeader(sb, "Diagnostics Summary");
        appendDiagnosticsLine(sb, "diagnostics.summary", DiagnosticStore.getInstance().compactSummary());
        appendDiagnosticsLine(sb, "diagnostics.capture", DiagnosticStore.getInstance().isCaptureEnabled() ? "ON" : "OFF");
        if (!DiagnosticStore.getInstance().isCaptureEnabled()) {
            appendDiagnosticsLine(sb, "diagnostics.note", "Diagnostics Capture is OFF. Enable capture to record detailed variable/script/request/runner diagnostics.");
        }
        sb.append('\n');
        appendDiagnosticsSectionHeader(sb, "Diagnostics Events");
        sb.append(DiagnosticStore.getInstance().sanitizedReport(diagnosticsIncludeDebugBox != null && diagnosticsIncludeDebugBox.isSelected()));
        return sb.toString();
    }

    void writeDiagnosticsSnapshot(File file, String snapshotText) throws IOException {
        if (file == null) {
            throw new IOException("No export file selected.");
        }
        Files.writeString(file.toPath(), snapshotText != null ? snapshotText : "", StandardCharsets.UTF_8);
    }

    private String safeEnvironmentRefLabel(JComboBox<EnvironmentRef> combo) {
        if (combo == null) {
            return "absent";
        }
        Object selected = combo.getSelectedItem();
        if (selected == null) {
            return "none";
        }
        return selected.toString();
    }

    private void exportDiagnosticsSnapshot() {
        String snapshot = buildSanitizedDiagnosticsReport();
        if (diagnosticsArea != null) {
            diagnosticsArea.setText(snapshot);
            diagnosticsArea.setCaretPosition(0);
        }
        recordDiagnostic(
                DiagnosticOperation.EXPORT,
                DiagnosticSeverity.INFO,
                "ImporterPanel",
                "Diagnostics snapshot exported",
                "includeDebug=" + (diagnosticsIncludeDebugBox != null && diagnosticsIncludeDebugBox.isSelected()) +
                        "\nlength=" + snapshot.length());
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
        Map<String, String> runtimeOverlay = activeEnvironmentOverlayForRuntimeUse();
        EnvironmentProfile activeEnvironment = getActiveEnvironment();
        List<UnresolvedVariableIssue> issues = collectUnresolvedVariableIssues(loadedCollections, selected, runtimeOverlay);
        if (!issues.isEmpty()) {
            List<ApiCollection> targetCollections = collectCollectionsForRequests(loadedCollections, selected);
            UnresolvedVariablesDialog.Action action = showUnresolvedVariablesDialog(issues, targetCollections);
            if (action == UnresolvedVariablesDialog.Action.CANCEL) {
                appendImportLog("Import cancelled due to unresolved variables.");
                return;
            }
            runtimeOverlay = activeEnvironmentOverlayForRuntimeUse();
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
            this::applyRuntimeVariableDeltaToActiveEnvironment,
            activeEnvironment,
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
            runnerQueuedRequests.clear();
            runnerQueueFresh = false;
            runnerExecutingQueueIndex = -1;
            refreshRunnerQueueList(-1);
            updateRunnerQueueUiState();
            appendImportLog("No requests selected to run.");
            return;
        }
        runnerQueuedRequests.clear();
        runnerQueuedRequests.addAll(selected);
        runnerQueueFresh = true;
        runnerExecutingQueueIndex = -1;
        refreshRunnerQueueList(0);
        switchToTabByName("Collection Runner");
        appendRunnerLog(selected.size() + " requests queued in runner. Configure settings and press Start.");
        updateRunnerQueueUiState();
    }

    private void installRunnerQueueTransferSupport() {
        runnerQueueTransferHandler = new RunnerQueueTransferHandler(
                () -> runnerQueuedRequests,
                this::reorderRunnerQueue,
                this::appendRunnerLog);
        if (runnerQueueList != null) {
            runnerQueueList.setTransferHandler(runnerQueueTransferHandler);
            runnerQueueList.setDropMode(DropMode.INSERT);
            if (!GraphicsEnvironment.isHeadless()) {
                try {
                    runnerQueueList.setDragEnabled(true);
                } catch (HeadlessException ignored) {
                    // best effort only
                }
            }
        }
        if (runnerQueueScrollPane != null) {
            runnerQueueScrollPane.setTransferHandler(runnerQueueTransferHandler);
            JViewport viewport = runnerQueueScrollPane.getViewport();
            if (viewport != null) {
                viewport.setTransferHandler(runnerQueueTransferHandler);
            }
        }
    }

    private void refreshRunnerQueueList(int preferredSelectionIndex) {
        if (runnerQueueListModel == null) {
            return;
        }
        Runnable update = () -> {
            ApiRequest selected = runnerQueueList != null ? runnerQueueList.getSelectedValue() : null;
            if (runnerQueueList != null) {
                runnerQueueList.clearSelection();
            }
            runnerQueueListModel.clear();
            for (ApiRequest request : runnerQueuedRequests) {
                runnerQueueListModel.addElement(request);
            }
            int indexToSelect = -1;
            if (selected != null) {
                indexToSelect = indexOfRunnerQueueRequest(selected);
            }
            if (indexToSelect < 0 && preferredSelectionIndex >= 0 && !runnerQueuedRequests.isEmpty()) {
                indexToSelect = Math.min(preferredSelectionIndex, runnerQueuedRequests.size() - 1);
            }
            if (indexToSelect >= 0) {
                selectRunnerQueueIndex(indexToSelect);
            } else if (runnerQueueList != null) {
                runnerQueueList.repaint();
            }
        };
        runOnEdtSync(update);
    }

    boolean reorderRunnerQueue(int sourceIndex, int targetIndex) {
        if (runner == null) {
            // queue editing is still allowed without a runner instance
        } else if (runner.isRunning()) {
            appendRunnerLog("Runner queue cannot be reordered while running.");
            return false;
        }
        if (sourceIndex < 0 || sourceIndex >= runnerQueuedRequests.size()) {
            return false;
        }
        int clampedTarget = Math.max(0, Math.min(targetIndex, runnerQueuedRequests.size()));
        if (sourceIndex == clampedTarget || sourceIndex + 1 == clampedTarget) {
            refreshRunnerQueueList(sourceIndex);
            return true;
        }
        ApiRequest moved = runnerQueuedRequests.remove(sourceIndex);
        if (sourceIndex < clampedTarget) {
            clampedTarget--;
        }
        clampedTarget = Math.max(0, Math.min(clampedTarget, runnerQueuedRequests.size()));
        runnerQueuedRequests.add(clampedTarget, moved);
        refreshRunnerQueueList(clampedTarget);
        updateRunnerQueueUiState();
        final int selectedIndex = clampedTarget;
        runOnEdtSync(() -> selectRunnerQueueIndex(selectedIndex));
        notifyWorkspaceChangedImmediately();
        appendRunnerLog("Reordered runner queue: " + (moved != null && moved.name != null ? moved.name : "Request") + " -> position " + (clampedTarget + 1));
        return true;
    }

    private void selectRunnerQueueIndex(int index) {
        if (runnerQueueList == null) {
            return;
        }
        if (index < 0 || index >= runnerQueueListModel.getSize()) {
            runnerQueueList.clearSelection();
            runnerQueueList.repaint();
            return;
        }
        runnerQueueList.setSelectedIndex(index);
        runnerQueueList.ensureIndexIsVisible(index);
        runnerQueueList.repaint();
    }

    private void showRunnerQueueSelection(ApiRequest request) {
        if (runnerDetailPanel == null) {
            return;
        }
        ApiCollection collection = request != null ? requestToCollectionMap.get(request) : null;
        if (collection == null && request != null) {
            collection = findCollectionByRequest(request);
        }
        if (request == null) {
            runnerDetailPanel.clear();
            return;
        }
        HistoryEntry entry = buildRunnerQueuePreviewEntry(collection, request);
        runnerDetailPanel.showEntry(entry);
    }

    private String buildRunnerQueueTooltip(ApiRequest request) {
        if (request == null) {
            return null;
        }
        ApiCollection collection = requestToCollectionMap.get(request);
        if (collection == null) {
            collection = findCollectionByRequest(request);
        }
        StringBuilder sb = new StringBuilder("<html>");
        sb.append("Collection: ").append(htmlEscape(collection != null && collection.name != null ? collection.name : safeCollectionName(null))).append("<br/>");
        sb.append("Folder: ").append(htmlEscape(RequestPathResolver.getRequestFolderPath(collection, request))).append("<br/>");
        sb.append("Method: ").append(htmlEscape(request.method != null ? request.method : "GET")).append("<br/>");
        sb.append("URL Template: ").append(htmlEscape(request.url != null ? request.url : "")).append("<br/>");
        EnvironmentProfile activeEnvironment = getActiveEnvironment();
        sb.append("Active Environment: ").append(htmlEscape(activeEnvironment != null ? activeEnvironment.displayName() : "No Environment"));
        if (runnerExecutingQueueIndex >= 0 && runnerQueueList != null) {
            int index = indexOfRunnerQueueRequest(request);
            if (index == runnerExecutingQueueIndex) {
                sb.append("<br/>State: Running");
            }
        }
        sb.append("</html>");
        return sb.toString();
    }

    private void updateRunnerQueueTooltipForLocation(Point point) {
        if (runnerQueueList == null || point == null) {
            return;
        }
        int index = runnerQueueList.locationToIndex(point);
        if (index < 0 || index >= runnerQueueListModel.size()) {
            runnerQueueList.setToolTipText(null);
            return;
        }
        ApiRequest request = runnerQueueListModel.getElementAt(index);
        runnerQueueList.setToolTipText(buildRunnerQueueTooltip(request));
    }

    private void runOnEdtSync(Runnable action) {
        if (action == null) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause != null ? cause : e);
        }
    }

    private int indexOfRunnerQueueRequest(ApiRequest request) {
        if (request == null) {
            return -1;
        }
        for (int i = 0; i < runnerQueuedRequests.size(); i++) {
            if (runnerQueuedRequests.get(i) == request) {
                return i;
            }
        }
        return -1;
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
        startRunner(showPreviewDialog, false);
    }

    private void startRunner(boolean showPreviewDialog, boolean stepMode) {
        List<ApiRequest> selected = new ArrayList<>(runnerQueuedRequests);
        if (selected.isEmpty() || loadedCollections.isEmpty()) {
            appendRunnerLog("No requests queued. Use Workbench > Actions > Run Checked first.");
            updateRunnerQueueUiState();
            return;
        }

        Map<String, String> runtimeOverlay = activeEnvironmentOverlayForRuntimeUse();
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
            if (!showRunnerPreviewDialog(selected, previewRows, showPreviewDialog)) {
                appendRunnerLog("Runner preview cancelled.");
                updateRunnerQueueUiState();
                return;
            }
        }

        if (selected.isEmpty()) {
            appendRunnerLog("Runner queue is empty.");
            updateRunnerQueueUiState();
            return;
        }

        startRunnerExecution(selected, stepMode);
    }

    private void startRunnerExecution(List<ApiRequest> selected, boolean stepMode) {
        runner.setDelayMs((Integer) runnerDelaySpinner.getValue());
        runner.setMaxRetries((Integer) runnerRetriesSpinner.getValue());
        runner.setStopConditions(buildRunnerStopConditionsFromUi());
        runner.setFollowRedirects(followRedirectsBox.isSelected());
        runner.setDebugRawRequest(runnerDebugRawRequestBox.isSelected());

        resultModel.clear();
        timelineModel.clear();
        runnerLog.setText("");
        runnerProgress.setValue(0);
        runnerExecutingQueueIndex = -1;
        runnerExecutionSequence = 0;
        runnerResultById.clear();
        runnerResultByName.clear();

        if (activeRunnerListener != null) {
            runner.removeListener(activeRunnerListener);
        }
        activeRunnerListener = new CollectionRunner.RunnerListener() {
            @Override public void onStart(String name, int total) {
                SwingUtilities.invokeLater(() -> {
                    appendRunnerLog("Starting runner (" + total + " requests from " +
                        loadedCollections.size() + " collection(s))");
                    setRunnerControlsRunning(true);
                    cancelRunnerBtn.setEnabled(true);
                    runnerProgress.setMaximum(total);
                    runnerProgress.setValue(0);
                    runnerProgress.setString("0/" + total);
                    runnerQueueFresh = true;
                    runnerCompletedQueueCount = 0;
                    updateRunnerQueueUiState();
                });
            }
            @Override public void onRequestStart(RunnerResult result) {
                SwingUtilities.invokeLater(() -> {
                    runnerExecutingQueueIndex = resolveRunnerQueueIndex(result);
                    updateRunnerQueueUiState();
                    RunnerExecutionTableModel.Entry entry = buildExecutionRowFromRequestStart(result);
                    resultModel.addEntry(entry);
                    timelineModel.addRow(buildTimelineRowFromExecutionEntry(entry));
                    if (entry.detailEntry != null) {
                        runnerDetailPanel.showEntry(entry.detailEntry);
                    }
                });
            }
            @Override public void onSkip(String name, String reason) {
                SwingUtilities.invokeLater(() -> {
                    appendRunnerLog("Skipped: " + name + " (" + reason + ")");
                    RunnerExecutionTableModel.Entry entry = buildExecutionRowFromSkip(name, reason);
                    resultModel.addEntry(entry);
                    timelineModel.addRow(buildTimelineRowFromExecutionEntry(entry));
                    if (entry.detailEntry != null) {
                        runnerDetailPanel.showEntry(entry.detailEntry);
                    }
                });
            }
            @Override public void onRequestComplete(RunnerResult result) {
                SwingUtilities.invokeLater(() -> {
                    if (result != null) {
                        indexRunnerResult(result);
                    }
                    RunnerExecutionTableModel.Entry entry = buildExecutionRowFromRequestResult(result);
                    resultModel.addEntry(entry);
                    if (timelineModel != null) {
                        timelineModel.addRow(buildTimelineRow(result));
                    }
                    if (result != null && !result.dependentExecution && !result.adHocExecution) {
                        runnerCompletedQueueCount++;
                    }
                    runnerProgress.setValue(runnerCompletedQueueCount);
                    runnerProgress.setString(runnerCompletedQueueCount + "/" + runnerProgress.getMaximum());
                    String status = result != null ? result.displayLogStatusLabel() : "FAIL";
                    appendRunnerLog((resultModel.getRequestResultCount()) + ". " + (result != null && result.requestName != null ? result.requestName : "Request") + " -> " + status);
                    if (!result.extractedVariables.isEmpty()) {
                        appendRunnerLog("   Extracted: " + result.extractedVariables);
                    }
                    runnerExecutingQueueIndex = -1;
                    updateRunnerQueueUiState();
                    runnerDetailPanel.showEntry(entry.detailEntry);
                    setRunnerControlsRunning(runner.isRunning());
                });
            }
            @Override public void onAttemptComplete(RunnerResult result) {
                SwingUtilities.invokeLater(() -> {
                    if (result != null) {
                        indexRunnerResult(result);
                    }
                    recordRunnerHistoryAttempt(result);
                });
            }
            @Override public void onTimeline(RunnerTimelineRow row) {
                SwingUtilities.invokeLater(() -> {
                    timelineModel.addRow(row);
                    resultModel.addEntry(buildExecutionRowFromTimeline(row, findRunnerResultForTimeline(row)));
                });
            }
            @Override public void onComplete(List<RunnerResult> results) {
                // Terminal state now drives the runner summary.
            }
            @Override public void onTerminal(RunnerTerminationResult termination, List<RunnerResult> results) {
                runnerTerminalHandled = true;
                SwingUtilities.invokeLater(() -> {
                    RunnerTerminationResult terminal = termination != null ? termination : new RunnerTerminationResult(
                            RunnerTerminationType.INTERNAL_ERROR,
                            "Missing runner termination state.",
                            null,
                            null,
                            null,
                            runnerCompletedQueueCount,
                            runnerProgress != null ? runnerProgress.getMaximum() : runnerCompletedQueueCount,
                            0,
                            null,
                            "terminal callback missing",
                            null);
                    int total = Math.max(1, terminal.totalQueuedCount);
                    int completed = terminalCompletedCount(terminal, results);
                    int failure = terminalFailureCount(terminal, completed, results);
                    int success = Math.max(0, completed - failure);
                    appendRunnerLog("\n=== Runner " + terminal.displayLabel() + " ===");
                    appendRunnerLog("Reason: " + (terminal.reason != null && !terminal.reason.isBlank() ? terminal.reason : "none"));
                    appendRunnerLog("Completed: " + completed + "/" + total);
                    appendRunnerLog("Successful: " + success + "/" + completed);
                    appendRunnerLog("Failed: " + failure);
                    appendRunnerLog("Total extracted vars: " + runner.getExtractedVariables().size());
                    if (runnerProgress != null) {
                        runnerProgress.setMaximum(total);
                        runnerProgress.setValue(completed);
                        runnerProgress.setString(terminal.displayLabel() + " (" + completed + "/" + total + ")");
                    }
                    RunnerExecutionTableModel.Entry entry = buildExecutionRowFromRunnerTerminal(terminal, results);
                    resultModel.addEntry(entry);
                    if (timelineModel != null) {
                        timelineModel.addRow(buildTimelineRowFromExecutionEntry(entry));
                    }
                    setRunnerControlsRunning(false);
                    cancelRunnerBtn.setEnabled(false);
                    runnerExecutingQueueIndex = -1;
                    runnerQueueFresh = false;
                    updateRunnerQueueUiState();
                    runnerDetailPanel.showEntry(buildRunnerTerminalEntry(terminal, results));
                });
            }
            @Override public void onDebug(String message) {
                SwingUtilities.invokeLater(() -> {
                    appendRunnerLog(message);
                    resultModel.addEntry(buildExecutionRowFromDebug(message));
                });
            }
            @Override public void onError(String message) {
                if (runnerTerminalHandled) {
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    appendRunnerLog("ERROR: " + message);
                    resultModel.addEntry(buildExecutionRowFromError(message));
                    setRunnerControlsRunning(false);
                    cancelRunnerBtn.setEnabled(false);
                    runnerExecutingQueueIndex = -1;
                    updateRunnerQueueUiState();
                });
            }
        };
        runner.addListener(activeRunnerListener);

        if (stepMode) {
            runner.runCollections(loadedCollections, selected, true);
        } else {
            runner.runCollections(loadedCollections, selected);
        }
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
        boolean paused = running && runner != null && runner.isPaused();
        if (startRunnerBtn != null) {
            startRunnerBtn.setEnabled(!running && !runnerQueuedRequests.isEmpty());
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
            stepRunnerBtn.setEnabled((running && paused) || (!running && !runnerQueuedRequests.isEmpty() && runnerQueueFresh));
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
        if (runner != null && runner.isRunning()) {
            runner.runNextOnly();
        } else {
            startRunner(false, true);
        }
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

    private void clearRunnerFromUi() {
        if (runner != null && runner.isRunning()) {
            appendRunnerLog("Runner is running. Cancel it before clearing the queue.");
            return;
        }

        if (resultModel != null) {
            resultModel.clear();
        }
        if (timelineModel != null) {
            timelineModel.clear();
        }
        if (runnerLog != null) {
            runnerLog.setText("");
        }
        runnerQueuedRequests.clear();
        runnerQueueFresh = false;
        runnerExecutingQueueIndex = -1;
        runnerExecutionSequence = 0;
        runnerCompletedQueueCount = 0;
        runnerResultById.clear();
        runnerResultByName.clear();
        runnerTerminalHandled = false;
        refreshRunnerQueueList(-1);
        if (runnerProgress != null) {
            runnerProgress.setValue(0);
            runnerProgress.setString("0/0");
        }
        clearRunnerDetailPane();
        setRunnerControlsRunning(false);
    }

    private void updateRunnerQueueUiState() {
        boolean hasQueue = !runnerQueuedRequests.isEmpty();
        boolean running = runner != null && runner.isRunning();
        boolean paused = running && runner != null && runner.isPaused();
        if (startRunnerBtn != null) {
            startRunnerBtn.setEnabled(!running && hasQueue);
        }
        if (stepRunnerBtn != null) {
            stepRunnerBtn.setEnabled((running && paused) || (!running && hasQueue && runnerQueueFresh));
        }
        if (runnerQueueListModel != null) {
            refreshRunnerQueueList(runnerQueueList != null ? runnerQueueList.getSelectedIndex() : -1);
        }
        if (runnerQueueList != null) {
            runnerQueueList.repaint();
        }
    }

    static void clearRunnerPreviewQueue(List<ApiRequest> selectedRequests,
                                        List<RunnerPreviewRow> previewRows,
                                        List<ApiRequest> queuedRequests) {
        if (selectedRequests != null) {
            selectedRequests.clear();
        }
        if (previewRows != null) {
            previewRows.clear();
        }
        if (queuedRequests != null) {
            queuedRequests.clear();
        }
    }

    static void removeRunnerPreviewRows(List<ApiRequest> selectedRequests,
                                        List<RunnerPreviewRow> previewRows,
                                        List<ApiRequest> queuedRequests,
                                        int... modelRows) {
        if (selectedRequests == null || previewRows == null || modelRows == null || modelRows.length == 0) {
            if (queuedRequests != null) {
                queuedRequests.clear();
                if (selectedRequests != null) {
                    queuedRequests.addAll(selectedRequests);
                }
            }
            return;
        }

        java.util.Set<Integer> indexes = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
        for (int index : modelRows) {
            if (index >= 0 && index < selectedRequests.size() && index < previewRows.size()) {
                indexes.add(index);
            }
        }
        for (int index : indexes) {
            selectedRequests.remove(index);
            previewRows.remove(index);
        }
        if (queuedRequests != null) {
            queuedRequests.clear();
            queuedRequests.addAll(selectedRequests);
        }
    }

    private boolean showRunnerPreviewDialog(List<ApiRequest> selectedRequests,
                                            List<RunnerPreviewRow> previewRows,
                                            boolean previewRequested) {
        if (previewRows == null || previewRows.isEmpty()) {
            return true;
        }

        if (runnerPreviewModel == null) {
            runnerPreviewModel = new RunnerPreviewTableModel();
        }
        List<ApiRequest> workingSelectedRequests = selectedRequests != null ? selectedRequests : new ArrayList<>();
        List<RunnerPreviewRow> workingPreviewRows = new ArrayList<>(previewRows);
        runnerPreviewModel.setRows(workingPreviewRows);

        JTable previewTable = new JTable(runnerPreviewModel);
        previewTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        previewTable.setFillsViewportHeight(true);
        previewTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
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
        JButton clearBtn = new JButton("Clear");
        JButton removeBtn = new JButton("Remove Selected");
        JButton continueBtn = new JButton("Start Runner");
        JButton cancelBtn = new JButton("Cancel");
        final boolean[] accepted = {false};
        Runnable refreshButtons = () -> {
            boolean hasRows = !workingSelectedRequests.isEmpty();
            boolean hasSelection = previewTable.getSelectedRowCount() > 0;
            continueBtn.setEnabled(hasRows);
            removeBtn.setEnabled(hasSelection && hasRows);
            clearBtn.setEnabled(hasRows);
        };
        previewTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refreshButtons.run();
            }
        });
        clearBtn.addActionListener(e -> {
            clearRunnerPreviewQueue(workingSelectedRequests, workingPreviewRows, runnerQueuedRequests);
            runnerPreviewModel.setRows(Collections.emptyList());
            updateRunnerQueueUiState();
            appendRunnerLog("Runner queue cleared from preview.");
            dialog.dispose();
        });
        removeBtn.addActionListener(e -> {
            int[] viewRows = previewTable.getSelectedRows();
            if (viewRows == null || viewRows.length == 0) {
                return;
            }
            int[] modelRows = new int[viewRows.length];
            for (int i = 0; i < viewRows.length; i++) {
                modelRows[i] = previewTable.convertRowIndexToModel(viewRows[i]);
            }
            removeRunnerPreviewRows(workingSelectedRequests, workingPreviewRows, runnerQueuedRequests, modelRows);
            runnerPreviewModel.setRows(workingPreviewRows);
            previewTable.clearSelection();
            updateRunnerQueueUiState();
            refreshButtons.run();
        });
        continueBtn.addActionListener(e -> {
            if (workingSelectedRequests.isEmpty()) {
                appendRunnerLog("No requests queued.");
                refreshButtons.run();
                return;
            }
            accepted[0] = true;
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());
        buttonPanel.add(clearBtn);
        buttonPanel.add(removeBtn);
        buttonPanel.add(cancelBtn);
        buttonPanel.add(continueBtn);
        content.add(buttonPanel, BorderLayout.SOUTH);
        refreshButtons.run();

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
            recordDiagnostic(
                    DiagnosticOperation.OAUTH2_TOKEN_FETCH,
                    DiagnosticSeverity.WARNING,
                    "ImporterPanel",
                    "Populate OAuth2 skipped",
                    "reason=empty request");
            return;
        }
        EnvironmentProfile activeEnvironment = getActiveEnvironment();
        if (activeEnvironment == null) {
            String message = "Create or select an Active Environment before populating OAuth2 settings.";
            Window owner = SwingUtilities.getWindowAncestor(mainPanel);
            if (GraphicsEnvironment.isHeadless() || owner == null || !owner.isDisplayable()) {
                appendImportLog(message);
            } else {
                JOptionPane.showMessageDialog(owner,
                        message,
                        "Active Environment Required",
                        JOptionPane.WARNING_MESSAGE);
            }
            recordDiagnostic(
                    DiagnosticOperation.OAUTH2_TOKEN_FETCH,
                    DiagnosticSeverity.WARNING,
                    "ImporterPanel",
                    "Populate OAuth2 requires active environment",
                    "request=" + req.name);
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
            recordDiagnostic(
                    DiagnosticOperation.OAUTH2_TOKEN_FETCH,
                    DiagnosticSeverity.WARNING,
                    "ImporterPanel",
                    "Populate OAuth2 found no relevant fields",
                    "request=" + req.name);
            return;
        }

        Map<String, String> existing = buildOAuth2PopulateExistingVars(owningCollection, req, activeEnvironment);
        Map<String, String> merged = burp.utils.OAuth2PopulateHelper.mergeWithExisting(extracted, existing);
        Map<String, String> configVars = filterOAuth2ConfigVars(merged);
        applyOAuth2ConfigToActiveEnvironment(configVars);
        oauth2ConfigRefreshPending = true;
        oauth2Panel.populateFromOAuth2Map(configVars, () -> {
            oauth2ConfigRefreshPending = false;
            renderedOAuth2ConfigEnvironmentId = activeEnvironment.id;
            oauth2ConfigDirty = false;
            notifyWorkspaceChangedImmediately();
        });

        String collectionName = owningCollection != null && owningCollection.name != null ? owningCollection.name : "unknown collection";
        appendImportLog("Populate OAuth2: Filled " + extracted.size() + " field(s) from request \"" + req.name
                + "\" using collection \"" + collectionName + "\".");

        List<String> unresolved = collectUnresolvedOAuth2PopulateVariables(extracted);
        if (!unresolved.isEmpty()) {
            appendImportLog("Populate OAuth2: Unresolved variable(s) remain: " + String.join(", ", unresolved) + ".");
            recordDiagnostic(
                    DiagnosticOperation.OAUTH2_TOKEN_FETCH,
                    DiagnosticSeverity.WARNING,
                    "ImporterPanel",
                    "Populate OAuth2 unresolved variables",
                    "request=" + req.name +
                            "\nunresolved=" + String.join(", ", unresolved));
        } else {
            recordDiagnostic(
                    DiagnosticOperation.OAUTH2_TOKEN_FETCH,
                    DiagnosticSeverity.INFO,
                    "ImporterPanel",
                    "Populate OAuth2 completed",
                    "request=" + req.name +
                            "\ncollection=" + collectionName +
                            "\nfields=" + extracted.size());
        }
    }

    private List<ApiRequest> collectOAuth2PopulateRequests(DefaultMutableTreeNode root) {
        List<ApiRequest> selected = collectCheckedRequests(root);
        return selected.size() == 1 ? selected : Collections.emptyList();
    }

    static VariableResolver buildOAuth2PopulateResolver(ApiCollection collection, ApiRequest request, Map<String, String> activeOverlay) {
        return burp.utils.RuntimeResolverFactory.build(
                collection,
                request,
                burp.utils.RuntimeResolverFactory.Options.withRuntimeVariableOverlay(activeOverlay)
                        .withCollectionRuntimeLayers(false)
        );
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
        if (collection != null && collection.runtimeOAuth2 != null) {
            existing.putAll(collection.runtimeOAuth2);
        }
        if (collection != null && collection.runtimeVars != null) {
            existing.putAll(collection.runtimeVars);
        }
        if (collection != null && request != null) {
            String folderPath = RequestPathResolver.getRequestFolderPath(collection, request);
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
        List<ApiRequest.Header> hiddenTransportSnapshot = copyHeaders(liveRequest.headers);
        applyEditedRequestToLiveRequest(collection, liveRequest, edited);
        if (!edited.isExactHttpMode()) {
            liveRequest.headers = mergeHiddenTransportHeaders(hiddenTransportSnapshot, liveRequest.headers);
        }
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

    private void recordDiagnostic(DiagnosticOperation operation,
                                  DiagnosticSeverity severity,
                                  String sourceArea,
                                  String message,
                                  String details) {
        DiagnosticEvent event = DiagnosticEvent.of(operation, severity, sourceArea, message);
        event.collectionName = safeCollectionName(requestEditor != null ? requestEditor.getCurrentCollection() : null);
        event.requestName = safeRequestName(requestEditor != null ? requestEditor.getCurrentRequest() : null);
        event.requestId = requestEditor != null && requestEditor.getCurrentRequest() != null ? requestEditor.getCurrentRequest().id : null;
        event.folderPath = requestEditor != null && requestEditor.getCurrentRequest() != null ? requestEditor.getCurrentRequest().path : null;
        if (requestEditor != null && requestEditor.getCurrentRequest() != null) {
            event.withAttribute("buildMode", requestEditor.getCurrentRequest().resolveBuildMode() != null
                    ? requestEditor.getCurrentRequest().resolveBuildMode().name()
                    : null);
        }
        EnvironmentProfile active = getActiveEnvironment();
        event.environmentName = active != null ? active.displayName() : null;
        event.details = details;
        DiagnosticStore.getInstance().record(event);
    }

    private void recordReplayDiagnostic(DiagnosticSeverity severity,
                                        String message,
                                        HistoryEntry entry,
                                        ApiCollection collection,
                                        ApiRequest request,
                                        EnvironmentProfile environment,
                                        String reason) {
        DiagnosticEvent event = DiagnosticEvent.of(DiagnosticOperation.REPLAY, severity, "ImporterPanel", message);
        if (entry != null) {
            event.withAttribute("historyId", entry.id);
        }
        if (collection != null) {
            event.collectionName = collection.name;
            event.withAttribute("collectionId", collection.id);
        } else if (entry != null) {
            event.collectionName = resolveHistoryCollectionName(entry);
            event.withAttribute("collectionId", resolveHistoryCollectionIdentity(entry));
        }
        if (request != null) {
            event.requestName = request.name;
            event.requestId = request.id;
            event.withAttribute("buildMode", request.resolveBuildMode() != null ? request.resolveBuildMode().name() : null);
        } else if (entry != null) {
            event.requestName = resolveHistoryRequestName(entry);
            event.requestId = resolveHistoryRequestId(entry);
        }
        if (environment != null) {
            event.environmentName = environment.displayName();
        }
        event.executionSource = burp.scripts.ExecutionSource.HISTORY_REPLAY;
        event.withAttribute("reason", reason);
        event.withDetails(reason != null ? reason : "");
        DiagnosticStore.getInstance().record(event);
    }

    private String cleanReplayFailureReason(Exception e) {
        if (e == null) {
            return "Unknown error";
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String firstLine = message.split("\\R", 2)[0].trim();
        return firstLine.isEmpty() ? e.getClass().getSimpleName() : firstLine;
    }

    public void appendRunnerLog(String msg) {
        Runnable append = () -> {
            runnerLog.append(msg + "\n");
            runnerLog.setCaretPosition(runnerLog.getDocument().getLength());
        };
        if (SwingUtilities.isEventDispatchThread()) {
            append.run();
        } else {
            SwingUtilities.invokeLater(append);
        }
    }

    private void updateRunnerDetailPane(HistoryEntry entry) {
        if (runnerDetailPanel == null) {
            return;
        }
        if (entry == null) {
            clearRunnerDetailPane();
            return;
        }
        runnerDetailPanel.showEntry(entry);
    }

    private void clearRunnerDetailPane() {
        if (runnerDetailPanel != null) {
            runnerDetailPanel.clear();
        }
    }

    RequestEditorPanel getRequestEditorForTests() { return requestEditor; }
    JTree getRequestTreeForTests() { return requestTree; }
    HistoryPanel getHistoryPanelForTests() { return historyPanel; }
    HistoryDetailPanel getWorkbenchDetailPanelForTests() { return workbenchDetailPanel; }
    JTable getRunnerResultTableForTests() { return resultTable; }
    JList<ApiRequest> getRunnerQueueListForTests() { return runnerQueueList; }
    HistoryDetailPanel getRunnerDetailPanelForTests() { return runnerDetailPanel; }
    JButton getStartRunnerButtonForTests() { return startRunnerBtn; }
    JButton getPauseRunnerButtonForTests() { return pauseRunnerBtn; }
    JButton getResumeRunnerButtonForTests() { return resumeRunnerBtn; }
    JButton getStepRunnerButtonForTests() { return stepRunnerBtn; }
    JButton getCancelRunnerButtonForTests() { return cancelRunnerBtn; }
    JCheckBox getDiagnosticsCaptureBoxForTests() { return diagnosticsCaptureBox; }
    JCheckBox getDiagnosticsIncludeDebugBoxForTests() { return diagnosticsIncludeDebugBox; }
    JButton getDiagnosticsRefreshButtonForTests() { return diagnosticsRefreshButton; }
    JButton getDiagnosticsClearButtonForTests() { return diagnosticsClearButton; }
    JButton getDiagnosticsCopyButtonForTests() { return diagnosticsCopyButton; }
    JList<DiagnosticEvent> getDiagnosticsEventListForTests() { return diagnosticsEventList; }
    JTextArea getDiagnosticsEventDetailAreaForTests() { return diagnosticsEventDetailArea; }
    JTextArea getDiagnosticsSnapshotAreaForTests() { return diagnosticsArea; }
    JComboBox<EnvironmentRef> getWorkbenchEnvironmentComboForTests() { return workbenchEnvironmentCombo; }
    JComboBox<EnvironmentRef> getEnvironmentComboForTests() { return environmentCombo; }
    JButton getActionsButtonForTests() { return actionsBtn; }
    JTextArea getImportLogAreaForTests() { return importLog; }
    void queueRunnerRequestsForTests(List<ApiRequest> selected) { queueRunnerRequests(selected != null ? selected : Collections.emptyList()); }

    public JPanel getPanel() { return mainPanel; }
    public JTabbedPane getTabbedPane() { return tabbedPane; }
}




