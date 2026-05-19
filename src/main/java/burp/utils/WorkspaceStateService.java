package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import burp.models.WorkspaceState;

public class WorkspaceStateService {
    private static final String KEY = "api_workbench_workspace_state_json";
    private static final String POLICY_KEY = "api_workbench_workspace_sensitive_persistence_opt_in";

    public interface StringStore {
        String get(String key);
        void set(String key, String value);
    }

    private final StringStore store;

    public WorkspaceStateService(MontoyaApi api) {
        this(api != null ? new MontoyaStringStore(api.persistence() != null ? api.persistence().extensionData() : null) : null);
    }

    public WorkspaceStateService(PersistedObject object) {
        this(object != null ? new MontoyaStringStore(object) : null);
    }

    WorkspaceStateService(StringStore store) {
        this.store = store;
    }

    public WorkspaceState load() {
        if (store == null) {
            return new WorkspaceState();
        }
        return WorkspaceStateJson.fromJson(store.get(KEY));
    }

    public void save(WorkspaceState state) {
        if (store == null) {
            return;
        }
        store.set(KEY, WorkspaceStateJson.toJson(state));
    }

    public Boolean loadSensitivePersistenceOptIn() {
        if (store == null) {
            return null;
        }
        String raw = store.get(POLICY_KEY);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    public void saveSensitivePersistenceOptIn(boolean allowSensitivePersistence) {
        if (store == null) {
            return;
        }
        store.set(POLICY_KEY, String.valueOf(allowSensitivePersistence));
    }

    private static class MontoyaStringStore implements StringStore {
        private final PersistedObject object;

        MontoyaStringStore(PersistedObject object) {
            this.object = object;
        }

        @Override
        public String get(String key) {
            return object != null ? object.getString(key) : null;
        }

        @Override
        public void set(String key, String value) {
            if (object != null) {
                object.setString(key, value != null ? value : "");
            }
        }
    }
}
