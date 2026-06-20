package burp.history;

import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryStoreConcurrencyTest {

    @Test
    void exactRetentionBoundaryKeepsNewestKnownWritesUntilIntentionalEviction() {
        HistoryStore store = new HistoryStore();
        store.setRetentionPolicy(new HistoryRetentionPolicy(3));

        store.addEntry(entry("id-1", 1));
        store.addEntry(entry("id-2", 2));
        store.addEntry(entry("id-3", 3));
        store.addEntry(entry("id-4", 4));

        assertThat(ids(store.snapshot())).containsExactly("id-4", "id-3", "id-2");
        assertThat(store.getById("id-1")).isNull();
        assertThat(store.getById("id-3")).isNotNull();

        assertThat(store.removeById("id-3")).isTrue();
        assertThat(store.getById("id-3")).isNull();

        store.addEntry(entry("id-5", 5));
        assertThat(ids(store.snapshot())).containsExactly("id-5", "id-4", "id-2");
    }

    @Test
    void replaceAllProducesWholeStoreReplacementAndSnapshotCopiesStayIsolated() throws Exception {
        HistoryStore store = new HistoryStore();
        store.addEntry(entry("old-1", 1));
        store.addEntry(entry("old-2", 2));

        List<HistoryEntry> before = store.snapshot();
        CountDownLatch writerDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread replaceThread = new Thread(() -> {
            try {
                store.replaceAll(List.of(entry("new-1", 10), entry("new-2", 11), entry("new-3", 12)));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                writerDone.countDown();
            }
        }, "history-replace-all");

        replaceThread.start();
        assertThat(writerDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();

        before.get(0).id = "mutated-snapshot";
        before.clear();

        assertThat(ids(store.snapshot())).containsExactly("new-3", "new-2", "new-1");
        assertThat(store.getById("old-1")).isNull();
        assertThat(store.getById("new-2")).isNotNull();
    }

    @Test
    void retentionPolicyChangesDuringWritesYieldConsistentTrimmedSnapshots() throws Exception {
        HistoryStore store = new HistoryStore();
        store.setRetentionPolicy(new HistoryRetentionPolicy(6));

        CountDownLatch threeWritesApplied = new CountDownLatch(1);
        CountDownLatch policyChanged = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                store.addEntry(entry("id-1", 1));
                store.addEntry(entry("id-2", 2));
                store.addEntry(entry("id-3", 3));
                threeWritesApplied.countDown();
                await(policyChanged);
                store.addEntry(entry("id-4", 4));
                store.addEntry(entry("id-5", 5));
                store.addEntry(entry("id-6", 6));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "history-writer");

        Thread policyThread = new Thread(() -> {
            try {
                await(threeWritesApplied);
                store.setRetentionPolicy(new HistoryRetentionPolicy(3));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                policyChanged.countDown();
            }
        }, "history-policy");

        writer.start();
        policyThread.start();
        writer.join(5000);
        policyThread.join(5000);

        assertThat(writer.isAlive()).isFalse();
        assertThat(policyThread.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(ids(store.snapshot())).containsExactly("id-6", "id-5", "id-4");
    }

    @Test
    void explicitRemovalsAndGetByIdsRemainStableAcrossConcurrentMutation() throws Exception {
        HistoryStore store = new HistoryStore();
        store.replaceAll(List.of(
                entry("id-1", 1),
                entry("id-2", 2),
                entry("id-3", 3),
                entry("id-4", 4),
                entry("id-5", 5)));

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch readerCaptured = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread remover = new Thread(() -> {
            try {
                await(start);
                await(readerCaptured);
                List<HistoryEntry> removed = store.removeByIds(List.of("id-2", "id-4"));
                assertThat(ids(removed)).containsExactlyInAnyOrder("id-4", "id-2");
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "history-remover");

        Thread reader = new Thread(() -> {
            try {
                await(start);
                List<HistoryEntry> selected = store.getByIds(List.of("id-1", "id-2", "id-5"));
                assertThat(ids(selected)).containsExactly("id-5", "id-2", "id-1");
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                readerCaptured.countDown();
            }
        }, "history-reader");

        remover.start();
        reader.start();
        start.countDown();

        remover.join(5000);
        reader.join(5000);
        assertThat(remover.isAlive()).isFalse();
        assertThat(reader.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(ids(store.snapshot())).containsExactly("id-5", "id-3", "id-1");
    }

    @Test
    void snapshotIterationRemainsStableWhileWritersAndRemoversOperate() throws Exception {
        HistoryStore store = new HistoryStore();
        store.setRetentionPolicy(new HistoryRetentionPolicy(10));
        store.replaceAll(List.of(
                entry("id-1", 1),
                entry("id-2", 2),
                entry("id-3", 3),
                entry("id-4", 4),
                entry("id-5", 5)));

        List<HistoryEntry> snapshot = store.snapshot();
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                await(start);
                store.addEntry(entry("id-6", 6));
                store.addEntry(entry("id-7", 7));
                store.addEntry(entry("id-8", 8));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "history-writer");

        Thread remover = new Thread(() -> {
            try {
                await(start);
                store.removeById("id-2");
                store.removeById("id-3");
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "history-remover");

        writer.start();
        remover.start();
        start.countDown();

        List<String> iteratedIds = new ArrayList<>();
        for (HistoryEntry entry : snapshot) {
            iteratedIds.add(entry.id);
        }

        writer.join(5000);
        remover.join(5000);
        assertThat(writer.isAlive()).isFalse();
        assertThat(remover.isAlive()).isFalse();
        assertThat(failure.get()).isNull();

        assertThat(iteratedIds).containsExactly("id-5", "id-4", "id-3", "id-2", "id-1");
        assertThat(ids(snapshot)).containsExactly("id-5", "id-4", "id-3", "id-2", "id-1");
        assertThat(ids(store.snapshot())).containsExactly("id-8", "id-7", "id-6", "id-5", "id-4", "id-1");
    }

    private static HistoryEntry entry(String id, long offsetSeconds) {
        return HistoryTestFixtures.copyEntry(
                HistoryTestFixtures.sampleWorkbenchEntry(),
                id,
                Instant.parse("2026-06-20T00:00:00Z").plusSeconds(offsetSeconds));
    }

    private static List<String> ids(List<HistoryEntry> entries) {
        List<String> ids = new ArrayList<>();
        if (entries == null) {
            return ids;
        }
        for (HistoryEntry entry : entries) {
            ids.add(entry.id);
        }
        return ids;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for history concurrency latch", e);
        }
    }
}
