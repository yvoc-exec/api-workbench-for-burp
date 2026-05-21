package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.AuthInheritanceResolver;
import com.google.gson.*;
import java.io.*;
import java.util.*;

/**
 * Parser for Postman Collections (v2.0, v2.1) and Environments.
 */
public class PostmanParser implements CollectionParser {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private static class AuthContext {
        final ApiRequest.Auth auth;
        final String source;
        final boolean explicitNoAuth;

        AuthContext(ApiRequest.Auth auth, String source) {
            this(auth, source, false);
        }

        AuthContext(ApiRequest.Auth auth, String source, boolean explicitNoAuth) {
            this.auth = auth;
            this.source = source;
            this.explicitNoAuth = explicitNoAuth;
        }
    }

    @Override
    public boolean canParse(File file) {
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            // Postman collection has info.schema or info._postman_id
            if (obj.has("info") && obj.get("info").isJsonObject()) {
                JsonObject info = obj.getAsJsonObject("info");
                return info.has("schema") || info.has("_postman_id");
            }
            // Wrapped format (crAPI)
            if (obj.has("collection") && obj.get("collection").isJsonObject()) {
                JsonObject col = obj.getAsJsonObject("collection");
                if (col.has("info") && col.get("info").isJsonObject()) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    @Override
    public ApiCollection parse(File file) throws Exception {
        JsonObject jsonObject;
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
        }

        JsonObject collectionObj;
        if (jsonObject.has("collection") && jsonObject.get("collection").isJsonObject()) {
            collectionObj = jsonObject.getAsJsonObject("collection");
        } else {
            collectionObj = jsonObject;
        }

        if (collectionObj == null) {
            throw new Exception("Failed to parse collection: null object");
        }
        ApiCollection collection = new ApiCollection();
        JsonObject info = collectionObj.getAsJsonObject("info");
        if (info == null) {
            throw new Exception("Invalid Postman collection: missing 'info' object");
        }
        collection.name = getString(info, "name", "Unnamed Postman Collection");
        collection.description = getString(info, "description", "");
        collection.format = "postman";
        collection.version = getString(info, "schema", "v2.1");

        // Collection variables
        if (collectionObj.has("variable") && collectionObj.get("variable").isJsonArray()) {
            for (JsonElement v : collectionObj.getAsJsonArray("variable")) {
                JsonObject var = v.getAsJsonObject();
                ApiRequest.Variable cv = new ApiRequest.Variable();
                cv.key = getString(var, "key", "");
                cv.value = extractVariableValue(var, "value");
                cv.type = getString(var, "type", "string");
                collection.variables.add(cv);
            }
        }

        // Collection-level auth inheritance
        AuthContext collectionAuth = null;
        if (collectionObj.has("auth") && collectionObj.get("auth").isJsonObject()) {
            collection.auth = parseAuth(collectionObj.getAsJsonObject("auth"));
            if (collection.auth != null && collection.auth.type != null && !"none".equalsIgnoreCase(collection.auth.type)) {
                collectionAuth = new AuthContext(deepCopyAuth(collection.auth), "collection: " + collection.name);
            }
        }

        // Parse items recursively
        if (collectionObj.has("item") && collectionObj.get("item").isJsonArray()) {
            parseItems(collectionObj.getAsJsonArray("item"), "", collection, collectionAuth);
        }

        AuthInheritanceResolver.recomputeCollectionAuth(collection);

        return collection;
    }

    private void parseItems(JsonArray items, String path, ApiCollection collection, AuthContext inheritedAuth) {
        for (JsonElement elem : items) {
            JsonObject item = elem.getAsJsonObject();
            String name = getString(item, "name", "Unnamed");
            String currentPath = path.isEmpty() ? name : path + "/" + name;

            // Folder-level auth overrides collection-level auth
            AuthContext nextInherited = inheritedAuth;
            if (item.has("auth") && item.get("auth").isJsonObject()) {
                JsonObject authObj = item.getAsJsonObject("auth");
                ApiRequest.Auth folderAuth = parseAuth(authObj);
                storeFolderAuth(collection, currentPath, authObj, folderAuth);
                String folderAuthMode = determineAuthMode(authObj, folderAuth);
                if ("none".equalsIgnoreCase(folderAuthMode) && folderAuth != null) {
                    nextInherited = new AuthContext(deepCopyAuth(folderAuth), "folder: " + currentPath, true);
                } else if ("explicit".equalsIgnoreCase(folderAuthMode) && folderAuth != null) {
                    nextInherited = new AuthContext(deepCopyAuth(folderAuth), "folder: " + currentPath);
                }
            }

            if (item.has("request") && item.get("request").isJsonObject()) {
                JsonObject reqObj = item.getAsJsonObject("request");
                ApiRequest req = parseRequest(reqObj, name, currentPath, nextInherited);
                req.sourceCollection = collection.name;

                // Item-level events take priority; fall back to request-level events
                if (item.has("event") && item.get("event").isJsonArray()) {
                    parseEvents(item.getAsJsonArray("event"), req);
                } else if (reqObj.has("event") && reqObj.get("event").isJsonArray()) {
                    parseEvents(reqObj.getAsJsonArray("event"), req);
                }

                collection.requests.add(req);
            }

            // Nested folders
            if (item.has("item") && item.get("item").isJsonArray()) {
                parseItems(item.getAsJsonArray("item"), currentPath, collection, nextInherited);
            }
        }
    }

    private ApiRequest parseRequest(JsonObject reqObj, String name, String path, AuthContext inheritedAuth) {
        ApiRequest req = new ApiRequest();
        req.name = name;
        req.path = path;
        req.method = getString(reqObj, "method", "GET");
        req.description = getString(reqObj, "description", "");

        // URL - handle both string and object
        req.url = parsePostmanUrl(reqObj.get("url"));

        // Headers
        if (reqObj.has("header") && reqObj.get("header").isJsonArray()) {
            for (JsonElement h : reqObj.getAsJsonArray("header")) {
                JsonObject header = h.getAsJsonObject();
                boolean disabled = header.has("disabled") && header.get("disabled").getAsBoolean();
                req.headers.add(new ApiRequest.Header(
                    getString(header, "key", ""),
                    getString(header, "value", ""),
                    disabled
                ));
            }
        }

        // Body
        if (reqObj.has("body") && reqObj.get("body").isJsonObject()) {
            req.body = parseBody(reqObj.getAsJsonObject("body"));
        }

        // Auth (inherit from folder/collection if request has none)
        if (reqObj.has("auth") && reqObj.get("auth").isJsonObject()) {
            JsonObject authObj = reqObj.getAsJsonObject("auth");
            ApiRequest.Auth explicitAuth = parseAuth(authObj);
            String authMode = determineAuthMode(authObj, explicitAuth);
            if ("inherit".equalsIgnoreCase(authMode)) {
                req.authOverrideMode = "inherit";
                req.explicitAuth = null;
                applyInheritedAuth(req, inheritedAuth);
            } else {
                req.authOverrideMode = authMode != null ? authMode : "explicit";
                req.explicitAuth = deepCopyAuth(explicitAuth);
                req.auth = explicitAuth;
                req.authInherited = false;
                req.authSource = "request: " + name;
                req.authExplicitlyDisabled = "none".equalsIgnoreCase(req.auth.type);
            }
        } else {
            req.authOverrideMode = "inherit";
            req.explicitAuth = null;
            applyInheritedAuth(req, inheritedAuth);
        }

        // Events parsed outside parseRequest to allow item-level override

        return req;
    }

    private void storeFolderAuth(ApiCollection collection, String folderPath, JsonObject authObj, ApiRequest.Auth parsedAuth) {
        if (collection == null || authObj == null) {
            return;
        }
        String normalizedPath = AuthInheritanceResolver.normalizeFolderPath(folderPath);
        if (normalizedPath.isEmpty()) {
            return;
        }
        String mode = determineAuthMode(authObj, parsedAuth);
        if (mode == null) {
            return;
        }
        if (collection.folderAuthModes == null) {
            collection.folderAuthModes = new LinkedHashMap<>();
        }
        if (collection.folderAuth == null) {
            collection.folderAuth = new LinkedHashMap<>();
        }
        collection.folderAuthModes.put(normalizedPath, mode);
        if ("inherit".equalsIgnoreCase(mode)) {
            collection.folderAuth.remove(normalizedPath);
            return;
        }
        if ("none".equalsIgnoreCase(mode)) {
            ApiRequest.Auth none = parsedAuth != null ? deepCopyAuth(parsedAuth) : new ApiRequest.Auth();
            none.type = "none";
            collection.folderAuth.put(normalizedPath, none);
            return;
        }
        collection.folderAuth.put(normalizedPath, deepCopyAuth(parsedAuth));
    }

    private String determineAuthMode(JsonObject authObj, ApiRequest.Auth parsedAuth) {
        if (authObj == null) {
            return null;
        }
        String rawType = getString(authObj, "type", "");
        if (rawType == null || rawType.isBlank()) {
            return parsedAuth != null && parsedAuth.type != null ? "explicit" : null;
        }
        if ("inherit".equalsIgnoreCase(rawType) || "inheritauth".equalsIgnoreCase(rawType)) {
            return "inherit";
        }
        if ("noauth".equalsIgnoreCase(rawType) || "none".equalsIgnoreCase(rawType)) {
            return "none";
        }
        return "explicit";
    }

    private void applyInheritedAuth(ApiRequest req, AuthContext inheritedAuth) {
        if (inheritedAuth != null && inheritedAuth.auth != null) {
            req.auth = deepCopyAuth(inheritedAuth.auth);
            req.authInherited = true;
            req.authExplicitlyDisabled = inheritedAuth.explicitNoAuth;
            req.authSource = inheritedAuth.source;
        } else {
            req.auth = null;
            req.authInherited = false;
            req.authExplicitlyDisabled = false;
            req.authSource = "none";
        }

        if (req.auth == null && inheritedAuth != null && inheritedAuth.explicitNoAuth) {
            req.auth = new ApiRequest.Auth();
            req.auth.type = "none";
            req.authInherited = true;
            req.authExplicitlyDisabled = true;
            req.authSource = inheritedAuth.source;
        }
    }

    private void parseEvents(JsonArray events, ApiRequest req) {
        Set<String> seen = new HashSet<>();
        for (JsonElement e : events) {
            JsonObject event = e.getAsJsonObject();
            String listen = getString(event, "listen", "");
            if (event.has("script") && event.get("script").isJsonObject()) {
                JsonObject script = event.getAsJsonObject("script");
                String exec = "";
                if (script.has("exec") && script.get("exec").isJsonArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonElement line : script.getAsJsonArray("exec")) {
                        sb.append(line.getAsString()).append("\n");
                    }
                    exec = sb.toString();
                } else if (script.has("exec") && script.get("exec").isJsonPrimitive()) {
                    exec = script.get("exec").getAsString();
                }
                // Deduplicate by content hash
                String hash = listen + "|" + exec.hashCode();
                if (seen.contains(hash)) continue;
                seen.add(hash);
                if ("prerequest".equals(listen)) {
                    req.preRequestScripts.add(new ApiRequest.Script("js", exec));
                } else if ("test".equals(listen)) {
                    req.postResponseScripts.add(new ApiRequest.Script("js", exec));
                }
            }
        }
    }

