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

import javax.swing.*;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class HistoryDetailSelectionUiIT {

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
        Robot robot = SwingRobotTestSupport.newRobot();

        SwingRobotTestSupport.clickTabbedPaneTab(panel.getTabbedPane(), "History", robot);
        JTable table = historyPanel.getHistoryTable();
        HistoryDetailPanel detail = historyPanel.getDetailPanel();

        SwingRobotTestSupport.clickTableCell(table, 0, 0, robot);
        SwingRobotTestSupport.waitUntil(() -> detail.getMetadataArea().getText().contains("history-success")
                        || detail.getMetadataArea().getText().contains("Login"),
                Duration.ofSeconds(5),
                "History details did not update for the first row");
        String firstRequest = detail.getRequestArea().getText();
        String firstResponse = detail.getResponseArea().getText();
        assertThat(firstRequest).contains("POST /login HTTP/1.1");
        assertThat(firstResponse).isNotBlank();
        assertThat(detail.getVariablesArea().getText()).isNotBlank();
        assertThat(detail.getAssertionsArea().getText()).isNotBlank();
        assertThat(detail.getScriptArea().getText()).isNotBlank();

        SwingRobotTestSupport.clickTableCell(table, 1, 0, robot);
        SwingRobotTestSupport.waitUntil(() -> detail.getMetadataArea().getText().contains("history-no-response")
                        || detail.getMetadataArea().getText().contains("Missing variable"),
                Duration.ofSeconds(5),
                "History details did not update for the no-response row");
        assertThat(detail.getRequestArea().getText()).contains("POST /login HTTP/1.1");
        assertThat(detail.getResponseArea().getText()).isBlank();
        assertThat(detail.getResponseArea().getText()).doesNotContain(firstResponse);

        SwingRobotTestSupport.clickTableCell(table, 2, 0, robot);
        SwingRobotTestSupport.waitUntil(() -> detail.getMetadataArea().getText().contains("history-authored-raw")
                        || detail.getMetadataArea().getText().contains("URL Template"),
                Duration.ofSeconds(5),
                "History details did not update for the authored/raw row");
        assertThat(detail.getRequestArea().getText()).contains("POST /login HTTP/1.1");
        assertThat(detail.getMetadataArea().getText()).contains("URL Template: {{base_url}}/login");
        assertThat(detail.getVariablesArea().getText()).contains("Request Variables as Authored");

        SwingRobotTestSupport.clickTableCell(table, 3, 0, robot);
        SwingRobotTestSupport.waitUntil(() -> detail.getMetadataArea().getText().contains("history-runner")
                        || detail.getMetadataArea().getText().contains("Result Classification"),
                Duration.ofSeconds(5),
                "History details did not update for the runner row");
        assertThat(detail.getAssertionsArea().getText()).contains("Assertions:");
        assertThat(detail.getScriptArea().getText()).contains("Script Engine:");

        clearHistorySelectionByClickingTableWhitespace(table, robot);
        SwingRobotTestSupport.waitUntil(() -> detail.getRequestArea().getText().isBlank()
                        && detail.getResponseArea().getText().isBlank()
                        && detail.getMetadataArea().getText().isBlank()
                        && detail.getVariablesArea().getText().isBlank(),
                Duration.ofSeconds(5),
                "Clearing history selection did not empty all detail panes");

        SwingRobotTestSupport.clickTableCell(table, 0, 0, robot);
        JButton delete = historyPanel.getActionsPanel().getDeleteButton();
        SwingRobotTestSupport.click(delete, robot);
        SwingRobotTestSupport.waitUntil(() -> table.getRowCount() == 3,
                Duration.ofSeconds(5),
                "Deleting a history row did not update the table");
        assertThat(detail.getMetadataArea().getText()).doesNotContain("history-success");

        SwingRobotTestSupport.runOnEdt(() -> historyPanel.getHistoryStore().setRetentionPolicy(new HistoryRetentionPolicy(1)));
        HistoryEntry newest = HistoryTestFixtures.copyEntry(
                HistoryTestFixtures.sampleWorkbenchEntry(),
                "history-retained",
                Instant.parse("2026-06-20T00:00:06Z"));
        SwingRobotTestSupport.runOnEdt(() -> historyPanel.addHistoryEntry(newest));
        SwingRobotTestSupport.waitUntil(() -> table.getRowCount() == 1,
                Duration.ofSeconds(5),
                "Retention eviction did not reduce the visible history");
        SwingRobotTestSupport.clickTableCell(table, 0, 0, robot);
        SwingRobotTestSupport.waitUntil(() -> detail.getMetadataArea().getText().contains("history-retained")
                        || detail.getMetadataArea().getText().contains("Login"),
                Duration.ofSeconds(5),
                "Retained history entry did not render correctly");

        SwingRobotTestSupport.dispose(frame);
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
}
