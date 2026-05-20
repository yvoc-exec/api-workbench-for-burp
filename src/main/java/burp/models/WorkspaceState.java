package burp.models;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WorkspaceState {
    public int version = 1;
    public List<ApiCollection> collections = new ArrayList<>();
    public int selectedTabIndex = 0;
    public String selectedVariablesCollectionName;
    public String selectedOAuth2CollectionName;
    public String selectedRequestCollectionName;
    public String selectedRequestName;
    public String selectedRequestPath;
    public List<String> checkedRequestKeys = new ArrayList<>();
    public List<String> expandedTreePathKeys = new ArrayList<>();
    public Map<String, String> requestTreePaths = new LinkedHashMap<>();

    public static WorkspaceState fromCollections(List<ApiCollection> source) {
        WorkspaceState state = new WorkspaceState();
        state.collections = copyCollections(source);
        return state;
    }

    public static WorkspaceState copyOf(WorkspaceState source) {
        WorkspaceState copy = new WorkspaceState();
        if (source == null) {
            return copy;
        }
        copy.version = source.version > 0 ? source.version : 1;
        copy.collections = copyCollections(source.collections);
        copy.selectedTabIndex = source.selectedTabIndex;
        copy.selectedVariablesCollectionName = source.selectedVariablesCollectionName;
        copy.selectedOAuth2CollectionName = source.selectedOAuth2CollectionName;
        copy.selectedRequestCollectionName = source.selectedRequestCollectionName;
        copy.selectedRequestName = source.selectedRequestName;
        copy.selectedRequestPath = source.selectedRequestPath;
        copy.checkedRequestKeys = source.checkedRequestKeys != null ? new ArrayList<>(source.checkedRequestKeys) : new ArrayList<>();
        copy.expandedTreePathKeys = source.expandedTreePathKeys != null ? new ArrayList<>(source.expandedTreePathKeys) : new ArrayList<>();
        copy.requestTreePaths = source.requestTreePaths != null ? new LinkedHashMap<>(source.requestTreePaths) : new LinkedHashMap<>();
        return copy;
    }

    private static List<ApiCollection> copyCollections(List<ApiCollection> source) {
        List<ApiCollection> out = new ArrayList<>();
        if (source == null) {
            return out;
        }
        for (ApiCollection collection : source) {
            if (collection != null) {
                out.add(copyCollection(collection));
            }
        }
        return out;
    }

    private static ApiCollection copyCollection(ApiCollection src) {
        ApiCollection copy = new ApiCollection();
        copy.name = src.name;
        copy.description = src.description;
        copy.format = src.format;
        copy.version = src.version;
        copy.auth = copyAuth(src.auth);
        copy.folderAuthModes = src.folderAuthModes != null ? new LinkedHashMap<>(src.folderAuthModes) : new LinkedHashMap<>();
        copy.folderAuth = copyAuthMap(src.folderAuth);
        copy.requests = copyRequests(src.requests);
        copy.variables = copyVariables(src.variables);
        copy.folderVars = copyNestedStringMap(src.folderVars);
        copy.environment = src.environment != null ? new LinkedHashMap<>(src.environment) : new LinkedHashMap<>();
        copy.runtimeVars = src.runtimeVars != null ? new LinkedHashMap<>(src.runtimeVars) : new LinkedHashMap<>();
        copy.runtimeOAuth2 = src.runtimeOAuth2 != null ? new LinkedHashMap<>(src.runtimeOAuth2) : new LinkedHashMap<>();
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
        copy.authOverrideMode = src.authOverrideMode;
        copy.explicitAuth = copyAuth(src.explicitAuth);
        copy.auth = copyAuth(src.auth);
        copy.headers = copyHeaders(src.headers);
        copy.body = copyBody(src.body);
        copy.variables = copyVariables(src.variables);
        copy.preRequestScripts = copyScripts(src.preRequestScripts);
        copy.postResponseScripts = copyScripts(src.postResponseScripts);
        return copy;
    }

    private static Map<String, ApiRequest.Auth> copyAuthMap(Map<String, ApiRequest.Auth> src) {
        Map<String, ApiRequest.Auth> out = new LinkedHashMap<>();
        if (src == null) {
            return out;
        }
        for (Map.Entry<String, ApiRequest.Auth> entry : src.entrySet()) {
            if (entry.getKey() != null) {
                out.put(entry.getKey(), copyAuth(entry.getValue()));
            }
        }
        return out;
    }

    private static Map<String, Map<String, String>> copyNestedStringMap(Map<String, Map<String, String>> src) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (src == null) {
            return out;
        }
        for (Map.Entry<String, Map<String, String>> entry : src.entrySet()) {
            if (entry.getKey() != null) {
                out.put(entry.getKey(), entry.getValue() != null
                        ? new LinkedHashMap<>(entry.getValue())
                        : new LinkedHashMap<>());
            }
        }
        return out;
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
}
