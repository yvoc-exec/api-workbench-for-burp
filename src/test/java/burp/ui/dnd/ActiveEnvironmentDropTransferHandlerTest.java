package burp.ui.dnd;

import org.junit.jupiter.api.Test;

import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveEnvironmentDropTransferHandlerTest {

    @Test
    void acceptsEnvironmentPayloadAndLogsAcceptance() {
        EnvironmentDragPayload payload = new EnvironmentDragPayload("env-1", "UAT");
        AtomicReference<EnvironmentDragPayload> acceptedPayload = new AtomicReference<>();
        List<String> logMessages = new ArrayList<>();
        ActiveEnvironmentDropTransferHandler handler = new ActiveEnvironmentDropTransferHandler(payloadArg -> {
            acceptedPayload.set(payloadArg);
            return true;
        }, logMessages::add);

        Transferable transferable = new PayloadTransferable(payload);
        TransferHandler.TransferSupport support = TransferSupportTestUtils.dropSupport(transferable);

        assertThat(handler.canImport(support)).isTrue();
        assertThat(handler.importData(support)).isTrue();
        assertThat(acceptedPayload.get()).isSameAs(payload);
        assertThat(logMessages).contains("Active environment drop accepted: UAT");
    }

    @Test
    void rejectsFileDropsWithoutCallingActiveSetter() {
        AtomicReference<EnvironmentDragPayload> acceptedPayload = new AtomicReference<>();
        List<String> logMessages = new ArrayList<>();
        ActiveEnvironmentDropTransferHandler handler = new ActiveEnvironmentDropTransferHandler(payload -> {
            acceptedPayload.set(payload);
            return true;
        }, logMessages::add);

        TransferHandler.TransferSupport support = TransferSupportTestUtils.dropSupport(new StringSelection("not-environment-payload"));

        assertThat(handler.canImport(support)).isFalse();
        assertThat(handler.importData(support)).isFalse();
        assertThat(acceptedPayload.get()).isNull();
        assertThat(logMessages).isEmpty();
    }

    private static final class PayloadTransferable implements Transferable {
        private final EnvironmentDragPayload payload;

        private PayloadTransferable(EnvironmentDragPayload payload) {
            this.payload = payload;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{EnvironmentProfileDragSourceTransferHandler.ENVIRONMENT_PAYLOAD_FLAVOR};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return EnvironmentProfileDragSourceTransferHandler.ENVIRONMENT_PAYLOAD_FLAVOR.equals(flavor);
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
