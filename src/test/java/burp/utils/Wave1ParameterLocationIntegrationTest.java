package burp.utils;

import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Wave1ParameterLocationIntegrationTest {
    private final RequestBuilder builder = new RequestBuilder(null);

    @Test
    void runtimeUrlPreservesQueryFidelityAndMaterializesAllPathForms() {
        ApiRequest.Parameter first = parameter("query", "tag", "one", true);
        ApiRequest.Parameter second = parameter("query", "tag", "two", true);
        ApiRequest.Parameter disabled = parameter("query", "skip", "x", true);
        disabled.disabled = true;
        ApiRequest.Parameter bare = parameter("query", "flag", "", false);
        ApiRequest.Parameter encoded = parameter("query", "encoded", "a/b", true);
        encoded.rawValue = "a%2Fb";
        ApiRequest.Parameter path = parameter("path", "id", "a b", true);

        String materialized = RequestParameterSupport.materializeRequestUrl(
                "https://example.test/{id}/{{id}}/:id?old=1#frag",
                List.of(first, second, disabled, bare, encoded, path),
                null);

        assertThat(materialized).isEqualTo(
                "https://example.test/a%20b/a%20b/a%20b?tag=one&tag=two&flag&encoded=a%2Fb#frag");
    }

    @Test
    void pathReplacementHonorsDisabledRawEncodingScopeAndAllowReserved() {
        ApiRequest.Parameter disabled = parameter("path", "off", "changed", true);
        disabled.disabled = true;
        ApiRequest.Parameter raw = parameter("path", "raw", "a/b", true);
        raw.rawValue = "a%2Fb";
        ApiRequest.Parameter changed = parameter("path", "changed", "a/b ?#", true);
        changed.rawValue = "stale";
        ApiRequest.Parameter reserved = parameter("path", "reserved", "a/b?#", true);
        reserved.allowReserved = true;
        ApiRequest.Parameter query = parameter("query", "q", "1", true);

        String materialized = RequestParameterSupport.materializeRequestUrl(
                "https://{off}.test/{off}/{raw}/{changed}/{reserved}/pre:raw?x={raw}#f-{raw}",
                List.of(disabled, raw, changed, reserved, query),
                null);

        assertThat(materialized).isEqualTo(
                "https://{off}.test/{off}/a%2Fb/a%2Fb%20%3F%23/a/b?#/pre:raw?q=1#f-{raw}");
    }

    @Test
    void firstEnabledDuplicatePathParameterWinsAndPlusIsNotDecodedAsSpace() {
        ApiRequest.Parameter disabled = parameter("path", "id", "disabled", true);
        disabled.disabled = true;
        ApiRequest.Parameter first = parameter("path", "id", "a+b", true);
        first.rawValue = "a+b";
        ApiRequest.Parameter later = parameter("path", "id", "later", true);

        assertThat(RequestParameterSupport.materializeRequestUrl(
                "https://example.test/:id", List.of(disabled, first, later), null))
                .isEqualTo("https://example.test/a%2Bb");
    }

    @Test
    void headerAndCookieParametersParticipateInOrderedPrecedenceAndFiltering() throws Exception {
        ApiRequest request = request("http://example.test/items");
        request.headers.add(new ApiRequest.Header("Cookie", "explicit=1"));
        request.parameters.add(parameter("header", "Accept", "application/custom", true));
        request.parameters.add(parameter("header", "X-Dupe", "one", true));
        request.parameters.add(parameter("header", "X-Dupe", "two", true));
        ApiRequest.Parameter disabled = parameter("header", "X-Off", "no", true);
        disabled.disabled = true;
        request.parameters.add(disabled);
        request.parameters.add(parameter("header", "Content-Length", "999", true));
        request.parameters.add(parameter("header", "Connection", "X-Nominated", true));
        request.parameters.add(parameter("header", "X-Nominated", "drop", true));
        request.parameters.add(parameter("cookie", "bare", "", false));
        request.parameters.add(parameter("cookie", "empty", "", true));
        request.parameters.add(parameter("cookie", "last", "a/b", true));
        request.auth = new ApiRequest.Auth();
        request.auth.type = "cookie";
        request.auth.properties.put("value", "auth=3");

        String raw = new String(builder.buildRequest(request, new VariableResolver()), StandardCharsets.UTF_8);

        assertThat(raw).containsSubsequence(
                "Cookie: explicit=1", "Accept: application/custom", "X-Dupe: one", "X-Dupe: two",
                "Cookie: bare; empty=; last=a/b", "Cookie: auth=3", "Host: example.test");
        assertThat(raw).doesNotContain("X-Off:", "Content-Length: 999", "Connection:", "X-Nominated:");
        assertThat(raw).contains("Accept: application/custom");
        assertThat(raw).doesNotContain("Accept: application/json");
    }

    @Test
    void headerParameterPreventsAuthenticationReplacement() throws Exception {
        ApiRequest request = request("http://example.test/items");
        request.parameters.add(parameter("header", "Authorization", "Custom token", true));
        request.auth = new ApiRequest.Auth();
        request.auth.type = "bearer";
        request.auth.properties.put("token", "generated");

        String raw = new String(builder.buildRequest(request, null), StandardCharsets.UTF_8);
        assertThat(raw).contains("Authorization: Custom token").doesNotContain("Bearer generated");
    }

    @Test
    void pristineExactSnapshotIsByteIdenticalAndInvalidatedSnapshotRebuildsParameters() throws Exception {
        ApiRequest request = request("http://example.test/{id}");
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.parameters.add(parameter("path", "id", "42", true));
        request.parameters.add(parameter("header", "X-Param", "yes", true));
        request.parameters.add(parameter("cookie", "session", "abc", true));
        request.exactHttpRequest = new ExactHttpRequestSnapshot();
        request.exactHttpRequest.rawRequestBytes =
                "GET /original HTTP/1.1\r\nHost: original.test\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        request.exactHttpRequest.pristine = true;

        assertThat(builder.buildRequest(request, null)).isEqualTo(request.exactHttpRequest.rawRequestBytes);

        request.exactHttpRequest.pristine = false;
        String rebuilt = new String(builder.buildRequest(request, null), StandardCharsets.UTF_8);
        assertThat(rebuilt).startsWith("GET /42 HTTP/1.1\r\n")
                .contains("X-Param: yes", "Cookie: session=abc");
    }

    private static ApiRequest request(String url) {
        ApiRequest request = new ApiRequest();
        request.method = "GET";
        request.url = url;
        return request;
    }

    private static ApiRequest.Parameter parameter(String location,
                                                  String key,
                                                  String value,
                                                  boolean valuePresent) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter(location, key, value);
        parameter.valuePresent = valuePresent;
        return parameter;
    }
}
