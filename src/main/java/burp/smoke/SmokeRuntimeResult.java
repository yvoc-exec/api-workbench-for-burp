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
        public String group;
        public String name;
        public String status;
        public String details;
        public String format;
        public String artifactPath;
        public String endpointType;

        public CheckResult(String name, String status, String details) {
            this(null, name, status, details, null, null, null);
        }

        public CheckResult(String group, String name, String status, String details, String format, String artifactPath, String endpointType) {
            this.group = group;
            this.name = name;
            this.status = status;
            this.details = details;
            this.format = format;
            this.artifactPath = artifactPath;
            this.endpointType = endpointType;
        }
    }

    public void addCheck(String name, String status, String details) {
        addCheck(null, name, status, details, null, null, null);
    }

    public void addCheck(String group, String name, String status, String details) {
        addCheck(group, name, status, details, null, null, null);
    }

    public void addCheck(String group, String name, String status, String details, String format, String artifactPath, String endpointType) {
        checks.add(new CheckResult(group, name, status, details, format, artifactPath, endpointType));
        if (logConsumer != null) {
            String prefix = group != null && !group.isBlank() ? group + "." + name : name;
            logConsumer.accept("[" + (status != null ? status.toUpperCase() : "UNKNOWN") + "] " + prefix + " - " + (details != null ? details : ""));
        }
    }

    public void pass(String name, String details) {
        addCheck(name, "pass", details);
    }

    public void pass(String group, String name, String details) {
        addCheck(group, name, "pass", details, null, null, null);
    }

    public void fail(String name, String details) {
        addCheck(name, "fail", details);
    }

    public void fail(String group, String name, String details) {
        addCheck(group, name, "fail", details, null, null, null);
    }

    public void skipped(String name, String details) {
        addCheck(name, "skipped", details);
    }

    public void skipped(String group, String name, String details) {
        addCheck(group, name, "skipped", details, null, null, null);
    }

    public void manual(String name, String details) {
        addCheck(name, "manual", details);
    }

    public void manual(String group, String name, String details) {
        addCheck(group, name, "manual", details, null, null, null);
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
