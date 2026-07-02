package burp.runner;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.scripts.*;
import burp.utils.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionRunnerScriptCancellationTest {
    @Test
    void cancelStopsInfinitePreRequestScriptAndTerminatesRunner() throws Exception {
        CancellablePipeline pipeline = new CancellablePipeline("while (true) {}");
        CollectionRunner runner = new CollectionRunner(null, pipeline);
        runner.runCollections(List.of(collection()), List.of(request()));
        Thread.sleep(150);
        runner.cancel();
        waitUntilStopped(runner);
        assertThat(pipeline.cancelled).isTrue();
        assertThat(runner.isRunning()).isFalse();
    }

    @Test
    void runnerCanStartAgainAfterCancelledScript() throws Exception {
        CancellablePipeline pipeline = new CancellablePipeline("while (true) {}");
        CollectionRunner runner = new CollectionRunner(null, pipeline);
        runner.runCollections(List.of(collection()), List.of(request()));
        Thread.sleep(150);
        runner.cancel();
        waitUntilStopped(runner);
        pipeline.script = "console.log('ok');";
        runner.runCollections(List.of(collection()), List.of(request()));
        waitUntilStopped(runner);
        assertThat(runner.isRunning()).isFalse();
    }

    @Test
    void cancelStopsInfinitePreRequestScriptUsingRealPipeline() throws Exception {
        ApiCollection collection = collection();
        ApiRequest request = request();
        collection.requests = new java.util.ArrayList<>(List.of(request));
        collection.scriptBlocks.add(block("""
                awb.collection.set('token', 'partial', { persist: false });
                awb.environment.set('env_token', 'partial', { persist: false });
                while (true) {}
                """));

        EnvironmentProfile environment = new EnvironmentProfile();
        SharedRequestPipeline pipeline = new SharedRequestPipeline(null, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.FULL_JS), null);
        CollectionRunner runner = new CollectionRunner(null, pipeline);
        runner.setDelayMs(0);
        runner.setActiveEnvironmentProvider(col -> environment);

        ExecutorService watcher = Executors.newSingleThreadExecutor();
        try {
            watcher.submit(() -> runner.runCollections(List.of(collection), List.of(request)));
            waitUntilRunning(runner);
            runner.cancel();
            waitUntilStopped(runner);

            assertThat(runner.isRunning()).isFalse();
            assertThat(collection.runtimeVars).doesNotContainKey("token");
            assertThat(environment.runtimeVariables).isEmpty();

            ApiCollection laterCollection = collection();
            ApiRequest laterRequest = request();
            laterCollection.scriptBlocks.add(block("awb.collection.set('later', 'ok', { persist: false });"));
            ExecutionResult laterResult = pipeline.execute(laterRequest, laterCollection, false);
            assertThat(laterResult.scriptVariableMutations).isNotEmpty();
            assertThat(laterResult.scriptVariableMutations).anySatisfy(mutation -> {
                assertThat(mutation.key).isEqualTo("later");
                assertThat(mutation.newValue).isEqualTo("ok");
            });

            pipeline.close();
            assertThat(pipelineIsClosed(pipeline)).isTrue();
        } finally {
            watcher.shutdownNow();
            pipeline.close();
        }
    }

    private void waitUntilStopped(CollectionRunner runner) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000;
        while (runner.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
    }

    private void waitUntilRunning(CollectionRunner runner) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000;
        while (!runner.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(runner.isRunning()).isTrue();
    }

    private ApiCollection collection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "c";
        collection.requests = new java.util.ArrayList<>();
        collection.scriptBlocks = new java.util.ArrayList<>();
        return collection;
    }

    private ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.name = "r";
        request.id = "r";
        request.method = "GET";
        request.url = "https://example.test";
        return request;
    }

    private ScriptBlock block(String source) {
        ScriptBlock block = ScriptBlock.of(source, ScriptDialect.API_WORKBENCH, ScriptPhase.PRE_REQUEST, ScriptScope.COLLECTION);
        block.enabled = true;
        return block;
    }

    private boolean pipelineIsClosed(SharedRequestPipeline pipeline) throws Exception {
        java.lang.reflect.Method method = SharedRequestPipeline.class.getDeclaredMethod("isClosedForTests");
        method.setAccessible(true);
        return (Boolean) method.invoke(pipeline);
    }

    static class CancellablePipeline extends SharedRequestPipeline {
        final UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile String script;

        CancellablePipeline(String script) {
            super(null, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);
            this.script = script;
        }

        @Override
        public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
            ExecutionResult result = new ExecutionResult();
            col.scriptBlocks.clear();
            col.scriptBlocks.add(ScriptBlock.of(script, ScriptDialect.API_WORKBENCH, ScriptPhase.PRE_REQUEST, ScriptScope.COLLECTION));
            ScriptExecutionResult scriptResult = runtime.executePreRequest(col, req, null, ExecutionSource.RUNNER, 1);
            result.success = scriptResult.success;
            return result;
        }

        @Override
        public void cancelActiveScriptExecutions() {
            cancelled.set(true);
            runtime.cancelActiveExecutions();
        }
    }
}
