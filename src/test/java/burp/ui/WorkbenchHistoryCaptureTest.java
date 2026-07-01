package burp.ui;

import burp.UniversalImporter;
import burp.history.HistoryEntry;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RedirectHop;
import burp.models.RedirectTerminationReason;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import burp.utils.ExecutionResult;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchHistoryCaptureTest {

    @Test
    void workbenchSendCreatesTemplateBasedHistoryEntry() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        ApiRequest request = HistoryTestFixtures.sampleRequest();
        EnvironmentProfile environment = HistoryTestFixtures.sampleEnvironment();
        bundle.panel.replaceEnvironmentProfiles(List.of(environment));
        bundle.panel.setActiveEnvironmentId(environment.id);

        ExecutionResult exec = HistoryTestFixtures.sampleWorkbenchExecutionResult();
        UniversalImporter.SingleSendResult sendResult = new UniversalImporter.SingleSendResult(
                exec.response,
                exec.builtRequest,
                exec.requestHeaders,
                exec.resolvedUrl,
                exec.elapsedMs,
                null,
                exec);

        ImporterPanelTestSupport.invokeVoid(
                bundle.panel,
                "recordWorkbenchHistoryEntry",
                new Class<?>[]{ApiRequest.class, ApiCollection.class, UniversalImporter.SingleSendResult.class, String.class, List.class, java.util.Map.class, String.class},
                request,
                collection,
                sendResult,
                null,
                List.of(),
                null,
                "Send");

        ImporterPanelTestSupport.awaitCondition(
                () -> bundle.panel.getWorkspaceStateSnapshot().historyEntries.size() == 1,
                Duration.ofSeconds(3));

        HistoryEntry entry = bundle.panel.getWorkspaceStateSnapshot().historyEntries.get(0);
        assertThat(entry.source).isEqualTo(burp.history.HistorySource.WORKBENCH);
        assertThat(entry.requestSnapshot.urlTemplate).isEqualTo("{{base_url}}/login");
        assertThat(entry.responseSnapshot.statusCode).isEqualTo(200);
        assertThat(entry.environmentName).isEqualTo(HistoryTestFixtures.ENVIRONMENT_NAME);
        assertThat(entry.result).isEqualTo(burp.history.HistoryResult.SUCCESS);
        assertThat(entry.unresolvedVariables).isEmpty();
    }

    @Test
    void workbenchSendKeepsRedirectEvidenceNestedOnSuccessfulMultiHopExecution() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        ApiRequest request = HistoryTestFixtures.sampleRequest();
        EnvironmentProfile environment = HistoryTestFixtures.sampleEnvironment();
        bundle.panel.replaceEnvironmentProfiles(List.of(environment));
        bundle.panel.setActiveEnvironmentId(environment.id);

        ExecutionResult exec = HistoryTestFixtures.sampleWorkbenchExecutionResult();
        exec.success = true;
        exec.initialResolvedUrl = "https://api.example.test/login";
        exec.finalResolvedUrl = "https://api.example.test/final";
        exec.redirectsEnabled = true;
        exec.redirectTerminationReason = RedirectTerminationReason.FINAL_RESPONSE;
        exec.redirectHops.add(redirectHop(1, "https://api.example.test/login", "https://api.example.test/next", true, null));
        exec.redirectHops.add(redirectHop(2, "https://api.example.test/next", "https://api.example.test/final", true, null));

        UniversalImporter.SingleSendResult sendResult = new UniversalImporter.SingleSendResult(
                exec.response,
                exec.builtRequest,
                exec.requestHeaders,
                exec.resolvedUrl,
                exec.elapsedMs,
                null,
                exec);

        ImporterPanelTestSupport.invokeVoid(
                bundle.panel,
                "recordWorkbenchHistoryEntry",
                new Class<?>[]{ApiRequest.class, ApiCollection.class, UniversalImporter.SingleSendResult.class, String.class, List.class, java.util.Map.class, String.class},
                request,
                collection,
                sendResult,
                null,
                List.of(),
                null,
                "Send");

        ImporterPanelTestSupport.awaitCondition(
                () -> bundle.panel.getWorkspaceStateSnapshot().historyEntries.size() == 1,
                Duration.ofSeconds(3));

        HistoryEntry entry = bundle.panel.getWorkspaceStateSnapshot().historyEntries.get(0);
        assertThat(entry.source).isEqualTo(burp.history.HistorySource.WORKBENCH);
        assertThat(entry.redirectsEnabled).isTrue();
        assertThat(entry.initialResolvedUrl).isEqualTo("https://api.example.test/login");
        assertThat(entry.finalResolvedUrl).isEqualTo("https://api.example.test/final");
        assertThat(entry.redirectTerminationReason).isEqualTo(RedirectTerminationReason.FINAL_RESPONSE);
        assertThat(entry.redirectHops).hasSize(2);
        assertThat(entry.redirectHops.get(0).targetUrl).isEqualTo("https://api.example.test/next");
        assertThat(entry.redirectHops.get(1).targetUrl).isEqualTo("https://api.example.test/final");
        assertThat(entry.responseSnapshot.statusCode).isEqualTo(200);
        assertThat(bundle.panel.getWorkspaceStateSnapshot().historyEntries).hasSize(1);
    }

    @Test
    void workbenchRedirectFailureRetainsHopEvidenceAndFailureReason() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        ApiRequest request = HistoryTestFixtures.sampleRequest();
        EnvironmentProfile environment = HistoryTestFixtures.sampleEnvironment();
        bundle.panel.replaceEnvironmentProfiles(List.of(environment));
        bundle.panel.setActiveEnvironmentId(environment.id);

        ExecutionResult exec = HistoryTestFixtures.sampleWorkbenchExecutionResult();
        exec.success = false;
        exec.errorMessage = "Redirect limit exceeded";
        exec.response = HistoryTestFixtures.mockResponseResponse(302, "", "text/plain");
        exec.initialResolvedUrl = "https://api.example.test/start";
        exec.finalResolvedUrl = "https://api.example.test/next";
        exec.redirectsEnabled = true;
        exec.redirectTerminationReason = RedirectTerminationReason.LIMIT_EXCEEDED;
        exec.redirectHops.add(redirectHop(1, "https://api.example.test/start", "https://api.example.test/next", true, null));
        exec.redirectHops.add(redirectHop(2, "https://api.example.test/next", "https://api.example.test/next", false, "Redirect limit exceeded"));

        UniversalImporter.SingleSendResult sendResult = new UniversalImporter.SingleSendResult(
                exec.response,
                exec.builtRequest,
                exec.requestHeaders,
                exec.resolvedUrl,
                exec.elapsedMs,
                null,
                exec);

        ImporterPanelTestSupport.invokeVoid(
                bundle.panel,
                "recordWorkbenchHistoryEntry",
                new Class<?>[]{ApiRequest.class, ApiCollection.class, UniversalImporter.SingleSendResult.class, String.class, List.class, java.util.Map.class, String.class},
                request,
                collection,
                sendResult,
                "Redirect limit exceeded",
                List.of(),
                null,
                "Send");

        ImporterPanelTestSupport.awaitCondition(
                () -> bundle.panel.getWorkspaceStateSnapshot().historyEntries.size() == 1,
                Duration.ofSeconds(3));

        HistoryEntry entry = bundle.panel.getWorkspaceStateSnapshot().historyEntries.get(0);
        assertThat(entry.redirectHops).hasSize(2);
        assertThat(entry.redirectTerminationReason).isEqualTo(RedirectTerminationReason.LIMIT_EXCEEDED);
        assertThat(entry.errorMessage).isEqualTo("Redirect limit exceeded");
        assertThat(entry.redirectHops.get(1).followed).isFalse();
        assertThat(entry.redirectHops.get(1).failureReason).isEqualTo("Redirect limit exceeded");
    }

    private static RedirectHop redirectHop(int hopNumber, String sourceUrl, String targetUrl, boolean followed, String failureReason) {
        RedirectHop hop = new RedirectHop();
        hop.hopNumber = hopNumber;
        hop.sourceUrl = sourceUrl;
        hop.targetUrl = targetUrl;
        hop.location = targetUrl != null ? targetUrl : "";
        hop.followed = followed;
        hop.failureReason = failureReason;
        hop.statusCode = followed ? 302 : 307;
        hop.rawRequestBytes = (sourceUrl + "\r\n").getBytes(StandardCharsets.UTF_8);
        hop.responseBody = (targetUrl != null ? targetUrl : "").getBytes(StandardCharsets.UTF_8);
        hop.forwardedSensitiveHeaderNames = new java.util.ArrayList<>(List.of("Authorization"));
        hop.strippedSensitiveHeaderNames = new java.util.ArrayList<>(List.of("Proxy-Authorization"));
        return hop;
    }
}
