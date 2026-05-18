package burp.utils;

import burp.models.ApiCollection;
import burp.models.WorkspacePersistenceOptions;
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
        collection.runtimeVars.put("baseUrl", "https://api.example.test");

        service.save(WorkspaceState.fromCollections(List.of(collection), WorkspacePersistenceOptions.defaults()));
        WorkspaceState loaded = service.load();

        assertThat(loaded.collections).hasSize(1);
        assertThat(loaded.collections.get(0).name).isEqualTo("Demo");
        assertThat(loaded.collections.get(0).runtimeVars).containsEntry("baseUrl", "https://api.example.test");
    }
}
