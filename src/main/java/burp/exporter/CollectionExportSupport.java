package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.utils.RequestPathResolver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CollectionExportSupport {
    static final String CROSS_FORMAT_SCRIPT_WARNING = "Scripts were preserved, but tool-specific scripting APIs were not translated.";
    private static final java.util.Set<String> TRANSPORT_HEADERS = java.util.Set.of(
            "host", "content-length", "transfer-encoding", "connection", "proxy-connection"
    );

    private CollectionExportSupport() {
    }

    static VariableResolver buildResolver(ApiCollection collection,
                                          ApiRequest request,
                                          EnvironmentProfile activeEnvironment,
                                          Map<String, String> exportOnlyVariables) {
        return ExportVariableResolutionService.buildResolver(collection, request, activeEnvironment, exportOnlyVariables);
    }

    static String resolve(String value, VariableResolver resolver, boolean resolve) {
        if (!resolve || value == null || value.isEmpty() || resolver == null) {
            return value;
        }
        return resolver.resolve(value);
    }

    static JsonArray toVariablesArray(List<ApiRequest.Variable> variables, VariableResolver resolver, boolean resolve) {
        JsonArray out = new JsonArray();
        if (variables == null) {
            return out;
        }
        for (ApiRequest.Variable variable : variables) {
            if (variable == null || variable.key == null || variable.key.isBlank()) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("key", resolve(variable.key, resolver, resolve));
            obj.addProperty("value", resolve(variable.value, resolver, resolve) != null ? resolve(variable.value, resolver, resolve) : "");
            if (variable.type != null && !variable.type.isBlank()) {
                obj.addProperty("type", resolve(variable.type, resolver, resolve));
            }
            obj.addProperty("enabled", variable.enabled);
            out.add(obj);
        }
        return out;
    }

    static JsonArray toHeadersArray(List<ApiRequest.Header> headers, VariableResolver resolver, boolean resolve) {
        JsonArray out = new JsonArray();
        if (headers == null) {
            return out;
        }
        for (ApiRequest.Header header : headers) {
            if (header == null || header.key == null || header.key.isBlank()) {
                continue;
            }
            String normalized = header.key.trim().toLowerCase(Locale.ROOT);
            if (TRANSPORT_HEADERS.contains(normalized)) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("key", resolve(header.key, resolver, resolve));
            obj.addProperty("value", resolve(header.value, resolver, resolve) != null ? resolve(header.value, resolver, resolve) : "");
            if (header.disabled) {
                obj.addProperty("disabled", true);
            }
            out.add(obj);
        }
        return out;
    }

    static boolean isTransportHeader(String headerName) {
        if (headerName == null) {
            return false;
        }
        return TRANSPORT_HEADERS.contains(headerName.trim().toLowerCase(Locale.ROOT));
    }

    static JsonObject authToJson(ApiRequest.Auth auth, VariableResolver resolver, boolean resolve) {
        JsonObject out = new JsonObject();
        if (auth == null || auth.type == null || auth.type.isBlank()) {
            out.addProperty("type", "inherit");
            return out;
        }
        out.addProperty("type", resolve(auth.type, resolver, resolve));
        if (auth.properties != null) {
            JsonObject props = new JsonObject();
            for (Map.Entry<String, String> entry : auth.properties.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                props.addProperty(entry.getKey(), resolve(entry.getValue(), resolver, resolve) != null ? resolve(entry.getValue(), resolver, resolve) : "");
            }
            out.add("properties", props);
        }
        return out;
    }

    static JsonObject authToPostman(ApiRequest.Auth auth, VariableResolver resolver, boolean resolve) {
        if (auth == null || auth.type == null || auth.type.isBlank() || "inherit".equalsIgnoreCase(auth.type)) {
            return null;
        }
        String type = auth.type.toLowerCase(Locale.ROOT);
        JsonObject out = new JsonObject();
        if ("none".equals(type) || "noauth".equals(type)) {
            out.addProperty("type", "noauth");
            return out;
        }
        out.addProperty("type", type);
        switch (type) {
            case "bearer" -> {
                JsonArray arr = new JsonArray();
                JsonObject token = new JsonObject();
                token.addProperty("key", "token");
                token.addProperty("value", resolve(firstNonBlank(auth.properties, "token", "value"), resolver, resolve) != null
                        ? resolve(firstNonBlank(auth.properties, "token", "value"), resolver, resolve)
                        : "");
                arr.add(token);
                out.add("bearer", arr);
            }
            case "basic" -> {
                JsonArray arr = new JsonArray();
                JsonObject user = new JsonObject();
                user.addProperty("key", "username");
                user.addProperty("value", resolve(firstNonBlank(auth.properties, "username", "user"), resolver, resolve) != null
                        ? resolve(firstNonBlank(auth.properties, "username", "user"), resolver, resolve)
                        : "");
                arr.add(user);
                JsonObject pass = new JsonObject();
                pass.addProperty("key", "password");
                pass.addProperty("value", resolve(firstNonBlank(auth.properties, "password", "pass"), resolver, resolve) != null
                        ? resolve(firstNonBlank(auth.properties, "password", "pass"), resolver, resolve)
                        : "");
                arr.add(pass);
                out.add("basic", arr);
            }
            case "apikey" -> {
                JsonArray arr = new JsonArray();
                JsonObject key = new JsonObject();
                key.addProperty("key", "key");
                key.addProperty("value", resolve(auth.properties.get("key"), resolver, resolve) != null ? resolve(auth.properties.get("key"), resolver, resolve) : "");
                arr.add(key);
                JsonObject value = new JsonObject();
                value.addProperty("key", "value");
                value.addProperty("value", resolve(auth.properties.get("value"), resolver, resolve) != null ? resolve(auth.properties.get("value"), resolver, resolve) : "");
                arr.add(value);
                String placement = resolve(firstNonBlank(auth.properties, "in", "placement"), resolver, resolve);
                if (placement != null && !placement.isBlank()) {
                    JsonObject in = new JsonObject();
                    in.addProperty("key", "in");
                    in.addProperty("value", placement);
                    arr.add(in);
                }
                out.add("apikey", arr);
            }
            case "oauth2" -> {
                JsonArray arr = new JsonArray();
                addAuthArrayEntry(arr, "grantType", resolve(firstNonBlank(auth.properties, "grantType", "grant_type"), resolver, resolve));
                addAuthArrayEntry(arr, "accessTokenUrl", resolve(firstNonBlank(auth.properties, "accessTokenUrl", "access_token_url"), resolver, resolve));
                addAuthArrayEntry(arr, "authorizationUrl", resolve(firstNonBlank(auth.properties, "authorizationUrl", "authorization_url"), resolver, resolve));
                addAuthArrayEntry(arr, "clientId", resolve(firstNonBlank(auth.properties, "clientId", "client_id"), resolver, resolve));
                addAuthArrayEntry(arr, "clientSecret", resolve(firstNonBlank(auth.properties, "clientSecret", "client_secret"), resolver, resolve));
                addAuthArrayEntry(arr, "scope", resolve(auth.properties.get("scope"), resolver, resolve));
                addAuthArrayEntry(arr, "accessToken", resolve(auth.properties.get("accessToken"), resolver, resolve));
                out.add("oauth2", arr);
            }
            default -> {
                JsonArray arr = new JsonArray();
                if (auth.properties != null) {
                    for (Map.Entry<String, String> entry : auth.properties.entrySet()) {
                        if (entry.getKey() == null || entry.getKey().isBlank()) {
                            continue;
                        }
                        JsonObject item = new JsonObject();
                        item.addProperty("key", entry.getKey());
                        item.addProperty("value", resolve(entry.getValue(), resolver, resolve) != null ? resolve(entry.getValue(), resolver, resolve) : "");
                        arr.add(item);
                    }
                }
                out.add(type, arr);
            }
        }
        return out;
    }

    static JsonObject authToInsomnia(ApiRequest.Auth auth, VariableResolver resolver, boolean resolve) {
        if (auth == null || auth.type == null || auth.type.isBlank() || "inherit".equalsIgnoreCase(auth.type)) {
            return null;
        }
        JsonObject out = new JsonObject();
        String type = auth.type.toLowerCase(Locale.ROOT);
        if ("none".equals(type) || "noauth".equals(type)) {
            out.addProperty("type", "none");
            return out;
        }
        out.addProperty("type", type);
        if (auth.properties != null) {
            for (Map.Entry<String, String> entry : auth.properties.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                out.addProperty(entry.getKey(), resolve(entry.getValue(), resolver, resolve) != null ? resolve(entry.getValue(), resolver, resolve) : "");
            }
        }
        if ("apikey".equals(type) && out.has("placement") && !out.has("addTo")) {
            out.addProperty("addTo", out.get("placement").getAsString());
        }
        return out;
    }

    static JsonObject authToOpenApiScheme(ApiRequest.Auth auth, VariableResolver resolver, boolean resolve) {
        if (auth == null || auth.type == null || auth.type.isBlank() || "inherit".equalsIgnoreCase(auth.type)) {
            return null;
        }
        String type = auth.type.toLowerCase(Locale.ROOT);
        JsonObject scheme = new JsonObject();
        switch (type) {
            case "none", "noauth" -> {
                return null;
            }
            case "bearer" -> {
                scheme.addProperty("type", "http");
                scheme.addProperty("scheme", "bearer");
            }
            case "basic" -> {
                scheme.addProperty("type", "http");
                scheme.addProperty("scheme", "basic");
            }
            case "apikey" -> {
                scheme.addProperty("type", "apiKey");
                String in = resolve(firstNonBlank(auth.properties, "in", "placement"), resolver, resolve);
                if (in == null || in.isBlank()) {
                    in = "header";
                }
                scheme.addProperty("in", in);
                scheme.addProperty("name", resolve(auth.properties.get("key"), resolver, resolve) != null ? resolve(auth.properties.get("key"), resolver, resolve) : "api_key");
            }
            case "oauth2" -> {
                scheme.addProperty("type", "oauth2");
                JsonObject flows = new JsonObject();
                String grantType = resolve(firstNonBlank(auth.properties, "grantType", "grant_type"), resolver, resolve);
                if ("authorization_code".equalsIgnoreCase(grantType) || "authorizationcode".equalsIgnoreCase(grantType)) {
                    JsonObject authorizationCode = new JsonObject();
                    addOAuthUrl(auth, resolver, resolve, authorizationCode, "authorizationUrl", "authorization_url");
                    addOAuthUrl(auth, resolver, resolve, authorizationCode, "tokenUrl", "accessTokenUrl", "access_token_url");
                    addOAuthScope(auth, resolver, resolve, authorizationCode);
                    flows.add("authorizationCode", authorizationCode);
                } else if ("client_credentials".equalsIgnoreCase(grantType) || "clientcredentials".equalsIgnoreCase(grantType)) {
                    JsonObject clientCredentials = new JsonObject();
                    addOAuthUrl(auth, resolver, resolve, clientCredentials, "tokenUrl", "accessTokenUrl", "access_token_url");
                    addOAuthScope(auth, resolver, resolve, clientCredentials);
                    flows.add("clientCredentials", clientCredentials);
                } else if ("password".equalsIgnoreCase(grantType)) {
                    JsonObject password = new JsonObject();
                    addOAuthUrl(auth, resolver, resolve, password, "tokenUrl", "accessTokenUrl", "access_token_url");
                    addOAuthScope(auth, resolver, resolve, password);
                    flows.add("password", password);
                } else {
                    JsonObject implicit = new JsonObject();
                    addOAuthUrl(auth, resolver, resolve, implicit, "authorizationUrl", "authorization_url");
                    addOAuthScope(auth, resolver, resolve, implicit);
                    flows.add("implicit", implicit);
                }
                scheme.add("flows", flows);
            }
            default -> {
                return null;
            }
        }
        return scheme;
    }

    static JsonObject bodyToPostman(ApiRequest.Body body, VariableResolver resolver, boolean resolve) {
        if (body == null || body.mode == null || body.mode.isBlank() || "none".equalsIgnoreCase(body.mode)) {
            return null;
        }
        JsonObject out = new JsonObject();
        String mode = body.mode.toLowerCase(Locale.ROOT);
        if ("raw".equals(mode)) {
            out.addProperty("mode", "raw");
            out.addProperty("raw", resolve(body.raw, resolver, resolve) != null ? resolve(body.raw, resolver, resolve) : "");
            JsonObject options = new JsonObject();
            JsonObject raw = new JsonObject();
            raw.addProperty("language", postmanLanguageForContentType(body.contentType));
            options.add("raw", raw);
            out.add("options", options);
        } else if ("urlencoded".equals(mode)) {
            out.addProperty("mode", "urlencoded");
            JsonArray arr = new JsonArray();
            if (body.urlencoded != null) {
                for (ApiRequest.Body.FormField field : body.urlencoded) {
                    if (field == null || field.key == null || field.key.isBlank()) {
                        continue;
                    }
                    JsonObject obj = new JsonObject();
                    obj.addProperty("key", resolve(field.key, resolver, resolve) != null ? resolve(field.key, resolver, resolve) : "");
                    obj.addProperty("value", resolve(field.value, resolver, resolve) != null ? resolve(field.value, resolver, resolve) : "");
                    if (field.disabled) {
                        obj.addProperty("disabled", true);
                    }
                    arr.add(obj);
                }
            }
            out.add("urlencoded", arr);
        } else if ("formdata".equals(mode)) {
            out.addProperty("mode", "formdata");
            JsonArray arr = new JsonArray();
            if (body.formdata != null) {
                for (ApiRequest.Body.FormField field : body.formdata) {
                    if (field == null || field.key == null || field.key.isBlank()) {
                        continue;
                    }
                    JsonObject obj = new JsonObject();
                    obj.addProperty("key", resolve(field.key, resolver, resolve) != null ? resolve(field.key, resolver, resolve) : "");
                    if (field.fileUpload || "file".equalsIgnoreCase(field.type)) {
                        obj.addProperty("type", "file");
                        if (field.filePath != null && !field.filePath.isBlank()) {
                            obj.addProperty("src", resolve(field.filePath, resolver, resolve) != null ? resolve(field.filePath, resolver, resolve) : "");
                        }
                    } else {
                        obj.addProperty("type", "text");
                        obj.addProperty("value", resolve(field.value, resolver, resolve) != null ? resolve(field.value, resolver, resolve) : "");
                    }
                    if (field.disabled) {
                        obj.addProperty("disabled", true);
                    }
                    arr.add(obj);
                }
            }
            out.add("formdata", arr);
        } else if ("graphql".equals(mode)) {
            out.addProperty("mode", "graphql");
            JsonObject gql = new JsonObject();
            gql.addProperty("query", resolve(body.graphql != null ? body.graphql.query : "", resolver, resolve) != null ? resolve(body.graphql != null ? body.graphql.query : "", resolver, resolve) : "");
            gql.addProperty("variables", resolve(body.graphql != null ? body.graphql.variables : "", resolver, resolve) != null ? resolve(body.graphql != null ? body.graphql.variables : "", resolver, resolve) : "{}");
            out.add("graphql", gql);
        } else {
            out.addProperty("mode", mode);
        }
        return out;
    }

    static JsonObject bodyToInsomnia(ApiRequest.Body body, VariableResolver resolver, boolean resolve) {
        if (body == null || body.mode == null || body.mode.isBlank() || "none".equalsIgnoreCase(body.mode)) {
            return null;
        }
        JsonObject out = new JsonObject();
        String mode = body.mode.toLowerCase(Locale.ROOT);
        switch (mode) {
            case "raw", "graphql" -> {
                out.addProperty("mimeType", contentTypeForRaw(body));
                String text = "graphql".equals(mode) && body.graphql != null ? body.graphql.query : body.raw;
                if ("graphql".equals(mode) && body.graphql != null) {
                    text = "{\"query\":" + jsonString(resolve(body.graphql.query, resolver, resolve)) + ",\"variables\":" + jsonString(resolve(body.graphql.variables, resolver, resolve)) + "}";
                }
                out.addProperty("text", resolve(text, resolver, resolve) != null ? resolve(text, resolver, resolve) : "");
            }
            case "urlencoded" -> {
                out.addProperty("mimeType", "application/x-www-form-urlencoded");
                JsonArray params = new JsonArray();
                if (body.urlencoded != null) {
                    for (ApiRequest.Body.FormField field : body.urlencoded) {
                        if (field == null) {
                            continue;
                        }
                        JsonObject obj = new JsonObject();
                        obj.addProperty("name", resolve(field.key, resolver, resolve) != null ? resolve(field.key, resolver, resolve) : "");
                        obj.addProperty("value", resolve(field.value, resolver, resolve) != null ? resolve(field.value, resolver, resolve) : "");
                        obj.addProperty("type", "text");
                        if (field.disabled) {
                            obj.addProperty("disabled", true);
                        }
                        params.add(obj);
                    }
                }
                out.add("params", params);
            }
            case "formdata" -> {
                out.addProperty("mimeType", "multipart/form-data");
                JsonArray params = new JsonArray();
                if (body.formdata != null) {
                    for (ApiRequest.Body.FormField field : body.formdata) {
                        if (field == null) {
                            continue;
                        }
                        JsonObject obj = new JsonObject();
                        obj.addProperty("name", resolve(field.key, resolver, resolve) != null ? resolve(field.key, resolver, resolve) : "");
                        if (field.fileUpload || "file".equalsIgnoreCase(field.type)) {
                            obj.addProperty("type", "file");
                            if (field.filePath != null) {
                                obj.addProperty("fileName", resolve(field.filePath, resolver, resolve) != null ? resolve(field.filePath, resolver, resolve) : "");
                            } else if (field.value != null && !field.value.isBlank()) {
                                obj.addProperty("value", resolve(field.value, resolver, resolve));
                            }
                        } else {
                            obj.addProperty("type", field.type != null && !field.type.isBlank() ? field.type : "text");
                            obj.addProperty("value", resolve(field.value, resolver, resolve) != null ? resolve(field.value, resolver, resolve) : "");
                        }
                        if (field.disabled) {
                            obj.addProperty("disabled", true);
                        }
                        params.add(obj);
                    }
                }
                out.add("params", params);
            }
            default -> {
                out.addProperty("mimeType", contentTypeForRaw(body));
                out.addProperty("text", resolve(body.raw, resolver, resolve) != null ? resolve(body.raw, resolver, resolve) : "");
            }
        }
        return out;
    }

    static JsonObject bodyToHar(ApiRequest.Body body, VariableResolver resolver, boolean resolve) {
        if (body == null || body.mode == null || body.mode.isBlank() || "none".equalsIgnoreCase(body.mode)) {
            return null;
        }
        JsonObject out = new JsonObject();
        String mode = body.mode.toLowerCase(Locale.ROOT);
        switch (mode) {
            case "urlencoded" -> {
                out.addProperty("mimeType", "application/x-www-form-urlencoded");
                JsonArray params = new JsonArray();
                if (body.urlencoded != null) {
                    for (ApiRequest.Body.FormField field : body.urlencoded) {
                        if (field == null || field.key == null || field.key.isBlank()) {
                            continue;
                        }
                        JsonObject obj = new JsonObject();
                        obj.addProperty("name", resolve(field.key, resolver, resolve) != null ? resolve(field.key, resolver, resolve) : "");
                        obj.addProperty("value", resolve(field.value, resolver, resolve) != null ? resolve(field.value, resolver, resolve) : "");
                        params.add(obj);
                    }
                }
                out.add("params", params);
            }
            case "formdata" -> {
                out.addProperty("mimeType", "multipart/form-data");
                JsonArray params = new JsonArray();
                if (body.formdata != null) {
                    for (ApiRequest.Body.FormField field : body.formdata) {
                        if (field == null || field.key == null || field.key.isBlank()) {
                            continue;
                        }
                        JsonObject obj = new JsonObject();
                        obj.addProperty("name", resolve(field.key, resolver, resolve) != null ? resolve(field.key, resolver, resolve) : "");
                        if (field.fileUpload || "file".equalsIgnoreCase(field.type)) {
                            obj.addProperty("type", "file");
                            if (field.filePath != null && !field.filePath.isBlank()) {
                                obj.addProperty("filePath", resolve(field.filePath, resolver, resolve) != null ? resolve(field.filePath, resolver, resolve) : "");
                            }
                        } else {
                            obj.addProperty("value", resolve(field.value, resolver, resolve) != null ? resolve(field.value, resolver, resolve) : "");
                        }
                        params.add(obj);
                    }
                }
                out.add("params", params);
            }
            case "graphql" -> {
                out.addProperty("mimeType", "application/json");
                String text = body.graphql != null
                        ? "{\"query\":" + jsonString(resolve(body.graphql.query, resolver, resolve)) + ",\"variables\":" + jsonString(resolve(body.graphql.variables, resolver, resolve)) + "}"
                        : "{}";
                out.addProperty("text", text);
            }
            default -> {
                out.addProperty("mimeType", contentTypeForRaw(body));
                out.addProperty("text", resolve(body.raw, resolver, resolve) != null ? resolve(body.raw, resolver, resolve) : "");
            }
        }
        return out;
    }

    static JsonArray scriptsToPostmanEvents(List<ApiRequest.Script> scripts, String listen, VariableResolver resolver, boolean resolve) {
        JsonArray events = new JsonArray();
        if (scripts == null) {
            return events;
        }
        JsonArray exec = new JsonArray();
        for (ApiRequest.Script script : scripts) {
            if (script == null || script.exec == null || script.exec.isBlank()) {
                continue;
            }
            String text = resolve(script.exec, resolver, resolve);
            if (text == null || text.isBlank()) {
                continue;
            }
            for (String line : text.split("\\R", -1)) {
                exec.add(line);
            }
        }
        if (!exec.isEmpty()) {
            JsonObject event = new JsonObject();
            event.addProperty("listen", listen);
            JsonObject script = new JsonObject();
            script.addProperty("type", "text/javascript");
            script.add("exec", exec);
            event.add("script", script);
            events.add(event);
        }
        return events;
    }

    static String normalizedFolderPath(ApiRequest request) {
        return RequestPathResolver.getRequestFolderPath(request);
    }

    static void addScriptExportWarnings(ApiCollection collection, CollectionExportFormat format, List<String> warnings) {
        if (collection == null || warnings == null || format == null || format == CollectionExportFormat.API_WORKBENCH_JSON) {
            return;
        }
        ScriptDialect target = switch (format) {
            case POSTMAN_JSON -> ScriptDialect.POSTMAN;
            case INSOMNIA_JSON -> ScriptDialect.INSOMNIA;
            case BRUNO_ZIP -> ScriptDialect.BRUNO;
            default -> null;
        };
        if (target == null) {
            if (hasAnyScript(collection)) {
                addWarningOnce(warnings, "Scripts cannot be represented in " + format.displayName() + " export and were omitted.");
            }
            return;
        }
        if (hasCrossDialectScript(collection, target)) {
            addWarningOnce(warnings, CROSS_FORMAT_SCRIPT_WARNING);
        }
    }

    private static boolean hasCrossDialectScript(ApiCollection collection, ScriptDialect target) {
        if (hasCrossDialectBlock(collection.scriptBlocks, target)) {
            return true;
        }
        if (collection.folderScriptBlocks != null) {
            for (List<ScriptBlock> blocks : collection.folderScriptBlocks.values()) {
                if (hasCrossDialectBlock(blocks, target)) {
                    return true;
                }
            }
        }
        if (collection.requests != null) {
            for (ApiRequest request : collection.requests) {
                if (request != null && hasCrossDialectBlock(request.scriptBlocks, target)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasCrossDialectBlock(List<ScriptBlock> blocks, ScriptDialect target) {
        if (blocks == null) {
            return false;
        }
        for (ScriptBlock block : blocks) {
            if (block == null || block.source == null || block.source.isBlank()) {
                continue;
            }
            ScriptDialect dialect = block.dialect != null ? block.dialect : ScriptDialect.LEGACY_JAVASCRIPT;
            if (dialect != target && dialect != ScriptDialect.API_WORKBENCH && dialect != ScriptDialect.LEGACY_JAVASCRIPT) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyScript(ApiCollection collection) {
        if (hasScripts(collection.scriptBlocks)) {
            return true;
        }
        if (collection.folderScriptBlocks != null) {
            for (List<ScriptBlock> blocks : collection.folderScriptBlocks.values()) {
                if (hasScripts(blocks)) {
                    return true;
                }
            }
        }
        if (collection.requests != null) {
            for (ApiRequest request : collection.requests) {
                if (request == null) {
                    continue;
                }
                if (hasScripts(request.scriptBlocks)
                        || hasLegacyScripts(request.preRequestScripts)
                        || hasLegacyScripts(request.postResponseScripts)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasScripts(List<ScriptBlock> blocks) {
        if (blocks == null) {
            return false;
        }
        for (ScriptBlock block : blocks) {
            if (block != null && block.source != null && !block.source.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLegacyScripts(List<ApiRequest.Script> scripts) {
        if (scripts == null) {
            return false;
        }
        for (ApiRequest.Script script : scripts) {
            if (script != null && script.exec != null && !script.exec.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static void addWarningOnce(List<String> warnings, String warning) {
        if (warning != null && !warning.isBlank() && !warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    static String firstNonBlank(Map<String, String> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String value = map.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void addAuthArrayEntry(JsonArray arr, String key, String value) {
        if (value == null) {
            return;
        }
        JsonObject item = new JsonObject();
        item.addProperty("key", key);
        item.addProperty("value", value);
        arr.add(item);
    }

    private static void addOAuthUrl(ApiRequest.Auth auth, VariableResolver resolver, boolean resolve, JsonObject target, String... keys) {
        for (String key : keys) {
            String value = auth.properties != null ? auth.properties.get(key) : null;
            if (value != null && !value.isBlank()) {
                target.addProperty(key, resolve(value, resolver, resolve));
                return;
            }
        }
    }

    private static void addOAuthScope(ApiRequest.Auth auth, VariableResolver resolver, boolean resolve, JsonObject target) {
        String scope = auth.properties != null ? auth.properties.get("scope") : null;
        if (scope != null && !scope.isBlank()) {
            target.addProperty("scope", resolve(scope, resolver, resolve));
        }
    }

    private static String postmanLanguageForContentType(String contentType) {
        if (contentType == null) {
            return "text";
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.contains("json")) {
            return "json";
        }
        if (lower.contains("xml")) {
            return "xml";
        }
        if (lower.contains("html")) {
            return "html";
        }
        if (lower.contains("javascript")) {
            return "javascript";
        }
        return "text";
    }

    private static String contentTypeForRaw(ApiRequest.Body body) {
        if (body != null && body.contentType != null && !body.contentType.isBlank()) {
            return body.contentType;
        }
        return "text/plain";
    }

    private static String jsonString(String value) {
        return new JsonPrimitive(value != null ? value : "").toString();
    }
}
