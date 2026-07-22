package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.ExactHttpRequestSnapshot;
import burp.scripts.ScriptBlock;
import burp.parser.VariableResolver;
import burp.utils.AuthInheritanceResolver;
import burp.utils.ExactHttpRequestSnapshotMigrationSupport;
import burp.utils.RequestPathResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ApiWorkbenchCollectionExporter {
    private ApiWorkbenchCollectionExporter() {
    }

    public static JsonObject build(ApiCollection collection, CollectionExportOptions options, List<String> warnings) {
        boolean resolve = options != null && options.resolveVariablesUsingActiveEnvironment;
        EnvironmentProfile activeEnvironment = options != null ? options.activeEnvironment : null;
        Map<String, String> exportOnly = options != null ? options.exportOnlyVariables : Map.of();

        JsonObject root = new JsonObject();
        root.addProperty("format", "api-workbench-collection");
        root.addProperty("schemaVersion", 2);

        JsonObject col = new JsonObject();
        if (collection != null) {
            String collectionId = collection.ensureId();
            ApiCollection canonical = canonicalCollection(collection, collectionId);
            col.addProperty("id", collectionId);
            VariableResolver collectionResolver = CollectionExportSupport.buildResolver(canonical, null, activeEnvironment, exportOnly);
            col.addProperty("name", CollectionExportSupport.resolve(canonical.name, collectionResolver, resolve));
            if (canonical.description != null) {
                col.addProperty("description", CollectionExportSupport.resolve(canonical.description, collectionResolver, resolve));
            }
            col.addProperty("format", canonical.format);
            if (canonical.version != null) {
                col.addProperty("version", canonical.version);
            }
            col.add("sourceMetadata", mapToJson(canonical.sourceMetadata, null, false));

            JsonArray folderPaths = new JsonArray();
            if (canonical.folderPaths != null) {
                for (String folderPath : canonical.folderPaths) {
                    if (folderPath == null || folderPath.isBlank()) {
                        continue;
                    }
                    folderPaths.add(resolveIfNeeded(folderPath, collectionResolver, resolve));
                }
            }
            col.add("folderPaths", folderPaths);

            col.add("variables", CollectionExportSupport.toVariablesArray(canonical.variables, collectionResolver, resolve));
            col.add("environment", mapToJson(canonical.environment, collectionResolver, resolve));
            col.add("auth", CollectionExportSupport.authToJson(canonical.auth, collectionResolver, resolve));
            col.add("folderAuthModes", mapToJson(canonical.folderAuthModes, null, false));
            col.add("folderAuth", authMapToJson(canonical.folderAuth, collectionResolver, resolve));
            col.add("folderVars", nestedStringMapToJson(canonical.folderVars, collectionResolver, resolve));
            col.add("scriptBlocks", scriptBlocksToJson(canonical.scriptBlocks));
            col.add("folderScriptBlocks", folderScriptBlocksToJson(canonical.folderScriptBlocks));

            JsonArray requests = new JsonArray();
            if (canonical.requests != null) {
                for (ApiRequest request : canonical.requests) {
                    if (request == null) {
                        continue;
                    }
                    VariableResolver resolver = CollectionExportSupport.buildResolver(canonical, request, activeEnvironment, exportOnly);
                    requests.add(requestToJson(request, resolver, resolve));
                }
            }
            col.add("requests", requests);
        }
        root.add("collection", col);
        return root;
    }

    public static void write(ApiCollection collection, CollectionExportOptions options, Writer writer, List<String> warnings) throws IOException {
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        writer.write(gson.toJson(build(collection, options, warnings)));
    }

    private static ApiCollection canonicalCollection(ApiCollection source, String collectionId) {
        ApiCollection canonical = new ApiCollection();
        canonical.id = collectionId;
        canonical.name = nonBlankOr(source.name, "Untitled Collection");
        canonical.description = source.description;
        canonical.format = source.format != null ? source.format : "api-workbench";
        canonical.version = source.version;
        canonical.sourceMetadata = source.sourceMetadata;
        canonical.variables = source.variables;
        canonical.environment = source.environment;
        canonical.auth = canonicalAuth(source.auth);
        canonical.scriptBlocks = source.scriptBlocks;
        canonical.runtimeVars = source.runtimeVars;
        canonical.runtimeOAuth2 = source.runtimeOAuth2;
        canonical.runtimeFolderVars = source.runtimeFolderVars;

        canonical.folderPaths = canonicalFolderPaths(source.folderPaths);
        canonical.folderAuthModes = canonicalFolderMap(source.folderAuthModes);
        canonical.folderAuth = canonicalAuthMap(source.folderAuth);
        canonical.folderVars = canonicalFolderMap(source.folderVars);
        canonical.folderScriptBlocks = canonicalFolderMap(source.folderScriptBlocks);
        canonicalizeFolderAuthState(canonical);

        Map<ApiRequest, String> retainedAuthSources = new LinkedHashMap<>();
        canonical.requests = new ArrayList<>();
        if (source.requests != null) {
            for (ApiRequest request : source.requests) {
                if (request == null) {
                    continue;
                }
                ApiRequest copy = request.applyTo(new ApiRequest());
                copy.name = nonBlankOr(copy.name, "Unnamed Request");
                copy.path = RequestPathResolver.normalizeFolderPath(copy.path);
                copy.sourceCollection = nonBlankOr(copy.sourceCollection, canonical.name);
                copy.method = nonBlankOr(copy.method, "GET");
                copy.url = copy.url != null ? copy.url : "";
                copy.buildMode = copy.buildMode != null
                        ? copy.buildMode
                        : copy.editorMaterialized
                        ? ApiRequest.BuildMode.MANUAL_PRESERVE
                        : ApiRequest.BuildMode.AUTO_COMPATIBLE;
                copy.auth = canonicalAuth(copy.auth);
                copy.explicitAuth = canonicalAuth(copy.explicitAuth);
                copy.authOverrideMode = canonicalAuthOverrideMode(copy);
                copy.body = canonicalBody(request.body, copy.body);
                canonicalizeParameters(copy.parameters);
                canonicalizeScripts(copy.preRequestScripts);
                canonicalizeScripts(copy.postResponseScripts);
                copy.exactHttpRequest = ExactHttpRequestSnapshot.copyOf(copy.exactHttpRequest);
                ExactHttpRequestSnapshotMigrationSupport.migrateLegacySemanticFingerprint(copy);
                retainedAuthSources.put(copy, request.authSource);
                canonical.requests.add(copy);
            }
        }

        AuthInheritanceResolver.recomputeCollectionAuth(canonical);
        for (Map.Entry<ApiRequest, String> entry : retainedAuthSources.entrySet()) {
            if (entry.getValue() != null) {
                entry.getKey().authSource = entry.getValue();
            }
        }
        return canonical;
    }

    private static String nonBlankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String canonicalAuthOverrideMode(ApiRequest request) {
        String mode = AuthInheritanceResolver.normalizeAuthOverrideMode(request.authOverrideMode, request);
        if ("explicit".equals(mode) && request.explicitAuth == null && request.auth == null) {
            return "inherit";
        }
        if ("explicit".equals(mode)
                && ((request.explicitAuth != null && "none".equalsIgnoreCase(request.explicitAuth.type))
                || (request.explicitAuth == null && request.auth != null && "none".equalsIgnoreCase(request.auth.type)))) {
            return "none";
        }
        return mode;
    }

    private static ApiRequest.Auth canonicalAuth(ApiRequest.Auth source) {
        if (source == null || source.type == null || source.type.isBlank()
                || "inherit".equalsIgnoreCase(source.type)
                || "inheritauth".equalsIgnoreCase(source.type)) {
            return null;
        }
        ApiRequest.Auth canonical = new ApiRequest.Auth();
        String type = source.type.trim();
        canonical.type = "noauth".equalsIgnoreCase(type) ? "none" : type;
        if (source.properties != null) {
            for (Map.Entry<String, String> entry : source.properties.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank()) {
                    canonical.properties.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
                }
            }
        }
        return canonical;
    }

    private static List<String> canonicalFolderPaths(List<String> paths) {
        List<String> canonical = new ArrayList<>();
        if (paths == null) {
            return canonical;
        }
        for (String path : paths) {
            String normalized = RequestPathResolver.normalizeFolderPath(path);
            if (!normalized.isBlank()) {
                canonical.add(normalized);
            }
        }
        return canonical;
    }

    private static <T> Map<String, T> canonicalFolderMap(Map<String, T> source) {
        Map<String, T> canonical = new LinkedHashMap<>();
        if (source == null) {
            return canonical;
        }
        for (Map.Entry<String, T> entry : source.entrySet()) {
            String key = RequestPathResolver.normalizeFolderPath(entry.getKey());
            if (!key.isBlank()) {
                canonical.put(key, entry.getValue());
            }
        }
        return canonical;
    }

    private static Map<String, ApiRequest.Auth> canonicalAuthMap(Map<String, ApiRequest.Auth> source) {
        Map<String, ApiRequest.Auth> canonical = new LinkedHashMap<>();
        if (source == null) {
            return canonical;
        }
        for (Map.Entry<String, ApiRequest.Auth> entry : source.entrySet()) {
            String key = RequestPathResolver.normalizeFolderPath(entry.getKey());
            ApiRequest.Auth auth = canonicalAuth(entry.getValue());
            if (!key.isBlank() && auth != null) {
                canonical.put(key, auth);
            }
        }
        return canonical;
    }

    private static void canonicalizeFolderAuthState(ApiCollection collection) {
        for (Map.Entry<String, String> entry : new ArrayList<>(collection.folderAuthModes.entrySet())) {
            String path = entry.getKey();
            ApiRequest.Auth auth = collection.folderAuth.get(path);
            String mode = normalizeFolderAuthMode(entry.getValue(), auth);
            collection.folderAuthModes.put(path, mode);
            if ("inherit".equals(mode)) {
                collection.folderAuth.remove(path);
            } else if ("none".equals(mode)) {
                ApiRequest.Auth none = auth != null ? canonicalAuth(auth) : new ApiRequest.Auth();
                if (none == null) {
                    none = new ApiRequest.Auth();
                }
                none.type = "none";
                collection.folderAuth.put(path, none);
            }
        }
    }

    private static String normalizeFolderAuthMode(String mode, ApiRequest.Auth auth) {
        if (mode != null && !mode.isBlank()) {
            String normalized = mode.trim().toLowerCase(Locale.ROOT);
            if ("inherit".equals(normalized) || "explicit".equals(normalized) || "none".equals(normalized)) {
                return normalized;
            }
        }
        return auth != null && "none".equalsIgnoreCase(auth.type) ? "none" : "explicit";
    }

    private static ApiRequest.Body canonicalBody(ApiRequest.Body source, ApiRequest.Body copy) {
        if (source == null || copy == null) {
            return null;
        }
        if (copy.mode == null || copy.mode.isBlank()) {
            if (source.raw != null) {
                copy.mode = "raw";
            } else if (source.urlencoded != null) {
                copy.mode = "urlencoded";
            } else if (source.formdata != null) {
                copy.mode = "formdata";
            } else if (source.graphql != null) {
                copy.mode = "graphql";
            } else {
                copy.mode = "none";
            }
        }
        canonicalizeFormFields(copy.urlencoded);
        canonicalizeFormFields(copy.formdata);
        return copy;
    }

    private static void canonicalizeFormFields(List<ApiRequest.Body.FormField> fields) {
        if (fields == null) {
            return;
        }
        for (ApiRequest.Body.FormField field : fields) {
            if (field != null && "file".equalsIgnoreCase(field.type)) {
                field.fileUpload = true;
            }
        }
    }

    private static void canonicalizeParameters(List<ApiRequest.Parameter> parameters) {
        if (parameters == null) {
            return;
        }
        for (ApiRequest.Parameter parameter : parameters) {
            if (parameter != null && (parameter.location == null || parameter.location.isBlank())) {
                parameter.location = "query";
            }
        }
    }

    private static void canonicalizeScripts(List<ApiRequest.Script> scripts) {
        if (scripts == null) {
            return;
        }
        for (ApiRequest.Script script : scripts) {
            if (script != null && script.exec != null && script.type == null) {
                script.type = "js";
            }
        }
    }

    private static JsonObject requestToJson(ApiRequest request, VariableResolver resolver, boolean resolve) {
        JsonObject out = new JsonObject();
        out.addProperty("id", request.id != null ? request.id : "");
        out.addProperty("name", CollectionExportSupport.resolve(request.name, resolver, resolve));
        String resolvedPath = CollectionExportSupport.resolve(request.path, resolver, resolve);
        out.addProperty("path", RequestPathResolver.normalizeFolderPath(resolvedPath));
        out.addProperty("sourceCollection", request.sourceCollection);
        out.addProperty("method", CollectionExportSupport.resolve(request.method, resolver, resolve) != null ? CollectionExportSupport.resolve(request.method, resolver, resolve) : "GET");
        out.addProperty("url", CollectionExportSupport.resolve(request.url, resolver, resolve) != null ? CollectionExportSupport.resolve(request.url, resolver, resolve) : "");
        out.add("sourceMetadata", mapToJson(request.sourceMetadata, null, false));
        out.add("parameters", parametersToJson(request.parameters, resolver, resolve));
        if (request.description != null) {
            out.addProperty("description", CollectionExportSupport.resolve(request.description, resolver, resolve));
        }
        out.addProperty("editorMaterialized", request.editorMaterialized);
        out.addProperty("buildMode", request.buildMode.name());
        out.add("suppressedAutoHeaders", toStringArray(request.suppressedAutoHeaders, resolver, resolve));
        out.add("headers", headersToJson(request.headers, resolver, resolve));
        out.add("body", bodyToJson(request.body, resolver, resolve));
        out.add("auth", CollectionExportSupport.authToJson(request.auth, resolver, resolve));
        out.add("explicitAuth", CollectionExportSupport.authToJson(request.explicitAuth, resolver, resolve));
        out.addProperty("authInherited", request.authInherited);
        out.addProperty("authExplicitlyDisabled", request.authExplicitlyDisabled);
        out.addProperty("authSource", CollectionExportSupport.resolve(request.authSource, resolver, resolve));
        out.addProperty("authOverrideMode", request.authOverrideMode);
        out.add("variables", CollectionExportSupport.toVariablesArray(request.variables, resolver, resolve));
        out.add("preRequestScripts", scriptsToJson(request.preRequestScripts, resolver, resolve));
        out.add("postResponseScripts", scriptsToJson(request.postResponseScripts, resolver, resolve));
        out.add("scriptBlocks", scriptBlocksToJson(request.scriptBlocks));
        out.addProperty("disabled", request.disabled);
        out.addProperty("sequenceOrder", request.sequenceOrder);
        out.add("exactHttpRequest", exactHttpRequestToJson(request.exactHttpRequest));
        return out;
    }

    private static JsonArray parametersToJson(List<ApiRequest.Parameter> parameters,
                                               VariableResolver resolver,
                                               boolean resolve) {
        JsonArray out = new JsonArray();
        if (parameters == null) {
            return out;
        }
        for (ApiRequest.Parameter parameter : parameters) {
            if (parameter == null) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("location", parameter.location);
            item.addProperty("key", CollectionExportSupport.resolve(parameter.key, resolver, resolve));
            item.addProperty("value", CollectionExportSupport.resolve(parameter.value, resolver, resolve));
            item.addProperty("rawKey", resolve
                    ? CollectionExportSupport.resolve(parameter.rawKey, resolver, true)
                    : parameter.rawKey);
            item.addProperty("rawValue", resolve
                    ? CollectionExportSupport.resolve(parameter.rawValue, resolver, true)
                    : parameter.rawValue);
            item.addProperty("valuePresent", parameter.valuePresent);
            item.addProperty("disabled", parameter.disabled);
            item.addProperty("required", parameter.required);
            item.addProperty("type", CollectionExportSupport.resolve(parameter.type, resolver, resolve));
            item.addProperty("format", CollectionExportSupport.resolve(parameter.format, resolver, resolve));
            item.addProperty("description", CollectionExportSupport.resolve(parameter.description, resolver, resolve));
            item.addProperty("style", CollectionExportSupport.resolve(parameter.style, resolver, resolve));
            if (parameter.explode != null) {
                item.addProperty("explode", parameter.explode);
            }
            item.addProperty("allowReserved", parameter.allowReserved);
            item.addProperty("source", parameter.source);
            item.add("sourceMetadata", mapToJson(parameter.sourceMetadata, null, false));
            out.add(item);
        }
        return out;
    }

    private static JsonObject exactHttpRequestToJson(ExactHttpRequestSnapshot snapshot) {
        JsonObject out = new JsonObject();
        if (snapshot == null) {
            return out;
        }
        if (snapshot.rawRequestBytes != null && snapshot.rawRequestBytes.length > 0) {
            out.addProperty("rawRequestBase64", java.util.Base64.getEncoder().encodeToString(snapshot.rawRequestBytes));
        }
        out.addProperty("serviceHost", snapshot.serviceHost != null ? snapshot.serviceHost : "");
        out.addProperty("servicePort", snapshot.servicePort);
        out.addProperty("secure", snapshot.secure);
        if (snapshot.httpVersion != null) {
            out.addProperty("httpVersion", snapshot.httpVersion);
        }
        out.addProperty("pristine", snapshot.pristine);
        out.addProperty("binaryBody", snapshot.binaryBody);
        if (snapshot.sourceContext != null) {
            out.addProperty("sourceContext", snapshot.sourceContext);
        }
        if (snapshot.invalidationReason != null) {
            out.addProperty("invalidationReason", snapshot.invalidationReason);
        }
        if (snapshot.semanticFingerprint != null) {
            out.addProperty("semanticFingerprint", snapshot.semanticFingerprint);
        }
        return out;
    }

    private static JsonObject bodyToJson(ApiRequest.Body body, VariableResolver resolver, boolean resolve) {
        JsonObject out = new JsonObject();
        if (body == null) {
            return out;
        }
        if (body.mode != null) {
            out.addProperty("mode", body.mode);
        }
        if (body.raw != null) {
            out.addProperty("raw", CollectionExportSupport.resolve(body.raw, resolver, resolve));
        }
        if (body.contentType != null) {
            out.addProperty("contentType", CollectionExportSupport.resolve(body.contentType, resolver, resolve));
        }
        out.addProperty("required", body.required);
        if (body.description != null) out.addProperty("description", body.description);
        if (body.filePath != null) out.addProperty("filePath", CollectionExportSupport.resolve(body.filePath, resolver, resolve));
        if (body.source != null) out.addProperty("source", body.source);
        out.add("sourceMetadata", mapToJson(body.sourceMetadata, null, false));
        if (body.urlencoded != null && (!body.urlencoded.isEmpty() || "urlencoded".equalsIgnoreCase(body.mode))) {
            JsonArray arr = new JsonArray();
            for (ApiRequest.Body.FormField field : body.urlencoded) {
                if (field == null) {
                    continue;
                }
                JsonObject obj = new JsonObject();
                obj.addProperty("key", CollectionExportSupport.resolve(field.key, resolver, resolve) != null ? CollectionExportSupport.resolve(field.key, resolver, resolve) : "");
                obj.addProperty("value", CollectionExportSupport.resolve(field.value, resolver, resolve) != null ? CollectionExportSupport.resolve(field.value, resolver, resolve) : "");
                if (field.type != null) {
                    obj.addProperty("type", field.type);
                }
                obj.addProperty("fileUpload", field.fileUpload);
                if (field.filePath != null) {
                    obj.addProperty("filePath", CollectionExportSupport.resolve(field.filePath, resolver, resolve));
                }
                obj.addProperty("disabled", field.disabled);
                addFormFieldMetadata(obj, field);
                arr.add(obj);
            }
            out.add("urlencoded", arr);
        }
        if (body.formdata != null && (!body.formdata.isEmpty() || "formdata".equalsIgnoreCase(body.mode))) {
            JsonArray arr = new JsonArray();
            for (ApiRequest.Body.FormField field : body.formdata) {
                if (field == null) {
                    continue;
                }
                JsonObject obj = new JsonObject();
                obj.addProperty("key", CollectionExportSupport.resolve(field.key, resolver, resolve) != null ? CollectionExportSupport.resolve(field.key, resolver, resolve) : "");
                obj.addProperty("value", CollectionExportSupport.resolve(field.value, resolver, resolve) != null ? CollectionExportSupport.resolve(field.value, resolver, resolve) : "");
                if (field.type != null) {
                    obj.addProperty("type", field.type);
                }
                obj.addProperty("fileUpload", field.fileUpload);
                if (field.filePath != null) {
                    obj.addProperty("filePath", CollectionExportSupport.resolve(field.filePath, resolver, resolve));
                }
                obj.addProperty("disabled", field.disabled);
                addFormFieldMetadata(obj, field);
                arr.add(obj);
            }
            out.add("formdata", arr);
        }
        if (body.graphql != null) {
            JsonObject gql = new JsonObject();
            if (body.graphql.query != null) {
                gql.addProperty("query", CollectionExportSupport.resolve(body.graphql.query, resolver, resolve));
            }
            if (body.graphql.variables != null) {
                gql.addProperty("variables", CollectionExportSupport.resolve(body.graphql.variables, resolver, resolve));
            }
            out.add("graphql", gql);
        }
        return out;
    }

    private static void addFormFieldMetadata(JsonObject obj, ApiRequest.Body.FormField field) {
        obj.addProperty("required", field.required);
        if (field.description != null) obj.addProperty("description", field.description);
        if (field.contentType != null) obj.addProperty("contentType", field.contentType);
        if (field.style != null) obj.addProperty("style", field.style);
        if (field.explode != null) obj.addProperty("explode", field.explode);
        obj.addProperty("allowReserved", field.allowReserved);
        if (field.source != null) obj.addProperty("source", field.source);
        obj.add("sourceMetadata", mapToJson(field.sourceMetadata, null, false));
    }

    private static JsonArray headersToJson(List<ApiRequest.Header> headers, VariableResolver resolver, boolean resolve) {
        JsonArray out = new JsonArray();
        if (headers == null) {
            return out;
        }
        for (ApiRequest.Header header : headers) {
            if (header == null || header.key == null || header.key.isBlank()) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("key", CollectionExportSupport.resolve(header.key, resolver, resolve) != null ? CollectionExportSupport.resolve(header.key, resolver, resolve) : "");
            obj.addProperty("value", CollectionExportSupport.resolve(header.value, resolver, resolve) != null ? CollectionExportSupport.resolve(header.value, resolver, resolve) : "");
            obj.addProperty("disabled", header.disabled);
            out.add(obj);
        }
        return out;
    }

    private static JsonArray scriptsToJson(List<ApiRequest.Script> scripts, VariableResolver resolver, boolean resolve) {
        JsonArray out = new JsonArray();
        if (scripts == null) {
            return out;
        }
        for (ApiRequest.Script script : scripts) {
            if (script == null || script.exec == null) {
                continue;
            }
            JsonObject obj = new JsonObject();
            if (script.type != null) {
                obj.addProperty("type", CollectionExportSupport.resolve(script.type, resolver, resolve));
            }
            obj.addProperty("exec", CollectionExportSupport.resolve(script.exec, resolver, resolve));
            out.add(obj);
        }
        return out;
    }

    private static JsonArray scriptBlocksToJson(List<ScriptBlock> blocks) {
        JsonArray out = new JsonArray();
        if (blocks == null) {
            return out;
        }
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
        for (ScriptBlock block : blocks) {
            if (block == null) {
                continue;
            }
            out.add(gson.toJsonTree(block));
        }
        return out;
    }

    private static JsonObject folderScriptBlocksToJson(Map<String, List<ScriptBlock>> blocks) {
        JsonObject out = new JsonObject();
        if (blocks == null) {
            return out;
        }
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
        for (Map.Entry<String, List<ScriptBlock>> entry : blocks.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            JsonArray arr = new JsonArray();
            if (entry.getValue() != null) {
                for (ScriptBlock block : entry.getValue()) {
                    if (block != null) {
                        arr.add(gson.toJsonTree(block));
                    }
                }
            }
            out.add(entry.getKey(), arr);
        }
        return out;
    }

    private static JsonArray toStringArray(java.util.Set<String> values, VariableResolver resolver, boolean resolve) {
        JsonArray out = new JsonArray();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String resolved = CollectionExportSupport.resolve(value, resolver, resolve);
            if (resolved != null && !resolved.isBlank()) {
                out.add(resolved.trim());
            }
        }
        return out;
    }

    private static JsonObject mapToJson(Map<String, String> map, VariableResolver resolver, boolean resolve) {
        JsonObject out = new JsonObject();
        if (map == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            out.addProperty(entry.getKey(), CollectionExportSupport.resolve(entry.getValue(), resolver, resolve) != null
                    ? CollectionExportSupport.resolve(entry.getValue(), resolver, resolve)
                    : "");
        }
        return out;
    }

    private static JsonObject nestedStringMapToJson(Map<String, Map<String, String>> map, VariableResolver resolver, boolean resolve) {
        JsonObject out = new JsonObject();
        if (map == null) {
            return out;
        }
        for (Map.Entry<String, Map<String, String>> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            JsonObject nested = new JsonObject();
            if (entry.getValue() != null) {
                for (Map.Entry<String, String> nestedEntry : entry.getValue().entrySet()) {
                    if (nestedEntry.getKey() == null || nestedEntry.getKey().isBlank()) {
                        continue;
                    }
                    nested.addProperty(nestedEntry.getKey(), CollectionExportSupport.resolve(nestedEntry.getValue(), resolver, resolve) != null
                            ? CollectionExportSupport.resolve(nestedEntry.getValue(), resolver, resolve)
                            : "");
                }
            }
            out.add(entry.getKey(), nested);
        }
        return out;
    }

    private static JsonObject authMapToJson(Map<String, ApiRequest.Auth> map, VariableResolver resolver, boolean resolve) {
        JsonObject out = new JsonObject();
        if (map == null) {
            return out;
        }
        for (Map.Entry<String, ApiRequest.Auth> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            out.add(entry.getKey(), CollectionExportSupport.authToJson(entry.getValue(), resolver, resolve));
        }
        return out;
    }

    private static String resolveIfNeeded(String value, VariableResolver resolver, boolean resolve) {
        return CollectionExportSupport.resolve(value, resolver, resolve);
    }
}
