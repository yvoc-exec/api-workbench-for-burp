package burp.ui;

import burp.UniversalImporter;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.testsupport.ImporterPanelTestSupport;
import burp.testsupport.RunnerScriptTestFixtures;
import burp.utils.ExecutionPreflightResult;
import burp.utils.ExecutionPreflightStatus;
import burp.utils.PreflightDecisionHandler;
import burp.utils.ScriptMode;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ImporterPanelSendPreflightIntegrationTest {

    @Test
    void blockedScriptResultIsVisibleAndNotSent() throws Exception {
        WorkbenchHarness harness = harness("""
                throw new Error('boom');
                """);
        harness.importer.resetSaveCounts();

        invokeSend(harness.panel);
        waitForWorkbenchCompletion(harness);

        assertThat(harness.sendCount.get()).isZero();
        assertThat(harness.panel.getImportLogAreaForTests().getText()).contains("Request not sent \u2014 pre-request script failed.");
        assertThat(harness.importer.modelOnlySaveCount.get()).isEqualTo(0);
        assertThat(harness.importer.fullSaveCount.get()).isEqualTo(0);
    }

    @Test
    void unresolvedConfirmationContainsKeysAndFinalOrigin() throws Exception {
        WorkbenchHarness harness = harness(null);
        updateLoadedRequestUrl(harness, "https://example.test/{{missing}}");
        AtomicReference<ExecutionPreflightResult> captured = new AtomicReference<>();
        harness.panel.setPreflightDecisionHandlerForTests(preflight -> {
            captured.set(preflight);
            return false;
        });

        invokeSend(harness.panel);
        waitForWorkbenchCompletion(harness);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().unresolvedVariables).containsExactly("missing");
        assertThat(captured.get().originalHost).isEqualTo("https://example.test:443");
        assertThat(captured.get().effectiveHost).isEqualTo("https://example.test:443");
        assertThat(harness.sendCount.get()).isZero();
    }

    @Test
    void unresolvedApproveSendsExactlyOnce() throws Exception {
        WorkbenchHarness harness = harness(null);
        updateLoadedRequestUrl(harness, "https://example.test/{{missing}}");
        AtomicInteger handlerCalls = new AtomicInteger();
        harness.panel.setPreflightDecisionHandlerForTests(preflight -> {
            handlerCalls.incrementAndGet();
            return true;
        });

        invokeSend(harness.panel);
        waitForSend(harness);

        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(harness.sendCount.get()).isEqualTo(1);
        assertThat(workbenchRawRequestText(harness)).contains("{{missing}}");
    }

    @Test
    void targetChangeConfirmationUsesOriginalAndEffectiveOrigin() throws Exception {
        WorkbenchHarness harness = harness("""
                awb.request.url = 'https://other.example.test/';
                """);
        AtomicReference<ExecutionPreflightResult> captured = new AtomicReference<>();
        harness.panel.setPreflightDecisionHandlerForTests(preflight -> {
            captured.set(preflight);
            return true;
        });

        invokeSend(harness.panel);
        waitForSend(harness);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().targetChanged).isTrue();
        assertThat(captured.get().originalHost).isEqualTo("https://example.test:443");
        assertThat(captured.get().effectiveHost).isEqualTo("https://other.example.test:443");
        assertThat(harness.sendCount.get()).isEqualTo(1);
    }

    @Test
    void oauth2BlockedResultIsVisible() throws Exception {
        WorkbenchHarness harness = harness(null, request -> {
            request.auth = oauth2Auth();
            request.authOverrideMode = "explicit";
            request.authInherited = false;
            request.authExplicitlyDisabled = false;
            request.authSource = "request: " + request.name;
            request.explicitAuth = oauth2Auth();
        });
        harness.collection.runtimeOAuth2.remove("oauth2_token_url");
        harness.collection.runtimeOAuth2.put("oauth2_client_id", "client-id");
        harness.collection.runtimeOAuth2.put("oauth2_client_secret", "client-secret");
        harness.panel.setPreflightDecisionHandlerForTests(preflight -> true);

        invokeSend(harness.panel);
        waitForWorkbenchCompletion(harness);

        assertThat(harness.panel.getImportLogAreaForTests().getText()).contains("OAuth2 token acquisition failed");
    }

    @Test
    void persistentScriptMutationIsNotCommittedWhenConfirmationDenied() throws Exception {
        WorkbenchHarness harness = harness("""
                awb.environment.set('token', 'persisted', { persist: true });
                awb.request.url = 'https://other.example.test/';
                """);
        AtomicInteger handlerCalls = new AtomicInteger();
        harness.panel.setPreflightDecisionHandlerForTests(preflight -> {
            handlerCalls.incrementAndGet();
            return false;
        });
        harness.importer.resetSaveCounts();

        invokeSend(harness.panel);
        waitForWorkbenchCompletion(harness);

        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(harness.environment.variables).doesNotContainKey("token");
        assertThat(harness.importer.modelOnlySaveCount.get()).isEqualTo(0);
    }

    @Test
    void workbenchPolicyControlsRoundTripThroughWorkspace() throws Exception {
        WorkbenchHarness harness = harness(null);
        harness.panel.setPreflightDecisionHandlerForTests(preflight -> true);

        var state = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(harness.panel.getWorkspaceStateSnapshot()));
        assertThat(state.defaultResponseTimeoutMillis).isEqualTo(30_000);
        assertThat(state.workbenchTargetChangeMode).isNotNull();
    }

    private static void invokeSend(ImporterPanel panel) {
        ImporterPanelTestSupport.invokeVoid(panel, "executeWorkbenchSend", new Class<?>[0]);
    }

    private static void updateLoadedRequestUrl(WorkbenchHarness harness, String url) throws Exception {
        harness.request.url = url;
        SwingUtilities.invokeAndWait(() -> harness.requestEditor.loadRequest(harness.request));
        ImporterPanelTestSupport.awaitEdt();
    }

    private static void reloadLoadedRequest(WorkbenchHarness harness) throws Exception {
        SwingUtilities.invokeAndWait(() -> harness.requestEditor.loadRequest(harness.request));
        ImporterPanelTestSupport.awaitEdt();
    }

    private static void waitForSend(WorkbenchHarness harness) {
        ImporterPanelTestSupport.awaitCondition(() -> !harness.requestEditor.isSendEnabled(), Duration.ofSeconds(10));
        ImporterPanelTestSupport.awaitCondition(() -> harness.requestEditor.isSendEnabled(), Duration.ofSeconds(10));
        ImporterPanelTestSupport.awaitEdt();
    }

    private static void waitForWorkbenchCompletion(WorkbenchHarness harness) {
        waitForSend(harness);
        ImporterPanelTestSupport.awaitEdt();
    }

    private static String rawRequestText(HttpRequest request) {
        return request != null && request.toByteArray() != null
                ? new String(request.toByteArray().getBytes(), StandardCharsets.ISO_8859_1)
                : request != null && request.url() != null ? request.url() : "";
    }

    private static String workbenchRawRequestText(WorkbenchHarness harness) {
        var snapshot = harness.panel.getWorkbenchSendSnapshot(harness.request);
        if (snapshot != null && snapshot.detailEntry != null && snapshot.detailEntry.requestSnapshot != null
                && snapshot.detailEntry.requestSnapshot.rawRequestSentText != null) {
            return snapshot.detailEntry.requestSnapshot.rawRequestSentText;
        }
        return !harness.capturedRequests.isEmpty() ? rawRequestText(harness.capturedRequests.get(0)) : "";
    }

    private static ApiRequest.Auth oauth2Auth() {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "oauth2";
        auth.properties = new java.util.LinkedHashMap<>();
        auth.properties.put("token_url", "https://oauth2.test/token");
        auth.properties.put("client_id", "client-id");
        auth.properties.put("client_secret", "client-secret");
        return auth;
    }

    private static WorkbenchHarness harness(String scriptSource) throws Exception {
        return harness(scriptSource, null);
    }

    private static WorkbenchHarness harness(String scriptSource, Consumer<ApiRequest> requestCustomizer) throws Exception {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<HttpRequest> capturedRequests = new CopyOnWriteArrayList<>();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(
                sendCount,
                capturedRequests,
                () -> RunnerScriptTestFixtures.mockResponse(200, "{\"ok\":true}", "application/json")
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
        if (requestCustomizer != null) {
            requestCustomizer.accept(request);
        }
        if (scriptSource != null) {
            request.scriptBlocks = new ArrayList<>();
            request.scriptBlocks.add(scriptBlock("pre", scriptSource));
        }

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
        return new WorkbenchHarness(importer, panel, environment, collection, request, requestEditor, environmentRawArea, sendCount, capturedRequests);
    }

    private static ScriptBlock scriptBlock(String id, String source) {
        ScriptBlock block = new ScriptBlock();
        block.id = id;
        block.dialect = ScriptDialect.API_WORKBENCH;
        block.phase = ScriptPhase.PRE_REQUEST;
        block.scope = ScriptScope.REQUEST;
        block.source = source;
        block.enabled = true;
        return block;
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
                    timeout -> Mockito.mock(RequestOptions.class)
            );
        }

        void resetSaveCounts() {
            modelOnlySaveCount.set(0);
            fullSaveCount.set(0);
        }
    }

    private static final class WorkbenchHarness {
        final CountingImporter importer;
        final ImporterPanel panel;
        final EnvironmentProfile environment;
        final ApiCollection collection;
        final ApiRequest request;
        final RequestEditorPanel requestEditor;
        final JTextArea environmentRawArea;
        final AtomicInteger sendCount;
        final CopyOnWriteArrayList<HttpRequest> capturedRequests;

        WorkbenchHarness(CountingImporter importer,
                         ImporterPanel panel,
                         EnvironmentProfile environment,
                         ApiCollection collection,
                         ApiRequest request,
                         RequestEditorPanel requestEditor,
                         JTextArea environmentRawArea,
                         AtomicInteger sendCount,
                         CopyOnWriteArrayList<HttpRequest> capturedRequests) {
            this.importer = importer;
            this.panel = panel;
            this.environment = environment;
            this.collection = collection;
            this.request = request;
            this.requestEditor = requestEditor;
            this.environmentRawArea = environmentRawArea;
            this.sendCount = sendCount;
            this.capturedRequests = capturedRequests;
        }
    }
}
