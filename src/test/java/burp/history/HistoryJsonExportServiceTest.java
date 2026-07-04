package burp.history;

import burp.testsupport.HistoryTestFixtures;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryJsonExportServiceTest {

    @Test
    void jsonIncludesTruncationPinNotesAndTags() {
        HistoryJsonExportService service = new HistoryJsonExportService();
        HistoryEntry historyEntry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "json-entry", Instant.parse("2026-06-15T02:30:00Z"));
        historyEntry.pinned = true;
        historyEntry.analystNotes = "Reviewed";
        historyEntry.tags = new LinkedHashSet<>(List.of("Auth", "Evidence"));
        String json = service.export(List.of(historyEntry));

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertThat(root.get("version").getAsInt()).isEqualTo(2);
        assertThat(root.get("generatedAt").getAsString()).isNotBlank();

        JsonArray entries = root.getAsJsonArray("entries");
        assertThat(entries.size()).isEqualTo(1);
        JsonObject jsonEntry = entries.get(0).getAsJsonObject();
        assertThat(jsonEntry.get("pinned").getAsBoolean()).isTrue();
        assertThat(jsonEntry.get("analystNotes").getAsString()).isEqualTo("Reviewed");
        assertThat(jsonEntry.getAsJsonArray("tags")).extracting(item -> item.getAsString()).containsExactly("Auth", "Evidence");
        assertThat(jsonEntry.getAsJsonObject("requestSnapshot").get("bodyTruncated").getAsBoolean()).isFalse();
        assertThat(jsonEntry.getAsJsonObject("requestSnapshot").get("storedBodyLength").getAsLong()).isEqualTo(
                jsonEntry.getAsJsonObject("requestSnapshot").get("originalBodyLength").getAsLong());
        assertThat(jsonEntry.getAsJsonObject("requestSnapshot").get("fullBodySha256").getAsString()).isNotBlank();
        assertThat(jsonEntry.getAsJsonObject("requestSnapshot").get("fullRawBodySha256").getAsString()).isNotBlank();
        assertThat(jsonEntry.getAsJsonObject("requestSnapshot").get("parseWarning").getAsString()).isBlank();
        assertThat(jsonEntry.get("collectionId").getAsString()).isNotBlank();
        assertThat(jsonEntry.get("collectionName").getAsString()).isNotBlank();
        assertThat(jsonEntry.get("requestSnapshot").getAsJsonObject().get("urlTemplate").getAsString())
                .isEqualTo("{{base_url}}/login");
        assertThat(jsonEntry.get("requestSnapshot").getAsJsonObject().get("headersAsAuthored").getAsJsonArray().size())
                .isGreaterThanOrEqualTo(1);
        assertThat(jsonEntry.get("responseSnapshot").getAsJsonObject().get("statusCode").getAsInt()).isEqualTo(200);
        assertThat(jsonEntry.get("responseSnapshot").getAsJsonObject().has("body")).isTrue();
    }
}
