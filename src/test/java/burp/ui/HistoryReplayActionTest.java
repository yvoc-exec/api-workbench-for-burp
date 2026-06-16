package burp.ui;

import burp.UniversalImporter;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.history.HistoryEntry;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspaceState;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import burp.utils.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryReplayActionTest {

    @Test
    void replayFromHistoryUsesTemplateRequestAndCreatesNewHistoryRow() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));

        HistoryEntry entry = HistoryTestFixtures.sampleWorkbenchEntry();
        ExecutionResult exec = HistoryTestFixtures.sampleWorkbenchExecutionResult();
        HttpRequest builtRequest = org.mockito.Mockito.mock(HttpRequest.class);
        UniversalImporter.SingleSendResult sendResult = new UniversalImporter.SingleSendResult(
                exec.response,
                builtRequest,
                exec.requestHeaders,
                exec.resolvedUrl,
                exec.elapsedMs,
                null,
                exec);

        when(bundle.importer.sendSingleRequestWithBuiltRequest(any(ApiRequest.class), any(ApiCollection.class), anyBoolean()))
                .thenReturn(sendResult);

        ImporterPanelTestSupport.invokeVoid(
                bundle.panel,
                "replayHistoryEntry",
                new Class<?>[]{HistoryEntry.class},
                entry);

        ArgumentCaptor<ApiRequest> requestCaptor = ArgumentCaptor.forClass(ApiRequest.class);
        verify(bundle.importer, timeout(3000)).sendSingleRequestWithBuiltRequest(
                requestCaptor.capture(),
                any(ApiCollection.class),
                anyBoolean());
        assertThat(requestCaptor.getValue().url).isEqualTo("{{base_url}}/login");

        ImporterPanelTestSupport.awaitCondition(
                () -> bundle.panel.getWorkspaceStateSnapshot().historyEntries.size() == 1,
                Duration.ofSeconds(3));
        assertThat(bundle.panel.getWorkspaceStateSnapshot().historyEntries).hasSize(1);
        assertThat(bundle.panel.getWorkspaceStateSnapshot().historyEntries.get(0).requestSnapshot.urlTemplate)
                .isEqualTo("{{base_url}}/login");
        assertThat(collectionNames(ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections")))
                .doesNotContain("History Replays");
    }

    @Test
    void replayFromHistoryDoesNotCreateHistoryReplaysWhenOriginalRequestMissing() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        liveCollection.requests.remove(0);

        HistoryEntry entry = HistoryTestFixtures.sampleWorkbenchEntry();
        ExecutionResult exec = HistoryTestFixtures.sampleWorkbenchExecutionResult();
        HttpRequest builtRequest = org.mockito.Mockito.mock(HttpRequest.class);
        UniversalImporter.SingleSendResult sendResult = new UniversalImporter.SingleSendResult(
                exec.response,
                builtRequest,
                exec.requestHeaders,
                exec.resolvedUrl,
                exec.elapsedMs,
                null,
                exec);

        when(bundle.importer.sendSingleRequestWithBuiltRequest(any(ApiRequest.class), any(ApiCollection.class), anyBoolean()))
                .thenReturn(sendResult);

        ImporterPanelTestSupport.invokeVoid(
                bundle.panel,
                "replayHistoryEntry",
                new Class<?>[]{HistoryEntry.class},
                entry);

        ArgumentCaptor<ApiRequest> requestCaptor = ArgumentCaptor.forClass(ApiRequest.class);
        verify(bundle.importer, timeout(3000)).sendSingleRequestWithBuiltRequest(
                requestCaptor.capture(),
                any(ApiCollection.class),
                anyBoolean());

        ImporterPanelTestSupport.awaitCondition(
                () -> bundle.panel.getWorkspaceStateSnapshot().historyEntries.size() == 1,
                Duration.ofSeconds(3));
        assertThat(bundle.panel.getWorkspaceStateSnapshot().historyEntries).hasSize(1);
        assertThat(collectionNames(loadedCollections)).doesNotContain("History Replays");
        assertThat(liveCollection.requests).hasSize(1);
    }

    private static List<String> collectionNames(List<ApiCollection> collections) {
        return collections.stream()
                .map(collection -> collection != null ? collection.name : null)
                .toList();
    }
}
