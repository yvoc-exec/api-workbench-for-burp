package burp.runner;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.RunnerTerminationType;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.testsupport.RunnerScriptTestFixtures;
import burp.utils.ExecutionResult;
import burp.utils.SharedRequestPipeline;
import burp.utils.PreflightDecisionHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionRunnerFlowTargetSafetyTest {

    @Test
    void setNextAmbiguityStopsRun() throws Exception {
        CollectionRunner runner = flowRunner(call -> call == 1 ? setNextResult("Target", null) : successResult());
        ApiCollection collection = collection("Collection",
                request("current", "Current", "", null),
                request("a", "Target", "folder-a", null),
                request("b", "Target", "folder-b", null));

        run(runner, collection, collection.requests);

        assertThat(runner.getResults()).hasSize(1);
        assertThat(runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.STOPPED_BY_SCRIPT);
    }

    @Test
    void setNextDisabledTargetStopsRun() throws Exception {
        ApiRequest target = request("target-id", "Target", "folder-a", null);
        target.disabled = true;
        CollectionRunner runner = flowRunner(call -> call == 1 ? setNextResult("target-id", null) : successResult());
        ApiCollection collection = collection("Collection",
                request("current", "Current", "", null),
                target);

        run(runner, collection, collection.requests);

        assertThat(runner.getResults()).hasSize(1);
        assertThat(runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.STOPPED_BY_SCRIPT);
    }

    @Test
    void setNextNotFoundRetainsSequentialCompatibility() throws Exception {
        CollectionRunner runner = flowRunner(call -> call == 1 ? setNextResult("missing", null) : successResult());
        ApiCollection collection = collection("Collection",
                request("current", "Current", "", null),
                request("next", "Next", "", null));

        run(runner, collection, collection.requests);

        assertThat(runner.getResults()).hasSize(2);
        assertThat(runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.COMPLETED);
    }

    @Test
    void runRequestAmbiguityExecutesNothing() throws Exception {
        CollectionRunner runner = scriptRunner();
        ApiRequest childA = child("a", "Child", "folder-a");
        ApiRequest childB = child("b", "Child", "folder-b");
        ApiRequest parent = RunnerScriptTestFixtures.request(
                "parent",
                "Parent",
                1,
                "Collection",
                "https://example.test/parent",
                "pm.execution.runRequest('Child');",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);
        ApiCollection collection = collection("Collection", parent, childA, childB);

        run(runner, collection, List.of(parent));

        assertThat(runner.getResults()).hasSize(1);
        assertThat(runner.getResults().get(0).scriptWarnings)
                .anySatisfy(message -> assertThat(message).contains("Flow target is ambiguous"));
    }

    @Test
    void runRequestDisabledTargetExecutesNothing() throws Exception {
        CollectionRunner runner = scriptRunner();
        ApiRequest child = child("child", "Child", "folder-a");
        child.disabled = true;
        ApiRequest parent = RunnerScriptTestFixtures.request(
                "parent",
                "Parent",
                1,
                "Collection",
                "https://example.test/parent",
                "pm.execution.runRequest('Child');",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);
        ApiCollection collection = collection("Collection", parent, child);

        run(runner, collection, List.of(parent));

        assertThat(runner.getResults()).hasSize(1);
        assertThat(runner.getResults().get(0).scriptWarnings)
                .anySatisfy(message -> assertThat(message).contains("Flow target is disabled"));
    }

    @Test
    void runRequestQualifiedPathExecutesCorrectDuplicate() throws Exception {
        CollectionRunner runner = scriptRunner();
        ApiRequest childA = child("child-a", "Child", "folder-a");
        ApiRequest childB = child("child-b", "Child", "folder-b");
        ApiRequest parent = RunnerScriptTestFixtures.request(
                "parent",
                "Parent",
                1,
                "Collection",
                "https://example.test/parent",
                "pm.execution.runRequest('Collection/folder-b/Child');",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);
        ApiCollection collection = collection("Collection", parent, childA, childB);

        run(runner, collection, List.of(parent));

        assertThat(runner.getResults()).hasSize(2);
        assertThat(runner.getResults().get(0).requestId).isEqualTo("child-b");
        assertThat(runner.getResults().get(1).requestId).isEqualTo("parent");
    }

    @Test
    void runRequestExactIdExecutesCorrectDuplicate() throws Exception {
        CollectionRunner runner = scriptRunner();
        ApiRequest childA = child("child-a", "Child", "folder-a");
        ApiRequest childB = child("child-b", "Child", "folder-b");
        ApiRequest parent = RunnerScriptTestFixtures.request(
                "parent",
                "Parent",
                1,
                "Collection",
                "https://example.test/parent",
                "pm.execution.runRequest('child-b');",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);
        ApiCollection collection = collection("Collection", parent, childA, childB);

        run(runner, collection, List.of(parent));

        assertThat(runner.getResults()).hasSize(2);
        assertThat(runner.getResults().get(0).requestId).isEqualTo("child-b");
        assertThat(runner.getResults().get(1).requestId).isEqualTo("parent");
    }

    @Test
    void ambiguityDiagnosticListsQualifiedCandidates() throws Exception {
        CollectionRunner runner = scriptRunner();
        ApiRequest childA = child("a", "Child", "folder-a");
        ApiRequest childB = child("b", "Child", "folder-b");
        ApiRequest parent = RunnerScriptTestFixtures.request(
                "parent",
                "Parent",
                1,
                "Collection",
                "https://example.test/parent",
                "pm.execution.runRequest('Child');",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);
        ApiCollection collection = collection("Collection", parent, childA, childB);

        run(runner, collection, List.of(parent));

        assertThat(runner.getResults()).hasSize(1);
        assertThat(runner.getResults().get(0).scriptWarnings)
                .anySatisfy(message -> {
                    assertThat(message).contains("Collection/folder-a/Child");
                    assertThat(message).contains("Collection/folder-b/Child");
                });
    }

    private static CollectionRunner flowRunner(java.util.function.Function<Integer, ExecutionResult> responder) {
        CollectionRunner runner = new CollectionRunner(null, new FlowPipeline(responder), null);
        runner.setDelayMs(0);
        return runner;
    }

    private static CollectionRunner scriptRunner() {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> captured = new CopyOnWriteArrayList<>();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, captured, () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")));
        runner.setDelayMs(0);
        return runner;
    }

    private static void run(CollectionRunner runner, ApiCollection collection, List<ApiRequest> requests) throws Exception {
        runner.runCollections(List.of(collection), requests);
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);
    }

    private static ApiCollection collection(String name, ApiRequest... requests) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        collection.requests = new ArrayList<>();
        collection.folderPaths = new ArrayList<>();
        for (ApiRequest request : requests) {
            collection.requests.add(request);
            if (request != null && request.path != null && !request.path.isBlank()) {
                collection.folderPaths.add(request.path);
            }
        }
        return collection;
    }

    private static ApiRequest child(String id, String name, String path) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.method = "GET";
        request.url = "https://example.test/" + id;
        request.path = path;
        request.sourceCollection = "Collection";
        return request;
    }

    private static ApiRequest request(String id, String name, String path, String script) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.method = "GET";
        request.url = "https://example.test/" + id;
        request.path = path;
        request.sourceCollection = "Collection";
        if (script != null) {
            request.scriptBlocks = new ArrayList<>();
            request.scriptBlocks.add(RunnerScriptTestFixtures.scriptBlock(id + "-script", ScriptDialect.POSTMAN, ScriptPhase.PRE_REQUEST, ScriptScope.REQUEST, script, 1));
        }
        return request;
    }

    private static ExecutionResult setNextResult(String nextName, String nextId) {
        ExecutionResult exec = new ExecutionResult();
        exec.success = false;
        exec.requestSent = false;
        exec.scriptFlowControl = burp.scripts.ScriptFlowControl.SET_NEXT_REQUEST;
        exec.scriptFlowNextRequestName = nextName;
        exec.scriptFlowNextRequestId = nextId;
        exec.errorMessage = "flow";
        return exec;
    }

    private static ExecutionResult successResult() {
        ExecutionResult exec = new ExecutionResult();
        exec.success = true;
        exec.requestSent = true;
        exec.response = RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain");
        return exec;
    }

    private static final class FlowPipeline extends SharedRequestPipeline {
        private final java.util.function.Function<Integer, ExecutionResult> responder;
        private final AtomicInteger calls = new AtomicInteger();

        private FlowPipeline(java.util.function.Function<Integer, ExecutionResult> responder) {
            super(null, new burp.utils.RequestBuilder(null), new burp.utils.ScriptEngine(null, burp.utils.ScriptMode.DISABLED), null);
            this.responder = responder;
        }

        @Override
        public ExecutionResult execute(ApiRequest req,
                                       ApiCollection col,
                                       boolean followRedirects,
                                       Map<String, String> runtimeOverlay,
                                       SharedRequestPipeline.OAuth2TokenSink oauth2TokenSink,
                                       SharedRequestPipeline.RuntimeVariableSink runtimeVariableSink,
                                       burp.models.EnvironmentProfile activeEnvironment,
                                       burp.scripts.ExecutionSource executionSource,
                                       burp.scripts.ScriptDependentRequestExecutor dependentRequestExecutor,
                                       burp.models.RedirectPolicy redirectPolicy,
                                       burp.utils.ExecutionPolicy executionPolicy,
                                       PreflightDecisionHandler preflightDecisionHandler,
                                       BooleanSupplier cancellationRequested) {
            return responder.apply(calls.incrementAndGet());
        }
    }
}
