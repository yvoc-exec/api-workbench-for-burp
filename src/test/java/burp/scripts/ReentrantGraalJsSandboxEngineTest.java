package burp.scripts;

import org.graalvm.polyglot.HostAccess;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ReentrantGraalJsSandboxEngineTest {

    @Test
    void nestedScriptCallsCompleteWithoutWaitingForParentExecutorWorkers() {
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            try (ReentrantGraalJsSandboxEngine engine = new ReentrantGraalJsSandboxEngine(3_000L)) {
                assertThat(engine.isAvailable()).isTrue();
                AtomicInteger executions = new AtomicInteger();
                NestedBridge bridge = new NestedBridge(engine, executions, 6);

                Object result = engine.execute(
                        "bridge.executeLevel(0);",
                        Map.of("bridge", bridge),
                        3_000L);

                assertThat(result).isEqualTo(6);
                assertThat(executions).hasValue(7);
            }
        });
    }

    public static final class NestedBridge {
        private final ReentrantGraalJsSandboxEngine engine;
        private final AtomicInteger executions;
        private final int terminalLevel;

        public NestedBridge(ReentrantGraalJsSandboxEngine engine,
                            AtomicInteger executions,
                            int terminalLevel) {
            this.engine = engine;
            this.executions = executions;
            this.terminalLevel = terminalLevel;
        }

        @HostAccess.Export
        public int executeLevel(int level) throws Exception {
            executions.incrementAndGet();
            if (level >= terminalLevel) {
                return level;
            }
            Object nested = engine.execute(
                    "bridge.executeLevel(" + (level + 1) + ");",
                    Map.of("bridge", this),
                    3_000L);
            return nested instanceof Number number ? number.intValue() : -1;
        }
    }
}
