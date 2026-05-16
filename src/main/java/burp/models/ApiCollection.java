package burp.models;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Unified API Collection model.
 */
public class ApiCollection {
    public String name;
    public String description;
    public String format;         // postman, bruno, openapi, insomnia, har
    public String version;
    public List<ApiRequest> requests = new ArrayList<>();
    public List<ApiRequest.Variable> variables = new ArrayList<>();
    public Map<String, String> environment = new HashMap<>();

    /** Collection-scoped runtime overrides (Variables tab / env file bound to this collection). */
    public Map<String, String> runtimeVars = new HashMap<>();
    /** Collection-scoped OAuth2 overrides bound to this collection. */
    public Map<String, String> runtimeOAuth2 = new HashMap<>();

    private transient final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public int getEnabledRequestCount() {
        return (int) requests.stream().filter(r -> !r.disabled).count();
    }

    public void addChangeListener(Runnable listener) {
        if (listener != null) changeListeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        if (listener != null) changeListeners.remove(listener);
    }

    public void clearChangeListeners() {
        changeListeners.clear();
    }

    public void fireChanged() {
        for (Runnable r : changeListeners) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }

    /** Centralized runtime var mutation helpers (guarantee listener coverage). */
    public void putRuntimeVar(String key, String value) {
        runtimeVars.put(key, value);
        fireChanged();
    }

    public void putAllRuntimeVars(Map<String, String> vars) {
        if (vars != null && !vars.isEmpty()) {
            runtimeVars.putAll(vars);
            fireChanged();
        }
    }

    public void putRuntimeOAuth2(String key, String value) {
        runtimeOAuth2.put(key, value);
        fireChanged();
    }

    public void putAllRuntimeOAuth2(Map<String, String> vars) {
        if (vars != null && !vars.isEmpty()) {
            runtimeOAuth2.putAll(vars);
            fireChanged();
        }
    }

    /** Replaces OAuth2 runtime layer atomically (used for UI mirroring to avoid stale keys). */
    public void replaceRuntimeOAuth2(Map<String, String> vars) {
        runtimeOAuth2.clear();
        if (vars != null && !vars.isEmpty()) {
            runtimeOAuth2.putAll(vars);
        }
        fireChanged();
    }
}
