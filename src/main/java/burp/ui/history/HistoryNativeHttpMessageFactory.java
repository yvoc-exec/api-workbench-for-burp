package burp.ui.history;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class HistoryNativeHttpMessageFactory {
    private HistoryNativeHttpMessageFactory() {
    }

    public static HttpRequest request(String rawMessage) {
        String raw = rawMessage != null ? rawMessage : "";
        try {
            return HttpRequest.httpRequest(raw);
        } catch (Throwable ignored) {
            return proxy(HttpRequest.class, new ParsedRequest(raw));
        }
    }

    public static HttpResponse response(String rawMessage) {
        String raw = rawMessage != null ? rawMessage : "";
        try {
            return HttpResponse.httpResponse(raw);
        } catch (Throwable ignored) {
            return proxy(HttpResponse.class, new ParsedResponse(raw));
        }
    }

    private static <T> T proxy(Class<T> type, ParsedMessage parsed) {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> {
            String name = method.getName();
            switch (name) {
                case "toString":
                    return parsed.raw;
                case "hashCode":
                    return parsed.raw.hashCode();
                case "equals":
                    return proxy == args[0];
                default:
                    return parsed.invoke(method, args, proxy);
            }
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, handler));
    }

    private abstract static class ParsedMessage {
        final String raw;

        ParsedMessage(String raw) {
            this.raw = raw != null ? raw : "";
        }

        abstract Object invoke(Method method, Object[] args, Object proxy);

        static Object defaultValue(Class<?> type) {
            if (type == Void.TYPE) {
                return null;
            }
            if (!type.isPrimitive()) {
                if (type == java.util.Optional.class) {
                    return java.util.Optional.empty();
                }
                return null;
            }
            if (type == Boolean.TYPE) {
                return false;
            }
            if (type == Byte.TYPE) {
                return (byte) 0;
            }
            if (type == Short.TYPE) {
                return (short) 0;
            }
            if (type == Integer.TYPE) {
                return 0;
            }
            if (type == Long.TYPE) {
                return 0L;
            }
            if (type == Float.TYPE) {
                return 0f;
            }
            if (type == Double.TYPE) {
                return 0d;
            }
            if (type == Character.TYPE) {
                return '\0';
            }
            return null;
        }

        static Map<String, String> parseHeaders(String headerBlock) {
            Map<String, String> headers = new LinkedHashMap<>();
            if (headerBlock == null || headerBlock.isBlank()) {
                return headers;
            }
            String[] lines = headerBlock.replace("\r", "").split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line == null || line.isBlank()) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                headers.put(key, value);
            }
            return headers;
        }

        static String splitBody(String raw) {
            int separator = raw.indexOf("\r\n\r\n");
            if (separator >= 0) {
                return raw.substring(separator + 4);
            }
            separator = raw.indexOf("\n\n");
            if (separator >= 0) {
                return raw.substring(separator + 2);
            }
            return "";
        }
    }

    private static final class ParsedRequest extends ParsedMessage {
        private final String method;
        private final String target;
        private final Map<String, String> headers;
        private final String body;

        ParsedRequest(String raw) {
            super(raw);
            String head = raw;
            int separator = raw.indexOf("\r\n\r\n");
            if (separator >= 0) {
                head = raw.substring(0, separator);
                body = raw.substring(separator + 4);
            } else {
                separator = raw.indexOf("\n\n");
                if (separator >= 0) {
                    head = raw.substring(0, separator);
                    body = raw.substring(separator + 2);
                } else {
                    body = "";
                }
            }
            String[] lines = head.replace("\r", "").split("\n");
            String startLine = lines.length > 0 ? lines[0].trim() : "";
            String[] parts = startLine.split("\\s+", 3);
            method = parts.length > 0 ? parts[0] : "";
            target = parts.length > 1 ? parts[1] : "";
            StringBuilder headersText = new StringBuilder();
            for (int i = 1; i < lines.length; i++) {
                if (headersText.length() > 0) {
                    headersText.append('\n');
                }
                headersText.append(lines[i]);
            }
            headers = parseHeaders(headersText.toString());
        }

        @Override
        Object invoke(Method method, Object[] args, Object proxy) {
            return switch (method.getName()) {
                case "method" -> this.method;
                case "path", "url" -> this.target;
                case "httpVersion" -> "HTTP/1.1";
                case "bodyToString" -> body;
                case "headers" -> java.util.List.of();
                case "headerValue" -> headerValue((String) args[0]);
                case "hasHeader" -> hasHeader(args);
                case "toByteArray", "body" -> null;
                case "withService", "withPath", "withMethod", "withHeader", "withAddedHeader", "withAddedHeaders",
                        "withUpdatedHeader", "withUpdatedHeaders", "withRemovedHeader", "withRemovedHeaders",
                        "withParameter", "withAddedParameters", "withRemovedParameters", "withUpdatedParameters",
                        "withTransformationApplied", "withBody", "withMarkers", "copyToTempFile", "withDefaultHeaders" -> proxy;
                case "contains" -> contains(args);
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object headerValue(String name) {
            if (name == null) {
                return null;
            }
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        private Object hasHeader(Object[] args) {
            if (args == null || args.length == 0 || args[0] == null) {
                return false;
            }
            String name = String.valueOf(args[0]);
            for (String key : headers.keySet()) {
                if (key.equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        }

        private Object contains(Object[] args) {
            if (args == null || args.length == 0 || args[0] == null) {
                return false;
            }
            String needle = String.valueOf(args[0]);
            return raw.contains(needle);
        }
    }

    private static final class ParsedResponse extends ParsedMessage {
        private final short statusCode;
        private final String reasonPhrase;
        private final Map<String, String> headers;
        private final String body;

        ParsedResponse(String raw) {
            super(raw);
            String head = raw;
            int separator = raw.indexOf("\r\n\r\n");
            if (separator >= 0) {
                head = raw.substring(0, separator);
                body = raw.substring(separator + 4);
            } else {
                separator = raw.indexOf("\n\n");
                if (separator >= 0) {
                    head = raw.substring(0, separator);
                    body = raw.substring(separator + 2);
                } else {
                    body = "";
                }
            }
            String[] lines = head.replace("\r", "").split("\n");
            String statusLine = lines.length > 0 ? lines[0].trim() : "";
            String[] parts = statusLine.split("\\s+", 3);
            short parsedStatus = 0;
            String parsedReason = "";
            if (parts.length >= 2) {
                try {
                    parsedStatus = Short.parseShort(parts[1]);
                } catch (NumberFormatException ignored) {
                    parsedStatus = 0;
                }
                if (parts.length >= 3) {
                    parsedReason = parts[2];
                }
            }
            statusCode = parsedStatus;
            reasonPhrase = parsedReason;
            StringBuilder headersText = new StringBuilder();
            for (int i = 1; i < lines.length; i++) {
                if (headersText.length() > 0) {
                    headersText.append('\n');
                }
                headersText.append(lines[i]);
            }
            headers = parseHeaders(headersText.toString());
        }

        @Override
        Object invoke(Method method, Object[] args, Object proxy) {
            return switch (method.getName()) {
                case "statusCode" -> statusCode;
                case "reasonPhrase" -> reasonPhrase;
                case "bodyToString" -> body;
                case "headers" -> java.util.List.of();
                case "headerValue" -> headerValue((String) args[0]);
                case "hasHeader" -> hasHeader(args);
                case "toByteArray", "body" -> null;
                case "withStatusCode", "withReasonPhrase", "withHttpVersion", "withBody", "withAddedHeader",
                        "withAddedHeaders", "withUpdatedHeader", "withUpdatedHeaders", "withRemovedHeader",
                        "withRemovedHeaders", "withMarkers", "copyToTempFile" -> proxy;
                case "contains" -> contains(args);
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object headerValue(String name) {
            if (name == null) {
                return null;
            }
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        private Object hasHeader(Object[] args) {
            if (args == null || args.length == 0 || args[0] == null) {
                return false;
            }
            String name = String.valueOf(args[0]);
            for (String key : headers.keySet()) {
                if (key.equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        }

        private Object contains(Object[] args) {
            if (args == null || args.length == 0 || args[0] == null) {
                return false;
            }
            String needle = String.valueOf(args[0]);
            return raw.contains(needle);
        }
    }
}
