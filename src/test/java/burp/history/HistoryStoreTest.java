package burp.history;

import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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
}
