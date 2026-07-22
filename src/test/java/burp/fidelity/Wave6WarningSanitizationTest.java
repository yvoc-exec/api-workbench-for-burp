package burp.fidelity;

import burp.exporter.CollectionExportFormat;
import burp.exporter.CollectionExportOptions;
import burp.exporter.CollectionExportService;
import burp.exporter.ExportResult;
import burp.models.ApiCollection;
import burp.parser.BrunoParser;
import burp.parser.HarParser;
import burp.parser.InsomniaParser;
import burp.parser.OpenApiParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Wave6WarningSanitizationTest {
    private static final String ROOT = "fidelity/wave6/adversarial/";

    @TempDir
    Path tempDir;

    @Test
    void openApiImportWarningsAreSanitized() throws Exception {
        Path source = copy("openapi-warning.yaml");
        ApiCollection collection = new OpenApiParser().parse(source.toFile());

        assertThat(collection.importWarnings).isNotEmpty();
        assertSanitizedWarnings(collection.importWarnings, "WAVE6_OPENAPI_WARNING_CANARY");
    }

    @Test
    void insomniaImportWarningsAreSanitizedAndMalformedResourcesDoNotAbortLaterRequests() throws Exception {
        ApiCollection collection = new InsomniaParser().parse(copy("insomnia-warning.insomnia.json").toFile());

        assertThat(collection.requests).extracting(request -> request.name).contains("Valid").doesNotContain("Malformed");
        assertThat(collection.skippedRequestCount).isEqualTo(1);
        assertThat(collection.importWarnings).isNotEmpty();
        assertThat(collection.requests).allSatisfy(request -> assertThat(request.scriptBlocks)
                .noneMatch(block -> block.source != null
                        && block.source.contains("WAVE6_INSOMNIA_WARNING_CANARY")));
        assertSanitizedWarnings(collection.importWarnings, "WAVE6_INSOMNIA_WARNING_CANARY");
    }

    @Test
    void brunoImportAndExportWarningsAreSanitized() throws Exception {
        ApiCollection collection = new BrunoParser().parse(copy("bruno-warning.bru").toFile());
        Path target = tempDir.resolve("warning.bruno.zip");
        ExportResult result = new CollectionExportService().exportCollection(collection,
                options(CollectionExportFormat.BRUNO_ZIP, target));
        List<String> warnings = new ArrayList<>(collection.importWarnings);
        warnings.addAll(result.warnings);

        assertThat(warnings).isNotEmpty();
        assertSanitizedWarnings(warnings,
                "WAVE6_BRUNO_WARNING_CANARY", "WAVE6_EXPORT_WARNING_CANARY");
    }

    @Test
    void harWarningsOmitUnsafeHeaderContents() throws Exception {
        ApiCollection collection = new HarParser().parse(copy("har-warning.har").toFile());
        Path target = tempDir.resolve("warning-export.har");
        ExportResult result = new CollectionExportService().exportCollection(collection,
                options(CollectionExportFormat.HAR_JSON, target));
        String artifact = Files.readString(target, StandardCharsets.UTF_8);

        assertThat(result.warnings).isNotEmpty();
        assertThat(artifact).doesNotContain("Bad Header", "X-Break", "WAVE6_HAR_WARNING_CANARY",
                "Injected: yes");
        assertSanitizedWarnings(result.warnings, "WAVE6_HAR_WARNING_CANARY");
    }

    @Test
    void allRepresentativeExportWarningsAreSanitized() throws Exception {
        for (Wave6FixtureSupport.FixtureCase fixture : Wave6FixtureSupport.FixtureCase.values()) {
            Wave6FixtureSupport.LifecycleResult result = Wave6FixtureSupport.runLifecycle(
                    fixture, tempDir.resolve("representative"));
            List<String> warnings = new ArrayList<>();
            warnings.addAll(result.firstNativeExport().warnings);
            warnings.addAll(result.secondNativeExport().warnings);
            warnings.addAll(result.targetExport().warnings);
            assertSanitizedWarnings(warnings);
            assertThat(warnings).allSatisfy(warning -> assertThat(warning)
                    .doesNotContain("wave6-token", "retained text", "session=cookie",
                            "fixtures/not-read.bin"));
        }
    }

    private void assertSanitizedWarnings(List<String> warnings, String... canaries) {
        assertThat(new LinkedHashSet<>(warnings)).hasSize(warnings.size());
        for (String warning : warnings) {
            assertThat(warning).isNotBlank()
                    .doesNotContain("\r", "\n", "\u0000", tempDir.toAbsolutePath().toString(),
                            "Authorization:", "Bearer ", "Cookie:", "Set-Cookie:",
                            "C:\\Users\\", "/home/");
            if (canaries.length > 0) {
                assertThat(warning).doesNotContain(canaries);
            }
        }
    }

    private Path copy(String fileName) throws Exception {
        return Wave6FixtureSupport.copyResource(ROOT + fileName, tempDir.resolve(fileName));
    }

    private static CollectionExportOptions options(CollectionExportFormat format, Path output) {
        return new CollectionExportOptions(format, output, false, null, Map.of());
    }
}
