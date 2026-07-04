package burp.ui.history;

import burp.history.HistoryEntry;
import burp.history.evidence.HistoryEvidenceBundleOptions;
import burp.history.evidence.HistoryEvidenceBundleService;

import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

public final class HistoryEvidenceBundleUiInstaller {
    private static final String INSTALLED_KEY = "apiWorkbench.historyEvidenceBundleInstalled";

    private final HistoryEvidenceBundleService service;
    private final Clock clock;
    private final String extensionVersion;

    public HistoryEvidenceBundleUiInstaller() {
        this(new HistoryEvidenceBundleService(), Clock.systemUTC(), "2.0.0");
    }

    HistoryEvidenceBundleUiInstaller(HistoryEvidenceBundleService service,
                                     Clock clock,
                                     String extensionVersion) {
        this.service = service != null ? service : new HistoryEvidenceBundleService();
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.extensionVersion = extensionVersion != null ? extensionVersion : "unknown";
    }

    public boolean install(Component root) {
        HistoryPanel panel = findHistoryPanel(root);
        if (panel == null || panel.getActionsPanel() == null) {
            return false;
        }
        if (Boolean.TRUE.equals(panel.getClientProperty(INSTALLED_KEY))) {
            return true;
        }
        panel.getActionsPanel().setExportEvidenceBundleAction(() -> exportFrom(panel));
        panel.putClientProperty(INSTALLED_KEY, Boolean.TRUE);
        return true;
    }

    private void exportFrom(HistoryPanel panel) {
        List<HistoryEntry> selected = panel.getSelectedEntries();
        List<HistoryEntry> entries;
        if (selected == null || selected.isEmpty()) {
            entries = panel.getHistoryStore().snapshot();
            if (entries.isEmpty()) {
                return;
            }
            if (!GraphicsEnvironment.isHeadless()) {
                int answer = JOptionPane.showConfirmDialog(panel,
                        "No History rows are selected. Export all " + entries.size() + " stored entries?",
                        "Export All History Evidence",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) {
                    return;
                }
            }
        } else {
            entries = selected;
        }

        EvidenceSelection selection = chooseDestination(panel, entries.size());
        if (selection == null) {
            return;
        }
        if (!GraphicsEnvironment.isHeadless()) {
            int confirmed = JOptionPane.showConfirmDialog(panel,
                    "The bundle may contain raw requests, responses, analyst notes, tags, hashes, cookies, and credentials.\n"
                            + "Redaction is " + (selection.redact ? "enabled" : "disabled") + ". Continue?",
                    "Confirm Sensitive Evidence Export",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (confirmed != JOptionPane.YES_OPTION) {
                return;
            }
        }

        List<HistoryEntry> detached = entries.stream().map(HistoryEntry::copyOf).toList();
        HistoryEvidenceBundleOptions options = new HistoryEvidenceBundleOptions(
                selection.destination,
                selection.redact,
                clock,
                extensionVersion);
        SwingWorker<HistoryEvidenceBundleService.ExportResult, Void> worker = new SwingWorker<>() {
            @Override
            protected HistoryEvidenceBundleService.ExportResult doInBackground() throws Exception {
                return service.export(detached, options);
            }

            @Override
            protected void done() {
                try {
                    HistoryEvidenceBundleService.ExportResult result = get();
                    if (!GraphicsEnvironment.isHeadless()) {
                        JOptionPane.showMessageDialog(panel,
                                "Exported " + result.entryCount() + " History entries to:\n" + result.destination()
                                        + "\nSHA-256: " + result.sha256(),
                                "Evidence Bundle Exported",
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception failure) {
                    Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                    if (!GraphicsEnvironment.isHeadless()) {
                        JOptionPane.showMessageDialog(panel,
                                "Evidence bundle export failed: " + safeMessage(cause),
                                "Export Failed",
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        };
        worker.execute();
    }

    private EvidenceSelection chooseDestination(Component parent, int entryCount) {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        JCheckBox redact = new JCheckBox("Redact common secrets", false);
        JPanel options = new JPanel(new BorderLayout(4, 4));
        options.add(new JLabel("Entries to export: " + entryCount), BorderLayout.NORTH);
        options.add(redact, BorderLayout.CENTER);
        int optionsResult = JOptionPane.showConfirmDialog(parent,
                options,
                "Evidence Bundle Options",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (optionsResult != JOptionPane.OK_OPTION) {
            return null;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export History Evidence Bundle");
        chooser.setFileFilter(new FileNameExtensionFilter("ZIP archive (*.zip)", "zip"));
        chooser.setSelectedFile(new java.io.File("api-workbench-evidence.zip"));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path destination = chooser.getSelectedFile().toPath();
        if (!destination.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            destination = destination.resolveSibling(destination.getFileName() + ".zip");
        }
        return new EvidenceSelection(destination, redact.isSelected());
    }

    private HistoryPanel findHistoryPanel(Component component) {
        if (component instanceof HistoryPanel panel) {
            return panel;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                HistoryPanel found = findHistoryPanel(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure != null ? failure.getMessage() : null;
        return message != null && !message.isBlank()
                ? message
                : failure != null ? failure.getClass().getSimpleName() : "Unknown error";
    }

    private record EvidenceSelection(Path destination, boolean redact) {
    }
}
