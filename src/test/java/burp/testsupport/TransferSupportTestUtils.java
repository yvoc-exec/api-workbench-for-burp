package burp.testsupport;

import sun.misc.Unsafe;

import javax.swing.JPanel;
import javax.swing.TransferHandler;
import javax.swing.tree.TreePath;
import java.awt.Point;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.datatransfer.Transferable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class TransferSupportTestUtils {
    private static final Unsafe UNSAFE = initUnsafe();
    private static final long IS_DROP_OFFSET = fieldOffset(TransferHandler.TransferSupport.class, "isDrop");
    private static final long SOURCE_OFFSET = fieldOffset(TransferHandler.TransferSupport.class, "source");
    private static final long DROP_LOCATION_OFFSET = fieldOffset(TransferHandler.TransferSupport.class, "dropLocation");
    private static final long DROP_POINT_OFFSET = fieldOffset(TransferHandler.DropLocation.class, "dropPoint");

    private TransferSupportTestUtils() {
    }

    public static TransferHandler.TransferSupport support(Transferable transferable) {
        return new TransferHandler.TransferSupport(new JPanel(), transferable);
    }

    public static TransferHandler.TransferSupport dropSupport(Transferable transferable) {
        TransferHandler.TransferSupport support = support(transferable);
        UNSAFE.putBoolean(support, IS_DROP_OFFSET, true);
        UNSAFE.putObject(support, SOURCE_OFFSET, createDropEvent(transferable));
        return support;
    }

    public static TransferHandler.TransferSupport dropSupport(Transferable transferable, Object dropLocation) {
        TransferHandler.TransferSupport support = dropSupport(transferable);
        setDropLocation(support, dropLocation);
        return support;
    }

    public static Object treeDropLocation(TreePath path, int childIndex) {
        return allocateDropLocation("javax.swing.JTree$DropLocation", path, childIndex);
    }

    public static void setDropLocation(TransferHandler.TransferSupport support, Object dropLocation) {
        if (support == null) {
            return;
        }
        UNSAFE.putObject(support, DROP_LOCATION_OFFSET, dropLocation);
    }

    private static Object allocateDropLocation(String className, TreePath path, int index) {
        try {
            Class<?> type = Class.forName(className);
            Object dropLocation = UNSAFE.allocateInstance(type);
            setDropPoint(dropLocation, new Point(0, 0));
            setMatchingField(dropLocation, TreePath.class, path);
            setMatchingIntField(dropLocation, index);
            return dropLocation;
        } catch (InstantiationException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to create drop location for " + className, e);
        }
    }

    private static void setDropPoint(Object dropLocation, Point point) {
        if (dropLocation == null) {
            return;
        }
        UNSAFE.putObject(dropLocation, DROP_POINT_OFFSET, point);
    }

    private static void setMatchingField(Object target, Class<?> fieldType, Object value) {
        if (target == null) {
            return;
        }
        for (Field field : target.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (fieldType.isAssignableFrom(field.getType())) {
                long offset = UNSAFE.objectFieldOffset(field);
                UNSAFE.putObject(target, offset, value);
                return;
            }
        }
    }

    private static void setMatchingIntField(Object target, int value) {
        if (target == null) {
            return;
        }
        for (Field field : target.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (field.getType() == int.class) {
                long offset = UNSAFE.objectFieldOffset(field);
                UNSAFE.putInt(target, offset, value);
                return;
            }
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
