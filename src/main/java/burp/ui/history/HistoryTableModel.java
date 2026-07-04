package burp.ui.history;

import burp.history.HistoryEntry;

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

    private final List<HistoryEntry> entries = new ArrayList<>();

    public void setEntries(List<HistoryEntry> nextEntries) {
        entries.clear();
        if (nextEntries != null) {
            entries.addAll(nextEntries);
        }
        fireTableDataChanged();
    }

    public HistoryEntry getEntryAt(int row) {
        if (row < 0 || row >= entries.size()) {
            return null;
        }
        return entries.get(row);
    }

    public int indexOfEntryId(String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            HistoryEntry entry = entries.get(i);
            if (entry != null && id.equals(entry.id)) {
                return i;
            }
        }
        return -1;
    }

    public List<HistoryEntry> getEntries() {
        return new ArrayList<>(entries);
    }

    @Override
    public int getRowCount() {
        return entries.size();
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
        HistoryEntry entry = getEntryAt(rowIndex);
        if (entry == null) {
            return "";
        }
        return switch (columnIndex) {
            case 0 -> entry.pinned ? "★" : "";
            case 1 -> entry.timestamp != null ? TIME.format(entry.timestamp) : "";
            case 2 -> entry.source != null ? entry.source.displayName() : "";
            case 3 -> entry.attemptDisplay();
            case 4 -> entry.collectionName != null ? entry.collectionName : "";
            case 5 -> entry.folderPath != null ? entry.folderPath : "";
            case 6 -> entry.requestName != null ? entry.requestName : "";
            case 7 -> entry.requestSnapshot != null && entry.requestSnapshot.method != null ? entry.requestSnapshot.method : "";
            case 8 -> entry.requestSnapshot != null && entry.requestSnapshot.urlTemplate != null ? entry.requestSnapshot.urlTemplate : "";
            case 9 -> entry.statusCode > 0 ? String.valueOf(entry.statusCode) : (entry.hasError() ? "ERR" : "");
            case 10 -> entry.durationMillis > 0 ? entry.durationMillis + "ms" : "";
            case 11 -> entry.historySizeLabel();
            case 12 -> entry.environmentName != null ? entry.environmentName : "";
            case 13 -> entry.resultDisplayName();
            default -> "";
        };
    }
}