    private ApiRequest.Body parseBody(JsonObject bodyObj) {
        ApiRequest.Body body = new ApiRequest.Body();
        body.mode = getString(bodyObj, "mode", "none");

        switch (body.mode) {
            case "raw":
                body.raw = getString(bodyObj, "raw", "");
                if (bodyObj.has("options") && bodyObj.get("options").isJsonObject()) {
                    JsonObject opts = bodyObj.getAsJsonObject("options");
                    if (opts.has("raw") && opts.get("raw").isJsonObject()) {
                        JsonObject raw = opts.getAsJsonObject("raw");
                        body.contentType = getString(raw, "language", "text/plain");
                        if ("json".equals(body.contentType)) body.contentType = "application/json";
                        else if ("xml".equals(body.contentType)) body.contentType = "application/xml";
                        else if ("html".equals(body.contentType)) body.contentType = "text/html";
                        else body.contentType = "text/plain";
                    }
                }
                break;
            case "urlencoded":
                if (bodyObj.has("urlencoded") && bodyObj.get("urlencoded").isJsonArray()) {
                    for (JsonElement e : bodyObj.getAsJsonArray("urlencoded")) {
                        JsonObject f = e.getAsJsonObject();
                        boolean disabled = f.has("disabled") && f.get("disabled").getAsBoolean();
                        if (!disabled) {
                            body.urlencoded.add(new ApiRequest.Body.FormField(
                                getString(f, "key", ""),
                                getString(f, "value", "")
                            ));
                        }
                    }
                }
                break;
            case "formdata":
                if (bodyObj.has("formdata") && bodyObj.get("formdata").isJsonArray()) {
                    for (JsonElement e : bodyObj.getAsJsonArray("formdata")) {
                        JsonObject f = e.getAsJsonObject();
                        boolean disabled = f.has("disabled") && f.get("disabled").getAsBoolean();
                        if (!disabled) {
                            String type = getString(f, "type", "");
                            ApiRequest.Body.FormField field;
                            if ("file".equalsIgnoreCase(type)) {
                                field = new ApiRequest.Body.FormField(getString(f, "key", ""), "");
                                field.type = "file";
                                field.fileUpload = true;
                                field.filePath = extractFormDataFilePath(f);
                            } else {
                                field = new ApiRequest.Body.FormField(
                                    getString(f, "key", ""),
                                    getString(f, "value", "")
                                );
                                if (!type.isEmpty()) {
                                    field.type = type;
                                }
                            }
                            body.formdata.add(field);
                        }
                    }
                }
                break;
            case "graphql":
                if (bodyObj.has("graphql") && bodyObj.get("graphql").isJsonObject()) {
                    JsonObject gql = bodyObj.getAsJsonObject("graphql");
                    body.graphql = new ApiRequest.Body.GraphQL();
                    body.graphql.query = getString(gql, "query", "");
                    body.graphql.variables = getString(gql, "variables", "{}");
                    body.contentType = "application/json";
                }
                break;
        }
        return body;
    }

