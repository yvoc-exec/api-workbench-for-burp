package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.RequestBuilder;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
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
