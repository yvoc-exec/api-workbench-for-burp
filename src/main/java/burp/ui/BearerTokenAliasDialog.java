package burp.ui;

import burp.models.BearerTokenAliasCandidate;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class BearerTokenAliasDialog extends JDialog {
    public enum Action {
        BIND_SELECTED,
        SKIP
    }

    private final List<AliasRow> rows = new ArrayList<>();
    private final LinkedHashSet<String> selectedAliases = new LinkedHashSet<>();
    private Action action = Action.SKIP;

    public BearerTokenAliasDialog(Window owner, List<BearerTokenAliasCandidate> candidates) {
        super(owner, "Bind Bearer Token Variables", ModalityType.APPLICATION_MODAL);
        if (candidates != null) {
            for (BearerTokenAliasCandidate candidate : candidates) {
                rows.add(new AliasRow(candidate));
            }
        }
        buildUi();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                action = Action.SKIP;
            }
        });
    }

    public Action showDialog() {
        pack();
        setMinimumSize(new Dimension(760, 360));
        setLocationRelativeTo(getOwner());
        setVisible(true);
        return action;
    }

    public Set<String> getSelectedAliases() {
        return new LinkedHashSet<>(selectedAliases);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel message = new JLabel("<html>Token acquired. Some requests in this collection use bearer token variables that are not currently set.</html>");
        root.add(message, BorderLayout.NORTH);
        root.add(buildTable(), BorderLayout.CENTER);
        root.add(buildButtons(), BorderLayout.SOUTH);

        setContentPane(root);
    }

    private JComponent buildTable() {
        AliasTableModel model = new AliasTableModel(rows);
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.getColumnModel().getColumn(0).setMaxWidth(70);
        table.getColumnModel().getColumn(2).setMaxWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(320);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Bearer Token Variables"));
        return scrollPane;
    }

    private JComponent buildButtons() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton skipButton = new JButton("Skip");
        skipButton.addActionListener(e -> {
            action = Action.SKIP;
            dispose();
        });

        JButton bindButton = new JButton("Bind Selected");
        bindButton.addActionListener(e -> {
            selectedAliases.clear();
            for (AliasRow row : rows) {
                if (row.selected && row.candidate != null && row.candidate.alias != null) {
                    selectedAliases.add(row.candidate.alias);
                }
            }
            action = Action.BIND_SELECTED;
            dispose();
        });

        panel.add(skipButton);
        panel.add(bindButton);
        return panel;
    }

    private static final class AliasRow {
        private final BearerTokenAliasCandidate candidate;
        private boolean selected;

        private AliasRow(BearerTokenAliasCandidate candidate) {
            this.candidate = candidate;
            this.selected = candidate != null && candidate.defaultSelected;
        }
    }

    private static final class AliasTableModel extends AbstractTableModel {
        private final List<AliasRow> rows;

        private AliasTableModel(List<AliasRow> rows) {
            this.rows = rows != null ? rows : List.of();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Bind";
                case 1 -> "Variable";
                case 2 -> "Requests";
                case 3 -> "Overwrite Status";
                default -> "";
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            AliasRow row = rows.get(rowIndex);
            BearerTokenAliasCandidate candidate = row.candidate;
            return switch (columnIndex) {
                case 0 -> row.selected;
                case 1 -> candidate != null ? candidate.alias : "";
                case 2 -> candidate != null ? candidate.requestCount : 0;
                case 3 -> candidate != null ? candidate.overwriteStatus : "";
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex != 0 || rowIndex < 0 || rowIndex >= rows.size()) {
                return;
            }
            rows.get(rowIndex).selected = aValue instanceof Boolean && (Boolean) aValue;
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}
