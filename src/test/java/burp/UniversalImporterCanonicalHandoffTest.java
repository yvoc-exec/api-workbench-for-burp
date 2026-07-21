package burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.scripts.ExecutionSource;
import burp.utils.ExecutionResult;
import burp.utils.RequestBuilder;
import burp.utils.ScriptEngine;
import burp.utils.ScriptMode;
import burp.utils.SharedRequestPipeline;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UniversalImporterCanonicalHandoffTest {
    @Test
    void repeaterReceivesPipelineBuiltRequestWithoutReconstruction() throws Exception {
        Fixture fixture = fixture();
        try {
            run(fixture, "Repeater");
            verify(fixture.api.repeater()).sendToRepeater(
                    org.mockito.ArgumentMatchers.same(fixture.builtRequest), any(String.class));
            assertThat(fixture.pipeline.buildCalls).isEqualTo(1);
            assertThat(fixture.pipeline.executeCalls).isZero();
            assertThat(fixture.pipeline.captured).containsExactly(fixture.request);
        } finally {
            fixture.importer.cleanup();
        }
    }

    @Test
    void intruderReceivesPipelineBuiltRequestWithoutReconstruction() throws Exception {
        Fixture fixture = fixture();
        try {
            run(fixture, "Intruder");
            verify(fixture.api.intruder()).sendToIntruder(
                    org.mockito.ArgumentMatchers.same(fixture.builtRequest));
            assertThat(fixture.pipeline.buildCalls).isEqualTo(1);
            assertThat(fixture.pipeline.executeCalls).isZero();
            assertThat(fixture.pipeline.captured).containsExactly(fixture.request);
        } finally {
            fixture.importer.cleanup();
        }
    }

    @Test
    void sitemapUsesLivePipelineExecutionResult() throws Exception {
        Fixture fixture = fixture();
        try {
            run(fixture, "Sitemap");
            assertThat(fixture.pipeline.executeCalls).isEqualTo(1);
            assertThat(fixture.pipeline.buildCalls).isZero();
            assertThat(fixture.pipeline.captured).containsExactly(fixture.request);
            verify(fixture.api.http(), never()).sendRequest(any(HttpRequest.class));
            verify(fixture.api.siteMap()).add(
                    org.mockito.ArgumentMatchers.same(fixture.annotatedResponse));
        } finally {
            fixture.pipeline.closeAnnotationsMock();
            fixture.importer.cleanup();
        }
    }

    @Test
    void handoffsPreserveCanonicalParameterAndBodyModel() throws Exception {
        Fixture fixture = fixture();
        try {
            ApiRequest expected = fixture.request.applyTo(new ApiRequest());
            run(fixture, "Repeater");
            ApiRequest captured = fixture.pipeline.captured.get(0);
            assertThat(captured).isSameAs(fixture.request);
            assertThat(captured.parameters).usingRecursiveFieldByFieldElementComparator()
                    .containsExactlyElementsOf(expected.parameters);
            assertThat(captured.body).usingRecursiveComparison().isEqualTo(expected.body);
            assertThat(captured.parameters).extracting(parameter -> parameter.location)
                    .containsExactly("path", "query", "header", "cookie");
        } finally {
            fixture.importer.cleanup();
        }
    }

    private static void run(Fixture fixture, String destination) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        fixture.importer.importRequestsSequential(
                List.of(new UniversalImporter.QueuedRequest(fixture.collection, fixture.request)),
                List.of(destination),
                0,
                message -> { },
                result -> done.countDown());
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static Fixture fixture() {
        MontoyaApi api = mockApi();
        HttpRequest builtRequest = mock(HttpRequest.class);
        HttpRequestResponse response = mock(HttpRequestResponse.class);
        HttpRequestResponse annotated = mock(HttpRequestResponse.class);
        when(response.response()).thenReturn(mock(burp.api.montoya.http.message.responses.HttpResponse.class));
        when(response.withAnnotations(any(Annotations.class))).thenReturn(annotated);
        ApiRequest request = request();
        ApiCollection collection = new ApiCollection();
        collection.name = "Canonical";
        collection.requests.add(request);
        CapturingPipeline pipeline = new CapturingPipeline(request, builtRequest, response);
        TestImporter.NEXT_PIPELINE.set(pipeline);
        TestImporter importer;
        try {
            importer = new TestImporter(api);
        } finally {
            TestImporter.NEXT_PIPELINE.remove();
        }
        return new Fixture(api, importer, pipeline, collection, request,
                builtRequest, response, annotated);
    }

    private static MontoyaApi mockApi() {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(api.userInterface().createHttpRequestEditor(any(EditorOptions.class))).thenAnswer(invocation -> {
            HttpRequestEditor editor = mock(HttpRequestEditor.class);
            when(editor.uiComponent()).thenReturn(new JPanel());
            return editor;
        });
        when(api.userInterface().createHttpResponseEditor(any(EditorOptions.class))).thenAnswer(invocation -> {
            HttpResponseEditor editor = mock(HttpResponseEditor.class);
            when(editor.uiComponent()).thenReturn(new JPanel());
            return editor;
        });
        return api;
    }

    private static ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.id = "handoff";
        request.name = "Canonical Handoff";
        request.sourceCollection = "Canonical";
        request.method = "POST";
        request.url = "https://example.test/items/{id}";
        request.parameters = new ArrayList<>(List.of(
                parameter("path", "id", "42"),
                parameter("query", "q", "one"),
                parameter("header", "X-Wave", "header"),
                parameter("cookie", "session", "cookie")));
        request.body = new ApiRequest.Body();
        request.body.mode = "urlencoded";
        request.body.required = true;
        request.body.description = "body";
        request.body.sourceMetadata.put("body.metadata", "retained");
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField("field", "value");
        field.required = true;
        field.description = "field";
        field.contentType = "text/plain";
        field.style = "form";
        field.explode = Boolean.FALSE;
        field.allowReserved = true;
        field.sourceMetadata.put("field.metadata", "retained");
        request.body.urlencoded.add(field);
        return request;
    }

    private static ApiRequest.Parameter parameter(String location, String key, String value) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter(location, key, value);
        parameter.required = true;
        parameter.type = "string";
        parameter.format = "wave5";
        parameter.style = "form";
        parameter.explode = Boolean.TRUE;
        parameter.source = "handoff:test";
        parameter.sourceMetadata.put("parameter.metadata", "retained");
        return parameter;
    }

    private static final class TestImporter extends UniversalImporter {
        static final ThreadLocal<CapturingPipeline> NEXT_PIPELINE = new ThreadLocal<>();

        TestImporter(MontoyaApi api) {
            super(api, ScriptMode.DISABLED, null);
        }

        @Override
        protected SharedRequestPipeline createSharedRequestPipeline(
                MontoyaApi api, RequestBuilder requestBuilder,
                ScriptEngine scriptEngine, burp.auth.OAuth2Manager oauth2Manager) {
            return NEXT_PIPELINE.get();
        }
    }

    private static final class CapturingPipeline extends SharedRequestPipeline {
        final ApiRequest expected;
        final HttpRequest builtRequest;
        final HttpRequestResponse response;
        final List<ApiRequest> captured = new ArrayList<>();
        int buildCalls;
        int executeCalls;
        volatile org.mockito.MockedStatic<Annotations> annotationsMock;

        CapturingPipeline(ApiRequest expected, HttpRequest builtRequest, HttpRequestResponse response) {
            super(null, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);
            this.expected = expected;
            this.builtRequest = builtRequest;
            this.response = response;
        }

        @Override
        public ExecutionResult build(ApiRequest req, ApiCollection col,
                                     Map<String, String> runtimeOverlay,
                                     OAuth2TokenSink oauth2TokenSink,
                                     RuntimeVariableSink runtimeVariableSink,
                                     EnvironmentProfile activeEnvironment,
                                     ExecutionSource executionSource) {
            buildCalls++;
            captured.add(req);
            return result(false);
        }

        @Override
        public ExecutionResult execute(ApiRequest req, ApiCollection col, boolean followRedirects,
                                       Map<String, String> runtimeOverlay,
                                       OAuth2TokenSink oauth2TokenSink,
                                       RuntimeVariableSink runtimeVariableSink,
                                       EnvironmentProfile activeEnvironment,
                                       ExecutionSource executionSource) {
            executeCalls++;
            captured.add(req);
            Annotations annotation = mock(Annotations.class);
            annotationsMock = mockStatic(Annotations.class);
            annotationsMock.when(() -> Annotations.annotations(
                            "[Imported] " + expected.name,
                            HighlightColor.CYAN))
                    .thenReturn(annotation);
            return result(true);
        }

        void closeAnnotationsMock() {
            org.mockito.MockedStatic<Annotations> current = annotationsMock;
            if (current != null) {
                current.closeOnDemand();
            }
        }

        private ExecutionResult result(boolean live) {
            ExecutionResult result = new ExecutionResult();
            result.success = true;
            result.requestHeaders = "POST /items/42?q=one HTTP/1.1\r\nHost: example.test\r\n\r\n";
            result.rawRequestBytes = result.requestHeaders.getBytes(StandardCharsets.UTF_8);
            result.resolvedUrl = "https://example.test/items/42?q=one";
            result.builtRequest = builtRequest;
            if (live) {
                result.response = response;
            }
            return result;
        }
    }

    private record Fixture(MontoyaApi api,
                           TestImporter importer,
                           CapturingPipeline pipeline,
                           ApiCollection collection,
                           ApiRequest request,
                           HttpRequest builtRequest,
                           HttpRequestResponse response,
                           HttpRequestResponse annotatedResponse) { }
}
