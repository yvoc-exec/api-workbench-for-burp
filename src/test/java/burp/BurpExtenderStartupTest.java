package burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.utils.ScriptMode;
import burp.utils.ScriptModeDetector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.swing.*;
import java.awt.Component;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class BurpExtenderStartupTest {

    @Test
    void initializeLogsSchedulingMessageAndRegistersSuiteTab() throws Exception {
        StartupLogCapture capture = new StartupLogCapture();
        MontoyaApi api = mockApi(capture, false);
        BurpExtender extender = new BurpExtender();

        extender.initialize(api);
        SwingUtilities.invokeAndWait(() -> { });

        var extension = api.extension();
        var userInterface = api.userInterface();
        verify(extension).setName("API Workbench for Burp");
        assertThat(capture.output).contains("Extension core initialized; scheduling API Workbench UI registration...");
        assertThat(capture.output.stream().anyMatch(line -> line.startsWith("  Script engine: "))).isTrue();
        assertThat(capture.output).contains("API Workbench UI init starting...");
        assertThat(capture.output).contains("Creating WorkspaceStateService...");
        assertThat(capture.output).contains("Creating UniversalImporter...");
        assertThat(capture.output).contains("Getting API Workbench main panel...");
        assertThat(capture.output).contains("Registering API Workbench suite tab...");
        assertThat(capture.output).contains("Restoring API Workbench workspace state...");
        assertThat(capture.output).contains("API Workbench suite tab registered successfully.");
        assertThat(capture.errors).isEmpty();

        ArgumentCaptor<Component> panelCaptor = ArgumentCaptor.forClass(Component.class);
        verify(userInterface).registerSuiteTab(eq("API Workbench"), panelCaptor.capture());
        assertThat(panelCaptor.getValue()).isInstanceOf(JPanel.class);
    }

    @Test
    void initializeUiLogsFailureAndStackTraceWhenRegistrationThrows() throws Exception {
        StartupLogCapture capture = new StartupLogCapture();
        MontoyaApi api = mockApi(capture, true);
        BurpExtender extender = new BurpExtender();
        ScriptModeDetector.DetectionResult scriptResult =
                new ScriptModeDetector.DetectionResult(ScriptMode.DISABLED, "test", 17);

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
    }

    private static MontoyaApi mockApi(StartupLogCapture capture, boolean failRegistration) {
        MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        var userInterface = api.userInterface();
        var logging = api.logging();

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

    private static final class StartupLogCapture {
        final List<String> output = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
    }
}
