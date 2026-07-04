package burp.history;

import burp.models.WorkspaceState;
import burp.testsupport.HistoryTestFixtures;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryPersistenceServiceTest {

    @Test
    void historyRoundTripsThroughWorkspaceState() {
        HistoryPersistenceService service = new HistoryPersistenceService();
        WorkspaceState state = new WorkspaceState();
        List<HistoryEntry> entries = List.of(HistoryTestFixtures.sampleWorkbenchEntry(), HistoryTestFixtures.sampleRunnerEntry());

        service.writeHistory(state, entries);
        assertThat(state.historyEntries).hasSize(2);

        HistoryStore store = new HistoryStore();
        service.restoreStore(store, state);
        assertThat(store.snapshot()).extracting(entry -> entry.id)
                .containsExactly(HistoryTestFixtures.sampleRunnerEntry().id, HistoryTestFixtures.sampleWorkbenchEntry().id);
    }

    @Test
    void missingHistoryDataRestoresAsEmptyWithoutThrowing() {
        HistoryPersistenceService service = new HistoryPersistenceService();
        WorkspaceState state = new WorkspaceState();
        state.historyEntries = null;

        assertThat(service.extractHistory(state)).isEmpty();

        HistoryStore store = new HistoryStore();
        service.restoreStore(store, state);
        assertThat(store.isEmpty()).isTrue();
    }

    @Test
    void pinnedStateSurvivesWorkspaceRoundTrip() {
        HistoryPersistenceService service = new HistoryPersistenceService();
        WorkspaceState state = new WorkspaceState();
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "pinned-roundtrip", Instant.parse("2026-06-15T02:50:00Z"));
        entry.pinned = true;
        entry.analystNotes = "Reviewed";
        entry.tags = new LinkedHashSet<>(List.of("Auth", "Evidence"));

        service.writeHistory(state, List.of(entry));
        WorkspaceState restoredState = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));
        HistoryStore store = new HistoryStore();
        service.restoreStore(store, restoredState);

        HistoryEntry restored = store.getById("pinned-roundtrip");
        assertThat(restored.pinned).isTrue();
        assertThat(restored.analystNotes).isEqualTo("Reviewed");
        assertThat(restored.tags).containsExactly("Auth", "Evidence");
        assertThat(restoredState.historyEntries.get(0).pinned).isTrue();
    }

    @Test
    void truncationMetadataSurvivesWorkspaceRoundTrip() {
        HistoryPersistenceService service = new HistoryPersistenceService();
        WorkspaceState state = new WorkspaceState();
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "truncated-roundtrip", Instant.parse("2026-06-15T02:51:00Z"));
        String body = "prefix-TRUNCATION-SUFFIX";
        entry.requestSnapshot.bodyAsAuthored = body.getBytes(StandardCharsets.UTF_8);
        entry.requestSnapshot.rawRequestSent = ("POST /login HTTP/1.1\r\nHost: api.example.test\r\n\r\n" + body)
                .getBytes(StandardCharsets.UTF_8);
        entry.requestSnapshot.rawRequestSentText = new String(entry.requestSnapshot.rawRequestSent, StandardCharsets.UTF_8);
        entry.responseSnapshot.body = body.getBytes(StandardCharsets.UTF_8);
        HistoryBodyTruncator.apply(entry, new HistoryRetentionPolicy(100, 1_000_000, 4, 4, true));

        service.writeHistory(state, List.of(entry));
        WorkspaceState restoredState = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));
        HistoryStore store = new HistoryStore();
        service.restoreStore(store, restoredState);

        HistoryEntry restored = store.getById("truncated-roundtrip");
        assertThat(restored.requestSnapshot.bodyTruncated).isTrue();
        assertThat(restored.requestSnapshot.rawBodyTruncated).isTrue();
        assertThat(restored.responseSnapshot.bodyTruncated).isTrue();
        assertThat(restored.requestSnapshot.fullBodySha256).isEqualTo(entry.requestSnapshot.fullBodySha256);
        assertThat(restoredState.historyEntries.get(0).requestSnapshot.fullRawBodySha256)
                .isEqualTo(entry.requestSnapshot.fullRawBodySha256);
    }
}
