package burp.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpResponseEditor;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;

/**
 * Displays HTTP response with status summary and Burp native response viewer.
 */
public class ResponsePane extends JPanel {
    private final MontoyaApi api;
    private JLabel statusLabel;
    private HttpResponseEditor responseEditor;
    private JTextArea fallbackArea;

    public ResponsePane(MontoyaApi api) {
        this.api = api;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createTitledBorder("Response"));
        add(createStatusBar(), BorderLayout.NORTH);
        add(createBodyView(), BorderLayout.CENTER);
    }

    private JPanel createStatusBar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 2));
        statusLabel = new JLabel("No response yet");
        statusLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        panel.add(statusLabel);
        return panel;
    }

    private Component createBodyView() {
        if (api != null) {
            responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
            return responseEditor.uiComponent();
        }
        fallbackArea = new JTextArea();
        fallbackArea.setEditable(false);
        fallbackArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return new JScrollPane(fallbackArea);
    }

    public void clear() {
        statusLabel.setText("No response yet");
        if (responseEditor != null) {
            responseEditor.setResponse(HttpResponse.httpResponse());
        } else if (fallbackArea != null) {
            fallbackArea.setText("");
        }
    }

    public void displayResponse(HttpResponse response, long timeMs) {
        if (response == null) {
            clear();
            return;
        }
        int sizeBytes = response.body() != null ? response.body().length() : 0;
        statusLabel.setText(String.format("Status: %d | Time: %d ms | Size: %d bytes",
            (int) response.statusCode(), timeMs, sizeBytes));

        if (responseEditor != null) {
            responseEditor.setResponse(response);
        } else if (fallbackArea != null) {
            fallbackArea.setText(response.toString());
            fallbackArea.setCaretPosition(0);
        }
    }

    public void displayResponse(int statusCode, long timeMs, int sizeBytes,
                                 String responseHeaders, byte[] responseBody) {
        statusLabel.setText(String.format("Status: %d | Time: %d ms | Size: %d bytes",
            statusCode, timeMs, sizeBytes));
        String headerBlock = normalizeHeaders(responseHeaders, statusCode);
        String bodyText = responseBody != null ? new String(responseBody, StandardCharsets.UTF_8) : "";
        String raw = headerBlock + "\r\n\r\n" + bodyText;
        if (responseEditor != null) {
            responseEditor.setResponse(HttpResponse.httpResponse(raw));
        } else if (fallbackArea != null) {
            fallbackArea.setText(raw);
            fallbackArea.setCaretPosition(0);
        }
    }

    private String normalizeHeaders(String responseHeaders, int statusCode) {
        String raw = responseHeaders == null ? "" : responseHeaders.trim();
        if (raw.isEmpty()) {
            raw = "HTTP/1.1 " + statusCode;
        }
        raw = raw.replace("\r\n", "\n");
        if (!raw.toUpperCase().startsWith("HTTP/")) {
            raw = "HTTP/1.1 " + statusCode + "\n" + raw;
        }
        return raw.replace("\n", "\r\n");
    }
}
