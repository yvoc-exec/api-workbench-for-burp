package burp.ui;

import burp.history.HistoryDiffService;
import burp.history.HistoryEntry;
import burp.testsupport.HistoryTestFixtures;
import burp.ui.history.HistoryCompareDialog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class HistoryCompareDialogUiIT {

    @Test
    void compareDialogShowsReadOnlyDiffAndClosesCleanly() throws Exception {
        HistoryEntry left = HistoryTestFixtures.sampleWorkbenchEntry();
        HistoryEntry right = HistoryTestFixtures.sampleRunnerEntry();

        SwingUtilities.invokeLater(() ->
                HistoryCompareDialog.showDialog(null, new HistoryDiffService(), left, right));

        JDialog dialog = waitForDialog("Compare History Entries");
        assertThat(dialog).isNotNull();
        assertThat(dialog.getDefaultCloseOperation()).isEqualTo(WindowConstants.DISPOSE_ON_CLOSE);

        JTextArea textArea = findTextArea(dialog.getContentPane());
        assertThat(textArea).isNotNull();
        assertThat(textArea.isEditable()).isFalse();
        assertThat(textArea.getText()).contains("=== Metadata ===").contains("=== Request ===");

        JButton close = findButton(dialog.getContentPane(), "Close");
        assertThat(close).isNotNull();
        SwingUtilities.invokeAndWait(close::doClick);

        assertThat(dialog.isDisplayable()).isFalse();
        assertThat(left.requestSnapshot.preferredRawRequestText()).contains("POST /login HTTP/1.1");
        assertThat(right.requestSnapshot.preferredRawRequestText()).contains("POST /login HTTP/1.1");
    }

    private static JDialog waitForDialog(String title) throws Exception {
        for (int i = 0; i < 100; i++) {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog && window.isShowing()) {
                    JDialog dialog = (JDialog) window;
                    if (title.equals(dialog.getTitle())) {
                        return dialog;
                    }
                }
            }
            Thread.sleep(50);
        }
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog && window.isShowing() && title.equals(((JDialog) window).getTitle())) {
                return (JDialog) window;
            }
        }
        throw new AssertionError("Timed out waiting for compare dialog");
    }

    private static JTextArea findTextArea(Container container) {
        if (container == null) {
            return null;
        }
        for (Component component : container.getComponents()) {
            if (component instanceof JTextArea) {
                return (JTextArea) component;
            }
            if (component instanceof JScrollPane) {
                Component view = ((JScrollPane) component).getViewport().getView();
                if (view instanceof JTextArea) {
                    return (JTextArea) view;
                }
            }
            if (component instanceof Container) {
                JTextArea nested = findTextArea((Container) component);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static JButton findButton(Container container, String text) {
        if (container == null) {
            return null;
        }
        for (Component component : container.getComponents()) {
            if (component instanceof JButton && text.equals(((JButton) component).getText())) {
                return (JButton) component;
            }
            if (component instanceof Container) {
                JButton nested = findButton((Container) component, text);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
