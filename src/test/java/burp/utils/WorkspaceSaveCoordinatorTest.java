package burp.utils;

import burp.models.WorkspaceState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceSaveCoordinatorTest {

    @Test
    void rapidRevisionsKeepOneActiveAndLatestPendingAndCompleteReplacedFutures() throws Exception {
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Long> persisted = new ArrayList<>();
        WorkspaceSaveCoordinator coordinator = new WorkspaceSaveCoordinator("awb-workspace-save-test", (revision, state) -> {
            activeStarted.countDown();
            await(release);
            synchronized (persisted) {
                persisted.add(revision);
            }
            return saved(revision);
        });
        try {
            List<CompletableFuture<WorkspaceSaveResult>> futures = new ArrayList<>();
            futures.add(coordinator.submit(1L, state("revision-1")));
            assertThat(activeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            for (long revision = 2L; revision <= 10L; revision++) {
                futures.add(coordinator.submit(revision, state("revision-" + revision)));
            }

            WorkspaceSaveCoordinator.Metrics blocked = coordinator.metrics();
            assertThat(blocked.activeSnapshotCount()).isEqualTo(1);
            assertThat(blocked.pendingSnapshotCount()).isEqualTo(1);
            assertThat(blocked.maximumSnapshotCountObserved()).isEqualTo(2);
            for (int index = 1; index < 9; index++) {
                assertThat(futures.get(index).join().status())
                        .isEqualTo(WorkspaceSaveResult.Status.SUPERSEDED);
            }

            release.countDown();
            assertThat(coordinator.flushLatest().revision()).isEqualTo(10L);
            assertThat(persisted).containsExactly(1L, 10L);
            assertThat(coordinator.metrics().highestCompletedRevision()).isEqualTo(10L);
        } finally {
            release.countDown();
            coordinator.close();
        }
        assertThat(coordinator.metrics().workerTerminated()).isTrue();
    }

    @Test
    void failureDoesNotBlockLatestRevisionAndStaleRevisionCannotOverwriteIt() {
        List<Long> persisted = new ArrayList<>();
        WorkspaceSaveCoordinator coordinator = new WorkspaceSaveCoordinator("awb-workspace-save-test", (revision, state) -> {
            if (revision == 10L) {
                return new WorkspaceSaveResult(
                        revision, WorkspaceSaveResult.Status.FAILED, 0L, null, "failure");
            }
            persisted.add(revision);
            return saved(revision);
        });
        try {
            assertThat(coordinator.submit(10L, state("ten")).join().status())
                    .isEqualTo(WorkspaceSaveResult.Status.FAILED);
            assertThat(coordinator.submit(11L, state("eleven")).join().status())
                    .isEqualTo(WorkspaceSaveResult.Status.SAVED);
            assertThat(coordinator.submit(10L, state("stale")).join().status())
                    .isEqualTo(WorkspaceSaveResult.Status.SUPERSEDED);
            assertThat(coordinator.flushLatest().revision()).isEqualTo(11L);
            assertThat(persisted).containsExactly(11L);
        } finally {
            coordinator.close();
        }
    }

    @Test
    void closeFlushesLatestRejectsLaterSubmissionsAndTerminatesWorker() {
        AtomicInteger saves = new AtomicInteger();
        WorkspaceSaveCoordinator coordinator = new WorkspaceSaveCoordinator("awb-workspace-save-test", (revision, state) -> {
            saves.incrementAndGet();
            return saved(revision);
        });

        coordinator.submit(1L, state("one"));
        coordinator.close();
        coordinator.close();

        assertThat(saves.get()).isEqualTo(1);
        assertThat(coordinator.submit(2L, state("two")).join().status())
                .isEqualTo(WorkspaceSaveResult.Status.CLOSED);
        assertThat(coordinator.metrics().workerTerminated()).isTrue();
        assertThat(coordinator.metrics().activeSnapshotCount()).isZero();
        assertThat(coordinator.metrics().pendingSnapshotCount()).isZero();
    }

    @Test
    void boundedCloseInterruptsBlockedSaveAndReleasesOwnedSnapshots() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        WorkspaceSaveCoordinator coordinator = new WorkspaceSaveCoordinator(
                "awb-workspace-save-timeout-test",
                (revision, state) -> {
                    started.countDown();
                    try {
                        neverReleased.await();
                        return saved(revision);
                    } catch (InterruptedException expected) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                        return new WorkspaceSaveResult(
                                revision,
                                WorkspaceSaveResult.Status.FAILED,
                                0L,
                                null,
                                "interrupted");
                    }
                });

        CompletableFuture<WorkspaceSaveResult> submitted = coordinator.submit(1L, state("blocked"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        WorkspaceSaveResult closeResult = coordinator.closeWithin(100L, TimeUnit.MILLISECONDS);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(elapsedMillis).isLessThan(2_000L);
        assertThat(closeResult.status()).isEqualTo(WorkspaceSaveResult.Status.TIMED_OUT);
        assertThat(submitted.join().status()).isEqualTo(WorkspaceSaveResult.Status.TIMED_OUT);
        long terminationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (!coordinator.metrics().workerTerminated() && System.nanoTime() < terminationDeadline) {
            Thread.sleep(10L);
        }
        assertThat(interrupted).isTrue();
        assertThat(coordinator.metrics().workerTerminated()).isTrue();
        assertThat(coordinator.metrics().activeSnapshotCount()).isZero();
        assertThat(coordinator.metrics().pendingSnapshotCount()).isZero();
    }
    private static WorkspaceState state(String name) {
        WorkspaceState state = new WorkspaceState();
        state.selectedRequestName = name;
        return state;
    }

    private static WorkspaceSaveResult saved(long revision) {
        return new WorkspaceSaveResult(
                revision, WorkspaceSaveResult.Status.SAVED, 1L, "digest", null);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test latch interrupted", e);
        }
    }
}
