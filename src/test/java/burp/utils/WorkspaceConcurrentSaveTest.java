package burp.utils;

import burp.UniversalImporter;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.diagnostics.DiagnosticStore;
import burp.history.HistoryEntry;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class WorkspaceConcurrentSaveTest {
    private static final long CONCURRENCY_TIMEOUT_SECONDS = 1L;
    private static final long THREAD_JOIN_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(CONCURRENCY_TIMEOUT_SECONDS);

    @AfterEach
    void tearDown() {
        DiagnosticStore.getInstance().setCaptureEnabled(false);
        DiagnosticStore.getInstance().clear();
    }

    @Test
    void orderedCompetingSavesKeepTheLastApprovedSnapshotAndNeverPersistMixedJson() throws Exception {
        BlockingStringStore store = new BlockingStringStore();
        WorkspaceStateService service = new WorkspaceStateService(store);

        String workspaceV1 = workspaceJson("workspace-v1", "env-v1", false, "queue-v1", "history-v1");
        String workspaceV2 = workspaceJson("workspace-v2", "env-v2", true, "queue-v2", "history-v2");
        String workspaceV3 = workspaceJson("workspace-v3", "env-v3", false, "queue-v3", "history-v3");
        String workspaceV4 = workspaceJson("workspace-v4", "env-v4", true, "queue-v4", "history-v4");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread firstWriter = new Thread(() -> saveJson(service, workspaceV1, failure), "workspace-save-v1");
        Thread queuedWriters = new Thread(() -> {
            await(store.firstWriteEntered);
            saveJson(service, workspaceV2, failure);
            saveJson(service, workspaceV3, failure);
            saveJson(service, workspaceV4, failure);
        }, "workspace-save-v2-v4");

        firstWriter.start();
        assertThat(store.firstWriteEntered.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        queuedWriters.start();

        assertThat(store.writes()).isEmpty();
        store.allowFirstWrite.countDown();

        firstWriter.join(THREAD_JOIN_TIMEOUT_MILLIS);
        queuedWriters.join(THREAD_JOIN_TIMEOUT_MILLIS);
        assertThat(firstWriter.isAlive()).isFalse();
        assertThat(queuedWriters.isAlive()).isFalse();
        assertThat(failure.get()).isNull();

        assertThat(store.writes())
                .containsExactly("workspace-v1", "workspace-v2", "workspace-v3", "workspace-v4");

        WorkspaceState saved = WorkspaceStateJson.fromJson(store.currentValue());
        assertThat(saved.collections).singleElement().extracting(collection -> collection.name).isEqualTo("workspace-v4");
        assertThat(saved.environments).singleElement().extracting(environment -> environment.name).isEqualTo("env-v4");
        assertThat(saved.activeEnvironmentId).isEqualTo("env-id-env-v4");
        assertThat(saved.runnerQueuedRequestIdentityKeys)
                .containsExactlyElementsOf(workspaceState("workspace-v4", "env-v4", true, "queue-v4", "history-v4").runnerQueuedRequestIdentityKeys);
        assertThat(saved.historyEntries).singleElement().extracting(entry -> entry.id).isEqualTo("history-v4");
        assertThat(saved.diagnosticsCaptureEnabled).isTrue();
        assertThat(store.parsedSnapshots()).allSatisfy(snapshot -> assertThat(snapshot.collections).hasSize(1));
    }

    @Test
    void saveSnapshotsWorkspaceStateBeforeLaterCallerMutationsReachTheStore() throws Exception {
        BlockingStringStore store = new BlockingStringStore();
        WorkspaceStateService service = new WorkspaceStateService(store);
        WorkspaceState state = workspaceState("workspace-v1", "env-v1", false, "queue-v1", "history-v1");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                service.save(state);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "workspace-save-snapshot");

        writer.start();
        assertThat(store.firstWriteEntered.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        mutateWorkspaceState(state, "workspace-v2", "env-v2", true, "queue-v2", "history-v2");
        store.allowFirstWrite.countDown();

        writer.join(THREAD_JOIN_TIMEOUT_MILLIS);
        assertThat(writer.isAlive()).isFalse();
        assertThat(failure.get()).isNull();

        WorkspaceState saved = WorkspaceStateJson.fromJson(store.currentValue());
        assertThat(saved.collections).singleElement().extracting(collection -> collection.name).isEqualTo("workspace-v1");
        assertThat(saved.environments).singleElement().extracting(environment -> environment.name).isEqualTo("env-v1");
        assertThat(saved.activeEnvironmentId).isEqualTo("env-id-env-v1");
        assertThat(saved.runnerQueuedRequestIdentityKeys)
                .containsExactlyElementsOf(workspaceState("workspace-v1", "env-v1", false, "queue-v1", "history-v1").runnerQueuedRequestIdentityKeys);
        assertThat(saved.historyEntries).singleElement().extracting(entry -> entry.id).isEqualTo("history-v1");
        assertThat(saved.diagnosticsCaptureEnabled).isFalse();
    }

    @Test
    void loadWaitsForBlockedSaveAndReturnsOnlyRecoverableWholeSnapshots() throws Exception {
        BlockingStringStore store = new BlockingStringStore();
        WorkspaceStateService service = new WorkspaceStateService(store);

        String baseline = workspaceJson("workspace-v3", "env-v3", false, "queue-v3", "history-v3");
        store.seed(baseline);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<WorkspaceState> loaded = new AtomicReference<>();
        Thread writer = new Thread(() -> saveJson(service, workspaceJson("workspace-v4", "env-v4", true, "queue-v4", "history-v4"), failure),
                "workspace-save-v4");
        Thread loader = new Thread(() -> {
            try {
                await(store.firstWriteEntered);
                loaded.set(service.load());
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "workspace-load-during-save");

        writer.start();
        assertThat(store.firstWriteEntered.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        loader.start();

        assertThat(WorkspaceStateJson.fromJson(store.currentValue()).collections)
                .singleElement()
                .extracting(collection -> collection.name)
                .isEqualTo("workspace-v3");

        store.allowFirstWrite.countDown();

        writer.join(THREAD_JOIN_TIMEOUT_MILLIS);
        loader.join(THREAD_JOIN_TIMEOUT_MILLIS);
        assertThat(writer.isAlive()).isFalse();
        assertThat(loader.isAlive()).isFalse();
        assertThat(failure.get()).isNull();

        WorkspaceState loadedState = loaded.get();
        assertThat(loadedState).isNotNull();
        assertThat(loadedState.collections).singleElement().extracting(collection -> collection.name).isEqualTo("workspace-v4");
        assertThat(loadedState.runnerQueuedRequestIdentityKeys)
                .containsExactlyElementsOf(workspaceState("workspace-v4", "env-v4", true, "queue-v4", "history-v4").runnerQueuedRequestIdentityKeys);
        assertThat(store.parsedSnapshots()).allSatisfy(snapshot ->
                assertThat(snapshot.historyEntries).singleElement().extracting(entry -> entry.id).isEqualTo("history-v4"));
    }

    @Test
    void workspaceOwnerSaveIncludesMutationPerformedBeforeEdtSnapshotCapture() throws Exception {
        WorkspaceOwnerFixture fixture = newWorkspaceOwnerFixture();
        try {
            WorkspaceState stateA = workspaceState("workspace-before-a", "env-before-a", false, "queue-a", "history-a");
            WorkspaceState stateB = workspaceState("workspace-before-b", "env-before-b", true, "queue-b", "history-b");

            runOnEdt(() -> fixture.importer.getUI().restoreWorkspaceState(stateA));
            fixture.store.resetWriteTracking();

            runOnEdt(() -> fixture.importer.getUI().restoreWorkspaceState(stateB));
            fixture.importer.requestWorkspaceStateSaveNow();

            WorkspaceState saved = WorkspaceStateJson.fromJson(fixture.store.currentValue());
            assertExactState(saved, stateB);
        } finally {
            fixture.importer.cleanup();
        }
    }

    @Test
    void workspaceOwnerSaveExcludesMutationAppliedAfterSnapshotCapture() throws Exception {
        WorkspaceOwnerFixture fixture = newWorkspaceOwnerFixture();
        try {
            WorkspaceState stateA = workspaceState("workspace-capture-a", "env-capture-a", false, "queue-a", "history-a");
            WorkspaceState stateB = workspaceState("workspace-capture-b", "env-capture-b", true, "queue-b", "history-b");

            runOnEdt(() -> fixture.importer.getUI().restoreWorkspaceState(stateA));
            fixture.store.resetWriteTracking();
            fixture.store.blockNextWrite();

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread saveThread = new Thread(() -> invokeNowSave(fixture.importer, failure), "workspace-owner-save-now");
            saveThread.start();
            await(fixture.store.firstWriteEntered);

            runOnEdt(() -> fixture.importer.getUI().restoreWorkspaceState(stateB));
            fixture.store.allowBlockedWrite();

            saveThread.join(THREAD_JOIN_TIMEOUT_MILLIS);
            assertThat(saveThread.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            settleWorkspaceSaveWork(fixture.importer);

            List<String> writes = fixture.store.writes();
            List<WorkspaceState> snapshots = fixture.store.parsedSnapshots();
            assertThat(writes).isNotEmpty();
            assertThat(writes.get(0)).isEqualTo("workspace-capture-a");
            assertThat(snapshots).isNotEmpty();
            assertExactState(snapshots.get(0), stateA);
            assertObservedSnapshotsAreWholeStates(snapshots, stateA, stateB);
            if (snapshots.size() > 1) {
                assertExactState(snapshots.get(snapshots.size() - 1), stateB);
            }
        } finally {
            fixture.importer.cleanup();
        }
    }

    @Test
    void workspaceOwnerSaveNeverPersistsMixedSnapshotAcrossCompetingStates() throws Exception {
        WorkspaceOwnerFixture fixture = newWorkspaceOwnerFixture();
        try {
            WorkspaceState stateA = workspaceState("workspace-mix-a", "env-mix-a", false, "queue-a", "history-a");
            WorkspaceState stateB = workspaceState("workspace-mix-b", "env-mix-b", true, "queue-b", "history-b");

            runOnEdt(() -> fixture.importer.getUI().restoreWorkspaceState(stateA));
            fixture.store.resetWriteTracking();
            fixture.store.blockNextWrite();

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread saveThread = new Thread(() -> invokeNowSave(fixture.importer, failure), "workspace-owner-mix-save");
            saveThread.start();
            await(fixture.store.firstWriteEntered);

            runOnEdt(() -> fixture.importer.getUI().restoreWorkspaceState(stateB));
            fixture.store.allowBlockedWrite();

            saveThread.join(THREAD_JOIN_TIMEOUT_MILLIS);
            assertThat(saveThread.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            settleWorkspaceSaveWork(fixture.importer);

            List<WorkspaceState> snapshots = fixture.store.parsedSnapshots();
            assertThat(snapshots).isNotEmpty();
            assertObservedSnapshotsAreWholeStates(snapshots, stateA, stateB);
            WorkspaceState saved = WorkspaceStateJson.fromJson(fixture.store.currentValue());
            assertStateMatchesEither(saved, stateA, stateB);
        } finally {
            fixture.importer.cleanup();
        }
    }

    @Test
    void debouncedWorkspaceSaveKeepsOnlyTheLatestCapturedSnapshot() throws Exception {
        WorkspaceOwnerFixture fixture = newWorkspaceOwnerFixture();
        try {
            setDebounceDelay(fixture.importer, 100);

            WorkspaceState stateA = workspaceState("workspace-debounce-a", "env-debounce-a", false, "queue-a", "history-a");
            WorkspaceState stateB = workspaceState("workspace-debounce-b", "env-debounce-b", true, "queue-b", "history-b");
            WorkspaceState stateC = workspaceState("workspace-debounce-c", "env-debounce-c", false, "queue-c", "history-c");

            fixture.store.resetWriteTracking();
            runOnEdt(() -> fixture.importer.getUI().restoreWorkspaceState(stateA));
            requestDebouncedSave(fixture.importer);
            runOnEdt(() -> fixture.importer.getUI().restoreWorkspaceState(stateB));
            requestDebouncedSave(fixture.importer);
            runOnEdt(() -> fixture.importer.getUI().restoreWorkspaceState(stateC));
            requestDebouncedSave(fixture.importer);

            waitForWorkspaceName(fixture.store, "workspace-debounce-c");
            waitForDebounceQuietPeriod();
            WorkspaceState saved = WorkspaceStateJson.fromJson(fixture.store.currentValue());
            assertExactState(saved, stateC);
            assertThat(fixture.store.writes()).isNotEmpty();
            assertThat(fixture.store.writes().get(fixture.store.writes().size() - 1)).isEqualTo("workspace-debounce-c");
        } finally {
            fixture.importer.cleanup();
        }
    }

    @Test
    void cleanupDuringPendingWorkspaceSaveLeavesRecoverableStateAndNoLingeringWorker() throws Exception {
        WorkspaceOwnerFixture fixture = newWorkspaceOwnerFixture();
        try {
            WorkspaceState baseline = workspaceState("workspace-cleanup-a", "env-cleanup-a", false, "queue-a", "history-a");
            WorkspaceState pending = workspaceState("workspace-cleanup-b", "env-cleanup-b", true, "queue-b", "history-b");

            fixture.service.saveJson(WorkspaceStateJson.toJson(baseline));
            fixture.store.seed(WorkspaceStateJson.toJson(baseline));
            runOnEdt(() -> fixture.importer.getUI().restoreWorkspaceState(pending));
            fixture.store.resetWriteTracking();
            fixture.store.blockNextWrite();

            AtomicReference<Throwable> saveFailure = new AtomicReference<>();
            AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();
            Thread saveThread = new Thread(() -> invokeNowSave(fixture.importer, saveFailure), "workspace-owner-pending-save");
            Thread cleanupThread = new Thread(() -> {
                try {
                    fixture.importer.cleanup();
                } catch (Throwable t) {
                    cleanupFailure.compareAndSet(null, t);
                }
            }, "workspace-owner-cleanup");

            saveThread.start();
            await(fixture.store.firstWriteEntered);
            cleanupThread.start();
            fixture.store.allowBlockedWrite();

            saveThread.join(THREAD_JOIN_TIMEOUT_MILLIS);
            cleanupThread.join(THREAD_JOIN_TIMEOUT_MILLIS);
            assertThat(saveThread.isAlive()).isFalse();
            assertThat(cleanupThread.isAlive()).isFalse();
            assertThat(saveFailure.get()).isNull();
            assertThat(cleanupFailure.get()).isNull();
            assertThat(isWorkspaceSaveExecutorTerminated(fixture.importer)).isTrue();

            WorkspaceState recovered = WorkspaceStateJson.fromJson(fixture.store.currentValue());
            assertThat(recovered.collections).isNotEmpty();
            assertThat(recovered.environments).isNotEmpty();
            assertThat(recovered.historyEntries).isNotEmpty();
        } finally {
            if (!isWorkspaceSaveExecutorTerminated(fixture.importer)) {
                fixture.importer.cleanup();
            }
        }
    }

    private static void saveJson(WorkspaceStateService service, String json, AtomicReference<Throwable> failure) {
        try {
            service.saveJson(json);
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private static WorkspaceState workspaceState(String collectionName,
                                                 String environmentName,
                                                 boolean diagnosticsEnabled,
                                                 String queuedRequestKey,
                                                 String historyId) {
        WorkspaceState state = new WorkspaceState();
        state.diagnosticsCaptureEnabled = diagnosticsEnabled;
        state.activeEnvironmentId = "env-id-" + environmentName;

        ApiCollection collection = new ApiCollection();
        collection.name = collectionName;
        collection.description = "snapshot-" + collectionName;
        collection.environment = new LinkedHashMap<>();
        collection.environment.put("base_url", "https://" + collectionName + ".example.test");
        ApiRequest request = new ApiRequest();
        request.id = queuedRequestKey;
        request.name = "Request " + queuedRequestKey;
        request.method = "GET";
        request.url = "https://" + collectionName + ".example.test/" + queuedRequestKey;
        request.path = queuedRequestKey;
        request.sourceCollection = collectionName;
        collection.requests.add(request);
        state.collections.add(collection);

        EnvironmentProfile environment = new EnvironmentProfile();
        environment.id = state.activeEnvironmentId;
        environment.name = environmentName;
        environment.variables.put("token", environmentName + "-token");
        state.environments.add(environment);

        state.runnerQueuedRequestIdentityKeys = new ArrayList<>(List.of(workspaceRequestIdentityKey(collectionName, request)));
        state.historyEntries = new ArrayList<>(List.of(historyEntry(historyId)));
        return state;
    }

    private static String workspaceJson(String collectionName,
                                        String environmentName,
                                        boolean diagnosticsEnabled,
                                        String queuedRequestKey,
                                        String historyId) {
        return WorkspaceStateJson.toJson(workspaceState(collectionName, environmentName, diagnosticsEnabled, queuedRequestKey, historyId));
    }

    private static void mutateWorkspaceState(WorkspaceState state,
                                             String collectionName,
                                             String environmentName,
                                             boolean diagnosticsEnabled,
                                             String queuedRequestKey,
                                             String historyId) {
        state.diagnosticsCaptureEnabled = diagnosticsEnabled;
        state.activeEnvironmentId = "env-id-" + environmentName;
        state.collections.clear();
        ApiCollection collection = new ApiCollection();
        collection.name = collectionName;
        ApiRequest request = new ApiRequest();
        request.id = queuedRequestKey;
        request.name = "Request " + queuedRequestKey;
        request.method = "GET";
        request.url = "https://" + collectionName + ".example.test/" + queuedRequestKey;
        request.path = queuedRequestKey;
        request.sourceCollection = collectionName;
        collection.requests.add(request);
        state.collections.add(collection);
        state.environments.clear();
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.id = state.activeEnvironmentId;
        environment.name = environmentName;
        environment.variables.put("token", environmentName + "-token");
        state.environments.add(environment);
        state.runnerQueuedRequestIdentityKeys.clear();
        state.runnerQueuedRequestIdentityKeys.add(workspaceRequestIdentityKey(collectionName, request));
        state.historyEntries.clear();
        state.historyEntries.add(historyEntry(historyId));
    }

    private static String workspaceRequestIdentityKey(String collectionName, ApiRequest request) {
        return (collectionName != null ? collectionName : "")
                + '\u001F'
                + (request != null && request.id != null && !request.id.isBlank() ? "id=" + request.id.trim() : "");
    }

    private static HistoryEntry historyEntry(String id) {
        HistoryEntry entry = HistoryTestFixtures.copyEntry(
                HistoryTestFixtures.sampleWorkbenchEntry(),
                id,
                Instant.parse("2026-06-20T00:00:00Z"));
        entry.id = id;
        return entry;
    }

    private static void assertExactState(WorkspaceState actual, WorkspaceState expected) {
        assertThat(actual.collections).singleElement().extracting(collection -> collection.name)
                .isEqualTo(expected.collections.get(0).name);
        assertThat(actual.environments).singleElement().extracting(environment -> environment.name)
                .isEqualTo(expected.environments.get(0).name);
        assertThat(actual.activeEnvironmentId).isEqualTo(expected.activeEnvironmentId);
        assertThat(actual.runnerQueuedRequestIdentityKeys).containsExactlyElementsOf(expected.runnerQueuedRequestIdentityKeys);
        assertThat(actual.historyEntries).singleElement().extracting(entry -> entry.id)
                .isEqualTo(expected.historyEntries.get(0).id);
        assertThat(actual.diagnosticsCaptureEnabled).isEqualTo(expected.diagnosticsCaptureEnabled);
    }

    private static void assertStateMatchesEither(WorkspaceState actual, WorkspaceState left, WorkspaceState right) {
        boolean matchesLeft = stateMatches(actual, left);
        boolean matchesRight = stateMatches(actual, right);
        assertThat(matchesLeft || matchesRight).isTrue();
    }

    private static void assertObservedSnapshotsAreWholeStates(List<WorkspaceState> snapshots,
                                                              WorkspaceState left,
                                                              WorkspaceState right) {
        assertThat(snapshots).isNotEmpty();
        snapshots.forEach(snapshot -> {
            assertStateMatchesEither(snapshot, left, right);
            assertStateNotMixed(snapshot, left, right);
        });
    }

    private static void assertStateNotMixed(WorkspaceState actual, WorkspaceState left, WorkspaceState right) {
        String collectionName = actual.collections.get(0).name;
        String environmentName = actual.environments.get(0).name;
        String activeEnvironmentId = actual.activeEnvironmentId;
        String queuedKey = actual.runnerQueuedRequestIdentityKeys.get(0);
        String historyId = actual.historyEntries.get(0).id;
        boolean diagnosticsEnabled = actual.diagnosticsCaptureEnabled;
        assertThat(List.of(left.collections.get(0).name, right.collections.get(0).name)).contains(collectionName);
        assertThat(List.of(left.environments.get(0).name, right.environments.get(0).name)).contains(environmentName);
        assertThat(List.of(left.activeEnvironmentId, right.activeEnvironmentId)).contains(activeEnvironmentId);
        assertThat(List.of(left.runnerQueuedRequestIdentityKeys.get(0), right.runnerQueuedRequestIdentityKeys.get(0))).contains(queuedKey);
        assertThat(List.of(left.historyEntries.get(0).id, right.historyEntries.get(0).id)).contains(historyId);
        assertThat(List.of(left.diagnosticsCaptureEnabled, right.diagnosticsCaptureEnabled)).contains(diagnosticsEnabled);
        assertThat(collectionName.equals(left.collections.get(0).name)).isEqualTo(environmentName.equals(left.environments.get(0).name));
        assertThat(collectionName.equals(left.collections.get(0).name)).isEqualTo(activeEnvironmentId.equals(left.activeEnvironmentId));
        assertThat(collectionName.equals(left.collections.get(0).name)).isEqualTo(queuedKey.equals(left.runnerQueuedRequestIdentityKeys.get(0)));
        assertThat(collectionName.equals(left.collections.get(0).name)).isEqualTo(historyId.equals(left.historyEntries.get(0).id));
        assertThat(collectionName.equals(left.collections.get(0).name)).isEqualTo(diagnosticsEnabled == left.diagnosticsCaptureEnabled);
    }

    private static boolean stateMatches(WorkspaceState actual, WorkspaceState expected) {
        return actual != null
                && actual.collections != null
                && actual.environments != null
                && !actual.collections.isEmpty()
                && !actual.environments.isEmpty()
                && Objects.equals(actual.collections.get(0).name, expected.collections.get(0).name)
                && Objects.equals(actual.environments.get(0).name, expected.environments.get(0).name)
                && Objects.equals(actual.activeEnvironmentId, expected.activeEnvironmentId)
                && Objects.equals(actual.runnerQueuedRequestIdentityKeys, expected.runnerQueuedRequestIdentityKeys)
                && actual.historyEntries != null
                && !actual.historyEntries.isEmpty()
                && Objects.equals(actual.historyEntries.get(0).id, expected.historyEntries.get(0).id)
                && actual.diagnosticsCaptureEnabled == expected.diagnosticsCaptureEnabled;
    }

    private static WorkspaceOwnerFixture newWorkspaceOwnerFixture() {
        BlockingStringStore store = new BlockingStringStore();
        WorkspaceStateService service = new WorkspaceStateService(store);
        UniversalImporter importer = new UniversalImporter(mockApi(), ScriptMode.DISABLED, service);
        return new WorkspaceOwnerFixture(importer, service, store);
    }

    private static MontoyaApi mockApi() {
        MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        when(api.userInterface().createHttpRequestEditor(any(EditorOptions.class))).thenAnswer(invocation -> {
            HttpRequestEditor editor = Mockito.mock(HttpRequestEditor.class);
            when(editor.uiComponent()).thenReturn(new JPanel());
            return editor;
        });
        when(api.userInterface().createHttpResponseEditor(any(EditorOptions.class))).thenAnswer(invocation -> {
            HttpResponseEditor editor = Mockito.mock(HttpResponseEditor.class);
            when(editor.uiComponent()).thenReturn(new JPanel());
            return editor;
        });
        return api;
    }

    private static void requestDebouncedSave(UniversalImporter importer) {
        try {
            Method method = UniversalImporter.class.getDeclaredMethod("requestWorkspaceStateSave");
            method.setAccessible(true);
            method.invoke(importer);
        } catch (Exception e) {
            throw new AssertionError("Failed to request debounced workspace save", e);
        }
    }

    private static void invokeNowSave(UniversalImporter importer, AtomicReference<Throwable> failure) {
        try {
            importer.requestWorkspaceStateSaveNow();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private static void setDebounceDelay(UniversalImporter importer, int delayMs) {
        try {
            Field debouncedField = UniversalImporter.class.getDeclaredField("debouncedWorkspaceSave");
            debouncedField.setAccessible(true);
            Object debounced = debouncedField.get(importer);
            Field timerField = DebouncedSwingAction.class.getDeclaredField("timer");
            timerField.setAccessible(true);
            javax.swing.Timer timer = (javax.swing.Timer) timerField.get(debounced);
            timer.setInitialDelay(delayMs);
            timer.setDelay(delayMs);
        } catch (Exception e) {
            throw new AssertionError("Failed to adjust workspace debounce delay", e);
        }
    }

    private static boolean isWorkspaceSaveExecutorTerminated(UniversalImporter importer) {
        try {
            Method method = UniversalImporter.class.getDeclaredMethod("isWorkspaceSaveExecutorTerminatedForTests");
            method.setAccessible(true);
            return (boolean) method.invoke(importer);
        } catch (Exception e) {
            throw new AssertionError("Failed to inspect workspace save executor state", e);
        }
    }

    private static void settleWorkspaceSaveWork(UniversalImporter importer) {
        try {
            runOnEdt(() -> { });
            Field debouncedField = UniversalImporter.class.getDeclaredField("debouncedWorkspaceSave");
            debouncedField.setAccessible(true);
            DebouncedSwingAction debounced = (DebouncedSwingAction) debouncedField.get(importer);
            runOnEdt(() -> {
                if (debounced != null) {
                    debounced.stop();
                }
            });
            Field executorField = UniversalImporter.class.getDeclaredField("workspaceSaveExecutor");
            executorField.setAccessible(true);
            ExecutorService executor = (ExecutorService) executorField.get(importer);
            if (executor != null && !executor.isShutdown()) {
                Future<?> future = executor.submit(() -> null);
                future.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            runOnEdt(() -> { });
        } catch (Exception e) {
            throw new AssertionError("Failed to settle workspace save work", e);
        }
    }

    private static void waitForWriteCount(BlockingStringStore store, int expectedWrites) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONCURRENCY_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (store.writeCount.get() >= expectedWrites) {
                return;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for workspace save write count", e);
            }
        }
        assertThat(store.writeCount.get()).isGreaterThanOrEqualTo(expectedWrites);
    }

    private static void waitForWorkspaceName(BlockingStringStore store, String expectedWorkspaceName) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONCURRENCY_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            String json = store.currentValue();
            if (json != null && !json.isBlank()) {
                WorkspaceState snapshot = WorkspaceStateJson.fromJson(json);
                if (snapshot.collections != null
                        && !snapshot.collections.isEmpty()
                        && Objects.equals(snapshot.collections.get(0).name, expectedWorkspaceName)) {
                    return;
                }
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for workspace save: " + expectedWorkspaceName, e);
            }
        }
        WorkspaceState snapshot = WorkspaceStateJson.fromJson(store.currentValue());
        assertThat(snapshot.collections).singleElement().extracting(collection -> collection.name).isEqualTo(expectedWorkspaceName);
    }

    private static void waitForDebounceQuietPeriod() {
        try {
            Thread.sleep(250L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for debounce quiet period", e);
        }
    }

    private static void runOnEdt(Runnable action) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
            } else {
                SwingUtilities.invokeAndWait(action);
            }
        } catch (Exception e) {
            throw new AssertionError("EDT mutation failed", e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for concurrent workspace save", e);
        }
    }

    private static final class BlockingStringStore implements WorkspaceStateService.StringStore {
        private final AtomicReference<String> currentValue = new AtomicReference<>();
        private final CopyOnWriteArrayList<String> writeLabels = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkspaceState> parsedSnapshots = new CopyOnWriteArrayList<>();
        private final AtomicInteger writeCount = new AtomicInteger();
        private volatile CountDownLatch firstWriteEntered = new CountDownLatch(1);
        private volatile CountDownLatch allowFirstWrite = new CountDownLatch(0);
        private final AtomicReference<Boolean> firstWriteBlocked = new AtomicReference<>(Boolean.FALSE);

        @Override
        public String get(String key) {
            return currentValue.get();
        }

        @Override
        public void set(String key, String value) {
            if (firstWriteBlocked.compareAndSet(Boolean.FALSE, Boolean.TRUE)) {
                firstWriteEntered.countDown();
                await(allowFirstWrite);
            }
            WorkspaceState parsed = WorkspaceStateJson.fromJson(value);
            parsedSnapshots.add(parsed);
            writeLabels.add(parsed.collections.get(0).name);
            writeCount.incrementAndGet();
            currentValue.set(value);
        }

        void seed(String json) {
            currentValue.set(json);
        }

        String currentValue() {
            return currentValue.get();
        }

        List<String> writes() {
            return new ArrayList<>(writeLabels);
        }

        List<WorkspaceState> parsedSnapshots() {
            return new ArrayList<>(parsedSnapshots);
        }

        void resetWriteTracking() {
            writeLabels.clear();
            parsedSnapshots.clear();
            writeCount.set(0);
            firstWriteBlocked.set(Boolean.FALSE);
            firstWriteEntered = new CountDownLatch(1);
            allowFirstWrite = new CountDownLatch(0);
        }

        void blockNextWrite() {
            firstWriteBlocked.set(Boolean.FALSE);
            firstWriteEntered = new CountDownLatch(1);
            allowFirstWrite = new CountDownLatch(1);
        }

        void allowBlockedWrite() {
            allowFirstWrite.countDown();
        }
    }

    private static final class WorkspaceOwnerFixture {
        private final UniversalImporter importer;
        private final WorkspaceStateService service;
        private final BlockingStringStore store;

        private WorkspaceOwnerFixture(UniversalImporter importer,
                                      WorkspaceStateService service,
                                      BlockingStringStore store) {
            this.importer = importer;
            this.service = service;
            this.store = store;
        }
    }
}
