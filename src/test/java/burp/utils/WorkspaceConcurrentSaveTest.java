package burp.utils;

import burp.models.ApiCollection;
import burp.models.WorkspaceState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceConcurrentSaveTest {

    @Test
    void concurrentSaveJsonCallsPersistAConsistentWorkspaceSnapshot() throws Exception {
        Map<String, String> backing = new ConcurrentHashMap<>();
        WorkspaceStateService service = new WorkspaceStateService(new WorkspaceStateService.StringStore() {
            @Override
            public String get(String key) {
                return backing.get(key);
            }

            @Override
            public void set(String key, String value) {
                backing.put(key, value);
            }
        });

        WorkspaceState state = new WorkspaceState();
        state.diagnosticsCaptureEnabled = true;
        ApiCollection collection = new ApiCollection();
        collection.name = "Concurrent";
        state.collections.add(collection);
        String json = WorkspaceStateJson.toJson(state);

        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < workers; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    await(start);
                    service.saveJson(json);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();

        String saved = backing.get("api_workbench_workspace_state_json");
        assertThat(saved).isEqualTo(json);

        WorkspaceState parsed = WorkspaceStateJson.fromJson(saved);
        assertThat(parsed.collections).hasSize(1);
        assertThat(parsed.collections.get(0).name).isEqualTo("Concurrent");
        assertThat(parsed.diagnosticsCaptureEnabled).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent workspace save start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for concurrent workspace save start", e);
        }
    }
}
