package burp.history;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import burp.scripts.ScriptLogEntry;
import burp.scripts.ScriptVariableMutation;

public class HistoryDiffService {
    public String diff(HistoryEntry left, HistoryEntry right) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Metadata ===\n");
        appendField(sb, "History ID", value(left != null ? left.id : null), value(right != null ? right.id : null));
        appendField(sb, "Timestamp", value(left != null ? left.timeDisplay() : null), value(right != null ? right.timeDisplay() : null));
        appendField(sb, "Source", value(left != null && left.source != null ? left.source.displayName() : null), value(right != null && right.source != null ? right.source.displayName() : null));
        appendField(sb, "Attempt", value(left != null ? left.attemptDisplay() : null), value(right != null ? right.attemptDisplay() : null));
        appendField(sb, "Collection", value(left != null ? left.collectionName : null), value(right != null ? right.collectionName : null));
        appendField(sb, "Folder", value(left != null ? left.folderPath : null), value(right != null ? right.folderPath : null));
        appendField(sb, "Request", value(left != null ? left.requestName : null), value(right != null ? right.requestName : null));
        appendField(sb, "Environment", value(left != null ? left.environmentName : null), value(right != null ? right.environmentName : null));
        appendField(sb, "Result", value(left != null ? left.resultDisplayName() : null), value(right != null ? right.resultDisplayName() : null));
        appendField(sb, "Status", left != null ? String.valueOf(left.statusCode) : "", right != null ? String.valueOf(right.statusCode) : "");
        appendField(sb, "Duration", left != null ? left.durationMillis + "ms" : "", right != null ? right.durationMillis + "ms" : "");
        appendField(sb, "Size", left != null ? left.historySizeLabel() : "", right != null ? right.historySizeLabel() : "");
        appendField(sb, "Execution Source", value(left != null ? left.executionSource : null), value(right != null ? right.executionSource : null));
        appendField(sb, "Script Engine", value(left != null ? left.scriptEngineName : null), value(right != null ? right.scriptEngineName : null));
        appendField(sb, "Flow Control", value(left != null && left.scriptFlowControl != null ? left.scriptFlowControl.name() : null), value(right != null && right.scriptFlowControl != null ? right.scriptFlowControl.name() : null));

        sb.append("\n=== Request ===\n");
        appendBlock(sb, "Method", left != null && left.requestSnapshot != null ? left.requestSnapshot.method : null, right != null && right.requestSnapshot != null ? right.requestSnapshot.method : null);
        appendBlock(sb, "URL Template", left != null && left.requestSnapshot != null ? left.requestSnapshot.urlTemplate : null, right != null && right.requestSnapshot != null ? right.requestSnapshot.urlTemplate : null);
        appendBlock(sb, "Raw Sent Request", left != null && left.requestSnapshot != null ? left.requestSnapshot.preferredRawRequestText() : null, right != null && right.requestSnapshot != null ? right.requestSnapshot.preferredRawRequestText() : null);
        appendBlock(sb, "Headers", left != null && left.requestSnapshot != null ? left.requestSnapshot.displayHeaderBlock() : null, right != null && right.requestSnapshot != null ? right.requestSnapshot.displayHeaderBlock() : null);
        appendBlock(sb, "Body", prettyPrintMaybeJson(left != null && left.requestSnapshot != null ? left.requestSnapshot.displayBodyText() : null), prettyPrintMaybeJson(right != null && right.requestSnapshot != null ? right.requestSnapshot.displayBodyText() : null));

        sb.append("\n=== Response ===\n");
        appendBlock(sb, "Status", left != null && left.responseSnapshot != null ? String.valueOf(left.responseSnapshot.statusCode) : null, right != null && right.responseSnapshot != null ? String.valueOf(right.responseSnapshot.statusCode) : null);
        appendBlock(sb, "Headers", left != null && left.responseSnapshot != null ? left.responseSnapshot.displayHeaderBlock() : null, right != null && right.responseSnapshot != null ? right.responseSnapshot.displayHeaderBlock() : null);
        appendBlock(sb, "Body", prettyPrintMaybeJson(left != null && left.responseSnapshot != null ? left.responseSnapshot.bodyAsText() : null), prettyPrintMaybeJson(right != null && right.responseSnapshot != null ? right.responseSnapshot.bodyAsText() : null));

