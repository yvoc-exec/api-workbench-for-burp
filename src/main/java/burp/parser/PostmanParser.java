package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import com.google.gson.*;
import java.io.*;
import java.util.*;

/**
 * Parser for Postman Collections (v2.0, v2.1) and Environments.
 */
public class PostmanParser implements CollectionParser {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

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
        ApiRequest.Auth collectionAuth = null;
        if (collectionObj.has("auth") && collectionObj.get("auth").isJsonObject()) {
            collectionAuth = parseAuth(collectionObj.getAsJsonObject("auth"));
        }

        // Parse items recursively
        if (collectionObj.has("item") && collectionObj.get("item").isJsonArray()) {
            parseItems(collectionObj.getAsJsonArray("item"), "", collection, collectionAuth);
        }

        return collection;
    }

    private void parseItems(JsonArray items, String path, ApiCollection collection, ApiRequest.Auth inheritedAuth) {
        for (JsonElement elem : items) {
            JsonObject item = elem.getAsJsonObject();
            String name = getString(item, "name", "Unnamed");
            String currentPath = path.isEmpty() ? name : path + "/" + name;

            // Folder-level auth overrides collection-level auth
            ApiRequest.Auth nextInherited = inheritedAuth;
            if (item.has("auth") && item.get("auth").isJsonObject()) {
                nextInherited = parseAuth(item.getAsJsonObject("auth"));
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

    private ApiRequest parseRequest(JsonObject reqObj, String name, String path, ApiRequest.Auth inheritedAuth) {
        ApiRequest req = new ApiRequest();
        req.name = name;
        req.path = path;
        req.method = getString(reqObj, "method", "GET");
        req.description = getString(reqObj, "description", "");

        // URL - handle both string and object
        JsonElement urlElem = reqObj.get("url");
        if (urlElem != null) {
            if (urlElem.isJsonPrimitive()) {
                req.url = urlElem.getAsString();
            } else if (urlElem.isJsonObject()) {
                JsonObject urlObj = urlElem.getAsJsonObject();
                req.url = getString(urlObj, "raw", "");
            }
        }

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
            req.auth = parseAuth(reqObj.getAsJsonObject("auth"));
        }
        if ((req.auth == null || req.auth.type == null || "none".equals(req.auth.type)) && inheritedAuth != null) {
            req.auth = deepCopyAuth(inheritedAuth);
        }

        // Events parsed outside parseRequest to allow item-level override

        return req;
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

    private ApiRequest.Auth parseAuth(JsonObject authObj) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = getString(authObj, "type", "none");

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
