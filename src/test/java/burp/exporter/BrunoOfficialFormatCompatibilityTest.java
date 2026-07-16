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
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrunoOfficialFormatCompatibilityTest {
    @TempDir Path tempDir;

    @Test
    void emitsCanonicalMethodsSelectorsValuesMetadataAndFileBodies() throws Exception {
        ApiCollection collection = new ApiCollection();
        collection.name = "Official";
        collection.variables.add(variable("count", "42", "number", true));
        ApiRequest standard = request("Standard", "GET", "https://example.test/a", "raw");
        standard.body.contentType = "application/json";
        standard.body.raw = "\n{\n  \"a\": 1\n}\n";
        standard.headers.add(new ApiRequest.Header("X-Test", "literal\\n value", false));
        ApiRequest.Auth bearer = new ApiRequest.Auth(); bearer.type = "bearer"; bearer.properties.put("token", "{{token}}");
        AuthInheritanceResolver.markRequestExplicitAuth(standard, bearer);
        ApiRequest custom = request("Custom", "PROPFIND", "https://example.test/custom", "none");
        ApiRequest file = request("File", "POST", "https://example.test/upload", "file");
        file.body.raw = "C:\\Program Files\\upload.bin";
        file.body.contentType = "application/octet-stream";
        collection.requests.add(standard); collection.requests.add(custom); collection.requests.add(file);
        ApiRequest emptyFile = request("EmptyFile", "POST", "https://example.test/empty", "file");
        emptyFile.body.raw = "";
        collection.requests.add(emptyFile);

        Path zip = export(collection);
        Map<String, String> entries = entries(zip);
        assertThat(entries.keySet()).contains("Official/bruno.json", "Official/collection.bru")
                .noneMatch(name -> name.endsWith("_collection.bru") || name.endsWith("_folder.bru"));
        assertThat(entries.get("Official/bruno.json")).contains("\"version\": \"1\"", "\"type\": \"collection\"");
        String standardText = entries.get("Official/Standard.bru");
        assertThat(standardText).contains("get {\n  url: https://example.test/a", "body: json", "auth: bearer",
                "auth:bearer {", "body:json {", "literal\\n value").doesNotContain("url: \"");
        assertThat(entries.get("Official/Custom.bru")).contains("http {\n  method: PROPFIND", "body: none");
        assertThat(entries.get("Official/File.bru")).contains("body: file", "body:file {",
                "file: @file(C:\\Program Files\\upload.bin) @contentType(application/octet-stream)");
        assertThat(entries.get("Official/EmptyFile.bru")).contains("body: file", "file: @file()");
        assertThat(entries.get("Official/collection.bru")).contains("vars:pre-request {", "@number", "count: 42");

        ApiCollection imported = new BrunoParser().parse(zip.toFile());
        assertThat(imported.requests).extracting(r -> r.method).contains("GET", "PROPFIND", "POST");
        ApiRequest importedFile = imported.requests.stream().filter(r -> "File".equals(r.name)).findFirst().orElseThrow();
        assertThat(importedFile.body.mode).isEqualTo("file");
        assertThat(importedFile.body.raw).isEqualTo("C:\\Program Files\\upload.bin");
        assertThat(importedFile.body.contentType).isEqualTo("application/octet-stream");
        ApiRequest importedEmpty = imported.requests.stream().filter(r -> "EmptyFile".equals(r.name)).findFirst().orElseThrow();
        assertThat(importedEmpty.body.mode).isEqualTo("file");
        assertThat(importedEmpty.body.raw).isEmpty();
        assertThat(imported.variables.get(0).type).isEqualTo("number");
    }

    @Test
    void invalidMethodAndTripleDelimiterFailBeforeZipBytesAreWritten() {
        ApiCollection invalidMethod = new ApiCollection(); invalidMethod.name = "Bad";
        invalidMethod.requests.add(request("Bad", "BAD METHOD", "https://e.test", "none"));
        ByteArrayOutputStream methodOut = new ByteArrayOutputStream();
        assertThatThrownBy(() -> BrunoCollectionExporter.write(invalidMethod, null, methodOut, new ArrayList<>()))
                .isInstanceOf(java.io.IOException.class);
        assertThat(methodOut.size()).isZero();

        ApiCollection delimiter = new ApiCollection(); delimiter.name = "Bad";
        ApiRequest request = request("Bad", "GET", "https://e.test", "urlencoded");
        request.body.urlencoded.add(new ApiRequest.Body.FormField("key", " leading\n'''\ntrailing "));
        delimiter.requests.add(request);
        ByteArrayOutputStream valueOut = new ByteArrayOutputStream();
        assertThatThrownBy(() -> BrunoCollectionExporter.write(delimiter, null, valueOut, new ArrayList<>()))
                .isInstanceOf(java.io.IOException.class);
        assertThat(valueOut.size()).isZero();

        ApiCollection unsafeFile = new ApiCollection(); unsafeFile.name = "Bad";
        ApiRequest file = request("BadFile", "POST", "https://e.test", "file");
        file.body.raw = "hidden\npath"; unsafeFile.requests.add(file);
        ByteArrayOutputStream fileOut = new ByteArrayOutputStream();
        assertThatThrownBy(() -> BrunoCollectionExporter.write(unsafeFile, null, fileOut, new ArrayList<>()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageNotContaining("hidden\npath");
        assertThat(fileOut.size()).isZero();
    }

    private Path export(ApiCollection collection) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BrunoCollectionExporter.write(collection, null, out, new ArrayList<>());
        Path zip = tempDir.resolve("official-" + System.nanoTime() + ".zip"); Files.write(zip, out.toByteArray()); return zip;
    }
    private static Map<String,String> entries(Path zip) throws Exception {
        Map<String,String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry; while ((entry=in.getNextEntry())!=null) out.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } return out;
    }
    private static ApiRequest request(String name,String method,String url,String mode) {
        ApiRequest r=new ApiRequest();r.name=name;r.path=name;r.method=method;r.url=url;r.body=new ApiRequest.Body();r.body.mode=mode;return r;
    }
    private static ApiRequest.Variable variable(String key,String value,String type,boolean enabled) {
        ApiRequest.Variable v=new ApiRequest.Variable();v.key=key;v.value=value;v.type=type;v.enabled=enabled;return v;
    }
}
