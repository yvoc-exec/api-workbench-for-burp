package burp.ui;

import burp.history.HistoryEntry;
import burp.models.RedirectHop;
import burp.models.RunnerCancellationState;
import burp.models.RunnerResult;
import burp.models.RunnerResultSummary;
import burp.models.RunnerTimelineRow;
import burp.utils.ExecutionPreflightStatus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class RunnerExecutionTableModel extends RunnerResultTableModel {
    public static final int DEFAULT_MAX_ROWS = 5_000;
    public static final int MIN_MAX_ROWS = 100;
    public static final int MAX_MAX_ROWS = 20_000;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withLocale(Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final List<Entry> rows = new ArrayList<>();
    private final String[] columns = {"#", "Time", "Type", "State", "Request", "Source", "Method", "Status", "Attempt", "Kind", "Retry Reason", "Cancellation", "Result", "Duration", "Flow", "Message"};
    private final int maxRows;
    private long removedCompletedRowCount;

    public RunnerExecutionTableModel() {
        this(DEFAULT_MAX_ROWS);
    }

    RunnerExecutionTableModel(int maxRows) {
        this.maxRows = Math.max(MIN_MAX_ROWS, Math.min(MAX_MAX_ROWS, maxRows));
    }

    @Override
    public void addResult(RunnerResult result) {
        addSummary(RunnerResultSummary.from(result));
    }

    @Override
    public void addSummary(RunnerResultSummary summary) {
        addEntry(fromRequestSummary(summary));
    }

    public Entry addEntry(Entry entry) {
        if (entry == null) {
            return null;
        }
        completeMatchingRequestStart(entry);
        rows.add(entry);
        compactCompletedRows();
        fireTableDataChanged();
        return entry;
    }

    @Override
    public void clear() {
        rows.clear();
        removedCompletedRowCount = 0L;
        fireTableDataChanged();
    }

    public Entry getEntryAt(int row) { return rows.get(row); }
    public List<Entry> getEntries() { return new ArrayList<>(rows); }

    @Override
    public List<RunnerResult> getResults() {
        return getRequestSummaries().stream().map(RunnerResultSummary::toCompatibilityResult).toList();
    }

    @Override
    public RunnerResult getResultAt(int row) {
        RunnerResultSummary summary = getSummaryAt(row);
        return summary != null ? summary.toCompatibilityResult() : null;
    }

    @Override
    public RunnerResultSummary getSummaryAt(int row) {
        Entry entry = rows.get(row);
        return entry != null ? entry.requestSummary : null;
    }

    @Override
    public List<RunnerResultSummary> getSummaries() { return getRequestSummaries(); }

    public List<RunnerResult> getRequestResults() { return getResults(); }

    public List<RunnerResultSummary> getRequestSummaries() {
        List<RunnerResultSummary> summaries = new ArrayList<>();
        for (Entry row : rows) {
            if (row != null && row.requestSummary != null && !"REQUEST_STARTED".equals(row.type)) {
                summaries.add(row.requestSummary);
            }
        }
        return List.copyOf(summaries);
    }

    public int getRequestResultCount() { return getRequestSummaries().size(); }

    public RunnerResultSummary findLatestByRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        for (int i = rows.size() - 1; i >= 0; i--) {
            RunnerResultSummary summary = rows.get(i).requestSummary;
            if (summary != null && requestId.equals(summary.requestId())) {
                return summary;
            }
        }
        return null;
    }

    public RunnerResultSummary findLatestByRequestName(String collectionName, String requestName) {
        if (requestName == null || requestName.isBlank()) {
            return null;
        }
        for (int i = rows.size() - 1; i >= 0; i--) {
            RunnerResultSummary summary = rows.get(i).requestSummary;
            if (summary == null || !requestName.equals(summary.requestName())) {
                continue;
            }
            if (collectionName == null || collectionName.isBlank()
                    || Objects.equals(collectionName, summary.collectionName())) {
                return summary;
            }
        }
        return null;
    }

    public long getRemovedCompletedRowCount() { return removedCompletedRowCount; }
    int getMaxRows() { return maxRows; }

    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return columns.length; }
    @Override public String getColumnName(int column) { return columns[column]; }
    @Override public Class<?> getColumnClass(int column) { return String.class; }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        Entry row = rows.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> row.sequence > 0 ? row.sequence : rowIndex + 1;
            case 1 -> row.formatTime();
            case 2 -> value(row.type);
            case 3 -> value(row.state);
            case 4 -> value(row.requestName);
            case 5 -> value(row.source);
            case 6 -> value(row.method);
            case 7 -> value(row.status);
            case 8 -> value(row.attempt);
            case 9 -> value(row.kind);
            case 10 -> value(row.retryReason);
            case 11 -> value(row.cancellationState);
            case 12 -> value(row.result);
            case 13 -> value(row.duration);
            case 14 -> value(row.flow);
            case 15 -> value(row.message);
            default -> "";
        };
    }

    public static Entry fromRequestResult(RunnerResult result) {
        return fromRequestSummary(RunnerResultSummary.from(result));
    }

    public static Entry fromRequestSummary(RunnerResultSummary summary) {
        if (summary == null) {
            return null;
        }
        String status = summary.displayStatusLabel();
        Entry entry = new Entry(
                0, Instant.now(), "REQUEST_COMPLETED", summary.success() ? "COMPLETED" : "FAILED",
                summary.requestName(), summary.collectionName(), summary.method(),
                summary.statusCode() > 0 ? String.valueOf(summary.statusCode()) : "",
                summary.displayLogStatusLabel(), summary.responseTimeMs() > 0 ? summary.responseTimeMs() + " ms" : "",
                summary.scriptFlowControl() != null ? summary.scriptFlowControl().name() : "",
                summary.errorMessage() != null && !summary.errorMessage().isBlank() ? summary.errorMessage() : status,
                summary, summary.historyEntryId(), null, null, null,
                summary.requestId(), summary.collectionName(), true, true);
        entry.attempt = summary.attemptNumber() + "/" + summary.totalAttempts();
        entry.kind = executionKind(summary);
        entry.retryReason = summary.retryReason();
        entry.cancellationState = summary.cancellationState() != null ? summary.cancellationState().name() : "";
        return entry;
    }

    public static Entry fromRedirectHop(RunnerResult parent, RedirectHop hop) {
        RunnerResultSummary parentSummary = RunnerResultSummary.from(parent);
        if (parentSummary == null || hop == null) {
            return null;
        }
        RunnerResultSummary.RedirectSummary redirect = null;
        int index = 0;
        for (int i = 0; i < parentSummary.redirectSummaries().size(); i++) {
            RunnerResultSummary.RedirectSummary candidate = parentSummary.redirectSummaries().get(i);
            if (candidate.hopNumber() == hop.hopNumber) {
                redirect = candidate;
                index = i;
                break;
            }
        }
        return fromRedirectSummary(parentSummary, redirect, index);
    }

    public static Entry fromRedirectSummary(RunnerResultSummary parent,
                                            RunnerResultSummary.RedirectSummary hop,
                                            int hopIndex) {
        if (hop == null) {
            return null;
        }
        StringBuilder message = new StringBuilder();
        append(message, hop.sourceUrl());
        if (hop.targetUrl() != null && !hop.targetUrl().isBlank()) {
            if (message.length() > 0) message.append(" -> ");
            message.append(hop.targetUrl());
        }
        appendDelimited(message, hop.failureReason());
        if (!hop.forwardedSensitiveHeaderNames().isEmpty()) {
            appendDelimited(message, "forwarded=" + hop.forwardedSensitiveHeaderNames());
        }
        if (!hop.strippedSensitiveHeaderNames().isEmpty()) {
            appendDelimited(message, "stripped=" + hop.strippedSensitiveHeaderNames());
        }
        return new Entry(
                0, Instant.now(), hop.followed() ? "REDIRECT_HOP" : "REDIRECT_BLOCKED",
                hop.followed() ? "FOLLOWED" : "BLOCKED",
                "\u21b3 Redirect hop " + (hop.hopNumber() > 0 ? hop.hopNumber() : "?"),
                parent != null ? parent.collectionName() : "", hop.sourceMethod(),
                hop.statusCode() > 0 ? String.valueOf(hop.statusCode()) : "",
                hop.followed() ? "FOLLOWED" : "BLOCKED",
                hop.elapsedMs() > 0 ? hop.elapsedMs() + " ms" : "", "", message.toString(),
                null, parent != null ? parent.historyEntryId() : null, hopIndex, hop, null,
                parent != null ? parent.requestId() : null,
                parent != null ? parent.collectionName() : "", true, true);
    }

    private void completeMatchingRequestStart(Entry terminal) {
        if (terminal == null || terminal.requestSummary == null || "REQUEST_STARTED".equals(terminal.type)) {
            return;
        }
        for (int i = rows.size() - 1; i >= 0; i--) {
            Entry candidate = rows.get(i);
            if (candidate != null && "REQUEST_STARTED".equals(candidate.type)
                    && !candidate.lifecycleComplete
                    && sameRequest(candidate, terminal)) {
                candidate.lifecycleComplete = true;
                candidate.evictionEligible = true;
                break;
            }
        }
    }

    private static boolean sameRequest(Entry first, Entry second) {
        if (first.requestId != null && second.requestId != null) {
            return first.requestId.equals(second.requestId);
        }
        return Objects.equals(first.collectionName, second.collectionName)
                && Objects.equals(first.requestName, second.requestName);
    }

    private void compactCompletedRows() {
        while (rows.size() > maxRows) {
            int removable = -1;
            for (int i = 0; i < rows.size(); i++) {
                Entry row = rows.get(i);
                if (row != null && row.lifecycleComplete && row.evictionEligible) {
                    removable = i;
                    break;
                }
            }
            if (removable < 0) {
                return;
            }
            rows.remove(removable);
            removedCompletedRowCount++;
        }
    }

    private static String executionKind(RunnerResultSummary result) {
        if (result.cancellationState() != RunnerCancellationState.NOT_CANCELLED) return "CANCELLED";
        if (result.preflightStatus() == ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR
                || result.preflightStatus() == ExecutionPreflightStatus.BLOCKED_SCRIPT_TIMEOUT
                || result.preflightStatus() == ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE
                || result.preflightStatus() == ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES
                || result.preflightStatus() == ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE
                || result.preflightStatus() == ExecutionPreflightStatus.BLOCKED_POLICY) return "PREFLIGHT_BLOCKED";
        if (result.responseTimedOut()) return "TIMED_OUT";
        if (result.adHocExecution()) return "AD_HOC";
        if (result.dependentExecution()) return "DEPENDENT";
        if (result.attemptNumber() > 1) return "RETRY";
        return "QUEUED";
    }

    private static void append(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) builder.append(value);
    }

    private static void appendDelimited(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) return;
        if (builder.length() > 0) builder.append(" | ");
        builder.append(value);
    }

    private static String value(String value) { return value != null ? value : ""; }

    public static final class Entry {
        public final int sequence;
        public final Instant timestamp;
        public final String type;
        public final String state;
        public final String requestName;
        public final String source;
        public final String method;
        public final String status;
        public final String result;
        public final String duration;
        public final String flow;
        public final String message;
        public final RunnerResultSummary requestSummary;
        public final String historyEntryId;
        public final Integer redirectHopIndex;
        public final RunnerResultSummary.RedirectSummary redirectSummary;
        public final RunnerTimelineRow timelineRow;
        public final String requestId;
        public final String collectionName;
        public boolean lifecycleComplete;
        public boolean evictionEligible;
        public String attempt;
        public String kind;
        public String retryReason;
        public String cancellationState;

        public Entry(int sequence, Instant timestamp, String type, String state,
                     String requestName, String source, String method, String status,
                     String result, String duration, String flow, String message,
                     HistoryEntry detailEntry, RunnerResult requestResult,
                     RunnerTimelineRow timelineRow, String requestId, String collectionName) {
            this(sequence, timestamp, type, state, requestName, source, method, status,
                    result, duration, flow, message, RunnerResultSummary.from(requestResult),
                    requestResult != null ? requestResult.historyEntryId
                            : detailEntry != null ? detailEntry.id : null,
                    null, null, timelineRow, requestId, collectionName,
                    !"REQUEST_STARTED".equals(type), !"REQUEST_STARTED".equals(type));
        }

        public Entry(int sequence, Instant timestamp, String type, String state,
                     String requestName, String source, String method, String status,
                     String result, String duration, String flow, String message,
                     RunnerResultSummary requestSummary, String historyEntryId,
                     Integer redirectHopIndex, RunnerResultSummary.RedirectSummary redirectSummary,
                     RunnerTimelineRow timelineRow, String requestId, String collectionName,
                     boolean lifecycleComplete, boolean evictionEligible) {
            this.sequence = sequence;
            this.timestamp = timestamp;
            this.type = type;
            this.state = state;
            this.requestName = requestName;
            this.source = source;
            this.method = method;
            this.status = status;
            this.result = result;
            this.duration = duration;
            this.flow = flow;
            this.message = message;
            this.requestSummary = requestSummary;
            this.historyEntryId = historyEntryId;
            this.redirectHopIndex = redirectHopIndex;
            this.redirectSummary = redirectSummary;
            this.timelineRow = timelineRow;
            this.requestId = requestId;
            this.collectionName = collectionName;
            this.lifecycleComplete = lifecycleComplete;
            this.evictionEligible = evictionEligible;
        }

        public String formatTime() { return timestamp != null ? TIME_FORMAT.format(timestamp) : ""; }
    }
}
