package burp.history;

import burp.models.ApiRequest;
import burp.models.RedirectHop;
import burp.models.RunnerResult;
import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryRedirectHopSnapshotTest {

    @Test
    void malformedRequestLineKeepsRawEvidenceButNoParsedHeadersOrBody() {
        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent(), redirectHop(
                "BROKEN REQUEST\r\nHost: api.example.test\r\n\r\nbody",
                "POST",
                true
        ));

        assertThat(entry.requestSnapshot.method).isEqualTo("POST");
        assertThat(entry.requestSnapshot.headersAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.bodyAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.rawRequestSentText).isEqualTo("BROKEN REQUEST\r\nHost: api.example.test\r\n\r\nbody");
        assertThat(entry.requestSnapshot.parseWarning).isEqualTo("MALFORMED_HTTP_REQUEST_LINE");
    }

    @Test
    void malformedHeaderLineDoesNotExposePartialParsedEvidence() {
        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent(), redirectHop(
                "GET /redirect HTTP/1.1\r\nHost api.example.test\r\nX-Trace: one\r\n\r\nbody",
                "GET",
                true
        ));

        assertThat(entry.requestSnapshot.headersAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.bodyAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.parseWarning).isEqualTo("MALFORMED_HTTP_HEADER");
        assertThat(entry.requestSnapshot.rawRequestSentText).contains("X-Trace: one");
    }

    @Test
    void missingSeparatorDoesNotExposeParsedBody() {
        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent(), redirectHop(
                "GET /redirect HTTP/1.1\r\nHost: api.example.test\r\nbody-without-separator",
                "GET",
                true
        ));

        assertThat(entry.requestSnapshot.headersAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.bodyAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.parseWarning).isEqualTo("MISSING_HEADER_BODY_SEPARATOR");
        assertThat(entry.requestSnapshot.rawRequestSentText).contains("body-without-separator");
    }

    @Test
    void rejectsWhitespaceBeforeHeaderColon() {
        String rawRequest = "GET / HTTP/1.1\r\nHost : api.example.test\r\n\r\nbody";
        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent(), redirectHop(rawRequest, "POST", true));

        assertThat(entry.requestSnapshot.method).isEqualTo("POST");
        assertThat(entry.requestSnapshot.headersAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.bodyAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.parseWarning).isEqualTo("MALFORMED_HTTP_HEADER");
        assertThat(entry.requestSnapshot.rawRequestSent).isNotNull();
        assertThat(entry.requestSnapshot.rawRequestSentText).isEqualTo(rawRequest);
    }

    @Test
    void rejectsLeadingWhitespaceInHeaderName() {
        String rawRequest = "GET / HTTP/1.1\r\n Host: api.example.test\r\n\r\nbody";
        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent(), redirectHop(rawRequest, "POST", true));

        assertThat(entry.requestSnapshot.method).isEqualTo("POST");
        assertThat(entry.requestSnapshot.headersAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.bodyAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.parseWarning).isEqualTo("MALFORMED_HTTP_HEADER");
        assertThat(entry.requestSnapshot.rawRequestSent).isNotNull();
        assertThat(entry.requestSnapshot.rawRequestSentText).isEqualTo(rawRequest);
    }

    @Test
    void rejectsTabBeforeHeaderColon() {
        String rawRequest = "GET / HTTP/1.1\r\nHost\t: api.example.test\r\n\r\nbody";
        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent(), redirectHop(rawRequest, "POST", true));

        assertThat(entry.requestSnapshot.method).isEqualTo("POST");
        assertThat(entry.requestSnapshot.headersAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.bodyAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.parseWarning).isEqualTo("MALFORMED_HTTP_HEADER");
        assertThat(entry.requestSnapshot.rawRequestSent).isNotNull();
        assertThat(entry.requestSnapshot.rawRequestSentText).isEqualTo(rawRequest);
    }

    @Test
    void allowsWhitespaceAfterHeaderColon() {
        String rawRequest = "GET / HTTP/1.1\r\nHost:     api.example.test\r\n\r\nbody";
        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent(), redirectHop(rawRequest, "POST", true));

        assertThat(entry.requestSnapshot.method).isEqualTo("GET");
        assertThat(entry.requestSnapshot.headersAsAuthored).extracting(header -> header.name + "=" + header.value)
                .containsExactly("Host=api.example.test");
        assertThat(entry.requestSnapshot.bodyAsAuthored).isEqualTo("body".getBytes(StandardCharsets.UTF_8));
        assertThat(entry.requestSnapshot.parseWarning).isBlank();
    }

    @Test
    void malformedHeaderAfterValidHeaderExposesNoPartialSemanticEvidence() {
        String rawRequest = "GET / HTTP/1.1\r\nHost: api.example.test\r\nX-Valid: one\r\nBad Header: value\r\n\r\nbody";
        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent(), redirectHop(rawRequest, "POST", true));

        assertThat(entry.requestSnapshot.method).isEqualTo("POST");
        assertThat(entry.requestSnapshot.headersAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.bodyAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.parseWarning).isEqualTo("MALFORMED_HTTP_HEADER");
        assertThat(entry.requestSnapshot.rawRequestSent).isNotNull();
        assertThat(entry.requestSnapshot.rawRequestSentText).isEqualTo(rawRequest);
    }

    @Test
    void rawBytesTakePrecedenceOverConflictingRawText() {
        RedirectHop hop = redirectHop(
                "POST /redirect HTTP/1.1\r\nHost: api.example.test\r\n\r\nbody",
                "GET",
                true
        );
        hop.rawRequestText = "DELETE /evil HTTP/1.1\r\nHost: attacker.example.test\r\n\r\nnope";

        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent(), hop);

        assertThat(entry.requestSnapshot.method).isEqualTo("POST");
        assertThat(entry.requestSnapshot.rawRequestSentText).contains("POST /redirect HTTP/1.1");
        assertThat(entry.requestSnapshot.rawRequestSentText).doesNotContain("DELETE /evil");
        assertThat(entry.requestSnapshot.headersAsAuthored).extracting(header -> header.name + "=" + header.value)
                .containsExactly("Host=api.example.test");
    }

    @Test
    void validRequestStillPreservesDuplicateHeaderOrder() {
        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent(), redirectHop(
                "POST /redirect HTTP/1.1\r\nHost: api.example.test\r\nX-Trace: one\r\nX-Trace: two\r\nAccept: text/plain\r\n\r\nbody",
                "POST",
                true
        ));

        assertThat(entry.requestSnapshot.headersAsAuthored).extracting(header -> header.name + "=" + header.value)
                .containsExactly("Host=api.example.test", "X-Trace=one", "X-Trace=two", "Accept=text/plain");
        assertThat(entry.requestSnapshot.bodyAsAuthored).isEqualTo("body".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validCustomHttpMethodStillParses() {
        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent(), redirectHop(
                "PURGE-CACHE /redirect HTTP/1.1\r\nHost: api.example.test\r\n\r\nbody",
                "GET",
                true
        ));

        assertThat(entry.requestSnapshot.method).isEqualTo("PURGE-CACHE");
        assertThat(entry.requestSnapshot.parseWarning).isBlank();
    }

    @Test
    void malformedRawEvidenceStillReceivesLimit() {
        RedirectHop hop = redirectHop(
                "BROKEN-EVIDENCE-SHOULD-BE-TRUNCATED",
                "POST",
                false
        );

        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent(), hop);
        HistoryBodyTruncator.apply(entry, new HistoryRetentionPolicy(100, 10_000, 8, 1_000, true));

        assertThat(entry.requestSnapshot.rawBodyTruncated).isTrue();
        assertThat(entry.requestSnapshot.rawTruncationReason).isEqualTo(HistoryBodyTruncator.RAW_REQUEST_EVIDENCE_LIMIT_REASON);
        assertThat(entry.requestSnapshot.originalRawBodyLength)
                .isEqualTo("BROKEN-EVIDENCE-SHOULD-BE-TRUNCATED".getBytes(StandardCharsets.UTF_8).length);
        assertThat(entry.requestSnapshot.rawRequestSent).hasSize(8);
        assertThat(entry.requestSnapshot.rawRequestSentText).doesNotContain("TRUNCATED");
    }

    @Test
    void redirectRequestTruncationAppearsInMetadata() {
        HistoryEntry entry = metadataEntry(redirectHop(1, "abcdefghij", "ok"));
        HistoryBodyTruncator.apply(entry, new HistoryRetentionPolicy(100, 10_000, 4, 5, true));

        String metadata = entry.toMetadataText();
        String expectedHash = HistoryBodyTruncator.sha256Hex("abcdefghij".getBytes(StandardCharsets.UTF_8));

        assertThat(metadata).contains("Redirect hop 1 raw request truncated");
        assertThat(metadata).contains("stored 4 of 10 bytes; SHA-256=" + expectedHash + "; reason=" + HistoryBodyTruncator.RAW_REQUEST_BODY_LIMIT_REASON);
        assertThat(metadata).doesNotContain("response body truncated");
    }

    @Test
    void redirectResponseTruncationAppearsInMetadata() {
        HistoryEntry entry = metadataEntry(redirectHop(1, "abc", "uvwxyz1234"));
        HistoryBodyTruncator.apply(entry, new HistoryRetentionPolicy(100, 10_000, 4, 5, true));

        String metadata = entry.toMetadataText();
        String expectedHash = HistoryBodyTruncator.sha256Hex("uvwxyz1234".getBytes(StandardCharsets.UTF_8));

        assertThat(metadata).contains("Redirect hop 1 response body truncated");
        assertThat(metadata).contains("stored 5 of 10 bytes; SHA-256=" + expectedHash + "; reason=" + HistoryBodyTruncator.RESPONSE_BODY_LIMIT_REASON);
        assertThat(metadata).doesNotContain("raw request truncated");
    }

    @Test
    void multipleRedirectHopSummariesRemainInHopOrder() {
        HistoryEntry entry = metadataEntry(
                redirectHop(1, "abcdefghij", "uvwxyz1234"),
                redirectHop(2, "klmnopqrst", "fedcba9876")
        );
        HistoryBodyTruncator.apply(entry, new HistoryRetentionPolicy(100, 10_000, 4, 5, true));

        String metadata = entry.toMetadataText();
        int first = metadata.indexOf("Redirect hop 1 raw request truncated");
        int second = metadata.indexOf("Redirect hop 2 raw request truncated");

        assertThat(first).isGreaterThanOrEqualTo(0);
        assertThat(second).isGreaterThanOrEqualTo(0);
        assertThat(first).isLessThan(second);
    }

    @Test
    void untruncatedRedirectHopAddsNoTruncationSummary() {
        HistoryEntry entry = metadataEntry(redirectHop(1, "abc", "ok"));
        HistoryBodyTruncator.apply(entry, new HistoryRetentionPolicy(100, 10_000, 4, 5, true));

        String metadata = entry.toMetadataText();

        assertThat(metadata).doesNotContain("raw request truncated");
        assertThat(metadata).doesNotContain("response body truncated");
    }

    @Test
    void metadataUsesOriginalHashAndLength() {
        HistoryEntry entry = metadataEntry(redirectHop(1, "abcdefghij", "uvwxyz1234"));
        HistoryBodyTruncator.apply(entry, new HistoryRetentionPolicy(100, 10_000, 4, 5, true));

        String metadata = entry.toMetadataText();
        String originalRequestHash = HistoryBodyTruncator.sha256Hex("abcdefghij".getBytes(StandardCharsets.UTF_8));
        String prefixRequestHash = HistoryBodyTruncator.sha256Hex("abcd".getBytes(StandardCharsets.UTF_8));
        String originalResponseHash = HistoryBodyTruncator.sha256Hex("uvwxyz1234".getBytes(StandardCharsets.UTF_8));
        String prefixResponseHash = HistoryBodyTruncator.sha256Hex("uvwxy".getBytes(StandardCharsets.UTF_8));

        assertThat(metadata).contains("Redirect hop 1 raw request truncated: stored 4 of 10 bytes; SHA-256=" + originalRequestHash + "; reason=" + HistoryBodyTruncator.RAW_REQUEST_BODY_LIMIT_REASON);
        assertThat(metadata).contains("Redirect hop 1 response body truncated: stored 5 of 10 bytes; SHA-256=" + originalResponseHash + "; reason=" + HistoryBodyTruncator.RESPONSE_BODY_LIMIT_REASON);
        assertThat(metadata).doesNotContain(prefixRequestHash);
        assertThat(metadata).doesNotContain(prefixResponseHash);
    }

    @Test
    void collectionIdAndNameRemainDistinct() {
        RunnerResult parent = parent();
        parent.collectionId = "col-123";
        parent.collectionName = "Collection Name";

        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent, redirectHop(
                "PUT /redirect HTTP/1.1\r\nHost: api.example.test\r\n\r\nbody",
                "PUT",
                true
        ));

        assertThat(entry.collectionId).isEqualTo("col-123");
        assertThat(entry.collectionName).isEqualTo("Collection Name");
        assertThat(entry.requestSnapshot.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
    }

    private static RunnerResult parent() {
        RunnerResult result = HistoryTestFixtures.sampleRunnerResult();
        result.collectionId = "col-collection";
        result.collectionName = "Collection Name";
        result.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        result.requestUrl = "https://api.example.test/redirect";
        result.requestName = "Redirect Request";
        result.requestId = "req-redirect";
        result.folderPath = "Folder";
        result.attemptNumber = 1;
        result.totalAttempts = 1;
        return result;
    }

    private static RedirectHop redirectHop(String rawRequest, String sourceMethod, boolean followed) {
        RedirectHop hop = new RedirectHop();
        hop.hopNumber = 1;
        hop.sourceUrl = "https://api.example.test/redirect";
        hop.sourceMethod = sourceMethod;
        hop.statusCode = 302;
        hop.location = "https://api.example.test/next";
        hop.targetUrl = "https://api.example.test/next";
        hop.targetMethod = "GET";
        hop.elapsedMs = 42L;
        hop.rawRequestBytes = rawRequest.getBytes(StandardCharsets.UTF_8);
        hop.rawRequestText = rawRequest;
        hop.responseHeadersText = "HTTP/1.1 302 Found\r\nLocation: https://api.example.test/next";
        hop.responseBody = "redirect".getBytes(StandardCharsets.UTF_8);
        hop.followed = followed;
        hop.failureReason = followed ? "" : "redirect blocked";
        hop.forwardedSensitiveHeaderNames = List.of("Authorization");
        hop.strippedSensitiveHeaderNames = List.of("Cookie");
        return hop;
    }

    private static RedirectHop redirectHop(int hopNumber, String requestBody, String responseBody) {
        RedirectHop hop = new RedirectHop();
        hop.hopNumber = hopNumber;
        hop.sourceUrl = "https://api.example.test/redirect-hop-" + hopNumber;
        hop.sourceMethod = "POST";
        hop.statusCode = 302;
        hop.location = "https://api.example.test/next-" + hopNumber;
        hop.targetUrl = "https://api.example.test/next-" + hopNumber;
        hop.targetMethod = "GET";
        hop.elapsedMs = 42L;
        hop.rawRequestBytes = ("POST /redirect-hop-" + hopNumber + " HTTP/1.1\r\nHost: api.example.test\r\nContent-Type: text/plain\r\n\r\n" + requestBody)
                .getBytes(StandardCharsets.UTF_8);
        hop.rawRequestText = new String(hop.rawRequestBytes, StandardCharsets.UTF_8);
        hop.responseHeadersText = "HTTP/1.1 302 Found\r\nContent-Type: text/plain\r\nLocation: https://api.example.test/next-" + hopNumber;
        hop.responseBody = responseBody.getBytes(StandardCharsets.UTF_8);
        hop.followed = true;
        hop.failureReason = "";
        hop.forwardedSensitiveHeaderNames = List.of("Authorization");
        hop.strippedSensitiveHeaderNames = List.of("Cookie");
        return hop;
    }

    private static HistoryEntry metadataEntry(RedirectHop... hops) {
        HistoryEntry entry = new HistoryEntry();
        entry.metadataSummaryText = "Base";
        entry.requestSnapshot = new HistoryRequestSnapshot();
        entry.requestSnapshot.bodyMode = "raw";
        entry.requestSnapshot.bodyAsAuthored = new byte[0];
        entry.requestSnapshot.headersAsAuthored = new java.util.ArrayList<>();
        entry.responseSnapshot = new HistoryResponseSnapshot();
        entry.responseSnapshot.body = new byte[0];
        entry.redirectHops = List.of(hops);
        entry.ensureDefaults();
        return entry;
    }
}
