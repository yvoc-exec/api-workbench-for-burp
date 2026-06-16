package burp.ui.history;

import javax.swing.*;
import java.awt.*;

public class HistoryActionsPanel extends JPanel {
    private final JButton loadButton = new JButton("Load in Workbench");
    private final JButton replayButton = new JButton("Replay from History");
    private final JButton repeaterButton = new JButton("Send to Repeater");
    private final JButton copyUrlButton = new JButton("Copy URL");
    private final JButton copyCurlButton = new JButton("Copy as cURL");
    private final JButton compareButton = new JButton("Compare Selected");
    private final JButton exportButton = new JButton("Export");
    private final JButton deleteButton = new JButton("Delete Selected");
    private final JButton clearButton = new JButton("Clear History");

    private Runnable loadAction;
    private Runnable replayAction;
    private Runnable repeaterAction;
    private Runnable copyUrlAction;
    private Runnable copyCurlAction;
    private Runnable compareAction;
    private Runnable exportJsonAction;
    private Runnable exportCsvAction;
    private Runnable exportHarAction;
    private Runnable deleteAction;
    private Runnable clearAction;

    public HistoryActionsPanel() {
        setLayout(new FlowLayout(FlowLayout.LEFT, 5, 0));
        setBorder(BorderFactory.createTitledBorder("Actions"));
        add(loadButton);
        add(replayButton);
        add(repeaterButton);
        add(copyUrlButton);
        add(copyCurlButton);
        add(compareButton);
        add(exportButton);
        add(deleteButton);
        add(clearButton);
        installActions();
        updateSelectionState(0, false);
    }

    public void setLoadAction(Runnable loadAction) {
        this.loadAction = loadAction;
    }

    public void setReplayAction(Runnable replayAction) {
        this.replayAction = replayAction;
    }

    public void setRepeaterAction(Runnable repeaterAction) {
        this.repeaterAction = repeaterAction;
    }

    public void setCopyUrlAction(Runnable copyUrlAction) {
        this.copyUrlAction = copyUrlAction;
    }

    public void setCopyCurlAction(Runnable copyCurlAction) {
        this.copyCurlAction = copyCurlAction;
    }

    public void setCompareAction(Runnable compareAction) {
        this.compareAction = compareAction;
    }

    public void setExportJsonAction(Runnable exportJsonAction) {
        this.exportJsonAction = exportJsonAction;
    }

    public void setExportCsvAction(Runnable exportCsvAction) {
        this.exportCsvAction = exportCsvAction;
    }

    public void setExportHarAction(Runnable exportHarAction) {
        this.exportHarAction = exportHarAction;
    }

    public void setDeleteAction(Runnable deleteAction) {
        this.deleteAction = deleteAction;
    }

    public void setClearAction(Runnable clearAction) {
        this.clearAction = clearAction;
    }

    public void updateSelectionState(int selectedCount, boolean hasEntries) {
        boolean oneOrMore = selectedCount > 0;
        loadButton.setEnabled(oneOrMore);
        replayButton.setEnabled(oneOrMore);
        repeaterButton.setEnabled(oneOrMore);
        copyUrlButton.setEnabled(oneOrMore);
        copyCurlButton.setEnabled(oneOrMore);
        compareButton.setEnabled(selectedCount == 2);
        deleteButton.setEnabled(oneOrMore);
        clearButton.setEnabled(hasEntries);
        exportButton.setEnabled(hasEntries);
    }

    public JButton getLoadButton() {
        return loadButton;
    }

    public JButton getReplayButton() {
        return replayButton;
    }

    public JButton getRepeaterButton() {
        return repeaterButton;
    }

    public JButton getCopyUrlButton() {
        return copyUrlButton;
    }

    public JButton getCopyCurlButton() {
        return copyCurlButton;
    }

    public JButton getCompareButton() {
        return compareButton;
    }

    public JButton getExportButton() {
        return exportButton;
    }

    public JButton getDeleteButton() {
        return deleteButton;
    }

    public JButton getClearButton() {
        return clearButton;
    }

    private void installActions() {
        loadButton.addActionListener(e -> trigger(loadAction));
        replayButton.addActionListener(e -> trigger(replayAction));
        repeaterButton.addActionListener(e -> trigger(repeaterAction));
        copyUrlButton.addActionListener(e -> trigger(copyUrlAction));
        copyCurlButton.addActionListener(e -> trigger(copyCurlAction));
        compareButton.addActionListener(e -> trigger(compareAction));
        deleteButton.addActionListener(e -> trigger(deleteAction));
        clearButton.addActionListener(e -> trigger(clearAction));
        exportButton.addActionListener(e -> showExportMenu());
    }

    private void showExportMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem json = new JMenuItem("Export JSON");
        json.addActionListener(e -> trigger(exportJsonAction));
        JMenuItem csv = new JMenuItem("Export CSV");
        csv.addActionListener(e -> trigger(exportCsvAction));
        JMenuItem har = new JMenuItem("Export HAR");
        har.addActionListener(e -> trigger(exportHarAction));
        menu.add(json);
        menu.add(csv);
        menu.add(har);
        menu.show(exportButton, 0, exportButton.getHeight());
    }

    private static void trigger(Runnable action) {
        if (action != null) {
            action.run();
        }
    }
}
