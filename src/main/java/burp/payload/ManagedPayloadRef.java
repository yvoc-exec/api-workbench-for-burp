package burp.payload;

import java.util.Locale;
import java.util.Objects;

/** Persisted content-addressed descriptor. Payload bytes never live here. */
public final class ManagedPayloadRef {
    public static final int CURRENT_VERSION = 1;
    public static final String MANAGED_FILE = "MANAGED_FILE";

    public int version = CURRENT_VERSION;
    public String storageKind = MANAGED_FILE;
    public String payloadId;
    public long length;
    public String sha256;

    public ManagedPayloadRef() {
    }

    public ManagedPayloadRef(String payloadId, long length, String sha256) {
        this.payloadId = normalizeSha256(payloadId);
        this.length = length;
        this.sha256 = normalizeSha256(sha256);
        validate();
    }

    public ManagedPayloadRef copy() {
        ManagedPayloadRef copy = new ManagedPayloadRef();
        copy.version = version;
        copy.storageKind = storageKind;
        copy.payloadId = payloadId;
        copy.length = length;
        copy.sha256 = sha256;
        return copy;
    }

    public void validate() {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported managed payload reference version.");
        }
        if (!MANAGED_FILE.equals(storageKind)) {
            throw new IllegalArgumentException("Unsupported managed payload storage kind.");
        }
        payloadId = normalizeSha256(payloadId);
        sha256 = normalizeSha256(sha256);
        if (!isSha256(payloadId) || !isSha256(sha256) || !payloadId.equals(sha256)) {
            throw new IllegalArgumentException("Managed payload identifier is invalid.");
        }
        if (length < 0L) {
            throw new IllegalArgumentException("Managed payload length must be non-negative.");
        }
    }

    public static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String normalizeSha256(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ManagedPayloadRef ref)) return false;
        return length == ref.length
                && Objects.equals(payloadId, ref.payloadId)
                && Objects.equals(sha256, ref.sha256)
                && Objects.equals(storageKind, ref.storageKind);
    }

    @Override
    public int hashCode() {
        return Objects.hash(payloadId, length, sha256, storageKind);
    }
}
