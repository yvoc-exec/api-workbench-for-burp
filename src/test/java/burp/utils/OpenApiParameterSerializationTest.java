package burp.utils;

import burp.models.ApiRequest;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiParameterSerializationTest {
    private final VariableResolver resolver = new VariableResolver();

    @Test
    void serializesQueryStyleMatrixExactly() {
        assertTarget(query("form", true, "array", "[\"a\",\"b\"]"), "/x?q=a&q=b");
        assertTarget(query("form", false, "object", "{\"a\":\"1\",\"b\":\"2\"}"), "/x?q=a,1,b,2");
        assertTarget(query("spaceDelimited", false, "array", "[\"a\",\"b\"]"), "/x?q=a%20b");
        assertTarget(query("pipeDelimited", false, "array", "[\"a\",\"b\"]"), "/x?q=a|b");
        assertTarget(query("deepObject", true, "object", "{\"a\":\"1\"}"), "/x?q[a]=1");
    }

    @Test
    void serializesPathSimpleLabelAndMatrixExactly() {
        assertPath(path("simple", true, "object", "{\"a\":\"1\",\"b\":\"2\"}"), "/x/a=1,b=2");
        assertPath(path("label", true, "array", "[\"a\",\"b\"]"), "/x/.a.b");
        assertPath(path("matrix", false, "object", "{\"a\":\"1\"}"), "/x/;id=a,1");
    }

    @Test
    void serializesHeaderSimpleCookieFormAndAllowReserved() {
        ApiRequest.Parameter header = parameter("header", "X", "{\"a\":\"1\",\"b\":\"2\"}", "object", "simple", true);
        assertThat(RequestParameterSupport.serializeHeaderValue(header, resolver)).isEqualTo("a=1,b=2");
        ApiRequest.Parameter cookie = parameter("cookie", "c", "{\"a\":\"1\",\"b\":\"2\"}", "object", "form", true);
        assertThat(RequestParameterSupport.serializeCookieParts(cookie, resolver)).containsExactly("a=1", "b=2");
        ApiRequest.Parameter reserved = query("form", true, "array", "[\"a/b?c\"]");
        reserved.allowReserved = true;
        assertTarget(reserved, "/x?q=a/b?c");
    }

    @Test
    void malformedStructuredValuesAndUnstyledScalarsKeepWaveOneBehavior() {
        ApiRequest.Parameter malformed = query("form", true, "array", "not-json");
        malformed.rawValue = "not-json";
        assertTarget(malformed, "/x?q=not-json");
        ApiRequest.Parameter scalar = new ApiRequest.Parameter("query", "flag", "");
        scalar.valuePresent = false;
        assertTarget(scalar, "/x?flag");
    }

    private void assertTarget(ApiRequest.Parameter parameter, String expected) {
        String url = RequestParameterSupport.materializeRequestUrl("https://example.test/x", List.of(parameter), resolver);
        assertThat(url).isEqualTo("https://example.test" + expected);
    }

    private void assertPath(ApiRequest.Parameter parameter, String expected) {
        String url = RequestParameterSupport.materializeRequestUrl("https://example.test/x/{id}", List.of(parameter), resolver);
        assertThat(url).isEqualTo("https://example.test" + expected);
    }

    private static ApiRequest.Parameter query(String style, boolean explode, String type, String value) {
        return parameter("query", "q", value, type, style, explode);
    }

    private static ApiRequest.Parameter path(String style, boolean explode, String type, String value) {
        return parameter("path", "id", value, type, style, explode);
    }

    private static ApiRequest.Parameter parameter(String location, String key, String value, String type, String style, boolean explode) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter(location, key, value);
        parameter.type = type; parameter.style = style; parameter.explode = explode; parameter.valuePresent = true;
        return parameter;
    }
}
