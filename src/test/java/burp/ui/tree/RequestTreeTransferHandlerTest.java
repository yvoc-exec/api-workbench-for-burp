package burp.ui.tree;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.testsupport.TransferSupportTestUtils;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTreeTransferHandlerTest {

    @Test
    void sourceActionsAreMoveAndSelectionCreatesExpectedPayloads() {
        CollectionTreeNode collectionNode = new CollectionTreeNode(collection("APIM"));
        CollectionTreeNode folderNode = new CollectionTreeNode("Auth");
        CollectionTreeNode requestNode = new CollectionTreeNode(request("Login"));
        collectionNode.add(folderNode);
        folderNode.add(requestNode);

        JTree tree = new JTree(collectionNode);
        List<String> logs = new ArrayList<>();
        RequestTreeTransferHandler handler = new RequestTreeTransferHandler(
                tree,
                files -> {
                },
                requestDrop -> true,
                dropRequest -> true,
                logs::add);

        assertThat(handler.getSourceActions(new JPanel())).isEqualTo(TransferHandler.MOVE);
        tree.clearSelection();
        assertThat(handler.createTransferable(tree)).isNull();

        tree.setSelectionPath(new TreePath(collectionNode.getPath()));
        Transferable collectionPayload = handler.createTransferable(tree);
        assertThat(collectionPayload).isNotNull();
        assertThat(collectionPayload.isDataFlavorSupported(RequestTreeTransferHandler.TREE_PAYLOAD_FLAVOR)).isTrue();

        RequestTreeDragPayload payload = payloadFrom(collectionPayload);
        assertThat(payload.type).isEqualTo(RequestTreeDragPayload.NodeType.COLLECTION);
        assertThat(payload.collection.name).isEqualTo("APIM");

        tree.setSelectionPath(new TreePath(folderNode.getPath()));
        Transferable folderPayload = handler.createTransferable(tree);
        assertThat(payloadFrom(folderPayload).type).isEqualTo(RequestTreeDragPayload.NodeType.FOLDER);

        tree.setSelectionPath(new TreePath(requestNode.getPath()));
        Transferable requestPayload = handler.createTransferable(tree);
        assertThat(payloadFrom(requestPayload).type).isEqualTo(RequestTreeDragPayload.NodeType.REQUEST);
    }

    @Test
    void fileAndTreeDropsDelegateThroughPublicApis() {
        ApiCollection collection = collection("APIM");
        CollectionTreeNode collectionNode = new CollectionTreeNode(collection);
        CollectionTreeNode folderNode = new CollectionTreeNode("Auth");
        collectionNode.add(folderNode);
        JTree tree = new JTree(collectionNode);
        tree.setSelectionPath(new TreePath(collectionNode.getPath()));

        AtomicReference<List<File>> importedFiles = new AtomicReference<>();
        List<String> logs = new ArrayList<>();
        AtomicReference<TreeDropRequest> acceptedDrop = new AtomicReference<>();
        RequestTreeTransferHandler handler = new RequestTreeTransferHandler(
                tree,
                importedFiles::set,
                dropRequest -> dropRequest != null && dropRequest.payload != null,
                dropRequest -> {
                    acceptedDrop.set(dropRequest);
                    return true;
                },
                logs::add);

        TransferHandler.TransferSupport fileSupport = TransferSupportTestUtils.dropSupport(new FileListTransferable(List.of(new File("one.json"), new File("two.json"))));
        assertThat(handler.canImport(fileSupport)).isTrue();
        assertThat(handler.importData(fileSupport)).isTrue();
        assertThat(importedFiles.get()).hasSize(2);
        assertThat(logs).anyMatch(message -> message.contains("Dropped 2 file(s) onto request tree."));

        RequestTreeDragPayload payload = RequestTreeDragPayload.forFolder(collection, "Auth");
        Transferable transferable = new PayloadTransferable(payload);
        TreePath dropPath = new TreePath(new Object[]{collectionNode, folderNode});
        Object dropLocation = TransferSupportTestUtils.treeDropLocation(dropPath, -1);
        TransferHandler.TransferSupport dropSupport = TransferSupportTestUtils.dropSupport(transferable, dropLocation);

        assertThat(handler.canImport(dropSupport)).isTrue();
        assertThat(handler.importData(dropSupport)).isTrue();
        assertThat(acceptedDrop.get()).isNotNull();
        assertThat(acceptedDrop.get().position).isEqualTo(TreeDropRequest.DropPosition.ON_FOLDER);
        assertThat(logs).anyMatch(message -> message.contains("Moved FOLDER via request-tree drag and drop."));

        TransferHandler.TransferSupport unsupported = TransferSupportTestUtils.dropSupport(new StringSelection("nope"), dropLocation);
        assertThat(handler.canImport(unsupported)).isFalse();
        assertThat(handler.importData(unsupported)).isFalse();
    }

    @Test
    void buildDropRequestNormalizesCollectionFolderAndRequestInsertPositions() throws Exception {
        ApiCollection collection = collection("APIM");
        CollectionTreeNode collectionNode = new CollectionTreeNode(collection);
        CollectionTreeNode folderNode = new CollectionTreeNode("Auth");
        CollectionTreeNode requestNode = new CollectionTreeNode(request("Login"));
        collectionNode.add(folderNode);
        folderNode.add(requestNode);
        JTree tree = new JTree(collectionNode);

        RequestTreeTransferHandler handler = new RequestTreeTransferHandler(tree, files -> {
        }, dropRequest -> true, dropRequest -> true, message -> {
        });

        RequestTreeDragPayload collectionPayload = RequestTreeDragPayload.forCollection(collection);
        RequestTreeDragPayload folderPayload = RequestTreeDragPayload.forFolder(collection, "Auth");
        RequestTreeDragPayload requestPayload = RequestTreeDragPayload.forRequest(collection, request("Login"));

        TreeDropRequest rootInsert = invokeBuildDropRequest(handler, collectionPayload, collectionNode, -1);
        assertThat(rootInsert.position).isEqualTo(TreeDropRequest.DropPosition.ON_COLLECTION);
        assertThat(rootInsert.targetCollection).isNull();

        TreeDropRequest folderOnFolder = invokeBuildDropRequest(handler, folderPayload, folderNode, -1);
        assertThat(folderOnFolder.position).isEqualTo(TreeDropRequest.DropPosition.ON_FOLDER);
        assertThat(folderOnFolder.targetFolderPath).isEqualTo("Auth");

        TreeDropRequest requestOnRequest = invokeBuildDropRequest(handler, requestPayload, requestNode, -1);
        assertThat(requestOnRequest.position).isEqualTo(TreeDropRequest.DropPosition.ON_REQUEST);

        TreeDropRequest insertBefore = invokeBuildDropRequest(handler, collectionPayload, collectionNode, 0);
        assertThat(insertBefore.targetIndex).isEqualTo(0);
        assertThat(insertBefore.targetFolderPath).isEmpty();
    }

    private static TreeDropRequest invokeBuildDropRequest(RequestTreeTransferHandler handler,
                                                          RequestTreeDragPayload payload,
                                                          CollectionTreeNode targetNode,
                                                          int childIndex) throws Exception {
        Method method = RequestTreeTransferHandler.class.getDeclaredMethod("buildDropRequest", TransferHandler.TransferSupport.class, RequestTreeDragPayload.class);
        method.setAccessible(true);
        TreePath path = new TreePath(targetNode.getPath());
        Object dropLocation = TransferSupportTestUtils.treeDropLocation(path, childIndex);
        TransferHandler.TransferSupport support = TransferSupportTestUtils.dropSupport(new PayloadTransferable(payload), dropLocation);
        return (TreeDropRequest) method.invoke(handler, support, payload);
    }

    private static RequestTreeDragPayload payloadFrom(Transferable transferable) {
        try {
            return (RequestTreeDragPayload) transferable.getTransferData(RequestTreeTransferHandler.TREE_PAYLOAD_FLAVOR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JTree treeWithSelection(Object selection) {
        CollectionTreeNode root = new CollectionTreeNode(collection("APIM"));
        JTree tree = new JTree(root);
        if (selection instanceof CollectionTreeNode) {
            tree.setSelectionPath(new TreePath(((CollectionTreeNode) selection).getPath()));
        } else if (selection instanceof TreePath) {
            tree.setSelectionPath((TreePath) selection);
        } else {
            tree.clearSelection();
        }
        return tree;
    }

    private static CollectionTreeNode collectionNode(String name) {
        return new CollectionTreeNode(collection(name));
    }

    private static CollectionTreeNode folderNode(String folderPath) {
        return new CollectionTreeNode(folderPath);
    }

    private static CollectionTreeNode requestNode(String name) {
        return new CollectionTreeNode(request(name));
    }

    private static ApiCollection collection(String name) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        return collection;
    }

    private static ApiRequest request(String name) {
        ApiRequest request = new ApiRequest();
        request.name = name;
        request.method = "GET";
        request.url = "https://example.test/" + name.toLowerCase();
        return request;
    }

    private static final class PayloadTransferable implements Transferable {
        private final RequestTreeDragPayload payload;

        private PayloadTransferable(RequestTreeDragPayload payload) {
            this.payload = payload;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{RequestTreeTransferHandler.TREE_PAYLOAD_FLAVOR};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return RequestTreeTransferHandler.TREE_PAYLOAD_FLAVOR.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) {
            return payload;
        }
    }

    private static final class FileListTransferable implements Transferable {
        private final List<File> files;

        private FileListTransferable(List<File> files) {
            this.files = files;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) {
            return files;
        }
    }
}
