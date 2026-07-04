package burp.ui.history;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.history.HistoryEntry;
import burp.ui.SwingShortcutSupport;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

public class HistoryDetailPanel extends JPanel {
    private static final String NATIVE_CARD = "native";
    private static final String FALLBACK_CARD = "fallback";

    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea requestArea = new JTextArea();
    private final JTextArea responseArea = new JTextArea();
    private final JTextArea variablesArea = new JTextArea();
    private final JTextArea assertionsArea = new JTextArea();
    private final JTextArea scriptArea = new JTextArea();
    private final JTextArea metadataArea = new JTextArea();
    private final JCheckBox pinnedCheckBox = new JCheckBox("Pinned");
    private final JTextArea analystNotesArea = new JTextArea();
    private final JTextField tagsField = new JTextField();
    private final JButton saveMetadataButton = new JButton("Save");
    private final JLabel evidenceStatusLabel = new JLabel("Evidence can be edited and saved explicitly.");
    private final CardLayout requestCardLayout = new CardLayout();
    private final JPanel requestCardPanel = new JPanel(requestCardLayout);
    private final CardLayout responseCardLayout = new CardLayout();
    private final JPanel responseCardPanel = new JPanel(responseCardLayout);
    private final JPanel evidencePanel = new JPanel(new BorderLayout(6, 6));
    private final JScrollPane analystNotesScrollPane = new JScrollPane(analystNotesArea);
    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;
    private final boolean requestNativeViewerAvailable;
    private final boolean responseNativeViewerAvailable;
    private String requestNativeViewerReason;
    private String responseNativeViewerReason;
    private Runnable metadataSaveAction;
    private HistoryEntry currentEntry;

    public HistoryDetailPanel() {
        this(null);
    }

    public HistoryDetailPanel(MontoyaApi api) {
        setLayout(new BorderLayout());

        NativeRequestViewer requestViewer = createRequestViewer(api);
        this.requestEditor = requestViewer.editor;
        this.requestNativeViewerAvailable = requestViewer.nativeAvailable;
        this.requestNativeViewerReason = requestViewer.reason;
        if (requestNativeViewerAvailable) {
            requestCardPanel.add(requestViewer.component, NATIVE_CARD);
            requestCardPanel.add(createFallbackScrollPane(requestArea), FALLBACK_CARD);
            requestCardLayout.show(requestCardPanel, NATIVE_CARD);
        } else {
            requestCardPanel.add(createFallbackScrollPane(requestArea), FALLBACK_CARD);
            requestCardLayout.show(requestCardPanel, FALLBACK_CARD);
        }

        NativeResponseViewer responseViewer = createResponseViewer(api);
        this.responseEditor = responseViewer.editor;
        this.responseNativeViewerAvailable = responseViewer.nativeAvailable;
        this.responseNativeViewerReason = responseViewer.reason;
        if (responseNativeViewerAvailable) {
            responseCardPanel.add(responseViewer.component, NATIVE_CARD);
            responseCardPanel.add(createFallbackScrollPane(responseArea), FALLBACK_CARD);
            responseCardLayout.show(responseCardPanel, NATIVE_CARD);
        } else {
            responseCardPanel.add(createFallbackScrollPane(responseArea), FALLBACK_CARD);
            responseCardLayout.show(responseCardPanel, FALLBACK_CARD);
        }

        configureTextArea(requestArea);
        configureTextArea(responseArea);
        configureTextArea(variablesArea);
        configureTextArea(assertionsArea);
        configureTextArea(scriptArea);
        configureTextArea(metadataArea);
        configureEvidenceEditor();
        SwingShortcutSupport.installTextComponentShortcuts(requestArea);
        SwingShortcutSupport.installTextComponentShortcuts(responseArea);
        SwingShortcutSupport.installTextComponentShortcuts(variablesArea);
        SwingShortcutSupport.installTextComponentShortcuts(assertionsArea);
        SwingShortcutSupport.installTextComponentShortcuts(scriptArea);
        SwingShortcutSupport.installTextComponentShortcuts(metadataArea);
        SwingShortcutSupport.installTextComponentShortcuts(analystNotesArea);

        tabs.addTab("Request", requestCardPanel);
        tabs.addTab("Response", responseCardPanel);
        tabs.addTab("Metadata", createFallbackScrollPane(metadataArea));
        tabs.addTab("Variables / Environment", createFallbackScrollPane(variablesArea));
        tabs.addTab("Script Output", createFallbackScrollPane(scriptArea));
        tabs.addTab("Assertions / Extractions", createFallbackScrollPane(assertionsArea));
        tabs.addTab("Evidence", evidencePanel);
        add(tabs, BorderLayout.CENTER);
        clear();
    }

