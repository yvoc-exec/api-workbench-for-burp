package burp.ui;

import burp.models.RunnerResult;
import javax.swing.table.AbstractTableModel;
import java.util.*;

public class RunnerResultTableModel extends AbstractTableModel {
    private final List<RunnerResult> results = new ArrayList<>();
    private final String[] columns = {"#", "Host", "Path", "Method", "Status", "Size", "Length", "Extracted Vars"};

    public void addResult(RunnerResult result) {
        results.add(result);
        fireTableRowsInserted(results.size() - 1, results.size() - 1);
    }

    public void clear() {
        results.clear();
        fireTableDataChanged();
    }

    public List<RunnerResult> getResults() {
        return new ArrayList<>(results);
    }

    public RunnerResult getResultAt(int row) {
        return results.get(row);
    }

    @Override
    public int getRowCount() { return results.size(); }

    @Override
    public int getColumnCount() { return columns.length; }

    @Override
    public String getColumnName(int column) { return columns[column]; }

    @Override
    public Object getValueAt(int row, int column) {
        RunnerResult r = results.get(row);
        switch (column) {
            case 0: return row + 1;
            case 1: return r.host != null ? r.host : "";
            case 2: return r.path != null ? r.path : "";
            case 3: return r.method != null ? r.method : "";
            case 4: return r.displayStatusLabel();
            case 5: return r.responseSize;
            case 6: return r.responseBodyLength;
            case 7: return r.extractedVariables.isEmpty() ? "" : String.valueOf(r.extractedVariables.size());
            default: return "";
        }
    }

    @Override
    public Class<?> getColumnClass(int column) {
        if (column == 0 || column == 5 || column == 6) return Integer.class;
        return String.class;
    }
}
