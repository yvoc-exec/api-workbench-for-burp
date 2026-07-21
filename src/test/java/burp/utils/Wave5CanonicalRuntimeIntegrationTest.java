package burp.utils;

import burp.exporter.ApiWorkbenchCollectionExporter;
import burp.exporter.CollectionExportFormat;
import burp.exporter.CollectionExportOptions;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.models.WorkspaceState;
import burp.parser.ApiWorkbenchCollectionParser;
import burp.parser.VariableResolver;
import burp.ui.RequestEditorPanel;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;

class Wave5CanonicalRuntimeIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void canonicalModelProducesIdenticalBytesAcrossWave5Paths() throws Exception {
        ApiCollection collection = new ApiCollection();
        collection.name = "Wave5";
        ApiRequest request = canonicalRequest();
        collection.requests.add(request);
        Map<String, String> values = Map.of("id", "42", "token", "resolved-token");
        RequestBuilder builder = new RequestBuilder(null);
        byte[] baseline = builder.buildRequest(request, resolver(values));

        ApiRequest editorBuilt = onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.setCurrentCollection(collection);
            panel.loadRequest(request);
            return panel.buildRequestFromUI();
        });
        assertEquivalentModel(editorBuilt, request);
        assertThat(editorBuilt.body.raw).isEqualTo("retained-structured-body-source");
        assertThat(builder.buildRequest(editorBuilt, resolver(values))).containsExactly(baseline);

        try (SharedRequestPipeline pipeline = new SharedRequestPipeline(
                null, builder, new ScriptEngine(null, ScriptMode.DISABLED), null)) {
            ExecutionResult preview = pipeline.build(request, collection, values, null);
            assertThat(preview.success).isTrue();
            assertThat(preview.rawRequestBytes).containsExactly(baseline);
        }

        ApiRequest nativeRequest = nativeRoundTrip(collection);
        assertEquivalentModel(nativeRequest, request);
        assertThat(nativeRequest.body.raw).isEqualTo("retained-structured-body-source");
        assertThat(builder.buildRequest(nativeRequest, resolver(values))).containsExactly(baseline);

        ApiRequest workspaceRequest = WorkspaceStateJson.fromJson(
                WorkspaceStateJson.toJson(WorkspaceState.fromCollections(List.of(collection))))
                .collections.get(0).requests.get(0);
        assertEquivalentModel(workspaceRequest, request);
        assertThat(workspaceRequest.body.raw).isEqualTo("retained-structured-body-source");
        assertThat(builder.buildRequest(workspaceRequest, resolver(values))).containsExactly(baseline);

        String text = new String(baseline, StandardCharsets.UTF_8);
        assertThat(text).contains("/items/42?tag=%6Fne&tag=two&flag HTTP/");
        assertThat(text).contains("X-Dupe: one\r\nX-Dupe: two");
        assertThat(text).contains("Cookie: a=1; b=2");
        assertThat(text).doesNotContain("disabled-query", "disabled-body");
    }

    @Test
    void pristineExactSnapshotRemainsByteIdenticalAcrossWave5Paths() throws Exception {
        byte[] raw = "POST /exact HTTP/1.0\r\nHost: alternate.test\r\nX-Dupe: one\r\nX-Dupe: two\r\nContent-Length: 4\r\n\r\nbody"
                .getBytes(StandardCharsets.UTF_8);
        ApiRequest request = new ApiRequest();
        request.name = "Exact";
        request.method = "POST";
        request.url = "https://example.test/exact";
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.editorMaterialized = true;
        request.headers.add(new ApiRequest.Header("Host", "alternate.test", false));
        request.headers.add(new ApiRequest.Header("X-Dupe", "one", false));
        request.headers.add(new ApiRequest.Header("X-Dupe", "two", false));
        request.headers.add(new ApiRequest.Header("Content-Length", "4", false));
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "body";
        request.exactHttpRequest = new ExactHttpRequestSnapshot();
        request.exactHttpRequest.rawRequestBytes = raw.clone();
        request.exactHttpRequest.serviceHost = "example.test";
        request.exactHttpRequest.servicePort = 443;
        request.exactHttpRequest.secure = true;
        request.exactHttpRequest.httpVersion = "HTTP/1.0";
        request.exactHttpRequest.pristine = true;
        request.exactHttpRequest.semanticFingerprint = request.computeSemanticFingerprint();
        ApiCollection collection = new ApiCollection();
        collection.name = "Exact";
        collection.requests.add(request);

        ApiRequest editorBuilt = onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.setCurrentCollection(collection);
            panel.loadRequest(request);
            return panel.buildRequestFromUI();
        });
        ApiRequest nativeRequest = nativeRoundTrip(collection);
        ApiRequest workspaceRequest = WorkspaceStateJson.fromJson(
                WorkspaceStateJson.toJson(WorkspaceState.fromCollections(List.of(collection))))
                .collections.get(0).requests.get(0);

        RequestBuilder builder = new RequestBuilder(null);
        assertThat(builder.buildRequest(editorBuilt, null)).containsExactly(raw);
        assertThat(builder.buildRequest(nativeRequest, null)).containsExactly(raw);
        assertThat(builder.buildRequest(workspaceRequest, null)).containsExactly(raw);
        assertThat(editorBuilt.exactHttpRequest.pristine).isTrue();
        assertThat(nativeRequest.exactHttpRequest.pristine).isTrue();
        assertThat(workspaceRequest.exactHttpRequest.pristine).isTrue();
    }

    private ApiRequest nativeRoundTrip(ApiCollection collection) throws Exception {
        Path file = tempDir.resolve("native-" + System.nanoTime() + ".json");
        Files.writeString(file, new GsonBuilder().create().toJson(
                ApiWorkbenchCollectionExporter.build(collection,
                        new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON,
                                null, false, null, Map.of()), new ArrayList<>())));
        return new ApiWorkbenchCollectionParser().parse(file.toFile()).requests.get(0);
    }

    private static ApiRequest canonicalRequest() {
        ApiRequest request = new ApiRequest();
        request.name = "Canonical";
        request.method = "POST";
        request.url = "https://example.test/items/{id}";
        request.editorMaterialized = true;
        request.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        request.parameters.add(parameter("path", "id", "{{id}}", null, null, true, false));
        request.parameters.add(parameter("query", "tag", "one", "tag", "%6Fne", true, false));
        request.parameters.add(parameter("query", "tag", "two", "tag", "two", true, false));
        request.parameters.add(parameter("query", "flag", "retained", "flag", null, false, false));
        request.parameters.add(parameter("query", "disabled-query", "no", null, null, true, true));
        request.parameters.add(parameter("header", "X-Dupe", "one", null, null, true, false));
        request.parameters.add(parameter("header", "X-Dupe", "two", null, null, true, false));
        request.parameters.add(parameter("cookie", "a", "1", null, null, true, false));
        request.parameters.add(parameter("cookie", "b", "2", null, null, true, false));
        request.suppressedAutoHeaders.add("user-agent");
        request.headers.add(new ApiRequest.Header(
                "Content-Type", "application/x-www-form-urlencoded", false));
        request.headers.add(new ApiRequest.Header(
                "Authorization", "Bearer {{token}}", false));
        request.body = new ApiRequest.Body();
        request.body.mode = "urlencoded";
        request.body.raw = "retained-structured-body-source";
        request.body.required = true;
        request.body.description = "body";
        request.body.source = "test:body";
        request.body.sourceMetadata.put("body.metadata", "hidden");
        request.body.urlencoded.add(field("item", "one", false, 1));
        request.body.urlencoded.add(field("item", "two", false, 2));
        request.body.urlencoded.add(field("disabled-body", "no", true, 3));
        request.authOverrideMode = "explicit";
        request.explicitAuth = new ApiRequest.Auth();
        request.explicitAuth.type = "bearer";
        request.explicitAuth.properties.put("token", "{{token}}");
        request.auth = new ApiRequest.Auth();
        request.auth.type = "bearer";
        request.auth.properties.put("token", "{{token}}");
        return request;
    }

    private static ApiRequest.Parameter parameter(String location, String key, String value,
                                                  String rawKey, String rawValue,
                                                  boolean valuePresent, boolean disabled) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter(location, key, value);
        parameter.rawKey = rawKey;
        parameter.rawValue = rawValue;
        parameter.valuePresent = valuePresent;
        parameter.disabled = disabled;
        parameter.required = true;
        parameter.type = "string";
        parameter.format = "wave5";
        parameter.description = "metadata";
        parameter.style = "form";
        parameter.explode = Boolean.TRUE;
        parameter.allowReserved = false;
        parameter.source = "test:canonical";
        parameter.sourceMetadata.put("parameter.metadata", "hidden");
        return parameter;
    }

    private static ApiRequest.Body.FormField field(String key, String value, boolean disabled, int index) {
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(key, value);
        field.type = "text";
        field.disabled = disabled;
        field.required = true;
        field.description = "field-" + index;
        field.contentType = "text/plain";
        field.style = "form";
        field.explode = Boolean.TRUE;
        field.allowReserved = false;
        field.source = "test:field";
        field.sourceMetadata.put("field.metadata", "hidden");
        return field;
    }

    private static VariableResolver resolver(Map<String, String> values) {
        VariableResolver resolver = new VariableResolver();
        resolver.addAll(values);
        return resolver;
    }

    private static void assertEquivalentModel(ApiRequest actual, ApiRequest expected) {
        assertThat(actual.parameters).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(expected.parameters);
        assertThat(actual.body).usingRecursiveComparison().isEqualTo(expected.body);
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
