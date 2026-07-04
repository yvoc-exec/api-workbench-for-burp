package burp.parser;

import burp.models.ApiCollection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrunoParserZipSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsEntryCountLimit() throws Exception {
        Path extractionRoot = tempDir.resolve("entry-count");
        Path zip = createZip("entry-count.zip", files(Map.of(
                "collection/bruno.json", """
                        {"name":"Entry Count"}
                        """,
                "collection/requests/one.bru", sampleRequest("One", 1),
                "collection/requests/two.bru", sampleRequest("Two", 2)
        )));

        BrunoParser parser = newParser(new ArchiveImportLimits(1, 1_024, 1_024, 8, 200.0), extractionRoot);

        assertThatThrownBy(() -> parser.parse(zip.toFile()))
                .isInstanceOf(ArchiveImportLimitException.class)
                .satisfies(throwable -> assertThat(((ArchiveImportLimitException) throwable).getReason())
                        .isEqualTo(ArchiveImportLimitException.Reason.ENTRY_COUNT));
        assertThat(Files.exists(extractionRoot)).isFalse();
    }

    @Test
    void rejectsTotalUncompressedSizeLimit() throws Exception {
        Path extractionRoot = tempDir.resolve("total-bytes");
        Path zip = createZip("total-bytes.zip", files(Map.of(
                "collection/bruno.json", """
                        {"name":"Total Bytes"}
                        """,
                "collection/requests/one.bru", sampleRequest("One", 1),
                "collection/requests/two.bru", sampleRequest("Two", 2)
        )));

        BrunoParser parser = newParser(new ArchiveImportLimits(10, 40, 1_024, 8, 200.0), extractionRoot);

        assertThatThrownBy(() -> parser.parse(zip.toFile()))
                .isInstanceOf(ArchiveImportLimitException.class)
                .satisfies(throwable -> assertThat(((ArchiveImportLimitException) throwable).getReason())
                        .isEqualTo(ArchiveImportLimitException.Reason.TOTAL_UNCOMPRESSED_BYTES));
        assertThat(Files.exists(extractionRoot)).isFalse();
    }

    @Test
    void rejectsSingleEntryLimit() throws Exception {
        Path extractionRoot = tempDir.resolve("single-entry");
        Path zip = createZip("single-entry.zip", files(Map.of(
                "collection/bruno.json", """
                        {"name":"Single Entry"}
                        """,
                "collection/requests/large.bru", sampleRequest("Large", 1_024)
        )));

        BrunoParser parser = newParser(new ArchiveImportLimits(10, 10_240, 64, 8, 200.0), extractionRoot);

        assertThatThrownBy(() -> parser.parse(zip.toFile()))
                .isInstanceOf(ArchiveImportLimitException.class)
                .satisfies(throwable -> assertThat(((ArchiveImportLimitException) throwable).getReason())
                        .isEqualTo(ArchiveImportLimitException.Reason.ENTRY_UNCOMPRESSED_BYTES));
        assertThat(Files.exists(extractionRoot)).isFalse();
    }

    @Test
    void rejectsExcessiveCompressionRatio() throws Exception {
        Path extractionRoot = tempDir.resolve("ratio");
        String repeated = "A".repeat(8_192);
        Path zip = createZip("ratio.zip", files(Map.of(
                "collection/bruno.json", """
                        {"name":"Ratio"}
                        """,
                "collection/requests/large.bru", repeated
        )));

        BrunoParser parser = newParser(new ArchiveImportLimits(10, 100_000, 100_000, 8, 1.1), extractionRoot);

        assertThatThrownBy(() -> parser.parse(zip.toFile()))
                .isInstanceOf(ArchiveImportLimitException.class)
                .satisfies(throwable -> assertThat(((ArchiveImportLimitException) throwable).getReason())
                        .isEqualTo(ArchiveImportLimitException.Reason.COMPRESSION_RATIO));
        assertThat(Files.exists(extractionRoot)).isFalse();
    }

    @Test
    void rejectsExcessivePathDepth() throws Exception {
        Path extractionRoot = tempDir.resolve("depth");
        Path zip = createZip("depth.zip", files(Map.of(
                "collection/bruno.json", """
                        {"name":"Depth"}
                        """,
                "collection/a/b/c/d/e/f/g/too-deep.bru", sampleRequest("Too Deep", 1)
        )));

        BrunoParser parser = newParser(new ArchiveImportLimits(10, 10_240, 10_240, 4, 200.0), extractionRoot);

        assertThatThrownBy(() -> parser.parse(zip.toFile()))
                .isInstanceOf(ArchiveImportLimitException.class)
                .satisfies(throwable -> assertThat(((ArchiveImportLimitException) throwable).getReason())
                        .isEqualTo(ArchiveImportLimitException.Reason.PATH_DEPTH));
        assertThat(Files.exists(extractionRoot)).isFalse();
    }

    @Test
    void cleansTemporaryDirectoryAfterLimitFailure() throws Exception {
        Path extractionRoot = tempDir.resolve("cleanup");
        Path zip = createZip("cleanup.zip", files(Map.of(
                "collection/bruno.json", """
                        {"name":"Cleanup"}
                        """,
                "collection/requests/one.bru", sampleRequest("One", 1),
                "collection/requests/two.bru", sampleRequest("Two", 2)
        )));

        BrunoParser parser = newParser(new ArchiveImportLimits(1, 1_024, 1_024, 8, 200.0), extractionRoot);

        assertThatThrownBy(() -> parser.parse(zip.toFile()))
                .isInstanceOf(ArchiveImportLimitException.class);
        assertThat(Files.exists(extractionRoot)).isFalse();
    }

    @Test
    void doesNotReturnPartialCollectionAfterLimitFailure() throws Exception {
        Path extractionRoot = tempDir.resolve("partial");
        Path zip = createZip("partial.zip", files(Map.of(
                "collection/bruno.json", """
                        {"name":"Partial"}
                        """,
                "collection/requests/one.bru", sampleRequest("One", 1),
                "collection/requests/two.bru", sampleRequest("Two", 2)
        )));

        BrunoParser parser = newParser(new ArchiveImportLimits(1, 1_024, 1_024, 8, 200.0), extractionRoot);

        ApiCollection collection = null;
        try {
            collection = parser.parse(zip.toFile());
        } catch (ArchiveImportLimitException expected) {
            // expected
        }

        assertThat(collection).isNull();
        assertThat(Files.exists(extractionRoot)).isFalse();
    }

    @Test
    void validArchiveStillImports() throws Exception {
        Path extractionRoot = tempDir.resolve("valid");
        Path zip = createZip("valid.zip", files(Map.of(
                "collection/bruno.json", """
                        {"name":"Valid Collection"}
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
        )));

        BrunoParser parser = newParser(new ArchiveImportLimits(), extractionRoot);
        ApiCollection collection = parser.parse(zip.toFile());

        assertThat(collection.name).isEqualTo("Valid Collection");
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.requests.get(0).name).isEqualTo("Get Users");
        assertThat(Files.exists(extractionRoot)).isFalse();
    }

    @Test
    void rejectsAbsoluteAndDriveQualifiedPaths() throws Exception {
        Path absoluteZip = createZip("absolute.zip", files(Map.of(
                "collection/bruno.json", """
                        {"name":"Absolute"}
                        """,
                "/escape/Nope.bru", sampleRequest("Nope", 1)
        )));
        Path driveZip = createZip("drive.zip", files(Map.of(
                "collection/bruno.json", """
                        {"name":"Drive"}
                        """,
                "C:/escape/Nope.bru", sampleRequest("Nope", 1)
        )));

        BrunoParser parser = newParser(new ArchiveImportLimits(), tempDir.resolve("absolute"));

        assertThatThrownBy(() -> parser.parse(absoluteZip.toFile()))
                .isInstanceOf(ArchiveImportLimitException.class)
                .satisfies(throwable -> assertThat(((ArchiveImportLimitException) throwable).getReason())
                        .isEqualTo(ArchiveImportLimitException.Reason.UNSAFE_PATH));

        BrunoParser driveParser = newParser(new ArchiveImportLimits(), tempDir.resolve("drive"));
        assertThatThrownBy(() -> driveParser.parse(driveZip.toFile()))
                .isInstanceOf(ArchiveImportLimitException.class)
                .satisfies(throwable -> assertThat(((ArchiveImportLimitException) throwable).getReason())
                        .isEqualTo(ArchiveImportLimitException.Reason.UNSAFE_PATH));
    }

    @Test
    void countsDirectoriesTowardEntryLimit() throws Exception {
        Path extractionRoot = tempDir.resolve("directories");
        Path zip = createZip("directories.zip", files(Map.of(
                "collection/bruno.json", """
                        {"name":"Directories"}
                        """,
                "collection/requests/one.bru", sampleRequest("One", 1)
        )), List.of("collection/", "collection/requests/"));

        BrunoParser parser = newParser(new ArchiveImportLimits(2, 10_240, 10_240, 8, 200.0), extractionRoot);

        assertThatThrownBy(() -> parser.parse(zip.toFile()))
                .isInstanceOf(ArchiveImportLimitException.class)
                .satisfies(throwable -> assertThat(((ArchiveImportLimitException) throwable).getReason())
                        .isEqualTo(ArchiveImportLimitException.Reason.ENTRY_COUNT));
        assertThat(Files.exists(extractionRoot)).isFalse();
    }

    @Test
    void rejectsDuplicateNormalizedOutputPaths() throws Exception {
        Path extractionRoot = tempDir.resolve("duplicates");
        Path zip = createZip("duplicates.zip", files(Map.of(
                "collection/bruno.json", """
                        {"name":"Duplicates"}
                        """,
                "collection/Requests/One.bru", sampleRequest("One", 1),
                "collection/requests/one.bru", sampleRequest("One Again", 2)
        )));

        BrunoParser parser = newParser(new ArchiveImportLimits(), extractionRoot);

        assertThatThrownBy(() -> parser.parse(zip.toFile()))
                .isInstanceOf(ArchiveImportLimitException.class)
                .satisfies(throwable -> assertThat(((ArchiveImportLimitException) throwable).getReason())
                        .isEqualTo(ArchiveImportLimitException.Reason.DUPLICATE_OUTPUT_PATH));
        assertThat(Files.exists(extractionRoot)).isFalse();
    }

    @Test
    void keepsExistingZipSlipProtection() throws Exception {
        Path extractionRoot = tempDir.resolve("zip-slip");
        Path zip = createZip("zip-slip.zip", files(Map.of(
                "collection/bruno.json", """
                        {"name":"Zip Slip"}
                        """,
                "../escape/Nope.bru", sampleRequest("Nope", 1)
        )));

        BrunoParser parser = newParser(new ArchiveImportLimits(), extractionRoot);

        assertThatThrownBy(() -> parser.parse(zip.toFile()))
                .isInstanceOf(ArchiveImportLimitException.class)
                .satisfies(throwable -> assertThat(((ArchiveImportLimitException) throwable).getReason())
                        .isEqualTo(ArchiveImportLimitException.Reason.UNSAFE_PATH));
        assertThat(Files.exists(extractionRoot)).isFalse();
    }

    private static Map<String, String> files(Map<String, String> entries) {
        return new LinkedHashMap<>(entries);
    }

    private static String sampleRequest(String name, int seq) {
        return """
                meta {
                  name: %s
                  type: http
                  seq: %d
                }

                get {
                  url: https://api.example.test/%s
                }
                """.formatted(name, seq, name.toLowerCase());
    }

    private Path createZip(String name, Map<String, String> entries) throws IOException {
        return createZip(name, entries, java.util.List.of());
    }

    private Path createZip(String name, Map<String, String> entries, java.util.List<String> directories) throws IOException {
        Path zip = tempDir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            for (String directory : directories) {
                if (directory == null || directory.isBlank()) {
                    continue;
                }
                String normalized = directory.endsWith("/") ? directory : directory + "/";
                ZipEntry entry = new ZipEntry(normalized);
                out.putNextEntry(entry);
                out.closeEntry();
            }
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                out.putNextEntry(zipEntry);
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }

    private BrunoParser newParser(ArchiveImportLimits limits, Path extractionRoot) {
        return new BrunoParser(limits) {
            @Override
            Path createTemporaryExtractionDirectory() throws IOException {
                Files.createDirectories(extractionRoot);
                return extractionRoot;
            }
        };
    }
}
