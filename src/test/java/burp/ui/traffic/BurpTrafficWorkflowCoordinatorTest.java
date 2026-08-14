package burp.ui.traffic;

import burp.UniversalImporter;
import burp.history.HistoryEntry;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistorySource;
import burp.importer.BurpTrafficImportPlan;
import burp.importer.BurpTrafficConversionResult;
import burp.importer.BurpTrafficImportService;
import burp.importer.BurpTrafficSelection;
import burp.importer.TrafficImportLimits;
import burp.importer.TrafficImportPreflightResult;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.models.WorkspaceState;
import burp.utils.RequestBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class BurpTrafficWorkflowCoordinatorTest {

    @Test
    void disablingExactTransportMaterializesCapturedTextBodyBeforeSemanticSend() throws Exception {
        ApiRequest imported = importedTextRequest("body-id", "Body Request", "important-payload");
        ApiCollection destinationCollection = workspaceWithExistingCollection().collections.get(0);
        TrafficDestinationDialogModel destination = new TrafficDestinationDialogModel(
                List.of(destinationCollection), List.of(imported), false, false);
        destination.setPreserveExactTransport(false);
        BurpTrafficConversionResult conversion = new BurpTrafficConversionResult();
        conversion.requests.add(imported);

        BurpTrafficImportPlan plan = coordinator().buildPlan(destination, conversion);
        ApiRequest planned = plan.requests.get(0);
        String built = new String(new RequestBuilder(null).buildRequest(planned, null), StandardCharsets.UTF_8);

        assertThat(planned.exactHttpRequest.pristine).isFalse();
        assertThat(planned.body.raw).isEqualTo("important-payload");
        assertThat(built).endsWith("\r\n\r\nimportant-payload");
    }
    @Test
    void appliesExistingCollectionImportToDetachedWorkspaceAndAppendsEnabledRequestsToRunnerQueue() {
        WorkspaceState before = workspaceWithExistingCollection();
        before.runnerQueuedRequestIdentityKeys.add("Existing Collection\u001Fid=existing-id");
        before.runnerQueuedRequestIdentityKeys.add("Existing Collection\u001Fid=existing-id");
        ApiCollection selectedDestination = before.collections.get(0);
        ApiRequest imported = importedRequest("imported-id", "Imported Request");
        HistoryEntry history = importedHistory(imported);
        BurpTrafficImportPlan plan = new BurpTrafficImportPlan(
                selectedDestination,
                "",
                "Captured",
                List.of(imported),
                List.of(history),
                true,
                true,
                true);
        BurpTrafficWorkflowCoordinator coordinator = coordinator();

        WorkspaceState after = coordinator.applyPlan(before, plan);
        imported.name = "mutated-after-plan";

        assertThat(before.collections.get(0).requests).hasSize(1);
        assertThat(before.historyEntries).isEmpty();
        assertThat(before.runnerQueuedRequestIdentityKeys)
                .containsExactly(
                        "Existing Collection\u001Fid=existing-id",
                        "Existing Collection\u001Fid=existing-id");

        ApiCollection destination = after.collections.get(0);
        assertThat(destination.requests).hasSize(2);
        assertThat(destination.requests.get(1).name).isEqualTo("Imported Request");
        assertThat(destination.requests.get(1).path).isEqualTo("Captured");
        assertThat(destination.requests.get(1).sourceCollection).isEqualTo("Existing Collection");
        assertThat(destination.requests.get(1).exactHttpRequest.rawRequestBytes)
                .isEqualTo(imported.exactHttpRequest.rawRequestBytes);
        assertThat(after.historyEntries).singleElement().satisfies(entry -> {
            assertThat(entry.collectionId).isEqualTo("collection-id");
            assertThat(entry.collectionName).isEqualTo("Existing Collection");
            assertThat(entry.requestId).isEqualTo("imported-id");
        });
        assertThat(after.runnerQueuedRequestIdentityKeys)
                .containsExactly(
                        "Existing Collection\u001Fid=existing-id",
                        "Existing Collection\u001Fid=imported-id");
        assertThat(after.selectedRequestIdentityKey)
                .isEqualTo("Existing Collection\u001Fid=imported-id");
        assertThat(after.selectedTabIndex).isEqualTo(3);
    }

    @Test
    void createsUniqueCollectionWithoutMutatingSourceWorkspace() {
        WorkspaceState before = workspaceWithExistingCollection();
        ApiRequest imported = importedRequest("new-id", "New Request");
        BurpTrafficImportPlan plan = new BurpTrafficImportPlan(
                null,
                "Existing Collection",
                "",
                List.of(imported),
                List.of(),
                true,
                false,
                false);

        WorkspaceState after = coordinator().applyPlan(before, plan);

        assertThat(before.collections).hasSize(1);
        assertThat(after.collections).hasSize(2);
        assertThat(after.collections.get(1).name).isEqualTo("Existing Collection 2");
        assertThat(after.collections.get(1).requests).singleElement()
                .extracting(request -> request.id)
                .isEqualTo("new-id");
    }

    @Test
    void invalidFolderFailsBeforeAnySourceStateMutation() {
        WorkspaceState before = workspaceWithExistingCollection();
        ApiCollection selectedDestination = before.collections.get(0);
        BurpTrafficImportPlan plan = new BurpTrafficImportPlan(
                selectedDestination,
                "",
                "Missing/Folder",
                List.of(importedRequest("bad-id", "Bad Request")),
                List.of(),
                true,
                false,
                false);

        assertThatThrownBy(() -> coordinator().applyPlan(before, plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("folder");
        assertThat(before.collections.get(0).requests).hasSize(1);
        assertThat(before.historyEntries).isEmpty();
    }

    @Test
    void transactionCopySharesExactBackingButIsolatesMutableMetadata() {
        WorkspaceState before = workspaceWithExistingCollection();
        ApiRequest existing = before.collections.get(0).requests.get(0);
        existing.exactHttpRequest = importedRequest("backing", "Backing").exactHttpRequest;
        byte[] backing = existing.exactHttpRequest.rawRequestBytes;
        BurpTrafficImportPlan plan = new BurpTrafficImportPlan(
                before.collections.get(0), "", "Captured",
                List.of(importedRequest("new", "New")), List.of(), true, false, false);

        WorkspaceState after = coordinator().applyPlan(before, plan);
        ApiRequest copiedExisting = after.collections.get(0).requests.get(0);

        assertThat(copiedExisting).isNotSameAs(existing);
        assertThat(copiedExisting.exactHttpRequest).isNotSameAs(existing.exactHttpRequest);
        assertThat(copiedExisting.exactHttpRequest.rawRequestBytes).isSameAs(backing);
        copiedExisting.name = "changed";
        copiedExisting.exactHttpRequest.pristine = false;
        assertThat(existing.name).isEqualTo("Existing Request");
        assertThat(existing.exactHttpRequest.pristine).isTrue();
    }

    @Test
    void conversionAndPersistenceValidationRunOffEdtWhileCommitRunsOnEdt() {
        UniversalImporter importer = mock(UniversalImporter.class);
        burp.ui.ImporterPanel ui = mock(burp.ui.ImporterPanel.class);
        BurpTrafficImportService service = mock(BurpTrafficImportService.class);
        WorkspaceState before = workspaceWithExistingCollection();
        AtomicBoolean conversionOnEdt = new AtomicBoolean(true);
        AtomicBoolean validationOnEdt = new AtomicBoolean(true);
        AtomicBoolean commitOnEdt = new AtomicBoolean(false);
        AtomicBoolean presenterOnEdt = new AtomicBoolean(false);
        BurpTrafficConversionResult converted = acceptedConversion(importedRequest("imported", "Imported"));
        when(importer.getUI()).thenReturn(ui);
        when(ui.getPanel()).thenReturn(new javax.swing.JPanel());
        when(ui.getHistoryRetentionPolicySnapshot()).thenReturn(burp.history.HistoryRetentionPolicy.defaultPolicy());
        when(ui.getWorkspaceStateSnapshotFromModelForPersistence()).thenReturn(before);
        when(service.convert(any(), any())).thenAnswer(invocation -> {
            conversionOnEdt.set(javax.swing.SwingUtilities.isEventDispatchThread());
            return converted;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            validationOnEdt.set(javax.swing.SwingUtilities.isEventDispatchThread());
            return null;
        }).when(importer).validateWorkspaceStatePersistable(any());
        org.mockito.Mockito.doAnswer(invocation -> {
            commitOnEdt.set(javax.swing.SwingUtilities.isEventDispatchThread());
            return null;
        }).when(ui).restoreWorkspaceState(any());
        BurpTrafficWorkflowCoordinator coordinator = new BurpTrafficWorkflowCoordinator(
                importer, service,
                (owner, model) -> {
                    presenterOnEdt.set(javax.swing.SwingUtilities.isEventDispatchThread());
                    return true;
                },
                (parent, title, message, type) -> { });
        try {
            coordinator.importTraffic(List.of(selection()), false);

            assertThat(conversionOnEdt.get()).isFalse();
            assertThat(validationOnEdt.get()).isFalse();
            assertThat(commitOnEdt.get()).isTrue();
            assertThat(presenterOnEdt.get()).isTrue();
            verify(importer).requestWorkspaceStateSaveNow();
        } finally {
            coordinator.close();
        }
    }

    @Test
    void persistenceRejectionLeavesWorkspaceAndSaveUntouched() {
        UniversalImporter importer = mock(UniversalImporter.class);
        burp.ui.ImporterPanel ui = mock(burp.ui.ImporterPanel.class);
        BurpTrafficImportService service = mock(BurpTrafficImportService.class);
        WorkspaceState before = workspaceWithExistingCollection();
        BurpTrafficConversionResult converted = acceptedConversion(importedRequest("imported", "Imported"));
        when(importer.getUI()).thenReturn(ui);
        when(ui.getPanel()).thenReturn(new javax.swing.JPanel());
        when(ui.getHistoryRetentionPolicySnapshot()).thenReturn(burp.history.HistoryRetentionPolicy.defaultPolicy());
        when(ui.getWorkspaceStateSnapshotFromModelForPersistence()).thenReturn(before);
        when(service.convert(any(), any())).thenReturn(converted);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("too large: secret body omitted"))
                .when(importer).validateWorkspaceStatePersistable(any());
        java.util.concurrent.atomic.AtomicReference<String> shown = new java.util.concurrent.atomic.AtomicReference<>();
        BurpTrafficWorkflowCoordinator coordinator = new BurpTrafficWorkflowCoordinator(
                importer, service, (owner, model) -> true,
                (parent, title, message, type) -> shown.set(message));
        try {
            coordinator.importTraffic(List.of(selection()), true);

            verify(ui, never()).restoreWorkspaceState(any());
            verify(importer, never()).requestWorkspaceStateSaveNow();
            assertThat(shown.get()).contains("No workspace data was changed").doesNotContain("secret body");
            assertThat(before.collections.get(0).requests).hasSize(1);
            assertThat(before.historyEntries).isEmpty();
            assertThat(before.runnerQueuedRequestIdentityKeys).isEmpty();
        } finally {
            coordinator.close();
        }
    }

    @Test
    void handledBackgroundFailureDoesNotEscapeToTheCaller() {
        UniversalImporter importer = mock(UniversalImporter.class);
        burp.ui.ImporterPanel ui = mock(burp.ui.ImporterPanel.class);
        BurpTrafficImportService service = mock(BurpTrafficImportService.class);
        when(importer.getUI()).thenReturn(ui);
        when(ui.getPanel()).thenReturn(new javax.swing.JPanel());
        when(ui.getHistoryRetentionPolicySnapshot())
                .thenReturn(burp.history.HistoryRetentionPolicy.defaultPolicy());
        when(service.convert(any(), any())).thenThrow(new IllegalStateException("safe failure"));
        AtomicBoolean presenterOnEdt = new AtomicBoolean(false);
        BurpTrafficWorkflowCoordinator coordinator = new BurpTrafficWorkflowCoordinator(
                importer, service, (owner, model) -> true,
                (parent, title, message, type) ->
                        presenterOnEdt.set(javax.swing.SwingUtilities.isEventDispatchThread()));
        try {
            assertThatCode(() -> coordinator.importTraffic(List.of(selection()), false))
                    .doesNotThrowAnyException();
            assertThat(presenterOnEdt.get()).isTrue();
            verify(ui, never()).restoreWorkspaceState(any());
            verify(importer, never()).requestWorkspaceStateSaveNow();
        } finally {
            coordinator.close();
        }
    }

    private static BurpTrafficConversionResult acceptedConversion(ApiRequest request) {
        BurpTrafficConversionResult result = new BurpTrafficConversionResult();
        result.requests.add(request);
        result.preflight = new TrafficImportPreflightResult(
                1, 1, request.exactHttpRequest.rawRequestBytes.length, 0,
                TrafficImportLimits.DEFAULT_MAX_EXACT_REQUEST_BYTES,
                TrafficImportLimits.DEFAULT_MAX_AGGREGATE_EXACT_REQUEST_BYTES,
                List.of());
        return result;
    }

    private static BurpTrafficSelection selection() {
        return BurpTrafficSelection.fromOwnedBytes(
                "GET / HTTP/1.1\r\nHost: example.invalid\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1),
                null, "example.invalid", 443, true, "Proxy", null, null, 0);
    }

    private static BurpTrafficWorkflowCoordinator coordinator() {
        return new BurpTrafficWorkflowCoordinator(
                mock(UniversalImporter.class),
                new BurpTrafficImportService(),
                (owner, model) -> false,
                (parent, title, message, type) -> { });
    }

    private static WorkspaceState workspaceWithExistingCollection() {
        WorkspaceState state = new WorkspaceState();
        ApiCollection collection = new ApiCollection();
        collection.id = "collection-id";
        collection.name = "Existing Collection";
        collection.folderPaths.add("Captured");
        ApiRequest existing = new ApiRequest();
        existing.id = "existing-id";
        existing.name = "Existing Request";
        existing.method = "GET";
        existing.url = "https://example.invalid/existing";
        collection.requests.add(existing);
        state.collections.add(collection);
        return state;
    }

    private static ApiRequest importedRequest(String id, String name) {
        byte[] raw = ("GET /captured HTTP/1.1\r\nHost: example.invalid\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.method = "GET";
        request.url = "https://example.invalid/captured";
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.exactHttpRequest = new ExactHttpRequestSnapshot();
        request.exactHttpRequest.rawRequestBytes = raw;
        request.exactHttpRequest.serviceHost = "example.invalid";
        request.exactHttpRequest.servicePort = 443;
        request.exactHttpRequest.secure = true;
        request.exactHttpRequest.pristine = true;
        request.exactHttpRequest.semanticFingerprint = request.computeSemanticFingerprint();
        return request;
    }

    private static ApiRequest importedTextRequest(String id, String name, String body) {
        ApiRequest request = importedRequest(id, name);
        request.method = "POST";
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = null;
        request.exactHttpRequest.rawRequestBytes = (
                "POST /captured HTTP/1.1\r\nHost: example.invalid\r\nContent-Type: text/plain\r\n\r\n" + body)
                .getBytes(StandardCharsets.UTF_8);
        request.exactHttpRequest.binaryBody = false;
        request.exactHttpRequest.semanticFingerprint = request.computeSemanticFingerprint();
        return request;
    }

    private static HistoryEntry importedHistory(ApiRequest request) {
        HistoryEntry entry = new HistoryEntry();
        entry.id = "history-id";
        entry.timestamp = Instant.parse("2026-07-05T01:00:00Z");
        entry.source = HistorySource.BURP_TRAFFIC;
        entry.requestId = request.id;
        entry.requestName = request.name;
        entry.requestSnapshot = HistoryRequestSnapshot.from(request);
        entry.requestSnapshot.rawRequestSent = request.exactHttpRequest.rawRequestBytes.clone();
        entry.ensureDefaults();
        return entry;
    }
}
