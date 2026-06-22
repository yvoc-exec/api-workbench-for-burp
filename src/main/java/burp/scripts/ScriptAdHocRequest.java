package burp.scripts;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.models.ApiRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScriptAdHocRequest {
    public String name;
    public String method = "GET";
    public String url;
    public final List<ApiRequest.Header> headers = new ArrayList<>();
    public String body;
    public String contentType;

    public static ScriptAdHocRequest from(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ScriptAdHocRequest request) {
            return request;
        }
        if (value instanceof Map<?, ?> map) {
            return fromMap(map);
        }
        if (value instanceof org.graalvm.polyglot.Value polyValue) {
            if (polyValue.hasMembers()) {
                Map<String, Object> map = new LinkedHashMap<>();
                for (String key : polyValue.getMemberKeys()) {
                    try {
                        map.put(key, polyValue.getMember(key));
                    } catch (Exception ignored) {
                    }
                }
                return fromMap(map);
            }
            if (polyValue.isString()) {
                ScriptAdHocRequest request = new ScriptAdHocRequest();
                request.url = polyValue.asString();
                return request;
            }
        }
        if (value instanceof String string) {
            ScriptAdHocRequest request = new ScriptAdHocRequest();
            request.url = string;
            return request;
        }
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            for (java.lang.reflect.Field field : value.getClass().getFields()) {
                map.put(field.getName(), field.get(value));
            }
            return fromMap(map);
        } catch (Exception ignored) {
            return null;
        }
    }

    public ApiRequest toApiRequest() {
        ApiRequest request = new ApiRequest();
        request.name = name != null && !name.isBlank() ? name : "Ad-hoc Request";
        request.method = method != null && !method.isBlank() ? method : "GET";
        request.url = url;
        request.headers = new ArrayList<>();
        for (ApiRequest.Header header : headers) {
            if (header != null && header.key != null && !header.key.isBlank()) {
                request.headers.add(new ApiRequest.Header(header.key, header.value, header.disabled));
            }
        }
        if (body != null || contentType != null) {
            request.body = new ApiRequest.Body();
            request.body.mode = "raw";
            request.body.raw = body;
            request.body.contentType = contentType;
        }
        request.editorMaterialized = true;
        request.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        return request;
    }

    private static ScriptAdHocRequest fromMap(Map<?, ?> map) {
        ScriptAdHocRequest request = new ScriptAdHocRequest();
        if (map == null) {
            return request;
        }
        request.name = stringValue(map.get("name"));
        request.method = stringValue(map.get("method"), "GET");
        request.url = stringValue(map.get("url"));
        request.body = stringValue(map.get("body"));
        request.contentType = firstNonBlank(stringValue(map.get("contentType")), stringValue(map.get("content_type")));
        Object headersValue = map.get("headers");
        if (headersValue instanceof Map<?, ?> headersMap) {
            for (Map.Entry<?, ?> entry : headersMap.entrySet()) {
                String key = stringValue(entry.getKey());
                if (key == null || key.isBlank()) {
                    continue;
                }
                request.headers.add(new ApiRequest.Header(key, stringValue(entry.getValue()), false));
            }
        } else if (headersValue instanceof List<?> headersList) {
            for (Object headerValue : headersList) {
                if (headerValue instanceof Map<?, ?> headerMap) {
                    String key = stringValue(headerMap.get("name"));
                    if (key == null || key.isBlank()) {
                        key = stringValue(headerMap.get("key"));
                    }
                    if (key == null || key.isBlank()) {
                        continue;
                    }
                    String value = stringValue(headerMap.get("value"));
                    request.headers.add(new ApiRequest.Header(key, value, false));
                }
            }
        }
        return request;
    }

    private static String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

    private static String stringValue(Object value, String defaultValue) {
        String text = stringValue(value);
        return text != null && !text.isBlank() ? text : defaultValue;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
