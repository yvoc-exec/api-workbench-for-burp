package burp.ui;

import burp.auth.OAuth2Manager;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.runner.CollectionRunner;
import burp.ui.RequestEditorPanel;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.table.DefaultTableModel;
import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
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

        assertThat(combo).isInstanceOf(JComboBox.class);
        assertThat(importBtn).isInstanceOf(JButton.class);
        assertThat(importBtn.getText()).isEqualTo("Import");
        assertThat(combo.getItemCount()).isGreaterThanOrEqualTo(1);
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
    void duplicateEnvironmentNamesAreDisambiguatedInDropdownLabelsOnly() throws Exception {
        ImporterPanel ui = newPanel();

        EnvironmentProfile first = new EnvironmentProfile();
        first.name = "UAT";
        first.ensureId();

        EnvironmentProfile second = new EnvironmentProfile();
        second.name = "UAT";
        second.ensureId();

        EnvironmentProfile third = new EnvironmentProfile();
        third.name = "UAT";
        third.ensureId();

        ui.replaceEnvironmentProfiles(List.of(first, second, third));
        ui.setActiveEnvironmentId(first.id);
        SwingUtilities.invokeAndWait(() -> { });

        JComboBox<?> environmentCombo = (JComboBox<?>) privateField(ui, "environmentCombo");
        JComboBox<?> workbenchCombo = (JComboBox<?>) privateField(ui, "workbenchEnvironmentCombo");
        List<String> labels = comboLabels(environmentCombo);
        List<String> workbenchLabels = comboLabels(workbenchCombo);

        assertThat(labels).contains("No Environment", "UAT", "UAT (#2)", "UAT (#3)");
        assertThat(workbenchLabels).contains("No Environment", "UAT", "UAT (#2)", "UAT (#3)");

        WorkspaceState snapshot = ui.getWorkspaceStateSnapshot();
        assertThat(snapshot.environments).extracting(env -> env.name)
                .containsExactly("UAT", "UAT", "UAT");
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
    void addImportedEnvironmentProfilesRendersVariablesInRawAndTable() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile profile = environment("UAT", "https://uat.example.test");
        profile.variables.put("token", "abc123");

        invokePrivate(panel, "addImportedEnvironmentProfiles",
                new Class<?>[]{List.class, String.class},
                List.of(profile),
                "uat.postman_environment.json");
        drainEdt();

        JTextArea rawArea = (JTextArea) privateField(panel, "environmentRawArea");
        DefaultTableModel model = (DefaultTableModel) privateField(panel, "environmentTableModel");

        assertThat(rawArea.getText()).contains("base_url=https://uat.example.test");
        assertThat(rawArea.getText()).contains("token=abc123");
        assertThat(model.getRowCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void addImportedEnvironmentProfilesSetsActiveWhenNoActiveEnvironment() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile profile = environment("UAT", "https://uat.example.test");

        invokePrivate(panel, "addImportedEnvironmentProfiles",
                new Class<?>[]{List.class, String.class},
                List.of(profile),
                "uat.json");

        assertThat(panel.getActiveEnvironmentId()).isEqualTo(profile.id);
    }

    @Test
    void importingEnvironmentMakesImportedProfileActiveEvenWhenAnotherActiveExists() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile old = environment("OLD", "https://old.example.test");
        panel.replaceEnvironmentProfiles(List.of(old));
        panel.setActiveEnvironmentId(old.id);

        EnvironmentProfile imported = environment("UAT", "https://uat.example.test");
        invokePrivate(panel, "addImportedEnvironmentProfiles",
                new Class<?>[]{List.class, String.class},
                List.of(imported),
                "uat.json");

        assertThat(panel.getActiveEnvironmentId()).isEqualTo(imported.id);
    }

    @Test
    void addImportedEnvironmentProfilesSyncsWorkbenchDropdown() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile profile = environment("UAT", "https://uat.example.test");

        invokePrivate(panel, "addImportedEnvironmentProfiles",
                new Class<?>[]{List.class, String.class},
                List.of(profile),
                "uat.json");
        drainEdt();

        JComboBox<?> combo = (JComboBox<?>) privateField(panel, "workbenchEnvironmentCombo");
        assertThat(combo.getSelectedItem()).hasToString("UAT");
    }

    @Test
    void addImportedEnvironmentProfilesLogsVariableCount() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile profile = environment("UAT", "https://uat.example.test");
        profile.variables.put("token", "abc123");

        invokePrivate(panel, "addImportedEnvironmentProfiles",
                new Class<?>[]{List.class, String.class},
                List.of(profile),
                "uat.json");
        drainEdt();

        JTextArea logArea = (JTextArea) privateField(panel, "importLog");
        assertThat(logArea.getText()).contains("Environment \"UAT\" variables: 2.");
    }

    @Test
    void importedEnvironmentRemainsVisibleAfterPassiveUiRefresh() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile profile = environment("Imported", "https://imported.example.test");

        invokePrivate(panel, "addImportedEnvironmentProfiles",
                new Class<?>[]{List.class, String.class},
                List.of(profile),
                "env.json");
        drainEdt();

        JTextArea rawArea = (JTextArea) privateField(panel, "environmentRawArea");
        assertThat(rawArea.getText()).contains("base_url=https://imported.example.test");

        invokePrivate(panel, "updateEnvironmentUiState");
        invokePrivate(panel, "syncWorkbenchEnvironmentControls");
        invokePrivate(panel, "syncOAuth2UiState");
        invokePrivate(panel, "syncActiveEnvironmentToEditors");
        drainEdt();

        assertThat(rawArea.getText()).contains("base_url=https://imported.example.test");
    }

    @Test
    void collectionRuntimeRefreshDoesNotOverwriteEnvironmentRawEditor() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile profile = environment("UAT", "https://uat.example.test");
        profile.variables.put("token", "abc123");
        panel.replaceEnvironmentProfiles(List.of(profile));
        panel.setActiveEnvironmentId(profile.id);

        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        collection.runtimeVars.put("legacy", "legacy-value");
        panel.restoreWorkspaceCollections(List.of(collection));
        drainEdt();

        JTextArea rawArea = (JTextArea) privateField(panel, "environmentRawArea");
        assertThat(rawArea.getText()).contains("token=abc123");
        assertThat(rawArea.getText()).doesNotContain("legacy=legacy-value");
    }

    @Test
    void dirtyRawEnvironmentTextIsNotOverwrittenByCollectionRuntimeListener() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile profile = environment("UAT", "https://uat.example.test");
        panel.replaceEnvironmentProfiles(List.of(profile));
        panel.setActiveEnvironmentId(profile.id);

        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        panel.restoreWorkspaceCollections(List.of(collection));

        JTextArea rawArea = (JTextArea) privateField(panel, "environmentRawArea");
        SwingUtilities.invokeAndWait(() -> rawArea.setText("base_url=https://dirty.example.test\ntoken=abc123\n"));
        collection.putRuntimeVar("legacy", "legacy-value");
        drainEdt();

        assertThat(rawArea.getText()).contains("base_url=https://dirty.example.test");
        assertThat(rawArea.getText()).contains("token=abc123");
        assertThat(rawArea.getText()).doesNotContain("legacy=legacy-value");
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
        assertThat(rawArea.getText()).contains("base_url=https://new.example.test");
        assertThat(rawArea.getText()).contains("token=abc123");
    }

    @Test
    void dirtyEnvironmentEditorIsNotOverwrittenByNonForceRender() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile profile = environment("UAT", "https://saved.example.test");
        panel.replaceEnvironmentProfiles(List.of(profile));
        panel.setActiveEnvironmentId(profile.id);

        JTextArea rawArea = (JTextArea) privateField(panel, "environmentRawArea");
        SwingUtilities.invokeAndWait(() -> rawArea.setText("base_url=https://draft.example.test"));

        Method render = ImporterPanel.class.getDeclaredMethod("renderSelectedEnvironmentIntoEditor", boolean.class);
        render.setAccessible(true);
        render.invoke(panel, false);
        drainEdt();

        assertThat(rawArea.getText()).contains("base_url=https://draft.example.test");
        WorkspaceState snapshot = panel.getWorkspaceStateSnapshot();
        assertThat(snapshot.environments.get(0).variables).containsEntry("base_url", "https://saved.example.test");
    }

    @Test
    void transientNoEnvironmentSelectionDoesNotClearEditorWhenActiveEnvironmentExists() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile profile = environment("UAT", "https://active.example.test");
        panel.replaceEnvironmentProfiles(List.of(profile));
        panel.setActiveEnvironmentId(profile.id);

        JTextArea rawArea = (JTextArea) privateField(panel, "environmentRawArea");
        SwingUtilities.invokeAndWait(() -> rawArea.setText("base_url=https://draft.example.test"));

        JComboBox<?> environmentCombo = (JComboBox<?>) privateField(panel, "environmentCombo");
        SwingUtilities.invokeAndWait(() -> environmentCombo.setSelectedIndex(0));
        drainEdt();

        JComboBox<?> selectedCombo = (JComboBox<?>) privateField(panel, "environmentCombo");
        assertThat(selectedCombo.getSelectedItem()).hasToString("UAT");
        assertThat(rawArea.getText()).contains("base_url=https://draft.example.test");
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
    void syncActiveEnvironmentToEditorsDoesNotCommitDirtyEnvironmentDraft() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("UAT", "https://saved.example.test");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);

        RequestEditorPanel requestEditor = (RequestEditorPanel) privateField(panel, "requestEditor");
        SwingUtilities.invokeAndWait(() -> requestEditor.loadRequest(new ApiRequest()));

        JTextArea rawArea = (JTextArea) privateField(panel, "environmentRawArea");
        SwingUtilities.invokeAndWait(() -> rawArea.setText("""
                base_url=https://draft.example.test
                """));

        invokePrivate(panel, "syncActiveEnvironmentToEditors");
        drainEdt();

        WorkspaceState snapshot = panel.getWorkspaceStateSnapshot();
        assertThat(snapshot.environments).hasSize(1);
        assertThat(snapshot.environments.get(0).variables)
                .containsEntry("base_url", "https://saved.example.test")
                .doesNotContainEntry("base_url", "https://draft.example.test");
    }

    @Test
    void dirtyActiveEnvironmentRawEditIsCommittedBeforeRuntimeUse() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile profile = environment("UAT", "https://old.example.test");
        panel.replaceEnvironmentProfiles(List.of(profile));
        panel.setActiveEnvironmentId(profile.id);

        JTextArea rawArea = (JTextArea) privateField(panel, "environmentRawArea");
        rawArea.setText("base_url=https://new.example.test\ntoken=abc123\n");

        @SuppressWarnings("unchecked")
        Map<String, String> overlay = (Map<String, String>) invokePrivateReturning(panel, "activeEnvironmentOverlayForRuntimeUse");

        assertThat(overlay)
                .containsEntry("base_url", "https://new.example.test")
                .containsEntry("token", "abc123");
        assertThat(profile.variables).containsEntry("base_url", "https://new.example.test");
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

    private static void invokePrivate(ImporterPanel panel, String name, Class<?>[] parameterTypes, Object arg1, Object arg2) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        method.invoke(panel, arg1, arg2);
    }

    private static Object invokePrivateReturning(ImporterPanel panel, String name) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(panel);
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

    private static List<String> comboLabels(JComboBox<?> combo) {
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            Object item = combo.getItemAt(i);
            labels.add(String.valueOf(item));
        }
        return labels;
    }
}
