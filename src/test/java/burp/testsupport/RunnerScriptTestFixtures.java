package burp.testsupport;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.RunnerResult;
import burp.models.RunnerTimelineRow;
import burp.runner.CollectionRunner;
import burp.scripts.ExecutionSource;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.utils.RequestBuilder;
import burp.utils.ScriptEngine;
import burp.utils.ScriptMode;
import burp.utils.SharedRequestPipeline;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class RunnerScriptTestFixtures {
    private RunnerScriptTestFixtures() {
    }

    public static MontoyaApi mockRunnerApi(AtomicInteger sendCount,
                                           CopyOnWriteArrayList<HttpRequest> capturedRequests,
                                           Supplier<HttpRequestResponse> responseSupplier) {
        MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        when(api.http().sendRequest(any(HttpRequest.class))).thenAnswer(invocation -> {
            sendCount.incrementAndGet();
            HttpRequest request = invocation.getArgument(0);
            if (capturedRequests != null) {
                capturedRequests.add(request);
            }
            return responseSupplier != null ? responseSupplier.get() : mockResponse(200, "OK", "text/plain");
        });
        when(api.http().sendRequest(any(HttpRequest.class), any(RequestOptions.class))).thenAnswer(invocation -> {
            sendCount.incrementAndGet();
            HttpRequest request = invocation.getArgument(0);
            if (capturedRequests != null) {
                capturedRequests.add(request);
            }
            return responseSupplier != null ? responseSupplier.get() : mockResponse(200, "OK", "text/plain");
        });
        return api;
    }

    public static CollectionRunner newRunner(MontoyaApi api) {
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.FULL_JS), null);
        CollectionRunner runner = new CollectionRunner(api, pipeline, null);
        runner.setDelayMs(0);
        runner.setMaxRetries(0);
        return runner;
    }

    public static HttpRequestResponse mockResponse(int statusCode, String body, String contentType) {
        HttpResponse response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn((short) statusCode);
        ByteArray bodyBytes = mock(ByteArray.class);
        byte[] bytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        when(bodyBytes.getBytes()).thenReturn(bytes);
        when(bodyBytes.length()).thenReturn(bytes.length);
        when(response.body()).thenReturn(bodyBytes);
        when(response.bodyToString()).thenReturn(body != null ? body : "");
        burp.api.montoya.http.message.HttpHeader responseHeader = mock(burp.api.montoya.http.message.HttpHeader.class);
        when(responseHeader.name()).thenReturn("Content-Type");
        when(responseHeader.value()).thenReturn(contentType != null ? contentType : "text/plain");
        when(responseHeader.toString()).thenReturn("Content-Type: " + (contentType != null ? contentType : "text/plain"));
        when(response.headers()).thenReturn(List.of(responseHeader));

        HttpRequestResponse responseWrapper = mock(HttpRequestResponse.class);
        when(responseWrapper.response()).thenReturn(response);
        when(responseWrapper.withAnnotations(any(Annotations.class))).thenReturn(responseWrapper);
        return responseWrapper;
    }

    public static ApiCollection collection(String name, ApiRequest... requests) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        collection.format = "api-workbench";
        collection.requests = new ArrayList<>();
        if (requests != null) {
            for (ApiRequest request : requests) {
                if (request != null) {
                    collection.requests.add(request);
                }
            }
        }
        return collection;
    }

    public static ApiRequest request(String id,
                                     String name,
                                     int order,
                                     String sourceCollection,
                                     String url,
                                     String scriptSource,
                                     ScriptDialect dialect,
                                     ScriptPhase phase,
                                     ScriptScope scope) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.sequenceOrder = order;
        request.sourceCollection = sourceCollection;
        request.method = "POST";
        request.url = url;
        request.headers = new ArrayList<>();
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"name\":\"" + name + "\"}";
        request.body.contentType = "application/json";
        if (scriptSource != null && !scriptSource.isBlank()) {
            request.scriptBlocks = new ArrayList<>();
            request.scriptBlocks.add(scriptBlock(id + "-" + phase.name().toLowerCase(), dialect, phase, scope, scriptSource, order));
        }
        return request;
    }

    public static ScriptBlock scriptBlock(String id,
                                          ScriptDialect dialect,
                                          ScriptPhase phase,
                                          ScriptScope scope,
                                          String source,
                                          int order) {
        ScriptBlock block = new ScriptBlock();
        block.id = id;
        block.dialect = dialect;
        block.phase = phase;
        block.scope = scope;
        block.source = source;
        block.order = order;
        block.enabled = true;
        return block;
    }

    public static void waitForRunnerToStop(CollectionRunner runner) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (runner != null && runner.isRunning() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for runner", e);
            }
        }
        assertThat(runner != null && runner.isRunning()).isFalse();
    }

    public static final class RecordingRunnerListener implements CollectionRunner.RunnerListener {
        public final List<String> started = new CopyOnWriteArrayList<>();
        public final List<String> skipped = new CopyOnWriteArrayList<>();
        public final List<RunnerResult> requestResults = new CopyOnWriteArrayList<>();
        public final List<RunnerResult> attemptResults = new CopyOnWriteArrayList<>();
        public final List<RunnerTimelineRow> timelineRows = new CopyOnWriteArrayList<>();
        public final List<String> errors = new CopyOnWriteArrayList<>();
        public final List<List<RunnerResult>> completedRuns = new CopyOnWriteArrayList<>();

        @Override
        public void onStart(String collectionName, int totalRequests) {
            started.add(Objects.toString(collectionName, "") + ":" + totalRequests);
        }

        @Override
        public void onSkip(String requestName, String reason) {
            skipped.add(requestName + ":" + reason);
        }

        @Override
        public void onRequestComplete(RunnerResult result) {
            requestResults.add(result);
        }

        @Override
        public void onAttemptComplete(RunnerResult result) {
            attemptResults.add(result);
        }

        @Override
        public void onTimeline(RunnerTimelineRow row) {
            timelineRows.add(row);
        }

        @Override
        public void onError(String message) {
            errors.add(message);
        }

        @Override
        public void onComplete(List<RunnerResult> results) {
            completedRuns.add(results);
        }
    }

    public static ScriptDialect postman() {
        return ScriptDialect.POSTMAN;
    }

    public static ScriptDialect bruno() {
        return ScriptDialect.BRUNO;
    }

    public static ScriptDialect insomnia() {
        return ScriptDialect.INSOMNIA;
    }

    public static ScriptDialect nativeDialect() {
        return ScriptDialect.API_WORKBENCH;
    }

    public static ExecutionSource runnerSource() {
        return ExecutionSource.RUNNER;
    }
}
