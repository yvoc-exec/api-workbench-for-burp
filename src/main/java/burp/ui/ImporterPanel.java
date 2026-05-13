package burp.ui;

import burp.models.*;
import burp.parser.*;
import burp.runner.CollectionRunner;
import burp.auth.OAuth2Manager;
import burp.auth.OAuth2Config;
import burp.auth.TokenStore;
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
    private final OAuth2Manager oauth2Manager;
    private OAuth2Panel oauth2Panel;
    private final JPanel mainPanel;
    private final JTabbedPane tabbedPane;

    // Import tab
    private JTextArea importLog;
    private JProgressBar importProgress;
    private JTextField collectionField;
    private JTextField envField;
    private JTable previewTable;
    private RequestPreviewTableModel previewModel;
    private JRadioButton repeaterBtn, sitemapBtn, bothBtn;
    private JSpinner delaySpinner;
    private JButton importBtn, previewBtn, runBtn;
    private File selectedCollection;
    private File selectedEnv;
    private ApiCollection currentCollection;

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

    public ImporterPanel(UniversalImporter importer, CollectionRunner runner, OAuth2Manager oauth2Manager) {
        this.oauth2Manager = oauth2Manager;
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
        oauth2Panel = new OAuth2Panel(oauth2Manager);
        tabbedPane.addTab("OAuth2", oauth2Panel);

        panel.add(tabbedPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createImportTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));

        // Top: File selection
        JPanel filePanel = new JPanel(new GridBagLayout());
        filePanel.setBorder(BorderFactory.createTitledBorder("Collection Source"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0; gbc.gridy = 0;
        filePanel.add(new JLabel("Collection:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        collectionField = new JTextField();
        collectionField.setEditable(false);
        filePanel.add(collectionField, gbc);
        gbc.gridx = 2; gbc.weightx = 0;
        JButton browseBtn = new JButton("Browse...");
        browseBtn.addActionListener(e -> selectCollection());
        filePanel.add(browseBtn, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        filePanel.add(new JLabel("Env File (opt):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        envField = new JTextField();
        envField.setEditable(false);
        filePanel.add(envField, gbc);
        gbc.gridx = 2; gbc.weightx = 0;
        JButton envBrowseBtn = new JButton("Browse...");
        envBrowseBtn.addActionListener(e -> selectEnvironment());
        filePanel.add(envBrowseBtn, gbc);

        // Destination
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
        filePanel.add(new JLabel("Destination:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        JPanel destPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        ButtonGroup destGroup = new ButtonGroup();
        repeaterBtn = new JRadioButton("Repeater", true);
        sitemapBtn = new JRadioButton("Sitemap (Live)");
        bothBtn = new JRadioButton("Both");
        destGroup.add(repeaterBtn);
        destGroup.add(sitemapBtn);
        destGroup.add(bothBtn);
        destPanel.add(repeaterBtn);
        destPanel.add(Box.createHorizontalStrut(10));
        destPanel.add(sitemapBtn);
        destPanel.add(Box.createHorizontalStrut(10));
        destPanel.add(bothBtn);
        filePanel.add(destPanel, gbc);

        // Rate limit
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 1;
        filePanel.add(new JLabel("Delay (ms):"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        delaySpinner = new JSpinner(new SpinnerNumberModel(200, 0, 5000, 50));
        filePanel.add(delaySpinner, gbc);

        panel.add(filePanel, BorderLayout.NORTH);

        // Center: Preview table
        previewModel = new RequestPreviewTableModel();
        previewTable = new JTable(previewModel);
        previewTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        previewTable.getColumnModel().getColumn(0).setMaxWidth(50);
        previewTable.getColumnModel().getColumn(2).setMaxWidth(70);
        previewTable.getColumnModel().getColumn(4).setMaxWidth(50);
        previewTable.getColumnModel().getColumn(5).setMaxWidth(50);
        JScrollPane tableScroll = new JScrollPane(previewTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Request Preview (Select requests to import)"));
        panel.add(tableScroll, BorderLayout.CENTER);

        // Buttons above table
        JPanel selectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton selectAllBtn = new JButton("Select All");
        selectAllBtn.addActionListener(e -> previewModel.selectAll(true));
        JButton deselectAllBtn = new JButton("Deselect All");
        deselectAllBtn.addActionListener(e -> previewModel.selectAll(false));
        selectPanel.add(selectAllBtn);
        selectPanel.add(deselectAllBtn);
        panel.add(selectPanel, BorderLayout.WEST);

        // Bottom: Actions + Log
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));

        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        previewBtn = new JButton("Preview");
        previewBtn.setEnabled(false);
        previewBtn.addActionListener(e -> loadPreview());
        importBtn = new JButton("Import Selected");
        importBtn.setEnabled(false);
        importBtn.addActionListener(e -> startImport());
        runBtn = new JButton("Send to Runner →");
        runBtn.setEnabled(false);
        runBtn.addActionListener(e -> sendToRunner());
        actionPanel.add(previewBtn);
        actionPanel.add(importBtn);
        actionPanel.add(runBtn);
        bottomPanel.add(actionPanel, BorderLayout.NORTH);

        importLog = new JTextArea(8, 50);
        importLog.setEditable(false);
        importLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(importLog);
        logScroll.setBorder(BorderFactory.createTitledBorder("Import Log"));
        bottomPanel.add(logScroll, BorderLayout.CENTER);

        importProgress = new JProgressBar(0, 100);
        importProgress.setStringPainted(true);
        bottomPanel.add(importProgress, BorderLayout.SOUTH);

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

        // Center: Results table
        resultModel = new RunnerResultTableModel();
        resultTable = new JTable(resultModel);
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        JScrollPane tableScroll = new JScrollPane(resultTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Runner Results"));
        panel.add(tableScroll, BorderLayout.CENTER);

        // Bottom: Log + Actions
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));

        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        startRunnerBtn = new JButton("▶ Start Collection Runner");
        startRunnerBtn.setEnabled(false);
        startRunnerBtn.addActionListener(e -> startRunner());
        cancelRunnerBtn = new JButton("⏹ Cancel");
        cancelRunnerBtn.setEnabled(false);
        cancelRunnerBtn.addActionListener(e -> runner.cancel());
        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> {
            resultModel.clear();
            runnerLog.setText("");
        });
        actionPanel.add(clearBtn);
        actionPanel.add(startRunnerBtn);
        actionPanel.add(cancelRunnerBtn);
        bottomPanel.add(actionPanel, BorderLayout.NORTH);

        runnerLog = new JTextArea(8, 50);
        runnerLog.setEditable(false);
        runnerLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(runnerLog);
        logScroll.setBorder(BorderFactory.createTitledBorder("Runner Log"));
        bottomPanel.add(logScroll, BorderLayout.CENTER);

        runnerProgress = new JProgressBar(0, 100);
        runnerProgress.setStringPainted(true);
        bottomPanel.add(runnerProgress, BorderLayout.SOUTH);

        panel.add(bottomPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createVariablesTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createTitledBorder("Environment Variables (JSON or key=value per line)"));

        envVarsArea = new JTextArea(20, 60);
        envVarsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        envVarsArea.setText("# Example:
# base_url=http://localhost:8080
# api_key=your_key_here
# token={{auth_token}}");
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

    private void selectCollection() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
            "API Collections (JSON, YAML, YML, HAR, BRU folder)",
            "json", "yaml", "yml", "har"
        ));
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (chooser.showOpenDialog(mainPanel) == JFileChooser.APPROVE_OPTION) {
            selectedCollection = chooser.getSelectedFile();
            collectionField.setText(selectedCollection.getAbsolutePath());
            previewBtn.setEnabled(true);
            importBtn.setEnabled(false);
            runBtn.setEnabled(false);
        }
    }

    private void selectEnvironment() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files", "json"));
        if (chooser.showOpenDialog(mainPanel) == JFileChooser.APPROVE_OPTION) {
            selectedEnv = chooser.getSelectedFile();
            envField.setText(selectedEnv.getAbsolutePath());
        }
    }

    private void loadPreview() {
        if (selectedCollection == null) return;
        importLog.setText("");
        appendImportLog("Loading collection...");

        SwingWorker<ApiCollection, String> worker = new SwingWorker<>() {
            @Override
            protected ApiCollection doInBackground() throws Exception {
                publish("Detecting format...");
                ParserRegistry registry = new ParserRegistry();
                CollectionParser parser = registry.detectParser(selectedCollection);
                if (parser == null) {
                    throw new Exception("Unknown collection format. Supported: Postman, Bruno, OpenAPI, Insomnia, HAR");
                }
                publish("Detected: " + parser.getFormatName());
                publish("Parsing...");
                return parser.parse(selectedCollection);
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) appendImportLog(msg);
            }

            @Override
            protected void done() {
                try {
                    currentCollection = get();
                    previewModel.setRequests(currentCollection.requests);
                    appendImportLog("Loaded " + currentCollection.requests.size() + " requests from " + currentCollection.format + " collection.");
                    importBtn.setEnabled(true);
                    runBtn.setEnabled(true);
                    startRunnerBtn.setEnabled(true);
                } catch (Exception e) {
                    appendImportLog("Error: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void startImport() {
        List<ApiRequest> selected = previewModel.getSelectedRequests();
        if (selected.isEmpty()) {
            appendImportLog("No requests selected.");
            return;
        }
        String destination = repeaterBtn.isSelected() ? "repeater" :
                           sitemapBtn.isSelected() ? "sitemap" : "both";
        int delay = (Integer) delaySpinner.getValue();

        importer.importRequests(currentCollection, selected, selectedEnv, destination, delay, this::appendImportLog,
            result -> SwingUtilities.invokeLater(() -> {
                importProgress.setValue(100);
                appendImportLog("Import complete: " + result.successCount + "/" + result.totalRequests + " succeeded.");
            })
        );
    }

    private void sendToRunner() {
        List<ApiRequest> selected = previewModel.getSelectedRequests();
        if (selected.isEmpty()) {
            appendImportLog("No requests selected to send to runner.");
            return;
        }
        tabbedPane.setSelectedIndex(1); // Switch to Runner tab
        appendRunnerLog(selected.size() + " requests queued in runner. Configure settings and press Start.");
    }

    private void startRunner() {
        List<ApiRequest> selected = previewModel.getSelectedRequests();
        if (selected.isEmpty() || currentCollection == null) {
            appendRunnerLog("No requests to run. Import a collection first.");
            return;
        }

        Map<String, String> initialVars = parseEnvVarsMap();

        runner.setDelayMs((Integer) runnerDelaySpinner.getValue());
        runner.setStopOnError(stopOnErrorBox.isSelected());
        runner.setFollowRedirects(followRedirectsBox.isSelected());

        resultModel.clear();
        runnerLog.setText("");
        runnerProgress.setValue(0);

        runner.addListener(new CollectionRunner.RunnerListener() {
            @Override public void onStart(String name, int total) {
                SwingUtilities.invokeLater(() -> {
                    appendRunnerLog("▶ Starting: " + name + " (" + total + " requests)");
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

        runner.runCollection(currentCollection, selected, initialVars);
    }

    private Map<String, String> parseEnvVarsMap() {
        Map<String, String> vars = new HashMap<>();
        vars.putAll(oauth2Panel.getVariables());
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

    public JPanel getPanel() { return mainPanel; }
    public JTabbedPane getTabbedPane() { return tabbedPane; }
}
