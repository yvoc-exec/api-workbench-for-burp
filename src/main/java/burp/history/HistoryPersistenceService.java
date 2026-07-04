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
        HistoryRetentionPolicy policy = HistoryRetentionPolicy.copyOf(state.historyRetentionPolicy);
        policy.normalize();
        try {
            return HistoryStore.normalizeEntries(state.historyEntries, policy);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void writeHistory(WorkspaceState state, Collection<HistoryEntry> entries) {
        if (state == null) {
            return;
        }
        HistoryRetentionPolicy policy = HistoryRetentionPolicy.copyOf(state.historyRetentionPolicy);
        policy.normalize();
        state.historyRetentionPolicy = policy;
        state.historyEntries = HistoryStore.normalizeEntries(entries, policy);
    }

    public void restoreStore(HistoryStore store, WorkspaceState state) {
        if (store == null) {
            return;
        }
        HistoryRetentionPolicy policy = state != null ? HistoryRetentionPolicy.copyOf(state.historyRetentionPolicy) : HistoryRetentionPolicy.defaultPolicy();
        policy.normalize();
        store.setRetentionPolicy(policy);
        store.replaceAll(extractHistory(state));
    }

    public void writeStore(WorkspaceState state, HistoryStore store) {
        if (state == null || store == null) {
            return;
        }
        state.historyRetentionPolicy = store.getRetentionPolicy();
        state.historyEntries = store.snapshot();
    }
}
