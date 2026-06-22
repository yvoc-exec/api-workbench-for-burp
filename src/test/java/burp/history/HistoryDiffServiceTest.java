package burp.history;

import burp.scripts.ScriptFlowControl;
import burp.scripts.ScriptLogEntry;
import burp.scripts.ScriptVariableMutation;
import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryDiffServiceTest {

    @Test
    void diffIncludesRawRequestAndScriptSections() {
        HistoryEntry left = entry("Left", "GET /left HTTP/1.1\r\nAuthorization: Bearer left\r\n\r\nbody-left");
        left.scriptFlowControl = ScriptFlowControl.SKIP_REQUEST;
        left.scriptFlowMessage = "Skipped by script";
        left.scriptLogs = List.of(new ScriptLogEntry("info", "left log", "script-left", "Left Script"));
        left.scriptWarnings = List.of("left warning");
        left.scriptErrors = List.of("left error");
        ScriptVariableMutation leftMutation = new ScriptVariableMutation("token", "old", "new", "environment", true);
        leftMutation.sourceScriptId = "script-left";
        leftMutation.sourceScriptName = "Left Script";
        left.scriptVariableMutations = List.of(leftMutation);

        HistoryEntry right = entry("Right", "GET /right HTTP/1.1\r\nAuthorization: Bearer right\r\n\r\nbody-right");
        right.scriptFlowControl = ScriptFlowControl.STOP_RUN;
        right.scriptFlowMessage = "Stopped by script";
        right.scriptLogs = List.of(new ScriptLogEntry("error", "right log", "script-right", "Right Script"));
        right.scriptWarnings = List.of("right warning");
        right.scriptErrors = List.of("right error");
        ScriptVariableMutation rightMutation = new ScriptVariableMutation("token", "old2", "new2", "environment", true);
        rightMutation.sourceScriptId = "script-right";
        rightMutation.sourceScriptName = "Right Script";
        right.scriptVariableMutations = List.of(rightMutation);

        String diff = new HistoryDiffService().diff(left, right);

        assertThat(diff).contains("Raw Sent Request");
        assertThat(diff).contains("Script Logs");
        assertThat(diff).contains("Script Warnings");
        assertThat(diff).contains("Script Errors");
        assertThat(diff).contains("Script Mutations");
        assertThat(diff).contains("Flow Control");
        assertThat(diff).contains("Authorization: Bearer left");
        assertThat(diff).contains("Authorization: Bearer right");
    }

    @Test
    void diffCoversMetadataRequestResponseVariablesAndPrettyPrintedJson() {
        HistoryEntry left = HistoryTestFixtures.sampleWorkbenchEntry();
        left.id = "left-id";
        left.collectionName = "Collection-A";
        left.folderPath = "Auth";
        left.environmentName = "Dev";
        left.finalResolvedUrl = "https://api.example.test/left";
        left.requestSnapshot.rawRequestSentText = "GET /left HTTP/1.1\r\n\r\n";
        left.requestSnapshot.rawRequestSent = left.requestSnapshot.rawRequestSentText.getBytes(StandardCharsets.UTF_8);
        left.requestSnapshot.bodyAsAuthored = "{\"name\":\"left\"}".getBytes(StandardCharsets.UTF_8);
        left.responseSnapshot.body = "{\"left\":true}".getBytes(StandardCharsets.UTF_8);
        left.unresolvedVariables = List.of("left_var");
        left.assertions = List.of(new HistoryAssertionResult("status", true, "200", "200"));
        left.extractions = List.of(new HistoryExtractionResult("session", "left-token", "body", "saved"));
        left.scriptLogs = List.of(new ScriptLogEntry("info", "left log", "script-left", "Left Script"));
        left.scriptWarnings = List.of("left warning");
        left.scriptErrors = List.of("left error");
        ScriptVariableMutation mutation = new ScriptVariableMutation("token", "old", "new", "environment", true);
        mutation.sourceScriptId = "script-left";
        mutation.sourceScriptName = "Left Script";
        left.scriptVariableMutations = List.of(mutation);

        HistoryEntry right = HistoryTestFixtures.sampleRunnerEntry();
        right.id = "right-id";
        right.collectionName = "Collection-B";
        right.folderPath = "Users";
        right.environmentName = "QA";
        right.finalResolvedUrl = "https://api.example.test/right";
        right.requestSnapshot.rawRequestSentText = "POST /right HTTP/1.1\r\n\r\n";
        right.requestSnapshot.rawRequestSent = right.requestSnapshot.rawRequestSentText.getBytes(StandardCharsets.UTF_8);
        right.requestSnapshot.bodyAsAuthored = "{\"name\":\"right\"}".getBytes(StandardCharsets.UTF_8);
        right.responseSnapshot.body = "{\"right\":false}".getBytes(StandardCharsets.UTF_8);
        right.unresolvedVariables = List.of("right_var");
        right.assertions = List.of(new HistoryAssertionResult("status", false, "200", "500"));
        right.extractions = List.of(new HistoryExtractionResult("session", "right-token", "body", "saved"));
        right.scriptLogs = List.of(new ScriptLogEntry("error", "right log", "script-right", "Right Script"));
        right.scriptWarnings = List.of("right warning");
        right.scriptErrors = List.of("right error");
        ScriptVariableMutation rightMutation = new ScriptVariableMutation("token", "old2", "new2", "environment", true);
        rightMutation.sourceScriptId = "script-right";
        rightMutation.sourceScriptName = "Right Script";
        right.scriptVariableMutations = List.of(rightMutation);

        String diff = new HistoryDiffService().diff(left, right);

        assertThat(diff).contains("=== Metadata ===");
        assertThat(diff).contains("History ID: left-id  |  right-id");
        assertThat(diff).contains("Collection: Collection-A  |  Collection-B");
        assertThat(diff).contains("Environment: Dev  |  QA");
        assertThat(diff).contains("=== Request ===");
        assertThat(diff).contains("Raw Sent Request:");
        assertThat(diff).contains("GET /left HTTP/1.1");
        assertThat(diff).contains("POST /right HTTP/1.1");
        assertThat(diff).contains("=== Response ===");
        assertThat(diff).contains("\"left\": true");
        assertThat(diff).contains("\"right\": false");
        assertThat(diff).contains("=== Variables / Assertions ===");
        assertThat(diff).contains("Unresolved Variables:");
        assertThat(diff).contains("Assertions:");
        assertThat(diff).contains("Extractions:");
        assertThat(diff).contains("Script Logs:");
        assertThat(diff).contains("Script Warnings:");
        assertThat(diff).contains("Script Errors:");
        assertThat(diff).contains("Script Mutations:");
    }

    @Test
    void diffHandlesNullSidesAndEqualBlocks() {
        HistoryEntry entry = HistoryTestFixtures.sampleDiffEntry();

        String diff = new HistoryDiffService().diff(null, entry);

        assertThat(diff).contains("=== Metadata ===");
        assertThat(diff).contains("History ID:").contains("history-diff");
        assertThat(diff).contains("=== Request ===");
        assertThat(diff).contains("(same)");
        assertThat(new HistoryDiffService().different(entry, HistoryEntry.copyOf(entry))).isFalse();
        assertThat(new HistoryDiffService().different(null, entry)).isTrue();
    }

    private static HistoryEntry entry(String name, String rawRequest) {
        HistoryEntry entry = new HistoryEntry();
        entry.requestName = name;
        entry.requestSnapshot = new HistoryRequestSnapshot();
        entry.requestSnapshot.rawRequestSentText = rawRequest;
        entry.requestSnapshot.rawRequestSent = rawRequest.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        entry.requestSnapshot.method = "GET";
        entry.requestSnapshot.urlTemplate = "https://example.test/" + name.toLowerCase();
        entry.requestSnapshot.authoredRequest = new burp.models.ApiRequest();
        return entry;
    }
}
