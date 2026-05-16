package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class RequestBuilderTest {

    private VariableResolver resolver;
    private RequestBuilder builder;

    @BeforeEach
    void setUp() {
        resolver = new VariableResolver();
        builder = new RequestBuilder(null, resolver);
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

        byte[] raw = builder.buildRequest(req);
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

        byte[] raw = builder.buildRequest(req);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        // Only one Content-Type should exist; last one wins (medium precedence put)
        long count = parsed.headers.keySet().stream()
                .filter(k -> k.equalsIgnoreCase("Content-Type"))
                .count();
        assertThat(count).isEqualTo(1);
        assertThat(parsed.headerValue("Content-Type")).isEqualTo("application/json");
    }

    @Test
    void computedHostHeaderIsPresentAndCorrect() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com:8080/api";

        byte[] raw = builder.buildRequest(req);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.hasHeader("Host")).isTrue();
        assertThat(parsed.headerValue("Host")).isEqualTo("example.com:8080");
    }

    @Test
    void hostOmitsDefaultPortForHttps() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "https://example.com/api";

        byte[] raw = builder.buildRequest(req);
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

        byte[] raw = builder.buildRequest(req);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Authorization")).isEqualTo("Basic custom");
    }

    @Test
    void cookieMergeProducesSingleCookieHeader() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Cookie", "a=1", false));
        req.auth = new ApiRequest.Auth();
        req.auth.type = "apikey";
        req.auth.properties.put("key", "session");
        req.auth.properties.put("value", "xyz");
        req.auth.properties.put("in", "cookie");

        byte[] raw = builder.buildRequest(req);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.hasHeader("Cookie")).isTrue();
        String cookie = parsed.headerValue("Cookie");
        assertThat(cookie).contains("a=1").contains("session=xyz");
        long cookieCount = parsed.headers.keySet().stream()
                .filter(k -> k.equalsIgnoreCase("Cookie"))
                .count();
        assertThat(cookieCount).isEqualTo(1);
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

        byte[] raw = builder.buildRequest(req);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.hasHeader("Connection")).isFalse();
        assertThat(parsed.hasHeader("Proxy-Connection")).isFalse();
        assertThat(parsed.hasHeader("Accept-Encoding")).isFalse();
        assertThat(parsed.hasHeader("Postman-Token")).isFalse();
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

        byte[] raw = builder.buildRequest(req);
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

        byte[] raw = builder.buildRequest(req);
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

        byte[] raw = builder.buildRequest(req);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Content-Type")).isEqualTo("text/plain");
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

        byte[] raw = builder.buildRequest(req);
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

        byte[] raw = builder.buildRequest(req);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
        assertThat(new String(parsed.body, StandardCharsets.UTF_8)).isEqualTo("a=1&b=2");
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

        byte[] raw = builder.buildRequest(req);
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

        byte[] raw = builder.buildRequest(req);
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
    void formdataReplacesConflictingImportedContentType() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Content-Type", "text/plain", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "formdata";
        req.body.formdata.add(new ApiRequest.Body.FormField("field1", "value1"));

        byte[] raw = builder.buildRequest(req);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        String ct = parsed.headerValue("Content-Type");
        assertThat(ct).startsWith("multipart/form-data");

        long count = parsed.headers.keySet().stream()
                .filter(k -> k.equalsIgnoreCase("Content-Type"))
                .count();
        assertThat(count).isEqualTo(1);
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

        byte[] raw = builder.buildRequest(req);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.contentLength()).isEqualTo(11);
    }

    @Test
    void contentLengthSetForPostWithoutBody() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "http://example.com/api";

        byte[] raw = builder.buildRequest(req);
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

        byte[] raw = builder.buildRequest(req);
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

        byte[] raw = builder.buildRequest(req);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.contentLength()).isEqualTo(5);
    }

    @Test
    void getWithoutBodyHasNoContentLength() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";

        byte[] raw = builder.buildRequest(req);
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

        byte[] raw = builder.buildRequest(req);
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
        String text = "GET /api/{{user_id|123}} HTTP/1.1\r\n\r\n";
        Set<String> unresolved = RequestBuilder.findUnresolvedTokens(text.getBytes(StandardCharsets.UTF_8));
        assertThat(unresolved).containsExactly("user_id");
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

        byte[] raw = builder.buildRequest(req);
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

        byte[] raw = builder.buildRequest(req);
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

        byte[] raw = builder.buildRequest(req);
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

        byte[] raw = builder.buildRequest(req);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("X-API-Key")).isEqualTo("secret123");
    }

    @Test
    void apiKeyInCookieMergesWithExistingCookie() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Cookie", "existing=1", false));
        req.auth = new ApiRequest.Auth();
        req.auth.type = "apikey";
        req.auth.properties.put("key", "session");
        req.auth.properties.put("value", "abc");
        req.auth.properties.put("in", "cookie");

        byte[] raw = builder.buildRequest(req);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        String cookie = parsed.headerValue("Cookie");
        assertThat(cookie).contains("existing=1").contains("session=abc");
    }

    @Test
    void cookieAuthMergesIntoCookieHeader() throws Exception {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "http://example.com/api";
        req.headers.add(new ApiRequest.Header("Cookie", "a=1", false));
        req.auth = new ApiRequest.Auth();
        req.auth.type = "cookie";
        req.auth.properties.put("value", "b=2");

        byte[] raw = builder.buildRequest(req);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        String cookie = parsed.headerValue("Cookie");
        assertThat(cookie).contains("a=1").contains("b=2");
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

        byte[] raw = builder.buildRequest(req);
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

        byte[] raw = builder.buildRequest(req);
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

        assertThatThrownBy(() -> builder.buildRequest(req))
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

        assertThatThrownBy(() -> builder.buildRequest(req))
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

        assertThatThrownBy(() -> builder.buildRequest(req))
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

        byte[] raw = builder.buildRequest(req);
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

        byte[] raw = builder.buildRequest(req);
        RawRequestParser parsed = RawRequestParser.parse(raw);

        assertThat(parsed.headerValue("Accept")).isEqualTo("application/json, text/plain, */*");
        assertThat(parsed.headerValue("User-Agent")).isEqualTo("BurpExtensionRuntime");
        assertThat(parsed.headerValue("Cache-Control")).isEqualTo("no-cache");
    }
}
