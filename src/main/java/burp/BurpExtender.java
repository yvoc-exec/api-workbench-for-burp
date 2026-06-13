package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.smoke.SmokeRuntimeConfig;
import burp.smoke.SmokeRuntimeRunner;
import burp.smoke.SmokeRuntimeResult;
import burp.ui.ImporterPanel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javax.swing.*;
import java.io.PrintWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * API Workbench for Burp Suite
 *
 * Supports: Postman (v2.0/v2.1), Bruno (.bru), OpenAPI/Swagger (JSON/YAML),
 *           Insomnia (v4), HAR
 *
 * Features:
 * - Auto-detect collection format
 * - Preview and select requests before import
 * - Import to Repeater, Sitemap, or Both
 * - Variable resolution with environment files
 * - Collection Runner: sequential execution with variable extraction
 * - Rate limiting and retry logic
 *
 * Author: Sachinico De Leon
 * Version: 2.0.0
 * License: MIT
 */
public class BurpExtender implements BurpExtension {
    private static final Gson SMOKE_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private MontoyaApi api;
    private volatile UniversalImporter importer;
    private volatile SmokeRuntimeConfig smokeRuntimeConfig;
    private final AtomicBoolean smokeRuntimeRunnerStarted = new AtomicBoolean(false);
    private final AtomicBoolean smokeRuntimeBootstrapStarted = new AtomicBoolean(false);

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;

        boolean smokeRequested = isSmokeRuntimeRequested();
        writeSmokeInitializationMarker();
        writeSmokeLifecycleMarker("set-name-start");
        api.extension().setName("API Workbench for Burp");
        writeSmokeLifecycleMarker("set-name-complete");

        burp.utils.ScriptModeDetector.DetectionResult scriptResult;
        if (smokeRequested) {
            writeSmokeLifecycleMarker("script-detect-skipped-smoke-mode");
            scriptResult = createSmokeScriptResult();
        } else {
            writeSmokeLifecycleMarker("script-detect-start");
            scriptResult = burp.utils.ScriptModeDetector.detect();
            writeSmokeLifecycleMarker("script-detect-complete");
        }
        api.logging().logToOutput("===================================================");
        api.logging().logToOutput("  API Workbench for Burp v2.0.0");
        api.logging().logToOutput("  Supports: Postman, Bruno, OpenAPI, Insomnia, HAR");
        api.logging().logToOutput("  Features: Import + Collection Runner + Workbench");
        api.logging().logToOutput("  Java: " + scriptResult.javaVersion + " | Script: " + scriptResult.mode.label);
        if (scriptResult.reason != null) {
            api.logging().logToOutput("  Script reason: " + scriptResult.reason);
        }
        api.logging().logToOutput("===================================================");
        api.logging().logToOutput("Extension core initialized; scheduling API Workbench UI registration...");

        writeSmokeLifecycleMarker("bootstrap-schedule-start");
        if (smokeRequested) {
            startRuntimeSmokeBootstrapIfConfigured(api, scriptResult);
        }
        writeSmokeLifecycleMarker("bootstrap-schedule-complete");

        SwingUtilities.invokeLater(() -> {
            writeSmokeLifecycleMarker("initialize-ui-call-start");
            initializeUi(api, scriptResult);
            writeSmokeLifecycleMarker("initialize-ui-call-complete");
        });

