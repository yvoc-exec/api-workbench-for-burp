package burp.history;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerResult;
import burp.utils.ExecutionPreflightStatus;
import burp.utils.ExecutionResult;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryPreflightMetadataTest {

    @Test
    void blockedWorkbenchEntryPreservesRequestSentFalse() {
        HistoryEntry entry = HistoryEntry.fromWorkbenchExecution(collection(), request(), environment(), blockedExec(), 1, 1, List.of());

        assertThat(entry.requestSent).isFalse();
        assertThat(entry.result).isEqualTo(HistoryResult.BLOCKED);
        assertThat(entry.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR.name());
        assertThat(entry.requestSnapshot.hasRawRequestSent()).isFalse();
    }

    @Test
    void blockedEntryDoesNotClaimRawRequestWasSent() {
        HistoryEntry entry = HistoryEntry.fromWorkbenchExecution(collection(), request(), environment(), blockedExec(), 1, 1, List.of());

        assertThat(entry.requestSnapshot.hasRawRequestSent()).isFalse();
        assertThat(entry.requestSnapshot.rawRequestSentText).isNull();
    }

    @Test
    void timeoutEntryPreservesTimeoutMetadata() {
        ExecutionResult exec = new ExecutionResult();
        exec.requestSent = true;
        exec.responseTimedOut = true;
        exec.timeoutMillis = 2_500;
        exec.preflightStatus = ExecutionPreflightStatus.READY;
        exec.preflightMessage = "Request sent, but response timed out after 2500 ms.";
        exec.rawRequestBytes = "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        exec.rawRequestText = new String(exec.rawRequestBytes, StandardCharsets.UTF_8);
        exec.resolvedUrl = "https://example.test/";
        exec.effectiveResolvedUrl = "https://example.test/";
        exec.originalResolvedUrl = "https://example.test/";
        exec.success = false;

        HistoryEntry entry = HistoryEntry.fromWorkbenchExecution(collection(), request(), environment(), exec, 1, 1, List.of());

        assertThat(entry.result).isEqualTo(HistoryResult.TIMEOUT);
        assertThat(entry.responseTimedOut).isTrue();
        assertThat(entry.timeoutMillis).isEqualTo(2_500);
    }

    @Test
    void runnerBlockedEntryPreservesPreflightStatus() {
        RunnerResult result = new RunnerResult();
        result.requestSent = false;
        result.preflightStatus = ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE;
        result.preflightMessage = "Request not sent — script-driven destination change was not approved.";
        result.errorMessage = result.preflightMessage;
        result.unresolvedVariables = List.of("token");
        result.policyOverridesApplied = List.of("Target change override");

        HistoryEntry entry = HistoryEntry.fromRunnerAttempt(collection(), request(), environment(), result);

        assertThat(entry.result).isEqualTo(HistoryResult.BLOCKED);
        assertThat(entry.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE.name());
        assertThat(entry.requestSent).isFalse();
    }

    @Test
    void oldHistoryJsonRestoresNewFieldsWithDefaults() {
        WorkspaceStateJson.fromJson("""
                {
                  "version": 1,
                  "collections": [],
                  "environments": [],
                  "historyEntries": [{
                    "id": "hist-1",
                    "timestamp": "2026-07-01T00:00:00Z",
                    "source": "WORKBENCH",
                    "requestSnapshot": {
                      "method": "GET",
                      "urlTemplate": "https://example.test/"
                    }
                  }]
                }
                """);
        HistoryEntry entry = WorkspaceStateJson.fromJson("""
                {
                  "version": 1,
                  "collections": [],
                  "environments": [],
                  "historyEntries": [{
                    "id": "hist-1",
                    "timestamp": "2026-07-01T00:00:00Z",
                    "source": "WORKBENCH",
                    "requestSnapshot": {
                      "method": "GET",
                      "urlTemplate": "https://example.test/"
                    }
                  }]
                }
                """).historyEntries.get(0);

        assertThat(entry.preflightStatus).isEqualTo(ExecutionPreflightStatus.READY.name());
        assertThat(entry.requestSnapshot).isNotNull();
        assertThat(entry.requestSnapshot.hasRawRequestSent()).isFalse();
    }

    @Test
    void historyCopyPreservesPolicyOverrideMetadata() {
        HistoryEntry entry = HistoryEntry.fromWorkbenchExecution(collection(), request(), environment(), blockedExec(), 1, 1, List.of());
        entry.policyOverridesApplied = new java.util.ArrayList<>(List.of("Target change override"));

        HistoryEntry copy = HistoryEntry.copyOf(entry);

        assertThat(copy.policyOverridesApplied).containsExactly("Target change override");
        assertThat(copy.result).isEqualTo(HistoryResult.BLOCKED);
    }

    @Test
    void historyResultDisplaysBlockedAndTimeout() {
        assertThat(HistoryResult.BLOCKED.displayName()).isEqualTo("Blocked Before Send");
        assertThat(HistoryResult.TIMEOUT.displayName()).isEqualTo("Response Timeout");
    }

    private static ExecutionResult blockedExec() {
        ExecutionResult exec = new ExecutionResult();
        exec.requestSent = false;
        exec.preflightStatus = ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR;
        exec.preflightMessage = "Request not sent — pre-request script failed.";
        exec.success = false;
        exec.errorMessage = exec.preflightMessage;
        return exec;
    }

    private static ApiCollection collection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        return collection;
    }

    private static ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test/";
        return request;
    }

    private static EnvironmentProfile environment() {
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Env";
        return environment;
    }
}
