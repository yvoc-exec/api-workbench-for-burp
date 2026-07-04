package burp.history;

import burp.models.ApiRequest;
import burp.models.RedirectHop;
import burp.models.RunnerResult;
import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryRedirectHopSnapshotTest {

    @Test
    void redirectHopStoresOnlyActualBody() {
        RunnerResult parent = parent();
        RedirectHop hop = redirectHop(
                "GET /redirect HTTP/1.1\r\nHost: api.example.test\r\nX-Test: one\r\n\r\nactual-body",
                true);

        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent, hop);

        assertThat(entry.requestSnapshot.bodyAsAuthored).isEqualTo("actual-body".getBytes(StandardCharsets.UTF_8));
        assertThat(entry.requestSnapshot.displayBodyText()).isEqualTo("actual-body");
        assertThat(entry.requestSnapshot.rawRequestSentText).contains("GET /redirect HTTP/1.1");
        assertThat(entry.requestSnapshot.rawRequestSentText).contains("Host: api.example.test");
    }

    @Test
    void duplicateHeadersAndOrderPreserved() {
        RunnerResult parent = parent();
        RedirectHop hop = redirectHop(
                "POST /redirect HTTP/1.1\r\nHost: api.example.test\r\nX-Trace: one\r\nX-Trace: two\r\nAccept: text/plain\r\n\r\nbody",
                true);

        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent, hop);

        assertThat(entry.requestSnapshot.headersAsAuthored).extracting(header -> header.name + "=" + header.value)
                .containsExactly("Host=api.example.test", "X-Trace=one", "X-Trace=two", "Accept=text/plain");
    }

    @Test
    void collectionIdAndNameAreDistinct() {
        RunnerResult parent = parent();
        parent.collectionId = "col-123";
        parent.collectionName = "Collection Name";

        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent, redirectHop(
                "PUT /redirect HTTP/1.1\r\nHost: api.example.test\r\n\r\nbody",
                true));

        assertThat(entry.collectionId).isEqualTo("col-123");
        assertThat(entry.collectionName).isEqualTo("Collection Name");
        assertThat(entry.collectionId).isNotEqualTo(entry.collectionName);
        assertThat(entry.requestSnapshot.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
    }

    @Test
    void malformedRawRequestKeepsRawEvidenceAndParseWarning() {
        RunnerResult parent = parent();
        RedirectHop hop = new RedirectHop();
        hop.sourceUrl = "https://api.example.test/malformed";
        hop.sourceMethod = "POST";
        hop.rawRequestBytes = "BROKEN".getBytes(StandardCharsets.UTF_8);
        hop.rawRequestText = "BROKEN";
        hop.followed = false;

        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent, hop);

        assertThat(entry.requestSnapshot.parseWarning).isNotBlank();
        assertThat(entry.requestSnapshot.headersAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.bodyAsAuthored).isEmpty();
        assertThat(entry.requestSnapshot.rawRequestSentText).contains("BROKEN");
    }

    @Test
    void redirectHopRequestLineAndMethodAreParsed() {
        RunnerResult parent = parent();
        RedirectHop hop = redirectHop(
                "PATCH /redirect HTTP/1.1\r\nHost: api.example.test\r\n\r\nbody",
                true);

        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent, hop);

        assertThat(entry.requestSnapshot.method).isEqualTo("PATCH");
        assertThat(entry.requestSnapshot.urlTemplate).isEqualTo("https://api.example.test/redirect");
        assertThat(entry.requestSnapshot.resolvedUrl).isEqualTo("https://api.example.test/redirect");
    }

    @Test
    void redirectHopBodyTruncationPreservesHeaders() {
        RunnerResult parent = parent();
        RedirectHop hop = redirectHop(
                "POST /redirect HTTP/1.1\r\nHost: api.example.test\r\nX-Test: one\r\n\r\nabcdefghij",
                true);

        HistoryEntry entry = HistoryEntry.fromRedirectHop(parent, hop);
        HistoryBodyTruncator.apply(entry, new HistoryRetentionPolicy(100, 1_000, 4, 1_000, true));

        assertThat(entry.requestSnapshot.rawRequestSentText).contains("Host: api.example.test");
        assertThat(entry.requestSnapshot.rawRequestSentText).contains("X-Test: one");
        assertThat(entry.requestSnapshot.rawRequestSentText).doesNotContain("efghij");
        assertThat(entry.requestSnapshot.rawBodyTruncated).isTrue();
        assertThat(entry.requestSnapshot.storedRawBodyLength).isEqualTo(4);
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

    private static RedirectHop redirectHop(String rawRequest, boolean followed) {
        RedirectHop hop = new RedirectHop();
        hop.hopNumber = 1;
        hop.sourceUrl = "https://api.example.test/redirect";
        hop.sourceMethod = "GET";
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
