package burp.history;

import burp.models.WorkspaceState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class HistoryPersistenceService {
    public List<HistoryEntry> extractHistory(WorkspaceState state) {
        if (state == null || state.historyEntries == null) {
            return new ArrayList<>();
        }
        try {
            return HistoryStore.normalizeEntries(state.historyEntries, HistoryRetentionPolicy.defaultPolicy());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void writeHistory(WorkspaceState state, Collection<HistoryEntry> entries) {
        if (state == null) {
            return;
        }
        state.historyEntries = HistoryStore.normalizeEntries(entries, HistoryRetentionPolicy.defaultPolicy());
    }

    public void restoreStore(HistoryStore store, WorkspaceState state) {
        if (store == null) {
            return;
        }
        store.replaceAll(extractHistory(state));
    }

    public void writeStore(WorkspaceState state, HistoryStore store) {
        if (state == null || store == null) {
            return;
        }
        state.historyEntries = store.snapshot();
    }
}
