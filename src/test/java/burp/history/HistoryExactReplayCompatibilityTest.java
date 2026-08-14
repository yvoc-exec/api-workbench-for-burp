package burp.history;

import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.utils.RequestBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryExactReplayCompatibilityTest {
    private final RequestBuilder requestBuilder = new RequestBuilder(null);

    @Test
    void r6TextualHttp10ReplayRemainsByteExact() throws Exception {
        byte[] expected = bytes("POST /legacy HTTP/1.0\r\nHost: api.example.test\r\nContent-Length: 5\r\n\r\nhello");

        HistoryEntry stored = storeExact(expected, expected, false);

        assertThat(requestBuilder.buildRequest(stored.requestSnapshot.toAuthoredApiRequest(), null))
                .containsExactly(expected);
        assertThat(stored.requestSnapshot.authoredRequest.exactHttpRequest.httpVersion)
                .isEqualTo("HTTP/1.0");
    }

    @Test
    void r6BinaryReplayRemainsByteExactInsteadOfBecomingAnEmptySemanticBody() throws Exception {
        byte[] prefix = bytes("POST /binary HTTP/1.1\r\nHost: api.example.test\r\nContent-Type: application/octet-stream\r\nContent-Length: 5\r\n\r\n");
        byte[] expected = concat(prefix, new byte[]{0x00, (byte) 0xFF, 0x41, (byte) 0x80, 0x42});

        HistoryEntry stored = storeExact(expected, expected, true);
        ApiRequest replay = stored.requestSnapshot.toAuthoredApiRequest();

        assertThat(replay.exactHttpRequest.binaryBody).isTrue();
        assertThat(requestBuilder.buildRequest(replay, null)).containsExactly(expected);
    }

    @Test
    void r6DuplicateHeaderReplayRetainsOrderAndDuplicatesByteForByte() throws Exception {
        byte[] expected = bytes("GET /duplicates HTTP/1.1\r\nHost: api.example.test\r\nX-Test: one\r\nX-Test: two\r\nCookie: a=1\r\nCookie: b=2\r\n\r\n");

        HistoryEntry stored = storeExact(expected, expected, false);

        assertThat(requestBuilder.buildRequest(stored.requestSnapshot.toAuthoredApiRequest(), null))
                .containsExactly(expected);
    }

    @Test
    void runtimeRawEvidenceNeverReplacesDistinctAuthoredExactTransport() throws Exception {
        byte[] authored = bytes("POST /authored HTTP/1.0\r\nHost: api.example.test\r\nContent-Length: 8\r\n\r\nauthored");
        byte[] runtime = bytes("DELETE /runtime HTTP/1.1\r\nHost: changed.example.test\r\nContent-Length: 7\r\n\r\nruntime");

        HistoryEntry stored = storeExact(authored, runtime, false);

        assertThat(requestBuilder.buildRequest(stored.requestSnapshot.toAuthoredApiRequest(), null))
                .containsExactly(authored);
        assertThat(stored.requestSnapshot.rawRequestSent).containsExactly(runtime);
        assertThat(stored.requestSnapshot.authoredExactRequestBytes)
                .isNotSameAs(stored.requestSnapshot.rawRequestSent);
    }

    @Test
    void truncatedRuntimeEvidenceWithoutAuthoredExactBytesNeverBecomesExecutableExactTransport() {
        ApiRequest authored = semanticRequest();
        HistoryEntry entry = entry(authored);
        entry.requestSnapshot.rawRequestSent = bytes(
                "POST /runtime HTTP/1.1\r\nHost: api.example.test\r\nContent-Length: 16\r\n\r\n0123456789abcdef");
        HistoryRetentionPolicy policy = new HistoryRetentionPolicy(10, 1024 * 1024, 4, 4, true);
        HistoryStore store = new HistoryStore();
        store.setRetentionPolicy(policy);

        assertThat(store.admitEntry(entry).accepted()).isTrue();
        HistoryRequestSnapshot snapshot = store.getById(entry.id).requestSnapshot;

        assertThat(snapshot.rawBodyTruncated).isTrue();
        assertThat(snapshot.authoredExactRequestBytes).isNull();
        assertThat(snapshot.toAuthoredApiRequest().exactHttpRequest).isNull();
        assertThat(snapshot.rawRequestSent).isNotEmpty();
    }

    @Test
    void byteIdenticalAuthoredAndRuntimeTransportShareOneCanonicalHistoryOwner() {
        byte[] exact = bytes("GET /shared HTTP/1.1\r\nHost: api.example.test\r\n\r\n");

        HistoryEntry stored = storeExact(exact, exact.clone(), false);

        assertThat(stored.requestSnapshot.authoredRequest.exactHttpRequest.rawRequestBytes).isNull();
        assertThat(stored.requestSnapshot.authoredExactRequestBytes)
                .isSameAs(stored.requestSnapshot.rawRequestSent);
        assertThat(stored.requestSnapshot.approximateSizeBytes()).isLessThan(exact.length * 2L + 512L);
    }

    private HistoryEntry storeExact(byte[] authoredBytes, byte[] runtimeBytes, boolean binary) {
        ApiRequest request = exactRequest(authoredBytes, binary);
        HistoryEntry entry = entry(request);
        entry.requestSnapshot.rawRequestSent = runtimeBytes.clone();
        entry.requestSnapshot.canonicalizeExactTransportOwnership();
        HistoryStore store = new HistoryStore();
        assertThat(store.admitEntry(entry).accepted()).isTrue();
        return store.getById(entry.id);
    }

    private static HistoryEntry entry(ApiRequest request) {
        HistoryEntry entry = new HistoryEntry();
        entry.id = "exact-" + System.nanoTime();
        entry.timestamp = Instant.parse("2026-08-15T00:00:00Z");
        entry.source = HistorySource.WORKBENCH;
        entry.requestSnapshot = HistoryRequestSnapshot.fromWithoutExactTransport(request);
        entry.requestSent = true;
        return entry;
    }

    private static ApiRequest exactRequest(byte[] raw, boolean binary) {
        ApiRequest request = semanticRequest();
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.exactHttpRequest = new ExactHttpRequestSnapshot();
        request.exactHttpRequest.rawRequestBytes = raw.clone();
        request.exactHttpRequest.serviceHost = "api.example.test";
        request.exactHttpRequest.servicePort = 443;
        request.exactHttpRequest.secure = true;
        request.exactHttpRequest.httpVersion = raw.length > 0
                && new String(raw, 0, Math.min(raw.length, 64), StandardCharsets.ISO_8859_1).contains("HTTP/1.0")
                ? "HTTP/1.0"
                : "HTTP/1.1";
        request.exactHttpRequest.binaryBody = binary;
        request.exactHttpRequest.sourceContext = "R6 compatibility fixture";
        request.exactHttpRequest.pristine = true;
        request.exactHttpRequest.semanticFingerprint = request.computeSemanticFingerprint();
        if (binary) {
            request.body.raw = null;
        }
        return request;
    }

    private static ApiRequest semanticRequest() {
        ApiRequest request = new ApiRequest();
        request.method = "POST";
        request.url = "https://api.example.test/authored";
        request.headers.add(new ApiRequest.Header("Host", "api.example.test"));
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "authored";
        return request;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = new byte[left.length + right.length];
        System.arraycopy(left, 0, result, 0, left.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }
}
