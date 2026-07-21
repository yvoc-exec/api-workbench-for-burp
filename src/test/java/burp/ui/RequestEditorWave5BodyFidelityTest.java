package burp.ui;

import burp.models.ApiRequest;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;

class RequestEditorWave5BodyFidelityTest {
    @Test
    void noOpWorkbenchRoundTripPreservesBodyFieldMetadata() throws Exception {
        ApiRequest request = requestWithThreeStates();

        ApiRequest built = noOp(request);

        assertThat(built.body).usingRecursiveComparison().isEqualTo(request.body);
    }

    @Test
    void valueKindClearlyDistinguishesThreeFieldStates() throws Exception {
        onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(requestWithThreeStates());
            JTable table = ImporterPanelTestSupport.getField(panel, "bodyFormTable");
            RequestEditorBodyFieldDetailsPanel details = panel.bodyFieldDetailsPanelForTests();
            table.setRowSelectionInterval(0, 0);
            assertThat(details.valueKindFieldForTests().getText())
                    .isEqualTo("Text field — Value is serialized as text.");
            table.setRowSelectionInterval(1, 1);
            assertThat(details.valueKindFieldForTests().getText())
                    .isEqualTo("Local file path — request bytes are read from this machine.");
            table.setRowSelectionInterval(2, 2);
            assertThat(details.valueKindFieldForTests().getText())
                    .isEqualTo("Retained file metadata — no local file path. Value remains imported source text.");
            return null;
        });
    }

    @Test
    void retainedFileMetadataDoesNotBecomeLocalPath() throws Exception {
        ApiRequest.Body.FormField retained = noOp(requestWithThreeStates()).body.formdata.get(2);
        assertThat(retained.filePath).isNull();
        assertThat(retained.fileUpload).isTrue();
        assertThat(retained.type).isEqualTo("file");
        assertThat(retained.value).isEqualTo("retained inline text");
    }

    @Test
    void bodyFieldDetailsEditSupportedMetadata() throws Exception {
        ApiRequest request = requestWithThreeStates();
        ApiRequest.Body.FormField original = request.body.formdata.get(0);
        ApiRequest built = onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            RequestEditorBodyFieldDetailsPanel details = panel.bodyFieldDetailsPanelForTests();
            details.requiredBoxForTests().doClick();
            details.descriptionFieldForTests().setText("edited description");
            details.contentTypeFieldForTests().setText("application/edited");
            details.styleFieldForTests().setText("deepObject");
            details.explodeBoxForTests().setSelectedItem("False");
            details.allowReservedBoxForTests().doClick();
            return panel.buildRequestFromUI();
        });
        ApiRequest.Body.FormField actual = built.body.formdata.get(0);
        assertThat(actual.required).isEqualTo(!original.required);
        assertThat(actual.description).isEqualTo("edited description");
        assertThat(actual.contentType).isEqualTo("application/edited");
        assertThat(actual.style).isEqualTo("deepObject");
        assertThat(actual.explode).isFalse();
        assertThat(actual.allowReserved).isEqualTo(!original.allowReserved);
        assertThat(actual.source).isEqualTo(original.source);
        assertThat(actual.sourceMetadata).isEqualTo(original.sourceMetadata);
    }

    @Test
    void bodyMetadataSummaryNeverShowsValues() throws Exception {
        onEdt(() -> {
            ApiRequest request = requestWithThreeStates();
            request.body.formdata.get(0).sourceMetadata.clear();
            request.body.formdata.get(0).sourceMetadata.put("openapi.schema", "CANARY-SECRET-SCHEMA");
            request.body.formdata.get(0).sourceMetadata.put("vendor.raw", "CANARY-SECRET-VALUE");
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            String summary = panel.bodyFieldDetailsPanelForTests().metadataSummaryAreaForTests().getText();
            assertThat(summary).contains("openapi.schema", "vendor.raw", "Values hidden")
                    .doesNotContain("CANARY", "SECRET-SCHEMA", "SECRET-VALUE", "{", "\r", "\n");
            return null;
        });
    }

    @Test
    void passiveBodySelectionDoesNotDirtyEditor() throws Exception {
        onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(requestWithThreeStates());
            panel.markClean();
            JTable table = ImporterPanelTestSupport.getField(panel, "bodyFormTable");
            table.setRowSelectionInterval(1, 1);
            table.setRowSelectionInterval(2, 2);
            panel.bodyFieldDetailsPanelForTests().refreshSelection();
            assertThat(panel.isDirty()).isFalse();
            panel.bodyFieldDetailsPanelForTests().requiredBoxForTests().doClick();
            assertThat(panel.isDirty()).isTrue();
            return null;
        });
    }

    private static ApiRequest noOp(ApiRequest request) throws Exception {
        return onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            return panel.buildRequestFromUI();
        });
    }

    private static ApiRequest requestWithThreeStates() {
        ApiRequest request = new ApiRequest();
        request.name = "Body";
        request.method = "POST";
        request.url = "https://example.test/upload";
        request.body = new ApiRequest.Body();
        request.body.mode = "formdata";
        request.body.required = true;
        request.body.description = "body description";
        request.body.source = "test:body";
        request.body.sourceMetadata.put("body.metadata", "hidden");
        request.body.formdata.add(field("text", "ordinary", "text", false, null, 1));
        request.body.formdata.add(field("local", "ignored", "file", true,
                "C:\\tmp\\payload.bin", 2));
        ApiRequest.Body.FormField retained = field("retained", "retained inline text", "file", true, null, 3);
        retained.sourceMetadata.put("har.postData.param.original", "{\"fileName\":\"remote.bin\"}");
        request.body.formdata.add(retained);
        return request;
    }

    private static ApiRequest.Body.FormField field(String key, String value, String type,
                                                   boolean fileUpload, String filePath, int index) {
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(key, value);
        field.type = type;
        field.fileUpload = fileUpload;
        field.filePath = filePath;
        field.disabled = index == 2;
        field.required = index == 1;
        field.description = "description-" + index;
        field.contentType = "content/type-" + index;
        field.style = "style-" + index;
        field.explode = index == 2 ? null : index == 1;
        field.allowReserved = index == 3;
        field.source = "source-" + index;
        field.sourceMetadata.put("metadata.key." + index, "secret-value-" + index);
        return field;
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
