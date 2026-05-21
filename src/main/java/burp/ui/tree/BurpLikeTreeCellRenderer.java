package burp.ui.tree;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeCellRenderer;
import java.awt.*;

/**
 * Shared "Burp-like" tree cell renderer used by:
 * <ul>
 *   <li>Main request tree (non-checkbox mode)</li>
 *   <li>Popup selection trees (checkbox mode)</li>
 * </ul>
 *
 * <p>Provides consistent flat icons, compact spacing, explicit depth-based
 * hierarchy padding, and a subtle guide cue so nesting is readable regardless
 * of the current Look-and-Feel.</p>
 */
public class BurpLikeTreeCellRenderer implements TreeCellRenderer {

    private static final int BASE_INDENT = 4;
    private static final int LEVEL_INDENT = 14;
    private static final Color GUIDE_COLOR = new Color(200, 200, 200);

    private final boolean checkboxMode;
    private final DefaultTreeCellRenderer fallback = new DefaultTreeCellRenderer();
    private final JPanel panel;
    private final JCheckBox checkBox;
    private final JLabel label;

    // Shared icons built once; fall back to UIManager defaults if creation fails.
    private static final Icon COLLECTION_ICON = safeIcon(BurpLikeTreeCellRenderer::drawCollectionIcon,
            UIManager.getIcon("FileChooser.homeFolderIcon"));
    private static final Icon FOLDER_CLOSED_ICON = safeIcon(BurpLikeTreeCellRenderer::drawFolderClosedIcon,
            UIManager.getIcon("Tree.closedIcon"));
    private static final Icon FOLDER_OPEN_ICON = safeIcon(BurpLikeTreeCellRenderer::drawFolderOpenIcon,
            UIManager.getIcon("Tree.openIcon"));
    private static final Icon REQUEST_ICON = safeIcon(BurpLikeTreeCellRenderer::drawRequestIcon,
            UIManager.getIcon("Tree.leafIcon"));

    public boolean isCheckboxMode() {
        return checkboxMode;
    }

    public BurpLikeTreeCellRenderer(boolean checkboxMode) {
        this.checkboxMode = checkboxMode;
        if (checkboxMode) {
            this.panel = new JPanel(new BorderLayout(2, 0));
            this.panel.setOpaque(false);
            this.checkBox = new JCheckBox();
            this.checkBox.setOpaque(false);
            this.label = new JLabel();
            this.label.setOpaque(false);
            this.panel.add(checkBox, BorderLayout.WEST);
            this.panel.add(label, BorderLayout.CENTER);
        } else {
            this.panel = null;
            this.checkBox = null;
            this.label = null;
        }
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                  boolean expanded, boolean leaf, int row, boolean hasFocus) {
        if (!(value instanceof CollectionTreeNode)) {
            return fallback.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
        }

        CollectionTreeNode node = (CollectionTreeNode) value;
        Icon icon = resolveIcon(node, expanded, leaf);
        String text = value.toString();
        int depth = computeDepth(node);

        if (checkboxMode) {
            checkBox.setSelected(node.isChecked());
            label.setText(text);
            label.setIcon(icon);
            label.setBorder(createHierarchyBorder(depth));
            if (selected) {
                label.setForeground(tree.hasFocus()
                        ? safeColor("Tree.selectionForeground", Color.WHITE)
                        : safeColor("Tree.selectionInactiveForeground", Color.DARK_GRAY));
            } else {
                label.setForeground(safeColor("Tree.textForeground", Color.BLACK));
            }
            return panel;
        }

        Component c = fallback.getTreeCellRendererComponent(tree, text, selected, expanded, leaf, row, hasFocus);
        if (c instanceof JLabel) {
            ((JLabel) c).setIcon(icon);
            ((JLabel) c).setBorder(createHierarchyBorder(depth));
        }
        return c;
    }

    private static int computeDepth(CollectionTreeNode node) {
        return Math.max(0, node.getLevel() - 1);
    }

    private static Border createHierarchyBorder(int depth) {
        int leftPadding = BASE_INDENT + depth * LEVEL_INDENT;
        Border padding = BorderFactory.createEmptyBorder(0, leftPadding, 0, 0);
        if (depth > 0) {
            Border guide = BorderFactory.createMatteBorder(0, 1, 0, 0, GUIDE_COLOR);
            return BorderFactory.createCompoundBorder(guide, padding);
        }
        return padding;
    }

