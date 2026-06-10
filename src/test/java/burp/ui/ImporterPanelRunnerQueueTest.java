package burp.ui;

import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerPreviewRow;
import burp.runner.CollectionRunner;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelRunnerQueueTest {

    @Test
    void clearRunnerClearsResultsTimelineLogAndQueuedRequests() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("Checkout", request("Queued 1"), request("Queued 2"));
        panel.restoreWorkspaceCollections(List.of(collection));

        List<ApiRequest> queued = queue(panel, request("Queued 1"), request("Queued 2"));
        assertThat(queued).hasSize(2);

        RunnerResultHelper.addDummyRunnerData(panel);

        invokePrivate(panel, "clearRunnerFromUi");
        drainEdt();

        assertThat(queue(panel)).isEmpty();
        assertThat(resultModel(panel).getRowCount()).isZero();
        assertThat(timelineModel(panel).getRowCount()).isZero();
        assertThat(runnerLog(panel).getText()).isEmpty();
        assertThat(((JButton) privateField(panel, "startRunnerBtn")).isEnabled()).isFalse();
    }

    @Test
    void queueRunnerRequestsReplacesPriorQueueAndEnablesStart() throws Exception {
        ImporterPanel panel = newPanel();
        panel.restoreWorkspaceCollections(List.of(collection("Checkout", request("One"), request("Two"), request("Three"))));

        queue(panel, request("One"), request("Two"), request("Three"));
        assertThat(queue(panel)).extracting(r -> ((ApiRequest) r).name).containsExactly("One", "Two", "Three");

        queue(panel, request("Four"), request("Five"));
        assertThat(queue(panel)).extracting(r -> ((ApiRequest) r).name).containsExactly("Four", "Five");
        assertThat(((JButton) privateField(panel, "startRunnerBtn")).isEnabled()).isTrue();
    }

    @Test
    void startRunnerDoesNotRunWhenQueueCleared() throws Exception {
        CollectionRunner runner = Mockito.mock(CollectionRunner.class, Mockito.RETURNS_DEEP_STUBS);
        ImporterPanel panel = newPanel(runner);
        panel.restoreWorkspaceCollections(List.of(collection("Checkout", request("One"))));

        queue(panel, request("One"));
        invokePrivate(panel, "clearRunnerFromUi");
        drainEdt();

        invokePrivate(panel, "startRunner", new Class<?>[]{boolean.class}, false);
        drainEdt();

        Mockito.verify(runner, Mockito.never()).runCollections(Mockito.anyList(), Mockito.anyList());
        assertThat(runnerLog(panel).getText()).contains("No requests queued");
        assertThat(((JButton) privateField(panel, "startRunnerBtn")).isEnabled()).isFalse();
    }

    @Test
    void runnerPreviewRemoveSelectedRequestsUpdatesQueue() {
        List<ApiRequest> selected = new ArrayList<>(List.of(request("One"), request("Two"), request("Three")));
        List<RunnerPreviewRow> previewRows = new ArrayList<>(List.of(previewRow("One"), previewRow("Two"), previewRow("Three")));
        List<ApiRequest> queued = new ArrayList<>(selected);

        ImporterPanel.removeRunnerPreviewRows(selected, previewRows, queued, 1);

        assertThat(selected).extracting(r -> r.name).containsExactly("One", "Three");
        assertThat(previewRows).extracting(r -> r.requestName).containsExactly("One", "Three");
        assertThat(queued).extracting(r -> r.name).containsExactly("One", "Three");
    }

    @Test
    void runnerPreviewClearQueueClearsQueuedRequests() {
        List<ApiRequest> selected = new ArrayList<>(List.of(request("One"), request("Two")));
        List<RunnerPreviewRow> previewRows = new ArrayList<>(List.of(previewRow("One"), previewRow("Two")));
        List<ApiRequest> queued = new ArrayList<>(selected);

        ImporterPanel.clearRunnerPreviewQueue(selected, previewRows, queued);

        assertThat(selected).isEmpty();
        assertThat(previewRows).isEmpty();
        assertThat(queued).isEmpty();
    }

    @Test
    void oauth2AutoBindDefaultsUnchecked() throws Exception {
        ImporterPanel panel = newPanel();
        assertThat(oauth2Panel(panel).getAutoBindCheckBox().isSelected()).isFalse();

        EnvironmentProfile active = environment("UAT");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);
        drainEdt();

        assertThat(oauth2Panel(panel).getAutoBindCheckBox().isSelected()).isFalse();
    }

    private static ImporterPanel newPanel() {
        return newPanel(Mockito.mock(CollectionRunner.class, Mockito.RETURNS_DEEP_STUBS));
    }

    private static ImporterPanel newPanel(CollectionRunner runner) {
        burp.UniversalImporter importer = Mockito.mock(burp.UniversalImporter.class, Mockito.RETURNS_DEEP_STUBS);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpRequestEditor(Mockito.any(EditorOptions.class))).thenReturn(requestEditor);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpResponseEditor(Mockito.any(EditorOptions.class))).thenReturn(responseEditor);
        return new ImporterPanel(importer, runner, Mockito.mock(burp.auth.OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS), burp.utils.ScriptMode.DISABLED);
    }

    private static ApiCollection collection(String name, ApiRequest... requests) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        for (ApiRequest req : requests) {
            if (req != null) {
                req.sourceCollection = name;
                collection.requests.add(req);
            }
        }
        return collection;
    }

    private static ApiRequest request(String name) {
        ApiRequest request = new ApiRequest();
        request.name = name;
        request.method = "GET";
        request.url = "https://example.test/" + name.toLowerCase();
        return request;
    }

    private static RunnerPreviewRow previewRow(String name) {
        RunnerPreviewRow row = new RunnerPreviewRow();
        row.requestName = name;
        row.collectionName = "Checkout";
        row.method = "GET";
        row.urlPreview = "https://example.test/" + name.toLowerCase();
        return row;
    }

    @SuppressWarnings("unchecked")
    private static List<ApiRequest> queue(ImporterPanel panel, ApiRequest... requests) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("queueRunnerRequests", List.class);
        method.setAccessible(true);
        List<ApiRequest> selected = new ArrayList<>();
        for (ApiRequest request : requests) {
            selected.add(request);
        }
        method.invoke(panel, selected);
        drainEdt();
        return (List<ApiRequest>) privateField(panel, "runnerQueuedRequests");
    }

    @SuppressWarnings("unchecked")
    private static List<ApiRequest> queue(ImporterPanel panel) throws Exception {
        return (List<ApiRequest>) privateField(panel, "runnerQueuedRequests");
    }

    private static RunnerResultTableModel resultModel(ImporterPanel panel) throws Exception {
        return (RunnerResultTableModel) privateField(panel, "resultModel");
    }

    private static RunnerTimelineTableModel timelineModel(ImporterPanel panel) throws Exception {
        return (RunnerTimelineTableModel) privateField(panel, "timelineModel");
    }

    private static JTextArea runnerLog(ImporterPanel panel) throws Exception {
        return (JTextArea) privateField(panel, "runnerLog");
    }

    private static OAuth2Panel oauth2Panel(ImporterPanel panel) throws Exception {
        return (OAuth2Panel) privateField(panel, "oauth2Panel");
    }

    private static EnvironmentProfile environment(String name) {
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = name;
        profile.ensureId();
        profile.ensureDefaults();
        return profile;
    }

    private static void invokePrivate(ImporterPanel panel, String methodName) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(panel);
    }

    private static void invokePrivate(ImporterPanel panel, String methodName, Class<?>[] parameterTypes, Object arg) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        method.invoke(panel, arg);
    }

    private static Object privateField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void drainEdt() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
    }

    private static final class RunnerResultHelper {
        static void addDummyRunnerData(ImporterPanel panel) throws Exception {
            RunnerResultTableModel resultTableModel = resultModel(panel);
            burp.models.RunnerResult result = new burp.models.RunnerResult();
            result.requestName = "Queued 1";
            result.method = "GET";
            result.path = "/queued-1";
            result.host = "example.test";
            result.success = true;
            result.statusCode = 200;
            resultTableModel.addResult(result);

            RunnerTimelineTableModel timelineTableModel = timelineModel(panel);
            burp.models.RunnerTimelineRow row = new burp.models.RunnerTimelineRow();
            row.requestName = "Queued 1";
            row.collectionName = "Checkout";
            row.status = "OK";
            timelineTableModel.addRow(row);

            runnerLog(panel).setText("old log");
        }
    }
}
