package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.scripts.ScriptExecutionResult;
import burp.scripts.ScriptVariableMutation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptVariableScopeCommitTest {

    @Test
    void nonPersistentEnvironmentWritesRuntimeLayerOnly() {
        EnvironmentProfile environment = environment();
        commit(environment, mutation("token", "runtime", "environment", false));

        assertThat(environment.runtimeVariables).containsEntry("token", "runtime");
        assertThat(environment.variables).doesNotContainKey("token");
    }

    @Test
    void environmentMutationUpdatesOnlyEnvironmentScope() {
        EnvironmentProfile environment = environment();
        commit(environment, mutation("token", "runtime", "environment", false));

        assertThat(environment.runtimeVariables).containsEntry("token", "runtime");
        assertThat(environment.variables).doesNotContainKey("token");
    }

    @Test
    void persistentEnvironmentWritesPersistedLayerOnly() {
        EnvironmentProfile environment = environment();
        commit(environment, mutation("token", "persisted", "environment", true));

        assertThat(environment.variables).containsEntry("token", "persisted");
        assertThat(environment.runtimeVariables).doesNotContainKey("token");
    }

    @Test
    void persistentEnvironmentMutationUpdatesOnlyEnvironmentScope() {
        EnvironmentProfile environment = environment();
        commit(environment, mutation("token", "persisted", "environment", true));

        assertThat(environment.variables).containsEntry("token", "persisted");
        assertThat(environment.runtimeVariables).doesNotContainKey("token");
    }

    @Test
    void nonPersistentCollectionWritesRuntimeVarsOnly() {
        ApiCollection collection = collection();
        commit(collection, mutation("token", "runtime", "collection", false));

        assertThat(collection.runtimeVars).containsEntry("token", "runtime");
        assertThat(collection.variables).extracting(variable -> variable.key).doesNotContain("token");
    }

    @Test
    void collectionMutationUpdatesOnlyCollectionScope() {
        ApiCollection collection = collection();
        commit(collection, mutation("token", "runtime", "collection", false));

        assertThat(collection.runtimeVars).containsEntry("token", "runtime");
        assertThat(collection.variables).extracting(variable -> variable.key).doesNotContain("token");
    }

    @Test
    void nonPersistentCollectionUnsetRemovesRuntimeKey() {
        ApiCollection collection = collection();
        collection.runtimeVars.put("token", "temporary");

        commit(collection, mutation("token", null, "collection", false));

        assertThat(collection.runtimeVars).doesNotContainKey("token");
        assertThat(collection.runtimeVars).doesNotContainValue(null);
    }

    @Test
    void nonPersistentCollectionUnsetFiresChangeOnce() {
        ApiCollection collection = collection();
        collection.runtimeVars.put("token", "temporary");
        AtomicInteger changeCount = new AtomicInteger();
        collection.addChangeListener(changeCount::incrementAndGet);

        commit(collection, mutation("token", null, "collection", false));

        assertThat(changeCount).hasValue(1);
    }

    @Test
    void runtimeCollectionUnsetRevealsAuthoredCollectionValue() {
        ApiCollection collection = collection();
        collection.variables.add(variable("token", "persisted", "string", true));
        collection.runtimeVars.put("token", "temporary");

        commit(collection, mutation("token", null, "collection", false));

        assertThat(collection.runtimeVars).doesNotContainKey("token");
        assertThat(RuntimeResolverFactory.build(collection, request(), environment(), null).getVariables().get("token")).isEqualTo("persisted");
    }

    @Test
    void runtimeCollectionUnsetDoesNotRemoveAuthoredValue() {
        ApiCollection collection = collection();
        collection.variables.add(variable("token", "persisted", "string", true));
        collection.runtimeVars.put("token", "temporary");

        commit(collection, mutation("token", null, "collection", false));

        assertThat(collection.variables)
                .anySatisfy(variable -> {
                    assertThat(variable.key).isEqualTo("token");
                    assertThat(variable.value).isEqualTo("persisted");
                });
    }

    @Test
    void setThenUnsetRuntimeCollectionLeavesNoNullEntry() {
        ApiCollection collection = collection();

        commit(collection,
                mutation("token", "temporary", "collection", false),
                mutation("token", null, "collection", false));

        assertThat(collection.runtimeVars).doesNotContainKey("token");
        assertThat(collection.runtimeVars).doesNotContainValue(null);
    }

    @Test
    void unsetAbsentRuntimeCollectionKeyDoesNotFireChange() {
        ApiCollection collection = collection();
        AtomicInteger changeCount = new AtomicInteger();
        collection.addChangeListener(changeCount::incrementAndGet);

        commit(collection, mutation("missing", null, "collection", false));

        assertThat(changeCount).hasValue(0);
    }

    @Test
    void persistentCollectionWritesAuthoredVariableOnly() {
        ApiCollection collection = collection();
        commit(collection, mutation("token", "persisted", "collection", true));

        assertThat(collection.variables)
                .anySatisfy(variable -> {
                    assertThat(variable.key).isEqualTo("token");
                    assertThat(variable.value).isEqualTo("persisted");
                    assertThat(variable.type).isEqualTo("string");
                    assertThat(variable.enabled).isTrue();
                });
        assertThat(collection.runtimeVars).doesNotContainKey("token");
    }

    @Test
    void persistentAndRuntimeMutationPathsAreNotDoubleApplied() {
        ApiCollection collection = collection();
        commit(collection,
                mutation("token", "runtime", "collection", false),
                mutation("token", "persisted", "collection", true));

        assertThat(collection.runtimeVars).containsEntry("token", "runtime");
        assertThat(collection.variables)
                .extracting(variable -> variable.key + "=" + variable.value)
                .contains("token=persisted");
    }

    @Test
    void persistentCollectionUpdatePreservesTypeEnabledAndOrder() {
        ApiCollection collection = collection();
        collection.variables.add(variable("first", "1", "text", true));
        collection.variables.add(variable("token", "old", "number", false));
        collection.variables.add(variable("last", "3", "json", true));

        commit(collection, mutation("token", "updated", "collection", true));

        assertThat(collection.variables).hasSize(3);
        assertThat(collection.variables.get(0).key).isEqualTo("first");
        assertThat(collection.variables.get(1).key).isEqualTo("token");
        assertThat(collection.variables.get(1).value).isEqualTo("updated");
        assertThat(collection.variables.get(1).type).isEqualTo("number");
        assertThat(collection.variables.get(1).enabled).isFalse();
        assertThat(collection.variables.get(2).key).isEqualTo("last");
    }

    @Test
    void persistentCollectionUnsetDoesNotDeleteRuntimeValue() {
        ApiCollection collection = collection();
        collection.variables.add(variable("token", "persisted", "string", true));
        collection.runtimeVars.put("token", "runtime");

        commit(collection, mutation("token", null, "collection", true));

        assertThat(collection.variables).noneMatch(variable -> "token".equals(variable.key));
        assertThat(collection.runtimeVars).containsEntry("token", "runtime");
    }

    @Test
    void nonPersistentFolderWritesRuntimeFolderOnly() {
        ApiCollection collection = collection();
        commit(collection, mutation("token", "runtime", "folder", false, "Parent/Child"));

        assertThat(collection.runtimeFolderVars).containsKey("Parent/Child");
        assertThat(collection.runtimeFolderVars.get("Parent/Child")).containsEntry("token", "runtime");
        assertThat(collection.folderVars).doesNotContainKey("Parent/Child");
    }

    @Test
    void folderMutationUpdatesOnlyOwningFolder() {
        ApiCollection collection = collection();
        commit(collection, mutation("token", "runtime", "folder", false, "Parent/Child"));

        assertThat(collection.runtimeFolderVars).containsKey("Parent/Child");
        assertThat(collection.runtimeFolderVars.get("Parent/Child")).containsEntry("token", "runtime");
        assertThat(collection.runtimeFolderVars).doesNotContainKey("Parent/Sibling");
    }

    @Test
    void persistentFolderWritesAuthoredFolderOnly() {
        ApiCollection collection = collection();
        commit(collection, mutation("token", "persisted", "folder", true, "Parent/Child"));

        assertThat(collection.folderVars).containsKey("Parent/Child");
        assertThat(collection.folderVars.get("Parent/Child")).containsEntry("token", "persisted");
        assertThat(collection.runtimeFolderVars).doesNotContainKey("Parent/Child");
    }

    @Test
    void folderUnsetAffectsOnlyExactPathAndLayer() {
        ApiCollection collection = collection();
        collection.folderVars.put("Parent", new LinkedHashMap<>(Map.of("token", "parent-authored")));
        collection.folderVars.put("Parent/Child", new LinkedHashMap<>(Map.of("token", "child-authored")));
        collection.runtimeFolderVars.put("Parent", new LinkedHashMap<>(Map.of("token", "parent-runtime")));
        collection.runtimeFolderVars.put("Parent/Child", new LinkedHashMap<>(Map.of("token", "child-runtime")));

        commit(collection, mutation("token", null, "folder", false, "Parent/Child"));

        assertThat(collection.runtimeFolderVars.get("Parent")).containsEntry("token", "parent-runtime");
        assertThat(collection.runtimeFolderVars).doesNotContainKey("Parent/Child");
        assertThat(collection.folderVars.get("Parent")).containsEntry("token", "parent-authored");
        assertThat(collection.folderVars.get("Parent/Child")).containsEntry("token", "child-authored");
    }

    @Test
    void globalRequestAndLocalScopesAreNeverExternallyCommitted() {
        ApiCollection collection = collection();
        ApiRequest request = request();
        EnvironmentProfile environment = environment();

        commit(collection, request, environment,
                mutation("local_token", "local", "local", false),
                mutation("request_token", "request", "request", false),
                mutation("global_token", "global", "global", false));

        assertThat(collection.runtimeVars).isEmpty();
        assertThat(collection.variables).isEmpty();
        assertThat(environment.variables).isEmpty();
        assertThat(environment.runtimeVariables).isEmpty();
        assertThat(request.variables).isEmpty();
    }

    @Test
    void localVariableDoesNotLeakToNextRequest() {
        ApiCollection collection = collection();
        ApiRequest request = request();
        EnvironmentProfile environment = environment();

        commit(collection, request, environment, mutation("token", "local", "local", false));

        assertThat(collection.runtimeVars).doesNotContainKey("token");
        assertThat(RuntimeResolverFactory.build(collection, request, environment, null).getVariables().get("token")).isNull();
    }

    @Test
    void requestVariableDoesNotBecomeCollectionRuntime() {
        ApiCollection collection = collection();
        commit(collection, request(), environment(), mutation("token", "request", "request", false));

        assertThat(collection.runtimeVars).doesNotContainKey("token");
        assertThat(collection.variables).extracting(variable -> variable.key).doesNotContain("token");
    }

    @Test
    void sameKeyInMultipleScopesRemainsDistinct() {
        ApiCollection collection = collection();
        collection.variables.add(variable("token", "collection", "string", true));
        collection.runtimeVars.put("token", "runtime-collection");
        EnvironmentProfile environment = environment();
        environment.variables.put("token", "environment");
        environment.runtimeVariables.put("token", "runtime-environment");
        ApiRequest request = request();
        request.variables.add(variable("token", "request", "string", true));

        commit(collection, request, environment, mutation("token", null, "local", false));

        assertThat(collection.variables)
                .anySatisfy(variable -> assertThat(variable.key).isEqualTo("token"));
        assertThat(collection.runtimeVars).containsEntry("token", "runtime-collection");
        assertThat(environment.variables).containsEntry("token", "environment");
        assertThat(environment.runtimeVariables).containsEntry("token", "runtime-environment");
        assertThat(request.variables).extracting(variable -> variable.key).contains("token");
    }

    @Test
    void persistentAndRuntimeMutationsAreAppliedExactlyOnce() {
        ApiCollection collection = collection();
        commit(collection,
                mutation("token", "runtime", "collection", false),
                mutation("token", "persisted", "collection", true),
                mutation("token", "runtime-final", "collection", false));

        assertThat(collection.runtimeVars).containsEntry("token", "runtime-final");
        assertThat(collection.variables)
                .extracting(variable -> variable.key + "=" + variable.value)
                .containsExactly("token=persisted");
    }

    @Test
    void localUnsetDoesNotDeleteCollectionValue() {
        ApiCollection collection = collection();
        collection.variables.add(variable("token", "persisted", "string", true));
        collection.runtimeVars.put("token", "runtime");

        commit(collection, request(), environment(), mutation("token", null, "local", false));

        assertThat(collection.variables)
                .anySatisfy(variable -> assertThat(variable.key).isEqualTo("token"));
        assertThat(collection.runtimeVars).containsEntry("token", "runtime");
    }

    @Test
    void workspaceJsonExcludesRuntimeEnvironmentAndRuntimeFolderLayers() {
        EnvironmentProfile environment = environment();
        environment.variables.put("persisted", "yes");
        environment.runtimeVariables.put("runtime_env", "hidden");

        ApiCollection collection = collection();
        collection.folderVars.put("Parent", new LinkedHashMap<>(Map.of("persisted", "folder")));
        collection.runtimeFolderVars.put("Parent", new LinkedHashMap<>(Map.of("runtime_folder", "hidden")));

        WorkspaceState state = new WorkspaceState();
        state.collections = new ArrayList<>(List.of(collection));
        state.environments = new ArrayList<>(List.of(environment));

        String json = WorkspaceStateJson.toJson(state);
        WorkspaceState parsed = WorkspaceStateJson.fromJson(json);

        assertThat(json).doesNotContain("runtime_env");
        assertThat(json).doesNotContain("runtime_folder");
        assertThat(parsed.environments.get(0).runtimeVariables).isEmpty();
        assertThat(parsed.collections.get(0).runtimeFolderVars).isEmpty();
        assertThat(parsed.environments.get(0).variables).containsEntry("persisted", "yes");
        assertThat(parsed.collections.get(0).folderVars).containsKey("Parent");
    }

    private void commit(ApiCollection collection, ScriptVariableMutation... mutations) {
        commit(collection, request(), environment(), mutations);
    }

    private void commit(EnvironmentProfile environment, ScriptVariableMutation... mutations) {
        commit(collection(), request(), environment, mutations);
    }

    private void commit(ApiCollection collection, ApiRequest request, EnvironmentProfile environment, ScriptVariableMutation... mutations) {
        ScriptExecutionResult result = new ScriptExecutionResult();
        result.variableMutations.addAll(List.of(mutations));
        try (SharedRequestPipeline pipeline = new SharedRequestPipeline(null, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.FULL_JS), null)) {
            Method method = SharedRequestPipeline.class.getDeclaredMethod(
                    "commitScriptVariableMutations",
                    ScriptExecutionResult.class,
                    Map.class,
                    SharedRequestPipeline.RuntimeVariableSink.class,
                    ApiCollection.class,
                    ApiRequest.class,
                    EnvironmentProfile.class,
                    burp.scripts.ExecutionSource.class
            );
            method.setAccessible(true);
            method.invoke(pipeline, result, null, null, collection, request, environment, burp.scripts.ExecutionSource.WORKBENCH_SEND);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private ApiCollection collection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        collection.scriptBlocks = new ArrayList<>();
        collection.requests = new ArrayList<>();
        return collection;
    }

    private EnvironmentProfile environment() {
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Environment";
        return environment;
    }

    private ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.id = "req";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test/{{token}}";
        request.path = "Parent/Child/Request";
        return request;
    }

    private ScriptVariableMutation mutation(String key, String value, String scope, boolean persistent) {
        return mutation(key, value, scope, persistent, null);
    }

    private ScriptVariableMutation mutation(String key, String value, String scope, boolean persistent, String scopePath) {
        ScriptVariableMutation mutation = new ScriptVariableMutation();
        mutation.key = key;
        mutation.newValue = value;
        mutation.scope = scope;
        mutation.persistent = persistent;
        mutation.scopePath = scopePath;
        return mutation;
    }

    private ApiRequest.Variable variable(String key, String value, String type, boolean enabled) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        variable.type = type;
        variable.enabled = enabled;
        return variable;
    }
}
