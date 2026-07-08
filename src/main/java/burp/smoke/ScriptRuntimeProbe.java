package burp.smoke;

import burp.scripts.SandboxedJavaScriptEngine;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ScriptRuntimeProbe {

    private ScriptRuntimeProbe() {
    }

    public static final class ProbeResult {
        public final int javaVersion;
        public final String engineName;
        public final boolean runtimeAvailable;
        public final boolean legacyFallbackAvailable;
        public final String initializationFailure;
        public final Object evaluationResult;
        public final boolean success;
        public final int exitCode;

        private ProbeResult(int javaVersion,
                            String engineName,
                            boolean runtimeAvailable,
                            boolean legacyFallbackAvailable,
                            String initializationFailure,
                            Object evaluationResult,
                            boolean success,
                            int exitCode) {
            this.javaVersion = javaVersion;
            this.engineName = engineName;
            this.runtimeAvailable = runtimeAvailable;
            this.legacyFallbackAvailable = legacyFallbackAvailable;
            this.initializationFailure = initializationFailure;
            this.evaluationResult = evaluationResult;
            this.success = success;
            this.exitCode = exitCode;
        }
    }

    public static ProbeResult runProbe() {
        return runProbe(false);
    }

    public static ProbeResult runProbe(boolean requireFull) {
        SandboxedJavaScriptEngine engine = new SandboxedJavaScriptEngine();
        Object result = null;
        boolean success = false;
        String failure = engine.getInitializationFailure();
        try {
            result = engine.execute("1 + 1", Collections.emptyMap());
            success = isTwo(result) && engine.isAvailable();
            if (!success && failure == null) {
                failure = "JavaScript runtime probe did not evaluate 1 + 1 to 2.";
            }
            if (success && requireFull && !engine.isRuntimeAvailable()) {
                success = false;
                failure = "JavaScript runtime required but unavailable.";
            }
        } catch (Throwable t) {
            failure = failure != null ? failure : t.getMessage();
            success = false;
        }
        int exitCode = success ? 0 : 1;
        return new ProbeResult(
                Runtime.version().feature(),
                engine.getEngineName(),
                engine.isRuntimeAvailable(),
                engine.isLegacyFallbackAvailable(),
                failure,
                result,
                success,
                exitCode
        );
    }

    public static void main(String[] args) {
        List<String> arguments = args != null ? Arrays.asList(args) : List.of();
        boolean requireFull = arguments.contains("--require-full");
        ProbeResult result = runProbe(requireFull);
        System.out.println("Java: " + result.javaVersion);
        System.out.println("Script runtime: " + result.engineName);
        System.out.println("Runtime available: " + result.runtimeAvailable);
        System.out.println("Legacy fallback available: " + result.legacyFallbackAvailable);
        System.out.println("Evaluation result: " + result.evaluationResult);
        if (result.initializationFailure != null && !result.initializationFailure.isBlank()) {
            System.out.println("Initialization failure: " + result.initializationFailure);
        }
        if (requireFull) {
            System.out.println("Require full: true");
        }
        if (!result.success) {
            System.exit(result.exitCode);
        }
    }

    private static boolean isTwo(Object value) {
        if (value instanceof Number number) {
            return Math.abs(number.doubleValue() - 2.0d) < 0.0001d;
        }
        if (value == null) {
            return false;
        }
        return "2".equals(String.valueOf(value).trim());
    }
}
