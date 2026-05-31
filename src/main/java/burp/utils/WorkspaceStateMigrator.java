package burp.utils;

import burp.models.WorkspaceState;

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
        return state;
    }
}
