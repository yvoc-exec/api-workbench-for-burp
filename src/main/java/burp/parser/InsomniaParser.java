package burp.parser;

import burp.diagnostics.DiagnosticEvent;
import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSanitizer;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.utils.AuthInheritanceResolver;
import burp.utils.RequestParameterSupport;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class InsomniaParser implements CollectionParser {
    private static final Set<String> SUPPORTED_AUTH = Set.of(
            "none", "noauth", "basic", "bearer", "apikey", "oauth2");
    private static final String[] SCRIPT_SOURCE_PROPERTIES = {"script", "code", "source", "text", "value"};
    private static final Set<String> PRE_SCRIPT_KEYS = Set.of("pre", "request", "preRequest", "before");
    private static final Set<String> POST_SCRIPT_KEYS = Set.of("post", "response", "postResponse", "after", "afterResponse");
    private static final Set<String> TRANSPORT_HEADERS = Set.of(
            "host", "content-length", "transfer-encoding", "connection", "proxy-connection");

    @Override
    public boolean canParse(File file) {
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                return false;
            }
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("__type") && string(obj, "__type", "").contains("export")) {
                return true;
            }
            JsonArray resources = array(obj, "resources");
            if (resources != null) {
                for (JsonElement element : resources) {
                    if (element != null && element.isJsonObject()
                            && "request".equals(string(element.getAsJsonObject(), "_type", ""))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    @Override
    public ApiCollection parse(File file) throws Exception {
        JsonObject root;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }

        ApiCollection collection = new ApiCollection();
        collection.format = "insomnia";
        collection.version = string(root, "__export_format", null);
        collection.name = "Insomnia Collection";
        JsonArray resources = array(root, "resources");
        if (resources == null) {
            return collection;
        }

        JsonObject workspace = firstResource(resources, "workspace");
        String workspaceId = null;
        if (workspace != null) {
            workspaceId = string(workspace, "_id", null);
            if (workspaceId != null && !workspaceId.isBlank()) {
                collection.id = workspaceId;
            }
            String name = string(workspace, "name", null);
            if (name != null && !name.isBlank()) {
                collection.name = name;
            }
            collection.description = string(workspace, "description", null);
        }

        Map<String, String> folderNames = new LinkedHashMap<>();
        Map<String, String> folderParents = new LinkedHashMap<>();
        Map<String, ApiRequest.Auth> folderAuths = new LinkedHashMap<>();
        for (JsonElement element : resources) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject resource = element.getAsJsonObject();
            String type = string(resource, "_type", "");
            if (!"request_group".equals(type) && !"folder".equals(type)) {
                continue;
            }
            String id = string(resource, "_id", "");
            folderNames.put(id, string(resource, "name", ""));
            folderParents.put(id, string(resource, "parentId", ""));
            JsonArray folderHeaders = array(resource, "headers");
            if (folderHeaders != null && !folderHeaders.isEmpty()) {
                warn(collection, buildResourceLabel(resource, "folder"),
                        "Folder headers could not be retained in the collection model.");
            }
            JsonObject authObject = object(resource, "authentication");
            ApiRequest.Auth auth = parseAuth(authObject, collection,
                    buildResourceLabel(resource, "folder"));
            if (auth != null) {
                folderAuths.put(id, auth);
            }
        }
        for (String folderId : folderNames.keySet()) {
            String path = buildFolderPath(folderId, folderNames, folderParents);
            if (!path.isBlank() && !collection.folderPaths.contains(path)) {
                collection.folderPaths.add(path);
            }
            ApiRequest.Auth auth = folderAuths.get(folderId);
            if (auth != null && !path.isBlank()) {
                AuthInheritanceResolver.setFolderAuth(collection, path,
                        AuthInheritanceResolver.normalizeParsedAuthMode(auth), auth);
            }
            JsonObject folderResource = resourceById(resources, folderId);
            importFolderScripts(folderResource, path, collection);
            JsonObject environment = object(folderResource, "environment");
            if (environment != null && !path.isBlank()) {
                Map<String, String> values = collection.folderVars.computeIfAbsent(path, ignored -> new LinkedHashMap<>());
                for (Map.Entry<String, JsonElement> entry : environment.entrySet()) {
                    if (entry.getValue() == null) continue;
                    String text = entry.getValue().isJsonNull() ? "null" : entry.getValue().isJsonPrimitive()
                            ? entry.getValue().getAsString() : entry.getValue().toString();
                    values.put(entry.getKey(), text);
                    InsomniaEnvironmentValueTypes.remember(collection, path, entry.getKey(), entry.getValue());
                }
            }
        }

        importBaseEnvironment(resources, workspaceId, collection);

        for (JsonElement element : resources) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject resource = element.getAsJsonObject();
            if (!"request".equals(string(resource, "_type", ""))) {
                continue;
            }
            try {
                ApiRequest request = parseRequest(resource, folderNames, folderParents, collection);
                request.sourceCollection = collection.name;
                collection.requests.add(request);
                collection.importedRequestCount++;
            } catch (RuntimeException exception) {
                collection.skippedRequestCount++;
                warn(collection, buildResourceLabel(resource, "request"),
                        "Malformed request resource was skipped: " + exception.getClass().getSimpleName());
            }
        }

        AuthInheritanceResolver.recomputeCollectionAuth(collection);
        return collection;
    }

    private void importFolderScripts(JsonObject resource, String folderPath, ApiCollection collection) {
        if (resource == null || folderPath == null || folderPath.isBlank()) return;
        List<ScriptBlock> target = collection.folderScriptBlocks.computeIfAbsent(
                AuthInheritanceResolver.normalizeFolderPath(folderPath), ignored -> new ArrayList<>());
        int[] order = {target.size()};
        addFolderScriptElement(resource.get("preRequestScript"), target, collection, folderPath,
                ScriptPhase.PRE_REQUEST, "preRequestScript", order);
        addFolderScriptElement(resource.get("pre_request_script"), target, collection, folderPath,
                ScriptPhase.PRE_REQUEST, "pre_request_script", order);
        addFolderScriptElement(resource.get("requestHooks"), target, collection, folderPath,
                ScriptPhase.PRE_REQUEST, "requestHooks", order);
        addFolderScriptElement(resource.get("afterResponseScript"), target, collection, folderPath,
                ScriptPhase.POST_RESPONSE, "afterResponseScript", order);
        addFolderScriptElement(resource.get("after_response_script"), target, collection, folderPath,
                ScriptPhase.POST_RESPONSE, "after_response_script", order);
        addFolderScriptElement(resource.get("responseHooks"), target, collection, folderPath,
                ScriptPhase.POST_RESPONSE, "responseHooks", order);
    }

    private void addFolderScriptElement(JsonElement element, List<ScriptBlock> target,
                                        ApiCollection collection, String folderPath,
                                        ScriptPhase phase, String sourcePath, int[] order) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            int index = 0;
            for (JsonElement child : element.getAsJsonArray()) {
                addFolderScriptElement(child, target, collection, folderPath, phase,
                        sourcePath + "[" + index++ + "]", order);
            }
            return;
        }
        String source = null;
        boolean enabled = true;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            source = element.getAsString();
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String property : SCRIPT_SOURCE_PROPERTIES) {
                if (object.has(property) && object.get(property).isJsonPrimitive()
                        && object.getAsJsonPrimitive(property).isString()) {
                    source = object.get(property).getAsString();
                    break;
                }
            }
            enabled = object.has("enabled") ? bool(object, "enabled", true) : !bool(object, "disabled", false);
        }
        if (source == null) {
            warn(collection, folderPath, "Unknown folder script object shape at '" + sourcePath
                    + "'; no source was recovered.");
            return;
        }
        ScriptBlock block = ScriptBlock.of(source, ScriptDialect.INSOMNIA, phase, ScriptScope.FOLDER);
        block.enabled = enabled;
        block.sourceFormat = "insomnia";
        block.sourcePath = sourcePath;
        block.order = order[0]++;
        target.add(block);
    }

    private ApiRequest parseRequest(JsonObject resource,
                                    Map<String, String> folderNames,
                                    Map<String, String> folderParents,
                                    ApiCollection collection) {
        requirePrimitiveWhenPresent(resource, "name");
        requirePrimitiveWhenPresent(resource, "method");
        requirePrimitiveWhenPresent(resource, "url");
        ApiRequest request = new ApiRequest();
        request.id = string(resource, "_id", "");
        request.name = string(resource, "name", "Unnamed");
        request.method = string(resource, "method", "GET");
        request.url = string(resource, "url", "");
        request.description = string(resource, "description", "");
        String folderPath = buildFolderPath(string(resource, "parentId", ""), folderNames, folderParents);
        request.path = folderPath.isBlank() ? request.name : folderPath + "/" + request.name;
        String label = request.path != null ? request.path : request.name;

        parseQueryParameters(resource, request);
        parseHeaders(resource, request);
        parseBody(resource, request, collection, label);

        if (resource.has("authentication") && resource.get("authentication").isJsonObject()) {
            ApiRequest.Auth auth = parseAuth(resource.getAsJsonObject("authentication"), collection, label);
            if (auth == null) {
                AuthInheritanceResolver.markRequestInherit(request);
            } else if ("none".equalsIgnoreCase(AuthInheritanceResolver.normalizeParsedAuthMode(auth))) {
                AuthInheritanceResolver.markRequestNoAuth(request);
            } else {
                AuthInheritanceResolver.markRequestExplicitAuth(request, auth);
            }
        } else {
            AuthInheritanceResolver.markRequestInherit(request);
        }
        parseScripts(resource, request, collection, label);
        return request;
    }

    private void parseQueryParameters(JsonObject resource, ApiRequest request) {
        JsonArray structuredArray = array(resource, "parameters");
        if (structuredArray == null) {
            structuredArray = array(resource, "queryParameters");
        }
        List<ApiRequest.Parameter> structured = null;
        if (structuredArray != null) {
            structured = new ArrayList<>();
            for (JsonElement element : structuredArray) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject row = element.getAsJsonObject();
                String key = row.has("name") ? string(row, "name", "") : string(row, "key", "");
                boolean valuePresent = row.has("value") && !row.get("value").isJsonNull();
                ApiRequest.Parameter parameter = new ApiRequest.Parameter(
                        "query", key, valuePresent ? string(row, "value", "") : "");
                parameter.valuePresent = valuePresent;
                parameter.disabled = bool(row, "disabled", false);
                parameter.description = string(row, "description", null);
                parameter.type = string(row, "type", null);
                parameter.source = "insomnia:parameters";
                structured.add(parameter);
            }
        }
        request.parameters = QueryParameterImportSupport.reconcileStructuredQueryWithRawUrl(
                request.url, structured, "insomnia:url.raw", "insomnia:url.raw-unmatched");
        JsonArray pathRows = array(resource, "pathParameters");
        if (pathRows != null) {
            for (JsonElement element : pathRows) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject row = element.getAsJsonObject();
                ApiRequest.Parameter parameter = new ApiRequest.Parameter("path",
                        row.has("name") ? string(row, "name", "") : string(row, "key", ""),
                        string(row, "value", ""));
                parameter.valuePresent = true;
                parameter.source = "insomnia:pathParameters";
                request.parameters.add(parameter);
            }
        }
        request.url = RequestParameterSupport.materializeUrl(request.url, request.parameters, null);
    }

    private void parseHeaders(JsonObject resource, ApiRequest request) {
        JsonArray headers = array(resource, "headers");
        if (headers == null) {
            return;
        }
        for (JsonElement element : headers) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject header = element.getAsJsonObject();
            String name = header.has("name") ? string(header, "name", "") : string(header, "key", "");
            if (TRANSPORT_HEADERS.contains(name.trim().toLowerCase(Locale.ROOT))) continue;
            request.headers.add(new ApiRequest.Header(
                    name,
                    string(header, "value", ""),
                    bool(header, "disabled", false)));
        }
    }

    private void parseBody(JsonObject resource, ApiRequest request, ApiCollection collection, String label) {
        JsonObject body = object(resource, "body");
        if (body == null) {
            return;
        }
        request.body = new ApiRequest.Body();
        String mimeType = string(body, "mimeType", null);
        request.body.contentType = mimeType != null && !mimeType.isBlank() ? mimeType : null;
        if ("application/x-www-form-urlencoded".equalsIgnoreCase(mimeType)) {
            request.body.mode = "urlencoded";
            parseUrlEncodedFields(array(body, "params"), request.body.urlencoded);
            return;
        }
        if ("multipart/form-data".equalsIgnoreCase(mimeType)) {
            request.body.mode = "formdata";
            parseMultipartFields(array(body, "params"), request.body.formdata);
            return;
        }
        if (body.has("text") && !body.get("text").isJsonNull()) {
            request.body.mode = "raw";
            request.body.raw = string(body, "text", "");
            return;
        }
        String filePath = firstNonNullString(body, "fileName", "filePath", "src", "file");
        if (filePath != null) {
            request.body.mode = "file";
            request.body.raw = filePath;
            warn(collection, label, "File-only body metadata was retained; file-mode transport requires later validation.");
            return;
        }
        if (mimeType != null && !mimeType.isBlank()) {
            request.body.mode = "raw";
            request.body.raw = "";
        } else {
            request.body.mode = "none";
        }
    }

    private void parseUrlEncodedFields(JsonArray params, List<ApiRequest.Body.FormField> target) {
        if (params == null) {
            return;
        }
        for (JsonElement element : params) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(
                    row.has("name") ? string(row, "name", "") : string(row, "key", ""),
                    string(row, "value", ""));
            field.disabled = bool(row, "disabled", false);
            String type = string(row, "type", null);
            field.type = type != null && !type.isBlank() ? type : "text";
            field.fileUpload = false;
            field.filePath = null;
            target.add(field);
        }
    }

    private void parseMultipartFields(JsonArray params, List<ApiRequest.Body.FormField> target) {
        if (params == null) {
            return;
        }
        for (JsonElement element : params) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(
                    row.has("name") ? string(row, "name", "") : string(row, "key", ""),
                    string(row, "value", ""));
            field.disabled = bool(row, "disabled", false);
            String suppliedType = string(row, "type", null);
            boolean file = "file".equalsIgnoreCase(suppliedType)
                    || hasNonNull(row, "fileName") || hasNonNull(row, "filePath")
                    || hasNonNull(row, "src");
            if (file) {
                field.type = "file";
                field.fileUpload = true;
                field.filePath = firstNonNullString(row, "fileName", "filePath", "src", "value");
            } else {
                field.type = suppliedType != null && !suppliedType.isBlank() ? suppliedType : "text";
                field.fileUpload = false;
                field.filePath = null;
            }
            target.add(field);
        }
    }

    private ApiRequest.Auth parseAuth(JsonObject object, ApiCollection collection, String label) {
        if (object == null) {
            return null;
        }
        if (bool(object, "disabled", false)) {
            ApiRequest.Auth none = new ApiRequest.Auth();
            none.type = "none";
            return none;
        }
        String originalType = string(object, "type", null);
        if (originalType == null || originalType.isBlank()) {
            return null;
        }
        String normalized = originalType.toLowerCase(Locale.ROOT);
        if ("noauth".equals(normalized)) {
            normalized = "none";
        } else if ("api_key".equals(normalized)) {
            normalized = "apikey";
        }
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = normalized;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if ("type".equals(entry.getKey()) || entry.getValue() == null || entry.getValue().isJsonNull()) {
                continue;
            }
            auth.properties.put(entry.getKey(), entry.getValue().isJsonPrimitive()
                    ? entry.getValue().getAsString() : entry.getValue().toString());
        }
        String addTo = auth.properties.get("addTo");
        if (addTo != null) {
            if ("queryParams".equalsIgnoreCase(addTo) || "query".equalsIgnoreCase(addTo)) {
                auth.properties.put("in", "query");
            } else if ("header".equalsIgnoreCase(addTo)) {
                auth.properties.put("in", "header");
            } else if ("cookie".equalsIgnoreCase(addTo)) {
                warn(collection, label,
                        "API-key cookie placement was retained but runtime application is unsupported.");
            }
        }
        if ("oauth2".equals(normalized) && auth.properties.containsKey("redirectUrl")) {
            auth.properties.put("redirectUri", auth.properties.get("redirectUrl"));
        }
        if (!SUPPORTED_AUTH.contains(normalized)) {
            warn(collection, label, "Unsupported authentication type '" + normalized
                    + "' was retained; runtime request building may not apply it.");
        }
        return auth;
    }

    private void parseScripts(JsonObject resource, ApiRequest request, ApiCollection collection, String label) {
        int[] order = {0};
        addScriptElement(resource.get("preRequestScript"), request, collection, label,
                ScriptPhase.PRE_REQUEST, "preRequestScript", order);
        addScriptElement(resource.get("pre_request_script"), request, collection, label,
                ScriptPhase.PRE_REQUEST, "pre_request_script", order);
        addScriptElement(resource.get("requestHooks"), request, collection, label,
                ScriptPhase.PRE_REQUEST, "requestHooks", order);
        addScriptElement(resource.get("afterResponseScript"), request, collection, label,
                ScriptPhase.POST_RESPONSE, "afterResponseScript", order);
        addScriptElement(resource.get("after_response_script"), request, collection, label,
                ScriptPhase.POST_RESPONSE, "after_response_script", order);
        addScriptElement(resource.get("responseHooks"), request, collection, label,
                ScriptPhase.POST_RESPONSE, "responseHooks", order);
        parseScriptContainer(resource.get("script"), request, collection, label, "script", order);
        parseScriptContainer(resource.get("scripts"), request, collection, label, "scripts", order);
    }

    private void parseScriptContainer(JsonElement element, ApiRequest request, ApiCollection collection,
                                      String label, String path, int[] order) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (!element.isJsonObject()) {
            warn(collection, label, "Unknown script object shape at '" + path + "'; no source was recovered.");
            return;
        }
        JsonObject container = element.getAsJsonObject();
        boolean recognized = false;
        for (Map.Entry<String, JsonElement> entry : container.entrySet()) {
            ScriptPhase phase = PRE_SCRIPT_KEYS.contains(entry.getKey()) ? ScriptPhase.PRE_REQUEST
                    : POST_SCRIPT_KEYS.contains(entry.getKey()) ? ScriptPhase.POST_RESPONSE : null;
            if (phase != null) {
                recognized = true;
                addScriptElement(entry.getValue(), request, collection, label, phase,
                        path + "." + entry.getKey(), order);
            }
        }
        if (!recognized && !container.entrySet().isEmpty()) {
            warn(collection, label, "Unknown script object shape at '" + path + "'; no source was recovered.");
        }
    }

    private void addScriptElement(JsonElement element, ApiRequest request, ApiCollection collection,
                                  String label, ScriptPhase phase, String path, int[] order) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            int index = 0;
            for (JsonElement child : element.getAsJsonArray()) {
                addScriptElement(child, request, collection, label, phase,
                        path + "[" + index++ + "]", order);
            }
            return;
        }
        String source = null;
        boolean enabled = true;
        JsonObject metadataObject = null;
        String sourceProperty = null;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            source = element.getAsString();
        } else if (element.isJsonObject()) {
            metadataObject = element.getAsJsonObject();
            for (String property : SCRIPT_SOURCE_PROPERTIES) {
                if (metadataObject.has(property) && metadataObject.get(property).isJsonPrimitive()
                        && metadataObject.getAsJsonPrimitive(property).isString()) {
                    source = metadataObject.get(property).getAsString();
                    sourceProperty = property;
                    break;
                }
            }
            enabled = metadataObject.has("enabled") ? bool(metadataObject, "enabled", true)
                    : !bool(metadataObject, "disabled", false);
            if (source == null) {
                boolean recursed = false;
                for (Map.Entry<String, JsonElement> entry : metadataObject.entrySet()) {
                    ScriptPhase nestedPhase = PRE_SCRIPT_KEYS.contains(entry.getKey()) ? ScriptPhase.PRE_REQUEST
                            : POST_SCRIPT_KEYS.contains(entry.getKey()) ? ScriptPhase.POST_RESPONSE : null;
                    if (nestedPhase != null) {
                        recursed = true;
                        addScriptElement(entry.getValue(), request, collection, label, nestedPhase,
                                path + "." + entry.getKey(), order);
                    }
                }
                if (!recursed) {
                    warn(collection, label, "Unknown script object shape at '" + path + "'; no source was recovered.");
                }
                return;
            }
        } else {
            warn(collection, label, "Unknown script object shape at '" + path + "'; no source was recovered.");
            return;
        }
        if (source == null || source.isBlank()) {
            return;
        }
        ScriptBlock block = ScriptBlock.of(source, ScriptDialect.INSOMNIA, phase, ScriptScope.REQUEST);
        block.enabled = enabled;
        block.sourceFormat = "insomnia";
        block.sourcePath = path;
        block.order = order[0]++;
        block.metadata.put("sourceField", path);
        if (sourceProperty != null) {
            block.metadata.put("sourceProperty", sourceProperty);
        }
        if (metadataObject != null) {
            String name = string(metadataObject, "name", null);
            String id = string(metadataObject, "id", string(metadataObject, "_id", null));
            if (name != null) block.metadata.put("name", name);
            if (id != null) block.metadata.put("id", id);
        }
        request.scriptBlocks.add(block);
        if (enabled) {
            ApiRequest.Script legacy = block.toLegacyScript();
            if (phase == ScriptPhase.PRE_REQUEST) {
                request.preRequestScripts.add(legacy);
            } else {
                request.postResponseScripts.add(legacy);
            }
        }
    }

    private void importBaseEnvironment(JsonArray resources, String workspaceId, ApiCollection collection) {
        List<JsonObject> environments = resourcesOfType(resources, "environment");
        if (environments.isEmpty()) {
            return;
        }
        JsonObject base = null;
        for (JsonObject environment : environments) {
            String parent = string(environment, "parentId", "");
            String id = string(environment, "_id", "");
            if ((workspaceId != null && workspaceId.equals(parent))
                    || id.toUpperCase(Locale.ROOT).contains("BASE_ENVIRONMENT")) {
                base = environment;
                break;
            }
        }
        if (base == null && environments.size() == 1) {
            base = environments.get(0);
        }
        if (base != null) {
            JsonObject data = object(base, "data");
            if (data != null) {
                for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
                    if (entry.getValue() == null) continue;
                    String text = entry.getValue().isJsonNull() ? "null" : entry.getValue().isJsonPrimitive()
                            ? entry.getValue().getAsString() : entry.getValue().toString();
                    collection.environment.put(entry.getKey(), text);
                    InsomniaEnvironmentValueTypes.remember(collection, "", entry.getKey(), entry.getValue());
                }
            }
        }
        for (JsonObject environment : environments) {
            if (environment != base) {
                warn(collection, string(environment, "name", "Unnamed environment"),
                        "Child environment '" + string(environment, "name", "Unnamed environment")
                                + "' was not flattened; collection import supports the base environment only.");
            }
        }
    }

    private String buildFolderPath(String folderId, Map<String, String> names, Map<String, String> parents) {
        if (folderId == null || folderId.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = folderId;
        while (current != null && !current.isBlank() && names.containsKey(current) && visited.add(current)) {
            String name = names.get(current);
            if (name != null && !name.isBlank()) {
                parts.add(0, name);
            }
            current = parents.getOrDefault(current, "");
        }
        return String.join("/", parts);
    }

    private void warn(ApiCollection collection, String path, String reason) {
        if (collection == null || reason == null || reason.isBlank()) {
            return;
        }
        String safePath = sanitizeWarningLabel(DiagnosticSanitizer.sanitizeText(path != null ? path : "unknown resource"));
        String safeReason = sanitizeWarningLabel(DiagnosticSanitizer.sanitizeText(reason));
        String message = "Insomnia import warning for \"" + safePath + "\": " + safeReason;
        collection.importWarnings.add(message);
        DiagnosticStore.getInstance().record(DiagnosticEvent.of(
                        DiagnosticOperation.IMPORT, DiagnosticSeverity.WARNING,
                        "InsomniaParser", "Insomnia import warning")
                .withAttribute("path", safePath)
                .withAttribute("reason", safeReason)
                .withDetails(message));
    }

    private String sanitizeWarningLabel(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\r\\n\\u0085\\u2028\\u2029\\x00-\\x09\\x0B\\x0C\\x0E-\\x1F\\x7F]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static boolean hasAny(JsonObject object, String... names) {
        if (object == null || names == null) return false;
        for (String name : names) {
            if (name != null && object.has(name)) return true;
        }
        return false;
    }

    private static JsonObject firstResource(JsonArray resources, String type) {
        for (JsonElement element : resources) {
            if (element != null && element.isJsonObject()
                    && type.equals(string(element.getAsJsonObject(), "_type", ""))) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private static JsonObject resourceById(JsonArray resources, String id) {
        if (resources == null || id == null) return null;
        for (JsonElement element : resources) {
            if (element != null && element.isJsonObject()
                    && id.equals(string(element.getAsJsonObject(), "_id", null))) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private static List<JsonObject> resourcesOfType(JsonArray resources, String type) {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement element : resources) {
            if (element != null && element.isJsonObject()
                    && type.equals(string(element.getAsJsonObject(), "_type", ""))) {
                result.add(element.getAsJsonObject());
            }
        }
        return result;
    }

    private static String buildResourceLabel(JsonObject object, String fallback) {
        String name = string(object, "name", null);
        String id = string(object, "_id", null);
        return name != null && !name.isBlank() ? name : id != null && !id.isBlank() ? id : fallback;
    }

    private static boolean hasNonNull(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull();
    }

    private static void requirePrimitiveWhenPresent(JsonObject object, String key) {
        if (object != null && object.has(key) && !object.get(key).isJsonNull()
                && !object.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("Invalid " + key);
        }
    }

    private static String firstNonNullString(JsonObject object, String... keys) {
        for (String key : keys) {
            if (hasNonNull(object, key)) {
                return string(object, key, "");
            }
        }
        return null;
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : null;
    }

    private static JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject()
                ? object.getAsJsonObject(key) : null;
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (object != null && object.has(key) && !object.get(key).isJsonNull()
                && object.get(key).isJsonPrimitive()) {
            return object.get(key).getAsString();
        }
        return fallback;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    @Override
    public String getFormatName() {
        return "Insomnia";
    }

    @Override
    public String[] getSupportedExtensions() {
        return new String[]{"json"};
    }
}
