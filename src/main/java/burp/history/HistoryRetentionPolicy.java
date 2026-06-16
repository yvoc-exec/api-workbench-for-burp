package burp.history;

public class HistoryRetentionPolicy {
    public int maxEntries = 1000;

    public HistoryRetentionPolicy() {
    }

    public HistoryRetentionPolicy(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    public static HistoryRetentionPolicy defaultPolicy() {
        return new HistoryRetentionPolicy(1000);
    }
}
