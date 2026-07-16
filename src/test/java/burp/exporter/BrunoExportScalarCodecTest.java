package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.BrunoParser;
import burp.utils.AuthInheritanceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BrunoExportScalarCodecTest {
    @TempDir Path tempDir;

    @Test
    void scalarValuesAndQuotedKeysRoundTripThroughPairedCodec() throws Exception {
        String scalar = " colon:# \" ' \\ lead\tline\ncr\rback\bform\f nul\0 del\u007f Unicode-雪 literal\\n literal\\u0041 ";
        ApiCollection collection = new ApiCollection();
        collection.name = "Codec";
        collection.variables.add(variable("collection:key", scalar));
        collection.variables.add(variable("", "empty collection key"));
        ApiRequest request = request(" name\n\t雪 ", "https://e.test/a:path?q=one\\two#frag");
        for (String key : List.of("", " ", "a:b", "a\"b", "a\\b", "a\nb", "a\tb", "~lead")) {
            request.parameters.add(parameter("query", key, scalar));
        }
        request.parameters.add(parameter("path", "path:key", scalar));
        request.headers.add(new ApiRequest.Header("X-Codec", scalar, false));
        request.body.mode = "urlencoded";
        request.body.urlencoded.add(new ApiRequest.Body.FormField("field:key", scalar));
        request.variables.add(variable("request:key", scalar));
        request.variables.add(variable(" ", "space request key"));
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "basic";
        auth.properties = new LinkedHashMap<>();
        auth.properties.put("username", scalar);
        auth.properties.put("password", "p\\n");
        AuthInheritanceResolver.markRequestExplicitAuth(request, auth);
        collection.requests.add(request);

        Path archive = export(collection, new ArrayList<>());
        String bru = requestText(archive);
        ApiCollection importedCollection = new BrunoParser().parse(archive.toFile());
        ApiRequest imported = importedCollection.requests.get(0);

        assertThat(bru).contains("\\n", "\\r", "\\t", "\\b", "\\f", "\\u0000", "\\u007F", "\\\\n", "\\\\u0041");
        assertThat(bru).doesNotContain("\0", "\u007f");
        assertThat(imported.name).isEqualTo(request.name);
        assertThat(imported.headers.get(0).value).isEqualTo(scalar);
        assertThat(imported.body.urlencoded.get(0).value).isEqualTo(scalar);
        assertThat(imported.variables.get(0).value).isEqualTo(scalar);
        assertThat(imported.variables).extracting(v -> v.key).containsExactly("request:key", " ");
        assertThat(imported.auth.properties.get("username")).isEqualTo(scalar);
        assertThat(imported.parameters).extracting(p -> p.key)
                .containsExactly("", " ", "a:b", "a\"b", "a\\b", "a\nb", "a\tb", "~lead", "path:key");
        assertThat(imported.parameters).allSatisfy(p -> assertThat(p.value).isEqualTo(scalar));
        assertThat(importedCollection.variables.get(0).value).isEqualTo(scalar);
        assertThat(importedCollection.variables).extracting(v -> v.key).containsExactly("collection:key", "");
    }

    @Test
    void authPropertiesExportDeterministically() throws Exception {
        ApiCollection first = authCollection(Map.of("z:key", "z", "a:key", "a"));
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("z:key", "z");
        reversed.put("a:key", "a");
        ApiCollection second = authCollection(reversed);
        String one = requestText(export(first, new ArrayList<>()));
        String two = requestText(export(second, new ArrayList<>()));
        assertThat(one).isEqualTo(two);
        assertThat(one.indexOf("\"a:key\":" )).isLessThan(one.indexOf("\"z:key\":"));
    }

    @Test
    void multipartPathsRoundTripAndUnsafePathFallsBackWithoutBreakingLaterRows() throws Exception {
        ApiCollection collection = new ApiCollection(); collection.name = "Files";
        ApiRequest request = request("Files", "https://e.test/upload"); request.body.mode = "formdata";
        for (String path : List.of("C:\\Program Files\\A (1)\\file#name.bin", "/tmp/a (1)#file.bin", "{{baseDir}}\\folder\\file.bin")) {
            ApiRequest.Body.FormField field = new ApiRequest.Body.FormField("upload", "retained");
            field.type = "file"; field.fileUpload = true; field.filePath = path; request.body.formdata.add(field);
        }
        ApiRequest.Body.FormField unsafe = new ApiRequest.Body.FormField("unsafe", "fallback");
        unsafe.type = "file"; unsafe.fileUpload = true; unsafe.filePath = "secret\npath"; request.body.formdata.add(unsafe);
        request.body.formdata.add(new ApiRequest.Body.FormField("later", "ok"));
        collection.requests.add(request);
        List<String> warnings = new ArrayList<>(); Path archive = export(collection, warnings);
        String bru = requestText(archive); ApiRequest imported = new BrunoParser().parse(archive.toFile()).requests.get(0);
        assertThat(bru).contains("@file(C:\\Program Files\\A (1)\\file#name.bin)", "unsafe: fallback", "later: ok")
                .doesNotContain("secret\npath");
        assertThat(imported.body.formdata.subList(0, 3)).extracting(f -> f.filePath)
                .containsExactly("C:\\Program Files\\A (1)\\file#name.bin", "/tmp/a (1)#file.bin", "{{baseDir}}\\folder\\file.bin");
        assertThat(imported.body.formdata.get(3).value).isEqualTo("fallback");
        assertThat(imported.body.formdata.get(4).key).isEqualTo("later");
        assertThat(warnings).singleElement().asString().contains("Files", "unsafe").doesNotContain("secret\npath");
    }

    private static ApiCollection authCollection(Map<String, String> properties) {
        ApiCollection c = new ApiCollection(); c.name = "Auth"; ApiRequest r = request("Auth", "https://e.test");
        ApiRequest.Auth auth = new ApiRequest.Auth(); auth.type = "basic"; auth.properties = new LinkedHashMap<>(properties);
        AuthInheritanceResolver.markRequestExplicitAuth(r, auth); c.requests.add(r); return c;
    }

    private static ApiRequest request(String name, String url) {
        ApiRequest r = new ApiRequest(); r.name = name; r.path = name; r.method = "POST"; r.url = url;
        r.body = new ApiRequest.Body(); r.body.mode = "none"; return r;
    }

    private static ApiRequest.Parameter parameter(String location, String key, String value) {
        return new ApiRequest.Parameter(location, key, value);
    }

    private static ApiRequest.Variable variable(String key, String value) {
        ApiRequest.Variable v = new ApiRequest.Variable(); v.key = key; v.value = value; v.enabled = true; v.type = "string"; return v;
    }

    private Path export(ApiCollection collection, List<String> warnings) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); BrunoCollectionExporter.write(collection, null, out, warnings);
        Path path = tempDir.resolve("codec-" + System.nanoTime() + ".zip"); Files.write(path, out.toByteArray()); return path;
    }

    private static String requestText(Path archive) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".bru") && !entry.getName().endsWith("_collection.bru")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("request entry missing");
    }
}
