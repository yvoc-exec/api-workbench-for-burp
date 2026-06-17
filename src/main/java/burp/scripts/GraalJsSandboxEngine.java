package burp.scripts;

import org.openjdk.nashorn.api.scripting.ClassFilter;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.util.Map;

public class GraalJsSandboxEngine {
    private final boolean available;

    public GraalJsSandboxEngine() {
        this.available = detectAvailability();
    }

    public boolean isAvailable() {
        return available;
    }

    public Object execute(String source, Map<String, Object> bindings) throws Exception {
        if (source == null || source.isBlank()) {
            return null;
        }
        ScriptEngine engine = createEngine();
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

    private ScriptEngine createEngine() {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("nashorn");
        if (engine == null) {
            engine = manager.getEngineByName("javascript");
        }
        if (engine != null) {
            return engine;
        }
        try {
            NashornScriptEngineFactory factory = new NashornScriptEngineFactory();
            return factory.getScriptEngine(new DenyAllClassFilter());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean detectAvailability() {
        try {
            return createEngine() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final class DenyAllClassFilter implements ClassFilter {
        @Override
        public boolean exposeToScripts(String s) {
            return false;
        }
    }
}
