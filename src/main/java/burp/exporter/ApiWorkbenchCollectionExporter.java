package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.scripts.ScriptBlock;
import burp.parser.VariableResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
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
        root.addProperty("schemaVersion", 1);

        JsonObject col = new JsonObject();
        if (collection != null) {
            col.addProperty("id", collection.ensureId());
            VariableResolver collectionResolver = CollectionExportSupport.buildResolver(collection, null, activeEnvironment, exportOnly);
            col.addProperty("name", CollectionExportSupport.resolve(collection.name, collectionResolver, resolve) != null ? CollectionExportSupport.resolve(collection.name, collectionResolver, resolve) : "");
            if (collection.description != null) {
                col.addProperty("description", CollectionExportSupport.resolve(collection.description, collectionResolver, resolve));
            }
            if (collection.format != null) {
                col.addProperty("format", collection.format);
            }
            if (collection.version != null) {
                col.addProperty("version", collection.version);
            }

            JsonArray folderPaths = new JsonArray();
            if (collection.folderPaths != null) {
                for (String folderPath : collection.folderPaths) {
                    if (folderPath == null || folderPath.isBlank()) {
                        continue;
                    }
                    folderPaths.add(resolveIfNeeded(folderPath, collectionResolver, resolve));
                }
            }
            col.add("folderPaths", folderPaths);

            col.add("variables", CollectionExportSupport.toVariablesArray(collection.variables, collectionResolver, resolve));
            col.add("environment", mapToJson(collection.environment, collectionResolver, resolve));
            col.add("auth", CollectionExportSupport.authToJson(collection.auth, collectionResolver, resolve));
            col.add("folderAuthModes", mapToJson(collection.folderAuthModes, null, false));
            col.add("folderAuth", authMapToJson(collection.folderAuth, collectionResolver, resolve));
            col.add("folderVars", nestedStringMapToJson(collection.folderVars, collectionResolver, resolve));
            col.add("scriptBlocks", scriptBlocksToJson(collection.scriptBlocks));
            col.add("folderScriptBlocks", folderScriptBlocksToJson(collection.folderScriptBlocks));

            JsonArray requests = new JsonArray();
            if (collection.requests != null) {
                for (ApiRequest request : collection.requests) {
                    if (request == null) {
                        continue;
                    }
                    VariableResolver resolver = CollectionExportSupport.buildResolver(collection, request, activeEnvironment, exportOnly);
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

    private static JsonObject requestToJson(ApiRequest request, VariableResolver resolver, boolean resolve) {
        JsonObject out = new JsonObject();
        out.addProperty("id", request.id != null ? request.id : "");
        out.addProperty("name", CollectionExportSupport.resolve(request.name, resolver, resolve) != null ? CollectionExportSupport.resolve(request.name, resolver, resolve) : "");
        out.addProperty("path", CollectionExportSupport.resolve(request.path, resolver, resolve) != null ? CollectionExportSupport.resolve(request.path, resolver, resolve) : "");
        out.addProperty("sourceCollection", request.sourceCollection != null ? request.sourceCollection : "");
        out.addProperty("method", CollectionExportSupport.resolve(request.method, resolver, resolve) != null ? CollectionExportSupport.resolve(request.method, resolver, resolve) : "GET");
        out.addProperty("url", CollectionExportSupport.resolve(request.url, resolver, resolve) != null ? CollectionExportSupport.resolve(request.url, resolver, resolve) : "");
        if (request.description != null) {
            out.addProperty("description", CollectionExportSupport.resolve(request.description, resolver, resolve));
        }
        out.addProperty("editorMaterialized", request.editorMaterialized);
        if (request.buildMode != null) {
            out.addProperty("buildMode", request.buildMode.name());
        }
        out.add("suppressedAutoHeaders", toStringArray(request.suppressedAutoHeaders, resolver, resolve));
        out.add("headers", headersToJson(request.headers, resolver, resolve));
        out.add("body", bodyToJson(request.body, resolver, resolve));
        out.add("auth", CollectionExportSupport.authToJson(request.auth, resolver, resolve));
        out.add("explicitAuth", CollectionExportSupport.authToJson(request.explicitAuth, resolver, resolve));
        out.addProperty("authInherited", request.authInherited);
        out.addProperty("authExplicitlyDisabled", request.authExplicitlyDisabled);
        if (request.authSource != null) {
            out.addProperty("authSource", CollectionExportSupport.resolve(request.authSource, resolver, resolve));
        }
        if (request.authOverrideMode != null) {
            out.addProperty("authOverrideMode", request.authOverrideMode);
        }
        out.add("variables", CollectionExportSupport.toVariablesArray(request.variables, resolver, resolve));
        out.add("preRequestScripts", scriptsToJson(request.preRequestScripts, resolver, resolve));
        out.add("postResponseScripts", scriptsToJson(request.postResponseScripts, resolver, resolve));
        out.add("scriptBlocks", scriptBlocksToJson(request.scriptBlocks));
        out.addProperty("disabled", request.disabled);
        out.addProperty("sequenceOrder", request.sequenceOrder);
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
        if (body.urlencoded != null) {
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
                arr.add(obj);
            }
            out.add("urlencoded", arr);
        }
        if (body.formdata != null) {
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

    private static JsonArray headersToJson(List<ApiRequest.Header> headers, VariableResolver resolver, boolean resolve) {
        JsonArray out = new JsonArray();
        if (headers == null) {
            return out;
        }
        for (ApiRequest.Header header : headers) {
            if (header == null || header.key == null || header.key.isBlank()) {
                continue;
            }
            if (CollectionExportSupport.isTransportHeader(header.key)) {
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
            out.add(CollectionExportSupport.resolve(value, resolver, resolve));
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
