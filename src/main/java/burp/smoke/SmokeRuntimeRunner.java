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
import burp.ui.dnd.EnvironmentDragPayload;
import burp.ui.dnd.EnvironmentTransferHandler;
import burp.ui.dnd.RunnerQueueDragPayload;
import burp.ui.dnd.RunnerQueueTransferHandler;
import burp.ui.tree.RequestTreeDragPayload;
import burp.ui.tree.RequestTreeMutationService;
import burp.ui.tree.RequestTreeTransferHandler;
import burp.ui.tree.TreeDropRequest;
import burp.utils.EnvironmentImportService;
import burp.utils.RequestBuilder;
import burp.utils.RuntimeResolverFactory;
import burp.utils.ScriptEngine;
import burp.utils.ScriptMode;
import burp.utils.SharedRequestPipeline;
import burp.utils.WorkspaceStateJson;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import javax.swing.*;
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
        result.extensionJar = config.extensionJar;
        result.burpPath = config.burpPath;
        result.localApi = config.getResolvedLocalApiUrl();
        result.setLogConsumer(this::appendLog);
        result.metadata.put("scriptMode", scriptMode != null ? scriptMode.name() : "unknown");
        result.metadata.put("scriptModeLabel", scriptMode != null ? scriptMode.label : "unknown");
        result.metadata.put("configPath", config.configPath);
        result.metadata.put("apiWorkbenchRepo", config.apiWorkbenchRepo);
        result.metadata.put("testerRepo", config.testerRepo);
        result.addNote("Manual-only behavior that remains out of scope: mouse-driven drag/drop in the live Burp UI and interactive dialog confirmation flows.");

        addArtifacts(result, config.configPath, config.logPath, config.burpLogPath, config.localApiLogPath,
                config.workspaceSnapshotPath, config.collectionExportPath, config.environmentExportPath);

        try {
            appendLog("=== API Workbench runtime smoke starting ===");
            appendLog("Config: " + config.configPath);
            appendLog("Burp path: " + config.burpPath);
            appendLog("Extension JAR: " + config.extensionJar);
            appendLog("Local API: " + result.localApi);
            appendLog("Script mode: " + result.metadata.get("scriptModeLabel"));

            ImporterPanel ui = importer != null ? importer.getUI() : null;
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

            WorkspaceState uiState = WorkspaceState.copyOf(baseState);
            WorkspaceState sendState = WorkspaceState.copyOf(baseState);
            WorkspaceState treeState = WorkspaceState.copyOf(baseState);
            WorkspaceState runnerState = WorkspaceState.copyOf(baseState);

            if (activeEnvironment != null) {
                materialiseRuntimeRequestUrls(sendState, activeEnvironment);
                materialiseRuntimeRequestUrls(runnerState, activeEnvironment);
            }

            if (ui != null && uiState.collections != null && !uiState.collections.isEmpty()) {
                runOnEdt(() -> ui.restoreWorkspaceState(uiState));
                WorkspaceState restored = runOnEdt(() -> ui.getWorkspaceStateSnapshot());
                verifyRestoredWorkspaceState(result, uiState, restored);
                writeWorkspaceSnapshot(restored);
            } else {
                result.skipped("restore.workspace.snapshot", "Workspace snapshot restore skipped because no collections were imported.");
            }

            if (activeEnvironment != null && localApiReady) {
                sendCollection = postmanCollection != null
                        ? findCollectionByName(sendState.collections, postmanCollection.name)
                        : firstCollectionWithPath(sendState.collections, "/health", "/users", "/echo", "/headers", "/auth/bearer", "/status/404", "/delay");
                EnvironmentProfile sendEnvironment = findEnvironmentById(sendState.environments, activeEnvironment.id);
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
                    ? findCollectionByName(runnerState.collections, postmanCollection.name)
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
            } else {
                result.skipped("export.collection", "Collection export was skipped because a collection and active environment were not available.");
                result.skipped("export.environment", "Environment export was skipped because a collection and active environment were not available.");
            }

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
        checkFixturePath(result, "fixtures.collection.openapi", resolveFixture(config.fixtures != null ? config.fixtures.openApi : null));
        checkFixturePath(result, "fixtures.collection.postman", resolveFixture(config.fixtures != null ? config.fixtures.postman : null));
        checkFixturePath(result, "fixtures.environment.api_workbench", resolveFixture(config.fixtures != null ? config.fixtures.apiWorkbenchEnvironment : null));
        checkFixturePath(result, "fixtures.environment.postman", resolveFixture(config.fixtures != null ? config.fixtures.postmanEnvironment : null));
        checkFixturePath(result, "fixtures.environment.runtime_env", resolveFixture(config.fixtures != null ? config.fixtures.runtimeEnv : null));
        checkFixturePath(result, "fixtures.collection.unsupported", resolveFixture(config.fixtures != null ? config.fixtures.unsupported : null));
    }

    private boolean probeLocalApi(SmokeRuntimeResult result) {
        String baseUrl = config.getResolvedLocalApiUrl();
        if (baseUrl == null) {
            result.fail("fixtures.local_api.reachable", "No local API URL was configured.");
            return false;
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() != null ? response.body() : "";
            if (response.statusCode() == 200 && body.toLowerCase(Locale.ROOT).contains("ok")) {
                result.pass("fixtures.local_api.reachable", "GET /health returned 200 and confirmed the mock API is running.");
                return true;
            }
            result.fail("fixtures.local_api.reachable", "GET /health returned " + response.statusCode() + " with body: " + truncate(body, 120));
            return false;
        } catch (Exception e) {
            result.fail("fixtures.local_api.reachable", "Failed to reach local API: " + e.getMessage());
            return false;
        }
    }

    private ApiCollection importCollectionFixture(SmokeRuntimeResult result, String checkName, Path path) {
        if (!pathExists(path)) {
            result.fail(checkName, "Collection fixture missing: " + path);
            return null;
        }
        try {
            CollectionParser parser = parserRegistry.detectParser(path.toFile());
            if (parser == null) {
                result.fail(checkName, "No parser detected for collection fixture: " + path.getFileName());
                return null;
            }
            ApiCollection collection = parser.parse(path.toFile());
            if (collection == null) {
                result.fail(checkName, "Parser returned no collection for: " + path.getFileName());
                return null;
            }
            int requestCount = collection.requests != null ? collection.requests.size() : 0;
            result.pass(checkName, "Imported \"" + displayCollectionName(collection, path) + "\" with " + requestCount + " requests using " + parser.getClass().getSimpleName() + ".");
            return collection;
        } catch (Exception e) {
            result.fail(checkName, "Failed to import collection fixture '" + path.getFileName() + "': " + e.getMessage());
            return null;
        }
    }

    private List<EnvironmentProfile> importEnvironmentFixture(SmokeRuntimeResult result, String checkName, Path path) {
        List<EnvironmentProfile> imported = new ArrayList<>();
        if (!pathExists(path)) {
            result.fail(checkName, "Environment fixture missing: " + path);
            return imported;
        }
        try {
            List<EnvironmentProfile> profiles = EnvironmentImportService.importEnvironment(path.toFile());
            if (profiles == null || profiles.isEmpty()) {
                result.fail(checkName, "No environment profiles were imported from " + path.getFileName());
                return imported;
            }
            for (EnvironmentProfile profile : profiles) {
                if (profile != null) {
                    imported.add(profile);
                }
            }
            result.pass(checkName, "Imported " + imported.size() + " environment profile(s) from " + path.getFileName() + ".");
            return imported;
        } catch (Exception e) {
            result.fail(checkName, "Failed to import environment fixture '" + path.getFileName() + "': " + e.getMessage());
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
            result.fail("workbench.send.auth", "No collection was available for send checks.");
            result.fail("workbench.send.status404", "No collection was available for send checks.");
            result.fail("workbench.send.delay", "No collection was available for send checks.");
            return;
        }

        runSendCheck(result, "workbench.send.health", collection, runtimeOverlay, "/health", 200, "ok", false);
        runSendCheck(result, "workbench.send.users", collection, runtimeOverlay, "/users", 200, "users", false);
        runSendCheck(result, "workbench.send.echo", collection, runtimeOverlay, "/echo", 200, "hello from runtime environment", false);
        runSendCheck(result, "workbench.send.headers", collection, runtimeOverlay, "/headers", 200, "x-smoke-header", false);
        runSendCheck(result, "workbench.send.auth", collection, runtimeOverlay, "/auth/bearer", 200, "runtime-token", false);
        runSendCheck(result, "workbench.send.status404", collection, runtimeOverlay, "/status/404", 404, null, false);
        runSendCheck(result, "workbench.send.delay", collection, runtimeOverlay, "/delay?ms=25", 200, null, false);
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
        List<RunnerPreviewRow> preview = runner.buildRunPreview(sourceCollections, selectedRequests);
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
        return config.resolvePath(value);
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
