package burp.utils;

import javax.script.ScriptEngineManager;

/**
 * Detects script execution capability based on Java version and JavaScript runtime availability.
 */
public class ScriptModeDetector {

    public static class DetectionResult {
        public final ScriptMode mode;
        public final String reason;
        public final int javaVersion;

        public DetectionResult(ScriptMode mode, String reason, int javaVersion) {
            this.mode = mode;
            this.reason = reason;
            this.javaVersion = javaVersion;
        }
    }

    public static DetectionResult detect() {
        int version = parseJavaMajorVersion();
        if (version < 17) {
            return new DetectionResult(ScriptMode.DISABLED,
                "Java " + version + " detected. Java 17+ is required for script execution.", version);
        }
        // Probe the primary JavaScript runtime used by the extension.
        String probeReason = probeJavaScriptRuntime();
        if (probeReason == null) {
            return new DetectionResult(ScriptMode.FULL_JS,
                "Java " + version + " with JavaScript engine available.", version);
        } else {
            return new DetectionResult(ScriptMode.LIMITED,
                "Java " + version + " detected. JavaScript probe failed: " + probeReason, version);
        }
    }

    static int parseJavaMajorVersion() {
        try {
            // Runtime.version() is available since Java 9
            return Runtime.version().feature();
        } catch (Exception e) {
            // Fallback for very old JVMs or security manager restrictions
            String v = System.getProperty("java.version", "0");
            if (v.startsWith("1.")) {
                // e.g., 1.8.0_xxx -> 8
                try {
                    return Integer.parseInt(v.split("\\.")[1]);
                } catch (Exception ex) {
                    return 8;
                }
            }
            try {
                return Integer.parseInt(v.split("\\.")[0].split("-")[0]);
            } catch (Exception ex) {
                return 0;
            }
        }
    }

    static String probeJavaScriptRuntime() {
        try {
            burp.scripts.GraalJsSandboxEngine engine = new burp.scripts.GraalJsSandboxEngine();
            if (engine.isAvailable()) {
                return null;
            }
            return "No JavaScript runtime found";
        } catch (Throwable t) {
            return "JavaScript probe failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    static String probeNashorn() {
        return probeJavaScriptRuntime();
    }

    static String probeJavaScriptRuntimeLegacy() {
        try {
            ScriptEngineManager manager = new ScriptEngineManager();
            javax.script.ScriptEngine engine = manager.getEngineByName("nashorn");
            if (engine == null) {
                engine = manager.getEngineByName("javascript");
            }
            if (engine == null) {
                try {
                    Class<?> factoryClass = Class.forName("org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory");
                    Object factory = factoryClass.getDeclaredConstructor().newInstance();
                    engine = (javax.script.ScriptEngine) factoryClass.getMethod("getScriptEngine").invoke(factory);
                } catch (Throwable ex) {
                    return "Nashorn factory not available: " + ex.getMessage();
                }
            }
            if (engine == null) {
                return "No Nashorn or JavaScript engine found";
            }
            try {
                Object result = engine.eval("1+1");
                if (result == null || !"2".equals(result.toString())) {
                    return "Nashorn eval returned unexpected result: " + result;
                }
            } catch (Exception e) {
                return "Nashorn eval failed: " + e.getMessage();
            }
            return null; // success
        } catch (Throwable t) {
            return "JavaScript probe failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }
}
