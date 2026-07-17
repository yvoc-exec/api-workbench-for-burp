package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class RequestBuilderTest {

    private VariableResolver resolver;
    private RequestBuilder builder;

    @BeforeEach
    void setUp() {
        resolver = new VariableResolver();
        builder = new RequestBuilder(null);
    }

    // ===================================================================
    // A) Header normalization / dedupe / precedence
    // ===================================================================

    @Test
    void requestLevelHeaderOverridesCompatibilityDefault() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Accept", "text/html", false));

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Accept")).isEqualTo("text/html");
    }

    @Test
    void caseInsensitiveHeaderDedupe() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("content-type", "text/html", false));
        req.headers.add(new ApiRequest.Header("Content-Type", "application/json", false));

        String raw = new String(builder.buildRequest(req, resolver), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(raw).contains("content-type: text/html");
        assertThat(raw).contains("Content-Type: application/json");
    }

    @Test
    void computedHostHeaderIsPresentAndCorrect() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com:8080/api";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.hasHeader("Host")).isTrue();
        assertThat(parsed.headerValue("Host")).isEqualTo("example.com:8080");
    }

    @Test
    void hostOmitsDefaultPortForHttps() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "https://example.com/api";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Host")).isEqualTo("example.com");
    }

    @Test
    void explicitRequestAuthorizationWinsOverAuthConfig() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Authorization", "Basic custom", false));
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "abc123");

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Authorization")).isEqualTo("Basic custom");
    }

    @Test
    void parameterHeaderWinsOverDefaultsAndAuthentication() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        ApiRequest.Parameter accept = new ApiRequest.Parameter("header", "Accept", "application/parameter");
        accept.valuePresent = true;
        ApiRequest.Parameter authorization = new ApiRequest.Parameter("header", "Authorization", "Parameter token");
        authorization.valuePresent = true;
        req.parameters.add(accept);
        req.parameters.add(authorization);
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "generated");

        String raw = new String(builder.buildRequest(req, resolver), StandardCharsets.UTF_8);
        assertThat(raw).contains("Accept: application/parameter", "Authorization: Parameter token")
                .doesNotContain("Bearer generated", "Accept: application/json");
    }

    @Test
    void cookieAuthAppendsGeneratedCookieHeader() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Cookie", "a=1", false));
        req.auth = new ApiRequest.Auth();
        req.auth.type = "apikey";
        req.auth.properties.put("key", "session");
        req.auth.properties.put("value", "xyz");
        req.auth.properties.put("in", "cookie");

        String raw = new String(builder.buildRequest(req, resolver), StandardCharsets.UTF_8);

        assertThat(raw).contains("Cookie: a=1").contains("Cookie: session=xyz");
        assertThat(raw.indexOf("Cookie: a=1")).isLessThan(raw.indexOf("Cookie: session=xyz"));
    }

    // ===================================================================
    // B) Skip / strip headers
    // ===================================================================

    @Test
    void hopByHopHeadersAreNotEmitted() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Connection", "keep-alive", false));
        req.headers.add(new ApiRequest.Header("Proxy-Connection", "keep-alive", false));
        req.headers.add(new ApiRequest.Header("Accept-Encoding", "gzip", false));
        req.headers.add(new ApiRequest.Header("Postman-Token", "abc", false));

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.hasHeader("Connection")).isFalse();
        assertThat(parsed.hasHeader("Proxy-Connection")).isFalse();
        assertThat(parsed.hasHeader("Accept-Encoding")).isTrue();
        assertThat(parsed.hasHeader("Postman-Token")).isFalse();
    }

    @Test
    void disabledHeadersAreNotEmitted() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("X-Enabled", "yes", false));
        req.headers.add(new ApiRequest.Header("X-Disabled", "no", true));

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.hasHeader("X-Enabled")).isTrue();
        assertThat(parsed.hasHeader("X-Disabled")).isFalse();
    }

    @Test
    void reEnabledHeaderIsEmitted() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("X-Reenabled", "yes", false));

        RawRequestParser parsed = RawRequestParser.parse(builder.buildRequest(req, resolver));

        assertThat(parsed.hasHeader("X-Reenabled")).isTrue();
    }

    @Test
    void staleContentLengthAndTransferEncodingAreRemoved() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Content-Length", "9999", false));
        req.headers.add(new ApiRequest.Header("Transfer-Encoding", "chunked", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "hello";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.hasHeader("Transfer-Encoding")).isFalse();
        assertThat(parsed.contentLength()).isEqualTo(5); // "hello" in UTF-8
    }

    // ===================================================================
    // C) Body-mode alignment
    // ===================================================================

    @Test
    void rawJsonBodyGetsApplicationJsonContentType() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "{\"key\":\"value\"}";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Content-Type")).isEqualTo("application/json");
    }

    @Test
    void rawTextBodyGetsTextPlainContentType() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "plain text";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Content-Type")).isEqualTo("text/plain");
    }

    @Test
    void disabledContentTypeNoLongerSuppressesRawBodyContentTypeSynthesis() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Content-Type", "application/json", true));
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "{\"a\":1}";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Content-Type")).isEqualTo("application/json");
        assertThat(new String(parsed.body, StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
    }

    @Test
    void rawBodyRespectsExplicitContentType() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "plain text";
        req.body.contentType = "application/xml";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Content-Type")).isEqualTo("application/xml");
    }

    @Test
    void urlencodedBodyGetsCorrectContentType() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "urlencoded";
        req.body.urlencoded.add(new ApiRequest.Body.FormField("a", "1"));
        req.body.urlencoded.add(new ApiRequest.Body.FormField("b", "2"));

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
        assertThat(new String(parsed.body, StandardCharsets.UTF_8)).isEqualTo("a=1&b=2");
    }

    @Test
    void disabledContentTypeNoLongerSuppressesUrlencodedContentTypeSynthesis() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Content-Type", "application/x-www-form-urlencoded", true));
        req.body = new ApiRequest.Body();
        req.body.mode = "urlencoded";
        req.body.urlencoded.add(new ApiRequest.Body.FormField("a", "1"));

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
        assertThat(new String(parsed.body, StandardCharsets.UTF_8)).isEqualTo("a=1");
    }

    @Test
    void urlencodedBodySkipsDisabledFields() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "urlencoded";
        req.body.urlencoded.add(new ApiRequest.Body.FormField("enabled", "yes"));
        ApiRequest.Body.FormField disabled = new ApiRequest.Body.FormField("disabled", "no");
        disabled.disabled = true;
        req.body.urlencoded.add(disabled);

        byte[] raw = builder.buildRequest(req, resolver);
        String text = new String(raw, StandardCharsets.UTF_8);

        assertThat(text).contains("enabled=yes");
        assertThat(text).doesNotContain("disabled=no");
    }

    @Test
    void graphqlBodyGetsApplicationJsonContentType() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "graphql";
        req.body.graphql = new ApiRequest.Body.GraphQL();
        req.body.graphql.query = "{ users }";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Content-Type")).isEqualTo("application/json");
        assertThat(new String(parsed.body, StandardCharsets.UTF_8)).contains("query").contains("users");
    }

    @Test
    void formdataBodyGetsContentTypeWithBoundaryMatchingBody() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "formdata";
        req.body.formdata.add(new ApiRequest.Body.FormField("field1", "value1"));

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        String ct = parsed.headerValue("Content-Type");
        assertThat(ct).startsWith("multipart/form-data; boundary=");

        // Extract boundary from Content-Type
        String boundary = ct.substring(ct.indexOf("boundary=") + "boundary=".length());
        String bodyStr = new String(parsed.body, StandardCharsets.UTF_8);

        // Body must contain the exact boundary delimiters
        assertThat(bodyStr).contains("--" + boundary);
        assertThat(bodyStr).contains("--" + boundary + "--");
    }

    @Test
    void formdataBodySkipsDisabledFields() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "formdata";
        req.body.formdata.add(new ApiRequest.Body.FormField("enabled", "yes"));
        ApiRequest.Body.FormField disabled = new ApiRequest.Body.FormField("disabled", "no");
        disabled.disabled = true;
        req.body.formdata.add(disabled);

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);
        String body = new String(parsed.body, StandardCharsets.UTF_8);

        assertThat(body).contains("name=\"enabled\"");
        assertThat(body).contains("yes");
        assertThat(body).doesNotContain("name=\"disabled\"");
        assertThat(body).doesNotContain("no");
    }

    @Test
    void formdataReplacesConflictingImportedContentType() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Content-Type", "text/plain", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "formdata";
        req.body.formdata.add(new ApiRequest.Body.FormField("field1", "value1"));

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        String ct = parsed.headerValue("Content-Type");
        assertThat(ct).startsWith("multipart/form-data");

        long count = parsed.headers.keySet().stream()
                .filter(k -> k.equalsIgnoreCase("Content-Type"))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void formdataTreatsAbsolutePathAsTextUnlessExplicitFileUpload() throws Exception {
        Path tempFile = Files.createTempFile(Path.of("target"), "request-builder-", ".txt").toAbsolutePath().normalize();
        Files.writeString(tempFile, "secret-file-content", StandardCharsets.UTF_8);
        tempFile.toFile().deleteOnExit();

        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "formdata";
        req.body.formdata.add(new ApiRequest.Body.FormField("upload", tempFile.toString()));

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);
        String body = new String(parsed.body, StandardCharsets.UTF_8);

        assertThat(body).contains(tempFile.toString());
        assertThat(body).doesNotContain("secret-file-content");
    }

    @Test
    void formdataExplicitFileUploadReadsFileContent() throws Exception {
        Path tempFile = Files.createTempFile(Path.of("target"), "request-builder-upload-", ".txt").toAbsolutePath().normalize();
        Files.writeString(tempFile, "uploaded-content", StandardCharsets.UTF_8);
        tempFile.toFile().deleteOnExit();

        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "formdata";
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField("upload", "");
        field.fileUpload = true;
        field.filePath = tempFile.toString();
        req.body.formdata.add(field);

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);
        String body = new String(parsed.body, StandardCharsets.UTF_8);

        assertThat(body).contains("filename=\"" + tempFile.getFileName() + "\"");
        assertThat(body).contains("uploaded-content");
    }

    // ===================================================================
    // D) Content-Length correctness
    // ===================================================================

    @Test
    void contentLengthRecomputedFromBodyBytes() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "hello world";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.contentLength()).isEqualTo(11);
    }

    @Test
    void contentLengthSetForPostWithoutBody() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.contentLength()).isEqualTo(0);
    }

    @Test
    void contentLengthSetForPutWithBody() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "PUT";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "data";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.contentLength()).isEqualTo(4);
    }

    @Test
    void contentLengthSetForPatchWithBody() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "PATCH";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "patch";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.contentLength()).isEqualTo(5);
    }

    @Test
    void getWithoutBodyHasNoContentLength() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.hasHeader("Content-Length")).isFalse();
    }

    @Test
    void contentLengthNotDuplicated() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "x";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        long count = parsed.headers.keySet().stream()
                .filter(k -> k.equalsIgnoreCase("Content-Length"))
                .count();
        assertThat(count).isEqualTo(1);
    }

    // ===================================================================
    // E) Unresolved variable scanning
    // ===================================================================

    @Test
    void findUnresolvedTokensDetectsRemainingVariables() {
        String text = "GET /api/{{user_id}}/items HTTP/1.1\r\nHost: {{host}}\r\n\r\n";
        Set<String> unresolved = RequestBuilder.findUnresolvedTokens(text.getBytes(StandardCharsets.UTF_8));
        assertThat(unresolved).containsExactlyInAnyOrder("user_id", "host");
    }

    @Test
    void findUnresolvedTokensReturnsEmptyWhenNone() {
        String text = "GET /api/users HTTP/1.1\r\nHost: example.com\r\n\r\n";
        Set<String> unresolved = RequestBuilder.findUnresolvedTokens(text.getBytes(StandardCharsets.UTF_8));
        assertThat(unresolved).isEmpty();
    }

    @Test
    void findUnresolvedTokensHandlesDefaults() {
        String text = "GET /api/{{user_id|123}}/{{missing}} HTTP/1.1\r\n\r\n";
        Set<String> unresolved = RequestBuilder.findUnresolvedTokens(text.getBytes(StandardCharsets.UTF_8));
        assertThat(unresolved).containsExactly("missing");
    }

    // ===================================================================
    // F) Auth / OAuth2 precedence
    // ===================================================================

    @Test
    void bearerAuthGeneratesAuthorizationHeader() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "mytoken");

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Authorization")).isEqualTo("Bearer mytoken");
    }

    @Test
    void basicAuthGeneratesBase64AuthorizationHeader() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.auth = new ApiRequest.Auth();
        req.auth.type = "basic";
        req.auth.properties.put("username", "admin");
        req.auth.properties.put("password", "secret");

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Authorization")).isEqualTo("Basic YWRtaW46c2VjcmV0");
    }

    @Test
    void apiKeyInQueryAppendsCorrectly() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api?existing=1";
        req.auth = new ApiRequest.Auth();
        req.auth.type = "apikey";
        req.auth.properties.put("key", "api_key");
        req.auth.properties.put("value", "secret123");
        req.auth.properties.put("in", "query");

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.path()).contains("api_key=secret123");
    }

    @Test
    void apiKeyInHeaderAddsCustomHeader() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.auth = new ApiRequest.Auth();
        req.auth.type = "apikey";
        req.auth.properties.put("key", "X-API-Key");
        req.auth.properties.put("value", "secret123");
        req.auth.properties.put("in", "header");

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("X-API-Key")).isEqualTo("secret123");
    }

    @Test
    void apiKeyInHeaderAllowsNullResolver() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.auth = new ApiRequest.Auth();
        req.auth.type = "apikey";
        req.auth.properties.put("key", "X-API-Key");
        req.auth.properties.put("value", "secret123");
        req.auth.properties.put("in", "header");

        byte[] raw = builder.buildRequest(req, null);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("X-API-Key")).isEqualTo("secret123");
    }

    @Test
    void apiKeyInCookieAppendsAfterExistingCookie() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Cookie", "existing=1", false));
        req.auth = new ApiRequest.Auth();
        req.auth.type = "apikey";
        req.auth.properties.put("key", "session");
        req.auth.properties.put("value", "abc");
        req.auth.properties.put("in", "cookie");

        String raw = new String(builder.buildRequest(req, resolver), StandardCharsets.UTF_8);

        assertThat(raw).contains("Cookie: existing=1").contains("Cookie: session=abc");
        assertThat(raw.indexOf("Cookie: existing=1")).isLessThan(raw.indexOf("Cookie: session=abc"));
    }

    @Test
    void oauth2AuthUsesResolverTokenWithoutAcquiringTokenInRequestBuilder() throws Exception {
        burp.auth.OAuth2Manager manager = org.mockito.Mockito.mock(burp.auth.OAuth2Manager.class);
        RequestBuilder builderWithManager = new RequestBuilder(null, manager);
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.auth = new ApiRequest.Auth();
        req.auth.type = "oauth2";
        req.auth.properties.put("accessToken", "{{oauth2_access_token}}");
        resolver.addCustomVariable("oauth2_token_url", "https://auth.example.test/token");
        resolver.addCustomVariable("oauth2_client_id", "client");
        resolver.addCustomVariable("oauth2_client_secret", "secret");
        resolver.addCustomVariable("oauth2_grant", "client_credentials");
        resolver.addCustomVariable("oauth2_access_token", "resolver-token");

        byte[] raw = builderWithManager.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Authorization")).isEqualTo("Bearer resolver-token");
        org.mockito.Mockito.verifyNoInteractions(manager);
    }

    @Test
    void cookieAuthAppendsAfterExistingCookie() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Cookie", "a=1", false));
        req.auth = new ApiRequest.Auth();
        req.auth.type = "cookie";
        req.auth.properties.put("value", "b=2");

        String raw = new String(builder.buildRequest(req, resolver), StandardCharsets.UTF_8);

        assertThat(raw).contains("Cookie: a=1").contains("Cookie: b=2");
        assertThat(raw.indexOf("Cookie: a=1")).isLessThan(raw.indexOf("Cookie: b=2"));
    }

    // ===================================================================
    // G) OAuth2 token body builder (strict mode)
    // ===================================================================

    @Test
    void oauth2TokenRequestStrictModeBuildsCanonicalBody() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://auth.example.com/oauth/token";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "should-be-overridden";

        resolver.addCustomVariable("oauth2_token_url", "http://auth.example.com/oauth/token");
        resolver.addCustomVariable("oauth2_grant", "client_credentials");
        resolver.addCustomVariable("oauth2_client_id", "client123");
        resolver.addCustomVariable("oauth2_client_secret", "secret456");
        resolver.addCustomVariable("oauth2_scope", "read");

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
        String body = new String(parsed.body, StandardCharsets.UTF_8);
        assertThat(body).contains("grant_type=client_credentials");
        assertThat(body).contains("client_id=client123");
        assertThat(body).contains("client_secret=secret456");
        assertThat(body).contains("scope=read");
    }

    @Test
    void oauth2TokenRequestClientAuthBasicPutsSecretInHeaderNotBody() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://auth.example.com/oauth/token";

        resolver.addCustomVariable("oauth2_token_url", "http://auth.example.com/oauth/token");
        resolver.addCustomVariable("oauth2_grant", "client_credentials");
        resolver.addCustomVariable("oauth2_client_id", "client123");
        resolver.addCustomVariable("oauth2_client_secret", "secret456");
        resolver.addCustomVariable("oauth2_client_auth", "basic");

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        // Basic auth header should be present
        assertThat(parsed.headerValue("Authorization")).startsWith("Basic ");

        // Body should NOT contain client_secret
        String body = new String(parsed.body, StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("client_secret");
        assertThat(body).contains("grant_type=client_credentials");
        assertThat(body).contains("client_id=client123");
    }

    @Test
    void oauth2TokenRequestMissingClientIdThrows() {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://auth.example.com/oauth/token";

        resolver.addCustomVariable("oauth2_token_url", "http://auth.example.com/oauth/token");
        resolver.addCustomVariable("oauth2_grant", "client_credentials");
        // No client_id set

        assertThatThrownBy(() -> builder.buildRequest(req, resolver))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oauth2_client_id");
    }

    @Test
    void oauth2TokenRequestMissingClientSecretForClientCredentialsThrows() {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://auth.example.com/oauth/token";

        resolver.addCustomVariable("oauth2_token_url", "http://auth.example.com/oauth/token");
        resolver.addCustomVariable("oauth2_grant", "client_credentials");
        resolver.addCustomVariable("oauth2_client_id", "client123");
        // No client_secret

        assertThatThrownBy(() -> builder.buildRequest(req, resolver))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oauth2_client_secret");
    }

    @Test
    void oauth2TokenRequestPasswordGrantMissingCredentialsThrows() {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://auth.example.com/oauth/token";

        resolver.addCustomVariable("oauth2_token_url", "http://auth.example.com/oauth/token");
        resolver.addCustomVariable("oauth2_grant", "password");
        resolver.addCustomVariable("oauth2_client_id", "client123");
        resolver.addCustomVariable("oauth2_client_secret", "secret456");
        // No username/password

        assertThatThrownBy(() -> builder.buildRequest(req, resolver))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oauth2_username");
    }

    // ===================================================================
    // H) Variable resolution in request building
    // ===================================================================

    @Test
    void variablesResolvedInUrlHeadersAndBody() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://{{host}}/api/{{id}}";
        req.headers.add(new ApiRequest.Header("X-Token", "{{token}}", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "user={{user}}";

        resolver.addCustomVariable("host", "example.com");
        resolver.addCustomVariable("id", "42");
        resolver.addCustomVariable("token", "abc");
        resolver.addCustomVariable("user", "john");

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.path()).startsWith("/api/42");
        assertThat(parsed.headerValue("X-Token")).isEqualTo("abc");
        assertThat(new String(parsed.body, StandardCharsets.UTF_8)).isEqualTo("user=john");
    }

    @Test
    void compatibilityDefaultsPresentWhenNotOverridden() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Accept")).isEqualTo("application/json, text/plain, */*");
        assertThat(parsed.headerValue("User-Agent")).isEqualTo("BurpExtensionRuntime");
        assertThat(parsed.headerValue("Cache-Control")).isEqualTo("no-cache");
    }

    // ===================================================================
    // I) Effective headers (preview)
    // ===================================================================

    @Test
    void buildEffectiveHeadersReturnsFinalHeaderSetWithoutTransportHeaders() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "hello";
        req.headers.add(new ApiRequest.Header("X-Custom", "value", false));

        List<Map.Entry<String, String>> effective = builder.buildEffectiveHeaders(req, resolver);

        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : effective) {
            map.put(e.getKey().toLowerCase(), e.getValue());
        }

        assertThat(map).containsKey("host");
        assertThat(map).containsKey("accept");
        assertThat(map).containsKey("user-agent");
        assertThat(map).containsKey("x-custom");
        assertThat(map).doesNotContainKey("content-length");
        assertThat(map).doesNotContainKey("transfer-encoding");
    }

    @Test
    void buildEffectiveHeadersStillIncludeDefaultsWhenMatchingDisabledHeaderExists() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Accept", "text/html", true)); // disabled

        List<Map.Entry<String, String>> effective = builder.buildEffectiveHeaders(req, resolver);

        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : effective) {
            map.put(e.getKey().toLowerCase(), e.getValue());
        }

        assertThat(map).containsEntry("accept", "application/json, text/plain, */*");
    }

    @Test
    void buildEffectiveHeadersIncludesAuthAndBodyHeaders() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "{}";
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "tok123");

        List<Map.Entry<String, String>> effective = builder.buildEffectiveHeaders(req, resolver);

        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : effective) {
            map.put(e.getKey().toLowerCase(), e.getValue());
        }

        assertThat(map).containsEntry("authorization", "Bearer tok123");
        assertThat(map).containsEntry("content-type", "application/json");
    }

    @Test
    void editorMaterializedRequestsDoNotResynthesizeDefaultsAuthOrContentType() throws Exception {
        ApiRequest req = new ApiRequest();
        req.editorMaterialized = true;
        req.method = "POST";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Accept", "text/html", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "{}";
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "tok123");

        List<Map.Entry<String, String>> effective = builder.buildEffectiveHeaders(req, resolver);

        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : effective) {
            map.put(e.getKey().toLowerCase(), e.getValue());
        }

        assertThat(map).containsEntry("accept", "text/html");
        assertThat(map).containsKey("host");
        assertThat(map).doesNotContainKeys("authorization", "content-type", "user-agent", "cache-control");
    }

    @Test
    void editorMaterializedRequestsStillComputeTransportHeadersAtBuildTime() throws Exception {
        ApiRequest req = new ApiRequest();
        req.editorMaterialized = true;
        req.method = "POST";
        req.url = "https://other.example.test/path";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "hello";

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Host")).isEqualTo("other.example.test");
        assertThat(parsed.contentLength()).isEqualTo(5);
    }

    @Test
    void editorMaterializedMultipartWithoutContentTypeDoesNotBackfillHeader() throws Exception {
        ApiRequest req = new ApiRequest();
        req.editorMaterialized = true;
        req.method = "POST";
        req.url = "http://example.com/api";
        req.body = new ApiRequest.Body();
        req.body.mode = "formdata";
        req.body.formdata.add(new ApiRequest.Body.FormField("field1", "value1"));

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.hasHeader("Content-Type")).isFalse();
        assertThat(new String(parsed.body, StandardCharsets.UTF_8)).contains("name=\"field1\"");
    }

    @Test
    void editorMaterializedMultipartUsesExistingBoundaryWhenPresent() throws Exception {
        ApiRequest req = new ApiRequest();
        req.editorMaterialized = true;
        req.method = "POST";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Content-Type", "multipart/form-data; boundary=my-boundary", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "formdata";
        req.body.formdata.add(new ApiRequest.Body.FormField("field1", "value1"));

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);
        String body = new String(parsed.body, StandardCharsets.UTF_8);

        assertThat(parsed.headerValue("Content-Type")).isEqualTo("multipart/form-data; boundary=my-boundary");
        assertThat(body).contains("--my-boundary");
        assertThat(body).contains("--my-boundary--");
    }

    @Test
    void editorMaterializedMultipartCompletesBoundaryWhenGenericMultipartHeaderExists() throws Exception {
        ApiRequest req = new ApiRequest();
        req.editorMaterialized = true;
        req.method = "POST";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Content-Type", "multipart/form-data", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "formdata";
        req.body.formdata.add(new ApiRequest.Body.FormField("field1", "value1"));

        byte[] raw = builder.buildRequest(req, resolver);
        RawRequestParser parsed = RawRequestParser.parse(raw);
        String ct = parsed.headerValue("Content-Type");
        String body = new String(parsed.body, StandardCharsets.UTF_8);

        assertThat(ct).startsWith("multipart/form-data; boundary=");
        String boundary = ct.substring(ct.indexOf("boundary=") + "boundary=".length());
        assertThat(body).contains("--" + boundary);
        assertThat(body).contains("--" + boundary + "--");
    }

    @Test
    void pristineExactRequestBuildsOriginalBytes() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "https://api.example.test/upload";
        req.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "changed";
        req.exactHttpRequest = new burp.models.ExactHttpRequestSnapshot();
        req.exactHttpRequest.rawRequestBytes = "POST /upload HTTP/1.1\r\nHost: api.example.test\r\nContent-Length: 5\r\n\r\nhello".getBytes(StandardCharsets.UTF_8);
        req.exactHttpRequest.pristine = true;
        req.exactHttpRequest.semanticFingerprint = req.computeSemanticFingerprint();

        byte[] built = builder.buildRequest(req, resolver);

        assertThat(built).isEqualTo(req.exactHttpRequest.rawRequestBytes);
        built[0] = 'X';
        assertThat(req.exactHttpRequest.rawRequestBytes[0]).isEqualTo((byte) 'P');
    }

    @Test
    void nonPristineExactRequestFallsBackToSemanticBuild() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "hello";
        req.exactHttpRequest = new burp.models.ExactHttpRequestSnapshot();
        req.exactHttpRequest.rawRequestBytes = "POST /stale HTTP/1.1\r\nHost: stale.example\r\n\r\nstale".getBytes(StandardCharsets.UTF_8);
        req.exactHttpRequest.pristine = false;

        String built = new String(builder.buildRequest(req, resolver), StandardCharsets.UTF_8);

        assertThat(built).contains("POST /api HTTP/1.1");
        assertThat(built).doesNotContain("Host: stale.example");
        assertThat(built.endsWith("\r\n\r\nhello") || built.endsWith("\n\nhello")).isTrue();
    }
}
