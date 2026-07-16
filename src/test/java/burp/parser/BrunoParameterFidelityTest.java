package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.RequestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BrunoParameterFidelityTest {
    @TempDir Path tempDir;

    @Test
    void importsStructuredQueryAndPathParametersInSourceOrder() throws Exception {
        ApiRequest request = parse("""
                get {
                  url: https://example.test/users/:id?raw=one&encoded=a%2Fb
                }
                params:query {
                  encoded: a/b
                  ~disabled: hidden
                  duplicate: one
                  duplicate: two
                }
                params:path {
                  id: 42
                }
                """);
        assertThat(request.parameters).extracting(p -> p.location + ":" + p.key + "=" + p.value)
                .containsExactly("query:encoded=a/b", "query:disabled=hidden", "query:duplicate=one",
                        "query:duplicate=two", "query:raw=one", "path:id=42");
        assertThat(request.parameters.get(0).rawValue).isEqualTo("a%2Fb");
        assertThat(request.parameters.get(1).disabled).isTrue();
        assertThat(request.parameters.get(4).source).isEqualTo("bruno:url.raw-unmatched");
        assertThat(request.url).isEqualTo("https://example.test/users/:id?encoded=a%2Fb&duplicate=one&duplicate=two&raw=one");
    }

    @Test
    void structuredDisabledQueryDoesNotReactivateMatchingRawRow() throws Exception {
        ApiRequest request = parse("""
                get { url: https://example.test/a?skip=x }
                params:query { ~skip: x }
                """);
        assertThat(request.parameters).singleElement().satisfies(p -> {
            assertThat(p.key).isEqualTo("skip");
            assertThat(p.disabled).isTrue();
        });
        assertThat(request.url).isEqualTo("https://example.test/a");
    }

    @Test
    void preservesBareEmptyDuplicateAndEmptyKeyRawSegments() throws Exception {
        ApiRequest request = parse("get { url: https://example.test/a?flag&empty=&tag=one&tag=two&=x&& }");
        assertThat(request.parameters).hasSize(7);
        assertThat(request.parameters).extracting(p -> p.key)
                .containsExactly("flag", "empty", "tag", "tag", "", "", "");
        assertThat(request.parameters).extracting(p -> p.valuePresent)
                .containsExactly(false, true, true, true, true, false, false);
        assertThat(request.url).isEqualTo("https://example.test/a?flag&empty=&tag=one&tag=two&=x&&");
    }

    @Test
    void importsQuotedEmptyWhitespaceAndColonKeys() throws Exception {
        ApiRequest request = parse("""
                get { url: https://example.test/a }
                params:query {
                  "": empty
                  " ": space
                  "key:with:colon": value
                }
                """);
        assertThat(request.parameters).extracting(p -> p.key).containsExactly("", " ", "key:with:colon");
        assertThat(request.url).isEqualTo("https://example.test/a?=empty&%20=space&key%3Awith%3Acolon=value");
    }

    @Test
    void importsDisabledPathParametersWithoutMaterializingThem() throws Exception {
        ApiRequest request = parse("""
                get { url: https://example.test/users/:id }
                params:path { ~id: 42 }
                """);
        assertThat(request.parameters).singleElement().satisfies(p -> {
            assertThat(p.location).isEqualTo("path");
            assertThat(p.disabled).isTrue();
        });
        assertThat(request.url).isEqualTo("https://example.test/users/:id");
    }

    @Test
    void requestBuilderUsesImportedBrunoQueryParametersOnce() throws Exception {
        ApiRequest request = parse("""
                get {
                  url: https://example.test/a?one=1
                }
                params:query {
                  one: 1
                  one: 2
                  ~skip: x
                }
                """);
        String raw = new String(new RequestBuilder(null).buildRequest(request, new VariableResolver()), StandardCharsets.UTF_8);
        assertThat(raw).contains("GET /a?one=1&one=2 HTTP/1.1");
        assertThat(raw).doesNotContain("skip=x");
    }

    private ApiRequest parse(String source) throws Exception {
        Path file = tempDir.resolve("request-" + System.nanoTime() + ".bru");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        ApiCollection collection = new BrunoParser().parse(file.toFile());
        return collection.requests.get(0);
    }
}
