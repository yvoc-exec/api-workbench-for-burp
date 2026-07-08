package burp.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpUtilsTest {
    @Test
    void parseUrlDefaultsBlankToLocalhostHttp() {
        HttpUtils.HostInfo info = HttpUtils.parseUrl("  ");

        assertEquals("localhost", info.host);
        assertEquals(80, info.port);
        assertFalse(info.useHttps);
    }

    @Test
    void parseUrlReadsAbsoluteHttpsUrl() {
        HttpUtils.HostInfo info = HttpUtils.parseUrl("https://api.example.test/v1/users");

        assertEquals("api.example.test", info.host);
        assertEquals(443, info.port);
        assertTrue(info.useHttps);
    }

    @Test
    void parseUrlReadsAbsoluteHttpUrlWithExplicitPort() {
        HttpUtils.HostInfo info = HttpUtils.parseUrl("http://api.example.test:8080/v1/users");

        assertEquals("api.example.test", info.host);
        assertEquals(8080, info.port);
        assertFalse(info.useHttps);
    }

    @Test
    void parseUrlKeepsTemplateHostAndSchemeDefaults() {
        HttpUtils.HostInfo info = HttpUtils.parseUrl("https://{{api_host}}/v1/users");

        assertEquals("{{api_host}}", info.host);
        assertEquals(443, info.port);
        assertTrue(info.useHttps);
    }

    @Test
    void extractPathFromAbsoluteUrlKeepsRawQueryAndDropsFragment() {
        assertEquals("/v1/users?role=admin", HttpUtils.extractPathFromUrl(
                "https://api.example.test/v1/users?role=admin#section"));
    }

    @Test
    void extractPathFromSchemelessUrlKeepsLegacyPathShape() {
        assertEquals("/api.example.test/v1/users", HttpUtils.extractPathFromUrl("api.example.test/v1/users"));
    }

    @Test
    void buildHostWithPortOmitsDefaultsAndKeepsNonDefaults() {
        assertEquals("api.example.test", HttpUtils.buildHostWithPort("api.example.test", 443, true));
        assertEquals("api.example.test", HttpUtils.buildHostWithPort("api.example.test", 80, false));
        assertEquals("api.example.test:8443", HttpUtils.buildHostWithPort("api.example.test", 8443, true));
    }

    @Test
    void parseTargetForRequestParsesAbsoluteUrl() {
        HttpUtils.ParsedTarget target = HttpUtils.parseTargetForRequest(
                "https://api.example.test:8443/v1/users?role=admin#ignored");

        assertEquals("api.example.test", target.host);
        assertEquals(8443, target.port);
        assertTrue(target.useHttps);
        assertEquals("/v1/users?role=admin", target.pathWithQuery);
    }

    @Test
    void parseTargetForRequestParsesSchemeRelativeUrlAsHttps() {
        HttpUtils.ParsedTarget target = HttpUtils.parseTargetForRequest("//api.example.test/v1/users");

        assertEquals("api.example.test", target.host);
        assertEquals(443, target.port);
        assertTrue(target.useHttps);
        assertEquals("/v1/users", target.pathWithQuery);
    }

    @Test
    void parseTargetForRequestParsesSchemelessHostAsHttps() {
        HttpUtils.ParsedTarget target = HttpUtils.parseTargetForRequest("api.example.test:8080/v1/users?active=true");

        assertEquals("api.example.test", target.host);
        assertEquals(8080, target.port);
        assertTrue(target.useHttps);
        assertEquals("/v1/users?active=true", target.pathWithQuery);
    }

    @Test
    void parseTargetForRequestRejectsPathOnlyUrl() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> HttpUtils.parseTargetForRequest("/v1/users"));

        assertTrue(error.getMessage().contains("must include host"));
    }

    @Test
    void parseTargetForRequestRejectsUnsafeSchemes() {
        assertThrows(IllegalArgumentException.class, () -> HttpUtils.parseTargetForRequest("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> HttpUtils.parseTargetForRequest("data:text/plain,test"));
        assertThrows(IllegalArgumentException.class, () -> HttpUtils.parseTargetForRequest("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> HttpUtils.parseTargetForRequest("ftp://example.test/file"));
    }
}
