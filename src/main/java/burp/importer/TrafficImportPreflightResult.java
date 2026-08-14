package burp.importer;

import java.util.ArrayList;
import java.util.List;

/** Payload-free traffic admission result safe for UI and diagnostics. */
public final class TrafficImportPreflightResult {
    public enum ReasonCode {
        MISSING_RAW_REQUEST,
        EXACT_REQUEST_ITEM_LIMIT,
        EXACT_REQUEST_AGGREGATE_LIMIT,
        LENGTH_OVERFLOW,
        MALFORMED_HTTP_REQUEST,
        WORKSPACE_PERSISTENCE_LIMIT
    }

    public record Rejection(
            int encounterIndex,
            ReasonCode reasonCode,
            long offendingByteCount,
            long applicableLimit,
            String safeMessage) {
        public Rejection {
            reasonCode = reasonCode != null ? reasonCode : ReasonCode.MALFORMED_HTTP_REQUEST;
            safeMessage = safeMessage != null ? safeMessage : "Traffic import was rejected.";
        }
    }

    private final int selectionCount;
    private final int acceptedCount;
    private final long totalExactRequestBytes;
    private final long totalResponseBytes;
    private final long configuredPerItemLimit;
    private final long configuredAggregateLimit;
    private final List<Rejection> rejections;

    public TrafficImportPreflightResult(int selectionCount,
                                        int acceptedCount,
                                        long totalExactRequestBytes,
                                        long totalResponseBytes,
                                        long configuredPerItemLimit,
                                        long configuredAggregateLimit,
                                        List<Rejection> rejections) {
        this.selectionCount = Math.max(0, selectionCount);
        this.acceptedCount = Math.max(0, acceptedCount);
        this.totalExactRequestBytes = Math.max(0L, totalExactRequestBytes);
        this.totalResponseBytes = Math.max(0L, totalResponseBytes);
        this.configuredPerItemLimit = Math.max(0L, configuredPerItemLimit);
        this.configuredAggregateLimit = Math.max(0L, configuredAggregateLimit);
        this.rejections = rejections != null ? List.copyOf(rejections) : List.of();
    }

    public int selectionCount() { return selectionCount; }
    public int acceptedCount() { return acceptedCount; }
    public long totalExactRequestBytes() { return totalExactRequestBytes; }
    public long totalResponseBytes() { return totalResponseBytes; }
    public long configuredPerItemLimit() { return configuredPerItemLimit; }
    public long configuredAggregateLimit() { return configuredAggregateLimit; }
    public List<Rejection> rejections() { return rejections; }
    public boolean accepted() { return rejections.isEmpty() && acceptedCount == selectionCount; }

    public TrafficImportPreflightResult withRejection(Rejection rejection) {
        List<Rejection> copy = new ArrayList<>(rejections);
        if (rejection != null) {
            copy.add(rejection);
        }
        return new TrafficImportPreflightResult(
                selectionCount,
                acceptedCount,
                totalExactRequestBytes,
                totalResponseBytes,
                configuredPerItemLimit,
                configuredAggregateLimit,
                copy);
    }
}