    public void setMetadataSaveAction(Runnable metadataSaveAction) {
        this.metadataSaveAction = metadataSaveAction;
    }

    public void showEntry(HistoryEntry entry) {
        currentEntry = entry;
        if (entry == null) {
            clear();
            return;
        }

        String requestMessage = HistoryNativeMessageFormatter.requestMessage(entry);
        requestArea.setText(requestMessage);
        requestArea.setCaretPosition(0);
        if (requestEditor != null) {
            try {
                requestEditor.setRequest(HistoryNativeHttpMessageFactory.request(requestMessage));
                requestCardLayout.show(requestCardPanel, NATIVE_CARD);
                requestNativeViewerReason = null;
            } catch (Throwable t) {
                requestNativeViewerReason = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                requestCardLayout.show(requestCardPanel, FALLBACK_CARD);
            }
        } else {
            requestCardLayout.show(requestCardPanel, FALLBACK_CARD);
        }

        String responseMessage = HistoryNativeMessageFormatter.responseMessage(entry);
        responseArea.setText(responseMessage);
        responseArea.setCaretPosition(0);
        if (responseEditor != null) {
            try {
                responseEditor.setResponse(HistoryNativeHttpMessageFactory.response(responseMessage));
                responseCardLayout.show(responseCardPanel, NATIVE_CARD);
                responseNativeViewerReason = null;
            } catch (Throwable t) {
                responseNativeViewerReason = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                responseCardLayout.show(responseCardPanel, FALLBACK_CARD);
            }
        } else {
            responseCardLayout.show(responseCardPanel, FALLBACK_CARD);
        }

        variablesArea.setText(buildVariablesText(entry));
        assertionsArea.setText(buildAssertionsText(entry));
        scriptArea.setText(buildScriptText(entry));
        metadataArea.setText(entry.toMetadataText());
        variablesArea.setCaretPosition(0);
        assertionsArea.setCaretPosition(0);
        scriptArea.setCaretPosition(0);
        metadataArea.setCaretPosition(0);
        pinnedCheckBox.setSelected(entry.pinned);
        analystNotesArea.setText(entry.analystNotes != null ? entry.analystNotes : "");
        analystNotesArea.setCaretPosition(0);
        tagsField.setText(joinTags(entry.tags));
        String metadataText = entry.toMetadataText();
        evidenceStatusLabel.setText(metadataText != null && !metadataText.isBlank()
                ? "Selected entry evidence and analyst metadata"
                : "Evidence can be edited and saved explicitly.");
        saveMetadataButton.setEnabled(true);
    }

    public void setRequestMessage(HttpRequest request) {
        if (requestEditor == null) {
            return;
        }
        try {
            requestEditor.setRequest(request != null ? request : HistoryNativeHttpMessageFactory.request(""));
            requestCardLayout.show(requestCardPanel, NATIVE_CARD);
            requestNativeViewerReason = null;
        } catch (Throwable t) {
            requestNativeViewerReason = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            requestCardLayout.show(requestCardPanel, FALLBACK_CARD);
        }
    }

    public void setResponseMessage(HttpResponse response) {
        if (responseEditor == null) {
            return;
        }
        try {
            responseEditor.setResponse(response != null ? response : HistoryNativeHttpMessageFactory.response(""));
            responseCardLayout.show(responseCardPanel, NATIVE_CARD);
            responseNativeViewerReason = null;
        } catch (Throwable t) {
            responseNativeViewerReason = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            responseCardLayout.show(responseCardPanel, FALLBACK_CARD);
        }
    }