    private String extractFormDataFilePath(JsonObject fieldObj) {
        if (fieldObj == null || !fieldObj.has("src") || fieldObj.get("src").isJsonNull()) {
            return null;
        }
        JsonElement src = fieldObj.get("src");
        if (src.isJsonPrimitive()) {
            return src.getAsString();
        }
        if (src.isJsonArray()) {
            for (JsonElement elem : src.getAsJsonArray()) {
                if (elem != null && elem.isJsonPrimitive()) {
                    return elem.getAsString();
                }
            }
        }
        return null;
    }

    private String parsePostmanUrl(JsonElement urlElem) {
        if (urlElem == null || urlElem.isJsonNull()) {
            return "";
        }
        if (urlElem.isJsonPrimitive()) {
            return urlElem.getAsString();
        }
        if (!urlElem.isJsonObject()) {
            return "";
        }
        JsonObject urlObj = urlElem.getAsJsonObject();
        String raw = getString(urlObj, "raw", "");
        if (raw != null && !raw.isBlank()) {
            return raw;
        }

        String protocol = getString(urlObj, "protocol", "https");
        String host = joinUrlParts(urlObj.get("host"), ".");
        String path = joinUrlParts(urlObj.get("path"), "/");
        StringBuilder out = new StringBuilder();
        if (!host.isBlank()) {
            out.append(protocol != null && !protocol.isBlank() ? protocol : "https").append("://").append(host);
        }
        if (!path.isBlank()) {
            if (out.length() > 0 && !path.startsWith("/")) {
                out.append("/");
            }
            out.append(path);
        }
        String query = buildPostmanQuery(urlObj.get("query"));
        if (!query.isBlank()) {
            out.append("?").append(query);
        }
        return out.toString();
    }

