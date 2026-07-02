package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.scripts.ExecutionSource;
import burp.scripts.GraalJsSandboxEngine;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptExecutionContext;
import burp.scripts.ScriptExecutionResult;
import burp.scripts.ScriptLifecycleExecutor;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.testsupport.RunnerScriptTestFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.when;

class ScriptSuccessfulMutationCommitAfterErrorTest {

    @Test
    void successfulEnvironmentMutationBeforeFailedBlockStillCommits() {
        ExecutionFixture fixture = executionFixture(
                block("""
                        awb.environment.set('first', 'one', { persist: true });
                        """, ScriptPhase.PRE_REQUEST),
                block("""
                        awb.environment.set('temp', 'temp', { persist: true });
                        throw new Error('boom');
                        """, ScriptPhase.PRE_REQUEST)
        );

        AtomicInteger callbackCount = new AtomicInteger();
        ExecutionResult result = fixture.pipeline.execute(
                fixture.request,
                fixture.collection,
                false,
                null,
                null,
                new CallbackSink(fixture.environment, callbackCount),
                fixture.environment
        );

        assertThat(result.scriptErrors).isNotEmpty();
        assertThat(fixture.environment.variables).containsEntry("first", "one");
        assertThat(fixture.environment.variables).doesNotContainKey("temp");
        assertThat(callbackCount.get()).isEqualTo(1);
        assertThat(fixture.sendCount.get()).isEqualTo(1);
    }

    @Test
    void successfulCollectionMutationAfterFailedBlockCommitsWhenExecutionContinues() {
        ExecutionFixture fixture = executionFixture(
                block("""
                        awb.collection.set('first', 'one');
                        """, ScriptPhase.PRE_REQUEST),
                block("""
                        awb.collection.set('temp', 'temp');
                        throw new Error('boom');
                        """, ScriptPhase.PRE_REQUEST),
                block("""
                        awb.collection.set('third', 'three');
                        """, ScriptPhase.PRE_REQUEST)
        );

        AtomicInteger callbackCount = new AtomicInteger();
        ExecutionResult result = fixture.pipeline.execute(
                fixture.request,
                fixture.collection,
                false,
                null,
                null,
                new CallbackSink(fixture.environment, callbackCount),
                fixture.environment
        );

        assertThat(result.scriptErrors).isNotEmpty();
        assertThat(fixture.collection.variables)
                .extracting(variable -> variable.key + "=" + variable.value)
                .contains("first=one", "third=three")
                .doesNotContain("temp=temp");
        assertThat(callbackCount.get()).isZero();
        assertThat(fixture.sendCount.get()).isEqualTo(1);
    }

    @Test
    void failedBlockMutationIsRolledBackAndNotCommitted() {
        ExecutionFixture fixture = executionFixture(
                block("""
                        awb.environment.set('temp', 'temp', { persist: true });
                        throw new Error('boom');
                        """, ScriptPhase.PRE_REQUEST)
        );

        AtomicInteger callbackCount = new AtomicInteger();
        ExecutionResult result = fixture.pipeline.execute(
                fixture.request,
                fixture.collection,
                false,
                null,
                null,
                new CallbackSink(fixture.environment, callbackCount),
                fixture.environment
        );

        assertThat(result.scriptErrors).isNotEmpty();
        assertThat(fixture.environment.variables).doesNotContainKey("temp");
        assertThat(fixture.environment.runtimeVariables).doesNotContainKey("temp");
        assertThat(callbackCount.get()).isZero();
    }

    @Test
    void failedAssertionDoesNotDiscardSuccessfulExtraction() {
        ExecutionFixture fixture = executionFixture(
                block("""
                        awb.environment.set('token', awb.response.json().get('token'), { persist: true });
                        awb.test('status is 201', function () {
                            awb.expect(awb.response.code).to.equal(201);
                        });
                        """, ScriptPhase.POST_RESPONSE)
        );

        AtomicInteger callbackCount = new AtomicInteger();
        ExecutionResult result = fixture.pipeline.execute(
                fixture.request,
                fixture.collection,
                false,
                null,
                null,
                new CallbackSink(fixture.environment, callbackCount),
                fixture.environment
        );

        assertThat(result.assertions).isNotEmpty();
        assertThat(fixture.environment.variables).containsEntry("token", "abc");
        assertThat(callbackCount.get()).isEqualTo(1);
        assertThat(fixture.sendCount.get()).isEqualTo(1);
    }

