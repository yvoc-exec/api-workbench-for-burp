package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import com.google.gson.*;
import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.util.*;
import java.util.Set;
import java.util.HashSet;

/**
 * Parser for OpenAPI/Swagger specs (JSON and YAML).
 */
public class OpenApiParser implements CollectionParser {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public boolean canParse(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".yaml") || name.endsWith(".yml")) return true;
        if (!name.endsWith(".json")) return false;

        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            return obj.has("openapi") || obj.has("swagger");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ApiCollection parse(File file) throws Exception {
        Map<String, Object> spec;
        String name = file.getName().toLowerCase();

        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            Yaml yaml = new Yaml();
            try (java.io.InputStreamReader reader = new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                spec = yaml.load(reader);
            }
        } else {
            try (java.io.InputStreamReader reader = new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                spec = gson.fromJson(reader, Map.class);
            }
        }

        ApiCollection collection = new ApiCollection();
        collection.format = "openapi";
        collection.name = getString(spec, "info.title", "OpenAPI Spec");
        collection.description = getString(spec, "info.description", "");
        collection.version = getString(spec, "openapi", getString(spec, "swagger", "3.0"));

        // Extract security schemes (OAS3 or Swagger2)
        Map<String, Map<String, Object>> securitySchemes = extractSecuritySchemes(spec);

        // Top-level default security
        List<Map<String, Object>> defaultSecurity = null;
        if (spec.containsKey("security") && spec.get("security") instanceof List) {
            defaultSecurity = (List) spec.get("security");
        }

        // Extract servers/base URLs
        List<String> baseUrls = new ArrayList<>();
        if (spec.containsKey("servers") && spec.get("servers") instanceof List) {
            for (Object s : (List) spec.get("servers")) {
                if (s instanceof Map) {
                    String url = (String) ((Map) s).get("url");
                    if (url != null) baseUrls.add(url);
                }
            }
        } else if (spec.containsKey("host")) {
            String scheme = getString(spec, "schemes.0", "https");
            String host = (String) spec.get("host");
            String basePath = getString(spec, "basePath", "");
            baseUrls.add(scheme + "://" + host + basePath);
        }

        String defaultBaseUrl = baseUrls.isEmpty() ? "http://localhost" : baseUrls.get(0);

        // Parse paths
        if (spec.containsKey("paths") && spec.get("paths") instanceof Map) {
            Map<String, Object> paths = (Map) spec.get("paths");
            for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
                String path = pathEntry.getKey();
                if (pathEntry.getValue() instanceof Map) {
                    Map<String, Object> methods = (Map) pathEntry.getValue();
                    for (Map.Entry<String, Object> methodEntry : methods.entrySet()) {
                        String method = methodEntry.getKey().toUpperCase();
                        if (isHttpMethod(method)) {
                            ApiRequest req = parseOpenApiOperation(
                                method, path, (Map) methodEntry.getValue(), defaultBaseUrl,
                                securitySchemes, defaultSecurity
                            );
                            req.sourceCollection = collection.name;
                            collection.requests.add(req);
                        }
                    }
                }
            }
        }

        return collection;
    }

    private Map<String, Map<String, Object>> extractSecuritySchemes(Map<String, Object> spec) {
        Map<String, Map<String, Object>> schemes = new HashMap<>();
        if (spec.containsKey("components") && spec.get("components") instanceof Map) {
            Map<String, Object> components = (Map) spec.get("components");
            if (components.containsKey("securitySchemes") && components.get("securitySchemes") instanceof Map) {
                Map<String, Object> raw = (Map) components.get("securitySchemes");
                for (Map.Entry<String, Object> e : raw.entrySet()) {
                    if (e.getValue() instanceof Map) {
                        schemes.put(e.getKey(), (Map) e.getValue());
                    }
                }
            }
        } else if (spec.containsKey("securityDefinitions") && spec.get("securityDefinitions") instanceof Map) {
            Map<String, Object> raw = (Map) spec.get("securityDefinitions");
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (e.getValue() instanceof Map) {
                    schemes.put(e.getKey(), (Map) e.getValue());
                }
            }
        }
        return schemes;
    }

    private ApiRequest parseOpenApiOperation(String method, String path, Map<String, Object> op, String baseUrl,
                                             Map<String, Map<String, Object>> securitySchemes,
                                             List<Map<String, Object>> defaultSecurity) {
        ApiRequest req = new ApiRequest();
        req.method = method;
        req.name = getString(op, "operationId", method + " " + path);
        req.path = path;
        req.description = getString(op, "summary", "") + "\n" + getString(op, "description", "");
        req.url = baseUrl + path;

        // Parse parameters (query, header, path)
        if (op.containsKey("parameters") && op.get("parameters") instanceof List) {
            List<Map<String, Object>> params = (List) op.get("parameters");
            for (Map<String, Object> param : params) {
                String in = (String) param.get("in");
                String paramName = (String) param.get("name");
                if ("header".equals(in)) {
                    req.headers.add(new ApiRequest.Header(paramName, String.valueOf(generateExampleValue(param))));
                }
                // Path/query params are embedded in URL or handled by variable resolver
            }
        }

        // Parse request body
        if (op.containsKey("requestBody") && op.get("requestBody") instanceof Map) {
            Map<String, Object> reqBody = (Map) op.get("requestBody");
            if (reqBody.containsKey("content") && reqBody.get("content") instanceof Map) {
                Map<String, Object> content = (Map) reqBody.get("content");
                // Prefer application/json
                if (content.containsKey("application/json")) {
                    req.body = new ApiRequest.Body();
                    req.body.mode = "raw";
                    req.body.contentType = "application/json";
                    req.body.raw = generateJsonExample((Map) ((Map) content.get("application/json")).get("schema"));
                } else if (content.containsKey("application/x-www-form-urlencoded")) {
                    req.body = new ApiRequest.Body();
                    req.body.mode = "urlencoded";
                    Map<String, Object> schema = (Map) ((Map) content.get("application/x-www-form-urlencoded")).get("schema");
                    if (schema != null && schema.containsKey("properties")) {
                        Map<String, Object> props = (Map) schema.get("properties");
                        for (String key : props.keySet()) {
                            req.body.urlencoded.add(new ApiRequest.Body.FormField(key, ""));
                        }
                    }
                } else if (content.containsKey("multipart/form-data")) {
                    req.body = new ApiRequest.Body();
                    req.body.mode = "formdata";
                    Map<String, Object> schema = (Map) ((Map) content.get("multipart/form-data")).get("schema");
                    if (schema != null && schema.containsKey("properties")) {
                        Map<String, Object> props = (Map) schema.get("properties");
                        for (String key : props.keySet()) {
                            req.body.formdata.add(new ApiRequest.Body.FormField(key, ""));
                        }
                    }
                }
            }
        }

        // Parse security/auth with scheme resolution
        List<Map<String, Object>> security = null;
        if (op.containsKey("security") && op.get("security") instanceof List) {
            security = (List) op.get("security");
        } else {
            security = defaultSecurity;
        }
        req.auth = resolveSecurity(security, securitySchemes);

        return req;
    }

    private ApiRequest.Auth resolveSecurity(List<Map<String, Object>> security,
                                            Map<String, Map<String, Object>> securitySchemes) {
        if (security == null) return null;
        if (security.isEmpty()) {
            // Explicitly no auth (security: [])
            ApiRequest.Auth auth = new ApiRequest.Auth();
            auth.type = "none";
            return auth;
        }
        // Use first security requirement
        Map<String, Object> firstReq = security.get(0);
        if (firstReq == null || firstReq.isEmpty()) return null;

        String schemeName = firstReq.keySet().iterator().next();
        Map<String, Object> scheme = securitySchemes.get(schemeName);
        if (scheme == null) return null;

        return mapSecurityScheme(scheme);
    }

    private ApiRequest.Auth mapSecurityScheme(Map<String, Object> scheme) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        String type = getString(scheme, "type", "");

        if ("http".equalsIgnoreCase(type)) {
            String schemeValue = getString(scheme, "scheme", "").toLowerCase();
            if ("bearer".equals(schemeValue)) {
                auth.type = "bearer";
                auth.properties.put("token", "{{token}}");
            } else if ("basic".equals(schemeValue)) {
                auth.type = "basic";
                auth.properties.put("username", "{{username}}");
                auth.properties.put("password", "{{password}}");
            }
        } else if ("apiKey".equalsIgnoreCase(type)) {
            String in = getString(scheme, "in", "header");
            String name = getString(scheme, "name", "");
            if ("cookie".equalsIgnoreCase(in)) {
                auth.type = "cookie";
                auth.properties.put("value", name + "={{api_key}}");
            } else {
                auth.type = "apikey";
                auth.properties.put("key", name);
                auth.properties.put("value", "{{api_key}}");
                auth.properties.put("in", in);
            }
        } else if ("oauth2".equalsIgnoreCase(type)) {
            auth.type = "oauth2";
            if (scheme.containsKey("flows") && scheme.get("flows") instanceof Map) {
                Map<String, Object> flows = (Map) scheme.get("flows");
                extractOAuth2Flows(auth, flows);
            } else {
                // Swagger2 style
                String flow = getString(scheme, "flow", "");
                auth.properties.put("grantType", mapSwagger2Flow(flow));
                String tokenUrl = getString(scheme, "tokenUrl", "");
                if (!tokenUrl.isEmpty()) auth.properties.put("accessTokenUrl", tokenUrl);
                String authUrl = getString(scheme, "authorizationUrl", "");
                if (!authUrl.isEmpty()) auth.properties.put("authorizationUrl", authUrl);
                if (scheme.containsKey("scopes") && scheme.get("scopes") instanceof Map) {
                    Map<String, Object> scopes = (Map) scheme.get("scopes");
                    if (!scopes.isEmpty()) auth.properties.put("scope", String.join(" ", scopes.keySet()));
                }
            }
            auth.properties.put("clientId", "{{oauth2_client_id}}");
            auth.properties.put("clientSecret", "{{oauth2_client_secret}}");
            auth.properties.put("accessToken", "{{oauth2_access_token}}");
        } else if ("basic".equalsIgnoreCase(type)) {
            // Swagger2 explicit basic
            auth.type = "basic";
            auth.properties.put("username", "{{username}}");
            auth.properties.put("password", "{{password}}");
        }

        return auth.type != null ? auth : null;
    }

    private void extractOAuth2Flows(ApiRequest.Auth auth, Map<String, Object> flows) {
        String[] flowTypes = {"clientCredentials", "password", "authorizationCode", "implicit"};
        for (String flowType : flowTypes) {
            if (flows.containsKey(flowType) && flows.get(flowType) instanceof Map) {
                Map<String, Object> flow = (Map) flows.get(flowType);
                auth.properties.put("grantType", mapOas3FlowType(flowType));
                String tokenUrl = getString(flow, "tokenUrl", "");
                if (!tokenUrl.isEmpty()) auth.properties.put("accessTokenUrl", tokenUrl);
                String authUrl = getString(flow, "authorizationUrl", "");
                if (!authUrl.isEmpty()) auth.properties.put("authorizationUrl", authUrl);
                String refreshUrl = getString(flow, "refreshUrl", "");
                if (!refreshUrl.isEmpty()) auth.properties.put("refreshTokenUrl", refreshUrl);
                if (flow.containsKey("scopes") && flow.get("scopes") instanceof Map) {
                    Map<String, Object> scopes = (Map) flow.get("scopes");
                    if (!scopes.isEmpty()) {
                        auth.properties.put("scope", String.join(" ", scopes.keySet()));
                    }
                }
                break;
            }
        }
    }

    private String mapOas3FlowType(String flow) {
        switch (flow) {
            case "clientCredentials": return "client_credentials";
            case "authorizationCode": return "authorization_code";
            case "password": return "password";
            case "implicit": return "implicit";
            default: return flow;
        }
    }

    private String mapSwagger2Flow(String flow) {
        switch (flow) {
            case "application": return "client_credentials";
            case "accessCode": return "authorization_code";
            default: return flow; // password, implicit
        }
    }

    private String generateJsonExample(Map<String, Object> schema) {
        Object example = generateExampleValue(schema);
        if (example instanceof Map || example instanceof List) {
            return gson.toJson(example);
        }
        return String.valueOf(example);
    }

    private Object generateExampleValue(Map<String, Object> schema) {
        return generateExampleValue(schema, new HashSet<>(), 0);
    }

    private Object generateExampleValue(Map<String, Object> schema, Set<String> visited, int depth) {
        if (schema == null) return null;
        if (depth > 10) return "{...}"; // Prevent deep nesting explosion

        // Handle $ref references (basic dereferencing with cycle detection)
        if (schema.containsKey("$ref")) {
            String ref = (String) schema.get("$ref");
            if (visited.contains(ref)) {
                return "{\"$ref\": \"" + ref + " (recursive)\"}";
            }
            visited.add(ref);
            return "{\"$ref\": \"" + ref + "\"}";
        }

        // Handle oneOf/anyOf/allOf
        if (schema.containsKey("oneOf") && schema.get("oneOf") instanceof List) {
            List<Map<String, Object>> oneOf = (List) schema.get("oneOf");
            if (!oneOf.isEmpty()) return generateExampleValue(oneOf.get(0));
        }
        if (schema.containsKey("anyOf") && schema.get("anyOf") instanceof List) {
            List<Map<String, Object>> anyOf = (List) schema.get("anyOf");
            if (!anyOf.isEmpty()) return generateExampleValue(anyOf.get(0));
        }
        if (schema.containsKey("allOf") && schema.get("allOf") instanceof List) {
            List<Map<String, Object>> allOf = (List) schema.get("allOf");
            Map<String, Object> merged = new LinkedHashMap<>();
            for (Map<String, Object> sub : allOf) {
                Object ex = generateExampleValue(sub, new HashSet<>(visited), depth + 1);
                if (ex instanceof Map) merged.putAll((Map) ex);
            }
            return merged;
        }

        String type = (String) schema.get("type");
        if (type == null) type = "object"; // Default assumption

        // Check for explicit example or default
        if (schema.containsKey("example")) return schema.get("example");
        if (schema.containsKey("default")) return schema.get("default");

        // Check for enum
        if (schema.containsKey("enum") && schema.get("enum") instanceof List) {
            List<Object> enums = (List) schema.get("enum");
            return enums.isEmpty() ? null : enums.get(0);
        }

        switch (type) {
            case "string":
                String format = (String) schema.get("format");
                if ("email".equals(format)) return "user@example.com";
                if ("uuid".equals(format)) return "550e8400-e29b-41d4-a716-446655440000";
                if ("date".equals(format)) return "2024-01-01";
                if ("date-time".equals(format)) return "2024-01-01T00:00:00Z";
                if ("uri".equals(format) || "url".equals(format)) return "https://example.com";
                if ("password".equals(format)) return "SecureP@ss123";
                int minLength = schema.containsKey("minLength") ? ((Number) schema.get("minLength")).intValue() : 0;
                return "a".repeat(Math.max(1, minLength));

            case "integer":
            case "number":
                Number minimum = schema.containsKey("minimum") ? (Number) schema.get("minimum") : null;
                Number maximum = schema.containsKey("maximum") ? (Number) schema.get("maximum") : null;
                Number multipleOf = schema.containsKey("multipleOf") ? (Number) schema.get("multipleOf") : null;
                if (minimum != null) {
                    double val = minimum.doubleValue();
                    if (multipleOf != null) val = Math.ceil(val / multipleOf.doubleValue()) * multipleOf.doubleValue();
                    return type.equals("integer") ? (int) val : val;
                }
                if (maximum != null) return type.equals("integer") ? maximum.intValue() : maximum.doubleValue();
                return type.equals("integer") ? 1 : 1.0;

            case "boolean":
                return true;

            case "array":
                List<Object> arr = new ArrayList<>();
                if (schema.containsKey("items") && schema.get("items") instanceof Map) {
                    Map<String, Object> items = (Map) schema.get("items");
                    arr.add(generateExampleValue(items));
                }
                return arr;

            case "object":
            default:
                Map<String, Object> obj = new LinkedHashMap<>();
                if (schema.containsKey("properties") && schema.get("properties") instanceof Map) {
                    Map<String, Object> props = (Map) schema.get("properties");
                    List<String> required = schema.containsKey("required") && schema.get("required") instanceof List
                            ? (List) schema.get("required") : new ArrayList<>();
                    for (Map.Entry<String, Object> entry : props.entrySet()) {
                        String propName = entry.getKey();
                        if (entry.getValue() instanceof Map) {
                            Map<String, Object> propSchema = (Map) entry.getValue();
                            // Only include required properties or first 5 to avoid bloat
                            if (required.contains(propName) || obj.size() < 5) {
                                obj.put(propName, generateExampleValue(propSchema));
                            }
                        }
                    }
                }
                return obj;
        }
    }

    private boolean isHttpMethod(String method) {
        return Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE").contains(method);
    }

    private String getString(Map<String, Object> map, String keyPath, String defaultValue) {
        String[] keys = keyPath.split("\\.");
        Object current = map;
        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map) current).get(key);
            } else {
                return defaultValue;
            }
        }
        return current != null ? current.toString() : defaultValue;
    }

    @Override
    public String getFormatName() { return "OpenAPI"; }

    @Override
    public String[] getSupportedExtensions() { return new String[]{"json", "yaml", "yml"}; }
}
