package burp.history;

import burp.models.WorkspaceState;
import burp.testsupport.TestResourceLoader;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryPersistenceCompatibilityTest {

    @Test
    void currentHistorySchemaLoadsNewestFirstAndPreservesRawSentEvidence() {
        WorkspaceState state = WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/history/current-history.json"));

        assertThat(state.historyEntries).extracting(entry -> entry.id)
                .containsExactly("hist-current-2", "hist-current-1");

        HistoryEntry rawEntry = state.historyEntries.get(1);
        assertThat(rawEntry.requestSnapshot.hasRawRequestSent()).isTrue();
        assertThat(rawEntry.requestSnapshot.preferredRawRequestText()).contains("POST /login HTTP/1.1");
        assertThat(rawEntry.requestSnapshot.toApiRequest().method).isEqualTo("POST");
        assertThat(rawEntry.requestSnapshot.toApiRequest().url).isEqualTo("{{base_url}}/login");
    }

    @Test
    void legacyHistoryEntriesWithoutRawRequestStillLoadAndRebuildRequestSafely() {
        WorkspaceState state = WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/history/legacy-history.json"));

        assertThat(state.historyEntries).hasSize(1);
        HistoryEntry entry = state.historyEntries.get(0);
        assertThat(entry.requestSnapshot.hasRawRequestSent()).isFalse();
        assertThat(entry.requestSnapshot.preferredRawRequestText()).isEmpty();
        assertThat(entry.requestSnapshot.toApiRequest().method).isEqualTo("POST");
        assertThat(entry.requestSnapshot.toApiRequest().url).isEqualTo("{{base_url}}/legacy-login");
    }

    @Test
    void futureFieldsAndMissingCollectionsOrListsLoadWithoutThrowing() {
        WorkspaceState state = WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/history/future-history.json"));

        assertThat(state.historyEntries).hasSize(1);
        HistoryEntry entry = state.historyEntries.get(0);
        assertThat(entry.result).isEqualTo(HistoryResult.UNKNOWN);
        assertThat(entry.requestSnapshot).isNotNull();
        assertThat(entry.responseSnapshot).isNotNull();
        assertThat(entry.assertions).isNotNull().isEmpty();
        assertThat(entry.scriptLogs).isNotNull().isEmpty();
        assertThat(entry.scriptWarnings).isNotNull().isEmpty();
        assertThat(entry.scriptErrors).isNotNull().isEmpty();
    }

    @Test
    void duplicateHistoryIdsAreDeduplicatedAndRetentionAppliesDuringRestore() {
        WorkspaceState state = WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/history/duplicate-history.json"));
        assertThat(state.historyEntries).hasSize(1);
        assertThat(state.historyEntries.get(0).id).isEqualTo("hist-dup");
        assertThat(state.historyEntries.get(0).requestSnapshot.urlTemplate).isEqualTo("https://api.example.test/new");

        HistoryStore store = new HistoryStore();
        store.setRetentionPolicy(new HistoryRetentionPolicy(1));
        new HistoryPersistenceService().restoreStore(store, state);
        assertThat(store.snapshot()).hasSize(1);
        assertThat(store.snapshot().get(0).id).isEqualTo("hist-dup");
    }

    @Test
    void corruptAndTruncatedHistoryJsonFailFast() {
        assertThatThrownBy(() -> WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/history/corrupt-history.json")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/history/truncated-history.json")))
                .isInstanceOf(RuntimeException.class);
    }
}
