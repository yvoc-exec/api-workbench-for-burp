package burp.payload;

import java.util.Objects;

/** A byte range in an existing managed payload; it never creates another blob. */
public final class PayloadSliceRef {
    public ManagedPayloadRef payload;
    public long offset;
    public long length;

    public PayloadSliceRef() {
    }

    public PayloadSliceRef(ManagedPayloadRef payload, long offset, long length) {
        this.payload = payload != null ? payload.copy() : null;
        this.offset = offset;
        this.length = length;
        validate();
    }

    public PayloadSliceRef copy() {
        return new PayloadSliceRef(payload, offset, length);
    }

    public void validate() {
        if (payload == null) {
            throw new IllegalArgumentException("Managed payload slice requires a payload reference.");
        }
        payload.validate();
        if (offset < 0L || length < 0L || offset > Long.MAX_VALUE - length) {
            throw new IllegalArgumentException("Managed payload slice range is invalid.");
        }
        if (offset + length > payload.length) {
            throw new IllegalArgumentException("Managed payload slice exceeds its payload.");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PayloadSliceRef ref)) return false;
        return offset == ref.offset && length == ref.length && Objects.equals(payload, ref.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(payload, offset, length);
    }
}
