package burp.utils;

import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBuilderExactHttpVersionTest {
    @Test
    void invalidatedExactSnapshotRebuildsWithHttp10() throws Exception {
        ApiRequest request = exactRequest("HTTP/1.0", false);
        assertThat(firstLine(new RequestBuilder(null).buildRequest(request, null)))
                .isEqualTo("GET /path HTTP/1.0");
    }

    @Test
    void unsupportedExactVersionFallsBackToHttp11() throws Exception {
        for (String version : java.util.Arrays.asList("HTTP/2", "h2", "HTTP/1.0\r\nInjected: yes", "", null)) {
            ApiRequest request = exactRequest(version, false);
            String raw = new String(new RequestBuilder(null).buildRequest(request, null), StandardCharsets.UTF_8);
            assertThat(firstLine(raw.getBytes(StandardCharsets.UTF_8))).isEqualTo("GET /path HTTP/1.1");
            assertThat(raw).doesNotContain("Injected");
        }
    }

    @Test
    void pristineSnapshotStillReturnsOriginalBytes() throws Exception {
        ApiRequest request = exactRequest("HTTP/1.0", true);
        byte[] original = "OPTIONS * HTTP/1.0\r\nX-Dupe: a\r\nX-Dupe: b\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8);
        request.exactHttpRequest.rawRequestBytes = original;
        assertThat(new RequestBuilder(null).buildRequest(request, null)).isEqualTo(original);
    }

    private static ApiRequest exactRequest(String version, boolean pristine) {
        ApiRequest request = new ApiRequest();
        request.method = "GET";
        request.url = "https://example.test/path";
        request.headers.add(new ApiRequest.Header("Host", "example.test"));
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.exactHttpRequest = new ExactHttpRequestSnapshot();
        request.exactHttpRequest.httpVersion = version;
        request.exactHttpRequest.pristine = pristine;
        request.exactHttpRequest.rawRequestBytes = "GET /old HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        return request;
    }

    private static String firstLine(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8).split("\\r\\n", 2)[0];
    }
}
