package burp.exporter;

import burp.models.*;
import burp.parser.BrunoParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;

import static org.assertj.core.api.Assertions.assertThat;

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
        ApiCollection legacy=new BrunoParser().parse(zip.toFile());
        assertThat(legacy.requests.get(0).headers.get(0).value).isEqualTo("line\nvalue");

        Path standalone=tempDir.resolve("canonical.bru");
        Files.writeString(standalone,"meta {\n type: http\n}\nget {\n url: https://e.test\n}\nheaders {\n X: \"line\\nvalue\"\n}\n");
        assertThat(new BrunoParser().parse(standalone.toFile()).requests.get(0).headers.get(0).value)
                .isEqualTo("\"line\\nvalue\"");
    }

    private static void add(ZipOutputStream out,String name,String content)throws Exception{out.putNextEntry(new ZipEntry(name));out.write(content.getBytes(StandardCharsets.UTF_8));out.closeEntry();}
}
