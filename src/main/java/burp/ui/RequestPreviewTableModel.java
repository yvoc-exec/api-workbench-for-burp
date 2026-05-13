package burp.ui;

import burp.models.ApiRequest;
import javax.swing.table.AbstractTableModel;
import java.util.*;

public class RequestPreviewTableModel extends AbstractTableModel {
    private final List<ApiRequest> requests = new ArrayList<>();
    private final List<Boolean> selected = new ArrayList<>();
    private final String[] columns = {"Select", "Name", "Method", "URL", "Auth", "Body", "Vars", "Source"};

    public void setRequests(List<ApiRequest> requests) {
        this.requests.clear();
        this.selected.clear();
        for (ApiRequest r : requests) {
            this.requests.add(r);
            this.selected.add(!r.disabled);
        }
        fireTableDataChanged();
    }

    public List<ApiRequest> getSelectedRequests() {
        List<ApiRequest> result = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            if (selected.get(i)) {
                result.add(requests.get(i));
            }
        }
        return result;
    }

    public void selectAll(boolean select) {
        for (int i = 0; i < selected.size(); i++) {
            selected.set(i, select);
        }
        fireTableDataChanged();
    }

    @Override
    public int getRowCount() { return requests.size(); }

    @Override
    public int getColumnCount() { return columns.length; }

    @Override
    public String getColumnName(int column) { return columns[column]; }

    @Override
    public Class<?> getColumnClass(int column) {
        if (column == 0) return Boolean.class;
        return String.class;
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return column == 0;
    }

    @Override
    public Object getValueAt(int row, int column) {
        ApiRequest req = requests.get(row);
        switch (column) {
            case 0: return selected.get(row);
            case 1: return req.name;
            case 2: return req.method;
            case 3: return req.url;
            case 4: return req.hasAuth() ? "Yes" : "No";
            case 5: return req.hasBody() ? "Yes" : "No";
            case 6: return req.variables.size() > 0 ? String.valueOf(req.variables.size()) : "";
            case 7: return req.sourceCollection != null ? req.sourceCollection : "";
            default: return "";
        }
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
        if (column == 0 && value instanceof Boolean) {
            selected.set(row, (Boolean) value);
        }
    }
}
