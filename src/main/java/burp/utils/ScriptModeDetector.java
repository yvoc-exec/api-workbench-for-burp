package burp.utils;

/**
 * Detects script execution capability based on Java version and JavaScript runtime availability.
 */
public class ScriptModeDetector {

    public static class DetectionResult {
        public final ScriptMode mode;
        public final String reason;
        public final int javaVersion;
        public final String engineName;

        public DetectionResult(ScriptMode mode, String reason, int javaVersion) {
            this(mode, reason, javaVersion, null);
        }

        public DetectionResult(ScriptMode mode, String reason, int javaVersion, String engineName) {
            this.mode = mode;
            this.reason = reason;
            this.javaVersion = javaVersion;
            this.engineName = engineName;
        }
    }

    public static DetectionResult detect() {
        int version = parseJavaMajorVersion();
        if (version < 17) {
            return new DetectionResult(ScriptMode.DISABLED,
                "Java " + version + " detected. Java 17+ is required for script execution.", version);
        }
        try (burp.scripts.GraalJsSandboxEngine engine = new burp.scripts.GraalJsSandboxEngine()) {
            String probeReason = engine.isAvailable() ? null : engine.getGraalFailure();
            if (probeReason == null) {
                return new DetectionResult(ScriptMode.FULL_JS,
                    "Java " + version + " with " + engine.getEngineName() + " available.", version, engine.getEngineName());
            }
            return new DetectionResult(ScriptMode.LIMITED,
                "Java " + version + " detected. Bounded full JavaScript unavailable: " + probeReason, version,
                engine.getEngineName());
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
        try (burp.scripts.GraalJsSandboxEngine engine = new burp.scripts.GraalJsSandboxEngine()) {
            return engine.isAvailable() ? null : engine.getGraalFailure();
        }
    }

    static String probeNashorn() {
        return probeJavaScriptRuntime();
    }

    static String probeJavaScriptRuntimeLegacy() {
        return probeJavaScriptRuntime();
    }
}
