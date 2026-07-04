package burp.ui.traffic;

import burp.UniversalImporter;
import burp.history.HistoryEntry;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistorySource;
import burp.importer.BurpTrafficImportPlan;
import burp.importer.BurpTrafficImportService;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.models.WorkspaceState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class BurpTrafficWorkflowCoordinatorTest {
    @Test
    void appliesExistingCollectionImportToDetachedWorkspaceAndQueuesEnabledRequests() {
        WorkspaceState before = workspaceWithExistingCollection();
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
        assertThat(before.runnerQueuedRequestIdentityKeys).isEmpty();

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
                .containsExactly("Existing Collection\u001Fid=imported-id");
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
        assertThat(after.collections.get(1).name).isEqualTo("Existing Collection (2)");
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
