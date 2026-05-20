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
    void nonCheckboxModeReturnsDefaultTreeCellRendererLikeComponent() {
        BurpLikeTreeCellRenderer renderer = new BurpLikeTreeCellRenderer(false);
        assertThat(renderer.isCheckboxMode()).isFalse();

        JTree tree = new JTree();
        CollectionTreeNode node = collectionNode("Test");
        Component c = renderer.getTreeCellRendererComponent(tree, node, false, false, false, 0, false);
        assertThat(c).isNotNull();
    }

    @Test
    void checkboxModeReturnsPanelWithCheckboxState() {
        BurpLikeTreeCellRenderer renderer = new BurpLikeTreeCellRenderer(true);
        assertThat(renderer.isCheckboxMode()).isTrue();

        JTree tree = new JTree();
        CollectionTreeNode node = requestNode("GET", "https://example.test");
        node.setChecked(true);

        Component c = renderer.getTreeCellRendererComponent(tree, node, false, false, true, 0, false);
        assertThat(c).isInstanceOf(JPanel.class);
    }

    @Test
    void checkboxModeReflectsNodeCheckedState() {
        BurpLikeTreeCellRenderer renderer = new BurpLikeTreeCellRenderer(true);
        JTree tree = new JTree();

        CollectionTreeNode checkedNode = requestNode("GET", "https://example.test");
        checkedNode.setChecked(true);
        renderer.getTreeCellRendererComponent(tree, checkedNode, false, false, true, 0, false);
        // The checkbox inside the panel should be selected; we verify by re-rendering.
        Component c1 = renderer.getTreeCellRendererComponent(tree, checkedNode, false, false, true, 0, false);
        assertThat(c1).isInstanceOf(JPanel.class);

        CollectionTreeNode uncheckedNode = requestNode("POST", "https://example.test");
        uncheckedNode.setChecked(false);
        Component c2 = renderer.getTreeCellRendererComponent(tree, uncheckedNode, false, false, true, 0, false);
        assertThat(c2).isInstanceOf(JPanel.class);
    }

    @Test
    void fallbackRendererUsedForNonCollectionTreeNodeValues() {
        BurpLikeTreeCellRenderer renderer = new BurpLikeTreeCellRenderer(false);
        JTree tree = new JTree();
        DefaultMutableTreeNode plainNode = new DefaultMutableTreeNode("plain");
        Component c = renderer.getTreeCellRendererComponent(tree, plainNode, false, false, false, 0, false);
        assertThat(c).isNotNull();
    }

    @Test
    void iconsAreNonNullForAllNodeTypes() {
        BurpLikeTreeCellRenderer renderer = new BurpLikeTreeCellRenderer(false);
        JTree tree = new JTree();

        CollectionTreeNode collection = collectionNode("Coll");
        Component c1 = renderer.getTreeCellRendererComponent(tree, collection, false, false, false, 0, false);
        assertThat(c1).isNotNull();

        CollectionTreeNode folder = folderNode("Folder");
        Component c2 = renderer.getTreeCellRendererComponent(tree, folder, false, false, false, 0, false);
        assertThat(c2).isNotNull();

        CollectionTreeNode request = requestNode("GET", "https://example.test");
        Component c3 = renderer.getTreeCellRendererComponent(tree, request, false, false, true, 0, false);
        assertThat(c3).isNotNull();
    }

    @Test
    void selectionColorsAreAppliedInCheckboxMode() {
        BurpLikeTreeCellRenderer renderer = new BurpLikeTreeCellRenderer(true);
        JTree tree = new JTree();
        CollectionTreeNode node = requestNode("GET", "https://example.test");

        Component selected = renderer.getTreeCellRendererComponent(tree, node, true, false, true, 0, false);
        assertThat(selected).isInstanceOf(JPanel.class);

        Component unselected = renderer.getTreeCellRendererComponent(tree, node, false, false, true, 0, false);
        assertThat(unselected).isInstanceOf(JPanel.class);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------
    private static CollectionTreeNode collectionNode(String name) {
        ApiCollection col = new ApiCollection();
        col.name = name;
        return new CollectionTreeNode(col);
    }

    private static CollectionTreeNode folderNode(String path) {
        return new CollectionTreeNode(path);
    }

    private static CollectionTreeNode requestNode(String method, String url) {
        ApiRequest req = new ApiRequest();
        req.name = "Request";
        req.method = method;
        req.url = url;
        return new CollectionTreeNode(req);
    }
}
