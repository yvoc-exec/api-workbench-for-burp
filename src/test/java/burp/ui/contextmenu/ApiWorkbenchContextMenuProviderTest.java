package burp.ui.contextmenu;

import burp.importer.BurpTrafficSelection;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ApiWorkbenchContextMenuProviderTest {
    @Test
    void buildsBatchMenuAndDispatchesDetachedSelectionsOnEdt() throws Exception {
        byte[] firstRequest = "GET /one HTTP/1.1\r\nHost: example.invalid\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] secondRequest = "POST /two HTTP/1.1\r\nHost: example.invalid\r\nContent-Length: 3\r\n\r\nabc"
                .getBytes(StandardCharsets.ISO_8859_1);
        FakeEvent event = new FakeEvent(List.of(
                new FakeRequestResponse(
                        new FakeRequest(firstRequest, "GET", new FakeService("example.invalid", 443, true)),
                        new FakeResponse("HTTP/1.1 200 OK\r\n\r\none".getBytes(StandardCharsets.ISO_8859_1))),
                new FakeRequestResponse(
                        new FakeRequest(secondRequest, "POST", new FakeService("example.invalid", 8443, true)),
                        null)));
        AtomicReference<List<BurpTrafficSelection>> captured = new AtomicReference<>();
        AtomicBoolean queue = new AtomicBoolean();
        ApiWorkbenchContextMenuProvider provider = new ApiWorkbenchContextMenuProvider((selections, queueAfterImport) -> {
            assertThat(SwingUtilities.isEventDispatchThread()).isTrue();
            captured.set(selections);
            queue.set(queueAfterImport);
        });

        List<JMenuItem> items = provider.provideMenuItems(event);
        firstRequest[0] = 'X';
        secondRequest[0] = 'Y';

        assertThat(items).extracting(JMenuItem::getText)
                .containsExactly(
                        "Send 2 requests to API Workbench",
                        "Send 2 requests to API Workbench and Queue");
        SwingUtilities.invokeAndWait(() -> items.get(1).doClick());

        assertThat(queue).isTrue();
        assertThat(captured.get()).hasSize(2);
        assertThat(captured.get().get(0).rawRequestBytes[0]).isEqualTo((byte) 'G');
        assertThat(captured.get().get(1).rawRequestBytes[0]).isEqualTo((byte) 'P');
        assertThat(captured.get()).extracting(selection -> selection.encounterIndex)
                .containsExactly(0, 1);
        assertThat(captured.get().get(0).sourceContext).isEqualTo("PROXY");
        assertThat(captured.get().get(0).servicePort).isEqualTo(443);
        assertThat(captured.get().get(1).servicePort).isEqualTo(8443);
        assertThat(captured.get().get(0).rawResponseBytes).isNotEmpty();
        assertThat(captured.get().get(1).rawResponseBytes).isEmpty();
    }

    @Test
    void omitsMenuWhenNoHttpRequestsAreSelected() {
        ApiWorkbenchContextMenuProvider provider = new ApiWorkbenchContextMenuProvider((selections, queue) -> { });

        assertThat(provider.provideMenuItems(new FakeEvent(List.of()))).isEmpty();
    }

    public static final class FakeEvent {
        private final List<FakeRequestResponse> requestResponses;

        public FakeEvent(List<FakeRequestResponse> requestResponses) {
            this.requestResponses = requestResponses;
        }

        public List<FakeRequestResponse> selectedRequestResponses() {
            return requestResponses;
        }

        public String invocationType() {
            return "PROXY";
        }
    }

    public static final class FakeRequestResponse {
        private final FakeRequest request;
        private final FakeResponse response;

        public FakeRequestResponse(FakeRequest request, FakeResponse response) {
            this.request = request;
            this.response = response;
        }

        public FakeRequest request() {
            return request;
        }

        public FakeResponse response() {
            return response;
        }
    }

    public static final class FakeRequest {
        private final byte[] bytes;
        private final String method;
        private final FakeService service;

        public FakeRequest(byte[] bytes, String method, FakeService service) {
            this.bytes = bytes;
            this.method = method;
            this.service = service;
        }

        public byte[] toByteArray() {
            return bytes;
        }

        public String method() {
            return method;
        }

        public FakeService httpService() {
            return service;
        }
    }

    public static final class FakeResponse {
        private final byte[] bytes;

        public FakeResponse(byte[] bytes) {
            this.bytes = bytes;
        }

        public byte[] toByteArray() {
            return bytes;
        }
    }

    public static final class FakeService {
        private final String host;
        private final int port;
        private final boolean secure;

        public FakeService(String host, int port, boolean secure) {
            this.host = host;
            this.port = port;
            this.secure = secure;
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }

        public boolean secure() {
            return secure;
        }
    }
}
