package burp.ui;

import burp.utils.ExecutionPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelPolicyDisplayTest {
    @Test
    void executionPolicyLabelsAreFriendlyButEnumValuesRemainPersistent() {
        assertThat(ImporterPanel.friendlyExecutionPolicyLabel(ExecutionPolicy.TargetChangeMode.ALLOW))
                .isEqualTo("Allow");
        assertThat(ImporterPanel.friendlyExecutionPolicyLabel(ExecutionPolicy.TargetChangeMode.ABORT))
                .isEqualTo("Abort");
        assertThat(ImporterPanel.friendlyExecutionPolicyLabel(ExecutionPolicy.TargetChangeMode.REQUIRE_CONFIRMATION))
                .isEqualTo("Require confirmation");
        assertThat(ImporterPanel.friendlyExecutionPolicyLabel(ExecutionPolicy.UnresolvedVariableMode.ALLOW_WITH_WARNING))
                .isEqualTo("Allow with warning");
        assertThat(ImporterPanel.friendlyExecutionPolicyLabel(ExecutionPolicy.UnresolvedVariableMode.REQUIRE_CONFIRMATION))
                .isEqualTo("Require confirmation");
        assertThat(ImporterPanel.friendlyExecutionPolicyLabel(ExecutionPolicy.OAuth2FailureMode.USE_STALE_TOKEN))
                .isEqualTo("Use stale token");
        assertThat(ImporterPanel.friendlyExecutionPolicyLabel(ExecutionPolicy.OAuth2FailureMode.SEND_WITHOUT_TOKEN))
                .isEqualTo("Send without token");
        assertThat(ImporterPanel.friendlyExecutionPolicyLabel(ExecutionPolicy.ScriptFailureMode.CONTINUE))
                .isEqualTo("Continue");

        assertThat(ExecutionPolicy.TargetChangeMode.REQUIRE_CONFIRMATION.name())
                .isEqualTo("REQUIRE_CONFIRMATION");
        assertThat(ExecutionPolicy.UnresolvedVariableMode.REQUIRE_CONFIRMATION.name())
                .isEqualTo("REQUIRE_CONFIRMATION");
    }
}
