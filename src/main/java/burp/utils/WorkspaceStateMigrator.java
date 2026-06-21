package burp.utils;

import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Minimal workspace migration scaffold.
 *
 * <p>Pass 4 keeps current persisted behavior unchanged while providing a
 * single place for future schema migrations.</p>
 */
public final class WorkspaceStateMigrator {
    public static final int CURRENT_VERSION = 1;

    private WorkspaceStateMigrator() {
    }

    public static WorkspaceState migrate(WorkspaceState state) {
        if (state == null) {
            return null;
        }
        if (state.version <= 0) {
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
        for (EnvironmentProfile profile : state.environments) {
            if (profile != null) {
                profile.ensureDefaults();
            }
        }
        normalizeEnvironmentIds(state);
        return state;
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
            ensureUniqueEnvironmentId(profile, seen);
        }
        if (state.activeEnvironmentId != null && seen.stream().noneMatch(id -> id.equals(state.activeEnvironmentId))) {
            state.activeEnvironmentId = null;
        }
    }

    static void ensureUniqueEnvironmentId(EnvironmentProfile profile, Set<String> seenIds) {
        if (profile == null || seenIds == null) {
            return;
        }
        String candidate = profile.ensureId();
        if (!isUsableUniqueId(candidate, seenIds)) {
            candidate = generateOrdinalUuidCandidate(seenIds.size() + 1);
        }
        if (!isUsableUniqueId(candidate, seenIds)) {
            candidate = generateNameBasedFallback(profile, seenIds.size() + 1);
        }
        if (!isUsableUniqueId(candidate, seenIds)) {
            candidate = "env-fallback-" + Integer.toUnsignedString(seenIds.size() + 1, 36);
        }
        profile.id = candidate;
        seenIds.add(candidate);
    }

    private static boolean isUsableUniqueId(String candidate, Set<String> seenIds) {
        return candidate != null && !candidate.isBlank() && !seenIds.contains(candidate);
    }

    private static String generateOrdinalUuidCandidate(int ordinal) {
        return "env-" + Integer.toUnsignedString(Math.max(1, ordinal), 36) + "-" + java.util.UUID.randomUUID();
    }

    private static String generateNameBasedFallback(EnvironmentProfile profile, int ordinal) {
        String base = profile != null && profile.name != null ? profile.name : "env";
        String normalized = base.trim()
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "")
                .toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            normalized = "env";
        }
        return normalized + "-" + Integer.toUnsignedString(Math.max(1, ordinal), 36);
    }
}
