package burp.exporter;

import burp.models.*;
import burp.parser.BrunoParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrunoCanonicalParserResidualTest {
    @TempDir Path tempDir;

    @Test
    void canonicalQuotedKeysValuesDescriptionsScriptsAndMultipartAnnotationsParseExactly() throws Exception {
        String source = """
                meta {
                  name: R
                  type: http
                }
                get {
                  url: https://e.test
                  body: multipart-form
                }
                params:query {
                  @description('single \\' quote')
                  "a\\\":b": "literal"
                  "a\\\\\\\"b:c": "\\n"
                  #key: hash
                  //key: slash
                  "": empty
                  ~"~literal": disabled
                }
                body:multipart-form {
                  upload: @file(path/to/file.bin) @contentType(application/octet-stream)
                  text: literal @contentType(text/plain)
                }
                script:pre-request {
                  first
                    second
                }
                """;
        Path file=tempDir.resolve("request.bru");Files.writeString(file,source,StandardCharsets.UTF_8);
        ApiRequest request=new BrunoParser().parse(file.toFile()).requests.get(0);
        assertThat(request.parameters).extracting(p->p.key)
                .containsExactly("a\":b","a\\\\\"b:c","#key","//key","","~literal");
        assertThat(request.parameters.get(0).value).isEqualTo("\"literal\"");
        assertThat(request.parameters.get(1).value).isEqualTo("\"\\n\"");
        assertThat(request.parameters.get(0).description).isEqualTo("single ' quote");
        assertThat(request.body.formdata.get(0).filePath).isEqualTo("path/to/file.bin");
        assertThat(request.body.formdata.get(1).fileUpload).isFalse();
        assertThat(request.scriptBlocks.get(0).source).isEqualTo("first\n  second");
        assertThat(request.sourceCollection).isNotNull();
    }

    @Test
    void positiveLegacyMetadataEnablesQuotedValueCompatibilityOnlyInsideThatCollection() throws Exception {
        Path zip=tempDir.resolve("legacy.zip");
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip),StandardCharsets.UTF_8)){
            add(out,"Legacy/_collection.bru","vars {\n  token: \"line\\nvalue\"\n}\n");
            add(out,"Legacy/R.bru","meta {\n  type: http\n}\nget {\n  url: https://e.test\n}\nheaders {\n  X: \"line\\nvalue\"\n}\n");
        }
        BrunoParser parser = new BrunoParser();
        ApiCollection legacy=parser.parse(zip.toFile());
        assertThat(legacy.requests.get(0).headers.get(0).value).isEqualTo("line\nvalue");

        Path standalone=tempDir.resolve("canonical.bru");
        Files.writeString(standalone,"meta {\n type: http\n}\nget {\n url: https://e.test\n}\nheaders {\n X: \"line\\nvalue\"\n}\n");
        assertThat(parser.parse(standalone.toFile()).requests.get(0).headers.get(0).value)
                .isEqualTo("\"line\\nvalue\"");
    }

    @Test
    void multilineDescriptionsRoundTripStructurallyForQueryAndPath() throws Exception {
        String description = "\nfirst\n  nested\n\nlast ' \" quote\n";
        ApiCollection collection = new ApiCollection(); collection.name = "Descriptions";
        ApiRequest request = new ApiRequest(); request.name = "R"; request.path = "R";
        request.method = "GET"; request.url = "https://e.test";
        request.body = new ApiRequest.Body(); request.body.mode = "none";
        ApiRequest.Parameter query = new ApiRequest.Parameter("query", "q", "one");
        query.description = description; query.disabled = true;
        ApiRequest.Parameter path = new ApiRequest.Parameter("path", "id", "42");
        path.description = description;
        request.parameters.addAll(List.of(query, path)); collection.requests.add(request);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BrunoCollectionExporter.write(collection, null, bytes, new ArrayList<>());
        Path zip = tempDir.resolve("descriptions.zip"); Files.write(zip, bytes.toByteArray());
        ApiRequest imported = new BrunoParser().parse(zip.toFile()).requests.get(0);
        assertThat(imported.parameters).hasSize(2);
        assertThat(imported.parameters.get(0).disabled).isTrue();
        assertThat(imported.parameters).extracting(parameter -> parameter.location)
                .containsExactly("query", "path");
        assertThat(imported.parameters).extracting(parameter -> parameter.description)
                .containsExactly(description, description);
    }

    @Test
    void legacyFailureCannotLeakIntoLaterCanonicalImport() throws Exception {
        Path bad = tempDir.resolve("bad-legacy.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(bad), StandardCharsets.UTF_8)) {
            add(out, "Legacy/_collection.bru", "vars {\n token: \"legacy\"\n}\n");
            add(out, "../escape.bru", "bad");
        }
        BrunoParser parser = new BrunoParser();
        assertThatThrownBy(() -> parser.parse(bad.toFile())).isInstanceOf(Exception.class);
        Path canonical = canonicalRequest("after-failure.bru", "\"literal\"");
        assertThat(parser.parse(canonical.toFile()).requests.get(0).headers.get(0).value)
                .isEqualTo("\"literal\"");
    }

    @Test
    void sameParserSupportsConcurrentCanonicalAndLegacyImports() throws Exception {
        Path legacyZip = tempDir.resolve("concurrent-legacy.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(legacyZip), StandardCharsets.UTF_8)) {
            add(out, "Legacy/_collection.bru", "vars {\n token: value\n}\n");
            add(out, "Legacy/R.bru", "meta {\n type: http\n}\nget {\n url: https://e.test\n}\nheaders {\n X: \"line\\nvalue\"\n}\n");
        }
        Path canonical = canonicalRequest("concurrent-canonical.bru", "\"line\\nvalue\"");
        BrunoParser parser = new BrunoParser();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ApiCollection> legacy = executor.submit(() -> parser.parse(legacyZip.toFile()));
            Future<ApiCollection> current = executor.submit(() -> parser.parse(canonical.toFile()));
            assertThat(legacy.get().requests.get(0).headers.get(0).value).isEqualTo("line\nvalue");
            assertThat(current.get().requests.get(0).headers.get(0).value).isEqualTo("\"line\\nvalue\"");
        } finally {
            executor.shutdownNow();
        }
    }

    private Path canonicalRequest(String name, String value) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, "meta {\n type: http\n}\nget {\n url: https://e.test\n}\nheaders {\n X: " + value + "\n}\n");
        return file;
    }

    private static void add(ZipOutputStream out,String name,String content)throws Exception{out.putNextEntry(new ZipEntry(name));out.write(content.getBytes(StandardCharsets.UTF_8));out.closeEntry();}
}
