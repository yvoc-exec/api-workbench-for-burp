package burp.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        AtomicReference<Throwable> failure = new AtomicReference<>();

        pool.submit(runWriter(store, ready, start, failure, "writer-a", DiagnosticSeverity.INFO));
        pool.submit(runWriter(store, ready, start, failure, "writer-b", DiagnosticSeverity.WARNING));
        pool.submit(runReader(store, ready, start, failure));
        pool.submit(runController(store, ready, start, failure));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();

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
                                      AtomicReference<Throwable> failure,
                                      String prefix,
                                      DiagnosticSeverity severity) {
        return () -> {
            ready.countDown();
            await(start);
            try {
                for (int i = 0; i < 400; i++) {
                    store.record(DiagnosticEvent.of(DiagnosticOperation.REQUEST_BUILD, severity, prefix, prefix + "-" + i)
                            .withDetails("Authorization: Bearer secret-token\nCookie: session=abc123"));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        };
    }

    private static Runnable runReader(DiagnosticStore store,
                                      CountDownLatch ready,
                                      CountDownLatch start,
                                      AtomicReference<Throwable> failure) {
        return () -> {
            ready.countDown();
            await(start);
            try {
                for (int i = 0; i < 200; i++) {
                    store.snapshot();
                    store.sanitizedReport(false);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        };
    }

    private static Runnable runController(DiagnosticStore store,
                                          CountDownLatch ready,
                                          CountDownLatch start,
                                          AtomicReference<Throwable> failure) {
        return () -> {
            ready.countDown();
            await(start);
            try {
                for (int i = 0; i < 40; i++) {
                    store.setCaptureEnabled(i % 2 == 0);
                    if (i % 5 == 0) {
                        store.clear();
                    }
                    store.record(DiagnosticEvent.of(DiagnosticOperation.ENVIRONMENT_SWITCH, DiagnosticSeverity.DEBUG, "controller", "toggle-" + i)
                            .withDetails("access_token=secret-" + i));
                }
                store.setCaptureEnabled(true);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        };
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
