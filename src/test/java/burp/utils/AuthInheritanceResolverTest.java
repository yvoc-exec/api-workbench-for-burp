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

    @Test
    void explicitRequestAuthSurvivesCollectionAuthRecompute() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        ApiRequest.Auth collectionAuth = new ApiRequest.Auth();
        collectionAuth.type = "bearer";
        collectionAuth.properties.put("token", "{{collectionToken}}");
        collection.auth = collectionAuth;

        ApiRequest request = new ApiRequest();
        request.name = "Special";
        request.path = "Admin/Special";
        ApiRequest.Auth requestAuth = new ApiRequest.Auth();
        requestAuth.type = "basic";
        requestAuth.properties.put("username", "u");
        requestAuth.properties.put("password", "p");

        AuthInheritanceResolver.markRequestExplicitAuth(request, requestAuth);
        collection.requests.add(request);

        AuthInheritanceResolver.recomputeCollectionAuth(collection);

        assertThat(request.auth.type).isEqualTo("basic");
        assertThat(request.auth.properties).containsEntry("username", "u");
        assertThat(request.authOverrideMode).isEqualTo("explicit");
        assertThat(request.explicitAuth.type).isEqualTo("basic");
        assertThat(request.authInherited).isFalse();
        assertThat(request.authExplicitlyDisabled).isFalse();
        assertThat(request.authSource).isEqualTo("request: Special");
    }

    @Test
    void requestNoAuthStopsCollectionAndFolderInheritanceAfterRecompute() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        ApiRequest.Auth collectionAuth = new ApiRequest.Auth();
        collectionAuth.type = "bearer";
        collectionAuth.properties.put("token", "{{collectionToken}}");
        collection.auth = collectionAuth;

        ApiRequest.Auth folderAuth = new ApiRequest.Auth();
        folderAuth.type = "bearer";
        folderAuth.properties.put("token", "{{folderToken}}");
        collection.folderAuthModes.put("Admin", "explicit");
        collection.folderAuth.put("Admin", folderAuth);

        ApiRequest request = new ApiRequest();
        request.name = "Public";
        request.path = "Admin/Public";
        AuthInheritanceResolver.markRequestNoAuth(request);
        collection.requests.add(request);

        AuthInheritanceResolver.recomputeCollectionAuth(collection);

        assertThat(request.hasAuth()).isFalse();
        assertThat(request.auth.type).isEqualTo("none");
        assertThat(request.authOverrideMode).isEqualTo("none");
        assertThat(request.explicitAuth.type).isEqualTo("none");
        assertThat(request.authInherited).isFalse();
        assertThat(request.authExplicitlyDisabled).isTrue();
        assertThat(request.authSource).isEqualTo("request: Public");
    }

    @Test
    void getRequestFolderPathTreatsSlashInRequestNameAsLabel() {
        ApiRequest request = new ApiRequest();
        request.name = "GET /users";
        request.path = "Auth";

        assertThat(AuthInheritanceResolver.getRequestFolderPath(request)).isEqualTo("Auth");

        request.path = "Auth/GET /users";
        assertThat(AuthInheritanceResolver.getRequestFolderPath(request)).isEqualTo("Auth");

        request.path = "";
        assertThat(AuthInheritanceResolver.getRequestFolderPath(request)).isEqualTo("");
    }
}
