package burp.importer;

import burp.history.HistoryRetentionPolicy;
import burp.history.HistoryEntry;
import burp.payload.FileManagedPayloadStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BurpTrafficCaptureServiceTest {
    @TempDir Path temp;

    @Test
    void stagesOffHeapInBoundedRangesAndRetainsOnlyResponsePreview() throws Exception {
        byte[] head = "POST /upload HTTP/1.1\r\nHost: example.test\r\nContent-Type: application/octet-stream\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] requestBytes = new byte[head.length + 3 * 1024 * 1024 + 17];
        System.arraycopy(head, 0, requestBytes, 0, head.length);
        java.util.Arrays.fill(requestBytes, head.length, requestBytes.length, (byte) 0xff);
        FakeByteArray requestArray = new FakeByteArray(requestBytes);
        FakeByteArray responseBody = new FakeByteArray(new byte[3 * 1024 * 1024]);
        FakeExchange exchange = new FakeExchange(
                new FakeRequest(requestArray, head.length),
                new FakeResponse(responseBody));

        try (FileManagedPayloadStore store = new FileManagedPayloadStore(temp.resolve("store"))) {
            BurpTrafficCaptureService service = new BurpTrafficCaptureService(store);
            List<BurpTrafficSelection> selections = service.stage(
                    List.of(new BurpTrafficSourceSelection(exchange, "PROXY", 0)),
                    new HistoryRetentionPolicy(100, 20 * 1024 * 1024L, 1024, 4096, true));
            BurpTrafficSelection selection = selections.get(0);

            assertThat(requestArray.copyToTempFileCount).isEqualTo(1);
            assertThat(requestArray.largestGetBytes).isLessThanOrEqualTo(1024 * 1024);
            assertThat(requestArray.wholeGetBytesCount).isZero();
            assertThat(selection.rawRequestBytes).isNull();
            assertThat(selection.exactPayload.length).isEqualTo(requestBytes.length);
            assertThat(selection.bodyLength).isEqualTo(requestBytes.length - head.length);
            assertThat(selection.binaryBody).isTrue();
            assertThat(selection.responseSnapshot.body).hasSize(4096);
            assertThat(selection.responseSnapshot.originalBodyLength).isEqualTo(3 * 1024 * 1024L);
            assertThat(responseBody.largestGetBytes).isLessThanOrEqualTo(1024 * 1024);

            BurpTrafficImportService importer = new BurpTrafficImportService();
            burp.models.ApiRequest request = importer.convertRequest(selection);
            HistoryEntry history = importer.convertHistory(selection, request,
                    new HistoryRetentionPolicy(100, 20 * 1024 * 1024L, 1024, 4096, true));
            assertThat(history.requestSnapshot.authoredExactPayloadRef).isNull();
            assertThat(history.requestSnapshot.authoredRequest.body.managedPayload).isNull();

            selection.commit(store);
            selection.close();
            assertThat(store.uniqueBlobCount()).isEqualTo(1);
        }
    }

    public static final class FakeExchange {
        private final FakeRequest request;
        private final FakeResponse response;
        FakeExchange(FakeRequest request, FakeResponse response) { this.request = request; this.response = response; }
        public FakeRequest request() { return request; }
        public FakeResponse response() { return response; }
    }

    public static final class FakeRequest {
        private final FakeByteArray bytes;
        private final int bodyOffset;
        FakeRequest(FakeByteArray bytes, int bodyOffset) { this.bytes = bytes; this.bodyOffset = bodyOffset; }
        public FakeByteArray toByteArray() { return bytes; }
        public int bodyOffset() { return bodyOffset; }
        public String method() { return "POST"; }
        public String url() { return "https://example.test/upload"; }
        public String path() { return "/upload"; }
        public String httpVersion() { return "HTTP/1.1"; }
        public FakeService httpService() { return new FakeService(); }
        public List<FakeHeader> headers() { return List.of(new FakeHeader("Content-Type", "application/octet-stream")); }
    }

    public static final class FakeResponse {
        private final FakeByteArray body;
        FakeResponse(FakeByteArray body) { this.body = body; }
        public int statusCode() { return 200; }
        public String reasonPhrase() { return "OK"; }
        public List<FakeHeader> headers() { return List.of(new FakeHeader("Content-Type", "application/octet-stream")); }
        public FakeByteArray body() { return body; }
        public FakeByteArray toByteArray() { return body; }
    }

    public record FakeHeader(String name, String value) { }

    public static final class FakeService {
        public String host() { return "example.test"; }
        public int port() { return 443; }
        public boolean secure() { return true; }
    }

    public static class FakeByteArray {
        private final byte[] bytes;
        private final boolean whole;
        int copyToTempFileCount;
        int largestGetBytes;
        int wholeGetBytesCount;

        FakeByteArray(byte[] bytes) { this(bytes, true); }
        private FakeByteArray(byte[] bytes, boolean whole) { this.bytes = bytes; this.whole = whole; }
        public int length() { return bytes.length; }
        public FakeByteArray copyToTempFile() { copyToTempFileCount++; return this; }
        public FakeByteArray subArray(int start, int end) {
            FakeByteArray slice = new FakeByteArray(java.util.Arrays.copyOfRange(bytes, start, end), false);
            slice.copyToTempFileCount = copyToTempFileCount;
            return new TrackingSlice(slice.bytes, this);
        }
        public byte[] getBytes() {
            largestGetBytes = Math.max(largestGetBytes, bytes.length);
            if (whole) wholeGetBytesCount++;
            return bytes.clone();
        }
    }

    private static final class TrackingSlice extends FakeByteArray {
        private final FakeByteArray owner;
        TrackingSlice(byte[] bytes, FakeByteArray owner) { super(bytes, false); this.owner = owner; }
        @Override public byte[] getBytes() {
            owner.largestGetBytes = Math.max(owner.largestGetBytes, length());
            return super.getBytes();
        }
    }
}