        sb.append("\n=== Variables / Assertions ===\n");
        appendBlock(sb, "Unresolved Variables", joinList(left != null ? left.unresolvedVariables : List.of()), joinList(right != null ? right.unresolvedVariables : List.of()));
        appendBlock(sb, "Assertions", assertionsText(left), assertionsText(right));
        appendBlock(sb, "Extractions", extractionsText(left), extractionsText(right));
        appendBlock(sb, "Script Logs", scriptLogsText(left), scriptLogsText(right));
        appendBlock(sb, "Script Warnings", joinList(left != null ? left.scriptWarnings : List.of()), joinList(right != null ? right.scriptWarnings : List.of()));
        appendBlock(sb, "Script Errors", joinList(left != null ? left.scriptErrors : List.of()), joinList(right != null ? right.scriptErrors : List.of()));
        appendBlock(sb, "Script Mutations", scriptMutationsText(left), scriptMutationsText(right));
        return sb.toString().trim();
    }

    public boolean different(HistoryEntry left, HistoryEntry right) {
        return !Objects.equals(diff(left, right), "");
    }

    private static void appendField(StringBuilder sb, String field, String left, String right) {
        sb.append(field).append(": ").append(left != null ? left : "").append("  |  ").append(right != null ? right : "").append('\n');
    }

    private static void appendBlock(StringBuilder sb, String field, String left, String right) {
        sb.append(field).append(":\n");
        if (Objects.equals(left, right)) {
            sb.append("  (same)\n");
            if (left != null && !left.isBlank()) {
                sb.append(indent(left)).append('\n');
            }
            return;
        }
        sb.append("  LEFT:\n").append(indent(left != null ? left : "")).append('\n');
        sb.append("  RIGHT:\n").append(indent(right != null ? right : "")).append('\n');
    }

    private static String indent(String value) {
        StringBuilder sb = new StringBuilder();
        for (String line : HistorySanitizer.safeLines(value)) {
            sb.append("    ").append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private static String prettyPrintMaybeJson(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return value;
        }
        try {
            com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(value);
            return HistoryJsonSupport.configure(new com.google.gson.GsonBuilder().setPrettyPrinting()).create().toJson(element);
        } catch (Exception e) {
            return value;
        }
    }

    private static String joinList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join(", ", values);
    }

    private static String assertionsText(HistoryEntry entry) {
        if (entry == null || entry.assertions == null || entry.assertions.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (HistoryAssertionResult assertion : entry.assertions) {
            if (assertion == null) {
                continue;
            }
            lines.add((assertion.passed ? "[PASS] " : "[FAIL] ") + (assertion.name != null ? assertion.name : "")
                    + " expected=" + value(assertion.expected)
                    + " actual=" + value(assertion.actual)
                    + (assertion.message != null ? " message=" + assertion.message : ""));
        }
        return String.join("\n", lines);
    }

    private static String extractionsText(HistoryEntry entry) {
        if (entry == null || entry.extractions == null || entry.extractions.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (HistoryExtractionResult extraction : entry.extractions) {
            if (extraction == null) {
                continue;
            }
            lines.add((extraction.name != null ? extraction.name : "") + "=" + value(extraction.value)
                    + (extraction.source != null ? " source=" + extraction.source : "")
                    + (extraction.message != null ? " message=" + extraction.message : ""));
        }
        return String.join("\n", lines);
    }

    private static String scriptLogsText(HistoryEntry entry) {
        if (entry == null || entry.scriptLogs == null || entry.scriptLogs.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (ScriptLogEntry log : entry.scriptLogs) {
            if (log == null) {
                continue;
            }
            lines.add("[" + value(log.level != null ? log.level.toUpperCase(Locale.ROOT) : "INFO") + "] " + value(log.message)
                    + (log.scriptName != null ? " script=" + log.scriptName : "")
                    + (log.scriptId != null ? " id=" + log.scriptId : ""));
        }
        return String.join("\n", lines);
    }

    private static String scriptMutationsText(HistoryEntry entry) {
        if (entry == null || entry.scriptVariableMutations == null || entry.scriptVariableMutations.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (ScriptVariableMutation mutation : entry.scriptVariableMutations) {
            if (mutation == null) {
                continue;
            }
            lines.add((mutation.scope != null ? mutation.scope : "")
                    + ": " + value(mutation.key)
                    + " old=" + value(mutation.oldValue)
                    + " new=" + value(mutation.newValue)
                    + " persistent=" + mutation.persistent
                    + (mutation.sourceScriptName != null ? " script=" + mutation.sourceScriptName : ""));
        }
        return String.join("\n", lines);
    }

    private static String value(String text) {
        return text != null ? text : "";
    }
}
