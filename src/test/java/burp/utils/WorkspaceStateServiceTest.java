package burp.utils;

import burp.models.ApiCollection;
import burp.models.WorkspaceState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceStateServiceTest {

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
}
