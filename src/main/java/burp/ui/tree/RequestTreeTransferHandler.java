package burp.ui.tree;

import burp.models.ApiCollection;
import burp.models.ApiRequest;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Thin request-tree drag/drop handler that delegates import and move logic.
 */
public final class RequestTreeTransferHandler extends TransferHandler {
    private static final long serialVersionUID = 1L;

    public static final DataFlavor TREE_PAYLOAD_FLAVOR;

    static {
        TREE_PAYLOAD_FLAVOR = new DataFlavor(
                RequestTreeDragPayload.class,
                "API Workbench Request Tree Drag Payload");
    }

    private final JTree tree;
    private final Consumer<List<File>> fileDropImporter;
    private final Predicate<TreeDropRequest> dropValidator;
    private final Function<TreeDropRequest, Boolean> dropHandler;
    private final Consumer<String> logger;

    public RequestTreeTransferHandler(JTree tree,
                                      Consumer<List<File>> fileDropImporter,
                                      Predicate<TreeDropRequest> dropValidator,
                                      Function<TreeDropRequest, Boolean> dropHandler,
                                      Consumer<String> logger) {
        this.tree = tree;
        this.fileDropImporter = fileDropImporter;
        this.dropValidator = dropValidator;
        this.dropHandler = dropHandler;
        this.logger = logger;
    }

    @Override
    public int getSourceActions(JComponent c) {
        return MOVE;
    }

    @Override
    protected Transferable createTransferable(JComponent c) {
        if (!(c instanceof JTree) || tree == null) {
            return null;
        }
        TreePath selection = tree.getSelectionPath();
        if (selection == null) {
            return null;
        }
        Object last = selection.getLastPathComponent();
        if (!(last instanceof CollectionTreeNode)) {
            return null;
        }
        RequestTreeDragPayload payload = payloadForNode(selection, (CollectionTreeNode) last);
        if (payload == null) {
            return null;
        }
        return new PayloadTransferable(payload);
    }

