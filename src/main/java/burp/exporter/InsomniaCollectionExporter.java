package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptPhase;
import burp.utils.RequestParameterSupport;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
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
        root.addProperty("__type", "export");
        root.addProperty("__export_format", 4);
        root.addProperty("__export_source", "api-workbench-for-burp");
        JsonArray resources = new JsonArray();

        String workspaceId = collection != null && collection.id != null && !collection.id.isBlank()
                ? collection.id : ExportIds.workspaceId(collection);
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
            env.addProperty("parentId", workspaceId);
            env.addProperty("name", collection.name != null ? collection.name + " Environment" : "Environment");
            JsonObject data = new JsonObject();
            Map<String, String> merged = new LinkedHashMap<>();
            if (collection.environment != null) {
                merged.putAll(collection.environment);
            }
            if (collection.variables != null) {
                java.util.Set<String> enabledKeys = new java.util.HashSet<>();
                for (ApiRequest.Variable variable : collection.variables) {
                    if (variable == null || variable.key == null || variable.key.isBlank()) {
                        continue;
                    }
                    if (!variable.enabled) {
                        addWarning(warnings, "Insomnia export omitted disabled collection variable '"
                                + safeText(variable.key) + "' from the active base environment.");
                        continue;
                    }
                    if (!enabledKeys.add(variable.key)) {
                        addWarning(warnings, "Insomnia export collapsed duplicate enabled collection variable '"
                                + safeText(variable.key) + "' using the last occurrence.");
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
            resource.addProperty("url", RequestParameterSupport.materializeUrl(
                    request.url, request.parameters, resolve ? resolver : null));
            addParameters(resource, request, resolver, resolve, warnings);
            JsonArray headers = CollectionExportSupport.toHeadersArray(request.headers, resolver, resolve);
            if (!headers.isEmpty()) {
                resource.add("headers", headers);
            }
            JsonObject body = CollectionExportSupport.bodyToInsomnia(request.body, resolver, resolve);
            if (body != null && !body.entrySet().isEmpty()) {
                resource.add("body", body);
                addBodyWarnings(request, warnings);
                addBodyApproximationWarnings(request, resolver, resolve, warnings);
            }
            JsonObject auth = requestAuth(request, resolver, resolve);
            if (auth != null) {
                resource.add("authentication", auth);
            }
            if (request.description != null && !request.description.isBlank()) {
                resource.addProperty("description", CollectionExportSupport.resolve(request.description, resolver, resolve));
            }
            addScripts(resource, request, resolver, resolve, warnings);
            resources.add(resource);
        }
    }

    private static void addParameters(JsonObject resource, ApiRequest request,
                                      VariableResolver resolver, boolean resolve,
                                      List<String> warnings) {
        JsonArray parameters = new JsonArray();
        if (request.parameters != null) {
            for (ApiRequest.Parameter parameter : request.parameters) {
                if (parameter == null) continue;
                if (!parameter.isQuery()) {
                    addWarning(warnings, "Insomnia export for request '" + safeRequestName(request)
                            + "' cannot represent parameter location '" + safeText(parameter.location) + "'.");
                    continue;
                }
                JsonObject row = new JsonObject();
                String key = CollectionExportSupport.resolve(parameter.key, resolver, resolve);
                row.addProperty("name", key != null ? key : "");
                if (parameter.valuePresent) {
                    String value = CollectionExportSupport.resolve(parameter.value, resolver, resolve);
                    row.addProperty("value", value != null ? value : "");
                }
                if (parameter.disabled) row.addProperty("disabled", true);
                if (parameter.description != null && !parameter.description.isBlank()) {
                    row.addProperty("description", CollectionExportSupport.resolve(parameter.description, resolver, resolve));
                }
                if (parameter.type != null && !parameter.type.isBlank()) {
                    row.addProperty("type", CollectionExportSupport.resolve(parameter.type, resolver, resolve));
                }
                parameters.add(row);
            }
        }
        if (!parameters.isEmpty()) resource.add("parameters", parameters);
    }

    private static void addScripts(JsonObject resource, ApiRequest request,
                                   VariableResolver resolver, boolean resolve,
                                   List<String> warnings) {
        String pre = scriptSource(request, ScriptPhase.PRE_REQUEST,
                request.preRequestScripts, resolver, resolve, warnings);
        String post = combinedPostSource(request, resolver, resolve, warnings);
        if (pre != null && !pre.isBlank()) resource.addProperty("preRequestScript", pre);
        if (post != null && !post.isBlank()) resource.addProperty("afterResponseScript", post);
    }

    private static String combinedPostSource(ApiRequest request, VariableResolver resolver,
                                             boolean resolve, List<String> warnings) {
        List<String> sources = new ArrayList<>();
        boolean hasBlocks = hasPhaseBlocks(request, ScriptPhase.POST_RESPONSE)
                || hasPhaseBlocks(request, ScriptPhase.TEST);
        if (hasBlocks) {
            List<ScriptBlock> sorted = sortedBlocks(request);
            for (ScriptBlock block : sorted) {
                if (block == null || (block.phase != ScriptPhase.POST_RESPONSE && block.phase != ScriptPhase.TEST)) continue;
                if (!block.enabled) {
                    addWarning(warnings, "Insomnia export omitted disabled " + block.phase
                            + " script for request '" + safeRequestName(request) + "'.");
                } else if (block.source != null && !block.source.isBlank()) {
                    if (block.phase == ScriptPhase.TEST) {
                        addWarning(warnings, "Insomnia export represented TEST script as after-response code for request '"
                                + safeRequestName(request) + "'.");
                    }
                    sources.add(CollectionExportSupport.resolve(block.source, resolver, resolve));
                }
            }
        } else if (request.postResponseScripts != null) {
            addLegacySources(sources, request.postResponseScripts, resolver, resolve);
        }
        sources.removeIf(value -> value == null || value.isBlank());
        return sources.isEmpty() ? null : String.join("\n\n", sources);
    }

    private static String scriptSource(ApiRequest request, ScriptPhase phase,
                                       List<ApiRequest.Script> legacy,
                                       VariableResolver resolver, boolean resolve,
                                       List<String> warnings) {
        List<String> sources = new ArrayList<>();
        if (hasPhaseBlocks(request, phase)) {
            for (ScriptBlock block : sortedBlocks(request)) {
                if (block == null || block.phase != phase) continue;
                if (!block.enabled) {
                    addWarning(warnings, "Insomnia export omitted disabled " + phase
                            + " script for request '" + safeRequestName(request) + "'.");
                } else if (block.source != null && !block.source.isBlank()) {
                    sources.add(CollectionExportSupport.resolve(block.source, resolver, resolve));
                }
            }
        } else {
            addLegacySources(sources, legacy, resolver, resolve);
        }
        sources.removeIf(value -> value == null || value.isBlank());
        return sources.isEmpty() ? null : String.join("\n\n", sources);
    }

    private static void addLegacySources(List<String> target, List<ApiRequest.Script> legacy,
                                         VariableResolver resolver, boolean resolve) {
        if (legacy == null) return;
        for (ApiRequest.Script script : legacy) {
            if (script != null && script.exec != null && !script.exec.isBlank()) {
                target.add(CollectionExportSupport.resolve(script.exec, resolver, resolve));
            }
        }
    }

    private static boolean hasPhaseBlocks(ApiRequest request, ScriptPhase phase) {
        if (request.scriptBlocks == null) return false;
        for (ScriptBlock block : request.scriptBlocks) {
            if (block != null && block.phase == phase) return true;
        }
        return false;
    }

    private static List<ScriptBlock> sortedBlocks(ApiRequest request) {
        List<ScriptBlock> sorted = new ArrayList<>(request.scriptBlocks != null ? request.scriptBlocks : List.of());
        sorted.sort(Comparator.comparingInt(block -> block != null ? block.order : Integer.MAX_VALUE));
        return sorted;
    }

    private static void addBodyWarnings(ApiRequest request, List<String> warnings) {
        if (request.body == null || request.body.formdata == null) return;
        for (ApiRequest.Body.FormField field : request.body.formdata) {
            if (field != null && (field.fileUpload || "file".equalsIgnoreCase(field.type))
                    && field.filePath == null) {
                addWarning(warnings, "Insomnia export retained multipart file field '"
                        + safeText(field.key) + "' for request '" + safeRequestName(request)
                        + "' without a file path.");
            }
        }
    }

    private static void addBodyApproximationWarnings(ApiRequest request, VariableResolver resolver,
                                                     boolean resolve, List<String> warnings) {
        if (request == null || request.body == null || request.body.mode == null) return;
        if ("file".equalsIgnoreCase(request.body.mode)) {
            String path = CollectionExportSupport.resolve(request.body.raw, resolver, resolve);
            if (path == null || path.isEmpty()) {
                addWarning(warnings, "Insomnia export represented empty file body for request '"
                        + safeRequestName(request) + "' as file metadata; runtime file transport requires validation.");
            } else {
                addWarning(warnings, "Insomnia export represented file body for request '"
                        + safeRequestName(request) + "' as file metadata; runtime file transport requires validation.");
            }
        } else if ("graphql".equalsIgnoreCase(request.body.mode)) {
            String variables = CollectionExportSupport.resolve(
                    request.body.graphql != null ? request.body.graphql.variables : null, resolver, resolve);
            if (variables != null && !variables.isBlank()) {
                try {
                    JsonParser.parseString(variables);
                } catch (RuntimeException invalidJson) {
                    addWarning(warnings, "Insomnia export retained invalid GraphQL variables as text for request '"
                            + safeRequestName(request) + "'.");
                }
            }
            addWarning(warnings, "Insomnia export represented GraphQL body for request '"
                    + safeRequestName(request) + "' as an application/json transport payload.");
        }
    }

    private static String safeRequestName(ApiRequest request) {
        return request != null ? safeText(request.name) : "Request";
    }

    private static String safeText(String value) {
        return value != null ? value.replaceAll("[\\r\\n]", " ") : "";
    }

    private static void addWarning(List<String> warnings, String warning) {
        if (warnings != null && warning != null && !warning.isBlank() && !warnings.contains(warning)) {
            warnings.add(warning);
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
