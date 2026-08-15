package burp.payload;

import burp.history.HistoryEntry;
import burp.history.HistoryRequestSnapshot;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.models.WorkspaceState;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExactPayloadMigrationServiceTest {
    @TempDir Path temp;

    @Test
    void migratesCollectionAndHistoryWithoutEmbeddingExactBytesInV3Json() throws Exception {
        byte[] raw = "POST /x HTTP/1.1\r\nHost: example.test\r\n\r\nbinary\0body"
                .getBytes(StandardCharsets.ISO_8859_1);
        ApiRequest request = request(raw);
        ApiCollection collection = new ApiCollection();
        collection.requests.add(request);
        HistoryEntry entry = new HistoryEntry();
        entry.requestSnapshot = HistoryRequestSnapshot.fromBorrowingExactTransport(request);
        entry.requestSnapshot.rawRequestSent = raw;
        WorkspaceState state = new WorkspaceState();
        state.version = 2;
        state.collections = List.of(collection);
        state.historyEntries = List.of(entry);

        try (FileManagedPayloadStore store = new FileManagedPayloadStore(temp.resolve("store"))) {
            assertThat(new ExactPayloadMigrationService(store).migrateDetached(state)).isEqualTo(2);
            assertThat(store.uniqueBlobCount()).isEqualTo(1);
            assertThat(request.exactHttpRequest.rawRequestBytes).isNull();
            assertThat(request.exactHttpRequest.payloadRef).isNotNull();
            assertThat(request.body.managedPayload.payload.payloadId)
                    .isEqualTo(request.exactHttpRequest.payloadRef.payloadId);
            assertThat(entry.requestSnapshot.authoredExactRequestBytes).isNull();
            assertThat(entry.requestSnapshot.authoredExactPayloadRef).isNotNull();

            String json = WorkspaceStateJson.toJson(state);
            assertThat(json).contains("MANAGED_FILE").doesNotContain("rawRequestBytes");
            assertThat(json).doesNotContain(java.util.Base64.getEncoder().encodeToString(raw));
        }
    }

    private static ApiRequest request(byte[] raw) {
        ApiRequest request = new ApiRequest();
        request.method = "POST";
        request.url = "https://example.test/x";
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.exactHttpRequest = ExactHttpRequestSnapshot.fromOwnedTrafficBytes(
                raw, Long.MAX_VALUE, "example.test", 443, true, "legacy", "fingerprint");
        return request;
    }
}
