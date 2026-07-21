package burp.utils;

import burp.models.ApiRequest;
import burp.parser.VariableResolver;
import burp.api.montoya.MontoyaApi;
import burp.auth.OAuth2Manager;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RequestBuilder {
    /**
     * Hop-by-hop and body-length headers that must not be emitted from imported
     * definitions. Burp normalises these too, but stale values cause 400s.
     */
    private static final Set<String> SAFE_FORBIDDEN_AUTHORED_HEADER_NAMES = Set.of(
            "host", "content-length", "transfer-encoding", "connection", "proxy-connection",
            "keep-alive", "te", "trailer", "upgrade", "http2-settings", "proxy-authorization"
    );
    private final MontoyaApi api;
    private final boolean debugMode = false;

    public RequestBuilder(MontoyaApi api) {
        this(api, null);
    }

    public RequestBuilder(MontoyaApi api, OAuth2Manager oauth2Manager) {
        this.api = api;
    }

    public byte[] buildRequest(ApiRequest request, VariableResolver resolver) throws Exception {
        if (request != null
                && request.resolveBuildMode() == ApiRequest.BuildMode.EXACT_HTTP
                && request.exactHttpRequest != null
                && request.exactHttpRequest.pristine
                && request.exactHttpRequest.rawRequestBytes != null
                && request.exactHttpRequest.rawRequestBytes.length > 0) {
            return request.exactHttpRequest.rawRequestBytes.clone();
        }
        RequestBuildPolicy policy = RequestBuildPolicy.forRequest(request);
        BuildContext ctx = buildHeadersAndBody(request, resolver, policy);
        List<String> rawHeaders = ctx.rawHeaders;
        byte[] body = ctx.body;

        if (!policy.exactHttp()) {
            // Final sanitization: strip any stale Content-Length / Transfer-Encoding that may
            // have leaked through, then compute exact Content-Length from body bytes.
            rawHeaders.removeIf(h -> {
                String lower = h.toLowerCase();
                return lower.startsWith("content-length:") || lower.startsWith("transfer-encoding:");
            });
            boolean shouldSendContentLength = body.length > 0
                    || ctx.method.equals("POST")
                    || ctx.method.equals("PUT")
                    || ctx.method.equals("PATCH");
            if (shouldSendContentLength) {
                rawHeaders.add("Content-Length: " + body.length);
            }
        }

        // Build raw request bytes preserving CRLF line endings
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(String.join("\r\n", rawHeaders).getBytes(StandardCharsets.UTF_8));
        baos.write("\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        baos.write(body);
        return baos.toByteArray();
    }

    /**
     * Returns the effective header set that API Workbench intends to send,
     * excluding transport framing headers like Content-Length and
     * Transfer-Encoding.
     */
    public List<Map.Entry<String, String>> buildEffectiveHeaders(ApiRequest request, VariableResolver resolver) throws Exception {
        RequestBuildPolicy policy = RequestBuildPolicy.forRequest(request);
        BuildContext ctx = buildHeadersAndBody(request, resolver, policy);
        List<Map.Entry<String, String>> effective = new ArrayList<>();
        for (String h : ctx.rawHeaders) {
            int colon = h.indexOf(':');
            if (colon > 0) {
                String key = h.substring(0, colon).trim();
                String lower = key.toLowerCase();
                if (policy.exactHttp() || (!lower.equals("content-length") && !lower.equals("transfer-encoding"))) {
                    effective.add(new AbstractMap.SimpleEntry<>(key, h.substring(colon + 1).trim()));
                }
            }
        }
        return effective;
    }

    private BuildContext buildHeadersAndBody(ApiRequest request, VariableResolver resolver, RequestBuildPolicy policy) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }
        if (request.url == null || request.url.trim().isEmpty()) {
            throw new IllegalArgumentException("Request URL cannot be null or empty");
        }

        // Resolve URL and parse target robustly
        String resolvedUrl = RequestParameterSupport.materializeRequestUrl(
                request.url,
                request.parameters,
                resolver);
        String method = request.method != null ? request.method.toUpperCase() : "GET";
        HttpUtils.ParsedTarget parsed = HttpUtils.parseTargetForRequest(resolvedUrl);

        String requestTarget = parsed.pathWithQuery;
        HeaderStore headers = new HeaderStore();
        byte[] body;

        if (policy.exactHttp()) {
            applyExplicitHeaders(request, headers, resolver, true);
            applyParameterHeadersAndCookies(request, headers, resolver, true);
            body = buildBody(request.body, headers, request.name, resolver, false, false);
        } else {
            applyExplicitHeaders(request, headers, resolver, false);
            applyParameterHeadersAndCookies(request, headers, resolver, false);

            if (policy.shouldApplyDefaultHeaders(request)) {
                if (!policy.isSuppressed(request, "accept")) {
                    headers.putDefault("Accept", "application/json, text/plain, */*");
                }
                if (!policy.isSuppressed(request, "user-agent")) {
                    headers.putDefault("User-Agent", "BurpExtensionRuntime");
                }
                if (!policy.isSuppressed(request, "cache-control")) {
                    headers.putDefault("Cache-Control", "no-cache");
                }
            }

            if (policy.shouldApplyAuthentication(request)) {
                requestTarget = applyAuthentication(request, headers, requestTarget, resolver, policy);
            }

            String hostValue = HttpUtils.buildHostWithPort(parsed.host, parsed.port, parsed.useHttps);
            headers.putComputed("Host", hostValue);

            if (policy.shouldSynthesizeBodyContentType(request)) {
                body = maybeBuildOAuth2TokenBody(request, method, resolvedUrl, headers, resolver, policy);
                if (body == null) {
                    body = buildBody(request.body, headers, request.name, resolver, true, !policy.isSuppressed(request, "content-type"));
                }
            } else if (request.body != null
                    && "formdata".equals(request.body.mode)
                    && !policy.isSuppressed(request, "content-type")
                    && (!policy.manualPreserve() || headers.has("Content-Type"))) {
                body = buildBody(request.body, headers, request.name, resolver, true, true);
            } else {
                body = buildBody(request.body, headers, request.name, resolver, false, false);
            }
        }

        // Insert request line at index 0
        List<String> rawHeaders = new ArrayList<>();
        rawHeaders.add(method + " " + requestTarget + " "
                + effectiveHttpVersion(request, policy));
        for (Map.Entry<String, String> entry : headers.entries()) {
            rawHeaders.add(entry.getKey() + ": " + entry.getValue());
        }

        return new BuildContext(rawHeaders, body, method, resolvedUrl);
    }

    private static String effectiveHttpVersion(ApiRequest request,
                                               RequestBuildPolicy policy) {
        if (policy != null
                && policy.exactHttp()
                && request != null
                && request.exactHttpRequest != null
                && request.exactHttpRequest.httpVersion != null) {
            String candidate = request.exactHttpRequest.httpVersion.trim();
            if ("HTTP/1.0".equalsIgnoreCase(candidate)) {
                return "HTTP/1.0";
            }
            if ("HTTP/1.1".equalsIgnoreCase(candidate)) {
                return "HTTP/1.1";
            }
        }
        return "HTTP/1.1";
    }

    private static final class BuildContext {
        final List<String> rawHeaders;
        final byte[] body;
        final String method;
        final String resolvedUrl;

        BuildContext(List<String> rawHeaders, byte[] body, String method, String resolvedUrl) {
            this.rawHeaders = rawHeaders;
            this.body = body;
            this.method = method;
            this.resolvedUrl = resolvedUrl;
        }
    }

    /**
     * Scans built request bytes for unresolved {{variable}} tokens.
     * Returns the set of unresolved variable names (empty if none).
     */
    public static Set<String> findUnresolvedTokens(byte[] rawRequest) {
        Set<String> unresolved = new LinkedHashSet<>();
        if (rawRequest == null || rawRequest.length == 0) return unresolved;
        String text = new String(rawRequest, StandardCharsets.UTF_8);
        Pattern p = Pattern.compile("\\{\\{([^}|]+)(?:\\|([^}]+))?\\}\\}");
        Matcher m = p.matcher(text);
        while (m.find()) {
            String variableName = m.group(1) != null ? m.group(1).trim() : "";
            String defaultValue = m.group(2);
            if (!variableName.isEmpty() && defaultValue == null) {
                unresolved.add(variableName);
            }
        }
        return unresolved;
    }

    /**
     * Ordered header store. Authored headers append and preserve duplicates;
     * defaults fill only absent names; computed headers replace same-name rows.
     */
    private static class HeaderStore {
        private final List<Map.Entry<String, String>> headers = new ArrayList<>();

        /** Adds only if absent (lowest precedence). */
        void putDefault(String key, String value) {
            String normalizedKey = normalizeHeaderName(key);
            if (normalizedKey == null) {
                return;
            }
            if (!has(normalizedKey)) {
                append(normalizedKey, value);
            }
        }

        /** Appends an authored header preserving duplicates and insertion order. */
        void addAuthored(String key, String value) {
            String normalizedKey = normalizeHeaderName(key);
            if (normalizedKey == null) {
                return;
            }
            append(normalizedKey, value);
        }

        /** Overrides any existing value (highest precedence for computed headers). */
        void putComputed(String key, String value) {
            String normalizedKey = normalizeHeaderName(key);
            if (normalizedKey == null) {
                return;
            }
            removeAll(normalizedKey);
            append(normalizedKey, value);
        }

        void appendComputed(String key, String value) {
            String normalizedKey = normalizeHeaderName(key);
            if (normalizedKey == null) {
                return;
            }
            append(normalizedKey, value);
        }

        /** Appends generated cookie auth without collapsing authored Cookie rows. */
        void appendGeneratedCookie(String cookieValue) {
            appendComputed("Cookie", cookieValue);
        }

        String get(String key) {
            if (key == null) {
                return null;
            }
            for (Map.Entry<String, String> e : headers) {
                if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
            }
            return null;
        }

        boolean has(String key) {
            if (key == null) {
                return false;
            }
            for (Map.Entry<String, String> e : headers) {
                if (e.getKey().equalsIgnoreCase(key)) {
                    return true;
                }
            }
            return false;
        }

        void removeAll(String key) {
            if (key == null) {
                return;
            }
            headers.removeIf(e -> e.getKey().equalsIgnoreCase(key));
        }

        Collection<Map.Entry<String, String>> entries() {
            return new ArrayList<>(headers);
        }

        private void append(String key, String value) {
            String normalizedKey = normalizeHeaderName(key);
            if (normalizedKey == null) {
                return;
            }
            headers.add(new AbstractMap.SimpleEntry<>(normalizedKey, normalizeHeaderValue(value)));
        }

        private static String normalizeHeaderName(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.replace("\r", " ").replace("\n", " ").trim();
            return normalized.isEmpty() ? null : normalized;
        }

        private static String normalizeHeaderValue(String value) {
            if (value == null) {
                return null;
            }
            return value.replace("\r", " ").replace("\n", " ");
        }
    }

    private void applyExplicitHeaders(ApiRequest request, HeaderStore headers, VariableResolver resolver, boolean exactHttp) {
        if (request.headers == null) {
            return;
        }
        Set<String> connectionNominated = exactHttp ? Collections.emptySet() : collectConnectionNominatedHeaders(request, resolver);
        for (ApiRequest.Header header : request.headers) {
            if (header == null || header.disabled || header.key == null || header.value == null) {
                continue;
            }
            String key = resolve(resolver, header.key);
            String value = resolve(resolver, header.value);
            if (key != null) {
                String trimmedKey = key.trim();
                String lower = trimmedKey.toLowerCase(Locale.ROOT);
                if (exactHttp || (!SAFE_FORBIDDEN_AUTHORED_HEADER_NAMES.contains(lower)
                        && !connectionNominated.contains(lower)
                        && !"postman-token".equals(lower))) {
                    headers.addAuthored(trimmedKey, value);
                }
            }
        }
    }

    private void applyParameterHeadersAndCookies(ApiRequest request,
                                                 HeaderStore headers,
                                                 VariableResolver resolver,
                                                 boolean exactHttp) {
        if (request == null || request.parameters == null) {
            return;
        }
        Set<String> connectionNominated = exactHttp
                ? Collections.emptySet()
                : collectConnectionNominatedHeaders(request, resolver);
        for (ApiRequest.Parameter parameter : request.parameters) {
            if (!RequestParameterSupport.isLocation(parameter, "header")
                    || parameter.disabled
                    || parameter.key == null
                    || parameter.key.isBlank()) {
                continue;
            }
            String key = resolve(resolver, parameter.key);
            String value = parameter.valuePresent
                    ? RequestParameterSupport.serializeHeaderValue(parameter, resolver)
                    : "";
            if (isAllowedAuthoredHeader(key, exactHttp, connectionNominated)) {
                headers.addAuthored(key.trim(), value);
            }
        }

        List<String> cookies = new ArrayList<>();
        for (ApiRequest.Parameter parameter : request.parameters) {
            if (!RequestParameterSupport.isLocation(parameter, "cookie")
                    || parameter.disabled
                    || parameter.key == null
                    || parameter.key.isBlank()) {
                continue;
            }
            String key = resolve(resolver, parameter.key);
            if (key == null || key.isBlank()) {
                continue;
            }
            cookies.addAll(RequestParameterSupport.serializeCookieParts(parameter, resolver));
        }
        if (!cookies.isEmpty() && isAllowedAuthoredHeader("Cookie", exactHttp, connectionNominated)) {
            headers.addAuthored("Cookie", String.join("; ", cookies));
        }
    }

    private boolean isAllowedAuthoredHeader(String key,
                                            boolean exactHttp,
                                            Set<String> connectionNominated) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lower = key.trim().toLowerCase(Locale.ROOT);
        return exactHttp || (!SAFE_FORBIDDEN_AUTHORED_HEADER_NAMES.contains(lower)
                && !connectionNominated.contains(lower)
                && !"postman-token".equals(lower));
    }

    private Set<String> collectConnectionNominatedHeaders(ApiRequest request, VariableResolver resolver) {
        Set<String> nominated = new LinkedHashSet<>();
        if (request == null) {
            return nominated;
        }
        if (request.headers != null) {
            for (ApiRequest.Header header : request.headers) {
                if (header == null || header.disabled || header.key == null || header.value == null) {
                    continue;
                }
                collectConnectionNominations(
                        resolve(resolver, header.key), resolve(resolver, header.value), nominated);
            }
        }
        if (request.parameters != null) {
            for (ApiRequest.Parameter parameter : request.parameters) {
                if (!RequestParameterSupport.isLocation(parameter, "header")
                        || parameter.disabled
                        || parameter.key == null) {
                    continue;
                }
                String value = parameter.valuePresent
                        ? RequestParameterSupport.serializeHeaderValue(parameter, resolver)
                        : "";
                collectConnectionNominations(resolve(resolver, parameter.key), value, nominated);
            }
        }
        return nominated;
    }

    private void collectConnectionNominations(String key, String value, Set<String> nominated) {
        if (key == null || !"connection".equalsIgnoreCase(key.trim()) || value == null) {
            return;
        }
        for (String token : value.split(",")) {
            String name = token != null ? token.trim().toLowerCase(Locale.ROOT) : "";
            if (!name.isEmpty() && !"connection".equals(name)) {
                nominated.add(name);
            }
        }
    }

    /**
     * Applies authentication to headers and optionally modifies the request target
     * (e.g., appending API key to query string). Returns the potentially-modified target.
     *
     * Uses putDefault() for Authorization/Cookie so explicit request-level headers win.
     */
    private String applyAuthentication(ApiRequest request, HeaderStore headers, String requestTarget, VariableResolver resolver, RequestBuildPolicy policy) {
        ApiRequest.Auth auth = request != null ? request.auth : null;
        if (auth == null || auth.type == null) return requestTarget;

        switch (auth.type.toLowerCase()) {
            case "bearer":
                if (!headers.has("Authorization") && !policy.isSuppressed(request, "authorization")) {
                    String token = auth.properties.getOrDefault("token", auth.properties.get("value"));
                    if (token != null) {
                        String prefix = auth.properties.getOrDefault("prefix", "Bearer");
                        headers.putDefault("Authorization", (resolver != null ? resolver.resolve(prefix) : prefix) + " " + (resolver != null ? resolver.resolve(token) : token));
                    }
                }
                break;
            case "basic":
                if (!headers.has("Authorization") && !policy.isSuppressed(request, "authorization")) {
                    String username = auth.properties.getOrDefault("username", auth.properties.get("user"));
                    String password = auth.properties.getOrDefault("password", auth.properties.get("pass"));
                    if (username != null || password != null) {
                        String credentials = (username != null ? (resolver != null ? resolver.resolve(username) : username) : "") + ":" +
                                (password != null ? (resolver != null ? resolver.resolve(password) : password) : "");
                        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                        headers.putDefault("Authorization", "Basic " + encoded);
                    }
                }
                break;
            case "apikey":
                String keyName = auth.properties.getOrDefault("key", auth.properties.get("keyname"));
                String keyValue = auth.properties.getOrDefault("value", auth.properties.get("keyvalue"));
                String in = auth.properties.getOrDefault("in", "header");
                if (keyName != null && keyValue != null) {
                    if ("query".equalsIgnoreCase(in)) {
                        String param = URLEncoder.encode(resolve(resolver, keyName), StandardCharsets.UTF_8)
                                + "=" + URLEncoder.encode(resolve(resolver, keyValue), StandardCharsets.UTF_8);
                        requestTarget = requestTarget.contains("?")
                                ? requestTarget + "&" + param
                                : requestTarget + "?" + param;
                    } else if ("cookie".equalsIgnoreCase(in)) {
                        String cookieValue = resolve(resolver, keyName) + "=" + resolve(resolver, keyValue);
                        headers.appendGeneratedCookie(cookieValue);
                    } else {
                        String resolvedKeyName = resolve(resolver, keyName);
                        String resolvedKeyValue = resolve(resolver, keyValue);
                        if (!headers.has(resolvedKeyName) && !policy.isSuppressed(request, resolvedKeyName)) {
                            headers.putDefault(resolvedKeyName, resolvedKeyValue);
                        }
                    }
                }
                break;
            case "cookie":
                String cookieValue = auth.properties.get("value");
                if (cookieValue != null) {
                    String resolvedCookie = resolver != null ? resolver.resolve(cookieValue) : cookieValue;
                    headers.appendGeneratedCookie(resolvedCookie);
                }
                break;
            case "oauth2":
                if (!headers.has("Authorization") && !policy.isSuppressed(request, "authorization")) {
                    String accessToken = auth.properties.get("accessToken");
                    if (accessToken != null && !accessToken.isBlank()) {
                        accessToken = resolver != null ? resolver.resolve(accessToken) : accessToken;
                    } else if (resolver != null && resolver.getVariables() != null) {
                        accessToken = resolver.getVariables().get("oauth2_access_token");
                    }
                    if (accessToken != null && !accessToken.isEmpty()) {
                        headers.putDefault("Authorization", "Bearer " + (resolver != null ? resolver.resolve(accessToken) : accessToken));
                    }
                }
                break;
        }
        return requestTarget;
    }

    private byte[] buildBody(ApiRequest.Body body, HeaderStore hs, String requestName, VariableResolver resolver, boolean synthesizeHeaders, boolean allowContentTypeHeader) throws Exception {
        if (body == null || body.mode == null || "none".equals(body.mode)) {
            return new byte[0];
        }

        byte[] result;
        switch (body.mode) {
            case "raw":
                if (body.raw != null) {
                    String resolved = resolver != null ? resolver.resolve(body.raw) : body.raw;
                    if (synthesizeHeaders && allowContentTypeHeader) {
                        String ct = body.contentType;
                        if (ct == null || ct.isBlank()) {
                            String trimmed = resolved.trim();
                            ct = (trimmed.startsWith("{") || trimmed.startsWith("[")) ? "application/json" : "text/plain";
                        }
                        enforceContentType(hs, ct, requestName);
                    }
                    result = resolved.getBytes(StandardCharsets.UTF_8);
                } else {
                    result = new byte[0];
                }
                break;

            case "graphql":
                if (body.graphql != null) {
                    result = buildGraphQLBody(body.graphql, hs, resolver, synthesizeHeaders && allowContentTypeHeader);
                    if (synthesizeHeaders && allowContentTypeHeader) {
                        enforceContentType(hs, "application/json", requestName);
                    }
                } else {
                    result = new byte[0];
                }
                break;

            case "urlencoded":
                if (body.urlencoded != null) {
                    List<String> params = new ArrayList<>();
                    for (ApiRequest.Body.FormField param : body.urlencoded) {
                        if (param == null || param.disabled || param.key == null) {
                            continue;
                        }
                        params.addAll(RequestParameterSupport.serializeFormPairs(
                                param.key, param.value, param.type, param.style,
                                param.explode, param.allowReserved, resolver));
                    }
                    if (synthesizeHeaders && allowContentTypeHeader) {
                        enforceContentType(hs, "application/x-www-form-urlencoded", requestName);
                    }
                    result = String.join("&", params).getBytes(StandardCharsets.UTF_8);
                } else {
                    result = new byte[0];
                }
                break;

            case "file":
                result = buildFileBody(body, resolver);
                if (synthesizeHeaders && allowContentTypeHeader) {
                    String contentType = body.contentType;
                    if (contentType == null || contentType.isBlank()) {
                        String rawPath = body.filePath != null && !body.filePath.isBlank() ? body.filePath : body.raw;
                        File file = rawPath != null ? new File(resolve(resolver, rawPath)) : null;
                        contentType = file != null ? java.nio.file.Files.probeContentType(file.toPath()) : null;
                    }
                    enforceContentType(hs, contentType != null ? contentType : "application/octet-stream", requestName);
                }
                break;

            case "formdata":
                if (body.formdata != null) {
                    String existingContentType = hs.get("Content-Type");
                    String boundary = resolveMultipartBoundary(existingContentType);
                    boundary = safeMultipartBoundary(boundary);
                    if (boundary == null || boundary.isBlank()) {
                        boundary = "----WebKitFormBoundary" + Long.toHexString(System.currentTimeMillis());
                    }
                    if (synthesizeHeaders && allowContentTypeHeader) {
                        String expected = "multipart/form-data; boundary=" + boundary;
                        if (hs.has("Content-Type")) {
                            String existing = hs.get("Content-Type");
                            if (existing != null && !existing.toLowerCase().startsWith("multipart/form-data")) {
                                if (api != null) {
                                    api.logging().logToOutput("[WARN] Request '" + requestName + "': replacing imported Content-Type '" + existing + "' with '" + expected + "' for body mode=formdata");
                                }
                            }
                        }
                        hs.putComputed("Content-Type", expected);
                    }
                    result = buildMultipartBody(body.formdata, boundary, resolver);
                } else {
                    result = new byte[0];
                }
                break;

            default:
                result = new byte[0];
        }

        return result;
    }

    private void enforceContentType(HeaderStore hs, String expectedType, String requestName) {
        if (hs.has("Content-Type")) {
            String existing = hs.get("Content-Type");
            if (existing != null && !existing.toLowerCase().startsWith(expectedType.toLowerCase())) {
                if (api != null) {
                    api.logging().logToOutput("[WARN] Request '" + requestName + "': replacing imported Content-Type '" + existing + "' with '" + expectedType + "' to match body mode");
                }
                hs.putComputed("Content-Type", expectedType);
            }
        } else {
            hs.putDefault("Content-Type", expectedType);
        }
    }

    private byte[] buildGraphQLBody(ApiRequest.Body.GraphQL graphql, HeaderStore headers, VariableResolver resolver, boolean synthesizeHeaders) {
        if (graphql == null) return new byte[0];
        try {
            com.google.gson.JsonObject body = new com.google.gson.JsonObject();
            if (graphql.query != null) {
                body.addProperty("query", resolver != null ? resolver.resolve(graphql.query) : graphql.query);
            }
            if (graphql.variables != null && !graphql.variables.trim().isEmpty()) {
                try {
                    com.google.gson.JsonElement vars = com.google.gson.JsonParser.parseString(resolver != null ? resolver.resolve(graphql.variables) : graphql.variables);
                    body.add("variables", vars);
                } catch (Exception e) {
                    body.add("variables", new com.google.gson.JsonObject());
                }
            } else {
                body.add("variables", new com.google.gson.JsonObject());
            }
            if (synthesizeHeaders && !headers.has("Content-Type")) {
                headers.putDefault("Content-Type", "application/json");
            }
            return new com.google.gson.GsonBuilder().serializeNulls().create().toJson(body).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private byte[] buildMultipartBody(List<ApiRequest.Body.FormField> formData, String boundary, VariableResolver resolver) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        for (ApiRequest.Body.FormField field : formData) {
            if (field == null || field.disabled || field.key == null) {
                continue;
            }
            String resolvedKey = resolver != null ? resolver.resolve(field.key) : field.key;
            String resolvedValue = field.value != null ? (resolver != null ? resolver.resolve(field.value) : field.value) : "";
            String uploadPath = field.fileUpload ? field.filePath : null;
            File file = uploadPath != null ? new File(resolver != null ? resolver.resolve(uploadPath) : uploadPath) : null;

            baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));

            if (file != null && file.exists() && file.isFile()) {
                // Security: Validate file path to prevent path traversal
                validateSafeFile(file, uploadPath);
                String filename = file.getName();
                writeMultipartHeader(baos, "Content-Disposition", "form-data; name=\""
                        + multipartQuoted(resolvedKey) + "\"; filename=\"" + multipartQuoted(filename) + "\"");
                writeMultipartHeader(baos, "Content-Type", safeMultipartContentType(field.contentType, file));
                baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
                baos.write(java.nio.file.Files.readAllBytes(file.toPath()));
                baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
            } else {
                writeMultipartHeader(baos, "Content-Disposition", "form-data; name=\""
                        + multipartQuoted(resolvedKey) + "\"");
                if (field.contentType != null) {
                    writeMultipartHeader(baos, "Content-Type", safeMultipartContentType(field.contentType, null));
                }
                baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
                baos.write(resolvedValue.getBytes(StandardCharsets.UTF_8));
                baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        baos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return baos.toByteArray();
    }

    private static void writeMultipartHeader(ByteArrayOutputStream output,
                                             String name,
                                             String value) throws java.io.IOException {
        output.write((name + ": " + stripMultipartControls(value) + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String multipartQuoted(String value) {
        return stripMultipartControls(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String stripMultipartControls(String value) {
        if (value == null) return "";
        StringBuilder safe = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) continue;
            safe.append(c);
        }
        return safe.toString();
    }

    private static String safeMultipartContentType(String imported, File file) {
        String candidate = imported != null ? imported.trim() : null;
        if (isValidMultipartContentType(candidate)) return candidate;
        if (file != null) {
            try {
                String probed = java.nio.file.Files.probeContentType(file.toPath());
                if (isValidMultipartContentType(probed)) return probed;
            } catch (Exception ignored) {
                // Fall through to a non-disclosing fixed default.
            }
        }
        return "application/octet-stream";
    }

    private static boolean isValidMultipartContentType(String value) {
        if (value == null || value.isBlank() || !value.equals(stripMultipartControls(value))) return false;
        String[] parts = value.split(";", -1);
        if (!parts[0].trim().matches("[A-Za-z0-9!#$&^_.+\\-]+/[A-Za-z0-9!#$&^_.+\\-]+")) return false;
        for (int i = 1; i < parts.length; i++) {
            String parameter = parts[i].trim();
            int equals = parameter.indexOf('=');
            if (equals <= 0 || !parameter.substring(0, equals).trim().matches("[A-Za-z0-9!#$&^_.+\\-]+")) return false;
            String parameterValue = parameter.substring(equals + 1).trim();
            if (parameterValue.isEmpty() || parameterValue.indexOf(':') >= 0) return false;
        }
        return true;
    }

    private static String safeMultipartBoundary(String boundary) {
        if (boundary == null || boundary.isBlank()) return null;
        String safe = stripMultipartControls(boundary).trim();
        return safe.matches("[A-Za-z0-9'()+_,./:=?\\-]{1,70}") ? safe : null;
    }

    private byte[] buildFileBody(ApiRequest.Body body, VariableResolver resolver) throws Exception {
        String authored = body.filePath != null && !body.filePath.isBlank() ? body.filePath : body.raw;
        String resolved = resolve(resolver, authored);
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalArgumentException("Binary request body requires a local file path");
        }
        File file = new File(resolved);
        validateSafeFile(file, authored);
        if (!file.exists()) throw new IllegalArgumentException("Binary request body file does not exist");
        if (!file.isFile()) throw new IllegalArgumentException("Binary request body path must be a regular file");
        return java.nio.file.Files.readAllBytes(file.toPath());
    }

    private void validateSafeFile(File file, String authoredPath) throws Exception {
        Path canonicalPath = file.getCanonicalFile().toPath();
        Path canonicalBase = new File(".").getCanonicalFile().toPath();
        Path canonicalUserHome = new File(System.getProperty("user.home")).getCanonicalFile().toPath();
        if (!canonicalPath.startsWith(canonicalBase) && !canonicalPath.startsWith(canonicalUserHome)) {
            throw new SecurityException("File path is outside the permitted upload roots");
        }
    }

    // Check for any header by name (case-insensitive) in a raw List
    /**
     * Detects whether the current request targets an OAuth2 token endpoint.
     * Primary: resolved URL matches resolved oauth2_token_url.
     * Fallback: auth.type == oauth2 AND URL path contains /token.
     */
    private boolean isOAuth2TokenRequest(String method, String resolvedUrl, Map<String, String> vars, ApiRequest request, VariableResolver resolver) {
        if (!"POST".equalsIgnoreCase(method)) return false;

        String tokenUrl = vars != null ? vars.get("oauth2_token_url") : null;
        if (tokenUrl != null && !tokenUrl.isBlank()) {
            String resolvedTokenUrl = resolver != null ? resolver.resolve(tokenUrl) : tokenUrl;
            String compareTokenUrl = stripFragment(resolvedTokenUrl).trim();
            String compareResolvedUrl = stripFragment(resolvedUrl).trim();
            return compareResolvedUrl.equals(compareTokenUrl);
        }

        // Secondary fallback: auth.type == oauth2 and URL path contains /token
        if (request != null && request.auth != null && "oauth2".equalsIgnoreCase(request.auth.type)) {
            String path = HttpUtils.extractPathFromUrl(resolvedUrl);
            return path != null && path.toLowerCase().contains("/token");
        }
        return false;
    }

    /**
     * Auto-builds an application/x-www-form-urlencoded OAuth2 token request body
     * when the request URL matches oauth2_token_url.
     * In strict mode (default), always overrides the imported body with a canonical
     * form body built from oauth2_* variables. In lenient mode, only builds when body is empty.
     * Supports client_credentials, password, refresh_token, and authorization_code grants.
     * Returns null when no auto-build is needed.
     */
    private byte[] maybeBuildOAuth2TokenBody(ApiRequest request, String method, String resolvedUrl, HeaderStore headers, VariableResolver resolver, RequestBuildPolicy policy) {
        Map<String, String> vars = resolver != null ? resolver.mutableVariables() : Collections.emptyMap();
        if (vars == null) return null;

        boolean isTokenReq = isOAuth2TokenRequest(method, resolvedUrl, vars, request, resolver);
        if (!isTokenReq) return null;

        boolean forceUrlEncoded = !"false".equalsIgnoreCase(vars.getOrDefault("oauth2_token_force_urlencoded", "true"));
        boolean allowMultipart = "true".equalsIgnoreCase(vars.getOrDefault("oauth2_token_allow_multipart", "false"));

        boolean shouldBuildBody;
        if (forceUrlEncoded && !allowMultipart) {
            // Strict mode: always override with canonical form body, ignoring imported body
            shouldBuildBody = true;
        } else {
            // Lenient mode: only build when imported body is empty
            shouldBuildBody = isBodyEmpty(request.body);
        }

        if (!shouldBuildBody) return null;

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
            if (!headers.has("Authorization") && !policy.isSuppressed(request, "authorization")) {
                String credentials = clientId + ":" + clientSecret;
                String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                headers.putComputed("Authorization", "Basic " + encoded);
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
        if (!policy.isSuppressed(request, "content-type")) {
            headers.putComputed("Content-Type", "application/x-www-form-urlencoded");
        }

        return String.join("&", params).getBytes(StandardCharsets.UTF_8);
    }

    private String resolveMultipartBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("boundary=([^;]+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
        if (!matcher.find()) {
            return null;
        }
        String boundary = matcher.group(1).trim();
        if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() >= 2) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }
        return boundary;
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
            case "file":
                return (body.filePath == null || body.filePath.isBlank())
                        && (body.raw == null || body.raw.isBlank());
            default:
                return true;
        }
    }

    private String resolve(VariableResolver resolver, String input) {
        return resolver != null ? resolver.resolve(input) : input;
    }
}
