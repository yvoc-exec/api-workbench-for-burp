package burp.performance;

import burp.history.HistoryEntry;
import burp.history.HistoryRetentionPolicy;
import burp.history.HistoryStore;
import burp.models.ApiRequest;
import burp.models.RedirectHop;
import burp.models.RunnerResult;
import burp.models.WorkspaceState;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryHardeningLogicalBudgetTest {

    @Test
    void deterministicLogicalRepresentationsAreAccountedSeparately() {
        byte[] raw = MemoryHardeningFixtureFactory.rawHttpRequest(64 * 1024);
        String rawText = new String(raw, StandardCharsets.ISO_8859_1);
        HistoryEntry history = MemoryHardeningFixtureFactory.historyEntry(1, raw.length, 96 * 1024);
        RedirectHop hop = history.redirectHops.get(0);
        RunnerResult runner = MemoryHardeningFixtureFactory.runnerResult(1, 4096, 128 * 1024);

        assertThat(raw).hasSize(64 * 1024);
        assertThat(MemoryHardeningFixtureFactory.utf8Length(rawText)).isEqualTo(raw.length);
        assertThat(history.requestSnapshot.rawRequestSent).hasSize(raw.length);
        assertThat(history.responseSnapshot.body).hasSize(96 * 1024);
        assertThat(hop.rawRequestBytes.length).isEqualTo(32 * 1024);
        assertThat(hop.responseBody.length).isEqualTo(12 * 1024);
        assertThat(runner.rawRequestBytes).hasSize(4096);
        assertThat(MemoryHardeningFixtureFactory.utf8Length(runner.responseBody)).isEqualTo(128 * 1024);

        long duplicateRepresentations = 0;
        duplicateRepresentations = MemoryHardeningFixtureFactory.safeAdd(duplicateRepresentations, raw.length);
        duplicateRepresentations = MemoryHardeningFixtureFactory.safeAdd(
                duplicateRepresentations, MemoryHardeningFixtureFactory.utf8Length(rawText));
        duplicateRepresentations = MemoryHardeningFixtureFactory.safeAdd(
                duplicateRepresentations, history.requestSnapshot.bodyAsAuthored.length);
        duplicateRepresentations = MemoryHardeningFixtureFactory.safeAdd(
                duplicateRepresentations, MemoryHardeningFixtureFactory.utf8Length(runner.responseBody));
        duplicateRepresentations = MemoryHardeningFixtureFactory.safeAdd(
                duplicateRepresentations, history.responseSnapshot.body.length);
        assertThat(duplicateRepresentations).isGreaterThan(raw.length + runner.responseBodyLength);
        assertThat(history.estimatedStoredBytes()).isGreaterThan(
                history.requestSnapshot.rawRequestSent.length + history.responseSnapshot.body.length);
    }

    @Test
    void workspaceSerializationAndExactSnapshotBytesHaveDeterministicLengths() {
        WorkspaceState state = MemoryHardeningFixtureFactory.workspace(2, 8192);
        String json = WorkspaceStateJson.toJson(state);
        ApiRequest request = state.collections.get(0).requests.get(0);
        request.exactHttpRequest = MemoryHardeningFixtureFactory.exactSnapshot(16 * 1024);

        assertThat(json.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(16 * 1024);
        assertThat(request.exactHttpRequest.rawRequestBytes).hasSize(16 * 1024);
    }

    @Test
    void overflowSafeAdditionSaturatesInsteadOfWrapping() {
        assertThat(MemoryHardeningFixtureFactory.safeAdd(Long.MAX_VALUE - 4, 10)).isEqualTo(Long.MAX_VALUE);
        assertThat(MemoryHardeningFixtureFactory.safeAdd(10, -1)).isEqualTo(10);
    }

    @Test
    void currentHistoryDefaultsAndPinnedOverBudgetBehaviorAreMeasured() {
        HistoryRetentionPolicy defaults = HistoryRetentionPolicy.defaultPolicy();
        assertThat(defaults.maxEntries).isEqualTo(1000);
        assertThat(defaults.maxTotalStoredBytes).isEqualTo(100L * 1024L * 1024L);
        assertThat(defaults.maxRequestBodyBytesPerEntry).isEqualTo(1L * 1024L * 1024L);
        assertThat(defaults.maxResponseBodyBytesPerEntry).isEqualTo(2L * 1024L * 1024L);
        assertThat(defaults.retainPinnedEntries).isTrue();

        HistoryStore store = new HistoryStore();
        store.setRetentionPolicy(new HistoryRetentionPolicy(1, 1024, 1024, 1024, true));
        HistoryEntry first = MemoryHardeningFixtureFactory.historyEntry(1, 64, 2048);
        first.pinned = true;
        HistoryEntry second = MemoryHardeningFixtureFactory.historyEntry(2, 64, 2048);
        second.pinned = true;
        store.addEntry(first);
        store.addEntry(second);

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.getRetentionStats().pinnedCount()).isEqualTo(2);
        assertThat(store.getRetentionStats().overBudget()).isTrue();
    }

    @Test
    void runnerAndWorkbenchOwnershipCountsAreStructuralNotJvmLayoutClaims() {
        List<RunnerResult> results = List.of(
                MemoryHardeningFixtureFactory.runnerResult(1, 64, 1024),
                MemoryHardeningFixtureFactory.runnerResult(2, 64, 1024));
        IdentityHashMap<ApiRequest, byte[]> workbenchOwners = new IdentityHashMap<>();
        workbenchOwners.put(new ApiRequest(), MemoryHardeningFixtureFactory.rawHttpRequest(128));
        workbenchOwners.put(new ApiRequest(), MemoryHardeningFixtureFactory.rawHttpRequest(128));

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(result -> assertThat(result.responseBody).hasSize(1024));
        assertThat(workbenchOwners).hasSize(2);
        assertThat(workbenchOwners.values()).allSatisfy(bytes -> assertThat(bytes).hasSize(128));
    }
}
