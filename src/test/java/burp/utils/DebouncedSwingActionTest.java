package burp.utils;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DebouncedSwingActionTest {

    @Test
    void rapidRestartRunsOnceAndStopAllowsLaterRestart() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DebouncedSwingAction action = new DebouncedSwingAction(50, calls::incrementAndGet);

        SwingUtilities.invokeAndWait(() -> {
            action.restart();
            action.restart();
            action.restart();
        });
        awaitCount(calls, 1);

        SwingUtilities.invokeAndWait(() -> {
            action.restart();
            action.stop();
        });
        Thread.sleep(100L);
        assertThat(calls.get()).isEqualTo(1);

        SwingUtilities.invokeAndWait(action::restart);
        awaitCount(calls, 2);
    }

    @Test
    void closeWhilePendingPreventsExecutionAndIsPermanentAndIdempotent() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DebouncedSwingAction action = new DebouncedSwingAction(75, calls::incrementAndGet);

        SwingUtilities.invokeAndWait(() -> {
            action.restart();
            action.close();
            action.close();
            action.restart();
        });
        Thread.sleep(150L);

        assertThat(calls.get()).isZero();
        assertThat(action.isClosed()).isTrue();
    }

    private static void awaitCount(AtomicInteger calls, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline && calls.get() < expected) {
            Thread.sleep(10L);
        }
        assertThat(calls.get()).isEqualTo(expected);
    }
}
