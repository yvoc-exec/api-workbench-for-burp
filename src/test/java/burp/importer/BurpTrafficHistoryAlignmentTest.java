package burp.importer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BurpTrafficHistoryAlignmentTest {
    @Test
    void mixedResponseAvailabilityKeepsHistoryAlignedWithRequestOrder() {
        BurpTrafficImportService service = new BurpTrafficImportService(
                Clock.fixed(Instant.parse("2026-07-05T02:00:00Z"), ZoneOffset.UTC));
        BurpTrafficSelection withoutResponse = selection(
                "GET /one HTTP/1.1\r\nHost: example.invalid\r\n\r\n",
                null,
                0);
        BurpTrafficSelection withResponse = selection(
                "GET /two HTTP/1.1\r\nHost: example.invalid\r\n\r\n",
                "HTTP/1.1 201 Created\r\nContent-Type: text/plain\r\n\r\ntwo",
                1);

        BurpTrafficConversionResult result = service.convert(List.of(withoutResponse, withResponse));

        assertThat(result.failures).isEmpty();
        assertThat(result.requests).hasSize(2);
        assertThat(result.historyEntries).hasSize(2);
        assertThat(result.historyEntries.get(0)).isNull();
        assertThat(result.historyEntries.get(1)).isNotNull();
        assertThat(result.historyEntries.get(1).requestId)
                .isEqualTo(result.requests.get(1).id);
        assertThat(result.historyEntries.get(1).requestName)
                .isEqualTo(result.requests.get(1).name);
        assertThat(result.historyEntries.get(1).statusCode).isEqualTo(201);
    }

    private static BurpTrafficSelection selection(String request, String response, int index) {
        return new BurpTrafficSelection(
                request.getBytes(StandardCharsets.ISO_8859_1),
                response != null ? response.getBytes(StandardCharsets.ISO_8859_1) : null,
                "example.invalid",
                443,
                true,
                "Proxy",
                null,
                null,
                index);
    }
}
