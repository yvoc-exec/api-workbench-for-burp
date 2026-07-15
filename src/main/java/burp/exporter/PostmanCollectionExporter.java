package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;
import burp.utils.RequestParameterSupport;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PostmanCollectionExporter {
    private static final String SCHEMA = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";

    private PostmanCollectionExporter() {
    }

    public static JsonObject build(ApiCollection collection, CollectionExportOptions options, List<String> warnings) {
        boolean resolve = options != null && options.resolveVariablesUsingActiveEnvironment;
        EnvironmentProfile activeEnvironment = options != null ? options.activeEnvironment : null;
        Map<String, String> exportOnly = options != null ? options.exportOnlyVariables : Map.of();
        JsonObject root = new JsonObject();

        ApiRequest rootRequest = null;
        VariableResolver rootResolver = CollectionExportSupport.buildResolver(collection, null, activeEnvironment, exportOnly);
        JsonObject info = new JsonObject();
        info.addProperty("name", collection != null && collection.name != null ? CollectionExportSupport.resolve(collection.name, rootResolver, resolve) : "Collection");
        if (collection != null && collection.description != null) {
            info.addProperty("description", CollectionExportSupport.resolve(collection.description, rootResolver, resolve));
        }
        info.addProperty("schema", SCHEMA);
        root.add("info", info);

        if (collection != null) {
            JsonObject rootAuth = CollectionExportSupport.authToPostman(collection.auth, rootResolver, resolve);
            if (rootAuth != null) {
                root.add("auth", rootAuth);
            }
        }

        JsonArray variables = new JsonArray();
        if (collection != null) {
            Map<String, String> merged = new LinkedHashMap<>();
            if (collection.environment != null) {
                merged.putAll(collection.environment);
            }
            if (collection.variables != null) {
                for (ApiRequest.Variable variable : collection.variables) {
                    if (variable == null || variable.key == null || variable.key.isBlank()) {
                        continue;
                    }
                    merged.put(variable.key, variable.value != null ? variable.value : "");
                }
            }
            for (Map.Entry<String, String> entry : merged.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("key", entry.getKey());
                item.addProperty("value", CollectionExportSupport.resolve(entry.getValue(), rootResolver, resolve) != null
                        ? CollectionExportSupport.resolve(entry.getValue(), rootResolver, resolve)
                        : "");
                item.addProperty("type", "default");
                item.addProperty("enabled", true);
                variables.add(item);
            }
        }
        root.add("variable", variables);

        CollectionExportTree.FolderNode tree = CollectionExportTree.build(collection);
        JsonArray items = new JsonArray();
        if (collection != null) {
            for (CollectionExportTree.FolderNode child : tree.children.values()) {
                items.add(folderToItem(collection, child, activeEnvironment, exportOnly, resolve, warnings));
            }
            for (ApiRequest request : tree.requests) {
                items.add(requestToItem(collection, request, activeEnvironment, exportOnly, resolve, warnings));
            }
        }
        root.add("item", items);
        return root;
    }

    public static void write(ApiCollection collection, CollectionExportOptions options, Writer writer, List<String> warnings) throws IOException {
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        writer.write(gson.toJson(build(collection, options, warnings)));
    }

    private static JsonObject folderToItem(ApiCollection collection,
                                           CollectionExportTree.FolderNode node,
                                           EnvironmentProfile activeEnvironment,
                                           Map<String, String> exportOnly,
                                           boolean resolve,
                                           List<String> warnings) {
        JsonObject folder = new JsonObject();
        VariableResolver resolver = CollectionExportSupport.buildResolver(collection, dummyRequestForFolder(node.path), activeEnvironment, exportOnly);
        folder.addProperty("name", CollectionExportSupport.resolve(node.name, resolver, resolve) != null ? CollectionExportSupport.resolve(node.name, resolver, resolve) : "");
        JsonObject folderAuth = folderAuth(collection, node.path, resolver, resolve);
        if (folderAuth != null) {
            folder.add("auth", folderAuth);
        }
        JsonArray items = new JsonArray();
        for (CollectionExportTree.FolderNode child : node.children.values()) {
            items.add(folderToItem(collection, child, activeEnvironment, exportOnly, resolve, warnings));
        }
        for (ApiRequest request : node.requests) {
            items.add(requestToItem(collection, request, activeEnvironment, exportOnly, resolve, warnings));
        }
        folder.add("item", items);
        return folder;
    }

    private static JsonObject requestToItem(ApiCollection collection,
                                            ApiRequest request,
                                            EnvironmentProfile activeEnvironment,
                                            Map<String, String> exportOnly,
                                            boolean resolve,
                                            List<String> warnings) {
        JsonObject item = new JsonObject();
        if (request != null && request.id != null && !request.id.isBlank()) {
            item.addProperty("id", request.id);
        }
        VariableResolver resolver = CollectionExportSupport.buildResolver(collection, request, activeEnvironment, exportOnly);
        item.addProperty("name", CollectionExportSupport.resolve(request.name, resolver, resolve) != null ? CollectionExportSupport.resolve(request.name, resolver, resolve) : "");
        JsonObject req = new JsonObject();
        req.addProperty("method", CollectionExportSupport.resolve(request.method, resolver, resolve) != null ? CollectionExportSupport.resolve(request.method, resolver, resolve) : "GET");
        req.add("url", requestUrlToPostman(request, resolver, resolve));
        JsonArray headers = CollectionExportSupport.toHeadersArray(request.headers, resolver, resolve);
        if (!headers.isEmpty()) {
            req.add("header", headers);
        }
        JsonObject body = CollectionExportSupport.bodyToPostman(request.body, resolver, resolve);
        if (body != null && !body.entrySet().isEmpty()) {
            req.add("body", body);
        }
        JsonObject auth = requestAuth(collection, request, resolver, resolve);
        if (auth != null) {
            req.add("auth", auth);
        }
        item.add("request", req);
        JsonArray events = new JsonArray();
        JsonArray pre = CollectionExportSupport.scriptsToPostmanEvents(request.preRequestScripts, "prerequest", resolver, resolve);
        JsonArray post = CollectionExportSupport.scriptsToPostmanEvents(request.postResponseScripts, "test", resolver, resolve);
        for (var e : pre) {
            events.add(e);
        }
        for (var e : post) {
            events.add(e);
        }
        if (!events.isEmpty()) {
            item.add("event", events);
        }
        if (request.description != null && !request.description.isBlank()) {
            item.addProperty("description", CollectionExportSupport.resolve(request.description, resolver, resolve));
        }
        return item;
    }

    private static JsonElement requestUrlToPostman(ApiRequest request,
                                                   VariableResolver resolver,
                                                   boolean resolve) {
        if (request == null || !RequestParameterSupport.hasQueryParameters(request.parameters)) {
            return new com.google.gson.JsonPrimitive(request != null && request.url != null
                    ? CollectionExportSupport.resolve(request.url, resolver, resolve)
                    : "");
        }
        VariableResolver materializationResolver = resolve ? resolver : null;
        JsonObject url = new JsonObject();
        url.addProperty("raw", RequestParameterSupport.materializeUrl(
                request.url != null ? request.url : "", request.parameters, materializationResolver));
        JsonArray query = new JsonArray();
        for (ApiRequest.Parameter parameter : request.parameters) {
            if (parameter == null || !parameter.isQuery()) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("key", CollectionExportSupport.resolve(parameter.key, resolver, resolve));
            if (parameter.valuePresent) {
                row.addProperty("value", CollectionExportSupport.resolve(parameter.value, resolver, resolve));
            }
            if (parameter.disabled) {
                row.addProperty("disabled", true);
            }
            if (parameter.description != null && !parameter.description.isBlank()) {
                row.addProperty("description", CollectionExportSupport.resolve(parameter.description, resolver, resolve));
            }
            if (parameter.type != null && !parameter.type.isBlank()) {
                row.addProperty("type", CollectionExportSupport.resolve(parameter.type, resolver, resolve));
            }
            query.add(row);
        }
        url.add("query", query);
        return url;
    }

    private static JsonObject requestAuth(ApiCollection collection, ApiRequest request, VariableResolver resolver, boolean resolve) {
        if (request == null) {
            return null;
        }
        if (request.authOverrideMode != null && "inherit".equalsIgnoreCase(request.authOverrideMode) && request.authInherited) {
            return null;
        }
        if (request.auth == null || request.auth.type == null) {
            return null;
        }
        JsonObject auth = CollectionExportSupport.authToPostman(request.auth, resolver, resolve);
        return auth;
    }

    private static JsonObject folderAuth(ApiCollection collection, String folderPath, VariableResolver resolver, boolean resolve) {
        if (collection == null || folderPath == null) {
            return null;
        }
        String normalized = burp.utils.AuthInheritanceResolver.normalizeFolderPath(folderPath);
        if (normalized.isEmpty() || collection.folderAuthModes == null) {
            return null;
        }
        String mode = collection.folderAuthModes.get(normalized);
        if (mode == null || "inherit".equalsIgnoreCase(mode)) {
            return null;
        }
        ApiRequest.Auth auth = collection.folderAuth != null ? collection.folderAuth.get(normalized) : null;
        return CollectionExportSupport.authToPostman(auth, resolver, resolve);
    }

    private static ApiRequest dummyRequestForFolder(String folderPath) {
        ApiRequest request = new ApiRequest();
        request.name = "__folder__";
        request.path = folderPath;
        return request;
    }
}
