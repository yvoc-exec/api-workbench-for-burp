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

    public GraalJsSandboxEngine() {
        boolean graalOk = false;
        try (Context context = createGraalContext()) {
            graalOk = context.getEngine() != null;
        } catch (Throwable ignored) {
            graalOk = false;
        }
        this.graalAvailable = graalOk;
        this.nashornFallbackAvailable = !graalOk && createNashornEngine() != null;
        if (graalAvailable) {
            this.engineName = "GraalJS";
        } else if (nashornFallbackAvailable) {
            this.engineName = "Nashorn fallback";
        } else {
            this.engineName = "Unavailable";
        }
    }

    public boolean isAvailable() {
        return graalAvailable || nashornFallbackAvailable;
    }

    public String getEngineName() {
        return engineName;
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
        throw new IllegalStateException("No JavaScript engine available");
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
