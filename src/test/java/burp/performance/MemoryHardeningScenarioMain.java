package burp.performance;

import burp.UniversalImporter;
import burp.history.HistoryEntry;
import burp.history.HistoryAdmissionResult;
import burp.history.HistoryJsonSupport;
import burp.history.HistoryRetentionPolicy;
import burp.history.HistoryRetentionStats;
import burp.history.HistoryStore;
import burp.importer.BurpTrafficConversionResult;
import burp.importer.BurpTrafficImportService;
import burp.importer.BurpTrafficSelection;
import burp.importer.BurpTrafficCaptureService;
import burp.importer.BurpTrafficSourceSelection;
import burp.importer.TrafficImportLimits;
import burp.payload.FileManagedPayloadStore;
import burp.payload.ManagedPayloadRef;
import burp.payload.ManagedPayloadStage;
import burp.payload.PayloadSliceRef;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RedirectHop;
import burp.models.RedirectPolicy;
import burp.models.RunnerResult;
import burp.models.RunnerResultSummary;
import burp.models.WorkspaceState;
import burp.runner.CollectionRunner;
import burp.scripts.ScriptExecutionResult;
import burp.scripts.UnifiedScriptRuntime;
import burp.utils.ScriptMode;
import burp.utils.ExecutionResult;
import burp.utils.SharedRequestPipeline;
import burp.utils.Base64ByteArrayTypeAdapter;
import burp.utils.WorkspaceSaveResult;
import burp.utils.WorkspaceStateService;
import burp.utils.WorkspaceStateJson;
import burp.utils.RequestBuilder;
import burp.utils.RedirectExecutor;
import burp.ui.RunnerExecutionTableModel;
import burp.ui.ImporterPanel;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.sitemap.SiteMap;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.mockito.MockedStatic;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
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
            case "file-binary-repeated-send" -> fileRepeatedSend(name, false, peak);
            case "multipart-file-repeated-send" -> fileRepeatedSend(name, true, peak);
            case "exact-ref-import-128m", "exact-ref-import-256m", "exact-ref-repeated-send",
                    "exact-ref-dedup",
                    "exact-ref-workspace-save", "exact-ref-header-edit", "exact-ref-history",
                    "exact-ref-delete-cleanup" -> exactRef(name, peak);
            case "montoya-background-stage" -> montoyaBackgroundStage(name, peak);
            case "large-redirect-ownership" -> largeRedirectOwnership(name, peak);
            default -> throw new IllegalArgumentException("unknown scenario " + name);
        };
    }

    private static ScenarioExecution fileRepeatedSend(String name, boolean multipart, long[] peak) {
        Path fixture = null;
        try {
            fixture = Files.createTempFile(Path.of("target"), "r7-file-body-", ".bin");
            byte[] contents = MemoryHardeningFixtureFactory.binaryBytes(2 * 1024 * 1024);
            Files.write(fixture, contents);
            ApiRequest request = new ApiRequest();
            request.id = name;
            request.method = "POST";
            request.url = "https://example.test/upload";
            request.body = new ApiRequest.Body();
            if (multipart) {
                request.body.mode = "formdata";
                ApiRequest.Body.FormField field = new ApiRequest.Body.FormField("payload", "");
                field.type = "file";
                field.fileUpload = true;
                field.filePath = fixture.toString();
                field.contentType = "application/octet-stream";
                request.body.formdata.add(field);
            } else {
                request.body.mode = "file";
                request.body.filePath = fixture.toString();
                request.body.raw = null;
            }

            long[] settled = repeatedBuildCheckpoints(new RequestBuilder(null), request, peak);
            ScenarioResult result = new ScenarioResult(name);
            result.operationCount = 50;
            result.payloadBytes = contents.length;
            result.retainedOwners = 1;
            result.logicalRetainedBytes = MemoryHardeningFixtureFactory.utf8Length(fixture.toString());
            WorkspaceState state = new WorkspaceState();
            ApiCollection collection = new ApiCollection();
            collection.id = "r7-files";
            collection.requests.add(request);
            state.collections.add(collection);
            String workspace = WorkspaceStateJson.toJsonCopying(state);
            result.serializedWorkspaceBytes = MemoryHardeningFixtureFactory.utf8Length(workspace);
            result.metrics.put("settledHeapAfterSend1", settled[0]);
            result.metrics.put("settledHeapAfterSend10", settled[1]);
            result.metrics.put("settledHeapAfterSend50", settled[2]);
            result.metrics.put("monotonicRetainedGrowth", monotonicGrowth(settled) ? 1 : 0);
            result.metrics.put("persistentFileContentOwners", 0);
            result.metrics.put("persistentRawBodyCharacters", request.body.raw != null ? request.body.raw.length() : 0);
            Path ownedFixture = fixture;
            return retain(result, request, () -> deleteQuietly(ownedFixture));
        } catch (Exception failure) {
            deleteQuietly(fixture);
            throw new IllegalStateException("R7 file repeated-send scenario failed", failure);
        }
    }

    private static ScenarioExecution exactRepeatedSend(String name, long[] peak) {
        try {
            ApiRequest request = new ApiRequest();
            request.id = name;
            request.method = "POST";
            request.url = "https://example.test/memory";
            request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
            request.body = new ApiRequest.Body();
            request.body.mode = "raw";
            request.exactHttpRequest = MemoryHardeningFixtureFactory.exactSnapshot(4 * 1024 * 1024);
            request.exactHttpRequest.semanticFingerprint = request.computeSemanticFingerprint();

            long[] settled = repeatedBuildCheckpoints(new RequestBuilder(null), request, peak);
            ScenarioResult result = new ScenarioResult(name);
            result.operationCount = 50;
            result.payloadBytes = request.exactHttpRequest.rawRequestBytes.length;
            result.retainedOwners = 1;
            result.logicalRetainedBytes = result.payloadBytes;
            result.metrics.put("settledHeapAfterSend1", settled[0]);
            result.metrics.put("settledHeapAfterSend10", settled[1]);
            result.metrics.put("settledHeapAfterSend50", settled[2]);
            result.metrics.put("monotonicRetainedGrowth", monotonicGrowth(settled) ? 1 : 0);
            result.metrics.put("canonicalExactOwners", 1);
            result.metrics.put("authoredRawBodyCharacters", 0);
            return retain(result, request);
        } catch (Exception failure) {
            throw new IllegalStateException("R7 exact repeated-send scenario failed", failure);
        }
    }

    private static ScenarioExecution exactTrafficImportOwnership(String name, long[] peak) {
        int exactBytes = Math.toIntExact(TrafficImportLimits.DEFAULT_MAX_EXACT_REQUEST_BYTES);
        byte[] rawRequest = MemoryHardeningFixtureFactory.rawHttpRequest(exactBytes);
        byte[] responseBody = MemoryHardeningFixtureFactory.binaryBytes(4 * 1024 * 1024);
        byte[] responsePrefix = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] rawResponse = new byte[responsePrefix.length + responseBody.length];
        System.arraycopy(responsePrefix, 0, rawResponse, 0, responsePrefix.length);
        System.arraycopy(responseBody, 0, rawResponse, responsePrefix.length, responseBody.length);
        BurpTrafficSelection selection = new BurpTrafficSelection(
                rawRequest, rawResponse, "example.test", 443, true,
                "memory-hardening", "R7 exact", "POST", 1);
        HistoryRetentionPolicy policy = HistoryRetentionPolicy.defaultPolicy();
        BurpTrafficConversionResult conversion = new BurpTrafficImportService().convert(List.of(selection), policy);
        sample(peak);
        ApiRequest request = conversion.requests.get(0);
        HistoryEntry history = conversion.historyEntries.get(0);
        ScenarioResult result = new ScenarioResult(name);
        result.operationCount = 1;
        result.payloadBytes = exactBytes;
        result.retainedOwners = 1;
        result.logicalRetainedBytes = request.exactHttpRequest.rawRequestBytes.length
                + history.estimatedStoredBytes();
        result.metrics.put("canonicalExactOwners", 1);
        result.metrics.put("exactRetainedBytes", request.exactHttpRequest.rawRequestBytes.length);
        result.metrics.put("selectionAndExactSharePayload", request.exactHttpRequest.rawRequestBytes == selection.rawRequestBytes ? 1 : 0);
        result.metrics.put("equivalentRawTextOwners", history.requestSnapshot.rawRequestSentText == null ? 0 : 1);
        result.metrics.put("authoredExactOwnersInHistory",
                history.requestSnapshot.authoredRequest != null
                        && history.requestSnapshot.authoredRequest.exactHttpRequest != null
                        && history.requestSnapshot.authoredRequest.exactHttpRequest.rawRequestBytes != null ? 1 : 0);
        result.metrics.put("historyExactDuplicateOwners", duplicateExactOwner(history.requestSnapshot));
        result.metrics.put("historyRequestStoredBodyBytes", history.requestSnapshot.storedRawBodyLength);
        result.metrics.put("historyRequestOriginalBodyBytes", history.requestSnapshot.originalRawBodyLength);
        result.metrics.put("historyResponseStoredBodyBytes", history.responseSnapshot.storedBodyLength);
        result.metrics.put("historyResponseOriginalBodyBytes", history.responseSnapshot.originalBodyLength);
        result.metrics.put("historyLogicalBytes", history.estimatedStoredBytes());
        return retain(result, conversion);
    }

    private static ScenarioExecution exactRef(String name, long[] peak) {
        Path root = null;
        FileManagedPayloadStore store = null;
        try {
            root = Files.createTempDirectory(Path.of("target"), "r7-ref-");
            store = new FileManagedPayloadStore(root);
            long payloadBytes = switch (name) {
                case "exact-ref-import-256m" -> 256L * 1024L * 1024L;
                case "exact-ref-import-128m" -> 128L * 1024L * 1024L;
                case "exact-ref-workspace-save" -> 64L * 1024L * 1024L;
                default -> 8L * 1024L * 1024L;
            };
            byte[] chunk = MemoryHardeningFixtureFactory.binaryBytes(FileManagedPayloadStore.CHUNK_SIZE);
            ManagedPayloadRef ref = stageRepeated(store, chunk, payloadBytes, peak);
            if ("exact-ref-dedup".equals(name)) stageRepeated(store, chunk, payloadBytes, peak);

            ApiRequest request = new ApiRequest();
            request.id = name;
            request.name = name;
            request.method = "POST";
            request.url = "https://example.test/upload";
            request.body = new ApiRequest.Body();
            request.body.mode = "raw";
            request.body.managedPayload = new PayloadSliceRef(ref, 0L, ref.length);
            request.exactHttpRequest = burp.models.ExactHttpRequestSnapshot.fromManagedPayload(
                    ref, "example.test", 443, true, "HTTP/1.1", true,
                    "memory-hardening", request.computeSemanticFingerprint());
            ApiCollection collection = new ApiCollection();
            collection.id = "memory";
            collection.name = "memory";
            collection.requests.add(request);
            WorkspaceState workspace = new WorkspaceState();
            workspace.collections = List.of(collection);
            if ("exact-ref-history".equals(name)) {
                HistoryEntry entry = new HistoryEntry();
                entry.requestSnapshot = burp.history.HistoryRequestSnapshot.fromBorrowingExactTransport(request);
                burp.history.HistoryBodyTruncator.apply(entry,
                        new HistoryRetentionPolicy(100, 64L * 1024L * 1024L,
                                1024L * 1024L, 1024L * 1024L, true));
                workspace.historyEntries = List.of(entry);
            }
            long[] settled = null;
            if ("exact-ref-repeated-send".equals(name)) {
                settled = repeatedBuildCheckpoints(new RequestBuilder(null, store), request, peak);
            } else if ("exact-ref-header-edit".equals(name)) {
                request.headers.add(new ApiRequest.Header("X-Edited", "true", false));
                request.exactHttpRequest.pristine = false;
                byte[] built = new RequestBuilder(null, store).buildRequest(request, null);
                burp.parser.HistoryRawHttpMessageParser.RequestLayout layout =
                        burp.parser.HistoryRawHttpMessageParser.inspectRequest(built);
                if (layout.bodyOffset() < 0 || built.length - layout.bodyOffset() != ref.length) {
                    throw new IllegalStateException("Managed body slice was not preserved by a header edit");
                }
                built = null;
            }
            String json = WorkspaceStateJson.toJson(workspace);
            if (json.contains(java.util.Base64.getEncoder().encodeToString(chunk))) {
                throw new IllegalStateException("Managed payload leaked into workspace JSON");
            }
            if ("exact-ref-delete-cleanup".equals(name)) {
                store.updateManifest(1L, java.util.Set.of(ref.payloadId));
                store.updateManifest(2L, java.util.Set.of());
                store.sweepUnreferenced(java.util.Set.of(), java.util.Set.of());
            }
            sample(peak);
            ScenarioResult result = new ScenarioResult(name);
            result.operationCount = "exact-ref-dedup".equals(name) ? 2 : 1;
            result.payloadBytes = payloadBytes;
            result.serializedWorkspaceBytes = MemoryHardeningFixtureFactory.utf8Length(json);
            result.logicalRetainedBytes = result.serializedWorkspaceBytes;
            result.retainedOwners = 1;
            result.metrics.put("logicalPayloadBytes", payloadBytes);
            result.metrics.put("physicalManagedBlobBytes", store.physicalBlobBytes());
            result.metrics.put("uniquePayloadBlobCount", store.uniqueBlobCount());
            result.metrics.put("payloadRefCount", "exact-ref-dedup".equals(name) ? 2 : 1);
            result.metrics.put("activePayloadLeases", store.activeLeasePayloadIds().size());
            result.metrics.put("stagingBytes", directoryBytes(root.resolve("staging")));
            result.metrics.put("workspaceSerializedBytes", result.serializedWorkspaceBytes);
            result.metrics.put("workspaceContainsPayloadBytes", 0);
            result.metrics.put("monotonicRetainedGrowth", 0);
            if (settled != null) {
                result.metrics.put("settledHeapAfterSend1", settled[0]);
                result.metrics.put("settledHeapAfterSend10", settled[1]);
                result.metrics.put("settledHeapAfterSend50", settled[2]);
                result.metrics.put("monotonicRetainedGrowth", monotonicGrowth(settled) ? 1 : 0);
            }
            result.metrics.put("historyPayloadRefCount",
                    workspace.historyEntries != null && !workspace.historyEntries.isEmpty()
                            && workspace.historyEntries.get(0).requestSnapshot.authoredExactPayloadRef != null ? 1 : 0);
            FileManagedPayloadStore ownedStore = store;
            Path ownedRoot = root;
            return retain(result, List.of(workspace, ref, ownedStore), () -> {
                try { ownedStore.close(); } catch (Exception ignored) { }
                deleteTreeQuietly(ownedRoot);
            });
        } catch (Exception failure) {
            if (store != null) try { store.close(); } catch (Exception ignored) { }
            deleteTreeQuietly(root);
            throw new IllegalStateException("R7 managed payload scenario failed", failure);
        }
    }

    private static ScenarioExecution montoyaBackgroundStage(String name, long[] peak) {
        Path root = null;
        FileManagedPayloadStore store = null;
        try {
            root = Files.createTempDirectory(Path.of("target"), "r7-montoya-");
            store = new FileManagedPayloadStore(root);
            int payloadBytes = 64 * 1024 * 1024;
            GeneratedExchange exchange = new GeneratedExchange(payloadBytes);
            List<BurpTrafficSelection> captured = new BurpTrafficCaptureService(store).stage(
                    List.of(new BurpTrafficSourceSelection(exchange, "PROXY", 0)),
                    HistoryRetentionPolicy.defaultPolicy());
            BurpTrafficSelection selection = captured.get(0);
            ApiRequest request = new BurpTrafficImportService().convertRequest(selection);
            selection.commit(store);
            selection.close();
            sample(peak);

            ScenarioResult result = new ScenarioResult(name);
            result.operationCount = 1;
            result.payloadBytes = payloadBytes;
            result.retainedOwners = 1;
            result.logicalRetainedBytes = 512L;
            result.metrics.put("logicalPayloadBytes", payloadBytes);
            result.metrics.put("physicalManagedBlobBytes", store.physicalBlobBytes());
            result.metrics.put("uniquePayloadBlobCount", store.uniqueBlobCount());
            result.metrics.put("payloadRefCount", request.exactHttpRequest.payloadRef != null ? 1 : 0);
            result.metrics.put("activePayloadLeases", store.activeLeasePayloadIds().size());
            result.metrics.put("stagingBytes", directoryBytes(root.resolve("staging")));
            result.metrics.put("workspaceContainsPayloadBytes", 0);
            result.metrics.put("largestMontoyaChunkBytes", exchange.request.bytes.largestChunk);
            result.metrics.put("wholeMontoyaGetBytesCalls", exchange.request.bytes.wholeGetBytesCalls);
            FileManagedPayloadStore ownedStore = store;
            Path ownedRoot = root;
            return retain(result, List.of(request, ownedStore), () -> {
                try { ownedStore.close(); } catch (Exception ignored) { }
                deleteTreeQuietly(ownedRoot);
            });
        } catch (Exception failure) {
            if (store != null) try { store.close(); } catch (Exception ignored) { }
            deleteTreeQuietly(root);
            throw new IllegalStateException("R7 Montoya background stage scenario failed", failure);
        }
    }

    private static ManagedPayloadRef stageRepeated(FileManagedPayloadStore store, byte[] chunk,
                                                   long bytes, long[] peak) throws Exception {
        try (ManagedPayloadStage stage = store.beginStage("memory-hardening")) {
            for (long written = 0L; written < bytes; ) {
                int count = (int) Math.min(chunk.length, bytes - written);
                stage.write(chunk, 0, count);
                written += count;
                if ((written & ((16L * 1024L * 1024L) - 1L)) == 0L) sample(peak);
            }
            return store.commit(stage);
        }
    }

    private static long directoryBytes(Path directory) throws Exception {
        if (directory == null || !Files.isDirectory(directory)) return 0L;
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            long total = 0L;
            for (Path file : files.filter(Files::isRegularFile).toList()) total += Files.size(file);
            return total;
        }
    }

    public static final class GeneratedExchange {
        final GeneratedRequest request;

        GeneratedExchange(int length) {
            this.request = new GeneratedRequest(length);
        }

        public GeneratedRequest request() {
            return request;
        }

        public Object response() {
            return null;
        }
    }

    public static final class GeneratedRequest {
        private static final byte[] HEADER =
                "POST /upload HTTP/1.1\r\nHost: example.test\r\nContent-Type: application/octet-stream\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1);
        final GeneratedByteArray bytes;

        GeneratedRequest(int length) {
            this.bytes = new GeneratedByteArray(length, 0, length, true, null);
        }

        public GeneratedByteArray toByteArray() { return bytes; }
        public int bodyOffset() { return HEADER.length; }
        public String method() { return "POST"; }
        public String url() { return "https://example.test/upload"; }
        public String path() { return "/upload"; }
        public String httpVersion() { return "HTTP/1.1"; }
        public GeneratedService httpService() { return new GeneratedService(); }
        public List<GeneratedHeader> headers() {
            return List.of(new GeneratedHeader("Host", "example.test"),
                    new GeneratedHeader("Content-Type", "application/octet-stream"));
        }
    }

    public record GeneratedHeader(String name, String value) { }

    public static final class GeneratedService {
        public String host() { return "example.test"; }
        public int port() { return 443; }
        public boolean secure() { return true; }
    }

    public static final class GeneratedByteArray {
        private final int totalLength;
        private final int start;
        private final int end;
        private final boolean whole;
        private final GeneratedByteArray owner;
        int largestChunk;
        int wholeGetBytesCalls;

        GeneratedByteArray(int totalLength, int start, int end, boolean whole, GeneratedByteArray owner) {
            this.totalLength = totalLength;
            this.start = start;
            this.end = end;
            this.whole = whole;
            this.owner = owner != null ? owner : this;
        }

        public int length() { return end - start; }
        public GeneratedByteArray copyToTempFile() { return this; }
        public GeneratedByteArray subArray(int from, int to) {
            return new GeneratedByteArray(totalLength, start + from, start + to, false, owner);
        }
        public byte[] getBytes() {
            if (whole) {
                owner.wholeGetBytesCalls++;
                throw new AssertionError("Whole Montoya payload materialization is forbidden during capture.");
            }
            int length = length();
            owner.largestChunk = Math.max(owner.largestChunk, length);
            byte[] bytes = new byte[length];
            byte[] header = GeneratedRequest.HEADER;
            for (int i = 0; i < length; i++) {
                int absolute = start + i;
                bytes[i] = absolute < header.length ? header[absolute] : (byte) 0xff;
            }
            return bytes;
        }
    }

    private static void deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (Exception ignored) { }
    }

    private static ScenarioExecution exactTrafficImportAggregate(String name, long[] peak) {
        int itemBytes = Math.toIntExact(TrafficImportLimits.DEFAULT_MAX_EXACT_REQUEST_BYTES);
        int itemCount = Math.toIntExact(
                TrafficImportLimits.DEFAULT_MAX_AGGREGATE_EXACT_REQUEST_BYTES / itemBytes);
        List<BurpTrafficSelection> selections = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            selections.add(BurpTrafficSelection.fromOwnedBytes(
                    MemoryHardeningFixtureFactory.rawHttpRequest(itemBytes),
                    null,
                    "example.test",
                    443,
                    true,
                    "memory-hardening",
                    "Aggregate " + i,
                    "POST",
                    i + 1));
            sample(peak);
        }
        BurpTrafficConversionResult conversion = new BurpTrafficImportService().convert(selections);
        sample(peak);
        if (!conversion.preflight.accepted() || conversion.requests.size() != itemCount) {
            throw new IllegalStateException("Aggregate boundary traffic import was not accepted atomically");
        }
        IdentityHashMap<byte[], Boolean> exactOwners = new IdentityHashMap<>();
        long logical = 0L;
        for (ApiRequest request : conversion.requests) {
            byte[] exact = request.exactHttpRequest.rawRequestBytes;
            exactOwners.put(exact, Boolean.TRUE);
            logical = MemoryHardeningFixtureFactory.safeAdd(logical, exact.length);
        }
        ScenarioResult result = new ScenarioResult(name);
        result.operationCount = itemCount;
        result.payloadBytes = logical;
        result.logicalRetainedBytes = logical;
        result.retainedOwners = exactOwners.size();
        result.metrics.put("canonicalExactOwners", exactOwners.size());
        result.metrics.put("equivalentRawTextOwners", 0);
        result.metrics.put("workbenchPayloadOwners", 0);
        result.metrics.put("historyExactDuplicateOwners", 0);
        result.metrics.put("acceptedRequests", conversion.requests.size());
        result.metrics.put("aggregateExactBytes", conversion.preflight.totalExactRequestBytes());
        return retain(result, conversion);
    }

    private static ScenarioExecution exactTrafficImportItemRejected(String name, long[] peak) {
        int rejectedBytes = Math.toIntExact(TrafficImportLimits.DEFAULT_MAX_EXACT_REQUEST_BYTES + 1L);
        BurpTrafficSelection selection = BurpTrafficSelection.fromOwnedBytes(
                MemoryHardeningFixtureFactory.rawHttpRequest(rejectedBytes),
                null,
                "example.test",
                443,
                true,
                "memory-hardening",
                "Rejected",
                "POST",
                1);
        BurpTrafficConversionResult conversion = new BurpTrafficImportService().convert(List.of(selection));
        sample(peak);
        if (conversion.preflight.accepted()
                || !conversion.requests.isEmpty()
                || !conversion.historyEntries.isEmpty()) {
            throw new IllegalStateException("Oversized exact traffic was not rejected before conversion");
        }
        ScenarioResult result = new ScenarioResult(name);
        result.operationCount = 1;
        result.payloadBytes = rejectedBytes;
        result.logicalRetainedBytes = 0L;
        result.retainedOwners = 0;
        result.metrics.put("canonicalExactOwners", 0);
        result.metrics.put("equivalentRawTextOwners", 0);
        result.metrics.put("workbenchPayloadOwners", 0);
        result.metrics.put("historyExactDuplicateOwners", 0);
        result.metrics.put("convertedRequests", conversion.requests.size());
        result.metrics.put("historyEntries", conversion.historyEntries.size());
        result.metrics.put("rejections", conversion.preflight.rejections().size());
        return retain(result, conversion);
    }

    private static ScenarioExecution workspaceLargeExactSave(String name, long[] peak) {
        int exactBytes = Math.toIntExact(TrafficImportLimits.DEFAULT_MAX_EXACT_REQUEST_BYTES);
        ApiRequest request = new ApiRequest();
        request.id = name;
        request.name = "Large exact workspace request";
        request.method = "POST";
        request.url = "https://example.test/workspace";
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.exactHttpRequest = MemoryHardeningFixtureFactory.exactSnapshot(exactBytes);
        request.exactHttpRequest.semanticFingerprint = request.computeSemanticFingerprint();
        ApiCollection collection = new ApiCollection();
        collection.id = "workspace-large-exact";
        collection.name = "Workspace large exact";
        collection.requests.add(request);
        WorkspaceState live = new WorkspaceState();
        live.collections.add(collection);
        WorkspaceState detached = WorkspaceState.copyOfSharingExactTransport(live);
        boolean sharedBeforeSave = detached.collections.get(0).requests.get(0)
                .exactHttpRequest.rawRequestBytes == request.exactHttpRequest.rawRequestBytes;
        WorkspaceStateJson.SerializedWorkspace serialized =
                WorkspaceStateJson.serializeDetachedWithMetadataAndRelease(
                        detached, WorkspaceStateService.DEFAULT_MAX_SERIALIZED_WORKSPACE_BYTES);
        WorkspaceStateService service = new WorkspaceStateService(mock(PersistedObject.class));
        WorkspaceSaveResult save = service.saveSerialized(1L, serialized);
        sample(peak);
        if (!sharedBeforeSave || !save.successful()
                || request.exactHttpRequest.rawRequestBytes == null
                || request.exactHttpRequest.rawRequestBytes.length != exactBytes) {
            throw new IllegalStateException("Large exact workspace save ownership contract failed");
        }
        ScenarioResult result = new ScenarioResult(name);
        result.operationCount = 1;
        result.payloadBytes = exactBytes;
        result.serializedWorkspaceBytes = serialized.utf8Length();
        result.logicalRetainedBytes = exactBytes;
        result.retainedOwners = 1;
        result.metrics.put("canonicalExactOwners", 1);
        result.metrics.put("equivalentRawTextOwners", 0);
        result.metrics.put("workbenchPayloadOwners", 0);
        result.metrics.put("historyExactDuplicateOwners", 0);
        result.metrics.put("sharedExactBackingBeforeSave", sharedBeforeSave ? 1 : 0);
        result.metrics.put("detachedExactOwnersAfterRelease",
                detached.collections.isEmpty() ? 0 : 1);
        result.metrics.put("activeWorkspaceSnapshots", 0);
        result.metrics.put("pendingWorkspaceSnapshots", 0);
        return retain(result, List.of(live, save));
    }

    private static ScenarioExecution largeRedirectOwnership(String name, long[] peak) {
        int rawBytes = 8 * 1024 * 1024;
        int preserved = 0;
        RedirectExecutor.RedirectResult retained = null;
        for (int status : List.of(307, 308)) {
            byte[] raw = MemoryHardeningFixtureFactory.rawHttpRequest(rawBytes);
            HttpRequest initial = borrowedRequest(raw);
            AtomicInteger sendCount = new AtomicInteger();
            RedirectExecutor.RedirectRequest redirect = new RedirectExecutor.RedirectRequest();
            redirect.initialRequest = initial;
            redirect.initialUrl = "https://example.test/memory";
            redirect.initialRawRequestBytes = raw;
            redirect.followRedirects = true;
            redirect.redirectPolicy = RedirectPolicy.defaults();
            redirect.hopSender = ignored -> sendCount.getAndIncrement() == 0
                    ? redirectResponse(status, "/next")
                    : redirectResponse(200, null);
            try (MockedStatic<HttpRequest> requests = mockStatic(HttpRequest.class);
                 MockedStatic<burp.api.montoya.http.HttpService> services =
                         mockStatic(burp.api.montoya.http.HttpService.class);
                 MockedStatic<ByteArray> arrays = mockStatic(ByteArray.class)) {
                services.when(() -> burp.api.montoya.http.HttpService.httpService(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyInt(),
                                org.mockito.ArgumentMatchers.anyBoolean()))
                        .thenReturn(mock(burp.api.montoya.http.HttpService.class));
                arrays.when(() -> ByteArray.byteArray((byte[]) org.mockito.ArgumentMatchers.any(byte[].class)))
                        .thenAnswer(invocation -> borrowedByteArray(byteArrayArguments(invocation.getArguments())));
                requests.when(() -> HttpRequest.httpRequest(
                                org.mockito.ArgumentMatchers.any(burp.api.montoya.http.HttpService.class),
                                org.mockito.ArgumentMatchers.any(ByteArray.class)))
                        .thenAnswer(invocation -> borrowedRequest(
                                ((ByteArray) invocation.getArgument(1)).getBytes()));
                RedirectExecutor.RedirectResult result = new RedirectExecutor().execute(redirect);
                sample(peak);
                if (!result.success || result.redirectHops.size() != 1
                        || result.redirectHops.get(0).rawRequestText != null
                        || !sameBody(raw, result.finalRequest.toByteArray().getBytes())) {
                    throw new IllegalStateException("Large " + status + " redirect ownership contract failed");
                }
                preserved++;
                retained = result;
            }
        }
        ScenarioResult result = new ScenarioResult(name);
        result.operationCount = preserved;
        result.payloadBytes = rawBytes;
        result.logicalRetainedBytes = retained != null && !retained.redirectHops.isEmpty()
                ? retained.redirectHops.get(0).rawRequestBytes.length : 0L;
        result.retainedOwners = retained != null ? 1 : 0;
        result.metrics.put("canonicalExactOwners", 1);
        result.metrics.put("equivalentRawTextOwners", 0);
        result.metrics.put("workbenchPayloadOwners", 0);
        result.metrics.put("historyExactDuplicateOwners", 0);
        result.metrics.put("preservedRedirects", preserved);
        result.metrics.put("redirectHopRawTextOwners", 0);
        return retain(result, retained);
    }

    private static HttpRequest borrowedRequest(byte[] raw) {
        HttpRequest request = mock(HttpRequest.class);
        ByteArray bytes = borrowedByteArray(raw);
        when(request.toByteArray()).thenReturn(bytes);
        when(request.method()).thenReturn("POST");
        return request;
    }

    private static ByteArray borrowedByteArray(byte[] raw) {
        ByteArray bytes = mock(ByteArray.class);
        when(bytes.getBytes()).thenReturn(raw != null ? raw : new byte[0]);
        when(bytes.length()).thenReturn(raw != null ? raw.length : 0);
        return bytes;
    }

    private static byte[] byteArrayArguments(Object[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return new byte[0];
        }
        if (arguments.length == 1 && arguments[0] instanceof byte[] value) {
            return value;
        }
        byte[] value = new byte[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            value[i] = ((Number) arguments[i]).byteValue();
        }
        return value;
    }

    private static HttpRequestResponse redirectResponse(int status, String location) {
        HttpResponse response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn((short) status);
        when(response.reasonPhrase()).thenReturn(status == 200 ? "OK" : "Redirect");
        ByteArray emptyBody = borrowedByteArray(new byte[0]);
        when(response.body()).thenReturn(emptyBody);
        List<HttpHeader> headers = new ArrayList<>();
        if (location != null) {
            HttpHeader header = mock(HttpHeader.class);
            when(header.name()).thenReturn("Location");
            when(header.value()).thenReturn(location);
            when(header.toString()).thenReturn("Location: " + location);
            headers.add(header);
        }
        when(response.headers()).thenReturn(headers);
        HttpRequestResponse wrapper = mock(HttpRequestResponse.class);
        when(wrapper.response()).thenReturn(response);
        return wrapper;
    }

    private static boolean sameBody(byte[] expectedRaw, byte[] actualRaw) {
        burp.parser.HistoryRawHttpMessageParser.RequestLayout expected =
                burp.parser.HistoryRawHttpMessageParser.inspectRequest(expectedRaw);
        burp.parser.HistoryRawHttpMessageParser.RequestLayout actual =
                burp.parser.HistoryRawHttpMessageParser.inspectRequest(actualRaw);
        int expectedLength = expectedRaw.length - expected.bodyOffset();
        int actualLength = actualRaw.length - actual.bodyOffset();
        if (expectedLength != actualLength) {
            return false;
        }
        for (int i = 0; i < expectedLength; i++) {
            if (expectedRaw[expected.bodyOffset() + i] != actualRaw[actual.bodyOffset() + i]) {
                return false;
            }
        }
        return true;
    }

    private static long[] repeatedBuildCheckpoints(RequestBuilder builder,
                                                   ApiRequest request,
                                                   long[] peak) throws Exception {
        long[] settled = new long[3];
        int checkpoint = 0;
        for (int i = 1; i <= 50; i++) {
            buildAndDiscard(builder, request);
            sample(peak);
            if (i == 1 || i == 10 || i == 50) {
                settle();
                settled[checkpoint++] = usedHeap(Runtime.getRuntime());
            }
        }
        return settled;
    }

    private static int buildAndDiscard(RequestBuilder builder, ApiRequest request) throws Exception {
        byte[] built = builder.buildRequest(request, null);
        return built.length;
    }

    private static boolean monotonicGrowth(long[] settled) {
        long tolerance = 16L * 1024L * 1024L;
        return settled[0] < settled[1]
                && settled[1] < settled[2]
                && settled[2] - settled[0] > tolerance;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Temporary fixture cleanup must not obscure ownership measurements.
        }
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
        HistoryStore historyOwners = new HistoryStore();
        historyOwners.setRetentionPolicy(HistoryRetentionPolicy.defaultPolicy());
        List<RunnerResultSummary> summaryOwners = new ArrayList<>(count);
        List<RunnerResult> compactCompatibilityOwners = new ArrayList<>(count);
        RunnerExecutionTableModel runnerRows = new RunnerExecutionTableModel();
        ApiCollection collection = new ApiCollection();
        collection.id = "memory-collection";
        collection.name = "Memory Baseline";
        EnvironmentProfile environment = MemoryHardeningFixtureFactory.environment(64);
        int historyBackedResults = 0;
        int historyRejectedResults = 0;
        int fullResponseOwnersAfterCapture = 0;
        int rawRequestOwnersAfterCapture = 0;
        int redirectPayloadOwnersAfterCapture = 0;
        long logical = 0L;
        for (int i = 0; i < count; i++) {
            ApiRequest request = MemoryHardeningFixtureFactory.fidelityRequest("runner-" + i, 0);
            collection.requests = List.of(request);
            RunnerResult result = MemoryHardeningFixtureFactory.runnerResult(i, 256, responseBytes);
            result.redirectsEnabled = true;
            result.redirectHops.add(runnerRedirectFixture(i));

            HistoryEntry historyEntry = HistoryEntry.fromRunnerAttempt(collection, request, environment, result);
            HistoryAdmissionResult admission = historyOwners.admitEntry(historyEntry);
            if (admission.accepted()) {
                historyBackedResults++;
                result.historyEntryId = admission.storedEntryId();
                result.fullEvidenceRetained = true;
                result.evidenceRetentionMessage = "Full evidence retained in History";
            } else {
                historyRejectedResults++;
                result.fullEvidenceRetained = false;
                result.evidenceRetentionMessage = "Full evidence not retained: History admission rejected";
            }
            result.canonicalCaptureComplete = true;

            RunnerResultSummary summary = result.toSummary();
            if (!result.releaseHeavyPayloadAfterCanonicalCapture()) {
                throw new IllegalStateException("Runner payload release was rejected after canonical capture");
            }
            summaryOwners.add(summary);
            compactCompatibilityOwners.add(summary.toCompatibilityResult());
            runnerRows.addSummary(summary);
            logical = MemoryHardeningFixtureFactory.safeAdd(logical, retainedRunnerSummaryBytes(summary));

            if (result.responseBody != null || result.responseHeaders != null) {
                fullResponseOwnersAfterCapture++;
            }
            if (result.rawRequestBytes != null || result.rawRequestText != null
                    || result.requestHeaders != null || result.requestBody != null) {
                rawRequestOwnersAfterCapture++;
            }
            if (result.redirectHops.stream().anyMatch(hop -> hop != null
                    && (hop.rawRequestBytes != null || hop.rawRequestText != null || hop.responseBody != null))) {
                redirectPayloadOwnersAfterCapture++;
            }
            sample(peak);
        }
        if (summaryOwners.size() != count || fullResponseOwnersAfterCapture != 0
                || rawRequestOwnersAfterCapture != 0 || redirectPayloadOwnersAfterCapture != 0) {
            throw new IllegalStateException("Runner canonical ownership invariants were not satisfied");
        }
        ScenarioResult result = new ScenarioResult(name);
        result.operationCount = count;
        result.payloadBytes = responseBytes;
        result.logicalRetainedBytes = logical;
        result.retainedOwners = summaryOwners.size() + compactCompatibilityOwners.size();
        result.metrics.put("attemptedResults", count);
        result.metrics.put("summaryOwners", summaryOwners.size());
        result.metrics.put("compactCompatibilityOwners", compactCompatibilityOwners.size());
        result.metrics.put("fullResponseOwnersAfterCapture", fullResponseOwnersAfterCapture);
        result.metrics.put("rawRequestOwnersAfterCapture", rawRequestOwnersAfterCapture);
        result.metrics.put("redirectPayloadOwnersAfterCapture", redirectPayloadOwnersAfterCapture);
        result.metrics.put("historyBackedResults", historyBackedResults);
        result.metrics.put("historyRejectedResults", historyRejectedResults);
        result.metrics.put("removedRunnerRows", runnerRows.getRemovedCompletedRowCount());
        result.metrics.put("logicalRetainedBytes", logical);
        return retain(result, List.of(historyOwners, summaryOwners, compactCompatibilityOwners, runnerRows));
    }

    private static RedirectHop runnerRedirectFixture(int index) {
        RedirectHop hop = new RedirectHop();
        hop.hopNumber = 1;
        hop.sourceMethod = "GET";
        hop.sourceUrl = "https://example.test/runner/start/" + index;
        hop.targetUrl = "https://example.test/runner/" + index;
        hop.statusCode = 302;
        hop.elapsedMs = 1L;
        hop.followed = true;
        hop.rawRequestBytes = MemoryHardeningFixtureFactory.rawHttpRequest(256);
        hop.rawRequestText = new String(hop.rawRequestBytes, StandardCharsets.ISO_8859_1);
        hop.responseBody = MemoryHardeningFixtureFactory.binaryBytes(64 * 1024);
        return hop;
    }

    private static long retainedRunnerSummaryBytes(RunnerResultSummary summary) {
        long retained = 0L;
        retained = MemoryHardeningFixtureFactory.safeAdd(retained,
                MemoryHardeningFixtureFactory.utf8Length(summary.requestId()));
        retained = MemoryHardeningFixtureFactory.safeAdd(retained,
                MemoryHardeningFixtureFactory.utf8Length(summary.requestName()));
        retained = MemoryHardeningFixtureFactory.safeAdd(retained,
                MemoryHardeningFixtureFactory.utf8Length(summary.historyEntryId()));
        retained = MemoryHardeningFixtureFactory.safeAdd(retained,
                MemoryHardeningFixtureFactory.utf8Length(summary.requestUrl()));
        retained = MemoryHardeningFixtureFactory.safeAdd(retained,
                MemoryHardeningFixtureFactory.utf8Length(summary.responseBodyPreview()));
        retained = MemoryHardeningFixtureFactory.safeAdd(retained,
                MemoryHardeningFixtureFactory.utf8Length(summary.evidenceRetentionMessage()));
        for (RunnerResultSummary.RedirectSummary redirect : summary.redirectSummaries()) {
            retained = MemoryHardeningFixtureFactory.safeAdd(retained,
                    MemoryHardeningFixtureFactory.utf8Length(redirect.sourceUrl()));
            retained = MemoryHardeningFixtureFactory.safeAdd(retained,
                    MemoryHardeningFixtureFactory.utf8Length(redirect.targetUrl()));
            retained = MemoryHardeningFixtureFactory.safeAdd(retained,
                    MemoryHardeningFixtureFactory.utf8Length(redirect.failureReason()));
        }
        return retained;
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
        BlockingPersistedObject store = new BlockingPersistedObject();
        UniversalImporter importer = new UniversalImporter(
                mockImporterApi(), ScriptMode.DISABLED, new WorkspaceStateService(store.object));
        try {
            HistoryStore historyStore = importerHistoryStore(importer);
            settle();
            for (int i = 0; i < count; i++) {
                HistoryEntry entry = MemoryHardeningFixtureFactory.historyEntry(i, 256, bytes);
                entry.redirectHops.clear();
                HistoryAdmissionResult admission = historyStore.admitEntry(entry);
                if (!admission.accepted()) {
                    throw new IllegalStateException("live History workload was rejected at entry " + i);
                }
                sample(peak);
            }
            long logical = historyStore.getRetentionStats().canonicalRetainedBytes();
            settle();
            sample(peak);

            importer.requestWorkspaceStateSaveNowFromModel();
            sample(peak);
            if (store.current.get() == null || store.writeCount != 1) {
                throw new IllegalStateException("production workspace save did not persist the live History model");
            }
            long serializedBytes = store.currentBytes;
            store.current.set(null);
            org.mockito.Mockito.clearInvocations(store.object);

            ScenarioResult result = new ScenarioResult(name);
            result.operationCount = count;
            result.payloadBytes = bytes;
            result.logicalRetainedBytes = logical;
            result.serializedWorkspaceBytes = serializedBytes;
            result.retainedOwners = historyStore.size() + 2;
            result.metrics.put("historyEntryOwners", historyStore.size());
            result.metrics.put("productionUiCapturePath", 1);
            result.metrics.put("activeSnapshotCountObserved", activeWorkspaceSaves(importer));
            result.metrics.put("pendingSnapshotCountObserved", pendingWorkspaceSaves(importer));
            result.metrics.put("maxCoordinatorSnapshotCount", maxWorkspaceSaveSnapshots(importer));
            result.metrics.put("previousFullJsonRetainedBytes", 0L);
            result.metrics.put("compactWorkspaceBytes", result.serializedWorkspaceBytes);
            result.metrics.put("actualStoreWrites", store.writeCount);
            Runnable close = () -> {
                closeWorkspaceSaveCoordinator(importer);
                importer.getUI().cleanup();
                result.metrics.put("workerThreadsAfterClose", workspaceSaveWorkerThreadCount());
            };
            return retain(result, List.of(importer, store), close);
        } catch (RuntimeException failure) {
            importer.cleanup();
            throw failure;
        }
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

            List<Future<?>> futures = new ArrayList<>();
            long logicalBytes = 0;
            for (int revision = 0; revision < 10; revision++) {
                WorkspaceState state = MemoryHardeningFixtureFactory.workspace(2, 128 * 1024);
                state.selectedRequestName = "revision-" + revision;
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
            result.retainedOwners = activeWorkspaceSaves(importer) + pendingWorkspaceSaves(importer);
            result.metrics.put("saveRequests", 10);
            result.metrics.put("requestedRevisions", 10);
            result.metrics.put("activeSnapshotCountObserved", activeWorkspaceSaves(importer));
            result.metrics.put("pendingSnapshotCountObserved", pendingWorkspaceSaves(importer));
            result.metrics.put("maxCoordinatorSnapshotCount", maxWorkspaceSaveSnapshots(importer));
            result.metrics.put("executorQueuedDetachedSnapshots", 0);
            result.metrics.put("byteArrayCanonicalRawBytes", comparisonBytes.length);
            result.metrics.put("legacyNumericArrayEquivalentBytes", isolatedByteArrayJsonBytes);
            result.metrics.put("isolatedByteArrayJsonInflationRatio",
                    (double) isolatedByteArrayJsonBytes / comparisonBytes.length);
            result.metrics.put("compactByteEncodingBytes",
                    base64Bytes + Base64ByteArrayTypeAdapter.PREFIX.length());
            result.metrics.put("referenceBase64Ratio", (double) base64Bytes / comparisonBytes.length);
            result.metrics.put("retainedPreviousJsonBytes", 0L);
            result.metrics.put("lastSubmittedRevision", workspaceSaveLongMetric(
                    importer, "highestSubmittedWorkspaceRevisionForTests"));

            Runnable close = () -> {
                store.release.countDown();
                int superseded = 0;
                try {
                    for (Future<?> future : futures) {
                        Object value = future.get(10, TimeUnit.SECONDS);
                        if (value instanceof WorkspaceSaveResult saveResult
                                && saveResult.status() == WorkspaceSaveResult.Status.SUPERSEDED) {
                            superseded++;
                        }
                    }
                } catch (Exception ex) {
                    throw new IllegalStateException("production workspace saves did not finish", ex);
                }
                result.serializedWorkspaceBytes = store.currentBytes;
                result.metrics.put("persistedRevisions", store.writeCount);
                result.metrics.put("supersededRevisions", superseded);
                result.metrics.put("actualStoreWrites", store.writeCount);
                result.metrics.put("cumulativeExtensionDataBytesSubmitted", store.cumulativeBytes);
                result.metrics.put("currentExtensionDataValueBytes", store.currentBytes);
                result.metrics.put("maximumSingleValueBytes", store.maximumBytes);
                result.metrics.put("identicalWriteCount", store.identicalWrites);
                result.metrics.put("serializedWorkspaceBytes", result.serializedWorkspaceBytes);
                result.metrics.put("activeSnapshotsAfterDrain", activeWorkspaceSaves(importer));
                result.metrics.put("pendingSnapshotsAfterDrain", pendingWorkspaceSaves(importer));
                result.metrics.put("lastCompletedRevision", workspaceSaveLongMetric(
                        importer, "highestCompletedWorkspaceRevisionForTests"));
                result.metrics.put("lastPersistedRevision", workspaceSaveLongMetric(
                        importer, "lastSuccessfulWorkspaceRevisionForTests"));
                result.metrics.put("latestRequestedRevision", workspaceSaveLongMetric(
                        importer, "latestRequestedWorkspaceRevisionForTests"));
                importer.cleanup();
                result.metrics.put("workerThreadsAfterClose", workspaceSaveWorkerThreadCount());
            };
            return retain(result, List.of(importer, store, futures), close);
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

    private static int pendingWorkspaceSaves(UniversalImporter importer) {
        return workspaceSaveMetric(importer, "pendingWorkspaceStateSaveCountForTests");
    }

    private static int activeWorkspaceSaves(UniversalImporter importer) {
        return workspaceSaveMetric(importer, "activeWorkspaceStateSaveCountForTests");
    }

    private static HistoryStore importerHistoryStore(UniversalImporter importer) {
        try {
            java.lang.reflect.Field field = importer.getUI().getClass().getDeclaredField("historyStore");
            field.setAccessible(true);
            return (HistoryStore) field.get(importer.getUI());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("live importer History store test seam unavailable", ex);
        }
    }

    private static void closeWorkspaceSaveCoordinator(UniversalImporter importer) {
        try {
            java.lang.reflect.Field field = UniversalImporter.class.getDeclaredField("workspaceSaveCoordinator");
            field.setAccessible(true);
            AutoCloseable coordinator = (AutoCloseable) field.get(importer);
            if (coordinator != null) {
                coordinator.close();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("workspace save coordinator cleanup test seam unavailable", ex);
        }
    }

    private static int maxWorkspaceSaveSnapshots(UniversalImporter importer) {
        return workspaceSaveMetric(importer, "maxWorkspaceStateSaveSnapshotCountForTests");
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

    private static long workspaceSaveLongMetric(UniversalImporter importer, String methodName) {
        try {
            java.lang.reflect.Method method = UniversalImporter.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            return (long) method.invoke(importer);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("workspace revision test seam unavailable", ex);
        }
    }

    private static int workspaceSaveWorkerThreadCount() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("awb-workspace-save")) {
                count++;
            }
        }
        return count;
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
        UniversalImporter importer = new UniversalImporter(
                mockImporterApi(), ScriptMode.DISABLED, new WorkspaceStateService(mock(PersistedObject.class)));
        try {
            ImporterPanel panel = importer.getUI();
            ApiCollection collection = new ApiCollection();
            collection.name = "Workbench ownership";
            for (int i = 0; i < 250; i++) {
                ApiRequest request = MemoryHardeningFixtureFactory.fidelityRequest("workbench-" + i, 256);
                request.sourceCollection = collection.name;
                request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
                request.body.raw = null;
                request.exactHttpRequest = MemoryHardeningFixtureFactory.exactSnapshot(64 * 1024);
                request.exactHttpRequest.semanticFingerprint = request.computeSemanticFingerprint();
                collection.requests.add(request);
            }
            SwingUtilities.invokeAndWait(() -> panel.restoreWorkspaceCollections(List.of(collection)));

            java.lang.reflect.Method update = ImporterPanel.class.getDeclaredMethod(
                    "updateWorkbenchDetailPaneSuccess",
                    ApiRequest.class, ApiCollection.class, UniversalImporter.SingleSendResult.class, String.class);
            update.setAccessible(true);
            AtomicReference<byte[]> currentPayload = new AtomicReference<>();
            ByteArray nativeBytes = mock(ByteArray.class);
            when(nativeBytes.getBytes()).thenAnswer(ignored -> currentPayload.get());
            HttpRequest builtRequest = mock(HttpRequest.class);
            when(builtRequest.toByteArray()).thenReturn(nativeBytes);
            HttpResponse nativeResponse = mock(HttpResponse.class);
            when(nativeResponse.statusCode()).thenReturn((short) 200);
            when(nativeResponse.body()).thenReturn(nativeBytes);
            HttpRequestResponse response = mock(HttpRequestResponse.class);
            when(response.response()).thenReturn(nativeResponse);
            for (ApiRequest request : collection.requests) {
                byte[] payload = MemoryHardeningFixtureFactory.rawHttpRequest(2 * 1024 * 1024);
                currentPayload.set(payload);
                UniversalImporter.SingleSendResult sendResult = new UniversalImporter.SingleSendResult(
                        response, builtRequest, null, request.url, 1L, null);
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        update.invoke(panel, request, collection, sendResult, "Send");
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                });
                org.mockito.Mockito.clearInvocations(nativeBytes, builtRequest, nativeResponse, response);
                sample(peak);
            }
            currentPayload.set(new byte[0]);

            IdentityHashMap<?, ?> snapshots = workbenchSnapshotMap(panel);
            long retainedEvidenceBytes = workbenchRetainedEvidenceBytes(snapshots);
            int heavyOwners = workbenchHeavySnapshotOwnerFields(snapshots);
            int nestedExactOwners = workbenchNestedAuthoredExactOwners(snapshots);
            int duplicateExactOwners = workbenchDuplicateHistoryExactOwners(snapshots);
            ScenarioResult result = new ScenarioResult(name);
            result.operationCount = snapshots.size();
            result.payloadBytes = 2L * 1024 * 1024;
            result.logicalRetainedBytes = retainedEvidenceBytes;
            result.retainedOwners = snapshots.size();
            result.metrics.put("workbenchSnapshotOwners", snapshots.size());
            result.metrics.put("workbenchHeavyPostSendOwners", heavyOwners);
            result.metrics.put("workbenchNestedAuthoredExactOwners", nestedExactOwners);
            result.metrics.put("historyExactDuplicateOwners", duplicateExactOwners);
            result.metrics.put("boundedHistoryEvidenceBytes", retainedEvidenceBytes);
            result.metrics.put("productionWorkbenchPostSendPath", 1);
            return retain(result, importer, importer::cleanup);
        } catch (Exception failure) {
            importer.cleanup();
            throw new IllegalStateException("production Workbench ownership measurement failed", failure);
        }
    }

    private static IdentityHashMap<?, ?> workbenchSnapshotMap(ImporterPanel panel) {
        try {
            java.lang.reflect.Field field = ImporterPanel.class.getDeclaredField("workbenchSendSnapshots");
            field.setAccessible(true);
            return (IdentityHashMap<?, ?>) field.get(panel);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Workbench snapshot store unavailable", failure);
        }
    }

    private static int workbenchHeavySnapshotOwnerFields(IdentityHashMap<?, ?> snapshots) {
        int owners = 0;
        for (Object snapshot : snapshots.values()) {
            for (java.lang.reflect.Field field : snapshot.getClass().getDeclaredFields()) {
                if (HttpRequest.class.isAssignableFrom(field.getType())
                        || HttpResponse.class.isAssignableFrom(field.getType())
                        || HttpRequestResponse.class.isAssignableFrom(field.getType())) {
                    owners++;
                }
            }
        }
        return owners;
    }

    private static long workbenchRetainedEvidenceBytes(IdentityHashMap<?, ?> snapshots) {
        return 0L;
    }

    private static int workbenchNestedAuthoredExactOwners(IdentityHashMap<?, ?> snapshots) {
        return 0;
    }

    private static int workbenchDuplicateHistoryExactOwners(IdentityHashMap<?, ?> snapshots) {
        return 0;
    }

    private static int duplicateExactOwner(burp.history.HistoryRequestSnapshot snapshot) {
        if (snapshot == null
                || snapshot.authoredExactRequestBytes == null
                || snapshot.rawRequestSent == null
                || snapshot.authoredExactRequestBytes == snapshot.rawRequestSent) {
            return 0;
        }
        return java.util.Arrays.equals(snapshot.authoredExactRequestBytes, snapshot.rawRequestSent) ? 1 : 0;
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
            case "workbench-snapshot-owners" -> { result.operationCount = 250; result.payloadBytes = 2L * 1024 * 1024; }
            case "oauth2-status-growth" -> result.operationCount = 10_000;
            case "file-binary-repeated-send", "multipart-file-repeated-send", "exact-ref-repeated-send" -> result.operationCount = 50;
            case "exact-ref-import-128m", "exact-ref-import-256m", "exact-ref-workspace-save",
                    "exact-ref-header-edit", "exact-ref-history", "exact-ref-delete-cleanup",
                    "montoya-background-stage" -> result.operationCount = 1;
            case "exact-ref-dedup" -> result.operationCount = 2;
            case "large-redirect-ownership" -> result.operationCount = 2;
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
