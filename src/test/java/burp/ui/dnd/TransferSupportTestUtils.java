package burp.ui.dnd;

import sun.misc.Unsafe;

import javax.swing.JPanel;
import javax.swing.TransferHandler;
import java.awt.Point;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.datatransfer.Transferable;
import java.lang.reflect.Field;

final class TransferSupportTestUtils {
    private static final Unsafe UNSAFE = initUnsafe();
    private static final long IS_DROP_OFFSET = fieldOffset(TransferHandler.TransferSupport.class, "isDrop");
    private static final long SOURCE_OFFSET = fieldOffset(TransferHandler.TransferSupport.class, "source");
    private static final long DROP_LOCATION_OFFSET = fieldOffset(TransferHandler.TransferSupport.class, "dropLocation");
    private static final long DROP_POINT_OFFSET = fieldOffset(TransferHandler.DropLocation.class, "dropPoint");
    private static final long LIST_INDEX_OFFSET = fieldOffset(loadClass("javax.swing.JList$DropLocation"), "index");
    private static final long LIST_IS_INSERT_OFFSET = fieldOffset(loadClass("javax.swing.JList$DropLocation"), "isInsert");

    private TransferSupportTestUtils() {
    }

    static TransferHandler.TransferSupport support(Transferable transferable) {
        return new TransferHandler.TransferSupport(new JPanel(), transferable);
    }

    static TransferHandler.TransferSupport dropSupport(Transferable transferable) {
        TransferHandler.TransferSupport support = support(transferable);
        UNSAFE.putBoolean(support, IS_DROP_OFFSET, true);
        UNSAFE.putObject(support, SOURCE_OFFSET, createDropEvent(transferable));
        return support;
    }

    static TransferHandler.TransferSupport dropSupport(Transferable transferable, int dropIndex) {
        TransferHandler.TransferSupport support = dropSupport(transferable);
        setDropLocation(support, dropIndex);
        return support;
    }

    static void setDropLocation(TransferHandler.TransferSupport support, int dropIndex) {
        try {
            Class<?> dropLocationType = loadClass("javax.swing.JList$DropLocation");
            Object dropLocation = UNSAFE.allocateInstance(dropLocationType);
            UNSAFE.putObject(dropLocation, DROP_POINT_OFFSET, new Point(0, 0));
            UNSAFE.putInt(dropLocation, LIST_INDEX_OFFSET, dropIndex);
            UNSAFE.putBoolean(dropLocation, LIST_IS_INSERT_OFFSET, true);
            UNSAFE.putObject(support, DROP_LOCATION_OFFSET, dropLocation);
        } catch (InstantiationException e) {
            throw new IllegalStateException("Failed to create JList drop location", e);
        }
    }

    private static DropTargetDropEvent createDropEvent(Transferable transferable) {
        try {
            FakeDropTargetDropEvent event = (FakeDropTargetDropEvent) UNSAFE.allocateInstance(FakeDropTargetDropEvent.class);
            event.setTransferable(transferable);
            return event;
        } catch (InstantiationException e) {
            throw new IllegalStateException("Failed to create fake drop event", e);
        }
    }

    private static Unsafe initUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to acquire Unsafe", e);
        }
    }

    private static long fieldOffset(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            return UNSAFE.objectFieldOffset(field);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to resolve field offset for " + type.getName() + "." + name, e);
        }
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Failed to load " + name, e);
        }
    }

    private static final class FakeDropTargetDropEvent extends DropTargetDropEvent {
        private Transferable transferable;

        private FakeDropTargetDropEvent() {
            super(null, new Point(0, 0), DnDConstants.ACTION_COPY, DnDConstants.ACTION_COPY, true);
        }

        private void setTransferable(Transferable transferable) {
            this.transferable = transferable;
        }

        @Override
        public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
            return transferable != null && transferable.isDataFlavorSupported(flavor);
        }

        @Override
        public java.awt.datatransfer.Transferable getTransferable() {
            return transferable;
        }

        @Override
        public java.awt.datatransfer.DataFlavor[] getCurrentDataFlavors() {
            return transferable != null ? transferable.getTransferDataFlavors() : new java.awt.datatransfer.DataFlavor[0];
        }
    }
}
