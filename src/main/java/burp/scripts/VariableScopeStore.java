package burp.scripts;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;
import burp.utils.RequestPathResolver;
import burp.utils.RuntimeResolverFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VariableScopeStore {
    private final ApiCollection collection;
    private final ApiRequest request;
    private final EnvironmentProfile activeEnvironment;
    private final boolean activeEnvironmentProvided;

    private final Map<String, String> collectionEnvironmentDefaults = new LinkedHashMap<>();
    private final List<ApiRequest.Variable> authoredCollectionVariables = new ArrayList<>();
    private final Map<String, Map<String, String>> authoredFolderVars = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> runtimeFolderVars = new LinkedHashMap<>();
    private final Map<String, String> collectionRuntimeOAuth2 = new LinkedHashMap<>();
    private final Map<String, String> collectionRuntimeVars = new LinkedHashMap<>();
    private final Map<String, String> environmentPersisted = new LinkedHashMap<>();
    private final Map<String, String> environmentRuntime = new LinkedHashMap<>();
    private final Map<String, String> executionOverlay = new LinkedHashMap<>();
    private final List<ApiRequest.Variable> authoredRequestVariables = new ArrayList<>();
    private final Map<String, String> requestRuntime = new LinkedHashMap<>();
    private final Map<String, String> localRuntime = new LinkedHashMap<>();
    private final Map<String, String> globalRuntime = new LinkedHashMap<>();

    private final VariableResolver resolver = new VariableResolver();

    public VariableScopeStore(ApiCollection collection, ApiRequest request, EnvironmentProfile activeEnvironment) {
        this(collection, request, activeEnvironment, null);
    }

    public VariableScopeStore(ApiCollection collection,
                              ApiRequest request,
                              EnvironmentProfile activeEnvironment,
                              Map<String, String> runtimeOverlay) {
        this.collection = collection;
        this.request = request;
        this.activeEnvironment = activeEnvironment;
        this.activeEnvironmentProvided = activeEnvironment != null;
        seed(runtimeOverlay);
    }

    public VariableResolver resolver() {
        return resolver;
    }

    public synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(resolver.getVariables());
    }

    public synchronized Map<String, String> effectiveVariablesSnapshot() {
        return new LinkedHashMap<>(resolver.getVariables());
    }

    public synchronized Snapshot checkpoint() {
        return new Snapshot(
                collectionEnvironmentDefaults,
                authoredCollectionVariables,
                authoredFolderVars,
                runtimeFolderVars,
                collectionRuntimeOAuth2,
                collectionRuntimeVars,
                environmentPersisted,
                environmentRuntime,
                executionOverlay,
                authoredRequestVariables,
                requestRuntime,
                localRuntime,
                globalRuntime
        );
    }

    public synchronized void restore(Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        replaceMap(collectionEnvironmentDefaults, snapshot.collectionEnvironmentDefaults);
        replaceVariables(authoredCollectionVariables, snapshot.authoredCollectionVariables);
        replaceNestedMap(authoredFolderVars, snapshot.authoredFolderVars);
        replaceNestedMap(runtimeFolderVars, snapshot.runtimeFolderVars);
        replaceMap(collectionRuntimeOAuth2, snapshot.collectionRuntimeOAuth2);
        replaceMap(collectionRuntimeVars, snapshot.collectionRuntimeVars);
        replaceMap(environmentPersisted, snapshot.environmentPersisted);
        replaceMap(environmentRuntime, snapshot.environmentRuntime);
        replaceMap(executionOverlay, snapshot.executionOverlay);
        replaceVariables(authoredRequestVariables, snapshot.authoredRequestVariables);
        replaceMap(requestRuntime, snapshot.requestRuntime);
        replaceMap(localRuntime, snapshot.localRuntime);
        replaceMap(globalRuntime, snapshot.globalRuntime);
        rebuildResolver();
    }

    public synchronized void refreshRequestState() {
        replaceVariables(authoredRequestVariables, copyVariables(request != null ? request.variables : null));
        rebuildResolver();
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
        String oldValue = resolver.getVariables().get(key);
        if (persist) {
            updateAuthoredCollectionVariable(key, value);
        } else {
            mutate(collectionRuntimeVars, "collection", false, key, value, scriptId, scriptName);
        }
        return createMutation(key, oldValue, value != null ? value : "", persist, "collection", scriptId, scriptName);
    }

    public synchronized ScriptVariableMutation unsetCollection(String key, boolean persist, String scriptId, String scriptName) {
        String oldValue = resolver.getVariables().get(key);
        if (persist) {
            removeAuthoredCollectionVariable(key);
        } else {
            remove(collectionRuntimeVars, "collection", false, key, scriptId, scriptName);
        }
        return createMutation(key, oldValue, null, persist, "collection", scriptId, scriptName);
    }

    public synchronized ScriptVariableMutation setFolder(String key, String value, boolean persist, String scriptId, String scriptName) {
        String oldValue = resolver.getVariables().get(key);
        String folderPath = currentFolderPath();
        if (!folderPath.isBlank()) {
            if (persist) {
                mutateNested(authoredFolderVars, folderPath, key, value);
            } else {
                mutateNested(runtimeFolderVars, folderPath, key, value);
            }
        }
        ScriptVariableMutation mutation = createMutation(key, oldValue, value != null ? value : "", persist, "folder", scriptId, scriptName);
        mutation.scopePath = folderPath;
        return mutation;
    }

    public synchronized ScriptVariableMutation unsetFolder(String key, boolean persist, String scriptId, String scriptName) {
        String oldValue = resolver.getVariables().get(key);
        String folderPath = currentFolderPath();
        if (!folderPath.isBlank()) {
            if (persist) {
                removeNested(authoredFolderVars, folderPath, key);
            } else {
                removeNested(runtimeFolderVars, folderPath, key);
            }
        }
        ScriptVariableMutation mutation = createMutation(key, oldValue, null, persist, "folder", scriptId, scriptName);
        mutation.scopePath = folderPath;
        return mutation;
    }

    public synchronized ScriptVariableMutation setEnvironment(String key, String value, boolean persist, String scriptId, String scriptName) {
        String oldValue = resolver.getVariables().get(key);
        if (persist) {
            mutate(environmentPersisted, "environment", true, key, value, scriptId, scriptName);
        } else {
            mutate(environmentRuntime, "environment", false, key, value, scriptId, scriptName);
        }
        return createMutation(key, oldValue, value != null ? value : "", persist, "environment", scriptId, scriptName);
    }

    public synchronized ScriptVariableMutation unsetEnvironment(String key, boolean persist, String scriptId, String scriptName) {
        String oldValue = resolver.getVariables().get(key);
        if (persist) {
            remove(environmentPersisted, "environment", true, key, scriptId, scriptName);
        } else {
            remove(environmentRuntime, "environment", false, key, scriptId, scriptName);
        }
        return createMutation(key, oldValue, null, persist, "environment", scriptId, scriptName);
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
        return new LinkedHashMap<>(collectionRuntimeVars);
    }

    public synchronized Map<String, String> getLocalLayer() {
        return new LinkedHashMap<>(localRuntime);
    }

    public synchronized Map<String, String> getRequestLayer() {
        return new LinkedHashMap<>(requestRuntime);
    }

    public synchronized Map<String, String> getFolderLayer() {
        return new LinkedHashMap<>(runtimeFolderVars.getOrDefault(currentFolderPath(), new LinkedHashMap<>()));
    }

    public static final class Snapshot {
        private final Map<String, String> collectionEnvironmentDefaults;
        private final List<ApiRequest.Variable> authoredCollectionVariables;
        private final Map<String, Map<String, String>> authoredFolderVars;
        private final Map<String, Map<String, String>> runtimeFolderVars;
        private final Map<String, String> collectionRuntimeOAuth2;
        private final Map<String, String> collectionRuntimeVars;
        private final Map<String, String> environmentPersisted;
        private final Map<String, String> environmentRuntime;
        private final Map<String, String> executionOverlay;
        private final List<ApiRequest.Variable> authoredRequestVariables;
        private final Map<String, String> requestRuntime;
        private final Map<String, String> localRuntime;
        private final Map<String, String> globalRuntime;

        private Snapshot(Map<String, String> collectionEnvironmentDefaults,
                         List<ApiRequest.Variable> authoredCollectionVariables,
                         Map<String, Map<String, String>> authoredFolderVars,
                         Map<String, Map<String, String>> runtimeFolderVars,
                         Map<String, String> collectionRuntimeOAuth2,
                         Map<String, String> collectionRuntimeVars,
                         Map<String, String> environmentPersisted,
                         Map<String, String> environmentRuntime,
                         Map<String, String> executionOverlay,
                         List<ApiRequest.Variable> authoredRequestVariables,
                         Map<String, String> requestRuntime,
                         Map<String, String> localRuntime,
                         Map<String, String> globalRuntime) {
            this.collectionEnvironmentDefaults = copyMap(collectionEnvironmentDefaults);
            this.authoredCollectionVariables = copyVariables(authoredCollectionVariables);
            this.authoredFolderVars = copyNestedMap(authoredFolderVars);
            this.runtimeFolderVars = copyNestedMap(runtimeFolderVars);
            this.collectionRuntimeOAuth2 = copyMap(collectionRuntimeOAuth2);
            this.collectionRuntimeVars = copyMap(collectionRuntimeVars);
            this.environmentPersisted = copyMap(environmentPersisted);
            this.environmentRuntime = copyMap(environmentRuntime);
            this.executionOverlay = copyMap(executionOverlay);
            this.authoredRequestVariables = copyVariables(authoredRequestVariables);
            this.requestRuntime = copyMap(requestRuntime);
            this.localRuntime = copyMap(localRuntime);
            this.globalRuntime = copyMap(globalRuntime);
        }
    }

    private void seed(Map<String, String> runtimeOverlay) {
        if (collection != null) {
            collection.ensureDefaults();
            replaceMap(collectionEnvironmentDefaults, collection.environment);
            replaceVariables(authoredCollectionVariables, copyVariables(collection.variables));
            replaceNestedMap(authoredFolderVars, collection.folderVars);
            replaceMap(collectionRuntimeOAuth2, collection.runtimeOAuth2);
            replaceMap(collectionRuntimeVars, collection.runtimeVars);
            replaceNestedMap(runtimeFolderVars, collection.runtimeFolderVars);
        }
        if (activeEnvironment != null) {
            activeEnvironment.ensureDefaults();
            replaceMap(environmentPersisted, activeEnvironment.toPersistedOverlay());
            replaceMap(environmentRuntime, activeEnvironment.runtimeVariables);
        }
        replaceMap(executionOverlay, runtimeOverlay);
        refreshRequestState();
        rebuildResolver();
    }

    private ScriptVariableMutation createMutation(String key, String oldValue, String value, boolean persistent, String scope, String scriptId, String scriptName) {
        if (key == null || key.isBlank()) {
            return null;
        }
        ScriptVariableMutation mutation = new ScriptVariableMutation(key, oldValue, value, scope, persistent);
        mutation.sourceScriptId = scriptId;
        mutation.sourceScriptName = scriptName;
        return mutation;
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

    private void updateAuthoredCollectionVariable(String key, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        String normalizedValue = value != null ? value : "";
        for (ApiRequest.Variable variable : authoredCollectionVariables) {
            if (variable != null && key.equals(variable.key)) {
                variable.value = normalizedValue;
                rebuildResolver();
                return;
            }
        }
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = normalizedValue;
        variable.type = "string";
        variable.enabled = true;
        authoredCollectionVariables.add(variable);
        rebuildResolver();
    }

    private void removeAuthoredCollectionVariable(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        authoredCollectionVariables.removeIf(variable -> variable != null && key.equals(variable.key));
        rebuildResolver();
    }

    private void mutateNested(Map<String, Map<String, String>> nested, String path, String key, String value) {
        if (nested == null || path == null || path.isBlank() || key == null || key.isBlank()) {
            return;
        }
        Map<String, String> layer = nested.computeIfAbsent(path, ignored -> new LinkedHashMap<>());
        layer.put(key, value != null ? value : "");
        rebuildResolver();
    }

    private void removeNested(Map<String, Map<String, String>> nested, String path, String key) {
        if (nested == null || path == null || path.isBlank() || key == null || key.isBlank()) {
            return;
        }
        Map<String, String> layer = nested.get(path);
        if (layer == null) {
            return;
        }
        layer.remove(key);
        if (layer.isEmpty()) {
            nested.remove(path);
        }
        rebuildResolver();
    }

    private String currentFolderPath() {
        return RequestPathResolver.normalizeFolderPath(RequestPathResolver.getRequestFolderPath(collection, request));
    }

    private void rebuildResolver() {
        resolver.clear();
        addMap(collectionEnvironmentDefaults);
        addVariables(authoredCollectionVariables);
        addFolderLayer(authoredFolderVars);
        addFolderLayer(runtimeFolderVars);
        addMap(collectionRuntimeOAuth2);
        addMap(collectionRuntimeVars);
        addMap(environmentPersisted);
        addMap(environmentRuntime);
        addMap(executionOverlay);
        addVariables(authoredRequestVariables);
        addMap(requestRuntime);
        addMap(localRuntime);
        addMap(globalRuntime);
        if (request != null && request.auth != null) {
            Map<String, String> authVars = burp.utils.OAuth2RuntimeMapper.mapAuthToVars(request.auth, resolver.getVariables(), true);
            authVars.entrySet().removeIf(entry -> entry.getValue() != null && entry.getValue().matches("^\\s*\\{\\{[^}|]+(?:\\|[^}]+)?\\}\\}\\s*$"));
            if (!authVars.isEmpty()) {
                resolver.addAll(authVars);
            }
        }
    }

    private void addMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        resolver.addAll(values);
    }

    private void addVariables(List<ApiRequest.Variable> variables) {
        if (variables == null || variables.isEmpty()) {
            return;
        }
        for (ApiRequest.Variable variable : variables) {
            if (variable == null || variable.key == null || variable.value == null) {
                continue;
            }
            resolver.addCustomVariable(variable.key, variable.value);
        }
    }

    private void addFolderLayer(Map<String, Map<String, String>> folderVars) {
        if (folderVars == null || folderVars.isEmpty()) {
            return;
        }
        List<String> ancestors = RuntimeResolverFactory.normalizedFolderAncestors(collection, request);
        for (String path : ancestors) {
            Map<String, String> values = folderVars.get(path);
            if (values != null && !values.isEmpty()) {
                resolver.addAll(values);
            }
        }
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        return source != null ? new LinkedHashMap<>(source) : new LinkedHashMap<>();
    }

    private static Map<String, Map<String, String>> copyNestedMap(Map<String, Map<String, String>> source) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (source == null) {
            return out;
        }
        for (Map.Entry<String, Map<String, String>> entry : source.entrySet()) {
            out.put(entry.getKey(), entry.getValue() != null ? new LinkedHashMap<>(entry.getValue()) : new LinkedHashMap<>());
        }
        return out;
    }

    private static List<ApiRequest.Variable> copyVariables(List<ApiRequest.Variable> source) {
        List<ApiRequest.Variable> out = new ArrayList<>();
        if (source == null) {
            return out;
        }
        for (ApiRequest.Variable variable : source) {
            if (variable == null) {
                continue;
            }
            ApiRequest.Variable copy = new ApiRequest.Variable();
            copy.key = variable.key;
            copy.value = variable.value;
            copy.type = variable.type;
            copy.enabled = variable.enabled;
            out.add(copy);
        }
        return out;
    }

    private static void replaceMap(Map<String, String> target, Map<String, String> source) {
        target.clear();
        if (source != null && !source.isEmpty()) {
            target.putAll(source);
        }
    }

    private static void replaceVariables(List<ApiRequest.Variable> target, List<ApiRequest.Variable> source) {
        target.clear();
        if (source != null && !source.isEmpty()) {
            target.addAll(copyVariables(source));
        }
    }

    private static void replaceNestedMap(Map<String, Map<String, String>> target, Map<String, Map<String, String>> source) {
        target.clear();
        if (source != null && !source.isEmpty()) {
            for (Map.Entry<String, Map<String, String>> entry : source.entrySet()) {
                target.put(entry.getKey(), entry.getValue() != null ? new LinkedHashMap<>(entry.getValue()) : new LinkedHashMap<>());
            }
        }
    }
}
