package burp.utils;

import burp.models.ApiRequest;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RequestParameterSupportTest {
    @Test
    void parsesDuplicatesRawEncodingBareAndEmptyValues() {
        List<ApiRequest.Parameter> parameters = RequestParameterSupport.parseQueryParameters(
                "https://example.test/items?flag&empty=&tag=one&tag=two&encoded=a%2Fb&space=hello%20world#section",
                "test");
        assertThat(parameters).extracting(p -> p.key)
                .containsExactly("flag", "empty", "tag", "tag", "encoded", "space");
        assertThat(parameters).extracting(p -> p.value)
                .containsExactly("", "", "one", "two", "a/b", "hello world");
        assertThat(parameters).extracting(p -> p.valuePresent)
                .containsExactly(false, true, true, true, true, true);
        assertThat(parameters.get(4).rawValue).isEqualTo("a%2Fb");
        assertThat(parameters.get(5).rawValue).isEqualTo("hello%20world");
    }

    @Test
    void materializesOnlyEnabledQueryParametersInOriginalOrder() {
        ApiRequest.Parameter first = parameter("tag", "one", true);
        ApiRequest.Parameter disabled = parameter("skip", "yes", true);
        disabled.disabled = true;
        ApiRequest.Parameter second = parameter("tag", "two", true);
        assertThat(RequestParameterSupport.materializeUrl("https://example.test/items", List.of(first, disabled, second), null))
                .isEqualTo("https://example.test/items?tag=one&tag=two");
    }

    @Test
    void replacesExistingUrlQueryInsteadOfDuplicatingIt() {
        assertThat(RequestParameterSupport.materializeUrl("https://example.test/a?stale=1", List.of(parameter("fresh", "2", true)), null))
                .isEqualTo("https://example.test/a?fresh=2");
    }

    @Test
    void preservesFragmentAfterMaterializedQuery() {
        assertThat(RequestParameterSupport.materializeUrl("https://example.test/a?old=1#frag", List.of(parameter("x", "1", true)), null))
                .isEqualTo("https://example.test/a?x=1#frag");
    }

    @Test
    void resolvesVariablesBeforeRendering() {
        VariableResolver resolver = new VariableResolver();
        resolver.addCustomVariable("host", "example.test");
        resolver.addCustomVariable("value", "a/b c");
        assertThat(RequestParameterSupport.materializeUrl("https://{{host}}/a", List.of(parameter("q", "{{value}}", true)), resolver))
                .isEqualTo("https://example.test/a?q=a%2Fb%20c");
    }

    @Test
    void preservesUnresolvedTemplateTokens() {
        assertThat(RequestParameterSupport.materializeUrl("https://example.test/a", List.of(parameter("q", "{{missing}}", true)), new VariableResolver()))
                .isEqualTo("https://example.test/a?q={{missing}}");
    }

    @Test
    void leavesUrlUnchangedWhenNoQueryParametersExist() {
        assertThat(RequestParameterSupport.materializeUrl("https://example.test/a?x=1#f", List.of(), null))
                .isEqualTo("https://example.test/a?x=1#f");
    }

    @Test
    void ignoresNonQueryParameterLocations() {
        ApiRequest.Parameter path = new ApiRequest.Parameter("path", "id", "1");
        assertThat(RequestParameterSupport.hasQueryParameters(List.of(path))).isFalse();
        assertThat(RequestParameterSupport.materializeUrl("https://example.test/a?x=1", List.of(path), null))
                .isEqualTo("https://example.test/a?x=1");
    }

    @Test
    void doesNotThrowOnMalformedPercentEncoding() {
        assertThatCode(() -> RequestParameterSupport.parseQueryParameters("https://example.test/?bad=%ZZ&short=%2", "test"))
                .doesNotThrowAnyException();
        assertThat(RequestParameterSupport.parseQueryParameters("https://example.test/?bad=%ZZ", "test").get(0).value)
                .isEqualTo("%ZZ");
    }

    private static ApiRequest.Parameter parameter(String key, String value, boolean valuePresent) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter("query", key, value);
        parameter.valuePresent = valuePresent;
        return parameter;
    }
}
