package burp.history;

import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryRetentionPolicyTest {

    @Test
    void defaultPolicyKeepsLatestThousandEntries() {
        assertThat(HistoryRetentionPolicy.defaultPolicy().maxEntries).isEqualTo(1000);
    }

    @Test
    void normalizeEntriesSortsNewestFirstAndClampsRetention() {
        List<HistoryEntry> entries = new ArrayList<>();
        entries.add(HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "old", Instant.parse("2026-06-15T01:00:00Z")));
        entries.add(HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleRunnerEntry(),
                "mid", Instant.parse("2026-06-15T01:01:00Z")));
        entries.add(HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "new", Instant.parse("2026-06-15T01:02:00Z")));

        List<HistoryEntry> normalized = HistoryStore.normalizeEntries(entries, new HistoryRetentionPolicy(2));

        assertThat(normalized).extracting(entry -> entry.id).containsExactly("new", "mid");
    }

    @Test
    void retentionPolicyClampsZeroToOne() {
        assertThat(new HistoryRetentionPolicy(0).maxEntries).isEqualTo(1);
    }
}
