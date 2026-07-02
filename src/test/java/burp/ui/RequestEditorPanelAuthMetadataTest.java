package burp.ui;

import burp.models.ApiRequest;
import burp.models.ApiCollection;
import burp.utils.AuthInheritanceResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class RequestEditorPanelAuthMetadataTest {

    @Test
    void applyAuthMetadataPreservesCollectionSourceWhenAuthTypeStaysTheSame() {
        ApiRequest currentRequest = new ApiRequest();
        currentRequest.name = "Get Me";
        currentRequest.auth = new ApiRequest.Auth();
        currentRequest.auth.type = "bearer";
        currentRequest.authInherited = true;
        currentRequest.authSource = "collection: Demo";

        ApiRequest rebuilt = new ApiRequest();
        rebuilt.name = "Get Me";
        rebuilt.auth = new ApiRequest.Auth();
        rebuilt.auth.type = "bearer";

        RequestEditorPanel.applyAuthMetadata(rebuilt, currentRequest, "bearer");

        assertThat(rebuilt.authInherited).isTrue();
        assertThat(rebuilt.authExplicitlyDisabled).isFalse();
        assertThat(rebuilt.authSource).isEqualTo("collection: Demo");
        assertThat(ImporterPanel.buildAuthMetaLine(rebuilt)).isEqualTo("Auth: bearer (collection: Demo)\n");
    }

    @Test
    void applyAuthMetadataPreservesMeaningfulNoAuthSourceWhenAuthTypeRemainsNone() {
        ApiRequest currentRequest = new ApiRequest();
        currentRequest.name = "Public";
        currentRequest.auth = new ApiRequest.Auth();
        currentRequest.auth.type = "none";
        currentRequest.authInherited = true;
        currentRequest.authExplicitlyDisabled = true;
        currentRequest.authSource = "folder: Public";

        ApiRequest rebuilt = new ApiRequest();
        rebuilt.name = "Public";

        RequestEditorPanel.applyAuthMetadata(rebuilt, currentRequest, "none");

        assertThat(rebuilt.auth).isNull();
        assertThat(rebuilt.authInherited).isTrue();
        assertThat(rebuilt.authExplicitlyDisabled).isTrue();
        assertThat(rebuilt.authSource).isEqualTo("folder: Public");
    }

    @Test
    void applyAuthMetadataPreservesInheritedAuthWhenSelectionStaysInherit() {
        ApiRequest currentRequest = new ApiRequest();
        currentRequest.name = "Get Me";
        currentRequest.auth = new ApiRequest.Auth();
        currentRequest.auth.type = "bearer";
        currentRequest.auth.properties.put("token", "{{accessToken}}");
        currentRequest.authInherited = true;
        currentRequest.authSource = "collection: Demo";

        ApiRequest rebuilt = new ApiRequest();
        rebuilt.name = "Get Me";
        rebuilt.auth = new ApiRequest.Auth();
        rebuilt.auth.type = "bearer";

        RequestEditorPanel.applyAuthMetadata(rebuilt, currentRequest, "inherit");

        assertThat(rebuilt.authOverrideMode).isEqualTo("inherit");
        assertThat(rebuilt.explicitAuth).isNull();
        assertThat(rebuilt.authInherited).isTrue();
        assertThat(rebuilt.authExplicitlyDisabled).isFalse();
        assertThat(rebuilt.authSource).isEqualTo("collection: Demo");
        assertThat(rebuilt.auth.type).isEqualTo("bearer");
    }

    @Test
    void applyEditedRequestToLiveRequestPersistsAuthOverrideMetadataAndRecomputesEffectiveAuth() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        collection.auth = new ApiRequest.Auth();
        collection.auth.type = "bearer";
        collection.auth.properties.put("token", "{{collectionToken}}");
        collection.runtimeVars.put("runtime_key", "runtime_value");

        ApiRequest liveRequest = new ApiRequest();
        liveRequest.name = "Get Me";
        liveRequest.path = "Get Me";
        liveRequest.sourceCollection = "Demo";
        liveRequest.authOverrideMode = "inherit";
        liveRequest.variables.add(new ApiRequest.Variable());
        liveRequest.variables.get(0).key = "request_var";
        liveRequest.variables.get(0).value = "request_value";
        collection.requests.add(liveRequest);
        AuthInheritanceResolver.recomputeCollectionAuth(collection);

        ApiRequest edited = new ApiRequest();
        edited.name = "Get Me";
        edited.path = "Get Me";
        edited.sourceCollection = "Demo";
        edited.method = "POST";
        edited.url = "https://api.example.test/me";
        edited.authOverrideMode = "explicit";
        edited.explicitAuth = new ApiRequest.Auth();
        edited.explicitAuth.type = "basic";
        edited.explicitAuth.properties.put("username", "u");
        edited.explicitAuth.properties.put("password", "p");
        edited.auth = AuthInheritanceResolver.copyAuth(edited.explicitAuth);
        edited.variables.add(new ApiRequest.Variable());
        edited.variables.get(0).key = "request_var";
        edited.variables.get(0).value = "request_value";

        ImporterPanel.applyEditedRequestToLiveRequest(collection, liveRequest, edited);

        assertThat(liveRequest.authOverrideMode).isEqualTo("explicit");
        assertThat(liveRequest.explicitAuth).isNotNull();
        assertThat(liveRequest.explicitAuth.type).isEqualTo("basic");
        assertThat(liveRequest.auth).isNotNull();
        assertThat(liveRequest.auth.type).isEqualTo("basic");
        assertThat(liveRequest.authSource).isEqualTo("request: Get Me");
        assertThat(liveRequest.variables).hasSize(1);
        assertThat(collection.runtimeVars).containsEntry("runtime_key", "runtime_value");
    }

    @Test
    void applyEditedRequestToLiveRequestCanReturnToInheritedCollectionAuth() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        collection.auth = new ApiRequest.Auth();
        collection.auth.type = "bearer";
        collection.auth.properties.put("token", "{{collectionToken}}");

        ApiRequest liveRequest = new ApiRequest();
        liveRequest.name = "Get Me";
        liveRequest.path = "Get Me";
        liveRequest.sourceCollection = "Demo";
        liveRequest.authOverrideMode = "explicit";
        liveRequest.explicitAuth = new ApiRequest.Auth();
        liveRequest.explicitAuth.type = "basic";
        liveRequest.explicitAuth.properties.put("username", "u");
        liveRequest.explicitAuth.properties.put("password", "p");
        liveRequest.auth = AuthInheritanceResolver.copyAuth(liveRequest.explicitAuth);
        collection.requests.add(liveRequest);
        AuthInheritanceResolver.recomputeCollectionAuth(collection);

        ApiRequest edited = new ApiRequest();
        edited.name = "Get Me";
        edited.path = "Get Me";
        edited.sourceCollection = "Demo";
        edited.method = "GET";
        edited.url = "https://api.example.test/me";
        edited.authOverrideMode = "inherit";
        edited.explicitAuth = null;

        ImporterPanel.applyEditedRequestToLiveRequest(collection, liveRequest, edited);

        assertThat(liveRequest.authOverrideMode).isEqualTo("inherit");
        assertThat(liveRequest.explicitAuth).isNull();
        assertThat(liveRequest.auth).isNotNull();
        assertThat(liveRequest.auth.type).isEqualTo("bearer");
        assertThat(liveRequest.auth.properties).containsEntry("token", "{{collectionToken}}");
        assertThat(liveRequest.authSource).isEqualTo("collection: Demo");
    }

    @Test
    void normalEditorApplyPreservesDescriptionAndRequestVariables() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        collection.auth = new ApiRequest.Auth();
        collection.auth.type = "bearer";
        collection.auth.properties.put("token", "{{collectionToken}}");
        collection.runtimeVars.put("runtime_key", "runtime_value");

        ApiRequest liveRequest = new ApiRequest();
        liveRequest.name = "Get Me";
        liveRequest.path = "Get Me";
        liveRequest.sourceCollection = "Demo";
        liveRequest.description = "Preserve me";
        liveRequest.authOverrideMode = "inherit";
        liveRequest.variables = new ArrayList<>();
        liveRequest.variables.add(variable("first", "one", "string", true));
        liveRequest.variables.add(variable("second", "two", "secret", false));
        collection.requests.add(liveRequest);
        AuthInheritanceResolver.recomputeCollectionAuth(collection);

        ApiRequest edited = new ApiRequest();
        edited.name = "Get Me";
        edited.path = "Get Me";
        edited.sourceCollection = "Demo";
        edited.method = "POST";
        edited.url = "https://api.example.test/me";
        edited.authOverrideMode = "explicit";
        edited.explicitAuth = new ApiRequest.Auth();
        edited.explicitAuth.type = "basic";
        edited.explicitAuth.properties.put("username", "u");
        edited.explicitAuth.properties.put("password", "p");
        edited.auth = AuthInheritanceResolver.copyAuth(edited.explicitAuth);
        edited.description = null;
        edited.variables = new ArrayList<>();

        ImporterPanel.applyEditedRequestToLiveRequest(collection, liveRequest, edited);

        assertThat(liveRequest.method).isEqualTo("POST");
        assertThat(liveRequest.url).isEqualTo("https://api.example.test/me");
        assertThat(liveRequest.authOverrideMode).isEqualTo("explicit");
        assertThat(liveRequest.explicitAuth).isNotNull();
        assertThat(liveRequest.explicitAuth.type).isEqualTo("basic");
        assertThat(liveRequest.auth).isNotNull();
        assertThat(liveRequest.auth.type).isEqualTo("basic");
        assertThat(liveRequest.authSource).isEqualTo("request: Get Me");
        assertThat(liveRequest.description).isEqualTo("Preserve me");
        assertThat(liveRequest.variables)
                .extracting(variable -> variable.key + "|" + variable.value + "|" + variable.type + "|" + variable.enabled)
                .containsExactly(
                        "first|one|string|true",
                        "second|two|secret|false");
    }

    @Test
    void historySnapshotApplyReplacesDescriptionAndRequestVariables() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";

        ApiRequest liveRequest = new ApiRequest();
        liveRequest.name = "Get Me";
        liveRequest.path = "Get Me";
        liveRequest.sourceCollection = "Demo";
        liveRequest.description = "Old description";
        liveRequest.variables = new ArrayList<>();
        liveRequest.variables.add(variable("oldFirst", "one", "string", true));
        liveRequest.variables.add(variable("oldSecond", "two", "secret", false));
        collection.requests.add(liveRequest);

        ApiRequest snapshotRequest = new ApiRequest();
        snapshotRequest.name = "Get Me";
        snapshotRequest.path = "Get Me";
        snapshotRequest.sourceCollection = "Demo";
        snapshotRequest.description = null;
        snapshotRequest.variables = new ArrayList<>();

        ImporterPanel.applyHistorySnapshotToLiveRequest(collection, liveRequest, snapshotRequest);

        assertThat(liveRequest.description).isNull();
        assertThat(liveRequest.variables).isNotNull().isEmpty();

        ApiRequest loadedSnapshot = new ApiRequest();
        loadedSnapshot.name = "Get Me";
        loadedSnapshot.path = "Get Me";
        loadedSnapshot.sourceCollection = "Demo";
        loadedSnapshot.description = "Loaded description";
        loadedSnapshot.variables = new ArrayList<>();
        loadedSnapshot.variables.add(variable("loadedToken", "xyz", "string", true));

        ImporterPanel.applyHistorySnapshotToLiveRequest(collection, liveRequest, loadedSnapshot);

        assertThat(liveRequest.description).isEqualTo("Loaded description");
        assertThat(liveRequest.variables)
                .extracting(variable -> variable.key + "|" + variable.value + "|" + variable.type + "|" + variable.enabled)
                .containsExactly("loadedToken|xyz|string|true");

        loadedSnapshot.variables.get(0).value = "mutated-after-load";
        assertThat(liveRequest.variables.get(0).value).isEqualTo("xyz");
    }

    private static ApiRequest.Variable variable(String key, String value, String type, boolean enabled) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        variable.type = type;
        variable.enabled = enabled;
        return variable;
    }
}
