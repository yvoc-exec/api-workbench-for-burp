package burp.ui.history;

import burp.history.HistoryDiffService;
import burp.history.HistoryEntry;

import javax.swing.*;
import java.awt.*;

public final class HistoryCompareDialog {
    private HistoryCompareDialog() {
    }

    public static void showDialog(Component parent, HistoryDiffService diffService, HistoryEntry left, HistoryEntry right) {
        HistoryDiffService service = diffService != null ? diffService : new HistoryDiffService();
        String diff = service.diff(left, right);

        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), "Compare History Entries", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(5, 5));

        JTextArea textArea = new JTextArea(diff);
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setCaretPosition(0);
        dialog.add(new JScrollPane(textArea), BorderLayout.CENTER);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        footer.add(close);
        dialog.add(footer, BorderLayout.SOUTH);

        dialog.setSize(1000, 700);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }
}
