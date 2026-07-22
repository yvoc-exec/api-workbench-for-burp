package burp.fidelity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Wave6FidelityDocumentationTest {
    @Test
    void fidelityMatrixCoversEverySupportedCollectionFormat() throws Exception {
        String matrix = Files.readString(Path.of("IMPORT-FIDELITY-MATRIX.md"));
        for (String value : List.of("API Workbench JSON", "Postman v2.0 / v2.1", "OpenAPI 3.x",
                "Swagger 2.0", "Insomnia", "Bruno", "HAR 1.2", "API_WORKBENCH_JSON",
                "POSTMAN_JSON", "OPENAPI_JSON", "OPENAPI_YAML", "INSOMNIA_JSON", "BRUNO_ZIP",
                "HAR_JSON", "POSTMAN_V21", "POSTMAN_V20", "BRUNO_FOLDER", "INSOMNIA_V4",
                "OPENAPI_31_JSON", "OPENAPI_31_YAML", "SWAGGER_20_JSON", "HAR_12", "NATIVE_V2")) {
            assertThat(matrix).contains(value);
        }
    }

    @Test
    void fidelityMatrixContainsRequiredDisclosureSections() throws Exception {
        String matrix = Files.readString(Path.of("IMPORT-FIDELITY-MATRIX.md"));
        for (String heading : List.of("## Status definitions", "## Automated lifecycle",
                "## Per-format fidelity matrix", "## Preserved structures", "## Normalized structures",
                "## Unsupported or retained-only structures", "## Warning behavior",
                "## Exact transport boundary", "## Manual Burp closure remaining")) {
            assertThat(matrix).contains(heading);
        }
        assertThat(matrix.toLowerCase()).doesNotContain("full parity", "100% parity",
                "byte-for-byte wire guarantee", "all scripts translated", "manual smoke passed");
    }

    @Test
    void manualBurpChecklistIsPresentAndExplicitlyPending() throws Exception {
        String checklist = Files.readString(Path.of("WAVE6-MANUAL-BURP-SMOKE.md"));
        assertThat(checklist).contains("Status: PENDING");
        for (String value : List.of("Postman", "Bruno", "Insomnia", "OpenAPI", "Swagger", "HAR",
                "API Workbench JSON", "Workbench", "Send", "Repeater", "Runner", "History",
                "Replay", "Export", "Re-import")) {
            assertThat(checklist).containsIgnoringCase(value);
        }
        assertThat(checklist).doesNotContain("Status: PASS", "Manual smoke passed");
    }

    @Test
    void readmeLinksFidelityMatrixAndManualChecklist() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        assertThat(readme).contains("IMPORT-FIDELITY-MATRIX.md", "WAVE6-MANUAL-BURP-SMOKE.md",
                "api-workbench-for-burp-2.0.1-jar-with-dependencies.jar");
    }
}
