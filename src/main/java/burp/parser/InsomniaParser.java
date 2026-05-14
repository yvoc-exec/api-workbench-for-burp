package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import com.google.gson.*;
import java.io.*;
import java.util.*;

/**
 * Parser for Insomnia v4 JSON exports.
 */
public class InsomniaParser implements CollectionParser {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public boolean canParse(File file) {
        if (!file.getName().endsWith(".json")) return false;
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            // Insomnia v4 has __type field or resources array
            if (obj.has("__type") && obj.get("__type").getAsString().contains("export")) return true;
            if (obj.has("resources") && obj.get("resources").isJsonArray()) {
                JsonArray resources = obj.getAsJsonArray("resources");
                for (JsonElement r : resources) {
                    JsonObject res = r.getAsJsonObject();
                    if (res.has("_type") && "request".equals(res.get("_type").getAsString())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    @Override
    public ApiCollection parse(File file) throws Exception {
        JsonObject obj;
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            obj = JsonParser.parseReader(reader).getAsJsonObject();
        }

        ApiCollection collection = new ApiCollection();
        collection.format = "insomnia";
        collection.name = getString(obj, "__export_format", "Insomnia Collection");

        JsonArray resources = obj.getAsJsonArray("resources");
        if (resources == null) return collection;

        // Build ID -> name/parent/auth maps for folders
        Map<String, String> folderNames = new HashMap<>();
        Map<String, String> folderParents = new HashMap<>();
        Map<String, ApiRequest.Auth> folderAuths = new HashMap<>();
        for (JsonElement r : resources) {
            JsonObject res = r.getAsJsonObject();
            if (res.has("_type") && "request_group".equals(res.get("_type").getAsString())) {
                String id = getString(res, "_id", "");
                String name = getString(res, "name", "");
                String parentId = getString(res, "parentId", "");
                folderNames.put(id, name);
                folderParents.put(id, parentId);
                if (res.has("authentication") && res.get("authentication").isJsonObject()) {
                    folderAuths.put(id, parseAuth(res.getAsJsonObject("authentication")));
                }
            }
        }

        // Parse requests
        for (JsonElement r : resources) {
            JsonObject res = r.getAsJsonObject();
            if (res.has("_type") && "request".equals(res.get("_type").getAsString())) {
                ApiRequest req = parseInsomniaRequest(res, folderNames, folderParents, folderAuths);
                req.sourceCollection = collection.name;
                collection.requests.add(req);
            }
        }

        return collection;
    }

    private ApiRequest parseInsomniaRequest(JsonObject res, Map<String, String> folderNames,
                                            Map<String, String> folderParents,
                                            Map<String, ApiRequest.Auth> folderAuths) {
        ApiRequest req = new ApiRequest();
        req.id = getString(res, "_id", "");
        req.name = getString(res, "name", "Unnamed");
        req.method = getString(res, "method", "GET");
        req.url = getString(res, "url", "");
        req.description = getString(res, "description", "");

        // Build path from folder hierarchy
        String parentId = getString(res, "parentId", "");
        List<String> pathParts = new ArrayList<>();
        while (parentId != null && !parentId.isEmpty() && folderNames.containsKey(parentId)) {
            pathParts.add(0, folderNames.get(parentId));
            parentId = folderParents.getOrDefault(parentId, "");
        }
        req.path = String.join("/", pathParts) + "/" + req.name;

        // Headers
        if (res.has("headers") && res.get("headers").isJsonArray()) {
            for (JsonElement h : res.getAsJsonArray("headers")) {
                JsonObject header = h.getAsJsonObject();
                boolean disabled = header.has("disabled") && header.get("disabled").getAsBoolean();
                req.headers.add(new ApiRequest.Header(
                    getString(header, "name", ""),
                    getString(header, "value", ""),
                    disabled
                ));
            }
        }

        // Body
        if (res.has("body") && res.get("body").isJsonObject()) {
            JsonObject bodyObj = res.getAsJsonObject("body");
            req.body = new ApiRequest.Body();
            req.body.mode = getString(bodyObj, "mimeType", "none");
            if (req.body.mode == null || req.body.mode.isEmpty()) req.body.mode = "none";

            switch (req.body.mode) {
                case "application/json":
                case "text/plain":
                case "application/xml":
                case "text/html":
                    req.body.mode = "raw";
                    req.body.raw = getString(bodyObj, "text", "");
                    req.body.contentType = getString(bodyObj, "mimeType", "text/plain");
                    break;
                case "application/x-www-form-urlencoded":
                    req.body.mode = "urlencoded";
                    if (bodyObj.has("params") && bodyObj.get("params").isJsonArray()) {
                        for (JsonElement p : bodyObj.getAsJsonArray("params")) {
                            JsonObject param = p.getAsJsonObject();
                            req.body.urlencoded.add(new ApiRequest.Body.FormField(
                                getString(param, "name", ""),
                                getString(param, "value", "")
                            ));
                        }
                    }
                    break;
                case "multipart/form-data":
                    req.body.mode = "formdata";
                    if (bodyObj.has("params") && bodyObj.get("params").isJsonArray()) {
                        for (JsonElement p : bodyObj.getAsJsonArray("params")) {
                            JsonObject param = p.getAsJsonObject();
                            req.body.formdata.add(new ApiRequest.Body.FormField(
                                getString(param, "name", ""),
                                getString(param, "value", "")
                            ));
                        }
                    }
                    break;
            }
        }

        // Auth
        if (res.has("authentication") && res.get("authentication").isJsonObject()) {
            req.auth = parseAuth(res.getAsJsonObject("authentication"));
        }
        // Inherit from parent groups if no local auth
        if ((req.auth == null || req.auth.type == null || "none".equals(req.auth.type))) {
            String folderId = getString(res, "parentId", "");
            ApiRequest.Auth inherited = findInheritedAuth(folderId, folderParents, folderAuths);
            if (inherited != null) {
                req.auth = deepCopyAuth(inherited);
            }
        }

        return req;
    }

    private ApiRequest.Auth parseAuth(JsonObject authObj) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = getString(authObj, "type", "none");
        for (Map.Entry<String, JsonElement> entry : authObj.entrySet()) {
            String key = entry.getKey();
            if ("type".equals(key)) continue;
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive()) {
                auth.properties.put(key, value.getAsString());
            }
        }
        // Normalize Insomnia-specific property names
        if (auth.properties.containsKey("addTo")) {
            auth.properties.put("in", auth.properties.get("addTo"));
        }
        return auth;
    }

    private ApiRequest.Auth findInheritedAuth(String folderId, Map<String, String> folderParents,
                                               Map<String, ApiRequest.Auth> folderAuths) {
        Set<String> visited = new HashSet<>();
        while (folderId != null && !folderId.isEmpty() && !visited.contains(folderId)) {
            visited.add(folderId);
            ApiRequest.Auth auth = folderAuths.get(folderId);
            if (auth != null && auth.type != null && !"none".equals(auth.type)) {
                return auth;
            }
            folderId = folderParents.getOrDefault(folderId, "");
        }
        return null;
    }

    private ApiRequest.Auth deepCopyAuth(ApiRequest.Auth src) {
        if (src == null) return null;
        ApiRequest.Auth copy = new ApiRequest.Auth();
        copy.type = src.type;
        if (src.properties != null) {
            copy.properties.putAll(src.properties);
        }
        return copy;
    }

    private String getString(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            JsonElement elem = obj.get(key);
            if (elem.isJsonPrimitive()) return elem.getAsString();
        }
        return defaultValue;
    }

    @Override
    public String getFormatName() { return "Insomnia"; }

    @Override
    public String[] getSupportedExtensions() { return new String[]{"json"}; }
}
