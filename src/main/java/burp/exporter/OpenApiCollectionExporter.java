package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;
import burp.utils.HttpUtils;
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

        Map<String, Object> info = new LinkedHashMap<>();
        VariableResolver rootResolver = CollectionExportSupport.buildResolver(collection, null, activeEnvironment, exportOnly);
        info.put("title", collection != null && collection.name != null ? CollectionExportSupport.resolve(collection.name, rootResolver, resolve) : "API Workbench Collection");
        info.put("version", collection != null && collection.version != null && !collection.version.isBlank() ? collection.version : "1.0.0");
        if (collection != null && collection.description != null && !collection.description.isBlank()) {
            info.put("description", CollectionExportSupport.resolve(collection.description, rootResolver, resolve));
        }
        root.put("info", info);

        ParsedUrl commonOrigin = commonOrigin(collection, activeEnvironment, exportOnly, resolve);
        if (commonOrigin != null && commonOrigin.origin != null && !commonOrigin.origin.isBlank()) {
            List<Map<String, Object>> servers = new ArrayList<>();
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("url", commonOrigin.origin);
            servers.add(server);
            root.put("servers", servers);
        }

        Map<String, Object> components = new LinkedHashMap<>();
        Map<String, Object> securitySchemes = new LinkedHashMap<>();
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
        if (collection != null && collection.requests != null) {
            int index = 0;
            for (ApiRequest request : collection.requests) {
                if (request == null) {
                    continue;
                }
                VariableResolver resolver = CollectionExportSupport.buildResolver(collection, request, activeEnvironment, exportOnly);
                ParsedUrl parsed = parseUrl(CollectionExportSupport.resolve(request.url, resolver, resolve), warnings, request.name);
                String path = parsed.path;
                Map<String, Object> pathItem = (Map<String, Object>) paths.computeIfAbsent(path, key -> new LinkedHashMap<>());
                String method = request.method != null ? request.method.toLowerCase(Locale.ROOT) : "get";
                Map<String, Object> operation = new LinkedHashMap<>();
                String operationId = slugOperationId(request.name, method, path, index++);
                operation.put("operationId", operationId);
                if (request.name != null) {
                    operation.put("summary", CollectionExportSupport.resolve(request.name, resolver, resolve));
                }
                if (request.description != null && !request.description.isBlank()) {
                    operation.put("description", CollectionExportSupport.resolve(request.description, resolver, resolve));
                }
                List<Map<String, Object>> parameters = buildParameters(request, parsed, resolver, resolve);
                if (!parameters.isEmpty()) {
                    operation.put("parameters", parameters);
                }
                Map<String, Object> requestBody = buildRequestBody(request, resolver, resolve);
                if (requestBody != null) {
                    operation.put("requestBody", requestBody);
                }
                List<Map<String, Object>> security = buildSecurity(collection, request, resolver, resolve, securitySchemes);
                if (security != null) {
                    operation.put("security", security);
                }
                Map<String, Object> responses = new LinkedHashMap<>();
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("description", "Default response");
                responses.put("default", response);
                operation.put("responses", responses);
                pathItem.put(method, operation);
            }
        }
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
        return root;
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

    private static List<Map<String, Object>> buildParameters(ApiRequest request, ParsedUrl parsed, VariableResolver resolver, boolean resolve) {
        List<Map<String, Object>> parameters = new ArrayList<>();
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

    private static Map<String, Object> buildRequestBody(ApiRequest request, VariableResolver resolver, boolean resolve) {
        if (request == null || request.body == null || request.body.mode == null || "none".equalsIgnoreCase(request.body.mode)) {
            return null;
        }
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("required", true);
        Map<String, Object> content = new LinkedHashMap<>();
        String mode = request.body.mode.toLowerCase(Locale.ROOT);
        if ("urlencoded".equals(mode)) {
            Map<String, Object> media = new LinkedHashMap<>();
            media.put("schema", formSchema(request.body.urlencoded, resolver, resolve));
            content.put("application/x-www-form-urlencoded", media);
        } else if ("formdata".equals(mode)) {
            Map<String, Object> media = new LinkedHashMap<>();
            media.put("schema", formSchema(request.body.formdata, resolver, resolve));
            content.put("multipart/form-data", media);
        } else if ("graphql".equals(mode)) {
            Map<String, Object> media = new LinkedHashMap<>();
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("query", stringSchema(CollectionExportSupport.resolve(request.body.graphql != null ? request.body.graphql.query : null, resolver, resolve)));
            props.put("variables", stringSchema(CollectionExportSupport.resolve(request.body.graphql != null ? request.body.graphql.variables : null, resolver, resolve)));
            schema.put("properties", props);
            schema.put("required", List.of("query"));
            media.put("schema", schema);
            content.put("application/json", media);
        } else {
            String contentType = request.body.contentType != null && !request.body.contentType.isBlank()
                    ? request.body.contentType
                    : "text/plain";
            Map<String, Object> media = new LinkedHashMap<>();
            media.put("schema", schemaFromRaw(resolve ? CollectionExportSupport.resolve(request.body.raw, resolver, true) : request.body.raw, contentType));
            content.put(contentType, media);
        }
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

    private static Map<String, Object> formSchema(List<ApiRequest.Body.FormField> fields, VariableResolver resolver, boolean resolve) {
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
                properties.put(key, stringSchema(CollectionExportSupport.resolve(field.value, resolver, resolve)));
                required.add(key);
            }
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
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
            ParsedUrl parsed = parseUrl(CollectionExportSupport.resolve(request.url, resolver, resolve), null, request.name);
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
}
