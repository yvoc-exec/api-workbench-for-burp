package burp.ui;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;

/**
 * Displays HTTP response with status summary, headers, and body in multiple views.
 */
public class ResponsePane extends JPanel {
    private JLabel statusLabel;
    private JTabbedPane tabs;
    private JTextArea headersArea;
    private JTextArea prettyBodyArea;
    private JTextArea rawBodyArea;
    private JTextArea hexBodyArea;

    public ResponsePane() {
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createTitledBorder("Response"));
        add(createStatusBar(), BorderLayout.NORTH);
        add(createBodyTabs(), BorderLayout.CENTER);
    }

    private JPanel createStatusBar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 2));
        statusLabel = new JLabel("No response yet");
        statusLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        panel.add(statusLabel);
        return panel;
    }

    private JTabbedPane createBodyTabs() {
        tabs = new JTabbedPane();

        headersArea = new JTextArea();
        headersArea.setEditable(false);
        headersArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tabs.addTab("Headers", new JScrollPane(headersArea));

        prettyBodyArea = new JTextArea();
        prettyBodyArea.setEditable(false);
        prettyBodyArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tabs.addTab("Pretty", new JScrollPane(prettyBodyArea));

        rawBodyArea = new JTextArea();
        rawBodyArea.setEditable(false);
        rawBodyArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tabs.addTab("Raw", new JScrollPane(rawBodyArea));

        hexBodyArea = new JTextArea();
        hexBodyArea.setEditable(false);
        hexBodyArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tabs.addTab("Hex", new JScrollPane(hexBodyArea));

        return tabs;
    }

    public void clear() {
        statusLabel.setText("No response yet");
        headersArea.setText("");
        prettyBodyArea.setText("");
        rawBodyArea.setText("");
        hexBodyArea.setText("");
    }

    public void displayResponse(int statusCode, long timeMs, int sizeBytes,
                                 String responseHeaders, byte[] responseBody) {
        statusLabel.setText(String.format("Status: %d | Time: %d ms | Size: %d bytes",
                statusCode, timeMs, sizeBytes));
        headersArea.setText(responseHeaders != null ? responseHeaders : "");
        headersArea.setCaretPosition(0);

        String bodyText = responseBody != null ? new String(responseBody, StandardCharsets.UTF_8) : "";
        rawBodyArea.setText(bodyText);
        rawBodyArea.setCaretPosition(0);

        prettyBodyArea.setText(tryPrettyPrint(bodyText));
        prettyBodyArea.setCaretPosition(0);

        hexBodyArea.setText(bytesToHex(responseBody));
        hexBodyArea.setCaretPosition(0);
    }

    private String tryPrettyPrint(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(trimmed);
                return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(el);
            } catch (Exception e) {
                return text;
            }
        }
        if (trimmed.startsWith("<") && trimmed.contains(">")) {
            // Simple XML indentation attempt
            return text.replace("><", ">\n<").replace(">", ">\n").replace("<", "\n<").trim();
        }
        return text;
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder hex = new StringBuilder();
        StringBuilder ascii = new StringBuilder();
        int len = Math.min(bytes.length, 32768); // cap to avoid UI freeze
        for (int i = 0; i < len; i++) {
            if (i > 0 && i % 16 == 0) {
                hex.append("  ").append(ascii).append("\n");
                ascii.setLength(0);
            }
            int v = bytes[i] & 0xFF;
            hex.append(String.format("%02X ", v));
            ascii.append(v >= 32 && v < 127 ? (char) v : '.');
        }
        int remainder = len % 16;
        if (remainder > 0) {
            for (int i = 0; i < (16 - remainder); i++) hex.append("   ");
            hex.append("  ").append(ascii);
        }
        if (bytes.length > len) {
            hex.append("\n... (truncated, total ").append(bytes.length).append(" bytes)");
        }
        return hex.toString();
    }
}
