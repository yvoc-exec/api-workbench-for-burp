package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.testsupport.ImporterPanelTestSupport;
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
    void exactModeToggleUsesDedicatedListenerWithoutGenericCollectionChange() {
        RequestEditorPanel panel = panel();
        ApiCollection collection = new ApiCollection();
        ApiRequest req = request(ApiRequest.BuildMode.MANUAL_PRESERVE);
        collection.requests.add(req);
        panel.setCurrentCollection(collection);
        panel.loadRequest(req);

        AtomicInteger genericCollectionChanges = new AtomicInteger();
        AtomicInteger buildModeChanges = new AtomicInteger();
        collection.addChangeListener(genericCollectionChanges::incrementAndGet);
        panel.setRequestBuildModeChangeListener(buildModeChanges::incrementAndGet);
        panel.setExactTransportWarningProviderForTests((parent, title, message) -> true);

        exactItem(panel).doClick();

        assertThat(req.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(buildModeChanges).hasValue(1);
        assertThat(genericCollectionChanges).hasValue(0);
        assertThat(panel.getExactTransportIndicatorForTests().isVisible()).isTrue();
        assertThat(exactItem(panel).isSelected()).isTrue();

        exactItem(panel).doClick();

        assertThat(req.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(buildModeChanges).hasValue(2);
        assertThat(genericCollectionChanges).hasValue(0);
        assertThat(panel.getExactTransportIndicatorForTests().isVisible()).isFalse();
        assertThat(exactItem(panel).isSelected()).isFalse();
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
    void exactRawUrlIsPreservedAcrossModeToggle() {
        RequestEditorPanel panel = panel();
        ApiRequest req = request(ApiRequest.BuildMode.MANUAL_PRESERVE);
        req.url = "https://api.example.test/search?q=hello%20world&path=a%2Fb&plus=%2B&pct=%25&empty=&flag&repeat=1&repeat=2#fragment";
        panel.loadRequest(req);
        String requestUrlBefore = req.url;
        String urlFieldBefore = panel.getUrlField().getText();
        List<String> paramsBefore = parameterRows(paramsModel(panel));

        exactItem(panel).doClick();

        assertThat(req.url).isEqualTo(requestUrlBefore);
        assertThat(panel.getUrlField().getText()).isEqualTo(urlFieldBefore);
        assertThat(parameterRows(paramsModel(panel))).containsExactlyElementsOf(paramsBefore);
        assertThat(req.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);

        exactItem(panel).doClick();

        assertThat(req.url).isEqualTo(requestUrlBefore);
        assertThat(panel.getUrlField().getText()).isEqualTo(urlFieldBefore);
        assertThat(parameterRows(paramsModel(panel))).containsExactlyElementsOf(paramsBefore);
        assertThat(req.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
    }

    @Test
    void autoCompatibleRoundTripsWithoutForcingEditorMaterialization() {
        RequestEditorPanel panel = panel();
        ApiRequest req = richRequest();
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        req.editorMaterialized = false;
        panel.loadRequest(req);
        String before = deepSnapshot(req);

        exactItem(panel).doClick();
        exactItem(panel).doClick();

        assertThat(req.buildMode).isEqualTo(ApiRequest.BuildMode.AUTO_COMPATIBLE);
        assertThat(req.editorMaterialized).isFalse();
        assertThat(deepSnapshot(req)).isEqualTo(before);
    }

    @Test
    void activeFormDataOrderStaysExactAcrossModeToggle() {
        RequestEditorPanel panel = panel();
        ApiRequest req = request(ApiRequest.BuildMode.MANUAL_PRESERVE);
        req.body = new ApiRequest.Body();
        req.body.mode = "formdata";
        req.body.formdata = new ArrayList<>(List.of(
                formField("text-1", "one", "text", false, null, false),
                formField("disabled-1", "skip", "text", false, null, true),
                formField("file-1", "", "file", true, "upload-a.bin", false),
                formField("text-2", "two", "text", false, null, false),
                formField("disabled-2", "skip2", "file", true, "upload-b.bin", true)
        ));
        panel.loadRequest(req);
        String before = fields(req.body.formdata);

        exactItem(panel).doClick();
        assertThat(fields(req.body.formdata)).isEqualTo(before);
        exactItem(panel).doClick();
        assertThat(fields(req.body.formdata)).isEqualTo(before);
    }

    @Test
    void activeUrlEncodedOrderStaysExactAcrossModeToggle() {
        RequestEditorPanel panel = panel();
        ApiRequest req = request(ApiRequest.BuildMode.MANUAL_PRESERVE);
        req.body = new ApiRequest.Body();
        req.body.mode = "urlencoded";
        req.body.urlencoded = new ArrayList<>(List.of(
                formField("u-1", "one", "text", false, null, false),
                formField("u-disabled", "skip", "text", false, null, true),
                formField("u-2", "two", "text", false, null, false),
                formField("u-disabled-2", "skip2", "text", false, null, true)
        ));
        panel.loadRequest(req);
        String before = fields(req.body.urlencoded);

        exactItem(panel).doClick();
        assertThat(fields(req.body.urlencoded)).isEqualTo(before);
        exactItem(panel).doClick();
        assertThat(fields(req.body.urlencoded)).isEqualTo(before);
    }

    @Test
    void multipleScriptObjectsStaySeparateAcrossModeToggle() {
        RequestEditorPanel panel = panel();
        ApiRequest req = richRequest();
        req.preRequestScripts = new ArrayList<>(List.of(
                new ApiRequest.Script("js", "  pre-one();  "),
                new ApiRequest.Script("python", "\npre_two()\n"),
                new ApiRequest.Script("js", "\tpre_three();\t")
        ));
        req.postResponseScripts = new ArrayList<>(List.of(
                new ApiRequest.Script("js", "post-one();"),
                new ApiRequest.Script("python", "post_two()")
        ));
        req.scriptBlocks = new ArrayList<>(List.of(
                scriptBlock("block-pre-1", ScriptDialect.LEGACY_NASHORN, ScriptPhase.PRE_REQUEST, ScriptScope.REQUEST, true, "folder/a", 3, Map.of("dialect", "js"), "source-a"),
                scriptBlock("block-post-1", ScriptDialect.LEGACY_NASHORN, ScriptPhase.POST_RESPONSE, ScriptScope.COLLECTION, false, "folder/b", 4, Map.of("scope", "collection"), "source-b"),
                scriptBlock("block-pre-2", ScriptDialect.LEGACY_NASHORN, ScriptPhase.PRE_REQUEST, ScriptScope.REQUEST, true, "folder/c", 5, Map.of("phase", "pre"), "source-c"),
                scriptBlock("block-post-2", ScriptDialect.LEGACY_NASHORN, ScriptPhase.POST_RESPONSE, ScriptScope.REQUEST, true, "folder/d", 6, Map.of("phase", "post"), "source-d")
        ));
        panel.loadRequest(req);
        String preBefore = scripts(req.preRequestScripts);
        String postBefore = scripts(req.postResponseScripts);
        String blocksBefore = blocks(req.scriptBlocks);

        exactItem(panel).doClick();
        assertThat(scripts(req.preRequestScripts)).isEqualTo(preBefore);
        assertThat(scripts(req.postResponseScripts)).isEqualTo(postBefore);
        assertThat(blocks(req.scriptBlocks)).isEqualTo(blocksBefore);

        exactItem(panel).doClick();
        assertThat(scripts(req.preRequestScripts)).isEqualTo(preBefore);
        assertThat(scripts(req.postResponseScripts)).isEqualTo(postBefore);
        assertThat(blocks(req.scriptBlocks)).isEqualTo(blocksBefore);
    }

    @Test
    void pendingMaterializedAcceptEditSurvivesModeToggleAndExplicitBuild() throws Exception {
        RequestEditorPanel panel = panel();
        ApiRequest req = request(ApiRequest.BuildMode.AUTO_COMPATIBLE);
        req.editorMaterialized = false;
        req.url = "https://example.com/original";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "{\"original\":true}";
        req.preRequestScripts = new ArrayList<>(List.of(new ApiRequest.Script("js", "pre();")));
        panel.loadRequest(req);

        DefaultTableModel headers = model(panel);
        int acceptRow = findRow(headers, "Accept");
        assertThat(acceptRow).isGreaterThanOrEqualTo(0);
        String urlBefore = panel.getUrlField().getText();

        SwingUtilities.invokeAndWait(() -> {
            panel.getUrlField().setText("https://example.com/edited");
            headers.setValueAt("application/xml", acceptRow, 1);
            panel.getBodyRawAreaForTests().setText("{\"edited\":true}");
            preScriptArea(panel).setText("console.log('pre');");
        });
        String headerRowsAfterEdit = headerRows(headers);

        exactItem(panel).doClick();

        assertThat(panel.getUrlField().getText()).isEqualTo("https://example.com/edited");
        assertThat(headers.getValueAt(acceptRow, 1)).isEqualTo("application/xml");
        assertThat(headerRows(headers)).isEqualTo(headerRowsAfterEdit);
        assertThat(panel.getBodyRawAreaForTests().getText()).isEqualTo("{\"edited\":true}");
        assertThat(preScriptArea(panel).getText()).isEqualTo("console.log('pre');");
        assertThat(req.url).isEqualTo(urlBefore);
        assertThat(req.body.raw).isEqualTo("{\"original\":true}");
        assertThat(scripts(req.preRequestScripts)).isEqualTo("[js:pre();]");

        ApiRequest built = panel.buildRequestFromUI();
        assertThat(built.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(built.url).isEqualTo("https://example.com/edited");
        assertThat(rows(built.headers)).contains("Accept: application/xml|false");
        assertThat(built.body.raw).isEqualTo("{\"edited\":true}");
        assertThat(scripts(built.preRequestScripts)).isEqualTo("[js:console.log('pre');]");
    }

    @Test
    void modeToggleChangesOnlyBuildModeOnRichRequest() {
        RequestEditorPanel panel = panel();
        ApiRequest req = richRequest();
        panel.loadRequest(req);
        String before = deepSnapshot(req);

        exactItem(panel).doClick();

        assertThat(req.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(deepSnapshot(req)).isEqualTo(before);

        exactItem(panel).doClick();

        assertThat(req.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(deepSnapshot(req)).isEqualTo(before);
    }

    @Test
    void modeTogglePreservesPendingEditsUntilExplicitBuild() throws Exception {
        RequestEditorPanel panel = panel();
        ApiRequest req = request(ApiRequest.BuildMode.AUTO_COMPATIBLE);
        req.editorMaterialized = false;
        req.url = "https://example.com/original";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "{\"original\":true}";
        req.preRequestScripts = new ArrayList<>(List.of(new ApiRequest.Script("js", "pre();")));
        panel.loadRequest(req);
        DefaultTableModel headers = model(panel);
        int acceptRow = findRow(headers, "Accept");
        assertThat(acceptRow).isGreaterThanOrEqualTo(0);
        String requestBefore = deepSnapshot(req);

        SwingUtilities.invokeAndWait(() -> {
            panel.getUrlField().setText("https://example.com/edited");
            headers.setValueAt("application/xml", acceptRow, 1);
            panel.getBodyRawAreaForTests().setText("{\"edited\":true}");
            preScriptArea(panel).setText("console.log('pre');");
        });

        exactItem(panel).doClick();

        assertThat(req.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(deepSnapshot(req)).isEqualTo(requestBefore);
        assertThat(panel.getUrlField().getText()).isEqualTo("https://example.com/edited");
        assertThat(headers.getValueAt(acceptRow, 1)).isEqualTo("application/xml");
        assertThat(panel.getBodyRawAreaForTests().getText()).isEqualTo("{\"edited\":true}");
        assertThat(preScriptArea(panel).getText()).isEqualTo("console.log('pre');");

        ApiRequest built = panel.buildRequestFromUI();
        assertThat(built.url).isEqualTo("https://example.com/edited");
        assertThat(rows(built.headers)).contains("Accept: application/xml|false");
        assertThat(built.body.raw).isEqualTo("{\"edited\":true}");
        assertThat(scripts(built.preRequestScripts)).isEqualTo("[js:console.log('pre');]");
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

    private static ScriptBlock scriptBlock(String id,
                                           ScriptDialect dialect,
                                           ScriptPhase phase,
                                           ScriptScope scope,
                                           boolean enabled,
                                           String sourcePath,
                                           int order,
                                           Map<String, String> metadata,
                                           String source) {
        ScriptBlock block = ScriptBlock.of(source, dialect, phase, scope);
        block.id = id;
        block.enabled = enabled;
        block.sourcePath = sourcePath;
        block.order = order;
        block.metadata.putAll(metadata);
        return block;
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

    private static String deepSnapshot(ApiRequest req) {
        return metadataSnapshot(req)
                + "|method=" + req.method
                + "|url=" + req.url
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

    private static List<String> parameterRows(DefaultTableModel model) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            Object key = model.getValueAt(i, 0);
            if (key == null || key.toString().isBlank()) continue;
            rows.add(key + ": " + model.getValueAt(i, 1));
        }
        return rows;
    }

    private static String headerRows(DefaultTableModel model) {
        return rows(model).toString();
    }

    private static int findRow(DefaultTableModel model, String key) {
        for (int i = 0; i < model.getRowCount(); i++) {
            Object value = model.getValueAt(i, 0);
            if (value != null && key.equals(value.toString())) {
                return i;
            }
        }
        return -1;
    }

    private static DefaultTableModel paramsModel(RequestEditorPanel panel) {
        return ImporterPanelTestSupport.getField(panel, "paramsModel");
    }

    private static JTextArea preScriptArea(RequestEditorPanel panel) {
        return ImporterPanelTestSupport.getField(panel, "preScriptArea");
    }

    private static JTextArea postScriptArea(RequestEditorPanel panel) {
        return ImporterPanelTestSupport.getField(panel, "postScriptArea");
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
