package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.testsupport.ImporterPanelTestSupport;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ImporterPanelScriptEnvironmentPersistenceTest {

    @Test
    void liveSendPersistentEnvironmentMutationRefreshesAndSavesFromModel() throws Exception {
        PanelHarness harness = harness();
        reset(harness.bundle.importer);

        harness.environment.variables.put("token", "persisted");
        invokeEnvironmentCallback(harness.panel, harness.environment, true, false);
        ImporterPanelTestSupport.awaitEdt();

        assertThat(harness.environment.variables).containsEntry("token", "persisted");
        assertThat(harness.environment.runtimeVariables).doesNotContainKey("token");
        assertThat(harness.requestEditor.getRuntimeVariablesSnapshot()).containsEntry("token", "persisted");
        assertThat(harness.environmentRawArea.getText()).contains("token=persisted");
        verify(harness.bundle.importer, times(1)).requestWorkspaceStateSaveNowFromModel();
        verify(harness.bundle.importer, never()).requestWorkspaceStateSaveNow();

        WorkspaceState state = workspaceState(harness.collection, harness.environment);
        String json = WorkspaceStateJson.toJson(state);
        WorkspaceState restored = WorkspaceStateJson.fromJson(json);

        assertThat(json).contains("persisted");
        assertThat(restored.environments.get(0).variables).containsEntry("token", "persisted");
        assertThat(restored.environments.get(0).runtimeVariables).isEmpty();
    }

    @Test
    void liveSendRuntimeEnvironmentMutationRefreshesWithoutWorkspaceSave() throws Exception {
        PanelHarness harness = harness();
        reset(harness.bundle.importer);

        harness.environment.runtimeVariables.put("token", "runtime");
        invokeEnvironmentCallback(harness.panel, harness.environment, false, true);
        ImporterPanelTestSupport.awaitEdt();

        assertThat(harness.environment.variables).doesNotContainKey("token");
        assertThat(harness.environment.runtimeVariables).containsEntry("token", "runtime");
        assertThat(harness.requestEditor.getRuntimeVariablesSnapshot()).containsEntry("token", "runtime");
        verify(harness.bundle.importer, never()).requestWorkspaceStateSaveNowFromModel();
        verify(harness.bundle.importer, never()).requestWorkspaceStateSaveNow();

        WorkspaceState state = workspaceState(harness.collection, harness.environment);
        String json = WorkspaceStateJson.toJson(state);
        WorkspaceState restored = WorkspaceStateJson.fromJson(json);

        assertThat(restored.environments.get(0).runtimeVariables).isEmpty();
    }

    @Test
    void scriptEnvironmentMutationDoesNotOverwriteDirtyEnvironmentDraft() throws Exception {
        PanelHarness harness = harness();
        reset(harness.bundle.importer);

        harness.environment.variables.put("token", "persisted");
        harness.environmentRawArea.setText("draft=unsaved");
        ImporterPanelTestSupport.setField(harness.panel, "environmentDirty", true);
        ImporterPanelTestSupport.setField(harness.panel, "renderedEnvironmentEditorProfileId", harness.environment.id);

        invokeEnvironmentCallback(harness.panel, harness.environment, true, false);
        ImporterPanelTestSupport.awaitEdt();

        assertThat(harness.environmentRawArea.getText()).isEqualTo("draft=unsaved");
        assertThat((Boolean) ImporterPanelTestSupport.getField(harness.panel, "environmentDirty")).isTrue();
        verify(harness.bundle.importer, times(1)).requestWorkspaceStateSaveNowFromModel();

        ImporterPanelTestSupport.setField(harness.panel, "environmentDirty", false);
        ImporterPanelTestSupport.invokeVoid(harness.panel, "renderSelectedEnvironmentIntoEditor", new Class<?>[]{boolean.class}, true);
        ImporterPanelTestSupport.awaitEdt();
        assertThat(harness.environmentRawArea.getText()).contains("token=persisted");
    }

    @Test
    void runnerPersistentEnvironmentMutationUsesSameCallbackAndSavesOnce() throws Exception {
        PanelHarness harness = harness();
        reset(harness.bundle.importer);

        Object panelSink = ImporterPanelTestSupport.getField(harness.panel, "scriptVariableMutationSink");
        Object runnerSink = ImporterPanelTestSupport.getField(harness.bundle.runner, "runtimeVariableSink");
        assertThat(runnerSink).isSameAs(panelSink);

        harness.environment.variables.put("token", "persisted");
        invokeEnvironmentCallback(harness.panel, harness.environment, true, false);
        ImporterPanelTestSupport.awaitEdt();

        verify(harness.bundle.importer, times(1)).requestWorkspaceStateSaveNowFromModel();
        verify(harness.bundle.importer, never()).requestWorkspaceStateSaveNow();
    }

    private static void invokeEnvironmentCallback(ImporterPanel panel,
                                                  EnvironmentProfile environment,
                                                  boolean persistedChanged,
                                                  boolean runtimeChanged) {
        ImporterPanelTestSupport.invokeVoid(
                panel,
                "handleScriptEnvironmentMutationCommitted",
                new Class<?>[]{EnvironmentProfile.class, boolean.class, boolean.class},
                environment,
                persistedChanged,
                runtimeChanged
        );
    }

    private static PanelHarness harness() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ImporterPanel panel = bundle.panel;

        EnvironmentProfile environment = new EnvironmentProfile();
        environment.id = "env-1";
        environment.name = "Env One";
        environment.variables.put("baseline", "baseline-value");

        ApiCollection collection = new ApiCollection();
        collection.id = "col-1";
        collection.name = "Collection";

        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test/{{token}}";
        request.path = "Parent/Child/Request";

        @SuppressWarnings("unchecked")
        List<EnvironmentProfile> profiles = ImporterPanelTestSupport.getField(panel, "environmentProfiles");
        profiles.clear();
        profiles.add(environment);
        ImporterPanelTestSupport.setField(panel, "activeEnvironmentId", environment.id);
        ImporterPanelTestSupport.invokeVoid(panel, "updateEnvironmentComboModel", new Class<?>[0]);
        ImporterPanelTestSupport.awaitEdt();

        JTextArea rawArea = ImporterPanelTestSupport.getField(panel, "environmentRawArea");
        requestEditor(panel).setCurrentCollection(collection);
        requestEditor(panel).loadRequest(request);
        ImporterPanelTestSupport.awaitEdt();

        return new PanelHarness(bundle, panel, environment, collection, requestEditor(panel), rawArea);
    }

    private static burp.ui.RequestEditorPanel requestEditor(ImporterPanel panel) {
        return (burp.ui.RequestEditorPanel) ImporterPanelTestSupport.requestEditor(panel).raw();
    }

    private static WorkspaceState workspaceState(ApiCollection collection, EnvironmentProfile environment) {
        WorkspaceState state = new WorkspaceState();
        state.collections = new ArrayList<>(List.of(collection));
        state.environments = new ArrayList<>(List.of(environment));
        return state;
    }

    private record PanelHarness(ImporterPanelTestSupport.PanelBundle bundle,
                                ImporterPanel panel,
                                EnvironmentProfile environment,
                                ApiCollection collection,
                                burp.ui.RequestEditorPanel requestEditor,
                                JTextArea environmentRawArea) {
    }
}
