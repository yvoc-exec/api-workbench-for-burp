package burp.scripts;

import java.util.concurrent.ExecutorService;

/**
 * Named runtime variant retained for compatibility with the unified runtime.
 * Reentrant execution, timeout enforcement, and cancellation are implemented
 * by the base sandbox engine.
 */
final class ReentrantSandboxedJavaScriptEngine extends SandboxedJavaScriptEngine {
    ReentrantSandboxedJavaScriptEngine(long timeoutMillis) {
        super(timeoutMillis);
    }

    ReentrantSandboxedJavaScriptEngine(long timeoutMillis, ExecutorService executor) {
        super(timeoutMillis, executor);
    }
}
