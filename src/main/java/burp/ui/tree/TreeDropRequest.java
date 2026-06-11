package burp.ui.tree;

import burp.models.ApiCollection;

/**
 * Normalized request-tree drop target used by the importer UI.
 */
public final class TreeDropRequest {
    public enum DropPosition {
        ON_COLLECTION,
        ON_FOLDER,
        ON_REQUEST,
        INSERT_BEFORE,
        INSERT_AFTER,
        ROOT_INSERT
    }

    public final RequestTreeDragPayload payload;
    public final ApiCollection targetCollection;
    public final CollectionTreeNode targetNode;
    public final String targetFolderPath;
    public final int targetIndex;
    public final DropPosition position;

    public TreeDropRequest(RequestTreeDragPayload payload,
                           ApiCollection targetCollection,
                           CollectionTreeNode targetNode,
                           String targetFolderPath,
                           int targetIndex,
                           DropPosition position) {
        this.payload = payload;
        this.targetCollection = targetCollection;
        this.targetNode = targetNode;
        this.targetFolderPath = RequestTreePathService.normalizeFolderPath(targetFolderPath);
        this.targetIndex = targetIndex;
        this.position = position;
    }

    public boolean isInsert() {
        return position == DropPosition.INSERT_BEFORE
                || position == DropPosition.INSERT_AFTER
                || position == DropPosition.ROOT_INSERT;
    }

    public boolean isOnTarget() {
        return position == DropPosition.ON_COLLECTION
                || position == DropPosition.ON_FOLDER
                || position == DropPosition.ON_REQUEST;
    }
}
