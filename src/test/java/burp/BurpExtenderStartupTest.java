package burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.auth.TokenStore;
import burp.models.WorkspaceState;
import burp.testsupport.HistoryTestFixtures;
import burp.utils.ScriptMode;
import burp.utils.ScriptModeDetector;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class BurpExtenderStartupTest {
    private static final String LEGACY_SMOKE_PROPERTY = "apiWorkbench." + "smoke" + ".config";
    private static final String LEGACY_SMOKE_BOOTSTRAP_THREAD = "api-workbench-" + "smoke-bootstrap";
    private static final String LEGACY_SMOKE_RUNNER_THREAD = "api-workbench-" + "smoke-runner";
    private static final String WORKSPACE_STATE_KEY = "api_workbench_workspace_state_json";

    @AfterEach
    void clearLegacySmokePropertyAndTokens() {
        System.clearProperty(LEGACY_SMOKE_PROPERTY);
        TokenStore.clearAll();
    }

    @Test
    void initializeUsesDetectedScriptModeRegistersUiAndRestoresWorkspace() throws Exception {
        StartupLogCapture capture = new StartupLogCapture();
        MontoyaApi api = mockApi(capture, false, sampleWorkspaceJson());
        BurpExtender extender = new BurpExtender();
        ScriptModeDetector.DetectionResult expected = ScriptModeDetector.detect();

        try {
            extender.initialize(api);
            flushEdt();
            flushEdt();

            verify(api.extension()).setName("API Workbench for Burp");
            verify(api.extension()).registerUnloadingHandler(any(ExtensionUnloadingHandler.class));

            ArgumentCaptor<Component> panelCaptor = ArgumentCaptor.forClass(Component.class);
            verify(api.userInterface()).registerSuiteTab(eq("API Workbench"), panelCaptor.capture());
            assertThat(panelCaptor.getValue()).isInstanceOf(JPanel.class);

            UniversalImporter importer = currentImporter(extender);
            assertThat(importer).isNotNull();

            WorkspaceState restored = importer.getUI().getWorkspaceStateSnapshot();
            assertThat(restored.collections).hasSize(1);
            assertThat(restored.collections.get(0).name).isEqualTo(HistoryTestFixtures.COLLECTION_NAME);
            assertThat(restored.environments).hasSize(1);
            assertThat(restored.environments.get(0).id).isEqualTo(HistoryTestFixtures.ENVIRONMENT_ID);
            assertThat(restored.activeEnvironmentId).isEqualTo(HistoryTestFixtures.ENVIRONMENT_ID);

            assertThat(scriptLine(capture)).contains(expected.mode.label);
            assertThat(capture.output).contains("Extension core initialized; scheduling API Workbench UI registration...");
            assertThat(capture.output).contains("API Workbench UI init starting...");
            assertThat(capture.output).contains("Creating WorkspaceStateService...");
            assertThat(capture.output).contains("Creating UniversalImporter...");
            assertThat(capture.output).contains("Getting API Workbench main panel...");
            assertThat(capture.output).contains("Registering API Workbench suite tab...");
            assertThat(capture.output).contains("Restoring API Workbench workspace state...");
            assertThat(capture.output).contains("API Workbench suite tab registered successfully.");
            assertThat(capture.errors).isEmpty();
            assertThat(allLogs(capture).toLowerCase(Locale.ROOT)).doesNotContain("smoke");
        } finally {
            unload(api);
        }
    }

    @Test
    void initializeIgnoresFormerSmokePropertyAndDoesNotWriteArtifacts() throws Exception {
        StartupLogCapture capture = new StartupLogCapture();
        MontoyaApi api = mockApi(capture, false, sampleWorkspaceJson());
        BurpExtender extender = new BurpExtender();
        ScriptModeDetector.DetectionResult expected = ScriptModeDetector.detect();
        Path tempDir = Files.createTempDirectory("awb-former-smoke-property-");
        Path configPath = tempDir.resolve("ignored-smoke-config.json");
        System.setProperty(LEGACY_SMOKE_PROPERTY, configPath.toString());

        try {
            extender.initialize(api);
            flushEdt();
            flushEdt();

            WorkspaceState restored = currentImporter(extender).getUI().getWorkspaceStateSnapshot();
            assertThat(restored.collections).hasSize(1);
            assertThat(restored.collections.get(0).name).isEqualTo(HistoryTestFixtures.COLLECTION_NAME);
            assertThat(scriptLine(capture)).contains(expected.mode.label);
            assertThat(allLogs(capture)).doesNotContain("Smoke runtime requested");
            assertThat(Files.exists(configPath)).isFalse();
            try (Stream<Path> paths = Files.list(tempDir)) {
                assertThat(paths.toList()).isEmpty();
            }
            assertThat(allLogs(capture).toLowerCase(Locale.ROOT)).doesNotContain("smoke");
        } finally {
            unload(api);
            System.clearProperty(LEGACY_SMOKE_PROPERTY);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void initializeDoesNotCreateLegacySmokeThreadsEvenWhenFormerPropertyIsSet() throws Exception {
        StartupLogCapture capture = new StartupLogCapture();
        MontoyaApi api = mockApi(capture, false, null);
        BurpExtender extender = new BurpExtender();
        Path tempDir = Files.createTempDirectory("awb-former-smoke-thread-");
        Path configPath = tempDir.resolve("ignored-smoke-config.json");
        System.setProperty(LEGACY_SMOKE_PROPERTY, configPath.toString());

        try {
            extender.initialize(api);
            flushEdt();
            flushEdt();

            assertThat(currentImporter(extender)).isNotNull();
            assertNoLegacySmokeThreads();
            try (Stream<Path> paths = Files.list(tempDir)) {
                assertThat(paths.toList()).isEmpty();
            }
        } finally {
            unload(api);
            System.clearProperty(LEGACY_SMOKE_PROPERTY);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void unloadingHandlerCleansUpImporterAndTokensWithoutSmokeSpecificPath() throws Exception {
        StartupLogCapture capture = new StartupLogCapture();
        MontoyaApi api = mockApi(capture, false, null);
        BurpExtender extender = new BurpExtender();
        TokenStore.TokenEntry entry = new TokenStore.TokenEntry();
        entry.accessToken = "access-token";
        entry.expiresAt = System.currentTimeMillis() + 60_000L;
        TokenStore.store("startup-test", entry);

        extender.initialize(api);
        flushEdt();
        flushEdt();

        UniversalImporter importer = currentImporter(extender);
        assertThat(importer).isNotNull();
        assertThat(importer.isWorkspaceSaveExecutorTerminatedForTests()).isFalse();

        ExtensionUnloadingHandler handler = registeredUnloadingHandler(api);
        assertThatCode(handler::extensionUnloaded).doesNotThrowAnyException();
        assertThat(importer.isWorkspaceSaveExecutorTerminatedForTests()).isTrue();
        assertThat(TokenStore.get("startup-test")).isNull();
        assertThat(capture.output).contains("API Workbench for Burp unloaded. Tokens cleared.");
        assertThat(allLogs(capture).toLowerCase(Locale.ROOT)).doesNotContain("smoke");
    }

    @Test
    void initializeUiLogsFailureAndStackTraceWhenRegistrationThrows() throws Exception {
        StartupLogCapture capture = new StartupLogCapture();
        MontoyaApi api = mockApi(capture, true, null);
        BurpExtender extender = new BurpExtender();
        ScriptModeDetector.DetectionResult scriptResult =
                new ScriptModeDetector.DetectionResult(ScriptMode.DISABLED, "test", 17);

        try {
            SwingUtilities.invokeAndWait(() -> extender.initializeUi(api, scriptResult));

            assertThat(capture.output).contains("API Workbench UI init starting...");
            assertThat(capture.output).contains("Creating WorkspaceStateService...");
            assertThat(capture.output).contains("Creating UniversalImporter...");
            assertThat(capture.output).contains("Getting API Workbench main panel...");
            assertThat(capture.output).contains("Registering API Workbench suite tab...");
            assertThat(capture.output).doesNotContain("API Workbench suite tab registered successfully.");
            assertThat(capture.errors).isNotEmpty();
            assertThat(capture.errors.get(0))
                    .startsWith("API Workbench UI initialization failed: java.lang.RuntimeException: register fail");
            assertThat(capture.errors.stream().anyMatch(line -> line.startsWith("\tat "))).isTrue();
        } finally {
            UniversalImporter importer = currentImporter(extender);
            if (importer != null) {
                importer.cleanup();
            }
        }
    }

    private static MontoyaApi mockApi(StartupLogCapture capture, boolean failRegistration, String workspaceJson) {
        MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        var userInterface = api.userInterface();
        var logging = api.logging();
        var extensionData = api.persistence().extensionData();

        Mockito.when(extensionData.getString(WORKSPACE_STATE_KEY)).thenReturn(workspaceJson);

        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(userInterface.createHttpRequestEditor(EditorOptions.READ_ONLY)).thenReturn(requestEditor);

        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(userInterface.createHttpResponseEditor(EditorOptions.READ_ONLY)).thenReturn(responseEditor);

        doAnswer(inv -> {
            capture.output.add(inv.getArgument(0, String.class));
            return null;
        }).when(logging).logToOutput(any(String.class));

        doAnswer(inv -> {
            capture.errors.add(inv.getArgument(0, String.class));
            return null;
        }).when(logging).logToError(any(String.class));

        if (failRegistration) {
            doThrow(new RuntimeException("register fail"))
                    .when(userInterface)
                    .registerSuiteTab(eq("API Workbench"), any(Component.class));
        }

        return api;
    }

    private static UniversalImporter currentImporter(BurpExtender extender) throws Exception {
        Field field = BurpExtender.class.getDeclaredField("importer");
        field.setAccessible(true);
        return (UniversalImporter) field.get(extender);
    }

    private static ExtensionUnloadingHandler registeredUnloadingHandler(MontoyaApi api) {
        ArgumentCaptor<ExtensionUnloadingHandler> captor = ArgumentCaptor.forClass(ExtensionUnloadingHandler.class);
        verify(api.extension()).registerUnloadingHandler(captor.capture());
        return captor.getValue();
    }

    private static void unload(MontoyaApi api) {
        try {
            registeredUnloadingHandler(api).extensionUnloaded();
        } catch (AssertionError ignored) {
            // Initialization did not complete far enough to register the handler.
        }
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static void assertNoLegacySmokeThreads() {
        assertThat(Thread.getAllStackTraces().keySet())
                .extracting(Thread::getName)
                .doesNotContain(LEGACY_SMOKE_BOOTSTRAP_THREAD, LEGACY_SMOKE_RUNNER_THREAD);
    }

    private static String sampleWorkspaceJson() {
        WorkspaceState state = WorkspaceState.fromCollections(List.of(HistoryTestFixtures.sampleCollection()));
        state.environments.add(HistoryTestFixtures.sampleEnvironment());
        state.activeEnvironmentId = HistoryTestFixtures.ENVIRONMENT_ID;
        return WorkspaceStateJson.toJson(state);
    }

    private static String scriptLine(StartupLogCapture capture) {
        return capture.output.stream()
                .filter(line -> line.startsWith("  Java: "))
                .findFirst()
                .orElse("");
    }

    private static String allLogs(StartupLogCapture capture) {
        return String.join("\n", capture.output) + "\n" + String.join("\n", capture.errors);
    }

    private static final class StartupLogCapture {
        final List<String> output = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
    }
}
