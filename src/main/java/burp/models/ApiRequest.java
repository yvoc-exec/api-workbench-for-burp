package burp.models;

import burp.history.HistoryBodyTruncator;
import burp.scripts.ScriptBlock;

import java.util.*;

/**
 * Unified API Request model used across all collection formats.
 * All parsers (Postman, Bruno, OpenAPI, Insomnia, HAR) convert to this model.
 */
public class ApiRequest {
    public enum BuildMode {
        AUTO_COMPATIBLE,
        MANUAL_PRESERVE,
        EXACT_HTTP
    }

    public String id;
    public String name;
    public String path;           // folder/path hierarchy
    public String sourceCollection; // which collection this request came from
    public String method;
    public String url;
    public String description;
    /** Format-specific request metadata retained without participating in transport. */
    public Map<String, String> sourceMetadata = new LinkedHashMap<>();
    public List<Parameter> parameters = new ArrayList<>();
    public List<Header> headers = new ArrayList<>();
    public Body body;
    public Auth auth;
    public ExactHttpRequestSnapshot exactHttpRequest;
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

    public static class Parameter {
        public String location = "query";
        public String key;
        public String value;

        /**
         * Original encoded transport components. These are reused only while they
         * still decode to the current key/value.
         */
        public String rawKey;
        public String rawValue;

        /**
         * Distinguishes ?flag from ?flag=.
         */
        public boolean valuePresent = true;

        public boolean disabled;
        public boolean required;
        public String type;
        public String format;
        public String description;
        public String style;
        public Boolean explode;
        public boolean allowReserved;
        public String source;
        public Map<String, String> sourceMetadata = new LinkedHashMap<>();

        public Parameter() {
        }

        public Parameter(String location, String key, String value) {
            this.location = location != null && !location.isBlank() ? location : "query";
            this.key = key;
            this.value = value;
        }

