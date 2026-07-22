package burp.history;

public class HistoryRetentionPolicy {
    public static final int MIN_MAX_ENTRIES = 1;
    public static final int MAX_MAX_ENTRIES = 10_000;
    public static final long MIN_MAX_TOTAL_STORED_BYTES = 1L;
    public static final long MAX_MAX_TOTAL_STORED_BYTES = 512L * 1024L * 1024L;
    public static final long MIN_MAX_REQUEST_BODY_BYTES_PER_ENTRY = 1L;
    public static final long MAX_MAX_REQUEST_BODY_BYTES_PER_ENTRY = 16L * 1024L * 1024L;
    public static final long MIN_MAX_RESPONSE_BODY_BYTES_PER_ENTRY = 1L;
    public static final long MAX_MAX_RESPONSE_BODY_BYTES_PER_ENTRY = 32L * 1024L * 1024L;
    public static final int CURRENT_POLICY_VERSION = 1;

    public static final int DEFAULT_MAX_ENTRIES = 1_000;
    public static final long DEFAULT_MAX_TOTAL_STORED_BYTES = 100L * 1024L * 1024L;
    public static final long DEFAULT_MAX_REQUEST_BODY_BYTES_PER_ENTRY = 1L * 1024L * 1024L;
    public static final long DEFAULT_MAX_RESPONSE_BODY_BYTES_PER_ENTRY = 2L * 1024L * 1024L;

    public int maxEntries;
    public long maxTotalStoredBytes;
    public long maxRequestBodyBytesPerEntry;
    public long maxResponseBodyBytesPerEntry;
    public boolean retainPinnedEntries;

    public HistoryRetentionPolicy() {
        this(
                DEFAULT_MAX_ENTRIES,
                DEFAULT_MAX_TOTAL_STORED_BYTES,
                DEFAULT_MAX_REQUEST_BODY_BYTES_PER_ENTRY,
                DEFAULT_MAX_RESPONSE_BODY_BYTES_PER_ENTRY,
                true
        );
    }

    public HistoryRetentionPolicy(int maxEntries) {
        this(
                maxEntries,
                DEFAULT_MAX_TOTAL_STORED_BYTES,
                DEFAULT_MAX_REQUEST_BODY_BYTES_PER_ENTRY,
                DEFAULT_MAX_RESPONSE_BODY_BYTES_PER_ENTRY,
                true
        );
    }

    public HistoryRetentionPolicy(int maxEntries,
                                  long maxTotalStoredBytes,
                                  long maxRequestBodyBytesPerEntry,
                                  long maxResponseBodyBytesPerEntry,
                                  boolean retainPinnedEntries) {
        this.maxEntries = maxEntries;
        this.maxTotalStoredBytes = maxTotalStoredBytes;
        this.maxRequestBodyBytesPerEntry = maxRequestBodyBytesPerEntry;
        this.maxResponseBodyBytesPerEntry = maxResponseBodyBytesPerEntry;
        this.retainPinnedEntries = retainPinnedEntries;
        normalize();
    }

    public static HistoryRetentionPolicy defaultPolicy() {
        return new HistoryRetentionPolicy();
    }

    public static HistoryRetentionPolicy copyOf(HistoryRetentionPolicy source) {
        if (source == null) {
            return defaultPolicy();
        }
        return new HistoryRetentionPolicy(
                source.maxEntries,
                source.maxTotalStoredBytes,
                source.maxRequestBodyBytesPerEntry,
                source.maxResponseBodyBytesPerEntry,
                source.retainPinnedEntries
        );
    }

    public void normalize() {
        if (maxEntries <= 0) {
            maxEntries = DEFAULT_MAX_ENTRIES;
        } else {
            maxEntries = Math.max(MIN_MAX_ENTRIES, Math.min(MAX_MAX_ENTRIES, maxEntries));
        }
        if (maxTotalStoredBytes <= 0) {
            maxTotalStoredBytes = DEFAULT_MAX_TOTAL_STORED_BYTES;
        } else {
            maxTotalStoredBytes = clamp(
                    maxTotalStoredBytes,
                    MIN_MAX_TOTAL_STORED_BYTES,
                    MAX_MAX_TOTAL_STORED_BYTES);
        }
        if (maxRequestBodyBytesPerEntry <= 0) {
            maxRequestBodyBytesPerEntry = DEFAULT_MAX_REQUEST_BODY_BYTES_PER_ENTRY;
        } else {
            maxRequestBodyBytesPerEntry = clamp(
                    maxRequestBodyBytesPerEntry,
                    MIN_MAX_REQUEST_BODY_BYTES_PER_ENTRY,
                    MAX_MAX_REQUEST_BODY_BYTES_PER_ENTRY);
        }
        if (maxResponseBodyBytesPerEntry <= 0) {
            maxResponseBodyBytesPerEntry = DEFAULT_MAX_RESPONSE_BODY_BYTES_PER_ENTRY;
        } else {
            maxResponseBodyBytesPerEntry = clamp(
                    maxResponseBodyBytesPerEntry,
                    MIN_MAX_RESPONSE_BODY_BYTES_PER_ENTRY,
                    MAX_MAX_RESPONSE_BODY_BYTES_PER_ENTRY);
        }
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
