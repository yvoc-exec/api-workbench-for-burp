package burp.ui.tree;

import javax.swing.*;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeCellRenderer;
import java.awt.*;

/**
 * Main request tree renderer that uses a safe panel layout with explicit
 * depth spacing. This is separate from the popup checkbox renderer.
 */
public class MainRequestTreeCellRenderer implements TreeCellRenderer {
    private static final int DEPTH_INDENT_PX = 16;

    private final DefaultTreeCellRenderer fallback = new DefaultTreeCellRenderer();
    private final JPanel panel = new JPanel(new BorderLayout(2, 0));
    private final JPanel spacer = new JPanel();
    private final JLabel label = new JLabel();

    public MainRequestTreeCellRenderer() {
        panel.setOpaque(false);
        spacer.setOpaque(false);
        label.setOpaque(false);
        panel.add(spacer, BorderLayout.WEST);
        panel.add(label, BorderLayout.CENTER);
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                  boolean expanded, boolean leaf, int row, boolean hasFocus) {
        if (!(value instanceof CollectionTreeNode)) {
            return fallback.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
        }

        CollectionTreeNode node = (CollectionTreeNode) value;
        Component fallbackComponent = fallback.getTreeCellRendererComponent(tree, value.toString(), selected, expanded, leaf, row, hasFocus);
        JLabel fallbackLabel = fallbackComponent instanceof JLabel ? (JLabel) fallbackComponent : null;

        label.setText(value.toString());
        label.setIcon(BurpLikeTreeCellRenderer.resolveIcon(node, expanded, leaf));
        label.setFont(fallbackLabel != null ? fallbackLabel.getFont() : tree.getFont());
        label.setEnabled(fallbackLabel == null || fallbackLabel.isEnabled());
        label.setForeground(resolveForeground(tree, fallbackLabel, selected, hasFocus));
        label.setBackground(resolveBackground(tree, fallbackLabel, selected));
        label.setOpaque(false);

        int level = Math.max(0, node.getLevel());
        int depthPadding = Math.max(0, level - 1) * DEPTH_INDENT_PX;
        Dimension spacerSize = new Dimension(depthPadding, 1);
        spacer.setPreferredSize(spacerSize);
        spacer.setMinimumSize(spacerSize);
        spacer.setMaximumSize(spacerSize);

        panel.setOpaque(selected);
        panel.setBackground(resolvePanelBackground(tree, fallbackLabel, selected));
        panel.setFont(label.getFont());
        return panel;
    }

    private Color resolveForeground(JTree tree, JLabel fallbackLabel, boolean selected, boolean hasFocus) {
        if (fallbackLabel != null && fallbackLabel.getForeground() != null && !selected) {
            return fallbackLabel.getForeground();
        }
        if (selected) {
            Color fg = tree != null && tree.hasFocus()
                    ? UIManager.getColor("Tree.selectionForeground")
                    : UIManager.getColor("Tree.selectionInactiveForeground");
            return fg != null ? fg : Color.WHITE;
        }
        Color fg = UIManager.getColor("Tree.textForeground");
        return fg != null ? fg : Color.BLACK;
    }

    private Color resolveBackground(JTree tree, JLabel fallbackLabel, boolean selected) {
        if (selected) {
            Color bg = fallbackLabel != null ? fallbackLabel.getBackground() : null;
            if (bg == null || bg.getAlpha() == 0) {
                bg = UIManager.getColor("Tree.selectionBackground");
            }
            return bg != null ? bg : new Color(0x3875D6);
        }
        Color bg = tree != null ? tree.getBackground() : null;
        return bg != null ? bg : UIManager.getColor("Tree.background");
    }

    private Color resolvePanelBackground(JTree tree, JLabel fallbackLabel, boolean selected) {
        if (selected) {
            return resolveBackground(tree, fallbackLabel, true);
        }
        Color bg = tree != null ? tree.getBackground() : null;
        return bg != null ? bg : UIManager.getColor("Tree.background");
    }
}
