package burp.ui.history;

import burp.history.HistoryEntry;

import javax.swing.*;
import java.awt.*;

public class HistoryDetailPanel extends JPanel {
    private final JTextArea requestArea = new JTextArea();
    private final JTextArea responseArea = new JTextArea();
    private final JTextArea variablesArea = new JTextArea();
    private final JTextArea assertionsArea = new JTextArea();
    private final JTextArea metadataArea = new JTextArea();

    public HistoryDetailPanel() {
        setLayout(new BorderLayout());
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Request", createTab(requestArea));
        tabs.addTab("Response", createTab(responseArea));
        tabs.addTab("Variables / Environment", createTab(variablesArea));
        tabs.addTab("Assertions / Extraction", createTab(assertionsArea));
        tabs.addTab("Metadata", createTab(metadataArea));
        add(tabs, BorderLayout.CENTER);
        clear();
    }

    public void showEntry(HistoryEntry entry) {
        if (entry == null) {
            clear();
            return;
        }
        requestArea.setText(entry.requestSnapshot != null ? entry.requestSnapshot.describe() : "");
        responseArea.setText(entry.responseSnapshot != null ? entry.responseSnapshot.describe() : "");
        variablesArea.setText(buildVariablesText(entry));
        assertionsArea.setText(buildAssertionsText(entry));
        metadataArea.setText(entry.toMetadataText());
        requestArea.setCaretPosition(0);
        responseArea.setCaretPosition(0);
        variablesArea.setCaretPosition(0);
        assertionsArea.setCaretPosition(0);
        metadataArea.setCaretPosition(0);
    }

    public void clear() {
        requestArea.setText("");
        responseArea.setText("");
        variablesArea.setText("");
        assertionsArea.setText("");
        metadataArea.setText("");
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

    public JTextArea getMetadataArea() {
        return metadataArea;
    }

    private static JScrollPane createTab(JTextArea area) {
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setLineWrap(false);
        area.setWrapStyleWord(false);
        return new JScrollPane(area);
    }

    private static String buildVariablesText(HistoryEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("Environment ID: ").append(entry.environmentId != null ? entry.environmentId : "").append('\n');
        sb.append("Environment Name: ").append(entry.environmentName != null ? entry.environmentName : "").append('\n');
        sb.append("Unresolved Variables: ").append(String.join(", ", entry.unresolvedVariables != null ? entry.unresolvedVariables : java.util.List.of())).append('\n');
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
}
