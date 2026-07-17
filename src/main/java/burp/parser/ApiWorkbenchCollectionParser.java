package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.scripts.ScriptBlock;
import burp.utils.AuthInheritanceResolver;
import burp.utils.RequestPathResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parser for the native API Workbench Collection JSON export format.
 */
public class ApiWorkbenchCollectionParser implements CollectionParser {
    @Override
    public boolean canParse(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                return false;
            }
            JsonObject root = parsed.getAsJsonObject();
            return "api-workbench-collection".equalsIgnoreCase(getString(root, "format"))
                    && root.has("collection")
                    && root.get("collection").isJsonObject();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ApiCollection parse(File file) throws Exception {
        JsonObject root;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new Exception("Invalid API Workbench collection export: expected JSON object at root");
            }
            root = parsed.getAsJsonObject();
        }

        if (!"api-workbench-collection".equalsIgnoreCase(getString(root, "format"))) {
            throw new Exception("Invalid API Workbench collection export: unsupported format");
        }
        if (!root.has("collection") || !root.get("collection").isJsonObject()) {
            throw new Exception("Invalid API Workbench collection export: missing collection object");
        }

        JsonObject collectionObj = root.getAsJsonObject("collection");
        ApiCollection collection = new ApiCollection();
        collection.id = getString(collectionObj, "id", null);
        collection.name = firstNonBlank(getString(collectionObj, "name"), "Untitled Collection");
        collection.description = getString(collectionObj, "description", "");
        collection.format = getString(collectionObj, "format", "api-workbench");
        collection.version = getString(collectionObj, "version", "");
        collection.sourceMetadata = parseMetadataMap(collectionObj.getAsJsonObject("sourceMetadata"));
        collection.folderPaths = parseStringList(collectionObj.getAsJsonArray("folderPaths"));
        collection.variables = parseVariables(collectionObj.getAsJsonArray("variables"));
        collection.environment = parseStringMap(collectionObj.getAsJsonObject("environment"));
        collection.auth = parseAuth(collectionObj.get("auth"));
        collection.folderAuthModes = normalizeFolderPathKeys(parseStringMap(collectionObj.getAsJsonObject("folderAuthModes")));
        collection.folderAuth = normalizeFolderPathKeys(parseAuthMap(collectionObj.getAsJsonObject("folderAuth")));
        collection.folderVars = normalizeFolderPathKeys(parseNestedStringMap(collectionObj.getAsJsonObject("folderVars")));
        collection.scriptBlocks = parseScriptBlocks(collectionObj.getAsJsonArray("scriptBlocks"));
        collection.folderScriptBlocks = parseFolderScriptBlocks(collectionObj.getAsJsonObject("folderScriptBlocks"));
        Map<ApiRequest, String> exportedAuthSources = new IdentityHashMap<>();
        collection.requests = parseRequests(collectionObj.getAsJsonArray("requests"), collection.name, exportedAuthSources);

        normalizeFolderAuthState(collection);
        AuthInheritanceResolver.recomputeCollectionAuth(collection);
        for (Map.Entry<ApiRequest, String> entry : exportedAuthSources.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                entry.getKey().authSource = entry.getValue();
            }
        }
        collection.ensureId();
        return collection;
    }

    private void normalizeFolderAuthState(ApiCollection collection) {
        if (collection == null || collection.folderAuthModes == null || collection.folderAuthModes.isEmpty()) {
            return;
        }
        if (collection.folderAuth == null) {
            collection.folderAuth = new LinkedHashMap<>();
        }
        for (Map.Entry<String, String> entry : new ArrayList<>(collection.folderAuthModes.entrySet())) {
            String normalizedPath = RequestPathResolver.normalizeFolderPath(entry.getKey());
            if (normalizedPath.isEmpty()) {
                continue;
            }
            String mode = normalizeAuthMode(entry.getValue(), collection.folderAuth.get(normalizedPath));
            collection.folderAuthModes.put(normalizedPath, mode);
            if ("inherit".equals(mode)) {
                collection.folderAuth.remove(normalizedPath);
                continue;
            }
            ApiRequest.Auth auth = collection.folderAuth.get(normalizedPath);
            if ("none".equals(mode)) {
                if (auth == null) {
                    auth = new ApiRequest.Auth();
                } else {
                    auth = copyAuth(auth);
                }
                auth.type = "none";
                collection.folderAuth.put(normalizedPath, auth);
            } else if (auth != null) {
                collection.folderAuth.put(normalizedPath, copyAuth(auth));
            }
        }
    }

    private List<ApiRequest> parseRequests(JsonArray requestsArray,
                                           String collectionName,
                                           Map<ApiRequest, String> exportedAuthSources) {
        List<ApiRequest> requests = new ArrayList<>();
        if (requestsArray == null) {
            return requests;
        }
        for (JsonElement element : requestsArray) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            ApiRequest request = new ApiRequest();
            request.id = getString(obj, "id", "");
            request.name = firstNonBlank(getString(obj, "name"), "Unnamed Request");
            request.path = RequestPathResolver.normalizeFolderPath(getString(obj, "path", ""));
            request.sourceCollection = firstNonBlank(getString(obj, "sourceCollection", ""), collectionName);
            request.method = firstNonBlank(getString(obj, "method"), "GET");
            request.url = getString(obj, "url", "");
            request.sourceMetadata = parseMetadataMap(obj.getAsJsonObject("sourceMetadata"));
            request.parameters = parseParameters(obj.getAsJsonArray("parameters"));
            request.description = getString(obj, "description", "");
            request.editorMaterialized = getBoolean(obj, "editorMaterialized", false);
            request.buildMode = parseBuildMode(getString(obj, "buildMode", null), request.editorMaterialized);
            request.suppressedAutoHeaders = parseSuppressedHeaders(obj.getAsJsonArray("suppressedAutoHeaders"));
            request.headers = parseHeaders(obj.getAsJsonArray("headers"));
            request.body = parseBody(obj.getAsJsonObject("body"));
            request.auth = parseAuth(obj.get("auth"));
            request.explicitAuth = parseAuth(obj.get("explicitAuth"));
            request.authOverrideMode = AuthInheritanceResolver.normalizeAuthOverrideMode(getString(obj, "authOverrideMode", null), request);
            request.authInherited = getBoolean(obj, "authInherited", false);
            request.authExplicitlyDisabled = getBoolean(obj, "authExplicitlyDisabled", false);
            request.authSource = getString(obj, "authSource", null);
            request.variables = parseVariables(obj.getAsJsonArray("variables"));
            request.preRequestScripts = parseScripts(obj.getAsJsonArray("preRequestScripts"));
            request.postResponseScripts = parseScripts(obj.getAsJsonArray("postResponseScripts"));
            request.scriptBlocks = parseScriptBlocks(obj.getAsJsonArray("scriptBlocks"));
            request.disabled = getBoolean(obj, "disabled", false);
            request.sequenceOrder = getInt(obj, "sequenceOrder", 0);
            request.exactHttpRequest = parseExactHttpRequest(obj.getAsJsonObject("exactHttpRequest"));
            requests.add(request);
            if (exportedAuthSources != null) {
                exportedAuthSources.put(request, request.authSource);
            }
        }
        return requests;
    }

    private List<ApiRequest.Parameter> parseParameters(JsonArray array) {
        List<ApiRequest.Parameter> parameters = new ArrayList<>();
        if (array == null) {
            return parameters;
        }
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            ApiRequest.Parameter parameter = new ApiRequest.Parameter();
            parameter.location = getString(obj, "location", "query");
            parameter.key = getString(obj, "key", null);
            parameter.value = getString(obj, "value", null);
            parameter.rawKey = getString(obj, "rawKey", null);
            parameter.rawValue = getString(obj, "rawValue", null);
            parameter.valuePresent = getBoolean(obj, "valuePresent", true);
            parameter.disabled = getBoolean(obj, "disabled", false);
            parameter.required = getBoolean(obj, "required", false);
            parameter.type = getString(obj, "type", null);
            parameter.format = getString(obj, "format", null);
            parameter.description = getString(obj, "description", null);
            parameter.style = getString(obj, "style", null);
            parameter.explode = obj.has("explode") && !obj.get("explode").isJsonNull()
                    ? obj.get("explode").getAsBoolean()
                    : null;
            parameter.allowReserved = getBoolean(obj, "allowReserved", false);
            parameter.source = getString(obj, "source", null);
            parameter.sourceMetadata = parseMetadataMap(obj.getAsJsonObject("sourceMetadata"));
            parameters.add(parameter);
        }
        return parameters;
    }

    private ExactHttpRequestSnapshot parseExactHttpRequest(JsonObject object) {
        if (object == null || object.entrySet().isEmpty()) {
            return null;
        }
        ExactHttpRequestSnapshot snapshot = new ExactHttpRequestSnapshot();
        String rawRequestBase64 = getString(object, "rawRequestBase64", null);
        if (rawRequestBase64 != null && !rawRequestBase64.isBlank()) {
            try {
                snapshot.rawRequestBytes = java.util.Base64.getDecoder().decode(rawRequestBase64);
            } catch (IllegalArgumentException ignored) {
                snapshot.rawRequestBytes = null;
            }
        }
        snapshot.serviceHost = getString(object, "serviceHost", null);
        snapshot.servicePort = getInt(object, "servicePort", 0);
        snapshot.secure = getBoolean(object, "secure", false);
        snapshot.pristine = getBoolean(object, "pristine", true);
        snapshot.binaryBody = getBoolean(object, "binaryBody", false);
        snapshot.sourceContext = getString(object, "sourceContext", null);
        snapshot.invalidationReason = getString(object, "invalidationReason", null);
        snapshot.semanticFingerprint = getString(object, "semanticFingerprint", null);
        return snapshot;
    }

    private ApiRequest.BuildMode parseBuildMode(String buildMode, boolean editorMaterialized) {
        if (buildMode != null && !buildMode.isBlank()) {
            try {
                return ApiRequest.BuildMode.valueOf(buildMode.trim().toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {
                // fall through to derived default
            }
        }
        return editorMaterialized ? ApiRequest.BuildMode.MANUAL_PRESERVE : ApiRequest.BuildMode.AUTO_COMPATIBLE;
    }

    private ApiRequest.Body parseBody(JsonObject bodyObj) {
        if (bodyObj == null) {
            return null;
        }
        if (bodyObj.entrySet().isEmpty()) {
            return null;
        }
        ApiRequest.Body body = new ApiRequest.Body();
        body.mode = getString(bodyObj, "mode", null);
        if (body.mode == null || body.mode.isBlank()) {
            if (bodyObj.has("raw")) {
                body.mode = "raw";
            } else if (bodyObj.has("urlencoded")) {
                body.mode = "urlencoded";
            } else if (bodyObj.has("formdata")) {
                body.mode = "formdata";
            } else if (bodyObj.has("graphql")) {
                body.mode = "graphql";
            } else {
                body.mode = "none";
            }
        }

        body.raw = getString(bodyObj, "raw", null);
        body.contentType = getString(bodyObj, "contentType", null);
        body.required = getBoolean(bodyObj, "required", false);
        body.description = getString(bodyObj, "description", null);
        body.filePath = getString(bodyObj, "filePath", null);
        body.source = getString(bodyObj, "source", null);
        body.sourceMetadata = parseMetadataMap(bodyObj.getAsJsonObject("sourceMetadata"));
        if (bodyObj.has("urlencoded") && bodyObj.get("urlencoded").isJsonArray()) {
            body.urlencoded = parseFormFields(bodyObj.getAsJsonArray("urlencoded"));
        }
        if (bodyObj.has("formdata") && bodyObj.get("formdata").isJsonArray()) {
            body.formdata = parseFormFields(bodyObj.getAsJsonArray("formdata"));
        }
        if (bodyObj.has("graphql") && bodyObj.get("graphql").isJsonObject()) {
            JsonObject gql = bodyObj.getAsJsonObject("graphql");
            body.graphql = new ApiRequest.Body.GraphQL();
            body.graphql.query = getString(gql, "query", null);
            body.graphql.variables = getString(gql, "variables", null);
        }
        return body;
    }

    private List<ApiRequest.Body.FormField> parseFormFields(JsonArray array) {
        List<ApiRequest.Body.FormField> fields = new ArrayList<>();
        if (array == null) {
            return fields;
        }
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(
                    getString(obj, "key", getString(obj, "name", "")),
                    getString(obj, "value", "")
            );
            field.type = getString(obj, "type", null);
            field.fileUpload = getBoolean(obj, "fileUpload", false);
            field.filePath = getString(obj, "filePath", getString(obj, "src", null));
            field.disabled = getBoolean(obj, "disabled", false);
            field.required = getBoolean(obj, "required", false);
            field.description = getString(obj, "description", null);
            field.contentType = getString(obj, "contentType", null);
            field.style = getString(obj, "style", null);
            field.explode = obj.has("explode") && !obj.get("explode").isJsonNull()
                    ? obj.get("explode").getAsBoolean() : null;
            field.allowReserved = getBoolean(obj, "allowReserved", false);
            field.source = getString(obj, "source", null);
            field.sourceMetadata = parseMetadataMap(obj.getAsJsonObject("sourceMetadata"));
            if (!field.fileUpload && "file".equalsIgnoreCase(field.type)) {
                field.fileUpload = true;
            }
            fields.add(field);
        }
        return fields;
    }

    private List<String> parseStringList(JsonArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            String value = element.isJsonPrimitive() ? element.getAsString() : element.toString();
            if (value == null) {
                continue;
            }
            String normalized = RequestPathResolver.normalizeFolderPath(value);
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return values;
    }

    private List<ApiRequest.Header> parseHeaders(JsonArray array) {
        List<ApiRequest.Header> headers = new ArrayList<>();
        if (array == null) {
            return headers;
        }
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            ApiRequest.Header header = new ApiRequest.Header(
                    getString(obj, "key", getString(obj, "name", "")),
                    getString(obj, "value", ""),
                    getBoolean(obj, "disabled", false)
            );
            headers.add(header);
        }
        return headers;
    }

    private List<ApiRequest.Variable> parseVariables(JsonArray array) {
        List<ApiRequest.Variable> variables = new ArrayList<>();
        if (array == null) {
            return variables;
        }
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            String key = getString(obj, "key", getString(obj, "name", ""));
            if (key.isBlank()) {
                continue;
            }
            ApiRequest.Variable variable = new ApiRequest.Variable();
            variable.key = key;
            variable.value = getString(obj, "value", getString(obj, "currentValue", getString(obj, "initialValue", "")));
            variable.type = getString(obj, "type", null);
            variable.enabled = getBoolean(obj, "enabled", !getBoolean(obj, "disabled", false));
            variables.add(variable);
        }
        return variables;
    }

    private List<ApiRequest.Script> parseScripts(JsonArray array) {
        List<ApiRequest.Script> scripts = new ArrayList<>();
        if (array == null) {
            return scripts;
        }
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            String exec = getString(obj, "exec", null);
            if (exec == null) {
                continue;
            }
            scripts.add(new ApiRequest.Script(getString(obj, "type", "js"), exec));
        }
        return scripts;
    }

    private List<ScriptBlock> parseScriptBlocks(JsonArray array) {
        List<ScriptBlock> blocks = new ArrayList<>();
        if (array == null) {
            return blocks;
        }
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().create();
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            try {
                ScriptBlock block = gson.fromJson(element, ScriptBlock.class);
                if (block != null) {
                    blocks.add(block);
                }
            } catch (Exception ignored) {
            }
        }
        return blocks;
    }

    private Map<String, List<ScriptBlock>> parseFolderScriptBlocks(JsonObject object) {
        Map<String, List<ScriptBlock>> blocks = new LinkedHashMap<>();
        if (object == null) {
            return blocks;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || !entry.getValue().isJsonArray()) {
                continue;
            }
            blocks.put(entry.getKey(), parseScriptBlocks(entry.getValue().getAsJsonArray()));
        }
        return blocks;
    }

    private Set<String> parseSuppressedHeaders(JsonArray array) {
        Set<String> headers = new LinkedHashSet<>();
        if (array == null) {
            return headers;
        }
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            String value = element.isJsonPrimitive() ? element.getAsString() : element.toString();
            if (value != null && !value.isBlank()) {
                headers.add(value.trim());
            }
        }
        return headers;
    }

    private Map<String, String> parseStringMap(JsonObject object) {
        Map<String, String> map = new LinkedHashMap<>();
        if (object == null) {
            return map;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isJsonNull()) {
                continue;
            }
            map.put(entry.getKey(), toStringValue(entry.getValue()));
        }
        return map;
    }

    private Map<String, String> parseMetadataMap(JsonObject object) {
        Map<String, String> map = new LinkedHashMap<>();
        if (object == null) return map;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (entry.getKey() == null || entry.getKey().isBlank() || value == null
                    || value.isJsonNull() || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()) {
                continue;
            }
            try {
                map.put(entry.getKey(), value.getAsString());
            } catch (RuntimeException ignored) {
                // Skip malformed entries independently.
            }
        }
        return map;
    }

    private <T> Map<String, T> normalizeFolderPathKeys(Map<String, T> map) {
        Map<String, T> normalized = new LinkedHashMap<>();
        if (map == null) {
            return normalized;
        }
        for (Map.Entry<String, T> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            String key = RequestPathResolver.normalizeFolderPath(entry.getKey());
            if (!key.isBlank()) {
                normalized.put(key, entry.getValue());
            }
        }
        return normalized;
    }

    private Map<String, Map<String, String>> parseNestedStringMap(JsonObject object) {
        Map<String, Map<String, String>> map = new LinkedHashMap<>();
        if (object == null) {
            return map;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || !entry.getValue().isJsonObject()) {
                continue;
            }
            map.put(RequestPathResolver.normalizeFolderPath(entry.getKey()), parseStringMap(entry.getValue().getAsJsonObject()));
        }
        return map;
    }

    private Map<String, ApiRequest.Auth> parseAuthMap(JsonObject object) {
        Map<String, ApiRequest.Auth> map = new LinkedHashMap<>();
        if (object == null) {
            return map;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            ApiRequest.Auth auth = parseAuth(entry.getValue());
            if (auth != null) {
                map.put(RequestPathResolver.normalizeFolderPath(entry.getKey()), auth);
            }
        }
        return map;
    }

    private ApiRequest.Auth parseAuth(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String type = getString(object, "type", null);
        if (type == null || type.isBlank() || "inherit".equalsIgnoreCase(type) || "inheritauth".equalsIgnoreCase(type)) {
            return null;
        }
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = normalizeAuthType(type);
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if ("type".equals(entry.getKey())) {
                continue;
            }
            if ("properties".equals(entry.getKey()) && entry.getValue() != null && entry.getValue().isJsonObject()) {
                for (Map.Entry<String, JsonElement> prop : entry.getValue().getAsJsonObject().entrySet()) {
                    if (prop.getKey() == null || prop.getKey().isBlank() || prop.getValue() == null || prop.getValue().isJsonNull()) {
                        continue;
                    }
                    auth.properties.put(prop.getKey(), toStringValue(prop.getValue()));
                }
                continue;
            }
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isJsonNull()) {
                continue;
            }
            if (entry.getValue().isJsonPrimitive()) {
                auth.properties.put(entry.getKey(), entry.getValue().getAsString());
            } else {
                auth.properties.put(entry.getKey(), entry.getValue().toString());
            }
        }
        return auth;
    }

    private String normalizeAuthMode(String mode, ApiRequest.Auth auth) {
        if (mode != null && !mode.isBlank()) {
            String normalized = mode.trim().toLowerCase(Locale.ROOT);
            if ("inherit".equals(normalized) || "explicit".equals(normalized) || "none".equals(normalized)) {
                return normalized;
            }
        }
        return auth != null && "none".equalsIgnoreCase(auth.type) ? "none" : "explicit";
    }

    private ApiRequest.Auth copyAuth(ApiRequest.Auth src) {
        if (src == null) {
            return null;
        }
        ApiRequest.Auth copy = new ApiRequest.Auth();
        copy.type = src.type;
        if (src.properties != null) {
            copy.properties.putAll(src.properties);
        }
        return copy;
    }

    private String normalizeAuthType(String type) {
        if (type == null) {
            return null;
        }
        String normalized = type.trim();
        if ("noauth".equalsIgnoreCase(normalized)) {
            return "none";
        }
        return normalized;
    }

    private String getString(JsonObject object, String key, String defaultValue) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        JsonElement element = object.get(key);
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        return defaultValue;
    }

    private String getString(JsonObject object, String key) {
        return getString(object, key, null);
    }

    private boolean getBoolean(JsonObject object, String key, boolean defaultValue) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        JsonElement element = object.get(key);
        if (element.isJsonPrimitive()) {
            try {
                return element.getAsBoolean();
            } catch (Exception ignored) {
                String text = element.getAsString();
                if (text != null) {
                    String normalized = text.trim();
                    if ("true".equalsIgnoreCase(normalized)) {
                        return true;
                    }
                    if ("false".equalsIgnoreCase(normalized)) {
                        return false;
                    }
                }
            }
        }
        return defaultValue;
    }

    private int getInt(JsonObject object, String key, int defaultValue) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        JsonElement element = object.get(key);
        if (element.isJsonPrimitive()) {
            try {
                return element.getAsInt();
            } catch (Exception ignored) {
                try {
                    return Integer.parseInt(element.getAsString().trim());
                } catch (Exception ignoredToo) {
                    // fall through
                }
            }
        }
        return defaultValue;
    }

    private String toStringValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }
        return value.toString();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @Override
    public String getFormatName() {
        return "API Workbench";
    }

    @Override
    public String[] getSupportedExtensions() {
        return new String[]{"json", "api-workbench.collection.json"};
    }
}
