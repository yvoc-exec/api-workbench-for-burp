package burp.utils;

import burp.models.ApiCollection;
import burp.models.EnvironmentProfile;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentRuntimeMutationIntegrationTest {

    @Test
    void runtimeVariableDeltaAppliesToActiveEnvironmentOnly() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        EnvironmentProfile active = HistoryTestFixtures.sampleEnvironment();
        bundle.panel.replaceEnvironmentProfiles(List.of(active));
        bundle.panel.setActiveEnvironmentId(active.id);

        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        Map<String, String> changed = new LinkedHashMap<>(Map.of("runtime_only", "updated"));
        ImporterPanelTestSupport.invokeVoid(
                bundle.panel,
                "applyRuntimeVariableDeltaToActiveEnvironment",
                new Class<?>[]{ApiCollection.class, Map.class, Set.class},
                collection,
                changed,
                Set.of());
        ImporterPanelTestSupport.awaitEdt();

        assertThat(active.variables).containsEntry("runtime_only", "updated");
        assertThat(collection.runtimeVars).doesNotContainEntry("runtime_only", "updated");
        assertThat(bundle.panel.getWorkspaceStateSnapshot().environments.get(0).variables)
                .containsEntry("runtime_only", "updated");
    }

    @Test
    void runtimeVariableDeltaFallsBackToCollectionWhenNoActiveEnvironmentExists() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        collection.runtimeVars.clear();
        bundle.panel.restoreWorkspaceCollections(List.of(collection));

        Map<String, String> changed = Map.of("fallback_only", "value");
        ImporterPanelTestSupport.invokeVoid(
                bundle.panel,
                "applyRuntimeVariableDeltaToActiveEnvironment",
                new Class<?>[]{ApiCollection.class, Map.class, Set.class},
                collection,
                changed,
                Set.of("missing"));

        assertThat(collection.runtimeVars).containsEntry("fallback_only", "value");
        assertThat(bundle.panel.getWorkspaceStateSnapshot().collections.get(0).runtimeVars)
                .containsEntry("fallback_only", "value");
    }

    @Test
    void oauth2RuntimeTokenOutputsAreClearedFromActiveEnvironmentVariables() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        EnvironmentProfile active = HistoryTestFixtures.sampleEnvironment();
        active.variables.put("oauth2_access_token", "access");
        active.variables.put("oauth2_refresh_token", "refresh");
        active.variables.put("oauth2_token_type", "Bearer");
        active.variables.put("oauth2_expires_in", "3600");
        bundle.panel.replaceEnvironmentProfiles(List.of(active));
        bundle.panel.setActiveEnvironmentId(active.id);

        ImporterPanelTestSupport.invokeVoid(bundle.panel, "clearActiveEnvironmentOAuth2TokenOutputs", new Class<?>[]{});
        ImporterPanelTestSupport.awaitEdt();

        assertThat(active.variables).doesNotContainKeys(
                "oauth2_access_token",
                "oauth2_refresh_token",
                "oauth2_token_type",
                "oauth2_expires_in");
        assertThat(bundle.panel.getWorkspaceStateSnapshot().environments.get(0).variables)
                .doesNotContainKeys(
                        "oauth2_access_token",
                        "oauth2_refresh_token",
                        "oauth2_token_type",
                        "oauth2_expires_in");
    }
}
