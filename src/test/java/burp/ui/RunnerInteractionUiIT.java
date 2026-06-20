package burp.ui;

import burp.UniversalImporter;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.testsupport.SwingRobotTestSupport;
import burp.testsupport.UiWorkflowFixtures;
import burp.ui.history.HistoryDetailPanel;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class RunnerInteractionUiIT {

    @AfterEach
    void tearDown() {
        SwingRobotTestSupport.disposeTrackedWindows();
    }

    @Test
    void runnerControlsUseRealPreviewAndRobotActionsForPauseStepResumeAndStop() {
        CountDownLatch blockedBStarted = new CountDownLatch(1);
        CountDownLatch releaseB = new CountDownLatch(1);
        CountDownLatch blockedStopStarted = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        AtomicInteger sendCount = new AtomicInteger();

        UniversalImporter importer = UiWorkflowFixtures.newImporter(
                UiWorkflowFixtures.mockUiApi(request -> {
                    int call = sendCount.incrementAndGet();
                    return switch (call) {
                        case 1 -> burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"request\":\"A\"}", "application/json");
                        case 2 -> {
                            blockedBStarted.countDown();
                            await(releaseB);
                            yield burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"request\":\"B\"}", "application/json");
                        }
                        case 3 -> burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"request\":\"C\"}", "application/json");
                        case 4 -> burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"request\":\"D\"}", "application/json");
                        case 5 -> burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"request\":\"E\"}", "application/json");
                        default -> {
                            blockedStopStarted.countDown();
                            await(releaseStop);
                            yield burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"request\":\"STOP\"}", "application/json");
                        }
                    };
                }));
        ImporterPanel panel = importer.getUI();
        ApiCollection workflow = collection("Workflow",
                request("Request A", "/a"),
                request("Request B", "/b"),
                request("Request C", "/c"),
                request("Request D", "/d"),
                request("Request E", "/e"));
        ApiCollection stopFlow = collection("StopFlow", request("Request Stop", "/stop"));
        SwingRobotTestSupport.runOnEdt(() -> panel.restoreWorkspaceCollections(List.of(workflow, stopFlow)));

        JFrame frame = SwingRobotTestSupport.showInFrame(panel.getPanel(), "Runner Interaction UI");
        Robot robot = SwingRobotTestSupport.newRobot();
        queueRunnerRequestsFromActionsDialog(panel, frame, robot, "Workflow");
        SwingRobotTestSupport.clickTabbedPaneTab(panel.getTabbedPane(), "Collection Runner", robot);

        JButton start = panel.getStartRunnerButtonForTests();
        JButton pause = panel.getPauseRunnerButtonForTests();
        JButton resume = panel.getResumeRunnerButtonForTests();
        JButton step = panel.getStepRunnerButtonForTests();
        JButton cancel = panel.getCancelRunnerButtonForTests();
        JTable resultTable = panel.getRunnerResultTableForTests();
        JList<ApiRequest> queueList = panel.getRunnerQueueListForTests();
        HistoryDetailPanel detailPanel = panel.getRunnerDetailPanelForTests();
        RunnerExecutionTableModel resultModel = (RunnerExecutionTableModel) resultTable.getModel();

        SwingRobotTestSupport.click(start, robot);
        Window preview = SwingRobotTestSupport.waitForWindowTitle("Runner Preview", Duration.ofSeconds(5));
        JButton startPreview = SwingRobotTestSupport.findByText((Container) ((RootPaneContainer) preview).getContentPane(), JButton.class, "Start Runner");
        assertThat(startPreview).isNotNull();
        SwingRobotTestSupport.click(startPreview, robot);

        SwingRobotTestSupport.waitUntil(() -> findCompletedRequestRow(resultModel, "Request A") >= 0,
                Duration.ofSeconds(10),
                "Runner did not complete Request A");
        await(blockedBStarted);
        SwingRobotTestSupport.waitUntil(() -> isRunnerQueueRowHighlighted(queueList, "Request B"),
                Duration.ofSeconds(5),
                "Runner queue did not visibly highlight the in-flight Request B row");

        int requestARow = findCompletedRequestRow(resultModel, "Request A");
        assertThat(requestARow).isGreaterThanOrEqualTo(0);
        SwingRobotTestSupport.clickTableCell(resultTable, requestARow, 0, robot);
        SwingRobotTestSupport.waitUntil(() -> detailPanel.getMetadataArea().getText().contains("Request Name: Request A"),
                Duration.ofSeconds(5),
                "Runner detail pane did not follow the selected request-result row");

        int eventRow = findEventRowForRequest(resultModel, "Request A");
        assertThat(eventRow).isGreaterThanOrEqualTo(0);
        SwingRobotTestSupport.clickTableCell(resultTable, eventRow, 0, robot);
        SwingRobotTestSupport.waitUntil(() -> !detailPanel.getMetadataArea().getText().isBlank(),
                Duration.ofSeconds(5),
                "Selecting an event row did not keep meaningful runner details");

        await(blockedBStarted);
        SwingRobotTestSupport.click(pause, robot);
        releaseB.countDown();
        SwingRobotTestSupport.waitUntil(() -> completedRequestCount(resultModel) == 2
                        && resume.isEnabled()
                        && !pause.isEnabled()
                        && step.isEnabled()
                        && cancel.isEnabled(),
                Duration.ofSeconds(10),
                "Runner did not pause after Request B completed");
        assertThat(findCompletedRequestRow(resultModel, "Request C")).isEqualTo(-1);

        SwingRobotTestSupport.click(step, robot);
        SwingRobotTestSupport.waitUntil(() -> completedRequestCount(resultModel) == 3
                        && resume.isEnabled()
                        && !pause.isEnabled()
                        && step.isEnabled(),
                Duration.ofSeconds(10),
                "Single-step execution did not complete exactly one request");
        SwingRobotTestSupport.click(step, robot);
        SwingRobotTestSupport.waitUntil(() -> completedRequestCount(resultModel) == 4
                        && resume.isEnabled()
                        && !pause.isEnabled()
                        && step.isEnabled(),
                Duration.ofSeconds(10),
                "Repeated step execution did not stay aligned with the number of clicks");
        assertThat(completedRequestCount(resultModel)).isEqualTo(4);

        SwingRobotTestSupport.click(resume, robot);
        SwingRobotTestSupport.waitUntil(() -> completedRequestCount(resultModel) == 5
                        && start.isEnabled()
                        && !pause.isEnabled()
                        && !resume.isEnabled()
                        && !cancel.isEnabled(),
                Duration.ofSeconds(10),
                "Runner did not resume and finish the remaining queued request");
        int requestERow = findCompletedRequestRow(resultModel, "Request E");
        assertThat(requestERow).isGreaterThanOrEqualTo(0);
        SwingRobotTestSupport.clickTableCell(resultTable, requestERow, 0, robot);
        SwingRobotTestSupport.waitUntil(() -> detailPanel.getMetadataArea().getText().contains("Request Name: Request E"),
                Duration.ofSeconds(5),
                "Runner detail pane did not update for a later request row");

        queueRunnerRequestsFromActionsDialog(panel, frame, robot, "StopFlow");
        SwingRobotTestSupport.click(start, robot);
        Window stopPreview = SwingRobotTestSupport.waitForWindowTitle("Runner Preview", Duration.ofSeconds(5));
        JButton startStopPreview = SwingRobotTestSupport.findByText((Container) ((RootPaneContainer) stopPreview).getContentPane(), JButton.class, "Start Runner");
        SwingRobotTestSupport.click(startStopPreview, robot);
        await(blockedStopStarted);

        SwingRobotTestSupport.clickTableCell(resultTable, requestERow, 0, robot);
        String stableMeta = detailPanel.getMetadataArea().getText();
        SwingRobotTestSupport.click(cancel, robot);
        int requestResultsBeforeRelease = completedRequestCount(resultModel);
        releaseStop.countDown();

        SwingRobotTestSupport.waitUntil(() -> start.isEnabled() && !pause.isEnabled() && !resume.isEnabled() && !cancel.isEnabled(),
                Duration.ofSeconds(10),
                "Runner controls were reactivated incorrectly after cancellation");
        SwingRobotTestSupport.waitUntil(() -> completedRequestCount(resultModel) == requestResultsBeforeRelease,
                Duration.ofSeconds(5),
                "Late completion after cancel unexpectedly produced an additional request result");
        assertThat(detailPanel.getMetadataArea().getText()).isEqualTo(stableMeta);

        SwingRobotTestSupport.dispose(frame);
    }

    private static void queueRunnerRequestsFromActionsDialog(ImporterPanel panel, JFrame frame, Robot robot, String nodeLabel) {
        SwingRobotTestSupport.clickTabbedPaneTab(panel.getTabbedPane(), "Workbench", robot);
        JButton actions = panel.getActionsButtonForTests();
        assertThat(actions).isNotNull();
        SwingRobotTestSupport.click(actions, robot);

        Window optionsDialog = SwingRobotTestSupport.waitForWindowTitle("Options", Duration.ofSeconds(5));
        @SuppressWarnings("unchecked")
        List<JTree> trees = SwingRobotTestSupport.findByType((Container) ((RootPaneContainer) optionsDialog).getContentPane(), JTree.class);
        assertThat(trees).isNotEmpty();
        JTree selectionTree = trees.get(0);
        TreePath path = findNodePath(selectionTree, nodeLabel);
        assertThat(path).as("Tree path for " + nodeLabel).isNotNull();
        SwingRobotTestSupport.clickTreePathCheckbox(selectionTree, path, robot);

        JButton runChecked = SwingRobotTestSupport.findByText((Container) ((RootPaneContainer) optionsDialog).getContentPane(), JButton.class, "Run Checked");
        assertThat(runChecked).isNotNull();
        SwingRobotTestSupport.waitUntil(runChecked::isEnabled,
                Duration.ofSeconds(5),
                "Run Checked did not enable after selecting requests in the Actions dialog");
        SwingRobotTestSupport.click(runChecked, robot);

        SwingRobotTestSupport.waitUntil(() -> !optionsDialog.isShowing(),
                Duration.ofSeconds(5),
                "Actions dialog did not close after queueing runner requests");
    }

    private static int findCompletedRequestRow(RunnerExecutionTableModel model, String requestName) {
        for (int i = 0; i < model.getRowCount(); i++) {
            RunnerExecutionTableModel.Entry entry = model.getEntryAt(i);
            if (requestName.equals(entry.requestName)
                    && "REQUEST_COMPLETED".equals(entry.type)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isRunnerQueueRowHighlighted(JList<ApiRequest> queueList, String requestName) {
        return SwingRobotTestSupport.runOnEdtValue(() -> {
            ListModel<ApiRequest> model = queueList.getModel();
            @SuppressWarnings("unchecked")
            ListCellRenderer<? super ApiRequest> renderer = queueList.getCellRenderer();
            if (renderer == null) {
                return false;
            }
            for (int i = 0; i < model.getSize(); i++) {
                ApiRequest request = model.getElementAt(i);
                if (request == null || !requestName.equals(request.name)) {
                    continue;
                }
                Component rendered = renderer.getListCellRendererComponent(
                        queueList,
                        request,
                        i,
                        queueList.isSelectedIndex(i),
                        false);
                return rendered.getFont() != null && rendered.getFont().isBold();
            }
            return false;
        });
    }

    private static int findEventRowForRequest(RunnerExecutionTableModel model, String requestName) {
        for (int i = 0; i < model.getRowCount(); i++) {
            RunnerExecutionTableModel.Entry entry = model.getEntryAt(i);
            if (requestName.equals(entry.requestName)
                    && entry.type != null
                    && !"REQUEST_COMPLETED".equals(entry.type)) {
                return i;
            }
        }
        return -1;
    }

    private static int completedRequestCount(RunnerExecutionTableModel model) {
        int completed = 0;
        for (int i = 0; i < model.getRowCount(); i++) {
            RunnerExecutionTableModel.Entry entry = model.getEntryAt(i);
            if ("REQUEST_COMPLETED".equals(entry.type)) {
                completed++;
            }
        }
        return completed;
    }

    private static TreePath findNodePath(JTree tree, String label) {
        Object root = tree.getModel().getRoot();
        return root instanceof DefaultMutableTreeNode node
                ? findNodePathRecursive(new TreePath(node), label)
                : null;
    }

    private static TreePath findNodePathRecursive(TreePath current, String label) {
        Object node = current.getLastPathComponent();
        if (node != null && label.equals(String.valueOf(node))) {
            return current;
        }
        if (node instanceof DefaultMutableTreeNode mutableNode) {
            for (int i = 0; i < mutableNode.getChildCount(); i++) {
                TreePath found = findNodePathRecursive(current.pathByAddingChild(mutableNode.getChildAt(i)), label);
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
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for runner latch", e);
        }
    }
}
