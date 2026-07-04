package burp.importer;

import burp.history.HistoryEntry;
import burp.models.ApiRequest;

import java.util.ArrayList;
import java.util.List;

public final class BurpTrafficConversionResult {
    public final List<ApiRequest> requests = new ArrayList<>();
    public final List<HistoryEntry> historyEntries = new ArrayList<>();
    public final List<Failure> failures = new ArrayList<>();

    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    public static final class Failure {
        public final int encounterIndex;
        public final String reasonCode;
        public final String safeMessage;

        public Failure(int encounterIndex, String reasonCode, String safeMessage) {
            this.encounterIndex = encounterIndex;
            this.reasonCode = reasonCode != null ? reasonCode : "";
            this.safeMessage = safeMessage != null ? safeMessage : "";
        }
    }
}
