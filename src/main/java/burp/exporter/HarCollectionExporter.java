package burp.exporter;

import burp.history.HistoryHeader;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.HistoryRawHttpMessageParser;
import burp.parser.VariableResolver;
import burp.utils.HarMetadataSupport;
import burp.utils.HttpUtils;
import burp.utils.RequestParameterSupport;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** HAR exporter with retained-entry fast path and safe canonical rebuilding. */
public final class HarCollectionExporter {
    private HarCollectionExporter() {
    }

    public static JsonObject build(ApiCollection collection,
                                   CollectionExportOptions options,
                                   List<String> warnings) {
        boolean resolve = options != null && options.resolveVariablesUsingActiveEnvironment;
        EnvironmentProfile activeEnvironment = options != null ? options.activeEnvironment : null;
        Map<String, String> exportOnly = options != null ? options.exportOnlyVariables : Map.of();

        JsonObject root = collection != null && collection.sourceMetadata != null
                ? HarMetadataSupport.parseObject(collection.sourceMetadata.get(HarMetadataSupport.ROOT_FIELDS))
                : new JsonObject();
        JsonObject log = collection != null && collection.sourceMetadata != null
                ? HarMetadataSupport.parseObject(collection.sourceMetadata.get(HarMetadataSupport.LOG_FIELDS))
                : new JsonObject();
        if (!log.has("version")) {
            log.addProperty("version", "1.2");
        }
        if (!log.has("creator")) {
            JsonObject creator = new JsonObject();
            creator.addProperty("name", "API Workbench for Burp");
            creator.addProperty("version", "2.0.0");
            log.add("creator", creator);
        }

        JsonArray entries = new JsonArray();
        if (collection != null && collection.requests != null) {
            int index = 0;
            for (ApiRequest request : collection.requests) {
                if (request == null) {
                    continue;
                }
                VariableResolver resolver = CollectionExportSupport.buildResolver(
                        collection, request, activeEnvironment, exportOnly);
                entries.add(entryFor(request, resolver, resolve, index++, warnings));
            }
        }
        log.add("entries", entries);
        root.add("log", log);
        return root;
    }

    public static void write(ApiCollection collection,
                             CollectionExportOptions options,
                             Writer writer,
                             List<String> warnings) throws IOException {
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        writer.write(gson.toJson(build(collection, options, warnings)));
    }

    private static JsonObject entryFor(ApiRequest request,
                                       VariableResolver resolver,
                                       boolean resolve,
                                       int index,
                                       List<String> warnings) {
        JsonObject original = originalEntry(request);
        if (!resolve && !original.entrySet().isEmpty() && retainedFingerprintMatches(request)) {
            return original;
        }

        JsonObject entry = original;
        entry.add("request", requestToHar(request, resolver, resolve, index, warnings));
        if (entry.has("response")) {
            ExportWarningSupport.add(warnings, "HAR request #" + (index + 1)
                    + ": retained response was replaced because the request changed or was resolved");
        }
        entry.add("response", responsePlaceholder());
        if (!entry.has("startedDateTime")) {
            entry.addProperty("startedDateTime", Instant.EPOCH.toString());
        }
        if (!entry.has("time")) {
            entry.addProperty("time", 0);
        }
        if (!entry.has("cache")) {
            entry.add("cache", new JsonObject());
        }
        if (!entry.has("timings")) {
            entry.add("timings", timings());
        }
        return entry;
    }

    private static JsonObject requestToHar(ApiRequest request,
                                           VariableResolver resolver,
                                           boolean resolve,
                                           int index,
                                           List<String> warnings) {
        boolean importedOriginal = !originalEntry(request).entrySet().isEmpty();
        if (!resolve
                && request != null
                && request.exactHttpRequest != null
                && request.exactHttpRequest.pristine
                && request.exactHttpRequest.rawRequestBytes != null
                && (!importedOriginal || retainedFingerprintMatches(request))) {
            return requestFromExactSnapshot(request, resolver, index, warnings);
        }
        return requestFromModel(request, resolver, resolve, index, warnings);
    }

