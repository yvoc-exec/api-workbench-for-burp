package burp.importer;

import burp.history.HistoryEntry;
import burp.models.ApiCollection;
import burp.models.ApiRequest;

import java.util.ArrayList;
import java.util.List;

public final class BurpTrafficImportPlan {
    public final ApiCollection existingDestinationCollection;
    public final String newCollectionName;
    public final String destinationFolder;
    public final List<ApiRequest> requests;
    public final List<HistoryEntry> historyEntries;
    public final boolean preserveExactTransport;
    public final boolean captureResponses;
    public final boolean queueInRunner;

    public BurpTrafficImportPlan(ApiCollection existingDestinationCollection,
                                 String newCollectionName,
                                 String destinationFolder,
                                 List<ApiRequest> requests,
                                 List<HistoryEntry> historyEntries,
                                 boolean preserveExactTransport,
                                 boolean captureResponses,
                                 boolean queueInRunner) {
        this.existingDestinationCollection = existingDestinationCollection;
        this.newCollectionName = newCollectionName != null ? newCollectionName : "";
        this.destinationFolder = destinationFolder != null ? destinationFolder : "";
        this.requests = requests != null ? new ArrayList<>(requests) : new ArrayList<>();
        this.historyEntries = historyEntries != null ? new ArrayList<>(historyEntries) : new ArrayList<>();
        this.preserveExactTransport = preserveExactTransport;
        this.captureResponses = captureResponses;
        this.queueInRunner = queueInRunner;
    }

    public boolean createsCollection() {
        return existingDestinationCollection == null;
    }

    public int requestCount() {
        return requests.size();
    }

    public int historyCount() {
        return captureResponses ? historyEntries.size() : 0;
    }
}
