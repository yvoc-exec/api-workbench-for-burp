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
}
