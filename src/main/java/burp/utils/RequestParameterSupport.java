package burp.utils;

import burp.models.ApiRequest;
import burp.parser.VariableResolver;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;

/** Utilities for lossless request-parameter copying, parsing, and transport rendering. */
public final class RequestParameterSupport {
    private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
    private static final String RESERVED = ":/?#[]@!$&'()*+,;=";

    private RequestParameterSupport() {
    }

    public static List<ApiRequest.Parameter> copyParameters(List<ApiRequest.Parameter> source) {
        List<ApiRequest.Parameter> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (ApiRequest.Parameter parameter : source) {
            if (parameter == null) {
                copy.add(null);
                continue;
            }
            ApiRequest.Parameter item = new ApiRequest.Parameter();
            item.location = parameter.location;
            item.key = parameter.key;
            item.value = parameter.value;
            item.rawKey = parameter.rawKey;
            item.rawValue = parameter.rawValue;
            item.valuePresent = parameter.valuePresent;
            item.disabled = parameter.disabled;
            item.required = parameter.required;
            item.type = parameter.type;
            item.format = parameter.format;
            item.description = parameter.description;
            item.style = parameter.style;
            item.explode = parameter.explode;
            item.allowReserved = parameter.allowReserved;
            item.source = parameter.source;
            item.sourceMetadata = parameter.sourceMetadata != null
                    ? new LinkedHashMap<>(parameter.sourceMetadata) : new LinkedHashMap<>();
            copy.add(item);
        }
        return copy;
    }

    public static boolean hasQueryParameters(List<ApiRequest.Parameter> parameters) {
        if (parameters == null) {
            return false;
        }
        for (ApiRequest.Parameter parameter : parameters) {
            if (parameter != null && parameter.isQuery()) {
                return true;
            }
        }
        return false;
    }

