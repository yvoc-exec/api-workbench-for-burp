package burp.ui;

import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.auth.OAuth2Manager;
import burp.models.ApiCollection;
import burp.models.EnvironmentProfile;
import burp.models.UnresolvedVariableIssue;
import burp.runner.CollectionRunner;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnresolvedVariablesDialogTest {

    @Test
    void unresolvedDialogDisablesApplyWithoutActiveEnvironment() throws Exception {
        ImporterPanel panel = newPanel();
        UnresolvedVariablesDialog dialog = panel.createUnresolvedVariablesDialog(sampleIssues(), List.of(sampleCollection()));

        assertThat(dialog.getApplyButtonText()).isEqualTo("Apply to Active Environment");
        assertThat(dialog.isApplyButtonEnabled()).isFalse();
        assertThat(dialog.getHintText()).contains("No Active Environment selected");
        assertThat(dialog.getApplyButtonTooltip()).contains("Select an Active Environment");
    }

    @Test
    void unresolvedDialogAppliesToActiveEnvironmentWhenActiveExists() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = new EnvironmentProfile();
        active.name = "UAT";
        active.ensureId();
        active.ensureDefaults();
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);

        UnresolvedVariablesDialog dialog = panel.createUnresolvedVariablesDialog(sampleIssues(), List.of(sampleCollection()));

        assertThat(dialog.getApplyButtonText()).isEqualTo("Apply to Active Environment");
        assertThat(dialog.isApplyButtonEnabled()).isTrue();
        assertThat(dialog.getHintText()).contains("Active Environment: UAT");
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

    private static List<UnresolvedVariableIssue> sampleIssues() {
        return List.of(new UnresolvedVariableIssue("APIM", "Token", "token", "header"));
    }

    private static ApiCollection sampleCollection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        return collection;
    }
}
