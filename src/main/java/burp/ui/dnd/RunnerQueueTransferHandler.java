package burp.ui.dnd;

import burp.models.ApiRequest;

import javax.swing.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Drag/drop handler for reordering queued runner requests.
 */
public final class RunnerQueueTransferHandler extends TransferHandler {
    private static final long serialVersionUID = 1L;
    public static final DataFlavor RUNNER_QUEUE_PAYLOAD_FLAVOR;

    static {
        try {
            RUNNER_QUEUE_PAYLOAD_FLAVOR = new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType
                    + ";class=" + RunnerQueueDragPayload.class.getName());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Supplier<List<ApiRequest>> queueSupplier;
    private final BiFunction<Integer, Integer, Boolean> reorderHandler;
    private final Consumer<String> logger;

    public RunnerQueueTransferHandler(Supplier<List<ApiRequest>> queueSupplier,
                                      BiFunction<Integer, Integer, Boolean> reorderHandler,
                                      Consumer<String> logger) {
        this.queueSupplier = queueSupplier;
        this.reorderHandler = reorderHandler;
        this.logger = logger;
    }

    @Override
    public int getSourceActions(JComponent c) {
        return MOVE;
    }

    @Override
    protected Transferable createTransferable(JComponent c) {
        if (!(c instanceof JList<?> list)) {
            return null;
        }
        int sourceIndex = list.getSelectedIndex();
        if (sourceIndex < 0) {
            return null;
        }
        List<ApiRequest> queue = queueSupplier != null ? queueSupplier.get() : null;
        if (queue == null || sourceIndex >= queue.size()) {
            return null;
        }
        ApiRequest request = queue.get(sourceIndex);
        if (request == null) {
            return null;
        }
        log("Dragging runner queue item: " + request.name);
        return new PayloadTransferable(new RunnerQueueDragPayload(request, sourceIndex));
    }

    @Override
    public boolean canImport(TransferSupport support) {
        return support != null
                && support.isDrop()
                && support.isDataFlavorSupported(RUNNER_QUEUE_PAYLOAD_FLAVOR);
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) {
            return false;
        }
        RunnerQueueDragPayload payload = extractPayload(support.getTransferable());
        if (payload == null || payload.request == null) {
            log("Runner queue drop ignored: missing payload.");
            return false;
        }
        int targetIndex = extractTargetIndex(support);
        List<ApiRequest> queue = queueSupplier != null ? queueSupplier.get() : null;
        if (queue == null || targetIndex < 0 || payload.sourceIndex < 0 || payload.sourceIndex >= queue.size()) {
            return false;
        }
        int adjustedTarget = targetIndex;
        if (payload.sourceIndex < adjustedTarget) {
            adjustedTarget--;
        }
        if (adjustedTarget == payload.sourceIndex) {
            log("Runner queue drop ignored: no position change.");
            return false;
        }
        boolean accepted = reorderHandler != null && Boolean.TRUE.equals(reorderHandler.apply(payload.sourceIndex, targetIndex));
        if (accepted) {
            log("Reordered runner queue item: " + payload.request.name);
        }
        return accepted;
    }

    private int extractTargetIndex(TransferSupport support) {
        if (!(support.getDropLocation() instanceof JList.DropLocation dropLocation)) {
            return -1;
        }
        return dropLocation.getIndex();
    }

    private RunnerQueueDragPayload extractPayload(Transferable transferable) {
        if (transferable == null) {
            return null;
        }
        try {
            Object data = transferable.getTransferData(RUNNER_QUEUE_PAYLOAD_FLAVOR);
            return data instanceof RunnerQueueDragPayload payload ? payload : null;
        } catch (UnsupportedFlavorException | IOException e) {
            log("Runner queue drop failed: " + e.getMessage());
            return null;
        }
    }

    private void log(String message) {
        if (logger != null && message != null && !message.isBlank()) {
            logger.accept(message);
        }
    }

    private static final class PayloadTransferable implements Transferable {
        private final RunnerQueueDragPayload payload;

        private PayloadTransferable(RunnerQueueDragPayload payload) {
            this.payload = payload;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{RUNNER_QUEUE_PAYLOAD_FLAVOR};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return RUNNER_QUEUE_PAYLOAD_FLAVOR.equals(flavor);
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
