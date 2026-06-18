package burp.scripts;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.openjdk.nashorn.api.scripting.ClassFilter;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class GraalJsSandboxEngine {
    private final boolean graalAvailable;
    private final boolean nashornFallbackAvailable;
    private final String engineName;
    private final String initializationFailure;
    private final String graalFailure;
    private final String nashornFailure;

    public GraalJsSandboxEngine() {
        ProbeOutcome graalProbe = probeGraalJs();
        this.graalAvailable = graalProbe.available;
        this.graalFailure = graalProbe.failure;
        ProbeOutcome nashornProbe = graalAvailable ? ProbeOutcome.unavailable(null) : probeNashornFallback();
        this.nashornFallbackAvailable = !graalAvailable && nashornProbe.available;
        this.nashornFailure = nashornProbe.failure;
        if (graalAvailable) {
            this.engineName = "GraalJS";
        } else if (nashornFallbackAvailable) {
            this.engineName = "Nashorn fallback";
        } else {
            this.engineName = "Unavailable";
        }
        this.initializationFailure = graalAvailable
                ? null
                : buildInitializationFailure(graalFailure, nashornFailure);
    }

    public boolean isAvailable() {
        return graalAvailable || nashornFallbackAvailable;
    }

    public boolean isGraalAvailable() {
        return graalAvailable;
    }

    public boolean isNashornFallbackAvailable() {
        return nashornFallbackAvailable;
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
        if (source == null || source.isBlank()) {
            return null;
        }
        if (graalAvailable) {
            return executeWithGraal(source, bindings);
        }
        if (nashornFallbackAvailable) {
            return executeWithNashorn(source, bindings);
        }
        throw new IllegalStateException(buildInitializationFailure(graalFailure, nashornFailure));
    }

    private Object executeWithGraal(String source, Map<String, Object> bindings) throws Exception {
        try (Context context = createGraalContext()) {
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
            return result == null || result.isNull() ? null : result.as(Object.class);
        } catch (PolyglotException e) {
            throw new Exception(extractMessage(e), e);
        }
    }

    private Object executeWithNashorn(String source, Map<String, Object> bindings) throws Exception {
        javax.script.ScriptEngine engine = createNashornEngine();
        if (engine == null) {
            throw new IllegalStateException("No JavaScript engine available");
        }
        if (bindings != null) {
            for (Map.Entry<String, Object> entry : bindings.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                engine.put(entry.getKey(), entry.getValue());
            }
        }
        return engine.eval(source);
    }

    private Context createGraalContext() {
        return Context.newBuilder("js")
                .allowHostAccess(HostAccess.newBuilder(HostAccess.NONE)
                        .allowAccessAnnotatedBy(HostAccess.Export.class)
                        .build())
                .allowHostClassLookup(className -> false)
                .allowIO(false)
                .allowCreateThread(false)
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

    private ProbeOutcome probeNashornFallback() {
        try {
            javax.script.ScriptEngine engine = createNashornEngine();
            if (engine == null) {
                return ProbeOutcome.unavailable("No Nashorn or JavaScript engine found");
            }
            Object result = engine.eval("1 + 1");
            if (!"2".equals(String.valueOf(result))) {
                return ProbeOutcome.unavailable("Nashorn eval returned unexpected result: " + result);
            }
            return ProbeOutcome.available();
        } catch (Throwable t) {
            return ProbeOutcome.unavailable("Nashorn fallback initialization failed: " + describeThrowable(t));
        }
    }

    private javax.script.ScriptEngine createNashornEngine() {
        try {
            javax.script.ScriptEngineManager manager = new javax.script.ScriptEngineManager();
            javax.script.ScriptEngine engine = manager.getEngineByName("nashorn");
            if (engine == null) {
                engine = manager.getEngineByName("javascript");
            }
            if (engine != null) {
                return engine;
            }
            NashornScriptEngineFactory factory = new NashornScriptEngineFactory();
            return factory.getScriptEngine(new DenyAllClassFilter());
        } catch (Throwable ignored) {
            return null;
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

    private static final class DenyAllClassFilter implements ClassFilter {
        @Override
        public boolean exposeToScripts(String s) {
            return false;
        }
    }
}
