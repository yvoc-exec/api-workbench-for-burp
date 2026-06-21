package burp.ui;

import burp.UniversalImporter;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.testsupport.SwingRobotTestSupport;
import burp.testsupport.UiWorkflowFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class WorkbenchSelectionLifecycleUiIT {

    @AfterEach
    void tearDown() {
        SwingRobotTestSupport.disposeTrackedWindows();
    }

    @Test
    void staleWorkbenchSendCompletionDoesNotOverwriteCurrentSelectionOrEmptyState() {
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch allowResponse = new CountDownLatch(1);

        UniversalImporter importer = UiWorkflowFixtures.newImporter(
                UiWorkflowFixtures.mockUiApi(request -> {
                    String url = request.url();
                    if (url != null && url.endsWith("/a")) {
                        sendStarted.countDown();
                        await(allowResponse);
                        return burp.testsupport.RunnerScriptTestFixtures.mockResponse(200,
                                "{\"request\":\"A\"}",
                                "application/json");
                    }
                    return burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"ok\":true}", "application/json");
                }));
        ImporterPanel panel = importer.getUI();
        RequestEditorPanel editor = panel.getRequestEditorForTests();

        ApiCollection collection = collection("Workspace",
                request("Request A", "/a"),
                request("Request B", "/b"));
        SwingRobotTestSupport.runOnEdt(() -> panel.restoreWorkspaceCollections(List.of(collection)));

        JFrame frame = SwingRobotTestSupport.showInFrame(panel.getPanel(), "Workbench Lifecycle UI");
        SwingRobotTestSupport.expandWindowToAvailableScreen(frame);
        Robot robot = SwingRobotTestSupport.newRobot();
        JTree tree = panel.getRequestTreeForTests();

        selectRequest(tree, "Request A", robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> editor.getCurrentRequest() != null
                        && "Request A".equals(editor.getCurrentRequest().name),
                Duration.ofSeconds(5),
                "Request A was not selected before send");
        String authoredA = editor.getUrlField().getText();
        JButton sendButton = editor.getSendButtonForTests();
        SwingRobotTestSupport.waitUntilOnEdt(sendButton::isEnabled,
                Duration.ofSeconds(5),
                "Send button did not enable for Request A");
        SwingRobotTestSupport.click(sendButton, robot);
        await(sendStarted);
        SwingRobotTestSupport.waitUntilOnEdt(() -> !editor.isSendEnabled(),
                Duration.ofSeconds(5),
                "Send controls did not disable during the in-flight send");

        selectRequest(tree, "Request B", robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> editor.getCurrentRequest() != null
                        && "Request B".equals(editor.getCurrentRequest().name),
                Duration.ofSeconds(5),
                "Request B was not selected during Request A send");
        String authoredB = editor.getUrlField().getText();
        assertThat(panel.getWorkbenchDetailMetaTextForTest()).contains("Request Name: Request B");

        allowResponse.countDown();
        SwingRobotTestSupport.waitUntilOnEdt(editor::isSendEnabled,
                Duration.ofSeconds(5),
                "Send controls were not re-enabled for the still-selected Request B");
        assertThat(editor.getCurrentRequest().name).isEqualTo("Request B");
        assertThat(editor.getUrlField().getText()).isEqualTo(authoredB);
        assertThat(panel.getWorkbenchDetailMetaTextForTest()).contains("Request Name: Request B");
        assertThat(panel.getWorkbenchDetailMetaTextForTest()).doesNotContain("Request Name: Request A");

        CountDownLatch clearStarted = new CountDownLatch(1);
        CountDownLatch allowClearResponse = new CountDownLatch(1);
        UniversalImporter clearImporter = UiWorkflowFixtures.newImporter(
                UiWorkflowFixtures.mockUiApi(request -> {
                    clearStarted.countDown();
                    await(allowClearResponse);
                    return burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"clear\":true}", "application/json");
                }));
        ImporterPanel clearPanel = clearImporter.getUI();
        RequestEditorPanel clearEditor = clearPanel.getRequestEditorForTests();
        SwingRobotTestSupport.runOnEdt(() -> clearPanel.restoreWorkspaceCollections(List.of(collection("Workspace", request("Request A", "/a")))));
        JFrame clearFrame = SwingRobotTestSupport.showInFrame(clearPanel.getPanel(), "Workbench Lifecycle UI Clear");
        SwingRobotTestSupport.expandWindowToAvailableScreen(clearFrame);
        JTree clearTree = clearPanel.getRequestTreeForTests();
        selectRequest(clearTree, "Request A", robot);
        SwingRobotTestSupport.click(clearEditor.getSendButtonForTests(), robot);
        await(clearStarted);
        clearTreeSelectionByClickingWhitespace(clearTree, robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> clearEditor.getCurrentRequest() == null,
                Duration.ofSeconds(5),
                "Clearing the request-tree selection did not empty the editor");
        allowClearResponse.countDown();
        SwingRobotTestSupport.waitUntilOnEdt(() -> !clearEditor.isSendEnabled(),
                Duration.ofSeconds(5),
                "Empty-selection send controls became enabled after a stale completion");
        assertThat(clearEditor.getCurrentRequest()).isNull();
        assertThat(clearPanel.getWorkbenchDetailMetaTextForTest()).isBlank();

        CountDownLatch deleteStarted = new CountDownLatch(1);
        CountDownLatch allowDeleteResponse = new CountDownLatch(1);
        UniversalImporter deleteImporter = UiWorkflowFixtures.newImporter(
                UiWorkflowFixtures.mockUiApi(request -> {
                    deleteStarted.countDown();
                    await(allowDeleteResponse);
                    return burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"deleted\":true}", "application/json");
                }));
        ImporterPanel deletePanel = deleteImporter.getUI();
        RequestEditorPanel deleteEditor = deletePanel.getRequestEditorForTests();
        ApiCollection deleteCollection = collection("Workspace", request("Request A", "/a"));
        SwingRobotTestSupport.runOnEdt(() -> deletePanel.restoreWorkspaceCollections(List.of(deleteCollection)));
        JFrame deleteFrame = SwingRobotTestSupport.showInFrame(deletePanel.getPanel(), "Workbench Lifecycle UI Delete");
        SwingRobotTestSupport.expandWindowToAvailableScreen(deleteFrame);
        JTree deleteTree = deletePanel.getRequestTreeForTests();
        selectRequest(deleteTree, "Request A", robot);
        SwingRobotTestSupport.click(deleteEditor.getSendButtonForTests(), robot);
        await(deleteStarted);
        deleteSelectedRequestViaContextMenu(deleteTree, "Request A", deleteFrame, robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> deleteEditor.getCurrentRequest() == null,
                Duration.ofSeconds(5),
                "Deleting the selected request during send did not clear the editor");
        allowDeleteResponse.countDown();
        SwingRobotTestSupport.waitUntilOnEdt(() -> deletePanel.getWorkbenchDetailMetaTextForTest().isBlank(),
                Duration.ofSeconds(5),
                "Deleting the selected request allowed a stale send completion to repopulate Workbench details");
        assertThat(findRequestPath(deleteTree, "Request A")).isNull();
        assertThat(deleteEditor.isSendEnabled()).isFalse();

        SwingRobotTestSupport.dispose(frame);
        SwingRobotTestSupport.dispose(clearFrame);
        SwingRobotTestSupport.dispose(deleteFrame);
    }

    private static void selectRequest(JTree tree, String requestName, Robot robot) {
        TreePath path = findRequestPath(tree, requestName);
        assertThat(path).as("Tree path for " + requestName).isNotNull();
        SwingRobotTestSupport.clickTreePath(tree, path, robot);
    }

    private static void clearTreeSelectionByClickingWhitespace(JTree tree, Robot robot) {
        Rectangle visible = SwingRobotTestSupport.runOnEdtValue(tree::getVisibleRect);
        int rowCount = SwingRobotTestSupport.runOnEdtValue(tree::getRowCount);
        int rowHeight = Math.max(20, tree.getRowHeight() > 0 ? tree.getRowHeight() : 20);
        Point point = SwingRobotTestSupport.toScreenPoint(tree,
                Math.max(12, visible.x + 12),
                Math.min(Math.max(visible.height - 12, rowCount * rowHeight + 12), Math.max(visible.height - 4, rowCount * rowHeight + 12)));
        SwingRobotTestSupport.moveTo(point, robot);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        SwingRobotTestSupport.awaitEdt();
    }

    private static void deleteSelectedRequestViaContextMenu(JTree tree, String requestName, JFrame frame, Robot robot) {
        TreePath path = findRequestPath(tree, requestName);
        assertThat(path).as("Tree path for " + requestName).isNotNull();
        SwingRobotTestSupport.rightClickTreePath(tree, path, robot);
        JPopupMenu popup = SwingRobotTestSupport.waitForPopup(frame.getRootPane(), Duration.ofSeconds(5));
        JMenuItem deleteItem = SwingRobotTestSupport.findByText(popup, JMenuItem.class, "Delete");
        assertThat(deleteItem).isNotNull();
        SwingRobotTestSupport.click(deleteItem, robot);
        Window confirmDialog = SwingRobotTestSupport.waitForWindowTitle("Confirm Delete", Duration.ofSeconds(5));
        JButton yesButton = SwingRobotTestSupport.findByText((Container) ((RootPaneContainer) confirmDialog).getContentPane(), JButton.class, "Yes");
        assertThat(yesButton).isNotNull();
        SwingRobotTestSupport.click(yesButton, robot);
    }

    private static TreePath findRequestPath(JTree tree, String requestName) {
        Object root = tree.getModel().getRoot();
        return root instanceof DefaultMutableTreeNode node
                ? findRequestPathRecursive(new TreePath(node), requestName)
                : null;
    }

    private static TreePath findRequestPathRecursive(TreePath current, String requestName) {
        Object node = current.getLastPathComponent();
        if (node instanceof burp.ui.tree.CollectionTreeNode treeNode
                && treeNode.request != null
                && requestName.equals(treeNode.request.name)) {
            return current;
        }
        if (node instanceof DefaultMutableTreeNode mutableNode) {
            for (int i = 0; i < mutableNode.getChildCount(); i++) {
                TreePath found = findRequestPathRecursive(current.pathByAddingChild(mutableNode.getChildAt(i)), requestName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static ApiCollection collection(String name, ApiRequest... requests) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        for (ApiRequest request : requests) {
            request.sourceCollection = name;
            collection.requests.add(request);
        }
        return collection;
    }

    private static ApiRequest request(String name, String path) {
        ApiRequest request = new ApiRequest();
        request.id = "req-" + name.toLowerCase().replace(' ', '-');
        request.name = name;
        request.method = "GET";
        request.url = "https://api.example.test" + path;
        request.path = "";
        return request;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for workbench lifecycle latch", e);
        }
    }
}
