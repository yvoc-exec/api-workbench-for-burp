package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspaceState;
import burp.testsupport.ImporterPanelTestSupport;
import burp.ui.tree.CollectionTreeNode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelExactTransportModeIntegrationTest {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @Test
    void buildModeToggleDoesNotPersistPendingEditorState() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        AtomicInteger workspaceNotifications = new AtomicInteger();
        bundle.panel.setWorkspaceChangeListener(workspaceNotifications::incrementAndGet);

        ApiCollection collection = new ApiCollection();
        collection.id = "col-pending-toggle";
        collection.name = "Pending Toggle";
        ApiRequest request = new ApiRequest();
        request.id = "req-pending-toggle";
        request.name = "Pending Toggle Request";
        request.method = "POST";
        request.url = "https://api.example.test/original?q=hello%20world&path=a%2Fb&plus=%2B&pct=%25";
        request.description = "Original description";
        request.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        request.editorMaterialized = true;
        request.headers = new ArrayList<>();
        request.headers.add(new ApiRequest.Header("Accept", "application/json"));
        request.headers.add(new ApiRequest.Header("X-Original", "one"));
        request.headers.add(new ApiRequest.Header("Content-Type", "text/plain"));
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "original-body";
        request.body.contentType = "text/plain";
        request.preRequestScripts = new ArrayList<>();
        request.preRequestScripts.add(new ApiRequest.Script("js", "console.log(\"original\");"));
        collection.requests.add(request);

        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));
        ImporterPanelTestSupport.awaitEdt();

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        ApiRequest liveRequest = liveCollection.requests.get(0);

        JTree tree = ImporterPanelTestSupport.getField(bundle.panel, "requestTree");
        CollectionTreeNode requestNode = findRequestNode((DefaultMutableTreeNode) tree.getModel().getRoot(), liveRequest.id);
        assertThat(requestNode).as("request tree node").isNotNull();
        SwingUtilities.invokeAndWait(() -> tree.setSelectionPath(new TreePath(requestNode.getPath())));

        RequestEditorPanel editor = ImporterPanelTestSupport.getField(bundle.panel, "requestEditor");
        ImporterPanelTestSupport.awaitCondition(
                () -> editor.getCurrentRequest() == liveRequest,
                Duration.ofSeconds(3));
        ImporterPanelTestSupport.awaitEdt();

        int notificationsBeforeToggle = workspaceNotifications.get();

        SwingUtilities.invokeAndWait(() -> {
            headersModel(editor).setValueAt("application/xml", findRow(headersModel(editor), "Accept"), 1);
            headersModel(editor).addRow(new Object[]{"X-Duplicate", "one"});
            headersModel(editor).addRow(new Object[]{"X-Duplicate", "two"});
            editor.getUrlField().setText("https://api.example.test/pending?q=pending%20value&path=x%2Fy&plus=%2B");
            paramsModel(editor).setRowCount(0);
            paramsModel(editor).addRow(new Object[]{"q", "pending value"});
            paramsModel(editor).addRow(new Object[]{"path", "x/y"});
            paramsModel(editor).addRow(new Object[]{"plus", "+"});
            editor.getBodyRawAreaForTests().setText("pending-body");
            preScriptArea(editor).setText("console.log(\"pending\");");
        });
        ImporterPanelTestSupport.awaitEdt();

        ApiRequest snapshotBeforeToggle = GSON.fromJson(GSON.toJson(liveRequest), ApiRequest.class);

        clickExactTransport(editor);
        ImporterPanelTestSupport.awaitEdt();

        assertThat(liveRequest.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(liveRequest).usingRecursiveComparison()
                .ignoringFields("buildMode")
                .isEqualTo(snapshotBeforeToggle);
        assertThat(workspaceNotifications.get()).isGreaterThan(notificationsBeforeToggle);
        assertThat(editor.getUrlField().getText()).isEqualTo("https://api.example.test/pending?q=pending%20value&path=x%2Fy&plus=%2B");
        assertThat(headersModel(editor).getValueAt(findRow(headersModel(editor), "Accept"), 1)).isEqualTo("application/xml");
        assertThat(editor.getBodyRawAreaForTests().getText()).isEqualTo("pending-body");
        assertThat(preScriptArea(editor).getText()).isEqualTo("console.log(\"pending\");");
        assertThat(headerRows(headersModel(editor))).containsSubsequence(
                "Accept=application/xml",
                "X-Original=one",
                "X-Duplicate=one",
                "X-Duplicate=two");

        clickExactTransport(editor);
        ImporterPanelTestSupport.awaitEdt();

        assertThat(liveRequest.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(liveRequest).usingRecursiveComparison()
                .ignoringFields("buildMode")
                .isEqualTo(snapshotBeforeToggle);
        assertThat(editor.getUrlField().getText()).isEqualTo("https://api.example.test/pending?q=pending%20value&path=x%2Fy&plus=%2B");
        assertThat(headersModel(editor).getValueAt(findRow(headersModel(editor), "Accept"), 1)).isEqualTo("application/xml");
        assertThat(editor.getBodyRawAreaForTests().getText()).isEqualTo("pending-body");
        assertThat(preScriptArea(editor).getText()).isEqualTo("console.log(\"pending\");");

        ApiRequest built = editor.buildRequestFromUI();
        assertThat(built.url).isEqualTo("https://api.example.test/pending?q=pending+value&path=x%2Fy&plus=%2B");
        assertThat(built.headers)
                .extracting(header -> header.key + "=" + header.value)
                .containsSubsequence(
                        "Accept=application/xml",
                        "X-Original=one",
                        "X-Duplicate=one",
                        "X-Duplicate=two");
        assertThat(built.body.raw).isEqualTo("pending-body");
        assertThat(built.preRequestScripts).extracting(script -> script.exec)
                .containsExactly("console.log(\"pending\");");
    }

    private static CollectionTreeNode findRequestNode(DefaultMutableTreeNode node, String requestId) {
        if (node instanceof CollectionTreeNode ctn
                && ctn.getNodeType() == CollectionTreeNode.Type.REQUEST
                && ctn.request != null
                && Objects.equals(ctn.request.id, requestId)) {
            return ctn;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            CollectionTreeNode found = findRequestNode((DefaultMutableTreeNode) node.getChildAt(i), requestId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static DefaultTableModel headersModel(RequestEditorPanel editor) {
        return (DefaultTableModel) editor.getHeadersTableForTests().getModel();
    }

    private static DefaultTableModel paramsModel(RequestEditorPanel editor) {
        return ImporterPanelTestSupport.getField(editor, "paramsModel");
    }

    private static int findRow(DefaultTableModel model, String key) {
        for (int i = 0; i < model.getRowCount(); i++) {
            Object value = model.getValueAt(i, 0);
            if (key.equals(value)) {
                return i;
            }
        }
        throw new AssertionError("Missing row: " + key);
    }

    private static JTextArea preScriptArea(RequestEditorPanel editor) {
        return ImporterPanelTestSupport.getField(editor, "preScriptArea");
    }

    private static void clickExactTransport(RequestEditorPanel editor) throws Exception {
        editor.setExactTransportWarningProviderForTests((parent, title, message) -> true);
        SwingUtilities.invokeAndWait(() -> {
            JPopupMenu menu = editor.createSendDropdownMenuForTests();
            for (Component component : menu.getComponents()) {
                if (component instanceof JCheckBoxMenuItem item
                        && "Exact transport headers \u2014 Advanced".equals(item.getText())) {
                    item.doClick();
                    return;
                }
            }
            throw new AssertionError("Exact transport menu item not found");
        });
    }

    private static List<String> headerRows(DefaultTableModel model) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            Object key = model.getValueAt(i, 0);
            Object value = model.getValueAt(i, 1);
            if (key != null && !key.toString().isBlank()) {
                rows.add(key + "=" + (value != null ? value : ""));
            }
        }
        return rows;
    }
}
