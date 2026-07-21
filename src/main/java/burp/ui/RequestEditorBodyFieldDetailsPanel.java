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

final class RequestEditorBodyFieldDetailsPanel extends JPanel {
    private final JTable table;
    private final DefaultTableModel model;

    private final JTextField valueKindField = new JTextField();
    private final JCheckBox requiredBox = new JCheckBox();
    private final JTextField descriptionField = new JTextField();
    private final JTextField contentTypeField = new JTextField();
    private final JTextField styleField = new JTextField();
    private final JComboBox<String> explodeBox = new JComboBox<>(new String[]{"Unspecified", "True", "False"});
    private final JCheckBox allowReservedBox = new JCheckBox();
    private final JTextField sourceField = new JTextField();
    private final JTextArea metadataSummaryArea = new JTextArea(2, 20);

    private boolean refreshing;

    RequestEditorBodyFieldDetailsPanel(JTable table, DefaultTableModel model) {
        super(new GridBagLayout());
        this.table = table;
        this.model = model;
        setBorder(BorderFactory.createTitledBorder("Selected Body Field Details"));
        valueKindField.setEditable(false);
        sourceField.setEditable(false);
        metadataSummaryArea.setEditable(false);
        metadataSummaryArea.setLineWrap(true);
        metadataSummaryArea.setWrapStyleWord(true);

        int row = 0;
        addPair(row++, "Value kind", valueKindField, "Required", requiredBox);
        addPair(row++, "Description", descriptionField, "Content Type", contentTypeField);
        addPair(row++, "Style", styleField, "Explode", explodeBox);
        addPair(row++, "Allow Reserved", allowReservedBox, "Source", sourceField);
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
        requiredBox.addActionListener(event -> applyUserChanges());
        explodeBox.addActionListener(event -> applyUserChanges());
        allowReservedBox.addActionListener(event -> applyUserChanges());
        installDocumentListener(descriptionField);
        installDocumentListener(contentTypeField);
        installDocumentListener(styleField);
        refreshSelection();
    }

    void refreshSelection() {
        refreshing = true;
        try {
            int row = selectedModelRow();
            if (row < 0 || row >= model.getRowCount()) {
                clearAndDisable("No body field selected.");
                return;
            }
            if (RequestEditorStateMapper.isUntouchedNewBodyRow(model, row)) {
                clearAndDisable("Select or enter a body field first.");
                return;
            }
            setControlsEnabled(true);
            valueKindField.setText(valueKindForRow(row));
            requiredBox.setSelected(Boolean.TRUE.equals(model.getValueAt(
                    row, RequestEditorStateMapper.BODY_REQUIRED_MODEL_COLUMN)));
            descriptionField.setText(stringValue(model.getValueAt(row, RequestEditorStateMapper.BODY_DESCRIPTION_MODEL_COLUMN)));
            contentTypeField.setText(stringValue(model.getValueAt(row, RequestEditorStateMapper.BODY_CONTENT_TYPE_MODEL_COLUMN)));
            styleField.setText(stringValue(model.getValueAt(row, RequestEditorStateMapper.BODY_STYLE_MODEL_COLUMN)));
            Object explode = model.getValueAt(row, RequestEditorStateMapper.BODY_EXPLODE_MODEL_COLUMN);
            explodeBox.setSelectedItem(explode instanceof Boolean
                    ? ((Boolean) explode ? "True" : "False") : "Unspecified");
            allowReservedBox.setSelected(Boolean.TRUE.equals(model.getValueAt(
                    row, RequestEditorStateMapper.BODY_ALLOW_RESERVED_MODEL_COLUMN)));
            sourceField.setText(stringValue(model.getValueAt(row, RequestEditorStateMapper.BODY_SOURCE_MODEL_COLUMN)));
            String sourceMetadata = stringValue(model.getValueAt(
                    row, RequestEditorStateMapper.BODY_SOURCE_METADATA_MODEL_COLUMN));
            metadataSummaryArea.setText(
                    RequestEditorMetadataSummary.summarizeCanonicalJson(sourceMetadata));
        } finally {
            refreshing = false;
        }
    }

    void selectFirstDataRowIfAvailable() {
        for (int row = 0; row < model.getRowCount(); row++) {
            if (!RequestEditorStateMapper.isUntouchedNewBodyRow(model, row)) {
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
                || RequestEditorStateMapper.isUntouchedNewBodyRow(model, row)) {
            return;
        }
        setIfChanged(row, RequestEditorStateMapper.BODY_REQUIRED_MODEL_COLUMN, requiredBox.isSelected());
        setIfChanged(row, RequestEditorStateMapper.BODY_DESCRIPTION_MODEL_COLUMN, nullableText(descriptionField));
        setIfChanged(row, RequestEditorStateMapper.BODY_CONTENT_TYPE_MODEL_COLUMN, nullableText(contentTypeField));
        setIfChanged(row, RequestEditorStateMapper.BODY_STYLE_MODEL_COLUMN, nullableText(styleField));
        Object explode = switch (String.valueOf(explodeBox.getSelectedItem())) {
            case "True" -> Boolean.TRUE;
            case "False" -> Boolean.FALSE;
            default -> null;
        };
        setIfChanged(row, RequestEditorStateMapper.BODY_EXPLODE_MODEL_COLUMN, explode);
        setIfChanged(row, RequestEditorStateMapper.BODY_ALLOW_RESERVED_MODEL_COLUMN, allowReservedBox.isSelected());
    }

    private String valueKindForRow(int modelRow) {
        RequestEditorStateMapper.BodyFieldState state =
                RequestEditorStateMapper.resolveBodyFieldState(
                        model,
                        modelRow,
                        true);

        if (state.usesLocalFile()) {
            return "Local file path — request bytes are read from this machine.";
        }

        if (state.retainsFileMetadataWithoutPath()) {
            return "Retained file metadata — no local file path. Value remains imported source text.";
        }

        return "Text field — Value is serialized as text.";
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
        valueKindField.setText("");
        requiredBox.setSelected(false);
        descriptionField.setText("");
        contentTypeField.setText("");
        styleField.setText("");
        explodeBox.setSelectedItem("Unspecified");
        allowReservedBox.setSelected(false);
        sourceField.setText("");
        metadataSummaryArea.setText(message);
    }

    private void setControlsEnabled(boolean enabled) {
        valueKindField.setEnabled(enabled);
        requiredBox.setEnabled(enabled);
        descriptionField.setEnabled(enabled);
        contentTypeField.setEnabled(enabled);
        styleField.setEnabled(enabled);
        explodeBox.setEnabled(enabled);
        allowReservedBox.setEnabled(enabled);
        sourceField.setEnabled(enabled);
        metadataSummaryArea.setEnabled(enabled);
    }

    private static String stringValue(Object value) {
        return value != null ? value.toString() : "";
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

    JTextField valueKindFieldForTests() { return valueKindField; }
    JCheckBox requiredBoxForTests() { return requiredBox; }
    JTextField descriptionFieldForTests() { return descriptionField; }
    JTextField contentTypeFieldForTests() { return contentTypeField; }
    JTextField styleFieldForTests() { return styleField; }
    JComboBox<String> explodeBoxForTests() { return explodeBox; }
    JCheckBox allowReservedBoxForTests() { return allowReservedBox; }
    JTextField sourceFieldForTests() { return sourceField; }
    JTextArea metadataSummaryAreaForTests() { return metadataSummaryArea; }
}
