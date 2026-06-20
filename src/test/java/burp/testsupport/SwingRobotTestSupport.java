package burp.testsupport;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

public final class SwingRobotTestSupport {
    private static final List<Window> TRACKED_WINDOWS = new CopyOnWriteArrayList<>();

    private SwingRobotTestSupport() {
    }

    public static Robot newRobot() {
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(30);
            robot.setAutoWaitForIdle(true);
            return robot;
        } catch (AWTException e) {
            throw new AssertionError("Failed to create AWT Robot", e);
        }
    }

    public static JFrame showInFrame(Component component, String title) {
        final JFrame[] frameRef = new JFrame[1];
        runOnEdt(() -> {
            JFrame frame = new JFrame(title);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setContentPane(component instanceof Container container ? (Container) component : new javax.swing.JPanel());
            if (!(component instanceof Container)) {
                frame.getContentPane().add(component);
            }
            frame.pack();
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
            frameRef[0] = frame;
            TRACKED_WINDOWS.add(frame);
        });
        waitUntil(() -> frameRef[0] != null && frameRef[0].isShowing(), Duration.ofSeconds(5),
                "Timed out waiting for frame to become visible");
        return frameRef[0];
    }

    public static void click(Component component, Robot robot) {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(robot, "robot");
        waitUntil(() -> component.isShowing(), Duration.ofSeconds(5), "Component was not showing for robot click");
        Point point = centerOnScreen(component);
        robot.mouseMove(point.x, point.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        awaitEdt();
    }

    public static void focus(Component component, Robot robot) {
        click(component, robot);
        waitUntil(component::isFocusOwner, Duration.ofSeconds(5),
                "Timed out waiting for component focus: " + component.getClass().getSimpleName());
    }

    public static void pressSaveShortcut(Robot robot) {
        int shortcutMask = shortcutMask();
        int modifierKey = shortcutMask == InputEvent.META_DOWN_MASK ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
        robot.keyPress(modifierKey);
        robot.keyPress(KeyEvent.VK_S);
        robot.keyRelease(KeyEvent.VK_S);
        robot.keyRelease(modifierKey);
        awaitEdt();
    }

    public static int shortcutMask() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                ? InputEvent.META_DOWN_MASK
                : InputEvent.CTRL_DOWN_MASK;
    }

    public static <T extends Component> T findByText(Container root, Class<T> type, String text) {
        if (root == null) {
            return null;
        }
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && textMatches(component, text)) {
                return type.cast(component);
            }
            if (component instanceof Container nested) {
                T found = findByText(nested, type, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    public static <T extends Component> List<T> findByType(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collectByType(root, type, found);
        return found;
    }

    public static void waitUntil(BooleanSupplier condition, Duration timeout, String message) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            awaitEdt();
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(message, e);
            }
        }
        assertThat(condition.getAsBoolean()).as(message).isTrue();
    }

    public static void disposeTrackedWindows() {
        for (Window window : new ArrayList<>(TRACKED_WINDOWS)) {
            dispose(window);
        }
        TRACKED_WINDOWS.clear();
        awaitEdt();
    }

    public static void dispose(Window window) {
        if (window == null) {
            return;
        }
        runOnEdt(() -> {
            window.setVisible(false);
            window.dispose();
        });
        TRACKED_WINDOWS.remove(window);
    }

    public static void awaitEdt() {
        runOnEdt(() -> {
        });
    }

    public static void runOnEdt(Runnable action) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
            } else {
                SwingUtilities.invokeAndWait(action);
            }
        } catch (Exception e) {
            throw new AssertionError("EDT operation failed", e);
        }
    }

    private static void collectByType(Container root, Class<? extends Component> type, List found) {
        if (root == null) {
            return;
        }
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                found.add(component);
            }
            if (component instanceof Container nested) {
                collectByType(nested, type, found);
            }
        }
    }

    private static boolean textMatches(Component component, String text) {
        if (component instanceof AbstractButton button) {
            return Objects.equals(button.getText(), text);
        }
        if (component instanceof JComponent jComponent) {
            Object value = jComponent.getClientProperty("text");
            return Objects.equals(value, text);
        }
        return false;
    }

    private static Point centerOnScreen(Component component) {
        Point topLeft = component.getLocationOnScreen();
        return new Point(topLeft.x + Math.max(1, component.getWidth()) / 2,
                topLeft.y + Math.max(1, component.getHeight()) / 2);
    }
}