        api.extension().registerUnloadingHandler(() -> {
            if (importer != null) {
                importer.cleanup();
            }
            burp.auth.TokenStore.clearAll();
            api.logging().logToOutput("API Workbench for Burp unloaded. Tokens cleared.");
        });
    }

    void initializeUi(MontoyaApi api, burp.utils.ScriptModeDetector.DetectionResult scriptResult) {
        try {
            writeSmokeLifecycleMarker("initialize-ui-start");
            api.logging().logToOutput("API Workbench UI init starting...");

            api.logging().logToOutput("Creating WorkspaceStateService...");
            burp.utils.WorkspaceStateService workspaceStateService = new burp.utils.WorkspaceStateService(api);

            api.logging().logToOutput("Creating UniversalImporter...");
            importer = new UniversalImporter(api, scriptResult.mode, workspaceStateService);

            api.logging().logToOutput("Getting API Workbench main panel...");
            JPanel mainPanel = importer.getMainPanel();
            if (mainPanel == null) {
                throw new IllegalStateException("API Workbench main panel is null.");
            }

            api.logging().logToOutput("Registering API Workbench suite tab...");
            api.userInterface().registerSuiteTab("API Workbench", mainPanel);
            writeSmokeLifecycleMarker("suite-tab-registered");

            startRuntimeSmokeIfConfigured(api, scriptResult);

            api.logging().logToOutput("Restoring API Workbench workspace state...");
            importer.restoreWorkspaceStateAfterUiRegistration();
            writeSmokeLifecycleMarker("workspace-restored");

            startRuntimeSmokeIfConfigured(api, scriptResult);

            api.logging().logToOutput("API Workbench suite tab registered successfully.");
        } catch (Throwable t) {
            api.logging().logToError("API Workbench UI initialization failed: " + t);
            StringWriter traceWriter = new StringWriter();
            t.printStackTrace(new PrintWriter(traceWriter));
            String[] traceLines = traceWriter.toString().split("\\R");
            for (int i = 1; i < traceLines.length; i++) {
                if (!traceLines[i].isEmpty()) {
                    api.logging().logToError(traceLines[i]);
                }
            }
        }
    }

    private boolean isSmokeRuntimeRequested() {
        String configured = System.getProperty("apiWorkbench.smoke.config");
        if (configured != null && !configured.isBlank()) {
            return true;
        }
        configured = System.getenv("API_WORKBENCH_SMOKE_CONFIG");
        return configured != null && !configured.isBlank();
    }

    private burp.utils.ScriptModeDetector.DetectionResult createSmokeScriptResult() {
        return new burp.utils.ScriptModeDetector.DetectionResult(
                burp.utils.ScriptMode.LIMITED,
                "Smoke runtime requested; Nashorn probe skipped to avoid startup hang.",
                Runtime.version().feature()
        );
    }

    private void startRuntimeSmokeIfConfigured(MontoyaApi api, burp.utils.ScriptModeDetector.DetectionResult scriptResult) {
        try {
            SmokeRuntimeConfig smokeConfig = resolveSmokeRuntimeConfig();
            if (smokeConfig == null) {
                return;
            }
            writeSmokeLifecycleMarker("smoke-config-resolved");
            tryStartSmokeRuntimeRunner(api, scriptResult, smokeConfig);
        } catch (Throwable t) {
            api.logging().logToError("API Workbench runtime smoke runner could not be started: " + t.getMessage());
        }
    }

    private void startRuntimeSmokeBootstrapIfConfigured(MontoyaApi api, burp.utils.ScriptModeDetector.DetectionResult scriptResult) {
        try {
            SmokeRuntimeConfig smokeConfig = resolveSmokeRuntimeConfig();
            if (smokeConfig == null) {
                return;
            }
            if (!smokeRuntimeBootstrapStarted.compareAndSet(false, true)) {
                return;
            }
            writeSmokeLifecycleMarker("bootstrap-scheduled");
            api.logging().logToOutput("API Workbench smoke config detected; scheduling runtime smoke bootstrap.");
            Thread bootstrapThread = new Thread(() -> bootstrapRuntimeSmoke(api, scriptResult, smokeConfig), "api-workbench-smoke-bootstrap");
            bootstrapThread.setDaemon(true);
            bootstrapThread.start();
        } catch (Throwable t) {
            api.logging().logToError("API Workbench runtime smoke bootstrap could not be scheduled: " + t.getMessage());
        }
    }

    private void bootstrapRuntimeSmoke(MontoyaApi api, burp.utils.ScriptModeDetector.DetectionResult scriptResult, SmokeRuntimeConfig smokeConfig) {
        long waitSeconds = Math.max(30L, Math.min(120L, smokeConfig.maxWaitSeconds > 0 ? smokeConfig.maxWaitSeconds : 120));
        long deadline = System.currentTimeMillis() + (waitSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            try {
                if (tryStartSmokeRuntimeRunner(api, scriptResult, smokeConfig)) {
                    return;
                }
            } catch (Throwable t) {
                api.logging().logToError("API Workbench smoke bootstrap failed: " + t.getMessage());
                writeSmokeBootstrapFailure(smokeConfig, scriptResult, "Smoke bootstrap failed: " + t.getMessage());
                return;
            }

            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (!smokeRuntimeRunnerStarted.get()) {
            writeSmokeBootstrapFailure(smokeConfig, scriptResult, "Timed out waiting for API Workbench UI initialization.");
        }
    }

    private boolean tryStartSmokeRuntimeRunner(MontoyaApi api, burp.utils.ScriptModeDetector.DetectionResult scriptResult, SmokeRuntimeConfig smokeConfig) {
        if (smokeConfig == null || importer == null) {
            return false;
        }
        ImporterPanel ui = importer.getUI();
        if (ui == null || ui.getPanel() == null || ui.getTabbedPane() == null) {
            return false;
        }
        if (!smokeRuntimeRunnerStarted.compareAndSet(false, true)) {
            return true;
        }
        writeSmokeLifecycleMarker("runtime-smoke-started");
        api.logging().logToOutput("API Workbench smoke config detected; starting runtime smoke runner.");
        SmokeRuntimeRunner smokeRunner = new SmokeRuntimeRunner(api, importer, scriptResult != null ? scriptResult.mode : null, smokeConfig);
        smokeRunner.startAsync();
        return true;
    }

    private SmokeRuntimeConfig resolveSmokeRuntimeConfig() {
        if (smokeRuntimeConfig != null) {
            return smokeRuntimeConfig;
        }
        smokeRuntimeConfig = SmokeRuntimeConfig.loadFromEnvironment();
        return smokeRuntimeConfig;
    }

    private void writeSmokeBootstrapFailure(SmokeRuntimeConfig smokeConfig, burp.utils.ScriptModeDetector.DetectionResult scriptResult, String message) {
        try {
            SmokeRuntimeResult result = new SmokeRuntimeResult();
            Instant started = Instant.now();
            result.startedAt = started.toString();
            result.finishedAt = Instant.now().toString();
            result.durationMs = 0L;
            result.status = "fail";
            result.runtimeConfigPath = smokeConfig.configPath;
            result.reportPath = smokeConfig.reportPath;
            result.logPath = smokeConfig.logPath;
            result.burpLogPath = smokeConfig.burpLogPath;
            result.localApiLogPath = smokeConfig.localApiLogPath;
            result.workspaceSnapshotPath = smokeConfig.workspaceSnapshotPath;
            result.collectionExportPath = smokeConfig.collectionExportPath;
            result.environmentExportPath = smokeConfig.environmentExportPath;
            result.extensionJar = smokeConfig.extensionJar;
            result.burpPath = smokeConfig.burpPath;
            result.localApi = smokeConfig.getResolvedLocalApiUrl();
            result.metadata.put("scriptMode", scriptResult != null && scriptResult.mode != null ? scriptResult.mode.name() : "unknown");
            result.metadata.put("scriptModeLabel", scriptResult != null && scriptResult.mode != null ? scriptResult.mode.label : "unknown");
            result.fail("startup.extension.initialized", message);
            result.fail("startup.tab.registered", message);
            result.addError(message);
            result.addNote("Smoke bootstrap failed before the runtime runner could start.");
            result.addArtifact(smokeConfig.configPath);
            result.addArtifact(smokeConfig.reportPath);
            result.addArtifact(smokeConfig.logPath);
            result.addArtifact(smokeConfig.burpLogPath);
            result.addArtifact(smokeConfig.localApiLogPath);
            result.addArtifact(smokeConfig.workspaceSnapshotPath);
            result.addArtifact(smokeConfig.collectionExportPath);
            result.addArtifact(smokeConfig.environmentExportPath);

            Path resultPath = smokeConfig.getResultJsonPath();
            if (resultPath != null) {
                Path parent = resultPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(resultPath, SMOKE_GSON.toJson(result), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
        } catch (IOException ioe) {
            api.logging().logToError("API Workbench smoke bootstrap could not write fallback result: " + ioe.getMessage());
        } catch (Throwable t) {
            api.logging().logToError("API Workbench smoke bootstrap fallback failed: " + t.getMessage());
        }
    }

    private void writeSmokeInitializationMarker() {
        writeSmokeMarker("extension-initialize.marker", "API Workbench extension initialize() reached at ");
    }

    private void writeSmokeLifecycleMarker(String stage) {
        if (stage == null || stage.isBlank()) {
            return;
        }
        writeSmokeMarker(stage + ".marker", "API Workbench smoke stage '" + stage + "' reached at ");
    }

    private void writeSmokeMarker(String fileName, String prefix) {
        try {
            String configured = System.getProperty("apiWorkbench.smoke.config");
            if (configured == null || configured.isBlank()) {
                configured = System.getenv("API_WORKBENCH_SMOKE_CONFIG");
            }
            if (configured == null || configured.isBlank()) {
                return;
            }
            Path markerPath = Path.of(configured).toAbsolutePath().normalize().resolveSibling(fileName);
            Path parent = markerPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    markerPath,
                    prefix + Instant.now() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (Throwable ignored) {
            // Best-effort diagnostic marker only.
        }
    }
}
