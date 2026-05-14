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

        String requestTarget = parsed.pathWithQuery;
        List<String> headers = new ArrayList<>();

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

        // Authentication (may modify requestTarget for API-key-in-query)
        requestTarget = applyAuthentication(headers, request.auth, requestTarget);

        // Insert request line at the top after auth has settled the target
        headers.add(0, method + " " + requestTarget + " HTTP/1.1");

        // Body
        byte[] body = maybeBuildOAuth2TokenBody(request, method, resolvedUrl, headers);
        if (body == null) {
            body = buildBody(request.body, headers);
        }

        // Override Content-Length to match exact body bytes; never emit Transfer-Encoding
        headers.removeIf(h -> h.toLowerCase().startsWith("content-length:") || h.toLowerCase().startsWith("transfer-encoding:"));
        boolean shouldSendContentLength = body.length > 0
                || method.equals("POST")
                || method.equals("PUT")
                || method.equals("PATCH");
        if (shouldSendContentLength) {
            headers.add("Content-Length: " + body.length);
        }

        // Build raw request bytes preserving CRLF line endings
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(String.join("\r\n", headers).getBytes(StandardCharsets.UTF_8));
        baos.write("\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        baos.write(body);
        return baos.toByteArray();
    }

    /**
     * Applies authentication to headers and optionally modifies the request target
     * (e.g., appending API key to query string). Returns the potentially-modified target.
     */
    private String applyAuthentication(List<String> headers, ApiRequest.Auth auth, String requestTarget) {
        if (auth == null || auth.type == null) return requestTarget;

        switch (auth.type.toLowerCase()) {
            case "bearer":
                if (!hasHeader(headers, "Authorization")) {
                    String token = auth.properties.getOrDefault("token", auth.properties.get("value"));
                    if (token != null) {
                        headers.add("Authorization: Bearer " + resolver.resolve(token));
                    }
                }
                break;
            case "basic":
                if (!hasHeader(headers, "Authorization")) {
                    String username = auth.properties.getOrDefault("username", auth.properties.get("user"));
                    String password = auth.properties.getOrDefault("password", auth.properties.get("pass"));
                    if (username != null || password != null) {
                        String credentials = (username != null ? resolver.resolve(username) : "") + ":" +
                                (password != null ? resolver.resolve(password) : "");
                        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                        headers.add("Authorization: Basic " + encoded);
                    }
                }
                break;
            case "apikey":
                String keyName = auth.properties.getOrDefault("key", auth.properties.get("keyname"));
                String keyValue = auth.properties.getOrDefault("value", auth.properties.get("keyvalue"));
                String in = auth.properties.getOrDefault("in", "header");
                if (keyName != null && keyValue != null) {
                    if ("query".equalsIgnoreCase(in)) {
                        String param = URLEncoder.encode(resolver.resolve(keyName), StandardCharsets.UTF_8)
                                + "=" + URLEncoder.encode(resolver.resolve(keyValue), StandardCharsets.UTF_8);
                        requestTarget = requestTarget.contains("?")
                                ? requestTarget + "&" + param
                                : requestTarget + "?" + param;
                    } else {
                        headers.add(resolver.resolve(keyName) + ": " + resolver.resolve(keyValue));
                    }
                }
                break;
            case "cookie":
                String cookieValue = auth.properties.get("value");
                if (cookieValue != null) {
                    String resolvedCookie = resolver.resolve(cookieValue);
                    Optional<String> existingCookie = headers.stream()
                            .filter(h -> h.toLowerCase().startsWith("cookie:"))
                            .findFirst();
                    if (existingCookie.isPresent()) {
                        int idx = headers.indexOf(existingCookie.get());
                        headers.set(idx, existingCookie.get() + "; " + resolvedCookie);
                    } else {
                        headers.add("Cookie: " + resolvedCookie);
                    }
                }
                break;
            case "oauth2":
                if (!hasHeader(headers, "Authorization")) {
                    String accessToken = auth.properties.get("accessToken");
                    // Auto-acquire live token if static token is missing/empty/templated
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
                }
                break;
        }
        return requestTarget;
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

    /**
     * Auto-builds an application/x-www-form-urlencoded OAuth2 token request body
     * when the request URL matches oauth2_token_url and the body is empty.
     * Supports client_credentials, password, refresh_token, and authorization_code grants.
     * Returns null when no auto-build is needed.
     */
    private byte[] maybeBuildOAuth2TokenBody(ApiRequest request, String method, String resolvedUrl, List<String> headers) {
        Map<String, String> vars = resolver.mutableVariables();
        if (vars == null) return null;

        String tokenUrl = vars.get("oauth2_token_url");
        if (tokenUrl == null || tokenUrl.isBlank()) return null;

        String resolvedTokenUrl = resolver.resolve(tokenUrl);
        // Strip fragments for comparison consistency
        resolvedTokenUrl = stripFragment(resolvedTokenUrl);
        String compareUrl = stripFragment(resolvedUrl);
        if (!"POST".equals(method) || !compareUrl.trim().equals(resolvedTokenUrl.trim())) {
            return null;
        }

        if (!isBodyEmpty(request.body)) {
            return null;
        }

        // Read OAuth2 variables
        String grant = vars.getOrDefault("oauth2_grant", "client_credentials");
        String clientId = vars.get("oauth2_client_id");
        String clientSecret = vars.get("oauth2_client_secret");
        String scope = vars.get("oauth2_scope");
        String username = vars.get("oauth2_username");
        String password = vars.get("oauth2_password");
        String refreshToken = vars.get("oauth2_refresh_token");
        String code = vars.get("oauth2_code");
        String redirectUri = vars.get("oauth2_redirect_uri");
        String codeVerifier = vars.get("oauth2_code_verifier");
        String clientAuth = vars.getOrDefault("oauth2_client_auth", "body");

        // Normalize grant type
        String normalizedGrant = grant.replace('-', '_').toLowerCase();

        // Validate grant-specific required fields
        List<String> missing = new ArrayList<>();
        if (clientId == null || clientId.isBlank()) {
            missing.add("oauth2_client_id");
        }
        switch (normalizedGrant) {
            case "client_credentials":
                if (clientSecret == null || clientSecret.isBlank()) {
                    missing.add("oauth2_client_secret");
                }
                break;
            case "password":
                if (username == null || username.isBlank()) missing.add("oauth2_username");
                if (password == null || password.isBlank()) missing.add("oauth2_password");
                break;
            case "refresh_token":
                if (refreshToken == null || refreshToken.isBlank()) missing.add("oauth2_refresh_token");
                break;
            case "authorization_code":
                if (code == null || code.isBlank()) {
                    throw new IllegalArgumentException(
                            "OAuth2 authorization_code token request missing oauth2_code. " +
                            "Acquire the authorization code via the OAuth2 tab before running this request.");
                }
                break;
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "OAuth2 token request missing required variables: " + String.join(", ", missing));
        }

        // Determine client authentication mode
        boolean useBasicAuth = "basic".equalsIgnoreCase(clientAuth) || "prefer_basic".equalsIgnoreCase(clientAuth);
        boolean includeSecretInBody = !useBasicAuth && clientSecret != null && !clientSecret.isBlank();

        // If using basic auth for client credentials, set Authorization header
        if (useBasicAuth && clientSecret != null && !clientSecret.isBlank()) {
            if (!hasHeader(headers, "Authorization")) {
                String credentials = clientId + ":" + clientSecret;
                String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                headers.add("Authorization: Basic " + encoded);
            }
        }

        // Build form-urlencoded body
        List<String> params = new ArrayList<>();
        params.add("grant_type=" + URLEncoder.encode(normalizedGrant, StandardCharsets.UTF_8));
        params.add("client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8));
        if (includeSecretInBody) {
            params.add("client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));
        }
        if (scope != null && !scope.isBlank()) {
            params.add("scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8));
        }
        switch (normalizedGrant) {
            case "password":
                if (username != null && !username.isBlank()) {
                    params.add("username=" + URLEncoder.encode(username, StandardCharsets.UTF_8));
                }
                if (password != null && !password.isBlank()) {
                    params.add("password=" + URLEncoder.encode(password, StandardCharsets.UTF_8));
                }
                break;
            case "refresh_token":
                if (refreshToken != null && !refreshToken.isBlank()) {
                    params.add("refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8));
                }
                break;
            case "authorization_code":
                if (code != null && !code.isBlank()) {
                    params.add("code=" + URLEncoder.encode(code, StandardCharsets.UTF_8));
                }
                if (redirectUri != null && !redirectUri.isBlank()) {
                    params.add("redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8));
                }
                if (codeVerifier != null && !codeVerifier.isBlank()) {
                    params.add("code_verifier=" + URLEncoder.encode(codeVerifier, StandardCharsets.UTF_8));
                }
                break;
        }

        // Ensure correct Content-Type for the auto-built body
        headers.removeIf(h -> h.toLowerCase().startsWith("content-type:"));
        headers.add("Content-Type: application/x-www-form-urlencoded");

        return String.join("&", params).getBytes(StandardCharsets.UTF_8);
    }

    private static String stripFragment(String url) {
        int hashIdx = url.indexOf('#');
        return hashIdx >= 0 ? url.substring(0, hashIdx) : url;
    }

    private boolean isBodyEmpty(ApiRequest.Body body) {
        if (body == null || body.mode == null || "none".equals(body.mode)) {
            return true;
        }
        switch (body.mode) {
            case "raw":
                return body.raw == null || body.raw.isBlank();
            case "urlencoded":
                return body.urlencoded == null || body.urlencoded.isEmpty();
            case "formdata":
                return body.formdata == null || body.formdata.isEmpty();
            default:
                return true;
        }
    }
}
