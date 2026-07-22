package burp.history;

import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryHardBudgetAdmissionTest {

    @Test
    void entryThatFitsIsAcceptedAndAccountingIsBounded() {
        HistoryEntry entry = entry("fits", 1, false, "small");
        long size = canonicalSize(entry);
        HistoryStore store = storeWithPolicy(10, size);

        HistoryAdmissionResult result = store.admitEntry(entry);

        assertThat(result.accepted()).isTrue();
        assertThat(result.storedEntryId()).isEqualTo("fits");
        assertThat(result.entriesAccepted()).isEqualTo(1);
        assertThat(result.entriesEvicted()).isZero();
        assertThat(store.getRetentionStats().canonicalRetainedBytes()).isEqualTo(size);
        assertBounded(store);
    }

    @Test
    void countAdmissionEvictsOldestEligibleEntryAndKeepsNewestFirst() {
        HistoryStore store = storeWithPolicy(2, 10_000_000L);
        store.admitEntry(entry("old", 1, false, "a"));
        store.admitEntry(entry("middle", 2, false, "b"));

        HistoryAdmissionResult result = store.admitEntry(entry("new", 3, false, "c"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.entriesEvicted()).isEqualTo(1);
        assertThat(store.snapshot()).extracting(value -> value.id).containsExactly("new", "middle");
        assertThat(store.getById("old")).isNull();
        assertBounded(store);
    }

    @Test
    void byteAdmissionEvictsOldestEligibleEntry() {
        HistoryEntry old = entry("old", 1, false, "a".repeat(200));
        HistoryEntry middle = entry("middle", 2, false, "b".repeat(200));
        HistoryEntry incoming = entry("new", 3, false, "c".repeat(200));
        long limit = Math.max(
                canonicalSize(old) + canonicalSize(middle),
                canonicalSize(middle) + canonicalSize(incoming));
        HistoryStore store = storeWithPolicy(10, limit);
        assertThat(store.admitEntry(old).accepted()).isTrue();
        assertThat(store.admitEntry(middle).accepted()).isTrue();

        HistoryAdmissionResult result = store.admitEntry(incoming);

        assertThat(result.accepted()).isTrue();
        assertThat(result.entriesEvicted()).isGreaterThanOrEqualTo(1);
        assertThat(store.getById("old")).isNull();
        assertThat(store.getById("new")).isNotNull();
        assertBounded(store);
    }

    @Test
    void pinnedOldestSurvivesAndOldestUnpinnedIsEvictedFirst() {
        HistoryStore store = storeWithPolicy(2, 10_000_000L);
        store.admitEntry(entry("pinned-old", 1, true, "pinned"));
        store.admitEntry(entry("unpinned-middle", 2, false, "middle"));

        HistoryAdmissionResult result = store.admitEntry(entry("new", 3, false, "new"));

        assertThat(result.accepted()).isTrue();
        assertThat(store.snapshot()).extracting(value -> value.id)
                .containsExactly("new", "pinned-old");
        assertBounded(store);
    }

    @Test
    void pinnedBudgetExhaustionRejectsWithoutEvictionOrMutation() {
        HistoryEntry pinned = entry("pinned", 1, true, "p".repeat(100));
        HistoryEntry incoming = entry("incoming", 2, false, "i".repeat(100));
        long limit = canonicalSize(pinned) + canonicalSize(incoming) - 1L;
        HistoryStore store = storeWithPolicy(10, limit);
        assertThat(store.admitEntry(pinned).accepted()).isTrue();
        List<HistoryEntry> before = store.snapshot();
        long bytesBefore = store.getRetentionStats().canonicalRetainedBytes();

        HistoryAdmissionResult result = store.admitEntry(incoming);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(HistoryAdmissionRejectionReason.PINNED_BUDGET_EXHAUSTED);
        assertThat(result.entriesEvicted()).isZero();
        assertThat(store.snapshot()).usingRecursiveComparison().isEqualTo(before);
        assertThat(store.getRetentionStats().canonicalRetainedBytes()).isEqualTo(bytesBefore);
        assertThat(store.getRetentionStats().rejectedAddCount()).isEqualTo(1);
        assertThat(store.getRetentionStats().lastEvictionCount()).isZero();
        assertBounded(store);
    }

    @Test
    void entryLargerThanEntirePolicyIsRejected() {
        HistoryEntry incoming = entry("oversized", 1, false, "x".repeat(400));
        HistoryStore store = storeWithPolicy(10, canonicalSize(incoming) - 1L);

        HistoryAdmissionResult result = store.admitEntry(incoming);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(HistoryAdmissionRejectionReason.ENTRY_EXCEEDS_POLICY);
        assertThat(store.snapshot()).isEmpty();
        assertBounded(store);
    }

    @Test
    void duplicateIdAtomicallyReplacesOldRecord() {
        HistoryStore store = storeWithPolicy(10, 10_000_000L);
        store.admitEntry(entry("same", 1, false, "old"));

        HistoryAdmissionResult result = store.admitEntry(entry("same", 2, false, "replacement"));

        assertThat(result.accepted()).isTrue();
        assertThat(store.snapshot()).hasSize(1);
        assertThat(store.getById("same").analystNotes).isEqualTo("replacement");
        assertThat(store.snapshot().get(0).timestamp).isEqualTo(Instant.parse("2026-07-01T00:00:02Z"));
        assertBounded(store);
    }

    @Test
    void nullEntryIsRejectedWithoutChangingStore() {
        HistoryStore store = storeWithPolicy(10, 10_000_000L);
        store.admitEntry(entry("existing", 1, false, "existing"));
        List<HistoryEntry> before = store.snapshot();

        HistoryAdmissionResult result = store.admitEntry(null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(HistoryAdmissionRejectionReason.INVALID_ENTRY);
        assertThat(store.snapshot()).usingRecursiveComparison().isEqualTo(before);
    }

    @Test
    void bulkAdmissionIsAllOrNothingAndDeduplicatesFirstOccurrence() {
        HistoryStore store = storeWithPolicy(3, 10_000_000L);
        store.admitEntry(entry("existing", 1, false, "existing"));

        HistoryAdmissionResult accepted = store.admitAll(List.of(
                entry("duplicate", 2, false, "first"),
                entry("duplicate", 3, false, "second")));
        assertThat(accepted.accepted()).isTrue();
        assertThat(store.getById("duplicate").analystNotes).isEqualTo("first");

        List<HistoryEntry> before = store.snapshot();
        HistoryAdmissionResult rejected = store.admitAll(List.of(
                entry("one", 4, false, "one"),
                entry("two", 5, false, "two"),
                entry("three", 6, false, "three"),
                entry("four", 7, false, "four")));

        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.rejectionReason()).isEqualTo(HistoryAdmissionRejectionReason.ENTRY_EXCEEDS_POLICY);
        assertThat(store.snapshot()).usingRecursiveComparison().isEqualTo(before);
        assertThat(store.getRetentionStats().lastEvictionCount()).isZero();
    }

    @Test
    void metadataGrowthIsAtomicAndNeverEvictsEditedRecord() {
        HistoryEntry edited = entry("edited", 2, true, "short");
        HistoryStore store = storeWithPolicy(10, canonicalSize(edited) + 20L);
        assertThat(store.admitEntry(edited).accepted()).isTrue();
        HistoryEntry before = store.getById("edited");

        HistoryAdmissionResult result = store.updateEvidenceMetadataWithResult(
                "edited", true, "n".repeat(1_000), List.of("Evidence"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(HistoryAdmissionRejectionReason.ENTRY_EXCEEDS_POLICY);
        assertThat(store.getById("edited")).usingRecursiveComparison().isEqualTo(before);
        assertThat(store.updateEvidenceMetadata("edited", true, "n".repeat(1_000), List.of("Evidence")))
                .isNull();
        assertThat(store.getById("edited")).usingRecursiveComparison().isEqualTo(before);
    }

    @Test
    void metadataGrowthCanAtomicallyEvictAnotherOldUnpinnedRecord() {
        HistoryEntry old = entry("old", 1, false, "old");
        HistoryEntry edited = entry("edited", 2, false, "short");
        HistoryEntry expanded = HistoryEntry.copyOf(edited);
        expanded.analystNotes = "expanded".repeat(100);
        expanded.tags = HistoryBodyTruncator.normalizeTags(List.of("Evidence"));
        long limit = Math.max(
                canonicalSize(old) + canonicalSize(edited),
                canonicalSize(expanded));
        HistoryStore store = storeWithPolicy(10, limit);
        assertThat(store.admitAll(List.of(old, edited)).accepted()).isTrue();

        HistoryAdmissionResult result = store.updateEvidenceMetadataWithResult(
                "edited", false, expanded.analystNotes, expanded.tags);

        assertThat(result.accepted()).isTrue();
        assertThat(result.entriesEvicted()).isEqualTo(1);
        assertThat(store.getById("edited")).isNotNull();
        assertThat(store.getById("old")).isNull();
        assertBounded(store);
    }

    @Test
    void rejectedPolicyChangePreservesPolicyEntriesAndAccounting() {
        HistoryEntry first = entry("first", 1, true, "first");
        HistoryEntry second = entry("second", 2, true, "second");
        HistoryStore store = storeWithPolicy(10, 10_000_000L);
        store.admitAll(List.of(first, second));
        HistoryRetentionPolicy oldPolicy = store.getRetentionPolicy();
        List<HistoryEntry> before = store.snapshot();
        long tooSmall = store.getRetentionStats().canonicalRetainedBytes() - 1L;

        HistoryAdmissionResult result = store.applyRetentionPolicy(
                new HistoryRetentionPolicy(10, tooSmall, 1_000_000L, 1_000_000L, true));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(HistoryAdmissionRejectionReason.POLICY_REJECTED);
        assertThat(store.getRetentionPolicy().maxTotalStoredBytes).isEqualTo(oldPolicy.maxTotalStoredBytes);
        assertThat(store.snapshot()).usingRecursiveComparison().isEqualTo(before);
        assertBounded(store);
    }

    @Test
    void pinAndUnpinMoveCachedBytesBetweenBuckets() {
        HistoryStore store = storeWithPolicy(10, 10_000_000L);
        store.admitEntry(entry("pin", 1, false, "pin"));
        long bytes = store.getRetentionStats().canonicalRetainedBytes();

        assertThat(store.setPinnedWithResult("pin", true).accepted()).isTrue();
        assertThat(store.getRetentionStats().pinnedRetainedBytes()).isEqualTo(bytes);
        assertThat(store.getRetentionStats().unpinnedRetainedBytes()).isZero();
        assertThat(store.setPinnedWithResult("pin", false).accepted()).isTrue();
        assertThat(store.getRetentionStats().pinnedRetainedBytes()).isZero();
        assertThat(store.getRetentionStats().unpinnedRetainedBytes()).isEqualTo(bytes);
        assertBounded(store);
    }

    @Test
    void defensiveCopiesCannotCorruptStoredAccounting() {
        HistoryStore store = storeWithPolicy(10, 10_000_000L);
        HistoryEntry supplied = entry("defensive", 1, false, "original");
        HistoryEntry stored = store.addEntry(supplied);
        long retainedBytes = store.getRetentionStats().canonicalRetainedBytes();

        supplied.analystNotes = "mutated-source";
        stored.analystNotes = "mutated-return";
        store.snapshot().get(0).analystNotes = "mutated-snapshot";

        assertThat(store.getById("defensive").analystNotes).isEqualTo("original");
        assertThat(store.getRetentionStats().canonicalRetainedBytes()).isEqualTo(retainedBytes);
        assertBounded(store);
    }

    private static HistoryStore storeWithPolicy(int maxEntries, long maxBytes) {
        HistoryStore store = new HistoryStore();
        HistoryAdmissionResult result = store.applyRetentionPolicy(new HistoryRetentionPolicy(
                maxEntries,
                maxBytes,
                1_000_000L,
                1_000_000L,
                true));
        assertThat(result.accepted()).isTrue();
        return store;
    }

    private static HistoryEntry entry(String id, long second, boolean pinned, String notes) {
        HistoryEntry entry = HistoryTestFixtures.copyEntry(
                HistoryTestFixtures.sampleWorkbenchEntry(),
                id,
                Instant.parse("2026-07-01T00:00:00Z").plusSeconds(second));
        entry.pinned = pinned;
        entry.analystNotes = notes;
        entry.tags.clear();
        return entry;
    }

    private static long canonicalSize(HistoryEntry entry) {
        HistoryEntry copy = HistoryEntry.copyOf(entry);
        copy.ensureDefaults();
        HistoryBodyTruncator.apply(copy, HistoryRetentionPolicy.defaultPolicy());
        copy.ensureDefaults();
        return copy.estimatedStoredBytes();
    }

    private static void assertBounded(HistoryStore store) {
        HistoryRetentionPolicy policy = store.getRetentionPolicy();
        HistoryRetentionStats stats = store.getRetentionStats();
        assertThat(stats.entryCount()).isLessThanOrEqualTo(policy.maxEntries);
        assertThat(stats.canonicalRetainedBytes()).isLessThanOrEqualTo(policy.maxTotalStoredBytes);
        assertThat(stats.totalEstimatedBytes()).isEqualTo(stats.canonicalRetainedBytes());
        assertThat(stats.pinnedRetainedBytes() + stats.unpinnedRetainedBytes())
                .isEqualTo(stats.canonicalRetainedBytes());
        assertThat(stats.overBudget()).isFalse();
    }
}
