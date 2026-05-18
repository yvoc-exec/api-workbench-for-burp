package burp.ui;

import burp.auth.TokenStore;
import burp.models.ApiCollection;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelOAuth2AcquireTest {

    @Test
    void buildOAuth2RuntimeSnapshotIncludesAcquiredTokenStateAndPreservesPanelVars() {
        TokenStore.TokenEntry entry = new TokenStore.TokenEntry();
        entry.accessToken = "access-123";
        entry.refreshToken = "refresh-456";
        entry.tokenType = "Bearer";
        entry.scope = "read write";
        entry.expiresAt = System.currentTimeMillis() + 10_000L;

        Map<String, String> panelVars = new LinkedHashMap<>();
        panelVars.put("oauth2_client_id", "client-1");
        panelVars.put("oauth2_scope", "panel-scope");
        panelVars.put("custom", "keep");

        Map<String, String> snapshot = ImporterPanel.buildOAuth2RuntimeSnapshot(entry, panelVars);

        assertThat(snapshot)
                .containsEntry("oauth2_client_id", "client-1")
                .containsEntry("custom", "keep")
                .containsEntry("oauth2_access_token", "access-123")
                .containsEntry("oauth2_refresh_token", "refresh-456")
                .containsEntry("oauth2_token_type", "Bearer")
                .containsEntry("oauth2_scope", "read write");
        assertThat(Integer.parseInt(snapshot.get("oauth2_expires_in"))).isBetween(0, 10);
    }

    @Test
    void applyAcquiredOAuth2RuntimeWritesOnlyToCapturedCollection() {
        ApiCollection captured = new ApiCollection();
        captured.name = "Captured";
        captured.runtimeOAuth2.put("oauth2_stale", "remove");

        ApiCollection other = new ApiCollection();
        other.name = "Other";
        other.runtimeOAuth2.put("oauth2_keep", "stay");

        TokenStore.TokenEntry entry = new TokenStore.TokenEntry();
        entry.accessToken = "access-123";
        entry.refreshToken = "refresh-456";
        entry.tokenType = "Bearer";
        entry.scope = "read write";
        entry.expiresAt = System.currentTimeMillis() + 10_000L;

        Map<String, String> panelVars = Map.of("oauth2_client_id", "client-1");

        ImporterPanel.applyAcquiredOAuth2Runtime(captured, entry, panelVars);

        assertThat(captured.runtimeOAuth2)
                .containsEntry("oauth2_client_id", "client-1")
                .containsEntry("oauth2_access_token", "access-123")
                .containsEntry("oauth2_refresh_token", "refresh-456")
                .containsEntry("oauth2_token_type", "Bearer")
                .containsEntry("oauth2_scope", "read write")
                .doesNotContainKey("oauth2_stale");
        assertThat(other.runtimeOAuth2).containsEntry("oauth2_keep", "stay");
    }
}
