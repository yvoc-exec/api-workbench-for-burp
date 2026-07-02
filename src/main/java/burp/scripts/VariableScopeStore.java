package burp.scripts;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;
import burp.utils.RequestPathResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class VariableScopeStore {
    private final ApiCollection collection;
    private final ApiRequest request;
    private final EnvironmentProfile activeEnvironment;
    private final boolean activeEnvironmentProvided;

    private final Map<String, String> collectionBase = new LinkedHashMap<>();
    private final Map<String, String> folderBase = new LinkedHashMap<>();
    private final Map<String, String> collectionRuntime = new LinkedHashMap<>();
    private final Map<String, String> oauth2Runtime = new LinkedHashMap<>();
    private final Map<String, String> environmentRuntime = new LinkedHashMap<>();
    private final Map<String, String> requestBase = new LinkedHashMap<>();
    private final Map<String, String> requestRuntime = new LinkedHashMap<>();
    private final Map<String, String> localRuntime = new LinkedHashMap<>();
    private final Map<String, String> globalRuntime = new LinkedHashMap<>();

    private final VariableResolver resolver = new VariableResolver();

    public VariableScopeStore(ApiCollection collection, ApiRequest request, EnvironmentProfile activeEnvironment) {
        this.collection = collection;
        this.request = request;
        this.activeEnvironment = activeEnvironment;
        this.activeEnvironmentProvided = activeEnvironment != null;
        seed();
    }

    public VariableResolver resolver() {
        return resolver;
    }

    public synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(resolver.getVariables());
    }

    public synchronized String resolve(String input) {
        return resolver.resolve(input);
    }

    public synchronized boolean has(String key) {
        return resolver.getVariables().containsKey(key);
    }

    public synchronized String get(String key) {
        return resolver.getVariables().get(key);
    }

    public synchronized ScriptVariableMutation setLocal(String key, String value, String scriptId, String scriptName) {
        return mutate(localRuntime, "local", false, key, value, scriptId, scriptName);
    }

    public synchronized ScriptVariableMutation unsetLocal(String key, String scriptId, String scriptName) {
        return remove(localRuntime, "local", false, key, scriptId, scriptName);
    }

    public synchronized ScriptVariableMutation setRequest(String key, String value, String scriptId, String scriptName) {
        return mutate(requestRuntime, "request", false, key, value, scriptId, scriptName);
    }

    public synchronized ScriptVariableMutation unsetRequest(String key, String scriptId, String scriptName) {
        return remove(requestRuntime, "request", false, key, scriptId, scriptName);
    }

    public synchronized ScriptVariableMutation setCollection(String key, String value, boolean persist, String scriptId, String scriptName) {
        if (collection != null) {
            if (collection.runtimeVars == null) {
                collection.runtimeVars = new LinkedHashMap<>();
            }
            collection.runtimeVars.put(key, value != null ? value : "");
        }
        return mutate(collectionRuntime, "collection", persist, key, value, scriptId, scriptName);
    }

    public synchronized ScriptVariableMutation unsetCollection(String key, boolean persist, String scriptId, String scriptName) {
        if (collection != null && collection.runtimeVars != null) {
            collection.runtimeVars.remove(key);
        }
        return remove(collectionRuntime, "collection", persist, key, scriptId, scriptName);
    }

    public synchronized ScriptVariableMutation setFolder(String key, String value, boolean persist, String scriptId, String scriptName) {
        if (collection != null && request != null) {
            String folderPath = RequestPathResolver.getRequestFolderPath(collection, request);
            if (folderPath != null && !folderPath.isBlank()) {
                if (collection.folderVars == null) {
                    collection.folderVars = new LinkedHashMap<>();
                }
                Map<String, String> folder = collection.folderVars.computeIfAbsent(folderPath, k -> new LinkedHashMap<>());
                folder.put(key, value != null ? value : "");
            }
        }
        return mutate(folderBase, "folder", persist, key, value, scriptId, scriptName);
    }

    public synchronized ScriptVariableMutation unsetFolder(String key, boolean persist, String scriptId, String scriptName) {
        if (collection != null && request != null && collection.folderVars != null) {
            String folderPath = RequestPathResolver.getRequestFolderPath(collection, request);
            if (folderPath != null && !folderPath.isBlank()) {
                Map<String, String> folder = collection.folderVars.get(folderPath);
                if (folder != null) {
                    folder.remove(key);
                }
            }
        }
        return remove(folderBase, "folder", persist, key, scriptId, scriptName);
    }

    public synchronized ScriptVariableMutation setEnvironment(String key, String value, boolean persist, String scriptId, String scriptName) {
        if (activeEnvironment != null) {
            activeEnvironment.ensureDefaults();
            activeEnvironment.variables.put(key, value != null ? value : "");
        }
        return mutate(environmentRuntime, "environment", persist, key, value, scriptId, scriptName);
    }

    public synchronized ScriptVariableMutation unsetEnvironment(String key, boolean persist, String scriptId, String scriptName) {
        if (activeEnvironment != null && activeEnvironment.variables != null) {
            activeEnvironment.variables.remove(key);
        }
        return remove(environmentRuntime, "environment", persist, key, scriptId, scriptName);
    }

    public synchronized ScriptVariableMutation setGlobal(String key, String value, String scriptId, String scriptName) {
        return mutate(globalRuntime, "global", false, key, value, scriptId, scriptName);
    }

    public synchronized ScriptVariableMutation unsetGlobal(String key, String scriptId, String scriptName) {
        return remove(globalRuntime, "global", false, key, scriptId, scriptName);
    }

    public synchronized Map<String, String> getEnvironmentLayer() {
        return new LinkedHashMap<>(environmentRuntime);
    }

    public synchronized Map<String, String> getCollectionLayer() {
        return new LinkedHashMap<>(collectionRuntime);
    }

    public synchronized Map<String, String> getLocalLayer() {
        return new LinkedHashMap<>(localRuntime);
    }

    public synchronized Map<String, String> getRequestLayer() {
        return new LinkedHashMap<>(requestRuntime);
    }

    public synchronized Map<String, String> getFolderLayer() {
        return new LinkedHashMap<>(folderBase);
    }

    private void seed() {
        if (collection != null) {
            if (collection.environment != null) {
                collectionBase.putAll(collection.environment);
            }
            if (collection.variables != null) {
                for (ApiRequest.Variable variable : collection.variables) {
                    if (variable == null || variable.key == null || variable.key.isBlank()) {
                        continue;
                    }
                    if (variable.value != null) {
                        collectionBase.put(variable.key, variable.value);
                    }
                }
            }
            if (collection.folderVars != null && request != null) {
                String folderPath = RequestPathResolver.getRequestFolderPath(collection, request);
                if (folderPath != null && !folderPath.isBlank()) {
                    List<String> seen = new ArrayList<>();
                    StringBuilder current = new StringBuilder();
                    for (String part : folderPath.split("/")) {
                        if (part == null || part.isBlank()) {
                            continue;
                        }
                        if (current.length() > 0) {
                            current.append("/");
                        }
                        current.append(part.trim());
                        String path = current.toString();
                        if (seen.contains(path)) {
                            continue;
                        }
                        seen.add(path);
                        Map<String, String> vars = collection.folderVars.get(path);
                        if (vars != null) {
                            folderBase.putAll(vars);
                        }
                    }
                }
            }
            if (collection.runtimeVars != null) {
                collectionRuntime.putAll(collection.runtimeVars);
            }
            if (collection.runtimeOAuth2 != null) {
                oauth2Runtime.putAll(collection.runtimeOAuth2);
            }
        }
        if (activeEnvironment != null) {
            environmentRuntime.putAll(activeEnvironment.toRuntimeOverlay());
        }
        if (request != null && request.variables != null) {
            for (ApiRequest.Variable variable : request.variables) {
                if (variable == null || variable.key == null || variable.key.isBlank()) {
                    continue;
                }
                if (variable.value != null) {
                    requestBase.put(variable.key, variable.value);
                }
            }
        }
        rebuildResolver();
    }

    private ScriptVariableMutation mutate(Map<String, String> layer,
                                          String scope,
                                          boolean persistent,
                                          String key,
                                          String value,
                                          String scriptId,
                                          String scriptName) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalizedValue = value != null ? value : "";
        String oldValue = resolver.getVariables().get(key);
        layer.put(key, normalizedValue);
        rebuildResolver();
        ScriptVariableMutation mutation = new ScriptVariableMutation(key, oldValue, normalizedValue, scope, persistent);
        mutation.sourceScriptId = scriptId;
        mutation.sourceScriptName = scriptName;
        return mutation;
    }

    private ScriptVariableMutation remove(Map<String, String> layer,
                                          String scope,
                                          boolean persistent,
                                          String key,
                                          String scriptId,
                                          String scriptName) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String oldValue = resolver.getVariables().get(key);
        layer.remove(key);
        rebuildResolver();
        ScriptVariableMutation mutation = new ScriptVariableMutation(key, oldValue, null, scope, persistent);
        mutation.sourceScriptId = scriptId;
        mutation.sourceScriptName = scriptName;
        return mutation;
    }

    private void rebuildResolver() {
        resolver.clear();
        resolver.addAll(collectionBase);
        resolver.addAll(folderBase);
        resolver.addAll(collectionRuntime);
        resolver.addAll(oauth2Runtime);
        resolver.addAll(environmentRuntime);
        resolver.addAll(requestBase);
        resolver.addAll(requestRuntime);
        resolver.addAll(localRuntime);
        resolver.addAll(globalRuntime);
    }
}
