package burp.testsupport;

import burp.UniversalImporter;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.auth.OAuth2Manager;
import burp.runner.CollectionRunner;
import burp.ui.ImporterPanel;
import org.mockito.Mockito;

import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public final class ImporterPanelTestSupport {
    private ImporterPanelTestSupport() {
    }

    public static PanelBundle newBundle() throws Exception {
        MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        when(api.userInterface().createHttpRequestEditor(any(EditorOptions.class))).thenAnswer(inv -> {
            HttpRequestEditor editor = Mockito.mock(HttpRequestEditor.class);
            when(editor.uiComponent()).thenReturn(new JPanel());
            return editor;
        });
        when(api.userInterface().createHttpResponseEditor(any(EditorOptions.class))).thenAnswer(inv -> {
            HttpResponseEditor editor = Mockito.mock(HttpResponseEditor.class);
            when(editor.uiComponent()).thenReturn(new JPanel());
            return editor;
        });

        UniversalImporter importer = Mockito.mock(UniversalImporter.class, Mockito.RETURNS_DEEP_STUBS);
        when(importer.getApi()).thenReturn(api);

        CollectionRunner runner = new CollectionRunner(null);
        OAuth2Manager oauth2Manager = Mockito.mock(OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS);
        ImporterPanel panel = new ImporterPanel(importer, runner, oauth2Manager, burp.utils.ScriptMode.DISABLED);
        return new PanelBundle(api, importer, runner, oauth2Manager, panel);
    }

    public static <T> T getField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            T value = (T) field.get(target);
            return value;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            T value = (T) method.invoke(target, args);
            return value;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void invokeVoid(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        invoke(target, methodName, parameterTypes, args);
    }

    public static RequestEditorPanelAccess requestEditor(ImporterPanel panel) {
        return new RequestEditorPanelAccess(getField(panel, "requestEditor"));
    }

    public static void awaitEdt() {
        try {
            SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void awaitCondition(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            awaitEdt();
            try {
                TimeUnit.MILLISECONDS.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("Condition was not met within " + timeout.toMillis() + " ms");
    }

    public static final class PanelBundle {
        public final MontoyaApi api;
        public final UniversalImporter importer;
        public final CollectionRunner runner;
        public final OAuth2Manager oauth2Manager;
        public final ImporterPanel panel;

        private PanelBundle(MontoyaApi api,
                            UniversalImporter importer,
                            CollectionRunner runner,
                            OAuth2Manager oauth2Manager,
                            ImporterPanel panel) {
            this.api = api;
            this.importer = importer;
            this.runner = runner;
            this.oauth2Manager = oauth2Manager;
            this.panel = panel;
        }
    }

    public static final class RequestEditorPanelAccess {
        private final Object panel;

        private RequestEditorPanelAccess(Object panel) {
            this.panel = panel;
        }

        public Object raw() {
            return panel;
        }
    }
}
