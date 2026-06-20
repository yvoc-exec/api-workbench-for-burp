package burp.testsupport;

import javax.swing.*;
import javax.swing.table.JTableHeader;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

public final class SwingRobotTestSupport {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final List<Window> TRACKED_WINDOWS = new CopyOnWriteArrayList<>();

    private SwingRobotTestSupport() {
    }

    public static Robot newRobot() {
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(35);
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
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setContentPane(component instanceof Container container ? container : new JPanel());
            if (!(component instanceof Container)) {
                frame.getContentPane().add(component);
            }
            frame.pack();
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
            frameRef[0] = frame;
            TRACKED_WINDOWS.add(frame);
        });
        waitForCondition(() -> frameRef[0] != null && frameRef[0].isShowing(), DEFAULT_TIMEOUT,
                "Timed out waiting for frame to become visible");
        return frameRef[0];
    }

    public static void click(Component component, Robot robot) {
        moveTo(component, robot);
        leftClick(robot);
    }

    public static void doubleClick(Component component, Robot robot) {
        moveTo(component, robot);
        doubleLeftClick(robot);
    }

    public static void clickTabbedPaneTab(JTabbedPane tabbedPane, String title, Robot robot) {
        Objects.requireNonNull(tabbedPane, "tabbedPane");
        int index = tabbedPane.indexOfTab(title);
        assertThat(index).isGreaterThanOrEqualTo(0);
        Rectangle bounds = tabbedPane.getBoundsAt(index);
        Point point = toScreenPoint(tabbedPane, bounds.x + Math.max(4, bounds.width / 2), bounds.y + Math.max(4, bounds.height / 2));
        moveTo(point, robot);
        leftClick(robot);
    }

    public static void clickTableCell(JTable table, int row, int column, Robot robot) {
        Point point = tableCellCenterOnScreen(table, row, column);
        moveTo(point, robot);
        leftClick(robot);
    }

    public static void doubleClickTableCell(JTable table, int row, int column, Robot robot) {
        Point point = tableCellCenterOnScreen(table, row, column);
        moveTo(point, robot);
        doubleLeftClick(robot);
    }

    public static void clickListIndex(JList<?> list, int index, Robot robot) {
        Objects.requireNonNull(list, "list");
        waitForComponentShowing(list, DEFAULT_TIMEOUT);
        Rectangle bounds = runOnEdtValue(() -> {
            list.ensureIndexIsVisible(index);
            return list.getCellBounds(index, index);
        });
        assertThat(bounds).as("List index bounds").isNotNull();
        Point point = toScreenPoint(list,
                bounds.x + Math.max(6, Math.max(1, bounds.width) / 2),
                bounds.y + Math.max(6, Math.max(1, bounds.height) / 2));
        moveTo(point, robot);
        leftClick(robot);
    }

    public static void clickTreePath(JTree tree, TreePath path, Robot robot) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(path, "path");
        waitForComponentShowing(tree, DEFAULT_TIMEOUT);
        Rectangle bounds = runOnEdtValue(() -> {
            tree.scrollPathToVisible(path);
            return tree.getPathBounds(path);
        });
        assertThat(bounds).as("Tree path bounds").isNotNull();
        Point point = toScreenPoint(tree,
                bounds.x + Math.max(6, Math.max(1, bounds.width) / 2),
                bounds.y + Math.max(6, Math.max(1, bounds.height) / 2));
        moveTo(point, robot);
        leftClick(robot);
    }

    public static void clickTreePathCheckbox(JTree tree, TreePath path, Robot robot) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(path, "path");
        waitForComponentShowing(tree, DEFAULT_TIMEOUT);
        Rectangle bounds = runOnEdtValue(() -> {
            tree.scrollPathToVisible(path);
            return tree.getPathBounds(path);
        });
        assertThat(bounds).as("Tree path bounds").isNotNull();
        Point point = toScreenPoint(tree,
                bounds.x + 8,
                bounds.y + Math.max(6, Math.max(1, bounds.height) / 2));
        moveTo(point, robot);
        leftClick(robot);
    }

    public static void rightClickTreePath(JTree tree, TreePath path, Robot robot) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(path, "path");
        waitForComponentShowing(tree, DEFAULT_TIMEOUT);
        Rectangle bounds = runOnEdtValue(() -> {
            tree.scrollPathToVisible(path);
            return tree.getPathBounds(path);
        });
        assertThat(bounds).as("Tree path bounds").isNotNull();
        Point point = toScreenPoint(tree,
                bounds.x + Math.max(6, Math.max(1, bounds.width) / 2),
                bounds.y + Math.max(6, Math.max(1, bounds.height) / 2));
        moveTo(point, robot);
        rightClick(robot);
    }

    public static void focus(Component component, Robot robot) {
        click(component, robot);
        waitForFocusOwner(component, DEFAULT_TIMEOUT);
    }

    public static void moveTo(Component component, Robot robot) {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(robot, "robot");
        waitForComponentShowing(component, DEFAULT_TIMEOUT);
        moveTo(centerOnScreen(component), robot);
    }

    public static void moveTo(Point point, Robot robot) {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(robot, "robot");
        robot.mouseMove(point.x, point.y);
        awaitEdt();
    }

    public static void moveBetween(Component source, Component target, int steps, Robot robot) {
        moveBetween(centerOnScreen(source), centerOnScreen(target), steps, robot);
    }

    public static void moveBetween(Point source, Point target, int steps, Robot robot) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(robot, "robot");
        int segments = Math.max(2, steps);
        for (int i = 0; i <= segments; i++) {
            double progress = (double) i / (double) segments;
            int x = (int) Math.round(source.x + ((target.x - source.x) * progress));
            int y = (int) Math.round(source.y + ((target.y - source.y) * progress));
            robot.mouseMove(x, y);
            robot.delay(20);
        }
        awaitEdt();
    }

    public static void selectAllAndType(Component component, String text, Robot robot) {
        focus(component, robot);
        pressShortcut(KeyEvent.VK_A, robot);
        pressKey(KeyEvent.VK_BACK_SPACE, robot);
        typeText(text, robot);
    }

    public static void typeText(String text, Robot robot) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!typeCharacter(ch, robot)) {
                pasteText(text.substring(i), robot);
                return;
            }
        }
        awaitEdt();
    }

    public static void pressCopyShortcut(Robot robot) {
        pressShortcut(KeyEvent.VK_C, robot);
    }

    public static void pressPasteShortcut(Robot robot) {
        pressShortcut(KeyEvent.VK_V, robot);
    }

    public static void pressSaveShortcut(Robot robot) {
        pressShortcut(KeyEvent.VK_S, robot);
    }

    public static void pressEscape(Robot robot) {
        pressKey(KeyEvent.VK_ESCAPE, robot);
    }

    public static void pressEnter(Robot robot) {
        pressKey(KeyEvent.VK_ENTER, robot);
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

    public static <T extends Component> T findByName(Container root, Class<T> type, String name) {
        if (root == null) {
            return null;
        }
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && Objects.equals(component.getName(), name)) {
                return type.cast(component);
            }
            if (component instanceof Container nested) {
                T found = findByName(nested, type, name);
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

    public static void waitForCondition(BooleanSupplier condition, Duration timeout, String message) {
        waitUntil(condition, timeout, message);
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

    public static void waitForComponentShowing(Component component, Duration timeout) {
        waitUntil(() -> component != null && component.isShowing(), timeout,
                "Timed out waiting for component to show: " + (component != null ? component.getClass().getSimpleName() : "null"));
    }

    public static Window waitForWindowTitle(String title, Duration timeout) {
        final Window[] found = new Window[1];
        waitUntil(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof Frame frame && Objects.equals(frame.getTitle(), title) && window.isShowing()) {
                    found[0] = window;
                    return true;
                }
                if (window instanceof Dialog dialog && Objects.equals(dialog.getTitle(), title) && window.isShowing()) {
                    found[0] = window;
                    return true;
                }
            }
            return false;
        }, timeout, "Timed out waiting for window title: " + title);
        return found[0];
    }

    public static JPopupMenu waitForPopup(Container root, Duration timeout) {
        final JPopupMenu[] found = new JPopupMenu[1];
        waitUntil(() -> {
            found[0] = findVisiblePopup(root);
            return found[0] != null;
        }, timeout, "Timed out waiting for popup");
        return found[0];
    }

    public static void waitForFocusOwner(Component component, Duration timeout) {
        waitUntil(() -> component != null && component.isFocusOwner(), timeout,
                "Timed out waiting for focus owner: " + (component != null ? component.getClass().getSimpleName() : "null"));
    }

    public static Point toScreenPoint(Component component, int x, int y) {
        waitForComponentShowing(component, DEFAULT_TIMEOUT);
        Point topLeft = component.getLocationOnScreen();
        return new Point(topLeft.x + x, topLeft.y + y);
    }

    public static Point centerOnScreen(Component component) {
        return centerOnScreenInternal(component);
    }

    public static int trackedWindowCount() {
        return TRACKED_WINDOWS.size();
    }

    public static void disposeTrackedWindows() {
        for (Window window : new ArrayList<>(TRACKED_WINDOWS)) {
            dispose(window);
        }
        for (Window window : Window.getWindows()) {
            if (window != null && window.isDisplayable()) {
                dispose(window);
            }
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
        runOnEdt(() -> { });
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

    public static <T> T runOnEdtValue(java.util.concurrent.Callable<T> action) {
        final Object[] value = new Object[1];
        final Throwable[] failure = new Throwable[1];
        runOnEdt(() -> {
            try {
                value[0] = action.call();
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] != null) {
            throw new AssertionError("EDT value operation failed", failure[0]);
        }
        @SuppressWarnings("unchecked")
        T typed = (T) value[0];
        return typed;
    }

    public static int shortcutMask() {
        return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    private static Point tableCellCenterOnScreen(JTable table, int row, int column) {
        waitForComponentShowing(table, DEFAULT_TIMEOUT);
        runOnEdt(() -> {
            if (table.getEditingRow() >= 0 && table.getCellEditor() != null) {
                table.getCellEditor().stopCellEditing();
            }
        });
        Rectangle bounds = table.getCellRect(row, column, true);
        JTableHeader header = table.getTableHeader();
        if (header != null && header.isShowing()) {
            bounds = bounds.intersection(new Rectangle(0, 0, table.getWidth(), table.getHeight()));
        }
        Point topLeft = table.getLocationOnScreen();
        return new Point(topLeft.x + bounds.x + Math.max(1, bounds.width) / 2,
                topLeft.y + bounds.y + Math.max(1, bounds.height) / 2);
    }

    private static void leftClick(Robot robot) {
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        awaitEdt();
    }

    private static void doubleLeftClick(Robot robot) {
        leftClick(robot);
        robot.delay(60);
        leftClick(robot);
    }

    private static void rightClick(Robot robot) {
        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
        awaitEdt();
    }

    private static void pressShortcut(int keyCode, Robot robot) {
        int modifierKey = shortcutMask() == InputEvent.META_DOWN_MASK ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
        robot.keyPress(modifierKey);
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
        robot.keyRelease(modifierKey);
        awaitEdt();
    }

    private static void pressKey(int keyCode, Robot robot) {
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
        awaitEdt();
    }

    private static boolean typeCharacter(char ch, Robot robot) {
        switch (ch) {
            case '\n' -> {
                pressEnter(robot);
                return true;
            }
            case '\t' -> {
                pressKey(KeyEvent.VK_TAB, robot);
                return true;
            }
            default -> {
            }
        }

        boolean shift = requiresShift(ch);
        int keyCode = keyCodeFor(ch);
        if (keyCode == KeyEvent.VK_UNDEFINED) {
            return false;
        }
        if (shift) {
            robot.keyPress(KeyEvent.VK_SHIFT);
        }
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
        if (shift) {
            robot.keyRelease(KeyEvent.VK_SHIFT);
        }
        return true;
    }

    private static int keyCodeFor(char ch) {
        if (Character.isLetterOrDigit(ch)) {
            return KeyEvent.getExtendedKeyCodeForChar(Character.toUpperCase(ch));
        }
        return switch (ch) {
            case ' ' -> KeyEvent.VK_SPACE;
            case '-' , '_' -> KeyEvent.VK_MINUS;
            case '=' , '+' -> KeyEvent.VK_EQUALS;
            case '[' , '{' -> KeyEvent.VK_OPEN_BRACKET;
            case ']' , '}' -> KeyEvent.VK_CLOSE_BRACKET;
            case '\\', '|' -> KeyEvent.VK_BACK_SLASH;
            case ';' , ':' -> KeyEvent.VK_SEMICOLON;
            case '\'' , '"' -> KeyEvent.VK_QUOTE;
            case ',' , '<' -> KeyEvent.VK_COMMA;
            case '.' , '>' -> KeyEvent.VK_PERIOD;
            case '/' , '?' -> KeyEvent.VK_SLASH;
            case '`' , '~' -> KeyEvent.VK_BACK_QUOTE;
            case '!' -> KeyEvent.VK_1;
            case '@' -> KeyEvent.VK_2;
            case '#' -> KeyEvent.VK_3;
            case '$' -> KeyEvent.VK_4;
            case '%' -> KeyEvent.VK_5;
            case '^' -> KeyEvent.VK_6;
            case '&' -> KeyEvent.VK_7;
            case '*' -> KeyEvent.VK_8;
            case '(' -> KeyEvent.VK_9;
            case ')' -> KeyEvent.VK_0;
            default -> KeyEvent.VK_UNDEFINED;
        };
    }

    private static boolean requiresShift(char ch) {
        return Character.isUpperCase(ch)
                || "~!@#$%^&*()_+{}|:\"<>?".indexOf(ch) >= 0;
    }

    private static void pasteText(String text, Robot robot) {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable previous = clipboard.getContents(null);
        try {
            clipboard.setContents(new StringSelection(text != null ? text : ""), null);
            pressPasteShortcut(robot);
        } finally {
            if (previous != null) {
                clipboard.setContents(previous, null);
            }
        }
    }

    private static <T extends Component> void collectByType(Container root, Class<T> type, List<T> found) {
        if (root == null) {
            return;
        }
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                found.add(type.cast(component));
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
        if (component instanceof JLabel label) {
            return Objects.equals(label.getText(), text);
        }
        if (component instanceof JTextComponent textComponent) {
            return Objects.equals(textComponent.getText(), text);
        }
        if (component instanceof JComponent jComponent) {
            Object value = jComponent.getClientProperty("text");
            return Objects.equals(value, text);
        }
        return false;
    }

    private static Point centerOnScreenInternal(Component component) {
        waitForComponentShowing(component, DEFAULT_TIMEOUT);
        Point topLeft = component.getLocationOnScreen();
        return new Point(topLeft.x + Math.max(1, component.getWidth()) / 2,
                topLeft.y + Math.max(1, component.getHeight()) / 2);
    }

    private static JPopupMenu findVisiblePopup(Container root) {
        if (root != null) {
            JPopupMenu nested = findVisiblePopupRecursive(root);
            if (nested != null) {
                return nested;
            }
        }
        for (Window window : Window.getWindows()) {
            if (window instanceof RootPaneContainer rootPaneContainer) {
                JPopupMenu nested = findVisiblePopupRecursive(rootPaneContainer.getRootPane());
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static JPopupMenu findVisiblePopupRecursive(Container root) {
        if (root == null) {
            return null;
        }
        for (Component component : root.getComponents()) {
            if (component instanceof JPopupMenu popup && popup.isVisible()) {
                return popup;
            }
            if (component instanceof Container nested) {
                JPopupMenu popup = findVisiblePopupRecursive(nested);
                if (popup != null) {
                    return popup;
                }
            }
        }
        return null;
    }

    public static String clipboardText() {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        try {
            Object data = clipboard.getData(DataFlavor.stringFlavor);
            return data != null ? String.valueOf(data) : "";
        } catch (Exception e) {
            throw new AssertionError("Failed to read clipboard", e);
        }
    }
}
