package burp.ui;

import burp.auth.OAuth2Manager;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.models.EnvironmentProfile;
import burp.runner.CollectionRunner;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.table.DefaultTableModel;
import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

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
    void workbenchEnvironmentPaneShowsDropdownAndImport() throws Exception {
        ImporterPanel panel = newPanel();

        JComboBox<?> combo = (JComboBox<?>) privateField(panel, "workbenchEnvironmentCombo");
        JButton importBtn = (JButton) privateField(panel, "workbenchEnvironmentImportBtn");
        JLabel status = (JLabel) privateField(panel, "workbenchEnvironmentStatusLabel");

        assertThat(combo).isInstanceOf(JComboBox.class);
        assertThat(importBtn).isInstanceOf(JButton.class);
        assertThat(importBtn.getText()).isEqualTo("Import");
        assertThat(status.getText()).contains("No active environment");
    }

    @Test
    void workbenchEnvironmentDropdownSyncsWithEnvironmentTabActiveSelection() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile uat = environment("UAT", "https://uat.example.test");
        EnvironmentProfile prd = environment("PRD", "https://prd.example.test");
        panel.replaceEnvironmentProfiles(List.of(uat, prd));
        panel.setActiveEnvironmentId(prd.id);

        JComboBox<?> combo = (JComboBox<?>) privateField(panel, "workbenchEnvironmentCombo");
        assertThat(combo.getSelectedItem()).hasToString("PRD");
        assertThat(panel.getActiveEnvironmentId()).isEqualTo(prd.id);
    }

    @Test
    void selectingWorkbenchEnvironmentDropdownSetsActiveEnvironment() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile uat = environment("UAT", "https://uat.example.test");
        EnvironmentProfile prd = environment("PRD", "https://prd.example.test");
        panel.replaceEnvironmentProfiles(List.of(uat, prd));

        JComboBox<?> combo = (JComboBox<?>) privateField(panel, "workbenchEnvironmentCombo");
        SwingUtilities.invokeAndWait(() -> combo.setSelectedIndex(2));
        drainEdt();

        assertThat(panel.getActiveEnvironmentId()).isEqualTo(prd.id);
    }

    @Test
    void importedEnvironmentVariablesRenderInRawAndTable() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile profile = environment("UAT", "https://uat.example.test");
        profile.variables.put("token", "abc123");
        profile.variables.put("base_url", "https://uat.example.test");
        panel.replaceEnvironmentProfiles(List.of(profile));
        panel.setActiveEnvironmentId(profile.id);
        drainEdt();

        JTextArea rawArea = (JTextArea) privateField(panel, "environmentRawArea");
        DefaultTableModel model = (DefaultTableModel) privateField(panel, "environmentTableModel");

        assertThat(rawArea.getText()).contains("base_url=https://uat.example.test");
        assertThat(rawArea.getText()).contains("token=abc123");
        assertThat(model.getRowCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void rawEnvironmentEditPersistsAfterSave() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile profile = environment("UAT", "https://uat.example.test");
        panel.replaceEnvironmentProfiles(List.of(profile));
        panel.setActiveEnvironmentId(profile.id);

        JTextArea rawArea = (JTextArea) privateField(panel, "environmentRawArea");
        rawArea.setText("""
                base_url=https://new.example.test
                token=abc123
                """);
        invokePrivate(panel, "commitEnvironmentEditorToSelectedProfile");

        assertThat(profile.variables)
                .containsEntry("base_url", "https://new.example.test")
                .containsEntry("token", "abc123");
    }

    @Test
    void tableEnvironmentEditPersistsAfterSave() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile profile = environment("UAT", "https://uat.example.test");
        panel.replaceEnvironmentProfiles(List.of(profile));
        panel.setActiveEnvironmentId(profile.id);
        SwingUtilities.invokeAndWait(() -> ((JRadioButton) privateFieldUnchecked(panel, "environmentTableViewBtn")).doClick());

        DefaultTableModel model = (DefaultTableModel) privateField(panel, "environmentTableModel");
        model.setValueAt("base_url", 0, 0);
        model.setValueAt("https://table.example.test", 0, 1);
        invokePrivate(panel, "commitEnvironmentEditorToSelectedProfile");

        assertThat(profile.variables).containsEntry("base_url", "https://table.example.test");
    }

    @Test
    void activeTableCellEditIsCommittedOnSave() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile profile = environment("UAT", "https://uat.example.test");
        panel.replaceEnvironmentProfiles(List.of(profile));
        panel.setActiveEnvironmentId(profile.id);
        SwingUtilities.invokeAndWait(() -> ((JRadioButton) privateFieldUnchecked(panel, "environmentTableViewBtn")).doClick());

        JTable table = (JTable) privateField(panel, "environmentTable");
        SwingUtilities.invokeAndWait(() -> {
            table.editCellAt(0, 1);
            java.awt.Component editor = table.getEditorComponent();
            if (editor instanceof JTextField textField) {
                textField.setText("https://edited.example.test");
            }
        });
        invokePrivate(panel, "commitEnvironmentEditorToSelectedProfile");

        assertThat(profile.variables).containsEntry("base_url", "https://edited.example.test");
    }

    @Test
    void switchingEnvironmentCommitsDirtyValues() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile uat = environment("UAT", "https://uat.example.test");
        EnvironmentProfile prd = environment("PRD", "https://prd.example.test");
        panel.replaceEnvironmentProfiles(List.of(uat, prd));
        panel.setActiveEnvironmentId(uat.id);

        JTextArea rawArea = (JTextArea) privateField(panel, "environmentRawArea");
        rawArea.setText("base_url=https://dirty.example.test");
        panel.setActiveEnvironmentId(prd.id);

        assertThat(uat.variables).containsEntry("base_url", "https://dirty.example.test");
        assertThat(panel.getActiveEnvironmentId()).isEqualTo(prd.id);
    }

    @Test
    void setActiveDoesNotEraseValues() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile profile = environment("UAT", "https://uat.example.test");
        profile.variables.put("token", "abc123");
        panel.replaceEnvironmentProfiles(List.of(profile));
        panel.setActiveEnvironmentId(profile.id);
        drainEdt();

        JTextArea rawArea = (JTextArea) privateField(panel, "environmentRawArea");
        assertThat(rawArea.getText()).contains("token=abc123");
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

    private static void invokePrivate(ImporterPanel panel, String name) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(panel);
    }

    private static void invokePrivate(ImporterPanel panel, String name, Class<?>[] parameterTypes, Object arg) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        method.invoke(panel, arg);
    }

    private static Object privateFieldUnchecked(Object target, String name) {
        try {
            return privateField(target, name);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static EnvironmentProfile environment(String name, String baseUrl) {
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = name;
        profile.ensureId();
        profile.ensureDefaults();
        profile.variables.put("base_url", baseUrl);
        return profile;
    }

    private static void drainEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }
}
