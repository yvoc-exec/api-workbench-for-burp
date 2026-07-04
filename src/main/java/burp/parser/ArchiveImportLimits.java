package burp.parser;

public final class ArchiveImportLimits {
    public static final int DEFAULT_MAX_ENTRIES = 10_000;
    public static final long DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L;
    public static final long DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES = 32L * 1024L * 1024L;
    public static final int DEFAULT_MAX_PATH_DEPTH = 32;
    public static final double DEFAULT_MAX_COMPRESSION_RATIO = 200.0d;

    public int maxEntries;
    public long maxTotalUncompressedBytes;
    public long maxEntryUncompressedBytes;
    public int maxPathDepth;
    public double maxCompressionRatio;

    public ArchiveImportLimits() {
        this(
                DEFAULT_MAX_ENTRIES,
                DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES,
                DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES,
                DEFAULT_MAX_PATH_DEPTH,
                DEFAULT_MAX_COMPRESSION_RATIO
        );
    }

    public ArchiveImportLimits(int maxEntries,
                               long maxTotalUncompressedBytes,
                               long maxEntryUncompressedBytes,
                               int maxPathDepth,
                               double maxCompressionRatio) {
        this.maxEntries = maxEntries;
        this.maxTotalUncompressedBytes = maxTotalUncompressedBytes;
        this.maxEntryUncompressedBytes = maxEntryUncompressedBytes;
        this.maxPathDepth = maxPathDepth;
        this.maxCompressionRatio = maxCompressionRatio;
        normalize();
    }

    public static ArchiveImportLimits defaultLimits() {
        return new ArchiveImportLimits();
    }

    public static ArchiveImportLimits copyOf(ArchiveImportLimits source) {
        if (source == null) {
            return defaultLimits();
        }
        return new ArchiveImportLimits(
                source.maxEntries,
                source.maxTotalUncompressedBytes,
                source.maxEntryUncompressedBytes,
                source.maxPathDepth,
                source.maxCompressionRatio
        );
    }

    public void normalize() {
        if (maxEntries <= 0) {
            maxEntries = DEFAULT_MAX_ENTRIES;
        }
        if (maxTotalUncompressedBytes <= 0) {
            maxTotalUncompressedBytes = DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES;
        }
        if (maxEntryUncompressedBytes <= 0) {
            maxEntryUncompressedBytes = DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES;
        }
        if (maxPathDepth <= 0) {
            maxPathDepth = DEFAULT_MAX_PATH_DEPTH;
        }
        if (!Double.isFinite(maxCompressionRatio) || maxCompressionRatio < 1.0d) {
            maxCompressionRatio = DEFAULT_MAX_COMPRESSION_RATIO;
        }
    }
}
