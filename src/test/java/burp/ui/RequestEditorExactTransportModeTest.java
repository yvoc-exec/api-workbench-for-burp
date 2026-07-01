package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.utils.RequestBuilder;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RequestEditorExactTransportModeTest {

    @Test
    void toolbarHasNoPermanentExactCheckbox() {
        RequestEditorPanel panel = panel();
        assertThat(findCheckBoxes(panel)).noneMatch(cb -> "Exact HTTP / Preserve authored headers".equals(cb.getText()));
        assertThat(panel.getMethodBox()).isNotNull();
        assertThat(panel.getUrlField()).isNotNull();
        assertThat(panel.getSendButtonForTests().getText()).isEqualTo("Send");
        assertThat(panel.getSendDropdownButtonForTests()).isNotNull();
    }

    @Test
    void sendMenuContainsAdvancedExactItemWithState() {
        RequestEditorPanel panel = panel();
        JCheckBoxMenuItem unloaded = exactItem(panel);
        assertThat(unloaded.isEnabled()).isFalse();

        ApiRequest safe = request(ApiRequest.BuildMode.MANUAL_PRESERVE);
        panel.loadRequest(safe);
        JCheckBoxMenuItem safeItem = exactItem(panel);
        assertThat(safeItem.isEnabled()).isTrue();
        assertThat(safeItem.isSelected()).isFalse();

        ApiRequest exact = request(ApiRequest.BuildMode.EXACT_HTTP);
        panel.loadRequest(exact);
        JCheckBoxMenuItem exactItem = exactItem(panel);
        assertThat(exactItem.isSelected()).isTrue();
    }

    @Test
    void warningAcceptedEnablesExactAndNotifiesPersistenceOnce() {
        RequestEditorPanel panel = panel();
        panel.loadRequest(request(ApiRequest.BuildMode.MANUAL_PRESERVE));
        panel.markClean();
        AtomicInteger warnings = new AtomicInteger();
        AtomicInteger persists = new AtomicInteger();
        panel.setExactTransportWarningProviderForTests((parent, title, message) -> {
            warnings.incrementAndGet();
            assertThat(title).isEqualTo("Exact transport headers");
            assertThat(message).contains("Host, Content-Length, Transfer-Encoding, and Connection");
            return true;
        });
        panel.setRequestBuildModeChangeListener(persists::incrementAndGet);

        exactItem(panel).doClick();

        assertThat(warnings).hasValue(1);
        assertThat(panel.getCurrentRequest().buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(panel.isExactTransportHeadersSelectedForTests()).isTrue();
        assertThat(panel.getExactTransportIndicatorForTests().isVisible()).isTrue();
        assertThat(panel.getExactTransportIndicatorForTests().getText()).isEqualTo("\u26A0 Exact transport headers");
        assertThat(panel.isDirty()).isTrue();
        assertThat(persists).hasValue(1);
    }

    @Test
    void warningCanceledLeavesCleanSafeRequest() {
        RequestEditorPanel panel = panel();
        panel.loadRequest(request(ApiRequest.BuildMode.MANUAL_PRESERVE));
        panel.markClean();
        AtomicInteger warnings = new AtomicInteger();
        AtomicInteger persists = new AtomicInteger();
        panel.setExactTransportWarningProviderForTests((parent, title, message) -> { warnings.incrementAndGet(); return false; });
        panel.setRequestBuildModeChangeListener(persists::incrementAndGet);

        exactItem(panel).doClick();

        assertThat(warnings).hasValue(1);
        assertThat(panel.getCurrentRequest().buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(panel.getExactTransportIndicatorForTests().isVisible()).isFalse();
        assertThat(panel.isDirty()).isFalse();
        assertThat(persists).hasValue(0);
        assertThat(panel.isExactTransportWarningAcknowledgedForTests()).isFalse();
    }

    @Test
    void warningShowsOnlyOncePerEditorSession() {
        RequestEditorPanel panel = panel();
        panel.loadRequest(request(ApiRequest.BuildMode.MANUAL_PRESERVE));
        AtomicInteger warnings = new AtomicInteger();
        panel.setExactTransportWarningProviderForTests((parent, title, message) -> { warnings.incrementAndGet(); return true; });

        exactItem(panel).doClick();
        exactItem(panel).doClick();
        exactItem(panel).doClick();

        assertThat(warnings).hasValue(1);
        assertThat(panel.getCurrentRequest().buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
    }

    @Test
    void loadingAndClearingExactRequestUpdatesIndicatorWithoutWarning() {
        RequestEditorPanel panel = panel();
        AtomicInteger warnings = new AtomicInteger();
        panel.setExactTransportWarningProviderForTests((parent, title, message) -> { warnings.incrementAndGet(); return true; });
        panel.loadRequest(request(ApiRequest.BuildMode.EXACT_HTTP));
        assertThat(warnings).hasValue(0);
        assertThat(panel.isExactTransportHeadersSelectedForTests()).isTrue();
        assertThat(panel.getExactTransportIndicatorForTests().isVisible()).isTrue();
        assertThat(exactItem(panel).isSelected()).isTrue();
        panel.clearRequest();
        assertThat(panel.isExactTransportHeadersSelectedForTests()).isFalse();
        assertThat(panel.getExactTransportIndicatorForTests().isVisible()).isFalse();
    }

    @Test
    void switchingLoadedRequestsUpdatesIndicatorWithoutWarning() {
        RequestEditorPanel panel = panel();
        AtomicInteger warnings = new AtomicInteger();
        panel.setExactTransportWarningProviderForTests((parent, title, message) -> { warnings.incrementAndGet(); return true; });
        panel.loadRequest(request(ApiRequest.BuildMode.EXACT_HTTP));
        assertThat(panel.getExactTransportIndicatorForTests().isVisible()).isTrue();
        panel.loadRequest(request(ApiRequest.BuildMode.MANUAL_PRESERVE));
        assertThat(panel.getExactTransportIndicatorForTests().isVisible()).isFalse();
        panel.loadRequest(request(ApiRequest.BuildMode.EXACT_HTTP));
        assertThat(panel.getExactTransportIndicatorForTests().isVisible()).isTrue();
        assertThat(warnings).hasValue(0);
    }

    @Test
    void togglingModeDoesNotMutateHeaderRows() {
        RequestEditorPanel panel = panel();
        ApiRequest req = request(ApiRequest.BuildMode.EXACT_HTTP);
        req.headers = orderedHeaders();
        panel.loadRequest(req);
        List<String> before = rows(model(panel));

        exactItem(panel).doClick();
        List<String> disabled = rows(model(panel));
        exactItem(panel).doClick();
        List<String> reenabled = rows(model(panel));

        assertThat(disabled).containsExactlyElementsOf(before);
        assertThat(reenabled).containsExactlyElementsOf(before);
        assertThat(rows(panel.getCurrentRequest().headers)).containsExactlyElementsOf(before);
    }

    @Test
    void mapperRetainsTransportDuplicatesDisabledAndOrderInManualPreserve() {
        RequestEditorPanel panel = panel();
        ApiRequest req = request(ApiRequest.BuildMode.MANUAL_PRESERVE);
        req.headers = orderedHeaders();
        panel.loadRequest(req);

        ApiRequest built = panel.buildRequestFromUI();

        assertThat(rows(built.headers)).containsExactlyElementsOf(rows(req.headers));
    }

    @Test
    void modeTogglePreservesUnrelatedRequestStateAndBodyMetadata() {
        RequestEditorPanel panel = panel();
        ApiRequest req = richRequest();
        panel.loadRequest(req);
        String before = nonModeSnapshot(req);

        exactItem(panel).doClick();

        assertThat(req.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(nonModeSnapshot(req)).isEqualTo(before);

        exactItem(panel).doClick();

        assertThat(req.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(nonModeSnapshot(req)).isEqualTo(before);
    }

    @Test
    void modeToggleAppliesIntentionalEditorEditsWithoutDroppingMetadata() {
        RequestEditorPanel panel = panel();
        ApiRequest req = richRequest();
        panel.loadRequest(req);
        String metadataBefore = metadataSnapshot(req);

        panel.getMethodBox().setSelectedItem("POST");
        panel.getUrlField().setText("http://example.com/changed");
        model(panel).setValueAt("changed.example", 0, 1);
        panel.getBodyRawAreaForTests().setText("{\"edited\":true}");

        exactItem(panel).doClick();

        assertThat(req.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(req.method).isEqualTo("POST");
        assertThat(req.url).isEqualTo("http://example.com/changed");
        assertThat(req.headers.get(0).value).isEqualTo("changed.example");
        assertThat(req.body.raw).isEqualTo("{\"edited\":true}");
        assertThat(metadataSnapshot(req)).isEqualTo(metadataBefore);
        assertThat(bodyMetadataSnapshot(req)).contains("application/vnd.awb+json", "upload.bin", "disabled-form");
    }

    private static RequestEditorPanel panel() {
        RequestEditorPanel panel = new RequestEditorPanel();
        panel.setRequestBuilder(new RequestBuilder(null));
        panel.setExactTransportWarningProviderForTests((parent, title, message) -> true);
        ApiCollection collection = new ApiCollection();
        panel.setCurrentCollection(collection);
        return panel;
    }

    private static ApiRequest request(ApiRequest.BuildMode mode) {
        ApiRequest req = new ApiRequest();
        req.name = "r";
        req.method = "GET";
        req.url = "http://example.com/path";
        req.buildMode = mode;
        return req;
    }

    private static JCheckBoxMenuItem exactItem(RequestEditorPanel panel) {
        JPopupMenu menu = panel.createSendDropdownMenuForTests();
        List<String> labels = new ArrayList<>();
        for (Component component : menu.getComponents()) {
            if (component instanceof JMenuItem item) labels.add(item.getText());
            if (component instanceof JCheckBoxMenuItem item && "Exact transport headers \u2014 Advanced".equals(item.getText())) {
                return item;
            }
        }
        throw new AssertionError("missing exact item: " + labels);
    }

    private static List<JCheckBox> findCheckBoxes(Component root) {
        List<JCheckBox> boxes = new ArrayList<>();
        if (root instanceof JCheckBox box) boxes.add(box);
        if (root instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) boxes.addAll(findCheckBoxes(child));
        }
        return boxes;
    }

    private static DefaultTableModel model(RequestEditorPanel panel) {
        return (DefaultTableModel) panel.getHeadersTableForTests().getModel();
    }

    private static List<ApiRequest.Header> orderedHeaders() {
        return new ArrayList<>(List.of(
                new ApiRequest.Header("Host", "custom.example", false),
                new ApiRequest.Header("X-Test", "one", false),
                new ApiRequest.Header("Content-Length", "999", false),
                new ApiRequest.Header("X-Test", "two", false),
                new ApiRequest.Header("Transfer-Encoding", "chunked", false),
                new ApiRequest.Header("Cookie", "a=1", false),
                new ApiRequest.Header("Cookie", "b=2", false),
                new ApiRequest.Header("Connection", "keep-alive", true)
        ));
    }

    private static ApiRequest richRequest() {
        ApiRequest req = request(ApiRequest.BuildMode.MANUAL_PRESERVE);
        req.id = "req-id";
        req.name = "rich";
        req.path = "folder/rich";
        req.sourceCollection = "collection-id";
        req.description = "important metadata";
        req.disabled = true;
        req.sequenceOrder = 42;
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = "tenant";
        variable.value = "acme";
        variable.type = "string";
        variable.enabled = false;
        req.variables.add(variable);
        req.authOverrideMode = "explicit";
        req.auth = auth("bearer", Map.of("token", "token-1"));
        req.explicitAuth = auth("bearer", Map.of("token", "token-1"));
        req.authSource = "folder auth";
        req.authInherited = true;
        req.authExplicitlyDisabled = true;
        req.preRequestScripts.add(new ApiRequest.Script("js", "pre();"));
        req.postResponseScripts.add(new ApiRequest.Script("js", "post();"));
        ScriptBlock block = ScriptBlock.of("block();", ScriptDialect.LEGACY_NASHORN, ScriptPhase.PRE_REQUEST, ScriptScope.REQUEST);
        block.id = "block-id";
        block.sourcePath = "folder/rich";
        block.order = 7;
        block.metadata.put("k", "v");
        req.scriptBlocks.add(block);
        req.suppressedAutoHeaders = new LinkedHashSet<>(List.of("accept", "user-agent"));
        req.headers = orderedHeaders();
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "{\"ok\":true}";
        req.body.contentType = "application/vnd.awb+json";
        req.body.graphql = new ApiRequest.Body.GraphQL();
        req.body.graphql.query = "query Existing { id }";
        req.body.graphql.variables = "{\"id\":1}";
        req.body.formdata.add(formField("text", "value", "text", false, null, false));
        req.body.formdata.add(formField("file", "", "file", true, "upload.bin", false));
        req.body.formdata.add(formField("disabled-form", "secret", "text", false, null, true));
        req.body.urlencoded.add(formField("u", "1", "text", false, null, false));
        req.body.urlencoded.add(formField("disabled-url", "2", "text", false, null, true));
        return req;
    }

    private static ApiRequest.Auth auth(String type, Map<String, String> properties) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = type;
        auth.properties.putAll(properties);
        return auth;
    }

    private static ApiRequest.Body.FormField formField(String key, String value, String type,
                                                       boolean fileUpload, String filePath, boolean disabled) {
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(key, value);
        field.type = type;
        field.fileUpload = fileUpload;
        field.filePath = filePath;
        field.disabled = disabled;
        return field;
    }

    private static String nonModeSnapshot(ApiRequest req) {
        return metadataSnapshot(req)
                + "|headers=" + rows(req.headers)
                + "|body=" + bodySnapshot(req)
                + "|auth=" + authSnapshot(req.auth)
                + "|explicitAuth=" + authSnapshot(req.explicitAuth)
                + "|pre=" + scripts(req.preRequestScripts)
                + "|post=" + scripts(req.postResponseScripts)
                + "|blocks=" + blocks(req.scriptBlocks)
                + "|suppressed=" + req.suppressedAutoHeaders;
    }

    private static String metadataSnapshot(ApiRequest req) {
        return "id=" + req.id
                + "|name=" + req.name
                + "|path=" + req.path
                + "|sourceCollection=" + req.sourceCollection
                + "|description=" + req.description
                + "|disabled=" + req.disabled
                + "|sequenceOrder=" + req.sequenceOrder
                + "|variables=" + variables(req.variables)
                + "|authSource=" + req.authSource
                + "|authInherited=" + req.authInherited
                + "|authExplicitlyDisabled=" + req.authExplicitlyDisabled
                + "|authOverrideMode=" + req.authOverrideMode;
    }

    private static String bodyMetadataSnapshot(ApiRequest req) {
        return req.body == null ? "null" : "contentType=" + req.body.contentType
                + "|form=" + fields(req.body.formdata)
                + "|urlencoded=" + fields(req.body.urlencoded)
                + "|graphqlVars=" + (req.body.graphql != null ? req.body.graphql.variables : null);
    }

    private static String bodySnapshot(ApiRequest req) {
        return req.body == null ? "null" : "mode=" + req.body.mode
                + "|raw=" + req.body.raw
                + "|" + bodyMetadataSnapshot(req)
                + "|graphqlQuery=" + (req.body.graphql != null ? req.body.graphql.query : null);
    }

    private static String fields(List<ApiRequest.Body.FormField> fields) {
        List<String> out = new ArrayList<>();
        for (ApiRequest.Body.FormField f : fields) {
            out.add(f.key + "=" + f.value + "|" + f.type + "|" + f.fileUpload + "|" + f.filePath + "|" + f.disabled);
        }
        return out.toString();
    }

    private static String variables(List<ApiRequest.Variable> vars) {
        List<String> out = new ArrayList<>();
        for (ApiRequest.Variable v : vars) {
            out.add(v.key + "=" + v.value + "|" + v.type + "|" + v.enabled);
        }
        return out.toString();
    }

    private static String scripts(List<ApiRequest.Script> scripts) {
        List<String> out = new ArrayList<>();
        for (ApiRequest.Script script : scripts) {
            out.add(script.type + ":" + script.exec);
        }
        return out.toString();
    }

    private static String blocks(List<ScriptBlock> blocks) {
        List<String> out = new ArrayList<>();
        for (ScriptBlock block : blocks) {
            out.add(block.id + "|" + block.phase + "|" + block.scope + "|" + block.source + "|" + block.enabled
                    + "|" + block.sourcePath + "|" + block.order + "|" + block.metadata);
        }
        return out.toString();
    }

    private static String authSnapshot(ApiRequest.Auth auth) {
        return auth == null ? "null" : auth.type + "|" + auth.properties;
    }

    private static List<String> rows(DefaultTableModel model) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            Object key = model.getValueAt(i, 0);
            if (key == null || key.toString().isBlank()) continue;
            rows.add(key + ": " + model.getValueAt(i, 1) + "|" + model.getValueAt(i, 2));
        }
        return rows;
    }

    private static List<String> rows(List<ApiRequest.Header> headers) {
        List<String> rows = new ArrayList<>();
        for (ApiRequest.Header h : headers) rows.add(h.key + ": " + h.value + "|" + h.disabled);
        return rows;
    }
}
