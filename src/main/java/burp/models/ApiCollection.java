package burp.models;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import burp.scripts.ScriptBlock;

/**
 * Unified API Collection model.
 */
public class ApiCollection {
    private static final Logger LOGGER = Logger.getLogger(ApiCollection.class.getName());

    public String id;
    public String name;
    public String description;
    public String format;         // postman, bruno, openapi, insomnia, har
    public String version;
    /** Format-specific source structures retained losslessly as canonical JSON strings. */
    public Map<String, String> sourceMetadata = new LinkedHashMap<>();
    public List<ApiRequest> requests = new ArrayList<>();
    /** Explicit folder paths preserved for empty/manual folders in the workbench tree. */
    public List<String> folderPaths = new ArrayList<>();
    public List<ApiRequest.Variable> variables = new ArrayList<>();
    /** Bruno folder-scoped variables keyed by normalized folder path. */
    public Map<String, Map<String, String>> folderVars = new LinkedHashMap<>();
    /** Collection-level script blocks in native model form. */
    public List<ScriptBlock> scriptBlocks = new ArrayList<>();
    /** Folder-level script blocks keyed by normalized folder path. */
    public Map<String, List<ScriptBlock>> folderScriptBlocks = new LinkedHashMap<>();
    public Map<String, String> environment = new HashMap<>();
    /** Collection-level effective auth used as the root Postman auth source. */
    public ApiRequest.Auth auth;
    /** Folder auth override modes keyed by normalized folder path. */
    public Map<String, String> folderAuthModes = new LinkedHashMap<>();
    /** Folder auth overrides keyed by normalized folder path. */
    public Map<String, ApiRequest.Auth> folderAuth = new LinkedHashMap<>();

    /** Collection-scoped runtime overrides used by legacy extraction flows. */
    public Map<String, String> runtimeVars = new HashMap<>();
    /** Collection-scoped OAuth2 overrides bound to this collection. */
    public Map<String, String> runtimeOAuth2 = new HashMap<>();
    /** Runtime folder overrides keyed by normalized folder path. */
    public transient Map<String, Map<String, String>> runtimeFolderVars = new LinkedHashMap<>();
    /** Bruno import summary metadata; transient so it does not persist to workspace exports. */
    public transient int importedRequestCount;
    public transient int skippedRequestCount;
    public transient List<String> importWarnings = new ArrayList<>();

    private transient final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public ApiCollection() {
        ensureDefaults();
    }

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

    public String ensureId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    public void ensureDefaults() {
        if (requests == null) requests = new ArrayList<>();
        if (sourceMetadata == null) sourceMetadata = new LinkedHashMap<>();
        if (folderPaths == null) folderPaths = new ArrayList<>();
        if (variables == null) variables = new ArrayList<>();
        if (folderVars == null) folderVars = new LinkedHashMap<>();
        if (scriptBlocks == null) scriptBlocks = new ArrayList<>();
        if (folderScriptBlocks == null) folderScriptBlocks = new LinkedHashMap<>();
        if (environment == null) environment = new LinkedHashMap<>();
        if (folderAuthModes == null) folderAuthModes = new LinkedHashMap<>();
        if (folderAuth == null) folderAuth = new LinkedHashMap<>();
        if (runtimeVars == null) runtimeVars = new LinkedHashMap<>();
        if (runtimeOAuth2 == null) runtimeOAuth2 = new LinkedHashMap<>();
        if (runtimeFolderVars == null) runtimeFolderVars = new LinkedHashMap<>();
        if (importWarnings == null) importWarnings = new ArrayList<>();
    }

    public void fireChanged() {
        for (Runnable r : changeListeners) {
            try {
                r.run();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "ApiCollection change listener failed", e);
            }
        }
    }

    /** Centralized runtime var mutation helpers (guarantee listener coverage). */
    public void putRuntimeVar(String key, String value) {
        ensureDefaults();
        runtimeVars.put(key, value);
        fireChanged();
    }

    public void putAllRuntimeVars(Map<String, String> vars) {
        ensureDefaults();
        if (vars != null && !vars.isEmpty()) {
            runtimeVars.putAll(vars);
            fireChanged();
        }
    }

    public void replaceRuntimeVars(Map<String, String> vars) {
        ensureDefaults();
        runtimeVars.clear();
        if (vars != null && !vars.isEmpty()) {
            runtimeVars.putAll(vars);
        }
        fireChanged();
    }

    /**
     * Applies runtime variable changes without replacing the full map.
     * This preserves unrelated keys that may have been added elsewhere while
     * a request was executing.
     */
    public synchronized void applyRuntimeVarDelta(Map<String, String> changedVars, Set<String> removedKeys) {
        ensureDefaults();
        boolean changed = false;

        if (removedKeys != null) {
            for (String key : removedKeys) {
                if (key != null && runtimeVars.containsKey(key)) {
                    runtimeVars.remove(key);
                    changed = true;
                }
            }
        }

        if (changedVars != null) {
            for (Map.Entry<String, String> entry : changedVars.entrySet()) {
                String key = entry.getKey();
                if (key == null) {
                    continue;
                }
                String value = entry.getValue();
                if (!Objects.equals(runtimeVars.get(key), value)) {
                    runtimeVars.put(key, value);
                    changed = true;
                }
            }
        }

        if (changed) {
            fireChanged();
        }
    }

    public void putRuntimeOAuth2(String key, String value) {
        ensureDefaults();
        runtimeOAuth2.put(key, value);
        fireChanged();
    }

    public void putAllRuntimeOAuth2(Map<String, String> vars) {
        ensureDefaults();
        if (vars != null && !vars.isEmpty()) {
            runtimeOAuth2.putAll(vars);
            fireChanged();
        }
    }

    /** Replaces OAuth2 runtime layer atomically (used for UI mirroring to avoid stale keys). */
    public void replaceRuntimeOAuth2(Map<String, String> vars) {
        ensureDefaults();
        runtimeOAuth2.clear();
        if (vars != null && !vars.isEmpty()) {
            runtimeOAuth2.putAll(vars);
        }
        fireChanged();
    }
}
