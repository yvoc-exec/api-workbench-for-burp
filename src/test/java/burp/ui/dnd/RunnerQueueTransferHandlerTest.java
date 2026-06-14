package burp.ui.dnd;

import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import javax.swing.JList;
import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerQueueTransferHandlerTest {

    @Test
    void importDataReordersThroughPayloadAndTargetIndex() {
        ApiRequest one = request("One");
        ApiRequest two = request("Two");
        ApiRequest three = request("Three");
        List<ApiRequest> queue = List.of(one, two, three);
        AtomicReference<int[]> reorderArgs = new AtomicReference<>();
        List<String> logMessages = new ArrayList<>();
        RunnerQueueTransferHandler handler = new RunnerQueueTransferHandler(() -> queue, (sourceIndex, targetIndex) -> {
            reorderArgs.set(new int[]{sourceIndex, targetIndex});
            return true;
        }, logMessages::add);

        Transferable transferable = new PayloadTransferable(new RunnerQueueDragPayload(one, 0));
        TransferHandler.TransferSupport support = TransferSupportTestUtils.dropSupport(transferable, 3);

        assertThat(handler.canImport(support)).isTrue();
        assertThat(handler.importData(support)).isTrue();
        assertThat(reorderArgs.get()).containsExactly(0, 3);
        assertThat(logMessages).contains("Reordered runner queue item: One");
    }

    @Test
    void missingPayloadNoOpAndInvalidSourceAreRejected() {
        List<ApiRequest> queue = List.of(request("One"), request("Two"), request("Three"));
        AtomicBoolean reorderCalled = new AtomicBoolean(false);
        RunnerQueueTransferHandler handler = new RunnerQueueTransferHandler(() -> queue, (sourceIndex, targetIndex) -> {
            reorderCalled.set(true);
            return true;
        }, message -> {
        });

        TransferHandler.TransferSupport missingPayload = TransferSupportTestUtils.dropSupport(new StringSelection("not-runner-payload"), 3);
        assertThat(handler.canImport(missingPayload)).isFalse();
        assertThat(handler.importData(missingPayload)).isFalse();

        TransferHandler.TransferSupport noOpSupport = TransferSupportTestUtils.dropSupport(new PayloadTransferable(new RunnerQueueDragPayload(queue.get(1), 1)), 2);
        assertThat(handler.canImport(noOpSupport)).isTrue();
        assertThat(handler.importData(noOpSupport)).isFalse();
        assertThat(reorderCalled).isFalse();

        TransferHandler.TransferSupport invalidSource = TransferSupportTestUtils.dropSupport(new PayloadTransferable(new RunnerQueueDragPayload(request("Missing"), 9)), 1);
        assertThat(handler.canImport(invalidSource)).isTrue();
        assertThat(handler.importData(invalidSource)).isFalse();
        assertThat(reorderCalled).isFalse();
    }

    private static ApiRequest request(String name) {
        ApiRequest request = new ApiRequest();
        request.name = name;
        request.method = "GET";
        request.url = "https://example.test/" + name.toLowerCase();
        return request;
    }

    private static final class PayloadTransferable implements Transferable {
        private final RunnerQueueDragPayload payload;

        private PayloadTransferable(RunnerQueueDragPayload payload) {
            this.payload = payload;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{RunnerQueueTransferHandler.RUNNER_QUEUE_PAYLOAD_FLAVOR};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return RunnerQueueTransferHandler.RUNNER_QUEUE_PAYLOAD_FLAVOR.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return payload;
        }
    }
}