    private static JsonObject requestFromExactSnapshot(ApiRequest request,
                                                       VariableResolver resolver,
                                                       int index,
                                                       List<String> warnings) {
        HistoryRawHttpMessageParser.ParsedRawHttpMessage parsed =
                HistoryRawHttpMessageParser.parseRequest(
                        request.exactHttpRequest.rawRequestBytes, null);
        if (!parsed.isTrustedRequest()) {
            return requestFromModel(request, resolver, false, index, warnings);
        }

        String url = stripFragment(RequestParameterSupport.materializeRequestUrl(
                request.url, request.parameters, null));
        JsonArray headers = new JsonArray();
        for (HistoryHeader header : parsed.headers()) {
            headers.add(harNameValue(header.name, header.value));
        }

        JsonObject out = originalRequest(request);
        out.addProperty("method", parsed.method());
        out.addProperty("url", url);
        out.addProperty("httpVersion", parsed.httpVersion());
        out.add("headers", headers);
        out.add("queryString", queryStringFromUrl(url, request));
        out.add("cookies", cookiesFromHeaders(headers));
        if (parsed.bodyBytes().length > 0 || request.body != null) {
            String contentType = contentTypeFromHeaders(headers);
            JsonObject postData = postDataFromRaw(parsed.bodyBytes(), contentType,
                    request.exactHttpRequest.binaryBody, index, warnings);
            if (postData != null) {
                out.add("postData", postData);
            } else {
                out.remove("postData");
            }
        } else {
            out.remove("postData");
        }
        out.addProperty("headersSize", parsed.bodyOffset() >= 0
                ? parsed.bodyOffset()
                : calculateHeadersSize(parsed.method(), url, parsed.httpVersion(), headers));
        out.addProperty("bodySize", parsed.bodyBytes().length);
        return out;
    }

    private static JsonObject requestFromModel(ApiRequest request,
                                               VariableResolver resolver,
                                               boolean resolve,
                                               int index,
                                               List<String> warnings) {
        JsonObject out = originalRequest(request);
        VariableResolver activeResolver = resolve ? resolver : null;
        String url = stripFragment(RequestParameterSupport.materializeRequestUrl(
                request != null ? request.url : "",
                request != null ? request.parameters : List.of(), activeResolver));
        String method = resolve(request != null ? request.method : null, resolver, resolve);
        if (method == null || method.isBlank()) {
            method = "GET";
        }
        String httpVersion = effectiveHttpVersion(request);
        JsonArray headers = headersFromModel(request, resolver, resolve, index, warnings);
        ensureHostHeader(headers, url);

        out.addProperty("method", method);
        out.addProperty("url", url);
        out.addProperty("httpVersion", httpVersion);
        out.add("headers", headers);
        out.add("queryString", queryStringFromUrl(url, request));
        out.add("cookies", cookiesFromHeaders(headers));
        JsonObject postData = postDataFromModel(request, resolver, resolve, index, warnings);
        if (postData != null) {
            out.add("postData", postData);
        } else {
            out.remove("postData");
        }
        out.addProperty("headersSize", calculateHeadersSize(method, url, httpVersion, headers));
        out.addProperty("bodySize", bodySize(postData));
        return out;
    }

    private static JsonObject originalEntry(ApiRequest request) {
        if (request == null || request.sourceMetadata == null) {
            return new JsonObject();
        }
        return HarMetadataSupport.parseObject(
                request.sourceMetadata.get(HarMetadataSupport.ENTRY_ORIGINAL));
    }

    private static JsonObject originalRequest(ApiRequest request) {
        JsonObject entry = originalEntry(request);
        if (entry.has("request") && entry.get("request").isJsonObject()) {
            return entry.getAsJsonObject("request").deepCopy();
        }
        return new JsonObject();
    }

    private static boolean retainedFingerprintMatches(ApiRequest request) {
        if (request == null || request.sourceMetadata == null) {
            return false;
        }
        String retained = request.sourceMetadata.get(HarMetadataSupport.REQUEST_FINGERPRINT);
        return retained != null && retained.equals(request.computeSemanticFingerprint());
    }

