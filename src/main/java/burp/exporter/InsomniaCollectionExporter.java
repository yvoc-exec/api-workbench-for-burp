package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InsomniaCollectionExporter {
    private InsomniaCollectionExporter() {
    }

    public static JsonObject build(ApiCollection collection, CollectionExportOptions options, List<String> warnings) {
        boolean resolve = options != null && options.resolveVariablesUsingActiveEnvironment;
        EnvironmentProfile activeEnvironment = options != null ? options.activeEnvironment : null;
        Map<String, String> exportOnly = options != null ? options.exportOnlyVariables : Map.of();
        JsonObject root = new JsonObject();
        JsonArray resources = new JsonArray();

        String workspaceId = ExportIds.workspaceId(collection);
        JsonObject workspace = new JsonObject();
        workspace.addProperty("_id", workspaceId);
        workspace.addProperty("_type", "workspace");
        workspace.addProperty("name", collection != null && collection.name != null ? collection.name : "Collection");
        if (collection != null && collection.description != null && !collection.description.isBlank()) {
            workspace.addProperty("description", CollectionExportSupport.resolve(collection.description, CollectionExportSupport.buildResolver(collection, null, activeEnvironment, exportOnly), resolve));
        }
        resources.add(workspace);

        if (collection != null) {
            JsonObject env = new JsonObject();
            env.addProperty("_id", ExportIds.environmentId(activeEnvironment != null ? activeEnvironment : new EnvironmentProfile()));
            env.addProperty("_type", "environment");
            env.addProperty("name", collection.name != null ? collection.name + " Environment" : "Environment");
            JsonObject data = new JsonObject();
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
            VariableResolver resolver = CollectionExportSupport.buildResolver(collection, null, activeEnvironment, exportOnly);
            for (Map.Entry<String, String> entry : merged.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    if (warnings != null) {
                        warnings.add("Skipped blank environment variable key.");
                    }
                    continue;
                }
                data.addProperty(entry.getKey(), CollectionExportSupport.resolve(entry.getValue(), resolver, resolve) != null
                        ? CollectionExportSupport.resolve(entry.getValue(), resolver, resolve)
                        : "");
            }
            env.add("data", data);
            resources.add(env);
        }

        CollectionExportTree.FolderNode tree = CollectionExportTree.build(collection);
        if (collection != null) {
            writeFolderResources(resources, collection, tree, workspaceId, activeEnvironment, exportOnly, resolve, warnings);
        }

        root.add("resources", resources);
        return root;
    }

    public static void write(ApiCollection collection, CollectionExportOptions options, Writer writer, List<String> warnings) throws IOException {
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        writer.write(gson.toJson(build(collection, options, warnings)));
    }

    private static void writeFolderResources(JsonArray resources,
                                             ApiCollection collection,
                                             CollectionExportTree.FolderNode node,
                                             String parentId,
                                             EnvironmentProfile activeEnvironment,
                                             Map<String, String> exportOnly,
                                             boolean resolve,
                                             List<String> warnings) {
        for (CollectionExportTree.FolderNode child : node.children.values()) {
            String folderId = ExportIds.folderId(child.path);
            JsonObject group = new JsonObject();
            group.addProperty("_id", folderId);
            group.addProperty("_type", "request_group");
            group.addProperty("name", child.name);
            group.addProperty("parentId", parentId);
            VariableResolver resolver = CollectionExportSupport.buildResolver(collection, dummyRequestForFolder(child.path), activeEnvironment, exportOnly);
            JsonObject auth = folderAuth(collection, child.path, resolver, resolve);
            if (auth != null) {
                group.add("authentication", auth);
            }
            resources.add(group);
            writeFolderResources(resources, collection, child, folderId, activeEnvironment, exportOnly, resolve, warnings);
        }

        int requestIndex = 0;
        for (ApiRequest request : node.requests) {
            if (request == null) {
                continue;
            }
            String requestId = ExportIds.requestId(request, requestIndex++);
            JsonObject resource = new JsonObject();
            resource.addProperty("_id", requestId);
            resource.addProperty("_type", "request");
            resource.addProperty("parentId", parentId);
            VariableResolver resolver = CollectionExportSupport.buildResolver(collection, request, activeEnvironment, exportOnly);
            resource.addProperty("name", CollectionExportSupport.resolve(request.name, resolver, resolve) != null ? CollectionExportSupport.resolve(request.name, resolver, resolve) : "");
            resource.addProperty("method", CollectionExportSupport.resolve(request.method, resolver, resolve) != null ? CollectionExportSupport.resolve(request.method, resolver, resolve) : "GET");
            resource.addProperty("url", CollectionExportSupport.resolve(request.url, resolver, resolve) != null ? CollectionExportSupport.resolve(request.url, resolver, resolve) : "");
            JsonArray headers = CollectionExportSupport.toHeadersArray(request.headers, resolver, resolve);
            if (!headers.isEmpty()) {
                resource.add("headers", headers);
            }
            JsonObject body = CollectionExportSupport.bodyToInsomnia(request.body, resolver, resolve);
            if (body != null && !body.entrySet().isEmpty()) {
                resource.add("body", body);
            }
            JsonObject auth = requestAuth(request, resolver, resolve);
            if (auth != null) {
                resource.add("authentication", auth);
            }
            if (request.description != null && !request.description.isBlank()) {
                resource.addProperty("description", CollectionExportSupport.resolve(request.description, resolver, resolve));
            }
            resources.add(resource);
        }
    }

    private static JsonObject requestAuth(ApiRequest request, VariableResolver resolver, boolean resolve) {
        if (request == null || request.auth == null) {
            return null;
        }
        if (request.authOverrideMode != null && "inherit".equalsIgnoreCase(request.authOverrideMode) && request.authInherited) {
            return null;
        }
        return CollectionExportSupport.authToInsomnia(request.auth, resolver, resolve);
    }

    private static JsonObject folderAuth(ApiCollection collection, String folderPath, VariableResolver resolver, boolean resolve) {
        if (collection == null || folderPath == null || collection.folderAuthModes == null) {
            return null;
        }
        String normalized = burp.utils.AuthInheritanceResolver.normalizeFolderPath(folderPath);
        String mode = collection.folderAuthModes.get(normalized);
        if (mode == null || "inherit".equalsIgnoreCase(mode)) {
            return null;
        }
        ApiRequest.Auth auth = collection.folderAuth != null ? collection.folderAuth.get(normalized) : null;
        return CollectionExportSupport.authToInsomnia(auth, resolver, resolve);
    }

    private static ApiRequest dummyRequestForFolder(String folderPath) {
        ApiRequest request = new ApiRequest();
        request.name = "__folder__";
        request.path = folderPath;
        return request;
    }
}