    @Test
    void stopOnScriptErrorCommitsOnlySuccessfulMutationsBeforeFailure() {
        ScriptExecutionContext context = new ScriptExecutionContext(
                Mockito.mock(MontoyaApi.class, RETURNS_DEEP_STUBS),
                collection(),
                request(),
                null,
                ExecutionSource.WORKBENCH_SEND,
                1
        );
        context.scriptErrorsStopExecution = true;

        GraalJsSandboxEngine engine = new GraalJsSandboxEngine();
        ScriptLifecycleExecutor executor = new ScriptLifecycleExecutor(engine);
        try {
            ScriptExecutionResult result = executor.execute(context, List.of(
                    block("""
                            awb.collection.set('first', 'one');
                            """, ScriptPhase.PRE_REQUEST),
                    block("""
                            awb.collection.set('temp', 'temp');
                            throw new Error('boom');
                            """, ScriptPhase.PRE_REQUEST),
                    block("""
                            awb.collection.set('third', 'three');
                            """, ScriptPhase.PRE_REQUEST)
            ));

            assertThat(result.success).isFalse();
            assertThat(result.errors).isNotEmpty();
            assertThat(result.variableMutations)
                    .extracting(mutation -> mutation.key + "=" + mutation.newValue)
                    .contains("first=one")
                    .doesNotContain("temp=temp", "third=three");
            assertThat(result.effectiveVariables).containsEntry("first", "one");
            assertThat(result.effectiveVariables).doesNotContainKeys("temp", "third");
        } finally {
            engine.close();
        }
    }

    private static ExecutionFixture executionFixture(ScriptBlock... blocks) {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> requests = new CopyOnWriteArrayList<>();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(
                sendCount,
                requests,
                () -> RunnerScriptTestFixtures.mockResponse(200, "{\"token\":\"abc\",\"ok\":true}", "application/json")
        );
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.FULL_JS), null);
        ApiCollection collection = collection(blocks);
        ApiRequest request = request();
        EnvironmentProfile environment = environment();
        return new ExecutionFixture(pipeline, collection, request, environment, sendCount);
    }

    private static ApiCollection collection(ScriptBlock... blocks) {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        collection.scriptBlocks = new ArrayList<>();
        if (blocks != null) {
            collection.scriptBlocks.addAll(List.of(blocks));
        }
        return collection;
    }

    private static ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test/start";
        request.path = "Parent/Child/Request";
        request.headers = new ArrayList<>();
        request.scriptBlocks = new ArrayList<>();
        return request;
    }

    private static EnvironmentProfile environment() {
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.id = "env-1";
        environment.name = "Environment";
        return environment;
    }

    private static ScriptBlock block(String source, ScriptPhase phase) {
        ScriptBlock block = new ScriptBlock();
        block.id = phase.name().toLowerCase() + "-block";
        block.dialect = ScriptDialect.API_WORKBENCH;
        block.phase = phase;
        block.scope = ScriptScope.REQUEST;
        block.source = source;
        block.enabled = true;
        return block;
    }

    private static final class CallbackSink implements SharedRequestPipeline.RuntimeVariableSink {
        private final EnvironmentProfile environment;
        private final AtomicInteger callbackCount;

        private CallbackSink(EnvironmentProfile environment, AtomicInteger callbackCount) {
            this.environment = environment;
            this.callbackCount = callbackCount;
        }

        @Override
        public void apply(ApiCollection collection, java.util.Map<String, String> changedVars, java.util.Set<String> removedKeys) {
            if (removedKeys != null) {
                for (String key : removedKeys) {
                    environment.runtimeVariables.remove(key);
                }
            }
            if (changedVars != null) {
                for (java.util.Map.Entry<String, String> entry : changedVars.entrySet()) {
                    environment.runtimeVariables.put(entry.getKey(), entry.getValue());
                }
            }
        }

        @Override
        public void environmentMutationCommitted(EnvironmentProfile environment, boolean persistedChanged, boolean runtimeChanged) {
            callbackCount.incrementAndGet();
        }
    }

    private record ExecutionFixture(SharedRequestPipeline pipeline,
                                    ApiCollection collection,
                                    ApiRequest request,
                                    EnvironmentProfile environment,
                                    AtomicInteger sendCount) {
    }
}
