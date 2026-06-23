package burp.ui;

import burp.UniversalImporter;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;
import burp.history.HistoryEntry;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import burp.utils.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryReplayActionTest {

    @AfterEach
    void tearDown() {
        DiagnosticStore.getInstance().setCaptureEnabled(false);
        DiagnosticStore.getInstance().clear();
    }

    @Test
    void replayFromHistoryUsesTemplateRequestAndCreatesNewHistoryRow() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));

        HistoryEntry entry = HistoryTestFixtures.sampleWorkbenchEntry();
        ExecutionResult exec = HistoryTestFixtures.sampleWorkbenchExecutionResult();
        exec.executionSource = burp.scripts.ExecutionSource.HISTORY_REPLAY;
        HttpRequest builtRequest = org.mockito.Mockito.mock(HttpRequest.class);
        UniversalImporter.SingleSendResult sendResult = new UniversalImporter.SingleSendResult(
                exec.response,
                builtRequest,
                exec.requestHeaders,
                exec.resolvedUrl,
                exec.elapsedMs,
                null,
                exec);

        AtomicReference<EnvironmentProfile> capturedEnvironment = new AtomicReference<>();
        AtomicReference<burp.scripts.ExecutionSource> capturedExecutionSource = new AtomicReference<>();
        when(bundle.importer.sendSingleRequestWithBuiltRequest(
                any(ApiRequest.class),
                any(ApiCollection.class),
                anyBoolean(),
                anyMap(),
                any(),
                any(),
                any(EnvironmentProfile.class),
                eq(burp.scripts.ExecutionSource.HISTORY_REPLAY)))
                .thenAnswer(invocation -> {
                    capturedEnvironment.set(invocation.getArgument(6));
                    capturedExecutionSource.set(invocation.getArgument(7));
                    return sendResult;
                });

        EnvironmentProfile activeEnvironment = HistoryTestFixtures.sampleEnvironment();
        bundle.panel.replaceEnvironmentProfiles(List.of(activeEnvironment));
        bundle.panel.setActiveEnvironmentId(activeEnvironment.id);

        DiagnosticStore.getInstance().setCaptureEnabled(true);

        ImporterPanelTestSupport.invokeVoid(
                bundle.panel,
                "replayHistoryEntry",
                new Class<?>[]{HistoryEntry.class},
                entry);

        ArgumentCaptor<ApiRequest> requestCaptor = ArgumentCaptor.forClass(ApiRequest.class);
        verify(bundle.importer, timeout(3000)).sendSingleRequestWithBuiltRequest(
                requestCaptor.capture(),
                any(ApiCollection.class),
                anyBoolean(),
                anyMap(),
                any(),
                any(),
                eq(activeEnvironment),
                eq(burp.scripts.ExecutionSource.HISTORY_REPLAY));
        assertThat(requestCaptor.getValue().url).isEqualTo("{{base_url}}/login");
        assertThat(capturedEnvironment.get()).isSameAs(activeEnvironment);
        assertThat(capturedExecutionSource.get()).isEqualTo(burp.scripts.ExecutionSource.HISTORY_REPLAY);

        ImporterPanelTestSupport.awaitCondition(
                () -> bundle.panel.getWorkspaceStateSnapshot().historyEntries.size() == 1,
                Duration.ofSeconds(3));
        assertThat(bundle.panel.getWorkspaceStateSnapshot().historyEntries).hasSize(1);
        assertThat(bundle.panel.getWorkspaceStateSnapshot().historyEntries.get(0).requestSnapshot.urlTemplate)
                .isEqualTo("{{base_url}}/login");
        assertThat(bundle.panel.getWorkspaceStateSnapshot().historyEntries.get(0).executionSource)
                .isEqualTo("HISTORY_REPLAY");
        assertThat(collectionNames(ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections")))
                .doesNotContain("History Replays");
    }

    @Test
    void replayFailureCreatesStructuredErrorDiagnostic() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));
        EnvironmentProfile activeEnvironment = HistoryTestFixtures.sampleEnvironment();
        bundle.panel.replaceEnvironmentProfiles(List.of(activeEnvironment));
        bundle.panel.setActiveEnvironmentId(activeEnvironment.id);
        DiagnosticStore.getInstance().setCaptureEnabled(true);

        HistoryEntry entry = HistoryTestFixtures.sampleWorkbenchEntry();
        when(bundle.importer.sendSingleRequestWithBuiltRequest(
                any(ApiRequest.class),
                any(ApiCollection.class),
                anyBoolean(),
                anyMap(),
                any(),
                any(),
                any(EnvironmentProfile.class),
                eq(burp.scripts.ExecutionSource.HISTORY_REPLAY)))
                .thenThrow(new Exception("Replay failed: missing token"));

        ImporterPanelTestSupport.invokeVoid(
                bundle.panel,
                "replayHistoryEntry",
                new Class<?>[]{HistoryEntry.class},
                entry);

        ImporterPanelTestSupport.awaitCondition(
                () -> DiagnosticStore.getInstance().snapshot().stream()
                        .anyMatch(event -> event.operation == DiagnosticOperation.REPLAY && event.severity == DiagnosticSeverity.ERROR),
                Duration.ofSeconds(3));

        assertThat(DiagnosticStore.getInstance().snapshot())
                .filteredOn(event -> event.operation == DiagnosticOperation.REPLAY)
                .anySatisfy(event -> {
                    assertThat(event.severity).isEqualTo(DiagnosticSeverity.ERROR);
                    assertThat(event.message).isEqualTo("Replay failed");
                    assertThat(event.collectionName).isEqualTo(HistoryTestFixtures.COLLECTION_NAME);
                    assertThat(event.requestName).isEqualTo(HistoryTestFixtures.REQUEST_NAME);
                    assertThat(event.environmentName).isEqualTo(HistoryTestFixtures.ENVIRONMENT_NAME);
                    assertThat(event.attributes).containsEntry("historyId", HistoryTestFixtures.sampleWorkbenchEntry().id);
                    assertThat(event.attributes).containsEntry("collectionId", HistoryTestFixtures.COLLECTION_ID);
                    assertThat(event.attributes).containsEntry("reason", "Replay failed: missing token");
                });
    }

    private static List<String> collectionNames(List<ApiCollection> collections) {
        return collections.stream()
                .map(collection -> collection != null ? collection.name : null)
                .toList();
    }
}
