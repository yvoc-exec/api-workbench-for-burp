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
import java.util.Set;

public final class InsomniaCollectionExporter {
    private static final Set<String> SUPPORTED_AUTH = Set.of(
            "none", "noauth", "basic", "bearer", "apikey", "oauth2");
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
        ExportIds.Allocator idAllocator = new ExportIds.Allocator();
        long[] sortKey = {-1000000000L};

        String desiredWorkspaceId = collection != null && collection.id != null && !collection.id.isBlank()
                ? collection.id : ExportIds.workspaceId(collection);
        String workspaceId = idAllocator.allocate(desiredWorkspaceId, ExportIds.workspaceId(collection));
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
            env.addProperty("_id", idAllocator.allocate(
                    ExportIds.environmentId(activeEnvironment != null ? activeEnvironment : new EnvironmentProfile()),
                    "env_base"));
            env.addProperty("_type", "environment");
            env.addProperty("parentId", workspaceId);
            env.addProperty("name", collection.name != null ? collection.name + " Environment" : "Environment");
            JsonObject data = new JsonObject();
            Map<String, String> merged = new LinkedHashMap<>();
            Set<String> variableOverrides = new java.util.HashSet<>();
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
                    variableOverrides.add(variable.key);
                    merged.put(variable.key, variable.value != null ? variable.value : "");
                }
            }
            VariableResolver resolver = CollectionExportSupport.buildResolver(collection, null, activeEnvironment, exportOnly);
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(merged).entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    addWarning(warnings, "Insomnia export skipped a blank environment variable key.");
                    continue;
                }
                String resolved = CollectionExportSupport.resolve(entry.getValue(), resolver, resolve);
                com.google.gson.JsonElement typed = variableOverrides.contains(entry.getKey()) ? null
                        : burp.parser.InsomniaEnvironmentValueTypes.recalled(
                        collection, "", entry.getKey(), entry.getValue());
                if (typed != null && !resolve) data.add(entry.getKey(), typed);
                else {
                    data.addProperty(entry.getKey(), resolved != null ? resolved : "");
                    warnJsonLookingEnvironmentString(collection, "", entry.getKey(), entry.getValue(), typed, warnings);
                }
            }
            env.add("data", data);
            resources.add(env);
        }

        CollectionExportTree.FolderNode tree = CollectionExportTree.build(collection);
        if (collection != null) {
            if (collection.auth != null && collection.auth.type != null && !collection.auth.type.isBlank()) {
                addWarning(warnings, "Insomnia export represented collection authentication on top-level request resources.");
            }
            addCollectionScriptWarnings(collection, warnings);
            Map<String, String> folderIds = new LinkedHashMap<>();
            folderIds.put("", workspaceId);
            writeFolderResources(resources, collection, tree, workspaceId, true, activeEnvironment,
                    exportOnly, resolve, warnings, idAllocator, sortKey, folderIds);
            writeRequestResources(resources, collection, workspaceId, folderIds, activeEnvironment,
                    exportOnly, resolve, warnings, idAllocator, sortKey);
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
                                             boolean topLevel,
                                             EnvironmentProfile activeEnvironment,
                                             Map<String, String> exportOnly,
                                             boolean resolve,
                                             List<String> warnings,
                                             ExportIds.Allocator idAllocator,
                                             long[] sortKey,
                                             Map<String, String> folderIds) {
        for (CollectionExportTree.FolderNode child : node.children.values()) {
            String desiredFolderId = ExportIds.folderId(child.path);
            String folderId = idAllocator.allocate(desiredFolderId, desiredFolderId);
            JsonObject group = new JsonObject();
            group.addProperty("_id", folderId);
            group.addProperty("_type", "request_group");
            group.addProperty("name", child.name);
            group.addProperty("parentId", parentId);
            group.addProperty("metaSortKey", sortKey[0]++);
            VariableResolver resolver = CollectionExportSupport.buildResolver(collection, dummyRequestForFolder(child.path), activeEnvironment, exportOnly);
            JsonObject auth = folderAuth(collection, child.path, resolver, resolve, warnings);
            if (auth == null && topLevel && collection.auth != null) {
                auth = insomniaAuth(collection.auth, resolver, resolve,
                        "top-level folder '" + safeText(child.path) + "'", warnings);
            }
            if (auth != null) {
                group.add("authentication", auth);
            }
            Map<String, String> folderEnvironment = collection.folderVars != null
                    ? collection.folderVars.get(burp.utils.AuthInheritanceResolver.normalizeFolderPath(child.path)) : null;
            if (folderEnvironment != null && !folderEnvironment.isEmpty()) {
                JsonObject environment = new JsonObject();
                for (Map.Entry<String, String> entry : new java.util.TreeMap<>(folderEnvironment).entrySet()) {
                    com.google.gson.JsonElement typed = burp.parser.InsomniaEnvironmentValueTypes.recalled(
                            collection, burp.utils.AuthInheritanceResolver.normalizeFolderPath(child.path),
                            entry.getKey(), entry.getValue());
                    if (typed != null && !resolve) environment.add(entry.getKey(), typed);
                    else {
                        environment.addProperty(entry.getKey(), CollectionExportSupport.resolve(entry.getValue(), resolver, resolve));
                        warnJsonLookingEnvironmentString(collection, child.path, entry.getKey(), entry.getValue(), typed, warnings);
                    }
                }
                group.add("environment", environment);
            }
            addFolderScripts(group, collection, child.path, resolver, resolve, warnings);
            resources.add(group);
            folderIds.put(burp.utils.AuthInheritanceResolver.normalizeFolderPath(child.path), folderId);
            writeFolderResources(resources, collection, child, folderId, false, activeEnvironment,
                    exportOnly, resolve, warnings, idAllocator, sortKey, folderIds);
        }
    }

    private static void warnJsonLookingEnvironmentString(ApiCollection collection, String scope, String key,
                                                         String value, com.google.gson.JsonElement typed,
                                                         List<String> warnings) {
        if (typed != null || value == null) return;
        try {
            com.google.gson.JsonElement parsed = JsonParser.parseString(value);
            if (parsed.isJsonObject() || parsed.isJsonArray()) {
                addWarning(warnings, "Insomnia export preserved JSON-looking environment value '"
                        + safeText(key) + "' in " + (scope == null || scope.isBlank() ? "the base environment"
                        : "folder '" + safeText(scope) + "'") + " as a string because its source type is unknown.");
            }
        } catch (RuntimeException ignored) {
            // Ordinary string.
        }
    }

    private static void addCollectionScriptWarnings(ApiCollection collection, List<String> warnings) {
        if (collection.scriptBlocks == null) return;
        Set<ScriptPhase> phases = new java.util.LinkedHashSet<>();
        for (ScriptBlock block : collection.scriptBlocks) if (block != null) phases.add(block.phase);
        for (ScriptPhase phase : phases) {
            addWarning(warnings, "Insomnia export omitted collection-level " + phase
                    + " script blocks because workspaces have no equivalent script scope.");
        }
    }

    private static void addFolderScripts(JsonObject group, ApiCollection collection, String folderPath,
                                         VariableResolver resolver, boolean resolve, List<String> warnings) {
        if (collection.folderScriptBlocks == null) return;
        List<ScriptBlock> blocks = collection.folderScriptBlocks.get(
                burp.utils.AuthInheritanceResolver.normalizeFolderPath(folderPath));
        if (blocks == null || blocks.isEmpty()) return;
        List<ScriptBlock> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparingInt(block -> block != null ? block.order : Integer.MAX_VALUE));
        List<String> pre = new ArrayList<>();
        List<String> post = new ArrayList<>();
        for (ScriptBlock block : sorted) {
            if (block == null) continue;
            if (!block.enabled) {
                addWarning(warnings, "Insomnia export omitted disabled " + block.phase
                        + " folder script for '" + safeText(folderPath) + "'.");
                continue;
            }
            if (block.source == null) continue;
            String source = CollectionExportSupport.resolve(block.source, resolver, resolve);
            if (block.phase == ScriptPhase.PRE_REQUEST) pre.add(source);
            else {
                if (block.phase == ScriptPhase.TEST) {
                    addWarning(warnings, "Insomnia export represented TEST folder script as after-response code for '"
                            + safeText(folderPath) + "'.");
                }
                post.add(source);
            }
        }
        if (!pre.isEmpty()) group.addProperty("preRequestScript", String.join("\n\n", pre));
        if (!post.isEmpty()) group.addProperty("afterResponseScript", String.join("\n\n", post));
    }

    private static void writeRequestResources(JsonArray resources,
                                              ApiCollection collection,
                                              String workspaceId,
                                              Map<String, String> folderIds,
                                              EnvironmentProfile activeEnvironment,
                                              Map<String, String> exportOnly,
                                              boolean resolve,
                                              List<String> warnings,
                                              ExportIds.Allocator idAllocator,
                                              long[] sortKey) {
        int requestIndex = 0;
        for (ApiRequest request : collection.requests != null ? collection.requests : List.<ApiRequest>of()) {
            if (request == null) {
                continue;
            }
            String desiredRequestId = request.id != null && !request.id.isBlank()
                    ? request.id : ExportIds.requestId(request, requestIndex);
            String requestId = idAllocator.allocate(desiredRequestId,
                    ExportIds.requestId(request, requestIndex));
            if (!requestId.equals(desiredRequestId)) {
                addWarning(warnings, "Insomnia export disambiguated a duplicate resource ID for request '"
                        + safeRequestName(request) + "'.");
            }
            requestIndex++;
            String folderPath = burp.utils.RequestPathResolver.getRequestFolderPath(collection, request);
            folderPath = burp.utils.AuthInheritanceResolver.normalizeFolderPath(folderPath);
            String parentId = folderIds.getOrDefault(folderPath, workspaceId);
            boolean topLevel = folderPath.isEmpty();
            JsonObject resource = new JsonObject();
            resource.addProperty("_id", requestId);
            resource.addProperty("_type", "request");
            resource.addProperty("parentId", parentId);
            resource.addProperty("metaSortKey", sortKey[0]++);
            VariableResolver resolver = CollectionExportSupport.buildResolver(collection, request, activeEnvironment, exportOnly);
            if (resolve) ExportVariableResolutionService.addDuplicateEnabledVariableWarnings(collection, request, warnings);
            resource.addProperty("name", CollectionExportSupport.resolve(request.name, resolver, resolve) != null ? CollectionExportSupport.resolve(request.name, resolver, resolve) : "");
            resource.addProperty("method", CollectionExportSupport.resolve(request.method, resolver, resolve) != null ? CollectionExportSupport.resolve(request.method, resolver, resolve) : "GET");
            List<ApiRequest.Parameter> exportParameters = insomniaParameters(request, warnings);
            String resolvedUrl = CollectionExportSupport.resolve(request.url, resolver, resolve);
            resource.addProperty("url", RequestParameterSupport.hasQueryParameters(exportParameters)
                    ? RequestParameterSupport.stripQuery(resolvedUrl)
                    : resolvedUrl);
            addParameters(resource, request, exportParameters, resolver, resolve, warnings);
            JsonArray headers = CollectionExportSupport.toInsomniaHeadersArray(request.headers, resolver, resolve);
            if (!headers.isEmpty()) {
                resource.add("headers", headers);
            }
            JsonObject body = CollectionExportSupport.bodyToInsomnia(request.body, resolver, resolve);
            if (body != null && !body.entrySet().isEmpty()) {
                resource.add("body", body);
                addBodyWarnings(request, warnings);
                addBodyApproximationWarnings(request, resolver, resolve, warnings);
            }
            JsonObject auth = requestAuth(request, resolver, resolve, warnings);
            if (auth == null && topLevel && collection.auth != null) {
                auth = insomniaAuth(collection.auth, resolver, resolve,
                        "root request '" + safeRequestName(request) + "'", warnings);
            }
            if (auth != null) {
                resource.add("authentication", auth);
            }
            if (request.description != null && !request.description.isBlank()) {
                resource.addProperty("description", CollectionExportSupport.resolve(request.description, resolver, resolve));
            }
            addScripts(resource, request, resolver, resolve, warnings);
            if (request.variables != null && !request.variables.isEmpty()) {
                addWarning(warnings, "Insomnia export omitted " + request.variables.size()
                        + " request variables for request '" + safeRequestName(request) + "'.");
            }
            resources.add(resource);
        }
    }

    private static void addParameters(JsonObject resource, ApiRequest request,
                                      List<ApiRequest.Parameter> exportParameters,
                                      VariableResolver resolver, boolean resolve,
                                      List<String> warnings) {
        JsonArray parameters = new JsonArray();
        JsonArray pathParameters = new JsonArray();
        java.util.Set<String> pathApproximation = new java.util.LinkedHashSet<>();
        java.util.Set<String> queryApproximation = new java.util.LinkedHashSet<>();
        if (exportParameters != null) {
            for (ApiRequest.Parameter parameter : exportParameters) {
                if (parameter == null) continue;
                if (!parameter.isQuery()) {
                    if (!"path".equalsIgnoreCase(parameter.location)) {
                        addWarning(warnings, "Insomnia export for request '" + safeRequestName(request)
                                + "' cannot represent parameter location '" + safeText(parameter.location) + "'.");
                        continue;
                    }
                    if (parameter.disabled) pathApproximation.add("disabled");
                    if (!parameter.valuePresent) pathApproximation.add("bare");
                    if (parameter.description != null && !parameter.description.isBlank()) pathApproximation.add("description");
                    if (parameter.type != null && !parameter.type.isBlank()) pathApproximation.add("type");
                    if (parameter.required) pathApproximation.add("required");
                    if (parameter.style != null && !parameter.style.isBlank()) pathApproximation.add("style");
                    if (parameter.explode != null) pathApproximation.add("explode");
                    if (parameter.allowReserved) pathApproximation.add("allowReserved");
                    if (!parameter.disabled) {
                        JsonObject pathRow = new JsonObject();
                        String key = CollectionExportSupport.resolve(parameter.key, resolver, resolve);
                        String value = CollectionExportSupport.resolve(parameter.value, resolver, resolve);
                        pathRow.addProperty("name", key != null ? key : "");
                        pathRow.addProperty("value", value != null ? value : "");
                        pathParameters.add(pathRow);
                    }
                    continue;
                }
                if (parameter.required) queryApproximation.add("required");
                if (parameter.style != null && !parameter.style.isBlank()) queryApproximation.add("style");
                if (parameter.explode != null) queryApproximation.add("explode");
                if (parameter.allowReserved) queryApproximation.add("allowReserved");
                if (parameter.source != null && !parameter.source.isBlank()) queryApproximation.add("source");
                JsonObject row = new JsonObject();
                String key = CollectionExportSupport.resolve(parameter.key, resolver, resolve);
                row.addProperty("name", key != null ? key : "");
                String value = CollectionExportSupport.resolve(parameter.value, resolver, resolve);
                row.addProperty("value", value != null ? value : "");
                if (!parameter.valuePresent) queryApproximation.add("bare-versus-empty");
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
        if (!pathParameters.isEmpty()) resource.add("pathParameters", pathParameters);
        if (!pathApproximation.isEmpty()) {
            addWarning(warnings, "Insomnia export approximated path parameter metadata for request '"
                    + safeRequestName(request) + "': " + String.join(", ", pathApproximation) + ".");
        }
        if (queryApproximation.remove("bare-versus-empty")) {
            addWarning(warnings, "Insomnia export for request '" + safeRequestName(request)
                    + "' cannot preserve bare query parameters; exported them as explicit-empty values.");
        }
        if (!queryApproximation.isEmpty()) {
            addWarning(warnings, "Insomnia export omitted unsupported query parameter metadata for request '"
                    + safeRequestName(request) + "': " + String.join(", ", queryApproximation) + ".");
        }
    }

    private static List<ApiRequest.Parameter> insomniaParameters(ApiRequest request, List<String> warnings) {
        List<ApiRequest.Parameter> result = RequestParameterSupport.copyParameters(request.parameters);
        if (!RequestParameterSupport.hasQueryParameters(result)) return result;
        List<ApiRequest.Parameter> raw = RequestParameterSupport.parseQueryParameters(request.url, "insomnia:export:url.raw");
        boolean[] consumed = new boolean[raw.size()];
        for (ApiRequest.Parameter parameter : result) {
            if (parameter == null || !parameter.isQuery()) continue;
            for (int index = 0; index < raw.size(); index++) {
                ApiRequest.Parameter candidate = raw.get(index);
                if (!consumed[index] && candidate != null
                        && java.util.Objects.equals(parameter.key, candidate.key)
                        && java.util.Objects.equals(parameter.value != null ? parameter.value : "",
                        candidate.value != null ? candidate.value : "")
                        && parameter.valuePresent == candidate.valuePresent) {
                    consumed[index] = true;
                    break;
                }
            }
        }
        boolean appended = false;
        for (int index = 0; index < raw.size(); index++) if (!consumed[index]) {
            result.add(raw.get(index));
            appended = true;
        }
        if (appended) {
            addWarning(warnings, "Insomnia export retained unmatched raw URL query segments once for request '"
                    + safeRequestName(request) + "'.");
        }
        return result;
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
        return ExportWarningSupport.label(value);
    }

    private static void addWarning(List<String> warnings, String warning) {
        ExportWarningSupport.add(warnings, warning);
    }

    private static JsonObject requestAuth(ApiRequest request, VariableResolver resolver, boolean resolve,
                                          List<String> warnings) {
        if (request == null) {
            return null;
        }
        String mode = burp.utils.AuthInheritanceResolver.normalizeAuthOverrideMode(
                request.authOverrideMode, request);
        if ("inherit".equals(mode)) {
            return null;
        }
        if ("none".equals(mode) || request.authExplicitlyDisabled) {
            JsonObject none = new JsonObject();
            none.addProperty("type", "none");
            return none;
        }
        ApiRequest.Auth auth = request.explicitAuth != null ? request.explicitAuth : request.auth;
        return insomniaAuth(auth, resolver, resolve,
                "request '" + safeRequestName(request) + "'", warnings);
    }

    private static JsonObject folderAuth(ApiCollection collection, String folderPath, VariableResolver resolver,
                                         boolean resolve, List<String> warnings) {
        if (collection == null || folderPath == null || collection.folderAuthModes == null) {
            return null;
        }
        String normalized = burp.utils.AuthInheritanceResolver.normalizeFolderPath(folderPath);
        String mode = collection.folderAuthModes.get(normalized);
        if (mode == null || "inherit".equalsIgnoreCase(mode)) {
            return null;
        }
        if ("none".equalsIgnoreCase(mode)) {
            JsonObject none = new JsonObject();
            none.addProperty("type", "none");
            return none;
        }
        ApiRequest.Auth auth = collection.folderAuth != null ? collection.folderAuth.get(normalized) : null;
        return insomniaAuth(auth, resolver, resolve,
                "folder '" + safeText(folderPath) + "'", warnings);
    }

    private static JsonObject insomniaAuth(ApiRequest.Auth auth,
                                           VariableResolver resolver,
                                           boolean resolve,
                                           String context,
                                           List<String> warnings) {
        if (auth != null && auth.type != null && !auth.type.isBlank()) {
            String type = auth.type.toLowerCase(java.util.Locale.ROOT);
            if (!SUPPORTED_AUTH.contains(type)) {
                addWarning(warnings, "Insomnia export retained unsupported authentication type '"
                        + safeText(type) + "' for " + context + "; runtime application may be unsupported.");
            }
            if (("apikey".equals(type) || "api_key".equals(type)) && auth.properties != null) {
                String placement = firstNonBlank(auth.properties, "in", "placement", "addTo");
                if (placement != null && !isSupportedApiKeyPlacement(placement)) {
                    addWarning(warnings, "Insomnia export retained unsupported API-key placement '"
                            + safeText(placement) + "' for " + context + ".");
                }
            }
            if (auth.properties != null && ("apikey".equals(type) || "api_key".equals(type) || "oauth2".equals(type))) {
                Set<String> supported = "oauth2".equals(type)
                        ? Set.of("grantType", "grant_type", "accessTokenUrl", "access_token_url",
                        "authorizationUrl", "authorization_url", "redirectUrl", "redirectUri", "callback_url",
                        "clientId", "client_id", "clientSecret", "client_secret", "scope", "username",
                        "password", "accessToken", "access_token")
                        : Set.of("key", "name", "value", "token", "in", "placement", "addTo");
                Set<String> unsupported = new java.util.TreeSet<>();
                for (String key : auth.properties.keySet()) if (key != null && !supported.contains(key)) unsupported.add(key);
                if (!unsupported.isEmpty()) {
                    addWarning(warnings, "Insomnia export omitted unsupported " + type
                            + " authentication properties for " + context + ": "
                            + String.join(", ", unsupported) + ".");
                }
            }
        }
        return CollectionExportSupport.authToInsomnia(auth, resolver, resolve);
    }

    private static String firstNonBlank(Map<String, String> values, String... keys) {
        if (values == null) return null;
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static boolean isSupportedApiKeyPlacement(String value) {
        return "header".equalsIgnoreCase(value)
                || "query".equalsIgnoreCase(value)
                || "queryParams".equalsIgnoreCase(value);
    }

    private static ApiRequest dummyRequestForFolder(String folderPath) {
        ApiRequest request = new ApiRequest();
        request.name = "__folder__";
        request.path = folderPath;
        return request;
    }
}
