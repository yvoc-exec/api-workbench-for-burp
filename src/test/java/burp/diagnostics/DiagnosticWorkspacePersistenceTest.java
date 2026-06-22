package burp.diagnostics;

import burp.models.ApiCollection;
import burp.models.WorkspaceState;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticWorkspacePersistenceTest {

    @Test
    void diagnosticsCaptureSettingRoundTripsThroughWorkspaceJson() {
        WorkspaceState state = new WorkspaceState();
        state.collections.add(new ApiCollection());
        state.diagnosticsCaptureEnabled = true;

        WorkspaceState parsed = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));

        assertThat(parsed.diagnosticsCaptureEnabled).isTrue();

        state.diagnosticsCaptureEnabled = false;
        parsed = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));

        assertThat(parsed.diagnosticsCaptureEnabled).isFalse();
    }
}
