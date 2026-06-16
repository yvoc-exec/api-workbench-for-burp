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

    public synchronized void setRetentionPolicy(HistoryRetentionPolicy retentionPolicy) {
        this.retentionPolicy = retentionPolicy != null ? retentionPolicy : HistoryRetentionPolicy.defaultPolicy();
        enforceRetention();
    }

    public synchronized HistoryRetentionPolicy getRetentionPolicy() {
        return retentionPolicy;
    }

    public synchronized HistoryEntry addEntry(HistoryEntry entry) {
        HistoryEntry stored = normalizeEntry(entry);
        entries.add(0, stored);
        enforceRetention();
        return HistoryEntry.copyOf(stored);
    }

    public synchronized void addAll(Collection<HistoryEntry> newEntries) {
        if (newEntries == null || newEntries.isEmpty()) {
            return;
        }
        List<HistoryEntry> normalized = normalizeEntries(newEntries, retentionPolicy);
        entries.clear();
        entries.addAll(normalized);
        enforceRetention();
    }

    public synchronized void replaceAll(Collection<HistoryEntry> newEntries) {
        entries.clear();
        if (newEntries != null) {
            entries.addAll(normalizeEntries(newEntries, retentionPolicy));
        }
        enforceRetention();
    }

    public synchronized List<HistoryEntry> snapshot() {
        List<HistoryEntry> copy = new ArrayList<>();
        for (HistoryEntry entry : entries) {
            copy.add(HistoryEntry.copyOf(entry));
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
                selected.add(HistoryEntry.copyOf(entry));
            }
        }
        return selected;
    }

    public synchronized boolean removeById(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return entries.removeIf(entry -> entry != null && Objects.equals(entry.id, id));
    }

    public synchronized List<HistoryEntry> removeByIds(Collection<String> ids) {
        List<HistoryEntry> removed = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return removed;
        }
        entries.removeIf(entry -> {
            if (entry != null && ids.contains(entry.id)) {
                removed.add(HistoryEntry.copyOf(entry));
                return true;
            }
            return false;
        });
        return removed;
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    private void enforceRetention() {
        int maxEntries = retentionPolicy != null ? Math.max(1, retentionPolicy.maxEntries) : 1000;
        while (entries.size() > maxEntries) {
            entries.remove(entries.size() - 1);
        }
    }

    private static HistoryEntry normalizeEntry(HistoryEntry entry) {
        HistoryEntry copy = HistoryEntry.copyOf(entry);
        if (copy == null) {
            copy = new HistoryEntry();
        }
        copy.ensureDefaults();
        if (copy.timestamp == null) {
            copy.timestamp = Instant.now();
        }
        return copy;
    }

    public static List<HistoryEntry> normalizeEntries(Collection<HistoryEntry> entries, HistoryRetentionPolicy retentionPolicy) {
        List<HistoryEntry> normalized = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            return normalized;
        }
        Map<String, HistoryEntry> deduped = new LinkedHashMap<>();
        for (HistoryEntry entry : entries) {
            HistoryEntry copy = normalizeEntry(entry);
            if (!deduped.containsKey(copy.id)) {
                deduped.put(copy.id, copy);
            }
        }
        normalized.addAll(deduped.values());
        normalized.sort(Comparator.comparing((HistoryEntry entry) -> entry.timestamp, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        int maxEntries = retentionPolicy != null ? Math.max(1, retentionPolicy.maxEntries) : 1000;
        if (normalized.size() > maxEntries) {
            normalized = new ArrayList<>(normalized.subList(0, maxEntries));
        }
        return normalized;
    }
}
