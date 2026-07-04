package burp.history;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public class HistoryFilterCriteria {
    public String freeText;
    public HistorySource source;
    public String method;
    public String statusClass;
    public Integer exactStatusCode;
    public String collection;
    public String folder;
    public String requestName;
    public String environment;
    public HistoryResult resultType;
    public Instant fromTimestamp;
    public Instant toTimestamp;
    public Boolean hasResponseBody;
    public Boolean hasError;
    public Boolean hasAssertionFailure;
    public String pinnedState;
    public String tagText;
    public Integer attemptNumber;
    public Integer totalAttempts;
    public Boolean retriesOnly;

    public static HistoryFilterCriteria copyOf(HistoryFilterCriteria source) {
        if (source == null) {
            return new HistoryFilterCriteria();
        }
        HistoryFilterCriteria copy = new HistoryFilterCriteria();
        copy.freeText = source.freeText;
        copy.source = source.source;
        copy.method = source.method;
        copy.statusClass = source.statusClass;
        copy.exactStatusCode = source.exactStatusCode;
        copy.collection = source.collection;
        copy.folder = source.folder;
        copy.requestName = source.requestName;
        copy.environment = source.environment;
        copy.resultType = source.resultType;
        copy.fromTimestamp = source.fromTimestamp;
        copy.toTimestamp = source.toTimestamp;
        copy.hasResponseBody = source.hasResponseBody;
        copy.hasError = source.hasError;
        copy.hasAssertionFailure = source.hasAssertionFailure;
        copy.pinnedState = source.pinnedState;
        copy.tagText = source.tagText;
        copy.attemptNumber = source.attemptNumber;
        copy.totalAttempts = source.totalAttempts;
        copy.retriesOnly = source.retriesOnly;
        return copy;
    }

    public boolean matches(HistoryEntry entry) {
        if (entry == null) {
            return false;
        }
        if (source != null && entry.source != source) {
            return false;
        }
        if (method != null && !method.isBlank() && !matchesText(entry.requestSnapshot != null ? entry.requestSnapshot.method : null, method)) {
            return false;
        }
        if (exactStatusCode != null && entry.statusCode != exactStatusCode) {
            return false;
        }
        if (statusClass != null && !statusClass.isBlank() && !matchesStatusClass(entry.statusCode, statusClass)) {
            return false;
        }
        if (collection != null && !collection.isBlank() && !containsIgnoreCase(entry.collectionName, collection)) {
            return false;
        }
        if (folder != null && !folder.isBlank() && !containsIgnoreCase(entry.folderPath, folder)) {
            return false;
        }
        if (requestName != null && !requestName.isBlank() && !containsIgnoreCase(entry.requestName, requestName)) {
            return false;
        }
        if (environment != null && !environment.isBlank() && !containsIgnoreCase(entry.environmentName, environment)) {
            return false;
        }
        if (resultType != null && entry.result != resultType) {
            return false;
        }
        if (fromTimestamp != null && entry.timestamp != null && entry.timestamp.isBefore(fromTimestamp)) {
            return false;
        }
        if (toTimestamp != null && entry.timestamp != null && entry.timestamp.isAfter(toTimestamp)) {
            return false;
        }
        if (hasResponseBody != null && entry.hasResponseBody() != hasResponseBody) {
            return false;
        }
        if (hasError != null && entry.hasError() != hasError) {
            return false;
        }
        if (hasAssertionFailure != null && entry.hasAssertionFailure() != hasAssertionFailure) {
            return false;
        }
        if (!matchesPinnedState(entry, pinnedState)) {
            return false;
        }
        if (tagText != null && !tagText.isBlank() && !matchesTagText(entry, tagText)) {
            return false;
        }
        if (attemptNumber != null && entry.attemptNumber != attemptNumber) {
            return false;
        }
        if (totalAttempts != null && entry.totalAttempts != totalAttempts) {
            return false;
        }
        if (retriesOnly != null) {
            boolean retried = entry.totalAttempts > 1 || entry.attemptNumber > 1;
            if (retriesOnly != retried) {
                return false;
            }
        }
        if (freeText != null && !freeText.isBlank() && !matchesFreeText(entry, freeText)) {
            return false;
        }
        return true;
    }

    private static boolean matchesText(String value, String filter) {
        return containsIgnoreCase(value, filter);
    }

    private static boolean containsIgnoreCase(String value, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }

    private static boolean matchesStatusClass(int statusCode, String statusClass) {
        if (statusClass == null) {
            return true;
        }
        String normalized = statusClass.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "1xx" -> statusCode >= 100 && statusCode < 200;
            case "2xx" -> statusCode >= 200 && statusCode < 300;
            case "3xx" -> statusCode >= 300 && statusCode < 400;
            case "4xx" -> statusCode >= 400 && statusCode < 500;
            case "5xx" -> statusCode >= 500 && statusCode < 600;
            case "err", "error" -> statusCode <= 0;
            default -> Objects.equals(normalized, String.valueOf(statusCode));
        };
    }

    private static boolean matchesFreeText(HistoryEntry entry, String filter) {
        return containsIgnoreCase(entry.id, filter)
                || containsIgnoreCase(entry.collectionName, filter)
                || containsIgnoreCase(entry.folderPath, filter)
                || containsIgnoreCase(entry.requestName, filter)
                || containsIgnoreCase(entry.environmentName, filter)
                || containsIgnoreCase(entry.errorMessage, filter)
                || containsIgnoreCase(entry.resultDisplayName(), filter)
                || containsIgnoreCase(entry.attemptDisplay(), filter)
                || containsIgnoreCase(String.valueOf(entry.statusCode), filter)
                || containsIgnoreCase(entry.requestSnapshot != null ? entry.requestSnapshot.method : null, filter)
                || containsIgnoreCase(entry.requestSnapshot != null ? entry.requestSnapshot.urlTemplate : null, filter)
                || containsIgnoreCase(entry.requestSnapshot != null ? entry.requestSnapshot.displayBodyText() : null, filter)
                || containsIgnoreCase(entry.responseSnapshot != null ? entry.responseSnapshot.bodyAsText() : null, filter)
                || containsIgnoreCase(entry.analystNotes, filter)
                || containsIgnoreCase(joinTags(entry), filter)
                || containsIgnoreCase(entry.toMetadataText(), filter);
    }

    private static boolean matchesPinnedState(HistoryEntry entry, String pinnedState) {
        if (pinnedState == null || pinnedState.isBlank()) {
            return true;
        }
        String normalized = pinnedState.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "pinned" -> entry.pinned;
            case "unpinned" -> !entry.pinned;
            default -> true;
        };
    }

    private static boolean matchesTagText(HistoryEntry entry, String tagFilter) {
        if (entry == null || tagFilter == null || tagFilter.isBlank()) {
            return true;
        }
        String normalizedFilter = tagFilter.toLowerCase(Locale.ROOT);
        if (entry.tags == null || entry.tags.isEmpty()) {
            return false;
        }
        for (String tag : entry.tags) {
            if (tag != null && tag.toLowerCase(Locale.ROOT).contains(normalizedFilter)) {
                return true;
            }
        }
        return false;
    }

    private static String joinTags(HistoryEntry entry) {
        if (entry == null || entry.tags == null || entry.tags.isEmpty()) {
            return "";
        }
        return String.join(", ", entry.tags);
    }
}
