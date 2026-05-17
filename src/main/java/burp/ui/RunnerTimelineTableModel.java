package burp.ui;

import burp.models.RunnerTimelineRow;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

public class RunnerTimelineTableModel extends AbstractTableModel {
    private final List<RunnerTimelineRow> rows = new ArrayList<>();
    private final String[] columns = {"#", "Collection", "Request", "Status", "Time", "Retries", "Vars changed", "Assertions"};

    public void addRow(RunnerTimelineRow row) {
        rows.add(row);
        fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
    }

    public void clear() {
        rows.clear();
        fireTableDataChanged();
    }

    public RunnerTimelineRow getRowAt(int row) {
        return rows.get(row);
    }

    public List<RunnerTimelineRow> getRows() {
        return new ArrayList<>(rows);
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public String getColumnName(int column) {
        return columns[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        switch (column) {
            case 0:
            case 5:
            case 6:
                return Integer.class;
            case 4:
                return Long.class;
            default:
                return String.class;
        }
    }

    @Override
    public Object getValueAt(int row, int column) {
        RunnerTimelineRow timelineRow = rows.get(row);
        switch (column) {
            case 0:
                return timelineRow.order > 0 ? timelineRow.order : row + 1;
            case 1:
                return timelineRow.collectionName != null ? timelineRow.collectionName : "";
            case 2:
                return timelineRow.requestName != null ? timelineRow.requestName : "";
            case 3:
                return timelineRow.status != null ? timelineRow.status : "";
            case 4:
                return timelineRow.timeMs;
            case 5:
                return timelineRow.retries;
            case 6:
                return timelineRow.varsChanged;
            case 7:
                return timelineRow.assertions != null ? timelineRow.assertions : "";
            default:
                return "";
        }
    }
}
