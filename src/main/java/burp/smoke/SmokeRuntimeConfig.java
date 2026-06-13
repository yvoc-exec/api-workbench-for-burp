package burp.smoke;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Opt-in runtime smoke configuration loaded from an explicit JSON file path.
 */
public final class SmokeRuntimeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String configPath;
    public int version = 1;
    public String runId;
    public String apiWorkbenchRepo;
    public String testerRepo;
    public String extensionJar;
    public String burpPath;
    public String javaExe;
    public String localApiHost;
    public int localApiPort;
    public String localApiUrl;
    public int maxWaitSeconds = 120;
    public String resultJsonPath;
    public String reportPath;
    public String logPath;
    public String burpLogPath;
    public String localApiLogPath;
    public String workspaceSnapshotPath;
    public String collectionExportPath;
    public String environmentExportPath;
    public FixturePaths fixtures = new FixturePaths();

    public static final class FixturePaths {
        public String openApi;
        public String postman;
        public String apiWorkbenchEnvironment;
        public String postmanEnvironment;
        public String runtimeEnv;
        public String unsupported;
    }

    public static SmokeRuntimeConfig loadFromEnvironment() {
        String configured = firstNonBlank(
                System.getProperty("apiWorkbench.smoke.config"),
                System.getenv("API_WORKBENCH_SMOKE_CONFIG")
        );
        if (configured == null) {
            return null;
        }
        return load(Path.of(configured));
    }

    public static SmokeRuntimeConfig load(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Smoke config path is required.");
        }
        Path resolved = path.toAbsolutePath().normalize();
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("Smoke config file not found: " + resolved);
        }
        try {
            String json = Files.readString(resolved, StandardCharsets.UTF_8);
            SmokeRuntimeConfig config = GSON.fromJson(json, SmokeRuntimeConfig.class);
            if (config == null) {
                throw new IllegalArgumentException("Smoke config file was empty: " + resolved);
            }
            config.configPath = resolved.toString();
            config.normalise();
            return config;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read smoke config '" + resolved + "': " + e.getMessage(), e);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Failed to parse smoke config '" + resolved + "': " + e.getMessage(), e);
        }
    }

    public Path resolvePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    public Path getResultJsonPath() {
        return resolvePath(resultJsonPath);
    }

    public Path getReportPath() {
        return resolvePath(reportPath);
    }

    public Path getLogPath() {
        return resolvePath(logPath);
    }

    public Path getBurpLogPath() {
        return resolvePath(burpLogPath);
    }

    public Path getLocalApiLogPath() {
        return resolvePath(localApiLogPath);
    }

    public Path getWorkspaceSnapshotPath() {
        return resolvePath(workspaceSnapshotPath);
    }

    public Path getCollectionExportPath() {
        return resolvePath(collectionExportPath);
    }

    public Path getEnvironmentExportPath() {
        return resolvePath(environmentExportPath);
    }

    public Path getFixturePath(String kind) {
        if (fixtures == null || kind == null) {
            return null;
        }
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "openapi" -> resolvePath(fixtures.openApi);
            case "postman" -> resolvePath(fixtures.postman);
            case "apiworkbenchenvironment" -> resolvePath(fixtures.apiWorkbenchEnvironment);
            case "postmanenvironment" -> resolvePath(fixtures.postmanEnvironment);
            case "runtimeenv" -> resolvePath(fixtures.runtimeEnv);
            case "unsupported" -> resolvePath(fixtures.unsupported);
            default -> null;
        };
    }

    public String getResolvedLocalApiUrl() {
        if (localApiUrl != null && !localApiUrl.isBlank()) {
            return localApiUrl;
        }
        if (localApiHost == null || localApiHost.isBlank() || localApiPort <= 0) {
            return null;
        }
        return "http://" + localApiHost + ":" + localApiPort;
    }

    private void normalise() {
        if (fixtures == null) {
            fixtures = new FixturePaths();
        }
        if (maxWaitSeconds <= 0) {
            maxWaitSeconds = 120;
        }
        if (localApiUrl == null || localApiUrl.isBlank()) {
            localApiUrl = getResolvedLocalApiUrl();
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
