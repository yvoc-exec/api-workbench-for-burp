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

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class RunnerInteractionUiIT {
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SHORT_UI_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CANCELLATION_STABILITY_WINDOW = Duration.ofMillis(250);
    private static final Path FAILURE_ARTIFACT_DIR = Path.of("target", "ui-failure-artifacts");

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

        UniversalImporter importer = UiWorkflowFixtures.newImporter(
                UiWorkflowFixtures.mockUiApi(request -> {
                    String url = request.url();
                    if (url != null && url.endsWith("/b")) {
                        blockedBStarted.countDown();
                        await(releaseB);
                        return burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"request\":\"B\"}", "application/json");
                    }
                    if (url != null && url.endsWith("/stop")) {
                        blockedStopStarted.countDown();
                        await(releaseStop);
                        return burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"request\":\"STOP\"}", "application/json");
                    }
                    if (url != null && url.endsWith("/a")) {
                        return burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"request\":\"A\"}", "application/json");
                    }
                    if (url != null && url.endsWith("/c")) {
                        return burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"request\":\"C\"}", "application/json");
                    }
                    if (url != null && url.endsWith("/d")) {
                        return burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"request\":\"D\"}", "application/json");
                    }
                    if (url != null && url.endsWith("/e")) {
                        return burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"request\":\"E\"}", "application/json");
                    }
                    return burp.testsupport.RunnerScriptTestFixtures.mockResponse(200, "{\"request\":\"OTHER\"}", "application/json");
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
        SwingRobotTestSupport.expandWindowToAvailableScreen(frame);
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
        Window preview = SwingRobotTestSupport.waitForWindowTitle("Runner Preview", SHORT_UI_TIMEOUT);
        JButton startPreview = SwingRobotTestSupport.findByText((Container) ((RootPaneContainer) preview).getContentPane(), JButton.class, "Start Runner");
        assertThat(startPreview).isNotNull();
        SwingRobotTestSupport.click(startPreview, robot);

        SwingRobotTestSupport.waitUntilOnEdt(() -> findCompletedRequestRow(resultModel, "Request A") >= 0,
                UI_TIMEOUT,
                "Runner did not complete Request A");
        await(blockedBStarted);
        SwingRobotTestSupport.waitUntil(() -> isRunnerQueueRowHighlighted(queueList, "Request B"),
                SHORT_UI_TIMEOUT,
                "Runner queue did not visibly highlight the in-flight Request B row");

        int requestARow = SwingRobotTestSupport.runOnEdtValue(() -> findCompletedRequestRow(resultModel, "Request A"));
        assertThat(requestARow).isGreaterThanOrEqualTo(0);
        SwingRobotTestSupport.clickTableCell(resultTable, requestARow, 0, robot);
        waitForRunnerMetadata(detailPanel, "Runner detail pane did not follow the selected request-result row",
                "Request Name: Request A");

        int eventRow = SwingRobotTestSupport.runOnEdtValue(() -> findEventRowForRequest(resultModel, "Request A"));
        assertThat(eventRow).isGreaterThanOrEqualTo(0);
        SwingRobotTestSupport.clickTableCell(resultTable, eventRow, 0, robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> !detailPanel.getMetadataArea().getText().isBlank(),
                SHORT_UI_TIMEOUT,
                "Selecting an event row did not keep meaningful runner details");

        await(blockedBStarted);
        SwingRobotTestSupport.click(pause, robot);
        releaseB.countDown();
        SwingRobotTestSupport.waitUntilOnEdt(() -> completedRequestCount(resultModel) == 2
                        && resume.isEnabled()
                        && !pause.isEnabled()
                        && step.isEnabled()
                        && cancel.isEnabled(),
                UI_TIMEOUT,
                "Runner did not pause after Request B completed");
        assertThat(SwingRobotTestSupport.runOnEdtValue(() -> findCompletedRequestRow(resultModel, "Request C"))).isEqualTo(-1);

        SwingRobotTestSupport.click(step, robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> completedRequestCount(resultModel) == 3
                        && resume.isEnabled()
                        && !pause.isEnabled()
                        && step.isEnabled(),
                UI_TIMEOUT,
                "Single-step execution did not complete exactly one request");
        SwingRobotTestSupport.click(step, robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> completedRequestCount(resultModel) == 4
                        && resume.isEnabled()
                        && !pause.isEnabled()
                        && step.isEnabled(),
                UI_TIMEOUT,
                "Repeated step execution did not stay aligned with the number of clicks");
        assertThat(SwingRobotTestSupport.runOnEdtValue(() -> completedRequestCount(resultModel))).isEqualTo(4);

        SwingRobotTestSupport.click(resume, robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> completedRequestCount(resultModel) == 5
                        && start.isEnabled()
                        && !pause.isEnabled()
                        && !resume.isEnabled()
                        && !cancel.isEnabled(),
                UI_TIMEOUT,
                "Runner did not resume and finish the remaining queued request");
        int requestERow = SwingRobotTestSupport.runOnEdtValue(() -> findCompletedRequestRow(resultModel, "Request E"));
        assertThat(requestERow).isGreaterThanOrEqualTo(0);
        SwingRobotTestSupport.clickTableCell(resultTable, requestERow, 0, robot);
        waitForRunnerMetadata(detailPanel, "Runner detail pane did not update for a later request row",
                "Request Name: Request E");

        queueRunnerRequestsFromActionsDialog(panel, frame, robot, "StopFlow");
        SwingRobotTestSupport.click(start, robot);
        Window stopPreview = SwingRobotTestSupport.waitForWindowTitle("Runner Preview", SHORT_UI_TIMEOUT);
        JButton startStopPreview = SwingRobotTestSupport.findByText((Container) ((RootPaneContainer) stopPreview).getContentPane(), JButton.class, "Start Runner");
        SwingRobotTestSupport.click(startStopPreview, robot);
        await(blockedStopStarted);

        SwingRobotTestSupport.clickTableCell(resultTable, requestERow, 0, robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> cancel.isEnabled() && !start.isEnabled(),
                SHORT_UI_TIMEOUT,
                "Runner cancel control did not enable for the in-flight request");
        String stableMeta = SwingRobotTestSupport.runOnEdtValue(() -> detailPanel.getMetadataArea().getText());
        SwingRobotTestSupport.click(cancel, robot);
        int requestResultsBeforeRelease = SwingRobotTestSupport.runOnEdtValue(() -> completedRequestCount(resultModel));
        releaseStop.countDown();

        SwingRobotTestSupport.waitUntilOnEdt(() -> start.isEnabled() && !pause.isEnabled() && !resume.isEnabled() && !cancel.isEnabled(),
                UI_TIMEOUT,
                "Runner controls were reactivated incorrectly after cancellation");
        waitForStableCompletedRequestCount(resultTable, resultModel, detailPanel, start, pause, resume, cancel,
                requestResultsBeforeRelease,
                "Late completion after cancel unexpectedly produced an additional request result");
        assertThat(SwingRobotTestSupport.runOnEdtValue(() -> detailPanel.getMetadataArea().getText())).isEqualTo(stableMeta);

        SwingRobotTestSupport.dispose(frame);
    }

    private static void queueRunnerRequestsFromActionsDialog(ImporterPanel panel, JFrame frame, Robot robot, String nodeLabel) {
        SwingRobotTestSupport.clickTabbedPaneTab(panel.getTabbedPane(), "Workbench", robot);
        JButton actions = panel.getActionsButtonForTests();
        assertThat(actions).isNotNull();
        SwingRobotTestSupport.click(actions, robot);

        Window optionsDialog = SwingRobotTestSupport.waitForWindowTitle("Options", SHORT_UI_TIMEOUT);
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
                SHORT_UI_TIMEOUT,
                "Run Checked did not enable after selecting requests in the Actions dialog");
        SwingRobotTestSupport.click(runChecked, robot);

        SwingRobotTestSupport.waitUntil(() -> !optionsDialog.isShowing(),
                SHORT_UI_TIMEOUT,
                "Actions dialog did not close after queueing runner requests");
    }

    private static void waitForRunnerMetadata(HistoryDetailPanel detailPanel,
                                              String message,
                                              String expectedSnippet) {
        SwingRobotTestSupport.waitUntilOnEdt(() -> detailPanel.getMetadataArea().getText().contains(expectedSnippet),
                SHORT_UI_TIMEOUT,
                message);
    }

    private static void waitForStableCompletedRequestCount(JTable resultTable,
                                                           RunnerExecutionTableModel resultModel,
                                                           HistoryDetailPanel detailPanel,
                                                           JButton start,
                                                           JButton pause,
                                                           JButton resume,
                                                           JButton cancel,
                                                           int expectedCompletedCount,
                                                           String message) {
        long deadline = System.nanoTime() + UI_TIMEOUT.toNanos();
        long stableSince = -1L;
        while (System.nanoTime() < deadline) {
            RunnerCancellationEvidence evidence = SwingRobotTestSupport.runOnEdtValue(() -> snapshotCancellationEvidence(
                    resultTable,
                    resultModel,
                    detailPanel,
                    start,
                    pause,
                    resume,
                    cancel));
            if (evidence.completedRequestCount() == expectedCompletedCount) {
                if (stableSince < 0L) {
                    stableSince = System.nanoTime();
                }
                if (System.nanoTime() - stableSince >= CANCELLATION_STABILITY_WINDOW.toNanos()) {
                    return;
                }
            } else {
                stableSince = -1L;
            }
            SwingRobotTestSupport.awaitEdt();
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(message, e);
            }
        }
        throw enrichCancellationFailure(resultTable, resultModel, detailPanel, start, pause, resume, cancel,
                expectedCompletedCount, message);
    }

    private static RunnerCancellationEvidence snapshotCancellationEvidence(JTable resultTable,
                                                                           RunnerExecutionTableModel resultModel,
                                                                           HistoryDetailPanel detailPanel,
                                                                           JButton start,
                                                                           JButton pause,
                                                                           JButton resume,
                                                                           JButton cancel) {
        return new RunnerCancellationEvidence(
                resultTable.getSelectedRow(),
                resultModel.getRowCount(),
                completedRequestCount(resultModel),
                detailPanel.getMetadataArea().getText(),
                start.isEnabled(),
                pause.isEnabled(),
                resume.isEnabled(),
                cancel.isEnabled(),
                activeWindowTitles());
    }

    private static AssertionError enrichCancellationFailure(JTable resultTable,
                                                            RunnerExecutionTableModel resultModel,
                                                            HistoryDetailPanel detailPanel,
                                                            JButton start,
                                                            JButton pause,
                                                            JButton resume,
                                                            JButton cancel,
                                                            int expectedCompletedCount,
                                                            String message) {
        RunnerCancellationEvidence evidence = SwingRobotTestSupport.runOnEdtValue(() -> snapshotCancellationEvidence(
                resultTable,
                resultModel,
                detailPanel,
                start,
                pause,
                resume,
                cancel));
        Path screenshot = captureScreenshot("RunnerInteractionUiIT-cancel-timeout.png");
        String visibleDetail = evidence.visibleDetailText();
        if (visibleDetail != null && visibleDetail.length() > 500) {
            visibleDetail = visibleDetail.substring(0, 500) + "...";
        }
        String failureMessage = message
                + System.lineSeparator() + "expectedCompletedRequestCount=" + expectedCompletedCount
                + System.lineSeparator() + "actualCompletedRequestCount=" + evidence.completedRequestCount()
                + System.lineSeparator() + "resultTableSelectedRow=" + evidence.selectedRow()
                + System.lineSeparator() + "resultTableRowCount=" + evidence.totalRowCount()
                + System.lineSeparator() + "startEnabled=" + evidence.startEnabled()
                + System.lineSeparator() + "pauseEnabled=" + evidence.pauseEnabled()
                + System.lineSeparator() + "resumeEnabled=" + evidence.resumeEnabled()
                + System.lineSeparator() + "cancelEnabled=" + evidence.cancelEnabled()
                + System.lineSeparator() + "visibleDetailText=" + (visibleDetail == null ? "" : visibleDetail)
                + System.lineSeparator() + "activeWindows=" + evidence.activeWindows()
                + System.lineSeparator() + "screenshot=" + (screenshot != null ? screenshot : "unavailable");
        return new AssertionError(failureMessage);
    }

    private static List<String> activeWindowTitles() {
        return List.of(Window.getWindows()).stream()
                .filter(Window::isShowing)
                .map(RunnerInteractionUiIT::describeWindow)
                .collect(Collectors.toList());
    }

    private static String describeWindow(Window window) {
        if (window instanceof Frame frame) {
            return titleOrClass(frame.getTitle(), window);
        }
        if (window instanceof Dialog dialog) {
            return titleOrClass(dialog.getTitle(), window);
        }
        return window.getClass().getSimpleName();
    }

    private static String titleOrClass(String title, Window window) {
        return title != null && !title.isBlank() ? title : window.getClass().getSimpleName();
    }

    private static Path captureScreenshot(String fileName) {
        try {
            Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            if (bounds == null || bounds.isEmpty()) {
                return null;
            }
            BufferedImage image = new Robot().createScreenCapture(bounds);
            Files.createDirectories(FAILURE_ARTIFACT_DIR);
            Path output = FAILURE_ARTIFACT_DIR.resolve(fileName);
            ImageIO.write(image, "png", output.toFile());
            return output;
        } catch (AWTException | IOException ignored) {
            return null;
        }
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

    private record RunnerCancellationEvidence(int selectedRow,
                                              int totalRowCount,
                                              int completedRequestCount,
                                              String visibleDetailText,
                                              boolean startEnabled,
                                              boolean pauseEnabled,
                                              boolean resumeEnabled,
                                              boolean cancelEnabled,
                                              List<String> activeWindows) {
    }
}