    @Override
    public boolean canImport(TransferSupport support) {
        if (support == null) {
            return false;
        }
        if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return support.isDrop();
        }
        if (!support.isDrop() || !support.isDataFlavorSupported(TREE_PAYLOAD_FLAVOR)) {
            return false;
        }
        TreeDropRequest dropRequest = buildDropRequest(support, extractPayload(support));
        return dropRequest != null && (dropValidator == null || dropValidator.test(dropRequest));
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (support == null) {
            return false;
        }
        if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            List<File> files = extractFiles(support);
            if (files == null || files.isEmpty()) {
                return false;
            }
            if (fileDropImporter != null) {
                fileDropImporter.accept(files);
            }
            log("Dropped " + files.size() + " file(s) onto request tree.");
            return true;
        }
        if (!support.isDrop() || !support.isDataFlavorSupported(TREE_PAYLOAD_FLAVOR)) {
            return false;
        }
        RequestTreeDragPayload payload = extractPayload(support);
        TreeDropRequest dropRequest = buildDropRequest(support, payload);
        if (dropRequest == null || dropHandler == null) {
            return false;
        }
        if (dropValidator != null && !dropValidator.test(dropRequest)) {
            return false;
        }
        boolean accepted = Boolean.TRUE.equals(dropHandler.apply(dropRequest));
        if (accepted) {
            log("Moved " + payload.type + " via request-tree drag and drop.");
        }
        return accepted;
    }

    private TreeDropRequest buildDropRequest(TransferSupport support, RequestTreeDragPayload payload) {
        if (support == null || payload == null || tree == null) {
            return null;
        }
        Object locationObject = support.getDropLocation();
        if (!(locationObject instanceof JTree.DropLocation)) {
            return null;
        }
        JTree.DropLocation dropLocation = (JTree.DropLocation) locationObject;
        TreePath dropPath = dropLocation.getPath();
        if (dropPath == null) {
            return null;
        }
        Object last = dropPath.getLastPathComponent();
        if (!(last instanceof CollectionTreeNode)) {
            return null;
        }

        CollectionTreeNode targetNode = (CollectionTreeNode) last;
        int childIndex = dropLocation.getChildIndex();
        CollectionTreeNode parentNode = childIndex >= 0
                ? targetNode
                : targetParentNode(targetNode);

        TreeDropRequest.DropPosition position = childIndex >= 0
                ? TreeDropRequest.DropPosition.INSERT_BEFORE
                : onPositionForTarget(payload, targetNode);

        ApiCollection targetCollection = collectionForNode(targetNode);
        if (targetCollection == null) {
            targetCollection = collectionForNode(parentNode);
        }

        String targetFolderPath = "";
        int targetIndex = -1;

        if (childIndex >= 0) {
            targetFolderPath = folderPathForParentNode(parentNode);
            targetIndex = sameTypeSiblingIndex(parentNode, childIndex, payload.type);
            if (targetIndex < 0) {
                targetIndex = -1;
            }
        } else if (payload.type == RequestTreeDragPayload.NodeType.COLLECTION) {
            targetIndex = collectionSiblingIndex(targetNode);
            targetFolderPath = "";
        } else if (payload.type == RequestTreeDragPayload.NodeType.FOLDER) {
            if (targetNode.getNodeType() == CollectionTreeNode.Type.FOLDER) {
                targetFolderPath = targetNode.folderPath != null ? targetNode.folderPath : "";
                targetIndex = -1;
            } else if (targetNode.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                targetFolderPath = "";
                targetIndex = -1;
            } else {
                targetFolderPath = folderPathForParentNode(targetNode);
                targetIndex = -1;
            }
        } else if (payload.type == RequestTreeDragPayload.NodeType.REQUEST) {
            if (targetNode.getNodeType() == CollectionTreeNode.Type.REQUEST) {
                targetFolderPath = folderPathForParentNode(targetNode);
                targetIndex = -1;
            } else if (targetNode.getNodeType() == CollectionTreeNode.Type.FOLDER) {
                targetFolderPath = targetNode.folderPath != null ? targetNode.folderPath : "";
                targetIndex = -1;
            } else {
                targetFolderPath = "";
                targetIndex = -1;
            }
        }

        if (payload.type == RequestTreeDragPayload.NodeType.COLLECTION) {
            targetCollection = null;
            targetFolderPath = "";
        }

        return new TreeDropRequest(payload, targetCollection, targetNode, targetFolderPath, targetIndex, position);
    }

    private static RequestTreeDragPayload payloadForNode(TreePath path, CollectionTreeNode node) {
        if (node == null) {
            return null;
        }
        ApiCollection collection = collectionForNodePath(path);
        if (collection == null) {
            return null;
        }
        switch (node.getNodeType()) {
            case COLLECTION:
                return RequestTreeDragPayload.forCollection(collection);
            case FOLDER:
                return RequestTreeDragPayload.forFolder(collection, node.folderPath);
            case REQUEST:
                return RequestTreeDragPayload.forRequest(collection, node.request);
            default:
                return null;
        }
    }

    private static ApiCollection collectionForNodePath(TreePath path) {
        if (path == null) {
            return null;
        }
        for (Object component : path.getPath()) {
            if (component instanceof CollectionTreeNode) {
                CollectionTreeNode node = (CollectionTreeNode) component;
                if (node.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
                    return node.collection;
                }
            }
        }
        return null;
    }

    private static ApiCollection collectionForNode(CollectionTreeNode node) {
        if (node == null) {
            return null;
        }
        if (node.getNodeType() == CollectionTreeNode.Type.COLLECTION) {
            return node.collection;
        }
        TreePath path = new TreePath(node.getPath());
        return collectionForNodePath(path);
    }

    private static CollectionTreeNode targetParentNode(CollectionTreeNode targetNode) {
        if (targetNode == null) {
            return null;
        }
        Object parent = targetNode.getParent();
        return parent instanceof CollectionTreeNode ? (CollectionTreeNode) parent : null;
    }

    private static String folderPathForParentNode(CollectionTreeNode parentNode) {
        if (parentNode == null) {
            return "";
        }
        if (parentNode.getNodeType() == CollectionTreeNode.Type.FOLDER) {
            return parentNode.folderPath != null ? parentNode.folderPath : "";
        }
        return "";
    }

    private static int collectionSiblingIndex(CollectionTreeNode node) {
        if (node == null) {
            return -1;
        }
        Object parent = node.getParent();
        if (!(parent instanceof DefaultMutableTreeNode)) {
            return -1;
        }
        DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) parent;
        int index = 0;
        for (int i = 0; i < parentNode.getChildCount(); i++) {
            Object child = parentNode.getChildAt(i);
            if (!(child instanceof CollectionTreeNode)) {
                continue;
            }
            CollectionTreeNode ctn = (CollectionTreeNode) child;
            if (ctn.getNodeType() != CollectionTreeNode.Type.COLLECTION) {
                continue;
            }
            if (ctn == node) {
                return index;
            }
            index++;
        }
        return index;
    }

    private static int sameTypeSiblingIndex(CollectionTreeNode parentNode, int childIndex, RequestTreeDragPayload.NodeType payloadType) {
        if (parentNode == null || childIndex < 0) {
            return -1;
        }
        int index = 0;
        for (int i = 0; i < Math.min(childIndex, parentNode.getChildCount()); i++) {
            Object child = parentNode.getChildAt(i);
            if (child instanceof CollectionTreeNode) {
                CollectionTreeNode ctn = (CollectionTreeNode) child;
                if (matchesPayloadType(ctn, payloadType)) {
                    index++;
                }
            }
        }
        return index;
    }

    private static boolean matchesPayloadType(CollectionTreeNode node, RequestTreeDragPayload.NodeType type) {
        if (node == null || type == null) {
            return false;
        }
        switch (type) {
            case COLLECTION:
                return node.getNodeType() == CollectionTreeNode.Type.COLLECTION;
            case FOLDER:
                return node.getNodeType() == CollectionTreeNode.Type.FOLDER;
            case REQUEST:
                return node.getNodeType() == CollectionTreeNode.Type.REQUEST;
            default:
                return false;
        }
    }

    private TreeDropRequest.DropPosition onPositionForTarget(RequestTreeDragPayload payload, CollectionTreeNode targetNode) {
        if (payload == null || targetNode == null) {
            return TreeDropRequest.DropPosition.ROOT_INSERT;
        }
        if (payload.type == RequestTreeDragPayload.NodeType.COLLECTION) {
            return TreeDropRequest.DropPosition.ON_COLLECTION;
        }
        if (payload.type == RequestTreeDragPayload.NodeType.FOLDER) {
            return targetNode.getNodeType() == CollectionTreeNode.Type.FOLDER
                    ? TreeDropRequest.DropPosition.ON_FOLDER
                    : TreeDropRequest.DropPosition.ON_COLLECTION;
        }
        if (payload.type == RequestTreeDragPayload.NodeType.REQUEST) {
            return targetNode.getNodeType() == CollectionTreeNode.Type.REQUEST
                    ? TreeDropRequest.DropPosition.ON_REQUEST
                    : (targetNode.getNodeType() == CollectionTreeNode.Type.FOLDER
                    ? TreeDropRequest.DropPosition.ON_FOLDER
                    : TreeDropRequest.DropPosition.ON_COLLECTION);
        }
        return TreeDropRequest.DropPosition.ROOT_INSERT;
    }

    private List<File> extractFiles(TransferSupport support) {
        try {
            Object data = support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
            if (!(data instanceof List)) {
                return List.of();
            }
            List<?> raw = (List<?>) data;
            List<File> files = new ArrayList<>();
            for (Object entry : raw) {
                if (entry instanceof File) {
                    files.add((File) entry);
                }
            }
            return files;
        } catch (UnsupportedFlavorException | IOException e) {
            log("File drop failed: " + e.getMessage());
            return List.of();
        }
    }

    private RequestTreeDragPayload extractPayload(TransferSupport support) {
        try {
            Object data = support.getTransferable().getTransferData(TREE_PAYLOAD_FLAVOR);
            return data instanceof RequestTreeDragPayload ? (RequestTreeDragPayload) data : null;
        } catch (UnsupportedFlavorException | IOException e) {
            log("Tree drag payload failed: " + e.getMessage());
            return null;
        }
    }

    private void log(String message) {
        if (logger != null && message != null && !message.isBlank()) {
            logger.accept(message);
        }
    }

    private static final class PayloadTransferable implements Transferable {
        private final RequestTreeDragPayload payload;

        private PayloadTransferable(RequestTreeDragPayload payload) {
            this.payload = payload;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{TREE_PAYLOAD_FLAVOR};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return TREE_PAYLOAD_FLAVOR.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return payload;
        }
    }
}
