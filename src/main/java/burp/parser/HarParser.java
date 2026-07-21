package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.utils.HarMetadataSupport;
import burp.utils.HttpUtils;
import burp.utils.RequestParameterSupport;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parser for HTTP Archive collections with lossless source retention. */
public class HarParser implements CollectionParser {
    private static final String EXACT_SOURCE_CONTEXT =
            "HAR_RECONSTRUCTED_HTTP_1_X";

    private record HeaderParseResult(boolean complete) {}
    private record BodyParseResult(boolean exactBodyAvailable,
                                   byte[] exactBodyBytes) {}
    private record CookieValue(String name,
                               String value,
                               boolean valuePresent) {}

    @Override
    public boolean canParse(File file) {
        if (file == null || !file.getName().toLowerCase(Locale.ROOT).endsWith(".har")) {
            return false;
        }
        try {
            JsonObject root = readRoot(file);
            JsonObject log = object(root, "log");
            return log.has("entries") && log.get("entries").isJsonArray();
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public ApiCollection parse(File file) throws Exception {
        JsonObject root = readRoot(file);
        JsonObject log = object(root, "log");
        if (!log.has("entries") || !log.get("entries").isJsonArray()) {
            throw new IOException("HAR log entries are missing or invalid");
        }

        ApiCollection collection = new ApiCollection();
        collection.format = "har";
        collection.version = getString(log, "version", "1.2");
        String filename = file != null ? file.getName() : "HAR";
        collection.name = filename.toLowerCase(Locale.ROOT).endsWith(".har")
                ? filename.substring(0, filename.length() - 4) : filename;

        JsonObject rootFields = root.deepCopy();
        rootFields.remove("log");
        HarMetadataSupport.putCanonical(collection.sourceMetadata,
                HarMetadataSupport.ROOT_FIELDS, rootFields);
        JsonObject logFields = log.deepCopy();
        logFields.remove("entries");
        HarMetadataSupport.putCanonical(collection.sourceMetadata,
                HarMetadataSupport.LOG_FIELDS, logFields);

        JsonArray entries = log.getAsJsonArray("entries");
        for (int index = 0; index < entries.size(); index++) {
            JsonElement element = entries.get(index);
            if (element == null || !element.isJsonObject()) {
                collection.skippedRequestCount++;
                addWarning(collection, requestLabel(index, "", ""),
                        "entry was malformed and was skipped");
                continue;
            }
            try {
                ApiRequest request = parseEntry(element.getAsJsonObject(), index, collection);
                collection.requests.add(request);
                collection.importedRequestCount++;
            } catch (RuntimeException ignored) {
                collection.skippedRequestCount++;
                addWarning(collection, requestLabel(index, "", ""),
                        "entry was malformed and was skipped");
            }
        }
        return collection;
    }

    private JsonObject readRoot(File file) throws IOException {
        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
                text = text.substring(1);
            }
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new IOException("HAR root JSON must be an object");
            }
            return parsed.getAsJsonObject();
        } catch (IOException exception) {
            if ("HAR root JSON must be an object".equals(exception.getMessage())) {
                throw exception;
            }
            throw new IOException("Unable to read HAR JSON", exception);
        } catch (RuntimeException exception) {
            throw new IOException("HAR root JSON must be an object");
        }
    }

    private ApiRequest parseEntry(JsonObject entry,
                                  int entryIndex,
                                  ApiCollection collection) {
        JsonObject request = object(entry, "request");
        if (request.entrySet().isEmpty()) {
            throw new IllegalArgumentException("missing request");
        }
        String method = getString(request, "method", "GET");
        String originalUrl = getString(request, "url", "");
        if (originalUrl.isBlank()) {
            throw new IllegalArgumentException("missing URL");
        }
        String label = requestLabel(entryIndex, method, originalUrl);

        ApiRequest target = new ApiRequest();
        target.sourceCollection = collection.name;
        HarMetadataSupport.putCanonical(target.sourceMetadata,
                HarMetadataSupport.ENTRY_ORIGINAL, entry);
        String httpVersion = getString(request, "httpVersion", "");
        target.sourceMetadata.put(HarMetadataSupport.REQUEST_HTTP_VERSION, httpVersion);
        target.name = originalUrl;
        if (target.name.length() > 80) {
            target.name = target.name.substring(0, 80) + "...";
        }
        target.method = method;
        target.description = getString(request, "comment", null);

        String transportUrl = stripFragment(originalUrl);
        if (!transportUrl.equals(originalUrl)) {
            addWarning(collection, label, "URL fragment was removed");
        }
        target.url = transportUrl;

        HeaderParseResult headers = parseHeaders(request, target, collection, label);
        reconcileQuery(request, target, collection, label, transportUrl);
        reconcileCookies(request, target, collection, label);
        BodyParseResult body = parseBody(request, target, collection, label);

        ExactHttpRequestSnapshot snapshot = buildExactSnapshot(target, transportUrl,
                httpVersion, headers, body, collection, label);
        target.exactHttpRequest = snapshot;
        target.buildMode = snapshot != null
                ? ApiRequest.BuildMode.EXACT_HTTP
                : ApiRequest.BuildMode.AUTO_COMPATIBLE;
        String fingerprint = target.computeSemanticFingerprint();
        target.sourceMetadata.put(HarMetadataSupport.REQUEST_FINGERPRINT, fingerprint);
        if (snapshot != null) {
            snapshot.semanticFingerprint = fingerprint;
        }
        return target;
    }

    private HeaderParseResult parseHeaders(JsonObject request,
                                           ApiRequest target,
                                           ApiCollection collection,
                                           String label) {
        JsonArray rows = array(request, "headers");
        boolean complete = true;
        for (JsonElement element : rows) {
            if (element == null || !element.isJsonObject()) {
                complete = false;
                addWarning(collection, label,
                        "unsafe header row was retained only as source metadata");
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            String name = getString(row, "name", "");
            String value = getString(row, "value", "");
            if (!isHttpToken(name) || containsLineBreak(name) || containsLineBreak(value)) {
                complete = false;
                addWarning(collection, label,
                        "unsafe header row was retained only as source metadata");
                continue;
            }
            target.headers.add(new ApiRequest.Header(name, value));
        }
        return new HeaderParseResult(complete);
    }

    private void reconcileQuery(JsonObject request,
                                ApiRequest target,
                                ApiCollection collection,
                                String label,
                                String transportUrl) {
        List<ApiRequest.Parameter> urlRows = RequestParameterSupport.parseQueryParameters(
                transportUrl, "har:request.url");
        boolean urlHasQuery = transportUrl.indexOf('?') >= 0;
        boolean structuredArrayPresent = request.has("queryString")
                && request.get("queryString").isJsonArray();
        List<ApiRequest.Parameter> structured = structuredArrayPresent
                ? parseQueryRows(request.getAsJsonArray("queryString"), collection, label)
                : new ArrayList<>();
        boolean structuredRowsPresent = !structured.isEmpty();

        if (urlHasQuery && structuredArrayPresent && queryEquivalent(urlRows, structured)) {
            for (int index = 0; index < structured.size(); index++) {
                ApiRequest.Parameter fromUrl = urlRows.get(index);
                ApiRequest.Parameter row = structured.get(index);
                row.rawKey = fromUrl.rawKey;
                row.rawValue = fromUrl.rawValue;
                row.valuePresent = fromUrl.valuePresent;
            }
            target.parameters.addAll(structured);
        } else if (urlHasQuery) {
            target.parameters.addAll(urlRows);
            if (structuredArrayPresent) {
                addWarning(collection, label,
                        "queryString did not match the URL query; URL query was used");
            }
        } else if (structuredRowsPresent) {
            target.parameters.addAll(structured);
            addWarning(collection, label,
                    "queryString was present while the URL had no query");
        }
        target.url = stripFragment(RequestParameterSupport.stripQuery(transportUrl));
    }

    private List<ApiRequest.Parameter> parseQueryRows(JsonArray rows,
                                                      ApiCollection collection,
                                                      String label) {
        List<ApiRequest.Parameter> parameters = new ArrayList<>();
        for (JsonElement element : rows) {
            if (element == null || !element.isJsonObject()) {
                addWarning(collection, label, "malformed query row was ignored");
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            ApiRequest.Parameter parameter = new ApiRequest.Parameter(
                    "query", getString(row, "name", ""), getString(row, "value", ""));
            parameter.valuePresent = row.has("value") && !row.get("value").isJsonNull();
            parameter.source = "har:request.queryString";
            parameter.description = getString(row, "comment", null);
            HarMetadataSupport.putCanonical(parameter.sourceMetadata,
                    HarMetadataSupport.QUERY_ROW_ORIGINAL, row);
            parameters.add(parameter);
        }
        return parameters;
    }

    private void reconcileCookies(JsonObject request,
                                  ApiRequest target,
                                  ApiCollection collection,
                                  String label) {
        if (!request.has("cookies") || !request.get("cookies").isJsonArray()) {
            return;
        }
        JsonArray rows = request.getAsJsonArray("cookies");
        List<ApiRequest.Parameter> structured = parseCookieRows(rows, collection, label);
        List<CookieValue> explicit = parseCookieHeaders(target.headers);
        if (!explicit.isEmpty()) {
            if (!cookiesEquivalent(explicit, structured)) {
                addWarning(collection, label,
                        "structured cookies did not match explicit Cookie headers");
            }
            return;
        }
        target.parameters.addAll(structured);
    }

    private List<ApiRequest.Parameter> parseCookieRows(JsonArray rows,
                                                       ApiCollection collection,
                                                       String label) {
        List<ApiRequest.Parameter> parameters = new ArrayList<>();
        for (JsonElement element : rows) {
            if (element == null || !element.isJsonObject()) {
                addWarning(collection, label, "malformed cookie row was ignored");
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            ApiRequest.Parameter parameter = new ApiRequest.Parameter(
                    "cookie", getString(row, "name", ""), getString(row, "value", ""));
            parameter.valuePresent = row.has("value") && !row.get("value").isJsonNull();
            parameter.source = "har:request.cookies";
            parameter.description = getString(row, "comment", null);
            HarMetadataSupport.putCanonical(parameter.sourceMetadata,
                    HarMetadataSupport.COOKIE_ROW_ORIGINAL, row);
            parameters.add(parameter);
        }
        return parameters;
    }

    private BodyParseResult parseBody(JsonObject request,
                                      ApiRequest target,
                                      ApiCollection collection,
                                      String label) {
        int declaredBodySize = getInt(request, "bodySize", -1);
        JsonObject postData = object(request, "postData");
        if (postData.entrySet().isEmpty()) {
            if (declaredBodySize > 0) {
                addWarning(collection, label, "serialized body bytes were unavailable");
                return new BodyParseResult(false, new byte[0]);
            }
            return new BodyParseResult(true, new byte[0]);
        }

        ApiRequest.Body body = new ApiRequest.Body();
        body.source = "har:request.postData";
        body.contentType = getString(postData, "mimeType", contentTypeFromHeaders(target.headers));
        HarMetadataSupport.putCanonical(body.sourceMetadata,
                HarMetadataSupport.POST_DATA_ORIGINAL, postData);
        target.body = body;

        JsonArray params = array(postData, "params");
        boolean hasText = postData.has("text")
                && !postData.get("text").isJsonNull()
                && postData.get("text").isJsonPrimitive();
        if (hasText) {
            body.mode = "raw";
            body.raw = getString(postData, "text", "");
            if (!params.isEmpty()) {
                addWarning(collection, label,
                        "postData contained both text and params; text was used");
            }
            return new BodyParseResult(true, body.raw.getBytes(StandardCharsets.UTF_8));
        }

        if (!params.isEmpty()) {
            List<ApiRequest.Body.FormField> fields = parsePostParams(params, collection, label);
            String mime = body.contentType != null ? body.contentType.toLowerCase(Locale.ROOT) : "";
            if (mime.contains("application/x-www-form-urlencoded")) {
                body.mode = "urlencoded";
                body.urlencoded.addAll(fields);
            } else if (mime.contains("multipart/form-data")) {
                body.mode = "formdata";
                body.formdata.addAll(fields);
            } else {
                body.mode = "raw";
                body.raw = "";
                addWarning(collection, label,
                        "postData params could not be represented for the declared MIME type");
            }
            return new BodyParseResult(false, new byte[0]);
        }

        body.mode = "raw";
        body.raw = "";
        if (declaredBodySize > 0) {
            addWarning(collection, label, "serialized body bytes were unavailable");
            return new BodyParseResult(false, new byte[0]);
        }
        return new BodyParseResult(true, new byte[0]);
    }

    private List<ApiRequest.Body.FormField> parsePostParams(JsonArray rows,
                                                            ApiCollection collection,
                                                            String label) {
        List<ApiRequest.Body.FormField> fields = new ArrayList<>();
        for (JsonElement element : rows) {
            if (element == null || !element.isJsonObject()) {
                addWarning(collection, label, "malformed postData parameter was ignored");
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(
                    getString(row, "name", ""), getString(row, "value", null));
            field.source = "har:request.postData.params";
            field.description = getString(row, "comment", null);
            field.contentType = getString(row, "contentType", null);
            if (row.has("fileName") && !row.get("fileName").isJsonNull()) {
                field.type = "file";
                field.fileUpload = true;
            } else {
                field.type = "text";
            }
            HarMetadataSupport.putCanonical(field.sourceMetadata,
                    HarMetadataSupport.POST_PARAM_ORIGINAL, row);
            fields.add(field);
        }
        return fields;
    }

    private ExactHttpRequestSnapshot buildExactSnapshot(ApiRequest request,
                                                        String transportUrl,
                                                        String httpVersion,
                                                        HeaderParseResult headers,
                                                        BodyParseResult body,
                                                        ApiCollection collection,
                                                        String label) {
        String normalizedVersion = normalizeHttp1Version(httpVersion);
        if (normalizedVersion == null) {
            addWarning(collection, label,
                    "HTTP version cannot be represented as an exact textual request");
            return null;
        }
        if (!isHttpToken(request.method) || !headers.complete() || !body.exactBodyAvailable()) {
            return null;
        }
        byte[] bodyBytes = body.exactBodyBytes() != null ? body.exactBodyBytes() : new byte[0];
        if (hasCanonicalCookieParameters(request)) {
            addWarning(collection, label,
                    "structured cookies were not represented by explicit Cookie headers");
            return null;
        }
        if ("HTTP/1.1".equals(normalizedVersion) && !hasValidHttp11Host(request.headers)) {
            addWarning(collection, label,
                    "HTTP/1.1 Host header was missing or ambiguous");
            return null;
        }
        if (hasTransferEncoding(request.headers)) {
            addWarning(collection, label,
                    "Transfer-Encoding prevented exact body reconstruction");
            return null;
        }
        if (hasUnsupportedContentEncoding(request.headers, bodyBytes.length)) {
            addWarning(collection, label,
                    "Content-Encoding prevented exact body reconstruction");
            return null;
        }
        if (!hasConsistentContentLength(request.headers, bodyBytes.length)) {
            addWarning(collection, label,
                    "request body framing was missing or inconsistent");
            return null;
        }
        try {
            HttpUtils.ParsedTarget parsed = HttpUtils.parseTargetForRequest(transportUrl);
            String canonicalTransportUrl = RequestParameterSupport.materializeRequestUrl(
                    request.url, request.parameters, null);
            HttpUtils.ParsedTarget canonical = HttpUtils.parseTargetForRequest(canonicalTransportUrl);
            if (!parsed.pathWithQuery.equals(canonical.pathWithQuery)) {
                return null;
            }
            StringBuilder raw = new StringBuilder();
            raw.append(request.method).append(' ').append(parsed.pathWithQuery)
                    .append(' ').append(normalizedVersion).append("\r\n");
            for (ApiRequest.Header header : request.headers) {
                raw.append(header.key).append(": ").append(header.value != null ? header.value : "")
                        .append("\r\n");
            }
            raw.append("\r\n");
            byte[] head = raw.toString().getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[head.length + bodyBytes.length];
            System.arraycopy(head, 0, bytes, 0, head.length);
            System.arraycopy(bodyBytes, 0, bytes, head.length, bodyBytes.length);

            ExactHttpRequestSnapshot snapshot = new ExactHttpRequestSnapshot();
            snapshot.rawRequestBytes = bytes;
            snapshot.serviceHost = parsed.host;
            snapshot.servicePort = parsed.port;
            snapshot.secure = parsed.useHttps;
            snapshot.httpVersion = normalizedVersion;
            snapshot.pristine = true;
            snapshot.binaryBody = false;
            snapshot.sourceContext = EXACT_SOURCE_CONTEXT;
            return snapshot;
        } catch (RuntimeException ignored) {
            addWarning(collection, label,
                    "request URL could not be represented as an exact textual request");
            return null;
        }
    }

    private boolean hasCanonicalCookieParameters(ApiRequest request) {
        if (request == null || request.parameters == null) {
            return false;
        }
        for (ApiRequest.Parameter parameter : request.parameters) {
            if (parameter != null && !parameter.disabled
                    && RequestParameterSupport.isLocation(parameter, "cookie")) {
                return true;
            }
        }
        return false;
    }

    private static List<ApiRequest.Header> headersNamed(
            List<ApiRequest.Header> headers,
            String name) {
        List<ApiRequest.Header> matches = new ArrayList<>();
        if (headers == null || name == null) {
            return matches;
        }
        for (ApiRequest.Header header : headers) {
            if (header != null && name.equalsIgnoreCase(header.key)) {
                matches.add(header);
            }
        }
        return matches;
    }

    private static boolean hasValidHttp11Host(
            List<ApiRequest.Header> headers) {
        List<ApiRequest.Header> hosts = headersNamed(headers, "Host");
        return hosts.size() == 1
                && hosts.get(0).value != null
                && !hosts.get(0).value.trim().isEmpty();
    }

    private static boolean hasTransferEncoding(
            List<ApiRequest.Header> headers) {
        return !headersNamed(headers, "Transfer-Encoding").isEmpty();
    }

    private static boolean hasUnsupportedContentEncoding(
            List<ApiRequest.Header> headers,
            int bodyLength) {
        if (bodyLength == 0) {
            return false;
        }
        for (ApiRequest.Header header : headersNamed(headers, "Content-Encoding")) {
            String value = header.value != null ? header.value : "";
            for (String token : value.split(",", -1)) {
                String candidate = token.trim();
                if (!candidate.isEmpty() && !"identity".equalsIgnoreCase(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasConsistentContentLength(
            List<ApiRequest.Header> headers,
            int bodyLength) {
        List<ApiRequest.Header> lengths = headersNamed(headers, "Content-Length");
        if (lengths.size() > 1) {
            return false;
        }
        if (lengths.isEmpty()) {
            return bodyLength == 0;
        }
        Long parsed = parseContentLength(lengths.get(0).value);
        return parsed != null && parsed == bodyLength;
    }

    private static Long parseContentLength(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        for (int index = 0; index < candidate.length(); index++) {
            char ch = candidate.charAt(index);
            if (ch < '0' || ch > '9') {
                return null;
            }
        }
        try {
            return Long.parseLong(candidate);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean queryEquivalent(List<ApiRequest.Parameter> left,
                                           List<ApiRequest.Parameter> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            ApiRequest.Parameter a = left.get(index);
            ApiRequest.Parameter b = right.get(index);
            if (!java.util.Objects.equals(a.key, b.key)
                    || !java.util.Objects.equals(a.value, b.value)
                    || a.valuePresent != b.valuePresent) {
                return false;
            }
        }
        return true;
    }

    private static List<CookieValue> parseCookieHeaders(List<ApiRequest.Header> headers) {
        List<CookieValue> cookies = new ArrayList<>();
        if (headers == null) {
            return cookies;
        }
        for (ApiRequest.Header header : headers) {
            if (header == null || !"cookie".equalsIgnoreCase(header.key)) {
                continue;
            }
            String value = header.value != null ? header.value : "";
            for (String item : value.split(";", -1)) {
                String trimmed = item.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int equals = trimmed.indexOf('=');
                cookies.add(new CookieValue(
                        equals >= 0 ? trimmed.substring(0, equals).trim() : trimmed,
                        equals >= 0 ? trimmed.substring(equals + 1).trim() : "",
                        equals >= 0));
            }
        }
        return cookies;
    }

    private static boolean cookiesEquivalent(List<CookieValue> headers,
                                             List<ApiRequest.Parameter> structured) {
        if (headers.size() != structured.size()) {
            return false;
        }
        for (int index = 0; index < headers.size(); index++) {
            CookieValue cookie = headers.get(index);
            ApiRequest.Parameter parameter = structured.get(index);
            if (!java.util.Objects.equals(cookie.name(), parameter.key)
                    || !java.util.Objects.equals(cookie.value(), parameter.value)
                    || cookie.valuePresent() != parameter.valuePresent) {
                return false;
            }
        }
        return true;
    }

    private static String contentTypeFromHeaders(List<ApiRequest.Header> headers) {
        if (headers != null) {
            for (ApiRequest.Header header : headers) {
                if (header != null && "content-type".equalsIgnoreCase(header.key)) {
                    return header.value;
                }
            }
        }
        return null;
    }

    private static String stripFragment(String url) {
        if (url == null) {
            return "";
        }
        int hash = url.indexOf('#');
        return hash >= 0 ? url.substring(0, hash) : url;
    }

    private static String requestLabel(int index,
                                       String method,
                                       String url) {
        String path = "";
        try {
            URI uri = URI.create(stripFragment(url));
            path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
        } catch (RuntimeException ignored) {
            // Keep the label value-free when the path cannot be parsed safely.
        }
        return "entry #" + (index + 1) + " "
                + HarMetadataSupport.safeLabel(method) + " "
                + HarMetadataSupport.safeLabel(path);
    }

    private static String normalizeHttp1Version(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if ("HTTP/1.0".equalsIgnoreCase(candidate)) {
            return "HTTP/1.0";
        }
        if ("HTTP/1.1".equalsIgnoreCase(candidate)) {
            return "HTTP/1.1";
        }
        return null;
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

    private static JsonObject object(JsonObject parent, String key) {
        if (parent != null && parent.has(key) && parent.get(key).isJsonObject()) {
            return parent.getAsJsonObject(key);
        }
        return new JsonObject();
    }

    private static JsonArray array(JsonObject parent, String key) {
        if (parent != null && parent.has(key) && parent.get(key).isJsonArray()) {
            return parent.getAsJsonArray(key);
        }
        return new JsonArray();
    }

    private static String getString(JsonObject object,
                                    String key,
                                    String fallback) {
        try {
            if (object != null && object.has(key) && !object.get(key).isJsonNull()
                    && object.get(key).isJsonPrimitive()) {
                return object.get(key).getAsString();
            }
        } catch (RuntimeException ignored) {
            // Use the fixed fallback.
        }
        return fallback;
    }

    private static int getInt(JsonObject object,
                              String key,
                              int fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static void addWarning(ApiCollection collection,
                                   String label,
                                   String reason) {
        if (collection == null) {
            return;
        }
        collection.ensureDefaults();
        String warning = HarMetadataSupport.safeLabel(
                "HAR " + HarMetadataSupport.safeLabel(label) + ": " + reason);
        if (!warning.isBlank() && !collection.importWarnings.contains(warning)) {
            collection.importWarnings.add(warning);
        }
    }

    @Override
    public String getFormatName() {
        return "HAR";
    }

    @Override
    public String[] getSupportedExtensions() {
        return new String[]{"har"};
    }
}
