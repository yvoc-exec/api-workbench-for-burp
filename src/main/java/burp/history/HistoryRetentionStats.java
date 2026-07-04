package burp.history;

public record HistoryRetentionStats(
        int entryCount,
        long totalEstimatedBytes,
        int pinnedCount,
        int truncatedEntryCount,
        boolean overBudget
) {
    public static HistoryRetentionStats empty() {
        return new HistoryRetentionStats(0, 0L, 0, 0, false);
    }
}
