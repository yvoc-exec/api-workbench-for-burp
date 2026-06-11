package burp.ui;

import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.auth.OAuth2Manager;
import burp.models.EnvironmentProfile;
import burp.runner.CollectionRunner;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;

import static org.assertj.core.api.Assertions.assertThat;

class UnresolvedVariablesDialogTest {

    @Test
    void unresolvedDialogDisablesApplyWithoutActiveEnvironment() throws Exception {
        ImporterPanel panel = newPanel();

        ImporterPanel.UnresolvedDialogConfig config = panel.buildUnresolvedDialogConfig();

        assertThat(config.applyButtonText).isEqualTo("Apply to Active Environment");
        assertThat(config.canApply).isFalse();
        assertThat(config.hintText).contains("No Active Environment selected");
    }

    @Test
    void unresolvedDialogAppliesToActiveEnvironmentWhenActiveExists() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = new EnvironmentProfile();
        active.name = "UAT";
        active.ensureId();
        active.ensureDefaults();
        panel.replaceEnvironmentProfiles(java.util.List.of(active));
        panel.setActiveEnvironmentId(active.id);

        ImporterPanel.UnresolvedDialogConfig config = panel.buildUnresolvedDialogConfig();

        assertThat(config.applyButtonText).isEqualTo("Apply to Active Environment");
        assertThat(config.canApply).isTrue();
        assertThat(config.hintText).contains("Active Environment: UAT");
    }

    @Test
    void exportUnresolvedDialogEnablesExportOnlyQuickEntry() throws Exception {
        ImporterPanel panel = newPanel();

        ImporterPanel.UnresolvedDialogConfig config = panel.buildExportUnresolvedDialogConfig();

        assertThat(config.applyButtonText).isEqualTo("Use for Export");
        assertThat(config.applyButtonEnabled).isTrue();
        assertThat(config.canApply).isFalse();
        assertThat(config.hintText).contains("apply only to this export");
    }

    private static ImporterPanel newPanel() throws Exception {
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
}
