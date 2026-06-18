package burp.ui;

import burp.auth.OAuth2Manager;
import burp.diagnostics.DiagnosticEvent;
import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.runner.CollectionRunner;
import burp.ui.tree.CollectionTreeNode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelDiagnosticsTabTest {

    @Test
    void diagnosticsSnapshotIncludesMainRequestTreeAndSafeUiMetadata() throws Exception {
        ImporterPanel panel = newPanel();
        seedDiagnosticsState(panel);

        String snapshot = buildSnapshot(panel);

        assertThat(snapshot).contains("=== Extension / Runtime Info ===");
        assertThat(snapshot).contains("=== UI State Summary ===");
        assertThat(snapshot).contains("=== Main Request Tree Diagnostics ===");
        assertThat(snapshot).contains("requestTree.exists=true");
        assertThat(snapshot).contains("treeUI=");
        assertThat(snapshot).contains("viewport.position=");
        assertThat(snapshot).contains("rowBounds[0]=");
        assertThat(snapshot).contains("rowBounds[3]=");
        assertThat(snapshot).contains("selectedTopLevelTab=Workbench");
    }

    @Test
    void diagnosticsSnapshotDoesNotExposeSensitiveValues() throws Exception {
        ImporterPanel panel = newPanel();
        seedDiagnosticsState(panel);

        String snapshot = buildSnapshot(panel);

        assertThat(snapshot).doesNotContain("access_token");
        assertThat(snapshot).doesNotContain("refresh_token");
        assertThat(snapshot).doesNotContain("client_secret");
        assertThat(snapshot).doesNotContain("secret-value");
        assertThat(snapshot).doesNotContain("Authorization:");
        assertThat(snapshot).contains("runtimeVars.count=");
        assertThat(snapshot).contains("runtimeOAuth2.count=");
    }

    @Test
    void diagnosticsSnapshotIncludesSanitizedRecordedEvents() throws Exception {
        DiagnosticStore.getInstance().clear();
        DiagnosticStore.getInstance().record(DiagnosticEvent.of(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, "test", "Request built")
                .withDetails("Authorization: Bearer token-secret\nCookie: session=abc123\naccess_token=super-secret"));

        ImporterPanel panel = newPanel();
        seedDiagnosticsState(panel);

        String snapshot = buildSnapshot(panel);

        assertThat(snapshot).contains("=== Diagnostics Events ===");
        assertThat(snapshot).contains("=== REQUEST_BUILD (1) ===");
        assertThat(snapshot).contains("Request built");
        assertThat(snapshot).doesNotContain("token-secret");
        assertThat(snapshot).doesNotContain("session=abc123");
        assertThat(snapshot).doesNotContain("super-secret");
        DiagnosticStore.getInstance().clear();
    }

    @Test
    void diagnosticsSnapshotGroupsEventsByOperationWithSummary() throws Exception {
        DiagnosticStore.getInstance().clear();
        DiagnosticStore.getInstance().record(DiagnosticEvent.of(DiagnosticOperation.IMPORT, DiagnosticSeverity.INFO, "test", "Import started"));
        DiagnosticStore.getInstance().record(DiagnosticEvent.of(DiagnosticOperation.EXPORT, DiagnosticSeverity.WARNING, "test", "Export warning"));

        ImporterPanel panel = newPanel();
        seedDiagnosticsState(panel);

        String snapshot = buildSnapshot(panel);

        assertThat(snapshot).contains("=== Diagnostics Summary ===");
        assertThat(snapshot).contains("Summary: events=");
        assertThat(snapshot).contains("=== IMPORT (1) ===");
        assertThat(snapshot).contains("=== EXPORT (1) ===");
        DiagnosticStore.getInstance().clear();
    }

    @Test
    void activeEnvironmentSwitchEmitsDiagnosticsEvent() throws Exception {
        DiagnosticStore.getInstance().clear();
        ImporterPanel panel = newPanel();
        EnvironmentProfile dev = environment("Dev", "https://dev.example.test");
        EnvironmentProfile qa = environment("QA", "https://qa.example.test");
        panel.replaceEnvironmentProfiles(List.of(dev, qa));

        panel.setActiveEnvironmentId(qa.id);

        String snapshot = buildSnapshot(panel);
        assertThat(snapshot).contains("=== ENVIRONMENT_SWITCH (1) ===");
        assertThat(snapshot).contains("Active environment switched");
        DiagnosticStore.getInstance().clear();
    }

    @Test
    void exportWritesTheCurrentSnapshotTextCorrectly() throws Exception {
        ImporterPanel panel = newPanel();
        seedDiagnosticsState(panel);
        String snapshot = buildSnapshot(panel);
        File out = Files.createTempFile("diagnostics", ".txt").toFile();

        writeSnapshot(panel, out, snapshot);

        assertThat(Files.readString(out.toPath(), StandardCharsets.UTF_8)).isEqualTo(snapshot);
    }

    @Test
    void snapshotHandlesMissingUiStateSafely() throws Exception {
        ImporterPanel panel = newPanel();
        setPrivateField(panel, "requestTree", null);
        setPrivateField(panel, "requestTreeScrollPane", null);
        setPrivateField(panel, "varsCollectionCombo", null);
        setPrivateField(panel, "oauth2CollectionCombo", null);

        String snapshot = buildSnapshot(panel);

        assertThat(snapshot).contains("requestTree.exists=false");
        assertThat(snapshot).contains("selectedVariablesCollection=none");
        assertThat(snapshot).contains("selectedOAuth2Collection=none");
    }

    private static ImporterPanel newPanel() {
        burp.UniversalImporter importer = Mockito.mock(burp.UniversalImporter.class, Mockito.RETURNS_DEEP_STUBS);
        burp.api.montoya.ui.editor.HttpRequestEditor requestEditor = Mockito.mock(burp.api.montoya.ui.editor.HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpRequestEditor(Mockito.any())).thenReturn(requestEditor);
        burp.api.montoya.ui.editor.HttpResponseEditor responseEditor = Mockito.mock(burp.api.montoya.ui.editor.HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpResponseEditor(Mockito.any())).thenReturn(responseEditor);
        CollectionRunner runner = Mockito.mock(CollectionRunner.class, Mockito.RETURNS_DEEP_STUBS);
        OAuth2Manager oauth2Manager = Mockito.mock(OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS);
        return new ImporterPanel(importer, runner, oauth2Manager, burp.utils.ScriptMode.DISABLED);
    }

    private static void seedDiagnosticsState(ImporterPanel panel) throws Exception {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.runtimeVars.put("runtime_key", "runtime_value");
        collection.runtimeOAuth2.put("oauth2_access_token", "token-secret");
        collection.runtimeOAuth2.put("oauth2_client_secret", "client-secret");
        collection.environment.put("base_url", "https://api.example.test");
        collection.variables.add(variable("collection_key", "collection_value"));
        collection.requests.add(request("req-get-token", "Get Token", "POST", "https://auth.example.test/token", 1));
        collection.requests.add(request("req-list-users", "List Users", "GET", "https://api.example.test/users", 2));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectComboItem(panel, "varsCollectionCombo", 0);
        selectComboItem(panel, "oauth2CollectionCombo", 0);

        JTabbedPane tabs = (JTabbedPane) privateField(panel, "tabbedPane");
        tabs.setSelectedIndex(0);

        JTree tree = (JTree) privateField(panel, "requestTree");
        TreePath path = findRequestPath(tree, "Get Token");
        tree.setSelectionPath(path);
        tree.expandRow(0);
        tree.expandRow(1);
        tree.expandRow(2);
    }

    private static ApiRequest.Variable variable(String key, String value) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        return variable;
    }

    private static ApiRequest request(String id, String name, String method, String url, int ordinal) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.method = method;
        request.url = url;
        request.path = "Auth/OAuth/" + name;
        request.sequenceOrder = ordinal;
        return request;
    }

    private static EnvironmentProfile environment(String name, String baseUrl) {
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = name;
        profile.ensureId();
        profile.ensureDefaults();
        profile.variables.put("base_url", baseUrl);
        return profile;
    }

    private static String buildSnapshot(ImporterPanel panel) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("buildDiagnosticsSnapshot");
        method.setAccessible(true);
        return (String) method.invoke(panel);
    }

    private static void writeSnapshot(ImporterPanel panel, File file, String snapshot) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("writeDiagnosticsSnapshot", File.class, String.class);
        method.setAccessible(true);
        method.invoke(panel, file, snapshot);
    }

    private static void selectComboItem(ImporterPanel panel, String fieldName, int index) throws Exception {
        JComboBox<?> combo = (JComboBox<?>) privateField(panel, fieldName);
        combo.setSelectedIndex(index);
    }

    private static TreePath findRequestPath(JTree tree, String requestName) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        return findRequestPathRecursive(new TreePath(root), requestName);
    }

    private static TreePath findRequestPathRecursive(TreePath currentPath, String requestName) {
        Object node = currentPath.getLastPathComponent();
        if (node instanceof CollectionTreeNode treeNode && treeNode.request != null && requestName.equals(treeNode.request.name)) {
            return currentPath;
        }
        if (node instanceof DefaultMutableTreeNode mutableNode) {
            for (int i = 0; i < mutableNode.getChildCount(); i++) {
                TreePath found = findRequestPathRecursive(currentPath.pathByAddingChild(mutableNode.getChildAt(i)), requestName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object privateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
