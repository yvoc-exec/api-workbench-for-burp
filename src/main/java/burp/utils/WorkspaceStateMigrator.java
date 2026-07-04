package burp.utils;

import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.models.ApiCollection;
import burp.history.HistoryEntry;
import burp.history.HistoryRetentionPolicy;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Minimal workspace migration scaffold.
 *
 * <p>Pass 4 keeps current persisted behavior unchanged while providing a
 * single place for future schema migrations.</p>
 */
public final class WorkspaceStateMigrator {
    public static final int CURRENT_VERSION = 2;

    private WorkspaceStateMigrator() {
    }

    public static WorkspaceState migrate(WorkspaceState state) {
        if (state == null) {
            return null;
        }
        if (state.version < CURRENT_VERSION) {
            state.version = CURRENT_VERSION;
        }
        if (state.collections == null) {
            state.collections = new java.util.ArrayList<>();
        }
        if (state.environments == null) {
            state.environments = new java.util.ArrayList<>();
        }
        if (state.historyEntries == null) {
            state.historyEntries = new java.util.ArrayList<>();
        }
        if (state.historyRetentionPolicy == null) {
            state.historyRetentionPolicy = HistoryRetentionPolicy.defaultPolicy();
        } else {
            state.historyRetentionPolicy = HistoryRetentionPolicy.copyOf(state.historyRetentionPolicy);
        }
        state.historyRetentionPolicy.normalize();
        for (HistoryEntry entry : state.historyEntries) {
            if (entry != null) {
                entry.ensureDefaults();
            }
        }
        for (EnvironmentProfile profile : state.environments) {
            if (profile != null) {
                profile.ensureDefaults();
            }
        }
        normalizeCollectionIds(state);
        normalizeEnvironmentIds(state);
        return state;
    }

    private static void normalizeCollectionIds(WorkspaceState state) {
        if (state == null || state.collections == null) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (ApiCollection collection : state.collections) {
            if (collection == null) {
                continue;
            }
            collection.ensureId();
            while (collection.id != null && seen.contains(collection.id)) {
                collection.id = java.util.UUID.randomUUID().toString();
            }
            if (collection.id != null) {
                seen.add(collection.id);
            }
        }
    }

    private static void normalizeEnvironmentIds(WorkspaceState state) {
        if (state == null || state.environments == null) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < state.environments.size(); i++) {
            EnvironmentProfile profile = state.environments.get(i);
            if (profile == null) {
                continue;
            }
            profile.ensureDefaults();
            String id = profile.id;
            if (id == null || id.isBlank() || seen.contains(id)) {
                profile.ensureId();
                while (profile.id != null && seen.contains(profile.id)) {
                    profile.id = java.util.UUID.randomUUID().toString();
                }
            }
            if (profile.id != null) {
                seen.add(profile.id);
            }
        }
        if (state.activeEnvironmentId != null && seen.stream().noneMatch(id -> id.equals(state.activeEnvironmentId))) {
            state.activeEnvironmentId = null;
        }
    }
}
