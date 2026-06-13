package burp.ui.dnd;

import org.junit.jupiter.api.Test;

import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentTransferHandlerTest {

    @Test
    void canImportAndImportDataDelegateDroppedFileList() {
        File first = new File("one.env");
        File second = new File("two.env");
        AtomicReference<List<File>> delegatedFiles = new AtomicReference<>();
        List<String> logMessages = new ArrayList<>();
        EnvironmentTransferHandler handler = new EnvironmentTransferHandler(delegatedFiles::set, logMessages::add);

        Transferable transferable = new FileListTransferable(List.of(first, second));
        TransferHandler.TransferSupport support = TransferSupportTestUtils.dropSupport(transferable);

        assertThat(handler.canImport(support)).isTrue();
        assertThat(handler.importData(support)).isTrue();
        assertThat(delegatedFiles.get()).containsExactly(first, second);
        assertThat(logMessages).contains("Dropped 2 environment file(s).");
    }

    @Test
    void unsupportedOrNonDropFlavorIsRejected() {
        AtomicReference<List<File>> delegatedFiles = new AtomicReference<>();
        List<String> logMessages = new ArrayList<>();
        EnvironmentTransferHandler handler = new EnvironmentTransferHandler(delegatedFiles::set, logMessages::add);

        TransferHandler.TransferSupport unsupportedSupport = TransferSupportTestUtils.dropSupport(new StringSelection("not files"));
        assertThat(handler.canImport(unsupportedSupport)).isFalse();
        assertThat(handler.importData(unsupportedSupport)).isFalse();
        assertThat(delegatedFiles.get()).isNull();
        assertThat(logMessages).isEmpty();
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
            if (!isDataFlavorSupported(flavor)) {
                throw new IllegalArgumentException("Unsupported flavor: " + flavor);
            }
            return files;
        }
    }
}
