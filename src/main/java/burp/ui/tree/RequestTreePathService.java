package burp.ui.tree;

import burp.models.ApiRequest;
import burp.utils.RequestPathResolver;

import java.util.Objects;

/**
 * Shared folder/request path helpers for the Workbench request tree.
 */
public final class RequestTreePathService {
    private RequestTreePathService() {
    }

    public static String normalizeFolderPath(String path) {
        return RequestPathResolver.normalizeFolderPath(path);
    }

    public static String joinFolderPath(String parentPath, String leafName) {
        String parent = normalizeFolderPath(parentPath);
        String leaf = normalizeFolderPath(leafName);
        if (parent.isEmpty()) {
            return leaf;
        }
        if (leaf.isEmpty()) {
            return parent;
        }
        return parent + "/" + leaf;
    }

    public static String getParentFolderPath(String path) {
        String normalized = normalizeFolderPath(path);
        if (normalized.isEmpty()) {
            return "";
        }
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash >= 0 ? normalized.substring(0, lastSlash) : "";
    }

    public static String leafFolderName(String path) {
        String normalized = normalizeFolderPath(path);
        if (normalized.isEmpty()) {
            return "";
        }
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
    }

    public static boolean isFolderPathInSubtree(String folderPath, String sourcePrefix) {
        String normalizedPath = normalizeFolderPath(folderPath);
        String normalizedPrefix = normalizeFolderPath(sourcePrefix);
        if (normalizedPrefix.isEmpty()) {
            return !normalizedPath.isEmpty();
        }
        return Objects.equals(normalizedPath, normalizedPrefix)
                || normalizedPath.startsWith(normalizedPrefix + "/");
    }

    public static String rewriteFolderPathPrefix(String value, String sourcePrefix, String targetPrefix) {
        String normalizedValue = normalizeFolderPath(value);
        String normalizedSource = normalizeFolderPath(sourcePrefix);
        String normalizedTarget = normalizeFolderPath(targetPrefix);
        if (normalizedValue.isEmpty() || normalizedSource.isEmpty()) {
            return normalizedValue;
        }
        if (!Objects.equals(normalizedValue, normalizedSource) && !normalizedValue.startsWith(normalizedSource + "/")) {
            return normalizedValue;
        }
        String suffix = normalizedValue.length() == normalizedSource.length()
                ? ""
                : normalizedValue.substring(normalizedSource.length());
        return normalizedTarget + suffix;
    }

    public static String getRequestFolderPath(ApiRequest request) {
        return RequestPathResolver.getRequestFolderPath(request);
    }

    public static String folderPathFromRequestPath(String requestPath, String requestName) {
        return RequestPathResolver.getRequestFolderPath(requestPath, requestName, true);
    }

    public static boolean isNestedRequestPath(String requestPath, String requestName) {
        if (requestPath == null || requestPath.isBlank()) {
            return false;
        }
        String normalized = requestPath.replace('\\', '/').trim();
        if (!normalized.contains("/")) {
            return false;
        }
        String name = requestName != null ? requestName.replace('\\', '/').trim() : "";
        if (name.isEmpty()) {
            return true;
        }
        return normalized.equals(name) || normalized.endsWith("/" + name);
    }
}
