package burp.ui.history;

import burp.history.HistoryReplayRedirectMode;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

public class HistoryActionsPanel extends JPanel {
    private final JButton loadButton = new JButton("Load in Workbench");
    private final JButton replayButton = new JButton("Replay from History");
    private final JButton replayMenuButton = new JButton("\u25BC");
    private final JButton repeaterButton = new JButton("Send to Repeater");
    private final JButton copyUrlButton = new JButton("Copy URL");
    private final JButton copyCurlButton = new JButton("Copy as cURL");
    private final JButton compareButton = new JButton("Compare Selected");
    private final JButton exportButton = new JButton("Export");
    private final JButton deleteButton = new JButton("Delete Selected");
    private final JButton clearButton = new JButton("Clear History");
    private final JButton pinButton = new JButton("Pin");
    private final JButton clearUnpinnedButton = new JButton("Clear Unpinned");

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
    private Runnable pinAction;
    private Runnable clearUnpinnedAction;
    private HistoryReplayRedirectMode replayRedirectMode = HistoryReplayRedirectMode.RECORDED;
    private Consumer<HistoryReplayRedirectMode> replayRedirectModeChangeListener;
    private Runnable redirectPolicyAction;

    public HistoryActionsPanel() {
        setLayout(new FlowLayout(FlowLayout.LEFT, 5, 0));
        setBorder(BorderFactory.createTitledBorder("Actions"));
        add(loadButton);
        add(replayButton);
        replayMenuButton.setToolTipText("History replay redirect behavior");
        replayMenuButton.setMargin(new Insets(0, 4, 0, 4));
        replayMenuButton.setFocusable(false);
        add(replayMenuButton);
        add(repeaterButton);
        add(copyUrlButton);
        add(copyCurlButton);
        add(compareButton);
        add(exportButton);
        add(deleteButton);
        add(clearButton);
        add(pinButton);
        add(clearUnpinnedButton);
        installActions();
        updateSelectionState(0, false);
        updatePinActionState(0, false);
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

    public void setPinAction(Runnable pinAction) {
        this.pinAction = pinAction;
    }

    public void setClearUnpinnedAction(Runnable clearUnpinnedAction) {
        this.clearUnpinnedAction = clearUnpinnedAction;
    }

    public HistoryReplayRedirectMode getReplayRedirectMode() {
        return replayRedirectMode;
    }

    public void setReplayRedirectMode(HistoryReplayRedirectMode mode) {
        replayRedirectMode = mode != null ? mode : HistoryReplayRedirectMode.RECORDED;
        if (replayRedirectModeChangeListener != null) {
            replayRedirectModeChangeListener.accept(replayRedirectMode);
        }
    }

    public void setReplayRedirectModeChangeListener(Consumer<HistoryReplayRedirectMode> listener) {
        this.replayRedirectModeChangeListener = listener;
    }

    public void setRedirectPolicyAction(Runnable action) {
        this.redirectPolicyAction = action;
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
        replayMenuButton.setEnabled(oneOrMore);
        pinButton.setEnabled(oneOrMore);
        clearUnpinnedButton.setEnabled(hasEntries);
    }

    public void updatePinActionState(int selectedCount, boolean allSelectedPinned) {
        boolean oneOrMore = selectedCount > 0;
        pinButton.setEnabled(oneOrMore);
        pinButton.setText(oneOrMore && allSelectedPinned ? "Unpin" : "Pin");
    }

    public JButton getLoadButton() {
        return loadButton;
    }

    public JButton getReplayButton() {
        return replayButton;
    }

    public JButton getReplayMenuButton() {
        return replayMenuButton;
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

    public JButton getPinButton() {
        return pinButton;
    }

    public JButton getClearUnpinnedButton() {
        return clearUnpinnedButton;
    }

    private void installActions() {
        loadButton.addActionListener(e -> trigger(loadAction));
        replayButton.addActionListener(e -> trigger(replayAction));
        replayMenuButton.addActionListener(e -> showReplayMenu());
        repeaterButton.addActionListener(e -> trigger(repeaterAction));
        copyUrlButton.addActionListener(e -> trigger(copyUrlAction));
        copyCurlButton.addActionListener(e -> trigger(copyCurlAction));
        compareButton.addActionListener(e -> trigger(compareAction));
        deleteButton.addActionListener(e -> trigger(deleteAction));
        clearButton.addActionListener(e -> trigger(clearAction));
        pinButton.addActionListener(e -> trigger(pinAction));
        clearUnpinnedButton.addActionListener(e -> trigger(clearUnpinnedAction));
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

    private void showReplayMenu() {
        JPopupMenu menu = new JPopupMenu();
        ButtonGroup group = new ButtonGroup();
        addReplayModeItem(menu, group, HistoryReplayRedirectMode.RECORDED);
        addReplayModeItem(menu, group, HistoryReplayRedirectMode.ALWAYS_FOLLOW);
        addReplayModeItem(menu, group, HistoryReplayRedirectMode.NEVER_FOLLOW);
        menu.addSeparator();
        JMenuItem policyItem = new JMenuItem("Redirect security policy...");
        policyItem.addActionListener(e -> trigger(redirectPolicyAction));
        menu.add(policyItem);
        menu.show(replayMenuButton, 0, replayMenuButton.getHeight());
    }

    private void addReplayModeItem(JPopupMenu menu, ButtonGroup group, HistoryReplayRedirectMode mode) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(mode.displayLabel(), mode == replayRedirectMode);
        item.addActionListener(e -> setReplayRedirectMode(mode));
        group.add(item);
        menu.add(item);
    }

    private static void trigger(Runnable action) {
        if (action != null) {
            action.run();
        }
    }
}
