package burp.scripts;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraalJsSandboxEngineTimeoutTest {
    @Test
    void infiniteLoopTimesOutWithinBound() throws Exception {
        try (GraalJsSandboxEngine engine = new GraalJsSandboxEngine(150)) {
            long start = System.currentTimeMillis();
            assertThatThrownBy(() -> engine.execute("while (true) {}", Map.of()))
                    .isInstanceOf(GraalJsSandboxEngine.ScriptTimedOutException.class)
                    .hasMessageContaining("Script timed out after 150 ms");
            assertThat(System.currentTimeMillis() - start).isLessThan(2_000);
        }
    }

    @Test
    void timeoutClosesContextAndReleasesWorker() throws Exception {
        try (GraalJsSandboxEngine engine = new GraalJsSandboxEngine(150)) {
            assertThatThrownBy(() -> engine.execute("while (true) {}", Map.of()))
                    .isInstanceOf(GraalJsSandboxEngine.ScriptTimedOutException.class);
            assertThat(engine.execute("1 + 2", Map.of()).toString()).isEqualTo("3");
        }
    }

    @Test
    void explicitCancellationStopsActiveScript() throws Exception {
        try (GraalJsSandboxEngine engine = new GraalJsSandboxEngine(5_000)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> future = executor.submit(() -> {
                try {
                    engine.execute("while (true) {}", Map.of());
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });
            Thread.sleep(100);
            engine.cancelActiveExecutions();
            assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                    .satisfies(t -> assertThat(t).isInstanceOfAny(ExecutionException.class, CancellationException.class));
            executor.shutdownNow();
        }
    }

    @Test
    void laterScriptExecutionStillWorksAfterTimeout() throws Exception {
        try (GraalJsSandboxEngine engine = new GraalJsSandboxEngine(150)) {
            assertThatThrownBy(() -> engine.execute("while (true) {}", Map.of()))
                    .isInstanceOf(GraalJsSandboxEngine.ScriptTimedOutException.class);
            Object result = engine.execute("'ok'", Map.of());
            assertThat(result.toString()).isEqualTo("ok");
        }
    }
}