        public boolean isQuery() {
            return location == null || location.isBlank() || "query".equalsIgnoreCase(location);
        }
    }

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
        public boolean required;
        public String description;
        public String filePath;
        public String source;
        public Map<String, String> sourceMetadata = new LinkedHashMap<>();
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
            public boolean required;
            public String description;
            public String contentType;
            public String style;
            public Boolean explode;
            public boolean allowReserved;
            public String source;
            public Map<String, String> sourceMetadata = new LinkedHashMap<>();
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
        target.sourceMetadata = sourceMetadata != null ? new LinkedHashMap<>(sourceMetadata) : new LinkedHashMap<>();
        target.parameters = copyParameters(parameters);
        target.headers = copyHeaders(headers);
        target.body = copyBody(body);
        target.auth = copyAuth(auth);
        target.exactHttpRequest = ExactHttpRequestSnapshot.copyOf(exactHttpRequest);
        target.editorMaterialized = editorMaterialized;
        target.buildMode = buildMode;
        target.suppressedAutoHeaders = suppressedAutoHeaders != null ? new LinkedHashSet<>(suppressedAutoHeaders) : new LinkedHashSet<>();
        target.variables = copyVariables(variables);
        target.preRequestScripts = copyScripts(preRequestScripts);
        target.postResponseScripts = copyScripts(postResponseScripts);
        target.scriptBlocks = copyScriptBlocks(scriptBlocks);
        target.disabled = disabled;
        target.sequenceOrder = sequenceOrder;
        target.authInherited = authInherited;
        target.authExplicitlyDisabled = authExplicitlyDisabled;
        target.authSource = authSource;
        target.authOverrideMode = authOverrideMode;
        target.explicitAuth = copyAuth(explicitAuth);
        return target;
    }

    public void invalidateExactTransport(String reason) {
        if (exactHttpRequest == null) {
            return;
        }
        exactHttpRequest.pristine = false;
        exactHttpRequest.invalidationReason = reason != null ? reason : "";
    }

    public String computeSemanticFingerprint() {
        return computeSemanticFingerprint(true);
    }

    public String computeLegacySemanticFingerprintV1() {
        return computeSemanticFingerprint(false);
    }

    private String computeSemanticFingerprint(boolean includeExactHttpVersion) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(method != null ? method : "").append('\n');
        canonical.append(url != null ? url : "").append('\n');
        appendParameters(canonical, parameters);
        canonical.append(description != null ? description : "").append('\n');
        canonical.append(buildMode != null ? buildMode.name() : "").append('\n');
        if (includeExactHttpVersion) {
            canonical.append(exactHttpRequest != null && exactHttpRequest.httpVersion != null
                    ? exactHttpRequest.httpVersion
                    : "").append('\n');
        }
        canonical.append(authOverrideMode != null ? authOverrideMode : "").append('\n');
        appendHeaders(canonical, headers);
        appendBody(canonical, body);
        appendAuth(canonical, auth);
        appendAuth(canonical, explicitAuth);
        appendVariables(canonical, variables);
        return HistoryBodyTruncator.sha256Hex(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public boolean isAutoCompatibleMode() {
        return resolveBuildMode() == BuildMode.AUTO_COMPATIBLE && !editorMaterialized;
    }

    public boolean isManualPreserveMode() {
        BuildMode mode = resolveBuildMode();
        return mode == BuildMode.MANUAL_PRESERVE || (editorMaterialized && mode != BuildMode.EXACT_HTTP);
    }

    public boolean isExactHttpMode() {
        return resolveBuildMode() == BuildMode.EXACT_HTTP;
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

    private static void appendHeaders(StringBuilder canonical, List<Header> source) {
        if (source == null) {
            return;
        }
        for (Header header : source) {
            canonical.append("H:");
            canonical.append(header != null && header.key != null ? header.key : "");
            canonical.append('=');
            canonical.append(header != null && header.value != null ? header.value : "");
            canonical.append('|');
            canonical.append(header != null && header.disabled);
            canonical.append('\n');
        }
    }

    private static void appendParameters(StringBuilder canonical, List<Parameter> source) {
        if (source == null) {
            return;
        }
        for (Parameter parameter : source) {
            if (parameter == null) {
                continue;
            }
            canonical.append("P:");
            canonical.append(parameter.location != null ? parameter.location : "").append('|');
            canonical.append(parameter.key != null ? parameter.key : "").append('|');
            canonical.append(parameter.value != null ? parameter.value : "").append('|');
            canonical.append(parameter.rawKey != null ? parameter.rawKey : "").append('|');
            canonical.append(parameter.rawValue != null ? parameter.rawValue : "").append('|');
            canonical.append(parameter.valuePresent).append('|');
            canonical.append(parameter.disabled).append('|');
            canonical.append(parameter.required).append('|');
            canonical.append(parameter.type != null ? parameter.type : "").append('|');
            canonical.append(parameter.description != null ? parameter.description : "").append('|');
            canonical.append(parameter.style != null ? parameter.style : "").append('|');
            canonical.append(parameter.explode == null ? "null" : parameter.explode).append('|');
            canonical.append(parameter.allowReserved).append('|');
            canonical.append(parameter.source != null ? parameter.source : "").append('\n');
        }
    }

    private static void appendBody(StringBuilder canonical, Body source) {
        if (source == null) {
            return;
        }
        canonical.append("B:").append(source.mode != null ? source.mode : "").append('\n');
        canonical.append(source.raw != null ? source.raw : "").append('\n');
        canonical.append(source.contentType != null ? source.contentType : "").append('\n');
        canonical.append(source.filePath != null ? source.filePath : "").append('\n');
        if (source.graphql != null) {
            canonical.append(source.graphql.query != null ? source.graphql.query : "").append('\n');
            canonical.append(source.graphql.variables != null ? source.graphql.variables : "").append('\n');
        }
        appendFormFields(canonical, source.urlencoded, "U");
        appendFormFields(canonical, source.formdata, "F");
    }

    private static void appendFormFields(StringBuilder canonical, List<Body.FormField> source, String prefix) {
        if (source == null) {
            return;
        }
        for (Body.FormField field : source) {
            canonical.append(prefix).append(':');
            canonical.append(field != null && field.key != null ? field.key : "");
            canonical.append('=');
            canonical.append(field != null && field.value != null ? field.value : "");
            canonical.append('|');
            canonical.append(field != null && field.type != null ? field.type : "");
            canonical.append('|');
            canonical.append(field != null && field.fileUpload);
            canonical.append('|');
            canonical.append(field != null && field.filePath != null ? field.filePath : "");
            canonical.append('|');
            canonical.append(field != null && field.disabled);
            canonical.append('|');
            canonical.append(field != null && field.style != null ? field.style : "").append('|');
            canonical.append(field != null && field.explode != null ? field.explode : "null").append('|');
            canonical.append(field != null && field.allowReserved).append('|');
            canonical.append(field != null && field.contentType != null ? field.contentType : "");
            canonical.append('\n');
        }
    }

    private static void appendAuth(StringBuilder canonical, Auth source) {
        if (source == null) {
            return;
        }
        canonical.append("A:").append(source.type != null ? source.type : "").append('\n');
        source.properties.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append(entry.getKey() != null ? entry.getKey() : "")
                        .append('=')
                        .append(entry.getValue() != null ? entry.getValue() : "")
                        .append('\n'));
    }

    private static void appendVariables(StringBuilder canonical, List<Variable> source) {
        if (source == null) {
            return;
        }
        for (Variable variable : source) {
            canonical.append("V:");
            canonical.append(variable != null && variable.key != null ? variable.key : "");
            canonical.append('=');
            canonical.append(variable != null && variable.value != null ? variable.value : "");
            canonical.append('|');
            canonical.append(variable != null && variable.type != null ? variable.type : "");
            canonical.append('|');
            canonical.append(variable != null && variable.enabled);
            canonical.append('\n');
        }
    }

    private static List<Header> copyHeaders(List<Header> source) {
        List<Header> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (Header header : source) {
            if (header == null) {
                copy.add(null);
            } else {
                copy.add(new Header(header.key, header.value, header.disabled));
            }
        }
        return copy;
    }

    private static List<Parameter> copyParameters(List<Parameter> source) {
        List<Parameter> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (Parameter parameter : source) {
            if (parameter == null) {
                copy.add(null);
                continue;
            }
            Parameter item = new Parameter();
            item.location = parameter.location;
            item.key = parameter.key;
            item.value = parameter.value;
            item.rawKey = parameter.rawKey;
            item.rawValue = parameter.rawValue;
            item.valuePresent = parameter.valuePresent;
            item.disabled = parameter.disabled;
            item.required = parameter.required;
            item.type = parameter.type;
            item.format = parameter.format;
            item.description = parameter.description;
            item.style = parameter.style;
            item.explode = parameter.explode;
            item.allowReserved = parameter.allowReserved;
            item.source = parameter.source;
            item.sourceMetadata = parameter.sourceMetadata != null
                    ? new LinkedHashMap<>(parameter.sourceMetadata) : new LinkedHashMap<>();
            copy.add(item);
        }
        return copy;
    }

    private static Body copyBody(Body source) {
        if (source == null) {
            return null;
        }
        Body copy = new Body();
        copy.mode = source.mode;
        copy.raw = source.raw;
        copy.contentType = source.contentType;
        copy.required = source.required;
        copy.description = source.description;
        copy.filePath = source.filePath;
        copy.source = source.source;
        copy.sourceMetadata = source.sourceMetadata != null
                ? new LinkedHashMap<>(source.sourceMetadata) : new LinkedHashMap<>();
        if (source.graphql != null) {
            copy.graphql = new Body.GraphQL();
            copy.graphql.query = source.graphql.query;
            copy.graphql.variables = source.graphql.variables;
        }
        copy.formdata = copyFormFields(source.formdata);
        copy.urlencoded = copyFormFields(source.urlencoded);
        return copy;
    }

    private static List<Body.FormField> copyFormFields(List<Body.FormField> source) {
        List<Body.FormField> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (Body.FormField field : source) {
            if (field == null) {
                copy.add(null);
                continue;
            }
            Body.FormField formField = new Body.FormField(field.key, field.value);
            formField.type = field.type;
            formField.fileUpload = field.fileUpload;
            formField.filePath = field.filePath;
            formField.disabled = field.disabled;
            formField.required = field.required;
            formField.description = field.description;
            formField.contentType = field.contentType;
            formField.style = field.style;
            formField.explode = field.explode;
            formField.allowReserved = field.allowReserved;
            formField.source = field.source;
            formField.sourceMetadata = field.sourceMetadata != null
                    ? new LinkedHashMap<>(field.sourceMetadata) : new LinkedHashMap<>();
            copy.add(formField);
        }
        return copy;
    }

    private static Auth copyAuth(Auth source) {
        if (source == null) {
            return null;
        }
        Auth copy = new Auth();
        copy.type = source.type;
        if (source.properties != null) {
            copy.properties.putAll(source.properties);
        }
        return copy;
    }

    private static List<Variable> copyVariables(List<Variable> source) {
        List<Variable> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (Variable variable : source) {
            if (variable == null) {
                copy.add(null);
                continue;
            }
            Variable item = new Variable();
            item.key = variable.key;
            item.value = variable.value;
            item.type = variable.type;
            item.enabled = variable.enabled;
            copy.add(item);
        }
        return copy;
    }

    private static List<Script> copyScripts(List<Script> source) {
        List<Script> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (Script script : source) {
            if (script == null) {
                copy.add(null);
            } else {
                copy.add(new Script(script.type, script.exec));
            }
        }
        return copy;
    }

    private static List<ScriptBlock> copyScriptBlocks(List<ScriptBlock> source) {
        List<ScriptBlock> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (ScriptBlock block : source) {
            copy.add(ScriptBlock.copyOf(block));
        }
        return copy;
    }
}
