package burp.scripts;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Routes script calls made from an active Graal worker through an isolated
 * child engine so nested dependent-request scripts never wait for the same
 * bounded executor that is already running their parent scripts.
 */
final class ReentrantGraalJsSandboxEngine extends GraalJsSandboxEngine {
    private static final String SCRIPT_WORKER_PREFIX = "api-workbench-script-";

    private final long configuredTimeoutMillis;
    private final Set<GraalJsSandboxEngine> nestedEngines = ConcurrentHashMap.newKeySet();
    private final AtomicLong cancellationEpoch = new AtomicLong();
    private volatile boolean closed;

    ReentrantGraalJsSandboxEngine(long timeoutMillis) {
        super(timeoutMillis);
        this.configuredTimeoutMillis = normalizeTimeout(timeoutMillis);
    }

    @Override
    public Object execute(String source, Map<String, Object> bindings) throws Exception {
        return executeNestedAware(source, bindings, null, configuredTimeoutMillis);
    }

    @Override
    public Object execute(String source, Map<String, Object> bindings, long timeoutMillis) throws Exception {
        return executeNestedAware(source, bindings, null, normalizeTimeout(timeoutMillis));
    }

    @Override
    public Object execute(String source,
                          Map<String, Object> bindings,
                          Runnable beforeContextClose) throws Exception {
        return executeNestedAware(source, bindings, beforeContextClose, configuredTimeoutMillis);
    }

    @Override
    public Object execute(String source,
                          Map<String, Object> bindings,
                          Runnable beforeContextClose,
                          long timeoutMillis) throws Exception {
        return executeNestedAware(source, bindings, beforeContextClose, normalizeTimeout(timeoutMillis));
    }

    private Object executeNestedAware(String source,
                                      Map<String, Object> bindings,
                                      Runnable beforeContextClose,
                                      long timeoutMillis) throws Exception {
        if (closed) {
            throw new IllegalStateException("Script engine is closed.");
        }
        if (!isScriptWorker(Thread.currentThread())) {
            return super.execute(source, bindings, beforeContextClose, timeoutMillis);
        }

        long observedCancellationEpoch = cancellationEpoch.get();
        GraalJsSandboxEngine nestedEngine = new GraalJsSandboxEngine(timeoutMillis);
        nestedEngines.add(nestedEngine);
        try {
            if (closed || observedCancellationEpoch != cancellationEpoch.get()) {
                nestedEngine.cancelActiveExecutions();
                throw new ScriptCancelledException();
            }
            return nestedEngine.execute(source, bindings, beforeContextClose, timeoutMillis);
        } finally {
            nestedEngines.remove(nestedEngine);
            nestedEngine.close();
        }
    }

    @Override
    public void cancelActiveExecutions() {
        cancellationEpoch.incrementAndGet();
        super.cancelActiveExecutions();
        for (GraalJsSandboxEngine nestedEngine : nestedEngines) {
            nestedEngine.cancelActiveExecutions();
        }
    }

    @Override
    public void close() {
        closed = true;
        cancelActiveExecutions();
        for (GraalJsSandboxEngine nestedEngine : nestedEngines) {
            nestedEngine.close();
        }
        nestedEngines.clear();
        super.close();
    }

    private static boolean isScriptWorker(Thread thread) {
        return thread != null
                && thread.getName() != null
                && thread.getName().startsWith(SCRIPT_WORKER_PREFIX);
    }

    private static long normalizeTimeout(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
        return Math.max(MIN_TIMEOUT_MILLIS, Math.min(MAX_TIMEOUT_MILLIS, timeoutMillis));
    }
}
