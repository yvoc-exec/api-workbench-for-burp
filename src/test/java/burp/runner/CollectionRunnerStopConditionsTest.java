package burp.runner;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.RunnerResult;
import burp.models.RunnerStopConditions;
import burp.models.RunnerTerminationType;
import burp.utils.ExecutionResult;
import burp.utils.ExecutionPreflightStatus;
import burp.utils.SharedRequestPipeline;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionRunnerStopConditionsTest {

    @Test
    void stopOnAssertionFailureStopsAfterFailedAssertion() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<>();
        CollectionRunner runner = new CollectionRunner(null, new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
                calls.incrementAndGet();
                ExecutionResult exec = new ExecutionResult();
                exec.success = true;
                exec.response = mockResponse(200);
                exec.requestHeaders = "GET /assert HTTP/1.1\r\nHost: example.com\r\n\r\n";
                exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
                exec.assertions.add(new RunnerResult.AssertionResult("status check", false, "200", "500"));
                return exec;
            }
        }, null);
        runner.setMaxRetries(0);
        runner.setStopConditions(stopConditions(false, true, false, false, 0));
        runner.addListener(new TestListener(errors));

        ApiCollection collection = collection("Assertion Collection", request("First", 1, "http://example.com/first"));
        ApiRequest request2 = request("Second", 2, "http://example.com/second");
        collection.requests.add(request2);

        runner.runCollections(List.of(collection), List.of(collection.requests.get(0), request2));
        waitForRunnerToStop(runner);
        drainEdt();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(runner.getResults()).hasSize(1);
        assertThat(runner.getResults().get(0).assertions).hasSize(1);
        assertThat(runner.getResults().get(0).assertions.get(0).passed).isFalse();
        assertThat(runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.STOPPED_ON_ASSERTION_FAILURE);
        assertThat(errors).containsExactly("Stopped on assertion failure for First");
    }

    @Test
    void stopOnStatusAtLeast400StopsOnFailedStatus() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<>();
        CollectionRunner runner = new CollectionRunner(null, new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
                calls.incrementAndGet();
                ExecutionResult exec = new ExecutionResult();
                exec.success = true;
                exec.response = mockResponse(500);
                exec.requestHeaders = "GET /status HTTP/1.1\r\nHost: example.com\r\n\r\n";
                exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
                return exec;
            }
        }, null);
        runner.setMaxRetries(0);
        runner.setStopConditions(stopConditions(false, false, true, false, 0));
        runner.addListener(new TestListener(errors));

        ApiCollection collection = collection("Status Collection", request("First", 1, "http://example.com/first"));
        ApiRequest request2 = request("Second", 2, "http://example.com/second");
        collection.requests.add(request2);

        runner.runCollections(List.of(collection), List.of(collection.requests.get(0), request2));
        waitForRunnerToStop(runner);
        drainEdt();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(runner.getResults()).hasSize(1);
        assertThat(runner.getResults().get(0).statusCode).isEqualTo(500);
        assertThat(runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.STOPPED_ON_STATUS);
        assertThat(errors).containsExactly("Stopped on status >= 400 for First (500)");
    }

    @Test
    void stopOnStatusStopsWithoutCompletingRun() throws Exception {
        AtomicInteger completeCount = new AtomicInteger();
        AtomicInteger terminalCount = new AtomicInteger();
        CopyOnWriteArrayList<List<RunnerResult>> completedResults = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<burp.models.RunnerTerminationResult> terminalResults = new CopyOnWriteArrayList<>();
        AtomicInteger errorCount = new AtomicInteger();
        CollectionRunner runner = new CollectionRunner(null, new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
                ExecutionResult exec = new ExecutionResult();
                exec.success = true;
                exec.response = mockResponse(500, "ERR");
                return exec;
            }
        }, null);
        RunnerStopConditions conditions = new RunnerStopConditions();
        conditions.stopOnStatusAtLeast400 = true;
        runner.setStopConditions(conditions);
        runner.addListener(new CollectionRunner.RunnerListener() {
            @Override public void onStart(String collectionName, int totalRequests) { }
            @Override public void onSkip(String requestName, String reason) { }
            @Override public void onRequestComplete(RunnerResult result) { }
            @Override public void onTimeline(burp.models.RunnerTimelineRow row) { }
            @Override public void onComplete(List<RunnerResult> results) {
                completeCount.incrementAndGet();
                completedResults.add(results);
            }
            @Override public void onTerminal(burp.models.RunnerTerminationResult termination, List<RunnerResult> results) {
                terminalCount.incrementAndGet();
                terminalResults.add(termination);
            }
            @Override public void onError(String message) { errorCount.incrementAndGet(); }
        });

        ApiCollection collection = collectionWithTwoRequests();
        runner.runCollections(List.of(collection), collection.requests);
        waitForRunnerToStop(runner);
        drainEdt();

        assertThat(errorCount.get()).isEqualTo(1);
        assertThat(completeCount.get()).isZero();
        assertThat(terminalCount.get()).isEqualTo(1);
        assertThat(terminalResults.get(0).type).isEqualTo(RunnerTerminationType.STOPPED_ON_STATUS);
        assertThat(completedResults).isEmpty();
        assertThat(runner.getResults()).hasSize(1);
    }

    @Test
    void stopOnMissingVariableStopsBeforeRequestExecution() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<>();
        CollectionRunner runner = new CollectionRunner(null, new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
                calls.incrementAndGet();
                ExecutionResult exec = new ExecutionResult();
                exec.success = true;
                exec.response = mockResponse(200);
                exec.requestHeaders = "GET /missing HTTP/1.1\r\nHost: example.com\r\n\r\n";
                exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
                return exec;
            }
        }, null);
        runner.setMaxRetries(0);
        runner.setStopConditions(stopConditions(false, false, false, true, 0));
        runner.addListener(new TestListener(errors));

        ApiCollection collection = new ApiCollection();
        collection.name = "Missing Collection";
        ApiRequest request = request("Missing", 1, "{{baseUrl}}/path");
        request.sourceCollection = collection.name;
        collection.requests.add(request);

        runner.runCollections(List.of(collection), List.of(request));
        waitForRunnerToStop(runner);
        drainEdt();

        assertThat(calls.get()).isZero();
        assertThat(runner.getResults()).hasSize(1);
        assertThat(runner.getResults().get(0).requestSent).isFalse();
        assertThat(runner.getResults().get(0).preflightStatus)
                .isIn(ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES, ExecutionPreflightStatus.BLOCKED_POLICY);
        assertThat(runner.getResults().get(0).errorMessage).contains("Request not sent");
        assertThat(runner.getLastTerminationResult()).isNotNull();
    }

    @Test
    void stopOnMissingVariableDoesNotStopWhenDefaultValueExists() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<>();
        CollectionRunner runner = new CollectionRunner(null, new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
                calls.incrementAndGet();
                ExecutionResult exec = new ExecutionResult();
                exec.success = true;
                exec.response = mockResponse(200);
                exec.requestHeaders = "GET /default HTTP/1.1\r\nHost: example.com\r\n\r\n";
                exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
                return exec;
            }
        }, null);
        runner.setMaxRetries(0);
        runner.setStopConditions(stopConditions(false, false, false, true, 0));
        runner.addListener(new TestListener(errors));

        ApiCollection collection = new ApiCollection();
        collection.name = "Default Collection";
        ApiRequest request = request("Default", 1, "http://example.com/{{base_url|https://example.com}}/path");
        request.sourceCollection = collection.name;
        collection.requests.add(request);

        runner.runCollections(List.of(collection), List.of(request));
        waitForRunnerToStop(runner);
        drainEdt();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(runner.getResults()).hasSize(1);
        assertThat(errors).isEmpty();
    }

    @Test
    void stopAfterFailureCountStopsAtThreshold() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<>();
        CollectionRunner runner = new CollectionRunner(null, new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
                int call = calls.incrementAndGet();
                ExecutionResult exec = new ExecutionResult();
                exec.success = call > 2;
                exec.errorMessage = exec.success ? null : "temporary failure " + call;
                exec.requestHeaders = "GET /failure HTTP/1.1\r\nHost: example.com\r\n\r\n";
                exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
                exec.response = exec.success ? mockResponse(200) : null;
                return exec;
            }
        }, null);
        runner.setMaxRetries(0);
        runner.setStopConditions(stopConditions(false, false, false, false, 2));
        runner.addListener(new TestListener(errors));

        ApiCollection collection = new ApiCollection();
        collection.name = "Failure Collection";
        ApiRequest request1 = request("First", 1, "http://example.com/1");
        ApiRequest request2 = request("Second", 2, "http://example.com/2");
        ApiRequest request3 = request("Third", 3, "http://example.com/3");
        collection.requests.add(request1);
        collection.requests.add(request2);
        collection.requests.add(request3);

        runner.runCollections(List.of(collection), List.of(request1, request2, request3));
        waitForRunnerToStop(runner);
        drainEdt();

        assertThat(calls.get()).isEqualTo(2);
        assertThat(runner.getResults()).hasSize(2);
        assertThat(runner.getResults()).allMatch(result -> !result.success);
        assertThat(runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.STOPPED_ON_FAILURE_COUNT);
        assertThat(errors).containsExactly("Stopped after failure count reached: 2/2");
    }

    @Test
    void stopOnErrorStopsWithTerminalState() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CollectionRunner runner = new CollectionRunner(null, new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects) {
                calls.incrementAndGet();
                ExecutionResult exec = new ExecutionResult();
                exec.success = false;
                exec.errorMessage = "boom";
                exec.requestHeaders = "GET /error HTTP/1.1\r\nHost: example.com\r\n\r\n";
                exec.rawRequestBytes = exec.requestHeaders.getBytes(StandardCharsets.UTF_8);
                return exec;
            }
        }, null);
        runner.setMaxRetries(0);
        runner.setStopConditions(stopConditions(true, false, false, false, 0));
        runner.addListener(new TestListener(new CopyOnWriteArrayList<>()));

        ApiCollection collection = collection("Error Collection", request("First", 1, "http://example.com/first"));
        ApiRequest request2 = request("Second", 2, "http://example.com/second");
        collection.requests.add(request2);

        runner.runCollections(List.of(collection), List.of(collection.requests.get(0), request2));
        waitForRunnerToStop(runner);
        drainEdt();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(runner.getResults()).hasSize(1);
        assertThat(runner.getLastTerminationResult().type).isEqualTo(RunnerTerminationType.STOPPED_ON_ERROR);
    }

    private static RunnerStopConditions stopConditions(boolean stopOnError,
                                                       boolean stopOnAssertionFailure,
                                                       boolean stopOnStatusAtLeast400,
                                                       boolean stopOnMissingVariable,
                                                       int stopAfterFailureCount) {
        RunnerStopConditions conditions = new RunnerStopConditions();
        conditions.stopOnError = stopOnError;
        conditions.stopOnAssertionFailure = stopOnAssertionFailure;
        conditions.stopOnStatusAtLeast400 = stopOnStatusAtLeast400;
        conditions.stopOnMissingVariable = stopOnMissingVariable;
        conditions.stopAfterFailureCount = stopAfterFailureCount;
        return conditions;
    }

    private static ApiCollection collection(String name, ApiRequest firstRequest) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        collection.requests.add(firstRequest);
        return collection;
    }

    private static ApiCollection collectionWithTwoRequests() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Status Collection";
        ApiRequest first = request("First", 1, "http://example.com/first");
        first.sourceCollection = collection.name;
        ApiRequest second = request("Second", 2, "http://example.com/second");
        second.sourceCollection = collection.name;
        collection.requests.add(first);
        collection.requests.add(second);
        return collection;
    }

    private static ApiRequest request(String name, int order, String url) {
        ApiRequest request = new ApiRequest();
        request.name = name;
        request.sequenceOrder = order;
        request.url = url;
        return request;
    }

    private static void waitForRunnerToStop(CollectionRunner runner) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1500;
        while (runner.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(runner.isRunning()).isFalse();
    }

    private static void drainEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static HttpRequestResponse mockResponse(int statusCode) {
        return mockResponse(statusCode, "OK");
    }

    private static HttpRequestResponse mockResponse(int statusCode, String bodyText) {
        HttpResponse response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn((short) statusCode);
        ByteArray body = mock(ByteArray.class);
        when(body.length()).thenReturn(bodyText.length());
        when(response.body()).thenReturn(body);
        when(response.bodyToString()).thenReturn(bodyText);
        when(response.headers()).thenReturn(Collections.emptyList());

        HttpRequestResponse responseWrapper = mock(HttpRequestResponse.class);
        when(responseWrapper.response()).thenReturn(response);
        when(responseWrapper.withAnnotations(any(Annotations.class))).thenReturn(responseWrapper);
        return responseWrapper;
    }

    private static class TestListener implements CollectionRunner.RunnerListener {
        private final CopyOnWriteArrayList<String> errors;

        private TestListener(CopyOnWriteArrayList<String> errors) {
            this.errors = errors;
        }

        @Override public void onStart(String collectionName, int totalRequests) { }
        @Override public void onSkip(String requestName, String reason) { }
        @Override public void onRequestComplete(RunnerResult result) { }
        @Override public void onComplete(List<RunnerResult> results) { }
        @Override public void onError(String message) { errors.add(message); }
    }
}
