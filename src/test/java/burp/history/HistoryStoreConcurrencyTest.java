package burp.history;

import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryStoreConcurrencyTest {

    @Test
    void concurrentAppendSnapshotLookupAndRemovalRemainStableUnderRetention() throws Exception {
        HistoryStore store = new HistoryStore();
        store.setRetentionPolicy(new HistoryRetentionPolicy(10));

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        pool.submit(runWriter(store, ready, start, failure));
        pool.submit(runReader(store, ready, start, failure));
        pool.submit(runRemover(store, ready, start, failure));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(failure.get()).isNull();

        List<HistoryEntry> snapshot = store.snapshot();
        assertThat(snapshot).hasSizeLessThanOrEqualTo(10);
        assertThat(snapshot).isSortedAccordingTo(Comparator.comparing((HistoryEntry entry) -> entry.timestamp).reversed());
        assertThat(snapshot).extracting(entry -> entry.id).doesNotHaveDuplicates();
    }

    private static Runnable runWriter(HistoryStore store,
                                      CountDownLatch ready,
                                      CountDownLatch start,
                                      AtomicReference<Throwable> failure) {
        return () -> {
            ready.countDown();
            await(start);
            try {
                Instant base = Instant.parse("2026-06-15T02:00:00Z");
                for (int i = 0; i < 100; i++) {
                    store.addEntry(HistoryTestFixtures.copyEntry(
                            HistoryTestFixtures.sampleWorkbenchEntry(),
                            "writer-" + i,
                            base.plusMillis(i)));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        };
    }

    private static Runnable runReader(HistoryStore store,
                                      CountDownLatch ready,
                                      CountDownLatch start,
                                      AtomicReference<Throwable> failure) {
        return () -> {
            ready.countDown();
            await(start);
            try {
                for (int i = 0; i < 200; i++) {
                    store.snapshot();
                    store.getById("writer-50");
                    store.getByIds(List.of("writer-1", "writer-2", "writer-3"));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        };
    }

    private static Runnable runRemover(HistoryStore store,
                                       CountDownLatch ready,
                                       CountDownLatch start,
                                       AtomicReference<Throwable> failure) {
        return () -> {
            ready.countDown();
            await(start);
            try {
                for (int i = 0; i < 20; i++) {
                    store.removeById("writer-" + i);
                    store.removeByIds(List.of("writer-" + (i + 1), "writer-" + (i + 2)));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for test start", e);
        }
    }
}
