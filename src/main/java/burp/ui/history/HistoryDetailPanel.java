package burp.ui.history;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.history.HistoryEntry;

import javax.swing.*;
import java.awt.*;

public class HistoryDetailPanel extends JPanel {
    private static final String NATIVE_CARD = "native";
    private static final String FALLBACK_CARD = "fallback";

    private final JTextArea requestArea = new JTextArea();
    private final JTextArea responseArea = new JTextArea();
    private final JTextArea variablesArea = new JTextArea();
    private final JTextArea assertionsArea = new JTextArea();
    private final JTextArea metadataArea = new JTextArea();
    private final CardLayout requestCardLayout = new CardLayout();
    private final JPanel requestCardPanel = new JPanel(requestCardLayout);
    private final CardLayout responseCardLayout = new CardLayout();
    private final JPanel responseCardPanel = new JPanel(responseCardLayout);
    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;
    private final boolean requestNativeViewerAvailable;
    private final boolean responseNativeViewerAvailable;
    private String requestNativeViewerReason;
    private String responseNativeViewerReason;

    public HistoryDetailPanel() {
        this(null);
    }

    public HistoryDetailPanel(MontoyaApi api) {
        setLayout(new BorderLayout());
        JTabbedPane tabs = new JTabbedPane();

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
        configureTextArea(metadataArea);

        tabs.addTab("Request", requestCardPanel);
        tabs.addTab("Response", responseCardPanel);
        tabs.addTab("Variables / Environment", createFallbackScrollPane(variablesArea));
        tabs.addTab("Assertions / Extraction", createFallbackScrollPane(assertionsArea));
        tabs.addTab("Metadata", createFallbackScrollPane(metadataArea));
        add(tabs, BorderLayout.CENTER);
        clear();
    }

    public void showEntry(HistoryEntry entry) {
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
        metadataArea.setText(entry.toMetadataText());
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

    public JTextArea getMetadataArea() {
        return metadataArea;
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

    private static void configureTextArea(JTextArea area) {
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setLineWrap(false);
        area.setWrapStyleWord(false);
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
