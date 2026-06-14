package burp.smoke;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Opt-in runtime smoke configuration loaded from an explicit JSON file path.
 */
public final class SmokeRuntimeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String configPath;
    public int version = 1;
    public String runId;
    public String apiWorkbenchRepo;
    public String testerRepo;
    public String fixturesRoot;
    public String extensionJar;
    public String burpPath;
    public String javaExe;
    public String localApiHost;
    public int localApiPort;
    public String localApiUrl;
    public int maxWaitSeconds = 120;
    public boolean includeLiveEndpointTests = true;
    public boolean requireLiveEndpointTests = false;
    public boolean surgicalCoverage = true;
    public int largeCollectionRequestCount = 250;
    public String evidenceDir;
    public String logScanPath;
    public String manualChecklistPath;
    public Boolean captureUiEvidence;
    public Boolean scanLogsForErrors;
    public Boolean generateManualChecklist;
    public Boolean visualDebug;
    public Integer pauseAfterMajorStepsMs;
    public String resultJsonPath;
    public String reportPath;
    public String logPath;
    public String burpLogPath;
    public String localApiLogPath;
    public String workspaceSnapshotPath;
    public String collectionExportPath;
    public String environmentExportPath;
    public FixturePaths fixtures = new FixturePaths();

    public static final class FixturePaths {
        public String openApi;
        public String postman;
        public String apiWorkbenchEnvironment;
        public String postmanEnvironment;
        public String runtimeEnv;
        public String unsupported;
    }

    public static SmokeRuntimeConfig loadFromEnvironment() {
        String configured = firstNonBlank(
                System.getProperty("apiWorkbench.smoke.config"),
                System.getenv("API_WORKBENCH_SMOKE_CONFIG")
        );
        if (configured == null) {
            return null;
        }
        return load(Path.of(configured));
    }

    public static SmokeRuntimeConfig load(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Smoke config path is required.");
        }
        Path resolved = path.toAbsolutePath().normalize();
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("Smoke config file not found: " + resolved);
        }
        try {
            String json = Files.readString(resolved, StandardCharsets.UTF_8);
            SmokeRuntimeConfig config = GSON.fromJson(json, SmokeRuntimeConfig.class);
            if (config == null) {
                throw new IllegalArgumentException("Smoke config file was empty: " + resolved);
            }
            config.configPath = resolved.toString();
            config.normalise();
            return config;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read smoke config '" + resolved + "': " + e.getMessage(), e);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Failed to parse smoke config '" + resolved + "': " + e.getMessage(), e);
        }
    }

    public Path resolvePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    public Path getResultJsonPath() {
        return resolvePath(resultJsonPath);
    }

    public Path getReportPath() {
        return resolvePath(reportPath);
    }

    public Path getLogPath() {
        return resolvePath(logPath);
    }

    public Path getBurpLogPath() {
        return resolvePath(burpLogPath);
    }

    public Path getLocalApiLogPath() {
        return resolvePath(localApiLogPath);
    }

    public Path getWorkspaceSnapshotPath() {
        return resolvePath(workspaceSnapshotPath);
    }

    public Path getCollectionExportPath() {
        return resolvePath(collectionExportPath);
    }

    public Path getEnvironmentExportPath() {
        return resolvePath(environmentExportPath);
    }

    public Path getEvidenceDirPath() {
        return resolvePath(evidenceDir);
    }

    public Path getLogScanPath() {
        return resolvePath(logScanPath);
    }

    public Path getManualChecklistPath() {
        return resolvePath(manualChecklistPath);
    }

    public boolean isCaptureUiEvidence() {
        return captureUiEvidence == null || captureUiEvidence;
    }

    public boolean isScanLogsForErrors() {
        return scanLogsForErrors == null || scanLogsForErrors;
    }

    public boolean isGenerateManualChecklist() {
        return generateManualChecklist == null || generateManualChecklist;
    }

    public boolean isVisualDebug() {
        return visualDebug != null && visualDebug;
    }

    public int getPauseAfterMajorStepsMs() {
        return pauseAfterMajorStepsMs != null && pauseAfterMajorStepsMs > 0 ? pauseAfterMajorStepsMs : 0;
    }

    public Path getFixturesRootPath() {
        return resolvePath(fixturesRoot);
    }

    public Path resolveFixturePath(String relativeOrAbsolutePath) {
        if (relativeOrAbsolutePath == null || relativeOrAbsolutePath.isBlank()) {
            return null;
        }
        Path candidate = Path.of(relativeOrAbsolutePath);
        if (candidate.isAbsolute()) {
            return candidate.toAbsolutePath().normalize();
        }
        Path root = getFixturesRootPath();
        if (root != null) {
            return root.resolve(candidate).toAbsolutePath().normalize();
        }
        return candidate.toAbsolutePath().normalize();
    }

    public Path getFixturePath(String kind) {
        if (fixtures == null || kind == null) {
            return null;
        }
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "openapi" -> resolvePath(fixtures.openApi);
            case "postman" -> resolvePath(fixtures.postman);
            case "apiworkbenchenvironment" -> resolvePath(fixtures.apiWorkbenchEnvironment);
            case "postmanenvironment" -> resolvePath(fixtures.postmanEnvironment);
            case "runtimeenv" -> resolvePath(fixtures.runtimeEnv);
            case "unsupported" -> resolvePath(fixtures.unsupported);
            default -> null;
        };
    }

    public String getResolvedLocalApiUrl() {
        if (localApiUrl != null && !localApiUrl.isBlank()) {
            return localApiUrl;
        }
        if (localApiHost == null || localApiHost.isBlank() || localApiPort <= 0) {
            return null;
        }
        return "http://" + localApiHost + ":" + localApiPort;
    }

    private void normalise() {
        if (fixtures == null) {
            fixtures = new FixturePaths();
        }
        if (maxWaitSeconds <= 0) {
            maxWaitSeconds = 120;
        }
        if (largeCollectionRequestCount <= 0) {
            largeCollectionRequestCount = 250;
        }
        if (localApiUrl == null || localApiUrl.isBlank()) {
            localApiUrl = getResolvedLocalApiUrl();
        }

        if (captureUiEvidence == null) {
            captureUiEvidence = true;
        }
        if (scanLogsForErrors == null) {
            scanLogsForErrors = true;
        }
        if (generateManualChecklist == null) {
            generateManualChecklist = true;
        }
        if (visualDebug == null) {
            visualDebug = false;
        }
        if (pauseAfterMajorStepsMs == null || pauseAfterMajorStepsMs < 0) {
            pauseAfterMajorStepsMs = 0;
        }

        Path report = getReportPath();
        Path reportsDir = report != null ? report.getParent() : null;
        String effectiveRunId = runId != null && !runId.isBlank() ? runId : null;
        if (effectiveRunId == null && report != null && report.getFileName() != null) {
            String fileName = report.getFileName().toString();
            String prefix = "runtime-smoke-report-";
            if (fileName.startsWith(prefix) && fileName.endsWith(".md")) {
                effectiveRunId = fileName.substring(prefix.length(), fileName.length() - 3);
            }
        }
        if (reportsDir != null) {
            if (evidenceDir == null || evidenceDir.isBlank()) {
                evidenceDir = reportsDir.resolve("evidence").resolve(effectiveRunId != null ? effectiveRunId : "current").toString();
            }
            if (logScanPath == null || logScanPath.isBlank()) {
                logScanPath = reportsDir.resolve("log-scan-" + (effectiveRunId != null ? effectiveRunId : "current") + ".json").toString();
            }
            if (manualChecklistPath == null || manualChecklistPath.isBlank()) {
                manualChecklistPath = reportsDir.resolve("manual-checklist-remaining-" + (effectiveRunId != null ? effectiveRunId : "current") + ".md").toString();
            }
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
