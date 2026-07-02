package burp.runner;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.scripts.*;
import burp.utils.*;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    private void waitUntilStopped(CollectionRunner runner) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000;
        while (runner.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
    }

    private ApiCollection collection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "c";
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
