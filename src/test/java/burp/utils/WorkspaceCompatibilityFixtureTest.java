package burp.utils;

import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.history.HistoryRetentionPolicy;
import burp.testsupport.TestResourceLoader;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class WorkspaceCompatibilityFixtureTest {

    @Test
    void currentWorkspaceFixtureRestoresCollectionsEnvironmentsHistoryAndUIState() {
        WorkspaceState state = WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/workspace/current-workspace.json"));

        assertThat(state.version).isEqualTo(2);
        assertThat(state.activeEnvironmentId).isEqualTo("env-dev");
        assertThat(state.collections).hasSize(1);
        assertThat(state.collections.get(0).name).isEqualTo("APIM");
        assertThat(state.collections.get(0).folderPaths).containsExactly("Auth", "Auth/OAuth");
        ApiRequest request = state.collections.get(0).requests.get(0);
        assertThat(request.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(request.suppressedAutoHeaders).containsExactly("authorization");
        assertThat(request.headers).extracting(header -> header.key)
                .containsExactly("Content-Type");
        assertThat(state.collections.get(0).runtimeVars).containsEntry("runtime_only", "value");
        assertThat(state.collections.get(0).runtimeOAuth2).containsEntry("oauth2_access_token", "token");
        assertThat(state.environments).hasSize(1);
        assertThat(state.environments.get(0).name).isEqualTo("Dev");
        assertThat(state.environments.get(0).oauth2.config).containsEntry("accessTokenUrl", "https://auth.example.test/token");
        assertThat(state.environments.get(0).oauth2.outputBindings).containsEntry("accessToken", "oauth2_access_token");
        assertThat(state.historyRetentionPolicy).isNotNull();
        assertThat(state.historyRetentionPolicy.maxEntries).isEqualTo(1000);
        assertThat(state.historyRetentionPolicy.maxTotalStoredBytes).isEqualTo(100L * 1024L * 1024L);
        assertThat(state.historyEntries).hasSize(2);
        assertThat(state.diagnosticsCaptureEnabled).isTrue();
        assertThat(state.checkedRequestKeys).containsExactly("APIM\u001FAuth/OAuth\u001FLogin\u001FPOST\u001F1");
        assertThat(state.expandedTreePathKeys).containsExactly("APIM\u001FAuth");
    }

    @Test
    void legacyWorkspaceFixtureDefaultsMissingStateSafely() {
        WorkspaceState state = WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/workspace/legacy-workspace.json"));

        assertThat(state.version).isEqualTo(2);
        assertThat(state.collections).hasSize(1);
        assertThat(state.environments).isEmpty();
        assertThat(state.activeEnvironmentId).isNull();
        assertThat(state.historyRetentionPolicy.maxEntries).isEqualTo(HistoryRetentionPolicy.DEFAULT_MAX_ENTRIES);
        assertThat(state.historyRetentionPolicy.maxTotalStoredBytes).isEqualTo(HistoryRetentionPolicy.DEFAULT_MAX_TOTAL_STORED_BYTES);
        assertThat(state.collections.get(0).requests.get(0).buildMode).isEqualTo(ApiRequest.BuildMode.AUTO_COMPATIBLE);
        assertThat(state.collections.get(0).requests.get(0).suppressedAutoHeaders).isEmpty();
    }

    @Test
    void duplicateEnvironmentFixtureDeduplicatesRestoredIdsAndPreservesActiveEnvironment() {
        WorkspaceState state = WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/workspace/duplicate-envs.json"));

        assertThat(state.environments).hasSize(2);
        assertThat(state.environments.get(0).id).isEqualTo("env-dup");
        assertThat(state.environments.get(1).id).isNotEqualTo("env-dup");
        assertThat(state.activeEnvironmentId).isEqualTo("env-dup");
    }

    @Test
    void duplicateEnvironmentIdsNormalizeWithinBoundedTime() {
        assertTimeoutPreemptively(Duration.ofMillis(250), () -> {
            WorkspaceState state = new WorkspaceState();

            EnvironmentProfile first = new EnvironmentProfile();
            first.id = "env-dup";
            first.name = "First";

            EnvironmentProfile second = new EnvironmentProfile();
            second.id = "env-dup";
            second.name = "Second";

            state.environments.add(first);
            state.environments.add(second);
            state.activeEnvironmentId = "env-dup";

            WorkspaceState parsed = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));

            assertThat(parsed.environments).hasSize(2);
            assertThat(parsed.environments.get(0).id).isEqualTo("env-dup");
            assertThat(parsed.environments.get(1).id).isNotEqualTo("env-dup");
            assertThat(parsed.activeEnvironmentId).isEqualTo("env-dup");
        });
    }

    @Test
    void futureWorkspaceFixtureIgnoresUnknownFieldsAndStillLoadsRequests() {
        WorkspaceState state = WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/workspace/future-workspace.json"));

        assertThat(state.collections).hasSize(1);
        assertThat(state.collections.get(0).name).isEqualTo("Future");
        assertThat(state.collections.get(0).requests.get(0).method).isEqualTo("DELETE");
        assertThat(state.historyEntries).hasSize(1);
        assertThat(state.historyEntries.get(0).requestSnapshot.urlTemplate).isEqualTo("https://api.example.test/future");
    }

    @Test
    void corruptAndTruncatedWorkspaceJsonFailFast() {
        assertThatThrownBy(() -> WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/workspace/corrupt-workspace.json")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/workspace/truncated-workspace.json")))
                .isInstanceOf(RuntimeException.class);
    }
}
