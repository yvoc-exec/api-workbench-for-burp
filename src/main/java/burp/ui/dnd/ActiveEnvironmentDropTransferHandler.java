package burp.ui.dnd;

import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Drop target that activates an existing environment profile.
 */
public final class ActiveEnvironmentDropTransferHandler extends TransferHandler {
    private static final long serialVersionUID = 1L;

    private final Function<EnvironmentDragPayload, Boolean> activeSetter;
    private final Consumer<String> logger;

    public ActiveEnvironmentDropTransferHandler(Function<EnvironmentDragPayload, Boolean> activeSetter,
                                                Consumer<String> logger) {
        this.activeSetter = activeSetter;
        this.logger = logger;
    }

    @Override
    public boolean canImport(TransferSupport support) {
        return support != null
                && support.isDrop()
                && support.isDataFlavorSupported(EnvironmentProfileDragSourceTransferHandler.ENVIRONMENT_PAYLOAD_FLAVOR);
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) {
            return false;
        }
        EnvironmentDragPayload payload = extractPayload(support.getTransferable());
        if (payload == null) {
            log("Active environment drop ignored: missing payload.");
            return false;
        }
        boolean accepted = activeSetter != null && Boolean.TRUE.equals(activeSetter.apply(payload));
        if (accepted) {
            log("Active environment drop accepted: " + payload.environmentName);
        } else {
            log("Active environment drop rejected: " + payload.environmentName);
        }
        return accepted;
    }

    private EnvironmentDragPayload extractPayload(Transferable transferable) {
        if (transferable == null) {
            return null;
        }
        try {
            Object data = transferable.getTransferData(EnvironmentProfileDragSourceTransferHandler.ENVIRONMENT_PAYLOAD_FLAVOR);
            return data instanceof EnvironmentDragPayload payload ? payload : null;
        } catch (UnsupportedFlavorException | IOException e) {
            log("Active environment drop failed: " + e.getMessage());
            return null;
        }
    }

    private void log(String message) {
        if (logger != null && message != null && !message.isBlank()) {
            logger.accept(message);
        }
    }
}
