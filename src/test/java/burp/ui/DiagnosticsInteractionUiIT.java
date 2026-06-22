package burp.ui;

import burp.UniversalImporter;
import burp.diagnostics.DiagnosticEvent;
import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;
import burp.testsupport.SwingRobotTestSupport;
import burp.testsupport.UiWorkflowFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class DiagnosticsInteractionUiIT {
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(5);
    private static final Path FAILURE_ARTIFACT_DIR = Path.of("target", "ui-failure-artifacts");

    private Transferable previousClipboard;

    @AfterEach
    void tearDown() {
        DiagnosticStore.getInstance().setCaptureEnabled(false);
        DiagnosticStore.getInstance().clear();
        restoreClipboard();
        SwingRobotTestSupport.disposeTrackedWindows();
    }

    @Test
    void diagnosticsTabUsesRealCaptureCopyClearAndDebugInteractions() {
        UniversalImporter importer = UiWorkflowFixtures.newImporter(UiWorkflowFixtures.mockUiApi(null));
        ImporterPanel panel = importer.getUI();
        JFrame frame = SwingRobotTestSupport.showInFrame(panel.getPanel(), "Diagnostics UI");
        SwingRobotTestSupport.expandWindowToAvailableScreen(frame);
        Robot robot = SwingRobotTestSupport.newRobot();

        rememberClipboard();
        SwingRobotTestSupport.runOnEdt(() -> panel.getTabbedPane().setSelectedIndex(panel.getTabbedPane().indexOfTab("Diagnostics")));

        JCheckBox capture = panel.getDiagnosticsCaptureBoxForTests();
        JCheckBox includeDebug = panel.getDiagnosticsIncludeDebugBoxForTests();
        JButton refresh = panel.getDiagnosticsRefreshButtonForTests();
        JButton clear = panel.getDiagnosticsClearButtonForTests();
        JButton copy = panel.getDiagnosticsCopyButtonForTests();
        JList<?> eventList = panel.getDiagnosticsEventListForTests();
        JTextArea detail = panel.getDiagnosticsEventDetailAreaForTests();
        JTextArea snapshot = panel.getDiagnosticsSnapshotAreaForTests();
        SwingRobotTestSupport.waitUntilOnEdt(
                () -> panel.getTabbedPane().getSelectedIndex() == panel.getTabbedPane().indexOfTab("Diagnostics"),
                UI_TIMEOUT,
                "Diagnostics tab did not become selected");
        SwingRobotTestSupport.waitForComponentShowing(capture, UI_TIMEOUT);

        assertThat(capture.isSelected()).isFalse();
        SwingRobotTestSupport.click(capture, robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> capture.isSelected(),
                UI_TIMEOUT,
                "Diagnostics capture did not turn on");

        emitDiagnostic(DiagnosticSeverity.INFO, "Visible info",
                "Authorization: Bearer secret-token\nCookie: session=secret-cookie\nclient_secret=secret-client");
        emitDiagnostic(DiagnosticSeverity.DEBUG, "Visible debug",
                "refresh_token=secret-refresh\npassword=secret-password");
        SwingRobotTestSupport.click(refresh, robot);

        SwingRobotTestSupport.waitUntilOnEdt(() -> eventList.getModel().getSize() == 1,
                UI_TIMEOUT,
                "Expected only non-debug events to be shown when debug is disabled");
        SwingRobotTestSupport.clickListIndex(eventList, 0, robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> eventList.getSelectedIndex() == 0,
                UI_TIMEOUT,
                "Diagnostics event row did not stay selected");
        waitForDiagnosticDetails(eventList, detail);

        assertThat(detail.getText()).contains("Visible info");
        assertThat(detail.getText()).doesNotContain("secret-token")
                .doesNotContain("secret-cookie")
                .doesNotContain("secret-client")
                .doesNotContain("secret-refresh")
                .doesNotContain("secret-password");

        SwingRobotTestSupport.click(copy, robot);
        String copiedWithoutDebug = SwingRobotTestSupport.clipboardText();
        assertThat(copiedWithoutDebug).contains("Visible info");
        assertThat(copiedWithoutDebug).doesNotContain("secret-token")
                .doesNotContain("secret-cookie")
                .doesNotContain("secret-client")
                .doesNotContain("secret-refresh")
                .doesNotContain("secret-password")
                .doesNotContain("Authorization: Bearer")
                .doesNotContain("Cookie:");

        SwingRobotTestSupport.click(includeDebug, robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> includeDebug.isSelected(),
                UI_TIMEOUT,
                "Include Debug checkbox did not turn on");
        SwingRobotTestSupport.waitUntilOnEdt(() -> eventList.getModel().getSize() == 2,
                UI_TIMEOUT,
                "Debug events did not become visible after enabling debug");
        assertThat(snapshot.getText()).contains("Visible info").contains("Visible debug");
        assertThat(snapshot.getText()).doesNotContain("secret-token")
                .doesNotContain("secret-cookie")
                .doesNotContain("secret-client")
                .doesNotContain("secret-refresh")
                .doesNotContain("secret-password");

        SwingRobotTestSupport.click(clear, robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> eventList.getModel().getSize() == 0,
                UI_TIMEOUT,
                "Diagnostics clear did not empty the event list");
        assertThat(detail.getText()).isBlank();

        SwingRobotTestSupport.click(capture, robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> !capture.isSelected(),
                UI_TIMEOUT,
                "Diagnostics capture did not turn off");
        emitDiagnostic(DiagnosticSeverity.INFO, "Capture off info", "Authorization: Bearer off-secret");
        SwingRobotTestSupport.click(refresh, robot);
        assertThat(eventList.getModel().getSize()).isZero();
        assertThat(DiagnosticStore.getInstance().snapshot()).isEmpty();

        SwingRobotTestSupport.dispose(frame);
    }

    private static void waitForDiagnosticDetails(JList<?> eventList, JTextArea detail) {
        try {
            SwingRobotTestSupport.waitUntilOnEdt(
                    () -> eventList.getSelectedIndex() >= 0 && !detail.getText().isBlank(),
                    UI_TIMEOUT,
                    "Diagnostics event details did not populate");
        } catch (AssertionError failure) {
            throw enrichFailure(failure, eventList, detail);
        }
    }

    private static void emitDiagnostic(DiagnosticSeverity severity, String message, String details) {
        DiagnosticEvent event = DiagnosticEvent.of(DiagnosticOperation.WORKBENCH_SEND, severity, "ui-test", message)
                .withDetails(details)
                .withAttribute("secret", "Authorization: Bearer secret-token");
        DiagnosticStore.getInstance().record(event);
    }

    private static AssertionError enrichFailure(AssertionError failure, JList<?> eventList, JTextArea detail) {
        DiagnosticsUiEvidence evidence = SwingRobotTestSupport.runOnEdtValue(() -> new DiagnosticsUiEvidence(
                eventList.getSelectedIndex(),
                eventList.getModel().getSize(),
                detail.getText(),
                activeWindowTitles()));
        Path screenshot = captureScreenshot();
        String visibleDetail = evidence.visibleDetailText();
        if (visibleDetail != null && visibleDetail.length() > 500) {
            visibleDetail = visibleDetail.substring(0, 500) + "...";
        }
        String message = "Diagnostics event details did not populate"
                + System.lineSeparator() + "selectedIndex=" + evidence.selectedIndex()
                + System.lineSeparator() + "modelRowCount=" + evidence.modelRowCount()
                + System.lineSeparator() + "visibleDetailText=" + (visibleDetail == null ? "" : visibleDetail)
                + System.lineSeparator() + "activeWindows=" + evidence.activeWindows()
                + System.lineSeparator() + "screenshot=" + (screenshot != null ? screenshot : "unavailable");
        return new AssertionError(message, failure);
    }

    private static List<String> activeWindowTitles() {
        return List.of(Window.getWindows()).stream()
                .filter(Window::isShowing)
                .map(DiagnosticsInteractionUiIT::describeWindow)
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

    private static Path captureScreenshot() {
        try {
            Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            if (bounds == null || bounds.isEmpty()) {
                return null;
            }
            BufferedImage image = new Robot().createScreenCapture(bounds);
            Files.createDirectories(FAILURE_ARTIFACT_DIR);
            Path output = FAILURE_ARTIFACT_DIR.resolve("DiagnosticsInteractionUiIT-detail-timeout.png");
            ImageIO.write(image, "png", output.toFile());
            return output;
        } catch (AWTException | IOException ignored) {
            return null;
        }
    }

    private void rememberClipboard() {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        previousClipboard = clipboard.getContents(null);
    }

    private void restoreClipboard() {
        if (previousClipboard == null) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(previousClipboard, null);
        } catch (Exception ignored) {
            // best effort
        }
    }

    private record DiagnosticsUiEvidence(int selectedIndex,
                                         int modelRowCount,
                                         String visibleDetailText,
                                         List<String> activeWindows) {
    }
}
