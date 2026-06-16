package burp.ui;

import burp.UniversalImporter;
import burp.history.HistoryEntry;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import burp.utils.ExecutionResult;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.time.Duration;
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
}
