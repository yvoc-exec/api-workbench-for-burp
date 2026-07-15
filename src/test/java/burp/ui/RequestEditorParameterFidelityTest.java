package burp.ui;

import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
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

    private static ApiRequest.Parameter parameter(String key, String value, boolean disabled) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter("query", key, value);
        parameter.valuePresent = true;
        parameter.disabled = disabled;
        return parameter;
    }
}
