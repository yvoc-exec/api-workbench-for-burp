package burp.history;

import burp.scripts.ScriptFlowControl;
import burp.scripts.ScriptLogEntry;
import burp.scripts.ScriptVariableMutation;
import org.junit.jupiter.api.Test;

import java.util.List;

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
