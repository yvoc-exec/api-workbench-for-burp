package burp.utils;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.models.RedirectCrossOriginMode;
import burp.models.RedirectHop;
import burp.models.RedirectPolicy;
import burp.models.RedirectTerminationReason;
import burp.models.TrustedRedirectRule;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class RedirectExecutorTest {

    @Test
    void followDisabledKeepsRedirectAsEvidenceOnly() {
        Harness harness = execute(
                request("POST", "/start", "api.example.test", 443, true, body("alpha")),
                "https://api.example.test/start",
                false,
                RedirectPolicy.defaults(),
                List.of(response(302, "Found", "/next", null, null))
        );

        assertThat(harness.sentRequests).hasSize(1);
        assertThat(harness.result.success).isTrue();
        assertThat(harness.result.terminationReason).isEqualTo(RedirectTerminationReason.FOLLOW_DISABLED);
        assertThat(harness.result.finalResponse).isNotNull();
        assertThat(harness.result.finalResponse.response().statusCode()).isEqualTo((short) 302);
        assertThat(harness.result.redirectHops).hasSize(1);
        RedirectHop hop = harness.result.redirectHops.get(0);
        assertThat(hop.followed).isFalse();
        assertThat(hop.failureReason).contains("disabled");
    }

    @Test
    void sameOriginRelativeRedirectPreservesSensitiveHeadersExceptProxyAuthorization() {
        Harness harness = execute(
                request(
                        "POST",
                        "/start",
                        "api.example.test",
                        443,
                        true,
                        headerLine("Authorization", "Bearer secret"),
                        headerLine("Cookie", "session=abc"),
                        headerLine("Proxy-Authorization", "Basic proxy"),
                        headerLine("X-Custom", "keep"),
                        headerLine("Content-Type", "application/json"),
                        headerLine("Content-Length", "13"),
                        body("{\"ok\":true}")
                ),
                "https://api.example.test/start",
                true,
                RedirectPolicy.defaults(),
                List.of(
                        response(302, "Found", "/next?mode=1", null, null),
                        response(200, "OK", null, "{\"ok\":true}", "application/json")
                )
        );

        assertThat(harness.sentRequests).hasSize(2);
        HttpRequest redirected = harness.sentRequests.get(1);
        String raw = rawRequest(redirected);
        assertThat(raw).contains("GET /next?mode=1 HTTP/1.1");
        assertThat(raw).contains("Authorization: Bearer secret");
        assertThat(raw).contains("Cookie: session=abc");
        assertThat(raw).contains("X-Custom: keep");
        assertThat(raw).doesNotContain("Proxy-Authorization:");
        assertThat(harness.result.success).isTrue();
        assertThat(harness.result.terminationReason).isEqualTo(RedirectTerminationReason.FINAL_RESPONSE);
        assertThat(harness.result.finalResponse.response().statusCode()).isEqualTo((short) 200);
        assertThat(harness.result.redirectHops).hasSize(1);
        assertThat(harness.result.redirectHops.get(0).forwardedSensitiveHeaderNames)
                .contains("Authorization", "Cookie");
        assertThat(harness.result.redirectHops.get(0).strippedSensitiveHeaderNames)
                .contains("Proxy-Authorization");
        assertThat(harness.result.redirectHops.get(0).safeSummary()).contains("Authorization").doesNotContain("secret");
    }

    @Test
    void redirects301302And303PostBecomeGetAndDropBodyFraming() {
        for (int statusCode : List.of(301, 302, 303)) {
            Harness harness = execute(
                    request(
                            "POST",
                            "/submit",
                            "api.example.test",
                            443,
                            true,
                            headerLine("Content-Type", "application/json"),
                            headerLine("Content-Length", "13"),
                            body("{\"ok\":true}")
                    ),
                    "https://api.example.test/submit",
                    true,
                    RedirectPolicy.defaults(),
                    List.of(
                            response(statusCode, "Redirect", "/next", null, null),
                            response(200, "OK", null, "done", "text/plain")
                    )
            );

            assertThat(harness.sentRequests).hasSize(2);
            String raw = rawRequest(harness.sentRequests.get(1));
            assertThat(raw).contains("GET /next HTTP/1.1");
            assertThat(raw).doesNotContain("Content-Length:");
            assertThat(raw).doesNotContain("Transfer-Encoding:");
            assertThat(raw).doesNotContain("Content-Type:");
            assertThat(harness.result.finalResponse.response().statusCode()).isEqualTo((short) 200);
        }
    }

    @Test
    void redirects307And308PreserveBinaryBodiesExactly() {
        byte[] binaryBody = new byte[]{0x00, 0x01, (byte) 0xFF, 0x10, 0x20};
        for (int statusCode : List.of(307, 308)) {
            Harness harness = execute(
                    request(
                            "POST",
                            "/binary",
                            "api.example.test",
                            443,
                            true,
                            binaryBody,
                            headerLine("Content-Type", "application/octet-stream"),
                            headerLine("Content-Length", String.valueOf(binaryBody.length))
                    ),
                    "https://api.example.test/binary",
                    true,
                    RedirectPolicy.defaults(),
                    List.of(
                            response(statusCode, "Redirect", "/next", null, null),
                            response(200, "OK", null, "done", "text/plain")
                    )
            );

            assertThat(harness.sentRequests).hasSize(2);
            assertThat(harness.sentRequests.get(1).method()).isEqualTo("POST");
            assertThat(bodyBytes(harness.sentRequests.get(1))).isEqualTo(binaryBody);
        }
    }

    @Test
    void bodyPreservingRedirectRetainsEntityMetadataAndRebuildsLength() {
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        Harness harness = execute(
                request(
                        "POST",
                        "/start",
                        "api.example.test",
                        443,
                        true,
                        body,
                        headerLine("Content-Type", "application/json"),
                        headerLine("Content-Encoding", "gzip"),
                        headerLine("Content-Language", "en"),
                        headerLine("Content-Disposition", "attachment; filename=\"payload.bin\""),
                        headerLine("Content-Length", "999"),
                        headerLine("X-Custom", "keep")
                ),
                "https://api.example.test/start",
                true,
                RedirectPolicy.defaults(),
                List.of(
                        response(307, "Temporary Redirect", "/next", null, null),
                        response(200, "OK", null, "done", "text/plain")
                )
        );

        assertThat(harness.sentRequests).hasSize(2);
        HttpRequest redirected = harness.sentRequests.get(1);
        String raw = rawRequestText(redirected);
        assertThat(redirected.method()).isEqualTo("POST");
        assertThat(bodyBytes(redirected)).isEqualTo(body);
        assertThat(requestLine(raw)).isEqualTo("POST /next HTTP/1.1");
        assertThat(raw).contains("Host: api.example.test");
        assertThat(raw).contains("Content-Type: application/json");
        assertThat(raw).contains("Content-Encoding: gzip");
        assertThat(raw).contains("Content-Language: en");
        assertThat(raw).contains("Content-Disposition: attachment; filename=\"payload.bin\"");
        assertThat(raw).contains("X-Custom: keep");
        assertThat(raw).doesNotContain("Transfer-Encoding:");
        assertThat(headerCount(raw, "Content-Length")).isEqualTo(1);
        assertThat(headerValue(raw, "Content-Length")).isEqualTo(String.valueOf(body.length));
    }

    @Test
    void bodyPreserving308RedirectKeepsContentType() {
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        Harness harness = execute(
                request(
                        "POST",
                        "/start",
                        "api.example.test",
                        443,
                        true,
                        body,
                        headerLine("Content-Type", "application/json"),
                        headerLine("Content-Length", "1")
                ),
                "https://api.example.test/start",
                true,
                RedirectPolicy.defaults(),
                List.of(
                        response(308, "Permanent Redirect", "/next", null, null),
                        response(200, "OK", null, "done", "text/plain")
                )
        );

        assertThat(harness.sentRequests).hasSize(2);
        HttpRequest redirected = harness.sentRequests.get(1);
        String raw = rawRequestText(redirected);
        assertThat(redirected.method()).isEqualTo("POST");
        assertThat(bodyBytes(redirected)).isEqualTo(body);
        assertThat(raw).contains("Content-Type: application/json");
        assertThat(headerCount(raw, "Content-Length")).isEqualTo(1);
        assertThat(headerValue(raw, "Content-Length")).isEqualTo(String.valueOf(body.length));
    }

    @Test
    void nonPostBodyPreservingRedirectRetainsBodyAndContentType() {
        for (int statusCode : List.of(301, 302)) {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            Harness harness = execute(
                    request(
                            "PUT",
                            "/start",
                            "api.example.test",
                            443,
                            true,
                            body,
                            headerLine("Content-Type", "application/json"),
                            headerLine("Content-Length", "5")
                    ),
                    "https://api.example.test/start",
                    true,
                    RedirectPolicy.defaults(),
                    List.of(
                            response(statusCode, "Redirect", "/next", null, null),
                            response(200, "OK", null, "done", "text/plain")
                    )
            );

            assertThat(harness.sentRequests).hasSize(2);
            HttpRequest redirected = harness.sentRequests.get(1);
            String raw = rawRequestText(redirected);
            assertThat(redirected.method()).isEqualTo("PUT");
            assertThat(bodyBytes(redirected)).isEqualTo(body);
            assertThat(raw).contains("Content-Type: application/json");
            assertThat(headerCount(raw, "Content-Length")).isEqualTo(1);
            assertThat(headerValue(raw, "Content-Length")).isEqualTo(String.valueOf(body.length));
        }
    }

    @Test
    void bodyDroppingRedirectRemovesEntityMetadataButKeepsAllowedHeaders() {
        for (int statusCode : List.of(301, 302, 303)) {
            Harness harness = execute(
                    request(
                            "POST",
                            "/start",
                            "api.example.test",
                            443,
                            true,
                            body("alpha"),
                            headerLine("Authorization", "Bearer secret"),
                            headerLine("Cookie", "session=abc"),
                            headerLine("Proxy-Authorization", "Basic proxy"),
                            headerLine("Content-Type", "application/json"),
                            headerLine("Content-Encoding", "gzip"),
                            headerLine("Content-Language", "en"),
                            headerLine("Content-Location", "/payload"),
                            headerLine("Content-Disposition", "attachment; filename=\"payload.bin\""),
                            headerLine("Digest", "sha-256=abc"),
                            headerLine("Content-Length", "13"),
                            headerLine("Transfer-Encoding", "chunked"),
                            headerLine("X-Custom", "keep")
                    ),
                    "https://api.example.test/start",
                    true,
                    RedirectPolicy.defaults(),
                    List.of(
                            response(statusCode, "Redirect", "/next", null, null),
                            response(200, "OK", null, "done", "text/plain")
                    )
            );

            assertThat(harness.sentRequests).hasSize(2);
            HttpRequest redirected = harness.sentRequests.get(1);
            String raw = rawRequestText(redirected);
            assertThat(requestLine(raw)).isEqualTo("GET /next HTTP/1.1");
            assertThat(bodyBytes(redirected)).isEmpty();
            assertThat(raw).contains("Authorization: Bearer secret");
            assertThat(raw).contains("Cookie: session=abc");
            assertThat(raw).contains("X-Custom: keep");
            assertThat(raw).doesNotContain("Proxy-Authorization:");
            assertThat(raw).doesNotContain("Content-Type:");
            assertThat(raw).doesNotContain("Content-Encoding:");
            assertThat(raw).doesNotContain("Content-Language:");
            assertThat(raw).doesNotContain("Content-Location:");
            assertThat(raw).doesNotContain("Content-Disposition:");
            assertThat(raw).doesNotContain("Digest:");
            assertThat(raw).doesNotContain("Content-Length:");
            assertThat(raw).doesNotContain("Transfer-Encoding:");
        }
    }

    @Test
    void invalidLocationUnsupportedSchemeLoopAndHopLimitFailPredictably() {
        Harness invalid = execute(
                request("GET", "/start", "api.example.test", 443, true),
                "https://api.example.test/start",
                true,
                RedirectPolicy.defaults(),
                List.of(response(302, "Found", "http://[invalid", null, null))
        );
        assertThat(invalid.result.success).isFalse();
        assertThat(invalid.result.terminationReason).isEqualTo(RedirectTerminationReason.INVALID_LOCATION);
        assertThat(invalid.result.redirectHops).hasSize(1);
        assertThat(invalid.result.redirectHops.get(0).followed).isFalse();

        Harness fragment = execute(
                request("GET", "/start", "api.example.test", 443, true),
                "https://api.example.test/start",
                true,
                RedirectPolicy.defaults(),
                List.of(
                        response(302, "Found", "/next#profile", null, null),
                        response(200, "OK", null, "done", "text/plain")
                )
        );
        assertThat(fragment.result.success).isTrue();
        assertThat(fragment.sentRequests).hasSize(2);
        assertThat(fragment.result.redirectHops).hasSize(1);
        assertThat(fragment.result.redirectHops.get(0).targetUrl).isEqualTo("https://api.example.test/next");
        assertThat(fragment.result.finalUrl).isEqualTo("https://api.example.test/next");

        Harness absoluteFragment = execute(
                request("GET", "/start", "api.example.test", 443, true),
                "https://api.example.test/start",
                true,
                RedirectPolicy.defaults(),
                List.of(response(302, "Found", "https://other.example.test:443/path#section", null, null), response(200, "OK", null, "done", "text/plain"))
        );
        assertThat(absoluteFragment.result.success).isTrue();
        assertThat(absoluteFragment.result.redirectHops).hasSize(1);
        assertThat(absoluteFragment.result.redirectHops.get(0).targetUrl).isEqualTo("https://other.example.test:443/path");
        assertThat(rawRequestText(absoluteFragment.sentRequests.get(1))).doesNotContain("#section");

        Harness unsupported = execute(
                request("GET", "/start", "api.example.test", 443, true),
                "https://api.example.test/start",
                true,
                RedirectPolicy.defaults(),
                List.of(response(302, "Found", "ftp://example.com/file", null, null))
        );
        assertThat(unsupported.result.success).isFalse();
        assertThat(unsupported.result.terminationReason).isEqualTo(RedirectTerminationReason.UNSUPPORTED_SCHEME);

        Harness loop = execute(
                request("GET", "/start", "api.example.test", 443, true),
                "https://api.example.test/start",
                true,
                RedirectPolicy.defaults(),
                List.of(
                        response(302, "Found", "/next", null, null),
                        response(302, "Found", "/start", null, null)
                )
        );
        assertThat(loop.result.success).isFalse();
        assertThat(loop.result.terminationReason).isEqualTo(RedirectTerminationReason.LOOP_DETECTED);
        assertThat(loop.sentRequests).hasSize(2);
        assertThat(loop.result.redirectHops).hasSize(2);
        assertThat(loop.result.redirectHops.get(1).followed).isFalse();

        RedirectPolicy limitPolicy = RedirectPolicy.defaults();
        limitPolicy.maxHops = 2;
        Harness limited = execute(
                request("GET", "/start", "api.example.test", 443, true),
                "https://api.example.test/start",
                true,
                limitPolicy,
                List.of(
                        response(302, "Found", "/one", null, null),
                        response(302, "Found", "/two", null, null),
                        response(302, "Found", "/three", null, null)
                )
        );
        assertThat(limited.result.success).isFalse();
        assertThat(limited.result.terminationReason).isEqualTo(RedirectTerminationReason.LIMIT_EXCEEDED);
        assertThat(limited.sentRequests).hasSize(3);
        assertThat(limited.result.redirectHops).hasSize(3);
        assertThat(limited.result.redirectHops.get(2).followed).isFalse();
    }

    @Test
    void fragmentOnlyRedirectLoopsAndVariantsAreCanonicalizedWithoutFragments() {
        Harness selfLoop = execute(
                request("GET", "/path", "api.example.test", 443, true),
                "https://api.example.test/path",
                true,
                RedirectPolicy.defaults(),
                List.of(response(302, "Found", "#other", null, null))
        );

        assertThat(selfLoop.result.success).isFalse();
        assertThat(selfLoop.result.terminationReason).isEqualTo(RedirectTerminationReason.LOOP_DETECTED);
        assertThat(selfLoop.sentRequests).hasSize(1);
        assertThat(selfLoop.result.redirectHops).hasSize(1);
        assertThat(selfLoop.result.redirectHops.get(0).location).isEqualTo("#other");
        assertThat(selfLoop.result.redirectHops.get(0).targetUrl).isEqualTo("https://api.example.test/path");
        assertThat(selfLoop.result.finalUrl).isEqualTo("https://api.example.test/path");

        Harness fragmentVariants = execute(
                request("GET", "/start", "api.example.test", 443, true),
                "https://api.example.test/start",
                true,
                RedirectPolicy.defaults(),
                List.of(
                        response(302, "Found", "/next#a", null, null),
                        response(302, "Found", "/start#b", null, null)
                )
        );

        assertThat(fragmentVariants.result.success).isFalse();
        assertThat(fragmentVariants.result.terminationReason).isEqualTo(RedirectTerminationReason.LOOP_DETECTED);
        assertThat(fragmentVariants.sentRequests).hasSize(2);
        assertThat(fragmentVariants.result.redirectHops).hasSize(2);
        assertThat(fragmentVariants.result.redirectHops.get(0).targetUrl).isEqualTo("https://api.example.test/next");
        assertThat(fragmentVariants.result.redirectHops.get(1).targetUrl).isEqualTo("https://api.example.test/start");
    }

    @Test
    void crossOriginPoliciesStripTrustAndPreserveHeadersAsConfigured() {
        List<String> sensitiveHeaders = List.of(
                headerLine("Authorization", "Bearer secret"),
                headerLine("Cookie", "session=abc"),
                headerLine("Proxy-Authorization", "Basic proxy"),
                headerLine("X-API-Key", "key"),
                headerLine("X-Tenant-ID", "tenant"),
                headerLine("X-Custom", "keep")
        );

        Harness stripped = execute(
                request("GET", "/start", "api.example.test", 443, true, sensitiveHeaders.toArray(String[]::new)),
                "https://api.example.test/start",
                true,
                RedirectPolicy.defaults(),
                List.of(
                        response(302, "Found", "https://other.example.test/next", null, null),
                        response(200, "OK", null, "done", "text/plain")
                )
        );
        String strippedRaw = rawRequest(stripped.sentRequests.get(1));
        assertThat(strippedRaw).doesNotContain("Authorization:");
        assertThat(strippedRaw).doesNotContain("Cookie:");
        assertThat(strippedRaw).doesNotContain("Proxy-Authorization:");
        assertThat(strippedRaw).contains("X-Custom: keep");

        RedirectPolicy trusted = RedirectPolicy.defaults();
        TrustedRedirectRule rule = new TrustedRedirectRule();
        rule.sourceOrigin = "https://api.example.test:443";
        rule.targetOrigin = "https://auth.example.test:443";
        rule.allowedHeaderNames = List.of("Authorization", "X-Tenant-ID", "Proxy-Authorization");
        trusted.trustedRules = List.of(rule);
        trusted.crossOriginMode = RedirectCrossOriginMode.TRUSTED_ORIGINS_ONLY;
        trusted.normalize();
        Harness trustedResult = execute(
                request("GET", "/start", "api.example.test", 443, true, sensitiveHeaders.toArray(String[]::new)),
                "https://api.example.test/start",
                true,
                trusted,
                List.of(
                        response(302, "Found", "https://auth.example.test/next", null, null),
                        response(200, "OK", null, "done", "text/plain")
                )
        );
        String trustedRaw = rawRequest(trustedResult.sentRequests.get(1));
        assertThat(trustedRaw).contains("Authorization: Bearer secret");
        assertThat(trustedRaw).contains("X-Tenant-ID: tenant");
        assertThat(trustedRaw).doesNotContain("Cookie:");
        assertThat(trustedRaw).doesNotContain("X-API-Key:");
        assertThat(trustedRaw).doesNotContain("Proxy-Authorization:");

        RedirectPolicy preserve = RedirectPolicy.defaults();
        preserve.crossOriginMode = RedirectCrossOriginMode.PRESERVE_ANY_HTTPS_TARGET;
        preserve.normalize();
        Harness preserveResult = execute(
                request("GET", "/start", "api.example.test", 443, true, sensitiveHeaders.toArray(String[]::new)),
                "https://api.example.test/start",
                true,
                preserve,
                List.of(
                        response(302, "Found", "https://other.example.test/next", null, null),
                        response(200, "OK", null, "done", "text/plain")
                )
        );
        String preserveRaw = rawRequest(preserveResult.sentRequests.get(1));
        assertThat(preserveRaw).contains("Authorization: Bearer secret");
        assertThat(preserveRaw).contains("Cookie: session=abc");
        assertThat(preserveRaw).doesNotContain("Proxy-Authorization:");

        Harness downgrade = execute(
                request("GET", "/start", "api.example.test", 443, true, sensitiveHeaders.toArray(String[]::new)),
                "https://api.example.test/start",
                true,
                preserve,
                List.of(
                        response(302, "Found", "http://other.example.test/next", null, null),
                        response(200, "OK", null, "done", "text/plain")
                )
        );
        String downgradeRaw = rawRequest(downgrade.sentRequests.get(1));
        assertThat(downgradeRaw).doesNotContain("Authorization:");
        assertThat(downgradeRaw).doesNotContain("Cookie:");
        assertThat(downgradeRaw).doesNotContain("Proxy-Authorization:");
    }

    @Test
    void supportedRedirectWithoutLocationAndOther3xxAreFinalResponses() {
        Harness noLocation = execute(
                request("GET", "/start", "api.example.test", 443, true),
                "https://api.example.test/start",
                true,
                RedirectPolicy.defaults(),
                List.of(response(302, "Found", null, "body", "text/plain"))
        );
        assertThat(noLocation.result.success).isTrue();
        assertThat(noLocation.result.terminationReason).isEqualTo(RedirectTerminationReason.FINAL_RESPONSE);
        assertThat(noLocation.sentRequests).hasSize(1);
        assertThat(noLocation.result.finalResponse.response().statusCode()).isEqualTo((short) 302);

        Harness other3xx = execute(
                request("GET", "/start", "api.example.test", 443, true),
                "https://api.example.test/start",
                true,
                RedirectPolicy.defaults(),
                List.of(response(304, "Not Modified", "https://example.com/next", null, null))
        );
        assertThat(other3xx.result.success).isTrue();
        assertThat(other3xx.result.terminationReason).isEqualTo(RedirectTerminationReason.FINAL_RESPONSE);
        assertThat(other3xx.sentRequests).hasSize(1);
        assertThat(other3xx.result.finalResponse.response().statusCode()).isEqualTo((short) 304);
    }

    private static Harness execute(HttpRequest request,
                                   String initialUrl,
                                   boolean followRedirects,
                                   RedirectPolicy policy,
                                   List<HttpRequestResponse> responses) {
        CopyOnWriteArrayList<HttpRequest> sent = new CopyOnWriteArrayList<>();
        AtomicInteger index = new AtomicInteger();
        RedirectExecutor executor = new RedirectExecutor();
        RedirectExecutor.RedirectRequest redirectRequest = new RedirectExecutor.RedirectRequest();
        redirectRequest.initialRequest = request;
        redirectRequest.initialUrl = initialUrl;
        redirectRequest.initialRawRequestBytes = request != null && request.toByteArray() != null ? request.toByteArray().getBytes().clone() : null;
        redirectRequest.followRedirects = followRedirects;
        redirectRequest.redirectPolicy = policy;
        redirectRequest.hopSender = hopRequest -> {
            sent.add(hopRequest);
            int responseIndex = Math.min(index.getAndIncrement(), Math.max(0, responses.size() - 1));
            return responses.get(responseIndex);
        };
        try (MockedStatic<HttpRequest> requestFactory = Mockito.mockStatic(HttpRequest.class);
             MockedStatic<burp.api.montoya.http.HttpService> serviceFactory = Mockito.mockStatic(burp.api.montoya.http.HttpService.class);
             MockedStatic<ByteArray> byteArrayFactory = Mockito.mockStatic(ByteArray.class)) {
            serviceFactory.when(() -> burp.api.montoya.http.HttpService.httpService(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean()))
                    .thenReturn(Mockito.mock(burp.api.montoya.http.HttpService.class));
            requestFactory.when(() -> HttpRequest.httpRequest(Mockito.any(burp.api.montoya.http.HttpService.class), Mockito.any(ByteArray.class)))
                    .thenAnswer(invocation -> mockRequest(((ByteArray) invocation.getArgument(1)).getBytes()));
            byteArrayFactory.when(() -> ByteArray.byteArray((byte[]) Mockito.any(byte[].class)))
                    .thenAnswer(invocation -> mockByteArray(toBytes(invocation.getArguments())));
            return new Harness(executor.execute(redirectRequest), sent);
        }
    }

    private static HttpRequest request(String method, String path, String host, int port, boolean https, String... headersAndBody) {
        byte[] raw = requestBytesFromLines(method, path, host, headersAndBody);
        return mockRequest(raw);
    }

    private static HttpRequest request(String method, String path, String host, int port, boolean https, byte[] body) {
        byte[] raw = requestBytes(method, path, host, body);
        return mockRequest(raw);
    }

    private static HttpRequest request(String method, String path, String host, int port, boolean https, byte[] body, String... headers) {
        byte[] raw = requestBytes(method, path, host, body, headers);
        return mockRequest(raw);
    }

    private static byte[] requestBytesFromLines(String method, String path, String host, String... headersAndBody) {
        StringBuilder header = new StringBuilder();
        header.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        header.append("Host: ").append(host).append("\r\n");
        byte[] body = new byte[0];
        if (headersAndBody != null) {
            for (String line : headersAndBody) {
                if (line == null) {
                    continue;
                }
                if (line.startsWith("BODY:")) {
                    body = line.substring(5).getBytes(StandardCharsets.UTF_8);
                } else {
                    header.append(line).append("\r\n");
                }
            }
        }
        header.append("\r\n");
        return concat(header.toString().getBytes(StandardCharsets.UTF_8), body);
    }

    private static byte[] requestBytes(String method, String path, String host, byte[] body, String... headers) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append("\r\n");
        if (headers != null) {
            for (String header : headers) {
                if (header != null && !header.isBlank()) {
                    sb.append(header).append("\r\n");
                }
            }
        }
        sb.append("\r\n");
        return concat(sb.toString().getBytes(StandardCharsets.UTF_8), body != null ? body : new byte[0]);
    }

    private static String body(String text) {
        return "BODY:" + text;
    }

    private static String headerLine(String name, String value) {
        return name + ": " + value;
    }

    private static HttpRequestResponse response(int statusCode, String reasonPhrase, String location, String body, String contentType) {
        HttpResponse response = Mockito.mock(HttpResponse.class);
        when(response.statusCode()).thenReturn((short) statusCode);
        when(response.reasonPhrase()).thenReturn(reasonPhrase);

        byte[] bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        ByteArray byteArray = mockByteArray(bodyBytes);
        when(response.body()).thenReturn(byteArray);
        when(response.bodyToString()).thenReturn(body != null ? body : "");

        List<HttpHeader> headers = new ArrayList<>();
        if (location != null) {
            headers.add(headerMock("Location", location));
        }
        if (contentType != null) {
            headers.add(headerMock("Content-Type", contentType));
        }
        when(response.headers()).thenReturn(headers);

        HttpRequestResponse wrapper = Mockito.mock(HttpRequestResponse.class);
        when(wrapper.response()).thenReturn(response);
        return wrapper;
    }

    private static HttpHeader headerMock(String name, String value) {
        HttpHeader header = Mockito.mock(HttpHeader.class);
        when(header.name()).thenReturn(name);
        when(header.value()).thenReturn(value);
        when(header.toString()).thenReturn(name + ": " + value);
        return header;
    }

    private static HttpRequest mockRequest(byte[] rawBytes) {
        HttpRequest request = Mockito.mock(HttpRequest.class);
        byte[] bytes = rawBytes != null ? rawBytes.clone() : new byte[0];
        ByteArray byteArray = mockByteArray(bytes);
        when(request.toByteArray()).thenReturn(byteArray);
        when(request.method()).thenReturn(parseMethod(bytes));
        when(request.toString()).thenReturn(new String(bytes, StandardCharsets.UTF_8));
        return request;
    }

    private static ByteArray mockByteArray(byte[] rawBytes) {
        ByteArray byteArray = Mockito.mock(ByteArray.class);
        byte[] bytes = rawBytes != null ? rawBytes.clone() : new byte[0];
        when(byteArray.getBytes()).thenReturn(bytes);
        when(byteArray.length()).thenReturn(bytes.length);
        return byteArray;
    }

    private static byte[] toBytes(Object[] values) {
        if (values == null || values.length == 0) {
            return new byte[0];
        }
        if (values.length == 1 && values[0] instanceof byte[] bytes) {
            return bytes;
        }
        if (values.length == 1 && values[0] instanceof Byte[] boxed) {
            byte[] bytes = new byte[boxed.length];
            for (int i = 0; i < boxed.length; i++) {
                bytes[i] = boxed[i] != null ? boxed[i] : 0;
            }
            return bytes;
        }
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value instanceof Byte single) {
                bytes[i] = single;
            } else if (value instanceof Number number) {
                bytes[i] = number.byteValue();
            } else if (value instanceof byte[] raw && raw.length > 0) {
                bytes[i] = raw[0];
            } else {
                bytes[i] = 0;
            }
        }
        return bytes;
    }

    private static String parseMethod(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) {
            return "GET";
        }
        String raw = new String(rawBytes, StandardCharsets.UTF_8);
        int space = raw.indexOf(' ');
        return space > 0 ? raw.substring(0, space) : "GET";
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] a = left != null ? left : new byte[0];
        byte[] b = right != null ? right : new byte[0];
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String rawRequest(HttpRequest request) {
        return request != null && request.toByteArray() != null
                ? new String(request.toByteArray().getBytes(), StandardCharsets.UTF_8)
                : "";
    }

    private static String rawRequestText(HttpRequest request) {
        return request != null && request.toByteArray() != null
                ? new String(request.toByteArray().getBytes(), StandardCharsets.ISO_8859_1)
                : "";
    }

    private static byte[] bodyBytes(HttpRequest request) {
        byte[] raw = request != null && request.toByteArray() != null ? request.toByteArray().getBytes() : new byte[0];
        String rawText = new String(raw, StandardCharsets.ISO_8859_1);
        int separator = rawText.indexOf("\r\n\r\n");
        if (separator < 0) {
            separator = rawText.indexOf("\n\n");
            if (separator < 0) {
                return new byte[0];
            }
            return rawText.substring(separator + 2).getBytes(StandardCharsets.ISO_8859_1);
        }
        return rawText.substring(separator + 4).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String requestLine(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        int newline = raw.indexOf("\r\n");
        return newline >= 0 ? raw.substring(0, newline) : raw;
    }

    private static long headerCount(String raw, String name) {
        return headerValues(raw, name).size();
    }

    private static List<String> headerValues(String raw, String name) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.isBlank() || name == null || name.isBlank()) {
            return values;
        }
        String[] lines = raw.replace("\r", "").split("\n");
        String prefix = name.toLowerCase(Locale.ROOT) + ":";
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) {
                break;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith(prefix)) {
                values.add(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        return values;
    }

    private static String headerValue(String raw, String name) {
        List<String> values = headerValues(raw, name);
        return values.isEmpty() ? "" : values.get(0);
    }

    private record Harness(RedirectExecutor.RedirectResult result, List<HttpRequest> sentRequests) {
    }
}

