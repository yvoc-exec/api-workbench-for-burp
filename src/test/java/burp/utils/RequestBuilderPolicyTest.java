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


    @Test
    void manualPreservePreservesInterleavedDuplicateOrdinaryHeaders() throws Exception {
        ApiRequest req = baseRequest();
        req.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        req.headers.add(new ApiRequest.Header("Authorization", "Bearer first", false));
        req.headers.add(new ApiRequest.Header("Cookie", "a=1", false));
        req.headers.add(new ApiRequest.Header("X-Test", "one", false));
        req.headers.add(new ApiRequest.Header("Authorization", "Bearer second", false));
        req.headers.add(new ApiRequest.Header("Cookie", "b=2", false));
        req.headers.add(new ApiRequest.Header("X-Test", "two", false));

        String raw = requestText(req);

        assertThat(raw).contains("Authorization: Bearer first");
        assertThat(raw).contains("Authorization: Bearer second");
        assertThat(raw).contains("Cookie: a=1");
        assertThat(raw).contains("Cookie: b=2");
        assertThat(raw).contains("X-Test: one");
        assertThat(raw).contains("X-Test: two");
        assertThat(raw.indexOf("Authorization: Bearer first")).isLessThan(raw.indexOf("Cookie: a=1"));
        assertThat(raw.indexOf("Cookie: a=1")).isLessThan(raw.indexOf("X-Test: one"));
        assertThat(raw.indexOf("X-Test: one")).isLessThan(raw.indexOf("Authorization: Bearer second"));
    }

    @Test
    void autoCompatiblePreservesOrdinaryDuplicatesAndAuthoredAcceptEncoding() throws Exception {
        ApiRequest req = baseRequest();
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        req.headers.add(new ApiRequest.Header("Accept", "application/xml", false));
        req.headers.add(new ApiRequest.Header("X-Test", "one", false));
        req.headers.add(new ApiRequest.Header("X-Test", "two", false));
        req.headers.add(new ApiRequest.Header("Accept-Encoding", "gzip, br", false));

        String raw = requestText(req);

        assertThat(raw).contains("Accept: application/xml");
        assertThat(raw).doesNotContain("Accept: application/json, text/plain, */*");
        assertThat(raw).contains("X-Test: one");
        assertThat(raw).contains("X-Test: two");
        assertThat(raw).contains("Accept-Encoding: gzip, br");
    }

    @Test
    void safeModeFiltersFramingHopByHopAndConnectionNominatedHeaders() throws Exception {
        ApiRequest req = baseRequest();
        req.method = "POST";
        req.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        req.headers.add(new ApiRequest.Header("Host", "attacker.example", false));
        req.headers.add(new ApiRequest.Header("Host", "second.example", false));
        req.headers.add(new ApiRequest.Header("Content-Length", "999", false));
        req.headers.add(new ApiRequest.Header("Content-Length", "1", false));
        req.headers.add(new ApiRequest.Header("Transfer-Encoding", "chunked", false));
        req.headers.add(new ApiRequest.Header("Connection", "keep-alive, X-Hop", false));
        req.headers.add(new ApiRequest.Header("Proxy-Connection", "keep-alive", false));
        req.headers.add(new ApiRequest.Header("Keep-Alive", "timeout=5", false));
        req.headers.add(new ApiRequest.Header("TE", "trailers", false));
        req.headers.add(new ApiRequest.Header("Trailer", "X-Trailer", false));
        req.headers.add(new ApiRequest.Header("Upgrade", "h2c", false));
        req.headers.add(new ApiRequest.Header("HTTP2-Settings", "value", false));
        req.headers.add(new ApiRequest.Header("Proxy-Authorization", "Basic secret", false));
        req.headers.add(new ApiRequest.Header("X-Hop", "remove", false));
        req.headers.add(new ApiRequest.Header("X-Keep", "keep", false));
        req.headers.add(new ApiRequest.Header("Accept-Encoding", "gzip", false));
        req.headers.add(new ApiRequest.Header("Postman-Token", "metadata", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "hello";

        String raw = requestText(req);

        assertThat(raw).contains("Host: example.com");
        assertThat(raw).doesNotContain("Host: attacker.example");
        assertThat(raw).contains("Content-Length: 5");
        assertThat(raw).doesNotContain("Content-Length: 999");
        assertThat(raw).doesNotContain("Transfer-Encoding:");
        assertThat(raw).doesNotContain("Connection:");
        assertThat(raw).doesNotContain("Proxy-Connection:");
        assertThat(raw).doesNotContain("Keep-Alive:");
        assertThat(raw).doesNotContain("TE:");
        assertThat(raw).doesNotContain("Trailer:");
        assertThat(raw).doesNotContain("Upgrade:");
        assertThat(raw).doesNotContain("HTTP2-Settings:");
        assertThat(raw).doesNotContain("Proxy-Authorization:");
        assertThat(raw).doesNotContain("X-Hop: remove");
        assertThat(raw).doesNotContain("Postman-Token:");
        assertThat(raw).contains("X-Keep: keep");
        assertThat(raw).contains("Accept-Encoding: gzip");
    }

    @Test
    void exactModePreservesFramingAndAuthoredOrderWithoutComputedHostOrLength() throws Exception {
        ApiRequest req = baseRequest();
        req.method = "POST";
        req.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        req.headers.add(new ApiRequest.Header("Host", "attacker.example", false));
        req.headers.add(new ApiRequest.Header("Host", "second.example", false));
        req.headers.add(new ApiRequest.Header("Content-Length", "999", false));
        req.headers.add(new ApiRequest.Header("Content-Length", "1", false));
        req.headers.add(new ApiRequest.Header("Transfer-Encoding", "chunked", false));
        req.headers.add(new ApiRequest.Header("Connection", "keep-alive, X-Hop", false));
        req.headers.add(new ApiRequest.Header("Proxy-Authorization", "Basic secret", false));
        req.headers.add(new ApiRequest.Header("X-Hop", "keep", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "hello";

        String raw = requestText(req);

        assertThat(raw).contains("Host: attacker.example");
        assertThat(raw).contains("Host: second.example");
        assertThat(raw).contains("Content-Length: 999");
        assertThat(raw).contains("Content-Length: 1");
        assertThat(raw).contains("Transfer-Encoding: chunked");
        assertThat(raw).contains("Connection: keep-alive, X-Hop");
        assertThat(raw).contains("Proxy-Authorization: Basic secret");
        assertThat(raw).contains("X-Hop: keep");
        assertThat(raw).doesNotContain("Host: example.com");
        assertThat(raw).doesNotContain("Content-Length: 5");
        assertThat(raw.indexOf("Host: attacker.example")).isLessThan(raw.indexOf("Host: second.example"));
    }

    @Test
    void duplicateAuthoredAuthorizationPreventsGeneratedBearerAuth() throws Exception {
        ApiRequest req = baseRequest();
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        req.headers.add(new ApiRequest.Header("Authorization", "Bearer first", false));
        req.headers.add(new ApiRequest.Header("Authorization", "Bearer second", false));
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "generated");

        String raw = requestText(req);

        assertThat(raw).contains("Authorization: Bearer first");
        assertThat(raw).contains("Authorization: Bearer second");
        assertThat(raw).doesNotContain("Authorization: Bearer generated");
    }

    @Test
    void generatedCookieAuthAppendsAfterAuthoredCookiesWithoutCollapsingDuplicates() throws Exception {
        ApiRequest req = baseRequest();
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        req.headers.add(new ApiRequest.Header("Cookie", "a=1", false));
        req.headers.add(new ApiRequest.Header("X-Test", "one", false));
        req.headers.add(new ApiRequest.Header("Cookie", "b=2", false));
        req.auth = new ApiRequest.Auth();
        req.auth.type = "cookie";
        req.auth.properties.put("value", "generated=3");

        String raw = requestText(req);

        assertThat(raw).contains("Cookie: a=1", "X-Test: one", "Cookie: b=2", "Cookie: generated=3");
        assertThat(raw.indexOf("Cookie: a=1")).isLessThan(raw.indexOf("X-Test: one"));
        assertThat(raw.indexOf("X-Test: one")).isLessThan(raw.indexOf("Cookie: b=2"));
        assertThat(raw.indexOf("Cookie: b=2")).isLessThan(raw.indexOf("Cookie: generated=3"));
    }

    @Test
    void apiKeyInCookieAppendsAfterAuthoredCookiesWithoutCollapsingDuplicates() throws Exception {
        ApiRequest req = baseRequest();
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        req.headers.add(new ApiRequest.Header("Cookie", "a=1", false));
        req.headers.add(new ApiRequest.Header("Cookie", "b=2", false));
        req.auth = new ApiRequest.Auth();
        req.auth.type = "apikey";
        req.auth.properties.put("in", "cookie");
        req.auth.properties.put("key", "api_key");
        req.auth.properties.put("value", "secret");

        String raw = requestText(req);

        assertThat(raw).contains("Cookie: a=1", "Cookie: b=2", "Cookie: api_key=secret");
        assertThat(raw.indexOf("Cookie: a=1")).isLessThan(raw.indexOf("Cookie: b=2"));
        assertThat(raw.indexOf("Cookie: b=2")).isLessThan(raw.indexOf("Cookie: api_key=secret"));
    }

    @Test
    void generatedCookieAuthEmitsOnceWhenNoAuthoredCookieExists() throws Exception {
        ApiRequest req = baseRequest();
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        req.auth = new ApiRequest.Auth();
        req.auth.type = "cookie";
        req.auth.properties.put("value", "generated=3");

        String raw = requestText(req);

        assertThat(count(raw, "Cookie: ")).isEqualTo(1);
        assertThat(raw).contains("Cookie: generated=3");
    }

    @Test
    void generatedCookieAuthSkipsDisabledAuthoredCookieButStillEmitsGeneratedCookie() throws Exception {
        ApiRequest req = baseRequest();
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        req.headers.add(new ApiRequest.Header("Cookie", "disabled=1", true));
        req.auth = new ApiRequest.Auth();
        req.auth.type = "cookie";
        req.auth.properties.put("value", "generated=3");

        String raw = requestText(req);

        assertThat(raw).doesNotContain("Cookie: disabled=1");
        assertThat(count(raw, "Cookie: ")).isEqualTo(1);
        assertThat(raw).contains("Cookie: generated=3");
    }

    @Test
    void exactModePreservesOnlyEnabledAuthoredCookiesAndDoesNotSynthesizeCookieAuth() throws Exception {
        ApiRequest req = baseRequest();
        req.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        req.headers.add(new ApiRequest.Header("Cookie", "a=1", false));
        req.headers.add(new ApiRequest.Header("Cookie", "disabled=1", true));
        req.auth = new ApiRequest.Auth();
        req.auth.type = "cookie";
        req.auth.properties.put("value", "generated=3");

        String raw = requestText(req);

        assertThat(raw).contains("Cookie: a=1");
        assertThat(raw).doesNotContain("Cookie: disabled=1");
        assertThat(raw).doesNotContain("Cookie: generated=3");
        assertThat(count(raw, "Cookie: ")).isEqualTo(1);
    }

    @Test
    void manualPreserveRetainsDuplicateContentTypeAndAutoCompatibleDoesNotAppendExtra() throws Exception {
        ApiRequest manual = baseRequest();
        manual.method = "POST";
        manual.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        manual.headers.add(new ApiRequest.Header("Content-Type", "text/plain", false));
        manual.headers.add(new ApiRequest.Header("Content-Type", "application/custom", false));
        manual.body = new ApiRequest.Body();
        manual.body.mode = "raw";
        manual.body.raw = "hello";
        assertThat(requestText(manual)).contains("Content-Type: text/plain").contains("Content-Type: application/custom");

        ApiRequest auto = baseRequest();
        auto.method = "POST";
        auto.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        auto.headers.add(new ApiRequest.Header("Content-Type", "application/json", false));
        auto.body = new ApiRequest.Body();
        auto.body.mode = "raw";
        auto.body.raw = "{}";
        String raw = requestText(auto);
        assertThat(raw.indexOf("Content-Type:")).isEqualTo(raw.lastIndexOf("Content-Type:"));
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

    private static int count(String text, String needle) {
        int count = 0;
        int idx = text.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = text.indexOf(needle, idx + needle.length());
        }
        return count;
    }
}
