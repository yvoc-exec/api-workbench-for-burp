package burp.utils;

import burp.models.ApiRequest;
import burp.parser.VariableResolver;
import burp.api.montoya.MontoyaApi;
import burp.auth.OAuth2Config;
import burp.auth.OAuth2Manager;
import burp.auth.TokenStore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;
import java.io.ByteArrayOutputStream;

public class RequestBuilder {
    /**
     * Hop-by-hop and body-length headers that must be overridden to match
     * the rebuilt request bytes. Burp normalises these too.
     */
    private static final Set<String> SKIP_HEADER_NAMES = Set.of(
            "host", "content-length", "transfer-encoding", "connection", "proxy-connection"
    );
    private final MontoyaApi api;
    private final VariableResolver resolver;
    private final boolean debugMode = false;
    private final OAuth2Manager oauth2Manager;

    public RequestBuilder(MontoyaApi api, VariableResolver resolver) {
        this(api, resolver, null);
    }

    public RequestBuilder(MontoyaApi api, VariableResolver resolver, OAuth2Manager oauth2Manager) {
        this.oauth2Manager = oauth2Manager;
        this.api = api;
        this.resolver = resolver;
    }

    public byte[] buildRequest(ApiRequest request) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }
        if (request.url == null || request.url.trim().isEmpty()) {
            throw new IllegalArgumentException("Request URL cannot be null or empty");
        }

        // Resolve URL and parse target robustly
        String resolvedUrl = resolver.resolve(request.url);
        String method = request.method != null ? request.method.toUpperCase() : "GET";
        HttpUtils.ParsedTarget parsed = HttpUtils.parseTargetForRequest(resolvedUrl);

        List<String> headers = new ArrayList<>();

        // Request line: METHOD <path> HTTP/1.1
        headers.add(method + " " + parsed.pathWithQuery + " HTTP/1.1");

        // Host header (only :port when non-default for scheme)
        String hostValue = HttpUtils.buildHostWithPort(parsed.host, parsed.port, parsed.useHttps);
        headers.add("Host: " + hostValue);

        // Custom headers (skip dangerous hop-by-hop / body-length headers)
        if (request.headers != null) {
            for (ApiRequest.Header header : request.headers) {
                if (!header.disabled && header.key != null && header.value != null) {
                    String key = resolver.resolve(header.key);
                    String value = resolver.resolve(header.value);
                    if (key != null && !SKIP_HEADER_NAMES.contains(key.trim().toLowerCase())) {
                        headers.add(key.trim() + ": " + value);
                    }
                }
            }
        }

        // Authentication
        applyAuthentication(headers, request.auth);

        // Body
        byte[] body = buildBody(request.body, headers);

        // Override Content-Length to match exact body bytes; never emit Transfer-Encoding
        headers.removeIf(h -> h.toLowerCase().startsWith("content-length:") || h.toLowerCase().startsWith("transfer-encoding:"));
        headers.add("Content-Length: " + body.length);

        // Build raw request bytes preserving CRLF line endings
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(String.join("\r\n", headers).getBytes(StandardCharsets.UTF_8));
        baos.write("\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        baos.write(body);
        return baos.toByteArray();
    }

    private void applyAuthentication(List<String> headers, ApiRequest.Auth auth) {
        if (auth == null || auth.type == null) return;

        switch (auth.type.toLowerCase()) {
            case "bearer":
                String token = auth.properties.getOrDefault("token", auth.properties.get("value"));
                if (token != null) {
                    headers.add("Authorization: Bearer " + resolver.resolve(token));
                }
                break;
            case "basic":
                String username = auth.properties.getOrDefault("username", auth.properties.get("user"));
                String password = auth.properties.getOrDefault("password", auth.properties.get("pass"));
                if (username != null || password != null) {
                    // FIX: Use UTF-8 charset per RFC 7617
                    String credentials = (username != null ? resolver.resolve(username) : "") + ":" +
                            (password != null ? resolver.resolve(password) : "");
                    String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                    headers.add("Authorization: Basic " + encoded);
                }
                break;
            case "apikey":
                String keyName = auth.properties.getOrDefault("key", auth.properties.get("keyname"));
                String keyValue = auth.properties.getOrDefault("value", auth.properties.get("keyvalue"));
                if (keyName != null && keyValue != null) {
                    headers.add(resolver.resolve(keyName) + ": " + resolver.resolve(keyValue));
                }
                break;
            case "oauth2":
                String accessToken = auth.properties.get("accessToken");
                // FIX: Auto-acquire live token if static token is missing/empty/templated
                if ((accessToken == null || accessToken.isEmpty() || accessToken.contains("{{")) && oauth2Manager != null) {
                    try {
                        OAuth2Config config = OAuth2Config.fromVariables(resolver.getVariables());
                        if (config.isValid()) {
                            TokenStore.TokenEntry entry = oauth2Manager.getValidToken(config);
                            accessToken = entry.accessToken;
                        }
                    } catch (Exception e) {
                        if (api != null) {
                            api.logging().logToOutput("OAuth2 auto-acquire failed: " + e.getMessage());
                        }
                    }
                }
                // Also check resolver variable injected by CollectionRunner
                if ((accessToken == null || accessToken.isEmpty()) && resolver.getVariables() != null) {
                    accessToken = resolver.getVariables().get("oauth2_access_token");
                }
                if (accessToken != null && !accessToken.isEmpty()) {
                    headers.add("Authorization: Bearer " + resolver.resolve(accessToken));
                }
                break;
        }
    }

    private byte[] buildBody(ApiRequest.Body body, List<String> headers) throws Exception {
        if (body == null || body.mode == null || "none".equals(body.mode)) {
            return new byte[0];
        }

        switch (body.mode) {
            case "raw":
                if (body.raw != null) {
                    String resolved = resolver.resolve(body.raw);
                    if (!hasContentType(headers)) {
                        headers.add("Content-Type: " + (body.contentType != null ? body.contentType : "text/plain"));
                    }
                    return resolved.getBytes(StandardCharsets.UTF_8);
                }
                break;

            case "graphql":
                if (body.graphql != null) {
                    return buildGraphQLBody(body.graphql, headers);
                }
                break;

            case "urlencoded":
                if (body.urlencoded != null) {
                    List<String> params = new ArrayList<>();
                    for (ApiRequest.Body.FormField param : body.urlencoded) {
                        if (param.key != null) {
                            String key = URLEncoder.encode(resolver.resolve(param.key), "UTF-8");
                            String value = param.value != null ?
                                    URLEncoder.encode(resolver.resolve(param.value), "UTF-8") : "";
                            params.add(key + "=" + value);
                        }
                    }
                    if (!hasContentType(headers)) {
                        headers.add("Content-Type: application/x-www-form-urlencoded");
                    }
                    return String.join("&", params).getBytes(StandardCharsets.UTF_8);
                }
                break;

            case "formdata":
                if (body.formdata != null) {
                    String boundary = "----WebKitFormBoundary" + Long.toHexString(System.currentTimeMillis());
                    if (!hasContentType(headers)) {
                        headers.add("Content-Type: multipart/form-data; boundary=" + boundary);
                    }
                    return buildMultipartBody(body.formdata, boundary);
                }
                break;
        }

        return new byte[0];
    }

    private byte[] buildGraphQLBody(ApiRequest.Body.GraphQL graphql, List<String> headers) {
        if (graphql == null) return new byte[0];
        try {
            com.google.gson.JsonObject body = new com.google.gson.JsonObject();
            if (graphql.query != null) {
                body.addProperty("query", resolver.resolve(graphql.query));
            }
            if (graphql.variables != null && !graphql.variables.trim().isEmpty()) {
                try {
                    com.google.gson.JsonElement vars = com.google.gson.JsonParser.parseString(resolver.resolve(graphql.variables));
                    body.add("variables", vars);
                } catch (Exception e) {
                    body.add("variables", new com.google.gson.JsonObject());
                }
            } else {
                body.add("variables", new com.google.gson.JsonObject());
            }
            if (!hasContentType(headers)) {
                headers.add("Content-Type: application/json");
            }
            return new com.google.gson.GsonBuilder().serializeNulls().create().toJson(body).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private byte[] buildMultipartBody(List<ApiRequest.Body.FormField> formData, String boundary) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        for (ApiRequest.Body.FormField field : formData) {
            if (field.key == null) continue;
            String resolvedKey = resolver.resolve(field.key);
            String resolvedValue = field.value != null ? resolver.resolve(field.value) : "";

            baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));

            // Check if value is a file path (Bruno/Postman file upload)
            boolean isFile = resolvedValue != null && (resolvedValue.startsWith("/") || (resolvedValue.length() > 2 && resolvedValue.charAt(1) == ':' && resolvedValue.charAt(2) == '\\') || resolvedValue.startsWith("file://"));
            java.io.File file = isFile ? new java.io.File(resolvedValue.replace("file://", "")) : null;
            if (file != null && file.exists() && file.isFile()) {
                // Security: Validate file path to prevent path traversal
                String canonicalPath = file.getCanonicalPath();
                String canonicalBase = new java.io.File(".").getCanonicalPath();
                if (!canonicalPath.startsWith(canonicalBase) && !canonicalPath.startsWith(System.getProperty("user.home"))) {
                    throw new SecurityException("Path traversal detected: " + resolvedValue);
                }
                String filename = file.getName();
                baos.write(("Content-Disposition: form-data; name=\"" + resolvedKey + "\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                String contentType = java.nio.file.Files.probeContentType(file.toPath());
                if (contentType == null) contentType = "application/octet-stream";
                baos.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                baos.write(java.nio.file.Files.readAllBytes(file.toPath()));
                baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
            } else {
                baos.write(("Content-Disposition: form-data; name=\"" + resolvedKey + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                baos.write(resolvedValue.getBytes(StandardCharsets.UTF_8));
                baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        baos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return baos.toByteArray();
    }

    private boolean hasContentType(List<String> headers) {
        return headers.stream().anyMatch(h -> h.toLowerCase().startsWith("content-type:"));
    }

    // Check for any header by name (case-insensitive)
    private boolean hasHeader(List<String> headers, String headerName) {
        String target = headerName.toLowerCase();
        return headers.stream().anyMatch(h -> {
            int colonIdx = h.indexOf(':');
            if (colonIdx == -1) return false;
            return h.substring(0, colonIdx).trim().toLowerCase().equals(target);
        });
    }
}
