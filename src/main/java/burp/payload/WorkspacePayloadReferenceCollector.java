package burp.payload;

import burp.history.HistoryEntry;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspaceState;

import java.util.LinkedHashSet;
import java.util.Set;

public final class WorkspacePayloadReferenceCollector {
    private WorkspacePayloadReferenceCollector() {
    }

    public static Set<String> collect(WorkspaceState state) {
        Set<String> ids = new LinkedHashSet<>();
        if (state == null) return ids;
        if (state.collections != null) {
            for (ApiCollection collection : state.collections) {
                if (collection == null || collection.requests == null) continue;
                for (ApiRequest request : collection.requests) collectRequest(ids, request);
            }
        }
        if (state.historyEntries != null) {
            for (HistoryEntry entry : state.historyEntries) {
                if (entry == null || entry.requestSnapshot == null) continue;
                add(ids, entry.requestSnapshot.authoredExactPayloadRef);
                if (entry.requestSnapshot.authoredRequest != null) {
                    collectRequest(ids, entry.requestSnapshot.authoredRequest);
                }
            }
        }
        return ids;
    }

    private static void collectRequest(Set<String> ids, ApiRequest request) {
        if (request == null) return;
        if (request.exactHttpRequest != null) add(ids, request.exactHttpRequest.payloadRef);
        if (request.body != null && request.body.managedPayload != null) {
            add(ids, request.body.managedPayload.payload);
        }
    }

    private static void add(Set<String> ids, ManagedPayloadRef ref) {
        if (ref != null && ManagedPayloadRef.isSha256(ref.payloadId)) ids.add(ref.payloadId);
    }
}
