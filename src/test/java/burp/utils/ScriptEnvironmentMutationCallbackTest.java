package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.scripts.ScriptExecutionResult;
import burp.scripts.ScriptVariableMutation;
import burp.scripts.UnifiedScriptRuntime;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScriptEnvironmentMutationCallbackTest {

    @Test
    void persistentEnvironmentMutationCallsCallbackOnce() {
        ApiCollection collection = collection();
        EnvironmentProfile environment = environment();
        ScriptExecutionResult result = result(mutation("token", "persisted", "environment", true));

        CallbackState state = new CallbackState();
        commit(result, collection, environment, state);

        assertThat(state.callbackCount.get()).isEqualTo(1);
        assertThat(state.persistedChanged.get()).isTrue();
        assertThat(state.runtimeChanged.get()).isFalse();
    }

    @Test
    void runtimeEnvironmentMutationCallsCallbackOnce() {
        ApiCollection collection = collection();
        EnvironmentProfile environment = environment();
        ScriptExecutionResult result = result(mutation("token", "runtime", "environment", false));

        CallbackState state = new CallbackState();
        commit(result, collection, environment, state);

        assertThat(state.callbackCount.get()).isEqualTo(1);
        assertThat(state.persistedChanged.get()).isFalse();
        assertThat(state.runtimeChanged.get()).isTrue();
    }

    @Test
    void mixedEnvironmentMutationsCallOneCallback() {
        ApiCollection collection = collection();
        EnvironmentProfile environment = environment();
        ScriptExecutionResult result = result(
                mutation("persisted", "persisted-value", "environment", true),
                mutation("runtime", "runtime-value", "environment", false)
        );

        CallbackState state = new CallbackState();
        commit(result, collection, environment, state);

        assertThat(state.callbackCount.get()).isEqualTo(1);
        assertThat(state.persistedChanged.get()).isTrue();
        assertThat(state.runtimeChanged.get()).isTrue();
    }

    @Test
    void unchangedEnvironmentValueDoesNotCallCallback() {
        ApiCollection collection = collection();
        EnvironmentProfile environment = environment();
        environment.variables.put("token", "same");
        ScriptExecutionResult result = result(mutation("token", "same", "environment", true));

        CallbackState state = new CallbackState();
        commit(result, collection, environment, state);

        assertThat(state.callbackCount.get()).isZero();
    }

    @Test
    void previewDoesNotCallEnvironmentCallback() {
        CallbackState state = new CallbackState();
        ExecutionFixture fixture = executionFixture(
                ScriptMode.FULL_JS,
                result(mutation("token", "preview", "environment", true))
        );

        ExecutionResult executionResult = fixture.pipeline.build(
                fixture.request,
                fixture.collection,
                Map.of(),
                null,
                state.sink,
                fixture.environment,
                burp.scripts.ExecutionSource.BUILD_PREVIEW
        );

        assertThat(executionResult.success).isTrue();
        assertThat(state.callbackCount.get()).isZero();
        assertThat(fixture.environment.variables).doesNotContainKey("token");
    }

    @Test
    void timeoutDoesNotCallEnvironmentCallback() {
        CallbackState state = new CallbackState();
        ExecutionFixture fixture = executionFixture(
                ScriptMode.FULL_JS,
                timedOutResult(mutation("token", "timeout", "environment", true))
        );

        ExecutionResult executionResult = fixture.pipeline.execute(
                fixture.request,
                fixture.collection,
                false,
                Map.of(),
                null,
                state.sink,
                fixture.environment
        );

        assertThat(executionResult.rawRequestText).isNull();
        assertThat(state.callbackCount.get()).isZero();
        assertThat(fixture.environment.variables).doesNotContainKey("token");
    }

    @Test
    void cancellationDoesNotCallEnvironmentCallback() {
        CallbackState state = new CallbackState();
        ExecutionFixture fixture = executionFixture(
                ScriptMode.FULL_JS,
                cancelledResult(mutation("token", "cancel", "environment", true))
        );

        ExecutionResult executionResult = fixture.pipeline.execute(
                fixture.request,
                fixture.collection,
                false,
                Map.of(),
                null,
                state.sink,
                fixture.environment
        );

        assertThat(executionResult.rawRequestText).isNull();
        assertThat(state.callbackCount.get()).isZero();
        assertThat(fixture.environment.variables).doesNotContainKey("token");
    }

    @Test
    void rolledBackScriptBlockDoesNotCallEnvironmentCallback() {
        ApiCollection collection = collection();
        EnvironmentProfile environment = environment();
        ScriptExecutionResult result = result(mutation("token", "rolled-back", "environment", true));
        result.success = false;

        CallbackState state = new CallbackState();
        commit(result, collection, environment, state);

        assertThat(state.callbackCount.get()).isZero();
        assertThat(environment.variables).doesNotContainKey("token");
        assertThat(environment.runtimeVariables).doesNotContainKey("token");
    }

    @Test
    void callbackRunsAfterEnvironmentMapsAreUpdated() {
        ApiCollection collection = collection();
        EnvironmentProfile environment = environment();
        ScriptExecutionResult result = result(
                mutation("persisted", "persisted-value", "environment", true),
                mutation("runtime", "runtime-value", "environment", false)
        );

        AtomicInteger callbackCount = new AtomicInteger();
        AtomicReference<Boolean> persistedSeen = new AtomicReference<>(false);
        AtomicReference<Boolean> runtimeSeen = new AtomicReference<>(false);
        SharedRequestPipeline.RuntimeVariableSink sink = new SharedRequestPipeline.RuntimeVariableSink() {
            @Override
            public void apply(ApiCollection collection, Map<String, String> changedVars, java.util.Set<String> removedKeys) {
                if (removedKeys != null) {
                    for (String key : removedKeys) {
                        environment.runtimeVariables.remove(key);
                    }
                }
                if (changedVars != null) {
                    for (Map.Entry<String, String> entry : changedVars.entrySet()) {
                        environment.runtimeVariables.put(entry.getKey(), entry.getValue());
                    }
                }
            }

            @Override
            public void environmentMutationCommitted(EnvironmentProfile env, boolean persistedChanged, boolean runtimeChanged) {
                callbackCount.incrementAndGet();
                persistedSeen.set(persistedChanged);
                runtimeSeen.set(runtimeChanged);
                assertThat(env.variables).containsEntry("persisted", "persisted-value");
                assertThat(env.runtimeVariables).containsEntry("runtime", "runtime-value");
            }
        };

        commit(result, collection, environment, sink);

        assertThat(callbackCount.get()).isEqualTo(1);
        assertThat(persistedSeen.get()).isTrue();
        assertThat(runtimeSeen.get()).isTrue();
    }

    private static void commit(ScriptExecutionResult result,
                               ApiCollection collection,
                               EnvironmentProfile environment,
                               CallbackState state) {
        commit(result, collection, environment, state.sink);
    }

    private static void commit(ScriptExecutionResult result,
                               ApiCollection collection,
                               EnvironmentProfile environment,
                               SharedRequestPipeline.RuntimeVariableSink sink) {
        try (SharedRequestPipeline pipeline = new SharedRequestPipeline(mock(MontoyaApi.class, RETURNS_DEEP_STUBS), new RequestBuilder(null), new ScriptEngine(null, ScriptMode.FULL_JS), null)) {
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
            method.invoke(pipeline, result, null, sink, collection, request(), environment, burp.scripts.ExecutionSource.WORKBENCH_SEND);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static ExecutionFixture executionFixture(ScriptMode scriptMode, ScriptExecutionResult runtimeResult) {
        MontoyaApi api = mock(MontoyaApi.class, RETURNS_DEEP_STUBS);
        UnifiedScriptRuntime runtime = mock(UnifiedScriptRuntime.class);
        when(runtime.isEnabled()).thenReturn(true);
        when(runtime.getEngineName()).thenReturn("GraalJS");
        when(runtime.executePreRequest(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(runtimeResult);
        ApiCollection collection = collection();
        EnvironmentProfile environment = environment();
        ApiRequest request = request();
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, scriptMode), null, runtime);
        return new ExecutionFixture(pipeline, collection, environment, request);
    }

    private static ScriptExecutionResult result(ScriptVariableMutation... mutations) {
        ScriptExecutionResult result = new ScriptExecutionResult();
        result.success = true;
        result.engineName = "GraalJS";
        result.effectiveVariables.put("token", "value");
        if (mutations != null) {
            result.variableMutations.addAll(List.of(mutations));
        }
        return result;
    }

    private static ScriptExecutionResult timedOutResult(ScriptVariableMutation... mutations) {
        ScriptExecutionResult result = result(mutations);
        result.timedOut = true;
        return result;
    }

    private static ScriptExecutionResult cancelledResult(ScriptVariableMutation... mutations) {
        ScriptExecutionResult result = result(mutations);
        result.cancelled = true;
        return result;
    }

    private static ScriptVariableMutation mutation(String key, String value, String scope, boolean persistent) {
        ScriptVariableMutation mutation = new ScriptVariableMutation();
        mutation.key = key;
        mutation.newValue = value;
        mutation.scope = scope;
        mutation.persistent = persistent;
        return mutation;
    }

    private static ApiCollection collection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        return collection;
    }

    private static EnvironmentProfile environment() {
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Environment";
        return environment;
    }

    private static ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test/{{token}}";
        request.path = "Parent/Child/Request";
        return request;
    }

    private static final class CallbackState {
        private final AtomicInteger callbackCount = new AtomicInteger();
        private final AtomicReference<Boolean> persistedChanged = new AtomicReference<>(false);
        private final AtomicReference<Boolean> runtimeChanged = new AtomicReference<>(false);
        private final SharedRequestPipeline.RuntimeVariableSink sink = new SharedRequestPipeline.RuntimeVariableSink() {
            @Override
            public void apply(ApiCollection collection, Map<String, String> changedVars, java.util.Set<String> removedKeys) {
                if (collection != null) {
                    collection.applyRuntimeVarDelta(changedVars, removedKeys);
                }
            }

            @Override
            public void environmentMutationCommitted(EnvironmentProfile environment, boolean persisted, boolean runtime) {
                callbackCount.incrementAndGet();
                persistedChanged.set(persisted);
                runtimeChanged.set(runtime);
            }
        };
    }

    private record ExecutionFixture(SharedRequestPipeline pipeline,
                                    ApiCollection collection,
                                    EnvironmentProfile environment,
                                    ApiRequest request) {
    }
}
