package burp.ui;

import burp.models.ApiRequest;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;

class RequestEditorParameterFidelityTest {
    @Test
    void loadingAndRebuildingPreservesQueryMetadataAndOrder() throws Exception {
        ApiRequest request = request();
        ApiRequest.Parameter active = parameter("tag", "one", false);
        active.rawKey = "t%61g";
        active.rawValue = "%6Fne";
        active.valuePresent = true;
        active.description = "active";
        active.required = true;
        active.type = "string";
        active.source = "postman:url.query";
        active.style = "form";
        active.explode = Boolean.FALSE;
        active.allowReserved = true;
        ApiRequest.Parameter disabled = parameter("skip", "x", true);
        disabled.rawKey = "skip";
        disabled.rawValue = "x";
        disabled.description = "disabled";
        disabled.type = "text";
        disabled.source = "postman:url.query";
        request.parameters.add(active);
        request.parameters.add(disabled);
        request.url = "https://example.test/a?t%61g=%6Fne";

        ApiRequest built = editWithoutChanges(request);

        assertThat(built.parameters).hasSize(2);
        assertThat(built.parameters.get(0)).usingRecursiveComparison().isEqualTo(active);
        assertThat(built.parameters.get(1)).usingRecursiveComparison().isEqualTo(disabled);
    }

    @Test
    void rebuiltUrlContainsOnlyActiveQueryParameters() throws Exception {
        ApiRequest request = request();
        ApiRequest.Parameter active = parameter("keep", "one", false);
        ApiRequest.Parameter disabled = parameter("skip", "two", true);
        request.parameters.add(active);
        request.parameters.add(disabled);
        request.url = "https://example.test/a?keep=one";

        assertThat(editWithoutChanges(request).url).isEqualTo("https://example.test/a?keep=one");
    }

    @Test
    void disabledUrlEncodedFieldsRemainPresentAndDisabled() throws Exception {
        ApiRequest request = request();
        request.body = new ApiRequest.Body();
        request.body.mode = "urlencoded";
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField("off", "2");
        field.disabled = true;
        field.type = "number";
        field.filePath = "metadata/path";
        request.body.urlencoded.add(field);

        ApiRequest.Body.FormField built = editWithoutChanges(request).body.urlencoded.get(0);
        assertThat(built.disabled).isTrue();
        assertThat(built.type).isEqualTo("number");
        assertThat(built.filePath).isEqualTo("metadata/path");
    }

    @Test
    void disabledMultipartFilesRetainAllFileMetadata() throws Exception {
        ApiRequest request = request();
        request.body = new ApiRequest.Body();
        request.body.mode = "formdata";
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField("upload", "");
        field.disabled = true;
        field.type = "file";
        field.fileUpload = true;
        field.filePath = "/tmp/file.bin";
        request.body.formdata.add(field);

        ApiRequest.Body.FormField built = editWithoutChanges(request).body.formdata.get(0);
        assertThat(built.disabled).isTrue();
        assertThat(built.type).isEqualTo("file");
        assertThat(built.fileUpload).isTrue();
        assertThat(built.filePath).isEqualTo("/tmp/file.bin");
    }

    @Test
    void existingNonQueryParametersRemainAfterRebuild() throws Exception {
        ApiRequest request = request();
        ApiRequest.Parameter path = new ApiRequest.Parameter("path", "id", "42");
        path.description = "future metadata";
        request.parameters.add(path);
        request.parameters.add(parameter("q", "one", false));

        ApiRequest built = editWithoutChanges(request);
        assertThat(built.parameters).extracting(p -> p.location).containsExactly("path", "query");
        assertThat(built.parameters.get(0)).isNotSameAs(path).usingRecursiveComparison().isEqualTo(path);
    }

