package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.BrunoParser;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.utils.AuthInheritanceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BrunoCollectionRoundTripTest {
    @TempDir Path tempDir;

    @Test
    void supportedTypedRequestsRoundTripThroughBrunoZip() throws Exception {
        ApiCollection source = collectionWithRequests();
        List<String> warnings = new ArrayList<>();
        Path zip = export(source, warnings);
        ApiCollection imported = new BrunoParser().parse(zip.toFile());

        assertThat(imported.requests).hasSize(6);
        ApiRequest encoded = byName(imported, "Encoded");
        assertThat(encoded.parameters).extracting(p -> p.location + ":" + p.key + ":" + p.disabled)
                .containsExactly("query:tag:false", "query:tag:false", "query:skip:true", "path:id:false");
        assertThat(encoded.body.mode).isEqualTo("urlencoded");
        assertThat(encoded.body.urlencoded).extracting(f -> f.key).containsExactly("", "field", "field");
        assertThat(encoded.body.urlencoded.get(2).disabled).isTrue();
        assertThat(encoded.variables).extracting(v -> v.enabled).containsExactly(true, false);
        assertThat(encoded.authOverrideMode).isEqualTo("explicit");
        assertThat(encoded.scriptBlocks).extracting(b -> b.phase)
                .containsExactly(ScriptPhase.PRE_REQUEST, ScriptPhase.POST_RESPONSE, ScriptPhase.TEST);
        assertThat(encoded.scriptBlocks).allSatisfy(b -> assertThat(b.dialect).isEqualTo(ScriptDialect.BRUNO));

        ApiRequest multipart = byName(imported, "Multipart");
        assertThat(multipart.body.formdata.get(1).fileUpload).isTrue();
        assertThat(multipart.body.formdata.get(1).filePath).isEqualTo("C:\\files\\one.bin");
        assertThat(byName(imported, "Json").body.contentType).isEqualTo("application/json");
        assertThat(byName(imported, "Xml").body.contentType).isEqualTo("application/vnd.custom+xml");
        assertThat(byName(imported, "GraphQL").body.graphql.variables).contains("id");
        assertThat(byName(imported, "None").body.mode).isEqualTo("none");
    }

    @Test
    void brunoExporterUsesTypedBlocksAndFileSyntax() throws Exception {
        Map<String, String> entries = readZip(export(collectionWithRequests(), new ArrayList<>()));
        String all = String.join("\n", entries.values());
        assertThat(all).contains("params:query {", "params:path {", "body:form-urlencoded {",
                "body:multipart-form {", "@file(", "script:pre-request {",
                "script:post-response {", "test {");
        assertThat(all).doesNotContain("# file");
    }

    @Test
    void disabledBareQueryProducesWarningInsteadOfSilentLoss() throws Exception {
        ApiCollection collection = new ApiCollection();
        collection.name = "Bare";
        ApiRequest request = request("Bare", "GET", "https://e.test/a", "none");
        ApiRequest.Parameter parameter = new ApiRequest.Parameter("query", "flag", "");
        parameter.valuePresent = false;
        parameter.disabled = true;
        request.parameters.add(parameter);
        collection.requests.add(request);
        List<String> warnings = new ArrayList<>();
        ApiCollection imported = new BrunoParser().parse(export(collection, warnings).toFile());
        assertThat(warnings).anyMatch(w -> w.contains("disabled bare query parameter") && w.contains("flag"));
        assertThat(imported.requests.get(0).parameters).singleElement().satisfies(p -> {
            assertThat(p.key).isEqualTo("flag");
            assertThat(p.disabled).isTrue();
            assertThat(p.valuePresent).isTrue();
        });
    }

    @Test
    void barePathParametersWarnAndRoundTripAsExplicitEmptyWithoutLosingOrder() throws Exception {
        ApiCollection collection = new ApiCollection();
        collection.name = "Paths";
        ApiRequest request = request("Path request", "GET", "https://e.test/:id", "none");
        request.parameters.add(barePath("id", false));
        request.parameters.add(parameter("path", "id", "one", false));
        request.parameters.add(barePath("id", false));
        request.parameters.add(parameter("path", "id", "two", true));
        request.parameters.add(barePath("", false));
        request.parameters.add(barePath(" ", true));
        collection.requests.add(request);

        List<String> warnings = new ArrayList<>();
        Path archive = export(collection, warnings);
        String bru = readZip(archive).values().stream().filter(v -> v.contains("params:path {")).findFirst().orElseThrow();
        ApiRequest imported = new BrunoParser().parse(archive.toFile()).requests.get(0);

        assertThat(bru).contains("params:path {", "id: ", "\"\": ", "~\" \": ");
        assertThat(warnings).filteredOn(w -> w.contains("bare path parameter")).hasSize(3);
        assertThat(imported.parameters).extracting(p -> p.location).containsOnly("path");
        assertThat(imported.parameters).extracting(p -> p.key).containsExactly("id", "id", "id", "id", "", " ");
        assertThat(imported.parameters).extracting(p -> p.valuePresent).containsOnly(true);
        assertThat(imported.parameters).extracting(p -> p.disabled)
                .containsExactly(false, false, false, true, false, true);
    }

    private ApiCollection collectionWithRequests() {
        ApiCollection collection = new ApiCollection();
        collection.name = "RoundTrip";
        ApiRequest encoded = request("Encoded", "POST", "https://e.test/items/:id?stale=x", "urlencoded");
        encoded.parameters.add(parameter("query", "tag", "one", false));
        encoded.parameters.add(parameter("query", "tag", "two", false));
        encoded.parameters.add(parameter("query", "skip", "hidden", true));
        encoded.parameters.add(parameter("path", "id", "42", false));
        encoded.body.urlencoded.add(field("", "blank", false, false, null));
        encoded.body.urlencoded.add(field("field", "one", false, false, null));
        encoded.body.urlencoded.add(field("field", "two", true, false, null));
        encoded.body.contentType = "application/x-www-form-urlencoded";
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "bearer";
        auth.properties.put("token", "token");
        AuthInheritanceResolver.markRequestExplicitAuth(encoded, auth);
        encoded.variables.add(variable("dup", "one", true));
        encoded.variables.add(variable("dup", "two", false));
        encoded.scriptBlocks.add(script("pre();", ScriptPhase.PRE_REQUEST, 0));
        encoded.scriptBlocks.add(script("post();", ScriptPhase.POST_RESPONSE, 1));
        encoded.scriptBlocks.add(script("test();", ScriptPhase.TEST, 2));

        ApiRequest multipart = request("Multipart", "POST", "https://e.test/upload", "formdata");
        multipart.body.formdata.add(field("text", "value", false, false, null));
        multipart.body.formdata.add(field("upload", "@file(C:\\files\\one.bin)", true, true, "C:\\files\\one.bin"));
        multipart.body.contentType = "multipart/form-data";
        ApiRequest json = request("Json", "POST", "https://e.test/json", "raw");
        json.body.raw = "{\"a\":1}";
        json.body.contentType = "application/json";
        ApiRequest xml = request("Xml", "POST", "https://e.test/xml", "raw");
        xml.body.raw = "<a/>";
        xml.body.contentType = "application/vnd.custom+xml";
        ApiRequest graphql = request("GraphQL", "POST", "https://e.test/graphql", "graphql");
        graphql.body.graphql = new ApiRequest.Body.GraphQL();
        graphql.body.graphql.query = "query Q { item }";
        graphql.body.graphql.variables = "{\"id\":1}";
        ApiRequest none = request("None", "GET", "https://e.test/none", "none");
        collection.requests.addAll(List.of(encoded, multipart, json, xml, graphql, none));
        return collection;
    }

    private static ApiRequest request(String name, String method, String url, String mode) {
        ApiRequest request = new ApiRequest();
        request.name = name;
        request.path = name;
        request.method = method;
        request.url = url;
        request.body = new ApiRequest.Body();
        request.body.mode = mode;
        return request;
    }

    private static ApiRequest.Parameter parameter(String location, String key, String value, boolean disabled) {
        ApiRequest.Parameter p = new ApiRequest.Parameter(location, key, value);
        p.disabled = disabled;
        return p;
    }

    private static ApiRequest.Parameter barePath(String key, boolean disabled) {
        ApiRequest.Parameter parameter = parameter("path", key, "", disabled);
        parameter.valuePresent = false;
        return parameter;
    }

    private static ApiRequest.Body.FormField field(String key, String value, boolean disabled,
                                                   boolean file, String path) {
        ApiRequest.Body.FormField f = new ApiRequest.Body.FormField(key, value);
        f.disabled = disabled;
        f.type = file ? "file" : "text";
        f.fileUpload = file;
        f.filePath = path;
        return f;
    }

    private static ApiRequest.Variable variable(String key, String value, boolean enabled) {
        ApiRequest.Variable v = new ApiRequest.Variable();
        v.key = key; v.value = value; v.type = "string"; v.enabled = enabled;
        return v;
    }

    private static ScriptBlock script(String source, ScriptPhase phase, int order) {
        ScriptBlock b = ScriptBlock.of(source, ScriptDialect.BRUNO, phase, ScriptScope.REQUEST);
        b.order = order;
        return b;
    }

    private Path export(ApiCollection collection, List<String> warnings) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BrunoCollectionExporter.write(collection, null, out, warnings);
        Path file = tempDir.resolve("roundtrip-" + System.nanoTime() + ".zip");
        Files.write(file, out.toByteArray());
        return file;
    }

    private static ApiRequest byName(ApiCollection collection, String name) {
        return collection.requests.stream().filter(r -> name.equals(r.name)).findFirst().orElseThrow();
    }

    private static Map<String, String> readZip(Path file) throws Exception {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) result.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return result;
    }
}
