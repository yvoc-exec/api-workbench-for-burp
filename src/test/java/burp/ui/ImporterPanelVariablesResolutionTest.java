package burp.ui;

import burp.auth.OAuth2Manager;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.runner.CollectionRunner;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelVariablesResolutionTest {

    @Test
    void requestEditorUsesActiveVariablesTabDraftForCurrentCollection() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = new ApiCollection();
        collection.name = "Alpha";

        ApiRequest request = new ApiRequest();
        request.name = "Auth request";
        request.method = "GET";
        request.url = "https://api.example.test/auth";
        request.auth = new ApiRequest.Auth();
        request.auth.type = "bearer";
        request.auth.properties.put("token", "{{token}}");
        collection.requests.add(request);

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection", new Class<?>[0]);

        JTextArea envVarsArea = (JTextArea) privateField(panel, "envVarsArea");
        runOnEdt(() -> envVarsArea.setText("# Runtime overrides (edits apply here)\n" +
                "token=abc123\n"));

        RequestEditorPanel requestEditor = (RequestEditorPanel) privateField(panel, "requestEditor");
        requestEditor.setCurrentCollection(collection);
        requestEditor.loadRequest(request);

        invokePrivateMethod(panel, "syncRequestEditorRuntimeContext",
                new Class<?>[]{ApiRequest.class, ApiCollection.class}, request, collection);

        JTextArea resolvedView = (JTextArea) privateField(requestEditor, "resolvedViewArea");
        assertThat(resolvedView.getText()).contains("Authorization: Bearer abc123");
    }

    @Test
    void requestEditorResolvedViewUpdatesWhenVariablesRawDraftChangesWithoutSaveNow() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = new ApiCollection();
        collection.name = "Alpha";
        ApiRequest request = bearerRequest();
        collection.requests.add(request);

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection", new Class<?>[0]);

        RequestEditorPanel requestEditor = (RequestEditorPanel) privateField(panel, "requestEditor");
        requestEditor.setCurrentCollection(collection);
        requestEditor.loadRequest(request);
        invokePrivateMethod(panel, "syncRequestEditorRuntimeContext",
                new Class<?>[]{ApiRequest.class, ApiCollection.class}, request, collection);

        JTextArea resolvedView = (JTextArea) privateField(requestEditor, "resolvedViewArea");
        assertThat(resolvedView.getText()).contains("Authorization: Bearer {{token}}");

        JTextArea envVarsArea = (JTextArea) privateField(panel, "envVarsArea");
        runOnEdt(() -> envVarsArea.setText("# Runtime overrides (edits apply here)\n" +
                "token=abc123\n"));

        assertThat(resolvedView.getText()).contains("Authorization: Bearer abc123");
    }

    @Test
    void workbenchSendUsesActiveVariablesTabDraftForCurrentCollection() throws Exception {
        CapturedImporter importer = newCapturedImporter();
        ImporterPanel panel = newPanel(importer.importer);
        ApiCollection collection = new ApiCollection();
        collection.name = "Alpha";

        ApiRequest request = bearerRequest();
        collection.requests.add(request);

        importer.sendLatch = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            assertThat(collection.runtimeVars).containsEntry("token", "abc123");
            importer.sendLatch.countDown();
            return new burp.UniversalImporter.SingleSendResult(null, null);
        }).when(importer.importer).sendSingleRequestWithBuiltRequest(Mockito.any(), Mockito.eq(collection), Mockito.anyBoolean());

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection", new Class<?>[0]);
        JTextArea envVarsArea = (JTextArea) privateField(panel, "envVarsArea");
        runOnEdt(() -> envVarsArea.setText("# Runtime overrides (edits apply here)\n" +
                "token=abc123\n"));

        RequestEditorPanel requestEditor = (RequestEditorPanel) privateField(panel, "requestEditor");
        requestEditor.setCurrentCollection(collection);
        requestEditor.loadRequest(request);

        invokePrivateMethod(panel, "executeWorkbenchSend", new Class<?>[0]);

        assertThat(importer.sendLatch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void variablesDraftForDifferentCollectionDoesNotBleedIntoRequestEditor() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection alpha = new ApiCollection();
        alpha.name = "Alpha";
        ApiCollection beta = new ApiCollection();
        beta.name = "Beta";
        panel.restoreWorkspaceCollections(List.of(alpha, beta));

        selectCollection(panel, "varsCollectionCombo", 1);
        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection", new Class<?>[0]);
        JTextArea envVarsArea = (JTextArea) privateField(panel, "envVarsArea");
        runOnEdt(() -> envVarsArea.setText("# Runtime overrides (edits apply here)\n" +
                "token=wrong\n"));

        RequestEditorPanel requestEditor = (RequestEditorPanel) privateField(panel, "requestEditor");
        requestEditor.setCurrentCollection(alpha);
        requestEditor.loadRequest(bearerRequest());

        invokePrivateMethod(panel, "syncRequestEditorRuntimeContext",
                new Class<?>[]{ApiRequest.class, ApiCollection.class}, requestEditor.getCurrentRequest(), alpha);

        JTextArea resolvedView = (JTextArea) privateField(requestEditor, "resolvedViewArea");
        assertThat(resolvedView.getText()).doesNotContain("wrong");
    }

    @Test
    void savedRuntimeVarsStillResolveWhenNoActiveDraftExists() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = new ApiCollection();
        collection.name = "Alpha";
        collection.runtimeVars.put("token", "saved123");
        panel.restoreWorkspaceCollections(List.of(collection));

        selectCollection(panel, "varsCollectionCombo", 0);
        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection", new Class<?>[0]);

        RequestEditorPanel requestEditor = (RequestEditorPanel) privateField(panel, "requestEditor");
        requestEditor.setCurrentCollection(collection);
        requestEditor.loadRequest(bearerRequest());

        invokePrivateMethod(panel, "syncRequestEditorRuntimeContext",
                new Class<?>[]{ApiRequest.class, ApiCollection.class}, requestEditor.getCurrentRequest(), collection);

        JTextArea resolvedView = (JTextArea) privateField(requestEditor, "resolvedViewArea");
        assertThat(resolvedView.getText()).contains("Authorization: Bearer saved123");
    }

    private static ImporterPanel newPanel() {
        return newPanel(newCapturedImporter().importer);
    }

    private static ImporterPanel newPanel(burp.UniversalImporter importer) {
        CollectionRunner runner = Mockito.mock(CollectionRunner.class, Mockito.RETURNS_DEEP_STUBS);
        OAuth2Manager oauth2Manager = Mockito.mock(OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS);
        return new ImporterPanel(importer, runner, oauth2Manager, burp.utils.ScriptMode.DISABLED);
    }

    private static CapturedImporter newCapturedImporter() {
        burp.UniversalImporter importer = Mockito.mock(burp.UniversalImporter.class, Mockito.RETURNS_DEEP_STUBS);
        burp.api.montoya.ui.editor.HttpRequestEditor requestEditor = Mockito.mock(burp.api.montoya.ui.editor.HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpRequestEditor(Mockito.any())).thenReturn(requestEditor);
        burp.api.montoya.ui.editor.HttpResponseEditor responseEditor = Mockito.mock(burp.api.montoya.ui.editor.HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpResponseEditor(Mockito.any())).thenReturn(responseEditor);
        return new CapturedImporter(importer);
    }

    private static ApiRequest bearerRequest() {
        ApiRequest request = new ApiRequest();
        request.name = "Auth request";
        request.method = "GET";
        request.url = "https://api.example.test/auth";
        request.auth = new ApiRequest.Auth();
        request.auth.type = "bearer";
        request.auth.properties.put("token", "{{token}}");
        return request;
    }

    private static void selectCollection(ImporterPanel panel, String fieldName, int index) throws Exception {
        JComboBox<?> combo = (JComboBox<?>) privateField(panel, fieldName);
        combo.setSelectedIndex(index);
    }

    private static void invokePrivateMethod(Object target, String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        method.invoke(target, args);
    }

    private static Object privateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void runOnEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action::run);
    }

    private static final class CapturedImporter {
        private final burp.UniversalImporter importer;
        private CountDownLatch sendLatch = new CountDownLatch(0);

        private CapturedImporter(burp.UniversalImporter importer) {
            this.importer = importer;
        }
    }
}
