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
            item.description = parameter.description;
            item.style = parameter.style;
            item.explode = parameter.explode;
            item.allowReserved = parameter.allowReserved;
            item.source = parameter.source;
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
            String key = resolve(resolver, parameter.key != null ? parameter.key : "");
            String value = resolve(resolver, parameter.value != null ? parameter.value : "");
            if (emittedAnyQueryParameter) {
                query.append('&');
            }
            query.append(renderComponent(parameter.rawKey, key, resolver, false));
            if (parameter.valuePresent) {
                query.append('=').append(renderComponent(parameter.rawValue, value, resolver, parameter.allowReserved));
            }
            emittedAnyQueryParameter = true;
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

        int fragmentIndex = authoredUrl.indexOf('#');
        int queryIndex = authoredUrl.indexOf('?');
        int pathEnd = authoredUrl.length();
        if (queryIndex >= 0) {
            pathEnd = queryIndex;
        }
        if (fragmentIndex >= 0 && fragmentIndex < pathEnd) {
            pathEnd = fragmentIndex;
        }
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
            String rendered = renderPathComponent(parameter.rawValue, editable, resolver, parameter.allowReserved);
            rendered = rendered.replace("?", questionMarker).replace("#", fragmentMarker);
            path = path.replace("{{" + key + "}}", rendered);
            path = path.replace("{" + key + "}", rendered);
            path = replaceColonPathSegment(path, key, rendered);
        }
        String withMaterializedPath = authoredUrl.substring(0, pathStart)
                + path
                + authoredUrl.substring(pathEnd);
        return materializeUrl(withMaterializedPath, parameters, resolver)
                .replace(questionMarker, "?")
                .replace(fragmentMarker, "#");
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
                            && (containsResolved(parameter.value, marker, resolver)
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
        int scheme = url.indexOf("://");
        int authorityStart;
        if (scheme >= 0 && scheme < pathEnd) {
            authorityStart = scheme + 3;
        } else if (url.startsWith("//")) {
            authorityStart = 2;
        } else {
            return 0;
        }
        int slash = url.indexOf('/', authorityStart);
        return slash >= 0 && slash < pathEnd ? slash : pathEnd;
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
