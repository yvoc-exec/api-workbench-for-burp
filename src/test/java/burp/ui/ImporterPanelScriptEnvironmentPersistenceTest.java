package burp.ui;

import burp.UniversalImporter;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.testsupport.ImporterPanelTestSupport;
import burp.testsupport.RunnerScriptTestFixtures;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.utils.ScriptMode;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.JTextArea;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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

        harness.environment.runtimeVariables.put("runtime_only", "runtime");
        invokeEnvironmentCallback(harness.panel, harness.environment, false, true);
        ImporterPanelTestSupport.awaitEdt();

        assertThat(harness.environment.variables).doesNotContainKey("runtime_only");
        assertThat(harness.environment.runtimeVariables).containsEntry("runtime_only", "runtime");
        assertThat(harness.requestEditor.getRuntimeVariablesSnapshot()).containsEntry("runtime_only", "runtime");
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

    @Test
    void workbenchSendPersistentEnvironmentScriptUsesProductionCallbackAndModelOnlySave() throws Exception {
        WorkbenchHarness harness = workbenchHarness("""
                awb.environment.set('token', 'persisted-token', { persist: true });
                """);
        harness.importer.resetSaveCounts();
        harness.environment.variables.remove("token");
        harness.environment.runtimeVariables.remove("token");

        ImporterPanelTestSupport.invokeVoid(harness.panel, "executeWorkbenchSend", new Class<?>[0]);

        waitForWorkbenchCompletion(harness);

        ImporterPanelTestSupport.awaitCondition(
                () -> harness.importer.modelOnlySaveCount.get() == 1,
                Duration.ofSeconds(10)
        );

        assertThat(rawRequestText(harness.capturedRequests.get(0))).contains("persisted-token");
        assertThat(harness.environment.variables).containsEntry("token", "persisted-token");
        assertThat(harness.environment.runtimeVariables).doesNotContainKey("token");
        assertThat(harness.requestEditor.getRuntimeVariablesSnapshot()).containsEntry("token", "persisted-token");
        assertThat(harness.environmentRawArea.getText()).contains("persisted-token");
        assertThat(harness.capturedRequests).hasSize(1);
        assertThat(harness.importer.modelOnlySaveCount.get()).isEqualTo(1);
        assertThat(harness.importer.fullSaveCount.get()).isEqualTo(0);

        WorkspaceState state = workspaceState(harness.collection, harness.environment);
        String json = WorkspaceStateJson.toJson(state);
        WorkspaceState restored = WorkspaceStateJson.fromJson(json);
        assertThat(json).contains("persisted-token");
        assertThat(restored.environments.get(0).variables).containsEntry("token", "persisted-token");
        assertThat(restored.environments.get(0).runtimeVariables).isEmpty();
    }

    @Test
    void workbenchSendRuntimeEnvironmentScriptRefreshesWithoutWorkspaceSave() throws Exception {
        WorkbenchHarness harness = workbenchHarness("""
                awb.environment.set('token', 'runtime-token', { persist: false });
                """);
        harness.importer.resetSaveCounts();
        harness.environment.variables.remove("token");
        harness.environment.runtimeVariables.remove("token");

        ImporterPanelTestSupport.invokeVoid(harness.panel, "executeWorkbenchSend", new Class<?>[0]);

        waitForWorkbenchCompletion(harness);

        assertThat(harness.capturedRequests).hasSize(1);
        assertThat(rawRequestText(harness.capturedRequests.get(0))).contains("runtime-token");
        assertThat(harness.environment.variables).doesNotContainKey("token");
        assertThat(harness.environment.runtimeVariables).containsEntry("token", "runtime-token");
        assertThat(harness.requestEditor.getRuntimeVariablesSnapshot()).containsEntry("token", "runtime-token");
        assertThat(harness.importer.modelOnlySaveCount.get()).isEqualTo(0);
        assertThat(harness.importer.fullSaveCount.get()).isEqualTo(0);

        WorkspaceState state = workspaceState(harness.collection, harness.environment);
        String json = WorkspaceStateJson.toJson(state);
        WorkspaceState restored = WorkspaceStateJson.fromJson(json);

        assertThat(json).doesNotContain("runtime-token");
        assertThat(restored.environments.get(0).variables).doesNotContainKey("token");
        assertThat(restored.environments.get(0).runtimeVariables).isEmpty();
    }

    @Test
    void workbenchSendCommitsDirtyActiveEnvironmentBeforeScriptExecution() throws Exception {
        WorkbenchHarness harness = workbenchHarness("""
                awb.environment.set('token', 'draft-persisted', { persist: true });
                """);
        harness.environmentRawArea.setText("draft=unsaved");
        ImporterPanelTestSupport.setField(harness.panel, "environmentDirty", true);
        ImporterPanelTestSupport.setField(harness.panel, "renderedEnvironmentEditorProfileId", harness.environment.id);
        harness.importer.resetSaveCounts();
        harness.environment.variables.remove("token");
        harness.environment.runtimeVariables.remove("token");

        ImporterPanelTestSupport.invokeVoid(harness.panel, "executeWorkbenchSend", new Class<?>[0]);

        waitForWorkbenchCompletion(harness);

        ImporterPanelTestSupport.awaitCondition(
                () -> harness.importer.modelOnlySaveCount.get() == 1,
                Duration.ofSeconds(10)
        );

        assertThat(rawRequestText(harness.capturedRequests.get(0))).contains("draft-persisted");
        assertThat(harness.environment.variables).containsEntry("draft", "unsaved");
        assertThat(harness.environment.variables).containsEntry("token", "draft-persisted");
        assertThat(harness.environment.runtimeVariables).doesNotContainKey("token");
        assertThat((Boolean) ImporterPanelTestSupport.getField(harness.panel, "environmentDirty")).isFalse();
        assertThat(harness.environmentRawArea.getText()).contains("draft=unsaved");
        assertThat(harness.environmentRawArea.getText()).contains("draft-persisted");
        assertThat(harness.requestEditor.getRuntimeVariablesSnapshot()).containsEntry("draft", "unsaved");
        assertThat(harness.requestEditor.getRuntimeVariablesSnapshot()).containsEntry("token", "draft-persisted");
        assertThat(harness.capturedRequests).hasSize(1);
        assertThat(harness.importer.modelOnlySaveCount.get()).isEqualTo(1);
        assertThat(harness.importer.fullSaveCount.get()).isEqualTo(0);

        WorkspaceState state = workspaceState(harness.collection, harness.environment);
        String json = WorkspaceStateJson.toJson(state);
        WorkspaceState restored = WorkspaceStateJson.fromJson(json);
        assertThat(json).contains("draft");
        assertThat(json).contains("draft-persisted");
        assertThat(restored.environments.get(0).variables).containsEntry("draft", "unsaved");
        assertThat(restored.environments.get(0).variables).containsEntry("token", "draft-persisted");
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
        environment.variables.put("token", "baseline-token");

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

    private static String rawRequestText(HttpRequest request) {
        if (request == null) {
            return "";
        }
        if (request.toByteArray() != null) {
            return new String(request.toByteArray().getBytes(), StandardCharsets.ISO_8859_1);
        }
        return request.url() != null ? request.url() : "";
    }

    private static WorkbenchHarness workbenchHarness(String scriptSource) throws Exception {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<HttpRequest> capturedRequests = new CopyOnWriteArrayList<>();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(
                sendCount,
                capturedRequests,
                () -> RunnerScriptTestFixtures.mockResponse(200, "{\"token\":\"persisted-token\",\"ok\":true}", "application/json")
        );
        Mockito.when(api.userInterface().createHttpRequestEditor(Mockito.any(EditorOptions.class))).thenAnswer(inv -> {
            HttpRequestEditor editor = Mockito.mock(HttpRequestEditor.class);
            Mockito.when(editor.uiComponent()).thenReturn(new JPanel());
            return editor;
        });
        Mockito.when(api.userInterface().createHttpResponseEditor(Mockito.any(EditorOptions.class))).thenAnswer(inv -> {
            HttpResponseEditor editor = Mockito.mock(HttpResponseEditor.class);
            Mockito.when(editor.uiComponent()).thenReturn(new JPanel());
            return editor;
        });

        CountingImporter importer = new CountingImporter(api, ScriptMode.FULL_JS);
        ImporterPanel panel = importer.getUI();
        panel.setPreflightDecisionHandlerForTests(preflight -> true);

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
        request.scriptBlocks = new ArrayList<>();
        request.scriptBlocks.add(scriptBlock("pre", ScriptDialect.API_WORKBENCH, ScriptPhase.PRE_REQUEST, scriptSource, 0));

        @SuppressWarnings("unchecked")
        List<EnvironmentProfile> profiles = ImporterPanelTestSupport.getField(panel, "environmentProfiles");
        profiles.clear();
        profiles.add(environment);
        ImporterPanelTestSupport.setField(panel, "activeEnvironmentId", environment.id);
        ImporterPanelTestSupport.invokeVoid(panel, "updateEnvironmentComboModel", new Class<?>[0]);
        ImporterPanelTestSupport.awaitEdt();

        RequestEditorPanel requestEditor = (RequestEditorPanel) ImporterPanelTestSupport.requestEditor(panel).raw();
        SwingUtilities.invokeAndWait(() -> {
            requestEditor.setCurrentCollection(collection);
            requestEditor.loadRequest(request);
        });
        ImporterPanelTestSupport.awaitEdt();

        JTextArea environmentRawArea = ImporterPanelTestSupport.getField(panel, "environmentRawArea");
        return new WorkbenchHarness(importer, panel, environment, collection, requestEditor, environmentRawArea, sendCount, capturedRequests);
    }

    private static void waitForWorkbenchCompletion(WorkbenchHarness harness) {
        ImporterPanelTestSupport.awaitCondition(
                () -> !harness.requestEditor.isSendEnabled(),
                Duration.ofSeconds(10)
        );
        ImporterPanelTestSupport.awaitCondition(
                () -> harness.requestEditor.isSendEnabled(),
                Duration.ofSeconds(10)
        );
        ImporterPanelTestSupport.awaitEdt();
    }

    private static ScriptBlock scriptBlock(String id, ScriptDialect dialect, ScriptPhase phase, String source, int order) {
        ScriptBlock block = new ScriptBlock();
        block.id = id;
        block.dialect = dialect;
        block.phase = phase;
        block.scope = ScriptScope.REQUEST;
        block.source = source;
        block.order = order;
        block.enabled = true;
        return block;
    }

    private record PanelHarness(ImporterPanelTestSupport.PanelBundle bundle,
                                ImporterPanel panel,
                                EnvironmentProfile environment,
                                ApiCollection collection,
                                burp.ui.RequestEditorPanel requestEditor,
                                JTextArea environmentRawArea) {
    }

    private static final class CountingImporter extends UniversalImporter {
        private final AtomicInteger modelOnlySaveCount = new AtomicInteger();
        private final AtomicInteger fullSaveCount = new AtomicInteger();

        private CountingImporter(MontoyaApi api, ScriptMode scriptMode) {
            super(api, scriptMode, null);
        }

        @Override
        public void requestWorkspaceStateSaveNow() {
            fullSaveCount.incrementAndGet();
            super.requestWorkspaceStateSaveNow();
        }

        @Override
        public void requestWorkspaceStateSaveNowFromModel() {
            modelOnlySaveCount.incrementAndGet();
            super.requestWorkspaceStateSaveNowFromModel();
        }

        @Override
        protected burp.utils.SharedRequestPipeline createSharedRequestPipeline(MontoyaApi api,
                                                                               burp.utils.RequestBuilder requestBuilder,
                                                                               burp.utils.ScriptEngine scriptEngine,
                                                                               burp.auth.OAuth2Manager oauth2Manager) {
            return burp.utils.SharedRequestPipeline.withRequestOptionsFactory(
                    api,
                    requestBuilder,
                    scriptEngine,
                    oauth2Manager,
                    null,
                    timeout -> {
                        RequestOptions options = Mockito.mock(RequestOptions.class);
                        Mockito.when(options.withRedirectionMode(Mockito.any())).thenReturn(options);
                        Mockito.when(options.withResponseTimeout(Mockito.anyInt())).thenReturn(options);
                        return options;
                    }
            );
        }

        void resetSaveCounts() {
            modelOnlySaveCount.set(0);
            fullSaveCount.set(0);
        }
    }

    private static record WorkbenchHarness(CountingImporter importer,
                                           ImporterPanel panel,
                                           EnvironmentProfile environment,
                                           ApiCollection collection,
                                           RequestEditorPanel requestEditor,
                                           JTextArea environmentRawArea,
                                           AtomicInteger sendCount,
                                           CopyOnWriteArrayList<HttpRequest> capturedRequests) {
    }
}