    public static String normalizeLocation(String location) {
        if (location == null || location.isBlank()) {
            return "query";
        }
        return location.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isLocation(ApiRequest.Parameter parameter, String location) {
        return parameter != null
                && normalizeLocation(parameter.location).equals(normalizeLocation(location));
    }

    public static boolean hasParametersAtLocation(List<ApiRequest.Parameter> parameters, String location) {
        if (parameters == null) {
            return false;
        }
        for (ApiRequest.Parameter parameter : parameters) {
            if (isLocation(parameter, location)) {
                return true;
            }
        }
        return false;
    }

    public static List<ApiRequest.Parameter> parseQueryParameters(String url, String source) {
        List<ApiRequest.Parameter> parameters = new ArrayList<>();
        if (url == null) {
            return parameters;
        }
        int fragmentIndex = url.indexOf('#');
        int queryIndex = url.indexOf('?');
        if (queryIndex < 0 || (fragmentIndex >= 0 && queryIndex > fragmentIndex)) {
            return parameters;
        }
        int end = fragmentIndex >= 0 ? fragmentIndex : url.length();
        String query = url.substring(queryIndex + 1, end);
        for (String segment : query.split("&", -1)) {
            int equals = segment.indexOf('=');
            String rawKey = equals >= 0 ? segment.substring(0, equals) : segment;
            String rawValue = equals >= 0 ? segment.substring(equals + 1) : "";
            ApiRequest.Parameter parameter = new ApiRequest.Parameter("query", decodeOrRaw(rawKey), decodeOrRaw(rawValue));
            parameter.rawKey = rawKey;
            parameter.rawValue = rawValue;
            parameter.valuePresent = equals >= 0;
            parameter.source = source;
            parameters.add(parameter);
        }
        return parameters;
    }

    public static String stripQuery(String url) {
        if (url == null) {
            return null;
        }
        int fragmentIndex = url.indexOf('#');
        int queryIndex = url.indexOf('?');
        if (queryIndex < 0 || (fragmentIndex >= 0 && queryIndex > fragmentIndex)) {
            return url;
        }
        return url.substring(0, queryIndex) + (fragmentIndex >= 0 ? url.substring(fragmentIndex) : "");
    }

    public static String materializeUrl(String url,
                                        List<ApiRequest.Parameter> parameters,
                                        VariableResolver resolver) {
        String resolvedUrl = resolve(resolver, url != null ? url : "");
        if (!hasQueryParameters(parameters)) {
            return resolvedUrl;
        }

        String stripped = stripQuery(resolvedUrl);
        int fragmentIndex = stripped.indexOf('#');
        String fragment = fragmentIndex >= 0 ? stripped.substring(fragmentIndex) : "";
        String base = fragmentIndex >= 0 ? stripped.substring(0, fragmentIndex) : stripped;
        StringBuilder query = new StringBuilder();
        boolean emittedAnyQueryParameter = false;
        for (ApiRequest.Parameter parameter : parameters) {
            if (parameter == null || !parameter.isQuery() || parameter.disabled) {
                continue;
            }
            List<String> structured = serializeStructuredQuery(parameter, resolver);
            if (structured != null) {
                for (String segment : structured) {
                    if (emittedAnyQueryParameter) query.append('&');
                    query.append(segment);
                    emittedAnyQueryParameter = true;
                }
            } else {
                String key = resolve(resolver, parameter.key != null ? parameter.key : "");
                String value = resolve(resolver, parameter.value != null ? parameter.value : "");
                if (emittedAnyQueryParameter) query.append('&');
                query.append(renderComponent(parameter.rawKey, key, resolver, false));
                if (parameter.valuePresent) {
                    query.append('=').append(renderComponent(parameter.rawValue, value, resolver, parameter.allowReserved));
                }
                emittedAnyQueryParameter = true;
            }
        }
        return base + (emittedAnyQueryParameter ? "?" + query : "") + fragment;
    }

    public static String materializeRequestUrl(String url,
                                               List<ApiRequest.Parameter> parameters,
                                               VariableResolver resolver) {
        String authoredUrl = url != null ? url : "";
        if (!hasParametersAtLocation(parameters, "path")) {
            return materializeUrl(authoredUrl, parameters, resolver);
        }

        int pathEnd = pathEnd(authoredUrl);
        int pathStart = pathStart(authoredUrl, pathEnd);
        String path = authoredUrl.substring(pathStart, pathEnd);
        String questionMarker = uniquePathMarker(authoredUrl, parameters, resolver, "question");
        String fragmentMarker = uniquePathMarker(authoredUrl, parameters, resolver, "fragment");

        Map<String, ApiRequest.Parameter> firstEnabledByKey = new LinkedHashMap<>();
        if (parameters != null) {
            for (ApiRequest.Parameter parameter : parameters) {
                if (!isLocation(parameter, "path") || parameter.disabled || parameter.key == null) {
                    continue;
                }
                String key = parameter.key;
                if (!key.isEmpty()) {
                    firstEnabledByKey.putIfAbsent(key, parameter);
                }
            }
        }
        for (Map.Entry<String, ApiRequest.Parameter> entry : firstEnabledByKey.entrySet()) {
            String key = entry.getKey();
            ApiRequest.Parameter parameter = entry.getValue();
            String editable = resolve(resolver, parameter.value != null ? parameter.value : "");
            String rendered = serializeStructuredPath(parameter, resolver);
            if (rendered == null) {
                rendered = renderPathComponent(parameter.rawValue, editable, resolver, parameter.allowReserved);
            }
            rendered = rendered.replace("?", questionMarker).replace("#", fragmentMarker);
            path = path.replace("{{" + key + "}}", rendered);
            path = path.replace("{" + key + "}", rendered);
            path = replaceColonPathSegment(path, key, rendered);
        }
        ProtectedPath protectedDisabled = protectPathParameterTemplates(
                path, authoredUrl, parameters, resolver, true);
        path = protectedDisabled.path;
        String withMaterializedPath = authoredUrl.substring(0, pathStart)
                + path
                + authoredUrl.substring(pathEnd);
        return restoreProtectedPathTemplates(
                materializeUrl(withMaterializedPath, parameters, resolver), protectedDisabled)
                .replace(questionMarker, "?")
                .replace(fragmentMarker, "#");
    }

    public static String materializePostmanRawUrl(String url,
                                                  List<ApiRequest.Parameter> parameters,
                                                  VariableResolver resolver) {
        String authoredUrl = url != null ? url : "";
        if (!hasParametersAtLocation(parameters, "path")) {
            return materializeUrl(authoredUrl, parameters, resolver);
        }
        int pathEnd = pathEnd(authoredUrl);
        int pathStart = pathStart(authoredUrl, pathEnd);
        ProtectedPath protectedPath = protectPathParameterTemplates(
                authoredUrl.substring(pathStart, pathEnd),
                authoredUrl,
                parameters,
                resolver,
                false);
        String protectedUrl = authoredUrl.substring(0, pathStart)
                + protectedPath.path
                + authoredUrl.substring(pathEnd);
        return restoreProtectedPathTemplates(
                materializeUrl(protectedUrl, parameters, resolver), protectedPath);
    }

    private static ProtectedPath protectPathParameterTemplates(String path,
                                                               String url,
                                                               List<ApiRequest.Parameter> parameters,
                                                               VariableResolver resolver,
                                                               boolean disabledOnly) {
        String protectedPath = path != null ? path : "";
        Map<String, String> restorations = new LinkedHashMap<>();
        java.util.Set<String> protectedKeys = new java.util.LinkedHashSet<>();
        if (parameters != null) {
            for (ApiRequest.Parameter parameter : parameters) {
                if (!isLocation(parameter, "path")
                        || (disabledOnly && !parameter.disabled)
                        || parameter.key == null
                        || parameter.key.isEmpty()
                        || !protectedKeys.add(parameter.key)) {
                    continue;
                }
                String placeholder = "{{" + parameter.key + "}}";
                if (!protectedPath.contains(placeholder)) {
                    continue;
                }
                String marker = uniquePathMarker(
                        url, parameters, resolver, "template-" + restorations.size());
                protectedPath = protectedPath.replace(placeholder, marker);
                restorations.put(marker, placeholder);
            }
        }
        return new ProtectedPath(protectedPath, restorations);
    }

    private static String restoreProtectedPathTemplates(String value, ProtectedPath protectedPath) {
        String restored = value != null ? value : "";
        if (protectedPath != null) {
            for (Map.Entry<String, String> restoration : protectedPath.restorations.entrySet()) {
                restored = restored.replace(restoration.getKey(), restoration.getValue());
            }
        }
        return restored;
    }

    private static int pathEnd(String url) {
        int fragmentIndex = url.indexOf('#');
        int queryIndex = url.indexOf('?');
        int end = url.length();
        if (queryIndex >= 0) {
            end = queryIndex;
        }
        if (fragmentIndex >= 0 && fragmentIndex < end) {
            end = fragmentIndex;
        }
        return end;
    }

    private static String uniquePathMarker(String url,
                                           List<ApiRequest.Parameter> parameters,
                                           VariableResolver resolver,
                                           String label) {
        for (int suffix = 0; ; suffix++) {
            String marker = "\uE000awb-path-" + label + "-" + suffix + "\uE001";
            if (url != null && (url.contains(marker) || resolve(resolver, url).contains(marker))) {
                continue;
            }
            boolean collision = false;
            if (parameters != null) {
                for (ApiRequest.Parameter parameter : parameters) {
                    if (parameter != null
                            && (containsResolved(parameter.key, marker, resolver)
                            || containsResolved(parameter.rawKey, marker, resolver)
                            || containsResolved(parameter.value, marker, resolver)
                            || containsResolved(parameter.rawValue, marker, resolver))) {
                        collision = true;
                        break;
                    }
                }
            }
            if (!collision) {
                return marker;
            }
        }
    }

    private static boolean containsResolved(String value, String marker, VariableResolver resolver) {
        return value != null && resolve(resolver, value).contains(marker);
    }

    private static int pathStart(String url, int pathEnd) {
        if (url == null || url.isEmpty() || pathEnd <= 0) {
            return 0;
        }
        int authorityStart = 0;
        if (url.startsWith("//")) {
            authorityStart = 2;
        } else {
            int schemeSeparator = url.indexOf("://");
            if (schemeSeparator > 0
                    && schemeSeparator < pathEnd
                    && isValidScheme(url, schemeSeparator)) {
                authorityStart = schemeSeparator + 3;
            } else if (url.startsWith("/")) {
                return 0;
            }
        }
        int slash = url.indexOf('/', authorityStart);
        return slash >= 0 && slash < pathEnd ? slash : pathEnd;
    }

    private static boolean isValidScheme(String url, int end) {
        if (end <= 0 || !Character.isLetter(url.charAt(0))) {
            return false;
        }
        for (int i = 1; i < end; i++) {
            char ch = url.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '+' && ch != '-' && ch != '.') {
                return false;
            }
        }
        return true;
    }

