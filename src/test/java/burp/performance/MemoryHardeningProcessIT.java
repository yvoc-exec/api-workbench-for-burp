package burp.performance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryHardeningProcessIT {
    private static final List<String> SCENARIOS = List.of(
            "history-1000x64k",
            "history-100x2m",
            "runner-200x2m",
            "exact-250x256k",
            "script-json-8m",
            "workspace-history-80m",
            "workspace-ten-slow-saves",
            "runner-sitemap-traffic",
            "workbench-snapshot-owners",
            "oauth2-status-growth");
    private static final long TIMEOUT_SECONDS = 90;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DISCLAIMER =
            "Burp project physical page reclamation is outside this test harness. "
                    + "These values attribute extension-submitted volume, not exact project-file bytes.";

    @Test
    void isolatedScenariosProduceCompleteClassifiedBaseline() throws Exception {
        assertThat(Boolean.getBoolean("memory.hardening.enabled")).isTrue();
        Path output = Path.of(System.getProperty("memory.hardening.output", "target/memory-hardening"));
        Files.createDirectories(output);

        JsonArray results = new JsonArray();
        List<String> unclassified = new ArrayList<>();
        for (String scenario : SCENARIOS) {
            JsonObject result = executeChild(scenario, output);
            results.add(result);
            String classification = string(result, "exitClassification");
            if (classification == null || classification.isBlank() || "UNCLASSIFIED".equals(classification)) {
                unclassified.add(scenario);
            }
        }

        JsonObject workspaceMetrics = metricsFor(results, "workspace-ten-slow-saves");
        JsonObject runnerMetrics = metricsFor(results, "runner-sitemap-traffic");
        JsonObject ownership = logicalOwnership(results);
        assertAccounting(workspaceMetrics, runnerMetrics);
        assertRetainedOwnershipMeasurements(results);

        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 2);
        root.addProperty("generatedAtUtc", Instant.now().toString());
        root.addProperty("repository", "api-workbench-for-burp");
        root.addProperty("branch", "main");
        root.addProperty("startingSha", "68401e62d8929546622cff28231c180b91f0e412");
        root.addProperty("javaVersion", System.getProperty("java.version"));
        root.addProperty("mavenVersion", System.getProperty("memory.hardening.maven.version", "3.9.9"));
        root.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        root.addProperty("configuredChildHeap", "-Xms256m -Xmx512m");
        root.add("scenarioResults", results);
        root.add("workspaceWriteMetrics", workspaceMetrics);
        root.add("runnerSiteMapMetrics", runnerMetrics);
        root.add("logicalOwnershipMetrics", ownership);
        JsonArray notes = new JsonArray();
        notes.add("Observed measurements, deterministic structural counts, estimated logical bytes, JVM heap samples, and project-growth proxy metrics are labeled separately.");
        notes.add(DISCLAIMER);
        notes.add("Retained-settle heap is sampled while scenario owner roots remain strongly reachable; release-settle heap is sampled after explicit owner release.");
        notes.add("maximumSampledHeapBytes is sparse sampled heap, not a continuously observed JVM peak.");
        root.add("notes", notes);
        root.addProperty("reportStatus", unclassified.isEmpty() ? "R1A_MEASUREMENTS_COMPLETE_WITH_DECLARED_PROXIES" : "INCOMPLETE");

        Path jsonReport = output.resolve("baseline-report.json");
        Path textReport = output.resolve("baseline-summary.txt");
        Files.writeString(jsonReport, GSON.toJson(root), StandardCharsets.UTF_8);
        Files.writeString(textReport, summary(results, root.get("reportStatus").getAsString()), StandardCharsets.UTF_8);

        assertThat(results).hasSize(SCENARIOS.size());
        assertThat(jsonReport).exists().isNotEmptyFile();
        assertThat(textReport).exists().isNotEmptyFile();
        assertThat(unclassified).as("every child process must be classifiable").isEmpty();
        assertThat(root.get("reportStatus").getAsString()).isEqualTo("R1A_MEASUREMENTS_COMPLETE_WITH_DECLARED_PROXIES");
    }

    private static JsonObject executeChild(String scenario, Path output) throws Exception {
        Path resultFile = output.resolve(scenario + "-result.json");
        Path stdout = output.resolve(scenario + "-stdout.txt");
        Path stderr = output.resolve(scenario + "-stderr.txt");
        Path heapDump = output.resolve(scenario + ".hprof");
        Files.deleteIfExists(resultFile);
        Files.deleteIfExists(heapDump);

        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        List<String> command = List.of(
                java.toString(),
                "-Xms256m",
                "-Xmx512m",
                "-XX:+HeapDumpOnOutOfMemoryError",
                "-XX:HeapDumpPath=" + heapDump,
                "-Dmemory.hardening.enabled=true",
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                MemoryHardeningScenarioMain.class.getName(),
                scenario,
                resultFile.toString());
        long started = System.nanoTime();
        Process process = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            return classification(scenario, "TIMEOUT", elapsed, false, true,
                    "Child exceeded deterministic timeout.");
        }

        if (Files.isRegularFile(resultFile)) {
            JsonObject parsed = JsonParser.parseString(Files.readString(resultFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            parsed.addProperty("processExitCode", process.exitValue());
            return parsed;
        }
        String error = Files.isRegularFile(stderr)
                ? Files.readString(stderr, StandardCharsets.UTF_8)
                : "";
        if (error.contains("OutOfMemoryError") || Files.exists(heapDump)) {
            return classification(scenario, "OOM", elapsed, true, false,
                    "Child reached configured heap before writing its result.");
        }
        if (process.exitValue() != 0) {
            return classification(scenario, "NONZERO_EXIT", elapsed, false, false,
                    "Child exited nonzero before writing its result.");
        }
        return classification(scenario, "UNCLASSIFIED", elapsed, false, false,
                "Child exited without a result.");
    }

    private static JsonObject classification(String scenario, String classification, long elapsed,
                                             boolean oom, boolean timeout, String warning) {
        JsonObject result = new JsonObject();
        result.addProperty("scenarioName", scenario);
        result.addProperty("exitClassification", classification);
        result.addProperty("elapsedMillis", elapsed);
        result.addProperty("configuredHeapBytes", 512L * 1024L * 1024L);
        result.addProperty("heapUsedBefore", 0);
        result.addProperty("maximumSampledHeapBytes", 0);
        result.addProperty("heapAfterWorkload", 0);
        result.addProperty("heapAfterRetainedSettle", 0);
        result.addProperty("heapAfterReleaseSettle", 0);
        result.addProperty("payloadBytes", 0);
        result.addProperty("operationCount", 0);
        result.addProperty("logicalRetainedBytes", 0);
        result.addProperty("serializedWorkspaceBytes", 0);
        result.addProperty("retainedOwners", 0);
        result.addProperty("apiWorkbenchThreadCount", 0);
        result.addProperty("apiWorkbenchThreadCountBefore", 0);
        result.addProperty("apiWorkbenchThreadCountDuring", 0);
        result.addProperty("apiWorkbenchThreadCountAfterClose", 0);
        result.addProperty("apiWorkbenchThreadCountAfter", 0);
        result.addProperty("oom", oom);
        result.addProperty("timedOut", timeout);
        JsonArray warnings = new JsonArray();
        warnings.add(warning);
        result.add("warnings", warnings);
        result.add("metrics", new JsonObject());
        return result;
    }

    private static JsonObject metricsFor(JsonArray results, String name) {
        for (JsonElement item : results) {
            JsonObject object = item.getAsJsonObject();
            if (name.equals(string(object, "scenarioName"))) {
                JsonObject metrics = object.has("metrics") && object.get("metrics").isJsonObject()
                        ? object.getAsJsonObject("metrics").deepCopy()
                        : new JsonObject();
                metrics.addProperty("measurementType", "proxy metric for project growth");
                return metrics;
            }
        }
        return new JsonObject();
    }

    private static JsonObject logicalOwnership(JsonArray results) {
        JsonObject ownership = new JsonObject();
        ownership.addProperty("measurementType", "deterministic structural count and estimated logical bytes");
        for (JsonElement item : results) {
            JsonObject result = item.getAsJsonObject();
            JsonObject value = new JsonObject();
            value.addProperty("retainedOwners", longValue(result, "retainedOwners"));
            value.addProperty("logicalRetainedBytes", longValue(result, "logicalRetainedBytes"));
            ownership.add(string(result, "scenarioName"), value);
        }
        return ownership;
    }

    private static void assertAccounting(JsonObject workspace, JsonObject runner) {
        if (workspace.has("cumulativeExtensionDataBytesSubmitted")
                && workspace.has("currentExtensionDataValueBytes")) {
            assertThat(workspace.get("cumulativeExtensionDataBytesSubmitted").getAsLong())
                    .isGreaterThanOrEqualTo(workspace.get("currentExtensionDataValueBytes").getAsLong());
        }
        if (workspace.has("pendingSnapshotCountObserved")) {
            assertThat(workspace.get("pendingSnapshotCountObserved").getAsInt()).isEqualTo(10);
            assertThat(workspace.get("queuedDetachedWorkspaceStates").getAsInt()).isEqualTo(9);
            assertThat(workspace.get("activeDetachedWorkspaceStates").getAsInt()).isEqualTo(1);
        }
        if (workspace.has("isolatedByteArrayJsonBytes")) {
            assertThat(workspace.get("isolatedByteArrayJsonBytes").getAsLong())
                    .isGreaterThan(workspace.get("byteArrayCanonicalRawBytes").getAsLong());
        }
        if (runner.has("successfulAttempts") && runner.has("siteMapAddCalls")) {
            assertThat(runner.get("siteMapAddCalls").getAsLong())
                    .isEqualTo(runner.get("successfulAttempts").getAsLong());
        }
    }

    private static void assertRetainedOwnershipMeasurements(JsonArray results) {
        for (String scenario : List.of("history-1000x64k", "history-100x2m", "exact-250x256k",
                "workbench-snapshot-owners")) {
            JsonObject result = resultFor(results, scenario);
            if ("SUCCESS".equals(string(result, "exitClassification"))) {
                assertThat(longValue(result, "heapAfterRetainedSettle"))
                        .as(scenario + " retained owner heap")
                        .isGreaterThan(longValue(result, "heapAfterReleaseSettle"));
            }
        }
        JsonObject script = resultFor(results, "script-json-8m");
        if ("SUCCESS".equals(string(script, "exitClassification"))) {
            assertThat(longValue(script, "apiWorkbenchThreadCountDuring")).isPositive();
            assertThat(longValue(script, "apiWorkbenchThreadCountAfter"))
                    .isLessThan(longValue(script, "apiWorkbenchThreadCountDuring"));
        }
    }

    private static JsonObject resultFor(JsonArray results, String name) {
        for (JsonElement item : results) {
            JsonObject result = item.getAsJsonObject();
            if (name.equals(string(result, "scenarioName"))) {
                return result;
            }
        }
        throw new AssertionError("Missing memory scenario " + name);
    }

    private static String summary(JsonArray results, String status) {
        StringBuilder text = new StringBuilder();
        text.append("API Workbench memory and project-storage baseline\n");
        text.append("Status: ").append(status).append('\n');
        text.append("Configured child heap: -Xms256m -Xmx512m\n");
        for (JsonElement item : results) {
            JsonObject result = item.getAsJsonObject();
            text.append(string(result, "scenarioName"))
                    .append(": ").append(string(result, "exitClassification"))
                    .append(", maximumSampledHeapBytes=").append(longValue(result, "maximumSampledHeapBytes"))
                    .append(", retainedSettleHeapBytes=").append(longValue(result, "heapAfterRetainedSettle"))
                    .append(", releaseSettleHeapBytes=").append(longValue(result, "heapAfterReleaseSettle"))
                    .append(", logicalRetainedBytes=").append(longValue(result, "logicalRetainedBytes"))
                    .append('\n');
        }
        text.append(DISCLAIMER).append('\n');
        return text.toString();
    }

    private static String string(JsonObject object, String property) {
        return object.has(property) && !object.get(property).isJsonNull()
                ? object.get(property).getAsString()
                : null;
    }

    private static long longValue(JsonObject object, String property) {
        return object.has(property) && !object.get(property).isJsonNull()
                ? object.get(property).getAsLong()
                : 0L;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
