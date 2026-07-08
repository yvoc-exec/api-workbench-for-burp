package burp.ui;

import burp.scripts.ScriptPhase;
import burp.scripts.capabilities.ScriptRiskLevel;
import burp.scripts.capabilities.ScriptTrustReviewItem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptTrustReviewDialogTest {
    @Test
    void displaysFriendlyPhaseRiskLabelsAndFullLocationTooltip() {
        ScriptTrustReviewItem item = new ScriptTrustReviewItem();
        item.collectionName = "Imported API";
        item.folderPath = "Admin/Auth";
        item.requestName = "Login";
        item.phase = ScriptPhase.POST_RESPONSE;
        item.capabilityReport.riskLevel = ScriptRiskLevel.CRITICAL;

        assertThat(ScriptTrustReviewDialog.friendlyPhaseLabel(ScriptPhase.PRE_REQUEST)).isEqualTo("Pre-request");
        assertThat(ScriptTrustReviewDialog.friendlyPhaseLabel(ScriptPhase.POST_RESPONSE)).isEqualTo("Post-response");
        assertThat(ScriptTrustReviewDialog.friendlyPhaseLabel(ScriptPhase.TEST)).isEqualTo("Test");
        assertThat(ScriptTrustReviewDialog.friendlyRiskLabel(ScriptRiskLevel.LOW)).isEqualTo("Low");
        assertThat(ScriptTrustReviewDialog.friendlyRiskLabel(ScriptRiskLevel.CRITICAL)).isEqualTo("Critical");

        assertThat(ScriptTrustReviewDialog.reviewItemTooltip(item))
                .contains("Imported API")
                .contains("Admin/Auth/Login")
                .contains("Post-response")
                .contains("Critical");
    }
}
