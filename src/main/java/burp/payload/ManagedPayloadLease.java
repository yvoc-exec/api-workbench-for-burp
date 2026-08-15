package burp.payload;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ManagedPayloadLease implements AutoCloseable {
    private byte[] bytes;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    ManagedPayloadLease(byte[] bytes, Runnable release) {
        this.bytes = bytes;
        this.release = release;
    }

    public byte[] bytes() {
        if (closed.get() || bytes == null) {
            throw new IllegalStateException("Managed payload lease is closed.");
        }
        return bytes;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            bytes = null;
            if (release != null) release.run();
        }
    }
}
