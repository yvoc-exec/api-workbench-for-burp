package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class ExportIds {
    private ExportIds() {
    }

    static String workspaceId(ApiCollection collection) {
        return "wrk_" + slug(collection != null ? collection.name : "workspace");
    }

    static String folderId(String folderPath) {
        return "fld_" + shortHash(folderPath);
    }

    static String requestId(ApiRequest request, int index) {
        String seed = request != null && request.id != null && !request.id.isBlank()
                ? request.id
                : (request != null && request.name != null ? request.name : "request") + ":" + index;
        return "req_" + shortHash(seed);
    }

    static String environmentId(EnvironmentProfile profile) {
        return "env_" + slug(profile != null ? profile.displayName() : "environment");
    }

    static String slug(String value) {
        if (value == null || value.isBlank()) {
            return "item";
        }
        String out = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (out.isBlank()) {
            out = "item";
        }
        return out;
    }

    static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value != null ? value : "").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(4, hash.length); i++) {
                sb.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString((value != null ? value : "").hashCode());
        }
    }
}
