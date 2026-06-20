package burp.diagnostics;

import burp.exporter.EnvironmentExportFormat;
import burp.exporter.EnvironmentExportOptions;
import burp.exporter.EnvironmentExportService;
import burp.history.HistoryEntry;
import burp.models.EnvironmentProfile;
import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticPassiveBehaviorTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        DiagnosticStore.getInstance().setCaptureEnabled(false);
        DiagnosticStore.getInstance().clear();
    }

    @Test
    void diagnosticsToggleDoesNotChangeHistoryCaptureOutputs() {
        burp.utils.ExecutionResult execution = HistoryTestFixtures.sampleWorkbenchExecutionResult();
        execution.scriptWarnings.add("warn");

        DiagnosticStore.getInstance().setCaptureEnabled(false);
        HistoryEntry disabled = HistoryEntry.fromWorkbenchExecution(
                HistoryTestFixtures.sampleCollection(),
                HistoryTestFixtures.sampleRequest(),
                HistoryTestFixtures.sampleEnvironment(),
                execution,
                1,
                1,
                List.of("missing"));

        DiagnosticStore.getInstance().clear();
        DiagnosticStore.getInstance().setCaptureEnabled(true);
        HistoryEntry enabled = HistoryEntry.fromWorkbenchExecution(
                HistoryTestFixtures.sampleCollection(),
                HistoryTestFixtures.sampleRequest(),
                HistoryTestFixtures.sampleEnvironment(),
                execution,
                1,
                1,
                List.of("missing"));

        assertEquivalent(disabled, enabled);
    }

    @Test
    void diagnosticsToggleDoesNotChangeEnvironmentImportOrExportResults() throws Exception {
        Path envFile = tempDir.resolve("uat.env");
        Files.writeString(envFile, """
                base_url=https://api.example.test
                token=abc123
                """);

        DiagnosticStore.getInstance().setCaptureEnabled(false);
        List<EnvironmentProfile> importedDisabled = burp.utils.EnvironmentImportService.importEnvironment(envFile.toFile());

        DiagnosticStore.getInstance().setCaptureEnabled(true);
        List<EnvironmentProfile> importedEnabled = burp.utils.EnvironmentImportService.importEnvironment(envFile.toFile());

        assertThat(importedDisabled).hasSize(1);
        assertThat(importedEnabled).hasSize(1);
        assertThat(importedDisabled.get(0).name).isEqualTo(importedEnabled.get(0).name);
        assertThat(importedDisabled.get(0).sourceFormat).isEqualTo(importedEnabled.get(0).sourceFormat);
        assertThat(importedDisabled.get(0).sourceFileName).isEqualTo(importedEnabled.get(0).sourceFileName);
        assertThat(importedDisabled.get(0).variables).isEqualTo(importedEnabled.get(0).variables);
        assertThat(importedDisabled.get(0).oauth2.config).isEqualTo(importedEnabled.get(0).oauth2.config);
        assertThat(importedDisabled.get(0).oauth2.outputBindings).isEqualTo(importedEnabled.get(0).oauth2.outputBindings);

        EnvironmentProfile profile = importedEnabled.get(0);
        Path outputDisabled = tempDir.resolve("uat-disabled.api-workbench.environment.json");
        Path outputEnabled = tempDir.resolve("uat-enabled.api-workbench.environment.json");

        new EnvironmentExportService().exportEnvironment(profile, new EnvironmentExportOptions(EnvironmentExportFormat.API_WORKBENCH_JSON, outputDisabled));
        String exportedDisabled = Files.readString(outputDisabled);

        DiagnosticStore.getInstance().setCaptureEnabled(true);
        new EnvironmentExportService().exportEnvironment(profile, new EnvironmentExportOptions(EnvironmentExportFormat.API_WORKBENCH_JSON, outputEnabled));
        String exportedEnabled = Files.readString(outputEnabled);

        assertThat(exportedDisabled).contains("base_url");
        assertThat(exportedDisabled).contains("token");
        assertThat(exportedDisabled).isEqualTo(exportedEnabled);
    }

    private static void assertEquivalent(HistoryEntry left, HistoryEntry right) {
        assertThat(left.source).isEqualTo(right.source);
        assertThat(left.attemptNumber).isEqualTo(right.attemptNumber);
        assertThat(left.totalAttempts).isEqualTo(right.totalAttempts);
        assertThat(left.collectionName).isEqualTo(right.collectionName);
        assertThat(left.requestName).isEqualTo(right.requestName);
        assertThat(left.environmentName).isEqualTo(right.environmentName);
        assertThat(left.requestSnapshot.preferredRawRequestText()).isEqualTo(right.requestSnapshot.preferredRawRequestText());
        assertThat(left.requestSnapshot.rawRequestSent).isEqualTo(right.requestSnapshot.rawRequestSent);
        assertThat(left.requestSnapshot.resolvedVariables).isEqualTo(right.requestSnapshot.resolvedVariables);
        assertThat(left.responseSnapshot.displayHeaderBlock()).isEqualTo(right.responseSnapshot.displayHeaderBlock());
        assertThat(left.statusCode).isEqualTo(right.statusCode);
        assertThat(left.result).isEqualTo(right.result);
        assertThat(left.errorMessage).isEqualTo(right.errorMessage);
        assertThat(left.finalResolvedUrl).isEqualTo(right.finalResolvedUrl);
        assertThat(left.host).isEqualTo(right.host);
        assertThat(left.unresolvedVariables).isEqualTo(right.unresolvedVariables);
        assertThat(left.scriptWarnings).isEqualTo(right.scriptWarnings);
        assertThat(left.scriptErrors).isEqualTo(right.scriptErrors);
        assertThat(left.scriptVariableMutations).hasSize(right.scriptVariableMutations.size());
    }
}
