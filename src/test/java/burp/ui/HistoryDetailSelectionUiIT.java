package burp.ui;

import burp.UniversalImporter;
import burp.history.HistoryEntry;
import burp.history.HistoryRetentionPolicy;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.SwingRobotTestSupport;
import burp.testsupport.UiWorkflowFixtures;
import burp.ui.history.HistoryDetailPanel;
import burp.ui.history.HistoryPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class HistoryDetailSelectionUiIT {
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(5);
    private static final Path FAILURE_ARTIFACT_DIR = Path.of("target", "ui-failure-artifacts");

    @AfterEach
    void tearDown() {
        SwingRobotTestSupport.disposeTrackedWindows();
    }

    @Test
    void historySelectionReplacesAndClearsDetailsWithoutLeavingStaleContent() {
        UniversalImporter importer = UiWorkflowFixtures.newImporter(UiWorkflowFixtures.mockUiApi(null));
        ImporterPanel panel = importer.getUI();
        HistoryPanel historyPanel = panel.getHistoryPanelForTests();

        HistoryEntry success = HistoryTestFixtures.copyEntry(
                HistoryTestFixtures.sampleWorkbenchEntry(),
                "history-success",
                Instant.parse("2026-06-20T00:00:05Z"));
        HistoryEntry noResponse = HistoryTestFixtures.copyEntry(
                HistoryTestFixtures.sampleRunnerEntry(),
                "history-no-response",
                Instant.parse("2026-06-20T00:00:04Z"));
        noResponse.responseSnapshot = null;
        HistoryEntry authoredRawDifferent = HistoryTestFixtures.copyEntry(
                HistoryTestFixtures.sampleWorkbenchEntry(),
                "history-authored-raw",
                Instant.parse("2026-06-20T00:00:03Z"));
        HistoryEntry runnerEntry = HistoryTestFixtures.copyEntry(
                HistoryTestFixtures.sampleRunnerEntry(),
                "history-runner",
                Instant.parse("2026-06-20T00:00:02Z"));

        SwingRobotTestSupport.runOnEdt(() -> {
            historyPanel.getHistoryStore().replaceAll(List.of(success, noResponse, authoredRawDifferent, runnerEntry));
            historyPanel.refreshFromStore();
        });

        JFrame frame = SwingRobotTestSupport.showInFrame(panel.getPanel(), "History Detail UI");
        SwingRobotTestSupport.expandWindowToAvailableScreen(frame);
        Robot robot = SwingRobotTestSupport.newRobot();

        SwingRobotTestSupport.clickTabbedPaneTab(panel.getTabbedPane(), "History", robot);
        JTable table = historyPanel.getHistoryTable();
        HistoryDetailPanel detail = historyPanel.getDetailPanel();

        SwingRobotTestSupport.clickTableCell(table, 0, 0, robot);
        waitForHistoryMetadata(detail, "History details did not update for the first row",
                "history-success", "Login");
        String firstRequest = detail.getRequestArea().getText();
        String firstResponse = detail.getResponseArea().getText();
        assertThat(firstRequest).contains("POST /login HTTP/1.1");
        assertThat(firstResponse).isNotBlank();
        assertThat(detail.getVariablesArea().getText()).isNotBlank();
        assertThat(detail.getAssertionsArea().getText()).isNotBlank();
        assertThat(detail.getScriptArea().getText()).isNotBlank();

        SwingRobotTestSupport.clickTableCell(table, 1, 0, robot);
        waitForHistoryMetadata(detail, "History details did not update for the no-response row",
                "history-no-response", "Missing variable");
        assertThat(detail.getRequestArea().getText()).contains("POST /login HTTP/1.1");
        assertThat(detail.getResponseArea().getText()).isBlank();
        assertThat(detail.getResponseArea().getText()).doesNotContain(firstResponse);

        SwingRobotTestSupport.clickTableCell(table, 2, 0, robot);
        waitForHistoryMetadata(detail, "History details did not update for the authored/raw row",
                "history-authored-raw", "URL Template");
        assertThat(detail.getRequestArea().getText()).contains("POST /login HTTP/1.1");
        assertThat(detail.getMetadataArea().getText()).contains("URL Template: {{base_url}}/login");
        assertThat(detail.getVariablesArea().getText()).contains("Request Variables as Authored");

        SwingRobotTestSupport.clickTableCell(table, 3, 0, robot);
        waitForHistoryMetadata(detail, "History details did not update for the runner row",
                "history-runner", "Result Classification");
        assertThat(detail.getAssertionsArea().getText()).contains("Assertions:");
        assertThat(detail.getScriptArea().getText()).contains("Script Engine:");

        clearHistorySelectionByClickingTableWhitespace(table, robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> detail.getRequestArea().getText().isBlank()
                        && detail.getResponseArea().getText().isBlank()
                        && detail.getMetadataArea().getText().isBlank()
                        && detail.getVariablesArea().getText().isBlank(),
                UI_TIMEOUT,
                "Clearing history selection did not empty all detail panes");

        SwingRobotTestSupport.clickTableCell(table, 0, 0, robot);
        SwingRobotTestSupport.waitUntilOnEdt(
                () -> table.getSelectedRow() == 0
                        && historyPanel.getActionsPanel().getDeleteButton().isEnabled()
                        && detail.getMetadataArea().getText().contains("history-success"),
                UI_TIMEOUT,
                "History selection did not become delete-ready before clicking Delete Selected");
        JButton delete = historyPanel.getActionsPanel().getDeleteButton();
        SwingRobotTestSupport.click(delete, robot);
        waitForHistoryDeletion(table, historyPanel, detail, delete);
        assertThat(detail.getMetadataArea().getText()).doesNotContain("history-success");

        SwingRobotTestSupport.runOnEdt(() -> historyPanel.getHistoryStore().setRetentionPolicy(new HistoryRetentionPolicy(1)));
        HistoryEntry newest = HistoryTestFixtures.copyEntry(
                HistoryTestFixtures.sampleWorkbenchEntry(),
                "history-retained",
                Instant.parse("2026-06-20T00:00:06Z"));
        SwingRobotTestSupport.runOnEdt(() -> historyPanel.addHistoryEntry(newest));
        SwingRobotTestSupport.waitUntilOnEdt(() -> table.getRowCount() == 1,
                UI_TIMEOUT,
                "Retention eviction did not reduce the visible history");
        SwingRobotTestSupport.clickTableCell(table, 0, 0, robot);
        waitForHistoryMetadata(detail, "Retained history entry did not render correctly",
                "history-retained", "Login");

        SwingRobotTestSupport.dispose(frame);
    }

    private static void waitForHistoryMetadata(HistoryDetailPanel detail, String message, String... expectedSnippets) {
        SwingRobotTestSupport.waitUntilOnEdt(() -> {
            String metadata = detail.getMetadataArea().getText();
            for (String snippet : expectedSnippets) {
                if (metadata.contains(snippet)) {
                    return true;
                }
            }
            return false;
        }, UI_TIMEOUT, message);
    }

    private static void waitForHistoryDeletion(JTable table,
                                               HistoryPanel historyPanel,
                                               HistoryDetailPanel detail,
                                               JButton delete) {
        try {
            SwingRobotTestSupport.waitUntilOnEdt(() -> table.getRowCount() == 3
                            && historyPanel.getHistoryStore().snapshot().size() == 3
                            && table.getSelectedRowCount() == 0
                            && !delete.isEnabled()
                            && detail.getMetadataArea().getText().isBlank(),
                    UI_TIMEOUT,
                    "Deleting a history row did not update the table");
        } catch (AssertionError failure) {
            throw enrichDeleteFailure(failure, table, historyPanel, detail, delete);
        }
    }

    private static void clearHistorySelectionByClickingTableWhitespace(JTable table, Robot robot) {
        Rectangle visible = SwingRobotTestSupport.runOnEdtValue(table::getVisibleRect);
        Point clickPoint = SwingRobotTestSupport.toScreenPoint(table,
                Math.max(12, visible.x + 12),
                Math.max(visible.height - 12, table.getRowCount() * table.getRowHeight() + 12));
        SwingRobotTestSupport.moveTo(clickPoint, robot);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        SwingRobotTestSupport.awaitEdt();
        SwingRobotTestSupport.runOnEdt(table::clearSelection);
    }

    private static AssertionError enrichDeleteFailure(AssertionError failure,
                                                      JTable table,
                                                      HistoryPanel historyPanel,
                                                      HistoryDetailPanel detail,
                                                      JButton delete) {
        HistoryUiEvidence evidence = SwingRobotTestSupport.runOnEdtValue(() -> new HistoryUiEvidence(
                table.getSelectedRow(),
                table.getSelectedRowCount(),
                table.getRowCount(),
                historyPanel.getHistoryStore().snapshot().size(),
                delete.isEnabled(),
                detail.getMetadataArea().getText(),
                activeWindowTitles()));
        Path screenshot = captureScreenshot("HistoryDetailSelectionUiIT-delete-timeout.png");
        String metadata = evidence.visibleMetadataText();
        if (metadata != null && metadata.length() > 500) {
            metadata = metadata.substring(0, 500) + "...";
        }
        String message = "Deleting a history row did not update the table"
                + System.lineSeparator() + "selectedRow=" + evidence.selectedRow()
                + System.lineSeparator() + "selectedRowCount=" + evidence.selectedRowCount()
                + System.lineSeparator() + "tableRowCount=" + evidence.tableRowCount()
                + System.lineSeparator() + "storeSize=" + evidence.storeSize()
                + System.lineSeparator() + "deleteEnabled=" + evidence.deleteEnabled()
                + System.lineSeparator() + "visibleMetadataText=" + (metadata == null ? "" : metadata)
                + System.lineSeparator() + "activeWindows=" + evidence.activeWindows()
                + System.lineSeparator() + "screenshot=" + (screenshot != null ? screenshot : "unavailable");
        return new AssertionError(message, failure);
    }

    private static List<String> activeWindowTitles() {
        return List.of(Window.getWindows()).stream()
                .filter(Window::isShowing)
                .map(HistoryDetailSelectionUiIT::describeWindow)
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

    private record HistoryUiEvidence(int selectedRow,
                                     int selectedRowCount,
                                     int tableRowCount,
                                     int storeSize,
                                     boolean deleteEnabled,
                                     String visibleMetadataText,
                                     List<String> activeWindows) {
    }
}
