package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;
import burp.utils.HttpUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HarCollectionExporter {
    private HarCollectionExporter() {
    }

    public static JsonObject build(ApiCollection collection, CollectionExportOptions options, List<String> warnings) {
        boolean resolve = options != null && options.resolveVariablesUsingActiveEnvironment;
        EnvironmentProfile activeEnvironment = options != null ? options.activeEnvironment : null;
        Map<String, String> exportOnly = options != null ? options.exportOnlyVariables : Map.of();
        JsonObject root = new JsonObject();
        JsonObject log = new JsonObject();
        log.addProperty("version", "1.2");
        JsonObject creator = new JsonObject();
        creator.addProperty("name", "API Workbench for Burp");
        creator.addProperty("version", "2.0.0");
        log.add("creator", creator);

        JsonArray entries = new JsonArray();
        if (collection != null && collection.requests != null) {
            int index = 0;
            for (ApiRequest request : collection.requests) {
                if (request == null) {
                    continue;
                }
                VariableResolver resolver = CollectionExportSupport.buildResolver(collection, request, activeEnvironment, exportOnly);
                entries.add(entryFor(collection, request, resolver, resolve, index++));
            }
        }
        log.add("entries", entries);
        root.add("log", log);
        return root;
    }

    public static void write(ApiCollection collection, CollectionExportOptions options, Writer writer, List<String> warnings) throws IOException {
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        writer.write(gson.toJson(build(collection, options, warnings)));
    }

    private static JsonObject entryFor(ApiCollection collection,
                                       ApiRequest request,
                                       VariableResolver resolver,
                                       boolean resolve,
                                       int index) {
        JsonObject entry = new JsonObject();
        entry.addProperty("startedDateTime", Instant.EPOCH.toString());
        entry.addProperty("time", 0);
        entry.add("cache", new JsonObject());
        entry.add("timings", timings());
        entry.add("request", requestToHar(request, resolver, resolve));
        entry.add("response", responsePlaceholder());
        return entry;
    }

    private static JsonObject requestToHar(ApiRequest request, VariableResolver resolver, boolean resolve) {
        JsonObject req = new JsonObject();
        String resolvedUrl = CollectionExportSupport.resolve(request.url, resolver, resolve) != null
                ? CollectionExportSupport.resolve(request.url, resolver, resolve)
                : "";
        req.addProperty("method", CollectionExportSupport.resolve(request.method, resolver, resolve) != null ? CollectionExportSupport.resolve(request.method, resolver, resolve) : "GET");
        req.addProperty("url", resolvedUrl);
        req.addProperty("httpVersion", "HTTP/1.1");

        JsonArray headers = new JsonArray();
        JsonArray cookies = new JsonArray();
        for (ApiRequest.Header header : request.headers != null ? request.headers : List.<ApiRequest.Header>of()) {
            if (header == null || header.key == null || header.key.isBlank()) {
                continue;
            }
            String key = CollectionExportSupport.resolve(header.key, resolver, resolve);
            String value = CollectionExportSupport.resolve(header.value, resolver, resolve) != null ? CollectionExportSupport.resolve(header.value, resolver, resolve) : "";
            if (CollectionExportSupport.isTransportHeader(key)) {
                continue;
            }
            if ("cookie".equalsIgnoreCase(key)) {
                parseCookies(value, cookies);
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("name", key);
            obj.addProperty("value", value);
            headers.add(obj);
        }
        if (!resolvedUrl.isBlank()) {
            String host = hostHeaderFromUrl(resolvedUrl);
            boolean hasHost = false;
            for (int i = 0; i < headers.size(); i++) {
                if (headers.get(i) != null && headers.get(i).isJsonObject()) {
                    JsonObject headerObj = headers.get(i).getAsJsonObject();
                    if (headerObj.has("name") && "Host".equalsIgnoreCase(headerObj.get("name").getAsString())) {
                        hasHost = true;
                        break;
                    }
                }
            }
            if (host != null && !hasHost) {
                JsonObject hostHeader = new JsonObject();
                hostHeader.addProperty("name", "Host");
                hostHeader.addProperty("value", host);
                headers.add(hostHeader);
            }
        }
        req.add("headers", headers);
        req.add("cookies", cookies);
        req.add("queryString", queryStringFromUrl(resolvedUrl));

        JsonObject postData = postData(request.body, resolver, resolve);
        if (postData != null) {
            req.add("postData", postData);
        }
        req.addProperty("headersSize", -1);
        req.addProperty("bodySize", -1);
        return req;
    }

    private static JsonArray queryStringFromUrl(String url) {
        JsonArray out = new JsonArray();
        if (url == null || url.isBlank()) {
            return out;
        }
        int q = url.indexOf('?');
        if (q < 0 || q + 1 >= url.length()) {
            return out;
        }
        String query = url.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) {
            query = query.substring(0, hash);
        }
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            JsonObject item = new JsonObject();
            item.addProperty("name", key);
            item.addProperty("value", value);
            out.add(item);
        }
        return out;
    }

    private static JsonObject postData(ApiRequest.Body body, VariableResolver resolver, boolean resolve) {
        if (body == null || body.mode == null || "none".equalsIgnoreCase(body.mode)) {
            return null;
        }
        JsonObject postData = new JsonObject();
        String mode = body.mode.toLowerCase(java.util.Locale.ROOT);
        if ("urlencoded".equals(mode)) {
            postData.addProperty("mimeType", "application/x-www-form-urlencoded");
            JsonArray params = new JsonArray();
            if (body.urlencoded != null) {
                for (ApiRequest.Body.FormField field : body.urlencoded) {
                    if (field == null || field.key == null || field.key.isBlank()) {
                        continue;
                    }
                    JsonObject obj = new JsonObject();
                    obj.addProperty("name", CollectionExportSupport.resolve(field.key, resolver, resolve) != null ? CollectionExportSupport.resolve(field.key, resolver, resolve) : "");
                    obj.addProperty("value", CollectionExportSupport.resolve(field.value, resolver, resolve) != null ? CollectionExportSupport.resolve(field.value, resolver, resolve) : "");
                    params.add(obj);
                }
            }
            postData.add("params", params);
            return postData;
        }
        if ("formdata".equals(mode)) {
            postData.addProperty("mimeType", "multipart/form-data");
            JsonArray params = new JsonArray();
            if (body.formdata != null) {
                for (ApiRequest.Body.FormField field : body.formdata) {
                    if (field == null || field.key == null || field.key.isBlank()) {
                        continue;
                    }
                    JsonObject obj = new JsonObject();
                    obj.addProperty("name", CollectionExportSupport.resolve(field.key, resolver, resolve) != null ? CollectionExportSupport.resolve(field.key, resolver, resolve) : "");
                    if (field.fileUpload || "file".equalsIgnoreCase(field.type)) {
                        obj.addProperty("type", "file");
                        obj.addProperty("filePath", CollectionExportSupport.resolve(field.filePath, resolver, resolve) != null ? CollectionExportSupport.resolve(field.filePath, resolver, resolve) : "");
                    } else {
                        obj.addProperty("value", CollectionExportSupport.resolve(field.value, resolver, resolve) != null ? CollectionExportSupport.resolve(field.value, resolver, resolve) : "");
                    }
                    params.add(obj);
                }
            }
            postData.add("params", params);
            return postData;
        }
        if ("graphql".equals(mode)) {
            postData.addProperty("mimeType", "application/json");
            if (body.graphql != null) {
                postData.addProperty("text", "{\"query\":" + gsonString(CollectionExportSupport.resolve(body.graphql.query, resolver, resolve)) + ",\"variables\":" + gsonString(CollectionExportSupport.resolve(body.graphql.variables, resolver, resolve)) + "}");
            }
            return postData;
        }
        postData.addProperty("mimeType", body.contentType != null ? body.contentType : "text/plain");
        postData.addProperty("text", CollectionExportSupport.resolve(body.raw, resolver, resolve) != null ? CollectionExportSupport.resolve(body.raw, resolver, resolve) : "");
        return postData;
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

    private static void parseCookies(String cookieHeader, JsonArray cookies) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return;
        }
        for (String pair : cookieHeader.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            JsonObject cookie = new JsonObject();
            cookie.addProperty("name", eq >= 0 ? trimmed.substring(0, eq).trim() : trimmed);
            cookie.addProperty("value", eq >= 0 ? trimmed.substring(eq + 1).trim() : "");
            cookies.add(cookie);
        }
    }

    private static String hostHeaderFromUrl(String url) {
        try {
            HttpUtils.ParsedTarget parsed = HttpUtils.parseTargetForRequest(url);
            return HttpUtils.buildHostWithPort(parsed.host, parsed.port, parsed.useHttps);
        } catch (Exception e) {
            return null;
        }
    }

    private static String gsonString(String value) {
        return new com.google.gson.JsonPrimitive(value != null ? value : "").toString();
    }
}
