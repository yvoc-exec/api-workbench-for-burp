package burp.utils;

import burp.models.ApiRequest;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBuilderParameterTest {
    private final RequestBuilder builder = new RequestBuilder(null);

    @Test
    void builtRequestTargetIncludesActiveQueryParametersOnce() throws Exception {
        ApiRequest request = request("https://example.test/a");
        request.parameters.add(parameter("x", "1", true, false));
        assertThat(target(request, new VariableResolver())).isEqualTo("GET /a?x=1 HTTP/1.1");
    }

    @Test
    void existingStaleUrlQueryIsReplacedByFirstClassParameters() throws Exception {
        ApiRequest request = request("https://example.test/a?stale=1");
        request.parameters.add(parameter("x", "1", true, false));
        assertThat(target(request, new VariableResolver())).isEqualTo("GET /a?x=1 HTTP/1.1");
    }

    @Test
    void disabledParametersAreOmitted() throws Exception {
        ApiRequest request = request("https://example.test/a");
        request.parameters.add(parameter("keep", "yes", true, false));
        request.parameters.add(parameter("skip", "x", true, true));
        assertThat(target(request, new VariableResolver())).isEqualTo("GET /a?keep=yes HTTP/1.1");
    }

    @Test
    void duplicateKeysRetainOrder() throws Exception {
        ApiRequest request = request("https://example.test/a");
        request.parameters.add(parameter("tag", "one", true, false));
        request.parameters.add(parameter("tag", "two", true, false));
        assertThat(target(request, new VariableResolver())).isEqualTo("GET /a?tag=one&tag=two HTTP/1.1");
    }

    @Test
    void bareAndExplicitlyEmptyValuesProduceDifferentTargets() throws Exception {
        ApiRequest bare = request("https://example.test/a");
        bare.parameters.add(parameter("flag", "", false, false));
        ApiRequest empty = request("https://example.test/a");
        empty.parameters.add(parameter("flag", "", true, false));
        assertThat(target(bare, new VariableResolver())).isEqualTo("GET /a?flag HTTP/1.1");
        assertThat(target(empty, new VariableResolver())).isEqualTo("GET /a?flag= HTTP/1.1");
    }

    @Test
    void variableValuesResolveBeforeTransportRendering() throws Exception {
        ApiRequest request = request("https://{{host}}/a");
        request.parameters.add(parameter("q", "{{value}}", true, false));
        VariableResolver resolver = new VariableResolver();
        resolver.addCustomVariable("host", "example.test");
        resolver.addCustomVariable("value", "a/b c");
        assertThat(target(request, resolver)).isEqualTo("GET /a?q=a%2Fb%20c HTTP/1.1");
    }

    @Test
    void emptyParameterListsPreserveLegacyUrlBehavior() throws Exception {
        ApiRequest request = request("https://example.test/a?legacy=hello%20world");
        assertThat(target(request, new VariableResolver())).isEqualTo("GET /a?legacy=hello%20world HTTP/1.1");
    }

    private String target(ApiRequest request, VariableResolver resolver) throws Exception {
        return new String(builder.buildRequest(request, resolver), StandardCharsets.UTF_8).split("\\r\\n", 2)[0];
    }

    private static ApiRequest request(String url) {
        ApiRequest request = new ApiRequest();
        request.name = "R";
        request.method = "GET";
        request.url = url;
        return request;
    }

    private static ApiRequest.Parameter parameter(String key, String value, boolean present, boolean disabled) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter("query", key, value);
        parameter.valuePresent = present;
        parameter.disabled = disabled;
        return parameter;
    }
}
