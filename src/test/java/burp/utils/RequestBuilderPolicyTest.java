package burp.utils;

import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBuilderPolicyTest {

    private final RequestBuilder builder = new RequestBuilder(null);
    private final burp.parser.VariableResolver resolver = new burp.parser.VariableResolver();

    @Test
    void autoCompatibleAppliesBearerAuthWhenNotSuppressed() throws Exception {
        ApiRequest req = baseRequest();
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "tok123");
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;

        RawRequestParser parsed = parse(req);

        assertThat(parsed.headerValue("Authorization")).isEqualTo("Bearer tok123");
    }

    @Test
    void manualPreserveKeepsDeletedAuthorizationDeleted() throws Exception {
        ApiRequest req = baseRequest();
        req.editorMaterialized = true;
        req.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "tok123");
        req.suppressedAutoHeaders.add("authorization");

        RawRequestParser parsed = parse(req);

        assertThat(parsed.hasHeader("Authorization")).isFalse();
    }

    @Test
    void autoCompatibleSynthesizesJsonContentTypeWhenNotSuppressed() throws Exception {
        ApiRequest req = baseRequest();
        req.method = "POST";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "{\"key\":\"value\"}";
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;

        RawRequestParser parsed = parse(req);

        assertThat(parsed.headerValue("Content-Type")).isEqualTo("application/json");
    }

    @Test
    void autoCompatibleRespectsSuppressedDefaultHeaders() throws Exception {
        ApiRequest req = baseRequest();
        req.suppressedAutoHeaders.add("accept");
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;

        RawRequestParser parsed = parse(req);

        assertThat(parsed.hasHeader("Accept")).isFalse();
        assertThat(parsed.headerValue("User-Agent")).isEqualTo("BurpExtensionRuntime");
        assertThat(parsed.headerValue("Cache-Control")).isEqualTo("no-cache");
    }

    @Test
    void manualPreserveKeepsDeletedContentTypeDeleted() throws Exception {
        ApiRequest req = baseRequest();
        req.method = "POST";
        req.editorMaterialized = true;
        req.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "{\"key\":\"value\"}";
        req.suppressedAutoHeaders.add("content-type");

        RawRequestParser parsed = parse(req);

        assertThat(parsed.hasHeader("Content-Type")).isFalse();
    }

    @Test
    void manualPreservePreservesConflictingContentType() throws Exception {
        ApiRequest req = baseRequest();
        req.method = "POST";
        req.editorMaterialized = true;
        req.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        req.headers.add(new ApiRequest.Header("Content-Type", "text/plain", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "{\"key\":\"value\"}";

        RawRequestParser parsed = parse(req);

        assertThat(parsed.headerValue("Content-Type")).isEqualTo("text/plain");
        assertThat(new String(parsed.body, StandardCharsets.UTF_8)).isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    void autoCompatibleStillComputesContentLength() throws Exception {
        ApiRequest req = baseRequest();
        req.method = "POST";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "hello";
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;

        RawRequestParser parsed = parse(req);

        assertThat(parsed.contentLength()).isEqualTo(5);
    }

    @Test
    void manualPreserveStillComputesContentLengthForNow() throws Exception {
        ApiRequest req = baseRequest();
        req.method = "POST";
        req.editorMaterialized = true;
        req.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "hello";

        RawRequestParser parsed = parse(req);

        assertThat(parsed.contentLength()).isEqualTo(5);
    }

    @Test
    void defaultModeStillSkipsDisabledHeaders() throws Exception {
        ApiRequest req = baseRequest();
        req.headers.add(new ApiRequest.Header("X-Enabled", "yes", false));
        req.headers.add(new ApiRequest.Header("X-Disabled", "no", true));

        RawRequestParser parsed = parse(req);

        assertThat(parsed.hasHeader("X-Enabled")).isTrue();
        assertThat(parsed.hasHeader("X-Disabled")).isFalse();
    }

    @Test
    void manualPreserveStillSkipsDisabledHeaders() throws Exception {
        ApiRequest req = baseRequest();
        req.editorMaterialized = true;
        req.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        req.headers.add(new ApiRequest.Header("X-Enabled", "yes", false));
        req.headers.add(new ApiRequest.Header("X-Disabled", "no", true));

        RawRequestParser parsed = parse(req);

        assertThat(parsed.hasHeader("X-Enabled")).isTrue();
        assertThat(parsed.hasHeader("X-Disabled")).isFalse();
    }

    private ApiRequest baseRequest() {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        return req;
    }

    private RawRequestParser parse(ApiRequest req) throws Exception {
        byte[] raw = builder.buildRequest(req, resolver);
        return RawRequestParser.parse(raw);
    }
}
