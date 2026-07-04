package burp.history;

import burp.testsupport.HistoryTestFixtures;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryHarExportServiceTest {

    @Test
    void harIncludesAnalystCommentAndStructuredMetadata() {
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "har-metadata", Instant.parse("2026-06-15T02:40:00Z"));
        entry.pinned = true;
        entry.analystNotes = "Reviewed";
        entry.tags = new LinkedHashSet<>(List.of("Auth", "Evidence"));

        JsonObject root = JsonParser.parseString(new HistoryHarExportService().export(List.of(entry))).getAsJsonObject();
        JsonObject harEntry = root.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();

        assertThat(harEntry.get("comment").getAsString()).contains("Pinned: Yes");
        assertThat(harEntry.get("comment").getAsString()).contains("Reviewed");
        assertThat(harEntry.get("comment").getAsString()).contains("Auth");
        assertThat(harEntry.getAsJsonObject("_apiWorkbench").get("pinned").getAsBoolean()).isTrue();
        assertThat(harEntry.getAsJsonObject("_apiWorkbench").get("notes").getAsString()).isEqualTo("Reviewed");
        assertThat(harEntry.getAsJsonObject("_apiWorkbench").get("tags").getAsString()).contains("Auth", "Evidence");
    }

    @Test
    void harRemainsValidWhenBodiesAreTruncated() {
        HistoryEntry entry = truncatedEntry();

        JsonObject root = JsonParser.parseString(new HistoryHarExportService().export(List.of(entry))).getAsJsonObject();
        JsonObject harEntry = root.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();

        assertThat(harEntry.getAsJsonObject("request").getAsJsonObject("postData").get("_truncated").getAsBoolean()).isTrue();
        assertThat(harEntry.getAsJsonObject("response").getAsJsonObject("content").get("_truncated").getAsBoolean()).isTrue();
        assertThat(harEntry.getAsJsonObject("request").getAsJsonObject("postData").get("text").getAsString()).hasSize(4);
        assertThat(harEntry.getAsJsonObject("response").getAsJsonObject("content").get("text").getAsString()).hasSize(4);
    }

    @Test
    void harDoesNotClaimStoredTruncatedBodyIsComplete() {
        HistoryEntry entry = truncatedEntry();

        JsonObject root = JsonParser.parseString(new HistoryHarExportService().export(List.of(entry))).getAsJsonObject();
        JsonObject harEntry = root.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject postData = harEntry.getAsJsonObject("request").getAsJsonObject("postData");
        JsonObject content = harEntry.getAsJsonObject("response").getAsJsonObject("content");

        assertThat(postData.get("_originalBodyLength").getAsLong()).isGreaterThan(postData.get("_storedBodyLength").getAsLong());
        assertThat(content.get("_originalBodyLength").getAsLong()).isGreaterThan(content.get("_storedBodyLength").getAsLong());
        assertThat(postData.get("_truncationReason").getAsString()).isEqualTo("REQUEST_BODY_LIMIT");
        assertThat(content.get("_truncationReason").getAsString()).isEqualTo("RESPONSE_BODY_LIMIT");
        assertThat(postData.get("comment").getAsString()).contains("stored 4 of");
        assertThat(content.get("comment").getAsString()).contains("stored 4 of");
    }

    private static HistoryEntry truncatedEntry() {
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "har-truncated", Instant.parse("2026-06-15T02:41:00Z"));
        String uniqueSuffix = "HAR-FULL-BODY-SUFFIX";
        String body = "pre-" + uniqueSuffix;
        entry.requestSnapshot.bodyAsAuthored = body.getBytes(StandardCharsets.UTF_8);
        entry.requestSnapshot.authoredRequest.body.raw = body;
        entry.requestSnapshot.rawRequestSent = ("POST /login HTTP/1.1\r\nHost: api.example.test\r\nContent-Type: application/json\r\n\r\n" + body)
                .getBytes(StandardCharsets.UTF_8);
        entry.requestSnapshot.rawRequestSentText = new String(entry.requestSnapshot.rawRequestSent, StandardCharsets.UTF_8);
        entry.responseSnapshot.body = body.getBytes(StandardCharsets.UTF_8);
        entry.responseSnapshot.originalBodyLength = body.getBytes(StandardCharsets.UTF_8).length;
        entry.responseSnapshot.storedBodyLength = entry.responseSnapshot.originalBodyLength;
        entry.responseSnapshot.fullBodySha256 = HistoryBodyTruncator.sha256Hex(entry.responseSnapshot.body);
        HistoryBodyTruncator.apply(entry, new HistoryRetentionPolicy(100, 1_000_000, 4, 4, true));
        return entry;
    }
}
