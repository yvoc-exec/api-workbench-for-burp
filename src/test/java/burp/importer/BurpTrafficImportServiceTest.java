package burp.importer;

import burp.models.ApiRequest;
import burp.history.HistoryBodyTruncator;
import burp.history.HistoryEntry;
import burp.history.HistoryRetentionPolicy;
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
    void textualExactBodyUsesRawBytesAsItsOnlyLongLivedBodyOwner() {
        byte[] source = requestBytes("POST /text HTTP/1.1\r\nHost: api.example.test\r\n\r\nlarge textual body");
        BurpTrafficSelection selection = selection(source, null, "api.example.test", 443, true, "Proxy");

        ApiRequest request = service.convertRequest(selection);

        assertThat(request.body.raw).isNull();
        assertThat(request.exactHttpRequest.binaryBody).isFalse();
        assertThat(request.exactHttpRequest.rawRequestBytes).isSameAs(selection.rawRequestBytes);
        assertThat(selection.rawRequestBytes).isNotSameAs(source).isEqualTo(source);
    }

    @Test
    void trafficHistoryIsBoundedDuringConversionAndRetainsFullEvidenceMetadata() {
        byte[] requestBody = "0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] responseBody = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        byte[] rawRequest = concat(
                "POST /bounded HTTP/1.1\r\nHost: api.example.test\r\n\r\n".getBytes(StandardCharsets.UTF_8),
                requestBody);
        byte[] rawResponse = concat(
                "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n".getBytes(StandardCharsets.UTF_8),
                responseBody);
        BurpTrafficSelection selection = selection(
                rawRequest, rawResponse, "api.example.test", 443, true, "Proxy");
        ApiRequest request = service.convertRequest(selection);
        HistoryRetentionPolicy policy = new HistoryRetentionPolicy(100, 1024 * 1024, 4, 3, true);

        HistoryEntry entry = service.convertHistory(selection, request, policy);

        assertThat(entry.requestSnapshot.rawRequestSentText).isNull();
        assertThat(entry.requestSnapshot.authoredRequest.exactHttpRequest).isNotNull();
        assertThat(entry.requestSnapshot.authoredRequest.exactHttpRequest.rawRequestBytes).isNull();
        assertThat(entry.requestSnapshot.authoredExactRequestBytes).isNull();
        assertThat(entry.requestSnapshot.authoredRequest.exactHttpRequest.pristine).isFalse();
        assertThat(entry.requestSnapshot.originalRawBodyLength).isEqualTo(10);
        assertThat(entry.requestSnapshot.storedRawBodyLength).isEqualTo(4);
        assertThat(entry.requestSnapshot.rawBodyTruncated).isTrue();
        assertThat(entry.requestSnapshot.fullRawBodySha256)
                .isEqualTo(HistoryBodyTruncator.sha256Hex(requestBody));
        assertThat(entry.responseSnapshot.body).containsExactly((byte) 'a', (byte) 'b', (byte) 'c');
        assertThat(entry.responseSnapshot.originalBodyLength).isEqualTo(10);
        assertThat(entry.responseSnapshot.storedBodyLength).isEqualTo(3);
        assertThat(entry.responseSnapshot.bodyTruncated).isTrue();
        assertThat(entry.responseSnapshot.fullBodySha256)
                .isEqualTo(HistoryBodyTruncator.sha256Hex(responseBody));
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

    @Test
    void historicalDefaultItemBudgetDoesNotRejectPlusOne() {
        byte[] atLimit = validRequestOfSize(1024 * 1024);
        byte[] overLimit = validRequestOfSize(atLimit.length + 1);

        BurpTrafficConversionResult accepted = service.convert(List.of(ownedSelection(atLimit, 1)));
        BurpTrafficConversionResult formerlyRejected = service.convert(List.of(ownedSelection(overLimit, 1)));

        assertThat(accepted.preflight.accepted()).isTrue();
        assertThat(accepted.requests).hasSize(1);
        assertThat(formerlyRejected.preflight.accepted()).isTrue();
        assertThat(formerlyRejected.requests).hasSize(1);
    }

    @Test
    void configuredHistoricalItemBudgetIsNonEnforcing() {
        BurpTrafficImportService oneMib = new BurpTrafficImportService(
                Clock.systemUTC(), new TrafficImportLimits(TrafficImportLimits.MIB, 128L * TrafficImportLimits.MIB));
        assertThat(oneMib.preflight(List.of(ownedSelection(
                validRequestOfSize((int) TrafficImportLimits.MIB), 1))).accepted()).isTrue();
        assertThat(oneMib.preflight(List.of(ownedSelection(
                validRequestOfSize((int) TrafficImportLimits.MIB + 1), 1))).accepted()).isTrue();

        TrafficImportLimits clamped = new TrafficImportLimits(Long.MAX_VALUE, Long.MAX_VALUE);
        assertThat(clamped.maxExactRequestBytes()).isEqualTo(64L * TrafficImportLimits.MIB);
        assertThat(clamped.maxAggregateExactRequestBytes()).isEqualTo(512L * TrafficImportLimits.MIB);
        assertThat(new BurpTrafficImportService(Clock.systemUTC(), clamped).preflight(List.of(
                ownedSelection(validRequestOfSize(1024 * 1024), 1))).accepted()).isTrue();
    }

    @Test
    void legacyLimitConfigurationDoesNotRejectExactTraffic() {
        BurpTrafficImportService limited = new BurpTrafficImportService(
                java.time.Clock.systemUTC(), new TrafficImportLimits(1, 1));
        BurpTrafficSelection selection = ownedSelection(validRequestOfSize(2 * 1024 * 1024), 0);

        TrafficImportPreflightResult preflight = limited.preflight(List.of(selection));

        assertThat(preflight.accepted()).isTrue();
        assertThat(preflight.configuredPerItemLimit()).isZero();
        assertThat(preflight.configuredAggregateLimit()).isZero();
    }

    @Test
    void aggregateLengthAccountingSaturatesWithoutBecomingCapabilityPolicy() {
        assertThat(BurpTrafficImportService.wouldOverflow(Long.MAX_VALUE, 1L)).isTrue();
        assertThat(BurpTrafficImportService.wouldOverflow(10L, 20L)).isFalse();
    }

    @Test
    void identicalAuthoredAndSentExactHistoryUsesOnePayloadOwner() {
        BurpTrafficSelection selection = BurpTrafficSelection.fromOwnedBytes(
                requestBytes("POST /same HTTP/1.1\r\nHost: api.example.test\r\n\r\nsame"),
                responseBytes("HTTP/1.1 200 OK\r\n\r\nok"),
                "api.example.test", 443, true, "Proxy", null, null, 1);
        ApiRequest request = service.convertRequest(selection);

        HistoryEntry entry = service.convertHistory(selection, request,
                new HistoryRetentionPolicy(100, 1024 * 1024, 1024 * 1024, 1024 * 1024, true));

        assertThat(entry.requestSnapshot.authoredExactRequestBytes)
                .isSameAs(entry.requestSnapshot.rawRequestSent);
        assertThat(entry.requestSnapshot.authoredRequest.exactHttpRequest.rawRequestBytes).isNull();
    }

    private static BurpTrafficSelection ownedSelection(byte[] raw, int encounterIndex) {
        return BurpTrafficSelection.fromOwnedBytes(raw, null, "api.example.test", 443,
                true, "Proxy", null, null, encounterIndex);
    }

    private static byte[] validRequestOfSize(int totalSize) {
        byte[] header = requestBytes("POST /budget HTTP/1.1\r\nHost: api.example.test\r\n\r\n");
        if (totalSize < header.length) {
            throw new IllegalArgumentException("fixture too small");
        }
        byte[] raw = new byte[totalSize];
        System.arraycopy(header, 0, raw, 0, header.length);
        java.util.Arrays.fill(raw, header.length, raw.length, (byte) 'x');
        return raw;
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
