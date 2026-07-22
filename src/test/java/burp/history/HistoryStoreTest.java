package burp.history;

import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryStoreTest {

    @Test
    void addEntryKeepsNewestFirstAndReturnsDefensiveCopies() {
        HistoryStore store = new HistoryStore();
        HistoryEntry older = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "older", Instant.parse("2026-06-15T01:00:00Z"));
        HistoryEntry newer = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleRunnerEntry(),
                "newer", Instant.parse("2026-06-15T01:01:00Z"));

        store.addEntry(older);
        store.addEntry(newer);

        List<HistoryEntry> snapshot = store.snapshot();
        assertThat(snapshot).extracting(entry -> entry.id).containsExactly("newer", "older");

        snapshot.get(0).requestSnapshot.urlTemplate = "mutated";
        assertThat(store.getById("newer").requestSnapshot.urlTemplate).isEqualTo("{{base_url}}/login");
    }

    @Test
    void removeByIdAndIdsUpdateStore() {
        HistoryStore store = new HistoryStore();
        HistoryEntry first = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "one", Instant.parse("2026-06-15T01:00:00Z"));
        HistoryEntry second = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleRunnerEntry(),
                "two", Instant.parse("2026-06-15T01:01:00Z"));
        store.addEntry(first);
        store.addEntry(second);

        assertThat(store.removeById("one")).isTrue();
        assertThat(store.removeByIds(List.of("two"))).hasSize(1);
        assertThat(store.isEmpty()).isTrue();
    }

    @Test
    void isEmptyReturnsFalseWhileEntriesRemain() {
        HistoryStore store = new HistoryStore();
        store.addEntry(HistoryTestFixtures.sampleWorkbenchEntry());

        assertThat(store.isEmpty()).isFalse();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void addAllReplaceAllNormalizeAndLookupRemainDeterministic() {
        HistoryStore store = new HistoryStore();
        HistoryEntry older = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "older", Instant.parse("2026-06-15T01:00:00Z"));
        HistoryEntry newer = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleRunnerEntry(),
                "newer", Instant.parse("2026-06-15T01:01:00Z"));
        HistoryEntry duplicate = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleRunnerEntry(),
                "newer", Instant.parse("2026-06-15T01:02:00Z"));
        HistoryEntry missingTimestamp = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "missing", null);

        List<HistoryEntry> bulk = new ArrayList<>();
        bulk.add(older);
        bulk.add(newer);
        bulk.add(duplicate);
        bulk.add(missingTimestamp);
        store.addAll(bulk);
        assertThat(store.snapshot()).hasSize(3);
        assertThat(store.snapshot().get(0).id).isNotBlank();
        assertThat(store.snapshot().get(0).timestamp).isNotNull();
        assertThat(store.snapshot()).extracting(entry -> entry.id)
                .contains("missing", "newer", "older");
        assertThat(store.getById("missing")).isNotNull();
        assertThat(store.getById("")).isNull();
        assertThat(store.getById(null)).isNull();
        assertThat(store.getByIds(List.of("missing", "older", "absent")))
                .extracting(entry -> entry.id)
                .containsExactly("missing", "older");

        HistoryEntry replacement = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleRunnerEntry(),
                "replacement", Instant.parse("2026-06-15T01:03:00Z"));
        List<HistoryEntry> replacementBulk = new ArrayList<>();
        replacementBulk.add(replacement);
        store.replaceAll(replacementBulk);
        assertThat(store.snapshot()).extracting(entry -> entry.id).containsExactly("replacement");

        List<HistoryEntry> invalidBulk = new ArrayList<>();
        invalidBulk.add(HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "not-stored", Instant.parse("2026-06-15T01:04:00Z")));
        invalidBulk.add(null);
        HistoryAdmissionResult rejected = store.admitAll(invalidBulk);
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.rejectionReason()).isEqualTo(HistoryAdmissionRejectionReason.INVALID_ENTRY);
        assertThat(store.snapshot()).extracting(entry -> entry.id).containsExactly("replacement");

        store.setRetentionPolicy(new HistoryRetentionPolicy(0));
        assertThat(store.getRetentionPolicy().maxEntries).isEqualTo(HistoryRetentionPolicy.DEFAULT_MAX_ENTRIES);
        assertThat(store.snapshot()).hasSize(1);

        List<HistoryEntry> normalized = HistoryStore.normalizeEntries(
                new ArrayList<>(List.of(missingTimestamp, duplicate, older)),
                new HistoryRetentionPolicy(1));
        assertThat(normalized).hasSize(1);
        assertThat(normalized.get(0).id).isEqualTo("missing");
        assertThat(normalized.get(0).timestamp).isNotNull();
    }

    @Test
    void defensiveCopiesProtectNestedHistoryState() {
        HistoryStore store = new HistoryStore();
        HistoryEntry entry = HistoryTestFixtures.sampleWorkbenchEntry();
        store.addEntry(entry);

        HistoryEntry fromSnapshot = store.snapshot().get(0);
        fromSnapshot.requestSnapshot.rawRequestSentText = "mutated";
        fromSnapshot.unresolvedVariables.add("new-var");
        fromSnapshot.assertions.clear();

        HistoryEntry reread = store.getById(entry.id);
        assertThat(reread.requestSnapshot.rawRequestSentText).contains("POST /login HTTP/1.1");
        assertThat(reread.unresolvedVariables).contains("missing_password");
        assertThat(reread.assertions).isNotEmpty();
    }

    @Test
    void nullBulkOperationsAreSafeNoOps() {
        HistoryStore store = new HistoryStore();

        store.addAll(null);
        store.replaceAll(null);

        assertThat(store.getByIds(null)).isEmpty();
        assertThat(store.removeByIds(null)).isEmpty();
        assertThat(store.snapshot()).isEmpty();
    }

    @Test
    void completedNoOpRemovalResetsMostRecentEvictionCount() {
        HistoryStore store = new HistoryStore();
        store.setRetentionPolicy(new HistoryRetentionPolicy(1));
        store.addEntry(HistoryTestFixtures.copyEntry(
                HistoryTestFixtures.sampleWorkbenchEntry(),
                "older",
                Instant.parse("2026-06-15T01:00:00Z")));
        store.addEntry(HistoryTestFixtures.copyEntry(
                HistoryTestFixtures.sampleRunnerEntry(),
                "newer",
                Instant.parse("2026-06-15T01:01:00Z")));
        assertThat(store.getRetentionStats().lastEvictionCount()).isEqualTo(1);

        assertThat(store.removeByIds(List.of("absent"))).isEmpty();

        assertThat(store.getRetentionStats().lastEvictionCount()).isZero();
    }
}
