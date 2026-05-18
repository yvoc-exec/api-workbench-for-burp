package burp.models;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WorkspaceState {
    public int version = 1;
    public List<ApiCollection> collections = new ArrayList<>();

    public static WorkspaceState fromCollections(List<ApiCollection> source, WorkspacePersistenceOptions options) {
        WorkspaceState state = new WorkspaceState();
        if (source == null || options == null || !options.persistCollections) {
            return state;
        }
        for (ApiCollection collection : source) {
            if (collection != null) {
                state.collections.add(copyCollection(collection, options));
            }
        }
        return state;
    }

    private static ApiCollection copyCollection(ApiCollection src, WorkspacePersistenceOptions options) {
        ApiCollection copy = new ApiCollection();
        copy.name = src.name;
        copy.description = src.description;
        copy.format = src.format;
        copy.version = src.version;
        copy.auth = copyAuth(src.auth);
        copy.requests = copyRequests(src.requests);
        copy.variables = copyVariables(src.variables);
        copy.environment = src.environment != null ? new LinkedHashMap<>(src.environment) : new LinkedHashMap<>();
        copy.runtimeVars = options.persistRuntimeVars
                ? sanitizeMap(src.runtimeVars, options.persistSensitiveRuntimeValues)
                : new LinkedHashMap<>();
        copy.runtimeOAuth2 = options.persistOAuthRuntime
                ? sanitizeOAuth(src.runtimeOAuth2, options)
                : new LinkedHashMap<>();
        return copy;
    }

    private static ApiRequest.Auth copyAuth(ApiRequest.Auth src) {
        if (src == null) {
            return null;
        }
        ApiRequest.Auth copy = new ApiRequest.Auth();
        copy.type = src.type;
        copy.properties.putAll(src.properties);
        return copy;
    }

    private static List<ApiRequest> copyRequests(List<ApiRequest> src) {
        List<ApiRequest> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        for (ApiRequest req : src) {
            out.add(copyRequest(req));
        }
        return out;
    }

    private static ApiRequest copyRequest(ApiRequest src) {
        if (src == null) {
            return null;
        }
        ApiRequest copy = new ApiRequest();
        copy.id = src.id;
        copy.name = src.name;
        copy.path = src.path;
        copy.sourceCollection = src.sourceCollection;
        copy.method = src.method;
        copy.url = src.url;
        copy.description = src.description;
        copy.disabled = src.disabled;
        copy.sequenceOrder = src.sequenceOrder;
        copy.authInherited = src.authInherited;
        copy.authExplicitlyDisabled = src.authExplicitlyDisabled;
        copy.authSource = src.authSource;
        copy.auth = copyAuth(src.auth);
        copy.headers = copyHeaders(src.headers);
        copy.body = copyBody(src.body);
        copy.variables = copyVariables(src.variables);
        copy.preRequestScripts = copyScripts(src.preRequestScripts);
        copy.postResponseScripts = copyScripts(src.postResponseScripts);
        return copy;
    }

    private static List<ApiRequest.Header> copyHeaders(List<ApiRequest.Header> src) {
        List<ApiRequest.Header> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        for (ApiRequest.Header header : src) {
            if (header == null) {
                out.add(null);
                continue;
            }
            out.add(new ApiRequest.Header(header.key, header.value, header.disabled));
        }
        return out;
    }

    private static ApiRequest.Body copyBody(ApiRequest.Body src) {
        if (src == null) {
            return null;
        }
        ApiRequest.Body copy = new ApiRequest.Body();
        copy.mode = src.mode;
        copy.raw = src.raw;
        copy.contentType = src.contentType;
        copy.formdata = copyFormFields(src.formdata);
        copy.urlencoded = copyFormFields(src.urlencoded);
        copy.graphql = copyGraphQL(src.graphql);
        return copy;
    }

    private static List<ApiRequest.Body.FormField> copyFormFields(List<ApiRequest.Body.FormField> src) {
        List<ApiRequest.Body.FormField> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        for (ApiRequest.Body.FormField field : src) {
            if (field == null) {
                out.add(null);
                continue;
            }
            ApiRequest.Body.FormField copy = new ApiRequest.Body.FormField(field.key, field.value);
            copy.type = field.type;
            copy.fileUpload = field.fileUpload;
            copy.filePath = field.filePath;
            copy.disabled = field.disabled;
            out.add(copy);
        }
        return out;
    }

    private static ApiRequest.Body.GraphQL copyGraphQL(ApiRequest.Body.GraphQL src) {
        if (src == null) {
            return null;
        }
        ApiRequest.Body.GraphQL copy = new ApiRequest.Body.GraphQL();
        copy.query = src.query;
        copy.variables = src.variables;
        return copy;
    }

    private static List<ApiRequest.Variable> copyVariables(List<ApiRequest.Variable> src) {
        List<ApiRequest.Variable> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        for (ApiRequest.Variable variable : src) {
            if (variable == null) {
                out.add(null);
                continue;
            }
            ApiRequest.Variable copy = new ApiRequest.Variable();
            copy.key = variable.key;
            copy.value = variable.value;
            copy.type = variable.type;
            copy.enabled = variable.enabled;
            out.add(copy);
        }
        return out;
    }

    private static List<ApiRequest.Script> copyScripts(List<ApiRequest.Script> src) {
        List<ApiRequest.Script> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        for (ApiRequest.Script script : src) {
            if (script == null) {
                out.add(null);
                continue;
            }
            out.add(new ApiRequest.Script(script.type, script.exec));
        }
        return out;
    }

    private static Map<String, String> sanitizeMap(Map<String, String> src, boolean allowSensitive) {
        Map<String, String> out = new LinkedHashMap<>();
        if (src == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : src.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            if (!allowSensitive && looksSensitive(key)) {
                continue;
            }
            out.put(key, entry.getValue());
        }
        return out;
    }

    private static Map<String, String> sanitizeOAuth(Map<String, String> src, WorkspacePersistenceOptions options) {
        Map<String, String> out = new LinkedHashMap<>();
        if (src == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : src.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String lower = key.toLowerCase(Locale.ROOT);
            boolean token = lower.equals("oauth2_access_token") || lower.equals("oauth2_refresh_token") || lower.equals("oauth2_id_token");
            boolean secret = lower.equals("oauth2_client_secret") || lower.equals("oauth2_password");
            if (token && !options.persistOAuthTokens) {
                continue;
            }
            if (secret && !options.persistSensitiveRuntimeValues) {
                continue;
            }
            out.put(key, entry.getValue());
        }
        return out;
    }

    private static boolean looksSensitive(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("secret")
                || lower.contains("password")
                || lower.contains("token")
                || lower.contains("api_key")
                || lower.contains("apikey")
                || lower.contains("authorization");
    }
}
