package burp.ui.history;

import burp.history.*;
import burp.api.montoya.MontoyaApi;
import burp.ui.SwingShortcutSupport;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public class HistoryPanel extends JPanel {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final HistoryStore historyStore;
    private final HistoryExportService exportService;
    private final HistoryDiffService diffService;
    private final HistoryLoadResultNotifier notifier;
    private final HistoryTableModel tableModel = new HistoryTableModel();
    private final JTable table = new JTable(tableModel);
    private final HistoryFilterPanel filterPanel = new HistoryFilterPanel();
    private final HistoryDetailPanel detailPanel;
    private final HistoryActionsPanel actionsPanel = new HistoryActionsPanel();
    private final JLabel usageLabel = new JLabel();
    private final List<HistoryEntry> visibleEntries = new ArrayList<>();
    private final JScrollPane tableScrollPane = new JScrollPane(table);
    private HistoryFilterCriteria currentCriteria = new HistoryFilterCriteria();
    private Consumer<HistoryEntry> loadInWorkbenchAction;
    private Consumer<HistoryEntry> replayFromHistoryAction;
    private Consumer<HistoryEntry> sendToRepeaterAction;
    private Runnable workspaceChangeListener;

    public HistoryPanel(HistoryStore historyStore,
                        HistoryExportService exportService,
                        HistoryDiffService diffService,
                        HistoryLoadResultNotifier notifier) {
        this(historyStore, exportService, diffService, notifier, null);
    }

    public HistoryPanel(HistoryStore historyStore,
                        HistoryExportService exportService,
                        HistoryDiffService diffService,
                        HistoryLoadResultNotifier notifier,
                        MontoyaApi api) {
        this.historyStore = historyStore != null ? historyStore : new HistoryStore();
        this.exportService = exportService != null ? exportService : new HistoryExportService();
        this.diffService = diffService != null ? diffService : new HistoryDiffService();
        this.notifier = notifier != null ? notifier : new HistoryLoadResultNotifier();
        this.detailPanel = new HistoryDetailPanel(api);
        setLayout(new BorderLayout(5, 5));
        setBorder(new EmptyBorder(5, 5, 5, 5));

        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildCenterPane(), BorderLayout.CENTER);
        installActions();
        installSelectionListener();
        detailPanel.setMetadataSaveAction(this::saveSelectedMetadata);
        filterPanel.setChangeListener(this::applyCurrentFilter);
        applyCurrentFilter();
    }

    public void setLoadInWorkbenchAction(Consumer<HistoryEntry> loadInWorkbenchAction) {
        this.loadInWorkbenchAction = loadInWorkbenchAction;
    }

    public void setReplayFromHistoryAction(Consumer<HistoryEntry> replayFromHistoryAction) {
        this.replayFromHistoryAction = replayFromHistoryAction;
    }

    public void setSendToRepeaterAction(Consumer<HistoryEntry> sendToRepeaterAction) {
        this.sendToRepeaterAction = sendToRepeaterAction;
    }

    public void setWorkspaceChangeListener(Runnable workspaceChangeListener) {
        this.workspaceChangeListener = workspaceChangeListener;
    }

    public HistoryStore getHistoryStore() {
        return historyStore;
    }

    public HistoryFilterPanel getFilterPanel() {
        return filterPanel;
    }

    public HistoryDetailPanel getDetailPanel() {
        return detailPanel;
    }

    public HistoryActionsPanel getActionsPanel() {
        return actionsPanel;
    }

    public JLabel getUsageLabel() {
        return usageLabel;
    }

    JScrollPane getTableScrollPane() {
        return tableScrollPane;
    }

    public JTable getHistoryTable() {
        return table;
    }

    public HistoryEntry getSelectedEntry() {
        List<HistoryEntry> selected = getSelectedEntries();
        return selected.isEmpty() ? null : selected.get(0);
    }

    public List<HistoryEntry> getSelectedEntries() {
        int[] rows = table.getSelectedRows();
        List<HistoryEntry> selected = new ArrayList<>();
        for (int row : rows) {
            HistoryEntry entry = tableModel.getEntryAt(table.convertRowIndexToModel(row));
            if (entry != null) {
                selected.add(entry);
            }
        }
        return selected;
    }

    public void refreshFromStore() {
        refreshFromStore((String) null);
    }

    public void refreshFromStore(String preferredSelectedId) {
        List<String> selectedIds = new ArrayList<>();
        if (preferredSelectedId != null && !preferredSelectedId.isBlank()) {
            selectedIds.add(preferredSelectedId);
        } else {
            for (HistoryEntry entry : getSelectedEntries()) {
                if (entry != null && entry.id != null) {
                    selectedIds.add(entry.id);
                }
            }
        }
        refreshFromStore(selectedIds);
    }

    private void refreshFromStore(List<String> preferredSelectedIds) {
        applyCurrentFilter(preferredSelectedIds);
    }

    public void applyCurrentFilter() {
        applyCurrentFilter(Collections.emptyList());
    }

    public void clearHistory() {
        if (!notifier.confirmClearHistory(this)) {
            return;
        }
        historyStore.clear();
        refreshFromStore();
        updateUsageBanner();
        notifyWorkspaceChanged();
    }

    public void deleteSelectedEntries() {
        List<HistoryEntry> selected = getSelectedEntries();
        if (selected.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (HistoryEntry entry : selected) {
            if (entry != null && entry.id != null) {
                ids.add(entry.id);
            }
        }
        historyStore.removeByIds(ids);
        refreshFromStore();
        updateUsageBanner();
        notifyWorkspaceChanged();
    }

    public void togglePinSelectedEntries() {
        List<HistoryEntry> selected = getSelectedEntries();
        if (selected.isEmpty()) {
            return;
        }
        boolean allPinned = selected.stream().allMatch(entry -> entry != null && entry.pinned);
        List<String> ids = new ArrayList<>();
        for (HistoryEntry entry : selected) {
            if (entry != null && entry.id != null) {
                ids.add(entry.id);
                historyStore.setPinned(entry.id, !allPinned);
            }
        }
        refreshFromStore(ids);
        updateUsageBanner();
        notifyWorkspaceChanged();
    }

    public void clearUnpinnedEntries() {
        int removed = historyStore.clearUnpinned();
        if (removed > 0) {
            refreshFromStore();
            updateUsageBanner();
            notifyWorkspaceChanged();
        }
    }

    public void saveSelectedMetadata() {
        HistoryEntry selected = getSelectedEntry();
        if (selected == null || selected.id == null) {
            return;
        }
        String entryId = selected.id;
        String notes = detailPanel.getAnalystNotesText();
        java.util.Collection<String> tags = HistoryBodyTruncator.normalizeTags(detailPanel.getTagsText());
        HistoryEntry updated = historyStore.updateEvidenceMetadata(
                entryId,
                detailPanel.getPinnedCheckBox().isSelected(),
                notes,
                tags
        );
        if (updated != null) {
            refreshFromStore(updated.id);
        } else {
            refreshFromStore();
        }
        updateUsageBanner();
        notifyWorkspaceChanged();
    }

    public void loadSelectedInWorkbench() {
        HistoryEntry entry = getSelectedEntry();
        if (entry == null || loadInWorkbenchAction == null) {
            return;
        }
        loadInWorkbenchAction.accept(entry);
    }

    public void replaySelectedFromHistory() {
        HistoryEntry entry = getSelectedEntry();
        if (entry == null || replayFromHistoryAction == null) {
            return;
        }
        replayFromHistoryAction.accept(entry);
    }

    public void sendSelectedToRepeater() {
        HistoryEntry entry = getSelectedEntry();
        if (entry == null || sendToRepeaterAction == null) {
            return;
        }
        sendToRepeaterAction.accept(entry);
    }

    public void copySelectedUrl() {
        HistoryEntry entry = getSelectedEntry();
        if (entry == null || entry.requestSnapshot == null || entry.requestSnapshot.urlTemplate == null) {
            return;
        }
        copyToClipboard(entry.requestSnapshot.urlTemplate);
    }

    public void copySelectedCurl() {
        HistoryEntry entry = getSelectedEntry();
        if (entry == null || entry.requestSnapshot == null) {
            return;
        }
        copyToClipboard(entry.requestSnapshot.toCurlCommand());
    }

    public void compareSelected() {
        List<HistoryEntry> selected = getSelectedEntries();
        if (selected.size() != 2) {
            return;
        }
        HistoryCompareDialog.showDialog(this, diffService, selected.get(0), selected.get(1));
    }

    public void exportSelectedJson() {
        exportEntries("json");
    }

    public void exportSelectedCsv() {
        exportEntries("csv");
    }

    public void exportSelectedHar() {
        exportEntries("har");
    }

    public void addHistoryEntry(HistoryEntry entry) {
        addHistoryEntry(entry, true);
    }

    public void addHistoryEntry(HistoryEntry entry, boolean selectInsertedEntry) {
        HistoryEntry stored = historyStore.addEntry(entry);
        refreshFromStore(selectInsertedEntry ? stored.id : null);
        notifyWorkspaceChanged();
    }

    public void setSelectionByEntryId(String entryId) {
        int row = tableModel.indexOfEntryId(entryId);
        if (row < 0) {
            return;
        }
        if (row < table.getRowCount()) {
            int viewRow = table.convertRowIndexToView(row);
            if (viewRow >= 0) {
                table.getSelectionModel().setSelectionInterval(viewRow, viewRow);
                table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
            }
        }
    }

    private JComponent buildTopPanel() {
        JPanel top = new JPanel(new BorderLayout(5, 5));
        top.add(actionsPanel, BorderLayout.NORTH);
        top.add(buildNotice(usageLabel), BorderLayout.CENTER);
        top.add(filterPanel, BorderLayout.SOUTH);
        return top;
    }

    private JSplitPane buildCenterPane() {
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(false);
        table.setFillsViewportHeight(true);
        table.setRowSelectionAllowed(true);
        table.setColumnSelectionAllowed(false);
        SwingShortcutSupport.installTableShortcuts(table);

        tableScrollPane.setPreferredSize(new Dimension(820, 280));
        tableScrollPane.setMinimumSize(new Dimension(460, 220));
        detailPanel.setPreferredSize(new Dimension(520, 220));
        detailPanel.setMinimumSize(new Dimension(320, 140));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScrollPane, detailPanel);
        splitPane.setResizeWeight(0.65);
        splitPane.setDividerLocation(0.65);
        splitPane.setOneTouchExpandable(true);
        splitPane.setContinuousLayout(true);
        splitPane.setDividerSize(8);
        return splitPane;
    }

    private void installSelectionListener() {
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            HistoryEntry selected = getSelectedEntry();
            detailPanel.showEntry(selected);
            updateActionState();
        });
    }

    private void installActions() {
        actionsPanel.setLoadAction(this::loadSelectedInWorkbench);
        actionsPanel.setReplayAction(this::replaySelectedFromHistory);
        actionsPanel.setRepeaterAction(this::sendSelectedToRepeater);
        actionsPanel.setCopyUrlAction(this::copySelectedUrl);
        actionsPanel.setCopyCurlAction(this::copySelectedCurl);
        actionsPanel.setCompareAction(this::compareSelected);
        actionsPanel.setDeleteAction(this::deleteSelectedEntries);
        actionsPanel.setClearAction(this::clearHistory);
        actionsPanel.setPinAction(this::togglePinSelectedEntries);
        actionsPanel.setClearUnpinnedAction(this::clearUnpinnedEntries);
        actionsPanel.setExportJsonAction(this::exportSelectedJson);
        actionsPanel.setExportCsvAction(this::exportSelectedCsv);
        actionsPanel.setExportHarAction(this::exportSelectedHar);
    }

    private void applyCurrentFilter(List<String> preferredSelectedIds) {
        currentCriteria = filterPanel.getCriteria();
        List<HistoryEntry> all = historyStore.snapshot();
        visibleEntries.clear();
        for (HistoryEntry entry : all) {
            if (currentCriteria == null || currentCriteria.matches(entry)) {
                visibleEntries.add(entry);
            }
        }
        tableModel.setEntries(visibleEntries);
        restoreSelection(preferredSelectedIds);
        HistoryEntry selected = getSelectedEntry();
        detailPanel.showEntry(selected);
        updateUsageBanner();
        updateActionState();
    }

    private void restoreSelection(List<String> preferredSelectedIds) {
        if (visibleEntries.isEmpty()) {
            table.clearSelection();
            return;
        }
        List<String> idsToSelect = new ArrayList<>();
        if (preferredSelectedIds != null) {
            for (String id : preferredSelectedIds) {
                if (id != null && tableModel.indexOfEntryId(id) >= 0 && !idsToSelect.contains(id)) {
                    idsToSelect.add(id);
                }
            }
        }
        if (idsToSelect.isEmpty()) {
            table.clearSelection();
            return;
        }
        table.clearSelection();
        int firstViewIndex = -1;
        for (String selectedId : idsToSelect) {
            int modelIndex = tableModel.indexOfEntryId(selectedId);
            if (modelIndex < 0) {
                continue;
            }
            int viewIndex = modelIndex;
            if (viewIndex >= 0 && viewIndex < table.getRowCount()) {
                if (firstViewIndex < 0) {
                    table.getSelectionModel().setSelectionInterval(viewIndex, viewIndex);
                    firstViewIndex = viewIndex;
                } else {
                    table.getSelectionModel().addSelectionInterval(viewIndex, viewIndex);
                }
            }
        }
        if (firstViewIndex >= 0) {
            table.scrollRectToVisible(table.getCellRect(firstViewIndex, 0, true));
        }
    }

    private void updateActionState() {
        List<HistoryEntry> selected = getSelectedEntries();
        boolean hasEntries = !historyStore.isEmpty();
        actionsPanel.updateSelectionState(table.getSelectedRowCount(), hasEntries);
        boolean allPinned = !selected.isEmpty() && selected.stream().allMatch(entry -> entry != null && entry.pinned);
        actionsPanel.updatePinActionState(selected.size(), allPinned);
    }

    private void updateUsageBanner() {
        HistoryRetentionPolicy policy = historyStore.getRetentionPolicy();
        HistoryRetentionStats stats = historyStore.getRetentionStats();
        usageLabel.setText(buildUsageText(policy, stats));
        usageLabel.setForeground(stats != null && stats.overBudget() ? new Color(156, 32, 0) : new Color(96, 64, 0));
    }

    private static String buildUsageText(HistoryRetentionPolicy policy, HistoryRetentionStats stats) {
        HistoryRetentionPolicy safePolicy = HistoryRetentionPolicy.copyOf(policy);
        safePolicy.normalize();
        HistoryRetentionStats safeStats = stats != null ? stats : HistoryRetentionStats.empty();
        return "History retention: "
                + safeStats.entryCount() + "/" + safePolicy.maxEntries + " entries; "
                + "stored " + formatBytes(safeStats.totalEstimatedBytes()) + "/" + formatBytes(safePolicy.maxTotalStoredBytes) + "; "
                + "request body limit " + formatBytes(safePolicy.maxRequestBodyBytesPerEntry) + "; "
                + "response body limit " + formatBytes(safePolicy.maxResponseBodyBytesPerEntry) + "; "
                + "pinned " + safeStats.pinnedCount() + "; "
                + "truncated " + safeStats.truncatedEntryCount() + "; "
                + "retain pinned " + (safePolicy.retainPinnedEntries ? "yes" : "no") + "; "
                + "over budget: " + (safeStats.overBudget() ? "yes" : "no");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int unitIndex = -1;
        while (value >= 1024.0d && unitIndex + 1 < units.length) {
            value /= 1024.0d;
            unitIndex++;
        }
        if (unitIndex < 0) {
            return bytes + " B";
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unitIndex]);
    }

    private void exportEntries(String format) {
        List<HistoryEntry> entries = getSelectedEntries();
        if (entries.isEmpty()) {
            entries = historyStore.snapshot();
        }
        if (entries.isEmpty()) {
            return;
        }
        if (!notifier.confirmExportSensitiveData(this)) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export History");
        String suffix = switch (format) {
            case "csv" -> ".csv";
            case "har" -> ".har";
            default -> ".history.json";
        };
        chooser.setSelectedFile(new File("api-workbench-history-" + FILE_TIME.format(java.time.Instant.now()) + suffix));
        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (file == null) {
            return;
        }
        List<HistoryEntry> detachedEntries = detachedHistoryEntries(entries);
        if (detachedEntries.isEmpty()) {
            return;
        }
        startHistoryExportWorker(format, detachedEntries, file.toPath());
    }

    SwingWorker<Path, Void> startHistoryExportWorker(String format, List<HistoryEntry> detachedEntries, Path path) {
        SwingWorker<Path, Void> worker = createHistoryExportWorker(format, detachedEntries, path);
        actionsPanel.getExportButton().setEnabled(false);
        worker.execute();
        return worker;
    }

    SwingWorker<Path, Void> createHistoryExportWorker(String format, List<HistoryEntry> detachedEntries, Path path) {
        List<HistoryEntry> workerEntries = detachedEntries != null ? new ArrayList<>(detachedEntries) : List.of();
        return new SwingWorker<>() {
            @Override
            protected Path doInBackground() throws Exception {
                switch (format) {
                    case "csv" -> exportService.writeCsv(workerEntries, path);
                    case "har" -> exportService.writeHar(workerEntries, path);
                    default -> exportService.writeJson(workerEntries, path);
                }
                return path;
            }

            @Override
            protected void done() {
                try {
                    Path exportedPath = get();
                    notifier.showInfo(HistoryPanel.this, "History exported to " + exportedPath.toAbsolutePath());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    notifier.showError(HistoryPanel.this, "History export failed: " + exceptionMessage(e));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    notifier.showError(HistoryPanel.this, "History export failed: " + exceptionMessage(cause));
                } finally {
                    updateActionState();
                }
            }
        };
    }

    private static List<HistoryEntry> detachedHistoryEntries(List<HistoryEntry> entries) {
        List<HistoryEntry> detached = new ArrayList<>();
        if (entries == null) {
            return detached;
        }
        for (HistoryEntry entry : entries) {
            HistoryEntry copy = HistoryEntry.copyOf(entry);
            if (copy != null) {
                detached.add(copy);
            }
        }
        return detached;
    }

    private static String exceptionMessage(Throwable throwable) {
        if (throwable == null) {
            return "Exception";
        }
        String message = throwable.getMessage();
        return message != null ? message : throwable.getClass().getSimpleName();
    }

    private void copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text != null ? text : ""), null);
        } catch (Exception ignored) {
            // Clipboard access is best-effort in headless or restricted environments.
        }
    }

    private void notifyWorkspaceChanged() {
        if (workspaceChangeListener != null) {
            workspaceChangeListener.run();
        }
    }

    private static JLabel buildNotice(JLabel label) {
        label.setText("");
        label.setForeground(new Color(96, 64, 0));
        return label;
    }
}
