package burp.ui;

import burp.UniversalImporter;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.testsupport.ImporterPanelTestSupport;
import burp.testsupport.SwingRobotTestSupport;
import burp.testsupport.UiWorkflowFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class WorkbenchCollectionsToolbarUiIT {
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(8);

    @AfterEach
    void tearDown() {
        SwingRobotTestSupport.disposeTrackedWindows();
    }

    @Test
    void controlsRemainReachableAtMinimumAndPreferredPaneWidths() {
        UniversalImporter importer = UiWorkflowFixtures.newImporter(
                UiWorkflowFixtures.mockUiApi(request ->
                        burp.testsupport.RunnerScriptTestFixtures.mockResponse(
                                200, "{\"ok\":true}", "application/json")));

        ImporterPanel panel = importer.getUI();
        ApiCollection collection = new ApiCollection();
        collection.name = "Toolbar Fixture";

        ApiRequest request = new ApiRequest();
        request.name = "Toolbar Request";
        request.method = "GET";
        request.url = "https://example.test/toolbar";
        collection.requests.add(request);

        SwingRobotTestSupport.runOnEdt(
                () -> panel.restoreWorkspaceCollections(List.of(collection)));

        JFrame frame = null;
        try {
            frame = SwingRobotTestSupport.showInFrame(
                    panel.getPanel(), "Workbench Collections Toolbar UI");
            JFrame shownFrame = frame;

            SwingRobotTestSupport.runOnEdt(() -> {
                shownFrame.setSize(1100, 720);
                shownFrame.setLocationRelativeTo(null);
                shownFrame.validate();
                shownFrame.repaint();
            });

            Robot robot = SwingRobotTestSupport.newRobot();
            JButton add = ImporterPanelTestSupport.getField(panel, "addCollectionBtn");
            JButton remove = ImporterPanelTestSupport.getField(panel, "removeCollectionBtn");
            JButton actions = panel.getActionsButtonForTests();

            assertThat(add).isNotNull();
            assertThat(remove).isNotNull();
            assertThat(actions).isNotNull();

            JSplitPane split = findOwningHorizontalSplit(panel.getPanel(), add);
            assertThat(split).isNotNull();

            for (int targetWidth : new int[]{220, 280}) {
                setLeftPaneWidth(shownFrame, split, targetWidth);

                SwingRobotTestSupport.waitUntilOnEdt(
                        () -> {
                            int actualWidth = split.getLeftComponent().getWidth();
                            return actualWidth >= targetWidth - 12
                                    && actualWidth <= targetWidth + 12
                                    && centerIsVisible(add)
                                    && centerIsVisible(remove)
                                    && centerIsVisible(actions)
                                    && remove.isEnabled()
                                    && actions.isEnabled();
                        },
                        UI_TIMEOUT,
                        "Collections controls were clipped at width " + targetWidth);

                assertThat(centerIsVisible(add)).isTrue();
                assertThat(centerIsVisible(remove)).isTrue();
                assertThat(centerIsVisible(actions)).isTrue();
            }

            setLeftPaneWidth(shownFrame, split, 220);
            SwingRobotTestSupport.waitUntilOnEdt(
                    () -> centerIsVisible(actions) && actions.isEnabled(),
                    UI_TIMEOUT,
                    "Actions was not physically reachable at minimum width");

            // The existing Runner UI workflow already verifies that the real
            // Actions listener opens the Options dialog. This regression test
            // isolates physical reachability at the minimum pane width by
            // temporarily replacing listeners with a click counter.
            AtomicInteger clickCount = new AtomicInteger();
            ActionListener[] originalListeners = actions.getActionListeners();

            SwingRobotTestSupport.runOnEdt(() -> {
                for (ActionListener listener : originalListeners) {
                    actions.removeActionListener(listener);
                }
                actions.addActionListener(event -> clickCount.incrementAndGet());
            });

            try {
                SwingRobotTestSupport.click(actions, robot);
                SwingRobotTestSupport.waitUntilOnEdt(
                        () -> clickCount.get() == 1,
                        UI_TIMEOUT,
                        "Robot click did not reach Actions at minimum width");
            } finally {
                SwingRobotTestSupport.runOnEdt(() -> {
                    for (ActionListener listener : actions.getActionListeners()) {
                        actions.removeActionListener(listener);
                    }
                    for (ActionListener listener : originalListeners) {
                        actions.addActionListener(listener);
                    }
                });
            }
        } finally {
            SwingRobotTestSupport.dispose(frame);
        }
    }

    private static boolean centerIsVisible(JComponent component) {
        if (component == null
                || !component.isShowing()
                || component.getWidth() <= 0
                || component.getHeight() <= 0) {
            return false;
        }

        Rectangle visible = component.getVisibleRect();
        return visible.contains(
                component.getWidth() / 2,
                component.getHeight() / 2);
    }

    private static void setLeftPaneWidth(
            JFrame frame, JSplitPane split, int width) {

        SwingRobotTestSupport.runOnEdt(() -> {
            split.setDividerLocation(width);
            frame.validate();
            frame.repaint();
        });
    }

    private static JSplitPane findOwningHorizontalSplit(
            Component root, Component toolbarChild) {

        if (root instanceof JSplitPane split
                && split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT
                && split.getLeftComponent() != null
                && SwingUtilities.isDescendingFrom(
                        toolbarChild, split.getLeftComponent())) {
            return split;
        }

        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JSplitPane found = findOwningHorizontalSplit(
                        child, toolbarChild);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }
}