    private static final class ProtectedPath {
        final String path;
        final Map<String, String> restorations;

        ProtectedPath(String path, Map<String, String> restorations) {
            this.path = path;
            this.restorations = restorations;
        }
    }

    private static String replaceColonPathSegment(String path, String key, String rendered) {
        String target = ":" + key;
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].equals(target)) {
                segments[i] = rendered;
            }
        }
        return String.join("/", segments);
    }

    private static String renderPathComponent(String raw,
                                              String editable,
                                              VariableResolver resolver,
                                              boolean allowReserved) {
        if (raw != null) {
            String resolvedRaw = resolve(resolver, raw);
            if (isSafePathRaw(resolvedRaw, allowReserved)) {
                String decoded = decodePath(resolvedRaw);
                if (decoded != null && decoded.equals(editable)) {
                    return resolvedRaw;
                }
            }
        }
        return encode(editable, allowReserved);
    }

    /** Returns an OpenAPI simple-style header value, falling back to scalar text. */
    public static String serializeHeaderValue(ApiRequest.Parameter parameter, VariableResolver resolver) {
        Object structured = structuredValue(parameter != null ? parameter.type : null,
                parameter != null ? parameter.value : null, resolver);
        if (structured == null || !"simple".equals(parameter.style)) {
            return resolve(resolver, parameter != null && parameter.value != null ? parameter.value : "");
        }
        boolean explode = Boolean.TRUE.equals(parameter.explode);
        return delimitedValue(structured, ",", explode, false, false);
    }

    /** Returns cookie pairs for OpenAPI form serialization, retaining explode semantics. */
    public static List<String> serializeCookieParts(ApiRequest.Parameter parameter, VariableResolver resolver) {
        if (parameter == null) return Collections.emptyList();
        String key = resolve(resolver, parameter.key != null ? parameter.key : "");
        if (!parameter.valuePresent) return List.of(key);
        Object structured = structuredValue(parameter.type, parameter.value, resolver);
        if (structured == null || !"form".equals(parameter.style)) {
            return List.of(key + "=" + resolve(resolver, parameter.value != null ? parameter.value : ""));
        }
        boolean explode = Boolean.TRUE.equals(parameter.explode);
        List<String> result = new ArrayList<>();
        if (structured instanceof List<?> list) {
            if (explode) for (Object item : list) result.add(key + "=" + scalar(item));
            else result.add(key + "=" + joinScalars(list, ",", false, false));
        } else if (structured instanceof Map<?, ?> map) {
            if (explode) for (Map.Entry<?, ?> entry : map.entrySet()) result.add(scalar(entry.getKey()) + "=" + scalar(entry.getValue()));
            else result.add(key + "=" + joinObject(map, ",", false, false));
        }
        return result;
    }

    /** OpenAPI form-style application/x-www-form-urlencoded pairs without a leading question mark. */
    public static List<String> serializeFormPairs(String key,
                                                  String value,
                                                  String type,
                                                  String style,
                                                  Boolean explode,
                                                  boolean allowReserved,
                                                  VariableResolver resolver) {
        String resolvedKey = resolve(resolver, key != null ? key : "");
        String resolvedValue = resolve(resolver, value != null ? value : "");
        Object structured = structuredValue(type, value, resolver);
        if (structured == null) {
            String encodedValue = allowReserved
                    ? encode(resolvedValue, true)
                    : java.net.URLEncoder.encode(resolvedValue, StandardCharsets.UTF_8);
            return List.of(java.net.URLEncoder.encode(resolvedKey, StandardCharsets.UTF_8)
                    + "=" + encodedValue);
        }
        ApiRequest.Parameter parameter = new ApiRequest.Parameter("query", resolvedKey, resolvedValue);
        parameter.type = type;
        parameter.style = style == null || style.isBlank() ? "form" : style;
        parameter.explode = explode;
        parameter.allowReserved = allowReserved;
        List<String> query = serializeStructuredQuery(parameter, null);
        if (query != null) return query;
        String encodedValue = allowReserved
                ? encode(resolvedValue, true)
                : java.net.URLEncoder.encode(resolvedValue, StandardCharsets.UTF_8);
        return List.of(java.net.URLEncoder.encode(resolvedKey, StandardCharsets.UTF_8) + "=" + encodedValue);
    }

    private static List<String> serializeStructuredQuery(ApiRequest.Parameter parameter, VariableResolver resolver) {
        Object structured = structuredValue(parameter.type, parameter.value, resolver);
        if (structured == null) return null;
        String style = parameter.style != null ? parameter.style : "form";
        boolean explode = parameter.explode == null || parameter.explode;
        String key = encode(resolve(resolver, parameter.key != null ? parameter.key : ""), false);
        List<String> result = new ArrayList<>();
        if (structured instanceof List<?> list) {
            switch (style) {
                case "form" -> {
                    if (explode) for (Object item : list) result.add(key + "=" + encode(scalar(item), parameter.allowReserved));
                    else result.add(key + "=" + joinScalars(list, ",", true, parameter.allowReserved));
                }
                case "spaceDelimited" -> result.add(key + "=" + joinScalars(list, "%20", true, parameter.allowReserved));
                case "pipeDelimited" -> result.add(key + "=" + joinScalars(list, "|", true, parameter.allowReserved));
                default -> { return null; }
            }
        } else if (structured instanceof Map<?, ?> map) {
            switch (style) {
                case "form" -> {
                    if (explode) {
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            result.add(encode(scalar(entry.getKey()), false) + "=" + encode(scalar(entry.getValue()), parameter.allowReserved));
                        }
                    } else result.add(key + "=" + joinObject(map, ",", true, parameter.allowReserved));
                }
                case "deepObject" -> {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        result.add(key + "[" + encode(scalar(entry.getKey()), false) + "]="
                                + encode(scalar(entry.getValue()), parameter.allowReserved));
                    }
                }
                default -> { return null; }
            }
        }
        return result;
    }

    private static String serializeStructuredPath(ApiRequest.Parameter parameter, VariableResolver resolver) {
        Object structured = structuredValue(parameter.type, parameter.value, resolver);
        if (structured == null) return null;
        String style = parameter.style != null ? parameter.style : "simple";
        boolean explode = Boolean.TRUE.equals(parameter.explode);
        String key = encode(resolve(resolver, parameter.key != null ? parameter.key : ""), false);
        return switch (style) {
            case "simple" -> delimitedValue(structured, ",", explode, true, false);
            case "label" -> "." + delimitedValue(structured, explode ? "." : ",", explode, true, false);
            case "matrix" -> matrixValue(key, structured, explode);
            default -> null;
        };
    }

    private static String matrixValue(String key, Object structured, boolean explode) {
        if (structured instanceof List<?> list) {
            if (explode) {
                StringBuilder out = new StringBuilder();
                for (Object item : list) out.append(';').append(key).append('=').append(encode(scalar(item), false));
                return out.toString();
            }
            return ";" + key + "=" + joinScalars(list, ",", true, false);
        }
        Map<?, ?> map = (Map<?, ?>) structured;
        if (explode) {
            StringBuilder out = new StringBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.append(';').append(encode(scalar(entry.getKey()), false)).append('=').append(encode(scalar(entry.getValue()), false));
            }
            return out.toString();
        }
        return ";" + key + "=" + joinObject(map, ",", true, false);
    }

    private static String delimitedValue(Object structured,
                                         String separator,
                                         boolean explode,
                                         boolean encoded,
                                         boolean allowReserved) {
        if (structured instanceof List<?> list) return joinScalars(list, separator, encoded, allowReserved);
        Map<?, ?> map = (Map<?, ?>) structured;
        if (!explode) return joinObject(map, separator, encoded, allowReserved);
        List<String> members = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = encoded ? encode(scalar(entry.getKey()), false) : scalar(entry.getKey());
            String value = encoded ? encode(scalar(entry.getValue()), allowReserved) : scalar(entry.getValue());
            members.add(key + "=" + value);
        }
        return String.join(separator, members);
    }

    private static String joinScalars(List<?> values, String separator, boolean encoded, boolean allowReserved) {
        List<String> rendered = new ArrayList<>();
        for (Object value : values) rendered.add(encoded ? encode(scalar(value), allowReserved) : scalar(value));
        return String.join(separator, rendered);
    }

    private static String joinObject(Map<?, ?> values, String separator, boolean encoded, boolean allowReserved) {
        List<String> rendered = new ArrayList<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            rendered.add(encoded ? encode(scalar(entry.getKey()), false) : scalar(entry.getKey()));
            rendered.add(encoded ? encode(scalar(entry.getValue()), allowReserved) : scalar(entry.getValue()));
        }
        return String.join(separator, rendered);
    }

    private static Object structuredValue(String type, String value, VariableResolver resolver) {
        if (type == null || !("array".equalsIgnoreCase(type) || "object".equalsIgnoreCase(type))) return null;
        Object parsed = OpenApiMetadataSupport.parseCanonicalJson(resolve(resolver, value != null ? value : ""));
        if ("array".equalsIgnoreCase(type) && parsed instanceof List<?>) return parsed;
        if ("object".equalsIgnoreCase(type) && parsed instanceof Map<?, ?>) return parsed;
        return null;
    }

    private static String scalar(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> || value instanceof List<?>) return OpenApiMetadataSupport.canonicalJson(value);
        return String.valueOf(value);
    }

    private static boolean isSafePathRaw(String raw, boolean allowReserved) {
        if (raw == null || containsIllegalRawCharacters(raw)) {
            return false;
        }
        for (int i = 0; i < raw.length();) {
            char ch = raw.charAt(i);
            if (ch == '%') {
                if (i + 2 >= raw.length()
                        || Character.digit(raw.charAt(i + 1), 16) < 0
                        || Character.digit(raw.charAt(i + 2), 16) < 0) {
                    return false;
                }
                i += 3;
                continue;
            }
            if (ch >= 128 || (UNRESERVED.indexOf(ch) < 0
                    && !(allowReserved && RESERVED.indexOf(ch) >= 0))) {
                return false;
            }
            i++;
        }
        return true;
    }

    private static String renderComponent(String raw,
                                          String editable,
                                          VariableResolver resolver,
                                          boolean allowReserved) {
        if (raw != null) {
            String resolvedRaw = resolve(resolver, raw);
            if (!containsIllegalRawCharacters(resolvedRaw)) {
                String decoded = decode(resolvedRaw);
                if (decoded != null && decoded.equals(editable)) {
                    return resolvedRaw;
                }
            }
        }
        return encode(editable, allowReserved);
    }

    private static String resolve(VariableResolver resolver, String value) {
        return resolver != null ? resolver.resolve(value) : value;
    }

    private static String decodeOrRaw(String raw) {
        String decoded = decode(raw);
        return decoded != null ? decoded : raw;
    }

    private static String decode(String raw) {
        return decode(raw, true);
    }

    private static String decodePath(String raw) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            StringBuilder decoded = new StringBuilder();
            for (int i = 0; i < raw.length();) {
                char ch = raw.charAt(i);
                if (ch == '%') {
                    bytes.reset();
                    while (i < raw.length() && raw.charAt(i) == '%') {
                        if (i + 2 >= raw.length()) {
                            return null;
                        }
                        int high = Character.digit(raw.charAt(i + 1), 16);
                        int low = Character.digit(raw.charAt(i + 2), 16);
                        if (high < 0 || low < 0) {
                            return null;
                        }
                        bytes.write((high << 4) | low);
                        i += 3;
                    }
                    decoded.append(StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes.toByteArray())));
                } else {
                    decoded.append(ch);
                    i++;
                }
            }
            return decoded.toString();
        } catch (java.nio.charset.CharacterCodingException | RuntimeException e) {
            return null;
        }
    }

    private static String decode(String raw, boolean plusAsSpace) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            StringBuilder decoded = new StringBuilder();
            for (int i = 0; i < raw.length();) {
                char ch = raw.charAt(i);
                if (ch == '%') {
                    bytes.reset();
                    while (i < raw.length() && raw.charAt(i) == '%') {
                        if (i + 2 >= raw.length()) {
                            return null;
                        }
                        int high = Character.digit(raw.charAt(i + 1), 16);
                        int low = Character.digit(raw.charAt(i + 2), 16);
                        if (high < 0 || low < 0) {
                            return null;
                        }
                        bytes.write((high << 4) | low);
                        i += 3;
                    }
                    decoded.append(bytes.toString(StandardCharsets.UTF_8));
                } else {
                    decoded.append(plusAsSpace && ch == '+' ? ' ' : ch);
                    i++;
                }
            }
            return decoded.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String encode(String value, boolean allowReserved) {
        String input = value != null ? value : "";
        StringBuilder encoded = new StringBuilder();
        for (int offset = 0; offset < input.length();) {
            if (input.startsWith("{{", offset)) {
                int close = input.indexOf("}}", offset + 2);
                if (close >= 0) {
                    encoded.append(input, offset, close + 2);
                    offset = close + 2;
                    continue;
                }
            }
            int codePoint = input.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            if (codePoint < 128 && (UNRESERVED.indexOf(codePoint) >= 0
                    || (allowReserved && RESERVED.indexOf(codePoint) >= 0))) {
                encoded.append(character);
            } else {
                for (byte b : character.getBytes(StandardCharsets.UTF_8)) {
                    encoded.append('%');
                    int unsigned = b & 0xff;
                    encoded.append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)));
                    encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0x0f, 16)));
                }
            }
            offset += Character.charCount(codePoint);
        }
        return encoded.toString();
    }

    private static boolean containsIllegalRawCharacters(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch <= 0x20 || ch == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