    private static Icon resolveIcon(CollectionTreeNode node, boolean expanded, boolean leaf) {
        switch (node.getNodeType()) {
            case COLLECTION:
                return COLLECTION_ICON;
            case FOLDER:
                return expanded ? FOLDER_OPEN_ICON : FOLDER_CLOSED_ICON;
            case REQUEST:
                return REQUEST_ICON;
            default:
                return leaf ? REQUEST_ICON : FOLDER_CLOSED_ICON;
        }
    }

    // ------------------------------------------------------------------------
    // Safe icon factory
    // ------------------------------------------------------------------------
    private static Color safeColor(String uiKey, Color fallback) {
        Color c = UIManager.getColor(uiKey);
        return c != null ? c : fallback;
    }

    private static Icon safeIcon(IconFactory factory, Icon fallback) {
        try {
            Icon created = factory.create();
            return created != null ? created : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    @FunctionalInterface
    private interface IconFactory {
        Icon create();
    }

    // ------------------------------------------------------------------------
    // Programmatic flat icons (16x16)
    // ------------------------------------------------------------------------
    private static Icon drawCollectionIcon() {
        return new FlatIcon(g -> {
            // Stacked pages to read as a request collection, not a server/database.
            g.setColor(new Color(232, 232, 232));
            g.fillRect(2, 4, 9, 10);
            g.setColor(new Color(170, 170, 170));
            g.drawRect(2, 4, 9, 10);

            g.setColor(new Color(244, 244, 244));
            g.fillRect(5, 2, 9, 10);
            g.setColor(new Color(140, 140, 140));
            g.drawRect(5, 2, 9, 10);

            g.setColor(new Color(190, 190, 190));
            g.drawLine(7, 5, 12, 5);
            g.drawLine(7, 7, 12, 7);
            g.drawLine(7, 9, 11, 9);
        });
    }

    private static Icon drawFolderClosedIcon() {
        return new FlatIcon(g -> {
            g.setColor(new Color(214, 180, 80));
            g.fillRoundRect(1, 5, 14, 10, 2, 2);
            g.setColor(new Color(180, 150, 50));
            g.drawRoundRect(1, 5, 14, 10, 2, 2);
            // tab
            g.setColor(new Color(214, 180, 80));
            g.fillRect(3, 2, 6, 4);
            g.setColor(new Color(180, 150, 50));
            g.drawLine(3, 2, 8, 2);
            g.drawLine(3, 2, 3, 5);
            g.drawLine(8, 2, 8, 5);
        });
    }

    private static Icon drawFolderOpenIcon() {
        return new FlatIcon(g -> {
            g.setColor(new Color(230, 195, 95));
            g.fillRoundRect(1, 5, 14, 10, 2, 2);
            g.setColor(new Color(180, 150, 50));
            g.drawRoundRect(1, 5, 14, 10, 2, 2);
            // tab
            g.setColor(new Color(230, 195, 95));
            g.fillRect(3, 2, 6, 4);
            g.setColor(new Color(180, 150, 50));
            g.drawLine(3, 2, 8, 2);
            g.drawLine(3, 2, 3, 5);
            g.drawLine(8, 2, 8, 5);
            // open accent
            g.setColor(new Color(250, 235, 180));
            g.fillRect(2, 11, 12, 3);
        });
    }

    private static Icon drawRequestIcon() {
        return new FlatIcon(g -> {
            // page
            g.setColor(new Color(250, 250, 250));
            g.fillRect(3, 2, 9, 12);
            g.setColor(new Color(160, 160, 160));
            g.drawRect(3, 2, 9, 12);
            // folded corner
            g.setColor(new Color(220, 220, 220));
            g.fillRect(9, 2, 3, 4);
            g.setColor(new Color(160, 160, 160));
            g.drawLine(9, 2, 9, 5);
            g.drawLine(9, 5, 12, 5);
            g.drawLine(12, 2, 12, 5);
            // lines on page
            g.setColor(new Color(180, 180, 180));
            g.drawLine(5, 7, 10, 7);
            g.drawLine(5, 9, 10, 9);
            g.drawLine(5, 11, 8, 11);
        });
    }

    private static final class FlatIcon implements Icon {
        private final Painter painter;

        FlatIcon(Painter painter) {
            this.painter = painter;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.translate(x, y);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            painter.paint(g2);
            g2.dispose();
        }

        @Override public int getIconWidth() { return 16; }
        @Override public int getIconHeight() { return 16; }
    }

    @FunctionalInterface
    private interface Painter {
        void paint(Graphics2D g);
    }
}
