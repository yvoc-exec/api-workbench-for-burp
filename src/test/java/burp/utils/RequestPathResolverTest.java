package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RequestPathResolverTest {

    @Test
    void collectionScopedResolverOnlyTreatsSingleSegmentPathAsFolderWhenEvidenceExists() {
        assertTimeoutPreemptively(Duration.ofMillis(250), () -> {
            ApiCollection collection = new ApiCollection();
            collection.name = "Demo";

            ApiRequest request = new ApiRequest();
            request.name = "Users";
            request.path = "Users";
            request.sourceCollection = collection.name;
            collection.requests.add(request);

            assertThat(RequestPathResolver.getRequestFolderPath(collection, request)).isEmpty();

            collection.folderAuth = new LinkedHashMap<>();
            collection.folderAuth.put("Users", new ApiRequest.Auth());

            assertThat(RequestPathResolver.getRequestFolderPath(collection, request)).isEqualTo("Users");
        });
    }

    @Test
    void collectionScopedResolverTreatsFolderVarsAndDescendantsAsFolderEvidence() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";

        ApiRequest rootRequest = new ApiRequest();
        rootRequest.name = "Users";
        rootRequest.path = "Users";
        rootRequest.sourceCollection = collection.name;
        collection.requests.add(rootRequest);

        collection.folderVars = new LinkedHashMap<>();
        collection.folderVars.put("Users", new LinkedHashMap<>());

        assertThat(RequestPathResolver.getRequestFolderPath(collection, rootRequest)).isEqualTo("Users");

        collection.folderVars.clear();
        ApiRequest childRequest = new ApiRequest();
        childRequest.name = "List Users";
        childRequest.path = "Users/List";
        childRequest.sourceCollection = collection.name;
        collection.requests.add(childRequest);

        assertThat(RequestPathResolver.getRequestFolderPath(collection, rootRequest)).isEqualTo("Users");
    }
}
