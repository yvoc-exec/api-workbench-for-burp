package burp.utils;

import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBuildPolicyTest {

    @Test
    void exactHttpModeIsExposedThroughThePolicyHelper() {
        ApiRequest req = new ApiRequest();
        req.buildMode = ApiRequest.BuildMode.EXACT_HTTP;

        RequestBuildPolicy policy = RequestBuildPolicy.forRequest(req);

        assertThat(policy.exactHttp()).isTrue();
        assertThat(policy.autoCompatible()).isFalse();
        assertThat(policy.manualPreserve()).isFalse();
        assertThat(policy.shouldApplyDefaultHeaders(req)).isFalse();
        assertThat(policy.shouldApplyAuthentication(req)).isFalse();
        assertThat(policy.shouldSynthesizeBodyContentType(req)).isFalse();
    }

    @Test
    void legacyRequestsStillDefaultToSafeNormalizedCompatibility() {
        ApiRequest req = new ApiRequest();

        RequestBuildPolicy policy = RequestBuildPolicy.forRequest(req);

        assertThat(policy.exactHttp()).isFalse();
        assertThat(policy.autoCompatible()).isTrue();
        assertThat(policy.manualPreserve()).isFalse();
    }
}