    private static JsonArray headersFromModel(ApiRequest request,
                                              VariableResolver resolver,
                                              boolean resolve,
                                              int index,
                                              List<String> warnings) {
        JsonArray headers = new JsonArray();
        if (request != null && request.headers != null) {
            for (ApiRequest.Header header : request.headers) {
                if (header == null || header.disabled) {
                    continue;
                }
                String name = resolve(header.key, resolver, resolve);
                String value = resolve(header.value, resolver, resolve);
                if (!isSafeHeader(name, value)) {
                    addUnsafeHeaderWarning(warnings, index);
                    continue;
                }
                headers.add(harNameValue(name, value != null ? value : ""));
            }
        }
        appendParameterHeaders(request, headers, resolver, resolve, index, warnings);
        return headers;
    }

    private static void appendParameterHeaders(ApiRequest request,
                                               JsonArray headers,
                                               VariableResolver resolver,
                                               boolean resolve,
                                               int index,
                                               List<String> warnings) {
        if (request == null || request.parameters == null) {
            return;
        }
        VariableResolver activeResolver = resolve ? resolver : null;
        for (ApiRequest.Parameter parameter : request.parameters) {
            if (parameter == null || parameter.disabled
                    || !RequestParameterSupport.isLocation(parameter, "header")) {
                continue;
            }
            String name = resolve(parameter.key, resolver, resolve);
            String value = parameter.valuePresent
                    ? RequestParameterSupport.serializeHeaderValue(parameter, activeResolver) : "";
            if (!isSafeHeader(name, value)) {
                addUnsafeHeaderWarning(warnings, index);
                continue;
            }
            headers.add(harNameValue(name, value));
        }
        List<String> cookieParts = new ArrayList<>();
        for (ApiRequest.Parameter parameter : request.parameters) {
            if (parameter == null || parameter.disabled
                    || !RequestParameterSupport.isLocation(parameter, "cookie")
                    || parameter.key == null || parameter.key.isBlank()) {
                continue;
            }
            cookieParts.addAll(RequestParameterSupport.serializeCookieParts(parameter, activeResolver));
        }
        if (!cookieParts.isEmpty()) {
            String joinedValue = String.join("; ", cookieParts);
            if (isSafeHeader("Cookie", joinedValue)) {
                headers.add(harNameValue("Cookie", joinedValue));
            } else {
                addUnsafeHeaderWarning(warnings, index);
            }
        }
    }

    private static void ensureHostHeader(JsonArray headers, String url) {
        if (hasHeader(headers, "Host") || url == null || url.isBlank()) {
            return;
        }
        try {
            HttpUtils.ParsedTarget parsed = HttpUtils.parseTargetForRequest(url);
            headers.add(harNameValue("Host",
                    HttpUtils.buildHostWithPort(parsed.host, parsed.port, parsed.useHttps)));
        } catch (RuntimeException ignored) {
            // A model URL without a host remains exportable without synthesized Host.
        }
    }

