package burp.history;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns immutable defensive History copies and enforces the configured limits at
 * every mutation boundary. Logical byte sizes are calculated once when a copy
 * enters the store and are then maintained in {@link #storedSizes}.
 */
public class HistoryStore {
    private static final Comparator<HistoryEntry> NEWEST_FIRST = Comparator
            .comparing((HistoryEntry entry) -> entry.timestamp, Comparator.nullsLast(Comparator.naturalOrder()))
            .reversed();

    private final List<HistoryEntry> entries = new ArrayList<>();
    private final Map<String, Long> storedSizes = new LinkedHashMap<>();
    private HistoryRetentionPolicy retentionPolicy = HistoryRetentionPolicy.defaultPolicy();
    private HistoryRetentionStats retentionStats = HistoryRetentionStats.empty();
    private long canonicalRetainedBytes;
    private long pinnedRetainedBytes;
    private long unpinnedRetainedBytes;
    private int pinnedCount;
    private int truncatedEntryCount;
    private int lastEvictionCount;
    private long rejectedAddCount;
    private int legacyCompactedEntryCount;

    public synchronized HistoryAdmissionResult admitEntry(HistoryEntry entry) {
        long before = canonicalRetainedBytes;
        if (entry == null) {
            return rejectAdd(HistoryAdmissionRejectionReason.INVALID_ENTRY, null);
        }

        HistoryEntry incoming = normalizeIncomingEntry(entry, retentionPolicy, Instant.now());
        long incomingSize = canonicalSize(incoming);
        if (incomingSize > retentionPolicy.maxTotalStoredBytes) {
            return rejectAdd(HistoryAdmissionRejectionReason.ENTRY_EXCEEDS_POLICY, null);
        }

        Map<String, HistoryEntry> incomingById = new LinkedHashMap<>();
        incomingById.put(incoming.id, incoming);
        Map<String, Long> incomingSizes = Map.of(incoming.id, incomingSize);
        AdmissionPlan plan = planAdmission(
                entries,
                storedSizes,
                incomingById,
                incomingSizes,
                Set.of(incoming.id),
                retentionPolicy,
                HistoryAdmissionRejectionReason.PINNED_BUDGET_EXHAUSTED);
        if (!plan.accepted) {
            return rejectAdd(plan.rejectionReason, null);
        }

        commitPlan(plan, retentionPolicy, legacyCompactedEntryCount);
        return HistoryAdmissionResult.success(
                incoming.id,
                1,
                plan.evictions,
                before,
                canonicalRetainedBytes,
                retentionPolicy);
    }

    public synchronized HistoryAdmissionResult admitAll(Collection<HistoryEntry> newEntries) {
        long before = canonicalRetainedBytes;
        if (newEntries == null || newEntries.isEmpty()) {
            setLastEvictionCount(0);
            return HistoryAdmissionResult.success(null, 0, 0, before, before, retentionPolicy);
        }

        PreparedEntries prepared = prepareIncoming(newEntries, retentionPolicy, true);
        if (!prepared.valid) {
            return rejectAdd(HistoryAdmissionRejectionReason.INVALID_ENTRY, null);
        }
        if (prepared.entries.size() > retentionPolicy.maxEntries
                || prepared.totalBytes > retentionPolicy.maxTotalStoredBytes
                || prepared.hasIndividuallyOversizedEntry) {
            return rejectAdd(HistoryAdmissionRejectionReason.ENTRY_EXCEEDS_POLICY, null);
        }

        Set<String> mandatoryIds = new LinkedHashSet<>(prepared.entries.keySet());
        AdmissionPlan plan = planAdmission(
                entries,
                storedSizes,
                prepared.entries,
                prepared.sizes,
                mandatoryIds,
                retentionPolicy,
                HistoryAdmissionRejectionReason.PINNED_BUDGET_EXHAUSTED);
        if (!plan.accepted) {
            return rejectAdd(plan.rejectionReason, null);
        }

        commitPlan(plan, retentionPolicy, legacyCompactedEntryCount);
        return HistoryAdmissionResult.success(
                null,
                prepared.entries.size(),
                plan.evictions,
                before,
                canonicalRetainedBytes,
                retentionPolicy);
    }

    public synchronized HistoryAdmissionResult setPinnedWithResult(String id, boolean pinned) {
        return setPinnedAllWithResult(id != null ? List.of(id) : null, pinned);
    }

    /** Performs a multi-row pin operation as one atomic store mutation. */
    public synchronized HistoryAdmissionResult setPinnedAllWithResult(Collection<String> ids, boolean pinned) {
        long before = canonicalRetainedBytes;
        if (ids == null || ids.isEmpty()) {
            return rejectMutation(HistoryAdmissionRejectionReason.INVALID_ENTRY, null);
        }

        LinkedHashSet<String> normalizedIds = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank() || findStoredEntry(id) == null) {
                return rejectMutation(HistoryAdmissionRejectionReason.INVALID_ENTRY, null);
            }
            normalizedIds.add(id);
        }

        Map<String, HistoryEntry> replacements = new LinkedHashMap<>();
        Map<String, Long> sizes = new LinkedHashMap<>();
        for (String id : normalizedIds) {
            HistoryEntry current = findStoredEntry(id);
            if (current.pinned == pinned) {
                continue;
            }
            HistoryEntry candidate = HistoryEntry.copyOf(current);
            candidate.pinned = pinned;
            long size = canonicalSize(candidate);
            replacements.put(id, candidate);
            sizes.put(id, size);
        }
        if (replacements.isEmpty()) {
            setLastEvictionCount(0);
            return HistoryAdmissionResult.success(
                    normalizedIds.size() == 1 ? normalizedIds.iterator().next() : null,
                    normalizedIds.size(),
                    0,
                    before,
                    before,
                    retentionPolicy);
        }

        AdmissionPlan plan = planAdmission(
                entries,
                storedSizes,
                replacements,
                sizes,
                replacements.keySet(),
                retentionPolicy,
                HistoryAdmissionRejectionReason.PINNED_BUDGET_EXHAUSTED);
        if (!plan.accepted) {
            return rejectMutation(plan.rejectionReason, normalizedIds.size() == 1 ? normalizedIds.iterator().next() : null);
        }

        commitPlan(plan, retentionPolicy, legacyCompactedEntryCount);
        return HistoryAdmissionResult.success(
                normalizedIds.size() == 1 ? normalizedIds.iterator().next() : null,
                normalizedIds.size(),
                plan.evictions,
                before,
                canonicalRetainedBytes,
                retentionPolicy);
    }

    public synchronized HistoryAdmissionResult updateEvidenceMetadataWithResult(String id,
                                                                                 boolean pinned,
                                                                                 String notes,
                                                                                 Collection<String> tags) {
        long before = canonicalRetainedBytes;
        HistoryEntry current = findStoredEntry(id);
        if (current == null) {
            return rejectMutation(HistoryAdmissionRejectionReason.INVALID_ENTRY, null);
        }

        HistoryEntry candidate = HistoryEntry.copyOf(current);
        candidate.pinned = pinned;
        candidate.analystNotes = notes != null ? HistorySanitizer.safeMultiline(notes) : "";
        candidate.tags = HistoryBodyTruncator.normalizeTags(tags);
        long candidateSize = canonicalSize(candidate);
        if (candidateSize > retentionPolicy.maxTotalStoredBytes) {
            return rejectMutation(HistoryAdmissionRejectionReason.ENTRY_EXCEEDS_POLICY, id);
        }

        AdmissionPlan plan = planAdmission(
                entries,
                storedSizes,
                Map.of(id, candidate),
                Map.of(id, candidateSize),
                Set.of(id),
                retentionPolicy,
                HistoryAdmissionRejectionReason.PINNED_BUDGET_EXHAUSTED);
        if (!plan.accepted) {
            return rejectMutation(plan.rejectionReason, id);
        }

        commitPlan(plan, retentionPolicy, legacyCompactedEntryCount);
        return HistoryAdmissionResult.success(
                id,
                1,
                plan.evictions,
                before,
                canonicalRetainedBytes,
                retentionPolicy);
    }

    public synchronized HistoryAdmissionResult applyRetentionPolicy(HistoryRetentionPolicy requestedPolicy) {
        long before = canonicalRetainedBytes;
        HistoryRetentionPolicy candidatePolicy = HistoryRetentionPolicy.copyOf(requestedPolicy);
        PreparedEntries prepared = prepareIncoming(entries, candidatePolicy, false);
        if (!prepared.valid) {
            return rejectMutation(HistoryAdmissionRejectionReason.POLICY_REJECTED, null);
        }

        AdmissionPlan plan = planReplacement(
                prepared,
                candidatePolicy,
                false,
                HistoryAdmissionRejectionReason.POLICY_REJECTED);
        if (!plan.accepted) {
            return rejectMutation(HistoryAdmissionRejectionReason.POLICY_REJECTED, null);
        }

        commitPlan(plan, candidatePolicy, legacyCompactedEntryCount);
        return HistoryAdmissionResult.success(
                null,
                entries.size(),
                plan.evictions,
                before,
                canonicalRetainedBytes,
                retentionPolicy);
    }

    public synchronized HistoryAdmissionResult replaceAllWithResult(Collection<HistoryEntry> newEntries) {
        return replaceAllInternal(newEntries, retentionPolicy, false, false, -1);
    }

    public synchronized HistoryAdmissionResult restoreAll(Collection<HistoryEntry> restoredEntries,
                                                           boolean legacyMigration) {
        return restoreAll(restoredEntries, retentionPolicy, legacyMigration);
    }

    /** Atomically applies both a restored policy and its entries. */
    public synchronized HistoryAdmissionResult restoreAll(Collection<HistoryEntry> restoredEntries,
                                                           HistoryRetentionPolicy restoredPolicy,
                                                           boolean legacyMigration) {
        return replaceAllInternal(restoredEntries, restoredPolicy, legacyMigration, true, -1);
    }

    /**
     * Restores a state already migrated by WorkspaceStateJson while retaining
     * the one-time compaction count for the current load notification.
     */
    public synchronized HistoryAdmissionResult restoreAll(Collection<HistoryEntry> restoredEntries,
                                                           HistoryRetentionPolicy restoredPolicy,
                                                           boolean legacyMigration,
                                                           int legacyCompactedCountOverride) {
        return replaceAllInternal(
                restoredEntries,
                restoredPolicy,
                legacyMigration,
                true,
                Math.max(0, legacyCompactedCountOverride));
    }

    private HistoryAdmissionResult replaceAllInternal(Collection<HistoryEntry> newEntries,
                                                      HistoryRetentionPolicy requestedPolicy,
                                                      boolean legacyMigration,
                                                      boolean restore,
                                                      int legacyCompactedCountOverride) {
        long before = canonicalRetainedBytes;
        HistoryRetentionPolicy candidatePolicy = HistoryRetentionPolicy.copyOf(requestedPolicy);
        PreparedEntries prepared = prepareIncoming(newEntries, candidatePolicy, false);
        if (!prepared.valid) {
            return rejectMutation(HistoryAdmissionRejectionReason.INVALID_ENTRY, null);
        }

        AdmissionPlan plan = legacyMigration
                ? planLegacyRestore(prepared, candidatePolicy)
                : planReplacement(prepared, candidatePolicy, false, HistoryAdmissionRejectionReason.POLICY_REJECTED);
        if (!plan.accepted) {
            return rejectMutation(plan.rejectionReason, null);
        }

        int compacted = legacyCompactedCountOverride >= 0
                ? legacyCompactedCountOverride
                : legacyMigration ? plan.legacyCompactedCount : 0;
        commitPlan(plan, candidatePolicy, restore ? compacted : 0);
        return HistoryAdmissionResult.success(
                null,
                plan.finalEntries.size(),
                plan.evictions,
                before,
                canonicalRetainedBytes,
                retentionPolicy);
    }

    /* Compatibility wrappers. */

    public synchronized HistoryEntry addEntry(HistoryEntry entry) {
        HistoryAdmissionResult result = admitEntry(entry);
        return result.accepted() ? getById(result.storedEntryId()) : null;
    }

    public synchronized void addAll(Collection<HistoryEntry> newEntries) {
        admitAll(newEntries);
    }

    public synchronized void setRetentionPolicy(HistoryRetentionPolicy policy) {
        applyRetentionPolicy(policy);
    }

    public synchronized void replaceAll(Collection<HistoryEntry> newEntries) {
        replaceAllWithResult(newEntries);
    }

    public synchronized boolean setPinned(String id, boolean pinned) {
        return setPinnedWithResult(id, pinned).accepted();
    }

    public synchronized HistoryEntry updateAnalystMetadata(String id, String notes, Collection<String> tags) {
        HistoryEntry current = getById(id);
        if (current == null) {
            return null;
        }
        return updateEvidenceMetadata(id, current.pinned, notes, tags);
    }

    public synchronized HistoryEntry updateEvidenceMetadata(String id,
                                                            boolean pinned,
                                                            String notes,
                                                            Collection<String> tags) {
        HistoryAdmissionResult result = updateEvidenceMetadataWithResult(id, pinned, notes, tags);
        return result.accepted() ? getById(id) : null;
    }

    public synchronized HistoryRetentionPolicy getRetentionPolicy() {
        return HistoryRetentionPolicy.copyOf(retentionPolicy);
    }

    public synchronized HistoryRetentionStats getRetentionStats() {
        return retentionStats;
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
        HistoryEntry entry = findStoredEntry(id);
        return HistoryEntry.copyOf(entry);
    }

    public synchronized List<HistoryEntry> getByIds(Collection<String> ids) {
        List<HistoryEntry> selected = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return selected;
        }
        Set<String> requested = new HashSet<>(ids);
        for (HistoryEntry entry : entries) {
            if (entry != null && requested.contains(entry.id)) {
                selected.add(HistoryEntry.copyOf(entry));
            }
        }
        return selected;
    }

    public synchronized boolean removeById(String id) {
        if (id == null || id.isBlank()) {
            setLastEvictionCount(0);
            return false;
        }
        for (Iterator<HistoryEntry> iterator = entries.iterator(); iterator.hasNext(); ) {
            HistoryEntry entry = iterator.next();
            if (entry != null && Objects.equals(id, entry.id)) {
                iterator.remove();
                removeAccounting(entry);
                setLastEvictionCount(0);
                verifyCachedInvariant();
                return true;
            }
        }
        setLastEvictionCount(0);
        return false;
    }

    public synchronized List<HistoryEntry> removeByIds(Collection<String> ids) {
        List<HistoryEntry> removed = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            setLastEvictionCount(0);
            return removed;
        }
        Set<String> requested = new HashSet<>(ids);
        for (Iterator<HistoryEntry> iterator = entries.iterator(); iterator.hasNext(); ) {
            HistoryEntry entry = iterator.next();
            if (entry != null && requested.contains(entry.id)) {
                removed.add(HistoryEntry.copyOf(entry));
                iterator.remove();
                removeAccounting(entry);
            }
        }
        setLastEvictionCount(0);
        if (!removed.isEmpty()) {
            verifyCachedInvariant();
        }
        return removed;
    }

    public synchronized int clearUnpinned() {
        int removed = 0;
        for (Iterator<HistoryEntry> iterator = entries.iterator(); iterator.hasNext(); ) {
            HistoryEntry entry = iterator.next();
            if (entry != null && !entry.pinned) {
                iterator.remove();
                removeAccounting(entry);
                removed++;
            }
        }
        setLastEvictionCount(0);
        verifyCachedInvariant();
        return removed;
    }

    public synchronized void clear() {
        entries.clear();
        storedSizes.clear();
        canonicalRetainedBytes = 0L;
        pinnedRetainedBytes = 0L;
        unpinnedRetainedBytes = 0L;
        pinnedCount = 0;
        truncatedEntryCount = 0;
        lastEvictionCount = 0;
        refreshStats();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Compatibility normalization helper. Current state is always bounded; an
     * impossible protected-pinned set is rejected rather than exposed as an
     * accepted over-budget state.
     */
    public static List<HistoryEntry> normalizeEntries(Collection<HistoryEntry> sourceEntries,
                                                      HistoryRetentionPolicy policy) {
        HistoryStore temporary = new HistoryStore();
        HistoryAdmissionResult result = temporary.restoreAll(sourceEntries, policy, false);
        if (!result.accepted()) {
            throw new IllegalStateException("History entries cannot satisfy the configured retention policy");
        }
        return temporary.snapshot();
    }

    private AdmissionPlan planAdmission(List<HistoryEntry> baseEntries,
                                        Map<String, Long> baseSizes,
                                        Map<String, HistoryEntry> incoming,
                                        Map<String, Long> incomingSizes,
                                        Collection<String> mandatoryIds,
                                        HistoryRetentionPolicy policy,
                                        HistoryAdmissionRejectionReason blockedReason) {
        List<HistoryEntry> candidate = new ArrayList<>(incoming.size() + baseEntries.size());
        Map<String, Long> candidateSizes = new LinkedHashMap<>();
        Accounting accounting = new Accounting(
                canonicalRetainedBytes,
                pinnedRetainedBytes,
                unpinnedRetainedBytes,
                pinnedCount,
                truncatedEntryCount);

        for (HistoryEntry entry : incoming.values()) {
            candidate.add(entry);
            Long size = incomingSizes.get(entry.id);
            long resolvedSize = size != null ? size : canonicalSize(entry);
            candidateSizes.put(entry.id, resolvedSize);
            accounting.add(entry, resolvedSize);
        }
        for (HistoryEntry entry : baseEntries) {
            if (entry == null) {
                continue;
            }
            Long size = baseSizes.get(entry.id);
            long resolvedSize = size != null ? size : canonicalSize(entry);
            if (incoming.containsKey(entry.id)) {
                accounting.remove(entry, resolvedSize);
            } else {
                candidate.add(entry);
                candidateSizes.put(entry.id, resolvedSize);
            }
        }
        candidate.sort(NEWEST_FIRST);

        Set<String> protectedIds = mandatoryIds != null ? new HashSet<>(mandatoryIds) : Set.of();
        long mandatoryBytes = 0L;
        int mandatoryCount = 0;
        for (String id : protectedIds) {
            Long size = candidateSizes.get(id);
            if (size != null) {
                mandatoryBytes = safeAdd(mandatoryBytes, size);
                mandatoryCount++;
            }
        }
        if (mandatoryCount > policy.maxEntries || mandatoryBytes > policy.maxTotalStoredBytes) {
            return AdmissionPlan.rejected(HistoryAdmissionRejectionReason.ENTRY_EXCEEDS_POLICY);
        }

        return evictToFitIncremental(
                candidate,
                candidateSizes,
                protectedIds,
                policy,
                blockedReason,
                accounting);
    }

    private AdmissionPlan evictToFitIncremental(List<HistoryEntry> candidate,
                                                Map<String, Long> sizes,
                                                Set<String> protectedIds,
                                                HistoryRetentionPolicy policy,
                                                HistoryAdmissionRejectionReason blockedReason,
                                                Accounting accounting) {
        int evictions = 0;
        while (candidate.size() > policy.maxEntries
                || accounting.canonicalBytes > policy.maxTotalStoredBytes) {
            int evictionIndex = findOldestEligibleIndex(candidate, protectedIds, policy);
            if (evictionIndex < 0) {
                return AdmissionPlan.rejected(blockedReason);
            }
            HistoryEntry removed = candidate.remove(evictionIndex);
            Long cached = sizes.remove(removed.id);
            accounting.remove(removed, cached != null ? cached : 0L);
            evictions++;
        }
        return AdmissionPlan.acceptedIncremental(candidate, sizes, evictions, accounting);
    }

    private AdmissionPlan planReplacement(PreparedEntries prepared,
                                          HistoryRetentionPolicy policy,
                                          boolean protectAll,
                                          HistoryAdmissionRejectionReason blockedReason) {
        Set<String> protectedIds = protectAll ? prepared.entries.keySet() : Set.of();
        return evictToFit(
                new ArrayList<>(prepared.entries.values()),
                new LinkedHashMap<>(prepared.sizes),
                protectedIds,
                policy,
                blockedReason,
                0);
    }

    private AdmissionPlan planLegacyRestore(PreparedEntries prepared, HistoryRetentionPolicy policy) {
        List<HistoryEntry> candidate = new ArrayList<>(prepared.entries.values());
        Map<String, Long> sizes = new LinkedHashMap<>(prepared.sizes);
        candidate.sort(NEWEST_FIRST);
        int evictions = 0;

        while (candidate.size() > policy.maxEntries || totalBytes(sizes) > policy.maxTotalStoredBytes) {
            int evictionIndex = findOldestEligibleIndex(candidate, Set.of(), policy);
            if (evictionIndex < 0) {
                break;
            }
            HistoryEntry removed = candidate.remove(evictionIndex);
            sizes.remove(removed.id);
            evictions++;
        }

        if (candidate.size() > policy.maxEntries) {
            return AdmissionPlan.rejected(HistoryAdmissionRejectionReason.POLICY_REJECTED);
        }
        if (totalBytes(sizes) <= policy.maxTotalStoredBytes) {
            return AdmissionPlan.accepted(candidate, sizes, evictions, 0);
        }

        Set<String> compactedIds = new HashSet<>();
        long retainedTotal = totalBytes(sizes);
        for (int i = candidate.size() - 1; i >= 0 && retainedTotal > policy.maxTotalStoredBytes; i--) {
            HistoryEntry entry = candidate.get(i);
            if (!entry.pinned) {
                continue;
            }
            long previousSize = sizes.getOrDefault(entry.id, 0L);
            long excess = retainedTotal - policy.maxTotalStoredBytes;
            long targetEntryBytes = safeSubtract(previousSize, excess);
            if (HistoryBodyTruncator.compactLegacyPayloads(entry, 4L * 1024L, targetEntryBytes)) {
                long compactedSize = canonicalSize(entry);
                sizes.put(entry.id, compactedSize);
                retainedTotal = safeAdd(safeSubtract(retainedTotal, previousSize), compactedSize);
                compactedIds.add(entry.id);
            }
        }
        for (int i = candidate.size() - 1; i >= 0 && retainedTotal > policy.maxTotalStoredBytes; i--) {
            HistoryEntry entry = candidate.get(i);
            if (!entry.pinned) {
                continue;
            }
            long previousSize = sizes.getOrDefault(entry.id, 0L);
            long excess = retainedTotal - policy.maxTotalStoredBytes;
            long targetEntryBytes = safeSubtract(previousSize, excess);
            if (HistoryBodyTruncator.compactLegacyPayloads(entry, 0L, targetEntryBytes)) {
                long compactedSize = canonicalSize(entry);
                sizes.put(entry.id, compactedSize);
                retainedTotal = safeAdd(safeSubtract(retainedTotal, previousSize), compactedSize);
                compactedIds.add(entry.id);
            }
        }

        if (retainedTotal > policy.maxTotalStoredBytes) {
            return AdmissionPlan.rejected(HistoryAdmissionRejectionReason.POLICY_REJECTED);
        }
        return AdmissionPlan.accepted(candidate, sizes, evictions, compactedIds.size());
    }

    private AdmissionPlan evictToFit(List<HistoryEntry> candidate,
                                     Map<String, Long> sizes,
                                     Set<String> protectedIds,
                                     HistoryRetentionPolicy policy,
                                     HistoryAdmissionRejectionReason blockedReason,
                                     int legacyCompactedCount) {
        int evictions = 0;
        long total = totalBytes(sizes);
        while (candidate.size() > policy.maxEntries || total > policy.maxTotalStoredBytes) {
            int evictionIndex = findOldestEligibleIndex(candidate, protectedIds, policy);
            if (evictionIndex < 0) {
                return AdmissionPlan.rejected(blockedReason);
            }
            HistoryEntry removed = candidate.remove(evictionIndex);
            long removedSize = sizes.getOrDefault(removed.id, 0L);
            sizes.remove(removed.id);
            total = safeSubtract(total, removedSize);
            evictions++;
        }
        return AdmissionPlan.accepted(candidate, sizes, evictions, legacyCompactedCount);
    }

    private static int findOldestEligibleIndex(List<HistoryEntry> candidate,
                                               Set<String> protectedIds,
                                               HistoryRetentionPolicy policy) {
        for (int i = candidate.size() - 1; i >= 0; i--) {
            HistoryEntry entry = candidate.get(i);
            if (entry == null || protectedIds.contains(entry.id)) {
                continue;
            }
            if (!policy.retainPinnedEntries || !entry.pinned) {
                return i;
            }
        }
        return -1;
    }

    private PreparedEntries prepareIncoming(Collection<HistoryEntry> source,
                                            HistoryRetentionPolicy policy,
                                            boolean rejectNulls) {
        if (source == null || source.isEmpty()) {
            return PreparedEntries.empty();
        }
        Instant operationTimestamp = Instant.now();
        Map<String, HistoryEntry> prepared = new LinkedHashMap<>();
        Map<String, Long> sizes = new LinkedHashMap<>();
        boolean individuallyOversized = false;
        long total = 0L;
        for (HistoryEntry original : source) {
            if (original == null) {
                if (rejectNulls) {
                    return PreparedEntries.invalid();
                }
                continue;
            }
            HistoryEntry copy = normalizeIncomingEntry(original, policy, operationTimestamp);
            if (prepared.containsKey(copy.id)) {
                continue;
            }
            long size = canonicalSize(copy);
            individuallyOversized |= size > policy.maxTotalStoredBytes;
            prepared.put(copy.id, copy);
            sizes.put(copy.id, size);
            total = safeAdd(total, size);
        }
        List<HistoryEntry> sorted = new ArrayList<>(prepared.values());
        sorted.sort(NEWEST_FIRST);
        prepared.clear();
        for (HistoryEntry entry : sorted) {
            prepared.put(entry.id, entry);
        }
        return new PreparedEntries(true, prepared, sizes, total, individuallyOversized);
    }

    private static HistoryEntry normalizeIncomingEntry(HistoryEntry source,
                                                       HistoryRetentionPolicy policy,
                                                       Instant operationTimestamp) {
        HistoryEntry copy = HistoryEntry.copyOf(source);
        if (copy.timestamp == null) {
            copy.timestamp = operationTimestamp;
        }
        copy.ensureDefaults();
        HistoryBodyTruncator.apply(copy, policy);
        copy.ensureDefaults();
        return copy;
    }

    private void commitPlan(AdmissionPlan plan,
                            HistoryRetentionPolicy policy,
                            int legacyCompactedCount) {
        entries.clear();
        entries.addAll(plan.finalEntries);
        storedSizes.clear();
        storedSizes.putAll(plan.sizes);
        retentionPolicy = HistoryRetentionPolicy.copyOf(policy);
        this.legacyCompactedEntryCount = Math.max(0, legacyCompactedCount);
        lastEvictionCount = plan.evictions;

        // The plan calculated these scalars from cached entry sizes. Commit
        // never re-normalizes, deep-copies, or re-estimates retained entries.
        canonicalRetainedBytes = plan.canonicalBytes;
        pinnedRetainedBytes = plan.pinnedBytes;
        unpinnedRetainedBytes = plan.unpinnedBytes;
        pinnedCount = plan.pinnedCount;
        truncatedEntryCount = plan.truncatedCount;
        refreshStats();
        verifyCachedInvariant();
    }

    private void removeAccounting(HistoryEntry entry) {
        Long cachedSize = storedSizes.get(entry.id);
        long size = cachedSize != null ? cachedSize : canonicalSize(entry);
        storedSizes.remove(entry.id);
        canonicalRetainedBytes = safeSubtract(canonicalRetainedBytes, size);
        if (entry.pinned) {
            pinnedRetainedBytes = safeSubtract(pinnedRetainedBytes, size);
            pinnedCount = Math.max(0, pinnedCount - 1);
        } else {
            unpinnedRetainedBytes = safeSubtract(unpinnedRetainedBytes, size);
        }
        if (entry.hasTruncatedEvidence()) {
            truncatedEntryCount = Math.max(0, truncatedEntryCount - 1);
        }
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

    private HistoryAdmissionResult rejectAdd(HistoryAdmissionRejectionReason reason, String existingId) {
        rejectedAddCount = safeAdd(rejectedAddCount, 1L);
        lastEvictionCount = 0;
        refreshStats();
        return HistoryAdmissionResult.rejection(reason, existingId, canonicalRetainedBytes, retentionPolicy);
    }

    private HistoryAdmissionResult rejectMutation(HistoryAdmissionRejectionReason reason, String existingId) {
        lastEvictionCount = 0;
        refreshStats();
        return HistoryAdmissionResult.rejection(reason, existingId, canonicalRetainedBytes, retentionPolicy);
    }

    private void setLastEvictionCount(int count) {
        lastEvictionCount = Math.max(0, count);
        refreshStats();
    }

    private void refreshStats() {
        boolean overBudget = entries.size() > retentionPolicy.maxEntries
                || canonicalRetainedBytes > retentionPolicy.maxTotalStoredBytes;
        retentionStats = new HistoryRetentionStats(
                entries.size(),
                canonicalRetainedBytes,
                pinnedCount,
                truncatedEntryCount,
                overBudget,
                canonicalRetainedBytes,
                pinnedRetainedBytes,
                unpinnedRetainedBytes,
                lastEvictionCount,
                rejectedAddCount,
                legacyCompactedEntryCount);
    }

    private void verifyCachedInvariant() {
        boolean mismatch = storedSizes.size() != entries.size()
                || safeAdd(pinnedRetainedBytes, unpinnedRetainedBytes) != canonicalRetainedBytes
                || canonicalRetainedBytes > retentionPolicy.maxTotalStoredBytes
                || entries.size() > retentionPolicy.maxEntries
                || !hasValidIdentityAndOrdering();
        if (mismatch) {
            recalculateFromLiveEntries();
        }
        if (canonicalRetainedBytes > retentionPolicy.maxTotalStoredBytes
                || entries.size() > retentionPolicy.maxEntries
                || safeAdd(pinnedRetainedBytes, unpinnedRetainedBytes) != canonicalRetainedBytes
                || !hasValidIdentityAndOrdering()) {
            throw new IllegalStateException("History retention invariant could not be satisfied");
        }
    }

    private boolean hasValidIdentityAndOrdering() {
        Set<String> ids = new HashSet<>();
        HistoryEntry previous = null;
        for (HistoryEntry entry : entries) {
            if (entry == null || entry.id == null || entry.id.isBlank()
                    || !ids.add(entry.id) || !storedSizes.containsKey(entry.id)) {
                return false;
            }
            if (previous != null && NEWEST_FIRST.compare(previous, entry) > 0) {
                return false;
            }
            previous = entry;
        }
        return ids.size() == storedSizes.size();
    }

    /** Used only after a detected accounting mismatch or an explicitly allowed full-state operation. */
    private void recalculateFromLiveEntries() {
        storedSizes.clear();
        canonicalRetainedBytes = 0L;
        pinnedRetainedBytes = 0L;
        unpinnedRetainedBytes = 0L;
        pinnedCount = 0;
        truncatedEntryCount = 0;
        Set<String> ids = new HashSet<>();
        for (HistoryEntry entry : entries) {
            if (entry == null || entry.id == null || !ids.add(entry.id)) {
                throw new IllegalStateException("History retention invariant contains an invalid entry identifier");
            }
            long size = canonicalSize(entry);
            storedSizes.put(entry.id, size);
            canonicalRetainedBytes = safeAdd(canonicalRetainedBytes, size);
            if (entry.pinned) {
                pinnedCount++;
                pinnedRetainedBytes = safeAdd(pinnedRetainedBytes, size);
            } else {
                unpinnedRetainedBytes = safeAdd(unpinnedRetainedBytes, size);
            }
            if (entry.hasTruncatedEvidence()) {
                truncatedEntryCount++;
            }
        }
        refreshStats();
    }

    private static long canonicalSize(HistoryEntry entry) {
        return entry != null ? Math.max(0L, entry.estimatedStoredBytes()) : 0L;
    }

    private static long totalBytes(Map<String, Long> sizes) {
        long total = 0L;
        for (Long size : sizes.values()) {
            total = safeAdd(total, size != null ? Math.max(0L, size) : 0L);
        }
        return total;
    }

    private static long safeAdd(long left, long right) {
        if (left < 0L) {
            left = 0L;
        }
        if (right <= 0L) {
            return left;
        }
        if (left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long safeSubtract(long value, long amount) {
        if (value <= 0L || amount >= value) {
            return 0L;
        }
        return amount <= 0L ? value : value - amount;
    }

    private record PreparedEntries(boolean valid,
                                   Map<String, HistoryEntry> entries,
                                   Map<String, Long> sizes,
                                   long totalBytes,
                                   boolean hasIndividuallyOversizedEntry) {
        private static PreparedEntries empty() {
            return new PreparedEntries(true, new LinkedHashMap<>(), new LinkedHashMap<>(), 0L, false);
        }

        private static PreparedEntries invalid() {
            return new PreparedEntries(false, Map.of(), Map.of(), 0L, false);
        }
    }

    private static final class Accounting {
        private long canonicalBytes;
        private long pinnedBytes;
        private long unpinnedBytes;
        private int pinnedCount;
        private int truncatedCount;

        private Accounting(long canonicalBytes,
                           long pinnedBytes,
                           long unpinnedBytes,
                           int pinnedCount,
                           int truncatedCount) {
            this.canonicalBytes = canonicalBytes;
            this.pinnedBytes = pinnedBytes;
            this.unpinnedBytes = unpinnedBytes;
            this.pinnedCount = pinnedCount;
            this.truncatedCount = truncatedCount;
        }

        private void add(HistoryEntry entry, long size) {
            canonicalBytes = safeAdd(canonicalBytes, size);
            if (entry.pinned) {
                pinnedBytes = safeAdd(pinnedBytes, size);
                pinnedCount++;
            } else {
                unpinnedBytes = safeAdd(unpinnedBytes, size);
            }
            if (entry.hasTruncatedEvidence()) {
                truncatedCount++;
            }
        }

        private void remove(HistoryEntry entry, long size) {
            canonicalBytes = safeSubtract(canonicalBytes, size);
            if (entry.pinned) {
                pinnedBytes = safeSubtract(pinnedBytes, size);
                pinnedCount = Math.max(0, pinnedCount - 1);
            } else {
                unpinnedBytes = safeSubtract(unpinnedBytes, size);
            }
            if (entry.hasTruncatedEvidence()) {
                truncatedCount = Math.max(0, truncatedCount - 1);
            }
        }
    }

    private static final class AdmissionPlan {
        private final boolean accepted;
        private final List<HistoryEntry> finalEntries;
        private final Map<String, Long> sizes;
        private final int evictions;
        private final int legacyCompactedCount;
        private final HistoryAdmissionRejectionReason rejectionReason;
        private final long canonicalBytes;
        private final long pinnedBytes;
        private final long unpinnedBytes;
        private final int pinnedCount;
        private final int truncatedCount;

        private AdmissionPlan(boolean accepted,
                              List<HistoryEntry> finalEntries,
                              Map<String, Long> sizes,
                              int evictions,
                              int legacyCompactedCount,
                              HistoryAdmissionRejectionReason rejectionReason,
                              long canonicalBytes,
                              long pinnedBytes,
                              long unpinnedBytes,
                              int pinnedCount,
                              int truncatedCount) {
            this.accepted = accepted;
            this.finalEntries = finalEntries;
            this.sizes = sizes;
            this.evictions = evictions;
            this.legacyCompactedCount = legacyCompactedCount;
            this.rejectionReason = rejectionReason;
            this.canonicalBytes = canonicalBytes;
            this.pinnedBytes = pinnedBytes;
            this.unpinnedBytes = unpinnedBytes;
            this.pinnedCount = pinnedCount;
            this.truncatedCount = truncatedCount;
        }

        private static AdmissionPlan accepted(List<HistoryEntry> finalEntries,
                                              Map<String, Long> sizes,
                                              int evictions,
                                              int legacyCompactedCount) {
            long total = 0L;
            long pinnedBytes = 0L;
            long unpinnedBytes = 0L;
            int pinnedCount = 0;
            int truncatedCount = 0;
            for (HistoryEntry entry : finalEntries) {
                Long cached = sizes.get(entry.id);
                long size = cached != null ? cached : 0L;
                total = safeAdd(total, size);
                if (entry.pinned) {
                    pinnedCount++;
                    pinnedBytes = safeAdd(pinnedBytes, size);
                } else {
                    unpinnedBytes = safeAdd(unpinnedBytes, size);
                }
                if (entry.hasTruncatedEvidence()) {
                    truncatedCount++;
                }
            }
            return new AdmissionPlan(
                    true,
                    finalEntries,
                    sizes,
                    evictions,
                    legacyCompactedCount,
                    null,
                    total,
                    pinnedBytes,
                    unpinnedBytes,
                    pinnedCount,
                    truncatedCount);
        }

        private static AdmissionPlan acceptedIncremental(List<HistoryEntry> finalEntries,
                                                         Map<String, Long> sizes,
                                                         int evictions,
                                                         Accounting accounting) {
            return new AdmissionPlan(
                    true,
                    finalEntries,
                    sizes,
                    evictions,
                    0,
                    null,
                    accounting.canonicalBytes,
                    accounting.pinnedBytes,
                    accounting.unpinnedBytes,
                    accounting.pinnedCount,
                    accounting.truncatedCount);
        }

        private static AdmissionPlan rejected(HistoryAdmissionRejectionReason reason) {
            return new AdmissionPlan(false, List.of(), Map.of(), 0, 0, reason, 0L, 0L, 0L, 0, 0);
        }
    }
}
