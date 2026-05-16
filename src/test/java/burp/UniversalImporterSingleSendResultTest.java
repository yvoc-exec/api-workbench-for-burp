package burp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class UniversalImporterSingleSendResultTest {

    @Test
    void singleSendResultCarriesWorkbenchRenderFields() {
        UniversalImporter.SingleSendResult result = new UniversalImporter.SingleSendResult(
            null, null, "GET / HTTP/1.1", "https://example.com", 42L, null
        );

        assertThat(result.rawRequestText).isEqualTo("GET / HTTP/1.1");
        assertThat(result.resolvedUrl).isEqualTo("https://example.com");
        assertThat(result.elapsedMs).isEqualTo(42L);
        assertThat(result.errorMessage).isNull();
    }
}
