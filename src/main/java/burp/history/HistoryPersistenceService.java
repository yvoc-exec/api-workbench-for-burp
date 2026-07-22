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
        List<HistoryEntry> copies = new ArrayList<>(state.historyEntries.size());
        for (HistoryEntry entry : state.historyEntries) {
            HistoryEntry copy = HistoryEntry.copyOf(entry);
            if (copy != null) {
                copies.add(copy);
            }
        }
        return copies;
    }

    public void writeHistory(WorkspaceState state, Collection<HistoryEntry> entries) {
        if (state == null) {
            return;
        }
        HistoryRetentionPolicy policy = HistoryRetentionPolicy.copyOf(state.historyRetentionPolicy);
        policy.normalize();
        HistoryStore staging = new HistoryStore();
        HistoryAdmissionResult result = staging.restoreAll(entries, policy, false);
        if (!result.accepted() || result.entriesEvicted() > 0) {
            throw persistenceFailure();
        }
        state.historyRetentionPolicy = staging.getRetentionPolicy();
        state.historyRetentionPolicyVersion = currentOrFuturePolicyVersion(state.historyRetentionPolicyVersion);
        state.historyEntries = staging.snapshot();
    }

    public void restoreStore(HistoryStore store, WorkspaceState state) {
        if (store == null) {
            return;
        }
        HistoryRetentionPolicy policy = state != null ? HistoryRetentionPolicy.copyOf(state.historyRetentionPolicy) : HistoryRetentionPolicy.defaultPolicy();
        policy.normalize();
        boolean legacy = state == null
                || state.historyRetentionPolicyVersion == null
                || state.historyRetentionPolicyVersion < HistoryRetentionPolicy.CURRENT_POLICY_VERSION;
        int migratedCount = state != null ? Math.max(0, state.historyLegacyCompactedEntryCount) : 0;
        List<HistoryEntry> restoredEntries = extractHistory(state);
        HistoryAdmissionResult result = legacy
                ? store.restoreAll(restoredEntries, policy, true)
                : store.restoreAll(restoredEntries, policy, false, migratedCount);
        if (!result.accepted()) {
            throw persistenceFailure();
        }
        if (state != null && (state.historyRetentionPolicyVersion == null
                || state.historyRetentionPolicyVersion < HistoryRetentionPolicy.CURRENT_POLICY_VERSION)) {
            state.historyRetentionPolicyVersion = HistoryRetentionPolicy.CURRENT_POLICY_VERSION;
        }
        if (state != null) {
            state.historyLegacyCompactedEntryCount = 0;
        }
    }

    public void writeStore(WorkspaceState state, HistoryStore store) {
        if (state == null || store == null) {
            return;
        }
        state.historyRetentionPolicy = store.getRetentionPolicy();
        state.historyRetentionPolicyVersion = currentOrFuturePolicyVersion(state.historyRetentionPolicyVersion);
        state.historyEntries = store.snapshot();
    }

    private static int currentOrFuturePolicyVersion(Integer version) {
        return version != null && version > HistoryRetentionPolicy.CURRENT_POLICY_VERSION
                ? version
                : HistoryRetentionPolicy.CURRENT_POLICY_VERSION;
    }

    private static IllegalStateException persistenceFailure() {
        return new IllegalStateException(
                "History persistence could not satisfy the configured retention policy.");
    }
}
