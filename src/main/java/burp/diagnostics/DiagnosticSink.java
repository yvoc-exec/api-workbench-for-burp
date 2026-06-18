package burp.diagnostics;

public interface DiagnosticSink {
    void record(DiagnosticEvent event);
}
