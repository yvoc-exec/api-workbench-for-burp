package burp.ui.history;

import burp.history.HistoryEntry;

import javax.swing.*;
import java.awt.*;

public class HistoryLoadResultNotifier {
    public boolean confirmReplaceCurrentRequest(Component parent) {
        return JOptionPane.showConfirmDialog(
                parent,
                "Loading this history entry will replace the current request contents. Continue?",
                "Load History Entry",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    public void showLoadedIntoOriginalRequest(Component parent, HistoryEntry entry) {
        JOptionPane.showMessageDialog(
                parent,
                "History request loaded into original request: " + describePath(entry),
                "History",
                JOptionPane.INFORMATION_MESSAGE);
    }

    public void showLoadedUnderHistoryReplays(Component parent, String requestName) {
        JOptionPane.showMessageDialog(
                parent,
                "History request loaded under History Replays: " + (requestName != null ? requestName : ""),
                "History",
                JOptionPane.INFORMATION_MESSAGE);
    }

    public boolean confirmClearHistory(Component parent) {
        return JOptionPane.showConfirmDialog(
                parent,
                "Clear all replay history? This cannot be undone.",
                "Clear History",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    public boolean confirmExportSensitiveData(Component parent) {
        return JOptionPane.showConfirmDialog(
                parent,
                "Exported history may contain raw requests, responses, tokens, cookies, and other sensitive evidence. Review before sharing.",
                "Export History",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    public void showError(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "History", JOptionPane.ERROR_MESSAGE);
    }

    public void showInfo(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "History", JOptionPane.INFORMATION_MESSAGE);
    }

    private String describePath(HistoryEntry entry) {
        if (entry == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (entry.collectionName != null && !entry.collectionName.isBlank()) {
            sb.append(entry.collectionName);
        }
        if (entry.folderPath != null && !entry.folderPath.isBlank()) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(entry.folderPath);
        }
        if (entry.requestName != null && !entry.requestName.isBlank()) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(entry.requestName);
        }
        return sb.toString();
    }
}