    public void clear() {
        currentEntry = null;
        requestArea.setText("");
        responseArea.setText("");
        variablesArea.setText("");
        assertionsArea.setText("");
        scriptArea.setText("");
        metadataArea.setText("");
        pinnedCheckBox.setSelected(false);
        analystNotesArea.setText("");
        tagsField.setText("");
        evidenceStatusLabel.setText("Evidence can be edited and saved explicitly.");
        saveMetadataButton.setEnabled(false);

        if (requestEditor != null) {
            try {
                requestEditor.setRequest(HistoryNativeHttpMessageFactory.request(""));
                requestCardLayout.show(requestCardPanel, NATIVE_CARD);
                requestNativeViewerReason = null;
            } catch (Throwable t) {
                requestNativeViewerReason = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                requestCardLayout.show(requestCardPanel, FALLBACK_CARD);
            }
        } else {
            requestCardLayout.show(requestCardPanel, FALLBACK_CARD);
        }

        if (responseEditor != null) {
            try {
                responseEditor.setResponse(HistoryNativeHttpMessageFactory.response(""));
                responseCardLayout.show(responseCardPanel, NATIVE_CARD);
                responseNativeViewerReason = null;
            } catch (Throwable t) {
                responseNativeViewerReason = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                responseCardLayout.show(responseCardPanel, FALLBACK_CARD);
            }
        } else {
            responseCardLayout.show(responseCardPanel, FALLBACK_CARD);
        }
    }

    public JTextArea getRequestArea() {
        return requestArea;
    }

    public JTextArea getResponseArea() {
        return responseArea;
    }

    public JTextArea getVariablesArea() {
        return variablesArea;
    }

    public JTextArea getAssertionsArea() {
        return assertionsArea;
    }

    public JTextArea getScriptArea() {
        return scriptArea;
    }

    public JTextArea getMetadataArea() {
        return metadataArea;
    }

    public JCheckBox getPinnedCheckBox() {
        return pinnedCheckBox;
    }

    public JTextArea getAnalystNotesArea() {
        return analystNotesArea;
    }

    public JTextField getTagsField() {
        return tagsField;
    }

    public JButton getSaveMetadataButton() {
        return saveMetadataButton;
    }

    public JLabel getEvidenceStatusLabel() {
        return evidenceStatusLabel;
    }

    public JTabbedPane getTabbedPane() {
        return tabs;
    }

    public boolean isRequestNativeViewerAvailable() {
        return requestNativeViewerAvailable;
    }

    public boolean isResponseNativeViewerAvailable() {
        return responseNativeViewerAvailable;
    }

    public String getRequestNativeViewerReason() {
        return requestNativeViewerReason;
    }

    public String getResponseNativeViewerReason() {
        return responseNativeViewerReason;
    }

    public HistoryEntry getCurrentEntry() {
        return currentEntry;
    }

    public String getTagsText() {
        return tagsField.getText();
    }

    public String getAnalystNotesText() {
        return analystNotesArea.getText();
    }

    private static void configureTextArea(JTextArea area) {
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setLineWrap(false);
        area.setWrapStyleWord(false);
    }

