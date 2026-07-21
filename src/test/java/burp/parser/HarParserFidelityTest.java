package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.HarMetadataSupport;
import burp.utils.RequestBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HarParserFidelityTest {
    @TempDir Path tempDir;

    @Test
    void importsOrderedQueryHeadersCookiesBodyAndHttp10Snapshot() throws Exception {
        ApiCollection collection = parse("ordered.har", """
                {"request":{"method":"POST","url":"https://example.test:8443/p?a=one&a=two%20words&flag",
                "httpVersion":"HTTP/1.0","headers":[{"name":"X-Dupe","value":"1"},{"name":"X-Dupe","value":"2"},{"name":"Cookie","value":"sid=x; theme=dark"}],
                "queryString":[{"name":"a","value":"one"},{"name":"a","value":"two words"},{"name":"flag"}],
                "cookies":[{"name":"sid","value":"x"},{"name":"theme","value":"dark"}],
                "postData":{"mimeType":"application/json","text":"{\\"ok\\":true}"},"bodySize":11},"response":{"status":204}}
                """);
        ApiRequest request = collection.requests.get(0);
        assertThat(request.url).isEqualTo("https://example.test:8443/p");
        assertThat(request.parameters).extracting(p -> p.key).containsExactly("a", "a", "flag");
        assertThat(request.parameters.get(1).rawValue).isEqualTo("two%20words");
        assertThat(request.parameters).noneMatch(p -> "cookie".equals(p.location));
        assertThat(request.headers).extracting(h -> h.key).containsExactly("X-Dupe", "X-Dupe", "Cookie");
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.exactHttpRequest).isNotNull();
        String raw = new String(request.exactHttpRequest.rawRequestBytes, StandardCharsets.UTF_8);
        assertThat(raw).startsWith("POST /p?a=one&a=two%20words&flag HTTP/1.0\r\n")
                .contains("X-Dupe: 1\r\nX-Dupe: 2\r\n");
        assertThat(request.exactHttpRequest.serviceHost).isEqualTo("example.test");
        assertThat(request.exactHttpRequest.servicePort).isEqualTo(8443);
        assertThat(request.exactHttpRequest.secure).isTrue();
        assertThat(request.exactHttpRequest.semanticFingerprint)
                .isEqualTo(request.sourceMetadata.get(HarMetadataSupport.REQUEST_FINGERPRINT));
    }

    @Test
    void urlQueryWinsOnStructuredMismatchWithoutLeakingValues() throws Exception {
        ApiCollection collection = parse("mismatch.har", """
                {"request":{"method":"GET","url":"https://example.test/p?k=url-secret","httpVersion":"HTTP/1.1",
                "headers":[],"queryString":[{"name":"k","value":"CANARY-DO-NOT-LEAK"}]},"response":{"status":200}}
                """);
        assertThat(collection.requests.get(0).parameters.get(0).value).isEqualTo("url-secret");
        assertThat(collection.importWarnings).isNotEmpty();
        assertThat(collection.importWarnings).allSatisfy(w -> {
            assertThat(w).doesNotContain("CANARY-DO-NOT-LEAK", "\r", "\n", "url-secret");
        });
    }

    @Test
    void structuredQueryIsUsedWhenUrlHasNoQuery() throws Exception {
        ApiCollection collection = parse("structured.har", """
                {"request":{"method":"GET","url":"https://example.test/p","httpVersion":"HTTP/1.1","headers":[],
                "queryString":[{"name":"a","value":"1"},{"name":"flag"}]},"response":{"status":200}}
                """);
        ApiRequest request = collection.requests.get(0);
        assertThat(request.parameters).hasSize(2);
        assertThat(collection.importWarnings).anyMatch(w -> w.contains("URL had no query"));
        String raw = new String(new RequestBuilder(null).buildRequest(request, null), StandardCharsets.UTF_8);
        assertThat(raw).startsWith("GET /p?a=1&flag HTTP/1.1");
        assertThat(count(raw, "?a=1&flag")).isEqualTo(1);
    }

    @Test
    void cookiesBecomeParametersOnlyWithoutCookieHeader() throws Exception {
        ApiRequest request = parse("cookies.har", """
                {"request":{"method":"GET","url":"https://example.test/p","httpVersion":"HTTP/1.1","headers":[],
                "cookies":[{"name":"x","value":"1","path":"/","secure":true},{"name":"x","value":"2","vendor":7}]},"response":{"status":200}}
                """).requests.get(0);
        assertThat(request.parameters).extracting(p -> p.key).containsExactly("x", "x");
        assertThat(request.parameters).allMatch(p -> "cookie".equals(p.location));
        assertThat(request.parameters.get(0).sourceMetadata.get(HarMetadataSupport.COOKIE_ROW_ORIGINAL)).contains("secure");
    }

    @Test
    void importsUrlencodedAndMultipartFileMetadataWithoutInventingLocalPath() throws Exception {
        ApiCollection collection = parse("forms.har", """
                {"request":{"method":"POST","url":"https://example.test/a","httpVersion":"HTTP/1.1","headers":[],
                "postData":{"mimeType":"application/x-www-form-urlencoded","params":[{"name":"a","value":"1"},{"name":"a","value":"2"}]}},"response":{"status":200}},
                {"request":{"method":"POST","url":"https://example.test/b","httpVersion":"HTTP/1.1","headers":[],
                "postData":{"mimeType":"multipart/form-data","params":[{"name":"note","value":"x"},{"name":"upload","value":"inline","fileName":"source.bin","contentType":"application/octet-stream","comment":"c"}]}},"response":{"status":200}}
                """);
        assertThat(collection.requests.get(0).body.mode).isEqualTo("urlencoded");
        assertThat(collection.requests.get(0).body.urlencoded).extracting(f -> f.key).containsExactly("a", "a");
        ApiRequest.Body.FormField file = collection.requests.get(1).body.formdata.get(1);
        assertThat(file.fileUpload).isTrue();
        assertThat(file.type).isEqualTo("file");
        assertThat(file.filePath).isNull();
        assertThat(file.value).isEqualTo("inline");
        assertThat(file.sourceMetadata.get(HarMetadataSupport.POST_PARAM_ORIGINAL)).contains("source.bin");
    }

    @Test
    void textWinsWhenTextAndParamsAreBothPresent() throws Exception {
        ApiCollection collection = parse("both.har", """
                {"request":{"method":"POST","url":"https://example.test/p","httpVersion":"HTTP/1.1","headers":[],
                "postData":{"mimeType":"text/plain","text":"authoritative","params":[{"name":"ignored","value":"x"}]}},"response":{"status":200}}
                """);
        assertThat(collection.requests.get(0).body.raw).isEqualTo("authoritative");
        assertThat(collection.requests.get(0).exactHttpRequest).isNotNull();
        assertThat(collection.importWarnings).anyMatch(w -> w.contains("both text and params"));
    }

    @Test
    void paramsOnlyAndHttp2RequestsDoNotClaimExactSnapshots() throws Exception {
        ApiCollection collection = parse("nonexact.har", """
                {"request":{"method":"POST","url":"https://example.test/a","httpVersion":"HTTP/1.1","headers":[],"postData":{"mimeType":"application/x-www-form-urlencoded","params":[{"name":"a","value":"1"}]}},"response":{"status":200}},
                {"request":{"method":"POST","url":"https://example.test/b","httpVersion":"HTTP/2","headers":[],"postData":{"mimeType":"text/plain","text":"CANARY-BODY"}},"response":{"status":200}}
                """);
        assertThat(collection.requests).allSatisfy(r -> {
            assertThat(r.exactHttpRequest).isNull();
            assertThat(r.buildMode).isEqualTo(ApiRequest.BuildMode.AUTO_COMPATIBLE);
        });
        assertThat(collection.importWarnings).isNotEmpty().allSatisfy(w -> assertThat(w).doesNotContain("CANARY-BODY", "\r", "\n"));
    }

    @Test
    void acceptsUppercaseExtensionAndUtf8Bom() throws Exception {
        Path file = tempDir.resolve("bom.HAR");
        Files.writeString(file, "\ufeff{" + "\"log\":{\"version\":\"1.2\",\"entries\":[" +
                entry("{\"request\":{\"method\":\"GET\",\"url\":\"https://example.test/\",\"httpVersion\":\"HTTP/1.1\",\"headers\":[]}}") + "]}}", StandardCharsets.UTF_8);
        HarParser parser = new HarParser();
        assertThat(parser.canParse(file.toFile())).isTrue();
        assertThat(parser.parse(file.toFile()).requests).hasSize(1);
    }

    @Test
    void unsafeHeaderPreventsExactSnapshotAndDoesNotEnterCanonicalHeaders() throws Exception {
        ApiCollection collection = parse("unsafe.har", """
                {"request":{"method":"GET","url":"https://example.test/p","httpVersion":"HTTP/1.1",
                "headers":[{"name":"X-Good","value":"yes"},{"name":"X-Bad","value":"CANARY\\r\\nInjected: yes"}]},"response":{"status":200}}
                """);
        ApiRequest request = collection.requests.get(0);
        assertThat(request.headers).extracting(h -> h.key).containsExactly("X-Good");
        assertThat(request.exactHttpRequest).isNull();
        JsonObject original = HarMetadataSupport.parseObject(request.sourceMetadata.get(HarMetadataSupport.ENTRY_ORIGINAL));
        assertThat(original.toString()).contains("CANARY");
        assertThat(collection.importWarnings).allSatisfy(w -> assertThat(w).doesNotContain("CANARY", "Injected", "\r", "\n"));
    }

    private ApiCollection parse(String name, String entries) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, "{\"log\":{\"version\":\"1.2\",\"entries\":[" + entries + "]}}", StandardCharsets.UTF_8);
        return new HarParser().parse(file.toFile());
    }

    private static String entry(String requestOnly) {
        return requestOnly;
    }

    private static int count(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }
}
