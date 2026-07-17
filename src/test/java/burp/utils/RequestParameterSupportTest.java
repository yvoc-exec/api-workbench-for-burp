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

    @Test
    void parsesAndMaterializesEmptyKeysAndEmptySegments() {
        String url = "https://example.test/a?=x&flag&&tail=&";
        List<ApiRequest.Parameter> parameters = RequestParameterSupport.parseQueryParameters(url, "test");

        assertThat(parameters).hasSize(5);
        assertThat(parameters).extracting(p -> p.key).containsExactly("", "flag", "", "tail", "");
        assertThat(parameters).extracting(p -> p.value).containsExactly("x", "", "", "", "");
        assertThat(parameters).extracting(p -> p.valuePresent)
                .containsExactly(true, false, false, true, false);
        assertThat(RequestParameterSupport.materializeUrl(url, parameters, null)).isEqualTo(url);
    }

    @Test
    void preservesTerminalQuestionMarkAsEmptyBareSegment() {
        String url = "https://example.test/a?";
        List<ApiRequest.Parameter> parameters = RequestParameterSupport.parseQueryParameters(url, "test");

        assertThat(parameters).hasSize(1);
        assertThat(parameters.get(0).key).isEmpty();
        assertThat(parameters.get(0).valuePresent).isFalse();
        assertThat(RequestParameterSupport.materializeUrl(url, parameters, null)).isEqualTo(url);
    }

    @Test
    void preservesLeadingTrailingAndConsecutiveEmptySegments() {
        String url = "https://example.test/a?&x=1&&";
        List<ApiRequest.Parameter> parameters = RequestParameterSupport.parseQueryParameters(url, "test");

        assertThat(parameters).hasSize(4);
        assertThat(parameters).extracting(p -> p.key).containsExactly("", "x", "", "");
        assertThat(RequestParameterSupport.materializeUrl(url, parameters, null)).isEqualTo(url);
    }

    @Test
    void disabledEmptySegmentsAreOmittedWithoutCorruptingSeparators() {
        ApiRequest.Parameter first = parameter("", "", false);
        ApiRequest.Parameter disabled = parameter("", "", false);
        disabled.disabled = true;
        ApiRequest.Parameter last = parameter("k", "v", true);
        ApiRequest.Parameter trailing = parameter("", "", false);

        assertThat(RequestParameterSupport.materializeUrl(
                "https://example.test/a", List.of(first, disabled, last, trailing), null))
                .isEqualTo("https://example.test/a?&k=v&");
    }

    @Test
    void normalizesSupportedAndUnknownLocationsWithoutLosingMetadata() {
        ApiRequest.Parameter unknown = new ApiRequest.Parameter("  Vendor-Custom  ", "k", "v");
        assertThat(RequestParameterSupport.normalizeLocation(null)).isEqualTo("query");
        assertThat(RequestParameterSupport.normalizeLocation("  ")).isEqualTo("query");
        assertThat(RequestParameterSupport.normalizeLocation(" HEADER ")).isEqualTo("header");
        assertThat(RequestParameterSupport.normalizeLocation(unknown.location)).isEqualTo("vendor-custom");
        assertThat(RequestParameterSupport.isLocation(unknown, "VENDOR-CUSTOM")).isTrue();
        assertThat(RequestParameterSupport.hasParametersAtLocation(List.of(unknown), "header")).isFalse();
    }

    @Test
    void materializeRequestUrlReplacesOnlyCompletePathPlaceholders() {
        ApiRequest.Parameter id = new ApiRequest.Parameter("path", "id", "42");
        id.valuePresent = true;
        assertThat(RequestParameterSupport.materializeRequestUrl(
                "https://id.example/{id}/{{id}}/:id/prefix:id?x=:id#id", List.of(id), null))
                .isEqualTo("https://id.example/42/42/42/prefix:id?x=:id#id");
    }

    @Test
    void postmanRawMaterializationResolvesOrdinaryVariablesButPreservesPathTemplates() {
        VariableResolver resolver = new VariableResolver();
        resolver.addCustomVariable("baseUrl", "https://api.example.test");
        resolver.addCustomVariable("id", "99");
        ApiRequest.Parameter path = new ApiRequest.Parameter("path", "id", "42");
        path.valuePresent = true;
        ApiRequest.Parameter query = parameter("filter", "{{id}}", true);

        assertThat(RequestParameterSupport.materializePostmanRawUrl(
                "{{baseUrl}}/users/{{id}}?stale={{id}}", List.of(path, query), resolver))
                .isEqualTo("https://api.example.test/users/{{id}}?filter=99");
        assertThat(RequestParameterSupport.materializePostmanRawUrl(
                "https://{{id}}.example.test/users/{{id}}", List.of(path), resolver))
                .isEqualTo("https://99.example.test/users/{{id}}");
    }

    private static ApiRequest.Parameter parameter(String key, String value, boolean valuePresent) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter("query", key, value);
        parameter.valuePresent = valuePresent;
        return parameter;
    }
}
