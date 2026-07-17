package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.AuthInheritanceResolver;
import burp.utils.OpenApiMetadataSupport;
import burp.utils.RequestParameterSupport;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stateless, bounded OpenAPI 3.x and Swagger 2.0 importer. */
public class OpenApiParser implements CollectionParser {
    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "post", "put", "delete", "patch", "head", "options", "trace");
    private static final Set<String> STANDARD_OAS_HEADERS = Set.of(
            "accept", "content-type", "authorization");

    @Override
    public boolean canParse(File file) {
        try {
            Map<String, Object> root = new OpenApiReferenceResolver(file, new ArrayList<>()).loadRoot();
            return root.containsKey("openapi") || root.containsKey("swagger");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ApiCollection parse(File file) throws Exception {
        List<String> warnings = new ArrayList<>();
        OpenApiReferenceResolver references = new OpenApiReferenceResolver(file, warnings);
        Map<String, Object> spec = references.loadRoot();
        if (!spec.containsKey("openapi") && !spec.containsKey("swagger")) {
            throw new IllegalArgumentException("Document is not OpenAPI or Swagger");
        }
        boolean swagger = spec.containsKey("swagger");
        String sourceVersion = string(spec.get(swagger ? "swagger" : "openapi"), swagger ? "2.0" : "3.0");

        ApiCollection collection = new ApiCollection();
        collection.format = "openapi";
        collection.version = sourceVersion;
        collection.name = nestedString(spec, "info", "title", "OpenAPI Spec");
        collection.description = nestedString(spec, "info", "description", "");
        collection.importWarnings = warnings;
        collection.sourceMetadata.put(OpenApiMetadataSupport.SOURCE_VERSION, sourceVersion);
        putExtensions(collection.sourceMetadata, OpenApiMetadataSupport.DOCUMENT_EXTENSIONS, spec);
        retainUnsupported(collection.sourceMetadata, "openapi.document.unsupported", spec,
                Set.of("openapi", "swagger", "info", "servers", "host", "basePath", "schemes", "consumes",
                        "produces", "paths", "components", "definitions", "parameters", "responses",
                        "security", "securityDefinitions", "tags", "externalDocs"), warnings, "document");
        if (spec.get("servers") instanceof List<?> servers) {
            OpenApiMetadataSupport.putCanonical(collection.sourceMetadata, OpenApiMetadataSupport.DOCUMENT_SERVERS, servers);
        }
        retainStandard(collection.sourceMetadata, OpenApiMetadataSupport.DOCUMENT_TAGS, spec, "tags");
        retainStandard(collection.sourceMetadata, OpenApiMetadataSupport.DOCUMENT_INFO, spec, "info");
        retainStandard(collection.sourceMetadata, OpenApiMetadataSupport.DOCUMENT_EXTERNAL_DOCS, spec, "externalDocs");
        if (spec.get("components") instanceof Map<?, ?> components) {
            OpenApiMetadataSupport.putCanonical(collection.sourceMetadata,
                    OpenApiMetadataSupport.DOCUMENT_COMPONENTS, components);
        }
        Map<String, Object> swaggerStructures = selectedFields(spec,
                Set.of("consumes", "produces", "definitions", "parameters", "responses", "securityDefinitions"));
        if (!swaggerStructures.isEmpty()) OpenApiMetadataSupport.putCanonical(collection.sourceMetadata,
                OpenApiMetadataSupport.DOCUMENT_SWAGGER_STRUCTURES, swaggerStructures);

        Map<String, Map<String, Object>> securitySchemes = extractSecuritySchemes(spec, references, warnings);
        List<Map<String, Object>> defaultSecurity = listOfMaps(spec.get("security"));
        collection.auth = resolveSecurity(defaultSecurity, securitySchemes);
        if (collection.auth != null && "noauth".equalsIgnoreCase(collection.auth.type)) collection.auth.type = "none";

        String baseUrl = importServers(spec, collection, swagger);
        if (!(spec.get("paths") instanceof Map<?, ?> rawPaths)) {
            AuthInheritanceResolver.recomputeCollectionAuth(collection);
            return collection;
        }
        for (Map.Entry<String, Object> pathEntry : castMap(rawPaths).entrySet()) {
            if (!(pathEntry.getValue() instanceof Map<?, ?> rawPathItem)) continue;
            Map<String, Object> pathItem = castMap(rawPathItem);
            for (Map.Entry<String, Object> operationEntry : pathItem.entrySet()) {
                String method = operationEntry.getKey().toLowerCase(Locale.ROOT);
                if (!HTTP_METHODS.contains(method) || !(operationEntry.getValue() instanceof Map<?, ?> rawOperation)) continue;
                try {
                    ApiRequest request = parseOperation(
                            method.toUpperCase(Locale.ROOT), pathEntry.getKey(), pathItem,
                            castMap(rawOperation), baseUrl, spec, swagger, references,
                            securitySchemes, defaultSecurity, warnings);
                    request.sourceCollection = collection.name;
                    collection.requests.add(request);
                } catch (RuntimeException e) {
                    OpenApiWarningSupport.add(warnings, "unsupported retained field: operation import failed " + method.toUpperCase(Locale.ROOT));
                }
            }
        }
        AuthInheritanceResolver.recomputeCollectionAuth(collection);
        return collection;
    }

    private ApiRequest parseOperation(String method,
                                      String path,
                                      Map<String, Object> pathItem,
                                      Map<String, Object> operation,
                                      String baseUrl,
                                      Map<String, Object> spec,
                                      boolean swagger,
                                      OpenApiReferenceResolver references,
                                      Map<String, Map<String, Object>> securitySchemes,
                                      List<Map<String, Object>> defaultSecurity,
                                      List<String> warnings) {
        ApiRequest request = new ApiRequest();
        request.method = method;
        request.name = string(operation.get("operationId"), method + " " + path);
        request.path = path;
        request.sourceMetadata.put(OpenApiMetadataSupport.PATH_TEMPLATE, path);
        String summary = string(operation.get("summary"), "");
        String description = string(operation.get("description"), "");
        request.description = summary.isBlank() ? description : summary + (description.isBlank() ? "" : "\n" + description);
        ServerSelection server = effectiveServer(operation, pathItem, spec, swagger, baseUrl);
        request.url = joinBaseUrlAndPath(server.url, convertPathTemplate(path));
        if (("operation".equals(server.level) || "pathItem".equals(server.level)) && server.definition != null) {
            addRequestServerVariables(request, server.definition);
        }
        request.parameters.addAll(RequestParameterSupport.parseQueryParameters(request.url, "openapi:server"));
        putExtensions(request.sourceMetadata, OpenApiMetadataSupport.OPERATION_EXTENSIONS, operation);
        putExtensions(request.sourceMetadata, OpenApiMetadataSupport.PATH_ITEM_EXTENSIONS, pathItem);
        Map<String, Object> pathStructures = selectedFields(pathItem, Set.of("summary", "description", "$ref"));
        if (!pathStructures.isEmpty()) OpenApiMetadataSupport.putCanonical(request.sourceMetadata,
                OpenApiMetadataSupport.PATH_ITEM_STRUCTURES, pathStructures);
        if (pathItem.get("servers") instanceof List<?> servers) OpenApiMetadataSupport.putCanonical(
                request.sourceMetadata, OpenApiMetadataSupport.PATH_ITEM_SERVERS, servers);
        if (operation.get("servers") instanceof List<?> servers) OpenApiMetadataSupport.putCanonical(
                request.sourceMetadata, OpenApiMetadataSupport.OPERATION_SERVERS, servers);
        request.sourceMetadata.put(OpenApiMetadataSupport.EFFECTIVE_SERVER_LEVEL, server.level);
        retainStandard(request.sourceMetadata, OpenApiMetadataSupport.OPERATION_TAGS, operation, "tags");
        retainStandard(request.sourceMetadata, OpenApiMetadataSupport.OPERATION_EXTERNAL_DOCS, operation, "externalDocs");
        retainStandard(request.sourceMetadata, OpenApiMetadataSupport.OPERATION_DEPRECATED, operation, "deprecated");
        retainStandard(request.sourceMetadata, OpenApiMetadataSupport.OPERATION_RESPONSES, operation, "responses");
        Map<String, Object> operationSwagger = selectedFields(operation, Set.of("consumes", "produces"));
        if (!operationSwagger.isEmpty()) OpenApiMetadataSupport.putCanonical(request.sourceMetadata,
                OpenApiMetadataSupport.OPERATION_SWAGGER_STRUCTURES, operationSwagger);
        retainUnsupported(request.sourceMetadata, "openapi.pathItem.unsupported", pathItem,
                union(HTTP_METHODS, Set.of("parameters", "servers", "$ref", "summary", "description")),
                warnings, method + " path item");
        retainUnsupported(request.sourceMetadata, "openapi.operation.unsupported", operation,
                Set.of("tags", "summary", "description", "externalDocs", "operationId", "parameters",
                        "requestBody", "responses", "callbacks", "deprecated", "security", "servers", "consumes", "produces"),
                warnings, method + " operation");
        if (operation.containsKey("callbacks")) OpenApiMetadataSupport.putCanonical(
                request.sourceMetadata, OpenApiMetadataSupport.OPERATION_CALLBACKS, operation.get("callbacks"));

        List<ParameterDef> pathDefs = resolveParameters(pathItem.get("parameters"), true, swagger, references, warnings, method);
        List<ParameterDef> operationDefs = resolveParameters(operation.get("parameters"), false, swagger, references, warnings, method);
        List<String> unresolvedRefs = parameterRefs(pathItem.get("parameters"));
        unresolvedRefs.addAll(parameterRefs(operation.get("parameters")));
        for (ParameterDef def : pathDefs) if (def.ref != null) unresolvedRefs.remove(def.ref);
        for (ParameterDef def : operationDefs) if (def.ref != null) unresolvedRefs.remove(def.ref);
        if (!unresolvedRefs.isEmpty()) OpenApiMetadataSupport.putCanonical(
                request.sourceMetadata, "openapi.unresolvedParameterRefs", unresolvedRefs);
        List<ParameterDef> merged = mergeParameters(pathDefs, operationDefs, warnings, method);
        for (ParameterDef def : merged) {
            String location = sourceLocation(def.resolved);
            if (!Set.of("body", "formdata").contains(location)) {
                ApiRequest.Parameter converted = toParameter(def, swagger, references, warnings, method);
                int serverPosition = serverParameterPosition(request.parameters, converted);
                if (serverPosition >= 0) request.parameters.set(serverPosition, converted);
                else request.parameters.add(converted);
            }
        }

        if (swagger) {
            parseSwaggerBody(request, merged, operation, spec, references, warnings, method);
        } else {
            parseOasRequestBody(request, operation.get("requestBody"), references, warnings, method);
        }
        applySecurity(request, operation, securitySchemes, defaultSecurity);
        return request;
    }

    private List<ParameterDef> resolveParameters(Object raw,
                                                 boolean pathLevel,
                                                 boolean swagger,
                                                 OpenApiReferenceResolver references,
                                                 List<String> warnings,
                                                 String context) {
        List<ParameterDef> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return result;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> candidateRaw)) continue;
            Map<String, Object> candidate = castMap(candidateRaw);
            String ref = candidate.get("$ref") instanceof String text ? text : null;
            Map<String, Object> resolved = references.resolveObject(candidate, "parameter", context + " parameter");
            if (resolved == null) continue;
            String prefix = swagger ? "swagger2:" : "openapi:";
            String source = prefix + (ref != null ? "referenced-parameter" : pathLevel ? "path" : "operation");
            result.add(new ParameterDef(resolved, candidate, source, ref, pathLevel));
        }
        return result;
    }

    private List<ParameterDef> mergeParameters(List<ParameterDef> path,
                                               List<ParameterDef> operation,
                                               List<String> warnings,
                                               String context) {
        List<ParameterDef> merged = new ArrayList<>();
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (ParameterDef def : path) {
            String identity = identity(def.resolved);
            Integer existing = positions.get(identity);
            if (existing == null) {
                positions.put(identity, merged.size());
                merged.add(def);
            } else {
                merged.set(existing, def);
                OpenApiWarningSupport.add(warnings, "duplicate parameter identity: path-level " + safeIdentity(def.resolved));
            }
        }
        Map<String, ParameterDef> operationUnique = new LinkedHashMap<>();
        for (ParameterDef def : operation) {
            String identity = identity(def.resolved);
            if (operationUnique.containsKey(identity)) {
                OpenApiWarningSupport.add(warnings, "duplicate parameter identity: operation-level " + safeIdentity(def.resolved));
            }
            operationUnique.put(identity, def);
        }
        for (Map.Entry<String, ParameterDef> entry : operationUnique.entrySet()) {
            Integer existing = positions.get(entry.getKey());
            if (existing != null) {
                ParameterDef previous = merged.set(existing, entry.getValue());
                if (!Objects.equals(OpenApiMetadataSupport.canonicalJson(previous.resolved),
                        OpenApiMetadataSupport.canonicalJson(entry.getValue().resolved))) {
                    OpenApiWarningSupport.add(warnings, "operation parameter override: " + safeIdentity(entry.getValue().resolved));
                }
            } else {
                positions.put(entry.getKey(), merged.size());
                merged.add(entry.getValue());
            }
        }
        return merged;
    }

    private ApiRequest.Parameter toParameter(ParameterDef def,
                                             boolean swagger,
                                             OpenApiReferenceResolver references,
                                             List<String> warnings,
                                             String context) {
        Map<String, Object> source = def.resolved;
        String rawLocation = string(source.get("in"), "unknown");
        String location = rawLocation.isBlank() ? "unknown" : rawLocation.trim().toLowerCase(Locale.ROOT);
        ApiRequest.Parameter parameter = new ApiRequest.Parameter(location, string(source.get("name"), ""), "");
        parameter.source = def.source;
        parameter.disabled = bool(source.get("disabled")) || bool(source.get("x-disabled"));
        if (!Set.of("query", "path", "header", "cookie").contains(location)) {
            parameter.disabled = true;
            OpenApiWarningSupport.add(warnings, "unsupported parameter location: " + OpenApiWarningSupport.label(location));
        }
        Object originalRequired = source.get("required");
        if ("path".equals(location)) {
            parameter.required = true;
            if (!Boolean.TRUE.equals(originalRequired)) {
                OpenApiWarningSupport.add(warnings, "invalid path required state: " + OpenApiWarningSupport.label(parameter.key));
            }
        } else {
            parameter.required = bool(originalRequired);
        }
        parameter.description = nullableString(source.get("description"));
        parameter.allowReserved = "query".equals(location) && bool(source.get("allowReserved"));

        StyleDefaults defaults = styleDefaults(location);
        parameter.style = nullableString(source.get("style"));
        parameter.explode = source.get("explode") instanceof Boolean value ? value : null;
        if (swagger) applySwaggerCollectionFormat(parameter, source, warnings);
        if (parameter.style == null) parameter.style = defaults.style;
        if (parameter.explode == null) parameter.explode = defaults.explode;
        if (!supportedStyle(location, parameter.style)) {
            OpenApiWarningSupport.add(warnings, "unknown or incompatible style: " + OpenApiWarningSupport.label(parameter.style));
        }

        if (!swagger && "header".equals(location) && STANDARD_OAS_HEADERS.contains(parameter.key.toLowerCase(Locale.ROOT))) {
            parameter.disabled = true;
            OpenApiMetadataSupport.putCanonical(parameter.sourceMetadata, OpenApiMetadataSupport.IGNORED_STANDARD_HEADER, true);
            OpenApiWarningSupport.add(warnings, "ignored standard header parameter: " + OpenApiWarningSupport.label(parameter.key));
        }
        OpenApiValueSupport.SelectedValue selected = OpenApiValueSupport.selectParameterValue(source, references, warnings, context + " parameter");
        parameter.type = selected.type != null ? selected.type : nullableString(source.get("type"));
        parameter.format = selected.format != null ? selected.format : nullableString(source.get("format"));
        parameter.value = editableValue(selected.value);
        parameter.valuePresent = !"none".equals(selected.source);
        parameter.sourceMetadata.put(OpenApiMetadataSupport.VALUE_SOURCE, selected.source);

        if (def.ref != null) parameter.sourceMetadata.put(OpenApiMetadataSupport.REF, def.ref);
        if (source.containsKey("schema")) {
            OpenApiMetadataSupport.putCanonical(parameter.sourceMetadata, OpenApiMetadataSupport.SCHEMA, source.get("schema"));
            Object resolvedSchema = references.resolveSchemaTree(source.get("schema"), context + " parameter schema");
            if (resolvedSchema != null) OpenApiMetadataSupport.putCanonical(
                    parameter.sourceMetadata, OpenApiMetadataSupport.RESOLVED_SCHEMA, resolvedSchema);
        }
        if (source.containsKey("content")) {
            OpenApiMetadataSupport.putCanonical(parameter.sourceMetadata, OpenApiMetadataSupport.CONTENT, source.get("content"));
            if (source.get("content") instanceof Map<?, ?> rawContent) OpenApiMetadataSupport.putCanonical(
                    parameter.sourceMetadata, OpenApiMetadataSupport.RESOLVED_CONTENT,
                    resolvedContent(castMap(rawContent), references, context + " parameter content"));
        }
        if (source.containsKey("schema") && source.containsKey("content")) {
            OpenApiWarningSupport.add(warnings, "multiple parameter content types: schema and content; content selected");
        }
        if (source.containsKey("examples")) OpenApiMetadataSupport.putCanonical(parameter.sourceMetadata, OpenApiMetadataSupport.EXAMPLES, source.get("examples"));
        if (source.containsKey("example")) OpenApiMetadataSupport.putCanonical(parameter.sourceMetadata, OpenApiMetadataSupport.EXAMPLE, source.get("example"));
        if (source.containsKey("deprecated")) OpenApiMetadataSupport.putCanonical(parameter.sourceMetadata, OpenApiMetadataSupport.DEPRECATED, source.get("deprecated"));
        if (source.containsKey("allowEmptyValue")) OpenApiMetadataSupport.putCanonical(parameter.sourceMetadata, OpenApiMetadataSupport.ALLOW_EMPTY_VALUE, source.get("allowEmptyValue"));
        OpenApiMetadataSupport.putCanonical(parameter.sourceMetadata, OpenApiMetadataSupport.ORIGINAL_REQUIRED, originalRequired);
        OpenApiMetadataSupport.putCanonical(parameter.sourceMetadata, OpenApiMetadataSupport.EXPLICIT_REQUIRED, source.containsKey("required"));
        OpenApiMetadataSupport.putCanonical(parameter.sourceMetadata, OpenApiMetadataSupport.EXPLICIT_STYLE, source.containsKey("style"));
        OpenApiMetadataSupport.putCanonical(parameter.sourceMetadata, OpenApiMetadataSupport.EXPLICIT_EXPLODE, source.containsKey("explode"));
        putExtensions(parameter.sourceMetadata, OpenApiMetadataSupport.EXTENSIONS, source);
        retainUnsupported(parameter.sourceMetadata, OpenApiMetadataSupport.UNSUPPORTED, source,
                Set.of("name", "in", "description", "required", "deprecated", "allowEmptyValue", "style", "explode",
                        "allowReserved", "schema", "example", "examples", "content", "disabled", "type", "format",
                        "items", "default", "enum", "collectionFormat"), warnings, "parameter");
        return parameter;
    }

    private void parseOasRequestBody(ApiRequest request,
                                     Object candidate,
                                     OpenApiReferenceResolver references,
                                     List<String> warnings,
                                     String context) {
        if (!(candidate instanceof Map<?, ?> raw)) return;
        Map<String, Object> original = castMap(raw);
        String ref = original.get("$ref") instanceof String text ? text : null;
        Map<String, Object> bodyObject = references.resolveObject(original, "requestBody", context + " request body");
        if (bodyObject == null) {
            request.sourceMetadata.put("openapi.unresolvedRequestBodyRef", ref != null ? ref : "unresolved");
            return;
        }
        ApiRequest.Body body = new ApiRequest.Body();
        body.required = bool(bodyObject.get("required"));
        body.description = nullableString(bodyObject.get("description"));
        body.source = ref != null ? "openapi:referenced-requestBody" : "openapi:requestBody";
        if (ref != null) body.sourceMetadata.put(OpenApiMetadataSupport.REF, ref);
        putExtensions(body.sourceMetadata, OpenApiMetadataSupport.REQUEST_BODY_EXTENSIONS, bodyObject);
        retainUnsupported(body.sourceMetadata, "openapi.requestBody.unsupported", bodyObject,
                Set.of("description", "required", "content"), warnings, "request body");
        if (!(bodyObject.get("content") instanceof Map<?, ?> contentRaw) || contentRaw.isEmpty()) {
            body.mode = "raw";
            body.raw = "";
            OpenApiWarningSupport.add(warnings, "unsupported retained field: request body content");
            request.body = body;
            return;
        }
        populateBodyFromContent(body, castMap(contentRaw), references, warnings, context);
        request.body = body;
    }

    private void populateBodyFromContent(ApiRequest.Body body,
                                         Map<String, Object> content,
                                         OpenApiReferenceResolver references,
                                         List<String> warnings,
                                         String context) {
        OpenApiMetadataSupport.putCanonical(body.sourceMetadata, OpenApiMetadataSupport.REQUEST_BODY_CONTENT, content);
        Map<String, Object> portableContent = resolvedContent(content, references, context + " request body content");
        OpenApiMetadataSupport.putCanonical(body.sourceMetadata, OpenApiMetadataSupport.REQUEST_BODY_RESOLVED_CONTENT,
                portableContent);
        List<String> mediaTypes = OpenApiValueSupport.orderedMediaTypes(content);
        if (mediaTypes.isEmpty()) {
            body.mode = "raw";
            body.raw = "";
            return;
        }
        String mediaType = mediaTypes.get(0);
        body.contentType = mediaType;
        body.sourceMetadata.put(OpenApiMetadataSupport.REQUEST_BODY_SELECTED_MEDIA_TYPE, mediaType);
        if (mediaTypes.size() > 1) {
            OpenApiWarningSupport.add(warnings, "multiple request body content types: " + String.join(", ", mediaTypes));
        }
        Map<String, Object> media = content.get(mediaType) instanceof Map<?, ?> raw ? castMap(raw) : new LinkedHashMap<>();
        putExtensions(body.sourceMetadata, OpenApiMetadataSupport.MEDIA_EXTENSIONS, media);
        if (media.containsKey("encoding")) OpenApiMetadataSupport.putCanonical(
                body.sourceMetadata, OpenApiMetadataSupport.REQUEST_BODY_ENCODING, media.get("encoding"));
        Map<String, Object> portableMedia = portableContent.get(mediaType) instanceof Map<?, ?> rawPortable
                ? castMap(rawPortable) : new LinkedHashMap<>();
        Object originalSchema = media.get("schema");
        Object schema = portableMedia.get("schema");
        OpenApiValueSupport.SelectedValue selected = OpenApiValueSupport.selectMediaValue(media, references, warnings, context + " request body");
        String lower = mediaType.toLowerCase(Locale.ROOT);
        if ("application/x-www-form-urlencoded".equals(lower)) {
            body.mode = "urlencoded";
            body.urlencoded = formFields(originalSchema, schema, media, false, references, warnings, context);
        } else if ("multipart/form-data".equals(lower)) {
            body.mode = "formdata";
            body.formdata = formFields(originalSchema, schema, media, true, references, warnings, context);
        } else if (isWholeBodyBinary(mediaType, schema)) {
            body.mode = "file";
            body.filePath = "";
            body.raw = "";
            OpenApiWarningSupport.add(warnings, "binary body requires local file");
        } else if (isJsonMedia(mediaType)) {
            body.mode = "raw";
            body.raw = OpenApiMetadataSupport.canonicalJson(selected.value);
        } else {
            body.mode = "raw";
            if (selected.value instanceof Map<?, ?> || selected.value instanceof List<?>) {
                body.raw = OpenApiMetadataSupport.canonicalJson(selected.value);
                OpenApiWarningSupport.add(warnings, "schema branch approximation: structured non-JSON body");
            } else {
                body.raw = selected.value != null ? String.valueOf(selected.value) : "";
            }
        }
    }

    private List<ApiRequest.Body.FormField> formFields(Object originalSchema,
                                                      Object resolvedSchema,
                                                      Map<String, Object> media,
                                                      boolean multipart,
                                                      OpenApiReferenceResolver references,
                                                      List<String> warnings,
                                                      String context) {
        List<ApiRequest.Body.FormField> fields = new ArrayList<>();
        if (!(resolvedSchema instanceof Map<?, ?> rawSchema)) return fields;
        Map<String, Object> schemaMap = castMap(rawSchema);
        if (!(schemaMap.get("properties") instanceof Map<?, ?> rawProperties)) return fields;
        Map<String, Object> properties = castMap(rawProperties);
        Object shallowOriginalSchema = references.resolveSchemaNode(originalSchema, context + " form schema");
        Map<String, Object> originalProperties = shallowOriginalSchema instanceof Map<?, ?> originalMap
                && originalMap.get("properties") instanceof Map<?, ?> originalRawProperties
                ? castMap(originalRawProperties) : Map.of();
        Set<String> required = stringSet(schemaMap.get("required"));
        Set<String> disabledProperties = stringSet(media.get("x-api-workbench-disabled-properties"));
        Map<String, Object> encodings = media.get("encoding") instanceof Map<?, ?> raw ? castMap(raw) : Map.of();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            Object resolved = entry.getValue();
            Map<String, Object> property = resolved instanceof Map<?, ?> map ? castMap(map) : new LinkedHashMap<>();
            if (Boolean.TRUE.equals(property.get("readOnly"))) continue;
            Object value = OpenApiValueSupport.generateSchemaValue(resolved, references, warnings, context + " form field");
            ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(entry.getKey(), editableValue(value));
            field.type = OpenApiValueSupport.effectiveType(property);
            field.required = required.contains(entry.getKey());
            field.disabled = disabledProperties.contains(entry.getKey());
            field.description = nullableString(property.get("description"));
            field.source = "openapi:requestBody.property";
            Object originalProperty = originalProperties.getOrDefault(entry.getKey(), entry.getValue());
            OpenApiMetadataSupport.putCanonical(field.sourceMetadata, OpenApiMetadataSupport.SCHEMA, originalProperty);
            OpenApiMetadataSupport.putCanonical(field.sourceMetadata, OpenApiMetadataSupport.RESOLVED_SCHEMA, resolved);
            putExtensions(field.sourceMetadata, OpenApiMetadataSupport.SCHEMA_EXTENSIONS, property);
            if (encodings.get(entry.getKey()) instanceof Map<?, ?> rawEncoding) {
                Map<String, Object> encoding = castMap(rawEncoding);
                field.contentType = nullableString(encoding.get("contentType"));
                field.style = nullableString(encoding.get("style"));
                field.explode = encoding.get("explode") instanceof Boolean bool ? bool : null;
                field.allowReserved = bool(encoding.get("allowReserved"));
                OpenApiMetadataSupport.putCanonical(field.sourceMetadata, "openapi.encoding", encoding);
            }
            if (multipart && isFileSchema(property, null)) {
                field.type = "file";
                field.fileUpload = true;
                field.filePath = "";
                field.value = "";
                if ("array".equals(OpenApiValueSupport.effectiveType(property))) {
                    OpenApiWarningSupport.add(warnings, "multiple-file placeholder approximation: " + OpenApiWarningSupport.label(field.key));
                }
            }
            fields.add(field);
        }
        return fields;
    }

    private void parseSwaggerBody(ApiRequest request,
                                  List<ParameterDef> merged,
                                  Map<String, Object> operation,
                                  Map<String, Object> spec,
                                  OpenApiReferenceResolver references,
                                  List<String> warnings,
                                  String context) {
        List<ParameterDef> bodies = new ArrayList<>();
        List<ParameterDef> forms = new ArrayList<>();
        for (ParameterDef def : merged) {
            String in = sourceLocation(def.resolved);
            if ("body".equals(in)) bodies.add(def);
            if ("formdata".equals(in)) forms.add(def);
        }
        if (!bodies.isEmpty() && !forms.isEmpty()) OpenApiWarningSupport.add(warnings, "body/formData collision: body selected");
        List<String> consumes = stringList(operation.containsKey("consumes") ? operation.get("consumes") : spec.get("consumes"));
        if (consumes.isEmpty()) consumes = List.of("application/json");
        Map<String, Object> ranked = new LinkedHashMap<>();
        for (String media : consumes) ranked.put(media, Map.of());
        String selectedMedia = OpenApiValueSupport.orderedMediaTypes(ranked).get(0);
        if (!bodies.isEmpty()) {
            ParameterDef def = bodies.get(0);
            ApiRequest.Body body = new ApiRequest.Body();
            body.required = bool(def.resolved.get("required"));
            body.description = nullableString(def.resolved.get("description"));
            body.source = def.ref != null ? "swagger2:referenced-parameter" : "swagger2:body";
            Map<String, Object> media = new LinkedHashMap<>();
            media.put("schema", def.resolved.get("schema"));
            if (def.resolved.containsKey("x-example")) media.put("example", def.resolved.get("x-example"));
            Map<String, Object> content = new LinkedHashMap<>();
            content.put(selectedMedia, media);
            if (!forms.isEmpty()) OpenApiMetadataSupport.putCanonical(body.sourceMetadata, OpenApiMetadataSupport.RETAINED_FORM_DATA, formMaps(forms));
            populateBodyFromContent(body, content, references, warnings, context);
            request.body = body;
        } else if (!forms.isEmpty()) {
            boolean multipart = consumes.stream().anyMatch(value -> "multipart/form-data".equalsIgnoreCase(value));
            ApiRequest.Body body = new ApiRequest.Body();
            body.mode = multipart ? "formdata" : "urlencoded";
            body.contentType = multipart ? "multipart/form-data" : "application/x-www-form-urlencoded";
            body.source = "swagger2:formData";
            List<ApiRequest.Body.FormField> fields = new ArrayList<>();
            for (ParameterDef def : forms) {
                Map<String, Object> param = def.resolved;
                Object schema = swaggerParameterSchema(param);
                ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(
                        string(param.get("name"), ""), editableValue(OpenApiValueSupport.generateSchemaValue(schema, references, warnings, context)));
                field.type = nullableString(param.get("type"));
                field.required = bool(param.get("required"));
                field.description = nullableString(param.get("description"));
                field.source = def.source;
                applySwaggerFieldCollectionFormat(field, param, warnings);
                OpenApiMetadataSupport.putCanonical(field.sourceMetadata, OpenApiMetadataSupport.SCHEMA, schema);
                Object resolvedSchema = references.resolveSchemaTree(schema, context + " form field");
                if (resolvedSchema != null) OpenApiMetadataSupport.putCanonical(
                        field.sourceMetadata, OpenApiMetadataSupport.RESOLVED_SCHEMA, resolvedSchema);
                if (multipart && ("file".equalsIgnoreCase(field.type) || isFileSchema(castMap((Map<?, ?>) schema), null))) {
                    field.type = "file";
                    field.fileUpload = true;
                    field.filePath = "";
                    field.value = "";
                }
                fields.add(field);
            }
            if (multipart) body.formdata = fields; else body.urlencoded = fields;
            request.body = body;
        }
    }

    private void applySwaggerCollectionFormat(ApiRequest.Parameter parameter,
                                              Map<String, Object> source,
                                              List<String> warnings) {
        String format = nullableString(source.get("collectionFormat"));
        if (format == null) return;
        parameter.sourceMetadata.put(OpenApiMetadataSupport.SWAGGER_COLLECTION_FORMAT, format);
        switch (format.toLowerCase(Locale.ROOT)) {
            case "multi" -> { parameter.style = "form"; parameter.explode = true; }
            case "csv" -> { parameter.style = "form"; parameter.explode = false; }
            case "ssv" -> { parameter.style = "spaceDelimited"; parameter.explode = false; }
            case "pipes" -> { parameter.style = "pipeDelimited"; parameter.explode = false; }
            case "tsv" -> {
                parameter.style = "tabDelimited"; parameter.explode = false;
                OpenApiWarningSupport.add(warnings, "unsupported Swagger collectionFormat: tsv");
            }
            default -> OpenApiWarningSupport.add(warnings, "unsupported Swagger collectionFormat: " + OpenApiWarningSupport.label(format));
        }
    }

    private void applySwaggerFieldCollectionFormat(ApiRequest.Body.FormField field,
                                                   Map<String, Object> source,
                                                   List<String> warnings) {
        String format = nullableString(source.get("collectionFormat"));
        if (format == null) return;
        field.sourceMetadata.put(OpenApiMetadataSupport.SWAGGER_COLLECTION_FORMAT, format);
        switch (format.toLowerCase(Locale.ROOT)) {
            case "multi" -> { field.style = "form"; field.explode = true; }
            case "csv" -> { field.style = "form"; field.explode = false; }
            case "ssv" -> { field.style = "spaceDelimited"; field.explode = false; }
            case "pipes" -> { field.style = "pipeDelimited"; field.explode = false; }
            case "tsv" -> { field.style = "tabDelimited"; field.explode = false; OpenApiWarningSupport.add(warnings, "unsupported Swagger collectionFormat: tsv"); }
            default -> OpenApiWarningSupport.add(warnings, "unsupported Swagger collectionFormat: " + OpenApiWarningSupport.label(format));
        }
    }

    private Map<String, Map<String, Object>> extractSecuritySchemes(Map<String, Object> spec,
                                                                   OpenApiReferenceResolver references,
                                                                   List<String> warnings) {
        Object raw = null;
        if (spec.get("components") instanceof Map<?, ?> components) raw = components.get("securitySchemes");
        if (raw == null) raw = spec.get("securityDefinitions");
        Map<String, Map<String, Object>> schemes = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) return schemes;
        for (Map.Entry<String, Object> entry : castMap(map).entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> candidate) {
                Map<String, Object> resolved = references.resolveObject(castMap(candidate), "securityScheme", "security scheme");
                if (resolved != null) schemes.put(entry.getKey(), resolved);
            }
        }
        return schemes;
    }

    private void applySecurity(ApiRequest request,
                               Map<String, Object> operation,
                               Map<String, Map<String, Object>> schemes,
                               List<Map<String, Object>> defaults) {
        if (operation.containsKey("security")) {
            ApiRequest.Auth auth = resolveSecurity(listOfMaps(operation.get("security")), schemes);
            if (auth != null && "none".equalsIgnoreCase(AuthInheritanceResolver.normalizeParsedAuthMode(auth))) {
                AuthInheritanceResolver.markRequestNoAuth(request);
            } else {
                AuthInheritanceResolver.markRequestExplicitAuth(request, auth);
            }
        } else {
            AuthInheritanceResolver.markRequestInherit(request);
        }
    }

    private ApiRequest.Auth resolveSecurity(List<Map<String, Object>> security,
                                            Map<String, Map<String, Object>> schemes) {
        if (security == null) return null;
        if (security.isEmpty()) {
            ApiRequest.Auth auth = new ApiRequest.Auth(); auth.type = "none"; return auth;
        }
        Map<String, Object> first = security.get(0);
        if (first == null || first.isEmpty()) return null;
        return mapSecurityScheme(schemes.get(first.keySet().iterator().next()));
    }

    private ApiRequest.Auth mapSecurityScheme(Map<String, Object> scheme) {
        if (scheme == null) return null;
        ApiRequest.Auth auth = new ApiRequest.Auth();
        String type = string(scheme.get("type"), "");
        if ("http".equalsIgnoreCase(type)) {
            String value = string(scheme.get("scheme"), "").toLowerCase(Locale.ROOT);
            if ("bearer".equals(value)) { auth.type = "bearer"; auth.properties.put("token", "{{token}}"); }
            if ("basic".equals(value)) { auth.type = "basic"; auth.properties.put("username", "{{username}}"); auth.properties.put("password", "{{password}}"); }
        } else if ("basic".equalsIgnoreCase(type)) {
            auth.type = "basic"; auth.properties.put("username", "{{username}}"); auth.properties.put("password", "{{password}}");
        } else if ("apikey".equalsIgnoreCase(type)) {
            String in = string(scheme.get("in"), "header");
            String name = string(scheme.get("name"), "");
            if ("cookie".equalsIgnoreCase(in)) { auth.type = "cookie"; auth.properties.put("value", name + "={{api_key}}"); }
            else { auth.type = "apikey"; auth.properties.put("key", name); auth.properties.put("value", "{{api_key}}"); auth.properties.put("in", in); }
        } else if ("oauth2".equalsIgnoreCase(type)) {
            auth.type = "oauth2";
            auth.properties.put("clientId", "{{oauth2_client_id}}");
            auth.properties.put("clientSecret", "{{oauth2_client_secret}}");
            auth.properties.put("accessToken", "{{oauth2_access_token}}");
            if (scheme.get("flows") instanceof Map<?, ?> flows) extractOasFlows(auth, castMap(flows));
            else {
                auth.properties.put("grantType", mapSwaggerFlow(string(scheme.get("flow"), "")));
                putIfText(auth.properties, "accessTokenUrl", scheme.get("tokenUrl"));
                putIfText(auth.properties, "authorizationUrl", scheme.get("authorizationUrl"));
            }
        }
        return auth.type != null ? auth : null;
    }

    private void extractOasFlows(ApiRequest.Auth auth, Map<String, Object> flows) {
        for (String name : List.of("clientCredentials", "password", "authorizationCode", "implicit")) {
            if (!(flows.get(name) instanceof Map<?, ?> raw)) continue;
            Map<String, Object> flow = castMap(raw);
            auth.properties.put("grantType", switch (name) { case "clientCredentials" -> "client_credentials"; case "authorizationCode" -> "authorization_code"; default -> name; });
            putIfText(auth.properties, "accessTokenUrl", flow.get("tokenUrl"));
            putIfText(auth.properties, "authorizationUrl", flow.get("authorizationUrl"));
            putIfText(auth.properties, "refreshTokenUrl", flow.get("refreshUrl"));
            break;
        }
    }

    private String importServers(Map<String, Object> spec, ApiCollection collection, boolean swagger) {
        if (!swagger && spec.get("servers") instanceof List<?> servers) {
            for (Object item : servers) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                Map<String, Object> server = castMap(raw);
                if (server.get("variables") instanceof Map<?, ?> variables) {
                    for (Map.Entry<String, Object> entry : castMap(variables).entrySet()) {
                        if (entry.getValue() instanceof Map<?, ?> def && def.containsKey("default")) {
                            collection.environment.put(entry.getKey(), string(def.get("default"), ""));
                        }
                    }
                }
                String url = serverUrl(server);
                if (url != null) return url;
            }
        }
        if (swagger && (spec.get("host") != null || spec.get("basePath") != null || spec.get("schemes") != null)) {
            List<String> schemes = stringList(spec.get("schemes"));
            String scheme = schemes.isEmpty() ? "https" : schemes.get(0).toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https").contains(scheme)) scheme = "https";
            String url = scheme + "://" + string(spec.get("host"), "localhost") + string(spec.get("basePath"), "");
            OpenApiMetadataSupport.putCanonical(collection.sourceMetadata,
                    OpenApiMetadataSupport.DOCUMENT_SERVERS, List.of(Map.of("url", url)));
            return url;
        }
        return "http://localhost";
    }

    private ServerSelection effectiveServer(Map<String, Object> operation,
                                            Map<String, Object> pathItem,
                                            Map<String, Object> spec,
                                            boolean swagger,
                                            String rootBaseUrl) {
        if (!swagger) {
            ServerSelection operationServer = firstServer(operation.get("servers"), "operation");
            if (operationServer != null) return operationServer;
            ServerSelection pathServer = firstServer(pathItem.get("servers"), "pathItem");
            if (pathServer != null) return pathServer;
            ServerSelection rootServer = firstServer(spec.get("servers"), "root");
            if (rootServer != null) return rootServer;
        }
        return new ServerSelection(rootBaseUrl != null ? rootBaseUrl : "http://localhost",
                swagger ? "swagger" : "fallback", null);
    }

    private ServerSelection firstServer(Object rawServers, String level) {
        if (!(rawServers instanceof List<?> servers)) return null;
        for (Object item : servers) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> server = castMap(raw);
            String url = serverUrl(server);
            if (url != null && !url.isBlank()) return new ServerSelection(url, level, server);
        }
        return null;
    }

    private String serverUrl(Map<String, Object> server) {
        String url = nullableString(server != null ? server.get("url") : null);
        if (url == null) return null;
        if (server.get("variables") instanceof Map<?, ?> rawVariables) {
            for (String name : castMap(rawVariables).keySet()) {
                if (name != null && !name.isBlank()) url = url.replace("{" + name + "}", "{{" + name + "}}");
            }
        }
        return url;
    }

    private void addRequestServerVariables(ApiRequest request, Map<String, Object> server) {
        if (!(server.get("variables") instanceof Map<?, ?> rawVariables)) return;
        for (Map.Entry<String, Object> entry : castMap(rawVariables).entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) continue;
            ApiRequest.Variable variable = new ApiRequest.Variable();
            variable.key = entry.getKey();
            variable.type = "default";
            variable.enabled = true;
            variable.value = entry.getValue() instanceof Map<?, ?> definition && definition.containsKey("default")
                    ? string(definition.get("default"), "") : "{{" + entry.getKey() + "}}";
            request.variables.add(variable);
        }
    }

    private static String identity(Map<String, Object> parameter) {
        return string(parameter.get("name"), "") + "\u0000" + sourceLocation(parameter);
    }

    private static int serverParameterPosition(List<ApiRequest.Parameter> parameters, ApiRequest.Parameter candidate) {
        for (int i = 0; i < parameters.size(); i++) {
            ApiRequest.Parameter existing = parameters.get(i);
            if (existing != null && "openapi:server".equals(existing.source)
                    && Objects.equals(existing.key, candidate.key)
                    && RequestParameterSupport.normalizeLocation(existing.location)
                    .equals(RequestParameterSupport.normalizeLocation(candidate.location))) return i;
        }
        return -1;
    }

    private static String safeIdentity(Map<String, Object> parameter) {
        return OpenApiWarningSupport.label(string(parameter.get("name"), "")) + " in " + sourceLocation(parameter);
    }

    private static String sourceLocation(Map<String, Object> parameter) {
        String value = string(parameter.get("in"), "unknown").trim().toLowerCase(Locale.ROOT);
        return value.isBlank() ? "unknown" : value;
    }

    private static StyleDefaults styleDefaults(String location) {
        return switch (location) {
            case "path", "header" -> new StyleDefaults("simple", false);
            case "cookie", "query" -> new StyleDefaults("form", true);
            default -> new StyleDefaults(null, false);
        };
    }

    private static boolean supportedStyle(String location, String style) {
        if (style == null) return true;
        return switch (location) {
            case "query" -> Set.of("form", "spaceDelimited", "pipeDelimited", "deepObject").contains(style);
            case "path" -> Set.of("simple", "label", "matrix").contains(style);
            case "header" -> "simple".equals(style);
            case "cookie" -> "form".equals(style);
            default -> false;
        };
    }

    private static Object swaggerParameterSchema(Map<String, Object> parameter) {
        if (parameter.get("schema") != null) return parameter.get("schema");
        Map<String, Object> schema = new LinkedHashMap<>();
        for (String key : List.of("type", "format", "items", "default", "enum", "x-example")) {
            if (parameter.containsKey(key)) schema.put("x-example".equals(key) ? "example" : key, parameter.get(key));
        }
        return schema;
    }

    private static boolean isJsonMedia(String mediaType) {
        String lower = mediaType.toLowerCase(Locale.ROOT);
        return "application/json".equals(lower) || (lower.startsWith("application/") && lower.endsWith("+json"));
    }

    private static boolean isWholeBodyBinary(String mediaType, Object schema) {
        String lower = mediaType.toLowerCase(Locale.ROOT);
        if ("application/octet-stream".equals(lower)) return true;
        return schema instanceof Map<?, ?> raw && isFileSchema(castMap(raw), mediaType);
    }

    private static boolean isFileSchema(Map<String, Object> schema, String mediaType) {
        if (schema == null) return mediaType != null && "application/octet-stream".equalsIgnoreCase(mediaType);
        if ("file".equalsIgnoreCase(nullableString(schema.get("type")))) return true;
        String type = OpenApiValueSupport.effectiveType(schema);
        String format = OpenApiValueSupport.effectiveFormat(schema);
        if ("string".equals(type) && ("binary".equalsIgnoreCase(format) || "byte".equalsIgnoreCase(format)
                || schema.containsKey("contentEncoding") || schema.containsKey("contentMediaType"))) return true;
        if ("array".equals(type) && schema.get("items") instanceof Map<?, ?> items) return isFileSchema(castMap(items), mediaType);
        return schema.isEmpty() && mediaType != null && "application/octet-stream".equalsIgnoreCase(mediaType);
    }

    private static String editableValue(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof List<?>) return OpenApiMetadataSupport.canonicalJson(value);
        return String.valueOf(value);
    }

    private static String convertPathTemplate(String path) {
        return path == null ? "" : path.replaceAll("\\{([^/{}]+)}", "{{$1}}");
    }

    private static String joinBaseUrlAndPath(String base, String path) {
        if (base == null || base.isBlank()) return path == null ? "" : path;
        String normalizedPath = path == null ? "" : path;
        if (!normalizedPath.isEmpty() && !normalizedPath.startsWith("/")) normalizedPath = "/" + normalizedPath;
        int fragment = base.indexOf('#');
        String suffix = fragment >= 0 ? base.substring(fragment) : "";
        String head = fragment >= 0 ? base.substring(0, fragment) : base;
        int query = head.indexOf('?');
        if (query >= 0) { suffix = head.substring(query) + suffix; head = head.substring(0, query); }
        if (head.endsWith("/") && !normalizedPath.isEmpty()) head = head.substring(0, head.length() - 1);
        return head + normalizedPath + suffix;
    }

    private static void putExtensions(Map<String, String> metadata, String key, Map<String, Object> source) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) if (entry.getKey().toLowerCase(Locale.ROOT).startsWith("x-")) extensions.put(entry.getKey(), entry.getValue());
        if (!extensions.isEmpty()) OpenApiMetadataSupport.putCanonical(metadata, key, extensions);
    }

    private static void retainStandard(Map<String, String> metadata,
                                       String metadataKey,
                                       Map<String, Object> source,
                                       String field) {
        if (source != null && source.containsKey(field)) {
            OpenApiMetadataSupport.putCanonical(metadata, metadataKey, source.get(field));
        }
    }

    private static Map<String, Object> selectedFields(Map<String, Object> source, Set<String> fields) {
        Map<String, Object> selected = new LinkedHashMap<>();
        if (source == null || fields == null) return selected;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (fields.contains(entry.getKey())) selected.put(entry.getKey(), entry.getValue());
        }
        return selected;
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> union = new LinkedHashSet<>();
        if (left != null) union.addAll(left);
        if (right != null) union.addAll(right);
        return union;
    }

    private static void retainUnsupported(Map<String, String> metadata,
                                          String key,
                                          Map<String, Object> source,
                                          Set<String> known,
                                          List<String> warnings,
                                          String context) {
        Map<String, Object> unsupported = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String name = entry.getKey();
            if (name != null && !known.contains(name) && !name.toLowerCase(Locale.ROOT).startsWith("x-") && !"$ref".equals(name)) {
                unsupported.put(name, entry.getValue());
            }
        }
        if (!unsupported.isEmpty()) {
            OpenApiMetadataSupport.putCanonical(metadata, key, unsupported);
            OpenApiWarningSupport.add(warnings, "unsupported retained field: " + context + " "
                    + String.join(", ", unsupported.keySet().stream().map(OpenApiWarningSupport::label).toList()));
        }
    }

    private static Map<String, Object> resolvedContent(Map<String, Object> content,
                                                       OpenApiReferenceResolver references,
                                                       String context) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : content.entrySet()) {
            Map<String, Object> media = entry.getValue() instanceof Map<?, ?> raw
                    ? castMap(raw) : new LinkedHashMap<>();
            if (media.containsKey("schema")) {
                Object schema = references.resolveSchemaTree(media.get("schema"), context);
                if (schema != null) media.put("schema", schema); else media.remove("schema");
            }
            if (media.get("examples") instanceof Map<?, ?> rawExamples) {
                Map<String, Object> examples = new LinkedHashMap<>();
                for (Map.Entry<String, Object> example : castMap(rawExamples).entrySet()) {
                    Object resolved = references.resolveExampleNode(example.getValue(), context);
                    if (resolved != null) examples.put(example.getKey(), resolved);
                }
                media.put("examples", examples);
            }
            result.put(entry.getKey(), media);
        }
        return result;
    }

    private static Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }

    private static List<Map<String, Object>> listOfMaps(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) result.add(castMap(map));
        return result;
    }

    private static List<String> stringList(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object item : list) if (item != null) result.add(String.valueOf(item));
        return result;
    }

    private static List<String> parameterRefs(Object raw) {
        List<String> refs = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object item : list) {
            if (item instanceof Map<?, ?> map && map.get("$ref") instanceof String ref) refs.add(ref);
        }
        return refs;
    }

    private static Set<String> stringSet(Object raw) { return new LinkedHashSet<>(stringList(raw)); }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value); }
    private static String string(Object value, String fallback) { return value != null ? String.valueOf(value) : fallback; }
    private static String nullableString(Object value) { return value != null ? String.valueOf(value) : null; }
    private static String nestedString(Map<String, Object> map, String parent, String child, String fallback) {
        return map.get(parent) instanceof Map<?, ?> nested ? string(nested.get(child), fallback) : fallback;
    }
    private static void putIfText(Map<String, String> target, String key, Object value) { if (value != null && !String.valueOf(value).isBlank()) target.put(key, String.valueOf(value)); }
    private static String mapSwaggerFlow(String value) { return switch (value) { case "application" -> "client_credentials"; case "accessCode" -> "authorization_code"; default -> value; }; }
    private static List<Object> formMaps(List<ParameterDef> defs) { List<Object> values = new ArrayList<>(); for (ParameterDef def : defs) values.add(def.resolved); return values; }

    private record ParameterDef(Map<String, Object> resolved, Map<String, Object> original,
                                String source, String ref, boolean pathLevel) {}
    private record StyleDefaults(String style, Boolean explode) {}
    private record ServerSelection(String url, String level, Map<String, Object> definition) {}

    @Override public String getFormatName() { return "OpenAPI"; }
    @Override public String[] getSupportedExtensions() { return new String[]{"json", "yaml", "yml"}; }
}
