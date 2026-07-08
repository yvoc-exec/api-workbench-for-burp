package burp.ui;

import burp.scripts.capabilities.ScriptRiskLevel;
import burp.scripts.capabilities.ScriptTrustReviewItem;
import burp.scripts.capabilities.ScriptTrustReviewModel;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public final class ScriptTrustReviewDialog {
    private final ScriptTrustReviewModel model;
    private final Window owner;

    public ScriptTrustReviewDialog(Window owner, ScriptTrustReviewModel model) {
        this.owner = owner;
        this.model = model != null ? model : new ScriptTrustReviewModel(List.of());
    }

    public ScriptTrustReviewModel.Decision showDialog() {
        if (GraphicsEnvironment.isHeadless()) {
            return model.decision();
        }
        JDialog dialog = new JDialog(owner, "Review Imported Scripts", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        ScriptTableModel tableModel = new ScriptTableModel(model.items());
        JTable table = new JTable(tableModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                int modelRow = row >= 0 ? convertRowIndexToModel(row) : -1;
                ScriptTrustReviewItem item = tableModel.itemAt(modelRow);
                return item != null ? reviewItemTooltip(item) : super.getToolTipText(event);
            }
        };
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(2).setPreferredWidth(260);
        table.getColumnModel().getColumn(4).setPreferredWidth(110);
        table.getColumnModel().getColumn(5).setPreferredWidth(90);
        JTextArea preview = new JTextArea();
        preview.setEditable(false);
        preview.setLineWrap(false);
        preview.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                ScriptTrustReviewItem item = tableModel.itemAt(table.getSelectedRow());
                preview.setText(item != null ? item.sourcePreview : "");
                preview.setCaretPosition(0);
            }
        });
        if (tableModel.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }

        JLabel summary = new JLabel("Scripts: " + model.totalScriptCount()
                + " | Highest risk: " + model.highestRisk()
                + " | Unsupported: " + model.unsupportedCount());
        summary.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(table), new JScrollPane(preview));
        split.setResizeWeight(0.55);
        split.setPreferredSize(new Dimension(900, 520));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton keepDisabled = new JButton("Keep All Disabled");
        JButton trustSelected = new JButton("Trust Selected");
        JButton trustAll = new JButton("Trust All");
        JButton cancel = new JButton("Cancel Import");
        buttons.add(keepDisabled);
        buttons.add(trustSelected);
        buttons.add(trustAll);
        buttons.add(cancel);

        Runnable syncSelections = () -> {
            for (ScriptTrustReviewItem item : tableModel.items) {
                model.setSelectedForTrust(item.blockId, item.selectedForTrust);
            }
        };
        keepDisabled.addActionListener(event -> {
            syncSelections.run();
            model.setDecision(ScriptTrustReviewModel.Decision.KEEP_ALL_DISABLED);
            dialog.dispose();
        });
        trustSelected.addActionListener(event -> {
            syncSelections.run();
            model.setDecision(ScriptTrustReviewModel.Decision.TRUST_SELECTED);
            dialog.dispose();
        });
        trustAll.addActionListener(event -> {
            syncSelections.run();
            model.setDecision(ScriptTrustReviewModel.Decision.TRUST_ALL);
            if (model.requiresHighRiskTrustConfirmation()) {
                int answer = JOptionPane.showConfirmDialog(dialog,
                        "One or more scripts are HIGH or CRITICAL risk. Trust all supported scripts?",
                        "Confirm High-Risk Script Trust",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) {
                    model.setDecision(ScriptTrustReviewModel.Decision.KEEP_ALL_DISABLED);
                    return;
                }
            }
            dialog.dispose();
        });
        cancel.addActionListener(event -> {
            model.setDecision(ScriptTrustReviewModel.Decision.CANCEL_IMPORT);
            dialog.dispose();
        });

        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(summary, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return model.decision();
    }

    static String friendlyPhaseLabel(burp.scripts.ScriptPhase phase) {
        if (phase == null) {
            return "";
        }
        return switch (phase) {
            case PRE_REQUEST -> "Pre-request";
            case POST_RESPONSE -> "Post-response";
            case TEST -> "Test";
            case EVENT -> "Event";
        };
    }

    static String friendlyRiskLabel(ScriptRiskLevel riskLevel) {
        if (riskLevel == null) {
            return "Low";
        }
        String name = riskLevel.name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    static String reviewItemTooltip(ScriptTrustReviewItem item) {
        if (item == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<html>");
        if (item.collectionName != null && !item.collectionName.isBlank()) {
            sb.append("Collection: ").append(escapeHtml(item.collectionName)).append("<br>");
        }
        String location = item.folderPath != null && !item.folderPath.isBlank()
                ? item.folderPath + "/" + (item.requestName != null ? item.requestName : "")
                : item.requestName;
        sb.append("Location: ").append(escapeHtml(location != null ? location : "")).append("<br>");
        sb.append("Phase: ").append(escapeHtml(friendlyPhaseLabel(item.phase))).append("<br>");
        sb.append("Risk: ").append(escapeHtml(friendlyRiskLabel(item.capabilityReport != null ? item.capabilityReport.riskLevel : ScriptRiskLevel.LOW)));
        return sb.append("</html>").toString();
    }

    private static String escapeHtml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static final class ScriptTableModel extends AbstractTableModel {
        private final String[] columns = {"Trust", "Collection", "Location", "Dialect", "Phase", "Risk", "Capabilities", "Unsupported"};
        private final List<ScriptTrustReviewItem> items = new ArrayList<>();

        private ScriptTableModel(List<ScriptTrustReviewItem> source) {
            if (source != null) {
                items.addAll(source);
            }
        }

        @Override
        public int getRowCount() {
            return items.size();
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
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            ScriptTrustReviewItem item = itemAt(rowIndex);
            return columnIndex == 0
                    && item != null
                    && (item.capabilityReport == null || !item.capabilityReport.hasUnsupportedCapabilities());
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ScriptTrustReviewItem item = itemAt(rowIndex);
            if (item == null) {
                return "";
            }
            return switch (columnIndex) {
                case 0 -> item.selectedForTrust;
                case 1 -> item.collectionName;
                case 2 -> !item.requestName.isBlank() ? item.requestName : item.folderPath;
                case 3 -> item.dialect != null ? item.dialect.name() : "";
                case 4 -> friendlyPhaseLabel(item.phase);
                case 5 -> friendlyRiskLabel(item.capabilityReport != null ? item.capabilityReport.riskLevel : ScriptRiskLevel.LOW);
                case 6 -> item.capabilityReport != null ? item.capabilityReport.capabilitySummary() : "";
                case 7 -> item.capabilityReport != null ? item.capabilityReport.unsupportedSummary() : "";
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            ScriptTrustReviewItem item = itemAt(rowIndex);
            if (item != null && columnIndex == 0) {
                item.selectedForTrust = Boolean.TRUE.equals(value);
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }

        private ScriptTrustReviewItem itemAt(int row) {
            return row >= 0 && row < items.size() ? items.get(row) : null;
        }
    }
}
