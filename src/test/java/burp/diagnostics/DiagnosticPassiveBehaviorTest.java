package burp.diagnostics;

import burp.exporter.EnvironmentExportFormat;
import burp.exporter.EnvironmentExportOptions;
import burp.exporter.EnvironmentExportService;
import burp.diagnostics.DiagnosticEvent;
import burp.diagnostics.DiagnosticOperation;
import burp.history.HistoryEntry;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerResult;
import burp.runner.CollectionRunner;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.scripts.ScriptVariableMutation;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.RunnerScriptTestFixtures;
import burp.utils.EnvironmentImportService;
import burp.utils.ExecutionResult;
import burp.utils.RequestBuilder;
import burp.utils.ScriptEngine;
import burp.utils.ScriptMode;
import burp.utils.SharedRequestPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticPassiveBehaviorTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        DiagnosticStore.getInstance().setCaptureEnabled(false);
        DiagnosticStore.getInstance().clear();
    }

    @Test
    void diagnosticsToggleDoesNotChangeRequestRunnerHistoryOrEnvironmentOutputs() throws Exception {
        PassiveScenarioSnapshot captureOff = captureScenario(false, "capture-off");
        PassiveScenarioSnapshot captureOn = captureScenario(true, "capture-on");

        assertThat(captureOff.diagnosticEventCount).isZero();
        assertThat(captureOff.diagnosticOperations).isEmpty();
        assertThat(captureOff.sanitizedDiagnosticReport).contains("(none)");
        assertThat(captureOff.sanitizedDiagnosticReport)
                .doesNotContain("REQUEST_BUILD")
                .doesNotContain("RUNNER_RUN")
                .doesNotContain("SCRIPT_EXECUTION");

        assertThat(captureOn.diagnosticEventCount).isGreaterThan(0);
        assertThat(captureOn.diagnosticOperations)
                .contains(DiagnosticOperation.REQUEST_BUILD,
                        DiagnosticOperation.VARIABLE_RESOLUTION,
                        DiagnosticOperation.SCRIPT_EXECUTION,
                        DiagnosticOperation.HISTORY_CAPTURE);
        assertThat(captureOn.sanitizedDiagnosticReport)
                .contains("REQUEST_BUILD")
                .contains("SCRIPT_EXECUTION");
        assertThat(captureOn.sanitizedDiagnosticReport)
                .doesNotContain("seed-token")
                .doesNotContain("seed-password")
                .doesNotContain("client-123")
                .doesNotContain("refresh_token")
                .doesNotContain("oauth2_access_token")
                .doesNotContain("Authorization")
                .doesNotContain("Bearer ")
                .doesNotContain("Cookie:");

        assertThat(captureOff.workbenchRawRequestBytes).containsExactly(captureOn.workbenchRawRequestBytes);
        assertThat(captureOff.workbenchResponseStatus).isEqualTo(captureOn.workbenchResponseStatus);
        assertThat(captureOff.workbenchResponseBody).isEqualTo(captureOn.workbenchResponseBody);
        assertThat(captureOff.workbenchEnvironmentVariables).isEqualTo(captureOn.workbenchEnvironmentVariables);
        assertVariableMutationsEquivalent(captureOff.workbenchMutations, captureOn.workbenchMutations);
        assertThat(captureOff.runnerEnvironmentVariables).isEqualTo(captureOn.runnerEnvironmentVariables);

        assertHistoryEquivalent(captureOff.historyEntry, captureOn.historyEntry);
        assertRunnerEquivalent(captureOff.runnerResult, captureOn.runnerResult);

        assertEnvironmentEquivalent(captureOff.importedEnvironment, captureOn.importedEnvironment);
        assertThat(captureOff.exportedEnvironmentJson).isEqualTo(captureOn.exportedEnvironmentJson);
        assertThat(captureOff.oauth2Bindings).isEqualTo(captureOn.oauth2Bindings);
    }

    private PassiveScenarioSnapshot captureScenario(boolean captureEnabled, String filePrefix) throws Exception {
        DiagnosticStore store = DiagnosticStore.getInstance();
        store.clear();
        store.setCaptureEnabled(captureEnabled);

        Path envFile = tempDir.resolve("diagnostics-passive.api-workbench.environment.json");
        Files.writeString(envFile, """
                {
                  "id": "diag-passive-env",
                  "name": "Diagnostics Passive",
                  "variables": {
                    "base_url": "https://api.example.test",
                    "token": "seed-token",
                    "password": "seed-password"
                  },
                  "oauth2": {
                    "config": {
                      "oauth2_grant": "client_credentials",
                      "oauth2_client_id": "client-123",
                      "oauth2_token_url": "https://auth.example.test/token"
                    },
                    "outputBindings": {
                      "accessToken": "token",
                      "refreshToken": "refresh_token"
                    }
                  }
                }
                """);

        List<EnvironmentProfile> imported = EnvironmentImportService.importEnvironment(envFile.toFile());
        assertThat(imported).hasSize(1);
        EnvironmentProfile importedEnvironment = imported.get(0);

        Path exportedPath = tempDir.resolve(filePrefix + ".exported.api-workbench.environment.json");
        new EnvironmentExportService().exportEnvironment(
                importedEnvironment,
                new EnvironmentExportOptions(EnvironmentExportFormat.API_WORKBENCH_JSON, exportedPath));
        String exportedEnvironmentJson = Files.readString(exportedPath);

        ApiCollection workbenchCollection = newCollection("Diagnostics Workbench");
        ApiRequest workbenchRequest = scriptedRequest(workbenchCollection.name);
        workbenchCollection.requests.add(workbenchRequest);
        EnvironmentProfile workbenchEnvironment = importedEnvironment.copy();

        AtomicInteger sendCount = new AtomicInteger();
        SharedRequestPipeline pipeline = new SharedRequestPipeline(
                RunnerScriptTestFixtures.mockRunnerApi(
                        sendCount,
                        new java.util.concurrent.CopyOnWriteArrayList<>(),
                        () -> RunnerScriptTestFixtures.mockResponse(201, "{\"session\":\"resp-123\"}", "application/json")),
                new RequestBuilder(null),
                new ScriptEngine(null, ScriptMode.FULL_JS),
                null);

        ExecutionResult execution = pipeline.execute(
                workbenchRequest,
                workbenchCollection,
                true,
                workbenchEnvironment.toRuntimeOverlay(),
                null,
                (collection, changedVars, removedKeys) -> applyEnvironmentDelta(workbenchEnvironment, changedVars, removedKeys),
                workbenchEnvironment);

        assertThat(sendCount.get()).isEqualTo(1);
        HistoryEntry historyEntry = HistoryEntry.fromWorkbenchExecution(
                workbenchCollection,
                workbenchRequest,
                workbenchEnvironment,
                execution,
                1,
                1,
                List.of());

        ApiCollection runnerCollection = newCollection("Diagnostics Runner");
        ApiRequest runnerRequest = scriptedRequest(runnerCollection.name);
        runnerCollection.requests.add(runnerRequest);
        EnvironmentProfile runnerEnvironment = importedEnvironment.copy();

        SharedRequestPipeline runnerPipeline = new SharedRequestPipeline(
                RunnerScriptTestFixtures.mockRunnerApi(
                        new AtomicInteger(),
                        new java.util.concurrent.CopyOnWriteArrayList<>(),
                        () -> RunnerScriptTestFixtures.mockResponse(201, "{\"session\":\"resp-123\"}", "application/json")),
                new RequestBuilder(null),
                new ScriptEngine(null, ScriptMode.FULL_JS),
                null);
        CollectionRunner runner = new CollectionRunner(null, runnerPipeline, null);
        runner.setDelayMs(0);
        runner.setMaxRetries(0);
        runner.setRuntimeOverlayProvider(collection -> runnerEnvironment.toRuntimeOverlay());
        runner.setActiveEnvironmentProvider(collection -> runnerEnvironment);
        runner.setRuntimeVariableSink((collection, changedVars, removedKeys) ->
                applyEnvironmentDelta(runnerEnvironment, changedVars, removedKeys));
        runner.runCollections(List.of(runnerCollection), List.of(runnerRequest));
        waitForRunnerToStop(runner);

        List<RunnerResult> results = runner.getResults();
        assertThat(results).hasSize(1);

        PassiveScenarioSnapshot snapshot = new PassiveScenarioSnapshot();
        snapshot.workbenchRawRequestBytes = execution.rawRequestBytes != null ? execution.rawRequestBytes.clone() : null;
        snapshot.workbenchResponseStatus = execution.response != null && execution.response.response() != null
                ? execution.response.response().statusCode()
                : -1;
        snapshot.workbenchResponseBody = execution.response != null && execution.response.response() != null
                ? execution.response.response().bodyToString()
                : null;
        snapshot.workbenchEnvironmentVariables = new LinkedHashMap<>(workbenchEnvironment.variables);
        snapshot.workbenchMutations = copyMutations(execution.scriptVariableMutations);
        snapshot.historyEntry = historyEntry;
        snapshot.runnerResult = results.get(0);
        snapshot.runnerEnvironmentVariables = new LinkedHashMap<>(runnerEnvironment.variables);
        snapshot.importedEnvironment = importedEnvironment.copy();
        snapshot.exportedEnvironmentJson = exportedEnvironmentJson;
        snapshot.oauth2Bindings = new LinkedHashMap<>(importedEnvironment.oauth2.outputBindings);
        List<DiagnosticEvent> diagnosticsSnapshot = store.snapshot();
        snapshot.diagnosticEventCount = diagnosticsSnapshot.size();
        snapshot.diagnosticOperations = diagnosticsSnapshot.stream()
                .filter(java.util.Objects::nonNull)
                .map(event -> event.operation)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        snapshot.sanitizedDiagnosticReport = store.sanitizedReport(true);
        return snapshot;
    }

    private static void applyEnvironmentDelta(EnvironmentProfile environment,
                                              Map<String, String> changedVars,
                                              Set<String> removedKeys) {
        if (environment == null) {
            return;
        }
        if (removedKeys != null) {
            for (String key : removedKeys) {
                environment.variables.remove(key);
            }
        }
        if (changedVars != null) {
            environment.variables.putAll(changedVars);
        }
    }

    private static ApiCollection newCollection(String name) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        collection.format = "api-workbench";
        collection.environment.put("base_url", "https://collection.example.test");
        return collection;
    }

    private static ApiRequest scriptedRequest(String collectionName) {
        ApiRequest request = HistoryTestFixtures.sampleRequest();
        request.sourceCollection = collectionName;
        request.headers = new ArrayList<>(request.headers);
        request.scriptBlocks = new ArrayList<>();
        request.scriptBlocks.add(scriptBlock(
                "pre-1",
                ScriptPhase.PRE_REQUEST,
                """
                        pm.environment.set('trace_id', 'trace-001');
                        pm.request.headers.upsert('X-Trace', pm.environment.get('trace_id'));
                        """));
        request.scriptBlocks.add(scriptBlock(
                "post-1",
                ScriptPhase.POST_RESPONSE,
                """
                        pm.environment.set('session', pm.response.json().get('session'));
                        pm.environment.set('last_status', String(pm.response.code));
                        console.log('diagnostic-passive');
                        """));
        return request;
    }

    private static ScriptBlock scriptBlock(String id, ScriptPhase phase, String source) {
        ScriptBlock block = new ScriptBlock();
        block.id = id;
        block.phase = phase;
        block.scope = ScriptScope.REQUEST;
        block.dialect = ScriptDialect.POSTMAN;
        block.source = source;
        block.enabled = true;
        return block;
    }

    private static List<ScriptVariableMutation> copyMutations(List<ScriptVariableMutation> source) {
        List<ScriptVariableMutation> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (ScriptVariableMutation mutation : source) {
            if (mutation == null) {
                continue;
            }
            ScriptVariableMutation item = new ScriptVariableMutation();
            item.key = mutation.key;
            item.oldValue = mutation.oldValue;
            item.newValue = mutation.newValue;
            item.scope = mutation.scope;
            item.persistent = mutation.persistent;
            item.sourceScriptId = mutation.sourceScriptId;
            item.sourceScriptName = mutation.sourceScriptName;
            copy.add(item);
        }
        return copy;
    }

    private static void waitForRunnerToStop(CollectionRunner runner) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (runner != null && runner.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(runner).isNotNull();
        assertThat(runner.isRunning()).isFalse();
    }

    private static void assertEnvironmentEquivalent(EnvironmentProfile left, EnvironmentProfile right) {
        assertThat(left.name).isEqualTo(right.name);
        assertThat(left.sourceFormat).isEqualTo(right.sourceFormat);
        assertThat(left.sourceFileName).isEqualTo(right.sourceFileName);
        assertThat(left.variables).isEqualTo(right.variables);
        assertThat(left.oauth2.config).isEqualTo(right.oauth2.config);
        assertThat(left.oauth2.outputBindings).isEqualTo(right.oauth2.outputBindings);
    }

    private static void assertRunnerEquivalent(RunnerResult left, RunnerResult right) {
        assertThat(left.requestId).isEqualTo(right.requestId);
        assertThat(left.requestName).isEqualTo(right.requestName);
        assertThat(left.collectionName).isEqualTo(right.collectionName);
        assertThat(left.method).isEqualTo(right.method);
        assertThat(left.requestUrl).isEqualTo(right.requestUrl);
        assertThat(left.requestHeaders).isEqualTo(right.requestHeaders);
        assertThat(left.requestBody).isEqualTo(right.requestBody);
        assertThat(left.rawRequestBytes).containsExactly(right.rawRequestBytes);
        assertThat(left.rawRequestText).isEqualTo(right.rawRequestText);
        assertThat(left.success).isEqualTo(right.success);
        assertThat(left.statusCode).isEqualTo(right.statusCode);
        assertThat(left.responseHeaders).isEqualTo(right.responseHeaders);
        assertThat(left.responseBody).isEqualTo(right.responseBody);
        assertThat(left.errorMessage).isEqualTo(right.errorMessage);
        assertThat(left.extractedVariables).isEqualTo(right.extractedVariables);
        assertThat(left.resolvedVariables).isEqualTo(right.resolvedVariables);
        assertThat(left.scriptWarnings).isEqualTo(right.scriptWarnings);
        assertThat(left.scriptErrors).isEqualTo(right.scriptErrors);
        assertThat(left.scriptLogs).usingRecursiveFieldByFieldElementComparator().containsExactlyElementsOf(right.scriptLogs);
        assertVariableMutationsEquivalent(left.scriptVariableMutations, right.scriptVariableMutations);
    }

    private static void assertHistoryEquivalent(HistoryEntry left, HistoryEntry right) {
        assertThat(left.source).isEqualTo(right.source);
        assertThat(left.attemptNumber).isEqualTo(right.attemptNumber);
        assertThat(left.totalAttempts).isEqualTo(right.totalAttempts);
        assertThat(left.collectionName).isEqualTo(right.collectionName);
        assertThat(left.requestName).isEqualTo(right.requestName);
        assertThat(left.environmentName).isEqualTo(right.environmentName);
        assertThat(left.requestSnapshot.preferredRawRequestText()).isEqualTo(right.requestSnapshot.preferredRawRequestText());
        assertThat(left.requestSnapshot.rawRequestSent).containsExactly(right.requestSnapshot.rawRequestSent);
        assertThat(left.requestSnapshot.resolvedUrl).isEqualTo(right.requestSnapshot.resolvedUrl);
        assertThat(left.requestSnapshot.resolvedVariables).isEqualTo(right.requestSnapshot.resolvedVariables);
        assertThat(left.responseSnapshot.statusCode).isEqualTo(right.responseSnapshot.statusCode);
        assertThat(left.responseSnapshot.bodyAsText()).isEqualTo(right.responseSnapshot.bodyAsText());
        assertThat(left.responseSnapshot.displayHeaderBlock()).isEqualTo(right.responseSnapshot.displayHeaderBlock());
        assertThat(left.statusCode).isEqualTo(right.statusCode);
        assertThat(left.result).isEqualTo(right.result);
        assertThat(left.errorMessage).isEqualTo(right.errorMessage);
        assertThat(left.finalResolvedUrl).isEqualTo(right.finalResolvedUrl);
        assertThat(left.host).isEqualTo(right.host);
        assertThat(left.unresolvedVariables).isEqualTo(right.unresolvedVariables);
        assertThat(left.assertions).usingRecursiveFieldByFieldElementComparator().containsExactlyElementsOf(right.assertions);
        assertThat(left.extractions).usingRecursiveFieldByFieldElementComparator().containsExactlyElementsOf(right.extractions);
        assertThat(left.scriptWarnings).isEqualTo(right.scriptWarnings);
        assertThat(left.scriptErrors).isEqualTo(right.scriptErrors);
        assertVariableMutationsEquivalent(left.scriptVariableMutations, right.scriptVariableMutations);
    }

    private static void assertVariableMutationsEquivalent(List<ScriptVariableMutation> left,
                                                          List<ScriptVariableMutation> right) {
        assertThat(left).usingRecursiveFieldByFieldElementComparator().containsExactlyElementsOf(right);
    }

    private static final class PassiveScenarioSnapshot {
        private byte[] workbenchRawRequestBytes;
        private int workbenchResponseStatus;
        private String workbenchResponseBody;
        private Map<String, String> workbenchEnvironmentVariables;
        private List<ScriptVariableMutation> workbenchMutations;
        private HistoryEntry historyEntry;
        private RunnerResult runnerResult;
        private Map<String, String> runnerEnvironmentVariables;
        private EnvironmentProfile importedEnvironment;
        private String exportedEnvironmentJson;
        private Map<String, String> oauth2Bindings;
        private int diagnosticEventCount;
        private Set<DiagnosticOperation> diagnosticOperations;
        private String sanitizedDiagnosticReport;
    }
}
