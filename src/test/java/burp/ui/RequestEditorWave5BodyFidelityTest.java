package burp.ui;

import burp.models.ApiRequest;
import burp.testsupport.ImporterPanelTestSupport;
import burp.utils.RequestBuilder;
import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.nio.charset.StandardCharsets;
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

    @Test
    void clearingExistingLocalPathUsesRetainedTextState() throws Exception {
        ApiRequest request = requestWithField("formdata",
                field("upload", "retained fallback text", "file", true,
                        "C:\\tmp\\payload.bin", 1));

        ApiRequest built = onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            JTable table = bodyTable(panel);
            RequestEditorBodyFieldDetailsPanel details = panel.bodyFieldDetailsPanelForTests();
            table.setRowSelectionInterval(0, 0);
            assertThat(details.valueKindFieldForTests().getText())
                    .isEqualTo("Local file path — request bytes are read from this machine.");
            table.getModel().setValueAt("", 0,
                    RequestEditorStateMapper.BODY_FILE_PATH_MODEL_COLUMN);
            details.refreshSelection();
            assertThat(details.valueKindFieldForTests().getText())
                    .isEqualTo("Retained file metadata — no local file path. Value remains imported source text.");
            return panel.buildRequestFromUI();
        });

        ApiRequest.Body.FormField rebuilt = built.body.formdata.get(0);
        assertThat(rebuilt.type).isEqualTo("file");
        assertThat(rebuilt.fileUpload).isTrue();
        assertThat(rebuilt.filePath).isNull();
        assertThat(rebuilt.value).isEqualTo("retained fallback text");
        String raw = new String(new RequestBuilder(null).buildRequest(built, null),
                StandardCharsets.UTF_8);
        assertThat(raw).contains("retained fallback text")
                .doesNotContain("C:\\tmp\\payload.bin");
    }

    @Test
    void changingImportedFileRowToTextClearsFileUploadState() throws Exception {
        ApiRequest request = requestWithField("formdata",
                field("upload", "retained inline text", "file", true, null, 1));

        ApiRequest built = onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            JTable table = bodyTable(panel);
            table.setRowSelectionInterval(0, 0);
            table.getModel().setValueAt("text", 0,
                    RequestEditorStateMapper.BODY_TYPE_MODEL_COLUMN);
            RequestEditorBodyFieldDetailsPanel details = panel.bodyFieldDetailsPanelForTests();
            details.refreshSelection();
            assertThat(details.valueKindFieldForTests().getText())
                    .isEqualTo("Text field — Value is serialized as text.");
            return panel.buildRequestFromUI();
        });

        ApiRequest.Body.FormField rebuilt = built.body.formdata.get(0);
        assertThat(rebuilt.type).isEqualTo("text");
        assertThat(rebuilt.fileUpload).isFalse();
        assertThat(rebuilt.filePath).isNull();
        assertThat(rebuilt.value).isEqualTo("retained inline text");
        String raw = new String(new RequestBuilder(null).buildRequest(built, null),
                StandardCharsets.UTF_8);
        assertThat(raw).contains("retained inline text")
                .doesNotContain("filename=");
    }

    @Test
    void changingTextRowToFileWithoutPathCreatesRetainedFileState() throws Exception {
        ApiRequest request = requestWithField("formdata",
                field("upload", "unchanged text", "text", false, null, 1));

        ApiRequest built = onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            JTable table = bodyTable(panel);
            table.setRowSelectionInterval(0, 0);
            table.getModel().setValueAt("file", 0,
                    RequestEditorStateMapper.BODY_TYPE_MODEL_COLUMN);
            RequestEditorBodyFieldDetailsPanel details = panel.bodyFieldDetailsPanelForTests();
            details.refreshSelection();
            assertThat(details.valueKindFieldForTests().getText())
                    .isEqualTo("Retained file metadata — no local file path. Value remains imported source text.");
            return panel.buildRequestFromUI();
        });

        ApiRequest.Body.FormField rebuilt = built.body.formdata.get(0);
        assertThat(rebuilt.type).isEqualTo("file");
        assertThat(rebuilt.fileUpload).isTrue();
        assertThat(rebuilt.filePath).isNull();
        assertThat(rebuilt.value).isEqualTo("unchanged text");
    }

    @Test
    void addingVisibleFilePathCreatesLocalFileState() throws Exception {
        ApiRequest request = requestWithField("formdata",
                field("upload", "retained", "text", false, null, 1));

        ApiRequest built = onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            JTable table = bodyTable(panel);
            table.setRowSelectionInterval(0, 0);
            table.getModel().setValueAt("uploads/payload.bin", 0,
                    RequestEditorStateMapper.BODY_FILE_PATH_MODEL_COLUMN);
            RequestEditorBodyFieldDetailsPanel details = panel.bodyFieldDetailsPanelForTests();
            details.refreshSelection();
            assertThat(details.valueKindFieldForTests().getText())
                    .isEqualTo("Local file path — request bytes are read from this machine.");
            return panel.buildRequestFromUI();
        });

        ApiRequest.Body.FormField rebuilt = built.body.formdata.get(0);
        assertThat(rebuilt.fileUpload).isTrue();
        assertThat(rebuilt.filePath).isEqualTo("uploads/payload.bin");
    }

    @Test
    void unchangedImportedFileRowPreservesOriginalFileState() throws Exception {
        ApiRequest.Body.FormField unusual =
                field("upload", "retained", "custom-file-type", true, null, 1);

        ApiRequest.Body.FormField rebuilt = noOp(requestWithField("formdata", unusual))
                .body.formdata.get(0);

        assertThat(rebuilt.type).isEqualTo("custom-file-type");
        assertThat(rebuilt.fileUpload).isTrue();
        assertThat(rebuilt.filePath).isNull();
        assertThat(rebuilt.value).isEqualTo("retained");
    }

    @Test
    void formDataNoOpPreservesRetainedBodyRaw() throws Exception {
        ApiRequest request = requestWithField("formdata",
                field("field", "value", "text", false, null, 1));
        request.body.raw = "retained-formdata-source";

        ApiRequest built = noOp(request);

        assertThat(built.body.raw).isEqualTo("retained-formdata-source");
        assertThat(built.body.formdata).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(request.body.formdata);
    }

    @Test
    void urlEncodedNoOpPreservesRetainedBodyRaw() throws Exception {
        ApiRequest request = requestWithField("urlencoded",
                field("field", "value", "text", false, null, 1));
        request.body.raw = "retained-urlencoded-source";

        ApiRequest built = noOp(request);

        assertThat(built.body.raw).isEqualTo("retained-urlencoded-source");
        assertThat(built.body.urlencoded).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(request.body.urlencoded);
    }

    @Test
    void urlEncodedRowsNeverEnableNewLocalFileState() throws Exception {
        ApiRequest request = requestWithField("urlencoded",
                field("field", "retained text", "text", false, null, 1));

        ApiRequest built = onEdt(() -> {
            RequestEditorPanel panel = new RequestEditorPanel();
            panel.loadRequest(request);
            DefaultTableModel model = (DefaultTableModel) bodyTable(panel).getModel();
            model.setValueAt("file", 0, RequestEditorStateMapper.BODY_TYPE_MODEL_COLUMN);
            model.setValueAt("uploads/payload.bin", 0,
                    RequestEditorStateMapper.BODY_FILE_PATH_MODEL_COLUMN);
            return panel.buildRequestFromUI();
        });

        ApiRequest.Body.FormField rebuilt = built.body.urlencoded.get(0);
        assertThat(rebuilt.type).isEqualTo("file");
        assertThat(rebuilt.fileUpload).isFalse();
        assertThat(rebuilt.filePath).isNull();
        assertThat(rebuilt.value).isEqualTo("retained text");
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

    private static ApiRequest requestWithField(String mode, ApiRequest.Body.FormField field) {
        ApiRequest request = new ApiRequest();
        request.name = "Body";
        request.method = "POST";
        request.url = "https://example.test/upload";
        request.body = new ApiRequest.Body();
        request.body.mode = mode;
        request.body.contentType = "formdata".equals(mode)
                ? "multipart/form-data"
                : "application/x-www-form-urlencoded";
        if ("formdata".equals(mode)) {
            request.body.formdata.add(field);
        } else {
            request.body.urlencoded.add(field);
        }
        return request;
    }

    private static JTable bodyTable(RequestEditorPanel panel) {
        return ImporterPanelTestSupport.getField(panel, "bodyFormTable");
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
