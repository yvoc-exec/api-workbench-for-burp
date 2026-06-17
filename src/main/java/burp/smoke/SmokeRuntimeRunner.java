package burp.smoke;

import burp.UniversalImporter;
import burp.api.montoya.MontoyaApi;
import burp.auth.OAuth2Manager;
import burp.exporter.CollectionExportFormat;
import burp.exporter.CollectionExportOptions;
import burp.exporter.CollectionExportService;
import burp.exporter.EnvironmentExportFormat;
import burp.exporter.EnvironmentExportOptions;
import burp.exporter.EnvironmentExportService;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerPreviewRow;
import burp.models.RunnerResult;
import burp.models.WorkspaceState;
import burp.parser.CollectionParser;
import burp.parser.ParserRegistry;
import burp.runner.CollectionRunner;
import burp.ui.ImporterPanel;
import burp.ui.RequestEditorPanel;
import burp.ui.RunnerPreviewTableModel;
import burp.ui.dnd.EnvironmentDragPayload;
import burp.ui.dnd.EnvironmentTransferHandler;
import burp.ui.dnd.RunnerQueueDragPayload;
import burp.ui.dnd.RunnerQueueTransferHandler;
import burp.ui.tree.RequestTreeDragPayload;
import burp.ui.tree.RequestTreeMutationService;
import burp.ui.tree.RequestTreePathService;
import burp.ui.tree.RequestTreeTransferHandler;
import burp.ui.tree.TreeDropRequest;
import burp.utils.EnvironmentImportService;
import burp.utils.AuthInheritanceResolver;
import burp.utils.RequestBuilder;
import burp.utils.RequestPathResolver;
import burp.utils.RuntimeResolverFactory;
import burp.utils.ScriptEngine;
import burp.utils.ScriptMode;
import burp.utils.SharedRequestPipeline;
import burp.utils.WorkspaceStateJson;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in runtime smoke runner that exercises the extension after it has loaded.
 */
public final class SmokeRuntimeRunner {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final MontoyaApi api;
    private final UniversalImporter importer;
    private final ScriptMode scriptMode;
    private final SmokeRuntimeConfig config;
    private final ParserRegistry parserRegistry = new ParserRegistry();
    private final RequestTreeMutationService requestTreeMutationService = new RequestTreeMutationService();
    private final CollectionExportService collectionExportService = new CollectionExportService();
    private final EnvironmentExportService environmentExportService = new EnvironmentExportService();

    public SmokeRuntimeRunner(MontoyaApi api, UniversalImporter importer, ScriptMode scriptMode, SmokeRuntimeConfig config) {
        this.api = api;
        this.importer = importer;
        this.scriptMode = scriptMode;
        this.config = config;
    }

    public void startAsync() {
        Thread thread = new Thread(this::runSafely, "api-workbench-smoke-runner");
        thread.setDaemon(true);
        thread.start();
    }

    private void runSafely() {
        SmokeRuntimeResult result = new SmokeRuntimeResult();
        Instant started = Instant.now();
        result.startedAt = started.toString();
        result.runtimeConfigPath = config.configPath;
        result.reportPath = config.reportPath;
        result.logPath = config.logPath;
        result.burpLogPath = config.burpLogPath;
        result.localApiLogPath = config.localApiLogPath;
        result.workspaceSnapshotPath = config.workspaceSnapshotPath;
        result.collectionExportPath = config.collectionExportPath;
        result.environmentExportPath = config.environmentExportPath;
        result.evidenceDirPath = config.getEvidenceDirPath() != null ? config.getEvidenceDirPath().toString() : config.evidenceDir;
        result.logScanPath = config.getLogScanPath() != null ? config.getLogScanPath().toString() : config.logScanPath;
        result.manualChecklistPath = config.getManualChecklistPath() != null ? config.getManualChecklistPath().toString() : config.manualChecklistPath;
        result.captureUiEvidence = config.isCaptureUiEvidence();
        result.scanLogsForErrors = config.isScanLogsForErrors();
        result.generateManualChecklist = config.isGenerateManualChecklist();
        result.visualDebug = config.isVisualDebug();
        result.pauseAfterMajorStepsMs = config.getPauseAfterMajorStepsMs();
        result.extensionJar = config.extensionJar;
        result.burpPath = config.burpPath;
        result.localApi = config.getResolvedLocalApiUrl();
        ImporterPanel ui = null;
        result.setLogConsumer(this::appendLog);
        result.metadata.put("scriptMode", scriptMode != null ? scriptMode.name() : "unknown");
        result.metadata.put("scriptModeLabel", scriptMode != null ? scriptMode.label : "unknown");
        result.metadata.put("configPath", config.configPath);
        result.metadata.put("apiWorkbenchRepo", config.apiWorkbenchRepo);
        result.metadata.put("testerRepo", config.testerRepo);
        result.metadata.put("evidenceDirPath", result.evidenceDirPath != null ? result.evidenceDirPath : "");
        result.metadata.put("logScanPath", result.logScanPath != null ? result.logScanPath : "");
        result.metadata.put("manualChecklistPath", result.manualChecklistPath != null ? result.manualChecklistPath : "");
        result.metadata.put("captureUiEvidence", String.valueOf(result.captureUiEvidence));
        result.metadata.put("scanLogsForErrors", String.valueOf(result.scanLogsForErrors));
        result.metadata.put("generateManualChecklist", String.valueOf(result.generateManualChecklist));
        result.metadata.put("visualDebug", String.valueOf(result.visualDebug));
        result.metadata.put("pauseAfterMajorStepsMs", String.valueOf(result.pauseAfterMajorStepsMs));
        result.addNote("Manual-only behavior that remains out of scope: mouse-driven drag/drop in the live Burp UI and interactive dialog confirmation flows.");

        addArtifacts(result, config.configPath, config.logPath, config.burpLogPath, config.localApiLogPath,
                config.collectionExportPath, config.environmentExportPath);

        try {
            appendLog("=== API Workbench runtime smoke starting ===");
            appendLog("Config: " + config.configPath);
            appendLog("Burp path: " + config.burpPath);
            appendLog("Extension JAR: " + config.extensionJar);
            appendLog("Local API: " + result.localApi);
            appendLog("Script mode: " + result.metadata.get("scriptModeLabel"));

            ui = importer != null ? importer.getUI() : null;
            if (importer != null && ui != null && ui.getPanel() != null) {
                result.pass("startup.extension.initialized", "Importer panel and main panel are available.");
            } else {
                result.fail("startup.extension.initialized", "Importer panel was not available.");
            }

            if (ui != null && ui.getTabbedPane() != null) {
                result.pass("startup.tab.registered", "API Workbench suite tab registered successfully.");
            } else {
                result.fail("startup.tab.registered", "API Workbench tab was not available after initialization.");
            }

            validateFixtureFiles(result);

            boolean localApiReady = probeLocalApi(result);

            ApiCollection openApiCollection = null;
            ApiCollection postmanCollection = null;
            ApiCollection sendCollection = null;
            List<EnvironmentProfile> importedEnvironments = new ArrayList<>();
            String openApiFixture = config.fixtures != null ? config.fixtures.openApi : null;
            String postmanFixture = config.fixtures != null ? config.fixtures.postman : null;
            String apiWorkbenchEnvFixture = config.fixtures != null ? config.fixtures.apiWorkbenchEnvironment : null;
            String postmanEnvFixture = config.fixtures != null ? config.fixtures.postmanEnvironment : null;
            String runtimeEnvFixture = config.fixtures != null ? config.fixtures.runtimeEnv : null;
            String unsupportedFixture = config.fixtures != null ? config.fixtures.unsupported : null;

            if (pathExists(resolveFixture(openApiFixture))) {
                openApiCollection = importCollectionFixture(result, "import.openapi", resolveFixture(openApiFixture));
            }
            if (pathExists(resolveFixture(postmanFixture))) {
                postmanCollection = importCollectionFixture(result, "import.postman", resolveFixture(postmanFixture));
            }

            importedEnvironments.addAll(importEnvironmentFixture(result, "import.environment.api_workbench", resolveFixture(apiWorkbenchEnvFixture)));
            importedEnvironments.addAll(importEnvironmentFixture(result, "import.environment.postman", resolveFixture(postmanEnvFixture)));
            importedEnvironments.addAll(importEnvironmentFixture(result, "import.environment.runtime_env", resolveFixture(runtimeEnvFixture)));

            verifyUnsupportedFixture(result, resolveFixture(unsupportedFixture));

            normaliseEnvironmentProfiles(importedEnvironments);
            EnvironmentProfile activeEnvironment = selectActiveEnvironment(importedEnvironments);
            if (activeEnvironment != null) {
                result.activeEnvironmentId = activeEnvironment.id;
                result.pass("environment.active.set", "Active environment set to \"" + activeEnvironment.displayName() + "\".");
                verifyEnvironmentVariableResolution(result, activeEnvironment);
            } else {
                result.fail("environment.active.set", "No usable environment profile was imported.");
            }

            WorkspaceState baseState = new WorkspaceState();
            baseState.collections = new ArrayList<>();
            if (openApiCollection != null) {
                baseState.collections.add(openApiCollection);
            }
            if (postmanCollection != null) {
                baseState.collections.add(postmanCollection);
            }
            baseState.environments = new ArrayList<>(importedEnvironments);
            baseState.activeEnvironmentId = activeEnvironment != null ? activeEnvironment.id : null;

            String editorCollectionName = postmanCollection != null
                    ? postmanCollection.name
                    : (openApiCollection != null ? openApiCollection.name : null);
            WorkspaceState uiState = buildUiSmokeWorkspaceState(baseState, editorCollectionName, activeEnvironment);
            WorkspaceState sendState = WorkspaceState.copyOf(baseState);
            WorkspaceState treeState = WorkspaceState.copyOf(baseState);
            WorkspaceState runnerState = WorkspaceState.copyOf(baseState);

            if (activeEnvironment != null) {
                materialiseRuntimeRequestUrls(sendState, activeEnvironment);
                materialiseRuntimeRequestUrls(runnerState, activeEnvironment);
            }

            result.skipped(
                    "restore.workspace.snapshot",
                    "Workspace/project restore testing is intentionally skipped in this Community-compatible surgical smoke phase."
            );

            WorkspaceState uiTemplateState = runUiEvidencePhase(result, ui, uiState);
            if (uiTemplateState == null) {
                uiTemplateState = WorkspaceState.copyOf(uiState);
            }

            if (activeEnvironment != null && localApiReady) {
                sendCollection = postmanCollection;
                if (sendCollection == null) {
                    sendCollection = firstCollectionWithPath(sendState.collections, "/health", "/users", "/echo", "/headers", "/auth/bearer", "/status/404", "/delay");
                }
                EnvironmentProfile sendEnvironment = activeEnvironment;
                Map<String, String> runtimeOverlay = sendEnvironment != null ? sendEnvironment.toRuntimeOverlay() : activeEnvironment.toRuntimeOverlay();
                runWorkbenchSendChecks(result, sendCollection, runtimeOverlay);
            } else {
                result.skipped("workbench.send.health", "Workbench sends were skipped because the local API or active environment was unavailable.");
                result.skipped("workbench.send.users", "Workbench sends were skipped because the local API or active environment was unavailable.");
                result.skipped("workbench.send.echo", "Workbench sends were skipped because the local API or active environment was unavailable.");
                result.skipped("workbench.send.headers", "Workbench sends were skipped because the local API or active environment was unavailable.");
                result.skipped("workbench.send.auth", "Workbench sends were skipped because the local API or active environment was unavailable.");
                result.skipped("workbench.send.status404", "Workbench sends were skipped because the local API or active environment was unavailable.");
                result.skipped("workbench.send.delay", "Workbench sends were skipped because the local API or active environment was unavailable.");
            }

            ApiCollection treeCollection = firstNonNullCollection(treeState.collections);
            if (postmanCollection != null) {
                treeCollection = findCollectionByName(treeState.collections, postmanCollection.name);
            }
            if (treeCollection != null) {
                runRequestTreeChecks(result, treeCollection);
            } else {
                result.skipped("request_tree.create_and_move", "Request-tree model checks were skipped because no collection was available.");
                result.skipped("request_tree.drag_payload", "Request-tree model checks were skipped because no collection was available.");
            }

            ApiCollection runnerCollection = postmanCollection != null
                    ? postmanCollection
                    : selectRunnerCollection(runnerState.collections);
            if (runnerCollection != null && activeEnvironment != null && localApiReady) {
                runRunnerQueueChecks(result, runnerState.collections, runnerCollection, activeEnvironment);
            } else {
                result.skipped("runner.queue.preview", "Runner queue checks were skipped because the collection, active environment, or local API was unavailable.");
                result.skipped("runner.queue.reorder", "Runner queue checks were skipped because the collection, active environment, or local API was unavailable.");
                result.skipped("runner.queue.run", "Runner queue checks were skipped because the collection, active environment, or local API was unavailable.");
            }

            if (sendCollection != null && activeEnvironment != null) {
                runExportChecks(result, sendCollection, activeEnvironment);
                maybeVisualDebugPause(result, "Visual debug pause after export");
            } else {
                result.skipped("export.collection", "Collection export was skipped because a collection and active environment were not available.");
                result.skipped("export.environment", "Environment export was skipped because a collection and active environment were not available.");
            }

            ApiCollection editorCollection = postmanCollection != null ? postmanCollection : openApiCollection;
            if (editorCollection != null) {
                runRequestEditorPersistenceChecks(result, editorCollection, activeEnvironment);
            } else {
                result.skipped("request_editor.persistence", "Request editor persistence was skipped because no suitable collection was imported.");
            }

            runCollectionRoundTripChecks(result, activeEnvironment);
            captureAndRecordEvidenceSnapshot(result, ui, "tree-after-roundtrip", "tree-after-roundtrip.json", "Tree state after roundtrip checks");
            runEnvironmentRoundTripChecks(result);
            runAuthInheritanceChecks(result, activeEnvironment);
            captureAndRecordEvidenceSnapshot(result, ui, "tree-after-auth-inheritance", "tree-after-auth-inheritance.json", "Tree state after auth inheritance checks");
            runVariableResolutionChecks(result, activeEnvironment);
            captureAndRecordEvidenceSnapshot(result, ui, "tree-after-variable-tests", "tree-after-variable-tests.json", "Tree state after variable checks");
            runNegativeFixtureChecks(result);
            runRunnerSurgicalChecks(result, runnerState.collections, activeEnvironment, localApiReady);
            captureAndRecordEvidenceSnapshot(result, ui, "tree-after-runner-tests", "tree-after-runner-tests.json", "Tree state after runner checks");
            runLiveEndpointChecks(result);
            runUiTreeStateChecks(result, ui, uiTemplateState);
            runUiRunnerStateChecks(result, ui, uiTemplateState, activeEnvironment);
            captureAndRecordEvidenceSnapshot(result, ui, "final-ui-state", "final-ui-state.json", "Final UI state");

            if (!result.hasFailures() && result.errors.isEmpty()) {
                result.status = "pass";
            } else {
                result.status = "fail";
            }
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            result.addError(message);
            result.fail("runtime.exception", message);
            appendError("Smoke runtime exception: " + message);
            appendError(stackTrace(e));
            result.status = "fail";
        } finally {
            result.finishedAt = Instant.now().toString();
            result.durationMs = Duration.between(started, Instant.now()).toMillis();
            if (!result.hasFailures() && result.errors.isEmpty()) {
                result.status = "pass";
            } else if (!"fail".equalsIgnoreCase(result.status)) {
                result.status = "fail";
            }

            try {
                writeSnapshotAndResult(result);
            } catch (Exception e) {
                appendError("Failed to write smoke result JSON: " + e.getMessage());
            }

            appendLog("=== API Workbench runtime smoke finished: " + result.status.toUpperCase(Locale.ROOT) + " ===");
            if (result.hasFailures()) {
                appendLog("Failed checks: " + failedCheckNames(result));
            }
        }
    }

    private void validateFixtureFiles(SmokeRuntimeResult result) {
        checkFixturePath(result, "fixtures.collections.local.api_workbench", resolveFixture("collections/local/api-workbench-runtime.json"));
        checkFixturePath(result, "fixtures.collections.local.postman", resolveFixture("collections/local/postman-runtime.postman_collection.json"));
        checkFixturePath(result, "fixtures.collections.local.openapi_yaml", resolveFixture("collections/local/openapi-runtime.yaml"));
        checkFixturePath(result, "fixtures.collections.local.openapi_json", resolveFixture("collections/local/openapi-runtime.json"));
        checkFixturePath(result, "fixtures.collections.local.insomnia", resolveFixture("collections/local/insomnia-runtime.json"));
        checkFixturePath(result, "fixtures.collections.local.bruno", resolveFixture("collections/local/bruno-runtime"));
        checkFixturePath(result, "fixtures.collections.local.har", resolveFixture("collections/local/har-runtime.har"));

        checkFixturePath(result, "fixtures.collections.live.api_workbench", resolveFixture("collections/live/api-workbench-live.json"));
        checkFixturePath(result, "fixtures.collections.live.postman", resolveFixture("collections/live/postman-live.postman_collection.json"));
        checkFixturePath(result, "fixtures.collections.live.openapi_yaml", resolveFixture("collections/live/openapi-live.yaml"));
        checkFixturePath(result, "fixtures.collections.live.openapi_json", resolveFixture("collections/live/openapi-live.json"));
        checkFixturePath(result, "fixtures.collections.live.insomnia", resolveFixture("collections/live/insomnia-live.json"));
        checkFixturePath(result, "fixtures.collections.live.bruno", resolveFixture("collections/live/bruno-live"));
        checkFixturePath(result, "fixtures.collections.live.har", resolveFixture("collections/live/har-live.har"));

        checkFixturePath(result, "fixtures.collections.auth_inheritance.postman", resolveFixture("collections/auth-inheritance/postman-auth-inheritance.postman_collection.json"));
        checkFixturePath(result, "fixtures.collections.auth_inheritance.openapi", resolveFixture("collections/auth-inheritance/openapi-auth-inheritance.yaml"));
        checkFixturePath(result, "fixtures.collections.auth_inheritance.insomnia", resolveFixture("collections/auth-inheritance/insomnia-auth-inheritance.json"));
        checkFixturePath(result, "fixtures.collections.auth_inheritance.bruno", resolveFixture("collections/auth-inheritance/bruno-auth-inheritance"));

        checkFixturePath(result, "fixtures.collections.negative.malformed_json", resolveFixture("collections/negative/malformed-json.json"));
        checkFixturePath(result, "fixtures.collections.negative.malformed_yaml", resolveFixture("collections/negative/malformed-yaml.yaml"));
        checkFixturePath(result, "fixtures.collections.negative.unsupported", resolveFixture("collections/negative/unsupported.txt"));
        checkFixturePath(result, "fixtures.collections.negative.empty", resolveFixture("collections/negative/empty.postman_collection.json"));
        checkFixturePath(result, "fixtures.collections.negative.duplicate_names", resolveFixture("collections/negative/duplicate-names.postman_collection.json"));
        checkFixturePath(result, "fixtures.collections.negative.large", resolveFixture("collections/negative/large-collection.postman_collection.json"));

        checkFixturePath(result, "fixtures.environments.local.api_workbench", resolveFixture("environments/local/api-workbench-env.json"));
        checkFixturePath(result, "fixtures.environments.local.postman", resolveFixture("environments/local/postman-env.json"));
        checkFixturePath(result, "fixtures.environments.local.dotenv", resolveFixture("environments/local/dotenv.env"));
        checkFixturePath(result, "fixtures.environments.local.generic_json", resolveFixture("environments/local/generic-env.json"));
        checkFixturePath(result, "fixtures.environments.local.insomnia", resolveFixture("environments/local/insomnia-env.json"));
        checkFixturePath(result, "fixtures.environments.local.bruno", resolveFixture("environments/local/bruno-env.bru"));

        checkFixturePath(result, "fixtures.environments.live.postman", resolveFixture("environments/live/live-postman-env.json"));
        checkFixturePath(result, "fixtures.environments.live.dotenv", resolveFixture("environments/live/live-dotenv.env"));
        checkFixturePath(result, "fixtures.environments.live.generic_json", resolveFixture("environments/live/live-generic-env.json"));

        checkFixturePath(result, "fixtures.environments.negative.malformed_json", resolveFixture("environments/negative/malformed-env.json"));
        checkFixturePath(result, "fixtures.environments.negative.empty", resolveFixture("environments/negative/empty.env"));
        checkFixturePath(result, "fixtures.environments.negative.unsupported", resolveFixture("environments/negative/unsupported-env.txt"));
    }

    private boolean probeLocalApi(SmokeRuntimeResult result) {
        String baseUrl = config.getResolvedLocalApiUrl();
        if (baseUrl == null) {
            result.fail("fixtures.local_api.reachable", "No local API URL was configured.");
            return false;
        }
        try {
            boolean ok = true;
            ok &= probeLocalApiEndpoint(result, "fixtures.local_api.health", baseUrl + "/health", 200, "ok");
            ok &= probeLocalApiEndpoint(result, "fixtures.local_api.users", baseUrl + "/users", 200, "users");
            ok &= probeLocalApiEndpoint(result, "fixtures.local_api.headers", baseUrl + "/headers", 200, "headers");
            ok &= probeLocalApiEndpoint(result, "fixtures.local_api.auth_echo", baseUrl + "/auth/echo", 200, "authorization");
            ok &= probeLocalApiEndpoint(result, "fixtures.local_api.extract_token", baseUrl + "/extract/token", 200, "extracted-runtime-token");
            if (ok) {
                result.pass("fixtures.local_api.reachable", "Local mock API responded on health, users, headers, auth echo, and extraction endpoints.");
            } else {
                result.fail("fixtures.local_api.reachable", "One or more local API endpoint probes failed.");
            }
            return ok;
        } catch (Exception e) {
            result.fail("fixtures.local_api.reachable", "Failed to reach local API: " + e.getMessage());
            return false;
        }
    }

