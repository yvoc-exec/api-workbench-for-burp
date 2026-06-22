package burp.diagnostics;

import burp.scripts.ExecutionSource;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class DiagnosticEvent {
    public final Instant timestamp = Instant.now();
    public final String operationId = UUID.randomUUID().toString();
    public DiagnosticSeverity severity = DiagnosticSeverity.INFO;
    public DiagnosticOperation operation = DiagnosticOperation.REQUEST_BUILD;
    public String sourceArea;
    public String collectionName;
    public String requestName;
    public String requestId;
    public String folderPath;
    public String environmentName;
    public ExecutionSource executionSource;
    public ScriptDialect scriptDialect;
    public ScriptPhase scriptPhase;
    public String message;
    public String details;
    public final Map<String, String> attributes = new LinkedHashMap<>();

    public static DiagnosticEvent of(DiagnosticOperation operation, DiagnosticSeverity severity, String sourceArea, String message) {
        DiagnosticEvent event = new DiagnosticEvent();
        event.operation = operation != null ? operation : DiagnosticOperation.REQUEST_BUILD;
        event.severity = severity != null ? severity : DiagnosticSeverity.INFO;
        event.sourceArea = sourceArea;
        event.message = message;
        return event;
    }

    public DiagnosticEvent withAttribute(String key, String value) {
        if (key != null && !key.isBlank()) {
            attributes.put(key, value);
        }
        return this;
    }

    public DiagnosticEvent withDetails(String details) {
        this.details = details;
        return this;
    }

    public String sanitizedDetails() {
        return DiagnosticSanitizer.sanitizeText(details);
    }

    public String summaryLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(timestamp).append(' ');
        sb.append('[').append(severity).append("] ");
        sb.append(operation != null ? operation.name() : "");
        if (sourceArea != null && !sourceArea.isBlank()) {
            sb.append(" @").append(sourceArea);
        }
        if (collectionName != null && !collectionName.isBlank()) {
            sb.append(" collection=").append(collectionName);
        }
        if (requestName != null && !requestName.isBlank()) {
            sb.append(" request=").append(requestName);
        }
        if (requestId != null && !requestId.isBlank()) {
            sb.append(" id=").append(requestId);
        }
        if (environmentName != null && !environmentName.isBlank()) {
            sb.append(" env=").append(environmentName);
        }
        if (executionSource != null) {
            sb.append(" source=").append(executionSource.name());
        }
        if (scriptDialect != null) {
            sb.append(" dialect=").append(scriptDialect.name());
        }
        if (scriptPhase != null) {
            sb.append(" phase=").append(scriptPhase.name());
        }
        if (message != null && !message.isBlank()) {
            sb.append(" :: ").append(message);
        }
        return sb.toString();
    }
}
