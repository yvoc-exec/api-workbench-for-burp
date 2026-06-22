package burp.ui;

import javax.swing.*;
import java.awt.*;

final class VariableStatusColors {
    private static final Color LIGHT_RESOLVED_FALLBACK = new Color(0x1B, 0x5E, 0x20);
    private static final Color DARK_RESOLVED_FALLBACK = new Color(0x81, 0xC7, 0x84);
    private static final Color LIGHT_UNRESOLVED_FALLBACK = new Color(0xB3, 0x26, 0x1E);
    private static final Color DARK_UNRESOLVED_FALLBACK = new Color(0xEF, 0x9A, 0x9A);

    private VariableStatusColors() {
    }

    static Color resolved(Component component) {
        Color uiColor = distinctUiColor(component,
                "Actions.Green",
                "Component.successFocusColor",
                "Component.successColor");
        return uiColor != null ? uiColor : themedFallback(component, LIGHT_RESOLVED_FALLBACK, DARK_RESOLVED_FALLBACK);
    }

    static Color unresolved(Component component) {
        Color uiColor = distinctUiColor(component,
                "Actions.Red",
                "Component.errorFocusColor",
                "Component.errorColor");
        return uiColor != null ? uiColor : themedFallback(component, LIGHT_UNRESOLVED_FALLBACK, DARK_UNRESOLVED_FALLBACK);
    }

    static Color disabled(Component component) {
        Color uiColor = distinctUiColor(component,
                "Table.disabledForeground",
                "Label.disabledForeground",
                "Label.disabledText",
                "TextField.inactiveForeground");
        if (uiColor != null) {
            return uiColor;
        }
        Color foreground = referenceForeground(component);
        Color background = referenceBackground(component);
        return blend(foreground, background, isDark(background) ? 0.46 : 0.56);
    }

    static Font disabledFont(Font baseFont) {
        Font font = baseFont != null ? baseFont : UIManager.getFont("Table.font");
        return font != null ? font.deriveFont(Font.ITALIC) : null;
    }

    private static Color distinctUiColor(Component component, String... keys) {
        Color reference = referenceForeground(component);
        for (String key : keys) {
            Color candidate = UIManager.getColor(key);
            if (candidate != null && !candidate.equals(reference)) {
                return candidate;
            }
        }
        return null;
    }

    private static Color themedFallback(Component component, Color lightColor, Color darkColor) {
        return isDark(referenceBackground(component)) ? darkColor : lightColor;
    }

    private static Color referenceForeground(Component component) {
        if (component != null && component.getForeground() != null) {
            return component.getForeground();
        }
        Color uiColor = UIManager.getColor("Table.foreground");
        if (uiColor != null) {
            return uiColor;
        }
        uiColor = UIManager.getColor("Label.foreground");
        return uiColor != null ? uiColor : Color.BLACK;
    }

    private static Color referenceBackground(Component component) {
        if (component != null && component.getBackground() != null) {
            return component.getBackground();
        }
        Color uiColor = UIManager.getColor("Table.background");
        if (uiColor != null) {
            return uiColor;
        }
        uiColor = UIManager.getColor("Panel.background");
        return uiColor != null ? uiColor : Color.WHITE;
    }

    private static boolean isDark(Color color) {
        return luminance(color) < 128.0;
    }

    private static double luminance(Color color) {
        if (color == null) {
            return 255.0;
        }
        return (0.299 * color.getRed()) + (0.587 * color.getGreen()) + (0.114 * color.getBlue());
    }

    private static Color blend(Color primary, Color secondary, double secondaryWeight) {
        Color safePrimary = primary != null ? primary : Color.BLACK;
        Color safeSecondary = secondary != null ? secondary : Color.WHITE;
        double clamped = Math.max(0.0, Math.min(1.0, secondaryWeight));
        double primaryWeight = 1.0 - clamped;
        int red = (int) Math.round((safePrimary.getRed() * primaryWeight) + (safeSecondary.getRed() * clamped));
        int green = (int) Math.round((safePrimary.getGreen() * primaryWeight) + (safeSecondary.getGreen() * clamped));
        int blue = (int) Math.round((safePrimary.getBlue() * primaryWeight) + (safeSecondary.getBlue() * clamped));
        return new Color(red, green, blue);
    }
}
