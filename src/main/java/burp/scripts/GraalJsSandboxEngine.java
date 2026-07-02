package burp.scripts;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class GraalJsSandboxEngine implements AutoCloseable {
    public static final long DEFAULT_TIMEOUT_MILLIS = 5_000L;
    public static final long MIN_TIMEOUT_MILLIS = 100L;
    public static final long MAX_TIMEOUT_MILLIS = 60_000L;

    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private final boolean graalAvailable;
    private final boolean nashornFallbackAvailable;
    private final String engineName;
    private final String initializationFailure;
    private final String graalFailure;
    private final String nashornFailure;
    private final ExecutorService executor;
    private final Set<ActiveExecution> activeExecutions = ConcurrentHashMap.newKeySet();
    private volatile long timeoutMillis;

    public GraalJsSandboxEngine() {
        this(DEFAULT_TIMEOUT_MILLIS);
    }

    GraalJsSandboxEngine(long timeoutMillis) {
        this.timeoutMillis = normalizeTimeout(timeoutMillis);
        this.executor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2), daemonThreadFactory());
        ProbeOutcome graalProbe = probeGraalJs();
        this.graalAvailable = graalProbe.available;
        this.graalFailure = graalProbe.failure;
        this.nashornFallbackAvailable = false;
        this.nashornFailure = "Nashorn fallback disabled because bounded termination cannot be guaranteed.";
        this.engineName = graalAvailable ? "GraalJS" : "Unavailable";
        this.initializationFailure = graalAvailable ? null : buildInitializationFailure(graalFailure, nashornFailure);
    }

    void setTimeoutMillisForTests(long timeoutMillis) {
        this.timeoutMillis = normalizeTimeout(timeoutMillis);
    }

    public boolean isAvailable() {
        return graalAvailable;
    }

    public boolean isGraalAvailable() {
        return graalAvailable;
    }

    public boolean isNashornFallbackAvailable() {
        return false;
    }

    public String getEngineName() {
        return engineName;
    }

    public String getInitializationFailure() {
        return initializationFailure;
    }

    public String getGraalFailure() {
        return graalFailure;
    }

    public String getNashornFailure() {
        return nashornFailure;
    }

    public Object execute(String source, Map<String, Object> bindings) throws Exception {
        return execute(source, bindings, null, timeoutMillis);
    }

    public Object execute(String source, Map<String, Object> bindings, long timeoutMillis) throws Exception {
        return execute(source, bindings, null, timeoutMillis);
    }

    public Object execute(String source, Map<String, Object> bindings, Runnable beforeContextClose) throws Exception {
        return execute(source, bindings, beforeContextClose, timeoutMillis);
    }

    public Object execute(String source, Map<String, Object> bindings, Runnable beforeContextClose, long timeoutMillis) throws Exception {
        if (source == null || source.isBlank()) {
            return null;
        }
        long effectiveTimeout = normalizeTimeout(timeoutMillis);
        ActiveExecution active = new ActiveExecution(effectiveTimeout);
        Future<Object> future = executor.submit(() -> {
            active.worker = Thread.currentThread();
            try {
                if (graalAvailable) {
                    return executeWithGraal(source, bindings, active, beforeContextClose);
                }
                throw new IllegalStateException(buildInitializationFailure(graalFailure, nashornFailure));
            } finally {
                active.worker = null;
            }
        });
        active.future = future;
        activeExecutions.add(active);
        try {
            return future.get(effectiveTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            active.timedOut = true;
            closeActiveContext(active);
            future.cancel(true);
            throw new ScriptTimedOutException(effectiveTimeout);
        } catch (CancellationException e) {
            throw new ScriptCancelledException();
        } catch (InterruptedException e) {
            active.cancelled = true;
            closeActiveContext(active);
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ScriptCancelledException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (active.cancelled) {
                throw new ScriptCancelledException();
            }
            if (active.timedOut) {
                throw new ScriptTimedOutException(effectiveTimeout);
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new Exception(cause);
        } finally {
            activeExecutions.remove(active);
            closeActiveContext(active);
        }
    }

    private Object executeWithGraal(String source,
                                    Map<String, Object> bindings,
                                    ActiveExecution active,
                                    Runnable beforeContextClose) throws Exception {
        Context context = createGraalContext();
        active.context = context;
        try {
            if (bindings != null) {
                Value jsBindings = context.getBindings("js");
                for (Map.Entry<String, Object> entry : bindings.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank()) {
                        continue;
                    }
                    jsBindings.putMember(entry.getKey(), entry.getValue());
                }
            }
            Value result = context.eval("js", source);
            if (beforeContextClose != null) {
                beforeContextClose.run();
            }
            return result == null || result.isNull() ? null : result.as(Object.class);
        } catch (PolyglotException e) {
            if (active.cancelled) {
                throw new ScriptCancelledException();
            }
            if (active.timedOut) {
                throw new ScriptTimedOutException(active.timeoutMillis);
            }
            throw new Exception(extractMessage(e), e);
        } finally {
            try {
                context.close(false);
            } catch (Throwable ignored) {
            } finally {
                active.context = null;
            }
        }
    }

    private Context createGraalContext() {
        return Context.newBuilder("js")
                .allowHostAccess(HostAccess.newBuilder(HostAccess.NONE)
                        .allowAccessAnnotatedBy(HostAccess.Export.class)
                        .build())
                .allowHostClassLookup(className -> false)
                .allowIO(false)
                .allowCreateThread(false)
                .option("engine.WarnInterpreterOnly", "false")
                .option("js.ecmascript-version", "2023")
                .build();
    }

    private ProbeOutcome probeGraalJs() {
        try (Context context = createGraalContext()) {
            if (context.getEngine() == null) {
                return ProbeOutcome.unavailable("GraalJS engine could not be initialized.");
            }
            Value result = context.eval("js", "1 + 1");
            if (!isProbeSuccess(result)) {
                return ProbeOutcome.unavailable("GraalJS probe returned unexpected result: " + describeValue(result));
            }
            return ProbeOutcome.available();
        } catch (Throwable t) {
            return ProbeOutcome.unavailable("GraalJS initialization failed: " + describeThrowable(t));
        }
    }

    private boolean isProbeSuccess(Value value) {
        try {
            return value != null && value.fitsInInt() && value.asInt() == 2;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String describeValue(Value value) {
        if (value == null) {
            return "null";
        }
        try {
            if (value.isNull()) {
                return "null";
            }
            if (value.fitsInInt()) {
                return Integer.toString(value.asInt());
            }
            Object asObject = value.as(Object.class);
            return String.valueOf(asObject);
        } catch (Throwable t) {
            return value.getMetaObject() != null ? value.getMetaObject().toString() : value.toString();
        }
    }

    private String describeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        return message;
    }

    private String buildInitializationFailure(String graalFailure, String nashornFailure) {
        StringBuilder sb = new StringBuilder();
        if (graalFailure != null && !graalFailure.isBlank()) {
            sb.append("GraalJS unavailable: ").append(graalFailure.trim());
        }
        if (nashornFailure != null && !nashornFailure.isBlank()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("Nashorn fallback unavailable: ").append(nashornFailure.trim());
        }
        if (sb.length() == 0) {
            sb.append("No JavaScript runtime available");
        }
        return sb.toString();
    }

    private static final class ProbeOutcome {
        final boolean available;
        final String failure;

        private ProbeOutcome(boolean available, String failure) {
            this.available = available;
            this.failure = failure;
        }

        static ProbeOutcome available() {
            return new ProbeOutcome(true, null);
        }

        static ProbeOutcome unavailable(String failure) {
            return new ProbeOutcome(false, failure);
        }
    }

    private String extractMessage(PolyglotException exception) {
        if (exception == null) {
            return "Unknown GraalJS error";
        }
        String message = exception.getMessage();
        return message != null && !message.isBlank()
                ? message
                : exception.getClass().getSimpleName();
    }

    public void cancelActiveExecutions() {
        for (ActiveExecution active : activeExecutions) {
            active.cancelled = true;
            closeActiveContext(active);
            Future<Object> future = active.future;
            if (future != null) {
                future.cancel(true);
            }
            Thread worker = active.worker;
            if (worker != null) {
                worker.interrupt();
            }
        }
    }

    @Override
    public void close() {
        cancelActiveExecutions();
        executor.shutdownNow();
    }

    private static long normalizeTimeout(long configuredTimeout) {
        if (configuredTimeout <= 0) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
        return Math.max(MIN_TIMEOUT_MILLIS, Math.min(MAX_TIMEOUT_MILLIS, configuredTimeout));
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "api-workbench-script-" + THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void closeActiveContext(ActiveExecution active) {
        if (active == null || active.context == null) {
            return;
        }
        try {
            active.context.close(true);
        } catch (Throwable ignored) {
        }
    }

    private static final class ActiveExecution {
        final long timeoutMillis;
        volatile Context context;
        volatile Future<Object> future;
        volatile Thread worker;
        volatile boolean timedOut;
        volatile boolean cancelled;

        ActiveExecution(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }
    }

    public static class ScriptTimedOutException extends Exception {
        public final long timeoutMillis;

        ScriptTimedOutException(long timeoutMillis) {
            super("Script timed out after " + timeoutMillis + " ms");
            this.timeoutMillis = timeoutMillis;
        }
    }

    public static class ScriptCancelledException extends Exception {
        ScriptCancelledException() {
            super("Script execution cancelled");
        }
    }
}
