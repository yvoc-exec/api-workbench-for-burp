package burp.ui;

import burp.models.RunnerResult;
import burp.models.RunnerResultSummary;
import javax.swing.table.AbstractTableModel;
import java.util.*;

public class RunnerResultTableModel extends AbstractTableModel {
    private final List<RunnerResultSummary> summaries = new ArrayList<>();
    private final String[] columns = {"#", "Host", "Path", "Method", "Status", "Size", "Length", "Extracted Vars"};

    public void addResult(RunnerResult result) {
        addSummary(RunnerResultSummary.from(result));
    }

    public void addSummary(RunnerResultSummary summary) {
        if (summary == null) {
            return;
        }
        summaries.add(summary);
        fireTableRowsInserted(summaries.size() - 1, summaries.size() - 1);
    }

    public void clear() {
        summaries.clear();
        fireTableDataChanged();
    }

    public List<RunnerResult> getResults() {
        return summaries.stream().map(RunnerResultSummary::toCompatibilityResult).toList();
    }

    public RunnerResult getResultAt(int row) {
        return summaries.get(row).toCompatibilityResult();
    }

    public RunnerResultSummary getSummaryAt(int row) { return summaries.get(row); }

    public List<RunnerResultSummary> getSummaries() { return List.copyOf(summaries); }

    @Override
    public int getRowCount() { return summaries.size(); }

    @Override
    public int getColumnCount() { return columns.length; }

    @Override
    public String getColumnName(int column) { return columns[column]; }

    @Override
    public Object getValueAt(int row, int column) {
        RunnerResultSummary r = summaries.get(row);
        switch (column) {
            case 0: return row + 1;
            case 1: return r.host() != null ? r.host() : "";
            case 2: return r.path() != null ? r.path() : "";
            case 3: return r.method() != null ? r.method() : "";
            case 4: return r.displayStatusLabel();
            case 5: return r.responseSize();
            case 6: return r.responseBodyLength();
            case 7: return r.extractedVariableCount() == 0 ? "" : String.valueOf(r.extractedVariableCount());
            default: return "";
        }
    }

    @Override
    public Class<?> getColumnClass(int column) {
        if (column == 0 || column == 5 || column == 6) return Integer.class;
        return String.class;
    }
}
