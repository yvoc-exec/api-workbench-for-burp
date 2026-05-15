package burp.ui;

import burp.models.*;
import burp.parser.*;
import burp.runner.CollectionRunner;
import burp.auth.OAuth2Manager;
import burp.UniversalImporter;
import burp.ui.tree.CollectionTreeNode;
import burp.ui.tree.CheckBoxTreeCellRenderer;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.*;
import java.util.List;

public class ImporterPanel {
    private final UniversalImporter importer;
    private final CollectionRunner runner;
    private final JPanel mainPanel;
    private JTabbedPane tabbedPane;

    // Multi-collection support
    private final List<ApiCollection> loadedCollections = new ArrayList<>();
    private OAuth2Panel oauth2Panel;
    private File selectedEnv;

    // Workbench tab
    private JTree requestTree;
    private DefaultTreeModel treeModel;
    private JProgressBar importProgress;
    private JCheckBox repeaterBtn, sitemapBtn, intruderBtn;
    private JSpinner delaySpinner;
    private JButton importBtn, sendToRunnerBtn, sendWorkbenchBtn, addCollectionBtn, removeCollectionBtn;
    private JCheckBox debugRawRequestBox;
    private JTextField envField;
    private JButton envBrowseBtn, envApplySelectedBtn, envApplyAllBtn;
    private RequestEditorPanel requestEditor;
    private ResponsePane responsePane;
    private JTextArea importLog;

    // Runner tab
    private JTextArea runnerLog;
    private JProgressBar runnerProgress;
    private JTable resultTable;
    private RunnerResultTableModel resultModel;
    private JSpinner runnerDelaySpinner;
    private JCheckBox stopOnErrorBox;
    private JCheckBox followRedirectsBox;
    private JCheckBox runnerDebugRawRequestBox;
    private JButton startRunnerBtn, cancelRunnerBtn;

    // Runner detail pane
    private JTextArea detailRequestText;
    private JTextArea detailResponseText;
    private JTextArea detailVarsText;

    // Variables tab
    private JTextArea envVarsArea;
    private JComboBox<String> varsCollectionCombo;
    private JButton bindVarsBtn;
    private JLabel varsHintLabel;

    // OAuth2 tab
    private JComboBox<String> oauth2CollectionCombo;
    private JButton bindOAuth2Btn;
    private JLabel oauth2HintLabel;

    // Runner listener deduplication
    private CollectionRunner.RunnerListener activeRunnerListener;

    // Split Send button mode
    private enum SendMode { SEND_ONLY, SEND_AND_REPEATER }
    private SendMode currentSendMode = SendMode.SEND_ONLY;

