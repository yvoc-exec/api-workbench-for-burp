package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;
import burp.parser.OpenApiValueSupport;
import burp.utils.HttpUtils;
import burp.utils.OpenApiMetadataSupport;
import burp.utils.RequestParameterSupport;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class OpenApiCollectionExporter {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private OpenApiCollectionExporter() {
    }

    public static Map<String, Object> build(ApiCollection collection, CollectionExportOptions options, List<String> warnings) {
        boolean resolve = options != null && options.resolveVariablesUsingActiveEnvironment;
        EnvironmentProfile activeEnvironment = options != null ? options.activeEnvironment : null;
        Map<String, String> exportOnly = options != null ? options.exportOnlyVariables : Map.of();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("openapi", "3.0.3");
        if (collection != null) mergeExtensions(root, OpenApiMetadataSupport.parseObject(
                collection.sourceMetadata != null ? collection.sourceMetadata.get(OpenApiMetadataSupport.DOCUMENT_EXTENSIONS) : null));
        warnUnsupported(warnings, collection != null ? collection.sourceMetadata : null, "openapi.document.unsupported", "document");

        Map<String, Object> info = collection != null ? OpenApiMetadataSupport.parseObject(
                metadata(collection.sourceMetadata, OpenApiMetadataSupport.DOCUMENT_INFO)) : new LinkedHashMap<>();
        VariableResolver rootResolver = CollectionExportSupport.buildResolver(collection, null, activeEnvironment, exportOnly);
        info.put("title", collection != null && collection.name != null ? CollectionExportSupport.resolve(collection.name, rootResolver, resolve) : "API Workbench Collection");
        info.put("version", collection != null && collection.version != null && !collection.version.isBlank() ? collection.version : "1.0.0");
        if (collection != null && collection.description != null && !collection.description.isBlank()) {
            info.put("description", CollectionExportSupport.resolve(collection.description, rootResolver, resolve));
        }
        root.put("info", info);
        if (collection != null) {
            Object tags = OpenApiMetadataSupport.parseCanonicalJson(
                    metadata(collection.sourceMetadata, OpenApiMetadataSupport.DOCUMENT_TAGS));
            if (tags instanceof List<?>) root.put("tags", tags);
            Object externalDocs = OpenApiMetadataSupport.parseCanonicalJson(
                    metadata(collection.sourceMetadata, OpenApiMetadataSupport.DOCUMENT_EXTERNAL_DOCS));
            if (externalDocs instanceof Map<?, ?>) root.put("externalDocs", externalDocs);
            warnStandardStructures(warnings, collection.sourceMetadata,
                    OpenApiMetadataSupport.DOCUMENT_SWAGGER_STRUCTURES, "document");
        }

        List<Object> retainedServers = collection != null && collection.sourceMetadata != null
                ? OpenApiMetadataSupport.parseArray(collection.sourceMetadata.get(OpenApiMetadataSupport.DOCUMENT_SERVERS))
                : List.of();
        ParsedUrl commonOrigin = commonOrigin(collection, activeEnvironment, exportOnly, resolve);
        if (!retainedServers.isEmpty()) {
            root.put("servers", retainedServers);
        } else if (commonOrigin != null && commonOrigin.origin != null && !commonOrigin.origin.isBlank()) {
            List<Map<String, Object>> servers = new ArrayList<>();
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("url", commonOrigin.origin);
            servers.add(server);
            root.put("servers", servers);
        }

        Map<String, Object> components = collection != null ? OpenApiMetadataSupport.parseObject(
                metadata(collection.sourceMetadata, OpenApiMetadataSupport.DOCUMENT_COMPONENTS)) : new LinkedHashMap<>();
        components = portableSchemaMap(components, warnings, "retained component field");
        Map<String, Object> securitySchemes = components.get("securitySchemes") instanceof Map<?, ?> retainedSecurity
                ? castMap(retainedSecurity) : new LinkedHashMap<>();
        List<Map<String, Object>> globalSecurity = new ArrayList<>();
        if (collection != null && collection.auth != null) {
            String schemeName = "collectionAuth";
            Map<String, Object> scheme = CollectionExportSupport.authToOpenApiScheme(collection.auth, rootResolver, resolve) != null
                    ? gsonToMap(CollectionExportSupport.authToOpenApiScheme(collection.auth, rootResolver, resolve))
                    : null;
            if (scheme != null) {
                securitySchemes.put(schemeName, scheme);
                Map<String, Object> req = new LinkedHashMap<>();
                req.put(schemeName, List.of());
                globalSecurity.add(req);
            }
        }

        Map<String, Object> paths = new LinkedHashMap<>();
        List<PathServerRecord> pathServerRecords = new ArrayList<>();
        if (collection != null && collection.requests != null) {
            int index = 0;
            for (ApiRequest request : collection.requests) {
                if (request == null) {
                    continue;
                }
                VariableResolver resolver = CollectionExportSupport.buildResolver(collection, request, activeEnvironment, exportOnly);
                String exportUrl = RequestParameterSupport.materializePostmanRawUrl(
                        request.url, request.parameters, resolve ? resolver : null);
                ParsedUrl parsed = parseUrl(exportUrl, warnings, request.name);
                String retainedPathTemplate = metadata(request.sourceMetadata, OpenApiMetadataSupport.PATH_TEMPLATE);
                String path = request.sourceMetadata != null
                        && request.sourceMetadata.containsKey(OpenApiMetadataSupport.EFFECTIVE_SERVER_LEVEL)
                        && retainedPathTemplate != null && retainedPathTemplate.startsWith("/")
                        ? convertPathTemplate(retainedPathTemplate) : parsed.path;
                Map<String, Object> pathItem = (Map<String, Object>) paths.computeIfAbsent(path, key -> new LinkedHashMap<>());
                mergeExtensions(pathItem, OpenApiMetadataSupport.parseObject(
                        metadata(request.sourceMetadata, OpenApiMetadataSupport.PATH_ITEM_EXTENSIONS)));
                Map<String, Object> pathStructures = OpenApiMetadataSupport.parseObject(
                        metadata(request.sourceMetadata, OpenApiMetadataSupport.PATH_ITEM_STRUCTURES));
                for (Map.Entry<String, Object> structure : pathStructures.entrySet()) {
                    if (Set.of("summary", "description").contains(structure.getKey())) {
                        pathItem.putIfAbsent(structure.getKey(), structure.getValue());
                    }
                }
                String method = request.method != null ? request.method.toLowerCase(Locale.ROOT) : "get";
                Map<String, Object> operation = new LinkedHashMap<>();
                warnUnsupported(warnings, request.sourceMetadata, "openapi.operation.unsupported", "operation");
                mergeExtensions(operation, OpenApiMetadataSupport.parseObject(
                        request.sourceMetadata != null ? request.sourceMetadata.get(OpenApiMetadataSupport.OPERATION_EXTENSIONS) : null));
                if (request.sourceMetadata != null && request.sourceMetadata.containsKey(OpenApiMetadataSupport.OPERATION_CALLBACKS)) {
                    Map<String, Object> callbacks = OpenApiMetadataSupport.parseObject(
                            request.sourceMetadata.get(OpenApiMetadataSupport.OPERATION_CALLBACKS));
                    if (!callbacks.isEmpty()) operation.put("callbacks", callbacks);
                }
                putRetainedOperationFields(operation, request.sourceMetadata);
                warnStandardStructures(warnings, request.sourceMetadata,
                        OpenApiMetadataSupport.OPERATION_SWAGGER_STRUCTURES, "operation");
                String operationId = slugOperationId(request.name, method, path, index++);
                operation.put("operationId", operationId);
                if (request.name != null) {
                    operation.put("summary", CollectionExportSupport.resolve(request.name, resolver, resolve));
                }
                if (request.description != null && !request.description.isBlank()) {
                    operation.put("description", CollectionExportSupport.resolve(request.description, resolver, resolve));
                }
                List<Map<String, Object>> parameters = buildParameters(request, parsed, resolver, resolve, warnings);
                if (!parameters.isEmpty()) {
                    operation.put("parameters", parameters);
                }
                Map<String, Object> requestBody = buildRequestBody(request, resolver, resolve, warnings);
                if (requestBody != null) {
                    operation.put("requestBody", requestBody);
                }
                List<Map<String, Object>> security = buildSecurity(collection, request, resolver, resolve, securitySchemes);
                if (security != null) {
                    operation.put("security", security);
                }
                if (!operation.containsKey("responses")) {
                    Map<String, Object> responses = new LinkedHashMap<>();
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("description", "Default response");
                    responses.put("default", response);
                    operation.put("responses", responses);
                }
                List<Object> operationServers = OpenApiMetadataSupport.parseArray(
                        metadata(request.sourceMetadata, OpenApiMetadataSupport.OPERATION_SERVERS));
                if (!operationServers.isEmpty()) operation.put("servers", operationServers);
                List<Object> pathServers = OpenApiMetadataSupport.parseArray(
                        metadata(request.sourceMetadata, OpenApiMetadataSupport.PATH_ITEM_SERVERS));
                pathServerRecords.add(new PathServerRecord(path, operation, pathServers, !operationServers.isEmpty()));
                pathItem.put(method, operation);
            }
        }
        applyPathServers(paths, pathServerRecords, warnings);
        root.put("paths", paths);

        if (!securitySchemes.isEmpty()) {
            components.put("securitySchemes", securitySchemes);
        }
        if (!components.isEmpty()) {
            root.put("components", components);
        }
        if (!globalSecurity.isEmpty()) {
            root.put("security", globalSecurity);
        }
        return portableSchemaMap(root, warnings, "retained OpenAPI field");
    }

    public static void writeJson(ApiCollection collection, CollectionExportOptions options, Writer writer, List<String> warnings) throws IOException {
        writer.write(GSON.toJson(build(collection, options, warnings)));
    }

    public static void writeYaml(ApiCollection collection, CollectionExportOptions options, Writer writer, List<String> warnings) throws IOException {
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setPrettyFlow(true);
        dumperOptions.setIndent(2);
        Yaml yaml = new Yaml(dumperOptions);
        yaml.dump(build(collection, options, warnings), writer);
    }

    private static List<Map<String, Object>> buildParameters(ApiRequest request,
                                                             ParsedUrl parsed,
                                                             VariableResolver resolver,
                                                             boolean resolve,
                                                             List<String> warnings) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        if (request != null && request.parameters != null && !request.parameters.isEmpty()) {
            for (ApiRequest.Parameter parameter : request.parameters) {
                if (parameter == null || parameter.key == null || parameter.key.isBlank()) continue;
                parameters.add(modeledParameter(parameter, resolver, resolve, warnings));
            }
            return parameters;
        }
        if (parsed != null && parsed.queryParameters != null) {
            parameters.addAll(parsed.queryParameters);
        }
        if (request != null && request.headers != null) {
            for (ApiRequest.Header header : request.headers) {
                if (header == null || header.key == null || header.key.isBlank()) {
                    continue;
                }
                String normalized = header.key.trim().toLowerCase(Locale.ROOT);
                if (CollectionExportSupport.isTransportHeader(normalized) || "authorization".equals(normalized) || "content-type".equals(normalized)) {
                    continue;
                }
                Map<String, Object> param = new LinkedHashMap<>();
                param.put("name", CollectionExportSupport.resolve(header.key, resolver, resolve));
                param.put("in", "header");
                param.put("required", false);
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "string");
                param.put("schema", schema);
                if (header.value != null) {
                    param.put("example", CollectionExportSupport.resolve(header.value, resolver, resolve));
                }
                parameters.add(param);
            }
        }
        if (parsed != null && parsed.pathParameters != null) {
            parameters.addAll(parsed.pathParameters);
        }
        return parameters;
    }

    private static Map<String, Object> modeledParameter(ApiRequest.Parameter parameter,
                                                        VariableResolver resolver,
                                                        boolean resolve,
                                                        List<String> warnings) {
        Map<String, Object> out = new LinkedHashMap<>();
        String name = CollectionExportSupport.resolve(parameter.key, resolver, resolve);
        String location = parameter.location == null || parameter.location.isBlank()
                ? "query" : parameter.location.trim().toLowerCase(Locale.ROOT);
        boolean supported = Set.of("query", "path", "header", "cookie").contains(location);
        out.put("name", name);
        out.put("in", location);
        if (parameter.description != null && !parameter.description.isBlank()) out.put("description", parameter.description);
        out.put("required", "path".equals(location) || parameter.required);
        if (parameter.style != null && !parameter.style.isBlank()) out.put("style", parameter.style);
        if (parameter.explode != null) out.put("explode", parameter.explode);
        if ("query".equals(location) && parameter.allowReserved) out.put("allowReserved", true);
        if (parameter.disabled || !supported) out.put("x-disabled", true);
        if (!supported) addWarning(warnings, "unsupported parameter location: " + safeName(location));
        warnUnsupported(warnings, parameter.sourceMetadata, OpenApiMetadataSupport.UNSUPPORTED, "parameter");

        Map<String, Object> retainedContent = OpenApiMetadataSupport.parseObject(
                metadata(parameter.sourceMetadata, OpenApiMetadataSupport.RESOLVED_CONTENT));
        if (retainedContent.isEmpty()) retainedContent = OpenApiMetadataSupport.parseObject(
                metadata(parameter.sourceMetadata, OpenApiMetadataSupport.CONTENT));
        retainedContent = portableContent(retainedContent, warnings, "parameter content");
        Object currentValue = typedParameterValue(parameter, resolver, resolve);
        if (!retainedContent.isEmpty()) {
            List<String> mediaTypes = OpenApiValueSupport.orderedMediaTypes(retainedContent);
            if (!mediaTypes.isEmpty()) {
                String selected = mediaTypes.get(0);
                Map<String, Object> media = retainedContent.get(selected) instanceof Map<?, ?> raw
                        ? castMap(raw) : new LinkedHashMap<>();
                Map<String, Object> mediaSchema = media.get("schema") instanceof Map<?, ?> rawSchema
                        ? castMap(rawSchema) : new LinkedHashMap<>();
                String modeledType = parameter.type != null && !parameter.type.isBlank() ? parameter.type : "string";
                if (!mediaSchema.containsKey("type")
                        || !modeledType.equals(OpenApiValueSupport.effectiveType(mediaSchema))) {
                    mediaSchema.put("type", modeledType);
                }
                if (parameter.format != null && !parameter.format.isBlank()) mediaSchema.put("format", parameter.format);
                media.put("schema", mediaSchema);
                if (parameter.valuePresent) media.put("example", currentValue);
                retainedContent.put(selected, media);
            }
            out.put("content", retainedContent);
        } else {
            Map<String, Object> schema = OpenApiMetadataSupport.parseObject(
                    metadata(parameter.sourceMetadata, OpenApiMetadataSupport.RESOLVED_SCHEMA));
            if (schema.isEmpty()) schema = OpenApiMetadataSupport.parseObject(
                    metadata(parameter.sourceMetadata, OpenApiMetadataSupport.SCHEMA));
            schema = portableSchemaMap(schema, warnings, "parameter schema");
            if (schema.isEmpty()) schema = new LinkedHashMap<>();
            schema.remove("$ref");
            String modeledType = parameter.type != null && !parameter.type.isBlank() ? parameter.type : "string";
            if (!schema.containsKey("type") || !modeledType.equals(OpenApiValueSupport.effectiveType(schema))) {
                schema.put("type", modeledType);
            }
            if (parameter.format != null && !parameter.format.isBlank()) schema.put("format", parameter.format);
            mergeExtensions(schema, OpenApiMetadataSupport.parseObject(
                    metadata(parameter.sourceMetadata, OpenApiMetadataSupport.SCHEMA_EXTENSIONS)));
            out.put("schema", schema);
            if (parameter.valuePresent) out.put("example", currentValue);
        }
        mergeExtensions(out, OpenApiMetadataSupport.parseObject(
                metadata(parameter.sourceMetadata, OpenApiMetadataSupport.EXTENSIONS)));
        if (parameter.disabled || !supported) out.put("x-disabled", true);
        return out;
    }

    private static Map<String, Object> buildRequestBody(ApiRequest request,
                                                        VariableResolver resolver,
                                                        boolean resolve,
                                                        List<String> warnings) {
        if (request == null || request.body == null || request.body.mode == null || "none".equalsIgnoreCase(request.body.mode)) {
            return null;
        }
        Map<String, Object> requestBody = new LinkedHashMap<>();
        warnUnsupported(warnings, request.body.sourceMetadata, "openapi.requestBody.unsupported", "request body");
        requestBody.put("required", request.body.required);
        if (request.body.description != null && !request.body.description.isBlank()) requestBody.put("description", request.body.description);
        mergeExtensions(requestBody, OpenApiMetadataSupport.parseObject(
                metadata(request.body.sourceMetadata, OpenApiMetadataSupport.REQUEST_BODY_EXTENSIONS)));
        Map<String, Object> content = OpenApiMetadataSupport.parseObject(
                metadata(request.body.sourceMetadata, OpenApiMetadataSupport.REQUEST_BODY_RESOLVED_CONTENT));
        if (content.isEmpty()) content = OpenApiMetadataSupport.parseObject(
                metadata(request.body.sourceMetadata, OpenApiMetadataSupport.REQUEST_BODY_CONTENT));
        content = portableContent(content, warnings, "request body content");
        String mode = request.body.mode.toLowerCase(Locale.ROOT);
        String selectedMedia = metadata(request.body.sourceMetadata, OpenApiMetadataSupport.REQUEST_BODY_SELECTED_MEDIA_TYPE);
        if (selectedMedia == null || selectedMedia.isBlank()) {
            selectedMedia = request.body.contentType != null && !request.body.contentType.isBlank()
                    ? request.body.contentType
                    : switch (mode) {
                        case "urlencoded" -> "application/x-www-form-urlencoded";
                        case "formdata" -> "multipart/form-data";
                        case "file" -> "application/octet-stream";
                        case "graphql" -> "application/json";
                        default -> "text/plain";
                    };
        }
        Map<String, Object> media = content.get(selectedMedia) instanceof Map<?, ?> raw
                ? castMap(raw) : new LinkedHashMap<>();
        mergeExtensions(media, OpenApiMetadataSupport.parseObject(
                metadata(request.body.sourceMetadata, OpenApiMetadataSupport.MEDIA_EXTENSIONS)));
        if ("urlencoded".equals(mode)) {
            media.put("schema", formSchema(request.body.urlencoded, resolver, resolve, warnings));
            putEncoding(media, request.body.urlencoded);
            putDisabledProperties(media, request.body.urlencoded, warnings);
        } else if ("formdata".equals(mode)) {
            media.put("schema", formSchema(request.body.formdata, resolver, resolve, warnings));
            putEncoding(media, request.body.formdata);
            putDisabledProperties(media, request.body.formdata, warnings);
        } else if ("file".equals(mode)) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "string");
            schema.put("format", "binary");
            media.put("schema", schema);
            media.remove("example");
            addWarning(warnings, "binary body local file path intentionally omitted");
        } else if ("graphql".equals(mode)) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("query", stringSchema(CollectionExportSupport.resolve(request.body.graphql != null ? request.body.graphql.query : null, resolver, resolve)));
            props.put("variables", stringSchema(CollectionExportSupport.resolve(request.body.graphql != null ? request.body.graphql.variables : null, resolver, resolve)));
            schema.put("properties", props);
            schema.put("required", List.of("query"));
            media.put("schema", schema);
        } else {
            String raw = resolve ? CollectionExportSupport.resolve(request.body.raw, resolver, true) : request.body.raw;
            media.put("schema", schemaFromRaw(raw, selectedMedia));
            media.put("example", typedRawValue(raw, selectedMedia));
        }
        content.put(selectedMedia, media);
        requestBody.put("content", content);
        return requestBody;
    }

    private static List<Map<String, Object>> buildSecurity(ApiCollection collection,
                                                           ApiRequest request,
                                                           VariableResolver resolver,
                                                           boolean resolve,
                                                           Map<String, Object> securitySchemes) {
        if (request == null) {
            return null;
        }

        String source = request.authSource != null ? request.authSource : "";
        ApiRequest.Auth effectiveAuth = request.auth;
        if (effectiveAuth == null || effectiveAuth.type == null) {
            return null;
        }
        if ("none".equalsIgnoreCase(effectiveAuth.type)) {
            return List.of();
        }

        if (request.authOverrideMode != null && "inherit".equalsIgnoreCase(request.authOverrideMode) && request.authInherited
                && source.startsWith("collection:")) {
            return null; // collection security already applied globally
        }

        String schemeName;
        if (source.startsWith("folder:")) {
            schemeName = "folder_" + ExportIds.shortHash(source);
        } else if (request.authOverrideMode != null && "explicit".equalsIgnoreCase(request.authOverrideMode)) {
            schemeName = "request_" + ExportIds.shortHash(request.name + ":" + request.path + ":" + request.method);
        } else {
            schemeName = "request_" + ExportIds.shortHash(request.name + ":" + request.path + ":" + request.method);
        }
        if (!securitySchemes.containsKey(schemeName)) {
            Map<String, Object> scheme = CollectionExportSupport.authToOpenApiScheme(effectiveAuth, resolver, resolve) != null
                    ? gsonToMap(CollectionExportSupport.authToOpenApiScheme(effectiveAuth, resolver, resolve))
                    : null;
            if (scheme != null) {
                securitySchemes.put(schemeName, scheme);
            }
        }
        Map<String, Object> secReq = new LinkedHashMap<>();
        secReq.put(schemeName, List.of());
        return List.of(secReq);
    }

    private static Map<String, Object> formSchema(List<ApiRequest.Body.FormField> fields,
                                                  VariableResolver resolver,
                                                  boolean resolve,
                                                  List<String> warnings) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (fields != null) {
            for (ApiRequest.Body.FormField field : fields) {
                if (field == null || field.key == null || field.key.isBlank()) {
                    continue;
                }
                String key = CollectionExportSupport.resolve(field.key, resolver, resolve);
                Map<String, Object> property;
                if (field.fileUpload || "file".equalsIgnoreCase(field.type)) {
                    property = new LinkedHashMap<>();
                    property.put("type", "string");
                    property.put("format", "binary");
                } else {
                    property = OpenApiMetadataSupport.parseObject(metadata(field.sourceMetadata, OpenApiMetadataSupport.RESOLVED_SCHEMA));
                    if (property.isEmpty()) property = OpenApiMetadataSupport.parseObject(
                            metadata(field.sourceMetadata, OpenApiMetadataSupport.SCHEMA));
                    property = portableSchemaMap(property, warnings, "form property schema " + safeName(key));
                    if (property.isEmpty()) property = new LinkedHashMap<>();
                    String modeledType = field.type != null && !field.type.isBlank() ? field.type : "string";
                    if (!property.containsKey("type") || !modeledType.equals(OpenApiValueSupport.effectiveType(property))) {
                        property.put("type", modeledType);
                    }
                    property.put("example", typedFormValue(field, resolver, resolve));
                }
                if (field.description != null && !field.description.isBlank()) property.put("description", field.description);
                mergeExtensions(property, OpenApiMetadataSupport.parseObject(
                        metadata(field.sourceMetadata, OpenApiMetadataSupport.SCHEMA_EXTENSIONS)));
                properties.put(key, property);
                if (field.required) required.add(key);
            }
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static void putEncoding(Map<String, Object> media, List<ApiRequest.Body.FormField> fields) {
        Map<String, Object> encoding = new LinkedHashMap<>();
        if (fields != null) {
            for (ApiRequest.Body.FormField field : fields) {
                if (field == null || field.key == null || field.key.isBlank()) continue;
                Map<String, Object> item = OpenApiMetadataSupport.parseObject(
                        metadata(field.sourceMetadata, "openapi.encoding"));
                if (field.contentType != null && !field.contentType.isBlank()) item.put("contentType", field.contentType);
                if (field.style != null && !field.style.isBlank()) item.put("style", field.style);
                if (field.explode != null) item.put("explode", field.explode);
                if (field.allowReserved) item.put("allowReserved", true);
                retainSafeEncoding(item);
                if (!item.isEmpty()) encoding.put(field.key, item);
            }
        }
        if (!encoding.isEmpty()) media.put("encoding", encoding);
    }

    private static void retainSafeEncoding(Map<String, Object> encoding) {
        encoding.keySet().removeIf(key -> !Set.of("contentType", "style", "explode", "allowReserved", "headers").contains(key)
                && !key.toLowerCase(Locale.ROOT).startsWith("x-"));
        // Per-part headers are retained natively but intentionally not emitted by runtime/export.
        encoding.remove("headers");
    }

    private static void putDisabledProperties(Map<String, Object> media,
                                              List<ApiRequest.Body.FormField> fields,
                                              List<String> warnings) {
        List<String> disabled = new ArrayList<>();
        if (fields != null) for (ApiRequest.Body.FormField field : fields) {
            if (field != null && field.disabled && field.key != null && !field.key.isBlank()) disabled.add(field.key);
        }
        if (!disabled.isEmpty()) {
            media.put("x-api-workbench-disabled-properties", disabled);
            addWarning(warnings, "disabled form properties approximated: " + String.join(", ", disabled.stream().map(OpenApiCollectionExporter::safeName).toList()));
        }
    }

    private static Object typedParameterValue(ApiRequest.Parameter parameter, VariableResolver resolver, boolean resolve) {
        String value = resolve ? CollectionExportSupport.resolve(parameter.value, resolver, true) : parameter.value;
        if (value == null) return null;
        String valueSource = metadata(parameter.sourceMetadata, OpenApiMetadataSupport.VALUE_SOURCE);
        if ("null".equals(value) && valueSource != null) {
            Object original = OpenApiMetadataSupport.parseCanonicalJson(
                    metadata(parameter.sourceMetadata, OpenApiMetadataSupport.EXAMPLE));
            if (original == null && "null".equals(metadata(parameter.sourceMetadata, OpenApiMetadataSupport.EXAMPLE))) return null;
        }
        if ("array".equalsIgnoreCase(parameter.type) || "object".equalsIgnoreCase(parameter.type)) {
            Object parsed = OpenApiMetadataSupport.parseCanonicalJson(value);
            if (("array".equalsIgnoreCase(parameter.type) && parsed instanceof List<?>)
                    || ("object".equalsIgnoreCase(parameter.type) && parsed instanceof Map<?, ?>)) return parsed;
        }
        return scalarByType(value, parameter.type);
    }

    private static Map<String, Object> schemaForParameter(ApiRequest.Parameter parameter, Object value) {
        Map<String, Object> schema;
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            schema = jsonToSchema(value);
        } else {
            schema = new LinkedHashMap<>();
            schema.put("type", parameter.type != null && !parameter.type.isBlank() ? parameter.type : "string");
        }
        if (parameter.format != null && !parameter.format.isBlank()) schema.put("format", parameter.format);
        return schema;
    }

    private static Object typedFormValue(ApiRequest.Body.FormField field, VariableResolver resolver, boolean resolve) {
        String value = resolve ? CollectionExportSupport.resolve(field.value, resolver, true) : field.value;
        if (value == null) return null;
        if ("array".equalsIgnoreCase(field.type) || "object".equalsIgnoreCase(field.type)) {
            Object parsed = OpenApiMetadataSupport.parseCanonicalJson(value);
            if (parsed != null) return parsed;
        }
        return scalarByType(value, field.type);
    }

    private static Object scalarByType(String value, String type) {
        if (type == null) return value;
        try {
            return switch (type.toLowerCase(Locale.ROOT)) {
                case "integer" -> Long.parseLong(value);
                case "number" -> Double.parseDouble(value);
                case "boolean" -> Boolean.parseBoolean(value);
                case "null" -> null;
                default -> value;
            };
        } catch (RuntimeException e) {
            return value;
        }
    }

    private static Object typedRawValue(String raw, String contentType) {
        if (raw == null) return "";
        String lower = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";
        if (lower.equals("application/json") || (lower.startsWith("application/") && lower.endsWith("+json"))) {
            Object parsed = OpenApiMetadataSupport.parseCanonicalJson(raw);
            if (parsed != null || "null".equals(raw.trim())) return parsed;
        }
        return raw;
    }

    private static Map<String, Object> portableContent(Map<String, Object> content,
                                                       List<String> warnings,
                                                       String context) {
        Map<String, Object> portable = new LinkedHashMap<>();
        if (content == null) return portable;
        for (Map.Entry<String, Object> entry : content.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> rawMedia)) {
                portable.put(entry.getKey(), entry.getValue());
                continue;
            }
            Map<String, Object> media = castMap(rawMedia);
            if (media.containsKey("schema")) {
                Object schema = portableSchemaNode(media.get("schema"), warnings,
                        context + " schema " + safeName(entry.getKey()));
                if (schema instanceof Map<?, ?> || schema instanceof Boolean) media.put("schema", schema);
                else media.remove("schema");
            }
            portable.put(entry.getKey(), media);
        }
        return portable;
    }

    private static Map<String, Object> portableSchemaMap(Map<String, Object> schema,
                                                         List<String> warnings,
                                                         String context) {
        Object portable = portableSchemaNode(schema, warnings, context);
        return portable instanceof Map<?, ?> map ? castMap(map) : new LinkedHashMap<>();
    }

    private static Object portableSchemaNode(Object value,
                                             List<String> warnings,
                                             String context) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : castMap(raw).entrySet()) {
                if ("$ref".equals(entry.getKey())) {
                    addWarning(warnings, "unrepresentable reference omitted from " + context);
                    continue;
                }
                copy.put(entry.getKey(), portableSchemaNode(entry.getValue(), warnings,
                        context + " " + safeName(entry.getKey())));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(portableSchemaNode(item, warnings, context));
            return copy;
        }
        return value;
    }

    private static String metadata(Map<String, String> metadata, String key) {
        return metadata != null ? metadata.get(key) : null;
    }

    private static void putRetainedOperationFields(Map<String, Object> operation,
                                                   Map<String, String> metadata) {
        putRetained(operation, "tags", metadata, OpenApiMetadataSupport.OPERATION_TAGS, List.class);
        putRetained(operation, "externalDocs", metadata, OpenApiMetadataSupport.OPERATION_EXTERNAL_DOCS, Map.class);
        putRetained(operation, "deprecated", metadata, OpenApiMetadataSupport.OPERATION_DEPRECATED, Boolean.class);
        putRetained(operation, "responses", metadata, OpenApiMetadataSupport.OPERATION_RESPONSES, Map.class);
    }

    private static void putRetained(Map<String, Object> target,
                                    String field,
                                    Map<String, String> metadata,
                                    String metadataKey,
                                    Class<?> expected) {
        Object retained = OpenApiMetadataSupport.parseCanonicalJson(metadata(metadata, metadataKey));
        if (retained != null && expected.isInstance(retained)) target.put(field, retained);
    }

    private static void applyPathServers(Map<String, Object> paths,
                                         List<PathServerRecord> records,
                                         List<String> warnings) {
        Map<String, List<PathServerRecord>> byPath = new LinkedHashMap<>();
        for (PathServerRecord record : records) byPath.computeIfAbsent(record.path, ignored -> new ArrayList<>()).add(record);
        for (Map.Entry<String, List<PathServerRecord>> entry : byPath.entrySet()) {
            List<PathServerRecord> pathRecords = entry.getValue();
            String shared = null;
            boolean common = true;
            boolean any = false;
            for (PathServerRecord record : pathRecords) {
                String canonical = OpenApiMetadataSupport.canonicalJson(record.pathServers);
                if (!record.pathServers.isEmpty()) any = true;
                if (shared == null) shared = canonical;
                else if (!shared.equals(canonical)) common = false;
            }
            if (common && any && paths.get(entry.getKey()) instanceof Map<?, ?> rawPathItem) {
                @SuppressWarnings("unchecked") Map<String, Object> direct = (Map<String, Object>) rawPathItem;
                direct.put("servers", pathRecords.get(0).pathServers);
            } else if (any) {
                for (PathServerRecord record : pathRecords) {
                    if (!record.pathServers.isEmpty() && !record.explicitOperationServers) {
                        record.operation.put("servers", record.pathServers);
                    }
                }
                addWarning(warnings, "path-item servers approximated at operation level");
            }
        }
    }

    private static void warnStandardStructures(List<String> warnings,
                                               Map<String, String> metadata,
                                               String metadataKey,
                                               String context) {
        Map<String, Object> retained = OpenApiMetadataSupport.parseObject(metadata(metadata, metadataKey));
        if (!retained.isEmpty()) addWarning(warnings, "unsupported retained field omitted from " + context + ": "
                + String.join(", ", retained.keySet().stream().map(OpenApiCollectionExporter::safeName).toList()));
    }

    private static void mergeExtensions(Map<String, Object> target, Map<String, Object> extensions) {
        if (target == null || extensions == null) return;
        for (Map.Entry<String, Object> entry : extensions.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).startsWith("x-")) {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }

    private static void addWarning(List<String> warnings, String warning) {
        if (warnings == null || warning == null) return;
        String safe = warning.replaceAll("[\\r\\n\\u0000-\\u001f\\u007f]", " ").replaceAll("\\s+", " ").trim();
        if (safe.length() > 160) safe = safe.substring(0, 160);
        if (!safe.isBlank() && !warnings.contains(safe)) warnings.add(safe);
    }

    private static void warnUnsupported(List<String> warnings,
                                        Map<String, String> metadata,
                                        String key,
                                        String context) {
        Map<String, Object> retained = OpenApiMetadataSupport.parseObject(metadata(metadata, key));
        if (!retained.isEmpty()) addWarning(warnings, "unsupported retained field omitted from " + context + ": "
                + String.join(", ", retained.keySet().stream().map(OpenApiCollectionExporter::safeName).toList()));
    }

    private static String safeName(String value) {
        if (value == null) return "";
        String safe = value.replaceAll("[\\r\\n\\u0000-\\u001f\\u007f]", " ").trim();
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    private static Map<String, Object> schemaFromRaw(String raw, String contentType) {
        if (raw != null && raw.isBlank()) {
            raw = null;
        }
        String lower = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";
        if (lower.contains("json") && raw != null) {
            try {
                Object parsed = new com.google.gson.Gson().fromJson(raw, Object.class);
                return jsonToSchema(parsed);
            } catch (Exception ignored) {
                // fall through
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        if (raw != null) {
            schema.put("example", raw);
        }
        return schema;
    }

    private static Map<String, Object> jsonToSchema(Object parsed) {
        if (parsed instanceof Map<?, ?> map) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    props.put(String.valueOf(entry.getKey()), jsonToSchema(entry.getValue()));
                }
            }
            schema.put("properties", props);
            return schema;
        }
        if (parsed instanceof List<?> list) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "array");
            if (!list.isEmpty()) {
                schema.put("items", jsonToSchema(list.get(0)));
            }
            return schema;
        }
        if (parsed instanceof Boolean) {
            Map<String, Object> schema = stringSchema(String.valueOf(parsed));
            schema.put("type", "boolean");
            return schema;
        }
        if (parsed instanceof Number) {
            Map<String, Object> schema = stringSchema(String.valueOf(parsed));
            schema.put("type", parsed instanceof Integer || parsed instanceof Long ? "integer" : "number");
            return schema;
        }
        return stringSchema(parsed != null ? String.valueOf(parsed) : "");
    }

    private static Map<String, Object> stringSchema(String example) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        if (example != null) {
            schema.put("example", example);
        }
        return schema;
    }

    private static Map<String, Object> gsonToMap(com.google.gson.JsonObject obj) {
        return GSON.fromJson(obj, Map.class);
    }

    private static String slugOperationId(String name, String method, String path, int index) {
        String base = (name != null && !name.isBlank() ? name : method + " " + path) + "_" + index;
        return base.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("_+", "_");
    }

    private static ParsedUrl parseUrl(String rawUrl, List<String> warnings, String requestName) {
        ParsedUrl parsed = new ParsedUrl();
        if (rawUrl == null || rawUrl.isBlank()) {
            parsed.origin = null;
            parsed.path = "/" + slugOperationId(requestName, "get", "/", 0);
            return parsed;
        }
        try {
            HttpUtils.ParsedTarget target = HttpUtils.parseTargetForRequest(rawUrl);
            parsed.origin = originFromParsedTarget(rawUrl, target);
            String pathWithQuery = target.pathWithQuery;
            String query = null;
            int q = pathWithQuery != null ? pathWithQuery.indexOf('?') : -1;
            if (q >= 0) {
                query = pathWithQuery.substring(q + 1);
                pathWithQuery = pathWithQuery.substring(0, q);
            }
            parsed.path = convertPathTemplate(pathWithQuery);
            parsed.queryParameters = queryParams(query);
            parsed.pathParameters = pathParametersFromTemplate(parsed.path, requestName);
            return parsed;
        } catch (Exception ignored) {
            // best-effort fallback
        }
        if (rawUrl.startsWith("{{")) {
            int end = rawUrl.indexOf("}}");
            if (end > 1) {
                String serverVar = rawUrl.substring(0, end + 2);
                parsed.origin = serverVar;
                String rest = rawUrl.substring(end + 2);
                if (rest.isBlank()) {
                    parsed.path = "/";
                } else {
                    parsed.path = rest.startsWith("/") ? rest : "/" + rest;
                }
                parsed.path = convertPathTemplate(parsed.path);
                parsed.pathParameters = pathParametersFromTemplate(parsed.path, requestName);
                return parsed;
            }
        }
        int schemeIdx = rawUrl.indexOf("://");
        if (schemeIdx > 0) {
            int slash = rawUrl.indexOf('/', schemeIdx + 3);
            if (slash > 0) {
                parsed.origin = rawUrl.substring(0, slash);
                String rest = rawUrl.substring(slash);
                int q = rest.indexOf('?');
                if (q >= 0) {
                    parsed.path = convertPathTemplate(rest.substring(0, q));
                    parsed.queryParameters = queryParams(rest.substring(q + 1));
                } else {
                    parsed.path = convertPathTemplate(rest);
                }
                parsed.pathParameters = pathParametersFromTemplate(parsed.path, requestName);
                return parsed;
            }
        }
        parsed.origin = null;
        String pathOnly = rawUrl;
        String query = null;
        int q = rawUrl.indexOf('?');
        if (q >= 0) {
            pathOnly = rawUrl.substring(0, q);
            query = rawUrl.substring(q + 1);
        }
        parsed.path = pathOnly.startsWith("/") ? convertPathTemplate(pathOnly) : "/" + convertPathTemplate(pathOnly);
        parsed.queryParameters = queryParams(query);
        parsed.pathParameters = pathParametersFromTemplate(parsed.path, requestName);
        return parsed;
    }

    private static ParsedUrl commonOrigin(ApiCollection collection,
                                          EnvironmentProfile activeEnvironment,
                                          Map<String, String> exportOnly,
                                          boolean resolve) {
        if (collection == null || collection.requests == null || collection.requests.isEmpty()) {
            return null;
        }
        ParsedUrl common = null;
        for (ApiRequest request : collection.requests) {
            if (request == null || request.url == null || request.url.isBlank()) {
                continue;
            }
            VariableResolver resolver = CollectionExportSupport.buildResolver(collection, request, activeEnvironment, exportOnly);
            ParsedUrl parsed = parseUrl(RequestParameterSupport.materializePostmanRawUrl(
                    request.url, request.parameters, resolve ? resolver : null), null, request.name);
            if (parsed.origin == null || parsed.origin.isBlank()) {
                return null;
            }
            if (common == null) {
                common = parsed;
            } else if (!common.origin.equals(parsed.origin)) {
                return null;
            }
        }
        return common;
    }

    private static String originFromParsedTarget(String rawUrl, HttpUtils.ParsedTarget target) {
        if (rawUrl == null || rawUrl.isBlank() || target == null || target.host == null || target.host.isBlank()) {
            return null;
        }
        String scheme = target.useHttps ? "https" : "http";
        return scheme + "://" + target.host + (target.port == 80 || target.port == 443 ? "" : ":" + target.port);
    }

    private static String convertPathTemplate(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String converted = path.replaceAll("\\{\\{([^}|]+)(?:\\|[^}]+)?\\}\\}", "{$1}");
        if (!converted.startsWith("/")) {
            converted = "/" + converted;
        }
        return converted;
    }

    private static List<Map<String, Object>> queryParams(String query) {
        List<Map<String, Object>> params = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return params;
        }
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            Map<String, Object> param = new LinkedHashMap<>();
            param.put("name", convertPathTemplate(key).replace("/", ""));
            param.put("in", "query");
            param.put("required", false);
            param.put("schema", stringSchema(value));
            param.put("example", value);
            params.add(param);
        }
        return params;
    }

    private static List<Map<String, Object>> pathParametersFromTemplate(String path, String requestName) {
        List<Map<String, Object>> params = new ArrayList<>();
        if (path == null) {
            return params;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{([^/{}]+)\\}").matcher(path);
        Set<String> seen = new LinkedHashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name == null || name.isBlank() || !seen.add(name)) {
                continue;
            }
            Map<String, Object> param = new LinkedHashMap<>();
            param.put("name", name);
            param.put("in", "path");
            param.put("required", true);
            param.put("schema", stringSchema(requestName != null ? requestName : name));
            params.add(param);
        }
        return params;
    }

    private static final class ParsedUrl {
        String origin;
        String path;
        List<Map<String, Object>> queryParameters = List.of();
        List<Map<String, Object>> pathParameters = List.of();
    }

    private record PathServerRecord(String path,
                                    Map<String, Object> operation,
                                    List<Object> pathServers,
                                    boolean explicitOperationServers) {}
}
