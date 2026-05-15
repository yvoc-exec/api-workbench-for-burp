package burp.ui.tree;

import burp.models.ApiCollection;
import burp.models.ApiRequest;

import javax.swing.tree.DefaultMutableTreeNode;

/**
 * Tree node for the collection/folder/request checkbox tree.
 */
public class CollectionTreeNode extends DefaultMutableTreeNode {
    public enum Type { COLLECTION, FOLDER, REQUEST }

    private final Type type;
    private boolean checked = false;

    // For COLLECTION
    public ApiCollection collection;

    // For REQUEST
    public ApiRequest request;

    // For FOLDER
    public String folderPath;

    public CollectionTreeNode(ApiCollection collection) {
        super(collection.name, true);
        this.type = Type.COLLECTION;
        this.collection = collection;
    }

    public CollectionTreeNode(String folderPath) {
        super(folderPath.substring(folderPath.lastIndexOf('/') + 1), true);
        this.type = Type.FOLDER;
        this.folderPath = folderPath;
    }

    public CollectionTreeNode(ApiRequest request) {
        super(request.name, false);
        this.type = Type.REQUEST;
        this.request = request;
    }

    public Type getNodeType() { return type; }
    public boolean isChecked() { return checked; }
    public void setChecked(boolean checked) { this.checked = checked; }

    public void propagateCheck(boolean checked) {
        this.checked = checked;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof CollectionTreeNode) {
                ((CollectionTreeNode) getChildAt(i)).propagateCheck(checked);
            }
        }
    }

    public boolean updateParentCheckState() {
        if (getChildCount() == 0) return checked;
        boolean allChecked = true;
        boolean anyChecked = false;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof CollectionTreeNode) {
                boolean childChecked = ((CollectionTreeNode) getChildAt(i)).updateParentCheckState();
                if (childChecked) anyChecked = true;
                else allChecked = false;
            }
        }
        if (allChecked) checked = true;
        else if (!anyChecked) checked = false;
        // partial: keep current (visually handled by renderer if needed)
        return checked;
    }
}
