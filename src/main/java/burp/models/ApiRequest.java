package burp.models;

import java.util.*;

/**
 * Unified API Request model used across all collection formats.
 * All parsers (Postman, Bruno, OpenAPI, Insomnia, HAR) convert to this model.
 */
public class ApiRequest {
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
    public List<Variable> variables = new ArrayList<>();  // collection-level vars
    public List<Script> preRequestScripts = new ArrayList<>();
    public List<Script> postResponseScripts = new ArrayList<>();
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
}
