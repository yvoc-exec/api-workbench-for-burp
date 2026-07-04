package burp.ui.traffic;

import burp.models.ApiCollection;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

public final class TrafficDestinationDialog {
    private final Window owner;
    private final TrafficDestinationDialogModel model;

    public TrafficDestinationDialog(Window owner, TrafficDestinationDialogModel model) {
        this.owner = owner;
        this.model = model;
    }

    public boolean showDialog() {
        if (model == null) {
            return false;
        }
        if (GraphicsEnvironment.isHeadless()) {
            model.confirm();
            return model.isValid();
        }

        JDialog dialog = new JDialog(owner, "Send Burp Traffic to API Workbench", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;

        JComboBox<CollectionChoice> collectionCombo = new JComboBox<>();
        collectionCombo.addItem(new CollectionChoice(null, "New Collection"));
        for (ApiCollection collection : model.existingCollections()) {
            if (collection != null) {
                collectionCombo.addItem(new CollectionChoice(collection,
                        collection.name != null && !collection.name.isBlank() ? collection.name : "Untitled Collection"));
            }
        }
        if (!model.createNewCollection() && model.destinationCollection() != null) {
            for (int i = 0; i < collectionCombo.getItemCount(); i++) {
                CollectionChoice choice = collectionCombo.getItemAt(i);
                if (choice.collection == model.destinationCollection()) {
                    collectionCombo.setSelectedIndex(i);
                    break;
                }
            }
        }

        JTextField newCollectionName = new JTextField(model.newCollectionName(), 30);
        JTextField folder = new JTextField(model.destinationFolder(), 30);
        JCheckBox preserveExact = new JCheckBox("Preserve exact raw transport", model.preserveExactTransport());
        JCheckBox captureResponses = new JCheckBox("Capture available responses in History", model.captureResponses());
        captureResponses.setEnabled(model.responseAvailable());
        JCheckBox queue = new JCheckBox("Queue imported requests in Runner", model.queueInRunner());
        preserveExact.setEnabled(!model.hasBinaryRequest());

        int row = 0;
        addRow(form, gbc, row++, "Destination collection:", collectionCombo);
        addRow(form, gbc, row++, "New collection name:", newCollectionName);
        addRow(form, gbc, row++, "Destination folder:", folder);
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        form.add(preserveExact, gbc);
        gbc.gridy = row++;
        form.add(captureResponses, gbc);
        gbc.gridy = row++;
        form.add(queue, gbc);

        NameTableModel nameTableModel = new NameTableModel(model.generatedNames());
        JTable names = new JTable(nameTableModel);
        JScrollPane nameScroll = new JScrollPane(names);
        nameScroll.setBorder(BorderFactory.createTitledBorder("Imported request names"));
        nameScroll.setPreferredSize(new Dimension(620, Math.min(260, 60 + model.selectedCount() * 22)));

        collectionCombo.addActionListener(event -> {
            CollectionChoice choice = (CollectionChoice) collectionCombo.getSelectedItem();
            model.setDestinationCollection(choice != null ? choice.collection : null);
            boolean creating = choice == null || choice.collection == null;
            newCollectionName.setEnabled(creating);
            folder.setEnabled(!creating);
            nameTableModel.replace(model.generatedNames());
        });
        newCollectionName.setEnabled(model.createNewCollection());
        folder.setEnabled(!model.createNewCollection());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        JButton importButton = new JButton(model.queueAction() ? "Import and Queue" : "Import");
        actions.add(cancel);
        actions.add(importButton);

        cancel.addActionListener(event -> {
            model.cancel();
            dialog.dispose();
        });
        importButton.addActionListener(event -> {
            CollectionChoice choice = (CollectionChoice) collectionCombo.getSelectedItem();
            model.setDestinationCollection(choice != null ? choice.collection : null);
            model.setNewCollectionName(newCollectionName.getText());
            model.setDestinationFolder(folder.getText());
            model.setPreserveExactTransport(preserveExact.isSelected());
            model.setCaptureResponses(captureResponses.isSelected());
            model.setQueueInRunner(queue.isSelected());
            for (int i = 0; i < nameTableModel.getRowCount(); i++) {
                model.setGeneratedName(i, String.valueOf(nameTableModel.getValueAt(i, 0)));
            }
            model.confirm();
            if (!model.isValid()) {
                JOptionPane.showMessageDialog(dialog,
                        String.join("\n", model.validationErrors()),
                        "Invalid Destination",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            dialog.dispose();
        });

        root.add(new JLabel("Importing " + model.selectedCount() + " request(s). No request will be sent automatically."), BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(form, BorderLayout.NORTH);
        center.add(nameScroll, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return !model.isCancelled() && model.isValid();
    }

    private static void addRow(JPanel panel, GridBagConstraints gbc, int row, String label, java.awt.Component component) {
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(component, gbc);
    }

    private record CollectionChoice(ApiCollection collection, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final class NameTableModel extends AbstractTableModel {
        private final List<String> names = new ArrayList<>();

        private NameTableModel(List<String> source) {
            replace(source);
        }

        private void replace(List<String> source) {
            names.clear();
            if (source != null) {
                names.addAll(source);
            }
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return names.size();
        }

        @Override
        public int getColumnCount() {
            return 1;
        }

        @Override
        public String getColumnName(int column) {
            return "Request Name";
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return names.get(rowIndex);
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            names.set(rowIndex, value != null ? value.toString() : "");
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}
