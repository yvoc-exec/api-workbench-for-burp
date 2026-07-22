package burp.performance;

import burp.UniversalImporter;
import burp.history.HistoryEntry;
import burp.history.HistoryAdmissionResult;
import burp.history.HistoryJsonSupport;
import burp.history.HistoryRetentionPolicy;
import burp.history.HistoryRetentionStats;
import burp.history.HistoryStore;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerResult;
import burp.models.WorkspaceState;
import burp.runner.CollectionRunner;
import burp.scripts.ScriptExecutionResult;
import burp.scripts.UnifiedScriptRuntime;
import burp.utils.ScriptMode;
import burp.utils.ExecutionResult;
import burp.utils.SharedRequestPipeline;
import burp.utils.WorkspaceStateService;
import burp.utils.WorkspaceStateJson;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.sitemap.SiteMap;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.swing.JPanel;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** One isolated workload per process. Output contains measurements, never payloads. */
public final class MemoryHardeningScenarioMain {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MemoryHardeningScenarioMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: <scenario> <result-file>");
        }
        String scenario = args[0];
        Path resultFile = Path.of(args[1]);
        long started = System.nanoTime();
        Runtime runtime = Runtime.getRuntime();
        long before = usedHeap(runtime);
        int threadsBefore = apiWorkbenchThreadCount();
        long[] peak = {before};
        ScenarioResult result;
        ScenarioExecution execution = null;
        try {
            execution = run(scenario, peak);
            result = execution.result;
            result.exitClassification = "SUCCESS";
        } catch (OutOfMemoryError oom) {
            result = new ScenarioResult(scenario);
            applyExpectedWorkload(result);
            result.exitClassification = "OOM_REPORTED";
            result.oom = true;
            result.warnings.add("Child JVM reached its configured heap while measuring the baseline.");
        } catch (Throwable failure) {
            result = new ScenarioResult(scenario);
            applyExpectedWorkload(result);
            result.exitClassification = "NONZERO_REPORTED";
            result.warnings.add("Scenario failed with " + failure.getClass().getSimpleName() + ".");
        }
        result.configuredHeapBytes = runtime.maxMemory();
        result.heapUsedBefore = before;
        result.maximumSampledHeapBytes = Math.max(peak[0], usedHeap(runtime));
        result.heapAfterWorkload = usedHeap(runtime);
        if (execution != null) {
            settle();
            Reference.reachabilityFence(execution.retainedRoot);
            result.heapAfterRetainedSettle = usedHeap(runtime);
            result.apiWorkbenchThreadCountDuring = apiWorkbenchThreadCount();
            execution.closeOwners();
            result.apiWorkbenchThreadCountAfterClose = apiWorkbenchThreadCount();
            execution.releaseOwners();
        }
        settle();
        result.heapAfterReleaseSettle = usedHeap(runtime);
        result.elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        result.apiWorkbenchThreadCountBefore = threadsBefore;
        result.apiWorkbenchThreadCountAfter = apiWorkbenchThreadCount();
        result.apiWorkbenchThreadCount = result.apiWorkbenchThreadCountAfter;
        Files.createDirectories(resultFile.getParent());
        Files.writeString(resultFile, GSON.toJson(result), StandardCharsets.UTF_8);
    }

    private static ScenarioExecution run(String name, long[] peak) {
        return switch (name) {
            case "history-1000x64k" -> history(name, 1000, 64 * 1024, peak);
            case "history-100x2m" -> history(name, 100, 2 * 1024 * 1024, peak);
            case "runner-200x2m" -> runner(name, 200, 2 * 1024 * 1024, peak);
            case "exact-250x256k" -> exact(name, 250, 256 * 1024, peak);
            case "script-json-8m" -> scriptJson(name, 8 * 1024 * 1024, peak);
            case "workspace-history-80m" -> workspaceHistory(name, 80, 1024 * 1024, peak);
            case "workspace-ten-slow-saves" -> workspaceSaves(name, peak);
            case "runner-sitemap-traffic" -> runnerSiteMap(name, peak);
            case "workbench-snapshot-owners" -> workbenchOwners(name, peak);
            case "oauth2-status-growth" -> oauthStatus(name, peak);
            default -> throw new IllegalArgumentException("unknown scenario " + name);
        };
    }

    private static ScenarioExecution history(String name, int count, int responseBytes, long[] peak) {
        HistoryStore owners = new HistoryStore();
        owners.setRetentionPolicy(HistoryRetentionPolicy.defaultPolicy());
        int accepted = 0;
        int rejected = 0;
        long cumulativeEvictions = 0L;
        for (int i = 0; i < count; i++) {
            HistoryEntry entry = MemoryHardeningFixtureFactory.historyEntry(i, 256, responseBytes);
            HistoryAdmissionResult admission = owners.admitEntry(entry);
            if (admission.accepted()) {
                accepted++;
                cumulativeEvictions = MemoryHardeningFixtureFactory.safeAdd(
                        cumulativeEvictions, admission.entriesEvicted());
            } else {
                rejected++;
            }
            sample(peak);
        }
        HistoryRetentionStats stats = owners.getRetentionStats();
        HistoryRetentionPolicy policy = owners.getRetentionPolicy();
        ScenarioResult result = new ScenarioResult(name);
        result.operationCount = count;
        result.payloadBytes = responseBytes;
        result.logicalRetainedBytes = stats.canonicalRetainedBytes();
        result.retainedOwners = owners.size();
        result.metrics.put("historyEntryOwners", owners.size());
        result.metrics.put("attemptedAdds", count);
        result.metrics.put("acceptedAdds", accepted);
        result.metrics.put("rejectedAdds", rejected);
        result.metrics.put("cumulativeEvictions", cumulativeEvictions);
        result.metrics.put("retainedEntryCount", owners.size());
        result.metrics.put("canonicalRetainedBytes", stats.canonicalRetainedBytes());
        result.metrics.put("pinnedRetainedBytes", stats.pinnedRetainedBytes());
        result.metrics.put("unpinnedRetainedBytes", stats.unpinnedRetainedBytes());
        result.metrics.put("retentionLimitBytes", policy.maxTotalStoredBytes);
        result.metrics.put("retentionLimitEntries", policy.maxEntries);
        result.metrics.put("truncatedEntryCount", stats.truncatedEntryCount());
        result.metrics.put("overBudget", stats.overBudget() ? 1 : 0);
        return retain(result, owners);
    }

    private static ScenarioExecution runner(String name, int count, int responseBytes, long[] peak) {
        List<RunnerResult> owners = new ArrayList<>(count);
        long logical = 0;
        for (int i = 0; i < count; i++) {
            RunnerResult result = MemoryHardeningFixtureFactory.runnerResult(i, 256, responseBytes);
            owners.add(result);
            logical = MemoryHardeningFixtureFactory.safeAdd(logical,
                    result.rawRequestBytes.length + MemoryHardeningFixtureFactory.utf8Length(result.rawRequestText)
                            + MemoryHardeningFixtureFactory.utf8Length(result.responseBody));
            sample(peak);
        }
        ScenarioResult result = new ScenarioResult(name);
        result.operationCount = count;
        result.payloadBytes = responseBytes;
        result.logicalRetainedBytes = logical;
        result.retainedOwners = owners.size();
        result.metrics.put("runnerResultOwners", owners.size());
        result.metrics.put("fullResponseOwners", owners.size());
        return retain(result, owners);
    }

    private static ScenarioExecution exact(String name, int count, int rawBytes, long[] peak) {
        List<ApiRequest> owners = new ArrayList<>(count);
        long logical = 0;
        for (int i = 0; i < count; i++) {
            ApiRequest request = MemoryHardeningFixtureFactory.fidelityRequest("exact-" + i, 0);
            request.exactHttpRequest = MemoryHardeningFixtureFactory.exactSnapshot(rawBytes);
            owners.add(request);
            logical = MemoryHardeningFixtureFactory.safeAdd(logical, rawBytes);
            sample(peak);
        }
        ScenarioResult result = new ScenarioResult(name);
        result.operationCount = count;
        result.payloadBytes = rawBytes;
        result.logicalRetainedBytes = logical;
        result.retainedOwners = owners.size();
        result.metrics.put("exactSnapshotOwners", owners.size());
        return retain(result, owners);
    }

    private static ScenarioExecution scriptJson(String name, int bytes, long[] peak) {
        String body = "{\"payload\":\"" + "s".repeat(bytes - 20) + "\"}";
        ApiCollection collection = new ApiCollection();
        collection.name = "Script memory baseline";
        ApiRequest request = new ApiRequest();
        request.postResponseScripts.add(new ApiRequest.Script(
                "js", "pm.environment.set('captured', pm.response.json().get('payload'));"));
        collection.requests.add(request);
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.id = "memory-script-environment";
        environment.name = "Memory script environment";
        RunnerResult runner = new RunnerResult();
        runner.responseBody = body;
        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        ScriptExecutionResult scriptResult = runtime.executePostResponse(
                collection, request, environment, "Runner", 1, body, 200, Map.of(), 1L, runner);
        sample(peak);
        ScenarioResult result = new ScenarioResult(name);
        result.operationCount = 1;
        result.payloadBytes = MemoryHardeningFixtureFactory.utf8Length(body);
        result.logicalRetainedBytes = MemoryHardeningFixtureFactory.utf8Length(body)
                + MemoryHardeningFixtureFactory.utf8Length(runner.responseBody)
                + scriptResult.variableMutations.stream()
                        .map(mutation -> mutation.newValue)
                        .mapToLong(MemoryHardeningFixtureFactory::utf8Length)
                        .sum();
        result.retainedOwners = 3;
        result.metrics.put("responseStringOwners", 1);
        result.metrics.put("scriptExtractedValueOwners", scriptResult.variableMutations.size());
        result.metrics.put("unifiedRuntimeEnabled", runtime.isEnabled() ? 1 : 0);
        return retain(result, List.of(body, collection, request, environment, runner, scriptResult, runtime),
                runtime::close);
    }

    private static ScenarioExecution workspaceHistory(String name, int count, int bytes, long[] peak) {
        WorkspaceState workspace = MemoryHardeningFixtureFactory.workspace(count, bytes);
        long logical = workspace.historyEntries.stream().mapToLong(HistoryEntry::estimatedStoredBytes).sum();
        sample(peak);
        String json = WorkspaceStateJson.toJson(workspace);
        sample(peak);
        ScenarioResult result = new ScenarioResult(name);
        result.operationCount = count;
        result.payloadBytes = bytes;
        result.logicalRetainedBytes = logical;
        result.serializedWorkspaceBytes = MemoryHardeningFixtureFactory.utf8Length(json);
        result.retainedOwners = workspace.historyEntries.size() + 2;
        result.metrics.put("historyEntryOwners", workspace.historyEntries.size());
        result.metrics.put("previousFullJsonRetainedBytes", result.serializedWorkspaceBytes);
        return retain(result, List.of(workspace, json));
    }

    private static ScenarioExecution workspaceSaves(String name, long[] peak) {
        BlockingPersistedObject store = new BlockingPersistedObject();
        UniversalImporter importer = new UniversalImporter(
                mockImporterApi(), ScriptMode.DISABLED, new WorkspaceStateService(store.object));
        try {
            Future<?> baseline = submitWorkspaceSave(importer, MemoryHardeningFixtureFactory.workspace(0, 0));
            baseline.get(10, TimeUnit.SECONDS);
            store.resetMetrics();
            store.block.set(true);

            List<WorkspaceState> revisions = new ArrayList<>();
            List<Future<?>> futures = new ArrayList<>();
            long logicalBytes = 0;
            for (int revision = 0; revision < 10; revision++) {
                WorkspaceState state = MemoryHardeningFixtureFactory.workspace(2, 128 * 1024);
                state.selectedRequestName = "revision-" + revision;
                revisions.add(state);
                logicalBytes = MemoryHardeningFixtureFactory.safeAdd(
                        logicalBytes, MemoryHardeningFixtureFactory.utf8Length(WorkspaceStateJson.toJson(state)));
                futures.add(submitWorkspaceSave(importer, state));
                sample(peak);
            }
            if (!store.blockedWriteStarted.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("production workspace save did not reach blocked store");
            }

            byte[] comparisonBytes = MemoryHardeningFixtureFactory.binaryBytes(64 * 1024);
            long isolatedByteArrayJsonBytes = MemoryHardeningFixtureFactory.utf8Length(
                    HistoryJsonSupport.createGson().toJson(comparisonBytes));
            long base64Bytes = MemoryHardeningFixtureFactory.referenceBase64Length(comparisonBytes);
            ScenarioResult result = new ScenarioResult(name);
            result.operationCount = 10;
            result.logicalRetainedBytes = logicalBytes;
            result.retainedOwners = revisions.size();
            result.metrics.put("saveRequests", 10);
            result.metrics.put("queuedDetachedWorkspaceStates", queuedWorkspaceSaves(importer));
            result.metrics.put("activeDetachedWorkspaceStates", activeWorkspaceSaves(importer));
            result.metrics.put("pendingSnapshotCountObserved",
                    queuedWorkspaceSaves(importer) + activeWorkspaceSaves(importer));
            result.metrics.put("byteArrayCanonicalRawBytes", comparisonBytes.length);
            result.metrics.put("isolatedByteArrayJsonBytes", isolatedByteArrayJsonBytes);
            result.metrics.put("isolatedByteArrayJsonInflationRatio",
                    (double) isolatedByteArrayJsonBytes / comparisonBytes.length);
            result.metrics.put("referenceBase64Bytes", base64Bytes);
            result.metrics.put("referenceBase64Ratio", (double) base64Bytes / comparisonBytes.length);
            String retainedJson = lastSavedWorkspaceJson(importer);
            result.metrics.put("actualLastSavedWorkspaceJsonBytesDuringQueue",
                    MemoryHardeningFixtureFactory.utf8Length(retainedJson));

            Runnable close = () -> {
                store.release.countDown();
                try {
                    for (Future<?> future : futures) {
                        future.get(10, TimeUnit.SECONDS);
                    }
                } catch (Exception ex) {
                    throw new IllegalStateException("production workspace saves did not finish", ex);
                }
                String actualLastSaved = lastSavedWorkspaceJson(importer);
                result.serializedWorkspaceBytes = MemoryHardeningFixtureFactory.utf8Length(actualLastSaved);
                result.metrics.put("actualStoreWrites", store.writeCount);
                result.metrics.put("cumulativeExtensionDataBytesSubmitted", store.cumulativeBytes);
                result.metrics.put("currentExtensionDataValueBytes", store.currentBytes);
                result.metrics.put("maximumSingleValueBytes", store.maximumBytes);
                result.metrics.put("identicalWriteCount", store.identicalWrites);
                result.metrics.put("actualLastSavedWorkspaceJsonBytes", result.serializedWorkspaceBytes);
                result.metrics.put("queuedDetachedWorkspaceStatesAfterDrain", queuedWorkspaceSaves(importer));
                importer.cleanup();
            };
            return retain(result, List.of(importer, store, revisions, futures), close);
        } catch (Exception ex) {
            store.release.countDown();
            importer.cleanup();
            throw new IllegalStateException("production workspace queue measurement failed", ex);
        }
    }

    private static ScenarioExecution runnerSiteMap(String name, long[] peak) {
        int attempts = 100;
        int bytes = 64 * 1024;
        SiteMapRun defaultOff = runSiteMapGroup(attempts, bytes, false);
        SiteMapRun optIn = runSiteMapGroup(attempts, bytes, true);
        sample(peak);
        ScenarioResult result = new ScenarioResult(name);
        result.operationCount = attempts * 2;
        result.payloadBytes = bytes;
        result.logicalRetainedBytes = defaultOff.requestBytes + defaultOff.responseBytes
                + optIn.requestBytes + optIn.responseBytes;
        result.retainedOwners = defaultOff.retained.size() + optIn.retained.size();
        result.metrics.put("defaultOffRunnerAttempts", defaultOff.attempts);
        result.metrics.put("defaultOffSuccessfulAttempts", defaultOff.successfulAttempts);
        result.metrics.put("defaultOffSiteMapAddCalls", defaultOff.siteMapAdds);
        result.metrics.put("optInRunnerAttempts", optIn.attempts);
        result.metrics.put("optInSuccessfulAttempts", optIn.successfulAttempts);
        result.metrics.put("optInSiteMapAddCalls", optIn.siteMapAdds);
        result.metrics.put("optInApproximateRequestBytes", optIn.requestBytes);
        result.metrics.put("optInApproximateResponseBytes", optIn.responseBytes);
        result.metrics.put("runnerResultCount", result.retainedOwners);
        return retain(result, List.of(defaultOff.runner, defaultOff.retained, optIn.runner, optIn.retained));
    }

    private static SiteMapRun runSiteMapGroup(int attempts, int bytes, boolean enabled) {
        MontoyaApi api = mock(MontoyaApi.class);
        SiteMap siteMap = mock(SiteMap.class);
        when(api.siteMap()).thenReturn(siteMap);
        AtomicInteger siteMapAdds = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            siteMapAdds.incrementAndGet();
            return null;
        }).when(siteMap).add(nullable(HttpRequestResponse.class));
        AtomicInteger sends = new AtomicInteger();
        byte[] requestEvidence = MemoryHardeningFixtureFactory.rawHttpRequest(256);
        SharedRequestPipeline pipeline = new SharedRequestPipeline(null, null, null, null) {
            @Override
            public ExecutionResult execute(ApiRequest request, burp.models.ApiCollection collection,
                                           boolean followRedirects) {
                sends.incrementAndGet();
                ExecutionResult execution = new ExecutionResult();
                execution.success = true;
                execution.requestSent = true;
                execution.rawRequestBytes = requestEvidence;
                execution.rawRequestText = new String(requestEvidence, StandardCharsets.ISO_8859_1);
                execution.response = response(bytes);
                return execution;
            }
        };
        CollectionRunner runner = new CollectionRunner(api, pipeline, null);
        runner.setAddResponsesToSiteMap(enabled);
        useDirectExecutor(runner);
        burp.models.ApiCollection collection = new burp.models.ApiCollection();
        collection.name = "Memory baseline";
        for (int i = 0; i < attempts; i++) {
            ApiRequest request = new ApiRequest();
            request.id = "sitemap-" + i;
            request.name = "Site map baseline " + i;
            request.method = "GET";
            request.url = "https://example.test/sitemap/" + i;
            request.sourceCollection = collection.name;
            collection.requests.add(request);
        }
        try (org.mockito.MockedStatic<Annotations> annotations = mockStatic(Annotations.class)) {
            annotations.when(() -> Annotations.annotations(any(String.class), any(HighlightColor.class)))
                    .thenReturn(mock(Annotations.class));
            runner.runCollections(List.of(collection), collection.requests);
        }
        List<RunnerResult> retained = runner.getResults();
        long requestBytes = (long) sends.get() * requestEvidence.length;
        long responseBytes = (long) sends.get() * bytes;
        return new SiteMapRun(runner, retained, sends.get(),
                retained.stream().filter(value -> value.success).count(), siteMapAdds.get(),
                requestBytes, responseBytes);
    }

    private record SiteMapRun(CollectionRunner runner, List<RunnerResult> retained,
                              int attempts, long successfulAttempts, int siteMapAdds,
                              long requestBytes, long responseBytes) { }

    private static HttpRequestResponse response(int bytes) {
        ByteArray body = mock(ByteArray.class);
        when(body.length()).thenReturn(bytes);
        when(body.getBytes()).thenReturn(new byte[bytes]);
        HttpResponse response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn((short) 200);
        when(response.body()).thenReturn(body);
        when(response.bodyToString()).thenReturn("r".repeat(bytes));
        HttpRequestResponse wrapper = mock(HttpRequestResponse.class);
        when(wrapper.response()).thenReturn(response);
        when(wrapper.withAnnotations(nullable(Annotations.class))).thenReturn(wrapper);
        return wrapper;
    }

    private static void useDirectExecutor(CollectionRunner runner) {
        try {
            java.lang.reflect.Method method = CollectionRunner.class.getDeclaredMethod(
                    "setExecutorServiceFactory", Supplier.class);
            method.setAccessible(true);
            Supplier<ExecutorService> factory = MemoryHardeningScenarioMain::directExecutor;
            method.invoke(runner, factory);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("runner executor test seam unavailable", ex);
        }
    }

    private static Future<?> submitWorkspaceSave(UniversalImporter importer, WorkspaceState state) {
        try {
            java.lang.reflect.Method method = UniversalImporter.class.getDeclaredMethod(
                    "submitWorkspaceStateSaveForTests", WorkspaceState.class);
            method.setAccessible(true);
            return (Future<?>) method.invoke(importer, state);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("workspace submit test seam unavailable", ex);
        }
    }

    private static int queuedWorkspaceSaves(UniversalImporter importer) {
        return workspaceSaveMetric(importer, "queuedWorkspaceStateSaveCountForTests");
    }

    private static int activeWorkspaceSaves(UniversalImporter importer) {
        return workspaceSaveMetric(importer, "activeWorkspaceStateSaveCountForTests");
    }

    private static int workspaceSaveMetric(UniversalImporter importer, String methodName) {
        try {
            java.lang.reflect.Method method = UniversalImporter.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            return (int) method.invoke(importer);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("workspace queue test seam unavailable", ex);
        }
    }

    private static String lastSavedWorkspaceJson(UniversalImporter importer) {
        try {
            java.lang.reflect.Method method = UniversalImporter.class.getDeclaredMethod(
                    "lastSavedWorkspaceJsonForTests");
            method.setAccessible(true);
            return (String) method.invoke(importer);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("workspace previous-json test seam unavailable", ex);
        }
    }

    private static MontoyaApi mockImporterApi() {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        HttpRequestEditor requestEditor = mock(HttpRequestEditor.class);
        when(requestEditor.uiComponent()).thenReturn(new JPanel());
        when(api.userInterface().createHttpRequestEditor(any(EditorOptions.class))).thenReturn(requestEditor);
        HttpResponseEditor responseEditor = mock(HttpResponseEditor.class);
        when(responseEditor.uiComponent()).thenReturn(new JPanel());
        when(api.userInterface().createHttpResponseEditor(any(EditorOptions.class))).thenReturn(responseEditor);
        return api;
    }

    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            private volatile boolean shutdown;
            public void shutdown() { shutdown = true; }
            public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
            public boolean isShutdown() { return shutdown; }
            public boolean isTerminated() { return shutdown; }
            public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
            public void execute(Runnable command) { command.run(); }
        };
    }

    private static ScenarioExecution workbenchOwners(String name, long[] peak) {
        IdentityHashMap<ApiRequest, byte[]> owners = new IdentityHashMap<>();
        for (int i = 0; i < 250; i++) {
            owners.put(MemoryHardeningFixtureFactory.fidelityRequest("workbench-" + i, 256),
                    MemoryHardeningFixtureFactory.rawHttpRequest(32 * 1024));
            sample(peak);
        }
        ScenarioResult result = new ScenarioResult(name);
        result.operationCount = owners.size();
        result.payloadBytes = 32 * 1024;
        result.logicalRetainedBytes = (long) owners.size() * 32 * 1024;
        result.retainedOwners = owners.size();
        result.metrics.put("workbenchSnapshotOwners", owners.size());
        result.warnings.add("Workbench owner count is a deterministic identity-map proxy; no live Swing send was performed.");
        return retain(result, owners);
    }

    private static ScenarioExecution oauthStatus(String name, long[] peak) {
        StringBuilder swingDocument = new StringBuilder();
        String tokenSummary = "token acquired; value redacted";
        for (int i = 0; i < 10_000; i++) {
            swingDocument.append("OAuth2 status update ").append(i).append(": ").append(tokenSummary).append('\n');
            sample(peak);
        }
        ScenarioResult result = new ScenarioResult(name);
        result.operationCount = 10_000;
        result.logicalRetainedBytes = MemoryHardeningFixtureFactory.utf8Length(swingDocument.toString())
                + MemoryHardeningFixtureFactory.utf8Length(tokenSummary);
        result.retainedOwners = 2;
        result.metrics.put("swingDocumentLength", swingDocument.length());
        result.metrics.put("retainedTokenSummaryTextLength", tokenSummary.length());
        result.warnings.add("OAuth2 status measurement is a deterministic text-growth proxy; no live OAuth2Panel was used.");
        return retain(result, List.of(swingDocument, tokenSummary));
    }

    private static void sample(long[] peak) {
        peak[0] = Math.max(peak[0], usedHeap(Runtime.getRuntime()));
    }

    private static long usedHeap(Runtime runtime) {
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void settle() {
        for (int i = 0; i < 2; i++) {
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static int apiWorkbenchThreadCount() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("api-workbench") || name.contains("workspace-save") || name.contains("runner")) {
                count++;
            }
        }
        return count;
    }

    private static void applyExpectedWorkload(ScenarioResult result) {
        switch (result.scenarioName) {
            case "history-1000x64k" -> { result.operationCount = 1000; result.payloadBytes = 64 * 1024; }
            case "history-100x2m" -> { result.operationCount = 100; result.payloadBytes = 2L * 1024 * 1024; }
            case "runner-200x2m" -> { result.operationCount = 200; result.payloadBytes = 2L * 1024 * 1024; }
            case "exact-250x256k" -> { result.operationCount = 250; result.payloadBytes = 256 * 1024; }
            case "script-json-8m" -> { result.operationCount = 1; result.payloadBytes = 8L * 1024 * 1024; }
            case "workspace-history-80m" -> { result.operationCount = 80; result.payloadBytes = 1024 * 1024; }
            case "workspace-ten-slow-saves" -> result.operationCount = 10;
            case "runner-sitemap-traffic" -> { result.operationCount = 100; result.payloadBytes = 64 * 1024; }
            case "workbench-snapshot-owners" -> { result.operationCount = 250; result.payloadBytes = 32 * 1024; }
            case "oauth2-status-growth" -> result.operationCount = 10_000;
            default -> { }
        }
    }

    private static final class BlockingPersistedObject {
        final PersistedObject object = mock(PersistedObject.class);
        final AtomicBoolean block = new AtomicBoolean();
        final CountDownLatch blockedWriteStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<String> current = new AtomicReference<>();
        int writeCount;
        long cumulativeBytes;
        long currentBytes;
        long maximumBytes;
        int identicalWrites;

        BlockingPersistedObject() {
            when(object.getString(any(String.class))).thenAnswer(invocation -> current.get());
            org.mockito.Mockito.doAnswer(invocation -> {
                if (block.get()) {
                    blockedWriteStarted.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("blocked workspace persistence timed out");
                    }
                }
                record(invocation.getArgument(1, String.class));
                return null;
            }).when(object).setString(any(String.class), any(String.class));
        }

        synchronized void resetMetrics() {
            writeCount = 0;
            cumulativeBytes = 0;
            currentBytes = 0;
            maximumBytes = 0;
            identicalWrites = 0;
        }

        private synchronized void record(String value) {
            String safe = value != null ? value : "";
            if (safe.equals(current.get())) {
                identicalWrites++;
            }
            long bytes = MemoryHardeningFixtureFactory.utf8Length(safe);
            writeCount++;
            cumulativeBytes += bytes;
            currentBytes = bytes;
            maximumBytes = Math.max(maximumBytes, bytes);
            current.set(safe);
        }
    }

    private static ScenarioExecution retain(ScenarioResult result, Object owners) {
        return retain(result, owners, () -> { });
    }

    private static ScenarioExecution retain(ScenarioResult result, Object owners, Runnable closeAction) {
        return new ScenarioExecution(result, owners, closeAction);
    }

    private static final class ScenarioExecution {
        final ScenarioResult result;
        Object retainedRoot;
        final Runnable closeAction;

        ScenarioExecution(ScenarioResult result, Object retainedRoot, Runnable closeAction) {
            this.result = result;
            this.retainedRoot = retainedRoot;
            this.closeAction = closeAction;
        }

        void closeOwners() {
            closeAction.run();
        }

        void releaseOwners() {
            retainedRoot = null;
        }
    }

    static final class ScenarioResult {
        final String scenarioName;
        String exitClassification;
        long elapsedMillis;
        long configuredHeapBytes;
        long heapUsedBefore;
        long maximumSampledHeapBytes;
        long heapAfterWorkload;
        long heapAfterRetainedSettle;
        long heapAfterReleaseSettle;
        long payloadBytes;
        int operationCount;
        long logicalRetainedBytes;
        long serializedWorkspaceBytes;
        int retainedOwners;
        int apiWorkbenchThreadCount;
        int apiWorkbenchThreadCountBefore;
        int apiWorkbenchThreadCountDuring;
        int apiWorkbenchThreadCountAfterClose;
        int apiWorkbenchThreadCountAfter;
        boolean oom;
        boolean timedOut;
        final List<String> warnings = new ArrayList<>();
        final Map<String, Number> metrics = new LinkedHashMap<>();
        final String heapMetricType = "JVM heap sample";
        final String logicalBytesMetricType = "estimated logical bytes";
        final String retainedOwnersMetricType = "deterministic structural count";
        final String serializedBytesMetricType = "observed measurement";

        ScenarioResult(String scenarioName) {
            this.scenarioName = scenarioName;
        }
    }
}