    @Test
    void deletingQueryRowDoesNotTransferSerializationMetadata() throws Exception {
        ApiRequest request = requestWithDistinctParameterMetadata();

        ApiRequest built = onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            paramsModel(panel).removeRow(0);
            return panel.buildRequestFromUI();
        });

        assertThat(built.parameters).hasSize(1);
        assertParameterMetadata(built.parameters.get(0), "second", "spaceDelimited", null,
                false, "second-source", "s%65cond", "t%77o", "second description", false, "number");
    }

    @Test
    void reorderingQueryRowsMovesAllHiddenMetadataWithRows() throws Exception {
        ApiRequest request = requestWithDistinctParameterMetadata();

        ApiRequest built = onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            paramsModel(panel).moveRow(1, 1, 0);
            return panel.buildRequestFromUI();
        });

        assertThat(built.parameters).extracting(p -> p.key).containsExactly("second", "first");
        assertParameterMetadata(built.parameters.get(0), "second", "spaceDelimited", null,
                false, "second-source", "s%65cond", "t%77o", "second description", false, "number");
        assertParameterMetadata(built.parameters.get(1), "first", "form", Boolean.TRUE,
                true, "first-source", "f%69rst", "%6Fne", "first description", true, "string");
    }

    @Test
    void insertingQueryRowDoesNotShiftExistingMetadata() throws Exception {
        ApiRequest request = requestWithDistinctParameterMetadata();

        ApiRequest built = onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            paramsModel(panel).insertRow(0, new Object[]{
                    "new", "value", Boolean.TRUE, "", null, null, Boolean.TRUE,
                    Boolean.FALSE, null, "workbench", null, null, Boolean.FALSE
            });
            return panel.buildRequestFromUI();
        });

        assertThat(built.parameters).extracting(p -> p.key).containsExactly("new", "first", "second");
        ApiRequest.Parameter inserted = built.parameters.get(0);
        assertThat(inserted.style).isNull();
        assertThat(inserted.explode).isNull();
        assertThat(inserted.allowReserved).isFalse();
        assertThat(inserted.source).isEqualTo("workbench");
        assertParameterMetadata(built.parameters.get(1), "first", "form", Boolean.TRUE,
                true, "first-source", "f%69rst", "%6Fne", "first description", true, "string");
        assertParameterMetadata(built.parameters.get(2), "second", "spaceDelimited", null,
                false, "second-source", "s%65cond", "t%77o", "second description", false, "number");
    }

    @Test
    void reorderingUrlEncodedBodyRowsPreservesFileUploadMetadataByRow() throws Exception {
        ApiRequest request = requestWithUrlEncodedFileMetadata();

        ApiRequest built = onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            bodyFormModel(panel).moveRow(1, 1, 0);
            return panel.buildRequestFromUI();
        });

        assertThat(built.body.urlencoded).extracting(f -> f.key).containsExactly("plain", "upload-meta");
        assertThat(built.body.urlencoded).extracting(f -> f.fileUpload).containsExactly(false, true);
    }

    @Test
    void deletingUrlEncodedBodyRowDoesNotTransferFileUploadMetadata() throws Exception {
        ApiRequest request = requestWithUrlEncodedFileMetadata();

        ApiRequest built = onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            bodyFormModel(panel).removeRow(0);
            return panel.buildRequestFromUI();
        });

        assertThat(built.body.urlencoded).hasSize(1);
        assertThat(built.body.urlencoded.get(0).key).isEqualTo("plain");
        assertThat(built.body.urlencoded.get(0).fileUpload).isFalse();
    }

    private static ApiRequest editWithoutChanges(ApiRequest request) throws Exception {
        return onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            return panel.buildRequestFromUI();
        });
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.name = "R";
        request.method = "GET";
        request.url = "https://example.test/a";
        return request;
    }

    private static ApiRequest requestWithDistinctParameterMetadata() {
        ApiRequest request = request();
        request.parameters.add(parameterWithMetadata("first", "one", "form", Boolean.TRUE, true,
                "first-source", "f%69rst", "%6Fne", "first description", true, "string"));
        request.parameters.add(parameterWithMetadata("second", "two", "spaceDelimited", null, false,
                "second-source", "s%65cond", "t%77o", "second description", false, "number"));
        request.url = "https://example.test/a?f%69rst=%6Fne&s%65cond=t%77o";
        return request;
    }

    private static ApiRequest.Parameter parameterWithMetadata(String key,
                                                              String value,
                                                              String style,
                                                              Boolean explode,
                                                              boolean allowReserved,
                                                              String source,
                                                              String rawKey,
                                                              String rawValue,
                                                              String description,
                                                              boolean required,
                                                              String type) {
        ApiRequest.Parameter parameter = parameter(key, value, false);
        parameter.style = style;
        parameter.explode = explode;
        parameter.allowReserved = allowReserved;
        parameter.source = source;
        parameter.rawKey = rawKey;
        parameter.rawValue = rawValue;
        parameter.description = description;
        parameter.required = required;
        parameter.type = type;
        return parameter;
    }

    private static ApiRequest requestWithUrlEncodedFileMetadata() {
        ApiRequest request = request();
        request.body = new ApiRequest.Body();
        request.body.mode = "urlencoded";
        ApiRequest.Body.FormField upload = new ApiRequest.Body.FormField("upload-meta", "one");
        upload.type = "text";
        upload.fileUpload = true;
        ApiRequest.Body.FormField plain = new ApiRequest.Body.FormField("plain", "two");
        plain.type = "text";
        plain.fileUpload = false;
        request.body.urlencoded.add(upload);
        request.body.urlencoded.add(plain);
        return request;
    }

    private static void assertParameterMetadata(ApiRequest.Parameter parameter,
                                                String key,
                                                String style,
                                                Boolean explode,
                                                boolean allowReserved,
                                                String source,
                                                String rawKey,
                                                String rawValue,
                                                String description,
                                                boolean required,
                                                String type) {
        assertThat(parameter.key).isEqualTo(key);
        assertThat(parameter.style).isEqualTo(style);
        assertThat(parameter.explode).isEqualTo(explode);
        assertThat(parameter.allowReserved).isEqualTo(allowReserved);
        assertThat(parameter.source).isEqualTo(source);
        assertThat(parameter.rawKey).isEqualTo(rawKey);
        assertThat(parameter.rawValue).isEqualTo(rawValue);
        assertThat(parameter.description).isEqualTo(description);
        assertThat(parameter.required).isEqualTo(required);
        assertThat(parameter.type).isEqualTo(type);
    }

    private static DefaultTableModel paramsModel(RequestEditorPanel panel) {
        return ImporterPanelTestSupport.getField(panel, "paramsModel");
    }

    private static DefaultTableModel bodyFormModel(RequestEditorPanel panel) {
        return ImporterPanelTestSupport.getField(panel, "bodyFormModel");
    }

    private static ApiRequest.Parameter parameter(String key, String value, boolean disabled) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter("query", key, value);
        parameter.valuePresent = true;
        parameter.disabled = disabled;
        return parameter;
    }
}
