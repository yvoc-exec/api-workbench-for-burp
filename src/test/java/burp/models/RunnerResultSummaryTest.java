package burp.models;

import burp.runner.RetryFailureType;
import burp.scripts.ScriptFlowControl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerResultSummaryTest {
    @Test
    void summaryRetainsBoundedMetadataWithoutEvidencePayloads() {
        RunnerResult result = fullResult();

        RunnerResultSummary summary = result.toSummary();
        RunnerResult compatibility = summary.toCompatibilityResult();

        assertThat(summary.responseBodyPreview()).hasSize(RunnerResultSummary.RESPONSE_PREVIEW_LIMIT + 3);
        assertThat(summary.statusCode()).isEqualTo(503);
        assertThat(summary.retryFailureType()).isEqualTo(RetryFailureType.HTTP_STATUS);
        assertThat(summary.cancellationState()).isEqualTo(RunnerCancellationState.CANCELLED_DURING_HTTP_WAIT);
        assertThat(summary.scriptFlowControl()).isEqualTo(ScriptFlowControl.STOP_RUN);
        assertThat(summary.assertionPassedCount()).isEqualTo(1);
        assertThat(summary.assertionTotalCount()).isEqualTo(2);
        assertThat(summary.extractedVariableCount()).isEqualTo(2);
        assertThat(summary.extractedVariableNames()).containsExactly("token", "user");
        assertThat(summary.historyEntryId()).isEqualTo("history-1");
        assertThat(summary.redirectSummaries()).hasSize(1);
        assertThat(compatibility.responseBody).isNull();
        assertThat(compatibility.responseHeaders).isNull();
        assertThat(compatibility.requestBody).isNull();
        assertThat(compatibility.requestHeaders).isNull();
        assertThat(compatibility.rawRequestBytes).isNull();
        assertThat(compatibility.rawRequestText).isNull();
        assertThat(compatibility.redirectHops.get(0).rawRequestBytes).isNull();
        assertThat(compatibility.redirectHops.get(0).responseBody).isNull();
        assertThat(compatibility.isHeavyPayloadReleased()).isTrue();

        assertThat(RunnerResultSummary.class.getDeclaredFields())
                .extracting(Field::getType)
                .doesNotContain(RunnerResult.class, byte[].class);
        assertThat(RunnerResultSummary.RedirectSummary.class.getDeclaredFields())
                .extracting(Field::getType)
                .doesNotContain(RedirectHop.class, byte[].class);
    }

    private static RunnerResult fullResult() {
        RunnerResult result = new RunnerResult();
        result.requestId = "request-1";
        result.requestName = "Login";
        result.historyEntryId = "history-1";
        result.collectionId = "collection-1";
        result.collectionName = "API";
        result.requestUrl = "https://api.example.test/login";
        result.method = "POST";
        result.requestHeaders = "Authorization: Bearer secret";
        result.requestBody = "request-body";
        result.rawRequestBytes = "raw-request".getBytes(StandardCharsets.UTF_8);
        result.rawRequestText = "raw-request";
        result.responseHeaders = "Content-Type: text/plain";
        result.responseBody = "r".repeat(2_048);
        result.responseBodyLength = result.responseBody.length();
        result.responseSize = result.responseBody.length();
        result.statusCode = 503;
        result.retryFailureType = RetryFailureType.HTTP_STATUS;
        result.retryReason = "status 503";
        result.cancellationState = RunnerCancellationState.CANCELLED_DURING_HTTP_WAIT;
        result.scriptFlowControl = ScriptFlowControl.STOP_RUN;
        result.assertions = List.of(
                new RunnerResult.AssertionResult("one", true, "", ""),
                new RunnerResult.AssertionResult("two", false, "", ""));
        result.extractedVariables = new java.util.LinkedHashMap<>();
        result.extractedVariables.put("token", "secret");
        result.extractedVariables.put("user", "42");
        RedirectHop hop = new RedirectHop();
        hop.hopNumber = 1;
        hop.sourceMethod = "POST";
        hop.sourceUrl = result.requestUrl;
        hop.targetUrl = "https://api.example.test/final";
        hop.rawRequestBytes = "hop-request".getBytes(StandardCharsets.UTF_8);
        hop.responseBody = "hop-response".getBytes(StandardCharsets.UTF_8);
        result.redirectHops = List.of(hop);
        result.canonicalCaptureComplete = true;
        result.fullEvidenceRetained = true;
        result.evidenceRetentionMessage = "Full evidence retained in History.";
        return result;
    }
}
