package burp.history;

public record HistoryRetentionStats(
        int entryCount,
        long totalEstimatedBytes,
        int pinnedCount,
        int truncatedEntryCount,
        boolean overBudget,
        long canonicalRetainedBytes,
        long pinnedRetainedBytes,
        long unpinnedRetainedBytes,
        int lastEvictionCount,
        long rejectedAddCount,
        int legacyCompactedEntryCount
) {
    public HistoryRetentionStats(int entryCount,
                                 long totalEstimatedBytes,
                                 int pinnedCount,
                                 int truncatedEntryCount,
                                 boolean overBudget) {
        this(
                entryCount,
                totalEstimatedBytes,
                pinnedCount,
                truncatedEntryCount,
                overBudget,
                totalEstimatedBytes,
                0L,
                totalEstimatedBytes,
                0,
                0L,
                0);
    }

    public HistoryRetentionStats {
        entryCount = Math.max(0, entryCount);
        pinnedCount = Math.max(0, pinnedCount);
        truncatedEntryCount = Math.max(0, truncatedEntryCount);
        canonicalRetainedBytes = Math.max(0L, canonicalRetainedBytes);
        totalEstimatedBytes = canonicalRetainedBytes;
        pinnedRetainedBytes = Math.min(canonicalRetainedBytes, Math.max(0L, pinnedRetainedBytes));
        unpinnedRetainedBytes = canonicalRetainedBytes - pinnedRetainedBytes;
        lastEvictionCount = Math.max(0, lastEvictionCount);
        rejectedAddCount = Math.max(0L, rejectedAddCount);
        legacyCompactedEntryCount = Math.max(0, legacyCompactedEntryCount);
    }

    public static HistoryRetentionStats empty() {
        return new HistoryRetentionStats(0, 0L, 0, 0, false, 0L, 0L, 0L, 0, 0L, 0);
    }
}
