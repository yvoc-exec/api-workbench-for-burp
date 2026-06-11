package burp.ui;

import burp.models.ApiCollection;
import burp.models.UnresolvedVariableIssue;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UnresolvedVariablesDialog extends JDialog {
    public enum Action {
        APPLY_AND_CONTINUE,
        CONTINUE_WITHOUT_APPLYING,
        CANCEL
    }

    private final List<ApiCollection> collections;
    private final List<UnresolvedVariableIssue> issues;
    private final LinkedHashMap<String, JTextField> variableFields = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> enteredValues = new LinkedHashMap<>();
    private Action action = Action.CANCEL;
    private final boolean canApplyToActiveEnvironment;
    private final boolean applyButtonEnabled;
    private final String applyButtonText;
    private final String hintText;
    private JButton applyButton;
    private JLabel hintLabel;

    public UnresolvedVariablesDialog(Window owner,
                                     List<UnresolvedVariableIssue> issues,
                                     List<ApiCollection> collections) {
        this(owner, issues, collections, true, true, "Apply to Active Environment", "");
    }

    public UnresolvedVariablesDialog(Window owner,
                                     List<UnresolvedVariableIssue> issues,
                                     List<ApiCollection> collections,
                                     boolean canApplyToActiveEnvironment,
                                     String applyButtonText,
                                     String hintText) {
        this(owner, issues, collections, canApplyToActiveEnvironment, canApplyToActiveEnvironment, applyButtonText, hintText);
    }

    public UnresolvedVariablesDialog(Window owner,
                                     List<UnresolvedVariableIssue> issues,
                                     List<ApiCollection> collections,
                                     boolean canApplyToActiveEnvironment,
                                     boolean applyButtonEnabled,
                                     String applyButtonText,
                                     String hintText) {
        super(owner, "Unresolved Variables", ModalityType.APPLICATION_MODAL);
        this.issues = issues != null ? new ArrayList<>(issues) : new ArrayList<>();
        this.collections = collections != null ? new ArrayList<>(collections) : new ArrayList<>();
        this.canApplyToActiveEnvironment = canApplyToActiveEnvironment;
        this.applyButtonEnabled = applyButtonEnabled;
        this.applyButtonText = applyButtonText != null && !applyButtonText.isBlank()
                ? applyButtonText
                : "Apply to Active Environment";
        this.hintText = hintText != null ? hintText : "";
        buildUi();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                action = Action.CANCEL;
            }
        });
    }

    public Action showDialog() {
        pack();
        setMinimumSize(new Dimension(780, 420));
        setLocationRelativeTo(getOwner());
        setVisible(true);
        return action;
    }

    public Map<String, String> getEnteredValues() {
        return new LinkedHashMap<>(enteredValues);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        if (!hintText.isBlank()) {
            hintLabel = new JLabel("<html><body style='width: 680px'>" + safeHtml(hintText) + "</body></html>");
            hintLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
            root.add(hintLabel, BorderLayout.NORTH);
        }
        root.add(buildIssuesTable(), BorderLayout.CENTER);
        root.add(buildQuickEntryPanel(), BorderLayout.EAST);
        root.add(buildButtonRow(), BorderLayout.SOUTH);

        setContentPane(root);
    }

    private JComponent buildIssuesTable() {
        DefaultTableModel model = new DefaultTableModel(new Object[]{"Collection", "Request", "Variable", "Location", "Details"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (UnresolvedVariableIssue issue : issues) {
            if (issue == null) {
                continue;
            }
            model.addRow(new Object[]{
                    safe(issue.collectionName),
                    safe(issue.requestName),
                    safe(issue.variableName),
                    safe(issue.location),
                    safe(issue.message)
            });
        }

        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setFillsViewportHeight(true);
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Issues"));
        scrollPane.setPreferredSize(new Dimension(470, 260));
        return scrollPane;
    }

    private JComponent buildQuickEntryPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createTitledBorder("Quick Entry"));

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 0;

        Set<String> uniqueVariables = new LinkedHashSet<>();
        for (UnresolvedVariableIssue issue : issues) {
            if (issue != null && issue.variableName != null && !issue.variableName.isBlank()) {
                uniqueVariables.add(issue.variableName);
            }
        }

        for (String variableName : uniqueVariables) {
            gbc.gridx = 0;
            gbc.weightx = 0;
            fields.add(new JLabel(variableName + ":"), gbc);

            JTextField field = new JTextField(18);
            variableFields.put(variableName, field);
            gbc.gridx = 1;
            gbc.weightx = 1;
            fields.add(field, gbc);
            gbc.gridy++;
        }

        JScrollPane scrollPane = new JScrollPane(fields);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setPreferredSize(new Dimension(260, 260));
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildButtonRow() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        applyButton = new JButton(applyButtonText);
        applyButton.setToolTipText(canApplyToActiveEnvironment
                ? "Apply entered values to the Active Environment."
                : applyButtonEnabled
                    ? "Apply entered values only for export."
                    : "Select an Active Environment before applying values.");
        applyButton.setEnabled(applyButtonEnabled);
        applyButton.addActionListener(e -> {
            enteredValues.clear();
            enteredValues.putAll(collectEnteredValues());
            action = Action.APPLY_AND_CONTINUE;
            dispose();
        });

        JButton continueButton = new JButton("Continue Without Applying");
        continueButton.addActionListener(e -> {
            action = Action.CONTINUE_WITHOUT_APPLYING;
            dispose();
        });

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            action = Action.CANCEL;
            dispose();
        });

        panel.add(cancelButton);
        panel.add(continueButton);
        panel.add(applyButton);
        return panel;
    }

    private Map<String, String> collectEnteredValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JTextField> entry : variableFields.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            String text = entry.getValue().getText();
            if (text != null && !text.isBlank()) {
                values.put(entry.getKey(), text);
            }
        }
        return values;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static String safeHtml(String value) {
        return safe(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    String getApplyButtonText() {
        return applyButton != null ? applyButton.getText() : applyButtonText;
    }

    boolean isApplyButtonEnabled() {
        return applyButton != null && applyButton.isEnabled();
    }

    String getHintText() {
        return hintText;
    }

    String getApplyButtonTooltip() {
        return applyButton != null ? applyButton.getToolTipText() : null;
    }
}
