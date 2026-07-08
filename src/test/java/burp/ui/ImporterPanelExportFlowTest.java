package burp.ui;

import burp.UniversalImporter;
import burp.auth.OAuth2Manager;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.runner.CollectionRunner;
import burp.testsupport.ImporterPanelTestSupport;
import burp.ui.tree.CollectionTreeNode;
import burp.utils.ScriptMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ImporterPanelExportFlowTest {
    @TempDir
    Path tempDir;

    @Test
    void collectionContextMenuIncludesExportOnlyForCollectionNodes() {
        ImporterPanel panel = newPanel();

        JPopupMenu collectionMenu = panel.buildRequestTreeContextMenu(new CollectionTreeNode(collection("APIM")));
        assertThat(labels(collectionMenu)).containsExactly(
                "New Folder",
                "New Request",
                "Rename",
                "Duplicate",
                "Delete",
                "Export...",
                "Auth Settings..."
        );

        CollectionTreeNode folderNode = new CollectionTreeNode("Auth");
        folderNode.folderPath = "Auth";
        JPopupMenu folderMenu = panel.buildRequestTreeContextMenu(folderNode);
        assertThat(labels(folderMenu)).containsExactly(
                "New Folder",
                "New Request",
                "Rename",
                "Duplicate",
                "Delete",
                "Auth Settings..."
        );
        assertThat(labels(folderMenu)).doesNotContain("Export...");

        CollectionTreeNode requestNode = new CollectionTreeNode(request("req-1", "Login", "POST", "https://api.example.test/login"));
        JPopupMenu requestMenu = panel.buildRequestTreeContextMenu(requestNode);
        assertThat(labels(requestMenu)).containsExactly(
                "Rename",
                "Duplicate",
                "Delete",
                "Auth Settings..."
        );
        assertThat(labels(requestMenu)).doesNotContain("Export...");

        JPopupMenu rootMenu = panel.buildRequestTreeContextMenu(null);
        assertThat(labels(rootMenu)).containsExactly("New Collection");
    }

    @Test
    void exportUnresolvedDialogUsesExportOnlyQuickEntryConfig() {
        ImporterPanel panel = newPanel();
        ImporterPanel.UnresolvedDialogConfig config = panel.buildExportUnresolvedDialogConfig();

        assertThat(config.canApply).isFalse();
        assertThat(config.applyButtonEnabled).isTrue();
        assertThat(config.applyButtonText).isEqualTo("Use for Export");
        assertThat(config.hintText).contains("apply only to this export");
    }

    @Test
    void collectionExportDialogUsesSaveAsAndCancelWithoutPathField() {
        ImporterPanel panel = newPanel();
        ImporterPanel.CollectionExportDialogConfig config = panel.buildCollectionExportDialogConfig(collection("APIM"));

        assertThat(findTextFields(config.panel)).isEmpty();
        assertThat(buttonTexts(config.panel)).containsExactly("Cancel", "Save As");
        assertThat(checkboxTexts(config.panel)).containsExactly("Resolve variables using active environment");
        assertThat(checkboxTexts(config.panel)).doesNotContain(
                "Include runtime variables",
                "Include OAuth2 runtime tokens",
                "Pretty print JSON",
                "Include disabled/suppressed headers metadata"
        );
    }

    @Test
    void environmentExportDialogUsesSaveAsAndCancelWithoutPathField() {
        ImporterPanel panel = newPanel();
        ImporterPanel.EnvironmentExportDialogConfig config = panel.buildEnvironmentExportDialogConfig(environment("UAT"));

        assertThat(findTextFields(config.panel)).isEmpty();
        assertThat(buttonTexts(config.panel)).containsExactly("Cancel", "Save As");
        assertThat(checkboxTexts(config.panel)).isEmpty();
    }

    @Test
    void cancelledCollectionExportDoesNotWriteOutputFile() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collection = collection("APIM");
        Path output = tempDir.resolve("cancelled.api-workbench.collection.json");

        var result = panel.performCollectionExport(
                collection,
                burp.exporter.CollectionExportFormat.API_WORKBENCH_JSON,
                output,
                false,
                null,
                Map.of(),
                true
        );

        assertThat(result).isNull();
        assertThat(Files.exists(output)).isFalse();
    }

    @Test
    void cancelledEnvironmentExportDoesNotWriteOutputFile() throws Exception {
        ImporterPanel panel = newPanel();
        var profile = environment("UAT");
        Path output = tempDir.resolve("cancelled.api-workbench.environment.json");

        var result = panel.performEnvironmentExport(
                profile,
                burp.exporter.EnvironmentExportFormat.API_WORKBENCH_JSON,
                output,
                true
        );

        assertThat(result).isNull();
        assertThat(Files.exists(output)).isFalse();
    }

    @Test
    void collectionExportWorkerPerformsExportOffEdt() throws Exception {
        ImporterPanel panel = Mockito.spy(newPanel());
        ApiCollection collection = collection("APIM");
        Path output = tempDir.resolve("collection.api-workbench.collection.json");
        AtomicReference<Boolean> calledOnEdt = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            calledOnEdt.set(SwingUtilities.isEventDispatchThread());
            return new burp.exporter.ExportResult(output, "API Workbench", 0, 0, 0, List.of());
        }).when(panel).performCollectionExport(
                Mockito.same(collection),
                Mockito.eq(burp.exporter.CollectionExportFormat.API_WORKBENCH_JSON),
                Mockito.eq(output),
                Mockito.eq(false),
                Mockito.isNull(),
                Mockito.anyMap(),
                Mockito.eq(false)
        );

        SwingWorker<burp.exporter.ExportResult, Void> worker = panel.startCollectionExportWorker(
                collection,
                burp.exporter.CollectionExportFormat.API_WORKBENCH_JSON,
                output,
                false,
                null,
                Map.of()
        );

        assertThat(worker.get(5, TimeUnit.SECONDS).outputPath).isEqualTo(output);
        ImporterPanelTestSupport.awaitEdt();
        assertThat(calledOnEdt.get()).isFalse();
    }

    @Test
    void environmentExportWorkerPerformsExportOffEdt() throws Exception {
        ImporterPanel panel = Mockito.spy(newPanel());
        var profile = environment("UAT");
        Path output = tempDir.resolve("environment.api-workbench.environment.json");
        AtomicReference<Boolean> calledOnEdt = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            calledOnEdt.set(SwingUtilities.isEventDispatchThread());
            return new burp.exporter.ExportResult(output, "API Workbench", 0, 1, 0, List.of());
        }).when(panel).performEnvironmentExport(
                Mockito.same(profile),
                Mockito.eq(burp.exporter.EnvironmentExportFormat.API_WORKBENCH_JSON),
                Mockito.eq(output),
                Mockito.eq(false)
        );

        SwingWorker<burp.exporter.ExportResult, Void> worker = panel.startEnvironmentExportWorker(
                profile,
                burp.exporter.EnvironmentExportFormat.API_WORKBENCH_JSON,
                output
        );

        assertThat(worker.get(5, TimeUnit.SECONDS).outputPath).isEqualTo(output);
        ImporterPanelTestSupport.awaitEdt();
        assertThat(calledOnEdt.get()).isFalse();
    }

    @Test
    void environmentImportWorkerParsesFileOffEdt() throws Exception {
        ImporterPanel panel = Mockito.spy(newPanel());
        File file = tempDir.resolve("environment.json").toFile();
        AtomicReference<Boolean> calledOnEdt = new AtomicReference<>();
        var imported = environment("Imported");
        imported.variables.put("base_url", "https://api.example.test");
        assertThat(imported.variables).isNotEmpty();
        Mockito.doAnswer(invocation -> {
            calledOnEdt.set(SwingUtilities.isEventDispatchThread());
            return List.of(imported);
        }).when(panel).importEnvironmentProfiles(Mockito.same(file));

        SwingWorker<List<burp.models.EnvironmentProfile>, Void> worker = panel.startEnvironmentImportWorker(file);

        assertThat(worker.get(5, TimeUnit.SECONDS)).containsExactly(imported);
        ImporterPanelTestSupport.awaitEdt();
        assertThat(calledOnEdt.get()).isFalse();
    }

    private static ImporterPanel newPanel() {
        UniversalImporter importer = Mockito.mock(UniversalImporter.class);
        MontoyaApi api = Mockito.mock(MontoyaApi.class);
        UserInterface userInterface = Mockito.mock(UserInterface.class);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        when(requestEditor.uiComponent()).thenReturn(new JPanel());
        when(responseEditor.uiComponent()).thenReturn(new JPanel());
        when(userInterface.createHttpRequestEditor(Mockito.any())).thenReturn(requestEditor);
        when(userInterface.createHttpResponseEditor(Mockito.any())).thenReturn(responseEditor);
        when(api.userInterface()).thenReturn(userInterface);
        when(importer.getApi()).thenReturn(api);
        CollectionRunner runner = Mockito.mock(CollectionRunner.class);
        OAuth2Manager oauth2Manager = Mockito.mock(OAuth2Manager.class);
        return new ImporterPanel(importer, runner, oauth2Manager, ScriptMode.FULL_JS);
    }

    private static ApiCollection collection(String name) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        return collection;
    }

    private static burp.models.EnvironmentProfile environment(String name) {
        burp.models.EnvironmentProfile profile = new burp.models.EnvironmentProfile();
        profile.name = name;
        profile.ensureId();
        profile.ensureDefaults();
        return profile;
    }

    private static ApiRequest request(String id, String name, String method, String url) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.method = method;
        request.url = url;
        request.path = "";
        request.sourceCollection = "APIM";
        return request;
    }

    private static List<String> labels(JPopupMenu menu) {
        List<String> labels = new ArrayList<>();
        if (menu == null) {
            return labels;
        }
        for (int i = 0; i < menu.getComponentCount(); i++) {
            if (menu.getComponent(i) instanceof JMenuItem item) {
                labels.add(item.getText());
            }
        }
        return labels;
    }

    private static List<JTextField> findTextFields(Container container) {
        return findComponents(container, JTextField.class);
    }

    private static List<String> buttonTexts(Container container) {
        List<String> texts = new ArrayList<>();
        for (JButton button : findComponents(container, JButton.class)) {
            if (button.getText() != null && !button.getText().isBlank()) {
                texts.add(button.getText());
            }
        }
        return texts;
    }

    private static List<String> checkboxTexts(Container container) {
        List<String> texts = new ArrayList<>();
        for (JCheckBox box : findComponents(container, JCheckBox.class)) {
            if (box.getText() != null && !box.getText().isBlank()) {
                texts.add(box.getText());
            }
        }
        return texts;
    }

    private static <T extends Component> List<T> findComponents(Container container, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (container == null) {
            return found;
        }
        for (Component component : container.getComponents()) {
            if (type.isInstance(component)) {
                found.add(type.cast(component));
            }
            if (component instanceof Container child) {
                found.addAll(findComponents(child, type));
            }
        }
        return found;
    }
}
