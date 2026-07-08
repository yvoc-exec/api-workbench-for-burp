package burp.scripts;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class SandboxedJavaScriptEngine implements AutoCloseable {
    public static final long DEFAULT_TIMEOUT_MILLIS = 5_000L;
    public static final long MIN_TIMEOUT_MILLIS = 100L;
    public static final long MAX_TIMEOUT_MILLIS = 60_000L;

    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final AtomicInteger TIMEOUT_THREAD_ID = new AtomicInteger();

    private final boolean runtimeAvailable;
    private final boolean legacyFallbackAvailable;
    private final String engineName;
    private final String initializationFailure;
    private final String runtimeFailure;
    private final String legacyFallbackFailure;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final ScheduledExecutorService timeoutScheduler;
    private final ThreadLocal<Integer> executionDepth = ThreadLocal.withInitial(() -> 0);
    private final Set<ActiveExecution> activeExecutions = ConcurrentHashMap.newKeySet();
    private volatile long timeoutMillis;

    public SandboxedJavaScriptEngine() {
        this(DEFAULT_TIMEOUT_MILLIS);
    }

    SandboxedJavaScriptEngine(long timeoutMillis) {
        this(timeoutMillis, null);
    }

    SandboxedJavaScriptEngine(long timeoutMillis, ExecutorService executor) {
        this.timeoutMillis = normalizeTimeout(timeoutMillis);
        this.ownsExecutor = executor == null;
        this.executor = executor != null
                ? executor
                : Executors.newFixedThreadPool(
                        Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                        daemonThreadFactory("api-workbench-script-", THREAD_ID));
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(
                daemonThreadFactory("api-workbench-script-timeout-", TIMEOUT_THREAD_ID));
        ProbeOutcome runtimeProbe = probeRuntime();
        this.runtimeAvailable = runtimeProbe.available;
        this.runtimeFailure = runtimeProbe.failure;
        this.legacyFallbackAvailable = false;
        this.legacyFallbackFailure = "Legacy JavaScript fallback disabled because bounded termination cannot be guaranteed.";
        this.engineName = runtimeAvailable ? "Sandboxed JavaScript" : "Unavailable";
        this.initializationFailure = runtimeAvailable ? null : buildInitializationFailure(runtimeFailure, legacyFallbackFailure);
    }

    void setTimeoutMillisForTests(long timeoutMillis) {
        this.timeoutMillis = normalizeTimeout(timeoutMillis);
    }

    public boolean isAvailable() {
        return runtimeAvailable;
    }

    public boolean isRuntimeAvailable() {
        return runtimeAvailable;
    }

    public boolean isLegacyFallbackAvailable() {
        return false;
    }

    public String getEngineName() {
        return engineName;
    }

    public String getInitializationFailure() {
        return initializationFailure;
    }

    public String getRuntimeFailure() {
        return runtimeFailure;
    }

    public String getLegacyFallbackFailure() {
        return legacyFallbackFailure;
    }

    public static boolean isRuntimeValue(Object value) {
        return value instanceof Value;
    }

    public static boolean hasRuntimeMembers(Object value) {
        try {
            return value instanceof Value runtimeValue && runtimeValue.hasMembers();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Iterable<String> runtimeMemberKeys(Object value) {
        try {
            return value instanceof Value runtimeValue && runtimeValue.hasMembers()
                    ? runtimeValue.getMemberKeys()
                    : List.of();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public static Object runtimeMember(Object value, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return value instanceof Value runtimeValue && runtimeValue.hasMembers()
                    ? runtimeValue.getMember(key)
                    : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isRuntimeString(Object value) {
        try {
            return value instanceof Value runtimeValue ? runtimeValue.isString() : value instanceof String;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String runtimeString(Object value) {
        try {
            if (value instanceof Value runtimeValue) {
                return runtimeValue.isString() ? runtimeValue.asString() : null;
            }
            return value != null ? value.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isRuntimeNull(Object value) {
        try {
            return value instanceof Value runtimeValue ? runtimeValue.isNull() : value == null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isRuntimeBoolean(Object value) {
        try {
            return value instanceof Value runtimeValue ? runtimeValue.isBoolean() : value instanceof Boolean;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Boolean runtimeBoolean(Object value) {
        try {
            if (value instanceof Value runtimeValue) {
                return runtimeValue.isBoolean() ? runtimeValue.asBoolean() : null;
            }
            return value instanceof Boolean bool ? bool : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean executeRuntimeCallback(Object value) throws Throwable {
        if (!(value instanceof Value runtimeValue)) {
            return false;
        }
        if (!runtimeValue.canExecute()) {
            return false;
        }
        runtimeValue.execute();
        return true;
    }

    static Object unwrapRuntimeValue(Object value) {
        if (!(value instanceof Value runtimeValue)) {
            return value;
        }
        try {
            if (runtimeValue.isNull()) {
                return null;
            }
            if (runtimeValue.isBoolean()) {
                return runtimeValue.asBoolean();
            }
            if (runtimeValue.isNumber()) {
                try {
                    return runtimeValue.as(Object.class);
                } catch (Exception ignored) {
                    return runtimeValue.asDouble();
                }
            }
            if (runtimeValue.isString()) {
                return runtimeValue.asString();
            }
            return runtimeValue.as(Object.class);
        } catch (Exception ignored) {
            return runtimeValue.toString();
        }
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

    public Object execute(String source,
                          Map<String, Object> bindings,
                          Runnable beforeContextClose,
                          long timeoutMillis) throws Exception {
        if (source == null || source.isBlank()) {
            return null;
        }
        long effectiveTimeout = normalizeTimeout(timeoutMillis);
        if (executionDepth.get() > 0) {
            return executeInline(source, bindings, beforeContextClose, effectiveTimeout);
        }
        return executeSubmitted(source, bindings, beforeContextClose, effectiveTimeout);
    }

    private Object executeSubmitted(String source,
                                    Map<String, Object> bindings,
                                    Runnable beforeContextClose,
                                    long effectiveTimeout) throws Exception {
        ActiveExecution active = new ActiveExecution(effectiveTimeout);
        activeExecutions.add(active);
        Future<Object> future = executor.submit(
                () -> executeActive(source, bindings, active, beforeContextClose));
        active.future = future;
        if (active.cancelled) {
            future.cancel(true);
        }
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
            throw translateExecutionFailure(active, effectiveTimeout, e.getCause());
        } finally {
            active.completed = true;
            activeExecutions.remove(active);
            closeActiveContext(active);
        }
    }

    private Object executeInline(String source,
                                 Map<String, Object> bindings,
                                 Runnable beforeContextClose,
                                 long effectiveTimeout) throws Exception {
        ActiveExecution active = new ActiveExecution(effectiveTimeout);
        activeExecutions.add(active);
        ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(() -> {
            if (active.completed || active.cancelled) {
                return;
            }
            active.timedOut = true;
            closeActiveContext(active);
        }, effectiveTimeout, TimeUnit.MILLISECONDS);
        try {
            return executeActive(source, bindings, active, beforeContextClose);
        } finally {
            active.completed = true;
            timeoutTask.cancel(false);
            activeExecutions.remove(active);
            closeActiveContext(active);
        }
    }

    private Object executeActive(String source,
                                 Map<String, Object> bindings,
                                 ActiveExecution active,
                                 Runnable beforeContextClose) throws Exception {
        active.worker = Thread.currentThread();
        int previousDepth = executionDepth.get();
        executionDepth.set(previousDepth + 1);
        try {
            if (active.cancelled) {
                throw new ScriptCancelledException();
            }
            if (active.timedOut) {
                throw new ScriptTimedOutException(active.timeoutMillis);
            }
            if (runtimeAvailable) {
                return executeWithJavaScriptRuntime(source, bindings, active, beforeContextClose);
            }
            throw new IllegalStateException(buildInitializationFailure(runtimeFailure, legacyFallbackFailure));
        } finally {
            if (previousDepth == 0) {
                executionDepth.remove();
            } else {
                executionDepth.set(previousDepth);
            }
            active.worker = null;
        }
    }

    private Exception translateExecutionFailure(ActiveExecution active,
                                                long effectiveTimeout,
                                                Throwable cause) {
        if (active.cancelled) {
            return new ScriptCancelledException();
        }
        if (active.timedOut) {
            return new ScriptTimedOutException(effectiveTimeout);
        }
        if (cause instanceof Exception exception) {
            return exception;
        }
        return new Exception(cause);
    }

    private Object executeWithJavaScriptRuntime(String source,
                                                Map<String, Object> bindings,
                                                ActiveExecution active,
                                                Runnable beforeContextClose) throws Exception {
        Context context = createJavaScriptContext();
        active.context = context;
        if (active.cancelled) {
            closeActiveContext(active);
            throw new ScriptCancelledException();
        }
        if (active.timedOut) {
            closeActiveContext(active);
            throw new ScriptTimedOutException(active.timeoutMillis);
        }
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

    private Context createJavaScriptContext() {
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

    private ProbeOutcome probeRuntime() {
        try (Context context = createJavaScriptContext()) {
            if (context.getEngine() == null) {
                return ProbeOutcome.unavailable("JavaScript runtime could not be initialized.");
            }
            Value result = context.eval("js", "1 + 1");
            if (!isProbeSuccess(result)) {
                return ProbeOutcome.unavailable(
                        "JavaScript runtime probe returned unexpected result: " + describeValue(result));
            }
            return ProbeOutcome.available();
        } catch (Throwable t) {
            return ProbeOutcome.unavailable("JavaScript runtime initialization failed: " + describeThrowable(t));
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

    private String buildInitializationFailure(String runtimeFailure, String legacyFallbackFailure) {
        StringBuilder sb = new StringBuilder();
        if (runtimeFailure != null && !runtimeFailure.isBlank()) {
            sb.append("JavaScript runtime unavailable: ").append(runtimeFailure.trim());
        }
        if (legacyFallbackFailure != null && !legacyFallbackFailure.isBlank()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("Legacy JavaScript fallback unavailable: ").append(legacyFallbackFailure.trim());
        }
        if (sb.length() == 0) {
            sb.append("No JavaScript runtime available");
        }
        return sb.toString();
    }

    private String extractMessage(PolyglotException exception) {
        if (exception == null) {
            return "Unknown JavaScript runtime error";
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
        timeoutScheduler.shutdownNow();
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    private static long normalizeTimeout(long configuredTimeout) {
        if (configuredTimeout <= 0) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
        return Math.max(MIN_TIMEOUT_MILLIS, Math.min(MAX_TIMEOUT_MILLIS, configuredTimeout));
    }

    private static ThreadFactory daemonThreadFactory(String prefix, AtomicInteger counter) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
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

    private static final class ActiveExecution {
        final long timeoutMillis;
        volatile Context context;
        volatile Future<Object> future;
        volatile Thread worker;
        volatile boolean timedOut;
        volatile boolean cancelled;
        volatile boolean completed;

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
