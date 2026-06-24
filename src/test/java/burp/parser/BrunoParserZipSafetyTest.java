package burp.parser;

import burp.diagnostics.DiagnosticEvent;
import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    @Test
    void parseIgnoresPathTraversalZipEntriesAndKeepsSafeEntriesOnly() throws Exception {
        Path zip = createZip("traversal-entry.zip", new LinkedHashMap<>() {{
            put("Collection/bruno.json", """
                    {
                      "name": "Traversal Safe"
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
            put("../escape/Nope.bru", """
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

        assertThat(collection.name).isEqualTo("Traversal Safe");
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.requests.get(0).name).isEqualTo("Get Users");
    }

    @Test
    void parseDeletesTemporaryExtractionDirectoryAfterReadingZip() throws Exception {
        String previousTmpDir = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", tempDir.toString());
        try {
            Path zip = createZip("cleanup.zip", Map.of(
                    "collection/bruno.json", """
                            {
                              "name": "Cleanup"
                            }
                            """,
                    "collection/requests/get-users.bru", """
                            meta {
                              name: Get Users
                              type: http
                              seq: 1
                            }

                            get {
                              url: https://api.example.test/users
                            }
                            """
            ));

            ApiCollection collection = new BrunoParser().parse(zip.toFile());
            assertThat(collection.name).isEqualTo("Cleanup");

            List<Path> leftoverTempRoots = new ArrayList<>();
            try (var stream = Files.list(tempDir)) {
                stream.filter(path -> path.getFileName() != null && path.getFileName().toString().startsWith("bruno-import-"))
                        .forEach(leftoverTempRoots::add);
            }
            assertThat(leftoverTempRoots).isEmpty();
        } finally {
            if (previousTmpDir != null) {
                System.setProperty("java.io.tmpdir", previousTmpDir);
            } else {
                System.clearProperty("java.io.tmpdir");
            }
        }
    }

    @Test
    void parseReportsMalformedBrunoRequestsAndKeepsValidSiblings() throws Exception {
        DiagnosticStore store = DiagnosticStore.getInstance();
        boolean previousCapture = store.isCaptureEnabled();
        store.setCaptureEnabled(true);
        store.clear();
        try {
            Path zip = createZip("malformed.zip", new LinkedHashMap<>() {{
                put("collection/bruno.json", """
                        {
                          "name": "Malformed Bruno"
                        }
                        """);
                put("collection/requests/Valid.bru", """
                        meta {
                          name: Valid
                          type: http
                          seq: 1
                        }

                        get {
                          url: https://api.example.test/valid
                        }
                        """);
                put("collection/requests/Broken.bru", """
                        meta {
                          name: Broken
                          type: http
                          seq: 2
                        }

                        post {
                          url: https://api.example.test/broken
                        }

                        body:json {
                          {
                            "broken": true
                        }
                        """);
            }});

            ApiCollection collection = new BrunoParser().parse(zip.toFile());

            assertThat(collection.requests).hasSize(1);
            assertThat(collection.requests.get(0).name).isEqualTo("Valid");
            assertThat(collection.importedRequestCount).isEqualTo(1);
            assertThat(collection.skippedRequestCount).isEqualTo(1);
            assertThat(collection.importWarnings).hasSize(1);
            assertThat(collection.importWarnings.get(0))
                    .contains("Broken.bru")
                    .contains("Unclosed Bruno block");
            assertThat(store.snapshot())
                    .filteredOn(event -> event.operation == DiagnosticOperation.IMPORT && event.severity == DiagnosticSeverity.WARNING)
                    .anySatisfy(event -> {
                        assertThat(event.message).contains("Malformed Bruno file skipped");
                        assertThat(event.attributes).containsKey("path");
                    });
        } finally {
            store.clear();
            store.setCaptureEnabled(previousCapture);
        }
    }

    @Test
    void folderAndZipImportsProduceEquivalentBrunoRequestModels() throws Exception {
        Path folder = Files.createDirectories(tempDir.resolve("folder-import"));
        Files.writeString(folder.resolve("bruno.json"), """
                {
                  "name": "Equivalence",
                  "vars": {
                    "baseUrl": "https://api.example.test"
                  }
                }
                """, StandardCharsets.UTF_8);
        Files.createDirectories(folder.resolve("Admin"));
        Files.writeString(folder.resolve("Admin").resolve("Users.bru"), """
                meta {
                  name: Users
                  type: http
                  seq: 7
                }

                get {
                  url: {{baseUrl}}/users
                }

                headers {
                  Authorization: Bearer {{token}}
                }

                auth:bearer {
                  token: {{token}}
                }
                """, StandardCharsets.UTF_8);

        Path zip = createZip("equivalence.zip", new LinkedHashMap<>() {{
            put("collection/bruno.json", """
                    {
                      "name": "Equivalence",
                      "vars": {
                        "baseUrl": "https://api.example.test"
                      }
                    }
                    """);
            put("collection/Admin/Users.bru", """
                    meta {
                      name: Users
                      type: http
                      seq: 7
                    }

                    get {
                      url: {{baseUrl}}/users
                    }

                    headers {
                      Authorization: Bearer {{token}}
                    }

                    auth:bearer {
                      token: {{token}}
                    }
                    """);
        }});

        ApiCollection folderCollection = new BrunoParser().parse(folder.toFile());
        ApiCollection zipCollection = new BrunoParser().parse(zip.toFile());

        assertThat(zipCollection.name).isEqualTo(folderCollection.name);
        assertThat(zipCollection.environment).isEqualTo(folderCollection.environment);
        assertThat(zipCollection.requests).hasSize(folderCollection.requests.size());
        assertThat(zipCollection.requests.get(0).name).isEqualTo(folderCollection.requests.get(0).name);
        assertThat(zipCollection.requests.get(0).path).isEqualTo(folderCollection.requests.get(0).path);
        assertThat(zipCollection.requests.get(0).method).isEqualTo(folderCollection.requests.get(0).method);
        assertThat(zipCollection.requests.get(0).auth.type).isEqualTo(folderCollection.requests.get(0).auth.type);
        assertThat(zipCollection.requests.get(0).auth.properties).isEqualTo(folderCollection.requests.get(0).auth.properties);
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
