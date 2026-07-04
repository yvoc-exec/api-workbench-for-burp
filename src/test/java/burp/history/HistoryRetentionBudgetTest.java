package burp.history;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryRetentionBudgetTest {

    @Test
    void evictsOldestUnpinnedByByteBudget() {
        HistoryStore store = new HistoryStore();
        store.setRetentionPolicy(new HistoryRetentionPolicy(10, 1_000_000, 1_000_000, 1_000_000, true));
        HistoryEntry newest = entry("newest", Instant.parse("2026-06-15T01:02:00Z"), false, 48, 32);
        HistoryEntry middle = entry("middle", Instant.parse("2026-06-15T01:01:00Z"), false, 48, 32);
        HistoryEntry oldest = entry("oldest", Instant.parse("2026-06-15T01:00:00Z"), false, 48, 32);
        store.replaceAll(List.of(newest, middle, oldest));

        long total = store.getRetentionStats().totalEstimatedBytes();
        long oldestSize = store.snapshot().get(2).estimatedStoredBytes();
        store.setRetentionPolicy(new HistoryRetentionPolicy(10, total - oldestSize, 1_000_000, 1_000_000, true));

        assertThat(store.snapshot()).extracting(entry -> entry.id).containsExactly("newest", "middle");
        assertThat(store.getRetentionStats().overBudget()).isFalse();
    }

    @Test
    void pinnedEntriesSurviveNormalEviction() {
        HistoryStore store = new HistoryStore();
        store.setRetentionPolicy(new HistoryRetentionPolicy(10, 1_000_000, 1_000_000, 1_000_000, true));
        HistoryEntry pinnedOldest = entry("pinned", Instant.parse("2026-06-15T01:00:00Z"), true, 48, 32);
        HistoryEntry middle = entry("middle", Instant.parse("2026-06-15T01:01:00Z"), false, 48, 32);
        HistoryEntry newest = entry("newest", Instant.parse("2026-06-15T01:02:00Z"), false, 48, 32);
        store.replaceAll(List.of(newest, middle, pinnedOldest));

        long total = store.getRetentionStats().totalEstimatedBytes();
        long middleSize = store.snapshot().get(1).estimatedStoredBytes();
        store.setRetentionPolicy(new HistoryRetentionPolicy(10, total - middleSize, 1_000_000, 1_000_000, true));

        assertThat(store.snapshot()).extracting(entry -> entry.id).containsExactly("newest", "pinned");
        assertThat(store.getById("pinned")).isNotNull();
        assertThat(store.getById("middle")).isNull();
    }

    @Test
    void overBudgetReportedWhenPinnedEntriesExceedBudget() {
        HistoryStore store = new HistoryStore();
        HistoryEntry first = entry("first", Instant.parse("2026-06-15T01:00:00Z"), true, 48, 32);
        HistoryEntry second = entry("second", Instant.parse("2026-06-15T01:01:00Z"), true, 48, 32);
        store.replaceAll(List.of(second, first));
        long total = store.getRetentionStats().totalEstimatedBytes();

        store.setRetentionPolicy(new HistoryRetentionPolicy(10, total - 1, 1_000_000, 1_000_000, true));

        HistoryRetentionStats stats = store.getRetentionStats();
        assertThat(stats.overBudget()).isTrue();
        assertThat(stats.entryCount()).isEqualTo(2);
        assertThat(stats.totalEstimatedBytes()).isEqualTo(total);
        assertThat(store.snapshot()).extracting(entry -> entry.id).containsExactly("second", "first");
    }

    @Test
    void countAndByteLimitsAreBothEnforced() {
        HistoryStore store = new HistoryStore();
        store.setRetentionPolicy(new HistoryRetentionPolicy(10, 1_000_000, 1_000_000, 1_000_000, true));
        HistoryEntry newest = entry("newest", Instant.parse("2026-06-15T01:02:00Z"), false, 128, 128);
        HistoryEntry middle = entry("middle", Instant.parse("2026-06-15T01:01:00Z"), false, 128, 128);
        HistoryEntry oldest = entry("oldest", Instant.parse("2026-06-15T01:00:00Z"), false, 128, 128);
        store.replaceAll(List.of(newest, middle, oldest));

        long newestSize = store.snapshot().get(0).estimatedStoredBytes();
        store.setRetentionPolicy(new HistoryRetentionPolicy(2, newestSize + 1, 1_000_000, 1_000_000, true));

        assertThat(store.snapshot()).extracting(entry -> entry.id).containsExactly("newest");
        assertThat(store.getRetentionStats().totalEstimatedBytes()).isLessThanOrEqualTo(newestSize + 1);
    }

    @Test
    void restoreProducesDeterministicRetention() {
        HistoryRetentionPolicy policy = new HistoryRetentionPolicy(2, 1_000_000, 1_000_000, 1_000_000, true);
        List<HistoryEntry> entries = new ArrayList<>();
        entries.add(entry("old", Instant.parse("2026-06-15T01:00:00Z"), false, 32, 24));
        entries.add(entry("duplicate", Instant.parse("2026-06-15T01:01:00Z"), false, 32, 24));
        entries.add(entry("duplicate", Instant.parse("2026-06-15T01:02:00Z"), false, 32, 24));
        entries.add(entry("new", Instant.parse("2026-06-15T01:03:00Z"), true, 32, 24));

        List<HistoryEntry> first = HistoryStore.normalizeEntries(entries, policy);
        List<HistoryEntry> second = HistoryStore.normalizeEntries(entries, policy);

        assertThat(first).extracting(entry -> entry.id).containsExactly("new", "duplicate");
        assertThat(second).extracting(entry -> entry.id).containsExactly("new", "duplicate");
        assertThat(first.get(0).timestamp).isEqualTo(second.get(0).timestamp);
        assertThat(first.get(1).timestamp).isEqualTo(second.get(1).timestamp);
    }

    @Test
    void unpinningMakesEntryEligibleForEviction() {
        HistoryStore store = new HistoryStore();
        store.setRetentionPolicy(new HistoryRetentionPolicy(10, 1_000_000, 1_000_000, 1_000_000, true));
        HistoryEntry pinnedOldest = entry("pinned-oldest", Instant.parse("2026-06-15T01:00:00Z"), true, 48, 32);
        HistoryEntry pinnedNewest = entry("pinned-newest", Instant.parse("2026-06-15T01:01:00Z"), true, 48, 32);
        store.replaceAll(List.of(pinnedNewest, pinnedOldest));
        long total = store.getRetentionStats().totalEstimatedBytes();
        store.setRetentionPolicy(new HistoryRetentionPolicy(10, total - 1, 1_000_000, 1_000_000, true));
        assertThat(store.getRetentionStats().overBudget()).isTrue();

        assertThat(store.setPinned("pinned-oldest", false)).isTrue();
        assertThat(store.getById("pinned-oldest")).isNull();
        assertThat(store.getById("pinned-newest")).isNotNull();
        assertThat(store.getRetentionStats().overBudget()).isFalse();
    }

    @Test
    void retainPinnedEntriesFalseAllowsNormalEviction() {
        HistoryStore store = new HistoryStore();
        store.setRetentionPolicy(new HistoryRetentionPolicy(10, 1_000_000, 1_000_000, 1_000_000, false));
        HistoryEntry pinnedOldest = entry("pinned-oldest", Instant.parse("2026-06-15T01:00:00Z"), true, 48, 32);
        HistoryEntry unpinnedNewest = entry("unpinned-newest", Instant.parse("2026-06-15T01:01:00Z"), false, 48, 32);
        store.replaceAll(List.of(unpinnedNewest, pinnedOldest));
        long total = store.getRetentionStats().totalEstimatedBytes();
        store.setRetentionPolicy(new HistoryRetentionPolicy(1, total - 1, 1_000_000, 1_000_000, false));

        assertThat(store.getById("pinned-oldest")).isNull();
        assertThat(store.getById("unpinned-newest")).isNotNull();
        assertThat(store.getRetentionStats().entryCount()).isEqualTo(1);
    }

    @Test
    void sizeEstimateIsDeterministicAndNonNegative() {
        HistoryEntry entry = entry("estimate", Instant.parse("2026-06-15T01:00:00Z"), true, 64, 64);
        long first = entry.estimatedStoredBytes();
        long second = entry.estimatedStoredBytes();

        assertThat(first).isEqualTo(second);
        assertThat(first).isGreaterThanOrEqualTo(0L);
    }

    private static HistoryEntry entry(String id, Instant timestamp, boolean pinned, int requestBytes, int responseBytes) {
        HistoryEntry entry = new HistoryEntry();
        entry.id = id;
        entry.timestamp = timestamp;
        entry.source = HistorySource.RUNNER;
        entry.attemptNumber = 1;
        entry.totalAttempts = 1;
        entry.collectionId = "col-" + id;
        entry.collectionName = "Collection " + id;
        entry.requestId = "req-" + id;
        entry.requestName = "Request " + id;
        entry.folderPath = "Folder";
        entry.environmentId = "env";
        entry.environmentName = "Environment";
        entry.result = HistoryResult.SUCCESS;
        entry.pinned = pinned;
        entry.analystNotes = "Note " + id;
        entry.tags = new LinkedHashSet<>(List.of("Tag-" + id));
        entry.requestSnapshot = new HistoryRequestSnapshot();
        entry.requestSnapshot.method = "POST";
        entry.requestSnapshot.urlTemplate = "https://api.example.test/" + id;
        entry.requestSnapshot.bodyMode = "raw";
        entry.requestSnapshot.headersAsAuthored = new ArrayList<>();
        entry.requestSnapshot.bodyAsAuthored = bytes('r', requestBytes);
        entry.requestSnapshot.originalBodyLength = requestBytes;
        entry.requestSnapshot.storedBodyLength = requestBytes;
        entry.requestSnapshot.fullBodySha256 = HistoryBodyTruncator.sha256Hex(entry.requestSnapshot.bodyAsAuthored);
        entry.requestSnapshot.rawBodyTruncated = false;
        entry.requestSnapshot.originalRawBodyLength = 0L;
        entry.requestSnapshot.storedRawBodyLength = 0L;
        entry.requestSnapshot.fullRawBodySha256 = "";
        entry.requestSnapshot.rawTruncationReason = "";
        entry.requestSnapshot.parseWarning = "";
        entry.responseSnapshot = new HistoryResponseSnapshot();
        entry.responseSnapshot.statusCode = 200;
        entry.responseSnapshot.reasonPhrase = "OK";
        entry.responseSnapshot.headers = new ArrayList<>();
        entry.responseSnapshot.body = bytes('s', responseBytes);
        entry.responseSnapshot.originalBodyLength = responseBytes;
        entry.responseSnapshot.storedBodyLength = responseBytes;
        entry.responseSnapshot.fullBodySha256 = HistoryBodyTruncator.sha256Hex(entry.responseSnapshot.body);
        entry.responseSnapshot.bodyTruncated = false;
        entry.responseSnapshot.truncationReason = "";
        entry.requestSizeBytes = requestBytes;
        entry.responseSizeBytes = responseBytes;
        entry.ensureDefaults();
        return entry;
    }

    private static byte[] bytes(char ch, int length) {
        if (length <= 0) {
            return new byte[0];
        }
        String text = String.valueOf(ch).repeat(length);
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
