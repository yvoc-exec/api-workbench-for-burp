package burp.utils;

import burp.history.HistoryEntry;
import burp.models.ApiCollection;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceConcurrentSaveTest {

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
        assertThat(store.firstWriteEntered.await(5, TimeUnit.SECONDS)).isTrue();
        queuedWriters.start();

        assertThat(store.writes()).isEmpty();
        store.allowFirstWrite.countDown();

        firstWriter.join(5000);
        queuedWriters.join(5000);
        assertThat(firstWriter.isAlive()).isFalse();
        assertThat(queuedWriters.isAlive()).isFalse();
        assertThat(failure.get()).isNull();

        assertThat(store.writes())
                .containsExactly("workspace-v1", "workspace-v2", "workspace-v3", "workspace-v4");

        WorkspaceState saved = WorkspaceStateJson.fromJson(store.currentValue());
        assertThat(saved.collections).singleElement().extracting(collection -> collection.name).isEqualTo("workspace-v4");
        assertThat(saved.environments).singleElement().extracting(environment -> environment.name).isEqualTo("env-v4");
        assertThat(saved.activeEnvironmentId).isEqualTo("env-id-env-v4");
        assertThat(saved.runnerQueuedRequestIdentityKeys).containsExactly("queue-v4");
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
        assertThat(store.firstWriteEntered.await(5, TimeUnit.SECONDS)).isTrue();

        mutateWorkspaceState(state, "workspace-v2", "env-v2", true, "queue-v2", "history-v2");
        store.allowFirstWrite.countDown();

        writer.join(5000);
        assertThat(writer.isAlive()).isFalse();
        assertThat(failure.get()).isNull();

        WorkspaceState saved = WorkspaceStateJson.fromJson(store.currentValue());
        assertThat(saved.collections).singleElement().extracting(collection -> collection.name).isEqualTo("workspace-v1");
        assertThat(saved.environments).singleElement().extracting(environment -> environment.name).isEqualTo("env-v1");
        assertThat(saved.activeEnvironmentId).isEqualTo("env-id-env-v1");
        assertThat(saved.runnerQueuedRequestIdentityKeys).containsExactly("queue-v1");
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
        assertThat(store.firstWriteEntered.await(5, TimeUnit.SECONDS)).isTrue();
        loader.start();

        assertThat(WorkspaceStateJson.fromJson(store.currentValue()).collections)
                .singleElement()
                .extracting(collection -> collection.name)
                .isEqualTo("workspace-v3");

        store.allowFirstWrite.countDown();

        writer.join(5000);
        loader.join(5000);
        assertThat(writer.isAlive()).isFalse();
        assertThat(loader.isAlive()).isFalse();
        assertThat(failure.get()).isNull();

        WorkspaceState loadedState = loaded.get();
        assertThat(loadedState).isNotNull();
        assertThat(loadedState.collections).singleElement().extracting(collection -> collection.name).isEqualTo("workspace-v4");
        assertThat(loadedState.runnerQueuedRequestIdentityKeys).containsExactly("queue-v4");
        assertThat(store.parsedSnapshots()).allSatisfy(snapshot ->
                assertThat(snapshot.historyEntries).singleElement().extracting(entry -> entry.id).isEqualTo("history-v4"));
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
        state.collections.add(collection);

        EnvironmentProfile environment = new EnvironmentProfile();
        environment.id = state.activeEnvironmentId;
        environment.name = environmentName;
        environment.variables.put("token", environmentName + "-token");
        state.environments.add(environment);

        state.runnerQueuedRequestIdentityKeys = new ArrayList<>(List.of(queuedRequestKey));
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
        state.collections.add(collection);
        state.environments.clear();
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.id = state.activeEnvironmentId;
        environment.name = environmentName;
        environment.variables.put("token", environmentName + "-token");
        state.environments.add(environment);
        state.runnerQueuedRequestIdentityKeys.clear();
        state.runnerQueuedRequestIdentityKeys.add(queuedRequestKey);
        state.historyEntries.clear();
        state.historyEntries.add(historyEntry(historyId));
    }

    private static HistoryEntry historyEntry(String id) {
        HistoryEntry entry = HistoryTestFixtures.copyEntry(
                HistoryTestFixtures.sampleWorkbenchEntry(),
                id,
                Instant.parse("2026-06-20T00:00:00Z"));
        entry.id = id;
        return entry;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for concurrent workspace save", e);
        }
    }

    private static final class BlockingStringStore implements WorkspaceStateService.StringStore {
        private final AtomicReference<String> currentValue = new AtomicReference<>();
        private final CopyOnWriteArrayList<String> writeLabels = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkspaceState> parsedSnapshots = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstWriteEntered = new CountDownLatch(1);
        private final CountDownLatch allowFirstWrite = new CountDownLatch(1);
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
    }
}
