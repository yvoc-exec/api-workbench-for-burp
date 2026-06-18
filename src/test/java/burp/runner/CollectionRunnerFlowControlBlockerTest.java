package burp.runner;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.RequestOptions;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.RunnerStopConditions;
import burp.models.RunnerTimelineRow;
import burp.scripts.ScriptBlock;
import burp.utils.RequestBuilder;
import burp.utils.ScriptEngine;
import burp.utils.ScriptMode;
import burp.utils.SharedRequestPipeline;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionRunnerFlowControlBlockerTest {

    @Test
    void stopExecutionShouldStopTheRunnerAfterCurrentRequest() throws Exception {
        FlowHarness harness = flowHarness();
        ApiCollection collection = collectionWithThreeRequests(harness.collectionName(),
                scriptRequest("One", 1, harness.collectionName(), """
                        pm.execution.stopExecution();
                        console.log('stop');
                        """),
                scriptRequest("Two", 2, harness.collectionName(), null),
                scriptRequest("Three", 3, harness.collectionName(), null));

        harness.runner().runCollections(List.of(collection), collection.requests);
        waitForRunnerToStop(harness.runner());

        assertThat(harness.sendCalls().get()).isEqualTo(1);
        assertThat(harness.runner().getResults()).hasSize(1);
        assertThat(harness.runner().getResults().get(0).success).isTrue();
        assertThat(harness.runner().getResults().get(0).scriptFlowControl).isEqualTo(burp.scripts.ScriptFlowControl.STOP_RUN);
        assertThat(harness.runner().getResults().get(0).displayStatusLabel()).isEqualTo("Stopped by Script");
        assertThat(harness.timelineRows()).hasSize(1);
        assertThat(harness.timelineRows().get(0).status).isEqualTo("Stopped by Script");
    }

    @Test
    void skipRequestShouldSkipSendingTheCurrentRunnerRequest() throws Exception {
        FlowHarness harness = flowHarness();
        RunnerStopConditions stopConditions = new RunnerStopConditions();
        stopConditions.stopAfterFailureCount = 1;
        harness.runner().setStopConditions(stopConditions);
        ApiCollection collection = collectionWithThreeRequests(harness.collectionName(),
                scriptRequest("One", 1, harness.collectionName(), null),
                scriptRequest("Two", 2, harness.collectionName(), """
                        pm.execution.skipRequest();
                        console.log('skip');
                        """),
                scriptRequest("Three", 3, harness.collectionName(), null));

        harness.runner().runCollections(List.of(collection), collection.requests);
        waitForRunnerToStop(harness.runner());

        assertThat(harness.sendCalls().get()).isEqualTo(2);
        assertThat(harness.runner().getResults()).hasSize(3);
        assertThat(harness.runner().getResults().get(1).success).isTrue();
        assertThat(harness.runner().getResults().get(1).scriptFlowControl).isEqualTo(burp.scripts.ScriptFlowControl.SKIP_REQUEST);
        assertThat(harness.runner().getResults().get(1).displayStatusLabel()).isEqualTo("Skipped by Script");
        assertThat(harness.timelineRows()).hasSize(3);
        assertThat(harness.timelineRows().get(1).status).isEqualTo("Skipped by Script");
    }

    @Test
    void setNextRequestShouldJumpToTheNamedRequest() throws Exception {
        FlowHarness harness = flowHarness();
        ApiCollection collection = collectionWithThreeRequests(harness.collectionName(),
                scriptRequest("One", 1, harness.collectionName(), """
                        pm.execution.setNextRequest('Three');
                        console.log('jump');
                        """),
                scriptRequest("Two", 2, harness.collectionName(), null),
                scriptRequest("Three", 3, harness.collectionName(), null));

        harness.runner().runCollections(List.of(collection), collection.requests);
        waitForRunnerToStop(harness.runner());

        assertThat(harness.sendCalls().get()).isEqualTo(2);
        assertThat(harness.runner().getResults()).hasSize(2);
        assertThat(harness.runner().getResults().get(0).success).isTrue();
        assertThat(harness.runner().getResults().get(0).scriptFlowControl).isEqualTo(burp.scripts.ScriptFlowControl.SET_NEXT_REQUEST);
        assertThat(harness.timelineRows()).hasSize(2);
        assertThat(harness.timelineRows().get(0).status).isEqualTo("200");
    }

    private static FlowHarness flowHarness() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        AtomicInteger sendCalls = new AtomicInteger();
        CopyOnWriteArrayList<RunnerTimelineRow> timelineRows = new CopyOnWriteArrayList<>();
        java.util.function.Function<org.mockito.invocation.InvocationOnMock, HttpRequestResponse> responder = invocation -> {
            sendCalls.incrementAndGet();
            return mockResponse();
        };
        when(api.http().sendRequest(any(HttpRequest.class))).thenAnswer(invocation -> responder.apply(invocation));
        when(api.http().sendRequest(any(HttpRequest.class), any(RequestOptions.class))).thenAnswer(invocation -> {
            sendCalls.incrementAndGet();
            return mockResponse();
        });
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.FULL_JS), null);
        CollectionRunner runner = new CollectionRunner(api, pipeline, null);
        runner.setDelayMs(0);
        runner.setMaxRetries(0);
        runner.addListener(new CollectionRunner.RunnerListener() {
            @Override public void onStart(String collectionName, int totalRequests) { }
            @Override public void onSkip(String requestName, String reason) { }
            @Override public void onRequestComplete(burp.models.RunnerResult result) { }
            @Override public void onComplete(List<burp.models.RunnerResult> results) { }
            @Override public void onError(String message) { }
            @Override public void onTimeline(RunnerTimelineRow row) { timelineRows.add(row); }
        });
        return new FlowHarness(api, runner, sendCalls, timelineRows, "FlowCollection");
    }

    private static ApiCollection collectionWithThreeRequests(String collectionName,
                                                             ApiRequest first,
                                                             ApiRequest second,
                                                             ApiRequest third) {
        ApiCollection collection = new ApiCollection();
        collection.name = collectionName;
        collection.requests = new ArrayList<>(List.of(first, second, third));
        return collection;
    }

    private static ApiRequest scriptRequest(String name, int order, String collectionName, String script) {
        ApiRequest request = new ApiRequest();
        request.name = name;
        request.sourceCollection = collectionName;
        request.sequenceOrder = order;
        request.method = "GET";
        request.url = "http://example.com/" + name.toLowerCase();
        request.scriptBlocks = new ArrayList<>();
        if (script != null && !script.isBlank()) {
            ScriptBlock block = new ScriptBlock();
            block.id = name + "-pre";
            block.dialect = burp.scripts.ScriptDialect.POSTMAN;
            block.phase = burp.scripts.ScriptPhase.PRE_REQUEST;
            block.scope = burp.scripts.ScriptScope.REQUEST;
            block.source = script;
            block.enabled = true;
            request.scriptBlocks.add(block);
        }
        return request;
    }

    private static HttpRequestResponse mockResponse() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn((short) 200);
        ByteArray body = mock(ByteArray.class);
        when(body.length()).thenReturn(2);
        when(body.getBytes()).thenReturn("OK".getBytes(StandardCharsets.UTF_8));
        when(response.body()).thenReturn(body);
        when(response.bodyToString()).thenReturn("OK");
        when(response.headers()).thenReturn(List.of());

        HttpRequestResponse responseWrapper = mock(HttpRequestResponse.class);
        when(responseWrapper.response()).thenReturn(response);
        when(responseWrapper.withAnnotations(any(Annotations.class))).thenReturn(responseWrapper);
        return responseWrapper;
    }

    private static void waitForRunnerToStop(CollectionRunner runner) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (runner.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(runner.isRunning()).isFalse();
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record FlowHarness(MontoyaApi api, CollectionRunner runner, AtomicInteger sendCalls, CopyOnWriteArrayList<RunnerTimelineRow> timelineRows, String collectionName) {
    }
}
