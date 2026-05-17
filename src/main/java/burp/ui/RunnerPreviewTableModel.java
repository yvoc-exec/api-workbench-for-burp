package burp.ui;

import burp.models.RunnerPreviewRow;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public class RunnerPreviewTableModel extends AbstractTableModel {
    private final List<RunnerPreviewRow> rows = new ArrayList<>();
    private final String[] columns = {"#", "Collection", "Method", "URL Preview", "Unresolved Vars", "Auth"};

    public void setRows(List<RunnerPreviewRow> previewRows) {
        rows.clear();
        if (previewRows != null) {
            rows.addAll(previewRows);
        }
        fireTableDataChanged();
    }

    public RunnerPreviewRow getRowAt(int row) {
        return rows.get(row);
    }

    public List<RunnerPreviewRow> getRows() {
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
        return String.class;
    }

    @Override
    public Object getValueAt(int row, int column) {
        RunnerPreviewRow previewRow = rows.get(row);
        switch (column) {
            case 0:
                return previewRow.order > 0 ? previewRow.order : row + 1;
            case 1:
                return previewRow.collectionName != null ? previewRow.collectionName : "";
            case 2:
                return previewRow.method != null ? previewRow.method : "";
            case 3:
                return previewRow.urlPreview != null ? previewRow.urlPreview : "";
            case 4:
                if (previewRow.unresolvedVariables == null || previewRow.unresolvedVariables.isEmpty()) {
                    return "";
                }
                StringJoiner joiner = new StringJoiner(", ");
                for (String variable : previewRow.unresolvedVariables) {
                    if (variable != null && !variable.isEmpty()) {
                        joiner.add(variable);
                    }
                }
                return joiner.toString();
            case 5:
                return previewRow.authStatus != null ? previewRow.authStatus : "";
            default:
                return "";
        }
    }
}
