package burp.utils;

import burp.models.ApiCollection;
import burp.models.WorkspaceState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceStateServiceTest {

    @Test
    void saveSerializedEnforcesUtf8LimitAndSuppliedLengthWithoutReplacingPreviousValue() {
        Map<String, String> backing = new HashMap<>();
        backing.put("api_workbench_workspace_state_json", "previous");
        WorkspaceStateService service = new WorkspaceStateService(store(backing), 10L);
        String exact = "1234567890";

        WorkspaceSaveResult saved = service.saveSerialized(
                1L, exact, 10L, WorkspaceStateService.sha256(exact));
        WorkspaceSaveResult tooLarge = service.saveSerialized(
                2L, exact + "x", 11L, WorkspaceStateService.sha256(exact + "x"));
        WorkspaceSaveResult mismatch = service.saveSerialized(
                3L, exact, 9L, WorkspaceStateService.sha256(exact));

        assertThat(saved.status()).isEqualTo(WorkspaceSaveResult.Status.SAVED);
        assertThat(saved.persisted()).isTrue();
        assertThat(tooLarge.status()).isEqualTo(WorkspaceSaveResult.Status.REJECTED_TOO_LARGE);
        assertThat(mismatch.status()).isEqualTo(WorkspaceSaveResult.Status.FAILED);
        assertThat(backing.get("api_workbench_workspace_state_json")).isEqualTo(exact);
    }

    @Test
    void loadRejectsOversizedTextBeforeParsingWithoutIncludingContent() {
        Map<String, String> backing = new HashMap<>();
        backing.put("api_workbench_workspace_state_json", "secret-value");
        WorkspaceStateService service = new WorkspaceStateService(store(backing), 5L);

        assertThatThrownBy(service::loadJson)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("5 byte limit")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("secret-value"));
    }

    @Test
    void storageFailureReturnsMetadataOnlyFailureAndPreservesPreviousValue() {
        Map<String, String> backing = new HashMap<>();
        backing.put("api_workbench_workspace_state_json", "previous");
        WorkspaceStateService service = new WorkspaceStateService(new WorkspaceStateService.StringStore() {
            @Override
            public String get(String key) {
                return backing.get(key);
            }

            @Override
            public void set(String key, String value) {
                throw new IllegalStateException("secret storage detail");
            }
        }, 100L);

        WorkspaceSaveResult result = service.saveSerialized(
                4L, "replacement", 11L, WorkspaceStateService.sha256("replacement"));

        assertThat(result.status()).isEqualTo(WorkspaceSaveResult.Status.FAILED);
        assertThat(result.failureReason()).isEqualTo("Workspace persistence failed.");
        assertThat(backing.get("api_workbench_workspace_state_json")).isEqualTo("previous");
    }

    @Test
    void saveAndLoadRoundTripThroughExtensionData() {
        Map<String, String> backing = new HashMap<>();
        WorkspaceStateService service = new WorkspaceStateService(new WorkspaceStateService.StringStore() {
            @Override
            public String get(String key) {
                return backing.get(key);
            }

            @Override
            public void set(String key, String value) {
                backing.put(key, value);
            }
        });

        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        collection.runtimeVars.put("password", "runtime-password");
        collection.runtimeOAuth2.put("oauth2_access_token", "access");
        collection.runtimeOAuth2.put("oauth2_refresh_token", "refresh");
        collection.runtimeOAuth2.put("oauth2_client_secret", "client-secret");
        collection.runtimeOAuth2.put("oauth2_password", "oauth-password");
        collection.runtimeVars.put("baseUrl", "https://api.example.test");

        service.save(WorkspaceState.fromCollections(List.of(collection)));
        WorkspaceState loaded = service.load();

        assertThat(loaded.collections).hasSize(1);
        assertThat(loaded.collections.get(0).name).isEqualTo("Demo");
        assertThat(loaded.collections.get(0).runtimeVars).containsEntry("password", "runtime-password");
        assertThat(loaded.collections.get(0).runtimeVars).containsEntry("baseUrl", "https://api.example.test");
        assertThat(loaded.collections.get(0).runtimeOAuth2)
                .containsEntry("oauth2_access_token", "access")
                .containsEntry("oauth2_refresh_token", "refresh")
                .containsEntry("oauth2_client_secret", "client-secret")
                .containsEntry("oauth2_password", "oauth-password");
        String legacyKey = String.join("_",
                "api", "workbench", "workspace", "sensitive", "persistence", "opt", "in");
        assertThat(backing).containsKey("api_workbench_workspace_state_json");
        assertThat(backing).doesNotContainKey(legacyKey);
    }

    @Test
    void compatibilitySaveDoesNotMutateCallerOwnedWorkspace() {
        Map<String, String> backing = new HashMap<>();
        WorkspaceStateService service = new WorkspaceStateService(store(backing));
        WorkspaceState source = new WorkspaceState();
        source.version = 1;

        service.save(source);

        assertThat(source.version).isEqualTo(1);
        assertThat(WorkspaceStateJson.fromJson(backing.get("api_workbench_workspace_state_json")).version)
                .isEqualTo(WorkspaceState.CURRENT_VERSION);
    }

    @Test
    void saveJsonWritesRawPayloadDirectly() {
        Map<String, String> backing = new HashMap<>();
        WorkspaceStateService service = new WorkspaceStateService(new WorkspaceStateService.StringStore() {
            @Override
            public String get(String key) {
                return backing.get(key);
            }

            @Override
            public void set(String key, String value) {
                backing.put(key, value);
            }
        });

        String rawJson = "{\"version\":2,\"collections\":[]}";
        service.saveJson(rawJson);

        assertThat(backing).containsEntry("api_workbench_workspace_state_json", rawJson);
    }

    private static WorkspaceStateService.StringStore store(Map<String, String> backing) {
        return new WorkspaceStateService.StringStore() {
            @Override
            public String get(String key) {
                return backing.get(key);
            }

            @Override
            public void set(String key, String value) {
                backing.put(key, value);
            }
        };
    }
}
