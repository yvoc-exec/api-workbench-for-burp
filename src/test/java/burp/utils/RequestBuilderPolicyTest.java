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
    void autoCompatibleContinuesRemovingStaleFramingHeaders() throws Exception {
        ApiRequest req = baseRequest();
        req.method = "POST";
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        req.headers.add(new ApiRequest.Header("Content-Length", "9999", false));
        req.headers.add(new ApiRequest.Header("Transfer-Encoding", "chunked", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "hello";

        String raw = requestText(req);

        assertThat(raw).contains("Content-Length: 5");
        assertThat(raw).doesNotContain("Content-Length: 9999");
        assertThat(raw).doesNotContain("Transfer-Encoding: chunked");
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
    void exactHttpPreservesCustomHostAndDuplicateHeadersInOrder() throws Exception {
        ApiRequest req = baseRequest();
        req.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        req.headers.add(new ApiRequest.Header("Host", "alt.example.test", false));
        req.headers.add(new ApiRequest.Header("X-Test", "one", false));
        req.headers.add(new ApiRequest.Header("X-Test", "two", false));
        req.headers.add(new ApiRequest.Header("X-Test", "three", false));

        String raw = requestText(req);

        assertThat(raw).contains("Host: alt.example.test");
        assertThat(raw).contains("X-Test: one");
        assertThat(raw).contains("X-Test: two");
        assertThat(raw).contains("X-Test: three");
        assertThat(raw.indexOf("X-Test: one")).isLessThan(raw.indexOf("X-Test: two"));
        assertThat(raw.indexOf("X-Test: two")).isLessThan(raw.indexOf("X-Test: three"));
        assertThat(raw).doesNotContain("Host: example.com");
    }

    @Test
    void exactHttpPreservesDuplicateAuthorizationAndCookieHeaders() throws Exception {
        ApiRequest req = baseRequest();
        req.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        req.headers.add(new ApiRequest.Header("Authorization", "Bearer first", false));
        req.headers.add(new ApiRequest.Header("Cookie", "a=1", false));
        req.headers.add(new ApiRequest.Header("Authorization", "Bearer second", false));
        req.headers.add(new ApiRequest.Header("Cookie", "b=2", false));

        String raw = requestText(req);

        assertThat(raw).contains("Authorization: Bearer first");
        assertThat(raw).contains("Authorization: Bearer second");
        assertThat(raw).contains("Cookie: a=1");
        assertThat(raw).contains("Cookie: b=2");
        assertThat(raw.indexOf("Authorization: Bearer first")).isLessThan(raw.indexOf("Authorization: Bearer second"));
        assertThat(raw.indexOf("Cookie: a=1")).isLessThan(raw.indexOf("Cookie: b=2"));
    }

    @Test
    void exactHttpPreservesContentLengthTransferEncodingAndConnectionHeadersWithoutRecomputingLength() throws Exception {
        ApiRequest req = baseRequest();
        req.method = "POST";
        req.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        req.headers.add(new ApiRequest.Header("Content-Length", "9999", false));
        req.headers.add(new ApiRequest.Header("Transfer-Encoding", "chunked", false));
        req.headers.add(new ApiRequest.Header("Connection", "keep-alive", false));
        req.headers.add(new ApiRequest.Header("Proxy-Connection", "keep-alive", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "hello";

        String raw = requestText(req);

        assertThat(raw).contains("Content-Length: 9999");
        assertThat(raw).contains("Transfer-Encoding: chunked");
        assertThat(raw).contains("Connection: keep-alive");
        assertThat(raw).contains("Proxy-Connection: keep-alive");
        assertThat(raw).doesNotContain("Content-Length: 5");
    }

    @Test
    void exactHttpDoesNotSynthesizeDefaultHeadersUnexpectedly() throws Exception {
        ApiRequest req = baseRequest();
        req.buildMode = ApiRequest.BuildMode.EXACT_HTTP;

        String raw = requestText(req);

        assertThat(raw).doesNotContain("Accept: application/json, text/plain, */*");
        assertThat(raw).doesNotContain("User-Agent: BurpExtensionRuntime");
        assertThat(raw).doesNotContain("Cache-Control: no-cache");
        assertThat(raw).doesNotContain("Host: example.com");
    }

    @Test
    void legacyRequestsWithoutExplicitBuildModeStillDefaultToNormalizedMode() {
        ApiRequest req = baseRequest();

        assertThat(req.resolveBuildMode()).isEqualTo(ApiRequest.BuildMode.AUTO_COMPATIBLE);
        assertThat(req.isExactHttpMode()).isFalse();
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

    @Test
    void manualPreserveFormDataCompletesMissingBoundaryForNow() throws Exception {
        ApiRequest req = baseRequest();
        req.method = "POST";
        req.editorMaterialized = true;
        req.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        req.headers.add(new ApiRequest.Header("Content-Type", "multipart/form-data", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "formdata";
        req.body.formdata.add(new ApiRequest.Body.FormField("field", "value"));

        RawRequestParser parsed = parse(req);

        String contentType = parsed.headerValue("Content-Type");
        assertThat(contentType).contains("multipart/form-data");
        assertThat(contentType).contains("boundary=");
        String bodyText = new String(parsed.body, StandardCharsets.UTF_8);
        String boundary = contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
        assertThat(bodyText).contains(boundary);
        assertThat(parsed.contentLength()).isEqualTo(parsed.body.length);
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

    private String requestText(ApiRequest req) throws Exception {
        return new String(builder.buildRequest(req, resolver), StandardCharsets.UTF_8);
    }
}
