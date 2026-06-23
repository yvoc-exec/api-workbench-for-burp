package burp.models;

import burp.diagnostics.DiagnosticSeverity;

public enum RunnerTerminationType {
    COMPLETED("Completed", DiagnosticSeverity.INFO),
    CANCELLED("Cancelled", DiagnosticSeverity.WARNING),
    STOPPED_ON_ERROR("Stopped: error", DiagnosticSeverity.WARNING),
    STOPPED_ON_ASSERTION_FAILURE("Stopped: assertion failure", DiagnosticSeverity.WARNING),
    STOPPED_ON_STATUS("Stopped: HTTP status condition", DiagnosticSeverity.WARNING),
    STOPPED_ON_FAILURE_COUNT("Stopped: failure threshold", DiagnosticSeverity.WARNING),
    STOPPED_BY_SCRIPT("Stopped by script", DiagnosticSeverity.WARNING),
    STOPPED_ON_MISSING_VARIABLE("Stopped: missing variable", DiagnosticSeverity.WARNING),
    INTERNAL_ERROR("Internal runner error", DiagnosticSeverity.ERROR);

    private final String displayLabel;
    private final DiagnosticSeverity diagnosticSeverity;

    RunnerTerminationType(String displayLabel, DiagnosticSeverity diagnosticSeverity) {
        this.displayLabel = displayLabel;
        this.diagnosticSeverity = diagnosticSeverity;
    }

    public String displayLabel() {
        return displayLabel;
    }

    public DiagnosticSeverity diagnosticSeverity() {
        return diagnosticSeverity;
    }
}
