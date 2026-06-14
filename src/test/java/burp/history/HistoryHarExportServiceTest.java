package burp.history;

import burp.testsupport.HistoryTestFixtures;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryHarExportServiceTest {

    @Test
    void exportUsesTemplatedValuesOnly() {
        HistoryHarExportService service = new HistoryHarExportService();
        String har = service.export(List.of(HistoryTestFixtures.sampleWorkbenchEntry()));

        JsonObject root = JsonParser.parseString(har).getAsJsonObject();
        JsonObject log = root.getAsJsonObject("log");
        assertThat(log.getAsJsonObject("creator").get("name").getAsString()).isEqualTo("API Workbench for Burp");
        assertThat(log.getAsJsonObject("creator").get("version").getAsString()).isEqualTo("2.0.0");

        JsonObject entry = log.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertThat(entry.getAsJsonObject("request").get("url").getAsString()).isEqualTo("{{base_url}}/login");
        assertThat(entry.getAsJsonObject("request").getAsJsonObject("postData").get("text").getAsString())
                .contains("{{password}}");
        assertThat(entry.getAsJsonObject("response").getAsJsonObject("content").get("text").getAsString())
                .isEqualTo("{\"ok\":true}");
    }
}
