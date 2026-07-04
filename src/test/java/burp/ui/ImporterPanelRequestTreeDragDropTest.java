package burp.ui;

import burp.UniversalImporter;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.auth.OAuth2Manager;
import burp.exporter.CollectionExportFormat;
import burp.exporter.CollectionExportOptions;
import burp.exporter.CollectionExportService;
import burp.exporter.EnvironmentExportFormat;
import burp.exporter.EnvironmentExportOptions;
import burp.exporter.EnvironmentExportService;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.runner.CollectionRunner;
import burp.ui.tree.CollectionTreeNode;
import burp.ui.tree.RequestTreeDragPayload;
import burp.ui.tree.RequestTreeTransferHandler;
import burp.ui.tree.TreeDropRequest;
import burp.utils.ScriptMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ImporterPanelRequestTreeDragDropTest {
    @TempDir
    Path tempDir;

    @Test
    void requestTreeInstallsDragAndDropTransferHandler() throws Exception {
        ImporterPanel panel = newPanel();

        JTree tree = requestTree(panel);
        assertThat(tree.getTransferHandler()).isInstanceOf(RequestTreeTransferHandler.class);
        assertThat(tree.getDropMode()).isEqualTo(DropMode.ON_OR_INSERT);
    }

    @Test
    void fileDropImportsSupportedCollectionFormatsAndSkipsUnsupportedFiles() throws Exception {
        ImporterPanel panel = newPanel();
        restoreCollections(panel, List.of(collection("Existing")));

        List<File> files = new ArrayList<>();
        files.add(exportCollectionFile(sampleCollection(), CollectionExportFormat.API_WORKBENCH_JSON, "native.api-workbench.collection.json"));
        files.add(exportCollectionFile(sampleCollection(), CollectionExportFormat.POSTMAN_JSON, "postman.postman_collection.json"));
        files.add(exportCollectionFile(sampleCollection(), CollectionExportFormat.OPENAPI_JSON, "openapi.openapi.json"));
        files.add(exportCollectionFile(sampleCollection(), CollectionExportFormat.OPENAPI_YAML, "openapi.openapi.yaml"));
        files.add(exportCollectionFile(sampleCollection(), CollectionExportFormat.INSOMNIA_JSON, "insomnia.insomnia.json"));
        files.add(exportCollectionFile(sampleCollection(), CollectionExportFormat.HAR_JSON, "archive.har"));
        files.add(createBrunoCollectionDirectory());
        files.add(writeTextFile("notes.txt", "not a supported collection"));
        files.add(exportEnvironmentFile(environment("UAT"), "uat.api-workbench.environment.json"));

        ImporterPanel.DropImportResult result = invokeOnEdt(() -> panel.importCollectionFilesDroppedOnRequestTree(files));
        drainEdt();

        assertThat(result.importedCount).isEqualTo(7);
        assertThat(result.failedCount).isEqualTo(2);
        assertThat(result.importedCollections).hasSize(7);
        assertThat(result.messages).anySatisfy(message -> assertThat(message).contains("Skipped unsupported file: notes.txt"));
        assertThat(result.messages).anySatisfy(message -> assertThat(message).contains("Skipped unsupported file: uat.api-workbench.environment.json"));
        assertThat(loadedCollections(panel)).hasSize(8);
        assertThat(loadedCollections(panel).get(0).name).isEqualTo("Existing");
        assertThat(requestTree(panel).getSelectionPath()).isNotNull();
        assertThat(requestTree(panel).getSelectionPath().getLastPathComponent()).isInstanceOf(CollectionTreeNode.class);
        assertThat(importLogText(panel)).contains("Imported collection:");
        assertThat(importLogText(panel)).contains("Drop import complete: 7 imported, 2 failed.");
    }

    @Test
    void environmentFileDroppedOnRequestTreeIsRejectedWithoutMutatingTree() throws Exception {
        ImporterPanel panel = newPanel();
        restoreCollections(panel, List.of(collection("Existing")));

        File envFile = exportEnvironmentFile(environment("UAT"), "uat.api-workbench.environment.json");
        ImporterPanel.DropImportResult result = invokeOnEdt(() -> panel.importCollectionFilesDroppedOnRequestTree(List.of(envFile)));
        drainEdt();

        assertThat(result.importedCount).isZero();
        assertThat(result.failedCount).isOne();
        assertThat(loadedCollections(panel)).extracting(col -> col.name).containsExactly("Existing");
        assertThat(importLogText(panel)).contains("Skipped unsupported file: uat.api-workbench.environment.json");
    }

    @Test
    void collectionReorderMovesBeforeTargetCollection() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection apim = collection("APIM");
        ApiCollection admin = collection("Admin");
        ApiCollection archive = collection("Archive");
        restoreCollections(panel, List.of(apim, admin, archive));
        drainEdt();

        CollectionTreeNode targetNode = collectionNode(requestTree(panel), "APIM");
        RequestTreeDragPayload payload = RequestTreeDragPayload.forCollection(archive);
        TreeDropRequest dropRequest = new TreeDropRequest(payload, null, targetNode, "", 0, TreeDropRequest.DropPosition.ON_COLLECTION);

        assertThat(panel.canAcceptRequestTreeDrop(dropRequest)).isTrue();
        assertThat(panel.handleRequestTreeDrop(dropRequest)).isTrue();
        drainEdt();

        assertThat(loadedCollections(panel)).extracting(col -> col.name)
                .containsExactly("Archive", "APIM", "Admin");
    }

    @Test
    void movingRequestBetweenCollectionsPreservesRunnerQueueAndLatestSendSnapshot() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection source = collection("APIM");
        source.folderPaths.add("Auth");
        ApiRequest request = request("req-login", "Login", "Auth", "POST", "https://api.example.test/login");
        source.requests.add(request);
        ApiCollection target = collection("Archive");
        restoreCollections(panel, List.of(source, target));
        drainEdt();

        selectTreeNode(panel, requestNode(requestTree(panel), "req-login"));
        drainEdt();

        ImporterPanel.WorkbenchSendSnapshot snapshot = new ImporterPanel.WorkbenchSendSnapshot(
                mockRequest(),
                mockResponse(),
                "META REQUEST",
                null,
                "Send",
                System.currentTimeMillis()
        );
        panel.applyWorkbenchSendSnapshot(request, source, snapshot);
        runnerQueuedRequests(panel).add(request);
        assertThat(panel.getWorkbenchSendSnapshot(request)).isNotNull();

        CollectionTreeNode targetNode = collectionNode(requestTree(panel), "Archive");
        TreeDropRequest dropRequest = new TreeDropRequest(
                RequestTreeDragPayload.forRequest(source, request),
                target,
                targetNode,
                "",
                0,
                TreeDropRequest.DropPosition.ON_COLLECTION
        );

        assertThat(panel.canAcceptRequestTreeDrop(dropRequest)).isTrue();
        assertThat(panel.handleRequestTreeDrop(dropRequest)).isTrue();
        drainEdt();

        assertThat(request.sourceCollection).isEqualTo("Archive");
        assertThat(request.path).isBlank();
        assertThat(runnerQueuedRequests(panel)).containsExactly(request);
        assertThat(panel.getWorkbenchSendSnapshot(request)).isNotNull();
        assertThat(panel.getWorkbenchDetailMetaTextForTest()).startsWith("META REQUEST");
        assertThat(requestEditor(panel).getCurrentRequest()).isSameAs(request);
        assertThat(requestEditor(panel).getCurrentCollection()).isSameAs(target);
        assertThat(requestEditor(panel).isSendEnabled()).isTrue();
    }

    @Test
    void movingFolderBetweenCollectionsPreservesRunnerQueueAndLatestSendSnapshotForChildRequest() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection source = collection("APIM");
        source.folderPaths.add("Auth");
        source.folderPaths.add("Auth/OAuth");
        ApiRequest request = request("req-token", "Get Token", "Auth/OAuth", "POST", "https://api.example.test/token");
        source.requests.add(request);
        ApiCollection target = collection("Archive");
        target.folderPaths.add("Admin");
        restoreCollections(panel, List.of(source, target));
        drainEdt();

        selectTreeNode(panel, requestNode(requestTree(panel), "req-token"));
        drainEdt();

        ImporterPanel.WorkbenchSendSnapshot snapshot = new ImporterPanel.WorkbenchSendSnapshot(
                mockRequest(),
                mockResponse(),
                "META FOLDER",
                null,
                "Send",
                System.currentTimeMillis()
        );
        panel.applyWorkbenchSendSnapshot(request, source, snapshot);
        runnerQueuedRequests(panel).add(request);
        assertThat(panel.getWorkbenchSendSnapshot(request)).isNotNull();

        CollectionTreeNode targetNode = folderNodeByPath(requestTree(panel), "Admin");
        TreeDropRequest dropRequest = new TreeDropRequest(
                RequestTreeDragPayload.forFolder(source, "Auth"),
                target,
                targetNode,
                "Admin",
                0,
                TreeDropRequest.DropPosition.ON_FOLDER
        );

        assertThat(panel.canAcceptRequestTreeDrop(dropRequest)).isTrue();
        assertThat(panel.handleRequestTreeDrop(dropRequest)).isTrue();
        drainEdt();

        assertThat(request.sourceCollection).isEqualTo("Archive");
        assertThat(request.path).isEqualTo("Admin/Auth/OAuth");
        assertThat(runnerQueuedRequests(panel)).containsExactly(request);
        assertThat(panel.getWorkbenchSendSnapshot(request)).isNotNull();
        assertThat(panel.getWorkbenchDetailMetaTextForTest()).startsWith("META FOLDER");
        assertThat(requestEditor(panel).getCurrentRequest()).isSameAs(request);
        assertThat(requestEditor(panel).getCurrentCollection()).isSameAs(target);
        assertThat(folderNodeByPath(requestTree(panel), "Admin/Auth")).isNotNull();
    }

    @Test
    void invalidDropsAreRejected() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection source = collection("APIM");
        source.folderPaths.add("Auth");
        ApiRequest request = request("req-login", "Login", "", "GET", "https://api.example.test/login");
        source.requests.add(request);
        restoreCollections(panel, List.of(source));
        drainEdt();

        CollectionTreeNode collectionNode = collectionNode(requestTree(panel), "APIM");
        CollectionTreeNode folderNode = folderNodeByPath(requestTree(panel), "Auth");
        CollectionTreeNode requestNode = requestNode(requestTree(panel), "req-login");

        assertThat(panel.canAcceptRequestTreeDrop(new TreeDropRequest(
                RequestTreeDragPayload.forCollection(source),
                null,
                collectionNode,
                "",
                0,
                TreeDropRequest.DropPosition.ON_COLLECTION))).isFalse();

        assertThat(panel.canAcceptRequestTreeDrop(new TreeDropRequest(
                RequestTreeDragPayload.forFolder(source, "Auth"),
                source,
                folderNode,
                "Auth",
                0,
                TreeDropRequest.DropPosition.ON_FOLDER))).isFalse();

        assertThat(panel.canAcceptRequestTreeDrop(new TreeDropRequest(
                RequestTreeDragPayload.forRequest(source, request),
                source,
                requestNode,
                "",
                0,
                TreeDropRequest.DropPosition.ON_REQUEST))).isFalse();
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

    private File exportCollectionFile(ApiCollection collection, CollectionExportFormat format, String fileName) throws Exception {
        Path output = tempDir.resolve(fileName);
        new CollectionExportService().exportCollection(
                collection,
                new CollectionExportOptions(format, output, false, null, Map.of())
        );
        return output.toFile();
    }

    private File exportEnvironmentFile(EnvironmentProfile profile, String fileName) throws Exception {
        Path output = tempDir.resolve(fileName);
        new EnvironmentExportService().exportEnvironment(
                profile,
                new EnvironmentExportOptions(EnvironmentExportFormat.API_WORKBENCH_JSON, output)
        );
        return output.toFile();
    }

    private File createBrunoCollectionDirectory() throws Exception {
        Path root = tempDir.resolve("bruno-import");
        Path collectionRoot = root.resolve("APIM");
        Path authDir = collectionRoot.resolve("Auth");
        Files.createDirectories(authDir);
        Files.writeString(collectionRoot.resolve("bruno.json"), """
                {
                  "name": "APIM"
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(authDir.resolve("Login.bru"), """
                meta {
                  name: Login
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/login
                }
                """, StandardCharsets.UTF_8);
        return collectionRoot.toFile();
    }

    private File writeTextFile(String fileName, String text) throws Exception {
        Path output = tempDir.resolve(fileName);
        Files.writeString(output, text, StandardCharsets.UTF_8);
        return output.toFile();
    }

    private static ApiCollection collection(String name) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        collection.requests = new ArrayList<>();
        collection.folderPaths = new ArrayList<>();
        collection.variables = new ArrayList<>();
        collection.folderVars = new LinkedHashMap<>();
        collection.environment = new LinkedHashMap<>();
        collection.folderAuthModes = new LinkedHashMap<>();
        collection.folderAuth = new LinkedHashMap<>();
        collection.runtimeVars = new LinkedHashMap<>();
        collection.runtimeOAuth2 = new LinkedHashMap<>();
        return collection;
    }

    private static ApiCollection sampleCollection() {
        ApiCollection collection = collection("APIM");
        collection.description = "Drag/drop sample";
        collection.format = "api-workbench";
        collection.version = "1.0";
        collection.folderPaths.add("Auth");

        ApiRequest request = request("req-login", "Login", "Auth", "POST", "https://api.example.test/login");
        request.headers.add(new ApiRequest.Header("X-Test", "workflow"));
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"login\":true}";
        collection.requests.add(request);
        return collection;
    }

    private static EnvironmentProfile environment(String name) {
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = name;
        profile.ensureId();
        profile.ensureDefaults();
        profile.variables.put("base_url", "https://api.example.test");
        profile.variables.put("token", "live-token");
        return profile;
    }

    private static ApiRequest request(String id, String name, String path, String method, String url) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.path = path;
        request.sourceCollection = "APIM";
        request.method = method;
        request.url = url;
        request.headers = new ArrayList<>();
        request.variables = new ArrayList<>();
        request.preRequestScripts = new ArrayList<>();
        request.postResponseScripts = new ArrayList<>();
        request.suppressedAutoHeaders = new java.util.LinkedHashSet<>();
        return request;
    }

    private static HttpRequest mockRequest() {
        return Mockito.mock(HttpRequest.class);
    }

    private static HttpResponse mockResponse() {
        return Mockito.mock(HttpResponse.class);
    }

    private static void restoreCollections(ImporterPanel panel, List<ApiCollection> collections) throws Exception {
        SwingUtilities.invokeAndWait(() -> panel.restoreWorkspaceCollections(collections));
    }

    private static <T> T invokeOnEdt(java.util.concurrent.Callable<T> callable) throws Exception {
        java.util.concurrent.atomic.AtomicReference<T> ref = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                ref.set(callable.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() != null) {
            if (error.get() instanceof Exception e) {
                throw e;
            }
            if (error.get() instanceof Error e) {
                throw e;
            }
            throw new RuntimeException(error.get());
        }
        return ref.get();
    }

    private static void drainEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    private static void selectTreeNode(ImporterPanel panel, CollectionTreeNode node) throws Exception {
        if (node == null) {
            return;
        }
        SwingUtilities.invokeAndWait(() -> {
            try {
                requestTree(panel).setSelectionPath(new TreePath(node.getPath()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static JTree requestTree(ImporterPanel panel) throws Exception {
        return privateField(panel, "requestTree");
    }

    private static DefaultTreeModel treeModel(ImporterPanel panel) throws Exception {
        return privateField(panel, "treeModel");
    }

    private static List<ApiCollection> loadedCollections(ImporterPanel panel) throws Exception {
        return privateField(panel, "loadedCollections");
    }

    private static List<ApiRequest> runnerQueuedRequests(ImporterPanel panel) throws Exception {
        return privateField(panel, "runnerQueuedRequests");
    }

    private static RequestEditorPanel requestEditor(ImporterPanel panel) throws Exception {
        return privateField(panel, "requestEditor");
    }

    private static String importLogText(ImporterPanel panel) throws Exception {
        JTextArea log = privateField(panel, "importLog");
        return log != null ? log.getText() : "";
    }

    private static <T> T privateField(ImporterPanel panel, String fieldName) throws Exception {
        Field field = ImporterPanel.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        T value = (T) field.get(panel);
        return value;
    }

    private static CollectionTreeNode collectionNode(JTree tree, String collectionName) {
        return findNode(tree, node -> node.getNodeType() == CollectionTreeNode.Type.COLLECTION
                && node.collection != null
                && Objects.equals(node.collection.name, collectionName));
    }

    private static CollectionTreeNode folderNodeByPath(JTree tree, String folderPath) {
        String normalized = burp.ui.tree.RequestTreePathService.normalizeFolderPath(folderPath);
        return findNode(tree, node -> node.getNodeType() == CollectionTreeNode.Type.FOLDER
                && Objects.equals(burp.ui.tree.RequestTreePathService.normalizeFolderPath(node.folderPath), normalized));
    }

    private static CollectionTreeNode requestNode(JTree tree, String requestId) {
        return findNode(tree, node -> node.getNodeType() == CollectionTreeNode.Type.REQUEST
                && node.request != null
                && Objects.equals(node.request.id, requestId));
    }

    private static CollectionTreeNode findNode(JTree tree, java.util.function.Predicate<CollectionTreeNode> predicate) {
        if (tree == null || tree.getModel() == null || tree.getModel().getRoot() == null) {
            return null;
        }
        return findNode((DefaultMutableTreeNode) tree.getModel().getRoot(), predicate);
    }

    private static CollectionTreeNode findNode(DefaultMutableTreeNode node, java.util.function.Predicate<CollectionTreeNode> predicate) {
        if (node instanceof CollectionTreeNode ctn && predicate.test(ctn)) {
            return ctn;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            Object child = node.getChildAt(i);
            if (child instanceof DefaultMutableTreeNode dmtn) {
                CollectionTreeNode found = findNode(dmtn, predicate);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