    private String joinUrlParts(JsonElement element, String delimiter) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (!element.isJsonArray()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JsonElement part : element.getAsJsonArray()) {
            if (part != null && part.isJsonPrimitive()) {
                String value = part.getAsString();
                if (value != null && !value.isBlank()) {
                    parts.add(value);
                }
            }
        }
        return String.join(delimiter, parts);
    }

    private String buildPostmanQuery(JsonElement queryElement) {
        if (queryElement == null || !queryElement.isJsonArray()) {
            return "";
        }
        List<String> params = new ArrayList<>();
        for (JsonElement elem : queryElement.getAsJsonArray()) {
            if (elem == null || !elem.isJsonObject()) {
                continue;
            }
            JsonObject param = elem.getAsJsonObject();
            boolean disabled = param.has("disabled") && param.get("disabled").getAsBoolean();
            if (disabled) {
                continue;
            }
            String key = getString(param, "key", "");
            if (key.isBlank()) {
                continue;
            }
            String value = getString(param, "value", "");
            params.add(key + "=" + value);
        }
        return String.join("&", params);
    }

    private ApiRequest.Auth parseAuth(JsonObject authObj) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = getString(authObj, "type", "none");
        if ("inherit".equalsIgnoreCase(auth.type) || "inheritauth".equalsIgnoreCase(auth.type)) {
            return null;
        }
        if ("noauth".equalsIgnoreCase(auth.type)) {
            auth.type = "none";
        }

        // Handle array format: [{"key": "token", "value": "abc", "type": "string"}]
        // or object format: {"token": "abc"}
        String[] keys = {"bearer", "basic", "apikey", "oauth2"};
        for (String key : keys) {
            if (authObj.has(key)) {
                JsonElement elem = authObj.get(key);
                if (elem.isJsonArray()) {
                    for (JsonElement e : elem.getAsJsonArray()) {
                        JsonObject attr = e.getAsJsonObject();
                        auth.properties.put(getString(attr, "key", ""), getString(attr, "value", ""));
                    }
                } else if (elem.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : elem.getAsJsonObject().entrySet()) {
                        JsonElement value = entry.getValue();
                        if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
                            auth.properties.put(entry.getKey(), value.getAsString());
                        }
                    }
                }
            }
        }
        return auth;
    }

    private ApiRequest.Auth deepCopyAuth(ApiRequest.Auth src) {
        if (src == null) return null;
        ApiRequest.Auth copy = new ApiRequest.Auth();
        copy.type = src.type;
        copy.properties.putAll(src.properties);
        return copy;
    }

    private String getString(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            JsonElement elem = obj.get(key);
            if (elem.isJsonPrimitive()) return elem.getAsString();
        }
        return defaultValue;
    }

    /**
     * Extracts a variable value, serializing non-primitives (objects/arrays) to JSON string.
     */
    private String extractVariableValue(JsonObject var, String key) {
        if (!var.has(key) || var.get(key).isJsonNull()) return "";
        JsonElement elem = var.get(key);
        if (elem.isJsonPrimitive()) return elem.getAsString();
        return gson.toJson(elem);
    }

    @Override
    public String getFormatName() { return "Postman"; }

    @Override
    public String[] getSupportedExtensions() { return new String[]{"json"}; }
}
