package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.BearerTokenAliasCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2BearerAliasDetectorTest {

    @Test
    void detectsBearerAliasInAuthorizationHeaderAndIgnoresCanonicalOAuth2Token() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Orders";

        ApiRequest headerRequest = new ApiRequest();
        headerRequest.name = "Header Request";
        headerRequest.headers.add(new ApiRequest.Header("Authorization", "Bearer {{accessToken}}"));
        collection.requests.add(headerRequest);

        ApiRequest canonicalRequest = new ApiRequest();
        canonicalRequest.name = "Canonical Request";
        canonicalRequest.headers.add(new ApiRequest.Header("Authorization", "Bearer {{oauth2_access_token}}"));
        collection.requests.add(canonicalRequest);

        List<BearerTokenAliasCandidate> candidates = OAuth2BearerAliasDetector.detect(collection, "new-token");

        assertThat(candidates).hasSize(1);
        BearerTokenAliasCandidate candidate = candidates.get(0);
        assertThat(candidate.alias).isEqualTo("accessToken");
        assertThat(candidate.requestCount).isEqualTo(1);
        assertThat(candidate.defaultSelected).isTrue();
        assertThat(candidate.overwriteStatus).contains("missing");
    }

    @Test
    void detectsBearerAliasesFromAuthPropertiesAndCountsUniqueRequests() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Profile";

        ApiRequest first = new ApiRequest();
        first.name = "First";
        first.auth = new ApiRequest.Auth();
        first.auth.type = "bearer";
        first.auth.properties.put("token", "{{authToken}}");
        collection.requests.add(first);

        ApiRequest second = new ApiRequest();
        second.name = "Second";
        second.auth = new ApiRequest.Auth();
        second.auth.type = "oauth2";
        second.auth.properties.put("accessToken", "{{jwt}}");
        collection.requests.add(second);

        ApiRequest third = new ApiRequest();
        third.name = "Third";
        third.auth = new ApiRequest.Auth();
        third.auth.type = "bearer";
        third.auth.properties.put("value", "{{authToken}}");
        collection.requests.add(third);

        List<BearerTokenAliasCandidate> candidates = OAuth2BearerAliasDetector.detect(collection, "new-token");

        assertThat(candidates).extracting(c -> c.alias + ":" + c.requestCount)
                .containsExactlyInAnyOrder(
                        "authToken:2",
                        "jwt:1"
                );
    }

    @Test
    void marksExistingNonEmptyRuntimeVarAsOverwriteOnly() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Billing";
        collection.runtimeVars.put("apiToken", "old-token");

        ApiRequest request = new ApiRequest();
        request.name = "Use Token";
        request.headers.add(new ApiRequest.Header("Authorization", "Bearer {{apiToken}}"));
        collection.requests.add(request);

        List<BearerTokenAliasCandidate> candidates = OAuth2BearerAliasDetector.detect(collection, "new-token");

        assertThat(candidates).hasSize(1);
        BearerTokenAliasCandidate candidate = candidates.get(0);
        assertThat(candidate.defaultSelected).isFalse();
        assertThat(candidate.currentValue).isEqualTo("old-token");
        assertThat(candidate.overwriteStatus).contains("overwrite");
    }

    @Test
    void bindSelectedAliasesWritesOnlyToSelectedCollectionRuntimeVars() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Selected";
        collection.runtimeVars.put("apiToken", "keep-me");

        ApiRequest request = new ApiRequest();
        request.name = "Use Token";
        request.headers.add(new ApiRequest.Header("Authorization", "Bearer {{authToken}}"));
        request.headers.add(new ApiRequest.Header("Authorization", "Bearer {{apiToken}}"));
        collection.requests.add(request);

        List<BearerTokenAliasCandidate> candidates = OAuth2BearerAliasDetector.detect(collection, "new-token");

        OAuth2BearerAliasDetector.bindSelectedAliases(collection, candidates, Set.of("authToken"), "new-token");

        assertThat(collection.runtimeVars)
                .containsEntry("authToken", "new-token")
                .containsEntry("apiToken", "keep-me");
    }

    @Test
    void bindingLeavesOtherCollectionsUntouched() {
        ApiCollection captured = new ApiCollection();
        captured.name = "Captured";

        ApiCollection other = new ApiCollection();
        other.name = "Other";
        other.runtimeVars.put("apiToken", "stay-put");

        ApiRequest request = new ApiRequest();
        request.name = "Use Token";
        request.headers.add(new ApiRequest.Header("Authorization", "Bearer {{authToken}}"));
        captured.requests.add(request);

        List<BearerTokenAliasCandidate> candidates = OAuth2BearerAliasDetector.detect(captured, "new-token");
        OAuth2BearerAliasDetector.bindSelectedAliases(captured, candidates, Set.of("authToken"), "new-token");

        assertThat(captured.runtimeVars).containsEntry("authToken", "new-token");
        assertThat(other.runtimeVars).containsEntry("apiToken", "stay-put");
    }
}
