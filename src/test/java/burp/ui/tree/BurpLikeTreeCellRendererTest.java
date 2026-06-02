package burp.ui.tree;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;

class BurpLikeTreeCellRendererTest {

    @Test
    void nonCheckboxModeUsesFallbackLabelRendering() {
        BurpLikeTreeCellRenderer renderer = new BurpLikeTreeCellRenderer(false);
        assertThat(renderer.isCheckboxMode()).isFalse();

        JTree tree = new JTree();
        CollectionTreeNode node = collectionNode("Test");

        Component c = renderer.getTreeCellRendererComponent(tree, node, false, false, false, 0, false);

        assertThat(c).isInstanceOf(JLabel.class);
        assertThat(((JLabel) c).getIcon()).isNotNull();
    }

    @Test
    void popupModeReturnsPanelWithCheckbox() {
        BurpLikeTreeCellRenderer renderer = new BurpLikeTreeCellRenderer(true);
        assertThat(renderer.isCheckboxMode()).isTrue();

        JTree tree = new JTree();
        CollectionTreeNode node = requestNode("GET", "https://example.test");
        node.setChecked(true);

        Component c = renderer.getTreeCellRendererComponent(tree, node, true, false, true, 0, false);

        assertThat(c).isInstanceOf(JPanel.class);
        assertThat(containsCheckBox(c)).isTrue();
    }

    @Test
    void popupRendererReflectsNodeCheckedState() {
        BurpLikeTreeCellRenderer renderer = new BurpLikeTreeCellRenderer(true);
        JTree tree = new JTree();

        CollectionTreeNode checked = requestNode("GET", "https://example.test");
        checked.setChecked(true);
        JPanel selected = (JPanel) renderer.getTreeCellRendererComponent(tree, checked, true, false, true, 0, false);
        assertThat(checkboxOf(selected).isSelected()).isTrue();

        CollectionTreeNode unchecked = requestNode("POST", "https://example.test");
        unchecked.setChecked(false);
        JPanel unselected = (JPanel) renderer.getTreeCellRendererComponent(tree, unchecked, false, false, true, 0, false);
        assertThat(checkboxOf(unselected).isSelected()).isFalse();
    }

    @Test
    void fallbackRendererUsedForPlainTreeNodes() {
        BurpLikeTreeCellRenderer renderer = new BurpLikeTreeCellRenderer(false);
        JTree tree = new JTree();
        DefaultMutableTreeNode plainNode = new DefaultMutableTreeNode("plain");

        Component c = renderer.getTreeCellRendererComponent(tree, plainNode, false, false, false, 0, false);

        assertThat(c).isNotNull();
    }

    private static JCheckBox checkboxOf(JPanel panel) {
        for (Component child : panel.getComponents()) {
            if (child instanceof JCheckBox) {
                return (JCheckBox) child;
            }
        }
        throw new AssertionError("No checkbox found");
    }

    private static boolean containsCheckBox(Component c) {
        if (c instanceof JCheckBox) {
            return true;
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                if (containsCheckBox(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static CollectionTreeNode collectionNode(String name) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        return new CollectionTreeNode(collection);
    }

    private static CollectionTreeNode requestNode(String method, String url) {
        ApiRequest request = new ApiRequest();
        request.name = "Request";
        request.method = method;
        request.url = url;
        return new CollectionTreeNode(request);
    }
}
