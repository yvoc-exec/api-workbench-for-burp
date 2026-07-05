package burp.scripts;

import java.util.concurrent.ExecutorService;

/**
 * Named runtime variant retained for compatibility with the unified runtime.
 * Reentrant execution, timeout enforcement, and cancellation are implemented
 * by the base sandbox engine.
 */
final class ReentrantGraalJsSandboxEngine extends GraalJsSandboxEngine {
    ReentrantGraalJsSandboxEngine(long timeoutMillis) {
        super(timeoutMillis);
    }

    ReentrantGraalJsSandboxEngine(long timeoutMillis, ExecutorService executor) {
        super(timeoutMillis, executor);
    }
}
