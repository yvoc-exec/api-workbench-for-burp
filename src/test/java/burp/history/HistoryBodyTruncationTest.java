package burp.history;

import burp.models.ApiRequest;
import burp.models.RedirectHop;
import burp.models.WorkspaceState;
import burp.utils.WorkspaceStateJson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryBodyTruncationTest {

    @Test
    void requestBodyIsTruncatedByBytes() {
        HistoryEntry entry = entryWithRequestAndResponse("truncate-request", "abcdefghij", "ok", "abc");
        HistoryBodyTruncator.apply(entry, policy(4, 100, 100));

        assertThat(entry.requestSnapshot.bodyTruncated).isTrue();
        assertThat(entry.requestSnapshot.originalBodyLength).isEqualTo(10);
        assertThat(entry.requestSnapshot.storedBodyLength).isEqualTo(4);
        assertThat(entry.requestSnapshot.displayBodyText()).isEqualTo("abcd");
    }

    @Test
    void rawRequestPreservesCompleteHeadersAndTruncatesOnlyBody() {
        HistoryEntry entry = entryWithRequestAndResponse("truncate-raw", "abcdefghij", "ok", "abc");
        entry.requestSnapshot.rawRequestSent = ("POST /submit HTTP/1.1\r\nHost: api.example.test\r\nX-Test: one\r\n\r\nabcdefghij")
                .getBytes(StandardCharsets.UTF_8);
        entry.requestSnapshot.rawRequestSentText = new String(entry.requestSnapshot.rawRequestSent, StandardCharsets.UTF_8);

        HistoryBodyTruncator.apply(entry, policy(4, 100, 100));

        assertThat(entry.requestSnapshot.rawBodyTruncated).isTrue();
        assertThat(entry.requestSnapshot.rawRequestSentText).contains("POST /submit HTTP/1.1");
        assertThat(entry.requestSnapshot.rawRequestSentText).contains("Host: api.example.test");
        assertThat(entry.requestSnapshot.rawRequestSentText).contains("X-Test: one");
        assertThat(entry.requestSnapshot.rawRequestSentText).doesNotContain("efghij");
        assertThat(entry.requestSnapshot.storedRawBodyLength).isEqualTo(4);
    }

    @Test
    void responseBodyIsTruncatedByBytes() {
        HistoryEntry entry = entryWithRequestAndResponse("truncate-response", "abc", "abcdefghij", "xyz");
        HistoryBodyTruncator.apply(entry, policy(4, 100, 4));

        assertThat(entry.responseSnapshot.bodyTruncated).isTrue();
        assertThat(entry.responseSnapshot.originalBodyLength).isEqualTo(10);
        assertThat(entry.responseSnapshot.storedBodyLength).isEqualTo(4);
        assertThat(entry.responseSnapshot.bodyAsText()).isEqualTo("abcd");
    }

    @Test
    void fullBodyHashMatchesOriginal() {
        HistoryEntry entry = entryWithRequestAndResponse("hash", "abcdefghij", "abcdefghij", "abc");
        String expected = HistoryBodyTruncator.sha256Hex("abcdefghij".getBytes(StandardCharsets.UTF_8));

        HistoryBodyTruncator.apply(entry, policy(4, 100, 100));

        assertThat(entry.requestSnapshot.fullBodySha256).isEqualTo(expected);
        assertThat(entry.responseSnapshot.fullBodySha256).isEqualTo(expected);
    }

    @Test
    void headersRemainComplete() {
        HistoryEntry entry = entryWithRequestAndResponse("headers", "abcdefghij", "ok", "abc");
        entry.requestSnapshot.rawRequestSent = ("PUT /submit HTTP/1.1\r\nHost: api.example.test\r\nAuthorization: Bearer token\r\nContent-Type: text/plain\r\n\r\nabcdefghij")
                .getBytes(StandardCharsets.UTF_8);
        entry.requestSnapshot.rawRequestSentText = new String(entry.requestSnapshot.rawRequestSent, StandardCharsets.UTF_8);

        HistoryBodyTruncator.apply(entry, policy(4, 100, 100));

        assertThat(entry.requestSnapshot.rawRequestSentText).contains("Host: api.example.test");
        assertThat(entry.requestSnapshot.rawRequestSentText).contains("Authorization: Bearer token");
        assertThat(entry.requestSnapshot.rawRequestSentText).contains("Content-Type: text/plain");
        assertThat(entry.requestSnapshot.rawRequestSentText).doesNotContain("efghij");
    }

    @Test
    void unicodeTruncationDoesNotUseCharacterCount() {
        HistoryEntry entry = entryWithRequestAndResponse("unicode", "😀é漢字", "ok", "abc");
        HistoryBodyTruncator.apply(entry, policy(5, 100, 100));

        assertThat(entry.requestSnapshot.bodyTruncated).isTrue();
        assertThat(entry.requestSnapshot.storedBodyLength).isEqualTo(5);
        assertThat(entry.requestSnapshot.bodyAsAuthored).hasSize(5);
    }

    @Test
    void truncationMetadataRoundTrips() {
        HistoryEntry entry = entryWithRequestAndResponse("roundtrip", "abcdefghij", "abcdefghij", "abc");
        entry.requestSnapshot.rawRequestSent = ("POST /roundtrip HTTP/1.1\r\nHost: api.example.test\r\n\r\nabcdefghij")
                .getBytes(StandardCharsets.UTF_8);
        entry.requestSnapshot.rawRequestSentText = new String(entry.requestSnapshot.rawRequestSent, StandardCharsets.UTF_8);
        HistoryBodyTruncator.apply(entry, policy(4, 100, 4));

        HistoryEntry copied = HistoryEntry.copyOf(entry);
        assertThat(copied.requestSnapshot.bodyTruncated).isTrue();
        assertThat(copied.requestSnapshot.rawBodyTruncated).isTrue();
        assertThat(copied.responseSnapshot.bodyTruncated).isTrue();
        assertThat(copied.requestSnapshot.fullBodySha256).isEqualTo(entry.requestSnapshot.fullBodySha256);

        HistoryJsonExportService jsonExport = new HistoryJsonExportService();
        JsonObject exported = JsonParser.parseString(jsonExport.export(List.of(entry))).getAsJsonObject();
        JsonObject exportedEntry = exported.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertThat(exportedEntry.getAsJsonObject("requestSnapshot").get("bodyTruncated").getAsBoolean()).isTrue();
        assertThat(exportedEntry.getAsJsonObject("requestSnapshot").get("rawBodyTruncated").getAsBoolean()).isTrue();
        assertThat(exportedEntry.getAsJsonObject("responseSnapshot").get("bodyTruncated").getAsBoolean()).isTrue();
    }

    @Test
    void truncationRemovesFullBodyFromDuplicateFields() {
        String suffix = "FULL-BODY-SUFFIX";
        HistoryEntry entry = entryWithRequestAndResponse("duplicate", "prefix-" + suffix, "prefix-" + suffix, "body");
        entry.requestSnapshot.rawRequestSent = ("POST /dup HTTP/1.1\r\nHost: api.example.test\r\nContent-Type: text/plain\r\n\r\nprefix-" + suffix)
                .getBytes(StandardCharsets.UTF_8);
        entry.requestSnapshot.rawRequestSentText = new String(entry.requestSnapshot.rawRequestSent, StandardCharsets.UTF_8);
        HistoryBodyTruncator.apply(entry, policy(8, 100, 100));

        String metadata = entry.toMetadataText();
        String requestText = entry.requestSnapshot.displayBodyText();
        String serializedRequest = entry.requestSnapshot.toApiRequest().body.raw;
        String exportedJson = new HistoryJsonExportService().export(List.of(entry));

        assertThat(metadata).doesNotContain(suffix);
        assertThat(requestText).doesNotContain(suffix);
        assertThat(serializedRequest).doesNotContain(suffix);
        assertThat(exportedJson).doesNotContain(suffix);
    }

    @Test
    void untruncatedBodyStillReceivesConsistentLengthAndHashMetadata() {
        HistoryEntry entry = entryWithRequestAndResponse("plain", "abcdefgh", "abcdefgh", "xyz");
        HistoryBodyTruncator.apply(entry, policy(64, 100, 100));

        assertThat(entry.requestSnapshot.bodyTruncated).isFalse();
        assertThat(entry.requestSnapshot.originalBodyLength).isEqualTo(8);
        assertThat(entry.requestSnapshot.storedBodyLength).isEqualTo(8);
        assertThat(entry.requestSnapshot.fullBodySha256).isEqualTo(
                HistoryBodyTruncator.sha256Hex("abcdefgh".getBytes(StandardCharsets.UTF_8)));
        assertThat(entry.responseSnapshot.bodyTruncated).isFalse();
        assertThat(entry.responseSnapshot.originalBodyLength).isEqualTo(8);
        assertThat(entry.responseSnapshot.storedBodyLength).isEqualTo(8);
    }

    @Test
    void redirectHopRequestAndResponseBodiesAreTruncated() {
        HistoryEntry entry = entryWithRequestAndResponse("redirect-hop-truncate", "ok", "ok", null);
        entry.redirectHops.add(redirectHop("abcdefghij", "uvwxyz1234"));

        HistoryBodyTruncator.apply(entry, policy(4, 10_000, 5));

        RedirectHop hop = entry.redirectHops.get(0);
        assertThat(hop.rawRequestBodyTruncated).isTrue();
        assertThat(hop.originalRawRequestBodyLength).isEqualTo(10L);
        assertThat(hop.storedRawRequestBodyLength).isEqualTo(4L);
        assertThat(hop.rawRequestText).contains("POST /redirect-hop HTTP/1.1");
        assertThat(hop.rawRequestText).doesNotContain("efghij");
        assertThat(hop.responseBodyTruncated).isTrue();
        assertThat(hop.originalResponseBodyLength).isEqualTo(10L);
        assertThat(hop.storedResponseBodyLength).isEqualTo(5L);
        assertThat(new String(hop.responseBody, StandardCharsets.UTF_8)).isEqualTo("uvwxy");
    }

    @Test
    void redirectHopHashesMatchCompleteOriginalBodies() {
        String requestBody = "abcdefghij";
        String responseBody = "uvwxyz1234";
        HistoryEntry entry = entryWithRequestAndResponse("redirect-hop-hash", "ok", "ok", null);
        entry.redirectHops.add(redirectHop(requestBody, responseBody));

        HistoryBodyTruncator.apply(entry, policy(4, 10_000, 5));

        RedirectHop hop = entry.redirectHops.get(0);
        assertThat(hop.fullRawRequestBodySha256)
                .isEqualTo(HistoryBodyTruncator.sha256Hex(requestBody.getBytes(StandardCharsets.UTF_8)));
        assertThat(hop.fullResponseBodySha256)
                .isEqualTo(HistoryBodyTruncator.sha256Hex(responseBody.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void redirectHopRepeatedTruncationPreservesOriginalEvidenceMetadata() {
        HistoryEntry entry = entryWithRequestAndResponse("redirect-hop-repeat", "ok", "ok", null);
        entry.redirectHops.add(redirectHop("abcdefghij", "uvwxyz1234"));

        HistoryBodyTruncator.apply(entry, policy(8, 10_000, 8));
        RedirectHop once = RedirectHop.copyOf(entry.redirectHops.get(0));

        HistoryBodyTruncator.apply(entry, policy(4, 10_000, 5));
        RedirectHop twice = entry.redirectHops.get(0);

        assertThat(twice.fullRawRequestBodySha256).isEqualTo(once.fullRawRequestBodySha256);
        assertThat(twice.originalRawRequestBodyLength).isEqualTo(10L);
        assertThat(twice.storedRawRequestBodyLength).isEqualTo(4L);
        assertThat(twice.fullResponseBodySha256).isEqualTo(once.fullResponseBodySha256);
        assertThat(twice.originalResponseBodyLength).isEqualTo(10L);
        assertThat(twice.storedResponseBodyLength).isEqualTo(5L);
    }

    @Test
    void redirectHopFullPayloadSuffixAbsentFromHistoryJson() {
        String requestSuffix = "REQ-SUFFIX-FULL";
        String responseSuffix = "RESP-SUFFIX-FULL";
        HistoryEntry entry = entryWithRequestAndResponse("redirect-hop-json", "ok", "ok", null);
        entry.redirectHops.add(redirectHop("prefix-" + requestSuffix, "prefix-" + responseSuffix));

        HistoryBodyTruncator.apply(entry, policy(6, 10_000, 6));

        RedirectHop hop = entry.redirectHops.get(0);
        String json = new HistoryJsonExportService().export(List.of(entry));
        assertThat(hop.rawRequestText).doesNotContain(requestSuffix);
        assertThat(new String(hop.rawRequestBytes, StandardCharsets.UTF_8)).doesNotContain(requestSuffix);
        assertThat(new String(hop.responseBody, StandardCharsets.UTF_8)).doesNotContain(responseSuffix);
        assertThat(json).doesNotContain(requestSuffix);
        assertThat(json).doesNotContain(responseSuffix);
        assertThat(hop.fullRawRequestBodySha256)
                .isEqualTo(HistoryBodyTruncator.sha256Hex(("prefix-" + requestSuffix).getBytes(StandardCharsets.UTF_8)));
        assertThat(hop.fullResponseBodySha256)
                .isEqualTo(HistoryBodyTruncator.sha256Hex(("prefix-" + responseSuffix).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void redirectHopFullPayloadSuffixAbsentFromWorkspaceJson() {
        String requestSuffix = "WORKSPACE-REQ-SUFFIX";
        String responseSuffix = "WORKSPACE-RESP-SUFFIX";
        HistoryEntry entry = entryWithRequestAndResponse("redirect-hop-workspace", "ok", "ok", null);
        entry.redirectHops.add(redirectHop("prefix-" + requestSuffix, "prefix-" + responseSuffix));

        HistoryBodyTruncator.apply(entry, policy(6, 10_000, 6));

        WorkspaceState state = new WorkspaceState();
        state.historyEntries.add(entry);
        String json = WorkspaceStateJson.toJson(state);

        assertThat(json).doesNotContain(requestSuffix);
        assertThat(json).doesNotContain(responseSuffix);
        assertThat(WorkspaceStateJson.fromJson(json).historyEntries.get(0).redirectHops.get(0).fullRawRequestBodySha256)
                .isEqualTo(entry.redirectHops.get(0).fullRawRequestBodySha256);
    }

    @Test
    void malformedRedirectRawEvidenceIsBounded() {
        HistoryEntry entry = entryWithRequestAndResponse("redirect-hop-malformed", "ok", "ok", null);
        RedirectHop hop = new RedirectHop();
        hop.hopNumber = 1;
        hop.sourceUrl = "https://api.example.test/malformed";
        hop.sourceMethod = "POST";
        hop.rawRequestBytes = "BROKEN-RAW-EVIDENCE-SUFFIX".getBytes(StandardCharsets.UTF_8);
        hop.rawRequestText = "BROKEN-RAW-EVIDENCE-SUFFIX";
        hop.responseBody = "response".getBytes(StandardCharsets.UTF_8);
        entry.redirectHops.add(hop);

        HistoryBodyTruncator.apply(entry, policy(8, 10_000, 8));

        RedirectHop stored = entry.redirectHops.get(0);
        assertThat(stored.rawRequestBodyTruncated).isTrue();
        assertThat(stored.rawRequestTruncationReason).isEqualTo(HistoryBodyTruncator.RAW_REQUEST_EVIDENCE_LIMIT_REASON);
        assertThat(stored.originalRawRequestBodyLength)
                .isEqualTo("BROKEN-RAW-EVIDENCE-SUFFIX".getBytes(StandardCharsets.UTF_8).length);
        assertThat(stored.storedRawRequestBodyLength).isEqualTo(8L);
        assertThat(stored.rawRequestText).doesNotContain("EVIDENCE-SUFFIX");
        assertThat(stored.rawRequestText).isEqualTo(new String(stored.rawRequestBytes, StandardCharsets.UTF_8));
    }

    private static HistoryRetentionPolicy policy(long requestLimit, long totalLimit, long responseLimit) {
        return new HistoryRetentionPolicy(100, totalLimit, requestLimit, responseLimit, true);
    }

    private static HistoryEntry entryWithRequestAndResponse(String id, String requestBody, String responseBody, String rawSuffix) {
        HistoryEntry entry = new HistoryEntry();
        entry.id = id;
        entry.timestamp = Instant.parse("2026-06-15T01:00:00Z");
        entry.source = HistorySource.RUNNER;
        entry.collectionId = "collection-id";
        entry.collectionName = "Collection";
        entry.requestId = "request-id";
        entry.requestName = "Request";
        entry.folderPath = "Folder";
        entry.result = HistoryResult.SUCCESS;
        entry.requestSnapshot = new HistoryRequestSnapshot();
        entry.requestSnapshot.method = "POST";
        entry.requestSnapshot.urlTemplate = "https://api.example.test/" + id;
        entry.requestSnapshot.bodyMode = "raw";
        entry.requestSnapshot.bodyAsAuthored = requestBody.getBytes(StandardCharsets.UTF_8);
        entry.requestSnapshot.authoredRequest = new ApiRequest();
        entry.requestSnapshot.authoredRequest.body = new ApiRequest.Body();
        entry.requestSnapshot.authoredRequest.body.mode = "raw";
        entry.requestSnapshot.authoredRequest.body.raw = requestBody;
        entry.requestSnapshot.headersAsAuthored = new ArrayList<>();
        entry.requestSnapshot.headersAsAuthored.add(new HistoryHeader("Host", "api.example.test", false));
        entry.requestSnapshot.headersAsAuthored.add(new HistoryHeader("Content-Type", "text/plain", false));
        entry.requestSnapshot.originalBodyLength = requestBody.getBytes(StandardCharsets.UTF_8).length;
        entry.requestSnapshot.storedBodyLength = entry.requestSnapshot.originalBodyLength;
        entry.requestSnapshot.fullBodySha256 = HistoryBodyTruncator.sha256Hex(entry.requestSnapshot.bodyAsAuthored);
        entry.requestSnapshot.rawRequestSentText = "";
        entry.requestSnapshot.rawRequestSent = null;
        entry.requestSnapshot.requestVariablesAsAuthored = new java.util.LinkedHashMap<>();
        entry.requestSnapshot.resolvedVariables = new java.util.LinkedHashMap<>();
        entry.responseSnapshot = new HistoryResponseSnapshot();
        entry.responseSnapshot.statusCode = 200;
        entry.responseSnapshot.reasonPhrase = "OK";
        entry.responseSnapshot.mimeType = "text/plain";
        entry.responseSnapshot.headers = new ArrayList<>();
        entry.responseSnapshot.headers.add(new HistoryHeader("Content-Type", "text/plain"));
        entry.responseSnapshot.body = responseBody.getBytes(StandardCharsets.UTF_8);
        entry.responseSnapshot.originalBodyLength = entry.responseSnapshot.body.length;
        entry.responseSnapshot.storedBodyLength = entry.responseSnapshot.originalBodyLength;
        entry.responseSnapshot.fullBodySha256 = HistoryBodyTruncator.sha256Hex(entry.responseSnapshot.body);
        entry.responseSnapshot.bodyTruncated = false;
        entry.responseSnapshot.truncationReason = "";
        entry.ensureDefaults();
        if (rawSuffix != null && !rawSuffix.isBlank()) {
            entry.requestSnapshot.rawRequestSent = ("POST /" + id + " HTTP/1.1\r\nHost: api.example.test\r\nContent-Type: text/plain\r\n\r\n" + requestBody)
                    .getBytes(StandardCharsets.UTF_8);
            entry.requestSnapshot.rawRequestSentText = new String(entry.requestSnapshot.rawRequestSent, StandardCharsets.UTF_8);
        }
        return entry;
    }

    private static RedirectHop redirectHop(String requestBody, String responseBody) {
        RedirectHop hop = new RedirectHop();
        hop.hopNumber = 1;
        hop.sourceUrl = "https://api.example.test/redirect-hop";
        hop.sourceMethod = "POST";
        hop.statusCode = 302;
        hop.location = "https://api.example.test/next";
        hop.targetUrl = "https://api.example.test/next";
        hop.targetMethod = "POST";
        hop.elapsedMs = 12L;
        hop.rawRequestBytes = ("POST /redirect-hop HTTP/1.1\r\nHost: api.example.test\r\nContent-Type: text/plain\r\n\r\n" + requestBody)
                .getBytes(StandardCharsets.UTF_8);
        hop.rawRequestText = new String(hop.rawRequestBytes, StandardCharsets.UTF_8);
        hop.responseHeadersText = "HTTP/1.1 302 Found\r\nContent-Type: text/plain\r\nLocation: https://api.example.test/next";
        hop.responseBody = responseBody.getBytes(StandardCharsets.UTF_8);
        hop.followed = true;
        return hop;
    }
}
