package burp.ui;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Objects;

final class RequestEditorParameterDetailsPanel extends JPanel {
    private final JTable table;
    private final DefaultTableModel model;

    private final JComboBox<String> valueFormBox = new JComboBox<>(new String[]{"Key=value", "Bare key"});
    private final JCheckBox requiredBox = new JCheckBox();
    private final JTextField typeField = new JTextField();
    private final JTextField formatField = new JTextField();
    private final JTextField styleField = new JTextField();
    private final JComboBox<String> explodeBox = new JComboBox<>(new String[]{"Unspecified", "True", "False"});
    private final JCheckBox allowReservedBox = new JCheckBox();
    private final JTextField rawKeyField = new JTextField();
    private final JTextField rawValueField = new JTextField();
    private final JTextField sourceField = new JTextField();
    private final JTextArea metadataSummaryArea = new JTextArea(2, 20);

    private boolean refreshing;

    RequestEditorParameterDetailsPanel(JTable table, DefaultTableModel model) {
        super(new GridBagLayout());
        this.table = table;
        this.model = model;
        setBorder(BorderFactory.createTitledBorder("Selected Parameter Details"));
        sourceField.setEditable(false);
        metadataSummaryArea.setEditable(false);
        metadataSummaryArea.setLineWrap(true);
        metadataSummaryArea.setWrapStyleWord(true);

        int row = 0;
        addPair(row++, "Value form", valueFormBox, "Required", requiredBox);
        addPair(row++, "Type", typeField, "Format", formatField);
        addPair(row++, "Style", styleField, "Explode", explodeBox);
        addPair(row++, "Allow Reserved", allowReservedBox, "Raw Key", rawKeyField);
        addPair(row++, "Raw Value", rawValueField, "Source", sourceField);
        GridBagConstraints label = constraints(0, row);
        label.anchor = GridBagConstraints.NORTHWEST;
        add(new JLabel("Retained metadata"), label);
        GridBagConstraints area = constraints(1, row);
        area.gridwidth = 3;
        area.weightx = 1;
        area.fill = GridBagConstraints.HORIZONTAL;
        add(metadataSummaryArea, area);

        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                refreshSelection();
            }
        });
        model.addTableModelListener(event -> {
            if (!refreshing) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (!refreshing) {
                        refreshSelection();
                    }
                });
            }
        });
        valueFormBox.addActionListener(event -> applyUserChanges());
        requiredBox.addActionListener(event -> applyUserChanges());
        explodeBox.addActionListener(event -> applyUserChanges());
        allowReservedBox.addActionListener(event -> applyUserChanges());
        installDocumentListener(typeField);
        installDocumentListener(formatField);
        installDocumentListener(styleField);
        installDocumentListener(rawKeyField);
        installDocumentListener(rawValueField);
        refreshSelection();
    }

    void refreshSelection() {
        refreshing = true;
        try {
            int row = selectedModelRow();
            if (row < 0 || row >= model.getRowCount()) {
                clearAndDisable("No parameter selected.");
                return;
            }
            if (RequestEditorStateMapper.isUntouchedNewParameterRow(model, row)) {
                clearAndDisable("Select or enter a parameter first.");
                return;
            }
            setControlsEnabled(true);
            valueFormBox.setSelectedItem(Boolean.TRUE.equals(model.getValueAt(
                    row, RequestEditorStateMapper.PARAM_VALUE_PRESENT_MODEL_COLUMN))
                    ? "Key=value" : "Bare key");
            requiredBox.setSelected(Boolean.TRUE.equals(model.getValueAt(
                    row, RequestEditorStateMapper.PARAM_REQUIRED_MODEL_COLUMN)));
            typeField.setText(stringValue(model.getValueAt(row, RequestEditorStateMapper.PARAM_TYPE_MODEL_COLUMN)));
            formatField.setText(stringValue(model.getValueAt(row, RequestEditorStateMapper.PARAM_FORMAT_MODEL_COLUMN)));
            styleField.setText(stringValue(model.getValueAt(row, RequestEditorStateMapper.PARAM_STYLE_MODEL_COLUMN)));
            Boolean explode = nullableBoolean(model.getValueAt(row, RequestEditorStateMapper.PARAM_EXPLODE_MODEL_COLUMN));
            explodeBox.setSelectedItem(explode == null ? "Unspecified" : explode ? "True" : "False");
            allowReservedBox.setSelected(Boolean.TRUE.equals(model.getValueAt(
                    row, RequestEditorStateMapper.PARAM_ALLOW_RESERVED_MODEL_COLUMN)));
            rawKeyField.setText(stringValue(model.getValueAt(row, RequestEditorStateMapper.PARAM_RAW_KEY_MODEL_COLUMN)));
            rawValueField.setText(stringValue(model.getValueAt(row, RequestEditorStateMapper.PARAM_RAW_VALUE_MODEL_COLUMN)));
            sourceField.setText(stringValue(model.getValueAt(row, RequestEditorStateMapper.PARAM_SOURCE_MODEL_COLUMN)));
            String sourceMetadata = stringValue(model.getValueAt(
                    row, RequestEditorStateMapper.PARAM_SOURCE_METADATA_MODEL_COLUMN));
            metadataSummaryArea.setText(
                    RequestEditorMetadataSummary.summarizeCanonicalJson(sourceMetadata));
        } finally {
            refreshing = false;
        }
    }

    void selectFirstDataRowIfAvailable() {
        for (int row = 0; row < model.getRowCount(); row++) {
            if (!RequestEditorStateMapper.isUntouchedNewParameterRow(model, row)) {
                int viewRow = table.convertRowIndexToView(row);
                if (viewRow >= 0) {
                    table.setRowSelectionInterval(viewRow, viewRow);
                }
                return;
            }
        }
        table.clearSelection();
    }

    private int selectedModelRow() {
        int viewRow = table.getSelectedRow();
        return viewRow >= 0 ? table.convertRowIndexToModel(viewRow) : -1;
    }

    private void applyUserChanges() {
        if (refreshing) {
            return;
        }
        int row = selectedModelRow();
        if (row < 0 || row >= model.getRowCount()
                || RequestEditorStateMapper.isUntouchedNewParameterRow(model, row)) {
            return;
        }
        setIfChanged(row, RequestEditorStateMapper.PARAM_VALUE_PRESENT_MODEL_COLUMN,
                "Key=value".equals(valueFormBox.getSelectedItem()));
        setIfChanged(row, RequestEditorStateMapper.PARAM_REQUIRED_MODEL_COLUMN, requiredBox.isSelected());
        setIfChanged(row, RequestEditorStateMapper.PARAM_TYPE_MODEL_COLUMN, nullableText(typeField));
        setIfChanged(row, RequestEditorStateMapper.PARAM_FORMAT_MODEL_COLUMN, nullableText(formatField));
        setIfChanged(row, RequestEditorStateMapper.PARAM_STYLE_MODEL_COLUMN, nullableText(styleField));
        Object explode = switch (String.valueOf(explodeBox.getSelectedItem())) {
            case "True" -> Boolean.TRUE;
            case "False" -> Boolean.FALSE;
            default -> null;
        };
        setIfChanged(row, RequestEditorStateMapper.PARAM_EXPLODE_MODEL_COLUMN, explode);
        setIfChanged(row, RequestEditorStateMapper.PARAM_ALLOW_RESERVED_MODEL_COLUMN, allowReservedBox.isSelected());
        setIfChanged(row, RequestEditorStateMapper.PARAM_RAW_KEY_MODEL_COLUMN, nullableText(rawKeyField));
        setIfChanged(row, RequestEditorStateMapper.PARAM_RAW_VALUE_MODEL_COLUMN, nullableText(rawValueField));
    }

    private static String stringValue(Object value) {
        return value != null ? value.toString() : "";
    }

    private static Boolean nullableBoolean(Object value) {
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private void addPair(int row, String leftLabel, java.awt.Component left,
                         String rightLabel, java.awt.Component right) {
        add(new JLabel(leftLabel), constraints(0, row));
        GridBagConstraints leftField = constraints(1, row);
        leftField.weightx = 1;
        leftField.fill = GridBagConstraints.HORIZONTAL;
        add(left, leftField);
        add(new JLabel(rightLabel), constraints(2, row));
        GridBagConstraints rightField = constraints(3, row);
        rightField.weightx = 1;
        rightField.fill = GridBagConstraints.HORIZONTAL;
        add(right, rightField);
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.gridy = y;
        constraints.insets = new Insets(2, 4, 2, 4);
        constraints.anchor = GridBagConstraints.WEST;
        return constraints;
    }

    private void installDocumentListener(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyUserChanges(); }
            @Override public void removeUpdate(DocumentEvent e) { applyUserChanges(); }
            @Override public void changedUpdate(DocumentEvent e) { applyUserChanges(); }
        });
    }

    private void clearAndDisable(String message) {
        setControlsEnabled(false);
        valueFormBox.setSelectedItem("Key=value");
        requiredBox.setSelected(false);
        typeField.setText("");
        formatField.setText("");
        styleField.setText("");
        explodeBox.setSelectedItem("Unspecified");
        allowReservedBox.setSelected(false);
        rawKeyField.setText("");
        rawValueField.setText("");
        sourceField.setText("");
        metadataSummaryArea.setText(message);
    }

    private void setControlsEnabled(boolean enabled) {
        valueFormBox.setEnabled(enabled);
        requiredBox.setEnabled(enabled);
        typeField.setEnabled(enabled);
        formatField.setEnabled(enabled);
        styleField.setEnabled(enabled);
        explodeBox.setEnabled(enabled);
        allowReservedBox.setEnabled(enabled);
        rawKeyField.setEnabled(enabled);
        rawValueField.setEnabled(enabled);
        sourceField.setEnabled(enabled);
        metadataSummaryArea.setEnabled(enabled);
    }

    private static String nullableText(JTextField field) {
        String value = field.getText();
        return value == null || value.isEmpty() ? null : value;
    }

    private void setIfChanged(int row, int column, Object value) {
        if (!Objects.equals(model.getValueAt(row, column), value)) {
            model.setValueAt(value, row, column);
        }
    }

    JComboBox<String> valueFormBoxForTests() { return valueFormBox; }
    JCheckBox requiredBoxForTests() { return requiredBox; }
    JTextField typeFieldForTests() { return typeField; }
    JTextField formatFieldForTests() { return formatField; }
    JTextField styleFieldForTests() { return styleField; }
    JComboBox<String> explodeBoxForTests() { return explodeBox; }
    JCheckBox allowReservedBoxForTests() { return allowReservedBox; }
    JTextField rawKeyFieldForTests() { return rawKeyField; }
    JTextField rawValueFieldForTests() { return rawValueField; }
    JTextField sourceFieldForTests() { return sourceField; }
    JTextArea metadataSummaryAreaForTests() { return metadataSummaryArea; }
}
