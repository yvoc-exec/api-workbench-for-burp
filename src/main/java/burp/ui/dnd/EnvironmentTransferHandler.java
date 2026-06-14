package burp.ui.dnd;

import javax.swing.JComponent;
import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * File-drop handler for environment imports.
 */
public final class EnvironmentTransferHandler extends TransferHandler {
    private static final long serialVersionUID = 1L;

    private final Consumer<List<File>> fileDropImporter;
    private final Consumer<String> logger;

    public EnvironmentTransferHandler(Consumer<List<File>> fileDropImporter,
                                      Consumer<String> logger) {
        this.fileDropImporter = fileDropImporter;
        this.logger = logger;
    }

    @Override
    public boolean canImport(TransferSupport support) {
        return support != null
                && support.isDrop()
                && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) {
            return false;
        }
        List<File> files = extractFiles(support.getTransferable());
        if (files.isEmpty()) {
            log("Environment file drop ignored: no files supplied.");
            return false;
        }
        if (fileDropImporter != null) {
            fileDropImporter.accept(files);
        }
        log("Dropped " + files.size() + " environment file(s).");
        return true;
    }

    private List<File> extractFiles(Transferable transferable) {
        if (transferable == null) {
            return List.of();
        }
        try {
            Object data = transferable.getTransferData(DataFlavor.javaFileListFlavor);
            if (!(data instanceof List<?> raw)) {
                return List.of();
            }
            List<File> files = new ArrayList<>();
            for (Object entry : raw) {
                if (entry instanceof File file) {
                    files.add(file);
                }
            }
            return files;
        } catch (UnsupportedFlavorException | IOException e) {
            log("Environment file drop failed: " + e.getMessage());
            return List.of();
        }
    }

    private void log(String message) {
        if (logger != null && message != null && !message.isBlank()) {
            logger.accept(message);
        }
    }
}
