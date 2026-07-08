package burp.ui;

import burp.models.RedirectCrossOriginMode;
import burp.models.RedirectPolicy;
import burp.models.TrustedRedirectRule;
import burp.utils.RedirectOrigin;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public final class RedirectPolicyDialog extends JDialog {
    private final JSpinner maxHopsSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 20, 1));
    private final JComboBox<RedirectCrossOriginMode> modeCombo = new JComboBox<>(RedirectCrossOriginMode.values());
    private final JTextField additionalSensitiveField = new JTextField(32);
    private final RulesTableModel rulesModel = new RulesTableModel();
    private RedirectPolicy result;

    private RedirectPolicyDialog(Window owner, RedirectPolicy initialPolicy) {
        super(owner, "Redirect Security Policy", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        RedirectPolicy policy = RedirectPolicy.copyOf(initialPolicy);
        if (policy == null) {
            policy = RedirectPolicy.defaults();
        }
        policy.normalize();

        maxHopsSpinner.setValue(policy.maxHops);
        modeCombo.setSelectedItem(policy.crossOriginMode);
        additionalSensitiveField.setText(String.join(", ", policy.additionalSensitiveHeaderNames != null ? policy.additionalSensitiveHeaderNames : List.of()));
        for (TrustedRedirectRule rule : policy.trustedRules) {
            rulesModel.rows.add(RuleRow.from(rule));
        }

        add(buildForm(), BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);
        pack();
        setMinimumSize(new Dimension(720, 420));
        setLocationRelativeTo(owner);
    }

    public static RedirectPolicy showDialog(Component parent, RedirectPolicy initialPolicy) {
        if (GraphicsEnvironment.isHeadless()) {
            RedirectPolicy copy = RedirectPolicy.copyOf(initialPolicy);
            if (copy == null) {
                copy = RedirectPolicy.defaults();
            }
            copy.normalize();
            return copy;
        }
        Window owner = DialogParentResolver.ownerFor(parent);
        RedirectPolicyDialog dialog = new RedirectPolicyDialog(owner, initialPolicy);
        dialog.setVisible(true);
        return dialog.result;
    }

    private JComponent buildForm() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        panel.add(new JLabel("Maximum redirects:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(maxHopsSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        panel.add(new JLabel("Cross-origin credentials:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(modeCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        panel.add(new JLabel("Additional sensitive headers:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(additionalSensitiveField, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 1.0; gbc.gridwidth = 2;
        JLabel warning = new JLabel("Dangerous mode never forwards Proxy-Authorization and only applies to HTTPS targets.");
        warning.setForeground(new Color(160, 64, 0));
        panel.add(warning, gbc);

        gbc.gridy = 4;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1.0;
        panel.add(buildRulesPanel(), gbc);
        return panel;
    }

    private JComponent buildRulesPanel() {
        JTable table = new JTable(rulesModel);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder("Trusted redirect rules"));

        JButton addRule = new JButton("Add Rule");
        addRule.addActionListener(e -> rulesModel.addBlankRule());
        JButton removeRule = new JButton("Remove Selected");
        removeRule.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                rulesModel.removeRow(row);
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        buttons.add(addRule);
        buttons.add(removeRule);

        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildButtons() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton save = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        save.addActionListener(e -> onSave());
        cancel.addActionListener(e -> {
            result = null;
            dispose();
        });
        panel.add(save);
        panel.add(cancel);
        return panel;
    }

    private void onSave() {
        RedirectPolicy policy = new RedirectPolicy();
        policy.maxHops = (Integer) maxHopsSpinner.getValue();
        policy.crossOriginMode = (RedirectCrossOriginMode) modeCombo.getSelectedItem();
        policy.additionalSensitiveHeaderNames = parseHeaderNames(additionalSensitiveField.getText());
        policy.trustedRules = rulesModel.toRules();
        for (TrustedRedirectRule rule : policy.trustedRules) {
            String source = RedirectOrigin.canonicalOrigin(rule.sourceOrigin);
            String target = RedirectOrigin.canonicalOrigin(rule.targetOrigin);
            if (source == null || target == null) {
                JOptionPane.showMessageDialog(this, "Invalid trusted redirect origin.", "Redirect policy", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!target.startsWith("https://")) {
                JOptionPane.showMessageDialog(this, "Trusted redirect targets must use HTTPS.", "Redirect policy", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        policy.normalize();
        result = policy;
        dispose();
    }

    private static List<String> parseHeaderNames(String text) {
        List<String> names = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return names;
        }
        for (String part : text.split(",")) {
            String trimmed = part != null ? part.trim() : "";
            if (!trimmed.isBlank()) {
                names.add(trimmed);
            }
        }
        return names;
    }

    private static final class RulesTableModel extends AbstractTableModel {
        private final List<RuleRow> rows = new ArrayList<>();
        private final String[] columns = {"Source Origin", "Target Origin", "Allowed Headers"};

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public boolean isCellEditable(int rowIndex, int columnIndex) { return true; }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            RuleRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.sourceOrigin;
                case 1 -> row.targetOrigin;
                case 2 -> row.allowedHeaders;
                default -> "";
            };
        }
        @Override public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            RuleRow row = rows.get(rowIndex);
            String value = aValue != null ? aValue.toString() : "";
            switch (columnIndex) {
                case 0 -> row.sourceOrigin = value;
                case 1 -> row.targetOrigin = value;
                case 2 -> row.allowedHeaders = value;
            }
            fireTableCellUpdated(rowIndex, columnIndex);
        }
        void addBlankRule() {
            rows.add(new RuleRow());
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }
        void removeRow(int row) {
            if (row < 0 || row >= rows.size()) {
                return;
            }
            rows.remove(row);
            fireTableRowsDeleted(row, row);
        }
        List<TrustedRedirectRule> toRules() {
            List<TrustedRedirectRule> out = new ArrayList<>();
            for (RuleRow row : rows) {
                TrustedRedirectRule rule = new TrustedRedirectRule();
                rule.sourceOrigin = row.sourceOrigin;
                rule.targetOrigin = row.targetOrigin;
                rule.allowedHeaderNames = parseHeaderNames(row.allowedHeaders);
                out.add(rule);
            }
            return out;
        }
    }

    private static final class RuleRow {
        String sourceOrigin = "";
        String targetOrigin = "";
        String allowedHeaders = "";

        static RuleRow from(TrustedRedirectRule rule) {
            RuleRow row = new RuleRow();
            row.sourceOrigin = rule != null && rule.sourceOrigin != null ? rule.sourceOrigin : "";
            row.targetOrigin = rule != null && rule.targetOrigin != null ? rule.targetOrigin : "";
            row.allowedHeaders = rule != null && rule.allowedHeaderNames != null ? String.join(", ", rule.allowedHeaderNames) : "";
            return row;
        }
    }
}
