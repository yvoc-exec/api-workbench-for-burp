package burp.utils;

import burp.UniversalImporter;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.runner.CollectionRunner;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptRuntimeCleanupTest {

    @Test
    void pipelineCloseCancelsActiveScriptAndTerminatesExecutor() throws Exception {
        ApiCollection collection = collection("while (true) {}");
        ApiRequest request = request();
        try (SharedRequestPipeline pipeline = pipeline()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<ExecutionResult> future = executor.submit(() -> pipeline.execute(request, collection, false));
                Thread.sleep(100);
                pipeline.close();
                assertThat(isClosed(pipeline)).isTrue();
                ExecutionResult result = future.get(3, TimeUnit.SECONDS);

                assertThat(result.scriptVariableMutations).isEmpty();
                assertThat(collection.runtimeVars).isEmpty();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void pipelineCloseIsIdempotent() {
        SharedRequestPipeline pipeline = pipeline();
        pipeline.close();
        pipeline.close();
        assertThat(isClosed(pipeline)).isTrue();
    }

    @Test
    void universalImporterCleanupClosesPipelineRuntime() throws Exception {
        UniversalImporter importer = new UniversalImporter(null, ScriptMode.FULL_JS, null);
        try {
            importer.cleanup();
            SharedRequestPipeline pipeline = (SharedRequestPipeline) getField(importer, "pipeline");
            assertThat(isClosed(pipeline)).isTrue();
        } finally {
            importer.cleanup();
        }
    }

    @Test
    void scriptModeProbeClosesTemporaryEngine() throws Exception {
        String source = Files.readString(Path.of("src/main/java/burp/utils/ScriptModeDetector.java"));
        assertThat(source).contains("try (burp.scripts.GraalJsSandboxEngine engine = new burp.scripts.GraalJsSandboxEngine())");
        String first = ScriptModeDetector.probeJavaScriptRuntime();
        String second = ScriptModeDetector.probeJavaScriptRuntime();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void runnerCanRestartBeforePipelineCloseButNotLeakAfterUnload() throws Exception {
        ApiCollection collection = collection("while (true) {}");
        ApiRequest request = request();
        collection.requests = new java.util.ArrayList<>(List.of(request));
        EnvironmentProfile environment = new EnvironmentProfile();
        try (SharedRequestPipeline pipeline = pipeline()) {
            CollectionRunner runner = new CollectionRunner(null, pipeline);
            runner.setDelayMs(0);
            runner.setActiveEnvironmentProvider(col -> environment);
            runner.runCollections(List.of(collection), List.of(request));
            waitUntilRunning(runner);
            runner.cancel();
            waitUntilStopped(runner);

            ApiCollection laterCollection = collection("awb.collection.set('later', 'ok', { persist: false });");
            ApiRequest laterRequest = request();
            ExecutionResult laterResult = pipeline.execute(laterRequest, laterCollection, false);
            assertThat(laterResult.scriptVariableMutations).isNotEmpty();
            assertThat(laterResult.scriptVariableMutations).anySatisfy(mutation -> {
                assertThat(mutation.key).isEqualTo("later");
                assertThat(mutation.newValue).isEqualTo("ok");
            });

            pipeline.close();
            assertThat(isClosed(pipeline)).isTrue();
        }
    }

    private SharedRequestPipeline pipeline() {
        return new SharedRequestPipeline(null, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.FULL_JS), null);
    }

    private ApiCollection collection(String scriptSource) {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        collection.scriptBlocks = new java.util.ArrayList<>();
        collection.scriptBlocks.add(scriptBlock(scriptSource));
        collection.requests = new java.util.ArrayList<>();
        return collection;
    }

    private ScriptBlock scriptBlock(String source) {
        ScriptBlock block = new ScriptBlock();
        block.id = "script";
        block.dialect = ScriptDialect.API_WORKBENCH;
        block.phase = ScriptPhase.PRE_REQUEST;
        block.scope = ScriptScope.COLLECTION;
        block.source = source;
        block.enabled = true;
        return block;
    }

    private ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.id = "req";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test";
        return request;
    }

    private void waitUntilRunning(CollectionRunner runner) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (!runner.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(runner.isRunning()).isTrue();
    }

    private void waitUntilStopped(CollectionRunner runner) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (runner.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(runner.isRunning()).isFalse();
    }

    private boolean isClosed(SharedRequestPipeline pipeline) {
        try {
            java.lang.reflect.Method method = SharedRequestPipeline.class.getDeclaredMethod("isClosedForTests");
            method.setAccessible(true);
            return (Boolean) method.invoke(pipeline);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Object getField(Object target, String name) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
