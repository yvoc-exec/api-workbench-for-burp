package burp.ui;

import burp.models.ApiRequest;
import burp.models.ApiCollection;
import burp.utils.AuthInheritanceResolver;
import org.junit.jupiter.api.Test;

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
}
