package burp.ui;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encapsulates the request editor's body-mode UI and shared form-table behavior.
 */
final class RequestEditorBodySupport {

    private RequestEditorBodySupport() {
    }

    static final class BodyUi {
        final JPanel panel;
        final Map<String, JRadioButton> bodyModeButtons = new LinkedHashMap<>();
        final JPanel bodyContentPanel = new JPanel(new CardLayout());
        final JTextPane bodyRawArea = new JTextPane();
        final DefaultTableModel bodyFormModel = new DefaultTableModel(
                new Object[]{"Key", "Value", "Enabled", "Type", "File Path", "File Upload"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == RequestEditorStateMapper.BODY_ENABLED_MODEL_COLUMN
                        || columnIndex == RequestEditorStateMapper.BODY_FILE_UPLOAD_MODEL_COLUMN
                        ? Boolean.class
                        : String.class;
            }
        };
        final javax.swing.JTable bodyFormTable = RequestEditorTableSupport.createEditableTable(bodyFormModel);
        final Runnable refreshResolvedMirror;
        String bodyModeInternal = "none";

        BodyUi(Runnable refreshResolvedMirror) {
            this.refreshResolvedMirror = refreshResolvedMirror;
            this.panel = buildPanel();
        }

        private JPanel buildPanel() {
            JPanel bodyPanel = new JPanel(new BorderLayout(5, 5));
            bodyPanel.add(createBodyModeRadioPanel(), BorderLayout.NORTH);

            bodyRawArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            bodyContentPanel.add(new JScrollPane(bodyRawArea), "raw");

            JPanel formPanel = new JPanel(new BorderLayout());
            bodyFormTable.moveColumn(RequestEditorStateMapper.BODY_ENABLED_MODEL_COLUMN, 0);
            bodyFormTable.getColumnModel().getColumn(0).setPreferredWidth(70);
            bodyFormTable.getColumnModel().getColumn(0).setMaxWidth(90);
            bodyFormTable.getColumnModel().getColumn(0).setMinWidth(64);
            bodyFormTable.getColumnModel().getColumn(0).setCellRenderer(bodyFormTable.getDefaultRenderer(Boolean.class));
            bodyFormTable.removeColumn(bodyFormTable.getColumnModel().getColumn(5));
            formPanel.add(new JScrollPane(bodyFormTable), BorderLayout.CENTER);
            formPanel.add(RequestEditorTableSupport.createAddRemovePanel(bodyFormTable, bodyFormModel,
                    () -> new Object[]{"", "", Boolean.TRUE, "text", "", Boolean.FALSE}), BorderLayout.SOUTH);
            bodyContentPanel.add(formPanel, "form");
            RequestEditorStateMapper.ensureStarterRow(bodyFormModel);

            JLabel noBodyLabel = new JLabel("No body");
            noBodyLabel.setHorizontalAlignment(SwingConstants.LEFT);
            Border margin = javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8);
            noBodyLabel.setBorder(margin);
            bodyContentPanel.add(noBodyLabel, "none");

            bodyPanel.add(bodyContentPanel, BorderLayout.CENTER);
            setBodyModeInternal("none");
            return bodyPanel;
        }

        private JPanel createBodyModeRadioPanel() {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
            panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 2, 0, 2));

            LinkedHashMap<String, String> uiToInternal = new LinkedHashMap<>();
            uiToInternal.put("none", "none");
            uiToInternal.put("form-data", "formdata");
            uiToInternal.put("x-www-form-urlencoded", "urlencoded");
            uiToInternal.put("raw", "raw");
            uiToInternal.put("binary", "file");
            uiToInternal.put("GraphQL", "graphql");

            ButtonGroup group = new ButtonGroup();
            for (Map.Entry<String, String> entry : uiToInternal.entrySet()) {
                String label = entry.getKey();
                String mode = entry.getValue();
                JRadioButton btn = new JRadioButton(label);
                btn.addActionListener(e -> {
                    bodyModeInternal = mode;
                    updateBodyMode();
                    if ("formdata".equals(mode) || "urlencoded".equals(mode)) {
                        RequestEditorStateMapper.ensureStarterRow(bodyFormModel);
                    }
                });
                group.add(btn);
                panel.add(btn);
                bodyModeButtons.put(mode, btn);
            }
            return panel;
        }

        String getBodyModeInternal() {
            return bodyModeInternal != null ? bodyModeInternal : "none";
        }

        void setBodyModeInternal(String mode) {
            if (mode == null || mode.isEmpty()) {
                mode = "none";
            }
            if (!bodyModeButtons.containsKey(mode)) {
                mode = "none";
            }
            bodyModeInternal = mode;
            JRadioButton btn = bodyModeButtons.get(mode);
            if (btn != null && !btn.isSelected()) {
                btn.setSelected(true);
            }
            updateBodyMode();
        }

        void updateBodyMode() {
            String mode = getBodyModeInternal();
            CardLayout cl = (CardLayout) bodyContentPanel.getLayout();
            if ("raw".equals(mode)) {
                cl.show(bodyContentPanel, "raw");
            } else if ("none".equals(mode)) {
                cl.show(bodyContentPanel, "none");
            } else if ("graphql".equals(mode) || "file".equals(mode)) {
                cl.show(bodyContentPanel, "raw");
            } else {
                cl.show(bodyContentPanel, "form");
            }
            refreshResolvedMirror.run();
        }
    }

    static BodyUi createBodyUi(Runnable refreshResolvedMirror) {
        return new BodyUi(refreshResolvedMirror);
    }

    static String getBodyModeInternal(BodyUi bodyUi) {
        return bodyUi != null ? bodyUi.getBodyModeInternal() : "none";
    }

    static void setBodyModeInternal(BodyUi bodyUi, String mode) {
        if (bodyUi != null) {
            bodyUi.setBodyModeInternal(mode);
        }
    }

    static void updateBodyMode(BodyUi bodyUi) {
        if (bodyUi != null) {
            bodyUi.updateBodyMode();
        }
    }

    static JComponent panel(BodyUi bodyUi) {
        return bodyUi.panel;
    }
}
