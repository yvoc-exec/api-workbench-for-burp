package burp.utils;

import burp.models.ApiRequest;
import burp.parser.VariableResolver;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
                    decoded.append(ch == '+' ? ' ' : ch);
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
