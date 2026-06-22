package burp.diagnostics;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DiagnosticStore implements DiagnosticSink {
    private static final DiagnosticStore INSTANCE = new DiagnosticStore();
    private static final int MAX_EVENTS = 1000;

    private final List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
    private volatile boolean captureEnabled = false;

    private DiagnosticStore() {
    }

    public static DiagnosticStore getInstance() {
        return INSTANCE;
    }

    public boolean isCaptureEnabled() {
        return captureEnabled;
    }

    public void setCaptureEnabled(boolean captureEnabled) {
        this.captureEnabled = captureEnabled;
    }

    @Override
    public void record(DiagnosticEvent event) {
        if (event == null || !captureEnabled) {
            return;
        }
        events.add(event);
        if (events.size() > MAX_EVENTS) {
            int overflow = events.size() - MAX_EVENTS;
            if (overflow > 0) {
                events.subList(0, overflow).clear();
            }
        }
    }

    public DiagnosticEvent record(DiagnosticOperation operation, DiagnosticSeverity severity, String sourceArea, String message) {
        DiagnosticEvent event = DiagnosticEvent.of(operation, severity, sourceArea, message);
        record(event);
        return event;
    }

    public List<DiagnosticEvent> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public void clear() {
        events.clear();
    }

    public String sanitizedReport(boolean includeDebug) {
        StringBuilder sb = new StringBuilder();
        sb.append("Diagnostics Events\n");
        sb.append("===================\n");
        List<DiagnosticEvent> filtered = new ArrayList<>();
        for (DiagnosticEvent event : snapshot()) {
            if (event == null) {
                continue;
            }
            if (!includeDebug && event.severity == DiagnosticSeverity.DEBUG) {
                continue;
            }
            filtered.add(event);
        }
        if (filtered.isEmpty()) {
            sb.append("(none)\n");
            return sb.toString();
        }
        sb.append("Summary: events=").append(filtered.size())
                .append(" warnings=").append(countSeverity(filtered, DiagnosticSeverity.WARNING))
                .append(" errors=").append(countSeverity(filtered, DiagnosticSeverity.ERROR))
                .append(" debug=").append(countSeverity(filtered, DiagnosticSeverity.DEBUG))
                .append('\n');
        sb.append('\n');
        DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;
        Map<DiagnosticOperation, List<DiagnosticEvent>> grouped = new LinkedHashMap<>();
        for (DiagnosticEvent event : filtered) {
            DiagnosticOperation operation = event.operation != null ? event.operation : DiagnosticOperation.REQUEST_BUILD;
            grouped.computeIfAbsent(operation, key -> new ArrayList<>()).add(event);
        }
        for (Map.Entry<DiagnosticOperation, List<DiagnosticEvent>> entry : grouped.entrySet()) {
            DiagnosticOperation operation = entry.getKey();
            List<DiagnosticEvent> operationEvents = entry.getValue();
            sb.append("=== ")
                    .append(operation != null ? operation.name() : "UNKNOWN")
                    .append(" (")
                    .append(operationEvents.size())
                    .append(") ===\n");
            for (DiagnosticEvent event : operationEvents) {
                if (event == null) {
                    continue;
                }
                sb.append(formatter.format(event.timestamp)).append(" | ")
                        .append(event.severity).append(" | ")
                        .append(event.sourceArea != null ? event.sourceArea : "")
                        .append(" | ")
                        .append(DiagnosticSanitizer.sanitizeText(event.message != null ? event.message : ""))
                        .append('\n');
                String details = event.sanitizedDetails();
                if (details != null && !details.isBlank()) {
                    sb.append(indent(details)).append('\n');
                }
                if (event.attributes != null && !event.attributes.isEmpty()) {
                    event.attributes.forEach((k, v) -> sb.append("    ")
                            .append(k)
                            .append("=")
                            .append(DiagnosticSanitizer.sanitizeText(v))
                            .append('\n'));
                }
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    public String compactSummary() {
        int total = events.size();
        int errors = 0;
        int warnings = 0;
        int debug = 0;
        for (DiagnosticEvent event : events) {
            if (event == null) continue;
            switch (event.severity) {
                case ERROR -> errors++;
                case WARNING -> warnings++;
                case DEBUG -> debug++;
                default -> {
                }
            }
        }
        return String.format(Locale.ROOT, "events=%d warnings=%d errors=%d debug=%d", total, warnings, errors, debug);
    }

    private static String indent(String text) {
        String[] lines = text.split("\\R", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("    ").append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private static int countSeverity(List<DiagnosticEvent> events, DiagnosticSeverity severity) {
        int count = 0;
        for (DiagnosticEvent event : events) {
            if (event != null && event.severity == severity) {
                count++;
            }
        }
        return count;
    }
}
