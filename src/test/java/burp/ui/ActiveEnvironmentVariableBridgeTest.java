package burp.ui;

import burp.UniversalImporter;
import burp.models.EnvironmentProfile;
import burp.testsupport.UiWorkflowFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveEnvironmentVariableBridgeTest {

    @Test
    void persistedMutationUpdatesAuthoritativeProfileAndClearsRuntimeOverride() {
        UniversalImporter importer = UiWorkflowFixtures.newImporter(
                UiWorkflowFixtures.mockUiApi(null));
        ImporterPanel panel = importer.getUI();

        EnvironmentProfile active = environment(
                "env-dev",
                "Dev",
                "base_url",
                "https://dev.example.test");
        active.runtimeVariables.put("base_url", "https://runtime.example.test");
        EnvironmentProfile inactive = environment(
                "env-qa",
                "QA",
                "base_url",
                "https://qa.example.test");

        panel.replaceEnvironmentProfiles(List.of(active, inactive));
        panel.setActiveEnvironmentId(active.id);

        RequestEditorPanel.VariableActionBridge bridge =
                ActiveEnvironmentVariableBridge.createForTests(panel);
        boolean applied = bridge.updateActiveEnvironment(
                "base_url",
                "https://edited.example.test",
                false,
                true);

        assertThat(applied).isTrue();
        assertThat(active.variables)
                .containsEntry("base_url", "https://edited.example.test");
        assertThat(active.runtimeVariables)
                .doesNotContainKey("base_url");
        assertThat(inactive.variables)
                .containsEntry("base_url", "https://qa.example.test");
    }

    @Test
    void runtimeMutationDoesNotPollutePersistedVariables() {
        UniversalImporter importer = UiWorkflowFixtures.newImporter(
                UiWorkflowFixtures.mockUiApi(null));
        ImporterPanel panel = importer.getUI();

        EnvironmentProfile active = environment("env-dev", "Dev");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);

        RequestEditorPanel.VariableActionBridge bridge =
                ActiveEnvironmentVariableBridge.createForTests(panel);
        boolean applied = bridge.updateActiveEnvironment(
                "session_token",
                "runtime-token",
                true,
                false);

        assertThat(applied).isTrue();
        assertThat(active.variables).doesNotContainKey("session_token");
        assertThat(active.runtimeVariables)
                .containsEntry("session_token", "runtime-token");
    }

    private static EnvironmentProfile environment(
            String id,
            String name,
            String... pairs) {
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.id = id;
        profile.name = name;
        profile.ensureDefaults();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            profile.variables.put(pairs[index], pairs[index + 1]);
        }
        return profile;
    }
}
