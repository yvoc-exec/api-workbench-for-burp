package burp.payload;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

public final class ManagedPayloadStorePaths {
    static final String PROJECT_NAMESPACE_KEY = "awb.managed_payload_namespace.v1";

    private ManagedPayloadStorePaths() {
    }

    public static Path forProject(MontoyaApi api) {
        return defaultRoot().resolve(projectNamespaceHash(api));
    }

    public static Path defaultRoot() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            return Paths.get(local != null && !local.isBlank() ? local : home,
                    "API Workbench for Burp", "payloads");
        }
        if (os.contains("mac")) {
            return Paths.get(home, "Library", "Application Support", "API Workbench for Burp", "payloads");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        return Paths.get(xdg != null && !xdg.isBlank() ? xdg : Paths.get(home, ".local", "share").toString(),
                "api-workbench-for-burp", "payloads");
    }

    static String projectNamespaceHash(MontoyaApi api) {
        String namespace = null;
        try {
            PersistedObject object = api != null && api.persistence() != null
                    ? api.persistence().extensionData() : null;
            if (object != null) {
                namespace = object.getString(PROJECT_NAMESPACE_KEY);
                if (namespace == null || namespace.isBlank()) {
                    namespace = UUID.randomUUID().toString();
                    object.setString(PROJECT_NAMESPACE_KEY, namespace);
                }
            }
        } catch (RuntimeException ignored) {
            namespace = null;
        }
        if (namespace == null || namespace.isBlank()) {
            namespace = UUID.randomUUID().toString();
        }
        return sha256(namespace);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive managed payload namespace.", e);
        }
    }
}
