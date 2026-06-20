package burp.ui;

import burp.models.EnvironmentProfile;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import burp.testsupport.SwingRobotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JRadioButton;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class EnvironmentEditorShortcutUiIT {

    @AfterEach
    void tearDown() {
        SwingRobotTestSupport.disposeTrackedWindows();
    }

    @Test
    void ctrlSCommitsRawAndTableEnvironmentEdits() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        EnvironmentProfile profile = HistoryTestFixtures.sampleEnvironment();
        bundle.panel.replaceEnvironmentProfiles(java.util.List.of(profile));
        bundle.panel.setActiveEnvironmentId(profile.id);
        ImporterPanelTestSupport.awaitEdt();

        JTextArea rawArea = ImporterPanelTestSupport.getField(bundle.panel, "environmentRawArea");
        JTable table = ImporterPanelTestSupport.getField(bundle.panel, "environmentTable");
        JRadioButton rawView = ImporterPanelTestSupport.getField(bundle.panel, "environmentRawViewBtn");
        JRadioButton tableView = ImporterPanelTestSupport.getField(bundle.panel, "environmentTableViewBtn");

        SwingRobotTestSupport.runOnEdt(() -> {
            bundle.panel.getTabbedPane().setSelectedIndex(1);
            rawView.doClick();
        });
        JFrame frame = SwingRobotTestSupport.showInFrame(bundle.panel.getPanel(), "Environment Shortcut Test");
        Robot robot = SwingRobotTestSupport.newRobot();

        SwingRobotTestSupport.runOnEdt(() -> rawArea.setText("""
                base_url=https://keyboard.example.test
                token=abc123
                """));
        assertSaveActionRegistered(rawArea);
        SwingRobotTestSupport.focus(rawArea, robot);
        SwingRobotTestSupport.pressSaveShortcut(robot);
        SwingRobotTestSupport.waitUntil(() -> "https://keyboard.example.test".equals(profile.variables.get("base_url")),
                Duration.ofSeconds(5),
                "Raw editor Ctrl/Meta+S did not save the active environment");
        assertThat(profile.variables)
                .containsEntry("base_url", "https://keyboard.example.test")
                .containsEntry("token", "abc123");

        SwingRobotTestSupport.runOnEdt(tableView::doClick);
        SwingRobotTestSupport.runOnEdt(() -> {
            for (int i = 0; i < table.getRowCount(); i++) {
                if ("base_url".equals(table.getValueAt(i, 0))) {
                    table.setValueAt("https://table.example.test", i, 1);
                    break;
                }
            }
        });
        assertSaveActionRegistered(table);
        SwingRobotTestSupport.focus(table, robot);
        SwingRobotTestSupport.pressSaveShortcut(robot);
        SwingRobotTestSupport.waitUntil(() -> "https://table.example.test".equals(profile.variables.get("base_url")),
                Duration.ofSeconds(5),
                "Table editor Ctrl/Meta+S did not save the active environment");

        assertThat(profile.variables)
                .containsEntry("base_url", "https://table.example.test")
                .containsEntry("token", "abc123");

        SwingRobotTestSupport.dispose(frame);
    }

    private static void assertSaveActionRegistered(JComponent component) {
        KeyStroke save = KeyStroke.getKeyStroke(KeyEvent.VK_S, SwingRobotTestSupport.shortcutMask());
        Object actionKey = component.getInputMap(JComponent.WHEN_FOCUSED).get(save);
        if (actionKey == null) {
            actionKey = component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(save);
        }
        if (actionKey == null) {
            actionKey = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(save);
        }
        assertThat(actionKey).isEqualTo("environment-save");
        assertThat(component.getActionMap().get(actionKey)).isNotNull();
    }
}
