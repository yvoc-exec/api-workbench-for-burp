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
        HistoryRetentionPolicy policy = HistoryRetentionPolicy.defaultPolicy();
        assertThat(policy.maxEntries).isEqualTo(1_000);
        assertThat(policy.maxTotalStoredBytes).isEqualTo(100L * 1024L * 1024L);
        assertThat(policy.maxRequestBodyBytesPerEntry).isEqualTo(1L * 1024L * 1024L);
        assertThat(policy.maxResponseBodyBytesPerEntry).isEqualTo(2L * 1024L * 1024L);
        assertThat(policy.retainPinnedEntries).isTrue();
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
    void retentionPolicyUsesDefaultsForZero() {
        assertThat(new HistoryRetentionPolicy(0).maxEntries).isEqualTo(HistoryRetentionPolicy.DEFAULT_MAX_ENTRIES);
    }

    @Test
    void nonpositiveValuesUseDefaultsAndPositiveValuesClampToNamedBounds() {
        HistoryRetentionPolicy defaults = new HistoryRetentionPolicy(-1, 0, -2, 0, true);
        assertThat(defaults.maxEntries).isEqualTo(HistoryRetentionPolicy.DEFAULT_MAX_ENTRIES);
        assertThat(defaults.maxTotalStoredBytes).isEqualTo(HistoryRetentionPolicy.DEFAULT_MAX_TOTAL_STORED_BYTES);
        assertThat(defaults.maxRequestBodyBytesPerEntry)
                .isEqualTo(HistoryRetentionPolicy.DEFAULT_MAX_REQUEST_BODY_BYTES_PER_ENTRY);
        assertThat(defaults.maxResponseBodyBytesPerEntry)
                .isEqualTo(HistoryRetentionPolicy.DEFAULT_MAX_RESPONSE_BODY_BYTES_PER_ENTRY);

        HistoryRetentionPolicy maximums = new HistoryRetentionPolicy(
                Integer.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                false);
        assertThat(maximums.maxEntries).isEqualTo(HistoryRetentionPolicy.MAX_MAX_ENTRIES);
        assertThat(maximums.maxTotalStoredBytes).isEqualTo(HistoryRetentionPolicy.MAX_MAX_TOTAL_STORED_BYTES);
        assertThat(maximums.maxRequestBodyBytesPerEntry)
                .isEqualTo(HistoryRetentionPolicy.MAX_MAX_REQUEST_BODY_BYTES_PER_ENTRY);
        assertThat(maximums.maxResponseBodyBytesPerEntry)
                .isEqualTo(HistoryRetentionPolicy.MAX_MAX_RESPONSE_BODY_BYTES_PER_ENTRY);
        assertThat(maximums.retainPinnedEntries).isFalse();

        HistoryRetentionPolicy minimums = new HistoryRetentionPolicy(1, 1, 1, 1, true);
        assertThat(minimums.maxEntries).isEqualTo(HistoryRetentionPolicy.MIN_MAX_ENTRIES);
        assertThat(minimums.maxTotalStoredBytes).isEqualTo(HistoryRetentionPolicy.MIN_MAX_TOTAL_STORED_BYTES);
        assertThat(minimums.maxRequestBodyBytesPerEntry)
                .isEqualTo(HistoryRetentionPolicy.MIN_MAX_REQUEST_BODY_BYTES_PER_ENTRY);
        assertThat(minimums.maxResponseBodyBytesPerEntry)
                .isEqualTo(HistoryRetentionPolicy.MIN_MAX_RESPONSE_BODY_BYTES_PER_ENTRY);
    }

    @Test
    void normalizeAndCopyAreIdempotentAndClampMaliciousValues() {
        HistoryRetentionPolicy malicious = new HistoryRetentionPolicy();
        malicious.maxEntries = Integer.MAX_VALUE;
        malicious.maxTotalStoredBytes = Long.MAX_VALUE;
        malicious.maxRequestBodyBytesPerEntry = Long.MAX_VALUE;
        malicious.maxResponseBodyBytesPerEntry = Long.MAX_VALUE;

        HistoryRetentionPolicy copy = HistoryRetentionPolicy.copyOf(malicious);
        copy.normalize();
        HistoryRetentionPolicy second = HistoryRetentionPolicy.copyOf(copy);

        assertThat(second.maxEntries).isEqualTo(10_000);
        assertThat(second.maxTotalStoredBytes).isEqualTo(512L * 1024L * 1024L);
        assertThat(second.maxRequestBodyBytesPerEntry).isEqualTo(16L * 1024L * 1024L);
        assertThat(second.maxResponseBodyBytesPerEntry).isEqualTo(32L * 1024L * 1024L);
        assertThat(second.maxEntries).isPositive();
        assertThat(second.maxTotalStoredBytes).isPositive();
        assertThat(second.maxRequestBodyBytesPerEntry).isPositive();
        assertThat(second.maxResponseBodyBytesPerEntry).isPositive();
        assertThat(HistoryRetentionPolicy.CURRENT_POLICY_VERSION).isEqualTo(1);
    }
}
