package burp.utils;

import burp.models.ApiCollection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeVariablesJsonTest {

    @Test
    void toJsonAndFromJsonRoundTripRuntimeVariables() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection Name";
        collection.runtimeVars.put("baseUrl", "https://api.example.com");
        collection.runtimeOAuth2.put("oauth2_client_id", "client-id");

        String json = RuntimeVariablesJson.toJson(collection);
        RuntimeVariablesJson.RuntimeVariableBundle bundle = RuntimeVariablesJson.fromJson(json);

        assertThat(json).contains("\"version\":1");
        assertThat(json).contains("\"collection\":\"Collection Name\"");
        assertThat(bundle.collectionName).isEqualTo("Collection Name");
        assertThat(bundle.version).isEqualTo(1);
        assertThat(bundle.runtimeVars).containsEntry("baseUrl", "https://api.example.com");
        assertThat(bundle.runtimeOAuth2).containsEntry("oauth2_client_id", "client-id");
    }

    @Test
    void fromJsonUsesEmptyMapsWhenFieldsAreMissing() {
        RuntimeVariablesJson.RuntimeVariableBundle bundle = RuntimeVariablesJson.fromJson("{\"version\":1,\"collection\":\"Collection Name\"}");

        assertThat(bundle.collectionName).isEqualTo("Collection Name");
        assertThat(bundle.version).isEqualTo(1);
        assertThat(bundle.runtimeVars).isEmpty();
        assertThat(bundle.runtimeOAuth2).isEmpty();
    }

    @Test
    void applyBundleMergesImportedMapsIntoCollection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection Name";
        collection.runtimeVars.put("existing", "keep");
        collection.runtimeOAuth2.put("oauth2_existing", "keep");

        RuntimeVariablesJson.RuntimeVariableBundle bundle = new RuntimeVariablesJson.RuntimeVariableBundle();
        bundle.collectionName = "Imported Name";
        bundle.runtimeVars.put("baseUrl", "https://api.example.com");
        bundle.runtimeOAuth2.put("oauth2_client_id", "client-id");

        RuntimeVariablesJson.applyToCollection(collection, bundle, false);

        assertThat(collection.runtimeVars)
                .containsEntry("existing", "keep")
                .containsEntry("baseUrl", "https://api.example.com");
        assertThat(collection.runtimeOAuth2)
                .containsEntry("oauth2_existing", "keep")
                .containsEntry("oauth2_client_id", "client-id");
    }

    @Test
    void applyBundleReplacesExistingRuntimeVariablesAndOauth2Variables() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection Name";
        collection.runtimeVars.put("existing", "keep");
        collection.runtimeVars.put("stale", "remove");
        collection.runtimeOAuth2.put("oauth2_existing", "keep");
        collection.runtimeOAuth2.put("oauth2_stale", "remove");

        RuntimeVariablesJson.RuntimeVariableBundle bundle = new RuntimeVariablesJson.RuntimeVariableBundle();
        bundle.runtimeVars.put("baseUrl", "https://api.example.com");
        bundle.runtimeOAuth2.put("oauth2_client_id", "client-id");

        RuntimeVariablesJson.applyToCollection(collection, bundle, true);

        assertThat(collection.runtimeVars)
                .doesNotContainKeys("existing", "stale")
                .containsEntry("baseUrl", "https://api.example.com");
        assertThat(collection.runtimeOAuth2)
                .doesNotContainKeys("oauth2_existing", "oauth2_stale")
                .containsEntry("oauth2_client_id", "client-id");
    }
}
