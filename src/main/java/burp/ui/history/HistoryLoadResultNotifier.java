package burp.ui.history;

import burp.history.HistoryEntry;
import burp.history.HistoryAdmissionResult;
import burp.history.HistoryRetentionStats;

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
                "Exported history may contain analyst notes, tags, raw requests, response evidence, hashes, and truncation metadata. Review before sharing.",
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

    public void showAddRejected(Component parent,
                                HistoryAdmissionResult result,
                                HistoryRetentionStats stats) {
        showAdmissionRejected(parent, "History evidence was not retained", result, stats);
    }

    public void showPinRejected(Component parent,
                                HistoryAdmissionResult result,
                                HistoryRetentionStats stats) {
        showAdmissionRejected(parent, "History pin change was not applied", result, stats);
    }

    public void showMetadataRejected(Component parent,
                                     HistoryAdmissionResult result,
                                     HistoryRetentionStats stats) {
        showAdmissionRejected(parent, "History evidence metadata was not saved", result, stats);
    }

    public void showLegacyPayloadCompacted(Component parent, HistoryRetentionStats stats) {
        HistoryRetentionStats safeStats = stats != null ? stats : HistoryRetentionStats.empty();
        JOptionPane.showMessageDialog(
                parent,
                "Legacy History payloads were compacted once to satisfy the configured hard retention budget. "
                        + "Compacted entries: " + safeStats.legacyCompactedEntryCount()
                        + ". Original lengths and SHA-256 metadata remain available.",
                "History retention migration",
                JOptionPane.WARNING_MESSAGE);
    }

    public static String admissionRejectionMessage(String operation,
                                                   HistoryAdmissionResult result,
                                                   HistoryRetentionStats stats) {
        String safeOperation = operation != null && !operation.isBlank()
                ? operation.replace('\r', ' ').replace('\n', ' ')
                : "History operation";
        HistoryRetentionStats safeStats = stats != null ? stats : HistoryRetentionStats.empty();
        String reason = result != null && result.rejectionReason() != null
                ? result.rejectionReason().name()
                : "POLICY_REJECTED";
        long bytes = result != null ? result.bytesAfter() : safeStats.canonicalRetainedBytes();
        long byteLimit = result != null ? result.configuredByteLimit() : 0L;
        int entries = safeStats.entryCount();
        int entryLimit = result != null ? result.configuredEntryLimit() : 0;
        return safeOperation + ". Reason: " + reason
                + ". Retained bytes: " + Math.max(0L, bytes) + "/" + Math.max(0L, byteLimit)
                + ". Retained entries: " + Math.max(0, entries) + "/" + Math.max(0, entryLimit)
                + ". Clear unpinned evidence, reduce incoming evidence size, or use a larger permitted retention policy.";
    }

    private void showAdmissionRejected(Component parent,
                                       String operation,
                                       HistoryAdmissionResult result,
                                       HistoryRetentionStats stats) {
        JOptionPane.showMessageDialog(
                parent,
                admissionRejectionMessage(operation, result, stats),
                "History retention",
                JOptionPane.WARNING_MESSAGE);
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