    private void configureEvidenceEditor() {
        evidencePanel.setBorder(new EmptyBorder(8, 8, 8, 8));
        analystNotesArea.setEditable(true);
        analystNotesArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        analystNotesArea.setLineWrap(true);
        analystNotesArea.setWrapStyleWord(true);
        analystNotesScrollPane.setPreferredSize(new Dimension(360, 180));
        evidenceStatusLabel.setBorder(new EmptyBorder(0, 0, 4, 0));
        tagsField.setToolTipText("Comma-separated analyst tags");
        saveMetadataButton.addActionListener(e -> {
            if (metadataSaveAction != null) {
                metadataSaveAction.run();
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 0, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 0;
        gbc.gridy = 0;

        gbc.gridx = 0;
        form.add(new JLabel("Pinned:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        form.add(pinnedCheckBox, gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        gbc.weightx = 0;
        form.add(new JLabel("Tags:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        form.add(tagsField, gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        form.add(new JLabel("Analyst Notes:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        form.add(analystNotesScrollPane, gbc);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(evidenceStatusLabel, BorderLayout.CENTER);
        footer.add(saveMetadataButton, BorderLayout.EAST);

        evidencePanel.add(form, BorderLayout.CENTER);
        evidencePanel.add(footer, BorderLayout.SOUTH);
    }

    private static String joinTags(java.util.Collection<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        return String.join(", ", new LinkedHashSet<>(tags));
    }

    private static JScrollPane createFallbackScrollPane(JTextArea area) {
        configureTextArea(area);
        return new JScrollPane(area);
    }

    private static NativeRequestViewer createRequestViewer(MontoyaApi api) {
        if (api == null) {
            return new NativeRequestViewer(null, null, false, "Montoya API unavailable");
        }
        try {
            HttpRequestEditor editor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
            Component component = editor != null ? editor.uiComponent() : null;
            if (editor == null || component == null) {
                return new NativeRequestViewer(null, null, false, "Burp request viewer unavailable");
            }
            return new NativeRequestViewer(component, editor, true, null);
        } catch (Throwable t) {
            return new NativeRequestViewer(null, null, false, viewerReason(t));
        }
    }

    private static NativeResponseViewer createResponseViewer(MontoyaApi api) {
        if (api == null) {
            return new NativeResponseViewer(null, null, false, "Montoya API unavailable");
        }
        try {
            HttpResponseEditor editor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
            Component component = editor != null ? editor.uiComponent() : null;
            if (editor == null || component == null) {
                return new NativeResponseViewer(null, null, false, "Burp response viewer unavailable");
            }
            return new NativeResponseViewer(component, editor, true, null);
        } catch (Throwable t) {
            return new NativeResponseViewer(null, null, false, viewerReason(t));
        }
    }

    private static String viewerReason(Throwable t) {
        if (t == null) {
            return "Unknown viewer error";
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    private static String buildVariablesText(HistoryEntry entry) {
        if (entry != null && entry.variablesSummaryText != null && !entry.variablesSummaryText.isBlank()) {
            return entry.variablesSummaryText.trim();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Environment ID: ").append(entry.environmentId != null ? entry.environmentId : "").append('\n');
        sb.append("Environment Name: ").append(entry.environmentName != null ? entry.environmentName : "").append('\n');
        sb.append("Unresolved Variables: ").append(String.join(", ", entry.unresolvedVariables != null ? entry.unresolvedVariables : java.util.List.of())).append('\n');
        if (entry != null && entry.requestSnapshot != null && entry.requestSnapshot.resolvedVariables != null && !entry.requestSnapshot.resolvedVariables.isEmpty()) {
            sb.append('\n').append("Resolved Variables:").append('\n');
            entry.requestSnapshot.resolvedVariables.forEach((k, v) ->
                    sb.append(k).append(" = ").append(v != null ? v : "").append('\n'));
        }
        if (entry != null && entry.scriptVariableMutations != null && !entry.scriptVariableMutations.isEmpty()) {
            sb.append('\n').append("Variable Mutations:").append('\n');
            entry.scriptVariableMutations.forEach(mutation -> {
                if (mutation == null) {
                    return;
                }
                sb.append(mutation.scope != null ? mutation.scope : "")
                        .append(": ")
                        .append(mutation.key != null ? mutation.key : "")
                        .append(" old=").append(mutation.oldValue != null ? mutation.oldValue : "")
                        .append(" new=").append(mutation.newValue != null ? mutation.newValue : "")
                        .append(" persistent=").append(mutation.persistent)
                        .append(mutation.sourceScriptName != null ? " script=" + mutation.sourceScriptName : "")
                        .append('\n');
            });
        }
        sb.append('\n').append("Request Variables as Authored:").append('\n');
        if (entry.requestSnapshot != null && entry.requestSnapshot.requestVariablesAsAuthored != null) {
            if (entry.requestSnapshot.requestVariablesAsAuthored.isEmpty()) {
                sb.append("(none)");
            } else {
                entry.requestSnapshot.requestVariablesAsAuthored.forEach((k, v) ->
                        sb.append(k).append(" = ").append(v != null ? v : "").append('\n'));
            }
        } else {
            sb.append("(none)");
        }
        return sb.toString().trim();
    }

    private static String buildAssertionsText(HistoryEntry entry) {
        if (entry != null && entry.assertionsSummaryText != null && !entry.assertionsSummaryText.isBlank()) {
            return entry.assertionsSummaryText.trim();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Assertions:").append('\n');
        if (entry.assertions == null || entry.assertions.isEmpty()) {
            sb.append("(none)");
        } else {
            entry.assertions.forEach(assertion -> {
                if (assertion == null) {
                    return;
                }
                sb.append(assertion.passed ? "[PASS] " : "[FAIL] ")
                        .append(assertion.name != null ? assertion.name : "")
                        .append(" expected=").append(assertion.expected != null ? assertion.expected : "")
                        .append(" actual=").append(assertion.actual != null ? assertion.actual : "")
                        .append(assertion.message != null ? " message=" + assertion.message : "")
                        .append('\n');
            });
        }
        sb.append('\n').append("Extractions:").append('\n');
        if (entry.extractions == null || entry.extractions.isEmpty()) {
            sb.append("(none)");
        } else {
            entry.extractions.forEach(extraction -> {
                if (extraction == null) {
                    return;
                }
                sb.append(extraction.name != null ? extraction.name : "")
                        .append(" = ")
                        .append(extraction.value != null ? extraction.value : "")
                        .append(extraction.source != null ? " (source=" + extraction.source + ")" : "")
                        .append(extraction.message != null ? " " + extraction.message : "")
                        .append('\n');
            });
        }
        return sb.toString().trim();
    }

    private static String buildScriptText(HistoryEntry entry) {
        if (entry != null && entry.scriptOutputSummaryText != null && !entry.scriptOutputSummaryText.isBlank()) {
            return entry.scriptOutputSummaryText.trim();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Script Engine: ").append(entry != null && entry.scriptEngineName != null ? entry.scriptEngineName : "").append('\n');
        sb.append("Script Mode: ").append(entry != null && entry.scriptMode != null ? entry.scriptMode : "").append('\n');
        sb.append("Script Dialect: ").append(entry != null && entry.scriptDialect != null ? entry.scriptDialect : "").append('\n');
        sb.append("Execution Source: ").append(entry != null && entry.executionSource != null ? entry.executionSource : "").append('\n');
        sb.append("Flow Control: ").append(entry != null && entry.scriptFlowControl != null ? entry.scriptFlowControl.name() : "").append('\n');
        sb.append("Flow Message: ").append(entry != null && entry.scriptFlowMessage != null ? entry.scriptFlowMessage : "").append('\n');
        sb.append('\n').append("Logs:").append('\n');
        if (entry == null || entry.scriptLogs == null || entry.scriptLogs.isEmpty()) {
            sb.append("(none)");
        } else {
            entry.scriptLogs.forEach(log -> {
                if (log == null) {
                    return;
                }
                sb.append('[').append(log.level != null ? log.level.toUpperCase() : "INFO").append("] ")
                        .append(log.message != null ? log.message : "");
                if (log.scriptName != null && !log.scriptName.isBlank()) {
                    sb.append(" (script=").append(log.scriptName).append(')');
                }
                if (log.scriptId != null && !log.scriptId.isBlank()) {
                    sb.append(" (id=").append(log.scriptId).append(')');
                }
                sb.append('\n');
            });
        }

        sb.append('\n').append("Warnings:").append('\n');
        if (entry == null || entry.scriptWarnings == null || entry.scriptWarnings.isEmpty()) {
            sb.append("(none)");
        } else {
            entry.scriptWarnings.forEach(warning -> sb.append(warning != null ? warning : "").append('\n'));
        }

        sb.append('\n').append("Errors:").append('\n');
        if (entry == null || entry.scriptErrors == null || entry.scriptErrors.isEmpty()) {
            sb.append("(none)");
        } else {
            entry.scriptErrors.forEach(error -> sb.append(error != null ? error : "").append('\n'));
        }

        sb.append('\n').append("Variable Mutations:").append('\n');
        if (entry == null || entry.scriptVariableMutations == null || entry.scriptVariableMutations.isEmpty()) {
            sb.append("(none)");
        } else {
            entry.scriptVariableMutations.forEach(mutation -> {
                if (mutation == null) {
                    return;
                }
                sb.append(mutation.scope != null ? mutation.scope : "")
                        .append(": ")
                        .append(mutation.key != null ? mutation.key : "")
                        .append(" old=").append(mutation.oldValue != null ? mutation.oldValue : "")
                        .append(" new=").append(mutation.newValue != null ? mutation.newValue : "")
                        .append(" persistent=").append(mutation.persistent)
                        .append(mutation.sourceScriptName != null ? " script=" + mutation.sourceScriptName : "")
                        .append('\n');
            });
        }
        return sb.toString().trim();
    }

    private record NativeRequestViewer(Component component,
                                       HttpRequestEditor editor,
                                       boolean nativeAvailable,
                                       String reason) {
    }

    private record NativeResponseViewer(Component component,
                                        HttpResponseEditor editor,
                                        boolean nativeAvailable,
                                        String reason) {
    }
}
