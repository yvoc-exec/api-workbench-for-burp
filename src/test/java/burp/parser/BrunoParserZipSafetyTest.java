package burp.parser;

import burp.models.ApiCollection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BrunoParserZipSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    void canParseRejectsCorruptAndUnrelatedZipArchives() throws Exception {
        Path corrupt = tempDir.resolve("corrupt.zip");
        Files.writeString(corrupt, "not a zip", StandardCharsets.UTF_8);

        Path unrelated = createZip("unrelated.zip", Map.of(
                "notes/readme.txt", "hello"
        ));

        BrunoParser parser = new BrunoParser();

        assertThat(parser.canParse(corrupt.toFile())).isFalse();
        assertThat(parser.canParse(unrelated.toFile())).isFalse();
    }

    @Test
    void parseIgnoresAbsoluteZipEntriesAndImportsSafeRequestsOnly() throws Exception {
        Path zip = createZip("absolute-entry.zip", new LinkedHashMap<>() {{
            put("Collection/bruno.json", """
                    {
                      "name": "Absolute Safe"
                    }
                    """);
            put("Collection/Safe/GetUsers.bru", """
                    meta {
                      name: Get Users
                      type: http
                      seq: 1
                    }

                    get {
                      url: https://api.example.test/users
                    }
                    """);
            put("/escape/Nope.bru", """
                    meta {
                      name: Nope
                      type: http
                      seq: 2
                    }

                    get {
                      url: https://api.example.test/nope
                    }
                    """);
        }});

        ApiCollection collection = new BrunoParser().parse(zip.toFile());

        assertThat(collection.name).isEqualTo("Absolute Safe");
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.requests.get(0).name).isEqualTo("Get Users");
        assertThat(collection.requests.get(0).path).isEqualTo("Safe/Get Users");
    }

    private Path createZip(String name, Map<String, String> entries) throws Exception {
        Path zip = tempDir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }
}
