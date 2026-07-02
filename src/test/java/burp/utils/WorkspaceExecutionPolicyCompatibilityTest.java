package burp.utils;

import burp.models.WorkspaceState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceExecutionPolicyCompatibilityTest {

    @Test
    void missingPolicyFieldsRestoreSafeDefaults() {
        WorkspaceState state = WorkspaceStateJson.fromJson("{}");

        assertThat(state.defaultResponseTimeoutMillis).isEqualTo(30_000);
        assertThat(state.runnerResponseTimeoutMillis).isEqualTo(30_000);
        assertThat(state.workbenchScriptFailureMode).isEqualTo(ExecutionPolicy.ScriptFailureMode.ABORT);
        assertThat(state.oauth2FailureMode).isEqualTo(ExecutionPolicy.OAuth2FailureMode.ABORT);
        assertThat(state.workbenchTargetChangeMode).isEqualTo(ExecutionPolicy.TargetChangeMode.REQUIRE_CONFIRMATION);
        assertThat(state.workbenchUnresolvedVariableMode).isEqualTo(ExecutionPolicy.UnresolvedVariableMode.REQUIRE_CONFIRMATION);
        assertThat(state.runnerTargetChangeMode).isEqualTo(ExecutionPolicy.TargetChangeMode.ABORT);
    }

    @Test
    void policyFieldsRoundTrip() {
        WorkspaceState state = new WorkspaceState();
        state.defaultResponseTimeoutMillis = 12_000;
        state.runnerResponseTimeoutMillis = 15_000;
        state.workbenchScriptFailureMode = ExecutionPolicy.ScriptFailureMode.CONTINUE;
        state.oauth2FailureMode = ExecutionPolicy.OAuth2FailureMode.SEND_WITHOUT_TOKEN;
        state.workbenchTargetChangeMode = ExecutionPolicy.TargetChangeMode.ALLOW;
        state.workbenchUnresolvedVariableMode = ExecutionPolicy.UnresolvedVariableMode.ALLOW_WITH_WARNING;
        state.runnerTargetChangeMode = ExecutionPolicy.TargetChangeMode.ALLOW;

        WorkspaceState restored = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));
        assertThat(restored.defaultResponseTimeoutMillis).isEqualTo(12_000);
        assertThat(restored.runnerResponseTimeoutMillis).isEqualTo(15_000);
        assertThat(restored.workbenchScriptFailureMode).isEqualTo(ExecutionPolicy.ScriptFailureMode.CONTINUE);
        assertThat(restored.oauth2FailureMode).isEqualTo(ExecutionPolicy.OAuth2FailureMode.SEND_WITHOUT_TOKEN);
        assertThat(restored.workbenchTargetChangeMode).isEqualTo(ExecutionPolicy.TargetChangeMode.ALLOW);
        assertThat(restored.workbenchUnresolvedVariableMode).isEqualTo(ExecutionPolicy.UnresolvedVariableMode.ALLOW_WITH_WARNING);
        assertThat(restored.runnerTargetChangeMode).isEqualTo(ExecutionPolicy.TargetChangeMode.ALLOW);
    }

    @Test
    void timeoutValuesClampOnRestore() {
        WorkspaceState restored = WorkspaceStateJson.fromJson("""
                {
                  "defaultResponseTimeoutMillis": 1,
                  "runnerResponseTimeoutMillis": 999999,
                  "runnerTargetChangeMode": "REQUIRE_CONFIRMATION"
                }
                """);

        assertThat(restored.defaultResponseTimeoutMillis).isEqualTo(1_000);
        assertThat(restored.runnerResponseTimeoutMillis).isEqualTo(300_000);
        assertThat(restored.runnerTargetChangeMode).isEqualTo(ExecutionPolicy.TargetChangeMode.ABORT);
    }

    @Test
    void runnerRequireConfirmationRestoresAsAbort() {
        WorkspaceState restored = WorkspaceStateJson.fromJson("""
                {
                  "runnerTargetChangeMode": "REQUIRE_CONFIRMATION"
                }
                """);

        assertThat(restored.runnerTargetChangeMode).isEqualTo(ExecutionPolicy.TargetChangeMode.ABORT);
    }

    @Test
    void copyOfPreservesPolicyFields() {
        WorkspaceState state = new WorkspaceState();
        state.defaultResponseTimeoutMillis = 8_000;
        state.runnerResponseTimeoutMillis = 9_000;
        state.workbenchScriptFailureMode = ExecutionPolicy.ScriptFailureMode.CONTINUE;
        state.oauth2FailureMode = ExecutionPolicy.OAuth2FailureMode.USE_STALE_TOKEN;
        state.workbenchTargetChangeMode = ExecutionPolicy.TargetChangeMode.ALLOW;
        state.workbenchUnresolvedVariableMode = ExecutionPolicy.UnresolvedVariableMode.ALLOW_WITH_WARNING;
        state.runnerTargetChangeMode = ExecutionPolicy.TargetChangeMode.ALLOW;

        WorkspaceState copy = WorkspaceState.copyOf(state);
        assertThat(copy.defaultResponseTimeoutMillis).isEqualTo(8_000);
        assertThat(copy.runnerResponseTimeoutMillis).isEqualTo(9_000);
        assertThat(copy.workbenchScriptFailureMode).isEqualTo(ExecutionPolicy.ScriptFailureMode.CONTINUE);
        assertThat(copy.oauth2FailureMode).isEqualTo(ExecutionPolicy.OAuth2FailureMode.USE_STALE_TOKEN);
        assertThat(copy.workbenchTargetChangeMode).isEqualTo(ExecutionPolicy.TargetChangeMode.ALLOW);
        assertThat(copy.workbenchUnresolvedVariableMode).isEqualTo(ExecutionPolicy.UnresolvedVariableMode.ALLOW_WITH_WARNING);
        assertThat(copy.runnerTargetChangeMode).isEqualTo(ExecutionPolicy.TargetChangeMode.ALLOW);
    }
}
