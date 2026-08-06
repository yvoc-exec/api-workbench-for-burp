package burp.runner;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.RunnerResult;
import burp.models.RunnerResultSummary;
import burp.testsupport.RunnerScriptTestFixtures;
import burp.utils.ExecutionPolicy;
import burp.utils.ExecutionResult;
import burp.utils.PreflightDecisionHandler;
import burp.utils.RequestBuilder;
import burp.utils.ScriptEngine;
import burp.utils.ScriptMode;
import burp.utils.SharedRequestPipeline;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionRunnerResultRetentionTest {
    private static final int RETAINED_SUMMARY_LIMIT = 5_000;

    @Test
    void captureSeesFullEvidenceWhileCallbacksAndRetentionAreCompact() {
        AtomicInteger sends = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(
                        sends, new CopyOnWriteArrayList<>(),
                        () -> RunnerScriptTestFixtures.mockResponse(200, "response-secret", "text/plain")));
        AtomicInteger captures = new AtomicInteger();
        runner.setResultCaptureHandler(result -> {
            assertThat(result.responseBody).isEqualTo("response-secret");
            assertThat(result.rawRequestBytes).isNotEmpty();
            result.historyEntryId = "history-" + captures.incrementAndGet();
            result.fullEvidenceRetained = true;
            result.evidenceRetentionMessage = "Full evidence retained in History.";
        });
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);
        ApiRequest request = RunnerScriptTestFixtures.request(
                "request-1", "Request", 1, "Collection", "https://api.example.test/items",
                null, RunnerScriptTestFixtures.nativeDialect(), burp.scripts.ScriptPhase.PRE_REQUEST,
                burp.scripts.ScriptScope.REQUEST);
        ApiCollection collection = RunnerScriptTestFixtures.collection("Collection", request);

        runner.runCollections(List.of(collection), List.of(request));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(captures).hasValue(1);
        assertThat(listener.attemptResults).hasSize(1).allSatisfy(CollectionRunnerResultRetentionTest::assertCompact);
        assertThat(listener.requestResults).hasSize(1).allSatisfy(CollectionRunnerResultRetentionTest::assertCompact);
        assertThat(runner.getResultSummaries()).hasSize(1);
        assertThat(runner.getResultSummaries().get(0).historyEntryId()).isEqualTo("history-1");
        RunnerResult first = runner.getResults().get(0);
        first.requestName = "mutated";
        assertThat(runner.getResults().get(0).requestName).isEqualTo("Request");
        assertThat(listener.completedRuns).hasSize(1);
        assertThat(listener.completedRuns.get(0)).allSatisfy(CollectionRunnerResultRetentionTest::assertCompact);
        assertThat(CollectionRunner.class.getDeclaredFields())
                .extracting(Field::getType)
                .doesNotContain(CopyOnWriteArrayList.class, RunnerResult.class);
    }

    @Test
    void retryAttemptsReceiveIndependentHistoryIdsAndFinalResultUsesFinalAttempt() {
        RetryPipeline pipeline = new RetryPipeline();
        CollectionRunner runner = new CollectionRunner(null, pipeline, null);
        runner.setDelayMs(0);
        RunnerRetryPolicy retry = RunnerRetryPolicy.safeDefaults();
        retry.maxRetries = 1;
        retry.retryConnectionFailures = true;
        retry.normalize();
        runner.setRetryPolicy(retry);
        runner.setResultCaptureHandler(result -> {
            result.historyEntryId = "history-attempt-" + result.attemptNumber;
            result.fullEvidenceRetained = true;
        });
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);
        ApiRequest request = new ApiRequest();
        request.id = "retry";
        request.name = "Retry";
        request.method = "GET";
        request.url = "https://api.example.test/retry";
        ApiCollection collection = RunnerScriptTestFixtures.collection("Retry", request);

        runner.runCollections(List.of(collection), List.of(request));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(listener.attemptResults).extracting(result -> result.historyEntryId)
                .containsExactly("history-attempt-1", "history-attempt-2");
        assertThat(runner.getResults()).extracting(result -> result.historyEntryId)
                .containsExactly("history-attempt-2");
        assertThat(runner.getResultSummaries()).extracting(RunnerResultSummary::historyEntryId)
                .containsExactly("history-attempt-2");
    }

    @Test
    void retainedSummariesAreBoundedAndDiscardTheOldestResult() throws Exception {
        CollectionRunner runner = new CollectionRunner(null, null, null);

        for (int i = 0; i <= RETAINED_SUMMARY_LIMIT; i++) {
            retain(runner, compactableResult("request-" + i), true);
        }

        assertThat(runner.getResultSummaries()).hasSize(RETAINED_SUMMARY_LIMIT);
        assertThat(runner.getResultSummaries().get(0).requestName()).isEqualTo("request-1");
        assertThat(runner.getResultSummaries().get(RETAINED_SUMMARY_LIMIT - 1).requestName())
                .isEqualTo("request-5000");
        assertThat(runner.getDiscardedResultSummaryCount()).isEqualTo(1L);
    }

    @Test
    void clearRetainedResultsClearsSummaryAndCompatibilityOwnership() throws Exception {
        CollectionRunner runner = new CollectionRunner(null, null, null);
        retain(runner, compactableResult("request"), true);

        assertThat(runner.clearRetainedResults()).isTrue();

        assertThat(runner.getResultSummaries()).isEmpty();
        assertThat(runner.getResults()).isEmpty();
        assertThat(runner.getDiscardedResultSummaryCount()).isZero();
    }

    @Test
    void repeatedRunAndClearCyclesDoNotRetainSummaries() {
        AtomicInteger sends = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(
                        sends, new CopyOnWriteArrayList<>(),
                        () -> RunnerScriptTestFixtures.mockResponse(200, "ok", "text/plain")));
        runner.setDelayMs(0);
        runner.setResultCaptureHandler(result -> {
            result.historyEntryId = "history-" + sends.get();
            result.fullEvidenceRetained = true;
        });
        ApiRequest request = RunnerScriptTestFixtures.request(
                "request", "Request", 1, "Collection", "https://api.example.test/items",
                null, RunnerScriptTestFixtures.nativeDialect(), burp.scripts.ScriptPhase.PRE_REQUEST,
                burp.scripts.ScriptScope.REQUEST);
        ApiCollection collection = RunnerScriptTestFixtures.collection("Collection", request);

        for (int cycle = 0; cycle < 5; cycle++) {
            runner.runCollections(List.of(collection), List.of(request));
            RunnerScriptTestFixtures.waitForRunnerToStop(runner);
            assertThat(runner.getResultSummaries()).hasSize(1);
            assertThat(runner.clearRetainedResults()).isTrue();
            assertThat(runner.getResultSummaries()).isEmpty();
        }
    }

    private static RunnerResult compactableResult(String requestName) {
        RunnerResult result = new RunnerResult();
        result.requestName = requestName;
        result.canonicalCaptureComplete = true;
        return result;
    }

    private static void retain(CollectionRunner runner, RunnerResult result, boolean releaseOriginal) throws Exception {
        Method method = CollectionRunner.class.getDeclaredMethod(
                "retainCompletedResult", RunnerResult.class, boolean.class);
        method.setAccessible(true);
        method.invoke(runner, result, releaseOriginal);
    }

    private static void assertCompact(RunnerResult result) {
        assertThat(result.responseBody).isNull();
        assertThat(result.responseHeaders).isNull();
        assertThat(result.requestBody).isNull();
        assertThat(result.requestHeaders).isNull();
        assertThat(result.rawRequestBytes).isNull();
        assertThat(result.rawRequestText).isNull();
        assertThat(result.isHeavyPayloadReleased()).isTrue();
    }

    private static final class RetryPipeline extends SharedRequestPipeline {
        private final AtomicInteger calls = new AtomicInteger();

        private RetryPipeline() {
            super(null, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);
        }

        @Override
        public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                       Map<String, String> runtimeOverlay, OAuth2TokenSink oauth2TokenSink,
                                       RuntimeVariableSink runtimeVariableSink,
                                       burp.models.EnvironmentProfile activeEnvironment,
                                       burp.scripts.ExecutionSource executionSource,
                                       burp.scripts.ScriptDependentRequestExecutor dependentRequestExecutor,
                                       burp.models.RedirectPolicy redirectPolicy,
                                       ExecutionPolicy executionPolicy,
                                       PreflightDecisionHandler preflightDecisionHandler,
                                       BooleanSupplier cancellationRequested) {
            ExecutionResult result = new ExecutionResult();
            result.requestSent = true;
            result.rawRequestBytes = "GET /retry HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8);
            result.requestHeaders = new String(result.rawRequestBytes, StandardCharsets.UTF_8);
            result.resolvedUrl = req.url;
            if (calls.incrementAndGet() == 1) {
                result.success = false;
                result.errorMessage = "connection failed";
            } else {
                result.success = true;
                result.response = RunnerScriptTestFixtures.mockResponse(200, "ok", "text/plain");
            }
            return result;
        }
    }
}
