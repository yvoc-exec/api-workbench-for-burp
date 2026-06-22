package burp.testsupport;

import burp.ui.RequestEditorPanel;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;

public final class InteractiveVariableDialogProvider implements RequestEditorPanel.VariableDialogProvider {
    @Override
    public String prompt(Component parent, String title, String message, String initialValue) {
        AtomicReference<String> value = new AtomicReference<>(null);
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent),
                title != null ? title : "Variable Input",
                Dialog.ModalityType.APPLICATION_MODAL);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JLabel label = new JLabel("<html>" + escape(message) + "</html>");
        JTextField input = new JTextField(initialValue != null ? initialValue : "", 28);
        input.setName("variable-dialog-input");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        cancel.setName("variable-dialog-cancel");
        JButton ok = new JButton("OK");
        ok.setName("variable-dialog-ok");
        ok.addActionListener(e -> {
            value.set(input.getText());
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());
        input.addActionListener(e -> {
            value.set(input.getText());
            dialog.dispose();
        });
        buttons.add(cancel);
        buttons.add(ok);
        content.add(label, BorderLayout.NORTH);
        content.add(input, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        SwingUtilities.invokeLater(input::requestFocusInWindow);
        dialog.setVisible(true);
        return value.get();
    }

    @Override
    public boolean confirm(Component parent, String title, String message) {
        AtomicReference<Boolean> confirmed = new AtomicReference<>(Boolean.FALSE);
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent),
                title != null ? title : "Confirm Variable Change",
                Dialog.ModalityType.APPLICATION_MODAL);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JLabel label = new JLabel("<html>" + escape(message).replace("\n", "<br>") + "</html>");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        cancel.setName("variable-confirm-cancel");
        JButton ok = new JButton("OK");
        ok.setName("variable-confirm-ok");
        ok.addActionListener(e -> {
            confirmed.set(Boolean.TRUE);
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());
        buttons.add(cancel);
        buttons.add(ok);
        content.add(label, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        SwingUtilities.invokeLater(ok::requestFocusInWindow);
        dialog.setVisible(true);
        return confirmed.get();
    }

    @Override
    public void info(Component parent, String title, String message) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent),
                title != null ? title : "Variable Information",
                Dialog.ModalityType.APPLICATION_MODAL);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JLabel label = new JLabel("<html>" + escape(message).replace("\n", "<br>") + "</html>");
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        content.add(label, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        SwingUtilities.invokeLater(ok::requestFocusInWindow);
        dialog.setVisible(true);
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
