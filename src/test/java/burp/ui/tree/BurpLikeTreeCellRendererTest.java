package burp.ui.tree;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
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
    // Hierarchy depth tests
    // ------------------------------------------------------------------------

    @Test
    void nonCheckboxModeAppliesDepthAwareLeftPadding() {
        BurpLikeTreeCellRenderer renderer = new BurpLikeTreeCellRenderer(false);
        JTree tree = buildNestedTree();

        CollectionTreeNode collection = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);
        CollectionTreeNode folder = (CollectionTreeNode) collection.getChildAt(0);
        CollectionTreeNode request = (CollectionTreeNode) folder.getChildAt(0);

        int insetCollection = leftInsetOf(renderer.getTreeCellRendererComponent(tree, collection, false, false, false, 0, false));
        int insetFolder = leftInsetOf(renderer.getTreeCellRendererComponent(tree, folder, false, false, false, 1, false));
        int insetRequest = leftInsetOf(renderer.getTreeCellRendererComponent(tree, request, false, false, true, 2, false));

        assertThat(insetFolder).isGreaterThan(insetCollection);
        assertThat(insetRequest).isGreaterThan(insetFolder);
    }

    @Test
    void checkboxModeAppliesDepthAwareLeftPadding() {
        BurpLikeTreeCellRenderer renderer = new BurpLikeTreeCellRenderer(true);
        JTree tree = buildNestedTree();

        CollectionTreeNode collection = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);
        CollectionTreeNode folder = (CollectionTreeNode) collection.getChildAt(0);
        CollectionTreeNode request = (CollectionTreeNode) folder.getChildAt(0);

        int insetCollection = leftInsetOf(renderer.getTreeCellRendererComponent(tree, collection, false, false, false, 0, false));
        int insetFolder = leftInsetOf(renderer.getTreeCellRendererComponent(tree, folder, false, false, false, 1, false));
        int insetRequest = leftInsetOf(renderer.getTreeCellRendererComponent(tree, request, false, false, true, 2, false));

        assertThat(insetFolder).isGreaterThan(insetCollection);
        assertThat(insetRequest).isGreaterThan(insetFolder);
    }

    @Test
    void nestedNodesExposeApplicationOwnedGuideCue() {
        BurpLikeTreeCellRenderer renderer = new BurpLikeTreeCellRenderer(false);
        JTree tree = buildNestedTree();

        CollectionTreeNode collection = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);
        CollectionTreeNode folder = (CollectionTreeNode) collection.getChildAt(0);

        assertThat(hasGuideCue(renderer.getTreeCellRendererComponent(tree, collection, false, false, false, 0, false))).isFalse();
        assertThat(hasGuideCue(renderer.getTreeCellRendererComponent(tree, folder, false, false, false, 1, false))).isTrue();
    }

    @Test
    void rendererOutputDiffersInDepthAwareWayForCollectionFolderSubfolderRequest() {
        BurpLikeTreeCellRenderer renderer = new BurpLikeTreeCellRenderer(false);
        JTree tree = buildDeepTree();

        CollectionTreeNode collection = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);
        CollectionTreeNode folder = (CollectionTreeNode) collection.getChildAt(0);
        CollectionTreeNode subfolder = (CollectionTreeNode) folder.getChildAt(0);
        CollectionTreeNode request = (CollectionTreeNode) subfolder.getChildAt(0);

        int insetCollection = leftInsetOf(renderer.getTreeCellRendererComponent(tree, collection, false, false, false, 0, false));
        int insetFolder = leftInsetOf(renderer.getTreeCellRendererComponent(tree, folder, false, false, false, 1, false));
        int insetSubfolder = leftInsetOf(renderer.getTreeCellRendererComponent(tree, subfolder, false, false, false, 2, false));
        int insetRequest = leftInsetOf(renderer.getTreeCellRendererComponent(tree, request, false, false, true, 3, false));

        assertThat(insetCollection).isLessThan(insetFolder);
        assertThat(insetFolder).isLessThan(insetSubfolder);
        assertThat(insetSubfolder).isLessThan(insetRequest);

        assertThat(hasGuideCue(renderer.getTreeCellRendererComponent(tree, collection, false, false, false, 0, false))).isFalse();
        assertThat(hasGuideCue(renderer.getTreeCellRendererComponent(tree, folder, false, false, false, 1, false))).isTrue();
        assertThat(hasGuideCue(renderer.getTreeCellRendererComponent(tree, subfolder, false, false, false, 2, false))).isTrue();
        assertThat(hasGuideCue(renderer.getTreeCellRendererComponent(tree, request, false, false, true, 3, false))).isTrue();
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

    private static JTree buildNestedTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        CollectionTreeNode collection = collectionNode("Coll");
        CollectionTreeNode folder = folderNode("Folder");
        CollectionTreeNode request = requestNode("GET", "https://example.test");
        root.add(collection);
        collection.add(folder);
        folder.add(request);
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(false);
        return tree;
    }

    private static JTree buildDeepTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        CollectionTreeNode collection = collectionNode("Coll");
        CollectionTreeNode folder = folderNode("Folder");
        CollectionTreeNode subfolder = folderNode("Subfolder");
        CollectionTreeNode request = requestNode("GET", "https://example.test");
        root.add(collection);
        collection.add(folder);
        folder.add(subfolder);
        subfolder.add(request);
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(false);
        return tree;
    }

    private static int leftInsetOf(Component c) {
        Border border = extractBorder(c);
        if (border == null) return 0;
        return border.getBorderInsets(c).left;
    }

    private static boolean hasGuideCue(Component c) {
        Border border = extractBorder(c);
        return border != null && containsMatteBorder(border);
    }

    private static Border extractBorder(Component c) {
        if (!(c instanceof JComponent)) return null;
        if (c instanceof JPanel) {
            // In checkbox mode the border is on the nested JLabel, not the panel
            for (Component child : ((JPanel) c).getComponents()) {
                if (child instanceof JLabel && ((JLabel) child).getBorder() != null) {
                    return ((JLabel) child).getBorder();
                }
            }
            return ((JComponent) c).getBorder();
        }
        return ((JComponent) c).getBorder();
    }

    private static boolean containsMatteBorder(Border border) {
        if (border instanceof MatteBorder) return true;
        if (border instanceof CompoundBorder) {
            CompoundBorder cb = (CompoundBorder) border;
            return containsMatteBorder(cb.getOutsideBorder()) || containsMatteBorder(cb.getInsideBorder());
        }
        return false;
    }
}
