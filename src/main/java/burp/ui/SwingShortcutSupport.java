package burp.ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Small Swing shortcut helper used across the workbench, history, and diagnostics tabs.
 */
public final class SwingShortcutSupport {
    private SwingShortcutSupport() {
    }

    public static void installTextComponentShortcuts(JTextComponent component) {
        if (component == null) {
            return;
        }
        int mask = menuShortcutMask();
        InputMap inputMap = component.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = component.getActionMap();

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, mask), "awb.copy");
        actionMap.put("awb.copy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                component.copy();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, mask), "awb.selectAll");
        actionMap.put("awb.selectAll", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                component.selectAll();
            }
        });

        if (component.isEditable() && component.isEnabled()) {
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, mask), "awb.paste");
            actionMap.put("awb.paste", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    component.paste();
                }
            });
        }
    }

    public static void installTableShortcuts(JTable table) {
        if (table == null) {
            return;
        }
        int mask = menuShortcutMask();
        InputMap inputMap = table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actionMap = table.getActionMap();

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, mask), "awb.copyTable");
        actionMap.put("awb.copyTable", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copyTableSelection(table);
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, mask), "awb.selectAllTable");
        actionMap.put("awb.selectAllTable", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectAllTableRows(table);
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, mask), "awb.pasteTable");
        actionMap.put("awb.pasteTable", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pasteIntoTable(table);
            }
        });
    }

    public static void copyTextToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text != null ? text : ""), null);
        } catch (Exception ignored) {
            // best effort only
        }
    }

    public static void copyTableSelection(JTable table) {
        if (table == null) {
            return;
        }
        int[] rows = table.getSelectedRows();
        int[] cols = table.getSelectedColumns();
        if (rows == null || rows.length == 0) {
            return;
        }
        if (cols == null || cols.length == 0) {
            cols = new int[table.getColumnCount()];
            for (int i = 0; i < cols.length; i++) {
                cols[i] = i;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.length; r++) {
            if (r > 0) {
                sb.append('\n');
            }
            int modelRow = table.convertRowIndexToModel(rows[r]);
            for (int c = 0; c < cols.length; c++) {
                if (c > 0) {
                    sb.append('\t');
                }
                int modelCol = table.convertColumnIndexToModel(cols[c]);
                Object value = table.getModel().getValueAt(modelRow, modelCol);
                sb.append(value != null ? value : "");
            }
        }
        copyTextToClipboard(sb.toString());
    }

    public static void selectAllTableRows(JTable table) {
        if (table == null || table.getRowCount() <= 0) {
            return;
        }
        table.clearSelection();
        table.setRowSelectionInterval(0, table.getRowCount() - 1);
        if (table.getColumnCount() > 0) {
            table.setColumnSelectionInterval(0, table.getColumnCount() - 1);
        }
    }

    public static void pasteIntoTable(JTable table) {
        if (table == null || !table.isEnabled()) {
            return;
        }
        if (!(table.getModel() instanceof DefaultTableModel model)) {
            return;
        }
        String clipboard = getClipboardText();
        if (clipboard == null || clipboard.isBlank()) {
            return;
        }
        if (table.isEditing()) {
            try {
                table.getCellEditor().stopCellEditing();
            } catch (Exception ignored) {
                // continue with model write
            }
        }
        int startRow = Math.max(0, table.getSelectedRow());
        int startCol = Math.max(0, table.getSelectedColumn());
        String[] lines = clipboard.split("\\R", -1);
        int rowIndex = startRow;
        for (String line : lines) {
            String[] values = line.split("\\t", -1);
            ensureRow(model, rowIndex);
            for (int i = 0; i < values.length; i++) {
                int colIndex = startCol + i;
                if (colIndex >= model.getColumnCount()) {
                    break;
                }
                model.setValueAt(values[i], rowIndex, colIndex);
            }
            rowIndex++;
        }
    }

    private static void ensureRow(DefaultTableModel model, int rowIndex) {
        while (model.getRowCount() <= rowIndex) {
            Object[] row = new Object[model.getColumnCount()];
            for (int i = 0; i < row.length; i++) {
                row[i] = "";
            }
            model.addRow(row);
        }
    }

    private static String getClipboardText() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            return data != null ? data.toString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int menuShortcutMask() {
        try {
            return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (Exception e) {
            return InputEvent.CTRL_DOWN_MASK;
        }
    }
}
