package burp.utils;

import burp.models.ApiRequest;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestBuilderSecurityTest {

    @Test
    void crlfInHeaderNamesAndValuesIsNeutralizedBeforeRawRequestEmission() throws Exception {
        ApiRequest request = new ApiRequest();
        request.method = "GET";
        request.url = "https://api.example.test/profile";
        request.headers.add(new ApiRequest.Header("X-Test\r\nX-Evil", "safe\r\nInjected: yes", false));

        byte[] raw = new RequestBuilder(null).buildRequest(request, new VariableResolver());
        String text = new String(raw, StandardCharsets.UTF_8);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(text).doesNotContain("\r\nX-Evil:");
        assertThat(text).doesNotContain("\r\nInjected:");
        assertThat(parsed.hasHeader("X-Evil")).isFalse();
        assertThat(parsed.hasHeader("Injected")).isFalse();
        assertThat(parsed.hasHeader("X-Test  X-Evil")).isTrue();
    }

    @Test
    void unsafeUrlSchemesAreRejectedBeforeRequestTargetResolution() {
        assertThatThrownBy(() -> HttpUtils.parseTargetForRequest("javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported URL scheme");
        assertThatThrownBy(() -> HttpUtils.parseTargetForRequest("data:text/plain,hello"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported URL scheme");
        assertThatThrownBy(() -> HttpUtils.parseTargetForRequest("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported URL scheme");
        assertThatThrownBy(() -> HttpUtils.parseTargetForRequest("ftp://example.test/file"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported URL scheme");
    }
}
