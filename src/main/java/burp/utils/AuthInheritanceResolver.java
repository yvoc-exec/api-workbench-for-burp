package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves effective auth from collection -> folder -> request override metadata.
 */
public final class AuthInheritanceResolver {
    private static final String MODE_INHERIT = "inherit";
    private static final String MODE_EXPLICIT = "explicit";
    private static final String MODE_NONE = "none";

    private AuthInheritanceResolver() {
    }

    public static ApiRequest.Auth copyAuth(ApiRequest.Auth src) {
        if (src == null) {
            return null;
        }
        ApiRequest.Auth copy = new ApiRequest.Auth();
        copy.type = src.type;
        if (src.properties != null && !src.properties.isEmpty()) {
            copy.properties.putAll(src.properties);
        }
        return copy;
    }

    public static String normalizeParsedAuthMode(ApiRequest.Auth auth) {
        if (auth == null || auth.type == null || auth.type.isBlank()) {
            return MODE_INHERIT;
        }
        if ("none".equalsIgnoreCase(auth.type) || "noauth".equalsIgnoreCase(auth.type)) {
            return MODE_NONE;
        }
        return MODE_EXPLICIT;
    }

    public static String normalizeFolderPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String[] parts = path.split("/");
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                normalized.add(part.trim());
            }
        }
        return String.join("/", normalized);
    }

    public static String normalizeAuthOverrideMode(String mode, ApiRequest request) {
        if (mode != null && !mode.isBlank()) {
            String normalized = mode.trim().toLowerCase();
            if (MODE_INHERIT.equals(normalized) || MODE_EXPLICIT.equals(normalized) || MODE_NONE.equals(normalized)) {
                return normalized;
            }
        }

        if (request != null) {
            if (request.authOverrideMode != null && !request.authOverrideMode.isBlank()) {
                String normalized = request.authOverrideMode.trim().toLowerCase();
                if (MODE_INHERIT.equals(normalized) || MODE_EXPLICIT.equals(normalized) || MODE_NONE.equals(normalized)) {
                    return normalized;
                }
            }
            if (request.explicitAuth != null) {
                if ("none".equalsIgnoreCase(request.explicitAuth.type)) {
                    return MODE_NONE;
                }
                return MODE_EXPLICIT;
            }
            if (request.authExplicitlyDisabled) {
                return MODE_NONE;
            }
            if (request.authInherited) {
                return MODE_INHERIT;
            }
            if (request.auth != null && request.auth.type != null) {
                if ("none".equalsIgnoreCase(request.auth.type)) {
                    return request.authExplicitlyDisabled ? MODE_NONE : MODE_INHERIT;
                }
                return MODE_EXPLICIT;
            }
        }
        return MODE_INHERIT;
    }

    public static void markRequestInherit(ApiRequest request) {
        if (request == null) {
            return;
        }
        request.authOverrideMode = MODE_INHERIT;
        request.explicitAuth = null;
        request.authInherited = false;
        request.authExplicitlyDisabled = false;
        request.authSource = "none";
    }

    public static void markRequestExplicitAuth(ApiRequest request, ApiRequest.Auth auth) {
        if (request == null) {
            return;
        }
        String mode = normalizeParsedAuthMode(auth);
        if (MODE_INHERIT.equals(mode)) {
            markRequestInherit(request);
            return;
        }
        if (MODE_NONE.equals(mode)) {
            markRequestNoAuth(request);
            return;
        }
        ApiRequest.Auth explicit = copyAuth(auth);
        request.auth = copyAuth(explicit);
        request.explicitAuth = copyAuth(explicit);
        request.authOverrideMode = MODE_EXPLICIT;
        request.authInherited = false;
        request.authExplicitlyDisabled = false;
        request.authSource = requestLayerSource(request);
    }

    public static void markRequestNoAuth(ApiRequest request) {
        if (request == null) {
            return;
        }
        ApiRequest.Auth none = noneAuth();
        request.auth = copyAuth(none);
        request.explicitAuth = copyAuth(none);
        request.authOverrideMode = MODE_NONE;
        request.authInherited = false;
        request.authExplicitlyDisabled = true;
        request.authSource = requestLayerSource(request);
    }

    public static void setCollectionAuth(ApiCollection collection, ApiRequest.Auth auth) {
        if (collection == null) {
            return;
        }
        collection.auth = copyAuth(auth);
        recomputeCollectionAuth(collection);
    }

    public static void setFolderAuth(ApiCollection collection, String folderPath, String mode, ApiRequest.Auth auth) {
        if (collection == null) {
            return;
        }
        String normalizedPath = normalizeFolderPath(folderPath);
        if (normalizedPath.isEmpty()) {
            return;
        }
        String normalizedMode = normalizeAuthOverrideMode(mode, null);
        if (normalizedMode == null || MODE_INHERIT.equals(normalizedMode)) {
            if (collection.folderAuthModes == null) {
                collection.folderAuthModes = new LinkedHashMap<>();
            }
            collection.folderAuthModes.put(normalizedPath, MODE_INHERIT);
            if (collection.folderAuth != null) {
                collection.folderAuth.remove(normalizedPath);
            }
        } else {
            if (collection.folderAuthModes == null) {
                collection.folderAuthModes = new LinkedHashMap<>();
            }
            if (collection.folderAuth == null) {
                collection.folderAuth = new LinkedHashMap<>();
            }
            collection.folderAuthModes.put(normalizedPath, normalizedMode);
            collection.folderAuth.put(normalizedPath, MODE_NONE.equals(normalizedMode) ? noneAuth() : copyAuth(auth));
            if (MODE_NONE.equals(normalizedMode) && collection.folderAuth.get(normalizedPath) == null) {
                collection.folderAuth.put(normalizedPath, noneAuth());
            }
        }
        recomputeCollectionAuth(collection);
    }

    public static void clearFolderAuth(ApiCollection collection, String folderPath) {
        if (collection == null) {
            return;
        }
        String normalizedPath = normalizeFolderPath(folderPath);
        if (normalizedPath.isEmpty()) {
            return;
        }
        if (collection.folderAuthModes != null) {
            collection.folderAuthModes.remove(normalizedPath);
        }
        if (collection.folderAuth != null) {
            collection.folderAuth.remove(normalizedPath);
        }
        recomputeCollectionAuth(collection);
    }

    public static void recomputeCollections(List<ApiCollection> collections) {
        if (collections == null) {
            return;
        }
        for (ApiCollection collection : collections) {
            recomputeCollectionAuth(collection);
        }
    }

    public static void recomputeCollectionAuth(ApiCollection collection) {
        if (collection == null || collection.requests == null) {
            return;
        }
        for (ApiRequest request : collection.requests) {
            resolveRequestAuth(collection, request);
        }
    }

    public static void resolveRequestAuth(ApiCollection collection, ApiRequest request) {
        if (request == null) {
            return;
        }

        String mode = normalizeAuthOverrideMode(request.authOverrideMode, request);
        request.authOverrideMode = mode;

        if (MODE_EXPLICIT.equals(mode) || MODE_NONE.equals(mode)) {
            ApiRequest.Auth explicit = copyAuth(request.explicitAuth);
            if (explicit == null && request.auth != null && request.auth.type != null) {
                explicit = copyAuth(request.auth);
            }
            if (explicit != null && "none".equalsIgnoreCase(explicit.type)) {
                mode = MODE_NONE;
            }
            if (MODE_NONE.equals(mode)) {
                if (explicit == null) {
                    explicit = noneAuth();
                } else {
                    explicit.type = "none";
                }
            }
            applyResolvedAuth(request, explicit, requestLayerSource(request), false, MODE_NONE.equals(mode));
            request.explicitAuth = copyAuth(explicit);
            return;
        }

        ResolvedAuth resolved = resolveInheritedAuth(collection, request);
        applyResolvedAuth(request, resolved.auth, resolved.source, resolved.inherited, resolved.explicitlyDisabled);
        request.explicitAuth = null;
    }

    public static String getRequestFolderPath(ApiRequest request) {
        if (request == null) {
            return "";
        }
        String normalized = normalizeFolderPath(request.path);
        if (normalized.isEmpty()) {
            return "";
        }
        String requestName = request.name != null ? request.name.trim() : "";
        if (!requestName.isEmpty()) {
            int lastSlash = normalized.lastIndexOf('/');
            String lastSegment = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
            if (Objects.equals(lastSegment, requestName)) {
                return lastSlash >= 0 ? normalized.substring(0, lastSlash) : "";
            }
        }
        return normalized;
    }

    private static ResolvedAuth resolveInheritedAuth(ApiCollection collection, ApiRequest request) {
        ResolvedAuth resolved = new ResolvedAuth();
        if (collection != null && collection.auth != null && collection.auth.type != null) {
            resolved.applyLayer(copyAuth(collection.auth), collectionLayerSource(collection), true,
                    "none".equalsIgnoreCase(collection.auth.type));
        }

        if (collection != null) {
            for (String folderPath : getFolderPathChain(request)) {
                String normalizedPath = normalizeFolderPath(folderPath);
                if (normalizedPath.isEmpty()) {
                    continue;
                }
                String mode = collection.folderAuthModes != null ? collection.folderAuthModes.get(normalizedPath) : null;
                if (mode == null) {
                    continue;
                }
                String normalizedMode = normalizeAuthOverrideMode(mode, null);
                if (MODE_INHERIT.equals(normalizedMode)) {
                    continue;
                }
                ApiRequest.Auth folderAuth = collection.folderAuth != null ? collection.folderAuth.get(normalizedPath) : null;
                if (MODE_NONE.equals(normalizedMode)) {
                    if (folderAuth == null || folderAuth.type == null) {
                        folderAuth = noneAuth();
                    } else {
                        folderAuth = copyAuth(folderAuth);
                        folderAuth.type = "none";
                    }
                } else {
                    folderAuth = copyAuth(folderAuth);
                }
                resolved.applyLayer(folderAuth, folderLayerSource(normalizedPath), true,
                        folderAuth != null && "none".equalsIgnoreCase(folderAuth.type));
            }
        }

        return resolved;
    }

    private static List<String> getFolderPathChain(ApiRequest request) {
        List<String> chain = new ArrayList<>();
        String folderPath = getRequestFolderPath(request);
        if (folderPath.isEmpty()) {
            return chain;
        }
        String[] parts = folderPath.split("/");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (current.length() > 0) {
                current.append('/');
            }
            current.append(part.trim());
            chain.add(current.toString());
        }
        return chain;
    }

    private static void applyResolvedAuth(ApiRequest request,
                                          ApiRequest.Auth auth,
                                          String source,
                                          boolean inherited,
                                          boolean explicitlyDisabled) {
        request.auth = auth;
        request.authInherited = inherited;
        request.authExplicitlyDisabled = explicitlyDisabled;
        request.authSource = source != null ? source : "none";
    }

    private static String requestLayerSource(ApiRequest request) {
        String name = request != null && request.name != null ? request.name.trim() : "";
        return "request" + (name.isEmpty() ? "" : ": " + name);
    }

    private static String collectionLayerSource(ApiCollection collection) {
        String name = collection != null && collection.name != null ? collection.name.trim() : "";
        return "collection" + (name.isEmpty() ? "" : ": " + name);
    }

    private static String folderLayerSource(String folderPath) {
        return "folder" + (folderPath == null || folderPath.isBlank() ? "" : ": " + folderPath);
    }

    private static ApiRequest.Auth noneAuth() {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "none";
        return auth;
    }

    private static class ResolvedAuth {
        private ApiRequest.Auth auth;
        private String source = "none";
        private boolean inherited;
        private boolean explicitlyDisabled;

        private void applyLayer(ApiRequest.Auth layerAuth, String layerSource, boolean layerInherited, boolean layerExplicitlyDisabled) {
            if (layerAuth == null || layerAuth.type == null) {
                return;
            }
            this.auth = layerAuth;
            this.source = layerSource != null ? layerSource : this.source;
            this.inherited = layerInherited;
            this.explicitlyDisabled = layerExplicitlyDisabled;
        }
    }
}
