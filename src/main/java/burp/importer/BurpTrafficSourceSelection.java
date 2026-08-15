package burp.importer;

/** Lightweight Burp message reference captured without touching message bytes. */
public final class BurpTrafficSourceSelection {
    public final Object requestResponse;
    public final String sourceContext;
    public final int encounterIndex;

    public BurpTrafficSourceSelection(Object requestResponse, String sourceContext, int encounterIndex) {
        this.requestResponse = requestResponse;
        this.sourceContext = sourceContext != null ? sourceContext : "Burp";
        this.encounterIndex = encounterIndex;
    }
}
