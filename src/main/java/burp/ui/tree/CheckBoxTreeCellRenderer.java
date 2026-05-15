package burp.ui.tree;

import javax.swing.*;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.*;

/**
 * Renders tree nodes with a checkbox for selection.
 */
public class CheckBoxTreeCellRenderer extends DefaultTreeCellRenderer {
    private final JCheckBox checkBox = new JCheckBox();
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JLabel label = new JLabel();

    public CheckBoxTreeCellRenderer() {
        panel.setOpaque(false);
        checkBox.setOpaque(false);
        label.setOpaque(false);
        panel.add(checkBox, BorderLayout.WEST);
        panel.add(label, BorderLayout.CENTER);
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                  boolean expanded, boolean leaf, int row, boolean hasFocus) {
        Component defaultComp = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
        if (value instanceof CollectionTreeNode) {
            CollectionTreeNode node = (CollectionTreeNode) value;
            checkBox.setSelected(node.isChecked());
            label.setText(value.toString());
            label.setIcon(getIconForNode(node, expanded, leaf));
            if (sel) {
                label.setForeground(getTextSelectionColor());
                label.setBackground(getBackgroundSelectionColor());
            } else {
                label.setForeground(getTextNonSelectionColor());
                label.setBackground(getBackgroundNonSelectionColor());
            }
            return panel;
        }
        return defaultComp;
    }

    private Icon getIconForNode(CollectionTreeNode node, boolean expanded, boolean leaf) {
        switch (node.getNodeType()) {
            case COLLECTION: return expanded ? openIcon : closedIcon;
            case FOLDER: return expanded ? openIcon : closedIcon;
            case REQUEST: return leafIcon;
            default: return closedIcon;
        }
    }
}
