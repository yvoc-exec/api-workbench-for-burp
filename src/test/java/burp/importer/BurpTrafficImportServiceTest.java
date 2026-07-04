package burp.importer;

import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BurpTrafficImportServiceTest {

    private final BurpTrafficImportService service = new BurpTrafficImportService(
            Clock.fixed(Instant.parse("2026-07-04T16:00:00Z"), ZoneOffset.UTC));

    @Test
    void preservesRawRequestBytesExactly() {
        byte[] raw = requestBytes("GET /api/users HTTP/1.1\r\nHost: api.example.test\r\n\r\n");

        ApiRequest request = service.convertRequest(selection(raw, null, "api.example.test", 443, true, "Proxy"));

        assertThat(request.exactHttpRequest.rawRequestBytes).isEqualTo(raw);
    }

    @Test
    void preservesDuplicateHeadersAndOrder() {
        ApiRequest request = service.convertRequest(selection(
                requestBytes("GET /api/users HTTP/1.1\r\nHost: api.example.test\r\nX-Test: one\r\nX-Test: two\r\n\r\n"),
                null,
                "api.example.test",
                443,
                true,
                "Proxy"));

        assertThat(request.headers).extracting(header -> header.key + "=" + header.value)
                .containsExactly("Host=api.example.test", "X-Test=one", "X-Test=two");
    }

    @Test
    void preservesBinaryBodyBytesWithoutUtf8RoundTrip() {
        byte[] raw = concat(
                "POST /upload HTTP/1.1\r\nHost: api.example.test\r\nContent-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8),
                new byte[]{0x00, (byte) 0xFF, 0x41});

        ApiRequest request = service.convertRequest(selection(raw, null, "api.example.test", 443, true, "Proxy"));

        assertThat(request.body).isNotNull();
        assertThat(request.body.raw).isNull();
        assertThat(request.exactHttpRequest.binaryBody).isTrue();
        assertThat(request.exactHttpRequest.rawRequestBytes).isEqualTo(raw);
    }

    @Test
    void buildsAbsoluteUrlFromServiceAndOmitsDefaultPort() {
        ApiRequest request = service.convertRequest(selection(
                requestBytes("GET /api/users?page=1 HTTP/1.1\r\nHost: ignored.example\r\n\r\n"),
                null,
                "api.example.test",
                443,
                true,
                "Proxy"));

        assertThat(request.url).isEqualTo("https://api.example.test/api/users?page=1");
    }

    @Test
    void preservesNonDefaultPortAndIpv6Formatting() {
        ApiRequest request = service.convertRequest(selection(
                requestBytes("GET /status HTTP/1.1\r\nHost: [2001:db8::1]:8443\r\n\r\n"),
                null,
                "2001:db8::1",
                8443,
                true,
                "Repeater"));

        assertThat(request.url).isEqualTo("https://[2001:db8::1]:8443/status");
    }

    @Test
    void defaultsToExactHttpAndCreatesSafeSuggestedName() {
        ApiRequest request = service.convertRequest(new BurpTrafficSelection(
                requestBytes("GET /api/users?id=123 HTTP/1.1\r\nHost: api.example.test\r\n\r\n"),
                null,
                "api.example.test",
                80,
                false,
                "Proxy",
                "Unsafe:/Users?123",
                null,
                1));

        assertThat(request.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(request.name).doesNotContain("/");
        assertThat(request.name).isNotBlank();
        assertThat(request.preRequestScripts).isEmpty();
        assertThat(request.postResponseScripts).isEmpty();
        assertThat(request.scriptBlocks).isEmpty();
    }

    @Test
    void batchConversionPreservesOrderAndCapturesBurpTrafficHistory() {
        BurpTrafficConversionResult result = service.convert(List.of(
                selection(requestBytes("GET /one HTTP/1.1\r\nHost: api.example.test\r\n\r\n"),
                        responseBytes("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\none"),
                        "api.example.test", 443, true, "Proxy", 1),
                selection(requestBytes("GET /two HTTP/1.1\r\nHost: api.example.test\r\n\r\n"),
                        responseBytes("HTTP/1.1 201 Created\r\nContent-Type: text/plain\r\n\r\ntwo"),
                        "api.example.test", 443, true, "Proxy", 2)
        ));

        assertThat(result.failures).isEmpty();
        assertThat(result.requests).extracting(request -> request.url)
                .containsExactly("https://api.example.test/one", "https://api.example.test/two");
        assertThat(result.historyEntries).hasSize(2);
        assertThat(result.historyEntries).extracting(entry -> entry.source.name())
                .containsOnly("BURP_TRAFFIC");
    }

    @Test
    void conversionFailureReturnsSafeReasonAndIsAtomic() {
        BurpTrafficConversionResult result = service.convert(List.of(
                selection(requestBytes("GET /ok HTTP/1.1\r\nHost: api.example.test\r\n\r\n"), null, "api.example.test", 443, true, "Proxy", 1),
                selection("BROKEN".getBytes(StandardCharsets.UTF_8), null, "api.example.test", 443, true, "Proxy", 2)
        ));

        assertThat(result.requests).isEmpty();
        assertThat(result.historyEntries).isEmpty();
        assertThat(result.failures).singleElement().satisfies(failure -> {
            assertThat(failure.encounterIndex).isEqualTo(2);
            assertThat(failure.reasonCode).isEqualTo("MALFORMED_HTTP_REQUEST");
            assertThat(failure.safeMessage).isNotBlank();
            assertThat(failure.safeMessage).doesNotContain("Host:");
        });
    }

    @Test
    void defensiveCopiesDoNotAliasMontoyaBytes() {
        byte[] requestBytes = requestBytes("GET /alias HTTP/1.1\r\nHost: api.example.test\r\n\r\n");
        BurpTrafficSelection selection = selection(requestBytes, null, "api.example.test", 443, true, "Proxy");

        ApiRequest request = service.convertRequest(selection);
        requestBytes[0] = 'X';
        request.exactHttpRequest.rawRequestBytes[1] = 'Y';

        assertThat(selection.rawRequestBytes[0]).isEqualTo((byte) 'G');
        assertThat(request.exactHttpRequest.rawRequestBytes[0]).isEqualTo((byte) 'G');
    }

    private static BurpTrafficSelection selection(byte[] rawRequest,
                                                  byte[] rawResponse,
                                                  String host,
                                                  int port,
                                                  boolean secure,
                                                  String context) {
        return selection(rawRequest, rawResponse, host, port, secure, context, 1);
    }

    private static BurpTrafficSelection selection(byte[] rawRequest,
                                                  byte[] rawResponse,
                                                  String host,
                                                  int port,
                                                  boolean secure,
                                                  String context,
                                                  int index) {
        return new BurpTrafficSelection(rawRequest, rawResponse, host, port, secure, context, null, null, index);
    }

    private static byte[] requestBytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] responseBytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }
}
