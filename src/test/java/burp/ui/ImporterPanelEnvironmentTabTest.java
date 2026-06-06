package burp.ui;

import burp.auth.OAuth2Manager;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.runner.CollectionRunner;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelEnvironmentTabTest {

    @Test
    void environmentTabInitializesActiveEnvironmentControls() throws Exception {
        ImporterPanel panel = newPanel();

        assertThat(privateField(panel, "environmentCombo")).isInstanceOf(JComboBox.class);
        assertThat(privateField(panel, "environmentImportBtn")).isInstanceOf(JButton.class);
        assertThat(privateField(panel, "environmentNewBtn")).isInstanceOf(JButton.class);
        assertThat(privateField(panel, "environmentSetActiveBtn")).isInstanceOf(JButton.class);
        assertThat(privateField(panel, "environmentRawArea")).isInstanceOf(JTextArea.class);
        assertThat(privateField(panel, "environmentTable")).isInstanceOf(JTable.class);
    }

    @Test
    void environmentTabExposesCompatibilityAliasesForLegacyTests() throws Exception {
        ImporterPanel panel = newPanel();

        assertThat(privateField(panel, "envVarsArea")).isInstanceOf(JTextArea.class);
        assertThat(privateField(panel, "varsTableModel")).isInstanceOf(javax.swing.table.DefaultTableModel.class);
        assertThat(privateField(panel, "varsEditorCardPanel")).isInstanceOf(JPanel.class);
        assertThat(privateField(panel, "varsRawViewBtn")).isInstanceOf(JRadioButton.class);
        assertThat(privateField(panel, "varsTableViewBtn")).isInstanceOf(JRadioButton.class);
        assertThat(privateField(panel, "varsAutosaveStatusLabel")).isInstanceOf(JLabel.class);
    }

    @Test
    void oauth2TabInitializesActiveEnvironmentStatusLabels() throws Exception {
        ImporterPanel panel = newPanel();

        assertThat(privateField(panel, "oauth2ActiveEnvironmentLabel")).isInstanceOf(JLabel.class);
        assertThat(privateField(panel, "oauth2BindingHintLabel")).isInstanceOf(JLabel.class);
        assertThat(privateField(panel, "oauth2AccessTokenBindingCombo")).isInstanceOf(JComboBox.class);
        assertThat(privateField(panel, "oauth2RefreshTokenBindingCombo")).isInstanceOf(JComboBox.class);
        assertThat(privateField(panel, "oauth2TokenTypeBindingCombo")).isInstanceOf(JComboBox.class);
        assertThat(privateField(panel, "oauth2ExpiresInBindingCombo")).isInstanceOf(JComboBox.class);
    }

    private ImporterPanel newPanel() {
        burp.UniversalImporter importer = Mockito.mock(burp.UniversalImporter.class, Mockito.RETURNS_DEEP_STUBS);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpRequestEditor(Mockito.any(EditorOptions.class))).thenReturn(requestEditor);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpResponseEditor(Mockito.any(EditorOptions.class))).thenReturn(responseEditor);
        OAuth2Manager oauth2Manager = Mockito.mock(OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS);
        CollectionRunner runner = new CollectionRunner(null);
        return new ImporterPanel(importer, runner, oauth2Manager, burp.utils.ScriptMode.DISABLED);
    }

    private static Object privateField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
