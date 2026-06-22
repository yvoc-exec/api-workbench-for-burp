package burp.models;

import java.util.*;

import burp.scripts.ScriptBlock;

/**
 * Unified API Request model used across all collection formats.
 * All parsers (Postman, Bruno, OpenAPI, Insomnia, HAR) convert to this model.
 */
public class ApiRequest {
    public enum BuildMode {
        AUTO_COMPATIBLE,
        MANUAL_PRESERVE
    }

    public String id;
    public String name;
    public String path;           // folder/path hierarchy
    public String sourceCollection; // which collection this request came from
    public String method;
    public String url;
    public String description;
    public List<Header> headers = new ArrayList<>();
    public Body body;
    public Auth auth;
    /** True when the editor has materialized request-owned headers/body after load/import. */
    public boolean editorMaterialized = false;
    /** Explicit request-build intent used by the runtime builder and editor persistence. */
    public BuildMode buildMode = BuildMode.AUTO_COMPATIBLE;
    /** Lowercase derived headers the tester intentionally removed from a manual request. */
    public Set<String> suppressedAutoHeaders = new LinkedHashSet<>();
    public List<Variable> variables = new ArrayList<>();  // collection-level vars
    public List<Script> preRequestScripts = new ArrayList<>();
    public List<Script> postResponseScripts = new ArrayList<>();
    public List<ScriptBlock> scriptBlocks = new ArrayList<>();
    public boolean disabled = false;
    public int sequenceOrder = 0; // for runner ordering
    /** True when effective auth was inherited from a parent collection/folder auth context. */
    public boolean authInherited = false;
    /** True when the request explicitly disabled auth instead of inheriting one. */
    public boolean authExplicitlyDisabled = false;
    /** Human-readable source for the effective auth layer. */
    public String authSource;
    /** Auth override mode for editable inheritance: inherit, explicit, none. */
    public String authOverrideMode = "inherit";
    /** Explicit auth override configured at the request layer. */
    public Auth explicitAuth;

    public static class Header {
        public String key;
        public String value;
        public boolean disabled;
        public Header(String key, String value) { this.key = key; this.value = value; }
        public Header(String key, String value, boolean disabled) { this.key = key; this.value = value; this.disabled = disabled; }
    }

    public static class Body {
        public String mode;       // raw, urlencoded, formdata, graphql, file, none
        public String raw;
        public String contentType;
        public List<FormField> formdata = new ArrayList<>();
        public List<FormField> urlencoded = new ArrayList<>();
        public GraphQL graphql;

        public static class FormField {
            public String key;
            public String value;
            public String type;   // text, file
            public boolean fileUpload;
            public String filePath;
            public boolean disabled;
            public FormField(String key, String value) { this.key = key; this.value = value; }
        }

        public static class GraphQL {
            public String query;
            public String variables; // JSON string
        }
    }

    public static class Auth {
        public String type;       // bearer, basic, apikey, oauth2, none
        public Map<String, String> properties = new HashMap<>();
    }

    public static class Variable {
        public String key;
        public String value;
        public String type;
        public boolean enabled = true;
    }

    public static class Script {
        public String type;       // js, python (basic support)
        public String exec;       // script content
        public Script(String type, String exec) { this.type = type; this.exec = exec; }
    }

    public boolean hasBody() {
        return body != null && body.mode != null && !"none".equals(body.mode);
    }

    public boolean hasAuth() {
        return auth != null && auth.type != null && !"none".equals(auth.type);
    }

    public ApiRequest applyTo(ApiRequest target) {
        if (target == null) {
            return null;
        }
        target.id = id;
        target.name = name;
        target.path = path;
        target.sourceCollection = sourceCollection;
        target.method = method;
        target.url = url;
        target.description = description;
        target.headers = headers != null ? new ArrayList<>(headers) : new ArrayList<>();
        target.body = body;
        target.auth = auth;
        target.editorMaterialized = editorMaterialized;
        target.buildMode = buildMode;
        target.suppressedAutoHeaders = suppressedAutoHeaders != null ? new LinkedHashSet<>(suppressedAutoHeaders) : new LinkedHashSet<>();
        target.variables = variables != null ? new ArrayList<>(variables) : new ArrayList<>();
        target.preRequestScripts = preRequestScripts != null ? new ArrayList<>(preRequestScripts) : new ArrayList<>();
        target.postResponseScripts = postResponseScripts != null ? new ArrayList<>(postResponseScripts) : new ArrayList<>();
        target.scriptBlocks = scriptBlocks != null ? new ArrayList<>(scriptBlocks) : new ArrayList<>();
        target.disabled = disabled;
        target.sequenceOrder = sequenceOrder;
        target.authInherited = authInherited;
        target.authExplicitlyDisabled = authExplicitlyDisabled;
        target.authSource = authSource;
        target.authOverrideMode = authOverrideMode;
        target.explicitAuth = explicitAuth;
        return target;
    }

    public boolean isAutoCompatibleMode() {
        return resolveBuildMode() == BuildMode.AUTO_COMPATIBLE && !editorMaterialized;
    }

    public boolean isManualPreserveMode() {
        return resolveBuildMode() == BuildMode.MANUAL_PRESERVE || editorMaterialized;
    }

    public BuildMode resolveBuildMode() {
        if (buildMode != null) {
            return buildMode;
        }
        return editorMaterialized ? BuildMode.MANUAL_PRESERVE : BuildMode.AUTO_COMPATIBLE;
    }

    public boolean isAutoHeaderSuppressed(String headerName) {
        String normalized = normalizeHeaderName(headerName);
        if (normalized == null || suppressedAutoHeaders == null || suppressedAutoHeaders.isEmpty()) {
            return false;
        }
        for (String suppressed : suppressedAutoHeaders) {
            if (normalized.equals(normalizeHeaderName(suppressed))) {
                return true;
            }
        }
        return false;
    }

    public void suppressAutoHeader(String headerName) {
        String normalized = normalizeHeaderName(headerName);
        if (normalized == null) {
            return;
        }
        if (suppressedAutoHeaders == null) {
            suppressedAutoHeaders = new LinkedHashSet<>();
        }
        suppressedAutoHeaders.add(normalized);
    }

    public void clearSuppressedAutoHeader(String headerName) {
        String normalized = normalizeHeaderName(headerName);
        if (normalized == null || suppressedAutoHeaders == null || suppressedAutoHeaders.isEmpty()) {
            return;
        }
        suppressedAutoHeaders.removeIf(value -> normalized.equals(normalizeHeaderName(value)));
    }

    public void normalizeSuppressedAutoHeaders() {
        if (suppressedAutoHeaders == null) {
            suppressedAutoHeaders = new LinkedHashSet<>();
            return;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String headerName : suppressedAutoHeaders) {
            String value = normalizeHeaderName(headerName);
            if (value != null) {
                normalized.add(value);
            }
        }
        suppressedAutoHeaders = normalized;
    }

    private static String normalizeHeaderName(String headerName) {
        if (headerName == null) {
            return null;
        }
        String normalized = headerName.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