    private static JsonArray cookiesFromHeaders(JsonArray headers) {
        JsonArray cookies = new JsonArray();
        if (headers == null) {
            return cookies;
        }
        for (JsonElement element : headers) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject header = element.getAsJsonObject();
            String name = header.has("name") ? header.get("name").getAsString() : "";
            if (!"cookie".equalsIgnoreCase(name)) {
                continue;
            }
            String value = header.has("value") ? header.get("value").getAsString() : "";
            for (String part : value.split(";", -1)) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int equals = trimmed.indexOf('=');
                JsonObject cookie = new JsonObject();
                cookie.addProperty("name", equals >= 0 ? trimmed.substring(0, equals).trim() : trimmed);
                if (equals >= 0) {
                    cookie.addProperty("value", trimmed.substring(equals + 1).trim());
                }
                cookies.add(cookie);
            }
        }
        return cookies;
    }

    private static JsonArray queryStringFromUrl(String url,
                                                ApiRequest request) {
        JsonArray rows = new JsonArray();
        List<ApiRequest.Parameter> parsed = RequestParameterSupport.parseQueryParameters(url, "har:export.url");
        List<ApiRequest.Parameter> model = new ArrayList<>();
        if (request != null && request.parameters != null) {
            for (ApiRequest.Parameter parameter : request.parameters) {
                if (parameter != null && parameter.isQuery() && !parameter.disabled) {
                    model.add(parameter);
                }
            }
        }
        for (int index = 0; index < parsed.size(); index++) {
            ApiRequest.Parameter parameter = parsed.get(index);
            JsonObject row = index < model.size() && model.get(index).sourceMetadata != null
                    ? HarMetadataSupport.parseObject(model.get(index).sourceMetadata.get(
                    HarMetadataSupport.QUERY_ROW_ORIGINAL)) : new JsonObject();
            row.addProperty("name", parameter.key != null ? parameter.key : "");
            if (parameter.valuePresent) {
                row.addProperty("value", parameter.value != null ? parameter.value : "");
            } else {
                row.remove("value");
            }
            ApiRequest.Parameter current = index < model.size() ? model.get(index) : null;
            if (current != null && current.description != null) {
                row.addProperty("comment", current.description);
            } else {
                row.remove("comment");
            }
            rows.add(row);
        }
        return rows;
    }

    private static JsonObject postDataFromModel(ApiRequest request,
                                                VariableResolver resolver,
                                                boolean resolve,
                                                int index,
                                                List<String> warnings) {
        ApiRequest.Body body = request != null ? request.body : null;
        if (body == null || body.mode == null || body.mode.isBlank()
                || "none".equalsIgnoreCase(body.mode)) {
            return null;
        }
        JsonObject out = body.sourceMetadata != null
                ? HarMetadataSupport.parseObject(body.sourceMetadata.get(HarMetadataSupport.POST_DATA_ORIGINAL))
                : new JsonObject();
        String mode = body.mode.toLowerCase(Locale.ROOT);
        switch (mode) {
            case "urlencoded" -> {
                out.addProperty("mimeType", body.contentType != null && !body.contentType.isBlank()
                        ? body.contentType : "application/x-www-form-urlencoded");
                out.add("params", formFieldsToHar(body.urlencoded, resolver, resolve, index, warnings));
                out.remove("text");
            }
            case "formdata" -> {
                out.addProperty("mimeType", body.contentType != null && !body.contentType.isBlank()
                        ? body.contentType : "multipart/form-data");
                out.add("params", formFieldsToHar(body.formdata, resolver, resolve, index, warnings));
                out.remove("text");
            }
            case "graphql" -> {
                out.addProperty("mimeType", body.contentType != null && !body.contentType.isBlank()
                        ? body.contentType : "application/json");
                String query = body.graphql != null ? resolve(body.graphql.query, resolver, resolve) : "";
                String variables = body.graphql != null ? resolve(body.graphql.variables, resolver, resolve) : "";
                com.google.gson.JsonObject graph = new com.google.gson.JsonObject();
                graph.addProperty("query", query != null ? query : "");
                try {
                    graph.add("variables", variables != null && !variables.isBlank()
                            ? com.google.gson.JsonParser.parseString(variables) : new JsonObject());
                } catch (RuntimeException ignored) {
                    graph.addProperty("variables", variables != null ? variables : "");
                }
                out.addProperty("text", graph.toString());
                out.remove("params");
            }
            case "file" -> {
                out.addProperty("mimeType", body.contentType != null && !body.contentType.isBlank()
                        ? body.contentType : "application/octet-stream");
                out.remove("text");
                out.remove("params");
                ExportWarningSupport.add(warnings, "HAR request #" + (index + 1)
                        + ": whole-body binary or file content was omitted because standard HAR text was unavailable");
            }
            default -> {
                out.addProperty("mimeType", body.contentType != null && !body.contentType.isBlank()
                        ? body.contentType : "text/plain");
                out.addProperty("text", resolve(body.raw, resolver, resolve) != null
                        ? resolve(body.raw, resolver, resolve) : "");
                out.remove("params");
            }
        }
        return out;
    }

    private static JsonArray formFieldsToHar(List<ApiRequest.Body.FormField> fields,
                                             VariableResolver resolver,
                                             boolean resolve,
                                             int index,
                                             List<String> warnings) {
        JsonArray params = new JsonArray();
        if (fields == null) {
            return params;
        }
        for (ApiRequest.Body.FormField field : fields) {
            if (field == null || field.disabled || field.key == null) {
                continue;
            }
            JsonObject row = field.sourceMetadata != null
                    ? HarMetadataSupport.parseObject(field.sourceMetadata.get(
                    HarMetadataSupport.POST_PARAM_ORIGINAL)) : new JsonObject();
            row.addProperty("name", resolve(field.key, resolver, resolve));
            if (field.value != null) {
                row.addProperty("value", resolve(field.value, resolver, resolve));
            } else {
                row.remove("value");
            }
            if (field.fileUpload || "file".equalsIgnoreCase(field.type)) {
                String fileName = retainedFileName(field);
                if ((fileName == null || fileName.isBlank()) && field.filePath != null) {
                    fileName = basename(resolve(field.filePath, resolver, resolve));
                }
                if (fileName != null && !fileName.isBlank()) {
                    row.addProperty("fileName", fileName);
                } else {
                    row.remove("fileName");
                }
            } else {
                row.remove("fileName");
            }
            if (field.contentType != null && !field.contentType.isBlank()) {
                row.addProperty("contentType", resolve(field.contentType, resolver, resolve));
            } else {
                row.remove("contentType");
            }
            if (field.description != null) {
                row.addProperty("comment", resolve(field.description, resolver, resolve));
            } else {
                row.remove("comment");
            }
            row.remove("filePath");
            params.add(row);
        }
        return params;
    }

    private static JsonObject postDataFromRaw(byte[] body,
                                              String contentType,
                                              boolean binary,
                                              int index,
                                              List<String> warnings) {
        JsonObject postData = new JsonObject();
        if (contentType != null && !contentType.isBlank()) {
            postData.addProperty("mimeType", contentType);
        } else {
            postData.addProperty("mimeType", "");
        }
        if (binary) {
            ExportWarningSupport.add(warnings, "HAR request #" + (index + 1)
                    + ": binary request body text was omitted");
        } else {
            postData.addProperty("text", new String(body != null ? body : new byte[0], StandardCharsets.UTF_8));
        }
        return postData;
    }

    private static int calculateHeadersSize(String method,
                                            String url,
                                            String httpVersion,
                                            JsonArray headers) {
        String target = "/";
        try {
            target = HttpUtils.parseTargetForRequest(url).pathWithQuery;
        } catch (RuntimeException ignored) {
            // Keep a deterministic safe request target for sizing malformed model URLs.
        }
        StringBuilder raw = new StringBuilder();
        raw.append(method != null ? method : "GET").append(' ').append(target).append(' ')
                .append(httpVersion != null ? httpVersion : "HTTP/1.1").append("\r\n");
        if (headers != null) {
            for (JsonElement element : headers) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject header = element.getAsJsonObject();
                raw.append(header.has("name") ? header.get("name").getAsString() : "")
                        .append(": ")
                        .append(header.has("value") ? header.get("value").getAsString() : "")
                        .append("\r\n");
            }
        }
        raw.append("\r\n");
        return raw.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private static int bodySize(JsonObject postData) {
        if (postData == null) {
            return 0;
        }
        if (postData.has("text") && postData.get("text").isJsonPrimitive()) {
            return postData.get("text").getAsString().getBytes(StandardCharsets.UTF_8).length;
        }
        return -1;
    }

    private static String effectiveHttpVersion(ApiRequest request) {
        String candidate = request != null && request.exactHttpRequest != null
                ? request.exactHttpRequest.httpVersion : null;
        if (candidate == null && request != null && request.sourceMetadata != null) {
            candidate = request.sourceMetadata.get(HarMetadataSupport.REQUEST_HTTP_VERSION);
        }
        if (candidate != null && "HTTP/1.0".equalsIgnoreCase(candidate.trim())) {
            return "HTTP/1.0";
        }
        if (candidate != null && "HTTP/1.1".equalsIgnoreCase(candidate.trim())) {
            return "HTTP/1.1";
        }
        return "HTTP/1.1";
    }

    private static String contentTypeFromHeaders(JsonArray headers) {
        if (headers != null) {
            for (JsonElement element : headers) {
                if (element != null && element.isJsonObject()) {
                    JsonObject header = element.getAsJsonObject();
                    if (header.has("name") && "content-type".equalsIgnoreCase(
                            header.get("name").getAsString())) {
                        return header.has("value") ? header.get("value").getAsString() : "";
                    }
                }
            }
        }
        return null;
    }

    private static String retainedFileName(ApiRequest.Body.FormField field) {
        if (field == null || field.sourceMetadata == null) {
            return null;
        }
        JsonObject retained = HarMetadataSupport.parseObject(
                field.sourceMetadata.get(HarMetadataSupport.POST_PARAM_ORIGINAL));
        if (retained.has("fileName") && !retained.get("fileName").isJsonNull()
                && retained.get("fileName").isJsonPrimitive()) {
            return basename(retained.get("fileName").getAsString());
        }
        return null;
    }

    private static String basename(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String resolve(String value,
                                  VariableResolver resolver,
                                  boolean enabled) {
        return CollectionExportSupport.resolve(value, resolver, enabled);
    }

    private static String stripFragment(String url) {
        if (url == null) {
            return "";
        }
        int hash = url.indexOf('#');
        return hash >= 0 ? url.substring(0, hash) : url;
    }

    private static JsonObject responsePlaceholder() {
        JsonObject response = new JsonObject();
        response.addProperty("status", 0);
        response.addProperty("statusText", "");
        response.addProperty("httpVersion", "HTTP/1.1");
        response.add("headers", new JsonArray());
        response.add("cookies", new JsonArray());
        JsonObject content = new JsonObject();
        content.addProperty("size", 0);
        content.addProperty("mimeType", "");
        content.addProperty("text", "");
        response.add("content", content);
        response.addProperty("redirectURL", "");
        response.addProperty("headersSize", -1);
        response.addProperty("bodySize", -1);
        response.add("timings", timings());
        return response;
    }

    private static JsonObject timings() {
        JsonObject timings = new JsonObject();
        timings.addProperty("send", 0);
        timings.addProperty("wait", 0);
        timings.addProperty("receive", 0);
        timings.addProperty("dns", 0);
        timings.addProperty("connect", 0);
        timings.addProperty("ssl", 0);
        timings.addProperty("blocked", 0);
        return timings;
    }

    private static boolean hasHeader(JsonArray headers, String name) {
        if (headers == null || name == null) {
            return false;
        }
        for (JsonElement element : headers) {
            if (element != null && element.isJsonObject()) {
                JsonObject header = element.getAsJsonObject();
                if (header.has("name") && name.equalsIgnoreCase(header.get("name").getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSafeHeader(String name,
                                        String value) {
        return name != null
                && !name.isBlank()
                && isHttpToken(name)
                && !containsLineBreak(name)
                && !containsLineBreak(value);
    }

    private static boolean isHttpToken(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            boolean token = (ch >= '0' && ch <= '9')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= 'a' && ch <= 'z')
                    || ch == '!' || ch == '#' || ch == '$' || ch == '%' || ch == '&'
                    || ch == '\'' || ch == '*' || ch == '+' || ch == '-' || ch == '.'
                    || ch == '^' || ch == '_' || ch == '`' || ch == '|' || ch == '~';
            if (!token) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsLineBreak(String value) {
        return value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0);
    }

    private static void addUnsafeHeaderWarning(List<String> warnings, int index) {
        ExportWarningSupport.add(warnings, "HAR request #" + (index + 1)
                + ": unsafe header row was omitted");
    }

    private static JsonObject harNameValue(String name, String value) {
        JsonObject object = new JsonObject();
        object.addProperty("name", name != null ? name : "");
        object.addProperty("value", value != null ? value : "");
        return object;
    }
}
