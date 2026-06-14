package burp.history;

import burp.models.WorkspaceState;
import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

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
}
