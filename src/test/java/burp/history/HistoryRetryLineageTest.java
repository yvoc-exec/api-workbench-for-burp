package burp.history;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.RunnerCancellationState;
import burp.models.RunnerResult;
import burp.models.WorkspaceState;
import burp.runner.FlowTargetResolutionForm;
import burp.runner.RetryFailureType;
import burp.testsupport.HistoryTestFixtures;
import burp.utils.ExecutionPreflightStatus;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryRetryLineageTest {

    @Test
    void retryAttemptsHaveDistinctAttemptNumbers() {
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        ApiRequest request = HistoryTestFixtures.sampleRequest();
        RunnerResult first = HistoryTestFixtures.sampleRunnerResult(1, 3, false, 500, "retry");
        RunnerResult second = HistoryTestFixtures.sampleRunnerResult(2, 3, true, 200, null);

        HistoryEntry firstEntry = HistoryEntry.fromRunnerAttempt(collection, request, null, first);
        HistoryEntry secondEntry = HistoryEntry.fromRunnerAttempt(collection, request, null, second);

        assertThat(firstEntry.attemptNumber).isEqualTo(1);
        assertThat(firstEntry.totalAttempts).isEqualTo(3);
        assertThat(secondEntry.attemptNumber).isEqualTo(2);
        assertThat(secondEntry.totalAttempts).isEqualTo(3);
    }

    @Test
    void dependentRequestKeepsParentLineage() {
        HistoryEntry entry = HistoryEntry.fromRunnerAttempt(HistoryTestFixtures.sampleCollection(),
                HistoryTestFixtures.sampleRequest(),
                null,
                lineageResult(true, false, "Parent", "parent-id", 2));

        assertThat(entry.parentRequestName).isEqualTo("Parent");
        assertThat(entry.parentRequestId).isEqualTo("parent-id");
        assertThat(entry.dependentExecution).isTrue();
        assertThat(entry.dependentDepth).isEqualTo(2);
        assertThat(entry.toMetadataText()).contains("Parent Request: Parent [parent-id]");
    }

    @Test
    void adHocRequestKeepsParentLineage() {
        HistoryEntry entry = HistoryEntry.fromRunnerAttempt(HistoryTestFixtures.sampleCollection(),
                HistoryTestFixtures.sampleRequest(),
                null,
                lineageResult(false, true, "Parent", "parent-id", 1));

        assertThat(entry.adHocExecution).isTrue();
        assertThat(entry.parentRequestName).isEqualTo("Parent");
        assertThat(entry.parentRequestId).isEqualTo("parent-id");
        assertThat(entry.toMetadataText()).contains("Execution Kind: AD_HOC");
    }

    @Test
    void cancelledAttemptIsNotRecordedAsSuccess() {
        RunnerResult result = HistoryTestFixtures.sampleRunnerResult(1, 1, true, 200, null);
        result.success = false;
        result.preflightStatus = ExecutionPreflightStatus.CANCELLED;
        result.cancellationState = RunnerCancellationState.LATE_RESPONSE_IGNORED;

        HistoryEntry entry = HistoryEntry.fromRunnerAttempt(HistoryTestFixtures.sampleCollection(),
                HistoryTestFixtures.sampleRequest(),
                null,
                result);

        assertThat(entry.result).isEqualTo(HistoryResult.CANCELLED);
        assertThat(entry.result).isNotEqualTo(HistoryResult.SUCCESS);
        assertThat(entry.toMetadataText()).contains("Cancellation State: LATE_RESPONSE_IGNORED");
    }

    @Test
    void requestMayHaveBeenProcessedRoundTrips() {
        HistoryEntry entry = baseRunnerEntry();
        entry.requestMayHaveBeenProcessed = true;

        WorkspaceState state = new WorkspaceState();
        state.historyEntries.add(entry);

        WorkspaceState parsed = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));

        assertThat(parsed.historyEntries).hasSize(1);
        assertThat(parsed.historyEntries.get(0).requestMayHaveBeenProcessed).isTrue();
    }

    @Test
    void retryMetadataRoundTrips() {
        HistoryEntry entry = baseRunnerEntry();
        entry.retryDecision = "RETRY";
        entry.retryReason = "Retrying connection failure for eligible method GET.";
        entry.retryDelayMillis = 400;
        entry.retryFailureType = RetryFailureType.CONNECTION_FAILURE.name();

        WorkspaceState parsed = roundTrip(entry);

        HistoryEntry copy = parsed.historyEntries.get(0);
        assertThat(copy.retryDecision).isEqualTo("RETRY");
        assertThat(copy.retryReason).contains("Retrying connection failure");
        assertThat(copy.retryDelayMillis).isEqualTo(400);
        assertThat(copy.retryFailureType).isEqualTo(RetryFailureType.CONNECTION_FAILURE.name());
    }

    @Test
    void targetResolutionMetadataRoundTrips() {
        HistoryEntry entry = baseRunnerEntry();
        entry.targetResolutionForm = FlowTargetResolutionForm.QUALIFIED_PATH.name();
        entry.qualifiedTargetPath = "Collection/Folder/Request";

        WorkspaceState parsed = roundTrip(entry);

        HistoryEntry copy = parsed.historyEntries.get(0);
        assertThat(copy.targetResolutionForm).isEqualTo(FlowTargetResolutionForm.QUALIFIED_PATH.name());
        assertThat(copy.qualifiedTargetPath).isEqualTo("Collection/Folder/Request");
    }

    @Test
    void oldEntryWithoutNewFieldsRemainsReadable() {
        String json = """
                {
                  "version": 1,
                  "historyEntries": [{
                    "id": "legacy",
                    "timestamp": "2026-06-15T01:30:00Z",
                    "source": "RUNNER",
                    "requestName": "Legacy",
                    "requestSnapshot": {
                      "method": "GET",
                      "urlTemplate": "https://api.example.test/legacy"
                    }
                  }]
                }
                """;

        WorkspaceState state = WorkspaceStateJson.fromJson(json);
        assertThat(state.historyEntries).hasSize(1);
        HistoryEntry entry = state.historyEntries.get(0);
        assertThat(entry.requestName).isEqualTo("Legacy");
        assertThat(entry.retryDecision).isNull();
        assertThat(entry.requestMayHaveBeenProcessed).isFalse();
        assertThat(entry.cancellationState).isEqualTo(RunnerCancellationState.NOT_CANCELLED.name());
    }

    private static RunnerResult lineageResult(boolean dependent, boolean adHoc, String parentName, String parentId, int depth) {
        RunnerResult result = HistoryTestFixtures.sampleRunnerResult(1, 1, true, 200, null);
        result.dependentExecution = dependent;
        result.adHocExecution = adHoc;
        result.parentRequestName = parentName;
        result.parentRequestId = parentId;
        result.dependentDepth = depth;
        return result;
    }

    private static HistoryEntry baseRunnerEntry() {
        HistoryEntry entry = HistoryTestFixtures.sampleRunnerEntry();
        entry.id = "history-base";
        entry.timestamp = Instant.parse("2026-06-15T01:25:05Z");
        return entry;
    }

    private static WorkspaceState roundTrip(HistoryEntry entry) {
        WorkspaceState state = new WorkspaceState();
        state.historyEntries.add(entry);
        return WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));
    }
}
