package burp.history;

import burp.testsupport.HistoryTestFixtures;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryJsonExportServiceTest {

    @Test
    void exportPreservesTemplateSnapshotAndMetadata() {
        HistoryJsonExportService service = new HistoryJsonExportService();
        String json = service.export(List.of(HistoryTestFixtures.sampleWorkbenchEntry()));

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertThat(root.get("version").getAsInt()).isEqualTo(1);
        assertThat(root.get("generatedAt").getAsString()).isNotBlank();

        JsonArray entries = root.getAsJsonArray("entries");
        assertThat(entries.size()).isEqualTo(1);
        JsonObject entry = entries.get(0).getAsJsonObject();
        assertThat(entry.get("requestSnapshot").getAsJsonObject().get("urlTemplate").getAsString())
                .isEqualTo("{{base_url}}/login");
        assertThat(entry.get("requestSnapshot").getAsJsonObject().get("headersAsAuthored").getAsJsonArray().size())
                .isGreaterThanOrEqualTo(1);
        assertThat(entry.get("responseSnapshot").getAsJsonObject().get("statusCode").getAsInt()).isEqualTo(200);
        assertThat(entry.get("responseSnapshot").getAsJsonObject().has("body")).isTrue();
    }
}
