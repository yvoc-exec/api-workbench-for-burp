package burp.ui;

import burp.models.*;
import burp.parser.*;
import burp.runner.CollectionRunner;
import burp.auth.OAuth2Manager;
import burp.UniversalImporter;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
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
    private final DefaultListModel<String> collectionListModel = new DefaultListModel<>();
    private JList<String> collectionList;
    private File selectedEnv;

    // Import tab
    private JTextArea importLog;
    private JProgressBar importProgress;
    private JTextField envField;
    private JTable previewTable;
    private RequestPreviewTableModel previewModel;
    private JCheckBox repeaterBtn, sitemapBtn, intruderBtn;
    private JSpinner delaySpinner;
    private JButton importBtn, previewBtn, runBtn, addCollectionBtn, removeCollectionBtn;

    // Runner tab
    private JTextArea runnerLog;
    private JProgressBar runnerProgress;
    private JTable resultTable;
    private RunnerResultTableModel resultModel;
    private JSpinner runnerDelaySpinner;
    private JCheckBox stopOnErrorBox;
    private JCheckBox followRedirectsBox;
    private JButton startRunnerBtn, cancelRunnerBtn;
    private JTextArea envVarsArea;

    // Runner detail pane
    private JTextArea detailRequestText;
    private JTextArea detailResponseText;
    private JTextArea detailVarsText;

    public ImporterPanel(UniversalImporter importer, CollectionRunner runner, OAuth2Manager oauth2Manager) {
        this.oauth2Panel = new OAuth2Panel(oauth2Manager);
        this.importer = importer;
        this.runner = runner;
        this.mainPanel = createUI();
    }

    private JPanel createUI() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Import", createImportTab());
        tabbedPane.addTab("Collection Runner", createRunnerTab());
        tabbedPane.addTab("Variables", createVariablesTab());
        tabbedPane.addTab("OAuth2", oauth2Panel);

        panel.add(tabbedPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createImportTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));

        // Top: Collection list + controls
        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        topPanel.setBorder(BorderFactory.createTitledBorder("Collections"));

        // Collection list
        collectionList = new JList<>(collectionListModel);
        collectionList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        collectionList.setVisibleRowCount(4);
        JScrollPane listScroll = new JScrollPane(collectionList);
        listScroll.setPreferredSize(new Dimension(300, 90));
        topPanel.add(listScroll, BorderLayout.CENTER);

        // Collection buttons
        JPanel collectionBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        addCollectionBtn = new JButton("+ Add Collection");
        addCollectionBtn.addActionListener(e -> addCollection());
        removeCollectionBtn = new JButton("- Remove");
        removeCollectionBtn.addActionListener(e -> removeSelectedCollections());
        removeCollectionBtn.setEnabled(false);
        collectionList.addListSelectionListener(e -> removeCollectionBtn.setEnabled(!collectionList.isSelectionEmpty()));
        collectionBtnPanel.add(addCollectionBtn);
        collectionBtnPanel.add(removeCollectionBtn);
        topPanel.add(collectionBtnPanel, BorderLayout.SOUTH);

        // Environment file
        JPanel envPanel = new JPanel(new BorderLayout(5, 0));
        envPanel.add(new JLabel("Env File:"), BorderLayout.WEST);
        envField = new JTextField();
        envField.setEditable(false);
        envPanel.add(envField, BorderLayout.CENTER);
        JButton envBrowseBtn = new JButton("Browse...");
        envBrowseBtn.addActionListener(e -> selectEnvironment());
        envPanel.add(envBrowseBtn, BorderLayout.EAST);
        topPanel.add(envPanel, BorderLayout.NORTH);

        panel.add(topPanel, BorderLayout.NORTH);

        // Center: Preview table
        previewModel = new RequestPreviewTableModel();
        previewTable = new JTable(previewModel);
        previewTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        previewTable.getColumnModel().getColumn(0).setMaxWidth(50);  // Select
        previewTable.getColumnModel().getColumn(2).setMaxWidth(70);  // Method
        previewTable.getColumnModel().getColumn(4).setMaxWidth(80);  // Auth
        previewTable.getColumnModel().getColumn(5).setMaxWidth(50);  // Body
        previewTable.getColumnModel().getColumn(6).setMaxWidth(120); // Source
        JScrollPane tableScroll = new JScrollPane(previewTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Request Preview (Select requests to import/run)"));
        panel.add(tableScroll, BorderLayout.CENTER);

        // Selection controls
        JPanel selectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton selectAllBtn = new JButton("Select All");
        selectAllBtn.addActionListener(e -> previewModel.selectAll(true));
        JButton deselectAllBtn = new JButton("Deselect All");
        deselectAllBtn.addActionListener(e -> previewModel.selectAll(false));
        selectPanel.add(selectAllBtn);
        selectPanel.add(deselectAllBtn);
        panel.add(selectPanel, BorderLayout.WEST);

        // Bottom: Compact vertical stack
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));

        // Row 1: Destination checkboxes + Delay (compact)
        JPanel configRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        configRow.setBorder(BorderFactory.createTitledBorder("Destination"));
        repeaterBtn = new JCheckBox("Repeater", true);
        sitemapBtn = new JCheckBox("Sitemap (Live)");
        intruderBtn = new JCheckBox("Intruder");
        configRow.add(repeaterBtn);
        configRow.add(sitemapBtn);
        configRow.add(intruderBtn);
        configRow.add(Box.createHorizontalStrut(15));
        configRow.add(new JLabel("Delay (ms):"));
        delaySpinner = new JSpinner(new SpinnerNumberModel(200, 0, 5000, 50));
        delaySpinner.setMaximumSize(new Dimension(80, 25));
        configRow.add(delaySpinner);
        bottomPanel.add(configRow);

        // Row 2: Progress bar + Action buttons
        JPanel actionRow = new JPanel(new BorderLayout(10, 0));
        actionRow.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));

        importProgress = new JProgressBar(0, 100);
        importProgress.setStringPainted(true);
        importProgress.setPreferredSize(new Dimension(180, 20));
        actionRow.add(importProgress, BorderLayout.WEST);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        importBtn = new JButton("Import Selected");
        importBtn.setEnabled(false);
        importBtn.addActionListener(e -> startImport());
        runBtn = new JButton("Send to Runner →");
        runBtn.setEnabled(false);
        runBtn.addActionListener(e -> sendToRunner());
        btnPanel.add(importBtn);
        btnPanel.add(runBtn);
        actionRow.add(btnPanel, BorderLayout.EAST);
        bottomPanel.add(actionRow);

        // Row 3: Import Log (limited height)
        importLog = new JTextArea(3, 50);
        importLog.setEditable(false);
        importLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(importLog);
        logScroll.setBorder(BorderFactory.createTitledBorder("Import Log"));
        logScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        logScroll.setPreferredSize(new Dimension(400, 70));
        bottomPanel.add(logScroll);

        panel.add(bottomPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createRunnerTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));

        // Top: Config
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

        panel.add(configPanel, BorderLayout.NORTH);

        // Center: Results table + Detail pane split
        resultModel = new RunnerResultTableModel();
        resultTable = new JTable(resultModel);
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane tableScroll = new JScrollPane(resultTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Runner Results"));
        tableScroll.setPreferredSize(new Dimension(350, 250));
        tableScroll.setMinimumSize(new Dimension(200, 150));

        // Detail pane with tabs
        JTabbedPane detailTabs = new JTabbedPane();
        detailRequestText = new JTextArea();
        detailRequestText.setEditable(false);
        detailRequestText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane reqScroll = new JScrollPane(detailRequestText);
        reqScroll.setPreferredSize(new Dimension(300, 250));
        reqScroll.setMinimumSize(new Dimension(150, 150));
        detailTabs.addTab("Request", reqScroll);

        detailResponseText = new JTextArea();
        detailResponseText.setEditable(false);
        detailResponseText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane respScroll = new JScrollPane(detailResponseText);
        respScroll.setPreferredSize(new Dimension(300, 250));
        respScroll.setMinimumSize(new Dimension(150, 150));
        detailTabs.addTab("Response", respScroll);

        detailVarsText = new JTextArea();
        detailVarsText.setEditable(false);
        detailVarsText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane varsScroll = new JScrollPane(detailVarsText);
        varsScroll.setPreferredSize(new Dimension(300, 250));
        varsScroll.setMinimumSize(new Dimension(150, 150));
        detailTabs.addTab("Vars", varsScroll);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, detailTabs);
        splitPane.setResizeWeight(0.5);
        splitPane.setOneTouchExpandable(true);
        splitPane.setContinuousLayout(true);
        panel.add(splitPane, BorderLayout.CENTER);

        // Selection listener to populate detail pane
        resultTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && resultTable.getSelectedRow() >= 0) {
                RunnerResult r = resultModel.getResultAt(resultTable.getSelectedRow());
                updateRunnerDetailPane(r);
            }
        });

        // Bottom: Log + Actions (compact vertical stack)
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));

        // Row 1: Progress + Actions
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
        startRunnerBtn = new JButton("▶ Start Collection Runner");
        startRunnerBtn.setEnabled(false);
        startRunnerBtn.addActionListener(e -> startRunner());
        cancelRunnerBtn = new JButton("⏹ Cancel");
        cancelRunnerBtn.setEnabled(false);
        cancelRunnerBtn.addActionListener(e -> runner.cancel());
        btnPanel.add(clearBtn);
        btnPanel.add(startRunnerBtn);
        btnPanel.add(cancelRunnerBtn);
        actionRow.add(btnPanel, BorderLayout.EAST);
        bottomPanel.add(actionRow);

        // Row 2: Runner Log (limited height)
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

    private JPanel createVariablesTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createTitledBorder("Environment Variables (JSON or key=value per line)"));

        envVarsArea = new JTextArea(20, 60);
        envVarsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        envVarsArea.setText("# Example:\n# base_url=http://localhost:8080\n# api_key=your_key_here\n# token={{auth_token}}");
        JScrollPane scroll = new JScrollPane(envVarsArea);
        panel.add(scroll, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton parseBtn = new JButton("Parse Variables");
        parseBtn.addActionListener(e -> parseEnvVars());
        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> envVarsArea.setText(""));
        btnPanel.add(parseBtn);
        btnPanel.add(clearBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);

        return panel;
    }

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
                    collectionListModel.addElement(collection.name + " (" + collection.format + ", " + collection.requests.size() + " reqs)");
                    refreshPreviewTable();
                    appendImportLog("Loaded \"" + collection.name + "\" (" + collection.requests.size() + " requests)");
                    importBtn.setEnabled(true);
                    runBtn.setEnabled(true);
                    startRunnerBtn.setEnabled(true);
                } catch (Exception e) {
                    appendImportLog("Error loading collection: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void removeSelectedCollections() {
        int[] indices = collectionList.getSelectedIndices();
        // Remove in reverse order to maintain index validity
        Arrays.sort(indices);
        for (int i = indices.length - 1; i >= 0; i--) {
            int idx = indices[i];
            if (idx >= 0 && idx < loadedCollections.size()) {
                ApiCollection removed = loadedCollections.remove(idx);
                collectionListModel.remove(idx);
                appendImportLog("Removed collection: " + removed.name);
            }
        }
        refreshPreviewTable();
        if (loadedCollections.isEmpty()) {
            importBtn.setEnabled(false);
            runBtn.setEnabled(false);
            startRunnerBtn.setEnabled(false);
        }
    }

    private void refreshPreviewTable() {
        List<ApiRequest> allRequests = new ArrayList<>();
        for (ApiCollection col : loadedCollections) {
            allRequests.addAll(col.requests);
        }
        previewModel.setRequests(allRequests);
    }

    private void selectEnvironment() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files", "json"));
        if (chooser.showOpenDialog(mainPanel) == JFileChooser.APPROVE_OPTION) {
            selectedEnv = chooser.getSelectedFile();
            envField.setText(selectedEnv.getAbsolutePath());
        }
    }

    private void startImport() {
        List<ApiRequest> selected = previewModel.getSelectedRequests();
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

        // Group requests by collection for import
        Map<String, List<ApiRequest>> requestsByCollection = new HashMap<>();
        for (ApiRequest req : selected) {
            String colName = req.sourceCollection != null ? req.sourceCollection : "Unknown";
            requestsByCollection.computeIfAbsent(colName, k -> new ArrayList<>()).add(req);
        }

        // Find the collection object for each group
        for (Map.Entry<String, List<ApiRequest>> entry : requestsByCollection.entrySet()) {
            ApiCollection targetCol = loadedCollections.stream()
                .filter(c -> c.name.equals(entry.getKey()))
                .findFirst()
                .orElse(null);
            if (targetCol != null) {
                importer.importRequests(targetCol, entry.getValue(), selectedEnv, destinations, delay,
                    this::appendImportLog,
                    result -> SwingUtilities.invokeLater(() -> {
                        importProgress.setValue(100);
                        appendImportLog("Import complete for \"" + targetCol.name + "\": " + result.successCount + "/" + result.totalRequests + " succeeded.");
                    })
                );
            }
        }
    }

    private void sendToRunner() {
        List<ApiRequest> selected = previewModel.getSelectedRequests();
        if (selected.isEmpty()) {
            appendImportLog("No requests selected to send to runner.");
            return;
        }
        tabbedPane.setSelectedIndex(1);
        appendRunnerLog(selected.size() + " requests from " + 
            selected.stream().map(r -> r.sourceCollection).distinct().count() + 
            " collection(s) queued in runner. Configure settings and press Start.");
    }

    private void startRunner() {
        List<ApiRequest> selected = previewModel.getSelectedRequests();
        if (selected.isEmpty() || loadedCollections.isEmpty()) {
            appendRunnerLog("No requests to run. Load collections first.");
            return;
        }

        Map<String, String> initialVars = parseEnvVarsMap();
        // Merge OAuth2 variables
        if (oauth2Panel != null) {
            initialVars.putAll(oauth2Panel.getVariables());
        }
        // Merge collection variables from all loaded collections
        for (ApiCollection col : loadedCollections) {
            for (ApiRequest.Variable var : col.variables) {
                if (var.value != null) {
                    initialVars.putIfAbsent(var.key, var.value);
                }
            }
        }

        runner.setDelayMs((Integer) runnerDelaySpinner.getValue());
        runner.setStopOnError(stopOnErrorBox.isSelected());
        runner.setFollowRedirects(followRedirectsBox.isSelected());

        resultModel.clear();
        runnerLog.setText("");
        runnerProgress.setValue(0);

        runner.addListener(new CollectionRunner.RunnerListener() {
            @Override public void onStart(String name, int total) {
                SwingUtilities.invokeLater(() -> {
                    appendRunnerLog("▶ Starting runner (" + total + " requests from " + 
                        loadedCollections.size() + " collection(s))");
                    startRunnerBtn.setEnabled(false);
                    cancelRunnerBtn.setEnabled(true);
                    runnerProgress.setMaximum(total);
                });
            }
            @Override public void onSkip(String name, String reason) {
                SwingUtilities.invokeLater(() -> appendRunnerLog("⊘ Skipped: " + name + " (" + reason + ")"));
            }
            @Override public void onRequestComplete(RunnerResult result) {
                SwingUtilities.invokeLater(() -> {
                    resultModel.addResult(result);
                    runnerProgress.setValue(resultModel.getRowCount());
                    String status = result.success ? "✓ " + result.statusCode + "ms" : "✗ " + result.errorMessage;
                    appendRunnerLog((resultModel.getRowCount()) + ". " + result.requestName + " → " + status);
                    if (!result.extractedVariables.isEmpty()) {
                        appendRunnerLog("   Extracted: " + result.extractedVariables);
                    }
                });
            }
            @Override public void onComplete(List<RunnerResult> results) {
                SwingUtilities.invokeLater(() -> {
                    long success = results.stream().filter(r -> r.success).count();
                    appendRunnerLog("\n═══ Runner Complete ═══");
                    appendRunnerLog("Success: " + success + "/" + results.size());
                    appendRunnerLog("Total extracted vars: " + runner.getExtractedVariables().size());
                    startRunnerBtn.setEnabled(true);
                    cancelRunnerBtn.setEnabled(false);
                });
            }
            @Override public void onError(String message) {
                SwingUtilities.invokeLater(() -> appendRunnerLog("ERROR: " + message));
            }
        });

        // Create a merged collection for the runner
        ApiCollection mergedCollection = new ApiCollection();
        mergedCollection.name = "Merged (" + loadedCollections.size() + " collections)";
        for (ApiCollection col : loadedCollections) {
            mergedCollection.variables.addAll(col.variables);
            mergedCollection.environment.putAll(col.environment);
        }

        runner.runCollection(mergedCollection, selected, initialVars);
    }

    private Map<String, String> parseEnvVarsMap() {
        Map<String, String> vars = new HashMap<>();
        String text = envVarsArea.getText();
        // Try JSON first
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

        // Request tab
        StringBuilder req = new StringBuilder();
        req.append(r.method != null ? r.method : "GET").append(" ").append(r.path != null ? r.path : "/").append(" HTTP/1.1\n");
        req.append("Host: ").append(r.host != null ? r.host : "").append("\n");
        if (r.requestHeaders != null) {
            // Strip the first line (METHOD PATH HTTP/1.1) since we already showed it
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

        // Response tab
        StringBuilder resp = new StringBuilder();
        if (r.responseHeaders != null) {
            resp.append(r.responseHeaders).append("\n");
        }
        if (r.responseBody != null) {
            resp.append(r.responseBody);
        }
        detailResponseText.setText(resp.toString());
        detailResponseText.setCaretPosition(0);

        // Vars tab
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
