package burp.history;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable, lightweight projection used by the History table and filters.
 * Display labels are bounded to 512 characters, folders to 2,048, URLs to
 * 8,192, methods to 32, and status/error summaries to 1,024. The entry ID is
 * kept exact because it is the stable boundary for loading a full record.
 */
public final class HistoryEntrySummary {
    public static final int LABEL_LIMIT = 512;
    public static final int FOLDER_LIMIT = 2_048;
    public static final int URL_LIMIT = 8_192;
    public static final int METHOD_LIMIT = 32;
    public static final int STATUS_SUMMARY_LIMIT = 1_024;

    private final String id;
    private final Instant timestamp;
    private final HistorySource source;
    private final String executionSource;
    private final String collectionName;
    private final String folderPath;
    private final String requestName;
    private final String method;
    private final String urlTemplate;
    private final int statusCode;
    private final HistoryResult result;
    private final String resultClassification;
    private final String environmentName;
    private final boolean pinned;
    private final boolean truncated;
    private final boolean hasError;
    private final int attemptNumber;
    private final int totalAttempts;
    private final long durationMillis;
    private final long requestSizeBytes;
    private final long responseSizeBytes;
    private final String statusSummary;

    private HistoryEntrySummary(HistoryEntry entry) {
        id = entry != null && entry.id != null ? entry.id : "";
        timestamp = entry != null ? entry.timestamp : null;
        source = entry != null && entry.source != null ? entry.source : HistorySource.WORKBENCH;
        executionSource = bounded(entry != null ? entry.executionSource : null, LABEL_LIMIT);
        collectionName = bounded(entry != null ? entry.collectionName : null, LABEL_LIMIT);
        folderPath = bounded(entry != null ? entry.folderPath : null, FOLDER_LIMIT);
        requestName = bounded(entry != null ? entry.requestName : null, LABEL_LIMIT);
        method = bounded(entry != null && entry.requestSnapshot != null ? entry.requestSnapshot.method : null, METHOD_LIMIT);
        urlTemplate = bounded(entry != null && entry.requestSnapshot != null ? entry.requestSnapshot.urlTemplate : null, URL_LIMIT);
        statusCode = entry != null ? entry.statusCode : -1;
        result = entry != null && entry.result != null ? entry.result : HistoryResult.UNKNOWN;
        resultClassification = bounded(entry != null ? entry.resultClassification : null, LABEL_LIMIT);
        environmentName = bounded(entry != null ? entry.environmentName : null, LABEL_LIMIT);
        pinned = entry != null && entry.pinned;
        truncated = entry != null && entry.hasTruncatedEvidence();
        hasError = entry != null && entry.hasError();
        attemptNumber = entry != null ? Math.max(1, entry.attemptNumber) : 1;
        totalAttempts = entry != null ? Math.max(attemptNumber, entry.totalAttempts) : 1;
        durationMillis = entry != null ? Math.max(0L, entry.durationMillis) : 0L;
        requestSizeBytes = entry != null ? Math.max(0L, entry.requestSizeBytes) : 0L;
        responseSizeBytes = entry != null ? Math.max(0L, entry.responseSizeBytes) : 0L;
        statusSummary = bounded(entry != null ? entry.errorMessage : null, STATUS_SUMMARY_LIMIT);
    }

    public static HistoryEntrySummary from(HistoryEntry entry) { return new HistoryEntrySummary(entry); }
    public String id() { return id; }
    public Instant timestamp() { return timestamp; }
    public HistorySource source() { return source; }
    public String executionSource() { return executionSource; }
    public String collectionName() { return collectionName; }
    public String folderPath() { return folderPath; }
    public String requestName() { return requestName; }
    public String method() { return method; }
    public String urlTemplate() { return urlTemplate; }
    public int statusCode() { return statusCode; }
    public HistoryResult result() { return result; }
    public String resultClassification() { return resultClassification; }
    public String environmentName() { return environmentName; }
    public boolean pinned() { return pinned; }
    public boolean truncated() { return truncated; }
    public boolean hasError() { return hasError; }
    public int attemptNumber() { return attemptNumber; }
    public int totalAttempts() { return totalAttempts; }
    public long durationMillis() { return durationMillis; }
    public long requestSizeBytes() { return requestSizeBytes; }
    public long responseSizeBytes() { return responseSizeBytes; }
    public String statusSummary() { return statusSummary; }

    public String attemptDisplay() {
        return totalAttempts > 1 ? attemptNumber + "/" + totalAttempts : String.valueOf(attemptNumber);
    }

    public String resultDisplayName() { return result.displayName(); }

    public String historySizeLabel() {
        long bytes = responseSizeBytes > 0 ? responseSizeBytes : requestSizeBytes;
        return bytes > 0 ? formatBytes(bytes) : "";
    }

    private static String bounded(String value, int limit) {
        String normalized = value != null ? value : "";
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int unitIndex = -1;
        while (value >= 1024.0d && unitIndex + 1 < units.length) {
            value /= 1024.0d;
            unitIndex++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unitIndex]);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof HistoryEntrySummary summary && Objects.equals(id, summary.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
