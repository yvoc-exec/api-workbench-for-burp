package burp.ui;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.FlowLayout;
import java.util.function.Supplier;

/**
 * Shared JTable behavior for the request editor's key/value style tables.
 */
final class RequestEditorTableSupport {

    private RequestEditorTableSupport() {
    }

    static JTable createEditableTable(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        SwingShortcutSupport.installTableShortcuts(table);
        return table;
    }

    static JPanel createAddRemovePanel(JTable table, DefaultTableModel model, Supplier<Object[]> newRowSupplier) {
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JButton addBtn = new JButton("+");
        addBtn.addActionListener(e -> {
            commitTableEdit(table);
            model.addRow(newRowSupplier.get());
        });

        JButton delBtn = new JButton("-");
        delBtn.addActionListener(e -> {
            commitTableEdit(table);
            int row = resolveTargetRow(table);
            if (row >= 0) {
                model.removeRow(row);
            }
            RequestEditorStateMapper.ensureStarterRow(model);
        });

        btnPanel.add(addBtn);
        btnPanel.add(delBtn);
        return btnPanel;
    }

    static void commitAllEdits(JTable... tables) {
        if (tables == null) {
            return;
        }
        for (JTable table : tables) {
            commitTableEdit(table);
        }
    }

    private static void commitTableEdit(JTable table) {
        if (table != null && table.isEditing() && table.getCellEditor() != null) {
            try {
                table.getCellEditor().stopCellEditing();
            } catch (Exception ignored) {
                try {
                    table.getCellEditor().cancelCellEditing();
                } catch (Exception ignored2) {
                    // no-op
                }
            }
        }
    }

    private static int resolveTargetRow(JTable table) {
        if (table == null) {
            return -1;
        }
        int row = table.getSelectedRow();
        if (row < 0 && table.isEditing()) {
            row = table.getEditingRow();
        }
        return row;
    }
}
