package burp.ui;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.Window;

public final class DialogParentResolver {
    private DialogParentResolver() {
    }

    public static Window ownerFor(Component component) {
        if (component != null) {
            Window owner = SwingUtilities.getWindowAncestor(component);
            if (owner != null) {
                return owner;
            }
        }
        return activeVisibleWindow();
    }

    public static Component parentComponent(Component component) {
        return component != null ? component : activeVisibleWindow();
    }

    private static Window activeVisibleWindow() {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        Window active = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (active != null && active.isVisible()) {
            return active;
        }
        for (Window window : Window.getWindows()) {
            if (window != null && window.isVisible()) {
                return window;
            }
        }
        return null;
    }
}
