package burp.history;

import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryDiffServiceTest {

    @Test
    void diffHighlightsMetadataRequestAndResponseChanges() {
        HistoryEntry left = HistoryTestFixtures.sampleWorkbenchEntry();
        HistoryEntry right = HistoryEntry.copyOf(left);
        right.id = "history-diff-right";
        right.requestSnapshot.urlTemplate = "{{base_url}}/login/v2";
        right.requestSnapshot.headersAsAuthored.add(new HistoryHeader("X-Extra", "yes"));
        right.responseSnapshot.statusCode = 201;
        right.responseSnapshot.reasonPhrase = "Created";
        right.responseSnapshot.body = "{\"ok\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        right.durationMillis = 250L;
        right.errorMessage = "Changed";
        right.result = HistoryResult.FAILURE;

        HistoryDiffService service = new HistoryDiffService();
        String diff = service.diff(left, right);

        assertThat(diff).contains("=== Metadata ===");
        assertThat(diff).contains("URL Template");
        assertThat(diff).contains("{{base_url}}/login");
        assertThat(diff).contains("{{base_url}}/login/v2");
        assertThat(diff).contains("=== Response ===");
        assertThat(diff).contains("201");
        assertThat(diff).contains("Created");
    }
}
