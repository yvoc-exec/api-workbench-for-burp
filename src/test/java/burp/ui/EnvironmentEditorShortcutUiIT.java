package burp.ui;

import burp.models.EnvironmentProfile;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JRadioButton;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class EnvironmentEditorShortcutUiIT {

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

        rawView.doClick();
        rawArea.setText("""
                base_url=https://keyboard.example.test
                token=abc123
                """);
        invokeSaveShortcut(rawArea);
        assertThat(profile.variables)
                .containsEntry("base_url", "https://keyboard.example.test")
                .containsEntry("token", "abc123");

        tableView.doClick();
        for (int i = 0; i < table.getRowCount(); i++) {
            if ("base_url".equals(table.getValueAt(i, 0))) {
                table.setValueAt("https://table.example.test", i, 1);
                break;
            }
        }
        invokeSaveShortcut(table);

        assertThat(profile.variables)
                .containsEntry("base_url", "https://table.example.test")
                .containsEntry("token", "abc123");
    }

    private static void invokeSaveShortcut(JComponent component) {
        int mask = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                ? InputEvent.META_DOWN_MASK
                : InputEvent.CTRL_DOWN_MASK;
        KeyStroke save = KeyStroke.getKeyStroke(KeyEvent.VK_S, mask);
        Object actionKey = component.getInputMap(JComponent.WHEN_FOCUSED).get(save);
        if (actionKey == null) {
            actionKey = component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(save);
        }
        if (actionKey == null) {
            actionKey = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(save);
        }
        assertThat(actionKey).isEqualTo("environment-save");
        Action action = component.getActionMap().get(actionKey);
        assertThat(action).isNotNull();
        action.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, "environment-save"));
    }
}
