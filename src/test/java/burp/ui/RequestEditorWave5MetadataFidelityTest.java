package burp.ui;

import burp.exporter.ApiWorkbenchCollectionExporter;
import burp.exporter.CollectionExportFormat;
import burp.exporter.CollectionExportOptions;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.ApiWorkbenchCollectionParser;
import burp.testsupport.ImporterPanelTestSupport;
import burp.utils.RequestBuilder;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JTable;
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

class RequestEditorWave5MetadataFidelityTest {
    @TempDir
    Path tempDir;

    @Test
    void noOpWorkbenchRoundTripPreservesEveryParameterField() throws Exception {
        ApiRequest request = request();
        request.parameters.add(parameter("query", "q", "one", true, false, 1));
        request.parameters.add(parameter("path", "id", "42", true, false, 2));
        request.parameters.add(parameter("header", "X-Wave", "header", true, true, 3));
        request.parameters.add(parameter("cookie", "session", "cookie", true, false, 4));

        ApiRequest built = noOp(request);

        assertThat(built.parameters).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(request.parameters);
    }

    @Test
    void selectedParameterDetailsEditCanonicalMetadata() throws Exception {
        ApiRequest request = request();
        ApiRequest.Parameter original = parameter("query", "flag", "retained", true, false, 1);
        request.parameters.add(original);

        ApiRequest built = onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            RequestEditorParameterDetailsPanel details = panel.parameterDetailsPanelForTests();
            details.valueFormBoxForTests().setSelectedItem("Bare key");
            details.requiredBoxForTests().doClick();
            details.typeFieldForTests().setText("integer");
            details.formatFieldForTests().setText("int64");
            details.styleFieldForTests().setText("spaceDelimited");
            details.explodeBoxForTests().setSelectedItem("False");
            details.allowReservedBoxForTests().doClick();
            details.rawKeyFieldForTests().setText("f%6Cag");
            details.rawValueFieldForTests().setText("r%65tained");
            return panel.buildRequestFromUI();
        });

        ApiRequest.Parameter actual = built.parameters.get(0);
        assertThat(actual.valuePresent).isFalse();
        assertThat(actual.required).isEqualTo(!original.required);
        assertThat(actual.type).isEqualTo("integer");
        assertThat(actual.format).isEqualTo("int64");
        assertThat(actual.style).isEqualTo("spaceDelimited");
        assertThat(actual.explode).isFalse();
        assertThat(actual.allowReserved).isEqualTo(!original.allowReserved);
        assertThat(actual.rawKey).isEqualTo("f%6Cag");
        assertThat(actual.rawValue).isEqualTo("r%65tained");
        assertThat(actual.key).isEqualTo(original.key);
        assertThat(actual.value).isEqualTo(original.value);
        assertThat(actual.location).isEqualTo(original.location);
        assertThat(actual.description).isEqualTo(original.description);
        assertThat(actual.source).isEqualTo(original.source);
        assertThat(actual.sourceMetadata).isEqualTo(original.sourceMetadata);
    }

    @Test
    void bareParameterRetainsNonEmptyValueWithoutSerializingEquals() throws Exception {
        ApiRequest request = request();
        request.parameters.add(parameter("query", "flag", "retained-text", false, false, 1));

        ApiRequest built = noOp(request);
        String raw = new String(new RequestBuilder(null).buildRequest(built, null), StandardCharsets.UTF_8);
        ApiRequest restored = nativeRoundTrip(built);

        assertThat(built.parameters.get(0).value).isEqualTo("retained-text");
        assertThat(built.parameters.get(0).valuePresent).isFalse();
        assertThat(raw).contains("?flag HTTP/").doesNotContain("flag=");
        assertThat(restored.parameters.get(0).value).isEqualTo("retained-text");
        assertThat(restored.parameters.get(0).valuePresent).isFalse();
    }

    @Test
    void selectionAndMetadataPreviewDoNotDirtyEditor() throws Exception {
        onEdt(() -> {
            ApiRequest request = request();
            request.parameters.add(parameter("query", "one", "1", true, false, 1));
            request.parameters.add(parameter("header", "X-Two", "2", true, false, 2));
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            panel.markClean();
            JTable table = ImporterPanelTestSupport.getField(panel, "paramsTable");
            table.setRowSelectionInterval(1, 1);
            table.setRowSelectionInterval(0, 0);
            panel.parameterDetailsPanelForTests().refreshSelection();
            assertThat(panel.isDirty()).isFalse();
            panel.parameterDetailsPanelForTests().requiredBoxForTests().doClick();
            assertThat(panel.isDirty()).isTrue();
            return null;
        });
    }

    @Test
    void metadataSummaryShowsKeysButNeverValues() throws Exception {
        onEdt(() -> {
            ApiRequest request = request();
            ApiRequest.Parameter parameter = parameter("query", "q", "1", true, false, 1);
            parameter.sourceMetadata.clear();
            parameter.sourceMetadata.put("openapi.schema", "CANARY-SECRET-SCHEMA");
            parameter.sourceMetadata.put("vendor.raw", "CANARY-SECRET-VALUE");
            request.parameters.add(parameter);
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            String summary = panel.parameterDetailsPanelForTests().metadataSummaryAreaForTests().getText();
            assertThat(summary).contains("openapi.schema", "vendor.raw", "Values hidden")
                    .doesNotContain("CANARY", "SECRET-SCHEMA", "SECRET-VALUE", "{", "\r", "\n");
            return null;
        });
    }

    @Test
    void starterRowDetailsAreDisabledAndDoNotCreateParameter() throws Exception {
        ApiRequest built = onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request());
            JTable table = ImporterPanelTestSupport.getField(panel, "paramsTable");
            table.setRowSelectionInterval(0, 0);
            RequestEditorParameterDetailsPanel details = panel.parameterDetailsPanelForTests();
            details.refreshSelection();
            assertThat(details.requiredBoxForTests().isEnabled()).isFalse();
            return panel.buildRequestFromUI();
        });
        assertThat(built.parameters).isEmpty();
    }

    private ApiRequest nativeRoundTrip(ApiRequest request) throws Exception {
        ApiCollection collection = new ApiCollection();
        collection.name = "Wave5";
        collection.requests.add(request);
        Path file = tempDir.resolve("native.json");
        Files.writeString(file, new GsonBuilder().create().toJson(
                ApiWorkbenchCollectionExporter.build(collection,
                        new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON,
                                null, false, null, Map.of()), new ArrayList<>())));
        return new ApiWorkbenchCollectionParser().parse(file.toFile()).requests.get(0);
    }

    private static ApiRequest noOp(ApiRequest request) throws Exception {
        return onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            return panel.buildRequestFromUI();
        });
    }

    private static ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.name = "Wave5";
        request.method = "GET";
        request.url = "https://example.test/items";
        return request;
    }

    private static ApiRequest.Parameter parameter(String location, String key, String value,
                                                  boolean valuePresent, boolean disabled, int index) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter(location, key, value);
        parameter.rawKey = "raw-key-" + index;
        parameter.rawValue = "raw-value-" + index;
        parameter.valuePresent = valuePresent;
        parameter.disabled = disabled;
        parameter.required = index % 2 == 0;
        parameter.type = "type-" + index;
        parameter.format = "format-" + index;
        parameter.description = "description-" + index;
        parameter.style = "style-" + index;
        parameter.explode = index % 2 == 0 ? Boolean.TRUE : Boolean.FALSE;
        parameter.allowReserved = index % 2 != 0;
        parameter.source = "source-" + index;
        parameter.sourceMetadata.put("metadata.key." + index, "secret-value-" + index);
        return parameter;
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
