package burp.ui;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public final class VariableHighlightStyler {
    private static final String HIGHLIGHT_KEY = "awb.variable.highlight.tags";
    private static final Highlighter.HighlightPainter RESOLVED_PAINTER =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(188, 235, 188));
    private static final Highlighter.HighlightPainter UNRESOLVED_PAINTER =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 205, 205));

    private VariableHighlightStyler() {
    }

    public static List<VariableTokenScanner.VariableToken> apply(JTextComponent component,
                                                                 String text,
                                                                 java.util.Map<String, String> variables) {
        List<VariableTokenScanner.VariableToken> tokens = VariableTokenScanner.scan(text, variables);
        apply(component, tokens);
        return tokens;
    }

    public static void apply(JTextComponent component, List<VariableTokenScanner.VariableToken> tokens) {
        if (component == null) {
            return;
        }
        Highlighter highlighter = component.getHighlighter();
        if (highlighter == null) {
            return;
        }
        clear(component);
        if (tokens == null || tokens.isEmpty()) {
            return;
        }
        List<Object> tags = new ArrayList<>();
        for (VariableTokenScanner.VariableToken token : tokens) {
            if (token == null) {
                continue;
            }
            try {
                Highlighter.HighlightPainter painter = token.isResolved() ? RESOLVED_PAINTER : UNRESOLVED_PAINTER;
                Object tag = highlighter.addHighlight(token.start, token.end, painter);
                if (tag != null) {
                    tags.add(tag);
                }
            } catch (BadLocationException ignored) {
            }
        }
        component.putClientProperty(HIGHLIGHT_KEY, tags);
    }

    public static void clear(JTextComponent component) {
        if (component == null) {
            return;
        }
        Highlighter highlighter = component.getHighlighter();
        if (highlighter == null) {
            return;
        }
        Object prop = component.getClientProperty(HIGHLIGHT_KEY);
        if (prop instanceof List<?> list) {
            for (Object tag : list) {
                try {
                    highlighter.removeHighlight(tag);
                } catch (Exception ignored) {
                }
            }
        } else {
            highlighter.removeAllHighlights();
        }
        component.putClientProperty(HIGHLIGHT_KEY, new ArrayList<>());
    }
}
