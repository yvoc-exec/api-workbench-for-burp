package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Safe request path helpers shared by tree, auth, and mutation code.
 */
public final class RequestPathResolver {
    private RequestPathResolver() {
    }

    public static String normalizeFolderPath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String[] parts = normalizePathToken(value).split("/");
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                normalized.add(part.trim());
            }
        }
        return String.join("/", normalized);
    }

    public static boolean requestNameContainsPathSeparator(String requestName) {
        String normalized = normalizePathToken(requestName);
        return !normalized.isEmpty() && (normalized.indexOf('/') >= 0);
    }

    public static boolean isLegacyFullRequestPath(String requestPath, String requestName) {
        String rawPath = normalizePathToken(requestPath);
        if (rawPath.isEmpty() || !rawPath.contains("/")) {
            return false;
        }
        String normalizedName = normalizePathToken(requestName);
        if (normalizedName.isEmpty()) {
            return false;
        }
        return rawPath.endsWith("/" + normalizedName);
    }

    public static String maybeLegacyFolderPath(String requestPath, String requestName) {
        String rawPath = normalizePathToken(requestPath);
        if (rawPath.isEmpty()) {
            return "";
        }
        if (isLegacyFullRequestPath(requestPath, requestName)) {
            String normalizedName = normalizePathToken(requestName);
            int suffixLength = normalizedName.length() + 1;
            if (rawPath.length() > suffixLength) {
                return normalizeFolderPath(rawPath.substring(0, rawPath.length() - suffixLength));
            }
            return "";
        }
        return normalizeFolderPath(rawPath);
    }

    public static String getCanonicalFolderPath(String requestPath, String requestName) {
        String rawPath = normalizePathToken(requestPath);
        if (rawPath.isEmpty()) {
            return "";
        }
        String normalizedName = normalizePathToken(requestName);
        if (isLegacyFullRequestPath(requestPath, requestName)) {
            return maybeLegacyFolderPath(requestPath, requestName);
        }
        if (!rawPath.contains("/") && Objects.equals(rawPath, normalizedName)) {
            return "";
        }
        return normalizeFolderPath(rawPath);
    }

    public static String getRequestFolderPath(ApiRequest request) {
        if (request == null) {
            return "";
        }
        return getRequestFolderPath(request.path, request.name, true);
    }

    public static String getRequestFolderPath(String requestPath, String requestName, boolean preferFolderOnly) {
        String rawPath = normalizePathToken(requestPath);
        if (rawPath.isEmpty()) {
            return "";
        }

        if (preferFolderOnly) {
            if (requestNameContainsPathSeparator(requestName) && isLegacyFullRequestPath(requestPath, requestName)) {
                return maybeLegacyFolderPath(requestPath, requestName);
            }
            return normalizeFolderPath(rawPath);
        }

        if (isLegacyFullRequestPath(requestPath, requestName)) {
            return maybeLegacyFolderPath(requestPath, requestName);
        }

        String normalizedName = normalizePathToken(requestName);
        if (!rawPath.contains("/") && Objects.equals(rawPath, normalizedName)) {
            return "";
        }
        return normalizeFolderPath(rawPath);
    }

    public static String getRequestFolderPath(ApiCollection collection, ApiRequest request) {
        if (request == null) {
            return "";
        }
        String rawPath = normalizePathToken(request.path);
        if (rawPath.isEmpty()) {
            return "";
        }

        String candidate = getRequestFolderPath(request.path, request.name, false);
        String normalizedName = normalizePathToken(request.name);

        if (!rawPath.contains("/")) {
            if (Objects.equals(rawPath, normalizedName)) {
                return hasFolderEvidence(collection, request, rawPath) ? rawPath : "";
            }
            return normalizeFolderPath(rawPath);
        }

        if (hasFolderEvidence(collection, request, rawPath)) {
            return rawPath;
        }
        return candidate;
    }

    private static boolean hasFolderEvidence(ApiCollection collection, ApiRequest currentRequest, String rawPathToken) {
        if (collection == null || rawPathToken == null || rawPathToken.isBlank()) {
            return false;
        }

        if (collection.folderPaths != null) {
            for (String folderPath : collection.folderPaths) {
                if (Objects.equals(normalizePathToken(folderPath), rawPathToken)) {
                    return true;
                }
            }
        }

        if (collection.folderAuthModes != null) {
            for (String folderPath : collection.folderAuthModes.keySet()) {
                if (Objects.equals(normalizePathToken(folderPath), rawPathToken)) {
                    return true;
                }
            }
        }

        if (collection.folderAuth != null) {
            for (String folderPath : collection.folderAuth.keySet()) {
                if (Objects.equals(normalizePathToken(folderPath), rawPathToken)) {
                    return true;
                }
            }
        }

        if (collection.folderVars != null) {
            for (String folderPath : collection.folderVars.keySet()) {
                if (Objects.equals(normalizePathToken(folderPath), rawPathToken)) {
                    return true;
                }
            }
        }

        if (collection.requests != null) {
            String descendantPrefix = rawPathToken + "/";
            for (ApiRequest request : collection.requests) {
                if (request == null || request == currentRequest) {
                    continue;
                }
                String otherPath = normalizePathToken(request.path);
                if (otherPath.isEmpty()) {
                    continue;
                }
                if (Objects.equals(otherPath, rawPathToken) || otherPath.startsWith(descendantPrefix)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static String normalizePathToken(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\\', '/').trim();
    }
}
