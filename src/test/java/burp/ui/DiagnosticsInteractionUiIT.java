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

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.Transferable;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class DiagnosticsInteractionUiIT {

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
        Robot robot = SwingRobotTestSupport.newRobot();

        rememberClipboard();
        SwingRobotTestSupport.clickTabbedPaneTab(panel.getTabbedPane(), "Diagnostics", robot);

        JCheckBox capture = panel.getDiagnosticsCaptureBoxForTests();
        JCheckBox includeDebug = panel.getDiagnosticsIncludeDebugBoxForTests();
        JButton refresh = panel.getDiagnosticsRefreshButtonForTests();
        JButton clear = panel.getDiagnosticsClearButtonForTests();
        JButton copy = panel.getDiagnosticsCopyButtonForTests();
        JList<?> eventList = panel.getDiagnosticsEventListForTests();
        JTextArea detail = panel.getDiagnosticsEventDetailAreaForTests();
        JTextArea snapshot = panel.getDiagnosticsSnapshotAreaForTests();

        assertThat(capture.isSelected()).isFalse();
        SwingRobotTestSupport.click(capture, robot);
        SwingRobotTestSupport.waitUntil(capture::isSelected,
                Duration.ofSeconds(5),
                "Diagnostics capture did not turn on");

        emitDiagnostic(DiagnosticSeverity.INFO, "Visible info",
                "Authorization: Bearer secret-token\nCookie: session=secret-cookie\nclient_secret=secret-client");
        emitDiagnostic(DiagnosticSeverity.DEBUG, "Visible debug",
                "refresh_token=secret-refresh\npassword=secret-password");
        SwingRobotTestSupport.click(refresh, robot);

        SwingRobotTestSupport.waitUntil(() -> eventList.getModel().getSize() == 1,
                Duration.ofSeconds(5),
                "Expected only non-debug events to be shown when debug is disabled");
        SwingRobotTestSupport.clickListIndex(eventList, 0, robot);
        SwingRobotTestSupport.waitUntil(() -> !detail.getText().isBlank(),
                Duration.ofSeconds(5),
                "Diagnostics event details did not populate");

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
        SwingRobotTestSupport.waitUntil(includeDebug::isSelected,
                Duration.ofSeconds(5),
                "Include Debug checkbox did not turn on");
        SwingRobotTestSupport.waitUntil(() -> eventList.getModel().getSize() == 2,
                Duration.ofSeconds(5),
                "Debug events did not become visible after enabling debug");
        assertThat(snapshot.getText()).contains("Visible info").contains("Visible debug");
        assertThat(snapshot.getText()).doesNotContain("secret-token")
                .doesNotContain("secret-cookie")
                .doesNotContain("secret-client")
                .doesNotContain("secret-refresh")
                .doesNotContain("secret-password");

        SwingRobotTestSupport.click(clear, robot);
        SwingRobotTestSupport.waitUntil(() -> eventList.getModel().getSize() == 0,
                Duration.ofSeconds(5),
                "Diagnostics clear did not empty the event list");
        assertThat(detail.getText()).isBlank();

        SwingRobotTestSupport.click(capture, robot);
        SwingRobotTestSupport.waitUntil(() -> !capture.isSelected(),
                Duration.ofSeconds(5),
                "Diagnostics capture did not turn off");
        emitDiagnostic(DiagnosticSeverity.INFO, "Capture off info", "Authorization: Bearer off-secret");
        SwingRobotTestSupport.click(refresh, robot);
        assertThat(eventList.getModel().getSize()).isZero();
        assertThat(DiagnosticStore.getInstance().snapshot()).isEmpty();

        SwingRobotTestSupport.dispose(frame);
    }

    private static void emitDiagnostic(DiagnosticSeverity severity, String message, String details) {
        DiagnosticEvent event = DiagnosticEvent.of(DiagnosticOperation.WORKBENCH_SEND, severity, "ui-test", message)
                .withDetails(details)
                .withAttribute("secret", "Authorization: Bearer secret-token");
        DiagnosticStore.getInstance().record(event);
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
}
