package burp.ui.history;

import burp.history.*;

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
import java.util.function.Consumer;

public class HistoryPanel extends JPanel {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    private static final String HISTORY_NOTICE = "History keeps the latest 1000 entries; older entries are automatically removed. Stored history may contain sensitive request/response data.";

    private final HistoryStore historyStore;
    private final HistoryExportService exportService;
    private final HistoryDiffService diffService;
    private final HistoryLoadResultNotifier notifier;
    private final HistoryTableModel tableModel = new HistoryTableModel();
    private final JTable table = new JTable(tableModel);
    private final HistoryFilterPanel filterPanel = new HistoryFilterPanel();
    private final HistoryDetailPanel detailPanel = new HistoryDetailPanel();
    private final HistoryActionsPanel actionsPanel = new HistoryActionsPanel();
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
        this.historyStore = historyStore != null ? historyStore : new HistoryStore();
        this.exportService = exportService != null ? exportService : new HistoryExportService();
        this.diffService = diffService != null ? diffService : new HistoryDiffService();
        this.notifier = notifier != null ? notifier : new HistoryLoadResultNotifier();
        setLayout(new BorderLayout(5, 5));
        setBorder(new EmptyBorder(5, 5, 5, 5));

        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildCenterPane(), BorderLayout.CENTER);
        installActions();
        installSelectionListener();
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
        refreshFromStore(null);
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
        applyCurrentFilter(selectedIds);
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
        top.add(buildNotice(HISTORY_NOTICE), BorderLayout.CENTER);
        top.add(filterPanel, BorderLayout.SOUTH);
        return top;
    }

    private JSplitPane buildCenterPane() {
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(false);
        table.setFillsViewportHeight(true);
        table.setRowSelectionAllowed(true);
        table.setColumnSelectionAllowed(false);

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
            actionsPanel.updateSelectionState(table.getSelectedRowCount(), !historyStore.isEmpty());
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
        actionsPanel.updateSelectionState(table.getSelectedRowCount(), !historyStore.isEmpty());
    }

    private void restoreSelection(List<String> preferredSelectedIds) {
        if (visibleEntries.isEmpty()) {
            table.clearSelection();
            return;
        }
        String selectedId = null;
        if (preferredSelectedIds != null) {
            for (String id : preferredSelectedIds) {
                if (id != null && tableModel.indexOfEntryId(id) >= 0) {
                    selectedId = id;
                    break;
                }
            }
        }
        if (selectedId == null) {
            table.clearSelection();
            return;
        }
        int modelIndex = tableModel.indexOfEntryId(selectedId);
        if (modelIndex < 0) {
            return;
        }
        int viewIndex = modelIndex;
        if (viewIndex >= 0 && viewIndex < table.getRowCount()) {
            table.getSelectionModel().setSelectionInterval(viewIndex, viewIndex);
            table.scrollRectToVisible(table.getCellRect(viewIndex, 0, true));
        }
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
        try {
            Path path = file.toPath();
            switch (format) {
                case "csv" -> exportService.writeCsv(entries, path);
                case "har" -> exportService.writeHar(entries, path);
                default -> exportService.writeJson(entries, path);
            }
            notifier.showInfo(this, "History exported to " + path.toAbsolutePath());
        } catch (Exception e) {
            notifier.showError(this, "History export failed: " + e.getMessage());
        }
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

    private static JLabel buildNotice(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(96, 64, 0));
        return label;
    }
}
