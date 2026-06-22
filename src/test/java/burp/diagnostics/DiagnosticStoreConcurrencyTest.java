package burp.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticStoreConcurrencyTest {

    @AfterEach
    void tearDown() {
        DiagnosticStore store = DiagnosticStore.getInstance();
        store.setCaptureEnabled(false);
        store.clear();
    }

    @Test
    void concurrentWritersSnapshotsClearsAndCaptureTogglesRemainPassive() throws Exception {
        DiagnosticStore store = DiagnosticStore.getInstance();
        store.clear();
        store.setCaptureEnabled(true);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> writerA = pool.submit(runWriter(store, ready, start, "writer-a", DiagnosticSeverity.INFO));
        Future<?> writerB = pool.submit(runWriter(store, ready, start, "writer-b", DiagnosticSeverity.WARNING));
        Future<?> reader = pool.submit(runReader(store, ready, start));
        Future<?> controller = pool.submit(runController(store, ready, start));

        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            waitForWorker("writer-a", writerA);
            waitForWorker("writer-b", writerB);
            waitForWorker("reader", reader);
            waitForWorker("controller", controller);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS))
                    .as("Timed out waiting for diagnostics workers to terminate after shutdownNow()")
                    .isTrue();
        }

        String report = store.sanitizedReport(true);
        assertThat(report).contains("Diagnostics Events");
        assertThat(report).doesNotContain("secret-token");
        assertThat(store.snapshot()).hasSizeLessThanOrEqualTo(1000);
    }

    @Test
    void oldestEventsAreEvictedWhenMaxCapacityIsExceeded() {
        DiagnosticStore store = DiagnosticStore.getInstance();
        store.setCaptureEnabled(true);

        for (int i = 0; i < 1200; i++) {
            store.record(DiagnosticEvent.of(DiagnosticOperation.REQUEST_BUILD, DiagnosticSeverity.INFO, "test", "event-" + i));
        }

        assertThat(store.snapshot()).hasSize(1000);
        assertThat(store.snapshot().get(0).message).isEqualTo("event-200");
        assertThat(store.snapshot().get(999).message).isEqualTo("event-1199");
    }

    private static Runnable runWriter(DiagnosticStore store,
                                      CountDownLatch ready,
                                      CountDownLatch start,
                                      String prefix,
                                      DiagnosticSeverity severity) {
        return () -> {
            ready.countDown();
            await(start);
            for (int i = 0; i < 400; i++) {
                store.record(DiagnosticEvent.of(DiagnosticOperation.REQUEST_BUILD, severity, prefix, prefix + "-" + i)
                        .withDetails("Authorization: Bearer secret-token\nCookie: session=abc123"));
            }
        };
    }

    private static Runnable runReader(DiagnosticStore store,
                                      CountDownLatch ready,
                                      CountDownLatch start) {
        return () -> {
            ready.countDown();
            await(start);
            for (int i = 0; i < 200; i++) {
                store.snapshot();
                store.sanitizedReport(false);
            }
        };
    }

    private static Runnable runController(DiagnosticStore store,
                                          CountDownLatch ready,
                                          CountDownLatch start) {
        return () -> {
            ready.countDown();
            await(start);
            for (int i = 0; i < 40; i++) {
                store.setCaptureEnabled(i % 2 == 0);
                if (i % 5 == 0) {
                    store.clear();
                }
                store.record(DiagnosticEvent.of(DiagnosticOperation.ENVIRONMENT_SWITCH, DiagnosticSeverity.DEBUG, "controller", "toggle-" + i)
                        .withDetails("access_token=secret-" + i));
            }
            store.setCaptureEnabled(true);
        };
    }

    private static void waitForWorker(String workerName, Future<?> worker) throws Exception {
        try {
            worker.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError(workerName + " did not finish within 20 seconds", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError(workerName + " failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + workerName, e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent diagnostics test start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for concurrent diagnostics test start", e);
        }
    }
}
