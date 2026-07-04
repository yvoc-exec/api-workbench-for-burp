package burp.ui;

import burp.history.HistoryEntry;
import burp.models.RedirectHop;
import burp.models.RunnerResult;
import burp.models.RunnerTimelineRow;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RunnerExecutionTableModel extends RunnerResultTableModel {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withLocale(Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final List<Entry> rows = new ArrayList<>();
    private final String[] columns = {"#", "Time", "Type", "State", "Request", "Source", "Method", "Status", "Attempt", "Kind", "Retry Reason", "Cancellation", "Result", "Duration", "Flow", "Message"};

    @Override
    public void addResult(RunnerResult result) {
        addEntry(fromRequestResult(result));
    }

    public Entry addEntry(Entry entry) {
        if (entry == null) {
            return null;
        }
        rows.add(entry);
        int index = rows.size() - 1;
        fireTableRowsInserted(index, index);
        return entry;
    }

    public void clear() {
        rows.clear();
        fireTableDataChanged();
    }

    public Entry getEntryAt(int row) {
        return rows.get(row);
    }

    public List<Entry> getEntries() {
        return new ArrayList<>(rows);
    }

    @Override
    public List<RunnerResult> getResults() {
        List<RunnerResult> results = new ArrayList<>();
        for (Entry row : rows) {
            if (row != null && row.requestResult != null) {
                results.add(row.requestResult);
            }
        }
        return results;
    }

    @Override
    public RunnerResult getResultAt(int row) {
        Entry entry = rows.get(row);
        return entry != null ? entry.requestResult : null;
    }

    public List<RunnerResult> getRequestResults() {
        return getResults();
    }

    public int getRequestResultCount() {
        int count = 0;
        for (Entry row : rows) {
            if (row != null && row.requestResult != null) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public String getColumnName(int column) {
        return columns[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return String.class;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        Entry row = rows.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> row.sequence > 0 ? row.sequence : rowIndex + 1;
            case 1 -> row.formatTime();
            case 2 -> row.type != null ? row.type : "";
            case 3 -> row.state != null ? row.state : "";
            case 4 -> row.requestName != null ? row.requestName : "";
            case 5 -> row.source != null ? row.source : "";
            case 6 -> row.method != null ? row.method : "";
            case 7 -> row.status != null ? row.status : "";
            case 8 -> row.attempt != null ? row.attempt : "";
            case 9 -> row.kind != null ? row.kind : "";
            case 10 -> row.retryReason != null ? row.retryReason : "";
            case 11 -> row.cancellationState != null ? row.cancellationState : "";
            case 12 -> row.result != null ? row.result : "";
            case 13 -> row.duration != null ? row.duration : "";
            case 14 -> row.flow != null ? row.flow : "";
            case 15 -> row.message != null ? row.message : "";
            default -> "";
        };
    }

    public static Entry fromRequestResult(RunnerResult result) {
        if (result == null) {
            return null;
        }
        String status = result.displayStatusLabel();
        String resultLabel = result.displayLogStatusLabel();
        String duration = result.responseTimeMs > 0 ? result.responseTimeMs + " ms" : "";
        String flow = result.scriptFlowControl != null ? result.scriptFlowControl.name() : "";
        String message = result.errorMessage != null && !result.errorMessage.isBlank()
                ? result.errorMessage
                : status;
        HistoryEntry detailEntry = HistoryEntry.fromRunnerAttempt(null, null, null, result);
        if (detailEntry != null) {
            detailEntry.requestName = result.requestName;
            detailEntry.requestId = result.requestId;
            detailEntry.collectionName = result.collectionName;
            detailEntry.collectionId = result.collectionId != null ? result.collectionId : detailEntry.collectionId;
            detailEntry.folderPath = result.folderPath;
            detailEntry.initialResolvedUrl = result.initialResolvedUrl != null ? result.initialResolvedUrl : result.requestUrl;
            detailEntry.finalResolvedUrl = result.finalResolvedUrl != null ? result.finalResolvedUrl : result.requestUrl;
            detailEntry.redirectsEnabled = result.redirectsEnabled;
            detailEntry.redirectTerminationReason = result.redirectTerminationReason;
            detailEntry.redirectHops = copyRedirectHops(result.redirectHops);
            detailEntry.host = result.host;
            detailEntry.scriptMode = result.scriptEngineName;
            detailEntry.scriptDialect = result.scriptEngineName;
            detailEntry.resultClassification = detailEntry.result != null ? detailEntry.result.displayName() : null;
        }
        Entry entry = new Entry(
                0,
                Instant.now(),
                "REQUEST_COMPLETED",
                result.success ? "COMPLETED" : "FAILED",
                result.requestName,
                result.collectionName,
                result.method,
                result.statusCode > 0 ? String.valueOf(result.statusCode) : "",
                resultLabel,
                duration,
                flow,
                message,
                detailEntry,
                result,
                null,
                result.requestId,
                result.collectionName
        );
        entry.attempt = result.attemptNumber + "/" + result.totalAttempts;
        entry.kind = executionKind(result);
        entry.retryReason = result.retryReason;
        entry.cancellationState = result.cancellationState != null ? result.cancellationState.name() : "";
        return entry;
    }

    private static List<RedirectHop> copyRedirectHops(List<RedirectHop> hops) {
        List<RedirectHop> copy = new ArrayList<>();
        if (hops == null) {
            return copy;
        }
        for (RedirectHop hop : hops) {
            RedirectHop hopCopy = RedirectHop.copyOf(hop);
            if (hopCopy != null) {
                copy.add(hopCopy);
            }
        }
        return copy;
    }

    public static Entry fromRedirectHop(RunnerResult parent, RedirectHop hop) {
        if (hop == null) {
            return null;
        }
        HistoryEntry detailEntry = HistoryEntry.fromRedirectHop(parent, hop);
        String source = parent != null && parent.collectionName != null ? parent.collectionName : "";
        String method = hop.sourceMethod != null ? hop.sourceMethod : "";
        String status = hop.statusCode > 0 ? String.valueOf(hop.statusCode) : "";
        String duration = hop.elapsedMs > 0 ? hop.elapsedMs + " ms" : "";
        String resultLabel = hop.followed ? "FOLLOWED" : "BLOCKED";
        String state = hop.followed ? "FOLLOWED" : "BLOCKED";
        StringBuilder message = new StringBuilder();
        if (hop.sourceUrl != null && !hop.sourceUrl.isBlank()) {
            message.append(hop.sourceUrl);
        }
        if (hop.targetUrl != null && !hop.targetUrl.isBlank()) {
            if (message.length() > 0) {
                message.append(" -> ");
            }
            message.append(hop.targetUrl);
        }
        if (hop.failureReason != null && !hop.failureReason.isBlank()) {
            if (message.length() > 0) {
                message.append(" | ");
            }
            message.append(hop.failureReason);
        }
        if (!hop.forwardedSensitiveHeaderNames.isEmpty()) {
            if (message.length() > 0) {
                message.append(" | ");
            }
            message.append("forwarded=").append(hop.forwardedSensitiveHeaderNames);
        }
        if (!hop.strippedSensitiveHeaderNames.isEmpty()) {
            if (message.length() > 0) {
                message.append(" | ");
            }
            message.append("stripped=").append(hop.strippedSensitiveHeaderNames);
        }
        return new Entry(
                0,
                Instant.now(),
                hop.followed ? "REDIRECT_HOP" : "REDIRECT_BLOCKED",
                state,
                "↳ Redirect hop " + (hop.hopNumber > 0 ? hop.hopNumber : "?"),
                source,
                method,
                status,
                resultLabel,
                duration,
                "",
                message.toString(),
                detailEntry,
                null,
                null,
                parent != null ? parent.requestId : null,
                source
        );
    }

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
        public final HistoryEntry detailEntry;
        public final RunnerResult requestResult;
        public final RunnerTimelineRow timelineRow;
        public final String requestId;
        public final String collectionName;
        public String attempt;
        public String kind;
        public String retryReason;
        public String cancellationState;

        public Entry(int sequence,
                     Instant timestamp,
                     String type,
                     String state,
                     String requestName,
                     String source,
                     String method,
                     String status,
                     String result,
                     String duration,
                     String flow,
                     String message,
                     HistoryEntry detailEntry,
                     RunnerResult requestResult,
                     RunnerTimelineRow timelineRow,
                     String requestId,
                     String collectionName) {
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
            this.detailEntry = detailEntry;
            this.requestResult = requestResult;
            this.timelineRow = timelineRow;
            this.requestId = requestId;
            this.collectionName = collectionName;
        }

        public String formatTime() {
            return timestamp != null ? TIME_FORMAT.format(timestamp) : "";
        }
    }

    private static String executionKind(RunnerResult result) {
        if (result == null) {
            return "";
        }
        if (result.cancellationState != null && result.cancellationState != burp.models.RunnerCancellationState.NOT_CANCELLED) {
            return "CANCELLED";
        }
        if (result.preflightStatus == burp.utils.ExecutionPreflightStatus.BLOCKED_SCRIPT_ERROR
                || result.preflightStatus == burp.utils.ExecutionPreflightStatus.BLOCKED_SCRIPT_TIMEOUT
                || result.preflightStatus == burp.utils.ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE
                || result.preflightStatus == burp.utils.ExecutionPreflightStatus.BLOCKED_UNRESOLVED_VARIABLES
                || result.preflightStatus == burp.utils.ExecutionPreflightStatus.BLOCKED_TARGET_CHANGE
                || result.preflightStatus == burp.utils.ExecutionPreflightStatus.BLOCKED_POLICY) {
            return "PREFLIGHT_BLOCKED";
        }
        if (result.responseTimedOut) {
            return "TIMED_OUT";
        }
        if (result.adHocExecution) {
            return "AD_HOC";
        }
        if (result.dependentExecution) {
            return "DEPENDENT";
        }
        if (result.attemptNumber > 1) {
            return "RETRY";
        }
        return "QUEUED";
    }
}
