package burp.utils;

import burp.models.WorkspaceState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class WorkspaceSaveCoordinator implements AutoCloseable {
    private static final long CLOSE_TIMEOUT_SECONDS = 10L;

    @FunctionalInterface
    public interface SaveOperation {
        WorkspaceSaveResult save(long revision, WorkspaceState detached);
    }

    public record Metrics(
            int activeSnapshotCount,
            int pendingSnapshotCount,
            int maximumSnapshotCountObserved,
            long highestSubmittedRevision,
            long highestCompletedRevision,
            boolean workerTerminated) {
    }

    private final Object lock = new Object();
    private final SaveOperation saveOperation;
    private final ExecutorService executor;
    private PendingSave active;
    private PendingSave pending;
    private CompletableFuture<WorkspaceSaveResult> latestFuture;
    private long highestSubmittedRevision = -1L;
    private long highestCompletedRevision = -1L;
    private int maximumSnapshotCountObserved;
    private boolean drainScheduled;
    private boolean closed;
    private WorkspaceSaveResult closeResult;

    public WorkspaceSaveCoordinator(String threadNamePrefix, SaveOperation saveOperation) {
        if (saveOperation == null) {
            throw new IllegalArgumentException("Workspace save operation is required.");
        }
        this.saveOperation = saveOperation;
        String prefix = threadNamePrefix == null || threadNamePrefix.isBlank()
                ? "awb-workspace-save"
                : threadNamePrefix;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread worker = new Thread(runnable, prefix + "-1");
            worker.setDaemon(true);
            return worker;
        });
    }

    public CompletableFuture<WorkspaceSaveResult> submit(long revision, WorkspaceState detached) {
        if (detached == null) {
            return CompletableFuture.completedFuture(result(
                    revision, WorkspaceSaveResult.Status.FAILED, "Detached workspace is required."));
        }
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.completedFuture(result(
                        revision, WorkspaceSaveResult.Status.CLOSED, "Workspace save coordinator is closed."));
            }
            if (revision <= highestSubmittedRevision) {
                return CompletableFuture.completedFuture(result(
                        revision, WorkspaceSaveResult.Status.SUPERSEDED, "Workspace revision is stale."));
            }

            PendingSave accepted = new PendingSave(revision, detached);
            highestSubmittedRevision = revision;
            latestFuture = accepted.future;
            if (active == null) {
                active = accepted;
                scheduleDrainLocked();
            } else {
                PendingSave replaced = pending;
                pending = accepted;
                if (replaced != null) {
                    replaced.release();
                    replaced.future.complete(result(
                            replaced.revision,
                            WorkspaceSaveResult.Status.SUPERSEDED,
                            "Workspace revision was replaced by a newer revision."));
                }
            }
            maximumSnapshotCountObserved = Math.max(
                    maximumSnapshotCountObserved,
                    (active != null ? 1 : 0) + (pending != null ? 1 : 0));
            return accepted.future;
        }
    }

    public WorkspaceSaveResult flushLatest() {
        return flushLatest(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public WorkspaceSaveResult flushLatest(long timeout, TimeUnit unit) {
        CompletableFuture<WorkspaceSaveResult> future;
        long revision;
        synchronized (lock) {
            future = latestFuture;
            revision = Math.max(0L, highestSubmittedRevision);
        }
        return await(future, revision, deadlineAfter(timeout, unit));
    }

    @Override
    public void close() {
        closeWithin(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public WorkspaceSaveResult closeWithin(long timeout, TimeUnit unit) {
        long deadline = deadlineAfter(timeout, unit);
        CompletableFuture<WorkspaceSaveResult> future;
        long revision;
        synchronized (lock) {
            if (closed) {
                return closeResult != null
                        ? closeResult
                        : result(Math.max(0L, highestSubmittedRevision), WorkspaceSaveResult.Status.CLOSED, null);
            }
            closed = true;
            future = latestFuture;
            revision = Math.max(0L, highestSubmittedRevision);
        }

        WorkspaceSaveResult closeOutcome = await(future, revision, deadline);
        boolean timedOut = closeOutcome.status() == WorkspaceSaveResult.Status.TIMED_OUT;
        if (timedOut) {
            abortOutstanding("Workspace save timed out during shutdown.");
        } else {
            executor.shutdown();
        }

        long remaining = remainingNanos(deadline);
        try {
            if (remaining > 0L && !executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                timedOut = true;
            } else if (remaining == 0L && !executor.isTerminated()) {
                timedOut = true;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            timedOut = true;
        }
        if (timedOut && !executor.isTerminated()) {
            abortOutstanding("Workspace save timed out during shutdown.");
            closeOutcome = result(revision, WorkspaceSaveResult.Status.TIMED_OUT,
                    "Workspace save timed out during shutdown.");
        }

        synchronized (lock) {
            closeResult = closeOutcome;
        }
        return closeOutcome;
    }

    private WorkspaceSaveResult await(
            CompletableFuture<WorkspaceSaveResult> future,
            long revision,
            long deadline) {
        if (future == null) {
            return result(revision, WorkspaceSaveResult.Status.UNCHANGED, null);
        }
        long remaining = remainingNanos(deadline);
        if (remaining == 0L) {
            return result(revision, WorkspaceSaveResult.Status.TIMED_OUT,
                    "Workspace save timed out.");
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            return result(revision, WorkspaceSaveResult.Status.TIMED_OUT,
                    "Workspace save timed out.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return result(revision, WorkspaceSaveResult.Status.TIMED_OUT,
                    "Workspace save wait was interrupted.");
        } catch (ExecutionException failed) {
            return result(revision, WorkspaceSaveResult.Status.FAILED,
                    "Workspace save operation failed.");
        }
    }

    private void abortOutstanding(String reason) {
        List<PendingSave> abandoned = new ArrayList<>(2);
        synchronized (lock) {
            if (active != null) {
                abandoned.add(active);
                active = null;
            }
            if (pending != null) {
                abandoned.add(pending);
                pending = null;
            }
            drainScheduled = false;
        }
        for (PendingSave save : abandoned) {
            save.release();
            save.future.complete(result(save.revision, WorkspaceSaveResult.Status.TIMED_OUT, reason));
        }
        if (!executor.isTerminated()) {
            executor.shutdownNow();
        }
    }

    private static long deadlineAfter(long timeout, TimeUnit unit) {
        if (unit == null || timeout <= 0L) {
            return System.nanoTime();
        }
        long now = System.nanoTime();
        long nanos = unit.toNanos(timeout);
        return nanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + nanos;
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0L, deadline - System.nanoTime());
    }

    public Metrics metrics() {
        synchronized (lock) {
            return new Metrics(
                    active != null ? 1 : 0,
                    pending != null ? 1 : 0,
                    maximumSnapshotCountObserved,
                    highestSubmittedRevision,
                    highestCompletedRevision,
                    executor.isTerminated());
        }
    }

    private void scheduleDrainLocked() {
        if (!drainScheduled) {
            drainScheduled = true;
            executor.execute(this::drain);
        }
    }

    private void drain() {
        while (true) {
            PendingSave current;
            synchronized (lock) {
                current = active;
                if (current == null) {
                    drainScheduled = false;
                    return;
                }
            }

            WorkspaceSaveResult saveResult;
            try {
                saveResult = saveOperation.save(current.revision, current.workspace);
                if (saveResult == null) {
                    saveResult = result(
                            current.revision,
                            WorkspaceSaveResult.Status.FAILED,
                            "Workspace save operation returned no result.");
                }
            } catch (RuntimeException e) {
                saveResult = result(
                        current.revision,
                        WorkspaceSaveResult.Status.FAILED,
                        "Workspace save operation failed.");
            }

            synchronized (lock) {
                current.release();
                highestCompletedRevision = Math.max(highestCompletedRevision, current.revision);
                active = pending;
                pending = null;
                if (active == null) {
                    drainScheduled = false;
                }
            }
            current.future.complete(saveResult);
            synchronized (lock) {
                if (active == null) {
                    return;
                }
            }
        }
    }

    private static WorkspaceSaveResult result(
            long revision,
            WorkspaceSaveResult.Status status,
            String failureReason) {
        return new WorkspaceSaveResult(revision, status, 0L, null, failureReason);
    }

    private static final class PendingSave {
        private final long revision;
        private final CompletableFuture<WorkspaceSaveResult> future = new CompletableFuture<>();
        private WorkspaceState workspace;

        private PendingSave(long revision, WorkspaceState workspace) {
            this.revision = revision;
            this.workspace = workspace;
        }

        private void release() {
            workspace = null;
        }
    }
}
