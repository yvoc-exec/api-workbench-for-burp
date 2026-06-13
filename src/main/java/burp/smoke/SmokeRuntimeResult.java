package burp.smoke;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Machine-readable runtime smoke result written by the opt-in smoke runner.
 */
public final class SmokeRuntimeResult {
    public String status = "fail";
    public String startedAt;
    public String finishedAt;
    public long durationMs;
    public String extensionJar;
    public String burpPath;
    public String localApi;
    public String runtimeConfigPath;
    public String reportPath;
    public String logPath;
    public String burpLogPath;
    public String localApiLogPath;
    public String workspaceSnapshotPath;
    public String collectionExportPath;
    public String environmentExportPath;
    public String activeEnvironmentId;
    public final List<CheckResult> checks = new ArrayList<>();
    public final List<String> artifacts = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();
    public final Map<String, String> metadata = new LinkedHashMap<>();
    private transient Consumer<String> logConsumer;

    public static final class CheckResult {
        public String name;
        public String status;
        public String details;

        public CheckResult(String name, String status, String details) {
            this.name = name;
            this.status = status;
            this.details = details;
        }
    }

    public void addCheck(String name, String status, String details) {
        checks.add(new CheckResult(name, status, details));
        if (logConsumer != null) {
            logConsumer.accept("[" + (status != null ? status.toUpperCase() : "UNKNOWN") + "] " + name + " - " + (details != null ? details : ""));
        }
    }

    public void pass(String name, String details) {
        addCheck(name, "pass", details);
    }

    public void fail(String name, String details) {
        addCheck(name, "fail", details);
    }

    public void skipped(String name, String details) {
        addCheck(name, "skipped", details);
    }

    public void manual(String name, String details) {
        addCheck(name, "manual", details);
    }

    public void addArtifact(String path) {
        if (path != null && !path.isBlank() && !artifacts.contains(path)) {
            artifacts.add(path);
        }
    }

    public void addError(String error) {
        if (error != null && !error.isBlank() && !errors.contains(error)) {
            errors.add(error);
        }
    }

    public void addNote(String note) {
        if (note != null && !note.isBlank() && !notes.contains(note)) {
            notes.add(note);
        }
    }

    public boolean hasFailures() {
        for (CheckResult check : checks) {
            if (check != null && "fail".equalsIgnoreCase(check.status)) {
                return true;
            }
        }
        return false;
    }

    public void setLogConsumer(Consumer<String> logConsumer) {
        this.logConsumer = logConsumer;
    }
}
