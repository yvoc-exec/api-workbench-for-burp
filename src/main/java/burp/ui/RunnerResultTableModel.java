package burp.ui;

import burp.models.RunnerResult;
import javax.swing.table.AbstractTableModel;
import java.util.*;

public class RunnerResultTableModel extends AbstractTableModel {
    private final List<RunnerResult> results = new ArrayList<>();
    private final String[] columns = {"#", "Name", "Status", "Time (ms)", "Size", "Assertions", "Extracted Vars"};

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
            case 1: return r.requestName;
            case 2: return r.success ? String.valueOf(r.statusCode) : "ERR";
            case 3: return r.responseTimeMs;
            case 4: return r.responseSize;
            case 5:
                long passed = r.assertions.stream().filter(a -> a.passed).count();
                return passed + "/" + r.assertions.size();
            case 6: return r.extractedVariables.isEmpty() ? "" : String.valueOf(r.extractedVariables.size());
            default: return "";
        }
    }

    @Override
    public Class<?> getColumnClass(int column) {
        if (column == 0 || column == 3 || column == 4) return Integer.class;
        return String.class;
    }
}
