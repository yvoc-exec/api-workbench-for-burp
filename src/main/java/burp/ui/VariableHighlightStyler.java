package burp.ui;

import javax.swing.*;
import javax.swing.text.Highlighter;
import javax.swing.text.Document;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class VariableHighlightStyler {
    private static final String HIGHLIGHT_KEY = "awb.variable.highlight.tags";

    private VariableHighlightStyler() {
    }

    public static List<VariableTokenScanner.VariableToken> apply(JTextComponent component,
                                                                 String text,
                                                                 Map<String, String> variables) {
        List<VariableTokenScanner.VariableToken> tokens = VariableTokenScanner.scan(text, variables);
        apply(component, tokens);
        return tokens;
    }

    public static void apply(JTextComponent component, List<VariableTokenScanner.VariableToken> tokens) {
        if (component == null) {
            return;
        }
        clear(component);
        Document document = component.getDocument();
        if (!(document instanceof StyledDocument styledDocument)) {
            return;
        }

        List<VariableTokenScanner.VariableToken> safeTokens = tokens != null ? tokens : List.of();
        Color baseForeground = resolveBaseForeground(component);
        SimpleAttributeSet baseAttributes = new SimpleAttributeSet();
        StyleConstants.setForeground(baseAttributes, baseForeground);
        applyAttributes(styledDocument, 0, styledDocument.getLength(), baseAttributes);

        for (VariableTokenScanner.VariableToken token : safeTokens) {
            if (token == null) {
                continue;
            }
            SimpleAttributeSet attributes = new SimpleAttributeSet();
            StyleConstants.setForeground(attributes, token.isResolved()
                    ? resolveResolvedColor(component)
                    : resolveUnresolvedColor(component));
            applyAttributes(styledDocument, token.start, token.end - token.start, attributes);
        }
        component.putClientProperty(HIGHLIGHT_KEY, new ArrayList<>(safeTokens));
    }

    public static void clear(JTextComponent component) {
        if (component == null) {
            return;
        }
        Document document = component.getDocument();
        if (document instanceof StyledDocument styledDocument) {
            SimpleAttributeSet baseAttributes = new SimpleAttributeSet();
            StyleConstants.setForeground(baseAttributes, resolveBaseForeground(component));
            applyAttributes(styledDocument, 0, styledDocument.getLength(), baseAttributes);
        }
        Highlighter highlighter = component.getHighlighter();
        if (highlighter != null) {
            highlighter.removeAllHighlights();
        }
        component.putClientProperty(HIGHLIGHT_KEY, new ArrayList<>());
    }

    private static void applyAttributes(StyledDocument document, int start, int length, SimpleAttributeSet attributes) {
        if (document == null || attributes == null || length <= 0 || start < 0) {
            return;
        }
        try {
            document.setCharacterAttributes(start, length, attributes, false);
        } catch (Exception ignored) {
        }
    }

    private static Color resolveBaseForeground(JTextComponent component) {
        Color foreground = component != null ? component.getForeground() : null;
        if (foreground != null) {
            return foreground;
        }
        Color uiColor = UIManager.getColor("TextField.foreground");
        return uiColor != null ? uiColor : Color.BLACK;
    }

    private static Color resolveResolvedColor(JTextComponent component) {
        Color background = component != null ? component.getBackground() : null;
        return chooseThemeColor(background, new Color(0x2E7D32), new Color(0x81C784));
    }

    private static Color resolveUnresolvedColor(JTextComponent component) {
        Color background = component != null ? component.getBackground() : null;
        return chooseThemeColor(background, new Color(0xC62828), new Color(0xEF9A9A));
    }

    private static Color chooseThemeColor(Color background, Color lightThemeColor, Color darkThemeColor) {
        if (background == null) {
            return lightThemeColor;
        }
        double luminance = (0.299 * background.getRed()) + (0.587 * background.getGreen()) + (0.114 * background.getBlue());
        return luminance < 128.0 ? darkThemeColor : lightThemeColor;
    }
}
