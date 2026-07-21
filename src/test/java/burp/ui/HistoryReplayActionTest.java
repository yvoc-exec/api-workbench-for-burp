package burp.ui;

import burp.UniversalImporter;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;
import burp.history.HistoryEntry;
import burp.history.HistoryRequestSnapshot;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RedirectPolicy;
import burp.models.WorkspaceState;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import burp.ui.history.HistoryPanel;
import burp.utils.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
        EnvironmentProfile activeEnvironment = HistoryTestFixtures.sampleEnvironment();

        HistoryEntry entry = HistoryTestFixtures.sampleWorkbenchEntry();
        ApiRequest authored = entry.requestSnapshot.toApiRequest();
        authored.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        authored.parameters = new ArrayList<>(List.of(
                richParameter("path", "id", "42", false),
                richParameter("query", "q", "one", false),
                richParameter("header", "X-Authored", "header", true),
                richParameter("cookie", "session", "cookie", false)));
        authored.body.required = true;
        authored.body.description = "authored body";
        authored.body.source = "history:authored";
        authored.body.sourceMetadata.put("retained.body", "value");
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField("field", "retained");
        field.required = true;
        field.description = "field description";
        field.contentType = "text/plain";
        field.style = "form";
        field.explode = Boolean.FALSE;
        field.allowReserved = true;
        field.source = "history:authored";
        field.sourceMetadata.put("retained.field", "value");
        authored.body.formdata = new ArrayList<>(List.of(field));
        entry.requestSnapshot = HistoryRequestSnapshot.from(authored);
        entry.requestSnapshot.rawRequestSent =
                "DELETE /raw-evidence HTTP/1.1\r\nHost: evidence.invalid\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8);
        entry.requestSnapshot.rawRequestSentText =
                "DELETE /raw-evidence HTTP/1.1\r\nHost: evidence.invalid\r\n\r\n";
        ApiRequest expectedAuthored = entry.requestSnapshot.toApiRequest();
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
                org.mockito.ArgumentMatchers.nullable(EnvironmentProfile.class),
                eq(burp.scripts.ExecutionSource.HISTORY_REPLAY),
                any(RedirectPolicy.class)))
                .thenAnswer(invocation -> {
                    capturedEnvironment.set(invocation.getArgument(6));
                    capturedExecutionSource.set(invocation.getArgument(7));
                    return sendResult;
                });

        SwingUtilities.invokeAndWait(() -> {
            bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));
            bundle.panel.replaceEnvironmentProfiles(List.of(activeEnvironment));
            bundle.panel.setActiveEnvironmentId(activeEnvironment.id);
        });
        ImporterPanelTestSupport.awaitEdt();

        assertThat(bundle.panel.getActiveEnvironmentId()).isEqualTo(activeEnvironment.id);
        assertThat(bundle.panel.getEnvironmentProfilesSnapshot())
                .extracting(profile -> profile.id)
                .contains(activeEnvironment.id);
        @SuppressWarnings("unchecked")
        List<EnvironmentProfile> authoritativeProfiles =
                ImporterPanelTestSupport.getField(bundle.panel, "environmentProfiles");
        assertThat(authoritativeProfiles).hasSize(1);
        assertThat(authoritativeProfiles.get(0)).isSameAs(activeEnvironment);

        DiagnosticStore.getInstance().setCaptureEnabled(true);

        clickReplayHistoryButton(bundle, entry);

        ArgumentCaptor<ApiRequest> requestCaptor = ArgumentCaptor.forClass(ApiRequest.class);
        verify(bundle.importer, timeout(3000)).sendSingleRequestWithBuiltRequest(
                requestCaptor.capture(),
                any(ApiCollection.class),
                anyBoolean(),
                anyMap(),
                any(),
                any(),
                eq(activeEnvironment),
                eq(burp.scripts.ExecutionSource.HISTORY_REPLAY),
                any(RedirectPolicy.class));
        assertThat(requestCaptor.getValue().url).isEqualTo("{{base_url}}/login");
        assertThat(requestCaptor.getValue().buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(requestCaptor.getValue().parameters).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(expectedAuthored.parameters);
        assertThat(requestCaptor.getValue().body).usingRecursiveComparison().isEqualTo(expectedAuthored.body);
        assertThat(requestCaptor.getValue().url).doesNotContain("raw-evidence", "evidence.invalid");
        assertThat(capturedEnvironment.get()).isSameAs(activeEnvironment);
        assertThat(capturedExecutionSource.get()).isEqualTo(burp.scripts.ExecutionSource.HISTORY_REPLAY);

        ImporterPanelTestSupport.awaitCondition(
                () -> bundle.panel.getWorkspaceStateSnapshot().historyEntries.size() == 2,
                Duration.ofSeconds(3));
        assertThat(bundle.panel.getWorkspaceStateSnapshot().historyEntries).hasSize(2);
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
                eq(burp.scripts.ExecutionSource.HISTORY_REPLAY),
                any(RedirectPolicy.class)))
                .thenThrow(new Exception("Replay failed: missing token"));

        clickReplayHistoryButton(bundle, entry);

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

    private static ApiRequest.Parameter richParameter(String location, String key,
                                                      String value, boolean disabled) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter(location, key, value);
        parameter.rawKey = "raw-" + key;
        parameter.rawValue = "raw-" + value;
        parameter.valuePresent = true;
        parameter.disabled = disabled;
        parameter.required = true;
        parameter.type = "string";
        parameter.format = "wave5";
        parameter.description = "authored";
        parameter.style = "form";
        parameter.explode = Boolean.FALSE;
        parameter.allowReserved = true;
        parameter.source = "history:authored";
        parameter.sourceMetadata.put("retained.parameter", "value");
        return parameter;
    }

    private static void clickReplayHistoryButton(ImporterPanelTestSupport.PanelBundle bundle, HistoryEntry entry) throws Exception {
        HistoryPanel historyPanel = bundle.panel.getHistoryPanelForTests();
        historyPanel.getHistoryStore().addEntry(entry);
        historyPanel.refreshFromStore(entry.id);
        ImporterPanelTestSupport.awaitEdt();
        SwingUtilities.invokeAndWait(() -> {
            historyPanel.getHistoryTable().setRowSelectionInterval(0, 0);
            historyPanel.getActionsPanel().getReplayButton().doClick();
        });
        ImporterPanelTestSupport.awaitEdt();
    }
}
