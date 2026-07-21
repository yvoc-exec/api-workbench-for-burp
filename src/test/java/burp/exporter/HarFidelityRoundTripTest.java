package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.parser.ApiWorkbenchCollectionParser;
import burp.parser.HarParser;
import burp.utils.HarMetadataSupport;
import burp.utils.RequestBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HarFidelityRoundTripTest {
    @TempDir Path tempDir;

    @Test
    void untouchedHarEntryRoundTripsExactly() throws Exception {
        String source = detailedHar();
        ApiCollection collection = importHar(source);
        JsonObject exported = HarCollectionExporter.build(collection, harOptions(false), new ArrayList<>());
        JsonObject expected = JsonParser.parseString(source).getAsJsonObject();
        assertThat(exported.get("rootVendor")).isEqualTo(expected.get("rootVendor"));
        assertThat(exported.getAsJsonObject("log").get("browser"))
                .isEqualTo(expected.getAsJsonObject("log").get("browser"));
        assertThat(exported.getAsJsonObject("log").getAsJsonArray("entries").get(0))
                .isEqualTo(expected.getAsJsonObject("log").getAsJsonArray("entries").get(0));
    }

    @Test
    void nativeSaveReloadPreservesOriginalHarEntryAndExactSnapshot() throws Exception {
        ApiCollection imported = importHar(detailedHar());
        byte[] originalBytes = imported.requests.get(0).exactHttpRequest.rawRequestBytes.clone();
        Path nativeFile = tempDir.resolve("saved.api-workbench.json");
        new CollectionExportService().exportCollection(imported,
                new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON,
                        nativeFile, false, null, Map.of()));
        ApiCollection restored = new ApiWorkbenchCollectionParser().parse(nativeFile.toFile());
        JsonObject exported = HarCollectionExporter.build(restored, harOptions(false), new ArrayList<>());
        JsonObject expected = JsonParser.parseString(detailedHar()).getAsJsonObject();

        assertThat(exported.getAsJsonObject("log").getAsJsonArray("entries").get(0))
                .isEqualTo(expected.getAsJsonObject("log").getAsJsonArray("entries").get(0));
        assertThat(restored.requests.get(0).exactHttpRequest.rawRequestBytes).isEqualTo(originalBytes);
        assertThat(restored.requests.get(0).exactHttpRequest.httpVersion).isEqualTo("HTTP/1.0");
        JsonObject metadata = HarMetadataSupport.parseObject(restored.requests.get(0).sourceMetadata
                .get(HarMetadataSupport.ENTRY_ORIGINAL));
        metadata.addProperty("mutated", true);
        assertThat(restored.requests.get(0).sourceMetadata.get(HarMetadataSupport.ENTRY_ORIGINAL))
                .doesNotContain("mutated");
    }

    @Test
    void editedRequestRebuildsAndReplacesStaleResponse() throws Exception {
        ApiCollection collection = importHar(detailedHar());
        ApiRequest request = collection.requests.get(0);
        request.parameters.get(0).value = "new-value";
        request.invalidateExactTransport("edited");
        ArrayList<String> warnings = new ArrayList<>();
        JsonObject entry = HarCollectionExporter.build(collection, harOptions(false), warnings)
                .getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject exportedRequest = entry.getAsJsonObject("request");
        assertThat(exportedRequest.get("url").getAsString()).isEqualTo("https://example.test/p?a=new-value&a=two");
        assertThat(exportedRequest.getAsJsonArray("queryString")).hasSize(2);
        assertThat(entry.getAsJsonObject("response").get("status").getAsInt()).isZero();
        assertThat(warnings).anyMatch(w -> w.contains("retained response was replaced"));
        assertThat(warnings).allSatisfy(w -> assertThat(w).doesNotContain("new-value", "one", "two"));
    }

    @Test
    void canonicalModelExportsHeadersCookiesAndParametersWithoutDuplication() {
        ApiCollection collection = new ApiCollection();
        ApiRequest request = new ApiRequest();
        request.method = "GET";
        request.url = "https://example.test/p?stale=removed";
        ApiRequest.Parameter q1 = new ApiRequest.Parameter("query", "a", "1");
        ApiRequest.Parameter q2 = new ApiRequest.Parameter("query", "a", "2");
        ApiRequest.Parameter bare = new ApiRequest.Parameter("query", "flag", "");
        bare.valuePresent = false;
        request.parameters.add(q1);
        request.parameters.add(q2);
        request.parameters.add(bare);
        request.headers.add(new ApiRequest.Header("X-Dupe", "1"));
        request.headers.add(new ApiRequest.Header("X-Dupe", "2"));
        request.headers.add(new ApiRequest.Header("Cookie", "explicit=1"));
        request.parameters.add(new ApiRequest.Parameter("header", "X-Param", "v"));
        request.parameters.add(new ApiRequest.Parameter("cookie", "model", "2"));
        collection.requests.add(request);

        JsonObject out = firstRequest(HarCollectionExporter.build(collection, harOptions(false), new ArrayList<>()));
        assertThat(out.get("url").getAsString()).isEqualTo("https://example.test/p?a=1&a=2&flag");
        assertThat(out.getAsJsonArray("queryString")).hasSize(3);
        assertThat(out.getAsJsonArray("headers")).extracting(e -> e.getAsJsonObject().get("name").getAsString())
                .containsExactly("X-Dupe", "X-Dupe", "Cookie", "X-Param", "Cookie", "Host");
        assertThat(out.getAsJsonArray("cookies")).hasSize(2);
        assertThat(count(out.get("url").getAsString(), "?a=1&a=2&flag")).isOne();
    }

    @Test
    void multipartExportUsesFileNameAndNeverLeaksLocalPath() {
        ApiCollection collection = new ApiCollection();
        ApiRequest request = new ApiRequest();
        request.method = "POST";
        request.url = "https://example.test/upload";
        request.body = new ApiRequest.Body();
        request.body.mode = "formdata";
        ApiRequest.Body.FormField file = new ApiRequest.Body.FormField("upload", null);
        file.fileUpload = true;
        file.type = "file";
        file.filePath = "C:\\Users\\tester\\secret\\local.bin";
        file.contentType = "application/octet-stream";
        file.sourceMetadata.put(HarMetadataSupport.POST_PARAM_ORIGINAL,
                "{\"name\":\"upload\",\"fileName\":\"retained.bin\",\"vendor\":true}");
        request.body.formdata.add(file);
        collection.requests.add(request);
        JsonObject built = HarCollectionExporter.build(collection, harOptions(false), new ArrayList<>());
        String json = built.toString();
        JsonObject param = firstRequest(built).getAsJsonObject("postData").getAsJsonArray("params")
                .get(0).getAsJsonObject();
        assertThat(param.get("fileName").getAsString()).isEqualTo("retained.bin");
        assertThat(param.get("contentType").getAsString()).isEqualTo("application/octet-stream");
        assertThat(param.has("filePath")).isFalse();
        assertThat(json).doesNotContain("C:", "Users", "tester", "secret", "local.bin");
    }

    @Test
    void exactSnapshotExportsHttpVersionDuplicateHeadersAndBodySizes() {
        ApiCollection collection = new ApiCollection();
        ApiRequest request = exact("POST /p HTTP/1.0\r\nX-Dupe: a\r\nX-Dupe: b\r\nContent-Type: text/plain\r\n\r\nbody", false);
        request.method = "POST";
        request.url = "https://example.test/p";
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "body";
        collection.requests.add(request);
        JsonObject out = firstRequest(HarCollectionExporter.build(collection, harOptions(false), new ArrayList<>()));
        assertThat(out.get("httpVersion").getAsString()).isEqualTo("HTTP/1.0");
        assertThat(out.getAsJsonArray("headers")).extracting(e -> e.getAsJsonObject().get("name").getAsString())
                .containsExactly("X-Dupe", "X-Dupe", "Content-Type");
        assertThat(out.get("headersSize").getAsInt()).isPositive();
        assertThat(out.get("bodySize").getAsInt()).isEqualTo(4);
        assertThat(out.getAsJsonObject("postData").get("text").getAsString()).isEqualTo("body");
    }

    @Test
    void binaryExactBodyProducesWarningWithoutInvalidHarText() {
        ApiCollection collection = new ApiCollection();
        byte[] head = "POST /p HTTP/1.1\r\nContent-Type: application/octet-stream\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] raw = java.util.Arrays.copyOf(head, head.length + 3);
        raw[head.length] = 0;
        raw[head.length + 1] = (byte) 0xff;
        raw[head.length + 2] = 1;
        ApiRequest request = exact(raw, true);
        request.method = "POST";
        request.url = "https://example.test/p";
        collection.requests.add(request);
        ArrayList<String> warnings = new ArrayList<>();
        JsonObject postData = firstRequest(HarCollectionExporter.build(collection, harOptions(false), warnings))
                .getAsJsonObject("postData");
        assertThat(postData.get("mimeType").getAsString()).isEqualTo("application/octet-stream");
        assertThat(postData.has("text")).isFalse();
        assertThat(warnings).isNotEmpty().allSatisfy(w -> assertThat(w).doesNotContain("�", "\u0000"));
    }

    @Test
    void builtRequestIsEquivalentAfterHarNativeReload() throws Exception {
        ApiCollection imported = importHar(detailedHar().replace("HTTP/1.0", "HTTP/1.1"));
        Path nativeFile = tempDir.resolve("roundtrip.api-workbench.json");
        new CollectionExportService().exportCollection(imported,
                new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON,
                        nativeFile, false, null, Map.of()));
        ApiCollection restored = new ApiWorkbenchCollectionParser().parse(nativeFile.toFile());
        RequestBuilder builder = new RequestBuilder(null);
        assertThat(builder.buildRequest(restored.requests.get(0), null))
                .isEqualTo(builder.buildRequest(imported.requests.get(0), null));
    }

    private ApiCollection importHar(String json) throws Exception {
        Path file = tempDir.resolve("source-" + System.nanoTime() + ".har");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return new HarParser().parse(file.toFile());
    }

    private static JsonObject firstRequest(JsonObject root) {
        return root.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject()
                .getAsJsonObject("request");
    }

    private static CollectionExportOptions harOptions(boolean resolve) {
        return new CollectionExportOptions(CollectionExportFormat.HAR_JSON, null, resolve, null, Map.of());
    }

    private static ApiRequest exact(String raw, boolean binary) {
        return exact(raw.getBytes(StandardCharsets.UTF_8), binary);
    }

    private static ApiRequest exact(byte[] raw, boolean binary) {
        ApiRequest request = new ApiRequest();
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.exactHttpRequest = new ExactHttpRequestSnapshot();
        request.exactHttpRequest.rawRequestBytes = raw;
        request.exactHttpRequest.httpVersion = raw.length > 0 && new String(raw, StandardCharsets.UTF_8).startsWith("POST /p HTTP/1.0")
                ? "HTTP/1.0" : "HTTP/1.1";
        request.exactHttpRequest.pristine = true;
        request.exactHttpRequest.binaryBody = binary;
        return request;
    }

    private static int count(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }

    private static String detailedHar() {
        return """
                {"rootVendor":{"x":1},"log":{"version":"1.2","creator":{"name":"browser","version":"1"},
                "browser":{"name":"Test","version":"9"},"pages":[{"id":"page-1"}],"comment":"log-comment","vendorLog":true,
                "entries":[{"startedDateTime":"2020-01-01T00:00:00Z","time":12,"pageref":"page-1","serverIPAddress":"127.0.0.1","connection":"7","comment":"entry-comment","vendorEntry":{"a":1},
                "request":{"method":"POST","url":"https://example.test/p?a=one&a=two","httpVersion":"HTTP/1.0",
                "headers":[{"name":"Host","value":"example.test"},{"name":"X-Dupe","value":"1"},{"name":"X-Dupe","value":"2"},{"name":"Content-Type","value":"application/json"}],
                "queryString":[{"name":"a","value":"one","comment":"first"},{"name":"a","value":"two"}],"cookies":[],
                "postData":{"mimeType":"application/json","text":"{\\"ok\\":true}","vendorPost":1},"headersSize":99,"bodySize":11,"comment":"request-comment","vendorRequest":true},
                "response":{"status":201,"statusText":"Created","httpVersion":"HTTP/1.1","headers":[],"cookies":[],"content":{"size":2,"mimeType":"text/plain","text":"ok"},"redirectURL":"","headersSize":10,"bodySize":2,"vendorResponse":true},
                "cache":{"beforeRequest":{"lastAccess":"x"}},"timings":{"send":1,"wait":2,"receive":3},"vendorTail":"kept"}]}}
                """;
    }
}
