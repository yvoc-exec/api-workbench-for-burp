package burp.history;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class HistoryStore {
    private final List<HistoryEntry> entries = new ArrayList<>();
    private HistoryRetentionPolicy retentionPolicy = HistoryRetentionPolicy.defaultPolicy();
    private HistoryRetentionStats retentionStats = HistoryRetentionStats.empty();

    public synchronized void setRetentionPolicy(HistoryRetentionPolicy retentionPolicy) {
        HistoryRetentionPolicy safePolicy = HistoryRetentionPolicy.copyOf(retentionPolicy);
        safePolicy.normalize();
        this.retentionPolicy = safePolicy;
        normalizeAndRecalculate();
    }

    public synchronized HistoryRetentionPolicy getRetentionPolicy() {
        return HistoryRetentionPolicy.copyOf(retentionPolicy);
    }

    public synchronized HistoryRetentionStats getRetentionStats() {
        return retentionStats;
    }

    public synchronized HistoryEntry addEntry(HistoryEntry entry) {
        HistoryEntry stored = normalizeIncomingEntry(entry, retentionPolicy);
        entries.add(0, stored);
        normalizeAndRecalculate();
        return HistoryEntry.copyOf(stored);
    }

    public synchronized void addAll(Collection<HistoryEntry> newEntries) {
        if (newEntries == null || newEntries.isEmpty()) {
            return;
        }
        for (HistoryEntry entry : newEntries) {
            entries.add(normalizeIncomingEntry(entry, retentionPolicy));
        }
        normalizeAndRecalculate();
    }

    public synchronized void replaceAll(Collection<HistoryEntry> newEntries) {
        entries.clear();
        if (newEntries != null && !newEntries.isEmpty()) {
            for (HistoryEntry entry : newEntries) {
                entries.add(normalizeIncomingEntry(entry, retentionPolicy));
            }
        }
        normalizeAndRecalculate();
    }

    public synchronized List<HistoryEntry> snapshot() {
        List<HistoryEntry> copy = new ArrayList<>(entries.size());
        for (HistoryEntry entry : entries) {
            HistoryEntry cloned = HistoryEntry.copyOf(entry);
            if (cloned != null) {
                copy.add(cloned);
            }
        }
        return copy;
    }

    public synchronized HistoryEntry getById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (HistoryEntry entry : entries) {
            if (entry != null && Objects.equals(id, entry.id)) {
                return HistoryEntry.copyOf(entry);
            }
        }
        return null;
    }

    public synchronized List<HistoryEntry> getByIds(Collection<String> ids) {
        List<HistoryEntry> selected = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return selected;
        }
        for (HistoryEntry entry : entries) {
            if (entry != null && ids.contains(entry.id)) {
                HistoryEntry copy = HistoryEntry.copyOf(entry);
                if (copy != null) {
                    selected.add(copy);
                }
            }
        }
        return selected;
    }

    public synchronized boolean removeById(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        boolean removed = entries.removeIf(entry -> entry != null && Objects.equals(id, entry.id));
        if (removed) {
            normalizeAndRecalculate();
        }
        return removed;
    }

    public synchronized List<HistoryEntry> removeByIds(Collection<String> ids) {
        List<HistoryEntry> removed = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return removed;
        }
        entries.removeIf(entry -> {
            if (entry != null && ids.contains(entry.id)) {
                HistoryEntry copy = HistoryEntry.copyOf(entry);
                if (copy != null) {
                    removed.add(copy);
                }
                return true;
            }
            return false;
        });
        if (!removed.isEmpty()) {
            normalizeAndRecalculate();
        }
        return removed;
    }

    public synchronized boolean setPinned(String id, boolean pinned) {
        HistoryEntry entry = findStoredEntry(id);
        if (entry == null) {
            return false;
        }
        if (entry.pinned != pinned) {
            entry.pinned = pinned;
            normalizeAndRecalculate();
        }
        return true;
    }

    public synchronized HistoryEntry updateAnalystMetadata(String id, String notes, Collection<String> tags) {
        HistoryEntry entry = findStoredEntry(id);
        if (entry == null) {
            return null;
        }
        entry.analystNotes = notes != null ? HistorySanitizer.safeMultiline(notes) : "";
        entry.tags = HistoryBodyTruncator.normalizeTags(tags);
        normalizeAndRecalculate();
        HistoryEntry refreshed = getById(id);
        return refreshed != null ? refreshed : HistoryEntry.copyOf(entry);
    }

    public synchronized HistoryEntry updateEvidenceMetadata(String id,
                                                            boolean pinned,
                                                            String notes,
                                                            Collection<String> tags) {
        HistoryEntry entry = findStoredEntry(id);
        if (entry == null) {
            return null;
        }
        entry.pinned = pinned;
        entry.analystNotes = notes != null ? HistorySanitizer.safeMultiline(notes) : "";
        entry.tags = HistoryBodyTruncator.normalizeTags(tags);
        normalizeAndRecalculate();
        HistoryEntry retained = findStoredEntry(id);
        return retained != null ? HistoryEntry.copyOf(retained) : null;
    }

    public synchronized int clearUnpinned() {
        final int[] removed = {0};
        entries.removeIf(entry -> {
            if (entry != null && !entry.pinned) {
                removed[0]++;
                return true;
            }
            return false;
        });
        if (removed[0] > 0) {
            normalizeAndRecalculate();
        } else {
            recalculateStats();
        }
        return removed[0];
    }

    public synchronized void clear() {
        entries.clear();
        recalculateStats();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    private HistoryEntry findStoredEntry(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (HistoryEntry entry : entries) {
            if (entry != null && Objects.equals(id, entry.id)) {
                return entry;
            }
        }
        return null;
    }

    private void normalizeAndRecalculate() {
        List<HistoryEntry> normalized = normalizeEntries(entries, retentionPolicy);
        entries.clear();
        entries.addAll(normalized);
        recalculateStats();
    }

    private void recalculateStats() {
        retentionStats = calculateStats(entries, retentionPolicy);
    }

    private static HistoryEntry normalizeIncomingEntry(HistoryEntry entry, HistoryRetentionPolicy retentionPolicy) {
        HistoryRetentionPolicy safePolicy = HistoryRetentionPolicy.copyOf(retentionPolicy);
        safePolicy.normalize();
        HistoryEntry copy = HistoryEntry.copyOf(entry);
        if (copy == null) {
            copy = new HistoryEntry();
        }
        copy.ensureDefaults();
        HistoryBodyTruncator.apply(copy, safePolicy);
        copy.ensureDefaults();
        return copy;
    }

    public static List<HistoryEntry> normalizeEntries(Collection<HistoryEntry> sourceEntries, HistoryRetentionPolicy retentionPolicy) {
        List<HistoryEntry> normalized = new ArrayList<>();
        if (sourceEntries == null || sourceEntries.isEmpty()) {
            return normalized;
        }

        HistoryRetentionPolicy safePolicy = HistoryRetentionPolicy.copyOf(retentionPolicy);
        safePolicy.normalize();

        Map<String, HistoryEntry> deduped = new LinkedHashMap<>();
        for (HistoryEntry source : sourceEntries) {
            HistoryEntry copy = normalizeIncomingEntry(source, safePolicy);
            if (copy != null && !deduped.containsKey(copy.id)) {
                deduped.put(copy.id, copy);
            }
        }

        normalized.addAll(deduped.values());
        normalized.sort(Comparator.comparing((HistoryEntry entry) -> entry.timestamp, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        enforceRetention(normalized, safePolicy);
        return normalized;
    }

    private static void enforceRetention(List<HistoryEntry> entries, HistoryRetentionPolicy retentionPolicy) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        HistoryRetentionPolicy safePolicy = HistoryRetentionPolicy.copyOf(retentionPolicy);
        safePolicy.normalize();
        while (exceedsBudget(entries, safePolicy)) {
            int candidate = findOldestEligibleIndex(entries, safePolicy);
            if (candidate < 0) {
                break;
            }
            entries.remove(candidate);
        }
    }

    private static boolean exceedsBudget(List<HistoryEntry> entries, HistoryRetentionPolicy retentionPolicy) {
        if (entries == null) {
            return false;
        }
        int maxEntries = retentionPolicy != null ? Math.max(1, retentionPolicy.maxEntries) : HistoryRetentionPolicy.DEFAULT_MAX_ENTRIES;
        long maxBytes = retentionPolicy != null ? Math.max(1L, retentionPolicy.maxTotalStoredBytes) : HistoryRetentionPolicy.DEFAULT_MAX_TOTAL_STORED_BYTES;
        return entries.size() > maxEntries || totalEstimatedBytes(entries) > maxBytes;
    }

    private static int findOldestEligibleIndex(List<HistoryEntry> entries, HistoryRetentionPolicy retentionPolicy) {
        if (entries == null || entries.isEmpty()) {
            return -1;
        }
        boolean retainPinned = retentionPolicy == null || retentionPolicy.retainPinnedEntries;
        for (int i = entries.size() - 1; i >= 0; i--) {
            HistoryEntry entry = entries.get(i);
            if (entry == null) {
                return i;
            }
            if (!retainPinned || !entry.pinned) {
                return i;
            }
        }
        return -1;
    }

    private static HistoryRetentionStats calculateStats(List<HistoryEntry> entries, HistoryRetentionPolicy retentionPolicy) {
        if (entries == null || entries.isEmpty()) {
            return HistoryRetentionStats.empty();
        }
        long totalBytes = 0L;
        int pinnedCount = 0;
        int truncatedCount = 0;
        for (HistoryEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            totalBytes = safeAdd(totalBytes, Math.max(0L, entry.estimatedStoredBytes()));
            if (entry.pinned) {
                pinnedCount++;
            }
            if ((entry.requestSnapshot != null && (entry.requestSnapshot.bodyTruncated || entry.requestSnapshot.rawBodyTruncated))
                    || (entry.responseSnapshot != null && entry.responseSnapshot.bodyTruncated)) {
                truncatedCount++;
            }
        }
        int maxEntries = retentionPolicy != null ? Math.max(1, retentionPolicy.maxEntries) : HistoryRetentionPolicy.DEFAULT_MAX_ENTRIES;
        long maxBytes = retentionPolicy != null ? Math.max(1L, retentionPolicy.maxTotalStoredBytes) : HistoryRetentionPolicy.DEFAULT_MAX_TOTAL_STORED_BYTES;
        boolean overBudget = entries.size() > maxEntries || totalBytes > maxBytes;
        return new HistoryRetentionStats(entries.size(), totalBytes, pinnedCount, truncatedCount, overBudget);
    }

    private static long totalEstimatedBytes(List<HistoryEntry> entries) {
        long total = 0L;
        if (entries == null) {
            return 0L;
        }
        for (HistoryEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            total = safeAdd(total, Math.max(0L, entry.estimatedStoredBytes()));
        }
        return total;
    }

    private static long safeAdd(long left, long right) {
        if (right <= 0) {
            return left;
        }
        if (left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        long total = left + right;
        return total < 0 ? Long.MAX_VALUE : total;
    }
}