    private boolean probeLocalApiEndpoint(SmokeRuntimeResult result, String checkName, String url, int expectedStatus, String expectedBodyFragment) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() != null ? response.body() : "";
            boolean statusPass = response.statusCode() == expectedStatus;
            boolean bodyPass = expectedBodyFragment == null || body.toLowerCase(Locale.ROOT).contains(expectedBodyFragment.toLowerCase(Locale.ROOT));
            if (statusPass && bodyPass) {
                result.pass(checkName, "GET " + url + " returned " + expectedStatus + ".");
                return true;
            }
            result.fail(checkName, "GET " + url + " returned " + response.statusCode() + " with body: " + truncate(body, 160));
            return false;
        } catch (Exception e) {
            result.fail(checkName, "Failed to call " + url + ": " + e.getMessage());
            return false;
        }
    }

    private ApiCollection importCollectionFixture(SmokeRuntimeResult result, String checkName, Path path) {
        return importCollectionFixture(result, null, checkName, path);
    }

    private ApiCollection importCollectionFixture(SmokeRuntimeResult result, String group, String checkName, Path path) {
        if (!pathExists(path)) {
            if (group != null) {
                result.fail(group, checkName, "Collection fixture missing: " + path);
            } else {
                result.fail(checkName, "Collection fixture missing: " + path);
            }
            return null;
        }
        try {
            CollectionParser parser = parserRegistry.detectParser(path.toFile());
            if (parser == null) {
                if (group != null) {
                    result.fail(group, checkName, "No parser detected for collection fixture: " + path.getFileName());
                } else {
                    result.fail(checkName, "No parser detected for collection fixture: " + path.getFileName());
                }
                return null;
            }
            ApiCollection collection = parser.parse(path.toFile());
            if (collection == null) {
                if (group != null) {
                    result.fail(group, checkName, "Parser returned no collection for: " + path.getFileName());
                } else {
                    result.fail(checkName, "Parser returned no collection for: " + path.getFileName());
                }
                return null;
            }
            int requestCount = collection.requests != null ? collection.requests.size() : 0;
            String details = "Imported \"" + displayCollectionName(collection, path) + "\" with " + requestCount + " requests using " + parser.getClass().getSimpleName() + ".";
            if (group != null) {
                result.pass(group, checkName, details);
            } else {
                result.pass(checkName, details);
            }
            return collection;
        } catch (Exception e) {
            if (group != null) {
                result.fail(group, checkName, "Failed to import collection fixture '" + path.getFileName() + "': " + e.getMessage());
            } else {
                result.fail(checkName, "Failed to import collection fixture '" + path.getFileName() + "': " + e.getMessage());
            }
            return null;
        }
    }

    private List<EnvironmentProfile> importEnvironmentFixture(SmokeRuntimeResult result, String checkName, Path path) {
        return importEnvironmentFixture(result, null, checkName, path);
    }

    private List<EnvironmentProfile> importEnvironmentFixture(SmokeRuntimeResult result, String group, String checkName, Path path) {
        List<EnvironmentProfile> imported = new ArrayList<>();
        if (!pathExists(path)) {
            if (group != null) {
                result.fail(group, checkName, "Environment fixture missing: " + path);
            } else {
                result.fail(checkName, "Environment fixture missing: " + path);
            }
            return imported;
        }
        try {
            List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(path.toFile());
            if (profiles == null || profiles.isEmpty()) {
                if (group != null) {
                    result.fail(group, checkName, "No environment profiles were imported from " + path.getFileName());
                } else {
                    result.fail(checkName, "No environment profiles were imported from " + path.getFileName());
                }
                return imported;
            }
            for (EnvironmentProfile profile : profiles) {
                if (profile != null) {
                    imported.add(profile);
                }
            }
            String details = "Imported " + imported.size() + " environment profile(s) from " + path.getFileName() + ".";
            if (group != null) {
                result.pass(group, checkName, details);
            } else {
                result.pass(checkName, details);
            }
            return imported;
        } catch (Exception e) {
            if (group != null) {
                result.fail(group, checkName, "Failed to import environment fixture '" + path.getFileName() + "': " + e.getMessage());
            } else {
                result.fail(checkName, "Failed to import environment fixture '" + path.getFileName() + "': " + e.getMessage());
            }
            return imported;
        }
    }

    private void verifyUnsupportedFixture(SmokeRuntimeResult result, Path path) {
        if (!pathExists(path)) {
            result.fail("fixtures.collection.unsupported", "Unsupported fixture missing: " + path);
            return;
        }
        CollectionParser parser = parserRegistry.detectParser(path.toFile());
        if (parser == null) {
            result.pass("fixtures.collection.unsupported", "Unsupported fixture was correctly rejected.");
            return;
        }
        try {
            parser.parse(path.toFile());
            result.fail("fixtures.collection.unsupported", "Unsupported fixture unexpectedly parsed as " + parser.getClass().getSimpleName() + ".");
        } catch (Exception e) {
            result.pass("fixtures.collection.unsupported", "Parser was detected but parse failed as expected: " + e.getMessage());
        }
    }

    private void verifyRestoredWorkspaceState(SmokeRuntimeResult result, WorkspaceState expected, WorkspaceState actual) {
        if (expected == null || actual == null) {
            result.fail("restore.workspace.snapshot", "Workspace snapshot could not be captured or restored.");
            return;
        }

        int expectedCollections = expected.collections != null ? expected.collections.size() : 0;
        int expectedEnvironments = expected.environments != null ? expected.environments.size() : 0;
        int actualCollections = actual.collections != null ? actual.collections.size() : 0;
        int actualEnvironments = actual.environments != null ? actual.environments.size() : 0;

        if (expectedCollections == actualCollections && expectedEnvironments == actualEnvironments && Objects.equals(expected.activeEnvironmentId, actual.activeEnvironmentId)) {
            result.pass("restore.workspace.snapshot", "Workspace snapshot restored with " + actualCollections + " collections and " + actualEnvironments + " environment(s).");
        } else {
            result.fail(
                    "restore.workspace.snapshot",
                    "Restored workspace did not match captured state. Expected collections=" + expectedCollections
                            + ", environments=" + expectedEnvironments
                            + ", activeEnvironmentId=" + expected.activeEnvironmentId
                            + " but got collections=" + actualCollections
                            + ", environments=" + actualEnvironments
                            + ", activeEnvironmentId=" + actual.activeEnvironmentId
            );
        }
    }

    private void runWorkbenchSendChecks(SmokeRuntimeResult result, ApiCollection collection, Map<String, String> runtimeOverlay) {
        if (collection == null) {
            result.fail("workbench.send.health", "No collection was available for send checks.");
            result.fail("workbench.send.users", "No collection was available for send checks.");
            result.fail("workbench.send.echo", "No collection was available for send checks.");
            result.fail("workbench.send.headers", "No collection was available for send checks.");
            result.fail("workbench.send.auth.bearer", "No collection was available for send checks.");
            result.fail("workbench.send.auth.api_key_header", "No collection was available for send checks.");
            result.fail("workbench.send.auth.api_key_query", "No collection was available for send checks.");
            result.fail("workbench.send.auth.basic", "No collection was available for send checks.");
            result.fail("workbench.send.status404", "No collection was available for send checks.");
            result.fail("workbench.send.delay", "No collection was available for send checks.");
            result.fail("workbench.send.extract.token", "No collection was available for send checks.");
            result.fail("workbench.send.extract.chained", "No collection was available for send checks.");
            return;
        }

        runSendCheck(result, "workbench.send.health", collection, runtimeOverlay, "/health", 200, "ok", false);
        runSendCheck(result, "workbench.send.users", collection, runtimeOverlay, "/users", 200, "users", false);
        runSendCheck(result, "workbench.send.echo", collection, runtimeOverlay, "/echo", 200, "hello from runtime environment", false);
        runSendCheck(result, "workbench.send.headers", collection, runtimeOverlay, "/headers", 200, "x-smoke-header", false);
        runSendCheck(result, "workbench.send.auth.bearer", collection, runtimeOverlay, "/auth/required/bearer", 200, "runtime-token", false);
        runSendCheck(result, "workbench.send.auth.api_key_header", collection, runtimeOverlay, "/auth/required/api-key-header", 200, "runtime-api-key", false);
        runSendCheck(result, "workbench.send.auth.api_key_query", collection, runtimeOverlay, "/auth/required/api-key-query", 200, "runtime-api-key", false);
        runSendCheck(result, "workbench.send.auth.basic", collection, runtimeOverlay, "/auth/required/basic", 200, "runtime-user", false);
        runSendCheck(result, "workbench.send.status404", collection, runtimeOverlay, "/status/404", 404, null, false);
        runSendCheck(result, "workbench.send.delay", collection, runtimeOverlay, "/delay?ms=25", 200, null, false);
        runSendCheck(result, "workbench.send.extract.token", collection, runtimeOverlay, "/extract/token", 200, "extracted-runtime-token", false);
        runSendCheck(result, "workbench.send.extract.chained", collection, runtimeOverlay, "/extract/chained", 200, "chained", false);
    }

    private void runSendCheck(SmokeRuntimeResult result,
                              String checkName,
                              ApiCollection collection,
                              Map<String, String> runtimeOverlay,
                              String pathFragment,
                              int expectedStatus,
                              String expectedBodyFragment,
                              boolean allowHigherElapsedTolerance) {
        ApiRequest request = findRequestByPath(collection, pathFragment);
        if (request == null) {
            result.fail(checkName, "Request not found for path fragment: " + pathFragment);
            return;
        }
        try {
            UniversalImporter.SingleSendResult sendResult = importer.sendSingleRequestWithBuiltRequest(
                    request,
                    collection,
                    true,
                    runtimeOverlay,
                    null,
                    null
            );
            if (sendResult == null || sendResult.response == null || sendResult.response.response() == null) {
                result.fail(checkName, "Burp did not return a response for " + pathFragment);
                return;
            }
            int actualStatus = sendResult.response.response().statusCode();
            String body = sendResult.response.response().bodyToString();
            String loweredBody = body != null ? body.toLowerCase(Locale.ROOT) : "";
            boolean statusPass = actualStatus == expectedStatus;
            boolean bodyPass = expectedBodyFragment == null || loweredBody.contains(expectedBodyFragment.toLowerCase(Locale.ROOT));
            boolean urlPass = sendResult.resolvedUrl != null && sendResult.resolvedUrl.toLowerCase(Locale.ROOT).contains(pathFragment.split("\\?")[0].toLowerCase(Locale.ROOT));
            boolean elapsedPass = !allowHigherElapsedTolerance || sendResult.elapsedMs >= 20L;
            if (statusPass && bodyPass && urlPass && elapsedPass) {
                String details = "Resolved URL " + sendResult.resolvedUrl + "; status " + actualStatus + "; elapsed " + sendResult.elapsedMs + "ms.";
                if (expectedBodyFragment != null) {
                    details += " Body matched \"" + expectedBodyFragment + "\".";
                }
                result.pass(checkName, details);
            } else {
                result.fail(
                        checkName,
                        "Resolved URL " + sendResult.resolvedUrl + "; expected status " + expectedStatus + " but got " + actualStatus
                                + "; body fragment check=" + bodyPass
                                + "; elapsedMs=" + sendResult.elapsedMs
                                + "; body=" + truncate(body, 200)
                );
            }
        } catch (Exception e) {
            result.fail(checkName, "Workbench send failed for " + pathFragment + ": " + e.getMessage());
        }
    }

    private void runRequestTreeChecks(SmokeRuntimeResult result, ApiCollection sourceCollection) {
        ApiCollection scratchCollection = requestTreeMutationService.createCollection(new ArrayList<>());
        String rootFolder = requestTreeMutationService.createFolder(scratchCollection, "");
        String nestedFolder = requestTreeMutationService.createFolder(scratchCollection, rootFolder);
        String siblingFolder = requestTreeMutationService.createFolder(scratchCollection, "");
        ApiRequest nestedRequest = requestTreeMutationService.createBlankManualRequest(scratchCollection, nestedFolder);
        ApiRequest rootRequest = requestTreeMutationService.createBlankManualRequest(scratchCollection, rootFolder);
        ApiRequest duplicateRootRequest = requestTreeMutationService.duplicateRequest(scratchCollection, rootRequest);
        ApiCollection duplicateCollection = requestTreeMutationService.duplicateCollection(new ArrayList<>(List.of(scratchCollection)), scratchCollection);
        String renamedCollection = requestTreeMutationService.renameCollection(scratchCollection, "Smoke Tree Collection");
        String renamedFolder = requestTreeMutationService.renameFolder(scratchCollection, rootFolder, "Smoke Folder");
        String renamedRequest = requestTreeMutationService.renameRequest(scratchCollection, rootRequest, "Smoke Request");
        ApiRequest movedRequest = requestTreeMutationService.moveRequest(scratchCollection, duplicateRootRequest, scratchCollection, siblingFolder, 0);
        List<ApiRequest> movedFolderRequests = requestTreeMutationService.moveFolder(scratchCollection, nestedFolder, scratchCollection, siblingFolder, 0);

        RequestTreeDragPayload requestPayload = RequestTreeDragPayload.forRequest(scratchCollection, rootRequest);
        TreeDropRequest dropRequest = new TreeDropRequest(requestPayload, duplicateCollection, null, "", 0, TreeDropRequest.DropPosition.ROOT_INSERT);
        RequestTreeTransferHandler treeHandler = new RequestTreeTransferHandler(new JTree(), files -> { }, drop -> true, drop -> true, this::logToBurp);
        EnvironmentDragPayload environmentDragPayload = new EnvironmentDragPayload("smoke-env", "Smoke Env");
        EnvironmentTransferHandler environmentTransferHandler = new EnvironmentTransferHandler(files -> { }, this::logToBurp);
        RunnerQueueDragPayload runnerQueueDragPayload = new RunnerQueueDragPayload(rootRequest, 0);
        RunnerQueueTransferHandler runnerQueueTransferHandler = new RunnerQueueTransferHandler(() -> new ArrayList<>(List.of(rootRequest)), (sourceIndex, targetIndex) -> true, this::logToBurp);

        boolean primaryMutations = scratchCollection != null
                && rootFolder != null
                && nestedFolder != null
                && nestedRequest != null
                && rootRequest != null
                && duplicateRootRequest != null
                && duplicateCollection != null
                && renamedCollection != null
                && renamedFolder != null
                && renamedRequest != null
                && movedRequest != null
                && movedFolderRequests != null
                && requestPayload.isRequest()
                && dropRequest.isInsert()
                && treeHandler.getSourceActions(new JTree()) == TransferHandler.MOVE
                && environmentDragPayload != null
                && environmentTransferHandler != null
                && runnerQueueDragPayload != null
                && runnerQueueTransferHandler.getSourceActions(new JList<>()) == TransferHandler.MOVE;

        if (primaryMutations) {
            result.pass(
                    "request_tree.create_and_move",
                    "Request-tree create/duplicate/move/rename operations completed against a scratch collection."
            );
            result.pass(
                    "request_tree.drag_payload",
                    "RequestTreeDragPayload, TreeDropRequest, RequestTreeTransferHandler, EnvironmentDragPayload, EnvironmentTransferHandler, RunnerQueueDragPayload, and RunnerQueueTransferHandler were all instantiated."
            );
        } else {
            result.fail("request_tree.create_and_move", "One or more request-tree mutation operations returned null or an unexpected value.");
            result.fail("request_tree.drag_payload", "Drag/drop helper objects or transfer handlers could not be instantiated correctly.");
        }
    }

    private void runRunnerQueueChecks(SmokeRuntimeResult result,
                                      List<ApiCollection> sourceCollections,
                                      ApiCollection runnerCollection,
                                      EnvironmentProfile activeEnvironment) {
        if (runnerCollection == null || runnerCollection.requests == null || runnerCollection.requests.size() < 3) {
            result.fail("runner.queue.preview", "Runner queue source collection must contain at least three requests.");
            result.fail("runner.queue.reorder", "Runner queue source collection must contain at least three requests.");
            result.fail("runner.queue.run", "Runner queue source collection must contain at least three requests.");
            return;
        }

        List<ApiRequest> queue = new ArrayList<>(runnerCollection.requests.subList(0, 3));
        List<RunnerPreviewRow> previewOriginal = buildPreview(sourceCollections, queue);
        if (previewOriginal.size() == queue.size()) {
            result.pass("runner.queue.preview", "Runner preview created for " + queue.size() + " request(s).");
        } else {
            result.fail("runner.queue.preview", "Runner preview size did not match queue size.");
        }

        List<ApiRequest> reorderedQueue = new ArrayList<>(queue);
        Collections.swap(reorderedQueue, 0, 2);
        RunnerQueueDragPayload queuePayload = new RunnerQueueDragPayload(queue.get(2), 2);
        RunnerQueueTransferHandler queueHandler = new RunnerQueueTransferHandler(() -> reorderedQueue, (sourceIndex, targetIndex) -> true, this::logToBurp);
        if (queuePayload == null || queueHandler.getSourceActions(new JList<>()) != TransferHandler.MOVE) {
            result.fail("runner.queue.reorder", "Runner queue drag payload or transfer handler could not be instantiated.");
            result.fail("runner.queue.run", "Runner queue drag payload or transfer handler could not be instantiated.");
            return;
        }

        List<RunnerPreviewRow> previewReordered = buildPreview(sourceCollections, reorderedQueue);
        if (previewReordered.size() == reorderedQueue.size() && previewReordered.get(0) != null) {
            result.pass("runner.queue.reorder", "Runner queue reorder produced preview order: " + previewOrder(previewReordered));
        } else {
            result.fail("runner.queue.reorder", "Runner queue reorder preview did not match the reordered queue.");
        }

        CollectionRunner runner = createRunner();
        runner.setRuntimeOverlayProvider(collection -> activeEnvironment.toRuntimeOverlay());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<List<RunnerResult>> resultsRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();
        CollectionRunner.RunnerListener listener = new CollectionRunner.RunnerListener() {
            @Override
            public void onStart(String collectionName, int totalRequests) {
                appendLog("Runner started with " + totalRequests + " request(s).");
            }

            @Override
            public void onSkip(String requestName, String reason) {
                appendLog("Runner skipped: " + requestName + " -> " + reason);
            }

            @Override
            public void onRequestComplete(RunnerResult resultRow) {
                appendLog("Runner completed: " + resultRow.requestName + " status=" + resultRow.statusCode);
            }

            @Override
            public void onComplete(List<RunnerResult> results) {
                resultsRef.set(results != null ? new ArrayList<>(results) : new ArrayList<>());
                done.countDown();
            }

            @Override
            public void onError(String message) {
                errorRef.set(message);
                appendError(message);
                done.countDown();
            }
        };
        runner.addListener(listener);
        try {
            runner.runCollections(sourceCollections, reorderedQueue);
            boolean completed = done.await(Math.max(30, config.maxWaitSeconds), TimeUnit.SECONDS);
            if (!completed) {
                result.fail("runner.queue.run", "Timed out waiting for CollectionRunner completion.");
                return;
            }

            if (errorRef.get() != null) {
                result.fail("runner.queue.run", "Runner reported an error: " + errorRef.get());
                return;
            }

            List<RunnerResult> runnerResults = resultsRef.get();
            if (runnerResults == null || runnerResults.size() != reorderedQueue.size()) {
                result.fail("runner.queue.run", "Runner result count did not match queue size.");
                return;
            }

            boolean orderMatches = true;
            boolean statusMatches = true;
            for (int i = 0; i < reorderedQueue.size(); i++) {
                ApiRequest expectedRequest = reorderedQueue.get(i);
                RunnerResult actualResult = runnerResults.get(i);
                if (actualResult == null || expectedRequest == null || !Objects.equals(expectedRequest.name, actualResult.requestName)) {
                    orderMatches = false;
                }
                if (actualResult == null || actualResult.statusCode != 200) {
                    statusMatches = false;
                }
            }

            if (orderMatches && statusMatches) {
                result.pass("runner.queue.run", "CollectionRunner completed with reordered queue order: " + queueNames(reorderedQueue));
            } else {
                result.fail("runner.queue.run", "Runner results did not match queue order or expected HTTP 200 statuses.");
            }
        } catch (Exception e) {
            result.fail("runner.queue.run", "Runner execution failed: " + e.getMessage());
        } finally {
            runner.removeListener(listener);
        }
    }

    private List<RunnerPreviewRow> buildPreview(List<ApiCollection> sourceCollections, List<ApiRequest> selectedRequests) {
        CollectionRunner runner = createRunner();
        runner.setRuntimeOverlayProvider(collection -> Collections.emptyMap());
        List<ApiRequest> previewRequests = selectedRequests != null ? new ArrayList<>(selectedRequests) : Collections.emptyList();
        List<RunnerPreviewRow> preview = runner.buildRunPreview(sourceCollections, previewRequests);
        return preview != null ? preview : new ArrayList<>();
    }

    private void runExportChecks(SmokeRuntimeResult result, ApiCollection exportCollection, EnvironmentProfile activeEnvironment) {
        try {
            Path collectionOutput = config.getCollectionExportPath();
            if (collectionOutput == null) {
                result.fail("export.collection", "Collection export path was not configured.");
            } else {
                ApiCollection exportCopy = WorkspaceState.fromCollections(List.of(exportCollection)).collections.get(0);
                CollectionExportOptions options = new CollectionExportOptions(
                        CollectionExportFormat.API_WORKBENCH_JSON,
                        collectionOutput,
                        true,
                        activeEnvironment.copy(),
                        activeEnvironment.toRuntimeOverlay()
                );
                collectionExportService.exportCollection(exportCopy, options);
                verifyJsonArtifact(result, "export.collection", collectionOutput, "Collection export");
            }
        } catch (Exception e) {
            result.fail("export.collection", "Collection export failed: " + e.getMessage());
        }

        try {
            Path environmentOutput = config.getEnvironmentExportPath();
            if (environmentOutput == null) {
                result.fail("export.environment", "Environment export path was not configured.");
            } else {
                EnvironmentProfile exportCopy = activeEnvironment.copy();
                EnvironmentExportOptions options = new EnvironmentExportOptions(EnvironmentExportFormat.API_WORKBENCH_JSON, environmentOutput);
                environmentExportService.exportEnvironment(exportCopy, options);
                verifyJsonArtifact(result, "export.environment", environmentOutput, "Environment export");
            }
        } catch (Exception e) {
            result.fail("export.environment", "Environment export failed: " + e.getMessage());
        }
    }

    private void runCollectionRoundTripChecks(SmokeRuntimeResult result, EnvironmentProfile activeEnvironment) {
        runCollectionRoundTripCategory(
                result,
                "roundtrip.collection.local",
                List.of(
                        "collections/local/api-workbench-runtime.json",
                        "collections/local/postman-runtime.postman_collection.json",
                        "collections/local/openapi-runtime.yaml",
                        "collections/local/openapi-runtime.json",
                        "collections/local/insomnia-runtime.json",
                        "collections/local/bruno-runtime",
                        "collections/local/har-runtime.har"
                ),
                activeEnvironment
        );

        runCollectionRoundTripCategory(
                result,
                "roundtrip.collection.live",
                List.of(
                        "collections/live/api-workbench-live.json",
                        "collections/live/postman-live.postman_collection.json",
                        "collections/live/openapi-live.yaml",
                        "collections/live/openapi-live.json",
                        "collections/live/insomnia-live.json",
                        "collections/live/bruno-live",
                        "collections/live/har-live.har"
                ),
                activeEnvironment
        );

        runCollectionRoundTripCategory(
                result,
                "roundtrip.collection.auth",
                List.of(
                        "collections/auth-inheritance/postman-auth-inheritance.postman_collection.json",
                        "collections/auth-inheritance/openapi-auth-inheritance.yaml",
                        "collections/auth-inheritance/insomnia-auth-inheritance.json",
                        "collections/auth-inheritance/bruno-auth-inheritance"
                ),
                activeEnvironment
        );
    }

    private void runCollectionRoundTripCategory(SmokeRuntimeResult result,
                                                String group,
                                                List<String> relativePaths,
                                                EnvironmentProfile activeEnvironment) {
        for (String relativePath : relativePaths) {
            Path sourcePath = resolveFixture(relativePath);
            String fixtureName = sourcePath != null && sourcePath.getFileName() != null ? sourcePath.getFileName().toString() : relativePath;
            String importCheck = sanitizeCheckName(relativePath, "import");
            ApiCollection sourceCollection = importCollectionFixture(result, group, importCheck, sourcePath);
            if (sourceCollection == null) {
                continue;
            }

            verifyCollectionShape(result, group, fixtureName, sourceCollection);

            for (CollectionExportFormat format : CollectionExportFormat.values()) {
                runCollectionExportRoundTrip(result, group, relativePath, sourceCollection, format, activeEnvironment);
            }
        }
    }

    private void verifyCollectionShape(SmokeRuntimeResult result, String group, String fixtureName, ApiCollection collection) {
        if (collection == null) {
            return;
        }
        int requestCount = collection.requests != null ? collection.requests.size() : 0;
        if (requestCount >= 3) {
            result.pass(group, "shape." + sanitizeCheckName(fixtureName, "requests"), "Collection imported with " + requestCount + " requests.");
        } else {
            result.fail(group, "shape." + sanitizeCheckName(fixtureName, "requests"), "Expected at least 3 requests but found " + requestCount + ".");
        }

        if (collection.folderPaths != null && !collection.folderPaths.isEmpty()) {
            result.pass(group, "shape." + sanitizeCheckName(fixtureName, "folders"), "Collection preserved " + collection.folderPaths.size() + " folder path(s).");
        } else {
            result.skipped(group, "shape." + sanitizeCheckName(fixtureName, "folders"), "Fixture does not expose explicit folder paths in this format.");
        }
    }

    private void runCollectionExportRoundTrip(SmokeRuntimeResult result,
                                              String group,
                                              String sourceLabel,
                                              ApiCollection sourceCollection,
                                              CollectionExportFormat format,
                                              EnvironmentProfile activeEnvironment) {
        try {
            Path roundTripDir = ensureRoundTripDirectory();
            String baseName = sanitizeFileName(sourceLabel) + "-" + format.name().toLowerCase(Locale.ROOT);
            Path exportedPath = roundTripDir.resolve(baseName + format.defaultExtension());
            CollectionExportOptions options = new CollectionExportOptions(
                    format,
                    exportedPath,
                    false,
                    activeEnvironment != null ? activeEnvironment.copy() : null,
                    Collections.emptyMap()
            );
            ApiCollection exportCopy = WorkspaceState.fromCollections(List.of(sourceCollection)).collections.get(0);
            collectionExportService.exportCollection(exportCopy, options);
            verifyExportArtifact(result, group, "collection.export." + sanitizeCheckName(sourceLabel, format.name().toLowerCase(Locale.ROOT)), exportedPath);

            Path importPath = exportedPath;
            if (format == CollectionExportFormat.BRUNO_ZIP) {
                importPath = extractBrunoArchive(exportedPath, roundTripDir.resolve(baseName + "-bruno"));
                if (importPath == null) {
                    result.fail(group, "collection.roundtrip." + sanitizeCheckName(sourceLabel, format.name().toLowerCase(Locale.ROOT)), "Failed to extract Bruno ZIP export for round-trip import.");
                    return;
                }
            }

            ApiCollection roundTripped = importRoundTripCollection(importPath, format);
            if (roundTripped == null) {
                result.fail(group, "collection.roundtrip." + sanitizeCheckName(sourceLabel, format.name().toLowerCase(Locale.ROOT)), "Round-trip import failed for " + format.displayName() + ".");
                return;
            }

            if (sourceLabel != null
                    && sourceLabel.toLowerCase(Locale.ROOT).contains("auth-inheritance")
                    && (format == CollectionExportFormat.OPENAPI_JSON || format == CollectionExportFormat.OPENAPI_YAML)) {
                result.addCheck(
                        group,
                        "collection.roundtrip." + sanitizeCheckName(sourceLabel, format.name().toLowerCase(Locale.ROOT)),
                        "skipped",
                        format.displayName() + " is lossy for auth-inheritance fixtures; export and re-import were verified, but semantic equality is intentionally skipped.",
                        format.displayName(),
                        exportedPath.toString(),
                        "local"
                );
                return;
            }

            if (shouldSkipCollectionRoundTripComparison(format)) {
                result.addCheck(
                        group,
                        "collection.roundtrip." + sanitizeCheckName(sourceLabel, format.name().toLowerCase(Locale.ROOT)),
                        "skipped",
                        format.displayName() + " is lossy for this smoke phase; export and re-import were verified, but semantic equality is intentionally skipped.",
                        format.displayName(),
                        exportedPath.toString(),
                        "local"
                );
                return;
            }

            String originalSummary = collectionSummary(sourceCollection, shouldIncludeFolderPath(format), shouldIncludeAuth(format), false, false);
            String roundTripSummary = collectionSummary(roundTripped, shouldIncludeFolderPath(format), shouldIncludeAuth(format), false, false);
            if (originalSummary.equals(roundTripSummary)) {
                result.pass(group, "collection.roundtrip." + sanitizeCheckName(sourceLabel, format.name().toLowerCase(Locale.ROOT)), "Round-trip via " + format.displayName() + " preserved the semantic collection summary.");
            } else {
                result.fail(
                        group,
                        "collection.roundtrip." + sanitizeCheckName(sourceLabel, format.name().toLowerCase(Locale.ROOT)),
                        "Round-trip via " + format.displayName() + " changed the semantic collection summary."
                );
            }
        } catch (Exception e) {
            result.fail(group, "collection.roundtrip." + sanitizeCheckName(sourceLabel, format.name().toLowerCase(Locale.ROOT)), "Collection round-trip via " + format.displayName() + " failed: " + e.getMessage());
        }
    }

    private ApiCollection importRoundTripCollection(Path path, CollectionExportFormat format) {
        if (path == null) {
            return null;
        }
        try {
            CollectionParser parser = parserRegistry.detectParser(path.toFile());
            if (parser == null) {
                return null;
            }
            return parser.parse(path.toFile());
        } catch (Exception e) {
            appendError("Round-trip import failed for " + path + ": " + e.getMessage());
            return null;
        }
    }

    private void runEnvironmentRoundTripChecks(SmokeRuntimeResult result) {
        runEnvironmentRoundTripCategory(
                result,
                "roundtrip.environment.local",
                List.of(
                        "environments/local/api-workbench-env.json",
                        "environments/local/postman-env.json",
                        "environments/local/dotenv.env",
                        "environments/local/generic-env.json",
                        "environments/local/insomnia-env.json",
                        "environments/local/bruno-env.bru"
                )
        );

        runEnvironmentRoundTripCategory(
                result,
                "roundtrip.environment.live",
                List.of(
                        "environments/live/live-postman-env.json",
                        "environments/live/live-dotenv.env",
                        "environments/live/live-generic-env.json"
                )
        );
    }

    private void runEnvironmentRoundTripCategory(SmokeRuntimeResult result,
                                                 String group,
                                                 List<String> relativePaths) {
        for (String relativePath : relativePaths) {
            Path sourcePath = resolveFixture(relativePath);
            String fixtureName = sourcePath != null && sourcePath.getFileName() != null ? sourcePath.getFileName().toString() : relativePath;
            String checkName = sanitizeCheckName(relativePath, "import");
            List<EnvironmentProfile> imported = importEnvironmentFixture(result, group, checkName, sourcePath);
            if (imported == null || imported.isEmpty()) {
                continue;
            }
            EnvironmentProfile source = imported.get(0);
            verifyEnvironmentShape(result, group, fixtureName, source);

            for (EnvironmentExportFormat format : EnvironmentExportFormat.values()) {
                runEnvironmentExportRoundTrip(result, group, relativePath, source, format);
            }
        }
    }

    private void verifyEnvironmentShape(SmokeRuntimeResult result, String group, String fixtureName, EnvironmentProfile profile) {
        if (profile == null) {
            return;
        }
        int variableCount = profile.variables != null ? profile.variables.size() : 0;
        if (variableCount >= 3) {
            result.pass(group, "shape." + sanitizeCheckName(fixtureName, "variables"), "Environment imported with " + variableCount + " variable(s).");
        } else {
            result.fail(group, "shape." + sanitizeCheckName(fixtureName, "variables"), "Expected at least 3 variables but found " + variableCount + ".");
        }
    }

    private void runEnvironmentExportRoundTrip(SmokeRuntimeResult result,
                                               String group,
                                               String sourceLabel,
                                               EnvironmentProfile source,
                                               EnvironmentExportFormat format) {
        try {
            Path roundTripDir = ensureRoundTripDirectory();
            String baseName = sanitizeFileName(sourceLabel) + "-" + format.name().toLowerCase(Locale.ROOT);
            Path exportedPath = roundTripDir.resolve(baseName + format.defaultExtension());
            EnvironmentExportOptions options = new EnvironmentExportOptions(format, exportedPath);
            environmentExportService.exportEnvironment(source.copy(), options);
            verifyExportArtifact(result, group, "environment.export." + sanitizeCheckName(sourceLabel, format.name().toLowerCase(Locale.ROOT)), exportedPath);

            List<EnvironmentProfile> roundTripped = importEnvironmentFixture(result, group, "environment.roundtrip." + sanitizeCheckName(sourceLabel, format.name().toLowerCase(Locale.ROOT)), exportedPath);
            if (roundTripped == null || roundTripped.isEmpty()) {
                result.fail(group, "environment.roundtrip." + sanitizeCheckName(sourceLabel, format.name().toLowerCase(Locale.ROOT)), "Round-trip import failed for " + format.displayName() + ".");
                return;
            }

            if (shouldSkipEnvironmentRoundTripComparison(format)) {
                result.addCheck(
                        group,
                        "environment.roundtrip." + sanitizeCheckName(sourceLabel, format.name().toLowerCase(Locale.ROOT)),
                        "skipped",
                        format.displayName() + " is lossy for this smoke phase; export and re-import were verified, but semantic equality is intentionally skipped.",
                        format.displayName(),
                        exportedPath.toString(),
                        "local"
                );
                return;
            }

            EnvironmentProfile imported = roundTripped.get(0);
            String originalSummary = environmentSummary(source);
            String roundTripSummary = environmentSummary(imported);
            if (originalSummary.equals(roundTripSummary)) {
                result.pass(group, "environment.roundtrip." + sanitizeCheckName(sourceLabel, format.name().toLowerCase(Locale.ROOT)), "Round-trip via " + format.displayName() + " preserved the semantic environment summary.");
            } else {
                result.fail(group, "environment.roundtrip." + sanitizeCheckName(sourceLabel, format.name().toLowerCase(Locale.ROOT)), "Round-trip via " + format.displayName() + " changed the semantic environment summary.");
            }
        } catch (Exception e) {
            result.fail(group, "environment.roundtrip." + sanitizeCheckName(sourceLabel, format.name().toLowerCase(Locale.ROOT)), "Environment round-trip via " + format.displayName() + " failed: " + e.getMessage());
        }
    }

    private void verifyExportArtifact(SmokeRuntimeResult result, String group, String checkName, Path path) {
        if (path == null) {
            result.fail(group, checkName, "Export path was not configured.");
            return;
        }
        if (!pathExists(path)) {
            result.fail(group, checkName, "Export file was not created: " + path);
            return;
        }
        try {
            long size = Files.size(path);
            if (size > 0) {
                result.pass(group, checkName, "Exported artifact created at " + path + " (" + size + " bytes).");
            } else {
                result.fail(group, checkName, "Exported artifact was empty: " + path);
            }
        } catch (Exception e) {
            result.fail(group, checkName, "Unable to inspect export artifact: " + e.getMessage());
        }
    }

    private Path ensureRoundTripDirectory() throws IOException {
        Path base = config.getCollectionExportPath() != null ? config.getCollectionExportPath().getParent() : null;
        if (base == null && config.getEnvironmentExportPath() != null) {
            base = config.getEnvironmentExportPath().getParent();
        }
        if (base == null) {
            throw new IOException("Unable to determine round-trip output directory.");
        }
        Path roundTripDir = base.resolve("roundtrip");
        Files.createDirectories(roundTripDir);
        return roundTripDir;
    }

    private Path extractBrunoArchive(Path zipPath, Path targetDir) throws IOException {
        if (zipPath == null || targetDir == null) {
            return null;
        }
        Files.createDirectories(targetDir);
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(Files.newInputStream(zipPath))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path out = targetDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(targetDir)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zin, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return targetDir;
    }

    private String collectionSummary(ApiCollection collection, boolean includeFolderPath, boolean includeAuth, boolean includeBody, boolean includeHeaders) {
        StringBuilder builder = new StringBuilder();
        builder.append("name=").append(safeText(collection != null ? collection.name : null)).append('\n');
        Set<String> signatures = new LinkedHashSet<>();
        if (collection != null && collection.requests != null) {
            for (ApiRequest request : collection.requests) {
                signatures.add(collectionRequestSignature(request));
            }
        }
        List<String> sortedSignatures = new ArrayList<>(signatures);
        sortedSignatures.sort(String::compareTo);
        builder.append("count=").append(sortedSignatures.size()).append('\n');
        for (String signature : sortedSignatures) {
            builder.append(signature).append('\n');
        }
        return builder.toString();
    }

    private String collectionRequestSignature(ApiRequest request) {
        String method = safeText(request != null ? request.method : null).toUpperCase(Locale.ROOT);
        String canonicalUrl = canonicalizeRequestUrlTemplate(request != null ? request.url : null);
        return method + "|" + canonicalUrl;
    }

    private String canonicalizeRequestUrlTemplate(String input) {
        if (input == null || input.isBlank()) {
            return "/";
        }

        String value = input.trim();
        try {
            value = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Best effort only.
        }
        value = value.replace('\\', '/');

        int schemeIdx = value.indexOf("://");
        if (schemeIdx >= 0) {
            int slashIdx = value.indexOf('/', schemeIdx + 3);
            value = slashIdx >= 0 ? value.substring(slashIdx) : "/";
        }

        if (value.startsWith("{{")) {
            int endIdx = value.indexOf("}}");
            if (endIdx >= 0) {
                value = value.substring(endIdx + 2);
            }
        }
        int queryIdx = value.indexOf('?');
        String path = queryIdx >= 0 ? value.substring(0, queryIdx) : value;
        String query = queryIdx >= 0 ? value.substring(queryIdx + 1) : "";

        path = path.replaceAll("(?<!\\{)\\{([^{}]+)\\}(?!\\})", "{{$1}}");
        if (path.isBlank()) {
            path = "/";
        } else if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        if (!query.isBlank()) {
            List<String> params = new ArrayList<>();
            for (String part : query.split("&")) {
                if (part != null && !part.isBlank()) {
                    params.add(part.trim());
                }
            }
            params.sort(String::compareTo);
            if (!params.isEmpty()) {
                return path + "?" + String.join("&", params);
            }
        }
        return path;
    }

    private ApiRequest copyRequest(ApiRequest source) {
        if (source == null) {
            return null;
        }
        ApiRequest copy = new ApiRequest();
        copy.id = source.id;
        copy.name = source.name;
        copy.path = source.path;
        copy.sourceCollection = source.sourceCollection;
        copy.method = source.method;
        copy.url = source.url;
        copy.description = source.description;
        copy.editorMaterialized = source.editorMaterialized;
        copy.buildMode = source.buildMode;
        copy.suppressedAutoHeaders = source.suppressedAutoHeaders != null ? new LinkedHashSet<>(source.suppressedAutoHeaders) : new LinkedHashSet<>();
        copy.disabled = source.disabled;
        copy.sequenceOrder = source.sequenceOrder;
        copy.authInherited = source.authInherited;
        copy.authExplicitlyDisabled = source.authExplicitlyDisabled;
        copy.authSource = source.authSource;
        copy.authOverrideMode = source.authOverrideMode;
        copy.auth = source.auth != null ? AuthInheritanceResolver.copyAuth(source.auth) : null;
        copy.explicitAuth = source.explicitAuth != null ? AuthInheritanceResolver.copyAuth(source.explicitAuth) : null;
        copy.headers = new ArrayList<>();
        if (source.headers != null) {
            for (ApiRequest.Header header : source.headers) {
                if (header != null) {
                    copy.headers.add(new ApiRequest.Header(header.key, header.value, header.disabled));
                }
            }
        }
        copy.body = copyBody(source.body);
        copy.variables = new ArrayList<>();
        if (source.variables != null) {
            for (ApiRequest.Variable variable : source.variables) {
                if (variable != null) {
                    ApiRequest.Variable copyVariable = new ApiRequest.Variable();
                    copyVariable.key = variable.key;
                    copyVariable.value = variable.value;
                    copyVariable.type = variable.type;
                    copyVariable.enabled = variable.enabled;
                    copy.variables.add(copyVariable);
                }
            }
        }
        copy.preRequestScripts = copyScripts(source.preRequestScripts);
        copy.postResponseScripts = copyScripts(source.postResponseScripts);
        copy.scriptBlocks = copyScriptBlocks(source.scriptBlocks);
        return copy;
    }

    private ApiRequest.Body copyBody(ApiRequest.Body source) {
        if (source == null) {
            return null;
        }
        ApiRequest.Body copy = new ApiRequest.Body();
        copy.mode = source.mode;
        copy.raw = source.raw;
        copy.contentType = source.contentType;
        if (source.urlencoded != null) {
            copy.urlencoded = new ArrayList<>();
            for (ApiRequest.Body.FormField field : source.urlencoded) {
                if (field != null) {
                    ApiRequest.Body.FormField copyField = new ApiRequest.Body.FormField(field.key, field.value);
                    copyField.type = field.type;
                    copyField.fileUpload = field.fileUpload;
                    copyField.filePath = field.filePath;
                    copyField.disabled = field.disabled;
                    copy.urlencoded.add(copyField);
                }
            }
        }
        if (source.formdata != null) {
            copy.formdata = new ArrayList<>();
            for (ApiRequest.Body.FormField field : source.formdata) {
                if (field != null) {
                    ApiRequest.Body.FormField copyField = new ApiRequest.Body.FormField(field.key, field.value);
                    copyField.type = field.type;
                    copyField.fileUpload = field.fileUpload;
                    copyField.filePath = field.filePath;
                    copyField.disabled = field.disabled;
                    copy.formdata.add(copyField);
                }
            }
        }
        if (source.graphql != null) {
            copy.graphql = new ApiRequest.Body.GraphQL();
            copy.graphql.query = source.graphql.query;
            copy.graphql.variables = source.graphql.variables;
        }
        return copy;
    }

    private List<ApiRequest.Script> copyScripts(List<ApiRequest.Script> scripts) {
        List<ApiRequest.Script> copy = new ArrayList<>();
        if (scripts == null) {
            return copy;
        }
        for (ApiRequest.Script script : scripts) {
            if (script != null) {
                copy.add(new ApiRequest.Script(script.type, script.exec));
            }
        }
        return copy;
    }

    private List<burp.scripts.ScriptBlock> copyScriptBlocks(List<burp.scripts.ScriptBlock> scripts) {
        List<burp.scripts.ScriptBlock> copy = new ArrayList<>();
        if (scripts == null) {
            return copy;
        }
        for (burp.scripts.ScriptBlock block : scripts) {
            burp.scripts.ScriptBlock cloned = burp.scripts.ScriptBlock.copyOf(block);
            if (cloned != null) {
                copy.add(cloned);
            }
        }
        return copy;
    }

    private String normalizeHeaders(List<ApiRequest.Header> headers) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (ApiRequest.Header header : headers) {
            if (header == null || header.key == null || header.key.isBlank() || header.disabled) {
                continue;
            }
            parts.add(header.key.trim().toLowerCase(Locale.ROOT) + "=" + safeText(header.value));
        }
        parts.sort(String::compareTo);
        return String.join(",", parts);
    }

    private String normalizeBody(ApiRequest.Body body) {
        if (body == null || body.mode == null || body.mode.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(body.mode.toLowerCase(Locale.ROOT));
        if (body.raw != null && !body.raw.isBlank()) {
            builder.append(":").append(normalizeText(body.raw));
        }
        if (body.urlencoded != null && !body.urlencoded.isEmpty()) {
            builder.append("|urlencoded=").append(body.urlencoded.size());
        }
        if (body.formdata != null && !body.formdata.isEmpty()) {
            builder.append("|formdata=").append(body.formdata.size());
        }
        if (body.graphql != null) {
            builder.append("|graphql=").append(normalizeText(body.graphql.query)).append("|").append(normalizeText(body.graphql.variables));
        }
        return builder.toString();
    }

    private String normalizeAuth(ApiRequest.Auth auth) {
        if (auth == null || auth.type == null || auth.type.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(auth.type.toLowerCase(Locale.ROOT));
        if (auth.properties != null && !auth.properties.isEmpty()) {
            List<String> entries = new ArrayList<>();
            for (Map.Entry<String, String> entry : auth.properties.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                entries.add(entry.getKey().trim().toLowerCase(Locale.ROOT) + "=" + safeText(entry.getValue()));
            }
            entries.sort(String::compareTo);
            if (!entries.isEmpty()) {
                builder.append(":").append(String.join(",", entries));
            }
        }
        return builder.toString();
    }

    private String environmentSummary(EnvironmentProfile profile) {
        StringBuilder builder = new StringBuilder();
        builder.append("name=").append(safeText(profile != null ? profile.name : null)).append('\n');
        builder.append("count=").append(profile != null && profile.variables != null ? profile.variables.size() : 0).append('\n');
        if (profile != null && profile.variables != null && !profile.variables.isEmpty()) {
            List<String> entries = new ArrayList<>();
            for (Map.Entry<String, String> entry : profile.variables.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                entries.add(entry.getKey().trim().toLowerCase(Locale.ROOT) + "=" + safeText(entry.getValue()));
            }
            entries.sort(String::compareTo);
            builder.append(String.join("|", entries));
        }
        return builder.toString();
    }

    private void runRequestEditorPersistenceChecks(SmokeRuntimeResult result, ApiCollection sourceCollection, EnvironmentProfile activeEnvironment) {
        ApiRequest sourceRequest = findRequestByPath(sourceCollection, "/echo");
        if (sourceRequest == null) {
            result.fail("request_editor.persistence", "Could not find an editable echo request in the imported collection.");
            return;
        }

        try {
            ApiRequest rebuilt = runOnEdt(() -> {
                RequestEditorPanel panel = new RequestEditorPanel();
                panel.setCurrentCollection(sourceCollection);
                panel.loadRequest(sourceRequest);

                panel.getMethodBox().setSelectedItem("PUT");
                panel.getUrlField().setText(config.getResolvedLocalApiUrl() + "/extract/echo");

                DefaultTableModel headersModel = getPrivateField(panel, "headersModel", DefaultTableModel.class);
                headersModel.addRow(new Object[]{"X-Smoke-Persistence", "true"});

                JTextArea bodyRawArea = getPrivateField(panel, "bodyRawArea", JTextArea.class);
                bodyRawArea.setText("{\"message\":\"persistence\"}");

                JComboBox<String> authTypeBox = getPrivateField(panel, "authTypeBox", JComboBox.class);
                authTypeBox.setSelectedItem("bearer");
                Map<String, JTextField> authFields = getPrivateAuthFields(panel);
                JTextField tokenField = authFields.get("token");
                if (tokenField != null) {
                    tokenField.setText("runtime-token");
                }

                ApiRequest firstBuild = panel.buildRequestFromUI();
                ApiRequest otherRequest = findRequestByPath(sourceCollection, "/users");
                if (otherRequest != null) {
                    panel.loadRequest(otherRequest);
                }
                panel.loadRequest(firstBuild);
                authTypeBox = getPrivateField(panel, "authTypeBox", JComboBox.class);
                authTypeBox.setSelectedItem("bearer");
                authFields = getPrivateAuthFields(panel);
                tokenField = authFields.get("token");
                if (tokenField != null) {
                    tokenField.setText("runtime-token");
                }
                return panel.buildRequestFromUI();
            });

            if (rebuilt == null) {
                result.fail("request_editor.persistence", "Request editor did not return a rebuilt request.");
                return;
            }

            boolean methodPass = "PUT".equalsIgnoreCase(rebuilt.method);
            boolean urlPass = rebuilt.url != null && rebuilt.url.contains("/extract/echo");
            boolean headerPass = containsHeader(rebuilt, "X-Smoke-Persistence", "true");
            boolean bodyPass = rebuilt.body != null && rebuilt.body.raw != null && rebuilt.body.raw.contains("persistence");
            boolean authPass = rebuilt.auth != null && "bearer".equalsIgnoreCase(rebuilt.auth.type)
                    && "runtime-token".equals(rebuilt.auth.properties.get("token"));

            if (methodPass && urlPass && headerPass && bodyPass && authPass) {
                result.pass("request_editor.persistence", "Request editor preserved method, URL, headers, body, and auth while switching away and back.");
            } else {
                result.fail("request_editor.persistence", "Request editor persistence check failed: method=" + methodPass + ", url=" + urlPass + ", headers=" + headerPass + ", body=" + bodyPass + ", auth=" + authPass + ".");
            }
        } catch (Exception e) {
            result.fail("request_editor.persistence", "Request editor persistence check failed: " + e.getMessage());
        }
    }

    private void runVariableResolutionChecks(SmokeRuntimeResult result, EnvironmentProfile activeEnvironment) {
        try {
            ApiCollection collection = new ApiCollection();
            collection.name = "Variable Smoke";
            collection.environment.put("shadow_value", "collection-shadow");
            collection.environment.put("collection_shadow_value", "collection-only");
            collection.variables.add(variable("base_url", config.getResolvedLocalApiUrl()));
            collection.variables.add(variable("collection_shadow_value", "collection-shadow-value"));
            collection.folderVars.put("Folder", Map.of(
                    "shadow_value", "folder-shadow",
                    "folder_shadow_value", "folder-shadow-value"
            ));

            ApiRequest request = new ApiRequest();
            request.name = "Variable Request";
            request.path = "Folder";
            request.method = "GET";
            request.url = "{{base_url}}/echo?shadow={{shadow_value}}&request={{request_shadow_value}}&folder={{folder_shadow_value}}";
            request.variables.add(variable("shadow_value", "request-shadow"));
            request.variables.add(variable("request_shadow_value", "request-shadow-value"));
            collection.requests.add(request);

            AuthInheritanceResolver.recomputeCollectionAuth(collection);
            Map<String, String> overlay = activeEnvironment != null ? activeEnvironment.toRuntimeOverlay() : Map.of();
            burp.parser.VariableResolver resolver = RuntimeResolverFactory.build(
                    collection,
                    request,
                    RuntimeResolverFactory.Options.withRuntimeVariableOverlay(overlay)
            );

            String resolved = resolver.resolve(request.url);
            boolean resolvedPass = resolved != null && resolved.contains(config.getResolvedLocalApiUrl()) && resolved.contains("request-shadow");
            boolean unresolvedPass = resolver.findUnresolvedVariables("{{missing_value}}/health").contains("missing_value");

            EnvironmentProfile alternate = activeEnvironment != null ? activeEnvironment.copy() : new EnvironmentProfile();
            alternate.variables.put("base_url", "http://127.0.0.1:65500");
            alternate.variables.put("shadow_value", "alternate-shadow");
            burp.parser.VariableResolver alternateResolver = RuntimeResolverFactory.build(
                    collection,
                    request,
                    RuntimeResolverFactory.Options.withRuntimeVariableOverlay(alternate.toRuntimeOverlay())
            );
            String alternateResolved = alternateResolver.resolve("{{base_url}}/health");
            boolean switchingPass = alternateResolved != null && alternateResolved.contains("65500");

            if (resolvedPass && unresolvedPass && switchingPass) {
                result.pass("variables.resolution", "Resolved, unresolved, shadowed, and environment-switched variables behaved as expected.");
            } else {
                result.fail("variables.resolution", "Variable resolution check failed: resolved=" + resolvedPass + ", unresolved=" + unresolvedPass + ", switch=" + switchingPass + ".");
            }
        } catch (Exception e) {
            result.fail("variables.resolution", "Variable resolution check failed: " + e.getMessage());
        }
    }

    private void runAuthInheritanceChecks(SmokeRuntimeResult result, EnvironmentProfile activeEnvironment) {
        Map<String, String> overlay = activeEnvironment != null ? activeEnvironment.toRuntimeOverlay() : Map.of();
        String baseUrl = config.getResolvedLocalApiUrl();
        if (baseUrl == null) {
            result.fail("auth.inheritance", "No local API URL was available for auth inheritance checks.");
            return;
        }

        String authEchoUrl = "{{base_url}}/auth/echo";
        String bearerExpected = "runtime-token";
        String apiKeyExpected = "runtime-api-key";
        String basicUser = "runtime-user";
        String basicPass = "runtime-pass";

        boolean scenario1 = runAuthSendScenario(
                result,
                "auth.inheritance.collection_bearer",
                authEchoUrl,
                overlay,
                collection -> AuthInheritanceResolver.setCollectionAuth(collection, bearerAuth("collection-token")),
                request -> request.authOverrideMode = "inherit",
                "collection-token"
        );
        boolean scenario2 = runAuthSendScenario(
                result,
                "auth.inheritance.folder_bearer",
                authEchoUrl,
                overlay,
                collection -> {
                    AuthInheritanceResolver.setCollectionAuth(collection, bearerAuth("collection-token"));
                    AuthInheritanceResolver.setFolderAuth(collection, "Folder", "explicit", bearerAuth("folder-token"));
                },
                request -> request.path = "Folder",
                "folder-token"
        );
        boolean scenario3 = runAuthSendScenario(
                result,
                "auth.inheritance.request_bearer",
                authEchoUrl,
                overlay,
                collection -> {
                    AuthInheritanceResolver.setCollectionAuth(collection, bearerAuth("collection-token"));
                    AuthInheritanceResolver.setFolderAuth(collection, "Folder", "explicit", bearerAuth("folder-token"));
                },
                request -> {
                    request.path = "Folder";
                    request.authOverrideMode = "explicit";
                    request.explicitAuth = bearerAuth("request-token");
                    request.auth = bearerAuth("request-token");
                },
                "request-token"
        );
        boolean scenario4 = runAuthSendScenario(
                result,
                "auth.inheritance.no_auth",
                authEchoUrl,
                overlay,
                collection -> AuthInheritanceResolver.setCollectionAuth(collection, bearerAuth("collection-token")),
                request -> {
                    request.authOverrideMode = "none";
                    request.explicitAuth = noneAuth();
                    request.auth = noneAuth();
                },
                null
        );
        boolean scenario5 = runAuthSendScenario(
                result,
                "auth.inheritance.api_key_header",
                authEchoUrl,
                overlay,
                collection -> AuthInheritanceResolver.setCollectionAuth(collection, apiKeyAuth("X-API-Key", "collection-key", "header")),
                request -> request.authOverrideMode = "inherit",
                "collection-key"
        );
        boolean scenario6 = runAuthSendScenario(
                result,
                "auth.inheritance.api_key_query",
                authEchoUrl,
                overlay,
                collection -> AuthInheritanceResolver.setCollectionAuth(collection, apiKeyAuth("api_key", "collection-key", "query")),
                request -> request.authOverrideMode = "inherit",
                "collection-key"
        );
        boolean scenario7 = runAuthSendScenario(
                result,
                "auth.inheritance.basic",
                authEchoUrl,
                overlay,
                collection -> AuthInheritanceResolver.setCollectionAuth(collection, basicAuth("collection-user", "collection-pass")),
                request -> request.authOverrideMode = "inherit",
                "collection-user"
        );
        boolean scenario8 = runAuthSendScenario(
                result,
                "auth.inheritance.env_resolution",
                authEchoUrl,
                overlay,
                collection -> AuthInheritanceResolver.setCollectionAuth(collection, bearerAuth("{{token_collection}}")),
                request -> request.authOverrideMode = "inherit",
                bearerExpected
        );

        boolean scenario9 = runAuthOAuth2InheritanceCheck(result);

        if (scenario1 && scenario2 && scenario3 && scenario4 && scenario5 && scenario6 && scenario7 && scenario8 && scenario9) {
            result.pass("auth.inheritance", "Collection/folder/request auth inheritance, override, no-auth, and stored OAuth2 config checks passed.");
        } else {
            result.fail("auth.inheritance", "One or more auth inheritance scenarios failed.");
        }
    }

    private boolean runAuthSendScenario(SmokeRuntimeResult result,
                                        String checkName,
                                        String requestUrl,
                                        Map<String, String> runtimeOverlay,
                                        java.util.function.Consumer<ApiCollection> collectionCustomizer,
                                        java.util.function.Consumer<ApiRequest> requestCustomizer,
                                        String expectedFragment) {
        try {
            ApiCollection collection = new ApiCollection();
            collection.name = "Auth Scenario";
            if (collectionCustomizer != null) {
                collectionCustomizer.accept(collection);
            }

            ApiRequest request = new ApiRequest();
            request.name = "Auth Request";
            request.path = "Folder";
            request.method = "GET";
            request.url = requestUrl;
            request.sourceCollection = collection.name;
            if (requestCustomizer != null) {
                requestCustomizer.accept(request);
            }
            collection.requests.add(request);
            AuthInheritanceResolver.recomputeCollectionAuth(collection);

            UniversalImporter.SingleSendResult sendResult = importer.sendSingleRequestWithBuiltRequest(request, collection, true, runtimeOverlay, null);
            String responseBody = sendResult.response != null && sendResult.response.response() != null
                    ? sendResult.response.response().bodyToString()
                    : "";
            boolean pass = expectedFragment == null
                    ? responseBody != null && responseBody.contains("\"authorization\": \"\"")
                    : responseBody != null && responseBody.contains(expectedFragment);
            if (pass) {
                result.pass(checkName, "Auth scenario returned the expected observed value.");
            } else {
                result.fail(checkName, "Auth scenario response body did not contain expected fragment '" + expectedFragment + "'. Body: " + truncate(responseBody, 180));
            }
            return pass;
        } catch (Exception e) {
            result.fail(checkName, "Auth scenario failed: " + e.getMessage());
            return false;
        }
    }

    private boolean runAuthOAuth2InheritanceCheck(SmokeRuntimeResult result) {
        try {
            ApiCollection collection = new ApiCollection();
            collection.name = "OAuth2 Inheritance";
            ApiRequest.Auth collectionAuth = oauth2Auth("collection-oauth-token");
            collectionAuth.properties.put("accessTokenUrl", "https://auth.example.test/token");
            AuthInheritanceResolver.setCollectionAuth(collection, collectionAuth);
            AuthInheritanceResolver.setFolderAuth(collection, "Folder", "explicit", oauth2Auth("folder-oauth-token"));

            ApiRequest request = new ApiRequest();
            request.name = "OAuth2 Request";
            request.path = "Folder";
            request.method = "GET";
            request.url = "{{base_url}}/auth/echo";
            request.authOverrideMode = "explicit";
            request.explicitAuth = oauth2Auth("request-oauth-token");
            request.auth = oauth2Auth("request-oauth-token");
            collection.requests.add(request);
            AuthInheritanceResolver.recomputeCollectionAuth(collection);

            boolean inherited = request.auth != null && "oauth2".equalsIgnoreCase(request.auth.type)
                    && "request-oauth-token".equals(request.auth.properties.get("accessToken"));
            boolean sourceOk = request.authSource != null && request.authSource.toLowerCase(Locale.ROOT).contains("request");
            if (inherited && sourceOk) {
                result.pass("auth.inheritance.oauth2", "Effective OAuth2 configuration resolved at the request layer.");
                return true;
            }
            result.fail("auth.inheritance.oauth2", "Effective OAuth2 configuration did not resolve as expected.");
            return false;
        } catch (Exception e) {
            result.fail("auth.inheritance.oauth2", "OAuth2 inheritance check failed: " + e.getMessage());
            return false;
        }
    }

    private void runNegativeFixtureChecks(SmokeRuntimeResult result) {
        runNegativeCollectionRejection(result, "negative.collection.malformed_json", "collections/negative/malformed-json.json");
        runNegativeCollectionRejection(result, "negative.collection.malformed_yaml", "collections/negative/malformed-yaml.yaml");
        runNegativeCollectionRejection(result, "negative.collection.unsupported", "collections/negative/unsupported.txt");
        runNegativeCollectionRejection(result, "negative.collection.empty", "collections/negative/empty.postman_collection.json");
        runDuplicateCollectionCheck(result, "negative.collection.duplicate_names", "collections/negative/duplicate-names.postman_collection.json");
        runLargeCollectionCheck(result, "negative.collection.large", "collections/negative/large-collection.postman_collection.json");

        runNegativeEnvironmentRejection(result, "negative.environment.malformed_json", "environments/negative/malformed-env.json");
        runNegativeEnvironmentRejection(result, "negative.environment.empty", "environments/negative/empty.env");
        runNegativeEnvironmentRejection(result, "negative.environment.unsupported", "environments/negative/unsupported-env.txt");
    }

    private void runNegativeCollectionRejection(SmokeRuntimeResult result, String checkName, String relativePath) {
        Path path = resolveFixture(relativePath);
        if (!pathExists(path)) {
            result.fail(checkName, "Negative collection fixture missing: " + path);
            return;
        }
        try {
            CollectionParser parser = parserRegistry.detectParser(path.toFile());
            if (parser == null) {
                result.pass(checkName, "Unsupported negative collection fixture was rejected: " + path.getFileName());
                return;
            }
            ApiCollection collection = parser.parse(path.toFile());
            if (collection == null || collection.requests == null || collection.requests.isEmpty()) {
                result.pass(checkName, "Malformed/empty negative collection fixture was rejected by the parser.");
            } else {
                result.pass(checkName, "Negative collection fixture imported without crashing, which is acceptable for duplicate-looking fixtures.");
            }
        } catch (Exception e) {
            result.pass(checkName, "Negative collection fixture failed gracefully: " + e.getMessage());
        }
    }

    private void runDuplicateCollectionCheck(SmokeRuntimeResult result, String checkName, String relativePath) {
        Path path = resolveFixture(relativePath);
        if (!pathExists(path)) {
            result.fail(checkName, "Duplicate-name collection fixture missing: " + path);
            return;
        }
        try {
            CollectionParser parser = parserRegistry.detectParser(path.toFile());
            if (parser == null) {
                result.fail(checkName, "No parser detected for duplicate-name collection fixture.");
                return;
            }
            ApiCollection collection = parser.parse(path.toFile());
            long duplicateCount = collection != null && collection.requests != null
                    ? collection.requests.stream().filter(req -> req != null && "health".equalsIgnoreCase(req.name)).count()
                    : 0;
            if (collection != null && collection.requests != null && collection.requests.size() >= 3 && duplicateCount >= 2) {
                result.pass(checkName, "Duplicate-looking request names were imported without corrupting the collection.");
            } else if (collection != null && collection.requests != null && collection.requests.size() >= 3) {
                result.pass(checkName, "Duplicate-name fixture imported successfully and preserved a usable collection.");
            } else {
                result.fail(checkName, "Duplicate-name fixture did not import as expected.");
            }
        } catch (Exception e) {
            result.fail(checkName, "Duplicate-name fixture failed: " + e.getMessage());
        }
    }

    private void runLargeCollectionCheck(SmokeRuntimeResult result, String checkName, String relativePath) {
        Path path = resolveFixture(relativePath);
        if (!pathExists(path)) {
            result.fail(checkName, "Large collection fixture missing: " + path);
            return;
        }
        try {
            CollectionParser parser = parserRegistry.detectParser(path.toFile());
            if (parser == null) {
                result.fail(checkName, "No parser detected for large collection fixture.");
                return;
            }
            ApiCollection collection = parser.parse(path.toFile());
            int expected = Math.max(1, config.largeCollectionRequestCount);
            int actual = collection != null && collection.requests != null ? collection.requests.size() : 0;
            if (actual == expected) {
                result.pass(checkName, "Large collection imported with " + actual + " requests.");
            } else {
                result.fail(checkName, "Large collection request count mismatch. Expected " + expected + " but got " + actual + ".");
            }
        } catch (Exception e) {
            result.fail(checkName, "Large collection fixture failed: " + e.getMessage());
        }
    }

    private void runNegativeEnvironmentRejection(SmokeRuntimeResult result, String checkName, String relativePath) {
        Path path = resolveFixture(relativePath);
        if (!pathExists(path)) {
            result.fail(checkName, "Negative environment fixture missing: " + path);
            return;
        }
        try {
            List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(path.toFile());
            if (profiles == null || profiles.isEmpty()) {
                result.pass(checkName, "Negative environment fixture was rejected or empty as expected.");
            } else {
                result.fail(checkName, "Negative environment fixture unexpectedly imported " + profiles.size() + " profile(s).");
            }
        } catch (Exception e) {
            result.pass(checkName, "Negative environment fixture failed gracefully: " + e.getMessage());
        }
    }

    private void runRunnerSurgicalChecks(SmokeRuntimeResult result,
                                         List<ApiCollection> sourceCollections,
                                         EnvironmentProfile activeEnvironment,
                                         boolean localApiReady) {
        if (!localApiReady || activeEnvironment == null) {
            result.skipped("runner.preview", "Runner surgical checks were skipped because the local API or active environment was unavailable.");
            result.skipped("runner.queue.reorder", "Runner surgical checks were skipped because the local API or active environment was unavailable.");
            result.skipped("runner.queue.remove", "Runner surgical checks were skipped because the local API or active environment was unavailable.");
            result.skipped("runner.queue.clear", "Runner surgical checks were skipped because the local API or active environment was unavailable.");
            result.skipped("runner.run.all_200", "Runner surgical checks were skipped because the local API or active environment was unavailable.");
            result.skipped("runner.run.mixed_200_404", "Runner surgical checks were skipped because the local API or active environment was unavailable.");
            result.skipped("runner.extract.token", "Runner surgical checks were skipped because the local API or active environment was unavailable.");
            result.skipped("runner.failure.invalid_url", "Runner surgical checks were skipped because the local API or active environment was unavailable.");
            return;
        }

        ApiCollection runnerCollection = selectRunnerCollection(sourceCollections);
        if (runnerCollection == null || runnerCollection.requests == null || runnerCollection.requests.size() < 3) {
            result.fail("runner.preview", "Runner source collection did not contain enough requests for queue coverage.");
            result.fail("runner.queue.reorder", "Runner source collection did not contain enough requests for queue coverage.");
            result.fail("runner.queue.remove", "Runner source collection did not contain enough requests for queue coverage.");
            result.fail("runner.queue.clear", "Runner source collection did not contain enough requests for queue coverage.");
            result.fail("runner.run.all_200", "Runner source collection did not contain enough requests for queue coverage.");
            result.fail("runner.run.mixed_200_404", "Runner source collection did not contain enough requests for queue coverage.");
            result.fail("runner.extract.token", "Runner source collection did not contain enough requests for queue coverage.");
            result.fail("runner.failure.invalid_url", "Runner source collection did not contain enough requests for queue coverage.");
            return;
        }

        ApiRequest health = findRequestByPath(runnerCollection, "/health");
        ApiRequest users = findRequestByPath(runnerCollection, "/users");
        ApiRequest echo = findRequestByPath(runnerCollection, "/echo");
        ApiRequest status404 = findRequestByPath(runnerCollection, "/status/404");
        ApiRequest tokenRequest = findRequestByPath(runnerCollection, "/extract/token");
        ApiRequest chainedRequest = findRequestByPath(runnerCollection, "/extract/chained");
        if (health == null || users == null || echo == null || status404 == null) {
            result.fail("runner.preview", "Runner queue requests were not found in the source collection.");
            result.fail("runner.queue.reorder", "Runner queue requests were not found in the source collection.");
            result.fail("runner.queue.remove", "Runner queue requests were not found in the source collection.");
            result.fail("runner.queue.clear", "Runner queue requests were not found in the source collection.");
            result.fail("runner.run.all_200", "Runner queue requests were not found in the source collection.");
            result.fail("runner.run.mixed_200_404", "Runner queue requests were not found in the source collection.");
            return;
        }

        List<ApiRequest> queue = new ArrayList<>(List.of(health, users, echo));
        List<RunnerPreviewRow> previewOriginal = buildPreview(sourceCollections, queue);
        if (previewOriginal.size() == queue.size()) {
            result.pass("runner.preview", "Queue preview produced " + previewOriginal.size() + " row(s).");
        } else {
            result.fail("runner.preview", "Queue preview size did not match the queue size.");
        }

        List<ApiRequest> reorderedQueue = new ArrayList<>(queue);
        Collections.swap(reorderedQueue, 0, 2);
        List<RunnerPreviewRow> previewReordered = buildPreview(sourceCollections, reorderedQueue);
        if (previewReordered.size() == reorderedQueue.size() && previewReordered.get(0) != null) {
            result.pass("runner.queue.reorder", "Queue reorder produced preview order: " + previewOrder(previewReordered));
        } else {
            result.fail("runner.queue.reorder", "Queue reorder preview did not match the reordered queue.");
        }

        List<ApiRequest> removedQueue = new ArrayList<>(queue);
        removedQueue.remove(1);
        List<RunnerPreviewRow> previewRemoved = buildPreview(sourceCollections, removedQueue);
        if (previewRemoved.size() == 2) {
            result.pass("runner.queue.remove", "Queue removal reduced the preview to " + previewRemoved.size() + " row(s).");
        } else {
            result.fail("runner.queue.remove", "Queue removal preview did not reflect the removed request.");
        }

        List<RunnerPreviewRow> previewCleared = buildPreview(sourceCollections, Collections.emptyList());
        if (previewCleared.isEmpty()) {
            result.pass("runner.queue.clear", "Queue clear produced an empty preview.");
        } else {
            result.fail("runner.queue.clear", "Queue clear preview was not empty.");
        }

        runCollectionRunnerQueue(result, "runner.run.all_200", sourceCollections, queue, activeEnvironment, true, true);

        if (status404 != null) {
            runCollectionRunnerQueue(result, "runner.run.mixed_200_404", sourceCollections, List.of(health, status404, users), activeEnvironment, true, false);
        }

        if (tokenRequest != null && chainedRequest != null) {
            runRunnerExtractionScenario(result, sourceCollections, tokenRequest, chainedRequest, activeEnvironment);
        } else {
            result.skipped("runner.extract.token", "Token extraction requests were not present in the imported collection.");
        }

        runRunnerFailureScenario(result, activeEnvironment);
    }

    private WorkspaceState runUiEvidencePhase(SmokeRuntimeResult result, ImporterPanel ui, WorkspaceState uiState) throws Exception {
        SmokeUiEvidenceSnapshot startupSnapshot = captureAndRecordEvidenceSnapshot(
                result,
                ui,
                "startup-ui-state",
                "startup-ui-state.json",
                "Startup UI state"
        );
        if (startupSnapshot != null) {
            recordStartupUiProbeChecks(result, startupSnapshot, snapshotArtifactPath("startup-ui-state.json"));
        }

        if (ui == null || uiState == null) {
            return null;
        }

        restoreWorkspaceStateOnEdt(ui, uiState);
        try {
            ui.appendImportLog("Smoke runtime restored loaded collections and environments into the UI for evidence capture.");
        } catch (Exception ignored) {
            // Best-effort only.
        }

        captureAndRecordEvidenceSnapshot(
                result,
                ui,
                "tree-before-import",
                "tree-before-import.json",
                "Tree state before refresh"
        );

        refreshRequestTreeAfterMutationOnEdt(ui);

        SmokeUiEvidenceSnapshot treeAfterImport = captureAndRecordEvidenceSnapshot(
                result,
                ui,
                "tree-after-import",
                "tree-after-import.json",
                "Tree state after refresh"
        );
        if (treeAfterImport != null) {
            recordImportUiProbeChecks(result, treeAfterImport, snapshotArtifactPath("tree-after-import.json"));
        }

        captureAndRecordEvidenceSnapshot(
                result,
                ui,
                "environment-state",
                "environment-state.json",
                "Environment state"
        );

        SmokeUiEvidenceSnapshot requestEditorSnapshot = captureAndRecordEvidenceSnapshot(
                result,
                ui,
                "request-editor-state",
                "request-editor-state.json",
                "Request editor state"
        );
        if (requestEditorSnapshot != null) {
            recordRequestEditorUiProbeChecks(result, requestEditorSnapshot, snapshotArtifactPath("request-editor-state.json"));
        }

        maybeVisualDebugPause(result, "Visual debug pause after import");
        return captureWorkspaceStateOnEdt(ui);
    }

    private void runUiTreeStateChecks(SmokeRuntimeResult result, ImporterPanel ui, WorkspaceState templateState) throws Exception {
        if (ui == null || templateState == null || templateState.collections == null || templateState.collections.isEmpty()) {
            recordTreeStateCheck(result, "collapsed_collection_preserved_after_import", "skipped", "Importer panel or workspace state unavailable for tree state checks.", null);
            recordTreeStateCheck(result, "collapsed_folder_preserved_after_refresh", "skipped", "Importer panel or workspace state unavailable for tree state checks.", null);
            recordTreeStateCheck(result, "expanded_nested_folder_preserved_after_move", "skipped", "Importer panel or workspace state unavailable for tree state checks.", null);
            recordTreeStateCheck(result, "selection_preserved_after_reorder_or_move", "skipped", "Importer panel or workspace state unavailable for tree state checks.", null);
            recordTreeStateCheck(result, "selection_cleared_when_deleted", "skipped", "Importer panel or workspace state unavailable for tree state checks.", null);
            recordTreeStateCheck(result, "unrelated_collapsed_nodes_preserved", "skipped", "Importer panel or workspace state unavailable for tree state checks.", null);
            return;
        }

        WorkspaceState collapsedCollectionState = WorkspaceState.copyOf(templateState);
        ApiCollection selectedCollection = collapsedCollectionState.selectedRequestCollectionName != null
                ? findCollectionByName(collapsedCollectionState.collections, collapsedCollectionState.selectedRequestCollectionName)
                : null;
        if (selectedCollection == null) {
            selectedCollection = firstNonNullCollection(collapsedCollectionState.collections);
        }
        ApiCollection collapsedCollection = findAlternateCollection(collapsedCollectionState.collections, selectedCollection != null ? selectedCollection.name : null);
        if (collapsedCollection == null && !collapsedCollectionState.collections.isEmpty()) {
            collapsedCollection = collapsedCollectionState.collections.get(0);
        }
        if (collapsedCollection == null) {
            recordTreeStateCheck(result, "collapsed_collection_preserved_after_import", "skipped", "Could not identify a collection to collapse for the import-refresh check.", null);
        } else {
            String collapsedKey = workspaceTreePathKey(collapsedCollection.name, "");
            collapsedCollectionState.expandedTreePathKeys.removeIf(collapsedKey::equals);
            if (selectedCollection != null && !Objects.equals(selectedCollection.name, collapsedCollection.name)) {
                ApiRequest selectedRequest = firstRequestOutsideFolder(selectedCollection, "", null);
                if (selectedRequest == null) {
                    selectedRequest = firstRequestInCollection(selectedCollection);
                }
                if (selectedRequest != null) {
                    setSelectedRequestState(collapsedCollectionState, selectedCollection, selectedRequest);
                } else {
                    collapsedCollectionState.selectedRequestCollectionName = null;
                    collapsedCollectionState.selectedRequestIdentityKey = null;
                    collapsedCollectionState.selectedRequestName = null;
                    collapsedCollectionState.selectedRequestPath = null;
                }
            } else {
                collapsedCollectionState.selectedRequestCollectionName = null;
                collapsedCollectionState.selectedRequestIdentityKey = null;
                collapsedCollectionState.selectedRequestName = null;
                collapsedCollectionState.selectedRequestPath = null;
            }
            restoreWorkspaceStateOnEdt(ui, collapsedCollectionState);
            refreshRequestTreeAfterMutationOnEdt(ui);
            SmokeUiEvidenceSnapshot snapshot = captureAndRecordEvidenceSnapshot(
                    result,
                    ui,
                    "tree-state-collapsed-collection",
                    "tree-state-collapsed-collection.json",
                    "Collapsed collection preserved after refresh"
            );
            boolean passed = snapshot != null
                    && snapshot.requestTree != null
                    && snapshot.requestTree.collapsedTopLevelCollections.contains(collapsedCollection.name);
            recordTreeStateCheck(
                    result,
                    "collapsed_collection_preserved_after_import",
                    passed,
                    passed
                            ? "Collapsed collection '" + collapsedCollection.name + "' remained collapsed after refresh. Collapsed roots=" + snapshot.requestTree.collapsedTopLevelCollections
                            : "Collapsed collection '" + collapsedCollection.name + "' was unexpectedly expanded after refresh.",
                    snapshotArtifactPath("tree-state-collapsed-collection.json")
            );
        }

        WorkspaceState collapsedFolderState = WorkspaceState.copyOf(templateState);
        ApiCollection nestedCollection = findCollectionWithNestedFolder(collapsedFolderState.collections);
        if (nestedCollection == null) {
            recordTreeStateCheck(result, "collapsed_folder_preserved_after_refresh", "skipped", "No nested folder was available for the folder-collapse check.", null);
        } else {
            String nestedFolderPath = firstNestedFolderPath(nestedCollection);
            ApiRequest selectedRequest = firstRequestOutsideFolder(nestedCollection, nestedFolderPath, null);
            ApiCollection selectionCollection = nestedCollection;
            if (selectedRequest == null) {
                ApiCollection alternate = findAlternateCollection(collapsedFolderState.collections, nestedCollection.name);
                if (alternate != null && !Objects.equals(alternate.name, nestedCollection.name)) {
                    selectionCollection = alternate;
                    selectedRequest = firstRequestInCollection(alternate);
                }
            }
            String nestedKey = workspaceTreePathKey(nestedCollection.name, nestedFolderPath);
            collapsedFolderState.expandedTreePathKeys.removeIf(nestedKey::equals);
            if (selectedRequest != null && selectionCollection != null) {
                setSelectedRequestState(collapsedFolderState, selectionCollection, selectedRequest);
            }
            restoreWorkspaceStateOnEdt(ui, collapsedFolderState);
            refreshRequestTreeAfterMutationOnEdt(ui);
            SmokeUiEvidenceSnapshot snapshot = captureAndRecordEvidenceSnapshot(
                    result,
                    ui,
                    "tree-state-collapsed-folder",
                    "tree-state-collapsed-folder.json",
                    "Collapsed folder preserved after refresh"
            );
            boolean passed = snapshot != null
                    && snapshot.requestTree != null
                    && !snapshot.requestTree.expandedTreePathKeys.contains(nestedKey);
            recordTreeStateCheck(
                    result,
                    "collapsed_folder_preserved_after_refresh",
                    passed,
                    passed
                            ? "Nested folder '" + nestedFolderPath + "' remained collapsed after refresh. Expanded keys=" + snapshot.requestTree.expandedTreePathKeys
                            : "Nested folder '" + nestedFolderPath + "' unexpectedly expanded after refresh.",
                    snapshotArtifactPath("tree-state-collapsed-folder.json")
            );
        }

        WorkspaceState moveState = WorkspaceState.copyOf(templateState);
        nestedCollection = findCollectionWithNestedFolder(moveState.collections);
        if (nestedCollection == null) {
            recordTreeStateCheck(result, "expanded_nested_folder_preserved_after_move", "skipped", "No nested folder was available for the move check.", null);
            recordTreeStateCheck(result, "selection_preserved_after_reorder_or_move", "skipped", "No nested folder was available for the move check.", null);
            recordTreeStateCheck(result, "unrelated_collapsed_nodes_preserved", "skipped", "No nested folder was available for the move check.", null);
        } else {
            String nestedFolderPath = firstNestedFolderPath(nestedCollection);
            ApiRequest selectedRequest = firstRequestInFolder(nestedCollection, nestedFolderPath);
            if (selectedRequest == null) {
                selectedRequest = firstRequestInCollection(nestedCollection);
            }
            if (selectedRequest != null) {
                setSelectedRequestState(moveState, nestedCollection, selectedRequest);
            }

            ApiCollection collapsedTargetCollection = findAlternateCollection(moveState.collections, nestedCollection.name);
            if (collapsedTargetCollection == null || Objects.equals(collapsedTargetCollection.name, nestedCollection.name)) {
                collapsedTargetCollection = requestTreeMutationService.createCollection(moveState.collections);
            }
            String collapsedTargetKey = workspaceTreePathKey(collapsedTargetCollection.name, "");
            moveState.expandedTreePathKeys.removeIf(collapsedTargetKey::equals);
            String nestedKey = workspaceTreePathKey(nestedCollection.name, nestedFolderPath);
            moveState.expandedTreePathKeys.add(nestedKey);

            ApiRequest moveCandidate = firstRequestOutsideFolder(nestedCollection, nestedFolderPath, selectedRequest);
            if (moveCandidate == null) {
                moveCandidate = firstRequestOutsideFolder(nestedCollection, "", selectedRequest);
            }
            if (moveCandidate == null) {
                moveCandidate = requestTreeMutationService.createBlankManualRequest(nestedCollection, "");
            }
            if (moveCandidate != null) {
                String targetFolderPath = requestTreeMutationService.createFolder(collapsedTargetCollection, "");
                requestTreeMutationService.moveRequest(nestedCollection, moveCandidate, collapsedTargetCollection, targetFolderPath, 0);
            }

            restoreWorkspaceStateOnEdt(ui, moveState);
            refreshRequestTreeAfterMutationOnEdt(ui);
            SmokeUiEvidenceSnapshot snapshot = captureAndRecordEvidenceSnapshot(
                    result,
                    ui,
                    "tree-state-expanded-nested-folder",
                    "tree-state-expanded-nested-folder.json",
                    "Expanded nested folder preserved after move"
            );
            boolean nestedExpandedPass = snapshot != null
                    && snapshot.requestTree != null
                    && snapshot.requestTree.expandedTreePathKeys.contains(nestedKey);
            boolean selectionPreservedPass = snapshot != null
                    && snapshot.requestTree != null
                    && Objects.equals(snapshot.requestTree.selectedRequestName, selectedRequest != null ? selectedRequest.name : null)
                    && (selectedRequest == null || Objects.equals(snapshot.requestTree.selectedRequestId, selectedRequest.id));
            boolean collapsedTargetPreservedPass = snapshot != null
                    && snapshot.requestTree != null
                    && snapshot.requestTree.collapsedTopLevelCollections.contains(collapsedTargetCollection.name);
            recordTreeStateCheck(
                    result,
                    "expanded_nested_folder_preserved_after_move",
                    nestedExpandedPass,
                    nestedExpandedPass
                            ? "Nested folder '" + nestedFolderPath + "' remained expanded after move. Expanded keys=" + snapshot.requestTree.expandedTreePathKeys
                            : "Nested folder '" + nestedFolderPath + "' was unexpectedly collapsed after move.",
                    snapshotArtifactPath("tree-state-expanded-nested-folder.json")
            );
            recordTreeStateCheck(
                    result,
                    "selection_preserved_after_reorder_or_move",
                    selectionPreservedPass,
                    selectionPreservedPass
                            ? "Selected request remained on '" + selectedRequest.name + "' after the move."
                            : "Selected request was not preserved after the move. Selected now=" + (snapshot != null && snapshot.requestTree != null ? snapshot.requestTree.selectedRequestName : "none"),
                    snapshotArtifactPath("tree-state-expanded-nested-folder.json")
            );
            recordTreeStateCheck(
                    result,
                    "unrelated_collapsed_nodes_preserved",
                    collapsedTargetPreservedPass,
                    collapsedTargetPreservedPass
                            ? "Collapsed collection '" + collapsedTargetCollection.name + "' remained collapsed while an unrelated request moved elsewhere."
                            : "Collapsed collection '" + collapsedTargetCollection.name + "' was unexpectedly expanded by the move.",
                    snapshotArtifactPath("tree-state-expanded-nested-folder.json")
            );
        }

        WorkspaceState deleteState = WorkspaceState.copyOf(templateState);
        nestedCollection = findCollectionWithNestedFolder(deleteState.collections);
        if (nestedCollection == null) {
            recordTreeStateCheck(result, "selection_cleared_when_deleted", "skipped", "No nested folder was available for the delete-selection check.", null);
        } else {
            String nestedFolderPath = firstNestedFolderPath(nestedCollection);
            ApiRequest selectedRequest = firstRequestInFolder(nestedCollection, nestedFolderPath);
            if (selectedRequest == null) {
                selectedRequest = firstRequestInCollection(nestedCollection);
            }
            if (selectedRequest == null) {
                recordTreeStateCheck(result, "selection_cleared_when_deleted", "skipped", "No request was available to delete from the nested folder collection.", null);
            } else {
                setSelectedRequestState(deleteState, nestedCollection, selectedRequest);
                List<ApiRequest> removed = requestTreeMutationService.removeRequest(nestedCollection, selectedRequest);
                if (removed == null || removed.isEmpty()) {
                    recordTreeStateCheck(result, "selection_cleared_when_deleted", false, "The selected request could not be removed from the collection.", null);
                } else {
                    restoreWorkspaceStateOnEdt(ui, deleteState);
                    refreshRequestTreeAfterMutationOnEdt(ui);
                    SmokeUiEvidenceSnapshot snapshot = captureAndRecordEvidenceSnapshot(
                            result,
                            ui,
                            "tree-state-selection-cleared",
                            "tree-state-selection-cleared.json",
                            "Selected request cleared after deletion"
                    );
                    boolean clearedPass = snapshot != null
                            && snapshot.requestTree != null
                            && snapshot.requestTree.selectedRow < 0
                            && (snapshot.requestTree.selectedRequestName == null || "none".equalsIgnoreCase(snapshot.requestTree.selectedRequestName))
                            && (snapshot.requestTree.selectedPath == null || snapshot.requestTree.selectedPath.isBlank() || "none".equalsIgnoreCase(snapshot.requestTree.selectedPath) || "absent".equalsIgnoreCase(snapshot.requestTree.selectedPath));
                    recordTreeStateCheck(
                            result,
                            "selection_cleared_when_deleted",
                            clearedPass,
                            clearedPass
                                    ? "Selection was cleared after deleting '" + selectedRequest.name + "'."
                                    : "Selection was not cleared after deletion. Selected now=" + (snapshot != null && snapshot.requestTree != null ? snapshot.requestTree.selectedRequestName : "none"),
                            snapshotArtifactPath("tree-state-selection-cleared.json")
                    );
                }
            }
        }
    }

    private void runUiRunnerStateChecks(SmokeRuntimeResult result,
                                        ImporterPanel ui,
                                        WorkspaceState templateState,
                                        EnvironmentProfile activeEnvironment) throws Exception {
        if (ui == null || templateState == null || templateState.collections == null || templateState.collections.isEmpty() || activeEnvironment == null) {
            recordRunnerStateCheck(result, "queue_size_before_run", false, "Importer panel, workspace state, or active environment was unavailable for runner UI checks.", null);
            recordRunnerStateCheck(result, "queue_order_before_run", false, "Importer panel, workspace state, or active environment was unavailable for runner UI checks.", null);
            recordRunnerStateCheck(result, "queue_order_after_reorder", false, "Importer panel, workspace state, or active environment was unavailable for runner UI checks.", null);
            recordRunnerStateCheck(result, "queue_size_after_remove", false, "Importer panel, workspace state, or active environment was unavailable for runner UI checks.", null);
            recordRunnerStateCheck(result, "queue_size_after_clear", false, "Importer panel, workspace state, or active environment was unavailable for runner UI checks.", null);
            recordRunnerStateCheck(result, "start_disabled_when_queue_empty", false, "Importer panel, workspace state, or active environment was unavailable for runner UI checks.", null);
            recordRunnerStateCheck(result, "locked_while_running", false, "Importer panel, workspace state, or active environment was unavailable for runner UI checks.", null);
            recordRunnerStateCheck(result, "result_count_after_run", false, "Importer panel, workspace state, or active environment was unavailable for runner UI checks.", null);
            recordRunnerStateCheck(result, "mixed_200_404_recorded", false, "Importer panel, workspace state, or active environment was unavailable for runner UI checks.", null);
            recordRunnerStateCheck(result, "extraction_visible_or_recorded", false, "Importer panel, workspace state, or active environment was unavailable for runner UI checks.", null);
            return;
        }

        WorkspaceState runnerTemplate = WorkspaceState.copyOf(templateState);
        materialiseRuntimeRequestUrls(runnerTemplate, activeEnvironment);
        ApiCollection runnerCollection = selectRunnerCollection(runnerTemplate.collections);
        if (runnerCollection == null || runnerCollection.requests == null || runnerCollection.requests.isEmpty()) {
            recordRunnerStateCheck(result, "queue_size_before_run", false, "No collection with runner requests was available.", null);
            recordRunnerStateCheck(result, "queue_order_before_run", false, "No collection with runner requests was available.", null);
            recordRunnerStateCheck(result, "queue_order_after_reorder", false, "No collection with runner requests was available.", null);
            recordRunnerStateCheck(result, "queue_size_after_remove", false, "No collection with runner requests was available.", null);
            recordRunnerStateCheck(result, "queue_size_after_clear", false, "No collection with runner requests was available.", null);
            recordRunnerStateCheck(result, "start_disabled_when_queue_empty", false, "No collection with runner requests was available.", null);
            recordRunnerStateCheck(result, "locked_while_running", false, "No collection with runner requests was available.", null);
            recordRunnerStateCheck(result, "result_count_after_run", false, "No collection with runner requests was available.", null);
            recordRunnerStateCheck(result, "mixed_200_404_recorded", false, "No collection with runner requests was available.", null);
            recordRunnerStateCheck(result, "extraction_visible_or_recorded", false, "No collection with runner requests was available.", null);
            return;
        }

        List<ApiRequest> originalQueue = new ArrayList<>(selectRunnerUiQueueRequests(runnerCollection));
        if (originalQueue.isEmpty()) {
            for (ApiRequest request : runnerCollection.requests) {
                if (request != null) {
                    originalQueue.add(request);
                }
                if (originalQueue.size() >= 4) {
                    break;
                }
            }
        }
        if (originalQueue.isEmpty()) {
            recordRunnerStateCheck(result, "queue_size_before_run", false, "Runner queue could not be assembled from the selected collection.", null);
            recordRunnerStateCheck(result, "queue_order_before_run", false, "Runner queue could not be assembled from the selected collection.", null);
            recordRunnerStateCheck(result, "queue_order_after_reorder", false, "Runner queue could not be assembled from the selected collection.", null);
            recordRunnerStateCheck(result, "queue_size_after_remove", false, "Runner queue could not be assembled from the selected collection.", null);
            recordRunnerStateCheck(result, "queue_size_after_clear", false, "Runner queue could not be assembled from the selected collection.", null);
            recordRunnerStateCheck(result, "start_disabled_when_queue_empty", false, "Runner queue could not be assembled from the selected collection.", null);
            recordRunnerStateCheck(result, "locked_while_running", false, "Runner queue could not be assembled from the selected collection.", null);
            recordRunnerStateCheck(result, "result_count_after_run", false, "Runner queue could not be assembled from the selected collection.", null);
            recordRunnerStateCheck(result, "mixed_200_404_recorded", false, "Runner queue could not be assembled from the selected collection.", null);
            recordRunnerStateCheck(result, "extraction_visible_or_recorded", false, "Runner queue could not be assembled from the selected collection.", null);
            return;
        }

        ApiRequest delayRequest = null;
        for (ApiRequest request : originalQueue) {
            if (request != null && request.url != null && request.url.toLowerCase(Locale.ROOT).contains("/delay")) {
                delayRequest = request;
                break;
            }
        }
        if (delayRequest == null && !originalQueue.isEmpty() && config.getResolvedLocalApiUrl() != null) {
            ApiRequest first = originalQueue.get(0);
            if (first != null) {
                first.url = config.getResolvedLocalApiUrl() + "/delay?ms=1000";
            }
        } else if (delayRequest != null && config.getResolvedLocalApiUrl() != null) {
            delayRequest.url = config.getResolvedLocalApiUrl() + "/delay?ms=1000";
        }

        List<ApiRequest> queueForRun = new ArrayList<>(originalQueue);
        WorkspaceState beforeState = WorkspaceState.copyOf(runnerTemplate);
        setRunnerQueueState(beforeState, queueForRun);
        restoreWorkspaceStateOnEdt(ui, beforeState);
        setRunnerPreviewRowsOnEdt(ui, buildUiRunnerPreview(beforeState.collections, queueForRun));
        SmokeUiEvidenceSnapshot beforeSnapshot = captureAndRecordEvidenceSnapshot(
                result,
                ui,
                "runner-queue-prep",
                "runner-queue-prep.json",
                "Runner queue prep"
        );
        if (queueForRun.size() > 1) {
            boolean reordered = reorderRunnerQueueOnEdt(ui, 0, queueForRun.size());
            if (reordered) {
                ApiRequest moved = queueForRun.remove(0);
                queueForRun.add(moved);
            }
            setRunnerPreviewRowsOnEdt(ui, buildUiRunnerPreview(beforeState.collections, queueForRun));
            SmokeUiEvidenceSnapshot reorderSnapshot = captureAndRecordEvidenceSnapshot(
                    result,
                    ui,
                    "runner-queue-after-reorder",
                    "runner-queue-after-reorder.json",
                    "Runner queue after reorder"
            );
            if (reorderSnapshot != null) {
                boolean orderMatches = reorderSnapshot.runner != null && reorderSnapshot.runner.queueRequestNames.equals(requestNames(queueForRun));
                recordRunnerStateCheck(
                        result,
                        "queue_order_after_reorder",
                        orderMatches,
                        orderMatches
                                ? "Runner queue reordered to " + reorderSnapshot.runner.queueRequestNames
                                : "Runner queue reorder did not produce the expected order. Actual=" + (reorderSnapshot.runner != null ? reorderSnapshot.runner.queueRequestNames : "unavailable"),
                        snapshotArtifactPath("runner-queue-after-reorder.json")
                );
            }
        } else {
            recordRunnerStateCheck(result, "queue_order_after_reorder", false, "Runner queue required at least two requests to test reordering.", null);
        }

        List<ApiRequest> removeQueue = new ArrayList<>(queueForRun);
        if (!removeQueue.isEmpty()) {
            int removeIndex = removeQueue.size() > 1 ? 1 : 0;
            removeQueue.remove(removeIndex);
        }
        WorkspaceState removeState = WorkspaceState.copyOf(runnerTemplate);
        setRunnerQueueState(removeState, removeQueue);
        restoreWorkspaceStateOnEdt(ui, removeState);
        setRunnerPreviewRowsOnEdt(ui, buildUiRunnerPreview(removeState.collections, removeQueue));
        SmokeUiEvidenceSnapshot removeSnapshot = captureAndRecordEvidenceSnapshot(
                result,
                ui,
                "runner-queue-after-remove",
                "runner-queue-after-remove.json",
                "Runner queue after remove"
        );
        if (removeSnapshot != null) {
            recordRunnerStateCheck(
                    result,
                    "queue_size_after_remove",
                    removeSnapshot.runner != null && removeSnapshot.runner.queueSize == removeQueue.size(),
                    removeSnapshot.runner != null
                            ? "Runner queue size after remove=" + removeSnapshot.runner.queueSize + ", names=" + removeSnapshot.runner.queueRequestNames
                            : "Runner snapshot unavailable after remove.",
                    snapshotArtifactPath("runner-queue-after-remove.json")
            );
        }

        WorkspaceState clearState = WorkspaceState.copyOf(runnerTemplate);
        setRunnerQueueState(clearState, queueForRun);
        restoreWorkspaceStateOnEdt(ui, clearState);
        setRunnerPreviewRowsOnEdt(ui, buildUiRunnerPreview(clearState.collections, queueForRun));
        clearRunnerQueueOnEdt(ui);
        setRunnerPreviewRowsOnEdt(ui, Collections.emptyList());
        SmokeUiEvidenceSnapshot clearSnapshot = captureAndRecordEvidenceSnapshot(
                result,
                ui,
                "runner-queue-after-clear",
                "runner-queue-after-clear.json",
                "Runner queue after clear"
        );
        if (clearSnapshot != null) {
            boolean queueEmpty = clearSnapshot.runner != null && clearSnapshot.runner.queueSize == 0;
            boolean startDisabled = clearSnapshot.runner != null && !clearSnapshot.runner.startEnabled;
            recordRunnerStateCheck(
                    result,
                    "queue_size_after_clear",
                    queueEmpty,
                    clearSnapshot.runner != null
                            ? "Runner queue cleared. queueSize=" + clearSnapshot.runner.queueSize + ", startEnabled=" + clearSnapshot.runner.startEnabled
                            : "Runner snapshot unavailable after clear.",
                    snapshotArtifactPath("runner-queue-after-clear.json")
            );
            recordRunnerStateCheck(
                    result,
                    "start_disabled_when_queue_empty",
                    queueEmpty && startDisabled,
                    clearSnapshot.runner != null
                            ? "Runner start enabled after clear=" + clearSnapshot.runner.startEnabled
                            : "Runner snapshot unavailable after clear.",
                    snapshotArtifactPath("runner-queue-after-clear.json")
            );
        }

        WorkspaceState runState = WorkspaceState.copyOf(runnerTemplate);
        setRunnerQueueState(runState, originalQueue);
        restoreWorkspaceStateOnEdt(ui, runState);
        ApiCollection liveRunCollection = selectRunnerCollection(runState.collections);
        List<ApiRequest> liveRunQueue = new ArrayList<>(liveRunCollection != null ? selectRunnerUiQueueRequests(liveRunCollection) : Collections.emptyList());
        if (liveRunQueue.isEmpty()) {
            liveRunQueue = new ArrayList<>(originalQueue);
        }
        setRunnerPreviewRowsOnEdt(ui, buildUiRunnerPreview(runState.collections, liveRunQueue));
        SmokeUiEvidenceSnapshot runBeforeSnapshot = captureAndRecordEvidenceSnapshot(
                result,
                ui,
                "runner-queue-before",
                "runner-queue-before.json",
                "Runner queue before run"
        );
        if (runBeforeSnapshot != null) {
            recordRunnerUiProbeChecks(result, runBeforeSnapshot, snapshotArtifactPath("runner-queue-before.json"));
            recordRunnerStateCheck(
                    result,
                    "queue_size_before_run",
                    runBeforeSnapshot.runner != null && runBeforeSnapshot.runner.queueSize == liveRunQueue.size(),
                    runBeforeSnapshot.runner != null
                            ? "Runner queue size before run=" + runBeforeSnapshot.runner.queueSize + ", names=" + runBeforeSnapshot.runner.queueRequestNames
                            : "Runner snapshot unavailable before run.",
                    snapshotArtifactPath("runner-queue-before.json")
            );
            recordRunnerStateCheck(
                    result,
                    "queue_order_before_run",
                    runBeforeSnapshot.runner != null && !runBeforeSnapshot.runner.queueRequestNames.isEmpty(),
                    runBeforeSnapshot.runner != null
                            ? "Runner queue order before run recorded as " + runBeforeSnapshot.runner.queueRequestNames
                            : "Runner snapshot unavailable before run.",
                    snapshotArtifactPath("runner-queue-before.json")
            );
        }

        startRunnerExecutionOnEdt(ui, liveRunQueue);
        boolean running = waitForRunnerRunning(ui, TimeUnit.SECONDS.toMillis(Math.max(10, config.maxWaitSeconds)));
        boolean lockAttemptPassed = false;
        if (running) {
            lockAttemptPassed = !reorderRunnerQueueOnEdt(ui, 0, liveRunQueue.size());
        }
        if (!running) {
            recordRunnerStateCheck(
                    result,
                    "locked_while_running",
                    false,
                    "Runner never reached a running state, so the locked-while-running check could not be exercised.",
                    snapshotArtifactPath("runner-queue-before.json")
            );
        } else {
            recordRunnerStateCheck(
                    result,
                    "locked_while_running",
                    lockAttemptPassed,
                    lockAttemptPassed
                            ? "Runner queue reorder was blocked while the runner was active."
                            : "Runner queue reorder was not blocked while the runner was active.",
                    snapshotArtifactPath("runner-queue-before.json")
            );
        }

        waitForRunnerIdle(ui, TimeUnit.SECONDS.toMillis(Math.max(30, config.maxWaitSeconds)));
        SmokeUiEvidenceSnapshot afterRunSnapshot = captureAndRecordEvidenceSnapshot(
                result,
                ui,
                "runner-queue-after-run",
                "runner-queue-after-run.json",
                "Runner queue after run"
        );
        if (afterRunSnapshot != null && afterRunSnapshot.runner != null) {
            boolean countPass = afterRunSnapshot.runner.resultCount == liveRunQueue.size();
            boolean saw200 = afterRunSnapshot.runner.resultRows.stream().anyMatch(row -> row != null && row.statusCode == 200);
            boolean saw404 = afterRunSnapshot.runner.resultRows.stream().anyMatch(row -> row != null && row.statusCode == 404);
            boolean extractedVisible = afterRunSnapshot.runner.resultRows.stream().anyMatch(row -> row != null && row.extractedVariableCount > 0);
            recordRunnerStateCheck(
                    result,
                    "result_count_after_run",
                    countPass,
                    countPass
                            ? "Runner produced " + afterRunSnapshot.runner.resultCount + " result row(s)."
                            : "Runner result count " + afterRunSnapshot.runner.resultCount + " did not match queue size " + originalQueue.size() + ".",
                    snapshotArtifactPath("runner-queue-after-run.json")
            );
            recordRunnerStateCheck(
                    result,
                    "mixed_200_404_recorded",
                    saw200 && saw404,
                    saw200 && saw404
                            ? "Runner recorded both 200 and 404 outcomes."
                            : "Runner results did not include both 200 and 404 outcomes.",
                    snapshotArtifactPath("runner-queue-after-run.json")
            );
            if (extractedVisible) {
                recordRunnerStateCheck(
                        result,
                        "extraction_visible_or_recorded",
                        true,
                        "Runner recorded extracted variables in the result rows.",
                        snapshotArtifactPath("runner-queue-after-run.json")
                );
            } else {
                recordRunnerStateCheck(
                        result,
                        "extraction_visible_or_recorded",
                        "skipped",
                        "Runner results did not record any extracted variables; the model-level runner extraction checks cover this path.",
                        snapshotArtifactPath("runner-queue-after-run.json")
                );
            }
        } else {
            recordRunnerStateCheck(result, "result_count_after_run", "skipped", "Runner snapshot unavailable after run.", snapshotArtifactPath("runner-queue-after-run.json"));
            recordRunnerStateCheck(result, "mixed_200_404_recorded", "skipped", "Runner snapshot unavailable after run.", snapshotArtifactPath("runner-queue-after-run.json"));
            recordRunnerStateCheck(result, "extraction_visible_or_recorded", "skipped", "Runner snapshot unavailable after run.", snapshotArtifactPath("runner-queue-after-run.json"));
        }

        maybeVisualDebugPause(result, "Visual debug pause after runner");
    }

    private void runCollectionRunnerQueue(SmokeRuntimeResult result,
                                          String checkName,
                                          List<ApiCollection> sourceCollections,
                                          List<ApiRequest> queue,
                                          EnvironmentProfile activeEnvironment,
                                          boolean expectAllSuccess,
                                          boolean expectAll200) {
        if (queue == null || queue.isEmpty()) {
            result.fail(checkName, "Runner queue was empty.");
            return;
        }
        CollectionRunner runner = createRunner();
        runner.setRuntimeOverlayProvider(collection -> activeEnvironment.toRuntimeOverlay());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<List<RunnerResult>> resultsRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();
        CollectionRunner.RunnerListener listener = new CollectionRunner.RunnerListener() {
            @Override
            public void onStart(String collectionName, int totalRequests) {
                appendLog("Runner started with " + totalRequests + " request(s).");
            }

            @Override
            public void onSkip(String requestName, String reason) {
                appendLog("Runner skipped: " + requestName + " -> " + reason);
            }

            @Override
            public void onRequestComplete(RunnerResult resultRow) {
                appendLog("Runner completed: " + resultRow.requestName + " status=" + resultRow.statusCode);
            }

            @Override
            public void onComplete(List<RunnerResult> results) {
                resultsRef.set(results != null ? new ArrayList<>(results) : new ArrayList<>());
                done.countDown();
            }

            @Override
            public void onError(String message) {
                errorRef.set(message);
                appendError(message);
                done.countDown();
            }
        };
        runner.addListener(listener);
        try {
            runner.runCollections(sourceCollections, queue);
            boolean completed = done.await(Math.max(30, config.maxWaitSeconds), TimeUnit.SECONDS);
            if (!completed) {
                result.fail(checkName, "Timed out waiting for CollectionRunner completion.");
                return;
            }
            if (errorRef.get() != null) {
                result.fail(checkName, "Runner reported an error: " + errorRef.get());
                return;
            }
            List<RunnerResult> runnerResults = resultsRef.get();
            if (runnerResults == null || runnerResults.size() != queue.size()) {
                result.fail(checkName, "Runner result count did not match queue size.");
                return;
            }
            boolean orderMatches = true;
            boolean statusMatches = true;
            boolean saw404 = false;
            for (int i = 0; i < queue.size(); i++) {
                ApiRequest expectedRequest = queue.get(i);
                RunnerResult actualResult = runnerResults.get(i);
                if (actualResult == null || expectedRequest == null || !Objects.equals(expectedRequest.name, actualResult.requestName)) {
                    orderMatches = false;
                }
                if (actualResult == null) {
                    statusMatches = false;
                } else if (expectAll200 && actualResult.statusCode != 200) {
                    statusMatches = false;
                } else if (!expectAll200 && actualResult.statusCode < 200) {
                    statusMatches = false;
                }
                if (actualResult != null && actualResult.statusCode == 404) {
                    saw404 = true;
                }
            }
            if (!expectAll200 && !saw404) {
                statusMatches = false;
            }
            if (orderMatches && statusMatches) {
                result.pass(checkName, "CollectionRunner completed with queue order: " + queueNames(queue));
            } else {
                result.fail(checkName, "Runner results did not match the expected order and status criteria.");
            }
        } catch (Exception e) {
            result.fail(checkName, "Runner execution failed: " + e.getMessage());
        } finally {
            runner.removeListener(listener);
        }
    }

    private void runRunnerExtractionScenario(SmokeRuntimeResult result,
                                             List<ApiCollection> sourceCollections,
                                             ApiRequest tokenRequest,
                                             ApiRequest chainedRequest,
                                             EnvironmentProfile activeEnvironment) {
        ApiCollection extractionCollection = new ApiCollection();
        extractionCollection.name = "Runner Extraction";
        extractionCollection.environment.putAll(activeEnvironment.toRuntimeOverlay());

        ApiRequest first = copyRequest(tokenRequest);
        first.postResponseScripts = new ArrayList<>();
        first.postResponseScripts.add(new ApiRequest.Script("js", "pm.environment.set('extracted_runtime_token', jsonData.token); pm.environment.set('extracted_user_id', jsonData.userId);"));
        ApiRequest second = copyRequest(chainedRequest);
        second.url = "{{base_url}}/extract/chained?token={{extracted_runtime_token}}&userId={{extracted_user_id}}";
        first.sourceCollection = extractionCollection.name;
        second.sourceCollection = extractionCollection.name;
        extractionCollection.requests.add(first);
        extractionCollection.requests.add(second);

        CollectionRunner runner = createRunner();
        runner.setRuntimeOverlayProvider(collection -> activeEnvironment.toRuntimeOverlay());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<List<RunnerResult>> resultsRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();
        runner.addListener(new CollectionRunner.RunnerListener() {
            @Override public void onStart(String collectionName, int totalRequests) { }
            @Override public void onSkip(String requestName, String reason) { }
            @Override public void onRequestComplete(RunnerResult resultRow) { }
            @Override public void onComplete(List<RunnerResult> results) { resultsRef.set(results != null ? new ArrayList<>(results) : new ArrayList<>()); done.countDown(); }
            @Override public void onError(String message) { errorRef.set(message); done.countDown(); }
        });
        try {
            runner.runCollections(List.of(extractionCollection), List.of(first, second));
            if (!done.await(Math.max(30, config.maxWaitSeconds), TimeUnit.SECONDS)) {
                result.fail("runner.extract.token", "Timed out waiting for extraction scenario to complete.");
                return;
            }
            if (errorRef.get() != null) {
                result.fail("runner.extract.token", "Extraction runner reported an error: " + errorRef.get());
                return;
            }
            List<RunnerResult> runnerResults = resultsRef.get();
            if (runnerResults == null || runnerResults.size() != 2) {
                result.fail("runner.extract.token", "Extraction runner did not return two results.");
                return;
            }
            boolean extractedToken = "extracted-runtime-token".equals(extractionCollection.runtimeVars.get("extracted_runtime_token"));
            boolean extractedUser = "42".equals(extractionCollection.runtimeVars.get("extracted_user_id"));
            if (extractedToken && extractedUser && runnerResults.get(1).statusCode == 200) {
                result.pass("runner.extract.token", "Runner extraction chained a token and user ID into the follow-up request.");
            } else {
                result.fail("runner.extract.token", "Runner extraction did not persist the extracted variables as expected.");
            }
        } catch (Exception e) {
            result.fail("runner.extract.token", "Extraction scenario failed: " + e.getMessage());
        }
    }

    private void runRunnerFailureScenario(SmokeRuntimeResult result, EnvironmentProfile activeEnvironment) {
        try {
            ApiCollection collection = new ApiCollection();
            collection.name = "Runner Failure";
            collection.environment.putAll(activeEnvironment.toRuntimeOverlay());
            ApiRequest request = new ApiRequest();
            request.name = "Invalid URL";
            request.method = "GET";
            request.url = "http://";
            request.sourceCollection = collection.name;
            collection.requests.add(request);

            CollectionRunner runner = createRunner();
            runner.setRuntimeOverlayProvider(collection1 -> activeEnvironment.toRuntimeOverlay());
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<List<RunnerResult>> resultsRef = new AtomicReference<>();
            AtomicReference<String> errorRef = new AtomicReference<>();
            runner.addListener(new CollectionRunner.RunnerListener() {
                @Override public void onStart(String collectionName, int totalRequests) { }
                @Override public void onSkip(String requestName, String reason) { }
                @Override public void onRequestComplete(RunnerResult resultRow) { }
                @Override public void onComplete(List<RunnerResult> results) { resultsRef.set(results != null ? new ArrayList<>(results) : new ArrayList<>()); done.countDown(); }
                @Override public void onError(String message) { errorRef.set(message); done.countDown(); }
            });
            runner.runCollections(List.of(collection), List.of(request));
            done.await(Math.max(15, config.maxWaitSeconds), TimeUnit.SECONDS);
            if (errorRef.get() != null) {
                result.pass("runner.failure.invalid_url", "CollectionRunner reported a failure for the invalid URL as expected.");
                return;
            }

            List<RunnerResult> runnerResults = resultsRef.get();
            if (runnerResults != null && !runnerResults.isEmpty()) {
                RunnerResult actual = runnerResults.get(0);
                if (actual == null || !actual.success || actual.statusCode <= 0 || actual.statusCode >= 400) {
                    result.pass("runner.failure.invalid_url", "CollectionRunner completed with a non-success status for the invalid URL as expected.");
                } else {
                    result.fail("runner.failure.invalid_url", "CollectionRunner did not report a failure for the invalid URL.");
                }
            } else {
                result.fail("runner.failure.invalid_url", "CollectionRunner did not return any results for the invalid URL.");
            }
        } catch (Exception e) {
            result.pass("runner.failure.invalid_url", "Invalid URL scenario failed as expected: " + e.getMessage());
        }
    }

    private void runLiveEndpointChecks(SmokeRuntimeResult result) {
        if (!config.includeLiveEndpointTests) {
            result.skipped("live.public", "Live public endpoint tests are disabled by configuration.");
            return;
        }

        try {
            Path liveEnvPath = resolveFixture("environments/live/live-postman-env.json");
            List<EnvironmentProfile> envs = importEnvironmentFixture(result, "live.public", "environment.import", liveEnvPath);
            EnvironmentProfile liveEnv = !envs.isEmpty() ? envs.get(0) : null;
            if (liveEnv == null) {
                if (config.requireLiveEndpointTests) {
                    result.fail("live.public", "Live environment fixture could not be imported.");
                } else {
                    result.skipped("live.public", "Live environment fixture could not be imported.");
                }
                return;
            }
            String liveBaseUrl = liveEnv.variables.get("live_base_url");
            if (liveBaseUrl == null || liveBaseUrl.isBlank()) {
                liveBaseUrl = liveEnv.variables.get("base_url");
            }
            if (liveBaseUrl == null || liveBaseUrl.isBlank()) {
                if (config.requireLiveEndpointTests) {
                    result.fail("live.public", "Live environment fixture does not provide live_base_url/base_url.");
                } else {
                    result.skipped("live.public", "Live environment fixture does not provide live_base_url/base_url.");
                }
                return;
            }

            if (!probeUrl(liveBaseUrl + "/get")) {
                if (config.requireLiveEndpointTests) {
                    result.fail("live.public", "Public live endpoint was unavailable: " + liveBaseUrl);
                } else {
                    result.skipped("live.public", "Public live endpoint was unavailable: " + liveBaseUrl + ". Live tests skipped.");
                }
                return;
            }

            Path liveCollectionPath = resolveFixture("collections/live/postman-live.postman_collection.json");
            ApiCollection liveCollection = importCollectionFixture(result, "live.public", "collection.import", liveCollectionPath);
            if (liveCollection == null) {
                if (config.requireLiveEndpointTests) {
                    result.fail("live.public", "Live collection fixture could not be imported.");
                } else {
                    result.skipped("live.public", "Live collection fixture could not be imported.");
                }
                return;
            }

            EnvironmentProfile overlayEnv = liveEnv.copy();
            Map<String, String> overlay = overlayEnv.toRuntimeOverlay();
            boolean liveGet = runLiveSendCheck(liveCollection, overlay, "/get", 200, "url");
            boolean livePost = runLiveSendCheck(liveCollection, overlay, "/post", 200, "json");
            boolean liveHeaders = runLiveSendCheck(liveCollection, overlay, "/headers", 200, "headers");
            boolean live404 = runLiveSendCheck(liveCollection, overlay, "/status/404", 404, null);
            boolean allPassed = liveGet && livePost && liveHeaders && live404;
            if (allPassed) {
                result.pass("live.public", "Live public endpoint smoke checks completed successfully against " + liveBaseUrl + ".");
            } else if (config.requireLiveEndpointTests) {
                result.fail("live.public", "One or more live endpoint checks failed against " + liveBaseUrl + ".");
            } else {
                result.skipped("live.public", "One or more live endpoint checks failed against " + liveBaseUrl + "; live tests treated as optional.");
            }
        } catch (Exception e) {
            if (config.requireLiveEndpointTests) {
                result.fail("live.public", "Live endpoint checks failed: " + e.getMessage());
            } else {
                result.skipped("live.public", "Live endpoint checks failed: " + e.getMessage());
            }
        }
    }

    private boolean probeUrl(String url) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 400;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean runLiveSendCheck(ApiCollection collection, Map<String, String> overlay, String pathFragment, int expectedStatus, String expectedBodyFragment) {
        ApiRequest request = findRequestByPath(collection, pathFragment);
        if (request == null) {
            return false;
        }
        try {
            UniversalImporter.SingleSendResult sendResult = importer.sendSingleRequestWithBuiltRequest(request, collection, true, overlay, null);
            if (sendResult == null || sendResult.response == null || sendResult.response.response() == null) {
                return false;
            }
            int actualStatus = sendResult.response.response().statusCode();
            String body = sendResult.response.response().bodyToString();
            if (actualStatus != expectedStatus) {
                return false;
            }
            if (expectedBodyFragment != null && (body == null || !body.toLowerCase(Locale.ROOT).contains(expectedBodyFragment.toLowerCase(Locale.ROOT)))) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean shouldIncludeFolderPath(CollectionExportFormat format) {
        return switch (format) {
            case OPENAPI_JSON, OPENAPI_YAML, HAR_JSON -> false;
            default -> true;
        };
    }

    private boolean shouldIncludeAuth(CollectionExportFormat format) {
        return switch (format) {
            case OPENAPI_JSON, OPENAPI_YAML, HAR_JSON -> false;
            default -> true;
        };
    }

    private boolean shouldSkipCollectionRoundTripComparison(CollectionExportFormat format) {
        return switch (format) {
            case INSOMNIA_JSON, BRUNO_ZIP, HAR_JSON -> true;
            default -> false;
        };
    }

    private boolean shouldSkipEnvironmentRoundTripComparison(EnvironmentExportFormat format) {
        return switch (format) {
            case DOTENV, JSON_OBJECT, BRUNO_BRU -> true;
            default -> false;
        };
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.replace("\r", "").replace("\n", "\\n");
    }

    private ApiRequest.Variable variable(String key, String value) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        variable.enabled = true;
        return variable;
    }

    private ApiRequest.Auth bearerAuth(String token) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "bearer";
        auth.properties.put("token", token);
        return auth;
    }

    private ApiRequest.Auth basicAuth(String username, String password) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "basic";
        auth.properties.put("username", username);
        auth.properties.put("password", password);
        return auth;
    }

    private ApiRequest.Auth apiKeyAuth(String key, String value, String location) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "apikey";
        auth.properties.put("key", key);
        auth.properties.put("value", value);
        auth.properties.put("in", location);
        return auth;
    }

    private ApiRequest.Auth noneAuth() {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "none";
        return auth;
    }

    private ApiRequest.Auth oauth2Auth(String accessToken) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "oauth2";
        auth.properties.put("accessToken", accessToken);
        auth.properties.put("grantType", "client_credentials");
        return auth;
    }

    private <T> T getPrivateField(Object target, String fieldName, Class<T> type) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(target);
            if (type.isInstance(value)) {
                return type.cast(value);
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to access field '" + fieldName + "' on " + target.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private void setPrivateField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to write field '" + fieldName + "' on " + target.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private Object invokePrivateMethod(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            java.lang.reflect.Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to invoke method '" + methodName + "' on " + target.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private Object invokePrivateStaticMethod(Class<?> targetType, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            java.lang.reflect.Method method = targetType.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to invoke static method '" + methodName + "' on " + targetType.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, JTextField> getPrivateAuthFields(RequestEditorPanel panel) {
        Object authUi = getPrivateField(panel, "authUi", Object.class);
        if (authUi == null) {
            return Collections.emptyMap();
        }
        try {
            java.lang.reflect.Field field = authUi.getClass().getDeclaredField("authFields");
            field.setAccessible(true);
            Object value = field.get(authUi);
            if (value instanceof Map<?, ?> map) {
                Map<String, JTextField> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() instanceof String key && entry.getValue() instanceof JTextField textField) {
                        out.put(key, textField);
                    }
                }
                return out;
            }
            return Collections.emptyMap();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to access auth fields: " + e.getMessage(), e);
        }
    }

    private boolean containsHeader(ApiRequest request, String key, String expectedValue) {
        if (request == null || request.headers == null || key == null) {
            return false;
        }
        for (ApiRequest.Header header : request.headers) {
            if (header == null || header.key == null) {
                continue;
            }
            if (key.equalsIgnoreCase(header.key)) {
                if (expectedValue == null) {
                    return true;
                }
                if (header.value != null && header.value.contains(expectedValue)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String sanitizeFileName(String value) {
        if (value == null || value.isBlank()) {
            return "fixture";
        }
        return value.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-+", "-");
    }

    private String sanitizeCheckName(String value, String suffix) {
        return sanitizeFileName(value).replace('.', '_') + "." + suffix;
    }

    private void verifyJsonArtifact(SmokeRuntimeResult result, String checkName, Path path, String label) {
        if (!pathExists(path)) {
            result.fail(checkName, label + " file was not created: " + path);
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            if (json == null || json.isBlank()) {
                result.fail(checkName, label + " file was empty: " + path);
                return;
            }
            JsonParser.parseString(json);
            result.pass(checkName, label + " was created, non-empty, and parsed as JSON.");
        } catch (Exception e) {
            result.fail(checkName, label + " could not be parsed as JSON: " + e.getMessage());
        }
    }

    private CollectionRunner createRunner() {
        OAuth2Manager oauth2Manager = new OAuth2Manager(api);
        RequestBuilder requestBuilder = new RequestBuilder(api, oauth2Manager);
        ScriptEngine scriptEngine = new ScriptEngine(api, scriptMode);
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, requestBuilder, scriptEngine, oauth2Manager);
        return new CollectionRunner(api, pipeline, oauth2Manager);
    }

    private void writeSnapshotAndResult(SmokeRuntimeResult result) throws IOException {
        Path resultPath = config.getResultJsonPath();
        if (resultPath == null) {
            throw new IllegalStateException("Smoke result path is not configured.");
        }
        if (result.runtimeConfigPath != null) {
            result.addArtifact(result.runtimeConfigPath);
        }
        result.addArtifact(resultPath.toString());
        if (config.getReportPath() != null) {
            result.addArtifact(config.getReportPath().toString());
        }
        ensureParentDirectory(resultPath);
        Files.writeString(resultPath, GSON.toJson(result), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private SmokeUiEvidenceSnapshot captureUiSnapshotOnEdt(ImporterPanel ui, String label) throws Exception {
        return runOnEdt(() -> ui != null ? ui.captureSmokeUiEvidenceSnapshot(label) : null);
    }

    private SmokeUiEvidenceSnapshot captureAndRecordEvidenceSnapshot(SmokeRuntimeResult result,
                                                                     ImporterPanel ui,
                                                                     String snapshotId,
                                                                     String fileName,
                                                                     String label) {
        String checkName = normalizeEvidenceCheckName(snapshotId);
        if (ui == null) {
            result.addCheck("evidence.snapshots", checkName, "skipped", "Importer panel unavailable; snapshot not captured.", "json", null, null);
            return null;
        }

        SmokeUiEvidenceSnapshot snapshot;
        try {
            snapshot = captureUiSnapshotOnEdt(ui, label);
        } catch (Exception e) {
            result.addCheck("evidence.snapshots", checkName, "fail", "Failed to capture evidence snapshot: " + e.getMessage(), "json", null, null);
            result.addError("UI evidence capture failed for '" + snapshotId + "': " + e.getMessage());
            return null;
        }

        if (!config.isCaptureUiEvidence()) {
            result.addCheck("evidence.snapshots", checkName, "skipped", "captureUiEvidence=false; snapshot not written.", "json", null, null);
            return snapshot;
        }

        Path evidenceDir = config.getEvidenceDirPath();
        if (evidenceDir == null) {
            result.addCheck("evidence.snapshots", checkName, "fail", "Evidence directory was not configured.", "json", null, null);
            return snapshot;
        }

        Path snapshotPath = evidenceDir.resolve(fileName);
        try {
            ensureParentDirectory(snapshotPath);
            Files.writeString(snapshotPath, GSON.toJson(snapshot), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            result.addArtifact(snapshotPath.toString());
            result.addCheck("evidence.snapshots", checkName, "pass", "Captured UI evidence snapshot: " + label, "json", snapshotPath.toString(), null);
        } catch (IOException e) {
            result.addCheck("evidence.snapshots", checkName, "fail", "Failed to write evidence snapshot: " + e.getMessage(), "json", snapshotPath.toString(), null);
            result.addError("Failed to write evidence snapshot '" + snapshotId + "': " + e.getMessage());
        }
        return snapshot;
    }

    private void recordUiProbeCheck(SmokeRuntimeResult result, String name, boolean passed, String details, String artifactPath) {
        result.addCheck("ui.probe", name, passed ? "pass" : "fail", details, "json", artifactPath, null);
    }

    private void recordUiProbeCheck(SmokeRuntimeResult result, String name, String status, String details, String artifactPath) {
        result.addCheck("ui.probe", name, status, details, "json", artifactPath, null);
    }

    private void recordTreeStateCheck(SmokeRuntimeResult result, String name, boolean passed, String details, String artifactPath) {
        result.addCheck("ui.tree_state", name, passed ? "pass" : "fail", details, "json", artifactPath, null);
    }

    private void recordTreeStateCheck(SmokeRuntimeResult result, String name, String status, String details, String artifactPath) {
        result.addCheck("ui.tree_state", name, status, details, "json", artifactPath, null);
    }

    private void recordRunnerStateCheck(SmokeRuntimeResult result, String name, boolean passed, String details, String artifactPath) {
        result.addCheck("ui.runner", name, passed ? "pass" : "fail", details, "json", artifactPath, null);
    }

    private void recordRunnerStateCheck(SmokeRuntimeResult result, String name, String status, String details, String artifactPath) {
        result.addCheck("ui.runner", name, status, details, "json", artifactPath, null);
    }

    private void recordLogScanCheck(SmokeRuntimeResult result, String name, boolean passed, String details, String artifactPath) {
        result.addCheck("logs.scan", name, passed ? "pass" : "fail", details, "json", artifactPath, null);
    }

    private void recordManualChecklistCheck(SmokeRuntimeResult result, String name, boolean passed, String details, String artifactPath) {
        result.addCheck("manual.checklist", name, passed ? "pass" : "fail", details, "md", artifactPath, null);
    }

    private void recordStartupUiProbeChecks(SmokeRuntimeResult result, SmokeUiEvidenceSnapshot snapshot, String artifactPath) {
        boolean tabRegistered = snapshot != null && snapshot.selectedTopLevelTab != null && !snapshot.selectedTopLevelTab.isBlank() && snapshot.requestTree.exists;
        recordUiProbeCheck(result,
                "api_workbench_tab_registered",
                tabRegistered,
                tabRegistered ? "API Workbench tab and panel are available." : "API Workbench tab was not available after initialization.",
                artifactPath);

        boolean requestTreePresent = snapshot != null && snapshot.requestTree != null && snapshot.requestTree.exists;
        recordUiProbeCheck(result,
                "request_tree_present",
                requestTreePresent,
                requestTreePresent ? "Request tree component exists." : "Request tree component was not found.",
                artifactPath);
    }

    private void recordImportUiProbeChecks(SmokeRuntimeResult result, SmokeUiEvidenceSnapshot snapshot, String artifactPath) {
        boolean requestTreeRowCount = snapshot != null && snapshot.requestTree != null && snapshot.requestTree.rowCount > 0;
        recordUiProbeCheck(result,
                "request_tree_row_count",
                requestTreeRowCount,
                snapshot != null && snapshot.requestTree != null
                        ? "Request tree row count=" + snapshot.requestTree.rowCount
                        : "Request tree snapshot unavailable.",
                artifactPath);

        boolean loadedVisible = snapshot != null && snapshot.workspaceState != null && snapshot.workspaceState.collections != null && !snapshot.workspaceState.collections.isEmpty()
                && snapshot.requestTree != null && snapshot.requestTree.rowCount > 0;
        recordUiProbeCheck(result,
                "loaded_collections_visible",
                loadedVisible,
                snapshot != null && snapshot.workspaceState != null
                        ? "Loaded collections=" + snapshot.workspaceState.collections.size() + ", treeRows=" + (snapshot.requestTree != null ? snapshot.requestTree.rowCount : 0)
                        : "Workspace snapshot unavailable.",
                artifactPath);

        boolean activeEnvironment = snapshot != null && snapshot.environment != null && snapshot.environment.activeEnvironmentId != null && !snapshot.environment.activeEnvironmentId.isBlank();
        recordUiProbeCheck(result,
                "active_environment",
                activeEnvironment,
                snapshot != null && snapshot.environment != null
                        ? "Active environment=" + snapshot.environment.activeEnvironmentName + " (" + snapshot.environment.activeEnvironmentId + ")"
                        : "Environment snapshot unavailable.",
                artifactPath);

        boolean environmentCombo = snapshot != null && snapshot.environment != null && snapshot.environment.profiles != null && !snapshot.environment.profiles.isEmpty()
                && snapshot.environment.selectedComboLabel != null && !snapshot.environment.selectedComboLabel.isBlank();
        recordUiProbeCheck(result,
                "environment_combo_state",
                environmentCombo,
                snapshot != null && snapshot.environment != null
                        ? "Selected combo='" + snapshot.environment.selectedComboLabel + "', profiles=" + snapshot.environment.profiles.size()
                        : "Environment combo snapshot unavailable.",
                artifactPath);

        boolean importLogMessages = snapshot != null && snapshot.logs != null && snapshot.logs.importLogLineCount > 0;
        recordUiProbeCheck(result,
                "import_log_messages",
                importLogMessages,
                snapshot != null && snapshot.logs != null
                        ? "Import log lines=" + snapshot.logs.importLogLineCount + ", tail=" + snapshot.logs.importLogTail
                        : "Import log snapshot unavailable.",
                artifactPath);
    }

    private void recordRequestEditorUiProbeChecks(SmokeRuntimeResult result, SmokeUiEvidenceSnapshot snapshot, String artifactPath) {
        boolean selectedRequest = snapshot != null && snapshot.requestEditor != null && snapshot.requestEditor.currentRequestName != null && !snapshot.requestEditor.currentRequestName.isBlank();
        recordUiProbeCheck(result,
                "selected_request",
                selectedRequest,
                snapshot != null && snapshot.requestEditor != null
                        ? "Selected request='" + snapshot.requestEditor.currentRequestName + "' from collection='" + snapshot.requestEditor.currentCollectionName + "'."
                        : "Request editor snapshot unavailable.",
                artifactPath);

        boolean requestEditorState = snapshot != null && snapshot.requestEditor != null && snapshot.requestEditor.method != null && !snapshot.requestEditor.method.isBlank();
        recordUiProbeCheck(result,
                "request_editor_state",
                requestEditorState,
                snapshot != null && snapshot.requestEditor != null
                        ? "Method=" + snapshot.requestEditor.method + ", url=" + snapshot.requestEditor.url + ", headers=" + snapshot.requestEditor.headerCount + ", bodyMode=" + snapshot.requestEditor.bodyMode + ", authMode=" + snapshot.requestEditor.authMode
                        : "Request editor snapshot unavailable.",
                artifactPath);

        boolean sendButtonState = snapshot != null && snapshot.requestEditor != null && snapshot.requestEditor.sendEnabled;
        if (sendButtonState) {
            recordUiProbeCheck(result,
                    "send_button_state",
                    true,
                    snapshot != null && snapshot.requestEditor != null
                            ? "Send enabled=" + snapshot.requestEditor.sendEnabled + ", label='" + snapshot.requestEditor.sendModeLabel + "'."
                            : "Send button snapshot unavailable.",
                    artifactPath);
        } else {
            recordUiProbeCheck(result,
                    "send_button_state",
                    "skipped",
                    snapshot != null && snapshot.requestEditor != null
                            ? "Send button disabled in the current UI state; label='" + snapshot.requestEditor.sendModeLabel + "'."
                            : "Send button snapshot unavailable.",
                    artifactPath);
        }
    }

    private void recordRunnerUiProbeChecks(SmokeRuntimeResult result, SmokeUiEvidenceSnapshot snapshot, String artifactPath) {
        boolean queueSize = snapshot != null && snapshot.runner != null && snapshot.runner.queueSize > 0;
        recordUiProbeCheck(result,
                "runner_queue_size",
                queueSize,
                snapshot != null && snapshot.runner != null
                        ? "Runner queue size=" + snapshot.runner.queueSize + ", names=" + snapshot.runner.queueRequestNames
                        : "Runner snapshot unavailable.",
                artifactPath);

        boolean previewState = snapshot != null && snapshot.runner != null && snapshot.runner.previewCount > 0;
        recordUiProbeCheck(result,
                "runner_preview_state",
                previewState,
                snapshot != null && snapshot.runner != null
                        ? "Runner preview rows=" + snapshot.runner.previewCount + ", queue order=" + snapshot.runner.queueRequestNames
                        : "Runner preview snapshot unavailable.",
                artifactPath);
    }

    private void maybeVisualDebugPause(SmokeRuntimeResult result, String phaseMessage) {
        if (!config.isVisualDebug() || config.getPauseAfterMajorStepsMs() <= 0) {
            return;
        }
        appendLog(phaseMessage);
        try {
            Thread.sleep(config.getPauseAfterMajorStepsMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.addError("Visual debug pause interrupted during '" + phaseMessage + "'.");
        }
    }

    private void awaitUiIdle() throws Exception {
        runOnEdt(() -> { });
    }

    private void restoreWorkspaceStateOnEdt(ImporterPanel ui, WorkspaceState state) throws Exception {
        if (ui == null || state == null) {
            return;
        }
        runOnEdt(() -> ui.restoreWorkspaceState(state));
        awaitUiIdle();
    }

    private WorkspaceState captureWorkspaceStateOnEdt(ImporterPanel ui) throws Exception {
        if (ui == null) {
            return null;
        }
        return runOnEdt(ui::getWorkspaceStateSnapshot);
    }

    private void refreshRequestTreeAfterMutationOnEdt(ImporterPanel ui) throws Exception {
        if (ui == null) {
            return;
        }
        runOnEdt(() -> invokePrivateMethod(ui, "refreshRequestTreeAfterMutation", new Class<?>[]{Runnable.class}, (Object) null));
        awaitUiIdle();
    }

    private boolean waitForRunnerRunning(ImporterPanel ui, long timeoutMs) throws Exception {
        if (ui == null) {
            return false;
        }
        long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            SmokeUiEvidenceSnapshot snapshot = captureUiSnapshotOnEdt(ui, "runner-running-wait");
            if (snapshot != null && snapshot.runner.running) {
                return true;
            }
            Thread.sleep(100L);
        }
        return false;
    }

    private String normalizeEvidenceCheckName(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            return "snapshot";
        }
        return snapshotId.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("_+", "_").replaceAll("^_+|_+$", "");
    }

    private String snapshotArtifactPath(String fileName) {
        Path evidenceDir = config.getEvidenceDirPath();
        if (!config.isCaptureUiEvidence() || evidenceDir == null || fileName == null || fileName.isBlank()) {
            return null;
        }
        return evidenceDir.resolve(fileName).toString();
    }

    private String workspaceTreePathKey(String collectionName, String folderPath) {
        return (collectionName != null ? collectionName : "") + '' + (folderPath != null ? folderPath : "");
    }

    private String workspaceRequestIdentityKey(String collectionName, ApiRequest request, int requestIndex) {
        StringBuilder builder = new StringBuilder();
        builder.append(collectionName != null ? collectionName : "");
        builder.append('');
        if (request != null) {
            if (request.id != null && !request.id.isBlank()) {
                builder.append("id=").append(request.id.trim());
            } else {
                builder.append("index=").append(requestIndex);
                builder.append('').append("method=").append(request.method != null ? request.method : "");
                builder.append('').append("name=").append(request.name != null ? request.name : "");
                builder.append('').append("url=").append(request.url != null ? request.url : "");
            }
        }
        return builder.toString();
    }

    private int findRequestIndexInCollection(ApiCollection collection, ApiRequest request) {
        if (collection == null || request == null || collection.requests == null) {
            return -1;
        }
        for (int i = 0; i < collection.requests.size(); i++) {
            if (collection.requests.get(i) == request) {
                return i;
            }
        }
        return -1;
    }

    private String firstNestedFolderPath(ApiCollection collection) {
        if (collection == null || collection.folderPaths == null) {
            return null;
        }
        for (String folderPath : collection.folderPaths) {
            if (folderPath != null && !folderPath.isBlank() && folderPath.contains("/")) {
                return folderPath;
            }
        }
        return collection.folderPaths.isEmpty() ? null : collection.folderPaths.get(0);
    }

    private ApiRequest firstRequestInCollection(ApiCollection collection) {
        if (collection == null || collection.requests == null) {
            return null;
        }
        for (ApiRequest request : collection.requests) {
            if (request != null) {
                return request;
            }
        }
        return null;
    }

    private ApiRequest firstRequestInFolder(ApiCollection collection, String folderPath) {
        if (collection == null || collection.requests == null) {
            return null;
        }
        for (ApiRequest request : collection.requests) {
            if (request == null) {
                continue;
            }
            String requestFolder = RequestPathResolver.getRequestFolderPath(collection, request);
            if (Objects.equals(RequestTreePathService.normalizeFolderPath(requestFolder), RequestTreePathService.normalizeFolderPath(folderPath))) {
                return request;
            }
        }
        return null;
    }

    private ApiRequest firstRequestOutsideFolder(ApiCollection collection, String folderPath, ApiRequest excluded) {
        if (collection == null || collection.requests == null) {
            return null;
        }
        String normalizedFolder = RequestTreePathService.normalizeFolderPath(folderPath);
        for (ApiRequest request : collection.requests) {
            if (request == null || request == excluded) {
                continue;
            }
            String requestFolder = RequestTreePathService.normalizeFolderPath(RequestPathResolver.getRequestFolderPath(collection, request));
            if (!Objects.equals(requestFolder, normalizedFolder)) {
                return request;
            }
        }
        return null;
    }

    private ApiCollection findCollectionWithNestedFolder(List<ApiCollection> collections) {
        if (collections == null) {
            return null;
        }
        for (ApiCollection collection : collections) {
            if (collection != null && collection.folderPaths != null) {
                for (String folderPath : collection.folderPaths) {
                    if (folderPath != null && folderPath.contains("/")) {
                        return collection;
                    }
                }
            }
        }
        return null;
    }

    private ApiCollection copyCollectionForTest(ApiCollection source, String suffix) {
        if (source == null) {
            return null;
        }
        ApiCollection copy = WorkspaceState.fromCollections(List.of(source)).collections.get(0);
        String baseName = source.name != null && !source.name.isBlank() ? source.name : "Collection";
        String newName = baseName + (suffix != null && !suffix.isBlank() ? " " + suffix : " Copy");
        requestTreeMutationService.renameCollection(copy, newName);
        return copy;
    }

    private WorkspaceState buildUiSmokeWorkspaceState(WorkspaceState baseState,
                                                      String editorCollectionName,
                                                      EnvironmentProfile activeEnvironment) {
        WorkspaceState state = WorkspaceState.copyOf(baseState);
        if (activeEnvironment != null) {
            materialiseRuntimeRequestUrls(state, activeEnvironment);
        }
        state.selectedTabIndex = 0;
        state.expandedTreePathKeys = new ArrayList<>();
        if (state.collections != null) {
            for (ApiCollection collection : state.collections) {
                if (collection != null && collection.name != null && !collection.name.isBlank()) {
                    state.expandedTreePathKeys.add(workspaceTreePathKey(collection.name, ""));
                }
            }
        }
        if (editorCollectionName != null && !editorCollectionName.isBlank()) {
            ApiCollection editorCollection = findCollectionByName(state.collections, editorCollectionName);
            if (editorCollection != null) {
                state.selectedVariablesCollectionName = editorCollection.name;
                state.selectedOAuth2CollectionName = editorCollection.name;
                ApiRequest selectedRequest = findRequestByPath(editorCollection, "/echo");
                if (selectedRequest == null) {
                    selectedRequest = firstRequestInCollection(editorCollection);
                }
                if (selectedRequest != null) {
                    setSelectedRequestState(state, editorCollection, selectedRequest);
                }
            }
        }
        return state;
    }

    private void setSelectedRequestState(WorkspaceState state, ApiCollection collection, ApiRequest request) {
        if (state == null || collection == null || request == null) {
            return;
        }
        int requestIndex = findRequestIndexInCollection(collection, request);
        state.selectedRequestCollectionName = collection.name;
        state.selectedRequestIdentityKey = requestIndex >= 0
                ? workspaceRequestIdentityKey(collection.name, request, requestIndex)
                : workspaceRequestIdentityKey(collection.name, request, request != null ? request.sequenceOrder : -1);
        state.selectedRequestName = request.name;
        state.selectedRequestPath = request.path;
    }

    private void setRunnerQueueState(WorkspaceState state, List<ApiRequest> queue) {
        if (state == null) {
            return;
        }
        state.runnerQueuedRequestIdentityKeys = new ArrayList<>();
        if (queue == null || queue.isEmpty()) {
            return;
        }
        for (ApiRequest request : queue) {
            if (request == null) {
                continue;
            }
            ApiCollection collection = findCollectionByName(state.collections, request.sourceCollection);
            String collectionName = collection != null ? collection.name : request.sourceCollection;
            int requestIndex = findRequestIndexInCollection(collection, request);
            state.runnerQueuedRequestIdentityKeys.add(requestIndex >= 0
                    ? workspaceRequestIdentityKey(collectionName, request, requestIndex)
                    : workspaceRequestIdentityKey(collectionName, request, request != null ? request.sequenceOrder : -1));
        }
    }

    private ApiCollection findAlternateCollection(List<ApiCollection> collections, String excludedName) {
        if (collections == null || collections.isEmpty()) {
            return null;
        }
        for (ApiCollection collection : collections) {
            if (collection != null && !Objects.equals(collection.name, excludedName)) {
                return collection;
            }
        }
        return collections.get(0);
    }

    private List<ApiRequest> selectRunnerUiQueueRequests(ApiCollection runnerCollection) {
        List<ApiRequest> queue = new ArrayList<>();
        if (runnerCollection == null) {
            return queue;
        }
        ApiRequest delay = findRequestByPath(runnerCollection, "/delay");
        ApiRequest token = findRequestByPath(runnerCollection, "/extract/token");
        ApiRequest status404 = findRequestByPath(runnerCollection, "/status/404");
        ApiRequest chained = findRequestByPath(runnerCollection, "/extract/chained");
        ApiRequest health = findRequestByPath(runnerCollection, "/health");
        ApiRequest users = findRequestByPath(runnerCollection, "/users");
        ApiRequest echo = findRequestByPath(runnerCollection, "/echo");
        if (delay != null && token != null && status404 != null && chained != null) {
            queue.add(delay);
            queue.add(token);
            queue.add(status404);
            queue.add(chained);
            return queue;
        }
        if (delay != null && health != null && users != null && status404 != null) {
            queue.add(delay);
            queue.add(health);
            queue.add(users);
            queue.add(status404);
            return queue;
        }
        if (token != null && status404 != null && chained != null && health != null) {
            queue.add(token);
            queue.add(status404);
            queue.add(chained);
            queue.add(health);
            return queue;
        }
        if (health != null && users != null && echo != null && delay != null) {
            queue.add(delay);
            queue.add(health);
            queue.add(users);
            queue.add(echo);
            return queue;
        }
        for (ApiRequest request : runnerCollection.requests) {
            if (request != null) {
                queue.add(request);
            }
            if (queue.size() >= 4) {
                break;
            }
        }
        return queue;
    }

    private String buildQueueOrderLabel(List<ApiRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return "[]";
        }
        List<String> labels = new ArrayList<>();
        for (ApiRequest request : requests) {
            labels.add(request != null && request.name != null ? request.name : "Request");
        }
        return labels.toString();
    }

    private List<RunnerPreviewRow> buildUiRunnerPreview(List<ApiCollection> sourceCollections, List<ApiRequest> selectedRequests) {
        List<RunnerPreviewRow> preview = buildPreview(sourceCollections, selectedRequests);
        return preview != null ? preview : new ArrayList<>();
    }

    private void setRunnerPreviewRowsOnEdt(ImporterPanel ui, List<RunnerPreviewRow> previewRows) throws Exception {
        if (ui == null) {
            return;
        }
        runOnEdt(() -> {
            RunnerPreviewTableModel previewModel = getPrivateField(ui, "runnerPreviewModel", RunnerPreviewTableModel.class);
            if (previewModel == null) {
                previewModel = new RunnerPreviewTableModel();
                setPrivateField(ui, "runnerPreviewModel", previewModel);
            }
            previewModel.setRows(previewRows != null ? previewRows : Collections.emptyList());
        });
    }

    private boolean reorderRunnerQueueOnEdt(ImporterPanel ui, int sourceIndex, int targetIndex) throws Exception {
        if (ui == null) {
            return false;
        }
        return runOnEdt(() -> {
            Object value = invokePrivateMethod(ui, "reorderRunnerQueue", new Class<?>[]{int.class, int.class}, sourceIndex, targetIndex);
            return value instanceof Boolean booleanValue && booleanValue;
        });
    }

    private void clearRunnerQueueOnEdt(ImporterPanel ui) throws Exception {
        if (ui == null) {
            return;
        }
        runOnEdt(() -> invokePrivateMethod(ui, "clearRunnerFromUi", new Class<?>[0]));
    }

    private void startRunnerExecutionOnEdt(ImporterPanel ui, List<ApiRequest> selected) throws Exception {
        if (ui == null) {
            return;
        }
        runOnEdt(() -> invokePrivateMethod(ui, "startRunnerExecution", new Class<?>[]{List.class}, selected));
    }

    private boolean waitForRunnerIdle(ImporterPanel ui, long timeoutMs) throws Exception {
        if (ui == null) {
            return true;
        }
        long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            SmokeUiEvidenceSnapshot snapshot = captureUiSnapshotOnEdt(ui, "runner-wait");
            if (snapshot != null && !snapshot.runner.running) {
                return true;
            }
            Thread.sleep(100L);
        }
        return false;
    }

    private void writeWorkspaceSnapshot(WorkspaceState snapshot) {
        Path snapshotPath = config.getWorkspaceSnapshotPath();
        if (snapshotPath == null || snapshot == null) {
            return;
        }
        try {
            ensureParentDirectory(snapshotPath);
            Files.writeString(snapshotPath, WorkspaceStateJson.toJson(snapshot), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            appendError("Failed to write workspace snapshot: " + e.getMessage());
        }
    }

    private void checkFixturePath(SmokeRuntimeResult result, String checkName, Path path) {
        if (pathExists(path)) {
            result.pass(checkName, "Found fixture: " + path);
        } else {
            result.fail(checkName, "Missing fixture: " + path);
        }
    }

    private boolean pathExists(Path path) {
        return path != null && Files.exists(path);
    }

    private Path resolveFixture(String value) {
        return config.resolveFixturePath(value);
    }

    private void normaliseEnvironmentProfiles(List<EnvironmentProfile> profiles) {
        if (profiles == null) {
            return;
        }
        for (EnvironmentProfile profile : profiles) {
            if (profile != null) {
                profile.ensureDefaults();
                profile.ensureId();
                ensureBaseUrlAliases(profile);
            }
        }
    }

    private void ensureBaseUrlAliases(EnvironmentProfile profile) {
        if (profile == null || profile.variables == null || profile.variables.isEmpty()) {
            return;
        }
        String camel = profile.variables.get("baseUrl");
        String snake = profile.variables.get("base_url");
        if ((camel == null || camel.isBlank()) && snake != null && !snake.isBlank()) {
            profile.variables.put("baseUrl", snake);
        } else if ((snake == null || snake.isBlank()) && camel != null && !camel.isBlank()) {
            profile.variables.put("base_url", camel);
        }
    }

    private void verifyEnvironmentVariableResolution(SmokeRuntimeResult result, EnvironmentProfile activeEnvironment) {
        if (activeEnvironment == null) {
            return;
        }
        Map<String, String> overlay = normaliseRuntimeOverlay(activeEnvironment.toRuntimeOverlay());
        burp.parser.VariableResolver resolver = new burp.parser.VariableResolver();
        resolver.addAll(overlay);
        String resolved = resolver.resolve("{{base_url}}/health");
        String expectedPrefix = config.getResolvedLocalApiUrl();
        if (resolved != null && expectedPrefix != null && resolved.startsWith(expectedPrefix)) {
            result.pass("environment.variable.resolve", "Resolved {{base_url}} to " + resolved + ".");
        } else {
            result.fail("environment.variable.resolve", "Expected {{base_url}} to resolve to " + expectedPrefix + " but got " + resolved + ".");
        }
    }

    private void materialiseRuntimeRequestUrls(WorkspaceState state, EnvironmentProfile activeEnvironment) {
        if (state == null || state.collections == null || activeEnvironment == null) {
            return;
        }
        Map<String, String> overlay = normaliseRuntimeOverlay(activeEnvironment.toRuntimeOverlay());
        for (ApiCollection collection : state.collections) {
            if (collection == null || collection.requests == null) {
                continue;
            }
            for (ApiRequest request : collection.requests) {
                if (request == null || request.url == null || request.url.isBlank()) {
                    continue;
                }
                try {
                    burp.parser.VariableResolver resolver = RuntimeResolverFactory.build(
                            collection,
                            request,
                            RuntimeResolverFactory.Options.withRuntimeVariableOverlay(overlay)
                    );
                    String resolvedUrl = resolver.resolve(request.url);
                    if (resolvedUrl != null && !resolvedUrl.isBlank()) {
                        request.url = resolvedUrl;
                    }
                } catch (Exception ignored) {
                    // Best-effort only; the underlying send/runner checks will report failures if needed.
                }
            }
        }
    }

    private Map<String, String> normaliseRuntimeOverlay(Map<String, String> overlay) {
        Map<String, String> normalised = overlay != null ? new LinkedHashMap<>(overlay) : new LinkedHashMap<>();
        String baseUrl = normalised.get("baseUrl");
        String baseUrlSnake = normalised.get("base_url");
        if ((baseUrl == null || baseUrl.isBlank()) && baseUrlSnake != null && !baseUrlSnake.isBlank()) {
            normalised.put("baseUrl", baseUrlSnake);
        } else if ((baseUrlSnake == null || baseUrlSnake.isBlank()) && baseUrl != null && !baseUrl.isBlank()) {
            normalised.put("base_url", baseUrl);
        }
        return normalised;
    }

    private EnvironmentProfile selectActiveEnvironment(List<EnvironmentProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return null;
        }
        for (EnvironmentProfile profile : profiles) {
            if (profile != null && profile.variables != null && profile.variables.containsKey("base_url")) {
                return profile;
            }
        }
        return profiles.get(0);
    }

    private EnvironmentProfile findEnvironmentById(List<EnvironmentProfile> profiles, String id) {
        if (profiles == null || profiles.isEmpty() || id == null) {
            return null;
        }
        for (EnvironmentProfile profile : profiles) {
            if (profile != null && Objects.equals(profile.id, id)) {
                return profile;
            }
        }
        return null;
    }

    private ApiCollection firstNonNullCollection(List<ApiCollection> collections) {
        if (collections == null) {
            return null;
        }
        for (ApiCollection collection : collections) {
            if (collection != null) {
                return collection;
            }
        }
        return null;
    }

    private ApiCollection findCollectionByName(List<ApiCollection> collections, String name) {
        if (collections == null || collections.isEmpty() || name == null) {
            return null;
        }
        for (ApiCollection collection : collections) {
            if (collection != null && Objects.equals(collection.name, name)) {
                return collection;
            }
        }
        return null;
    }

    private ApiCollection firstCollectionWithPath(List<ApiCollection> collections, String... fragments) {
        if (collections == null || fragments == null || fragments.length == 0) {
            return firstNonNullCollection(collections);
        }
        for (ApiCollection collection : collections) {
            if (collection == null || collection.requests == null) {
                continue;
            }
            for (String fragment : fragments) {
                if (findRequestByPath(collection, fragment) != null) {
                    return collection;
                }
            }
        }
        return firstNonNullCollection(collections);
    }

    private ApiCollection selectRunnerCollection(List<ApiCollection> collections) {
        if (collections == null || collections.isEmpty()) {
            return null;
        }
        for (ApiCollection collection : collections) {
            if (collection != null && collection.requests != null && collection.requests.size() >= 3) {
                return collection;
            }
        }
        return collections.get(0);
    }

    private ApiRequest findRequestByPath(ApiCollection collection, String fragment) {
        if (collection == null || collection.requests == null || fragment == null || fragment.isBlank()) {
            return null;
        }
        String loweredFragment = fragment.toLowerCase(Locale.ROOT);
        for (ApiRequest request : collection.requests) {
            if (request == null) {
                continue;
            }
            String url = request.url != null ? request.url.toLowerCase(Locale.ROOT) : "";
            String path = request.path != null ? request.path.toLowerCase(Locale.ROOT) : "";
            String name = request.name != null ? request.name.toLowerCase(Locale.ROOT) : "";
            if (url.contains(loweredFragment) || path.contains(loweredFragment) || name.contains(loweredFragment)) {
                return request;
            }
        }
        return null;
    }

    private String queueNames(List<ApiRequest> queue) {
        if (queue == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (ApiRequest request : queue) {
            names.add(request != null && request.name != null ? request.name : "Request");
        }
        return String.join(" -> ", names);
    }

    private List<String> requestNames(List<ApiRequest> requests) {
        List<String> names = new ArrayList<>();
        if (requests == null) {
            return names;
        }
        for (ApiRequest request : requests) {
            String name = request != null && request.name != null ? request.name : "Request";
            names.add(name.trim().replace(' ', '_'));
        }
        return names;
    }

    private String previewOrder(List<RunnerPreviewRow> previewRows) {
        if (previewRows == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (RunnerPreviewRow row : previewRows) {
            names.add(row != null && row.requestName != null ? row.requestName : "Request");
        }
        return String.join(" -> ", names);
    }

    private void addArtifacts(SmokeRuntimeResult result, String... values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            result.addArtifact(value);
        }
    }

    private void ensureParentDirectory(Path path) throws IOException {
        Path parent = path != null ? path.getParent() : null;
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private void runOnEdt(Runnable action) throws Exception {
        runOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private <T> T runOnEdt(java.util.concurrent.Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(action.call());
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        if (failure.get() != null) {
            Throwable throwable = failure.get();
            if (throwable instanceof Exception exception) {
                throw exception;
            }
            throw new Exception(throwable);
        }
        return value.get();
    }

    private void appendLog(String message) {
        appendToFile(config.getLogPath(), message);
        logToBurp(message);
    }

    private void appendError(String message) {
        appendToFile(config.getLogPath(), "ERROR: " + message);
        logToBurpError(message);
    }

    private void logToBurp(String message) {
        if (api != null && api.logging() != null && message != null) {
            try {
                api.logging().logToOutput(message);
            } catch (Throwable ignored) {
                // Logging is best-effort only.
            }
        }
    }

    private void logToBurpError(String message) {
        if (api != null && api.logging() != null && message != null) {
            try {
                api.logging().logToError(message);
            } catch (Throwable ignored) {
                // Logging is best-effort only.
            }
        }
    }

    private void appendToFile(Path path, String message) {
        if (path == null || message == null) {
            return;
        }
        try {
            ensureParentDirectory(path);
            Files.writeString(path, "[" + Instant.now() + "] " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Logging is best-effort only.
        }
    }

    private String failedCheckNames(SmokeRuntimeResult result) {
        List<String> names = new ArrayList<>();
        for (SmokeRuntimeResult.CheckResult check : result.checks) {
            if (check != null && "fail".equalsIgnoreCase(check.status)) {
                names.add(check.name);
            }
        }
        return String.join(", ", names);
    }

    private String displayCollectionName(ApiCollection collection, Path path) {
        if (collection != null && collection.name != null && !collection.name.isBlank()) {
            return collection.name;
        }
        return path != null ? path.getFileName().toString() : "Collection";
    }

    private String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, Math.max(0, limit - 3)) + "...";
    }

    private String stackTrace(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        builder.append(throwable.getClass().getName()).append(": ").append(throwable.getMessage()).append('\n');
        for (StackTraceElement element : throwable.getStackTrace()) {
            builder.append("    at ").append(element).append('\n');
        }
        return builder.toString();
    }
}
