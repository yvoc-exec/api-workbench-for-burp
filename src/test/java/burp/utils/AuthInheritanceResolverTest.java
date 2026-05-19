package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthInheritanceResolverTest {

    @Test
    void resolvesFolderAndRequestAuthOverridesInOrder() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Auth Demo";
        collection.auth = new ApiRequest.Auth();
        collection.auth.type = "bearer";
        collection.auth.properties.put("token", "{{collectionToken}}");

        collection.folderAuthModes.put("Admin", "explicit");
        ApiRequest.Auth folderAuth = new ApiRequest.Auth();
        folderAuth.type = "bearer";
        folderAuth.properties.put("token", "{{adminToken}}");
        collection.folderAuth.put("Admin", folderAuth);

        ApiRequest request = new ApiRequest();
        request.name = "Special";
        request.path = "Admin/Special";
        request.authOverrideMode = "inherit";

        AuthInheritanceResolver.resolveRequestAuth(collection, request);

        assertThat(request.auth).isNotNull();
        assertThat(request.auth.type).isEqualTo("bearer");
        assertThat(request.auth.properties).containsEntry("token", "{{adminToken}}");
        assertThat(request.authInherited).isTrue();
        assertThat(request.authSource).isEqualTo("folder: Admin");

        request.authOverrideMode = "explicit";
        request.explicitAuth = new ApiRequest.Auth();
        request.explicitAuth.type = "basic";
        request.explicitAuth.properties.put("username", "u");
        request.explicitAuth.properties.put("password", "p");

        AuthInheritanceResolver.resolveRequestAuth(collection, request);

        assertThat(request.auth.type).isEqualTo("basic");
        assertThat(request.auth.properties).containsEntry("username", "u");
        assertThat(request.authInherited).isFalse();
        assertThat(request.authExplicitlyDisabled).isFalse();
        assertThat(request.authSource).isEqualTo("request: Special");
    }

    @Test
    void explicitNoAuthStaysVisibleWhenCollectionAndFolderAuthExist() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Auth Demo";
        collection.auth = new ApiRequest.Auth();
        collection.auth.type = "bearer";
        collection.auth.properties.put("token", "{{collectionToken}}");

        collection.folderAuthModes.put("Public", "none");
        ApiRequest.Auth folderAuth = new ApiRequest.Auth();
        folderAuth.type = "none";
        collection.folderAuth.put("Public", folderAuth);

        ApiRequest request = new ApiRequest();
        request.name = "Child";
        request.path = "Public/Child";
        request.authOverrideMode = "inherit";

        AuthInheritanceResolver.resolveRequestAuth(collection, request);

        assertThat(request.auth).isNotNull();
        assertThat(request.auth.type).isEqualTo("none");
        assertThat(request.authInherited).isTrue();
        assertThat(request.authExplicitlyDisabled).isTrue();
        assertThat(request.authSource).isEqualTo("folder: Public");
    }
}
