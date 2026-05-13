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
        try (FileReader reader = new FileReader(file)) {
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
        try (FileReader reader = new FileReader(file)) {
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
                cv.value = getString(var, "value", "");
                cv.type = getString(var, "type", "string");
                collection.variables.add(cv);
            }
        }

        // Parse items recursively
        if (collectionObj.has("item") && collectionObj.get("item").isJsonArray()) {
            parseItems(collectionObj.getAsJsonArray("item"), "", collection);
        }

        return collection;
    }

    private void parseItems(JsonArray items, String path, ApiCollection collection) {
        for (JsonElement elem : items) {
            JsonObject item = elem.getAsJsonObject();
            String name = getString(item, "name", "Unnamed");
            String currentPath = path.isEmpty() ? name : path + "/" + name;

            if (item.has("request") && item.get("request").isJsonObject()) {
                ApiRequest req = parseRequest(item.getAsJsonObject("request"), name, currentPath);
                collection.requests.add(req);
            }

            // Nested folders
            if (item.has("item") && item.get("item").isJsonArray()) {
                parseItems(item.getAsJsonArray("item"), currentPath, collection);
            }
        }
    }

    private ApiRequest parseRequest(JsonObject reqObj, String name, String path) {
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

        // Auth
        if (reqObj.has("auth") && reqObj.get("auth").isJsonObject()) {
            req.auth = parseAuth(reqObj.getAsJsonObject("auth"));
        }

        // Events (pre-request / test scripts)
        if (reqObj.has("event") && reqObj.get("event").isJsonArray()) {
            for (JsonElement e : reqObj.getAsJsonArray("event")) {
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
                    }
                    if ("prerequest".equals(listen)) {
                        req.preRequestScripts.add(new ApiRequest.Script("js", exec));
                    } else if ("test".equals(listen)) {
                        req.postResponseScripts.add(new ApiRequest.Script("js", exec));
                    }
                }
            }
        }

        return req;
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
                            body.formdata.add(new ApiRequest.Body.FormField(
                                getString(f, "key", ""),
                                getString(f, "value", "")
                            ));
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

    private String getString(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            JsonElement elem = obj.get(key);
            if (elem.isJsonPrimitive()) return elem.getAsString();
        }
        return defaultValue;
    }

    @Override
    public String getFormatName() { return "Postman"; }

    @Override
    public String[] getSupportedExtensions() { return new String[]{"json"}; }
}
