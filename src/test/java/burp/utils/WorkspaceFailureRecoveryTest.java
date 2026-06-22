package burp.utils;

import burp.UniversalImporter;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.models.ApiCollection;
import burp.runner.CollectionRunner;
import burp.utils.ScriptMode;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.JPanel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceFailureRecoveryTest {

    @Test
    void failedWorkspaceSaveLeavesPreviouslyStoredJsonIntact() throws Exception {
        Map<String, String> backing = new HashMap<>();
        AtomicInteger sets = new AtomicInteger();
        WorkspaceStateService service = new WorkspaceStateService(new WorkspaceStateService.StringStore() {
            @Override
            public String get(String key) {
                return backing.get(key);
            }

            @Override
            public void set(String key, String value) {
                int call = sets.incrementAndGet();
                if (call > 1) {
                    throw new IllegalStateException("boom");
                }
                backing.put(key, value);
            }
        });

        MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        UserInterface ui = Mockito.mock(UserInterface.class);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(api.userInterface()).thenReturn(ui);
        Mockito.when(ui.createHttpRequestEditor(Mockito.any(EditorOptions.class))).thenReturn(requestEditor);
        Mockito.when(ui.createHttpResponseEditor(Mockito.any(EditorOptions.class))).thenReturn(responseEditor);

        UniversalImporter importer = new UniversalImporter(api, ScriptMode.FULL_JS, service);
        try {
            ImporterPanelTestSupport.awaitEdt();
            ApiCollection collection = HistoryTestFixtures.sampleCollection();
            importer.getUI().restoreWorkspaceCollections(List.of(collection));
            importer.getUI().replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
            importer.getUI().setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

            importer.requestWorkspaceStateSaveNow();
            String savedJson = backing.get("api_workbench_workspace_state_json");
            assertThat(savedJson).contains("Petstore");

            collection.name = "Petstore-Modified";
            collection.runtimeVars.put("runtime_only", "value");
            importer.requestWorkspaceStateSaveNow();

            assertThat(backing.get("api_workbench_workspace_state_json")).isEqualTo(savedJson);
            assertThat(backing.get("api_workbench_workspace_state_json")).doesNotContain("Petstore-Modified");

            String lastSaved = ImporterPanelTestSupport.getField(importer, "lastSavedWorkspaceJson");
            assertThat(lastSaved).isEqualTo(savedJson);
        } finally {
            importer.cleanup();
        }
    }
}
