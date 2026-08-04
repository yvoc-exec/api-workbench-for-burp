package burp.ui.history;

import burp.history.HistoryEntry;
import burp.history.HistoryEntrySummary;

import javax.swing.table.AbstractTableModel;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class HistoryTableModel extends AbstractTableModel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String[] COLUMNS = {
            "Pin", "Time", "Source", "Attempt", "Collection", "Folder", "Request",
            "Method", "URL Template", "Status", "Duration", "Size", "Environment", "Result"
    };

    private final List<HistoryEntrySummary> summaries = new ArrayList<>();

    public void setSummaries(List<HistoryEntrySummary> nextSummaries) {
        summaries.clear();
        if (nextSummaries != null) {
            summaries.addAll(nextSummaries);
        }
        fireTableDataChanged();
    }

    public HistoryEntrySummary getSummaryAt(int row) {
        if (row < 0 || row >= summaries.size()) {
            return null;
        }
        return summaries.get(row);
    }

    public int indexOfEntryId(String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        for (int i = 0; i < summaries.size(); i++) {
            HistoryEntrySummary summary = summaries.get(i);
            if (summary != null && id.equals(summary.id())) {
                return i;
            }
        }
        return -1;
    }

    public List<HistoryEntrySummary> getSummaries() {
        return List.copyOf(summaries);
    }

    /** Compatibility view for older callers; the model never retains these objects. */
    @Deprecated
    public List<HistoryEntry> getEntries() {
        List<HistoryEntry> rows = new ArrayList<>(summaries.size());
        for (HistoryEntrySummary summary : summaries) {
            HistoryEntry entry = new HistoryEntry();
            entry.id = summary.id();
            rows.add(entry);
        }
        return rows;
    }

    @Override
    public int getRowCount() {
        return summaries.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        HistoryEntrySummary summary = getSummaryAt(rowIndex);
        if (summary == null) {
            return "";
        }
        return switch (columnIndex) {
            case 0 -> summary.pinned() ? "★" : "";
            case 1 -> summary.timestamp() != null ? TIME.format(summary.timestamp()) : "";
            case 2 -> summary.source().displayName();
            case 3 -> summary.attemptDisplay();
            case 4 -> summary.collectionName();
            case 5 -> summary.folderPath();
            case 6 -> summary.requestName();
            case 7 -> summary.method();
            case 8 -> summary.urlTemplate();
            case 9 -> summary.statusCode() > 0 ? String.valueOf(summary.statusCode()) : (summary.hasError() ? "ERR" : "");
            case 10 -> summary.durationMillis() > 0 ? summary.durationMillis() + "ms" : "";
            case 11 -> summary.historySizeLabel();
            case 12 -> summary.environmentName();
            case 13 -> summary.resultDisplayName();
            default -> "";
        };
    }
}