    public ImporterPanel(UniversalImporter importer, CollectionRunner runner, OAuth2Manager oauth2Manager) {
        this.oauth2Panel = new OAuth2Panel(oauth2Manager);
        this.importer = importer;
        this.runner = runner;
        this.mainPanel = createUI();
        if (oauth2Panel.getPopulateButton() != null) {
            oauth2Panel.getPopulateButton().addActionListener(e -> populateOAuth2FromSelectedRequest());
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

        // Bottom full-width rows: env binding + destination/actions
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));
        bottomPanel.add(createEnvBindingRow());
        bottomPanel.add(createDestinationRow());
        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createCollectionControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createTitledBorder("Collections"));
        addCollectionBtn = new JButton("+ Add Collection");
        addCollectionBtn.addActionListener(e -> addCollection());
        removeCollectionBtn = new JButton("- Remove Selected");
        removeCollectionBtn.addActionListener(e -> removeSelectedCollections());
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
        requestTree = new JTree(treeModel);
        requestTree.setRootVisible(false);
        requestTree.setCellRenderer(new CheckBoxTreeCellRenderer());
        requestTree.setShowsRootHandles(true);
        requestTree.addMouseListener(new TreeMouseListener());
        requestTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        requestTree.addTreeSelectionListener(e -> {
            TreePath path = requestTree.getSelectionPath();
            if (path != null) {
                Object node = path.getLastPathComponent();
                if (node instanceof CollectionTreeNode) {
                    CollectionTreeNode ctn = (CollectionTreeNode) node;
                    if (ctn.getNodeType() == CollectionTreeNode.Type.REQUEST && ctn.request != null) {
                        requestEditor.loadRequest(ctn.request);
                    }
                }
            }
            updateScopeControlState();
        });
        JScrollPane treeScroll = new JScrollPane(requestTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Request Tree"));
        panel.add(treeScroll, BorderLayout.CENTER);

        // Select controls
        JPanel selectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton selectAllBtn = new JButton("Select All");
        selectAllBtn.addActionListener(e -> setTreeCheckState(true));
        JButton deselectAllBtn = new JButton("Deselect All");
        deselectAllBtn.addActionListener(e -> setTreeCheckState(false));
        selectPanel.add(selectAllBtn);
        selectPanel.add(deselectAllBtn);
        panel.add(selectPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JComponent createRightWorkbenchPanel() {
        requestEditor = new RequestEditorPanel();
        responsePane = new ResponsePane();

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        split.setTopComponent(requestEditor);
        split.setBottomComponent(responsePane);
        split.setResizeWeight(0.50);
        split.setOneTouchExpandable(true);
        split.setContinuousLayout(true);
        split.setDividerSize(8);
        return split;
    }

    private JPanel createEnvBindingRow() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        panel.setBorder(BorderFactory.createTitledBorder("Environment Binding"));
        envField = new JTextField(20);
        envField.setEditable(false);
        envBrowseBtn = new JButton("Browse...");
        envBrowseBtn.addActionListener(e -> selectEnvironment());
        envApplySelectedBtn = new JButton("Apply to Selected Collection");
        envApplySelectedBtn.setEnabled(false);
        envApplySelectedBtn.addActionListener(e -> applyEnvToSelectedCollection());
        envApplyAllBtn = new JButton("Apply to All Collections");
        envApplyAllBtn.setEnabled(false);
        envApplyAllBtn.addActionListener(e -> applyEnvToAllCollections());
        panel.add(new JLabel("Env:"));
        panel.add(envField);
        panel.add(envBrowseBtn);
        panel.add(envApplySelectedBtn);
        panel.add(envApplyAllBtn);
        return panel;
    }

    private JPanel createDestinationRow() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Actions"));

        // Destination + Delay + Debug
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        repeaterBtn = new JCheckBox("Repeater", true);
        sitemapBtn = new JCheckBox("Sitemap (Live)");
        intruderBtn = new JCheckBox("Intruder");
        actionPanel.add(repeaterBtn);
        actionPanel.add(sitemapBtn);
        actionPanel.add(intruderBtn);
        actionPanel.add(Box.createHorizontalStrut(10));
        actionPanel.add(new JLabel("Delay (ms):"));
        delaySpinner = new JSpinner(new SpinnerNumberModel(200, 0, 5000, 50));
        delaySpinner.setPreferredSize(new Dimension(70, 22));
        actionPanel.add(delaySpinner);
        debugRawRequestBox = new JCheckBox("Debug final raw request");
        actionPanel.add(debugRawRequestBox);
        panel.add(actionPanel);

        // Import / Send / Send to Runner buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 2));
        importBtn = new JButton("Import Selected");
        importBtn.setEnabled(false);
        importBtn.addActionListener(e -> startImport());
        sendToRunnerBtn = new JButton("Send to Runner");
        sendToRunnerBtn.setEnabled(false);
        sendToRunnerBtn.addActionListener(e -> sendToRunner());

        JPanel sendSplitPanel = createSplitSendButton();
        btnPanel.add(sendSplitPanel);
        btnPanel.add(importBtn);
        btnPanel.add(sendToRunnerBtn);
        panel.add(btnPanel);

        // Progress + Log
        JPanel logPanel = new JPanel(new BorderLayout(5, 5));
        importProgress = new JProgressBar(0, 100);
        importProgress.setStringPainted(true);
        importProgress.setPreferredSize(new Dimension(180, 20));
        logPanel.add(importProgress, BorderLayout.WEST);
        importLog = new JTextArea(3, 50);
        importLog.setEditable(false);
        importLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(importLog);
        logScroll.setBorder(BorderFactory.createTitledBorder("Workbench Log"));
        logScroll.setPreferredSize(new Dimension(400, 70));
        logPanel.add(logScroll, BorderLayout.CENTER);
        panel.add(logPanel);

        return panel;
    }

    private JPanel createSplitSendButton() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));

        sendWorkbenchBtn = new JButton("Send");
        sendWorkbenchBtn.setToolTipText("Send current edited request directly");
        sendWorkbenchBtn.addActionListener(e -> executeWorkbenchSend());

        JButton arrowBtn = new JButton("v");
        arrowBtn.setPreferredSize(new Dimension(22, sendWorkbenchBtn.getPreferredSize().height));
        arrowBtn.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem sendOnlyItem = new JMenuItem("Send");
            JMenuItem sendRepeaterItem = new JMenuItem("Send + Repeater");
            sendOnlyItem.addActionListener(ev -> setSendMode(SendMode.SEND_ONLY));
            sendRepeaterItem.addActionListener(ev -> setSendMode(SendMode.SEND_AND_REPEATER));
            menu.add(sendOnlyItem);
            menu.add(sendRepeaterItem);
            menu.show(arrowBtn, 0, arrowBtn.getHeight());
        });

        panel.add(sendWorkbenchBtn, BorderLayout.CENTER);
        panel.add(arrowBtn, BorderLayout.EAST);
        return panel;
    }

    private void setSendMode(SendMode mode) {
        currentSendMode = mode;
        if (mode == SendMode.SEND_ONLY) {
            sendWorkbenchBtn.setText("Send");
            sendWorkbenchBtn.setToolTipText("Send current edited request directly");
        } else {
            sendWorkbenchBtn.setText("Send + Repeater");
            sendWorkbenchBtn.setToolTipText("Send request and also create Repeater tab");
        }
    }

    private void executeWorkbenchSend() {
        ApiRequest edited = requestEditor.buildRequestFromUI();
        if (edited == null) {
            appendImportLog("No request loaded in editor.");
            return;
        }
        ApiCollection col = findCollectionByName(edited.sourceCollection);
        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    publish("Sending: " + edited.method + " " + edited.url);
                    boolean follow = followRedirectsBox != null && followRedirectsBox.isSelected();

                    var result = importer.sendSingleRequestWithBuiltRequest(edited, col, follow);
                    var rr = result.response;

                    if (rr != null && rr.response() != null) {
                        var resp = rr.response();
                        StringBuilder headers = new StringBuilder();
                        headers.append("HTTP/1.1 ").append(resp.statusCode()).append("\n");
                        for (var h : resp.headers()) {
                            headers.append(h.name()).append(": ").append(h.value()).append("\n");
                        }
                        byte[] bodyBytes = resp.body().getBytes();
                        SwingUtilities.invokeLater(() -> {
                            responsePane.displayResponse(resp.statusCode(), 0, bodyBytes.length,
                                headers.toString(), bodyBytes);
                        });
                        publish("Response: " + resp.statusCode() + " (" + bodyBytes.length + " bytes)");
                    } else {
                        publish("No response received.");
                    }

                    if (currentSendMode == SendMode.SEND_AND_REPEATER && result.builtRequest != null) {
                        String tabName = importer.generateRepeaterTabName(edited.name,
                            edited.sourceCollection != null ? edited.sourceCollection : "Unknown");
                        importer.sendToRepeater(result.builtRequest, tabName);
                        publish("Sent to Repeater: " + tabName);
                    }
                } catch (Exception e) {
                    publish("Send failed: " + e.getMessage());
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



    // ========================================================================
    // Variables Tab
    // ========================================================================
    private JPanel createVariablesTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createTitledBorder("Environment Variables (JSON or key=value per line)"));

        envVarsArea = new JTextArea(20, 60);
        envVarsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        envVarsArea.setText("# Example:\n# base_url=http://localhost:8080\n# api_key=your_key_here\n# token={{auth_token}}");
        JScrollPane scroll = new JScrollPane(envVarsArea);
        panel.add(scroll, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));

        // Hint label
        varsHintLabel = new JLabel("Select a collection to edit scoped variables.");
        varsHintLabel.setForeground(Color.GRAY);
        bottomPanel.add(varsHintLabel, BorderLayout.CENTER);

        JPanel bindPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        varsCollectionCombo = new JComboBox<>();
        varsCollectionCombo.setPrototypeDisplayValue("Select collection...");
        varsCollectionCombo.addActionListener(e -> {
            String selected = (String) varsCollectionCombo.getSelectedItem();
            if (selected != null) {
                ApiCollection col = findCollectionByName(selected);
                if (col != null && col.runtimeVars != null && !col.runtimeVars.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, String> entry : col.runtimeVars.entrySet()) {
                        sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
                    }
                    envVarsArea.setText(sb.toString());
                } else {
                    envVarsArea.setText("");
                }
            } else {
                envVarsArea.setText("");
            }
            updateScopeControlState();
        });
        bindVarsBtn = new JButton("Bind to Collection");
        bindVarsBtn.addActionListener(e -> bindVarsToSelectedCollection());
        JButton bindAllBtn = new JButton("Bind to All Collections");
        bindAllBtn.addActionListener(e -> bindVarsToAllCollections());
        JButton parseBtn = new JButton("Parse Variables");
        parseBtn.addActionListener(e -> parseEnvVars());
        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> envVarsArea.setText(""));
        bindPanel.add(new JLabel("Target:"));
        bindPanel.add(varsCollectionCombo);
        bindPanel.add(bindVarsBtn);
        bindPanel.add(bindAllBtn);
        bindPanel.add(parseBtn);
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
        oauth2CollectionCombo.setPrototypeDisplayValue("Select collection...");
        oauth2HintLabel = new JLabel("Select a collection to bind OAuth2 settings.");
        oauth2HintLabel.setForeground(Color.GRAY);
        bindPanel.add(new JLabel("Target:"));
        bindPanel.add(oauth2CollectionCombo);
        bindOAuth2Btn = new JButton("Bind OAuth2 to Collection");
        bindOAuth2Btn.addActionListener(e -> {
            String colName = (String) oauth2CollectionCombo.getSelectedItem();
            if (colName == null) {
                appendImportLog("OAuth2: No collection selected for binding.");
                return;
            }
            ApiCollection col = findCollectionByName(colName);
            if (col == null) return;
            Map<String, String> vars = oauth2Panel.getVariables();
            col.runtimeOAuth2.putAll(vars);
            appendImportLog("OAuth2 bound to \"" + colName + "\": " + vars.size() + " var(s).");
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
                col.runtimeOAuth2.putAll(vars);
            }
            appendImportLog("OAuth2 bound to all " + loadedCollections.size() + " collection(s).");
        });
        bindPanel.add(bindAllBtn);
        panel.add(bindPanel, BorderLayout.NORTH);
        panel.add(oauth2Panel, BorderLayout.CENTER);
        panel.add(oauth2HintLabel, BorderLayout.SOUTH);

        // Keep combo in sync with loaded collections
        tabbedPane.addChangeListener(e -> {
            if (tabbedPane.getSelectedIndex() == 2) { // OAuth2 tab
                String prev = oauth2CollectionCombo.getSelectedItem() != null ? (String) oauth2CollectionCombo.getSelectedItem() : null;
                oauth2CollectionCombo.removeAllItems();
                for (ApiCollection c : loadedCollections) {
                    oauth2CollectionCombo.addItem(c.name);
                }
                if (prev != null) {
                    for (int i = 0; i < oauth2CollectionCombo.getItemCount(); i++) {
                        if (prev.equals(oauth2CollectionCombo.getItemAt(i))) {
                            oauth2CollectionCombo.setSelectedIndex(i);
                            break;
                        }
                    }
                }
                updateScopeControlState();
            }
        });
        oauth2CollectionCombo.addActionListener(e -> {
            updateScopeControlState();
        });
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
        stopOnErrorBox = new JCheckBox("Stop on error");
        configPanel.add(stopOnErrorBox, gbc);

        gbc.gridx = 3;
        followRedirectsBox = new JCheckBox("Follow redirects", true);
        configPanel.add(followRedirectsBox, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 4;
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

        JTabbedPane detailTabs = new JTabbedPane();
        detailRequestText = new JTextArea();
        detailRequestText.setEditable(false);
        detailRequestText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detailTabs.addTab("Request", new JScrollPane(detailRequestText));

        detailResponseText = new JTextArea();
        detailResponseText.setEditable(false);
        detailResponseText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detailTabs.addTab("Response", new JScrollPane(detailResponseText));

        detailVarsText = new JTextArea();
        detailVarsText.setEditable(false);
        detailVarsText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detailTabs.addTab("Vars", new JScrollPane(detailVarsText));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, detailTabs);
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
        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> {
            resultModel.clear();
            runnerLog.setText("");
        });
        startRunnerBtn = new JButton("Start Collection Runner");
        startRunnerBtn.setEnabled(false);
        startRunnerBtn.addActionListener(e -> startRunner());
        cancelRunnerBtn = new JButton("Cancel");
        cancelRunnerBtn.setEnabled(false);
        cancelRunnerBtn.addActionListener(e -> runner.cancel());
        btnPanel.add(clearBtn);
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
            Rectangle bounds = requestTree.getPathBounds(path);
            if (bounds == null) return;
            // Check if click is in checkbox area (left side)
            if (e.getX() < bounds.x + 20) {
                Object node = path.getLastPathComponent();
                if (node instanceof CollectionTreeNode) {
                    CollectionTreeNode ctn = (CollectionTreeNode) node;
                    ctn.propagateCheck(!ctn.isChecked());
                    javax.swing.tree.TreeNode parent = ctn.getParent();
                    if (parent instanceof CollectionTreeNode) {
                        ((CollectionTreeNode) parent).updateParentCheckState();
                    }
                    requestTree.repaint();
                }
            }
        }
    }

    private void rebuildTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Collections");
        for (ApiCollection col : loadedCollections) {
            CollectionTreeNode colNode = new CollectionTreeNode(col);
            root.add(colNode);
            for (ApiRequest req : col.requests) {
                String path = req.path != null ? req.path : "";
                String[] parts = path.split("/");
                java.util.List<String> segments = new java.util.ArrayList<>();
                for (String p : parts) {
                    if (!p.isEmpty()) segments.add(p);
                }
                boolean lastIsRequestName = !segments.isEmpty() && segments.get(segments.size() - 1).equals(req.name);
                int folderCount = lastIsRequestName ? segments.size() - 1 : segments.size();

                CollectionTreeNode parent = colNode;
                StringBuilder cumulative = new StringBuilder();
                for (int i = 0; i < folderCount; i++) {
                    if (cumulative.length() > 0) cumulative.append("/");
                    cumulative.append(segments.get(i));
                    parent = getOrCreateFolderNode(parent, segments.get(i), cumulative.toString());
                }
                parent.add(new CollectionTreeNode(req));
            }
        }
        treeModel.setRoot(root);
        for (int i = 0; i < requestTree.getRowCount(); i++) {
            requestTree.expandRow(i);
        }
    }

    private CollectionTreeNode getOrCreateFolderNode(CollectionTreeNode parent, String segment, String cumulativePath) {
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

    private void setTreeCheckState(boolean checked) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            if (root.getChildAt(i) instanceof CollectionTreeNode) {
                ((CollectionTreeNode) root.getChildAt(i)).propagateCheck(checked);
            }
        }
        requestTree.repaint();
    }

    private List<ApiRequest> getSelectedRequestsFromTree() {
        List<ApiRequest> selected = new ArrayList<>();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        enumerateCheckedRequests(root, selected);
        return selected;
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

    private ApiCollection findCollectionByName(String name) {
        for (ApiCollection c : loadedCollections) {
            if (c.name.equals(name)) return c;
        }
        return null;
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
                    loadedCollections.add(collection);
                    rebuildTree();
                    refreshCollectionCombos();
                    appendImportLog("Loaded \"" + collection.name + "\" (" + collection.requests.size() + " requests)");
                    importBtn.setEnabled(true);
                    sendToRunnerBtn.setEnabled(true);
                    startRunnerBtn.setEnabled(true);
                    removeCollectionBtn.setEnabled(true);
                    envApplyAllBtn.setEnabled(true);
                } catch (Exception e) {
                    appendImportLog("Error loading collection: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void removeSelectedCollections() {
        TreePath path = requestTree.getSelectionPath();
        if (path == null) {
            appendImportLog("Select a collection in the tree to remove.");
            return;
        }
        Object node = path.getLastPathComponent();
        if (node instanceof CollectionTreeNode) {
            CollectionTreeNode ctn = (CollectionTreeNode) node;
            ApiCollection target = findCollectionForNode(ctn);
            if (target != null) {
                loadedCollections.remove(target);
                rebuildTree();
                refreshCollectionCombos();
                appendImportLog("Removed collection: " + target.name);
                if (loadedCollections.isEmpty()) {
                    importBtn.setEnabled(false);
                    sendToRunnerBtn.setEnabled(false);
                    startRunnerBtn.setEnabled(false);
                    removeCollectionBtn.setEnabled(false);
                    envApplyAllBtn.setEnabled(false);
                }
            }
        }
    }

    private void refreshCollectionCombos() {
        String prevVars = varsCollectionCombo.getSelectedItem() != null ? (String) varsCollectionCombo.getSelectedItem() : null;
        varsCollectionCombo.removeAllItems();
        for (ApiCollection c : loadedCollections) {
            varsCollectionCombo.addItem(c.name);
        }
        if (prevVars != null) {
            for (int i = 0; i < varsCollectionCombo.getItemCount(); i++) {
                if (prevVars.equals(varsCollectionCombo.getItemAt(i))) {
                    varsCollectionCombo.setSelectedIndex(i);
                    break;
                }
            }
        }

        String prevOAuth2 = oauth2CollectionCombo.getSelectedItem() != null ? (String) oauth2CollectionCombo.getSelectedItem() : null;
        oauth2CollectionCombo.removeAllItems();
        for (ApiCollection c : loadedCollections) {
            oauth2CollectionCombo.addItem(c.name);
        }
        if (prevOAuth2 != null) {
            for (int i = 0; i < oauth2CollectionCombo.getItemCount(); i++) {
                if (prevOAuth2.equals(oauth2CollectionCombo.getItemAt(i))) {
                    oauth2CollectionCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        updateScopeControlState();
    }

    private void updateScopeControlState() {
        // Variables tab
        if (varsCollectionCombo != null && envVarsArea != null) {
            String varsSelected = varsCollectionCombo.getSelectedItem() != null ? (String) varsCollectionCombo.getSelectedItem() : null;
            boolean varsHasTarget = varsSelected != null;
            envVarsArea.setEnabled(varsHasTarget);
            envVarsArea.setEditable(varsHasTarget);
            if (bindVarsBtn != null) bindVarsBtn.setEnabled(varsHasTarget);
            if (varsHintLabel != null) {
                if (varsHasTarget) {
                    varsHintLabel.setText("Editing scoped variables for: " + varsSelected);
                    varsHintLabel.setForeground(Color.BLACK);
                } else {
                    varsHintLabel.setText("Select a collection to edit scoped variables.");
                    varsHintLabel.setForeground(Color.GRAY);
                }
            }
        }

        // OAuth2 tab
        if (oauth2CollectionCombo != null && oauth2Panel != null) {
            String oauth2Selected = oauth2CollectionCombo.getSelectedItem() != null ? (String) oauth2CollectionCombo.getSelectedItem() : null;
            boolean oauth2HasTarget = oauth2Selected != null;
            oauth2Panel.setEditable(oauth2HasTarget);
            if (bindOAuth2Btn != null) bindOAuth2Btn.setEnabled(oauth2HasTarget);
            if (oauth2HintLabel != null) {
                if (oauth2HasTarget) {
                    oauth2HintLabel.setText("Binding OAuth2 to: " + oauth2Selected);
                    oauth2HintLabel.setForeground(Color.BLACK);
                } else {
                    oauth2HintLabel.setText("Select a collection to bind OAuth2 settings.");
                    oauth2HintLabel.setForeground(Color.GRAY);
                }
            }
        }

        // Env apply selected requires a collection selected in the tree
        if (requestTree != null && envApplySelectedBtn != null) {
            TreePath path = requestTree.getSelectionPath();
            boolean treeHasCollection = false;
            if (path != null) {
                Object node = path.getLastPathComponent();
                if (node instanceof CollectionTreeNode) {
                    ApiCollection col = findCollectionForNode((CollectionTreeNode) node);
                    treeHasCollection = col != null;
                }
            }
            envApplySelectedBtn.setEnabled(treeHasCollection && !loadedCollections.isEmpty());
        }
    }

    // ========================================================================
    // Environment Binding
    // ========================================================================
    private void selectEnvironment() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files", "json"));
        if (chooser.showOpenDialog(mainPanel) == JFileChooser.APPROVE_OPTION) {
            selectedEnv = chooser.getSelectedFile();
            envField.setText(selectedEnv.getAbsolutePath());
        }
    }

    private void applyEnvToSelectedCollection() {
        if (selectedEnv == null) {
            appendImportLog("No environment file selected. Browse first.");
            return;
        }
        TreePath path = requestTree.getSelectionPath();
        if (path == null) {
            appendImportLog("Select a collection in the tree to bind env to.");
            return;
        }
        Object node = path.getLastPathComponent();
        if (node instanceof CollectionTreeNode) {
            ApiCollection target = findCollectionForNode((CollectionTreeNode) node);
            if (target != null) {
                importer.loadEnvFileIntoMap(selectedEnv, target.runtimeVars);
                appendImportLog("Env bound to collection: " + target.name);
            }
        }
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
        for (ApiCollection col : loadedCollections) {
            importer.loadEnvFileIntoMap(selectedEnv, col.runtimeVars);
        }
        appendImportLog("Env bound to all " + loadedCollections.size() + " collection(s).");
    }

    // ========================================================================
    // Variables Tab Binding
    // ========================================================================
    private void bindVarsToSelectedCollection() {
        String colName = (String) varsCollectionCombo.getSelectedItem();
        if (colName == null) {
            appendImportLog("Variables: No collection selected for binding.");
            return;
        }
        ApiCollection col = findCollectionByName(colName);
        if (col == null) return;
        Map<String, String> vars = parseEnvVarsMap();
        col.runtimeVars.putAll(vars);
        appendImportLog("Variables bound to \"" + colName + "\": " + vars.size() + " var(s).");
    }

    private void bindVarsToAllCollections() {
        if (loadedCollections.isEmpty()) {
            appendImportLog("Variables: No collections loaded.");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(mainPanel,
            "This will overwrite scoped variables in ALL " + loadedCollections.size() + " collection(s) with the current text. Continue?",
            "Confirm Apply to All Collections", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        Map<String, String> vars = parseEnvVarsMap();
        for (ApiCollection col : loadedCollections) {
            col.runtimeVars.putAll(vars);
        }
        appendImportLog("Variables bound to all " + loadedCollections.size() + " collection(s): " + vars.size() + " var(s).");
    }

    // ========================================================================
    // Import / Destination Flow
    // ========================================================================
    private void startImport() {
        List<ApiRequest> selected = getSelectedRequestsFromTree();
        if (selected.isEmpty()) {
            appendImportLog("No requests selected.");
            return;
        }
        List<String> destinations = new ArrayList<>();
        if (repeaterBtn.isSelected()) destinations.add("repeater");
        if (sitemapBtn.isSelected()) destinations.add("sitemap");
        if (intruderBtn.isSelected()) destinations.add("intruder");
        if (destinations.isEmpty()) {
            appendImportLog("No destination selected.");
            return;
        }
        int delay = (Integer) delaySpinner.getValue();
        importer.setDebugRawRequest(debugRawRequestBox.isSelected());

        // Build deterministic queue preserving collection order + request order
        List<UniversalImporter.QueuedRequest> queue = new ArrayList<>();
        for (ApiCollection col : loadedCollections) {
            for (ApiRequest req : selected) {
                if (col.name.equals(req.sourceCollection)) {
                    queue.add(new UniversalImporter.QueuedRequest(col, req));
                }
            }
        }

        importer.importRequestsSequential(queue, destinations, delay,
            this::appendImportLog,
            result -> SwingUtilities.invokeLater(() -> {
                importProgress.setValue(100);
                appendImportLog("Import complete: " + result.successCount + "/" + result.totalRequests + " succeeded.");
            })
        );
    }

    private void sendToRunner() {
        List<ApiRequest> selected = getSelectedRequestsFromTree();
        if (selected.isEmpty()) {
            appendImportLog("No requests selected to send to runner.");
            return;
        }
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
    private void startRunner() {
        List<ApiRequest> selected = getSelectedRequestsFromTree();
        if (selected.isEmpty() || loadedCollections.isEmpty()) {
            appendRunnerLog("No requests to run. Load collections first.");
            return;
        }

        runner.setDelayMs((Integer) runnerDelaySpinner.getValue());
        runner.setStopOnError(stopOnErrorBox.isSelected());
        runner.setFollowRedirects(followRedirectsBox.isSelected());
        runner.setDebugRawRequest(runnerDebugRawRequestBox.isSelected());

        resultModel.clear();
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
                });
            }
            @Override public void onComplete(List<RunnerResult> results) {
                SwingUtilities.invokeLater(() -> {
                    long success = results.stream().filter(r -> r.success).count();
                    appendRunnerLog("\n=== Runner Complete ===");
                    appendRunnerLog("Success: " + success + "/" + results.size());
                    appendRunnerLog("Total extracted vars: " + runner.getExtractedVariables().size());
                    startRunnerBtn.setEnabled(true);
                    cancelRunnerBtn.setEnabled(false);
                });
            }
            @Override public void onDebug(String message) {
                SwingUtilities.invokeLater(() -> appendRunnerLog(message));
            }
            @Override public void onError(String message) {
                SwingUtilities.invokeLater(() -> appendRunnerLog("ERROR: " + message));
            }
        };
        runner.addListener(activeRunnerListener);

        runner.runCollections(loadedCollections, selected);
    }

    // ========================================================================
    // Populate OAuth2 from selected request
    // ========================================================================
    private void populateOAuth2FromSelectedRequest() {
        List<ApiRequest> selected = getSelectedRequestsFromTree();
        if (selected.isEmpty()) {
            appendImportLog("Populate OAuth2: No request selected in tree.");
            return;
        }
        ApiRequest req = selected.get(0);
        Map<String, String> extracted = burp.utils.OAuth2PopulateHelper.extractOAuth2Fields(req);
        if (extracted.isEmpty()) {
            appendImportLog("Populate OAuth2: Selected request has no OAuth2-relevant data.");
            return;
        }
        Map<String, String> merged = burp.utils.OAuth2PopulateHelper.mergeWithExisting(extracted, parseEnvVarsMap());
        oauth2Panel.populateFromOAuth2Map(merged);
        appendImportLog("Populate OAuth2: Filled " + extracted.size() + " field(s) from request \"" + req.name + "\".");
    }

    // ========================================================================
    // Utility
    // ========================================================================
    private Map<String, String> parseEnvVarsMap() {
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

    private void parseEnvVars() {
        Map<String, String> vars = parseEnvVarsMap();
        appendImportLog("Parsed " + vars.size() + " variables.");
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
        if (r == null) return;
        StringBuilder req = new StringBuilder();
        req.append(r.method != null ? r.method : "GET").append(" ").append(r.path != null ? r.path : "/").append(" HTTP/1.1\n");
        req.append("Host: ").append(r.host != null ? r.host : "").append("\n");
        if (r.requestHeaders != null) {
            String[] lines = r.requestHeaders.split("\n");
            for (int i = 1; i < lines.length; i++) {
                req.append(lines[i]).append("\n");
            }
        }
        if (r.requestBody != null && !r.requestBody.isEmpty()) {
            req.append("\n").append(r.requestBody);
        }
        detailRequestText.setText(req.toString());
        detailRequestText.setCaretPosition(0);

        StringBuilder resp = new StringBuilder();
        if (r.responseHeaders != null) {
            resp.append(r.responseHeaders).append("\n");
        }
        if (r.responseBody != null) {
            resp.append(r.responseBody);
        }
        detailResponseText.setText(resp.toString());
        detailResponseText.setCaretPosition(0);

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
