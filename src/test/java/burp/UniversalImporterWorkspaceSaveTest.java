package burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.utils.DebouncedSwingAction;
import burp.utils.WorkspaceStateService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class UniversalImporterWorkspaceSaveTest {

    @Test
    void rapidWorkspaceChangeRequestsCollapseIntoSingleWrite() throws Exception {
        AtomicInteger writeCount = new AtomicInteger(0);
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            writeCount.incrementAndGet();
            return null;
        }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);

        // Speed up debounce for testing
        setDebounceDelay(importer, 100);

        // Trigger multiple rapid save requests
        for (int i = 0; i < 10; i++) {
            importer.requestWorkspaceStateSave();
        }

        assertThat(writeCount.get()).isZero();

        // Wait for debounce to fire
        Thread.sleep(200);

        assertThat(writeCount.get()).isEqualTo(1);
    }

    @Test
    void unchangedWorkspaceStateDoesNotWriteAgain() throws Exception {
        AtomicInteger writeCount = new AtomicInteger(0);
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            writeCount.incrementAndGet();
            return null;
        }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);

        // First save should write
        importer.saveWorkspaceState();
        assertThat(writeCount.get()).isEqualTo(1);

        // Second save with identical state should skip
        importer.saveWorkspaceState();
        assertThat(writeCount.get()).isEqualTo(1);
    }

    @Test
    void cleanupFlushesPendingWorkspaceSave() throws Exception {
        AtomicInteger writeCount = new AtomicInteger(0);
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            writeCount.incrementAndGet();
            return null;
        }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);

        // Speed up debounce for testing
        setDebounceDelay(importer, 5000);

        // Request a save but don't let the timer fire
        importer.requestWorkspaceStateSave();
        assertThat(writeCount.get()).isZero();

        // Cleanup should flush immediately
        importer.cleanup();
        assertThat(writeCount.get()).isEqualTo(1);

        // Timer should no longer fire
        Thread.sleep(100);
        assertThat(writeCount.get()).isEqualTo(1);
    }

    private static MontoyaApi mockApi() {
        MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(api.userInterface().createHttpRequestEditor(Mockito.any(EditorOptions.class))).thenReturn(requestEditor);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(api.userInterface().createHttpResponseEditor(Mockito.any(EditorOptions.class))).thenReturn(responseEditor);
        return api;
    }

    private static void setDebounceDelay(UniversalImporter importer, int delayMs) throws Exception {
        Field debouncedField = UniversalImporter.class.getDeclaredField("debouncedWorkspaceSave");
        debouncedField.setAccessible(true);
        DebouncedSwingAction debounced = (DebouncedSwingAction) debouncedField.get(importer);
        Field timerField = DebouncedSwingAction.class.getDeclaredField("timer");
        timerField.setAccessible(true);
        Timer timer = (Timer) timerField.get(debounced);
        timer.setInitialDelay(delayMs);
        timer.setDelay(delayMs);
    }
}
