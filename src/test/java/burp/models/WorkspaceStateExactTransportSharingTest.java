package burp.models;

import burp.history.HistoryEntry;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistoryResponseSnapshot;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceStateExactTransportSharingTest {
    @Test
    void saveSnapshotDetachesMetadataSharesExactBytesAndReleasesIndependently() {
        byte[] exact = "POST /exact HTTP/1.1\r\nHost: example.test\r\n\r\npayload"
                .getBytes(StandardCharsets.ISO_8859_1);
        ApiRequest liveRequest = new ApiRequest();
        liveRequest.id = "request-id";
        liveRequest.name = "Exact";
        liveRequest.method = "POST";
        liveRequest.url = "https://example.test/exact";
        liveRequest.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        liveRequest.sourceMetadata.put("origin", "live");
        liveRequest.exactHttpRequest = new ExactHttpRequestSnapshot();
        liveRequest.exactHttpRequest.rawRequestBytes = exact;
        liveRequest.exactHttpRequest.pristine = true;
        liveRequest.exactHttpRequest.sourceContext = "Proxy";
        ApiCollection collection = new ApiCollection();
        collection.id = "collection-id";
        collection.name = "Collection";
        collection.requests.add(liveRequest);
        WorkspaceState live = new WorkspaceState();
        live.collections.add(collection);

        WorkspaceState detached = WorkspaceState.copyOfSharingExactTransport(live);
        ApiRequest savedRequest = detached.collections.get(0).requests.get(0);

        assertThat(savedRequest).isNotSameAs(liveRequest);
        assertThat(savedRequest.exactHttpRequest).isNotSameAs(liveRequest.exactHttpRequest);
        assertThat(savedRequest.exactHttpRequest.rawRequestBytes).isSameAs(exact);
        assertThat(savedRequest.sourceMetadata).isNotSameAs(liveRequest.sourceMetadata);
        savedRequest.name = "changed";
        savedRequest.sourceMetadata.put("origin", "detached");
        savedRequest.exactHttpRequest.pristine = false;
        WorkspaceStateJson.SerializedWorkspace serialized =
                WorkspaceStateJson.serializeDetachedWithMetadataAndRelease(detached, 8 * 1024 * 1024);

        assertThat(serialized.json()).contains("awb:b64:v1:");
        assertThat(liveRequest.name).isEqualTo("Exact");
        assertThat(liveRequest.sourceMetadata).containsEntry("origin", "live");
        assertThat(liveRequest.exactHttpRequest.pristine).isTrue();
        assertThat(liveRequest.exactHttpRequest.rawRequestBytes).isSameAs(exact).isNotEmpty();
    }

    @Test
    void persistenceCopySharesBoundedHistoryEvidenceAndReleasesOnlyDetachedWrappers() {
        byte[] rawRequest = "GET /history HTTP/1.1\r\nHost: example.test\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] responseBody = new byte[]{1, 2, 3, 4};
        HistoryEntry history = new HistoryEntry();
        history.id = "history-id";
        history.requestSnapshot = new HistoryRequestSnapshot();
        history.requestSnapshot.rawRequestSent = rawRequest;
        history.responseSnapshot = new HistoryResponseSnapshot();
        history.responseSnapshot.body = responseBody;
        history.ensureDefaults();
        WorkspaceState live = new WorkspaceState();
        live.historyEntries.add(history);

        WorkspaceState detached = WorkspaceState.copyOfSharingPersistencePayload(live);
        HistoryEntry detachedHistory = detached.historyEntries.get(0);

        assertThat(detachedHistory).isNotSameAs(history);
        assertThat(detachedHistory.requestSnapshot).isNotSameAs(history.requestSnapshot);
        assertThat(detachedHistory.requestSnapshot.rawRequestSent).isSameAs(rawRequest);
        assertThat(detachedHistory.responseSnapshot.body).isSameAs(responseBody);
        detachedHistory.requestSnapshot.urlTemplate = "changed";
        WorkspaceStateJson.serializeDetachedWithMetadataAndRelease(detached, 8 * 1024 * 1024);

        assertThat(detached.historyEntries).isEmpty();
        assertThat(history.requestSnapshot.urlTemplate).isNull();
        assertThat(history.requestSnapshot.rawRequestSent).isSameAs(rawRequest).isNotEmpty();
        assertThat(history.responseSnapshot.body).isSameAs(responseBody).containsExactly(1, 2, 3, 4);
    }
}
