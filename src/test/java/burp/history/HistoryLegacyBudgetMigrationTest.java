package burp.history;

import burp.models.WorkspaceState;
import burp.utils.WorkspaceStateJson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryLegacyBudgetMigrationTest {

    @Test
    void missingPolicyVersionCompactsPinnedPayloadOnceAndBecomesCurrent() {
        byte[] original = payload(20_000, 'r');
        HistoryEntry entry = entry("legacy-pinned", "2026-06-15T01:00:00Z", true, original);
        String originalHash = HistoryBodyTruncator.sha256Hex(original);
        String legacyJson = workspaceJson(List.of(entry), policy(10, 8_000), false);

        WorkspaceState first = WorkspaceStateJson.fromJson(legacyJson);
        HistoryEntry compacted = first.historyEntries.get(0);

        assertThat(first.historyRetentionPolicyVersion)
                .isEqualTo(HistoryRetentionPolicy.CURRENT_POLICY_VERSION);
        assertThat(first.historyLegacyCompactedEntryCount).isEqualTo(1);
        assertThat(compacted.legacyBudgetCompacted).isTrue();
        assertThat(compacted.responseSnapshot.body).hasSize(HistoryBodyTruncator.LEGACY_PREVIEW_MAX_BYTES);
        assertThat(compacted.responseSnapshot.originalBodyLength).isEqualTo(original.length);
        assertThat(compacted.responseSnapshot.storedBodyLength)
                .isEqualTo(HistoryBodyTruncator.LEGACY_PREVIEW_MAX_BYTES);
        assertThat(compacted.responseSnapshot.fullBodySha256).isEqualTo(originalHash);
        assertThat(compacted.responseSnapshot.truncationReason)
                .isEqualTo(HistoryBodyTruncator.LEGACY_HISTORY_BUDGET_COMPACTION);

        JsonObject firstCurrentJson = JsonParser.parseString(WorkspaceStateJson.toJson(first)).getAsJsonObject();
        WorkspaceState second = WorkspaceStateJson.fromJson(firstCurrentJson.toString());
        JsonObject secondCurrentJson = JsonParser.parseString(WorkspaceStateJson.toJson(second)).getAsJsonObject();

        assertThat(second.historyEntries.get(0).responseSnapshot.body)
                .isEqualTo(compacted.responseSnapshot.body);
        assertThat(second.historyEntries.get(0).responseSnapshot.fullBodySha256).isEqualTo(originalHash);
        assertThat(second.historyLegacyCompactedEntryCount).isZero();
        assertThat(secondCurrentJson.get("historyRetentionPolicyVersion"))
                .isEqualTo(firstCurrentJson.get("historyRetentionPolicyVersion"));
        assertThat(secondCurrentJson.get("historyRetentionPolicy"))
                .isEqualTo(firstCurrentJson.get("historyRetentionPolicy"));
        assertThat(secondCurrentJson.get("historyEntries"))
                .isEqualTo(firstCurrentJson.get("historyEntries"));
    }

    @Test
    void migrationCountPropagatesOnceIntoRestoredStoreStats() {
        HistoryEntry entry = entry("legacy-count", "2026-06-15T01:00:00Z", true, payload(20_000, 'z'));
        WorkspaceState migrated = WorkspaceStateJson.fromJson(
                workspaceJson(List.of(entry), policy(10, 8_000), false));
        HistoryStore store = new HistoryStore();

        new HistoryPersistenceService().restoreStore(store, migrated);

        assertThat(store.getRetentionStats().legacyCompactedEntryCount()).isEqualTo(1);
        assertThat(migrated.historyLegacyCompactedEntryCount).isZero();
        store.addEntry(entry("ordinary", "2026-06-15T02:00:00Z", false, payload(4, 'o')));
        assertThat(store.getRetentionStats().legacyCompactedEntryCount()).isEqualTo(1);

        WorkspaceState current = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(workspace(store)));
        new HistoryPersistenceService().restoreStore(store, current);
        assertThat(store.getRetentionStats().legacyCompactedEntryCount()).isZero();
    }

    @Test
    void legacyUnpinnedEntriesAreEvictedOldestFirst() {
        HistoryEntry older = entry("older", "2026-06-15T01:00:00Z", false, payload(10, 'a'));
        HistoryEntry newer = entry("newer", "2026-06-15T02:00:00Z", false, payload(10, 'b'));

        WorkspaceState migrated = WorkspaceStateJson.fromJson(
                workspaceJson(List.of(older, newer), policy(1, 100_000), false));

        assertThat(migrated.historyEntries).extracting(value -> value.id).containsExactly("newer");
        assertThat(migrated.historyEntries.get(0).legacyBudgetCompacted).isFalse();
    }

    @Test
    void legacyPinnedPayloadIsNotCompactedWhenBudgetAlreadyFits() {
        HistoryEntry entry = entry("fits", "2026-06-15T01:00:00Z", true, payload(4_500, 'f'));

        WorkspaceState migrated = WorkspaceStateJson.fromJson(
                workspaceJson(List.of(entry), policy(10, 100_000), false));

        assertThat(migrated.historyEntries).hasSize(1);
        assertThat(migrated.historyEntries.get(0).legacyBudgetCompacted).isFalse();
        assertThat(migrated.historyEntries.get(0).responseSnapshot.body).hasSize(4_500);
    }

    @Test
    void existingNonblankTruncationReasonSurvivesWhenNoLegacyReductionOccurs() {
        HistoryEntry entry = entry("old-reason", "2026-06-15T01:00:00Z", true, payload(4, 'x'));
        entry.responseSnapshot.bodyTruncated = true;
        entry.responseSnapshot.originalBodyLength = 40;
        entry.responseSnapshot.storedBodyLength = 4;
        entry.responseSnapshot.fullBodySha256 = "legacy-full-hash";
        entry.responseSnapshot.truncationReason = "LEGACY_CUSTOM_REASON";

        WorkspaceState migrated = WorkspaceStateJson.fromJson(
                workspaceJson(List.of(entry), policy(10, 100_000), false));

        assertThat(migrated.historyEntries.get(0).responseSnapshot.truncationReason)
                .isEqualTo("LEGACY_CUSTOM_REASON");
        assertThat(migrated.historyEntries.get(0).responseSnapshot.fullBodySha256)
                .isEqualTo("legacy-full-hash");
    }

    @Test
    void pinnedCountFloorFailsWithoutDeletingProtectedRecords() {
        HistoryEntry first = entry("private-request-name-one", "2026-06-15T01:00:00Z", true, payload(4, 'a'));
        HistoryEntry second = entry("private-request-name-two", "2026-06-15T02:00:00Z", true, payload(4, 'b'));
        String json = workspaceJson(List.of(first, second), policy(1, 100_000), false);

        assertThatThrownBy(() -> WorkspaceStateJson.fromJson(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("History retention migration could not satisfy the configured policy.")
                .hasMessageNotContaining("private-request-name-one")
                .hasMessageNotContaining("private-request-name-two");
    }

    @Test
    void metadataOnlyFloorFailsWithSanitizedMessage() {
        HistoryEntry entry = entry("private-entry", "2026-06-15T01:00:00Z", true, new byte[0]);
        entry.analystNotes = "private-note-" + "n".repeat(8_000);
        entry.tags.add("private-tag");
        String json = workspaceJson(List.of(entry), policy(10, 256), false);

        assertThatThrownBy(() -> WorkspaceStateJson.fromJson(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("History retention migration could not satisfy the configured policy.")
                .hasMessageNotContaining("private-note")
                .hasMessageNotContaining("private-tag")
                .hasMessageNotContaining("private-entry");
    }

    @Test
    void currentWorkspaceDoesNotCompactPinnedPayloadToForceFit() {
        HistoryEntry entry = entry("current-pinned", "2026-06-15T01:00:00Z", true, payload(20_000, 'c'));
        String currentJson = workspaceJson(List.of(entry), policy(10, 8_000), true);

        assertThatThrownBy(() -> WorkspaceStateJson.fromJson(currentJson))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("History retention migration could not satisfy the configured policy.");
    }

    @Test
    void legacyBinaryRawRequestCompactionConvergesAcrossCurrentJsonReload() {
        HistoryEntry entry = entry("legacy-binary", "2026-06-15T01:00:00Z", true, new byte[0]);
        byte[] headers = "POST /binary HTTP/1.1\r\nHost: api.example.test\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] body = new byte[20_000];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i % 251);
        }
        body[0] = (byte) 0xff;
        byte[] raw = new byte[headers.length + body.length];
        System.arraycopy(headers, 0, raw, 0, headers.length);
        System.arraycopy(body, 0, raw, headers.length, body.length);
        entry.requestSnapshot.rawRequestSent = raw;
        entry.requestSnapshot.rawRequestSentText = null;

        WorkspaceState first = WorkspaceStateJson.fromJson(
                workspaceJson(List.of(entry), policy(10, 12_000), false));
        JsonObject firstCurrentJson = JsonParser.parseString(WorkspaceStateJson.toJson(first)).getAsJsonObject();
        WorkspaceState second = WorkspaceStateJson.fromJson(firstCurrentJson.toString());
        JsonObject secondCurrentJson = JsonParser.parseString(WorkspaceStateJson.toJson(second)).getAsJsonObject();

        HistoryEntry firstEntry = first.historyEntries.get(0);
        HistoryEntry secondEntry = second.historyEntries.get(0);
        assertThat(firstEntry.legacyBudgetCompacted).isTrue();
        assertThat(firstEntry.requestSnapshot.rawTruncationReason)
                .isEqualTo(HistoryBodyTruncator.LEGACY_HISTORY_BUDGET_COMPACTION);
        assertThat(firstEntry.requestSnapshot.rawRequestSent)
                .isEqualTo(secondEntry.requestSnapshot.rawRequestSent);
        assertThat(firstEntry.requestSnapshot.rawRequestSentText)
                .isEqualTo(secondEntry.requestSnapshot.rawRequestSentText)
                .isEqualTo(new String(firstEntry.requestSnapshot.rawRequestSent, StandardCharsets.UTF_8));
        assertThat(secondCurrentJson.get("historyEntries"))
                .isEqualTo(firstCurrentJson.get("historyEntries"));
    }

    private static HistoryRetentionPolicy policy(int entries, long bytes) {
        return new HistoryRetentionPolicy(entries, bytes, 1_000_000, 2_000_000, true);
    }

    private static HistoryEntry entry(
            String id,
            String timestamp,
            boolean pinned,
            byte[] responseBody) {
        HistoryEntry entry = new HistoryEntry();
        entry.id = id;
        entry.timestamp = Instant.parse(timestamp);
        entry.source = HistorySource.WORKBENCH;
        entry.result = HistoryResult.SUCCESS;
        entry.requestName = id;
        entry.pinned = pinned;
        entry.requestSnapshot = new HistoryRequestSnapshot();
        entry.requestSnapshot.method = "GET";
        entry.requestSnapshot.urlTemplate = "https://api.example.test/history";
        entry.requestSnapshot.bodyAsAuthored = new byte[0];
        entry.responseSnapshot = new HistoryResponseSnapshot();
        entry.responseSnapshot.statusCode = 200;
        entry.responseSnapshot.body = responseBody.clone();
        entry.responseSnapshot.originalBodyLength = responseBody.length;
        entry.responseSnapshot.storedBodyLength = responseBody.length;
        entry.responseSnapshot.fullBodySha256 = HistoryBodyTruncator.sha256Hex(responseBody);
        entry.ensureDefaults();
        return entry;
    }

    private static byte[] payload(int size, char value) {
        return String.valueOf(value).repeat(Math.max(0, size)).getBytes(StandardCharsets.US_ASCII);
    }

    private static String workspaceJson(
            List<HistoryEntry> entries,
            HistoryRetentionPolicy targetPolicy,
            boolean current) {
        WorkspaceState state = new WorkspaceState();
        state.historyRetentionPolicyVersion = HistoryRetentionPolicy.CURRENT_POLICY_VERSION;
        state.historyRetentionPolicy = HistoryRetentionPolicy.defaultPolicy();
        state.historyEntries = new ArrayList<>(entries);
        JsonObject root = JsonParser.parseString(WorkspaceStateJson.toJson(state)).getAsJsonObject();
        JsonObject policy = root.getAsJsonObject("historyRetentionPolicy");
        policy.addProperty("maxEntries", targetPolicy.maxEntries);
        policy.addProperty("maxTotalStoredBytes", targetPolicy.maxTotalStoredBytes);
        policy.addProperty("maxRequestBodyBytesPerEntry", targetPolicy.maxRequestBodyBytesPerEntry);
        policy.addProperty("maxResponseBodyBytesPerEntry", targetPolicy.maxResponseBodyBytesPerEntry);
        policy.addProperty("retainPinnedEntries", targetPolicy.retainPinnedEntries);
        if (!current) {
            root.remove("historyRetentionPolicyVersion");
        }
        return root.toString();
    }

    private static WorkspaceState workspace(HistoryStore store) {
        WorkspaceState state = new WorkspaceState();
        new HistoryPersistenceService().writeStore(state, store);
        return state;
    }
}
