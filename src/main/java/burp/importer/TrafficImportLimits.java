package burp.importer;

import burp.utils.Base64ByteArrayTypeAdapter;

/** Legacy configuration shape retained for workspace/test compatibility; no longer admission policy. */
@Deprecated
public final class TrafficImportLimits {
    public static final long MIB = 1024L * 1024L;
    public static final long DEFAULT_MAX_EXACT_REQUEST_BYTES = 16L * MIB;
    public static final long DEFAULT_MAX_AGGREGATE_EXACT_REQUEST_BYTES = 128L * MIB;
    public static final long MIN_MAX_EXACT_REQUEST_BYTES = 1L * MIB;
    public static final long MAX_MAX_EXACT_REQUEST_BYTES =
            Base64ByteArrayTypeAdapter.DEFAULT_MAX_DECODED_BYTES;
    public static final long MIN_MAX_AGGREGATE_EXACT_REQUEST_BYTES = 16L * MIB;
    public static final long MAX_MAX_AGGREGATE_EXACT_REQUEST_BYTES = 512L * MIB;

    private final long maxExactRequestBytes;
    private final long maxAggregateExactRequestBytes;

    public TrafficImportLimits(long maxExactRequestBytes, long maxAggregateExactRequestBytes) {
        this.maxExactRequestBytes = normalize(
                maxExactRequestBytes,
                MIN_MAX_EXACT_REQUEST_BYTES,
                MAX_MAX_EXACT_REQUEST_BYTES,
                DEFAULT_MAX_EXACT_REQUEST_BYTES);
        this.maxAggregateExactRequestBytes = normalize(
                maxAggregateExactRequestBytes,
                MIN_MAX_AGGREGATE_EXACT_REQUEST_BYTES,
                MAX_MAX_AGGREGATE_EXACT_REQUEST_BYTES,
                DEFAULT_MAX_AGGREGATE_EXACT_REQUEST_BYTES);
    }

    public static TrafficImportLimits defaults() {
        return new TrafficImportLimits(
                DEFAULT_MAX_EXACT_REQUEST_BYTES,
                DEFAULT_MAX_AGGREGATE_EXACT_REQUEST_BYTES);
    }

    public long maxExactRequestBytes() {
        return maxExactRequestBytes;
    }

    public long maxAggregateExactRequestBytes() {
        return maxAggregateExactRequestBytes;
    }

    private static long normalize(long requested, long minimum, long maximum, long defaultValue) {
        if (requested <= 0L) {
            return defaultValue;
        }
        return Math.max(minimum, Math.min(maximum, requested));
    }
}
