package burp.ui.tree;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;

class MainRequestTreeCellRendererTest {

    @Test
    void mainRendererUsesPanelWithoutCheckbox() {
        MainRequestTreeCellRenderer renderer = new MainRequestTreeCellRenderer();
        JTree tree = buildDeepTree();

        CollectionTreeNode folder = (CollectionTreeNode) ((CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0)).getChildAt(0);
        Component c = renderer.getTreeCellRendererComponent(tree, folder, false, false, false, 1, false);

        assertThat(c).isInstanceOf(JPanel.class);
        assertThat(containsCheckBox(c)).isFalse();
    }

    @Test
    void mainRendererAppliesDepthSpacerByNodeLevel() {
        MainRequestTreeCellRenderer renderer = new MainRequestTreeCellRenderer();
        JTree tree = buildDeepTree();

        CollectionTreeNode collection = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);
        CollectionTreeNode folder = (CollectionTreeNode) collection.getChildAt(0);
        CollectionTreeNode subfolder = (CollectionTreeNode) folder.getChildAt(0);
        CollectionTreeNode request = (CollectionTreeNode) subfolder.getChildAt(0);

        int collectionSpacer = spacerWidth(renderer.getTreeCellRendererComponent(tree, collection, false, false, false, 0, false));
        int folderSpacer = spacerWidth(renderer.getTreeCellRendererComponent(tree, folder, false, false, false, 1, false));
        int subfolderSpacer = spacerWidth(renderer.getTreeCellRendererComponent(tree, subfolder, false, false, false, 2, false));
        int requestSpacer = spacerWidth(renderer.getTreeCellRendererComponent(tree, request, false, false, true, 3, false));

        assertThat(folderSpacer).isGreaterThan(collectionSpacer);
        assertThat(subfolderSpacer).isGreaterThan(folderSpacer);
        assertThat(requestSpacer).isGreaterThanOrEqualTo(subfolderSpacer);
    }

    @Test
    void mainRendererResetsSpacerWidthPerRow() {
        MainRequestTreeCellRenderer renderer = new MainRequestTreeCellRenderer();
        JTree tree = buildDeepTree();

        CollectionTreeNode collection = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);
        CollectionTreeNode folder = (CollectionTreeNode) collection.getChildAt(0);
        CollectionTreeNode request = (CollectionTreeNode) ((CollectionTreeNode) folder.getChildAt(0)).getChildAt(0);

        int deepWidth = spacerWidth(renderer.getTreeCellRendererComponent(tree, request, false, false, true, 3, false));
        int shallowWidth = spacerWidth(renderer.getTreeCellRendererComponent(tree, collection, false, false, false, 0, false));

        assertThat(deepWidth).isGreaterThan(shallowWidth);
        assertThat(shallowWidth).isZero();
    }

    @Test
    void mainRendererPreservesSelectionVisibility() {
        MainRequestTreeCellRenderer renderer = new MainRequestTreeCellRenderer();
        JTree tree = buildDeepTree();
        CollectionTreeNode collection = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);

        JPanel selected = (JPanel) renderer.getTreeCellRendererComponent(tree, collection, true, false, false, 0, true);
        JLabel selectedLabel = (JLabel) selected.getComponent(1);

        assertThat(selected.isOpaque()).isTrue();
        assertThat(selected.getBackground()).isNotNull();
        assertThat(selectedLabel.getForeground()).isNotNull();

        JPanel unselected = (JPanel) renderer.getTreeCellRendererComponent(tree, collection, false, false, false, 0, false);
        JLabel unselectedLabel = (JLabel) unselected.getComponent(1);

        assertThat(unselected.isOpaque()).isFalse();
        assertThat(unselectedLabel.getForeground()).isNotNull();
    }

    private static int spacerWidth(Component c) {
        assertThat(c).isInstanceOf(JPanel.class);
        JPanel panel = (JPanel) c;
        Component spacer = panel.getComponent(0);
        return spacer.getPreferredSize().width;
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
