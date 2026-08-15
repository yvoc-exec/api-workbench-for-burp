package burp.payload;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.models.WorkspaceState;
import burp.history.HistoryEntry;
import burp.history.HistoryRequestSnapshot;
import burp.parser.HistoryRawHttpMessageParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Migrates detached v2 inline exact transport into content-addressed v3 refs. */
public final class ExactPayloadMigrationService {
    private final ManagedPayloadStore store;

    public ExactPayloadMigrationService(ManagedPayloadStore store) {
        if (store == null) throw new IllegalArgumentException("Managed payload store is required.");
        this.store = store;
    }

    public int migrateDetached(WorkspaceState state) throws IOException {
        if (state == null) return 0;
        List<PendingMigration> pending = new ArrayList<>();
        List<PendingHistoryMigration> pendingHistory = new ArrayList<>();
        try {
            for (ApiCollection collection : state.collections != null
                    ? state.collections : List.<ApiCollection>of()) {
                if (collection == null || collection.requests == null) continue;
                for (ApiRequest request : collection.requests) {
                    if (request == null || request.exactHttpRequest == null) continue;
                    ExactHttpRequestSnapshot exact = request.exactHttpRequest;
                    if (exact.payloadRef != null || exact.rawRequestBytes == null || exact.rawRequestBytes.length == 0) continue;
                    ManagedPayloadRef ref;
                    try (ManagedPayloadStage stage = store.beginStage("legacy-exact-migration")) {
                        stage.write(exact.rawRequestBytes);
                        ref = store.commit(stage);
                    }
                    HistoryRawHttpMessageParser.RequestLayout layout =
                            HistoryRawHttpMessageParser.inspectRequest(exact.rawRequestBytes);
                    long bodyOffset = Math.max(0, layout.bodyOffset());
                    long bodyLength = Math.max(0L, ref.length - bodyOffset);
                    pending.add(new PendingMigration(request, ref,
                            bodyLength > 0L ? new PayloadSliceRef(ref, bodyOffset, bodyLength) : null));
                }
            }
            if (state.historyEntries != null) {
                for (HistoryEntry entry : state.historyEntries) {
                    HistoryRequestSnapshot snapshot = entry != null ? entry.requestSnapshot : null;
                    if (snapshot == null || snapshot.authoredExactPayloadRef != null) continue;
                    byte[] raw = snapshot.authoredExactRequestBytes;
                    if ((raw == null || raw.length == 0) && snapshot.authoredRequest != null
                            && snapshot.authoredRequest.exactHttpRequest != null) {
                        raw = snapshot.authoredRequest.exactHttpRequest.rawRequestBytes;
                    }
                    if (raw == null || raw.length == 0) continue;
                    ManagedPayloadRef ref;
                    try (ManagedPayloadStage stage = store.beginStage("legacy-history-exact-migration")) {
                        stage.write(raw);
                        ref = store.commit(stage);
                    }
                    HistoryRawHttpMessageParser.RequestLayout layout =
                            HistoryRawHttpMessageParser.inspectRequest(raw);
                    long bodyOffset = Math.max(0, layout.bodyOffset());
                    long bodyLength = Math.max(0L, ref.length - bodyOffset);
                    pendingHistory.add(new PendingHistoryMigration(snapshot, ref,
                            bodyLength > 0L ? new PayloadSliceRef(ref, bodyOffset, bodyLength) : null,
                            snapshot.rawRequestSent != null
                                    && java.util.Arrays.equals(snapshot.rawRequestSent, raw)));
                }
            }
            for (PendingMigration migration : pending) {
                migration.request.exactHttpRequest.payloadRef = migration.payload.copy();
                migration.request.exactHttpRequest.rawRequestBytes = null;
                if (migration.request.body == null) migration.request.body = new ApiRequest.Body();
                migration.request.body.managedPayload = migration.body != null ? migration.body.copy() : null;
                if (migration.body != null) migration.request.body.raw = null;
            }
            for (PendingHistoryMigration migration : pendingHistory) {
                HistoryRequestSnapshot snapshot = migration.snapshot;
                snapshot.authoredExactPayloadRef = migration.payload.copy();
                snapshot.authoredExactRequestBytes = null;
                if (snapshot.authoredRequest != null) {
                    if (snapshot.authoredRequest.exactHttpRequest != null) {
                        snapshot.authoredRequest.exactHttpRequest.rawRequestBytes = null;
                        snapshot.authoredRequest.exactHttpRequest.payloadRef = null;
                    }
                    if (snapshot.authoredRequest.body == null && migration.body != null) {
                        snapshot.authoredRequest.body = new ApiRequest.Body();
                        snapshot.authoredRequest.body.mode = "raw";
                    }
                    if (snapshot.authoredRequest.body != null && migration.body != null) {
                        snapshot.authoredRequest.body.managedPayload = migration.body.copy();
                        snapshot.authoredRequest.body.raw = null;
                    }
                }
                if (migration.rawSentSame) {
                    snapshot.rawRequestSent = null;
                    snapshot.rawRequestSentUsesAuthoredExactPayload = true;
                }
                snapshot.canonicalizeExactTransportOwnership();
            }
            state.version = WorkspaceState.CURRENT_VERSION;
            return pending.size() + pendingHistory.size();
        } catch (IOException | RuntimeException failure) {
            throw failure;
        }
    }

    private record PendingMigration(ApiRequest request, ManagedPayloadRef payload, PayloadSliceRef body) {
    }

    private record PendingHistoryMigration(HistoryRequestSnapshot snapshot,
                                           ManagedPayloadRef payload,
                                           PayloadSliceRef body,
                                           boolean rawSentSame) {
    }
}
