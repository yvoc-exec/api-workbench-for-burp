package burp.scripts;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.ExactHttpRequestSnapshot;
import burp.utils.ScriptMode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptMutationTransactionTest {

    @Test
    void timeoutAfterCollectionSetDoesNotMutateLiveCollection() {
        ApiCollection collection = collection();
        ApiRequest request = request();
        AtomicInteger changeCount = new AtomicInteger();
        collection.addChangeListener(changeCount::incrementAndGet);
        collection.scriptBlocks.add(block("""
                awb.collection.set('token', 'partial');
                while (true) {}
                """));

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS, 150);
        try {
            ScriptExecutionResult result = runtime.executePreRequest(collection, request, null, "Send", 1);

            assertThat(result.timedOut).isTrue();
            assertThat(result.variableMutations).isEmpty();
            assertThat(collection.runtimeVars).doesNotContainKey("token");
            assertThat(collection.variables).isEmpty();
            assertThat(changeCount.get()).isZero();
        } finally {
            runtime.close();
        }
    }

    @Test
    void cancellationAfterEnvironmentSetDoesNotMutateLiveEnvironment() throws Exception {
        ApiCollection collection = collection();
        ApiRequest request = request();
        EnvironmentProfile environment = environment();
        collection.scriptBlocks.add(block("""
                awb.environment.set('token', 'partial', { persist: false });
                while (true) {}
                """));

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS, 5_000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ScriptExecutionResult> future = executor.submit(() ->
                    runtime.executePreRequest(collection, request, environment, "Send", 1));
            waitForFutureStart(future);
            runtime.cancelActiveExecutions();
            ScriptExecutionResult result = future.get(3, TimeUnit.SECONDS);

            assertThat(result.cancelled).isTrue();
            assertThat(result.variableMutations).isEmpty();
            assertThat(environment.variables).isEmpty();
            assertThat(environment.runtimeVariables).isEmpty();
        } finally {
            executor.shutdownNow();
            runtime.close();
        }
    }

    @Test
    void scriptErrorAfterFolderSetRollsBackFailingBlock() {
        ApiCollection collection = collection();
        ApiRequest request = request();
        request.path = "Parent/Child/Request";
        collection.scriptBlocks.add(block("""
                bru.folderScope.set('token', 'keep', { persist: false });
                """, ScriptDialect.BRUNO));
        collection.scriptBlocks.add(block("""
                bru.folderScope.set('token', 'partial', { persist: false });
                throw new Error('stop');
                """, ScriptDialect.BRUNO));

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS, 5_000);
        try {
            ScriptExecutionResult result = runtime.executePreRequest(collection, request, null, "Send", 1);

            assertThat(result.success).isFalse();
            assertThat(result.errors).isNotEmpty();
            assertThat(result.effectiveVariables).containsEntry("token", "keep");
            assertThat(result.effectiveVariables).doesNotContainEntry("token", "partial");
            assertThat(result.variableMutations)
                    .extracting(mutation -> mutation.key + "=" + mutation.newValue)
                    .contains("token=keep")
                    .doesNotContain("token=partial");
        } finally {
            runtime.close();
        }
    }

    @Test
    void successfulEarlierBlockThenLaterTimeoutCommitsNothingFromPhase() {
        ApiCollection collection = collection();
        ApiRequest request = request();
        EnvironmentProfile environment = environment();
        collection.scriptBlocks.add(block("""
                awb.collection.set('first', 'one');
                awb.environment.set('env', 'one', { persist: false });
                """));
        collection.scriptBlocks.add(block("""
                while (true) {}
                """));

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS, 150);
        try {
            ScriptExecutionResult result = runtime.executePreRequest(collection, request, environment, "Send", 1);

            assertThat(result.timedOut).isTrue();
            assertThat(result.variableMutations).isEmpty();
            assertThat(result.effectiveVariables).doesNotContainKeys("first", "env");
        } finally {
            runtime.close();
        }
    }

    @Test
    void phaseTimeoutRestoresExactTransportAfterEarlierRequestMutation() {
        ApiCollection collection = collection();
        ApiRequest request = exactRequest();
        byte[] originalRaw = request.exactHttpRequest.rawRequestBytes.clone();
        String originalFingerprint = request.exactHttpRequest.semanticFingerprint;
        collection.scriptBlocks.add(block("""
                awb.request.url = 'https://example.test/mutated';
                """));
        collection.scriptBlocks.add(block("""
                while (true) {}
                """));

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS, 150);
        try {
            ScriptExecutionResult result = runtime.executePreRequest(collection, request, null, "Send", 1);

            assertThat(result.timedOut).isTrue();
            assertThat(result.mutatedRequest.url).isEqualTo("https://example.test/start");
            assertThat(result.mutatedRequest.exactHttpRequest).isNotNull();
            assertThat(result.mutatedRequest.exactHttpRequest.pristine).isTrue();
            assertThat(result.mutatedRequest.exactHttpRequest.invalidationReason).isEmpty();
            assertThat(result.mutatedRequest.exactHttpRequest.semanticFingerprint).isEqualTo(originalFingerprint);
            assertThat(result.mutatedRequest.exactHttpRequest.rawRequestBytes).isEqualTo(originalRaw);
        } finally {
            runtime.close();
        }
    }

    @Test
    void failedBlockRestoresRequestAndVariablesButKeepsPriorSuccessfulBlockWhenNotTimeout() {
        ApiCollection collection = collection();
        ApiRequest request = request();
        request.path = "Parent/Child/Request";
        collection.scriptBlocks.add(block("""
                awb.collection.set('first', 'one');
                """));
        collection.scriptBlocks.add(block("""
                awb.collection.set('temp', 'temp');
                throw new Error('stop');
                """));
        collection.scriptBlocks.add(block("""
                awb.collection.set('third', 'three');
                """));

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS, 5_000);
        try {
            ScriptExecutionResult result = runtime.executePreRequest(collection, request, null, "Send", 1);

            assertThat(result.success).isFalse();
            assertThat(result.errors).isNotEmpty();
            assertThat(result.effectiveVariables)
                    .containsEntry("first", "one")
                    .containsEntry("third", "three")
                    .doesNotContainEntry("temp", "temp");
            assertThat(result.variableMutations)
                    .extracting(mutation -> mutation.key + "=" + mutation.newValue)
                    .contains("first=one", "third=three")
                    .doesNotContain("temp=temp");
        } finally {
            runtime.close();
        }
    }

    private void waitForFutureStart(Future<?> future) throws InterruptedException, TimeoutException, ExecutionException {
        long deadline = System.currentTimeMillis() + 1_000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(future.isDone()).isFalse();
    }

    private ApiCollection collection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        collection.scriptBlocks = new ArrayList<>();
        collection.requests = new ArrayList<>();
        return collection;
    }

    private ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.id = "req";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test/start";
        request.path = "Parent/Child/Request";
        return request;
    }

    private ApiRequest exactRequest() {
        ApiRequest request = request();
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.editorMaterialized = true;
        request.exactHttpRequest = new ExactHttpRequestSnapshot();
        request.exactHttpRequest.rawRequestBytes = (
                "GET /start HTTP/1.1\r\nHost: example.test\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        request.exactHttpRequest.serviceHost = "example.test";
        request.exactHttpRequest.servicePort = 443;
        request.exactHttpRequest.secure = true;
        request.exactHttpRequest.pristine = true;
        request.exactHttpRequest.invalidationReason = "";
        request.exactHttpRequest.semanticFingerprint = request.computeSemanticFingerprint();
        return request;
    }

    private EnvironmentProfile environment() {
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Environment";
        return environment;
    }

    private ScriptBlock block(String source) {
        return block(source, ScriptDialect.API_WORKBENCH);
    }

    private ScriptBlock block(String source, ScriptDialect dialect) {
        ScriptBlock block = new ScriptBlock();
        block.id = "script";
        block.dialect = dialect;
        block.phase = ScriptPhase.PRE_REQUEST;
        block.scope = ScriptScope.COLLECTION;
        block.source = source;
        block.enabled = true;
        return block;
    }
}
