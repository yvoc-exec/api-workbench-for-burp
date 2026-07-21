package burp.runner;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.RunnerResult;
import burp.testsupport.RunnerScriptTestFixtures;
import burp.utils.ExecutionPolicy;
import burp.utils.ExecutionResult;
import burp.utils.PreflightDecisionHandler;
import burp.utils.RequestBuilder;
import burp.utils.ScriptEngine;
import burp.utils.ScriptMode;
import burp.utils.SharedRequestPipeline;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionRunnerCanonicalModelTest {
    @Test
    void runnerPassesCanonicalAuthoredModelToPipelineAndRetriesWithoutFlattening() throws Exception {
        ApiRequest request = request();
        byte[] baseline = new RequestBuilder(null).buildRequest(request, null);
        CapturingPipeline pipeline = new CapturingPipeline(baseline);
        CollectionRunner runner = new CollectionRunner(null, pipeline, null);
        runner.setDelayMs(0);
        RunnerRetryPolicy retry = RunnerRetryPolicy.safeDefaults();
        retry.maxRetries = 1;
        retry.retryConnectionFailures = true;
        retry.normalize();
        runner.setRetryPolicy(retry);
        ApiCollection collection = new ApiCollection();
        collection.name = "Canonical";
        collection.requests = new ArrayList<>(List.of(request));

        runner.runCollections(List.of(collection), collection.requests);
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(pipeline.captured).hasSize(2).allMatch(captured -> captured == request);
        for (ApiRequest captured : pipeline.captured) {
            assertThat(captured.parameters).usingRecursiveFieldByFieldElementComparator()
                    .containsExactlyElementsOf(request.parameters);
            assertThat(captured.body).usingRecursiveComparison().isEqualTo(request.body);
            assertThat(captured.url).isEqualTo("https://example.test/items/{id}");
        }
        assertThat(pipeline.finalBytes).containsExactly(baseline);
        String raw = new String(baseline, StandardCharsets.UTF_8);
        assertThat(raw).contains("/items/42?q=one&flag HTTP/")
                .contains("X-Meta: one")
                .contains("Cookie: session=cookie")
                .doesNotContain("disabled-query", "disabled-field");
        assertThat(runner.getResults()).hasSize(1);
        RunnerResult result = runner.getResults().get(0);
        assertThat(result.rawRequestBytes).containsExactly(baseline);
        assertThat(result.totalAttempts).isEqualTo(2);
    }

    private static ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.id = "canonical";
        request.name = "Canonical";
        request.method = "GET";
        request.url = "https://example.test/items/{id}";
        request.parameters.add(parameter("path", "id", "42", false, true));
        request.parameters.add(parameter("query", "q", "one", false, true));
        request.parameters.add(parameter("query", "flag", "retained", false, false));
        request.parameters.add(parameter("query", "disabled-query", "no", true, true));
        request.parameters.add(parameter("header", "X-Meta", "one", false, true));
        request.parameters.add(parameter("cookie", "session", "cookie", false, true));
        request.body = new ApiRequest.Body();
        request.body.mode = "urlencoded";
        request.body.required = true;
        request.body.description = "body";
        request.body.sourceMetadata.put("body.metadata", "retained");
        request.body.urlencoded.add(field("item", "one", false));
        request.body.urlencoded.add(field("disabled-field", "no", true));
        return request;
    }

    private static ApiRequest.Parameter parameter(String location, String key, String value,
                                                  boolean disabled, boolean valuePresent) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter(location, key, value);
        parameter.valuePresent = valuePresent;
        parameter.disabled = disabled;
        parameter.required = true;
        parameter.type = "string";
        parameter.format = "wave5";
        parameter.style = "form";
        parameter.explode = Boolean.TRUE;
        parameter.allowReserved = true;
        parameter.source = "runner:test";
        parameter.sourceMetadata.put("parameter.metadata", "retained");
        return parameter;
    }

    private static ApiRequest.Body.FormField field(String key, String value, boolean disabled) {
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(key, value);
        field.disabled = disabled;
        field.required = true;
        field.description = "field";
        field.contentType = "text/plain";
        field.style = "form";
        field.explode = Boolean.FALSE;
        field.allowReserved = true;
        field.source = "runner:test";
        field.sourceMetadata.put("field.metadata", "retained");
        return field;
    }

    private static final class CapturingPipeline extends SharedRequestPipeline {
        final List<ApiRequest> captured = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final byte[] finalBytes;

        CapturingPipeline(byte[] finalBytes) {
            super(null, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);
            this.finalBytes = finalBytes;
        }

        @Override
        public ExecutionResult execute(ApiRequest req,
                                       ApiCollection col,
                                       boolean followRedirects,
                                       Map<String, String> runtimeOverlay,
                                       OAuth2TokenSink oauth2TokenSink,
                                       RuntimeVariableSink runtimeVariableSink,
                                       burp.models.EnvironmentProfile activeEnvironment,
                                       burp.scripts.ExecutionSource executionSource,
                                       burp.scripts.ScriptDependentRequestExecutor dependentRequestExecutor,
                                       burp.models.RedirectPolicy redirectPolicy,
                                       ExecutionPolicy executionPolicy,
                                       PreflightDecisionHandler preflightDecisionHandler,
                                       BooleanSupplier cancellationRequested) {
            captured.add(req);
            int call = calls.incrementAndGet();
            ExecutionResult result = new ExecutionResult();
            result.rawRequestBytes = finalBytes.clone();
            result.requestHeaders = new String(finalBytes, StandardCharsets.UTF_8);
            result.resolvedUrl = req.url;
            result.elapsedMs = 1;
            result.requestSent = true;
            if (call == 1) {
                result.success = false;
                result.errorMessage = "connection failed";
                return result;
            }
            result.success = true;
            result.response = RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain");
            return result;
        }
    }
}
