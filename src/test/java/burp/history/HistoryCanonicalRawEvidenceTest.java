package burp.history;

import burp.models.RedirectHop;
import burp.models.WorkspaceState;
import burp.testsupport.HistoryTestFixtures;
import burp.utils.WorkspaceStateJson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryCanonicalRawEvidenceTest {
    @Test
    void bytesAreAuthoritativeAndTextIsDerivedOnlyOnDemand() {
        String visible = "POST /items HTTP/1.1\r\nHost: api.example.test\r\n\r\nhello";
        HistoryRequestSnapshot source = new HistoryRequestSnapshot();
        source.rawRequestSent = visible.getBytes(StandardCharsets.UTF_8);
        source.rawRequestSentText = visible;

        HistoryRequestSnapshot copy = HistoryRequestSnapshot.copyOf(source);

        assertThat(copy.rawRequestSent).containsExactly(source.rawRequestSent);
        assertThat(copy.rawRequestSentText).isNull();
        assertThat(copy.preferredRawRequestText()).isEqualTo(visible);
        long canonicalSize = copy.approximateSizeBytes();
        copy.rawRequestSentText = visible;
        assertThat(copy.approximateSizeBytes()).isEqualTo(canonicalSize);
    }

    @Test
    void binaryBytesRemainExactAndCopyDoesNotManufactureText() {
        byte[] binary = new byte[]{'P', 'O', 'S', 'T', ' ', '/', ' ', 'H', 'T', 'T', 'P', '/', '1', '.', '1', '\r', '\n', '\r', '\n', 0, (byte) 0xff, 1};
        HistoryRequestSnapshot source = new HistoryRequestSnapshot();
        source.rawRequestSent = binary.clone();
        source.rawRequestSentText = "not-authoritative";

        HistoryRequestSnapshot copy = HistoryRequestSnapshot.copyOf(source);

        assertThat(copy.rawRequestSent).containsExactly(binary);
        assertThat(copy.rawRequestSentText).isNull();
    }

    @Test
    void legacyTextPromotesOnlyWhenUtf8RoundTripIsLossless() {
        HistoryRequestSnapshot textual = new HistoryRequestSnapshot();
        textual.rawRequestSentText = "GET /legacy/é HTTP/1.1\r\n\r\n";
        textual.canonicalizeRawEvidence();
        assertThat(textual.rawRequestSent).isEqualTo("GET /legacy/é HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(textual.rawRequestSentText).isNull();

        HistoryRequestSnapshot malformed = new HistoryRequestSnapshot();
        malformed.rawRequestSentText = "GET /legacy/\uD800 HTTP/1.1\r\n\r\n";
        malformed.canonicalizeRawEvidence();
        assertThat(malformed.rawRequestSent).isNull();
        assertThat(malformed.preferredRawRequestText()).isEqualTo("GET /legacy/\uD800 HTTP/1.1\r\n\r\n");
    }

    @Test
    void redirectUsesTheSameCanonicalRuleAndIsIdempotent() {
        byte[] raw = new byte[]{'G', 'E', 'T', ' ', '/', ' ', 'H', 'T', 'T', 'P', '/', '1', '.', '1', '\r', '\n', '\r', '\n', (byte) 0xfe};
        RedirectHop hop = new RedirectHop();
        hop.rawRequestBytes = raw.clone();
        hop.rawRequestText = "duplicate";

        hop.canonicalizeRawEvidence();
        hop.canonicalizeRawEvidence();
        RedirectHop copy = RedirectHop.copyOf(hop);

        assertThat(copy.rawRequestBytes).containsExactly(raw);
        assertThat(copy.rawRequestText).isNull();
    }

    @Test
    void truncationCanonicalizesRawEvidenceAndRemainsIdempotent() {
        HistoryEntry entry = HistoryEntry.copyOf(HistoryTestFixtures.sampleWorkbenchEntry());
        byte[] raw = "POST /large HTTP/1.1\r\nHost: api.example.test\r\n\r\nabcdefghij".getBytes(StandardCharsets.UTF_8);
        entry.requestSnapshot.rawRequestSent = raw;
        entry.requestSnapshot.rawRequestSentText = new String(raw, StandardCharsets.UTF_8);
        HistoryRetentionPolicy policy = new HistoryRetentionPolicy(10, 1024 * 1024, 4, 1024, true);

        HistoryBodyTruncator.apply(entry, policy);
        byte[] once = entry.requestSnapshot.rawRequestSent.clone();
        String hash = entry.requestSnapshot.fullRawBodySha256;
        long original = entry.requestSnapshot.originalRawBodyLength;
        HistoryBodyTruncator.apply(entry, policy);

        assertThat(entry.requestSnapshot.rawRequestSentText).isNull();
        assertThat(entry.requestSnapshot.rawRequestSent).containsExactly(once);
        assertThat(entry.requestSnapshot.fullRawBodySha256).isEqualTo(hash);
        assertThat(entry.requestSnapshot.originalRawBodyLength).isEqualTo(original).isEqualTo(10);
        assertThat(entry.requestSnapshot.storedRawBodyLength).isEqualTo(4);
        assertThat(entry.requestSnapshot.rawTruncationReason).isEqualTo(HistoryBodyTruncator.RAW_REQUEST_BODY_LIMIT_REASON);
    }

    @Test
    void currentWorkspaceSerializesOnlyCanonicalRawFormsWithoutSchemaChange() {
        HistoryEntry entry = HistoryEntry.copyOf(HistoryTestFixtures.sampleWorkbenchEntry());
        String raw = "GET /workspace HTTP/1.1\r\nHost: api.example.test\r\n\r\n";
        entry.requestSnapshot.rawRequestSent = raw.getBytes(StandardCharsets.UTF_8);
        entry.requestSnapshot.rawRequestSentText = raw;
        RedirectHop hop = new RedirectHop();
        hop.rawRequestBytes = raw.getBytes(StandardCharsets.UTF_8);
        hop.rawRequestText = raw;
        entry.redirectHops = List.of(hop);
        WorkspaceState state = new WorkspaceState();
        state.historyRetentionPolicyVersion = HistoryRetentionPolicy.CURRENT_POLICY_VERSION;
        state.historyEntries = List.of(entry);

        String json = WorkspaceStateJson.toJson(state);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject savedEntry = root.getAsJsonArray("historyEntries").get(0).getAsJsonObject();
        JsonObject savedRequest = savedEntry.getAsJsonObject("requestSnapshot");
        JsonObject savedHop = savedEntry.getAsJsonArray("redirectHops").get(0).getAsJsonObject();

        assertThat(root.get("version").getAsInt()).isEqualTo(WorkspaceState.CURRENT_VERSION);
        assertThat(savedRequest.has("rawRequestSent")).isTrue();
        assertThat(savedRequest.has("rawRequestSentText")).isFalse();
        assertThat(savedHop.has("rawRequestBytes")).isTrue();
        assertThat(savedHop.has("rawRequestText")).isFalse();
        HistoryEntry restored = WorkspaceStateJson.fromJson(json).historyEntries.get(0);
        assertThat(restored.requestSnapshot.preferredRawRequestText()).isEqualTo(raw);
    }
}
