package burp.history;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerResult;
import burp.scripts.ExecutionSource;
import burp.scripts.ScriptFlowControl;
import burp.scripts.ScriptLogEntry;
import burp.scripts.ScriptVariableMutation;
import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryEntryCompatibilityTest {

    @Test
    void workbenchCaptureRetainsRawAndAuthoredSnapshotsAlongWithExecutionMetadata() {
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        ApiRequest request = HistoryTestFixtures.sampleRequest();
        EnvironmentProfile environment = HistoryTestFixtures.sampleEnvironment();
        burp.utils.ExecutionResult execution = HistoryTestFixtures.sampleWorkbenchExecutionResult();
        execution.scriptEngineName = "GraalJS";
        execution.executionSource = ExecutionSource.WORKBENCH_SEND;
        execution.scriptFlowControl = ScriptFlowControl.CONTINUE;
        execution.resolvedUrl = "https://api.example.test/login";
        execution.scriptFlowMessage = "Continue";
        execution.scriptLogs.add(new ScriptLogEntry("info", "script log", "script-1", "Pre Request"));
        execution.scriptWarnings.add("warn-one");
        execution.scriptErrors.add("error-one");
        ScriptVariableMutation mutation = new ScriptVariableMutation("token", "old", "new", "environment", true);
        mutation.sourceScriptId = "script-1";
        mutation.sourceScriptName = "Pre Request";
        execution.scriptVariableMutations.add(mutation);
        execution.resolvedVariables.put("base_url", "https://api.example.test");
        execution.resolvedVariables.put("token", "env-token");

        HistoryEntry entry = HistoryEntry.fromWorkbenchExecution(
                collection,
                request,
                environment,
                execution,
                2,
                4,
                List.of("missing_token"));

        assertThat(entry.source).isEqualTo(HistorySource.WORKBENCH);
        assertThat(entry.attemptDisplay()).isEqualTo("2/4");
        assertThat(entry.requestSnapshot).isNotNull();
        assertThat(entry.requestSnapshot.authoredRequest).isNotSameAs(request);
        assertThat(entry.requestSnapshot.hasRawRequestSent()).isTrue();
        assertThat(entry.requestSnapshot.preferredRawRequestText()).contains("POST /login HTTP/1.1");
        assertThat(entry.requestSnapshot.resolvedUrl).isEqualTo(execution.resolvedUrl);
        assertThat(entry.requestSnapshot.resolvedVariables).containsEntry("token", "env-token");
        assertThat(entry.responseSnapshot).isNotNull();
        assertThat(entry.responseSnapshot.bodyAsText()).contains("{\"ok\":true}");
        assertThat(entry.finalResolvedUrl).isEqualTo(execution.resolvedUrl);
        assertThat(entry.host).isEqualTo("api.example.test");
        assertThat(entry.scriptEngineName).isEqualTo("GraalJS");
        assertThat(entry.executionSource).isEqualTo("WORKBENCH_SEND");
        assertThat(entry.scriptLogs).hasSize(1);
        assertThat(entry.scriptWarnings).containsExactly("warn-one");
        assertThat(entry.scriptErrors).containsExactly("error-one");
        assertThat(entry.scriptVariableMutations).hasSize(1);
        assertThat(entry.unresolvedVariables).containsExactly("missing_token");
        assertThat(entry.result).isEqualTo(HistoryResult.MISSING_VARIABLE);
        assertThat(entry.resultClassification).isEqualTo(HistoryResult.MISSING_VARIABLE.displayName());
        assertThat(entry.toMetadataText()).contains("Raw Request Available: yes");
        assertThat(entry.toMetadataText()).contains("Result Classification: Missing Variable");

        execution.rawRequestBytes[0] = 'X';
        execution.resolvedVariables.put("token", "mutated");

        assertThat(entry.requestSnapshot.rawRequestSent[0]).isNotEqualTo((byte) 'X');
        assertThat(entry.requestSnapshot.resolvedVariables).containsEntry("token", "env-token");
    }

    @Test
    void runnerCaptureClassifiesSkippedStoppedAndFailedAttemptMetadata() {
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        ApiRequest request = HistoryTestFixtures.sampleRequest();
        EnvironmentProfile environment = HistoryTestFixtures.sampleEnvironment();

        RunnerResult skipped = HistoryTestFixtures.sampleRunnerResult(3, 5, false, 0, null);
        skipped.scriptFlowControl = ScriptFlowControl.SKIP_REQUEST;
        skipped.scriptFlowMessage = "Skipped intentionally";
        skipped.executionSource = ExecutionSource.RUNNER;
        skipped.responseSize = 0;
        skipped.statusCode = 0;
        skipped.responseBody = null;
        skipped.responseHeaders = null;

        HistoryEntry skippedEntry = HistoryEntry.fromRunnerAttempt(collection, request, environment, skipped);
        assertThat(skippedEntry.source).isEqualTo(HistorySource.RUNNER);
        assertThat(skippedEntry.result).isEqualTo(HistoryResult.SKIPPED);
        assertThat(skippedEntry.resultClassification).isEqualTo("Skipped by Script");
        assertThat(skippedEntry.hasResponseBody()).isFalse();
        assertThat(skippedEntry.toMetadataText()).contains("Flow Control: SKIP_REQUEST");

        RunnerResult stopped = HistoryTestFixtures.sampleRunnerResult(4, 5, false, 0, null);
        stopped.scriptFlowControl = ScriptFlowControl.STOP_RUN;
        stopped.scriptFlowMessage = "runner stopped";
        stopped.executionSource = ExecutionSource.RUNNER;
        stopped.responseSize = 0;
        stopped.statusCode = 0;
        stopped.responseBody = null;
        stopped.responseHeaders = null;

        HistoryEntry stoppedEntry = HistoryEntry.fromRunnerAttempt(collection, request, environment, stopped);
        assertThat(stoppedEntry.result).isEqualTo(HistoryResult.STOPPED);
        assertThat(stoppedEntry.resultClassification).isEqualTo("Stopped by Script");
        assertThat(stoppedEntry.errorMessage).isNull();
        assertThat(stoppedEntry.hasError()).isFalse();
        assertThat(stoppedEntry.toMetadataText()).contains("runner stopped");

        RunnerResult failed = HistoryTestFixtures.sampleRunnerResult(5, 5, false, 500, "boom");
        failed.executionSource = ExecutionSource.RUNNER;
        failed.responseSize = 12;
        failed.responseBody = "{\"error\":true}";
        failed.responseHeaders = "HTTP/1.1 500 Error\nContent-Type: application/json";
        HistoryEntry failedEntry = HistoryEntry.fromRunnerAttempt(collection, request, environment, failed);
        assertThat(failedEntry.result).isEqualTo(HistoryResult.ASSERTION_FAILURE);
        assertThat(failedEntry.resultClassification).isEqualTo(HistoryResult.ASSERTION_FAILURE.displayName());
        assertThat(failedEntry.hasResponseBody()).isTrue();
        assertThat(failedEntry.responseSnapshot.displayHeaderBlock()).contains("HTTP/1.1 500 Error");
    }

    @Test
    void copyOfAndEnsureDefaultsFillPartialLegacyStateWithoutSharingNestedInstances() {
        HistoryEntry entry = new HistoryEntry();
        entry.id = "";
        entry.timestamp = null;
        entry.requestSnapshot = new HistoryRequestSnapshot();
        entry.requestSnapshot.rawRequestSentText = "raw";
        entry.unresolvedVariables = null;
        entry.assertions = null;
        entry.extractions = null;
        entry.scriptLogs = null;
        entry.scriptWarnings = null;
        entry.scriptErrors = null;
        entry.scriptVariableMutations = null;
        entry.result = null;
        entry.resultClassification = null;
        entry.scriptFlowControl = null;

        entry.ensureDefaults();
        assertThat(entry.id).isNotBlank();
        assertThat(entry.timestamp).isNotNull();
        assertThat(entry.result).isEqualTo(HistoryResult.UNKNOWN);
        assertThat(entry.resultClassification).isEqualTo(HistoryResult.UNKNOWN.displayName());
        assertThat(entry.scriptFlowControl).isEqualTo(ScriptFlowControl.CONTINUE);
        assertThat(entry.assertions).isNotNull().isEmpty();
        assertThat(entry.extractions).isNotNull().isEmpty();
        assertThat(entry.scriptLogs).isNotNull().isEmpty();

        HistoryEntry copy = HistoryEntry.copyOf(entry);
        assertThat(copy).isNotSameAs(entry);
        assertThat(copy.requestSnapshot).isNotSameAs(entry.requestSnapshot);
        assertThat(copy.requestSnapshot.preferredRawRequestText()).isEqualTo("raw");
        copy.requestSnapshot.rawRequestSentText = "changed";
        assertThat(entry.requestSnapshot.rawRequestSentText).isEqualTo("raw");
    }
}
