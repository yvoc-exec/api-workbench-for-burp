package burp.ui.dnd;

import burp.models.EnvironmentProfile;

import javax.swing.JComponent;
import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Drag source for existing environment profiles.
 */
public final class EnvironmentProfileDragSourceTransferHandler extends TransferHandler {
    private static final long serialVersionUID = 1L;
    public static final DataFlavor ENVIRONMENT_PAYLOAD_FLAVOR;

    static {
        ENVIRONMENT_PAYLOAD_FLAVOR = new DataFlavor(
                EnvironmentDragPayload.class,
                "API Workbench Environment Drag Payload");
    }

    private final Supplier<EnvironmentProfile> selectedProfileSupplier;
    private final Consumer<String> logger;

    public EnvironmentProfileDragSourceTransferHandler(Supplier<EnvironmentProfile> selectedProfileSupplier,
                                                       Consumer<String> logger) {
        this.selectedProfileSupplier = selectedProfileSupplier;
        this.logger = logger;
    }

    @Override
    public int getSourceActions(JComponent c) {
        return COPY;
    }

    @Override
    protected Transferable createTransferable(JComponent c) {
        EnvironmentProfile profile = selectedProfileSupplier != null ? selectedProfileSupplier.get() : null;
        if (profile == null) {
            return null;
        }
        profile.ensureId();
        EnvironmentDragPayload payload = new EnvironmentDragPayload(profile.id, profile.displayName());
        log("Dragging environment profile: " + profile.displayName());
        return new PayloadTransferable(payload);
    }

    private void log(String message) {
        if (logger != null && message != null && !message.isBlank()) {
            logger.accept(message);
        }
    }

    private static final class PayloadTransferable implements Transferable {
        private final EnvironmentDragPayload payload;

        private PayloadTransferable(EnvironmentDragPayload payload) {
            this.payload = payload;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{ENVIRONMENT_PAYLOAD_FLAVOR};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return ENVIRONMENT_PAYLOAD_FLAVOR.equals(flavor);
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
