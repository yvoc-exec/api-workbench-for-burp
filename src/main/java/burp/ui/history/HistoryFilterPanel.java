package burp.ui.history;

import burp.history.HistoryFilterCriteria;
import burp.history.HistoryResult;
import burp.history.HistorySource;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public class HistoryFilterPanel extends JPanel {
    private final JTextField freeTextField = new JTextField(12);
    private final JComboBox<String> sourceCombo = new JComboBox<>(new String[]{"All", "Workbench", "Runner"});
    private final JComboBox<String> methodCombo = new JComboBox<>(new String[]{"All", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"});
    private final JComboBox<String> statusClassCombo = new JComboBox<>(new String[]{"All", "1xx", "2xx", "3xx", "4xx", "5xx", "Error"});
    private final JTextField exactStatusField = new JTextField(5);
    private final JTextField collectionField = new JTextField(8);
    private final JTextField folderField = new JTextField(8);
    private final JTextField requestField = new JTextField(8);
    private final JTextField environmentField = new JTextField(8);
    private final JComboBox<String> resultCombo = new JComboBox<>(new String[]{"All", "Success", "Failure", "Error", "Assertion Failure", "Missing Variable", "Skipped by Script", "Stopped by Script", "Unknown"});
    private final JTextField fromField = new JTextField(9);
    private final JTextField toField = new JTextField(9);
    private final JCheckBox hasResponseBodyBox = new JCheckBox("Has body");
    private final JCheckBox hasErrorBox = new JCheckBox("Has error");
    private final JCheckBox hasAssertionFailureBox = new JCheckBox("Has assert");
    private final JTextField attemptField = new JTextField(4);
    private final JTextField totalAttemptsField = new JTextField(4);
    private final JCheckBox retriesOnlyBox = new JCheckBox("Retries only");
    private final JButton clearButton = new JButton("Clear Filters");
    private Runnable changeListener;

    public HistoryFilterPanel() {
        setLayout(new BorderLayout(0, 2));
        setBorder(BorderFactory.createTitledBorder("Filters"));

        JPanel rows = new JPanel();
        rows.setOpaque(false);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.add(buildPrimaryRow());
        rows.add(Box.createVerticalStrut(2));
        rows.add(buildSecondaryRow());
        add(rows, BorderLayout.CENTER);

        setPreferredSize(new Dimension(0, 86));
        setMinimumSize(new Dimension(0, 72));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 108));

        installListeners();
        clear();
    }

    public void setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener;
    }

    public HistoryFilterCriteria getCriteria() {
        HistoryFilterCriteria criteria = new HistoryFilterCriteria();
        String freeText = freeTextField.getText();
        criteria.freeText = freeText != null && !freeText.isBlank() ? freeText.trim() : null;
        criteria.source = comboSource(sourceCombo);
        String method = comboText(methodCombo);
        criteria.method = "All".equalsIgnoreCase(method) ? null : method;
        String statusClass = comboText(statusClassCombo);
        criteria.statusClass = "All".equalsIgnoreCase(statusClass) ? null : statusClass;
        criteria.exactStatusCode = parseInteger(exactStatusField.getText());
        criteria.collection = emptyToNull(collectionField.getText());
        criteria.folder = emptyToNull(folderField.getText());
        criteria.requestName = emptyToNull(requestField.getText());
        criteria.environment = emptyToNull(environmentField.getText());
        String result = comboText(resultCombo);
        criteria.resultType = "All".equalsIgnoreCase(result) ? null : resultFromText(result);
        criteria.fromTimestamp = parseInstant(fromField.getText());
        criteria.toTimestamp = parseInstant(toField.getText());
        criteria.hasResponseBody = hasResponseBodyBox.isSelected() ? Boolean.TRUE : null;
        criteria.hasError = hasErrorBox.isSelected() ? Boolean.TRUE : null;
        criteria.hasAssertionFailure = hasAssertionFailureBox.isSelected() ? Boolean.TRUE : null;
        criteria.attemptNumber = parseInteger(attemptField.getText());
        criteria.totalAttempts = parseInteger(totalAttemptsField.getText());
        criteria.retriesOnly = retriesOnlyBox.isSelected() ? Boolean.TRUE : null;
        return criteria;
    }

    public void setCriteria(HistoryFilterCriteria criteria) {
        HistoryFilterCriteria next = criteria != null ? HistoryFilterCriteria.copyOf(criteria) : new HistoryFilterCriteria();
        freeTextField.setText(next.freeText != null ? next.freeText : "");
        sourceCombo.setSelectedItem(next.source != null ? next.source.displayName() : "All");
        methodCombo.setSelectedItem(next.method != null ? next.method.toUpperCase(Locale.ROOT) : "All");
        statusClassCombo.setSelectedItem(next.statusClass != null ? next.statusClass : "All");
        exactStatusField.setText(next.exactStatusCode != null ? String.valueOf(next.exactStatusCode) : "");
        collectionField.setText(next.collection != null ? next.collection : "");
        folderField.setText(next.folder != null ? next.folder : "");
        requestField.setText(next.requestName != null ? next.requestName : "");
        environmentField.setText(next.environment != null ? next.environment : "");
        resultCombo.setSelectedItem(next.resultType != null ? next.resultType.displayName() : "All");
        fromField.setText(next.fromTimestamp != null ? next.fromTimestamp.toString() : "");
        toField.setText(next.toTimestamp != null ? next.toTimestamp.toString() : "");
        hasResponseBodyBox.setSelected(Boolean.TRUE.equals(next.hasResponseBody));
        hasErrorBox.setSelected(Boolean.TRUE.equals(next.hasError));
        hasAssertionFailureBox.setSelected(Boolean.TRUE.equals(next.hasAssertionFailure));
        attemptField.setText(next.attemptNumber != null ? String.valueOf(next.attemptNumber) : "");
        totalAttemptsField.setText(next.totalAttempts != null ? String.valueOf(next.totalAttempts) : "");
        retriesOnlyBox.setSelected(Boolean.TRUE.equals(next.retriesOnly));
    }

    public void clear() {
        setCriteria(new HistoryFilterCriteria());
    }

    public JButton getClearButton() {
        return clearButton;
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension preferred = super.getPreferredSize();
        return new Dimension(Integer.MAX_VALUE, Math.max(preferred.height, 86));
    }

    private void installListeners() {
        java.awt.event.ActionListener listener = e -> notifyChanged();
        freeTextField.addActionListener(listener);
        sourceCombo.addActionListener(listener);
        methodCombo.addActionListener(listener);
        statusClassCombo.addActionListener(listener);
        exactStatusField.addActionListener(listener);
        collectionField.addActionListener(listener);
        folderField.addActionListener(listener);
        requestField.addActionListener(listener);
        environmentField.addActionListener(listener);
        resultCombo.addActionListener(listener);
        fromField.addActionListener(listener);
        toField.addActionListener(listener);
        attemptField.addActionListener(listener);
        totalAttemptsField.addActionListener(listener);
        hasResponseBodyBox.addActionListener(listener);
        hasErrorBox.addActionListener(listener);
        hasAssertionFailureBox.addActionListener(listener);
        retriesOnlyBox.addActionListener(listener);
        clearButton.addActionListener(e -> {
            clear();
            notifyChanged();
        });
    }

    private JPanel buildPrimaryRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        addField(row, "Search", freeTextField);
        addField(row, "Source", sourceCombo);
        addField(row, "Method", methodCombo);
        addField(row, "Status", statusClassCombo);
        addField(row, "Code", exactStatusField);
        addField(row, "Result", resultCombo);
        row.add(clearButton);
        return row;
    }

    private JPanel buildSecondaryRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        addField(row, "Collection", collectionField);
        addField(row, "Folder", folderField);
        addField(row, "Request", requestField);
        addField(row, "Env", environmentField);
        addField(row, "From", fromField);
        addField(row, "To", toField);
        addField(row, "Attempt", attemptField);
        addField(row, "Total", totalAttemptsField);
        row.add(hasResponseBodyBox);
        row.add(hasErrorBox);
        row.add(hasAssertionFailureBox);
        row.add(retriesOnlyBox);
        return row;
    }

    private static void addField(JPanel row, String label, JComponent field) {
        row.add(new JLabel(label + ":"));
        row.add(field);
    }

    private void notifyChanged() {
        if (changeListener != null) {
            changeListener.run();
        }
    }

    private static String comboText(JComboBox<String> combo) {
        Object value = combo.getSelectedItem();
        return value != null ? value.toString() : "";
    }

    private static HistorySource comboSource(JComboBox<String> combo) {
        String value = comboText(combo);
        if (value == null || value.isBlank() || "All".equalsIgnoreCase(value)) {
            return null;
        }
        for (HistorySource source : HistorySource.values()) {
            if (source.displayName().equalsIgnoreCase(value)) {
                return source;
            }
        }
        return null;
    }

    private static HistoryResult resultFromText(String value) {
        if (value == null || value.isBlank() || "All".equalsIgnoreCase(value)) {
            return null;
        }
        if ("Skipped".equalsIgnoreCase(value)) {
            return HistoryResult.SKIPPED;
        }
        if ("Stopped".equalsIgnoreCase(value)) {
            return HistoryResult.STOPPED;
        }
        for (HistoryResult result : HistoryResult.values()) {
            if (result.displayName().equalsIgnoreCase(value) || result.name().equalsIgnoreCase(value.replace(' ', '_'))) {
                return result;
            }
        }
        return null;
    }

    private static Integer parseInteger(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(trimmed));
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private static String emptyToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
