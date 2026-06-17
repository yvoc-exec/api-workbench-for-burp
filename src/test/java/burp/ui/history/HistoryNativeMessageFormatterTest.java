package burp.ui.history;

import burp.history.HistoryEntry;
import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryNativeMessageFormatterTest {

    @Test
    void requestMessagePrefersRawSentRequestWhenAvailable() {
        HistoryEntry entry = HistoryTestFixtures.sampleWorkbenchEntry();

        String message = HistoryNativeMessageFormatter.requestMessage(entry);

        assertThat(message).contains("POST /login HTTP/1.1");
        assertThat(message).contains("Host: api.example.test");
        assertThat(message).contains("Authorization: Bearer {{token}}");
    }

    @Test
    void requestMessageFallsBackToAuthoredTemplateWhenRawRequestUnavailable() {
        HistoryEntry entry = HistoryTestFixtures.sampleWorkbenchEntry();
        entry.requestSnapshot.rawRequestSent = null;
        entry.requestSnapshot.rawRequestSentText = null;

        String message = HistoryNativeMessageFormatter.requestMessage(entry);

        assertThat(message).contains("POST {{base_url}}/login HTTP/1.1");
        assertThat(message).contains("Authorization: Bearer {{token}}");
        assertThat(message).doesNotContain("Host: api.example.test");
    }
}
