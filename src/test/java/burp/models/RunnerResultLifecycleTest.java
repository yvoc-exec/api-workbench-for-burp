package burp.models;

import burp.scripts.ScriptDependentRequestResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerResultLifecycleTest {
    @Test
    void releaseRequiresCaptureAndIsIdempotent() {
        RunnerResult result = payloadResult("parent");

        assertThat(result.releaseHeavyPayloadAfterCanonicalCapture()).isFalse();
        assertThat(result.responseBody).isNotNull();
        assertThat(result.rawRequestBytes).isNotNull();

        result.canonicalCaptureComplete = true;
        result.fullEvidenceRetained = true;
        assertThat(result.releaseHeavyPayloadAfterCanonicalCapture()).isTrue();
        assertThat(result.requestHeaders).isNull();
        assertThat(result.requestBody).isNull();
        assertThat(result.rawRequestBytes).isNull();
        assertThat(result.rawRequestText).isNull();
        assertThat(result.responseHeaders).isNull();
        assertThat(result.responseBody).isNull();
        assertThat(result.responseBodyPreview).isEqualTo("response-parent");
        assertThat(result.responseBodyLength).isEqualTo("response-parent".length());
        assertThat(result.statusCode).isEqualTo(200);
        assertThat(result.redirectHops.get(0).rawRequestBytes).isNull();
        assertThat(result.redirectHops.get(0).responseBody).isNull();
        assertThat(result.releaseHeavyPayloadAfterCanonicalCapture()).isTrue();
        assertThat(result.isHeavyPayloadReleased()).isTrue();
    }

    @Test
    void dependentChildRemainsUsableUntilParentRelease() {
        RunnerResult parent = payloadResult("parent");
        RunnerResult child = payloadResult("child");
        child.canonicalCaptureComplete = true;
        ScriptDependentRequestResult dependent = new ScriptDependentRequestResult();
        dependent.runnerResult = child;
        parent.scriptDependentRequestResults = List.of(dependent);

        assertThat(child.responseBody).isEqualTo("response-child");
        assertThat(child.isHeavyPayloadReleased()).isFalse();

        parent.canonicalCaptureComplete = true;
        assertThat(parent.releaseHeavyPayloadAfterCanonicalCapture()).isTrue();
        assertThat(child.responseBody).isNull();
        assertThat(child.responseBodyPreview).isEqualTo("response-child");
        assertThat(child.isHeavyPayloadReleased()).isTrue();
    }

    private static RunnerResult payloadResult(String suffix) {
        RunnerResult result = new RunnerResult();
        result.requestHeaders = "headers-" + suffix;
        result.requestBody = "request-" + suffix;
        result.rawRequestBytes = ("raw-" + suffix).getBytes(StandardCharsets.UTF_8);
        result.rawRequestText = "raw-" + suffix;
        result.responseHeaders = "response-headers-" + suffix;
        result.responseBody = "response-" + suffix;
        result.statusCode = 200;
        result.responseSize = result.responseBody.length();
        RedirectHop hop = new RedirectHop();
        hop.rawRequestBytes = ("redirect-" + suffix).getBytes(StandardCharsets.UTF_8);
        hop.rawRequestText = "redirect-" + suffix;
        hop.responseBody = ("redirect-response-" + suffix).getBytes(StandardCharsets.UTF_8);
        result.redirectHops = List.of(hop);
        return result;
    }
}
