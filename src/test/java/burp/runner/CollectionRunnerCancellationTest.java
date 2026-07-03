package burp.runner;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerCancellationState;
import burp.models.RunnerResult;
import burp.models.RunnerTerminationType;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.testsupport.RunnerScriptTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionRunnerCancellationTest {

    @Test
    void cancelTerminatesInfiniteScript() throws Exception {
        AtomicInteger sendCount = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")));
        ApiCollection collection = collection("Collection", request("parent", "Parent", """
                while (true) {}
                """));

        runner.runCollections(List.of(collection), List.of(collection.requests.get(0)));
        waitForRunning(runner);
        runner.cancel();
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(sendCount.get()).isZero();
        assertThat(runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.CANCELLED);
    }

    @Test
    void cancelDuringHttpWaitProducesSingleCancelledTerminal() throws Exception {
        HttpWaitScenario scenario = httpWaitScenario(false);
        scenario.runner.runCollections(List.of(scenario.collection), List.of(scenario.collection.requests.get(0)));
        await(scenario.sendStarted, 2_000L);
        scenario.runner.cancel();
        scenario.releaseResponse.countDown();
        RunnerScriptTestFixtures.waitForRunnerToStop(scenario.runner);

        assertThat(scenario.runner.getResults()).hasSize(1);
        assertThat(scenario.runner.getResults().get(0).cancellationState).isEqualTo(RunnerCancellationState.CANCELLED_DURING_HTTP_WAIT);
        assertThat(scenario.listener.terminalResults).hasSize(1);
        assertThat(scenario.listener.terminalResults.get(0).type).isEqualTo(RunnerTerminationType.CANCELLED);
    }

    @Test
    void lateResponseAfterCancelIsIgnored() throws Exception {
        HttpWaitScenario scenario = httpWaitScenario(false);
        scenario.runner.runCollections(List.of(scenario.collection), List.of(scenario.collection.requests.get(0)));
        await(scenario.sendStarted, 2_000L);
        scenario.runner.cancel();
        scenario.releaseResponse.countDown();
        RunnerScriptTestFixtures.waitForRunnerToStop(scenario.runner);

        RunnerResult result = scenario.runner.getResults().get(0);
        assertThat(result.success).isFalse();
        assertThat(result.cancellationState).isEqualTo(RunnerCancellationState.CANCELLED_DURING_HTTP_WAIT);
        assertThat(result.responseBody).isNull();
        assertThat(result.responseHeaders).isNull();
    }

    @Test
    void lateResponseDoesNotRunPostResponseScript() throws Exception {
        HttpWaitScenario scenario = httpWaitScenario(true);
        scenario.runner.runCollections(List.of(scenario.collection), List.of(scenario.collection.requests.get(0)));
        await(scenario.sendStarted, 2_000L);
        scenario.runner.cancel();
        scenario.releaseResponse.countDown();
        RunnerScriptTestFixtures.waitForRunnerToStop(scenario.runner);

        RunnerResult result = scenario.runner.getResults().get(0);
        assertThat(result.scriptLogs).noneMatch(log -> log != null && log.message != null && log.message.contains("post response"));
        assertThat(result.scriptVariableMutations).isEmpty();
    }

    @Test
    void lateResponseDoesNotCommitVariables() throws Exception {
        HttpWaitScenario scenario = httpWaitScenario(true);
        EnvironmentProfile environment = new EnvironmentProfile();
        scenario.runner.setRuntimeOverlayProvider(collection -> environment.toRuntimeOverlay());
        scenario.runner.setActiveEnvironmentProvider(collection -> environment);
        scenario.runner.setRuntimeVariableSink((collection, changedVars, removedKeys) -> {
            if (changedVars != null) {
                environment.variables.putAll(changedVars);
            }
            if (removedKeys != null) {
                removedKeys.forEach(environment.variables::remove);
            }
        });

        scenario.runner.runCollections(List.of(scenario.collection), List.of(scenario.collection.requests.get(0)));
        await(scenario.sendStarted, 2_000L);
        scenario.runner.cancel();
        scenario.releaseResponse.countDown();
        RunnerScriptTestFixtures.waitForRunnerToStop(scenario.runner);

        assertThat(environment.variables).doesNotContainKey("committed");
    }

    @Test
    void cancelledAttemptIsPublishedOnce() throws Exception {
        HttpWaitScenario scenario = httpWaitScenario(false);
        scenario.runner.runCollections(List.of(scenario.collection), List.of(scenario.collection.requests.get(0)));
        await(scenario.sendStarted, 2_000L);
        scenario.runner.cancel();
        scenario.releaseResponse.countDown();
        RunnerScriptTestFixtures.waitForRunnerToStop(scenario.runner);

        assertThat(scenario.listener.attemptResults).hasSize(1);
        assertThat(scenario.listener.timelineRows).hasSize(1);
        assertThat(scenario.listener.terminalResults).hasSize(1);
    }

    @Test
    void dependentRequestDoesNotStartAfterCancel() throws Exception {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> captured = new CopyOnWriteArrayList<>();
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, captured, () -> {
                    int call = sendCount.get();
                    if (call == 1) {
                        childStarted.countDown();
                        awaitQuietly(releaseChild);
                    }
                    return RunnerScriptTestFixtures.mockResponse(call == 1 ? 200 : 201, "OK", "text/plain");
                }));
        ApiRequest childOne = request("child-1", "Child", """
                console.log('child one');
                """);
        ApiRequest childTwo = request("child-2", "Child2", """
                console.log('child two');
                """);
        ApiRequest parent = request("parent", "Parent", """
                pm.execution.runRequest('Child');
                pm.execution.runRequest('Child2');
                """);
        ApiCollection collection = collection("Collection", parent, childOne, childTwo);

        runner.runCollections(List.of(collection), List.of(parent));
        await(childStarted, 2_000L);
        runner.cancel();
        releaseChild.countDown();
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(sendCount.get()).isLessThan(3);
    }

    @Test
    void dependentStacksUnwindAfterCancel() throws Exception {
        HttpWaitScenario scenario = httpWaitScenario(false);
        scenario.runner.runCollections(List.of(scenario.collection), List.of(scenario.collection.requests.get(0)));
        await(scenario.sendStarted, 2_000L);
        scenario.runner.cancel();
        scenario.releaseResponse.countDown();
        RunnerScriptTestFixtures.waitForRunnerToStop(scenario.runner);

        assertThat(scenario.runner.isRunning()).isFalse();
        assertThat(scenario.runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.CANCELLED);
    }

    @Test
    void runnerCanStartAgainAfterCancel() throws Exception {
        AtomicInteger sendCount = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")));
        ApiCollection collection = collection("Collection", request("parent", "Parent", """
                while (true) {}
                """));

        runner.runCollections(List.of(collection), List.of(collection.requests.get(0)));
        waitForRunning(runner);
        runner.cancel();
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        ApiCollection secondCollection = collection("Collection", request("second", "Second", null));
        runner.runCollections(List.of(secondCollection), List.of(secondCollection.requests.get(0)));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(runner.isRunning()).isFalse();
        assertThat(runner.getResults()).isNotEmpty();
    }

    private static HttpWaitScenario httpWaitScenario(boolean includePostResponseScript) {
        AtomicInteger sendCount = new AtomicInteger();
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> {
                    sendStarted.countDown();
                    awaitQuietly(releaseResponse);
                    return RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain");
                }));
        runner.setDelayMs(0);

        ApiRequest request = request("request-1", "Request", null);
        if (includePostResponseScript) {
            request.scriptBlocks = new ArrayList<>();
            request.scriptBlocks.add(RunnerScriptTestFixtures.scriptBlock(
                    "post-response",
                    ScriptDialect.POSTMAN,
                    ScriptPhase.POST_RESPONSE,
                    ScriptScope.REQUEST,
                    """
                            console.log('post response');
                            pm.environment.set('committed', 'yes');
                            """,
                    2));
        }
        ApiCollection collection = collection("Collection", request);
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);
        return new HttpWaitScenario(runner, collection, listener, sendStarted, releaseResponse);
    }

    private static ApiCollection collection(String name, ApiRequest... requests) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        collection.requests = new ArrayList<>();
        if (requests != null) {
            collection.requests.addAll(List.of(requests));
        }
        return collection;
    }

    private static ApiRequest request(String id, String name, String script) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.method = "GET";
        request.url = "https://example.test/" + id;
        request.sourceCollection = "Collection";
        request.sequenceOrder = 1;
        if (script != null) {
            request.scriptBlocks = new ArrayList<>();
            request.scriptBlocks.add(RunnerScriptTestFixtures.scriptBlock(
                    id + "-script",
                    ScriptDialect.POSTMAN,
                    ScriptPhase.PRE_REQUEST,
                    ScriptScope.REQUEST,
                    script,
                    1));
        }
        return request;
    }

    private static void waitForRunning(CollectionRunner runner) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (!runner.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(runner.isRunning()).isTrue();
    }

    private static void await(CountDownLatch latch, long timeoutMillis) throws InterruptedException {
        assertThat(latch.await(timeoutMillis, TimeUnit.MILLISECONDS)).isTrue();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record HttpWaitScenario(CollectionRunner runner,
                                    ApiCollection collection,
                                    RunnerScriptTestFixtures.RecordingRunnerListener listener,
                                    CountDownLatch sendStarted,
                                    CountDownLatch releaseResponse) {
    }
}
