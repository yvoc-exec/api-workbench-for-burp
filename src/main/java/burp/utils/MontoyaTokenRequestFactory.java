package burp.utils;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class MontoyaTokenRequestFactory {
    private MontoyaTokenRequestFactory() {
    }

    public static HttpRequest postForm(String tokenUrl, String rawRequest, String body) {
        HttpService service = service(tokenUrl);
        try {
            return HttpRequest.httpRequest(service, rawRequest);
        } catch (NullPointerException ignored) {
            // Unit tests/no-Burp harnesses do not initialize Montoya's object factory.
        }
        URI uri = URI.create(tokenUrl);
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        byte[] rawBytes = rawRequest.getBytes(StandardCharsets.UTF_8);
        List<HttpHeader> headers = headers(service, bodyBytes.length, rawRequest);
        return (HttpRequest) Proxy.newProxyInstance(
                HttpRequest.class.getClassLoader(),
                new Class<?>[]{HttpRequest.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("httpService".equals(name)) return service;
                    if ("url".equals(name)) return tokenUrl;
                    if ("method".equals(name)) return "POST";
                    if ("path".equals(name)) return path(tokenUrl);
                    if ("query".equals(name)) return uri.getRawQuery() != null ? uri.getRawQuery() : "";
                    if ("pathWithoutQuery".equals(name)) return uri.getRawPath() != null && !uri.getRawPath().isBlank() ? uri.getRawPath() : "/";
                    if ("headers".equals(name)) return headers;
                    if ("headerValue".equals(name)) return headerValue(headers, (String) args[0]);
                    if ("hasHeader".equals(name)) return args != null && args.length > 0 && headerValue(headers, String.valueOf(args[0])) != null;
                    if ("httpVersion".equals(name)) return "HTTP/1.1";
                    if ("body".equals(name)) return bytes(bodyBytes);
                    if ("bodyToString".equals(name)) return body;
                    if ("toByteArray".equals(name)) return bytes(rawBytes);
                    if ("toString".equals(name)) return rawRequest;
                    return defaultValue(method.getReturnType());
                });
    }

    public static HttpService service(String tokenUrl) {
        String host = host(tokenUrl);
        int port = port(tokenUrl);
        boolean secure = tokenUrl != null && tokenUrl.startsWith("https");
        try {
            return HttpService.httpService(host, port, secure);
        } catch (NullPointerException ignored) {
            // Unit tests/no-Burp harnesses do not initialize Montoya's object factory.
        }
        return (HttpService) Proxy.newProxyInstance(
                HttpService.class.getClassLoader(),
                new Class<?>[]{HttpService.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("host".equals(name)) return host;
                    if ("port".equals(name)) return port;
                    if ("secure".equals(name)) return secure;
                    if ("ipAddress".equals(name)) return null;
                    if ("toString".equals(name)) return host + ":" + port;
                    return defaultValue(method.getReturnType());
                });
    }

    private static List<HttpHeader> headers(HttpService service, int bodyLength, String rawRequest) {
        List<HttpHeader> headers = new ArrayList<>();
        headers.add(header("Host", service.host() + (service.port() != 443 && service.port() != 80 ? ":" + service.port() : "")));
        headers.add(header("Content-Type", "application/x-www-form-urlencoded"));
        headers.add(header("Content-Length", Integer.toString(bodyLength)));
        String authorization = authHeader(rawRequest);
        if (authorization != null) {
            headers.add(header("Authorization", authorization));
        }
        return headers;
    }

    private static HttpHeader header(String name, String value) {
        return (HttpHeader) Proxy.newProxyInstance(
                HttpHeader.class.getClassLoader(),
                new Class<?>[]{HttpHeader.class},
                (proxy, method, args) -> {
                    if ("name".equals(method.getName())) return name;
                    if ("value".equals(method.getName())) return value;
                    if ("toString".equals(method.getName())) return name + ": " + value;
                    return defaultValue(method.getReturnType());
                });
    }

    private static ByteArray bytes(byte[] bytes) {
        byte[] data = bytes != null ? bytes.clone() : new byte[0];
        return (ByteArray) Proxy.newProxyInstance(
                ByteArray.class.getClassLoader(),
                new Class<?>[]{ByteArray.class},
                (proxy, method, args) -> {
                    if ("length".equals(method.getName())) return data.length;
                    if ("getBytes".equals(method.getName())) return data.clone();
                    if ("getByte".equals(method.getName())) return data[(Integer) args[0]];
                    if ("toString".equals(method.getName())) return new String(data, StandardCharsets.UTF_8);
                    if ("iterator".equals(method.getName())) return java.util.Collections.emptyIterator();
                    return defaultValue(method.getReturnType());
                });
    }

    private static String headerValue(List<HttpHeader> headers, String name) {
        if (name == null) {
            return null;
        }
        for (HttpHeader header : headers) {
            if (header != null && name.equalsIgnoreCase(header.name())) {
                return header.value();
            }
        }
        return null;
    }

    private static String authHeader(String rawRequest) {
        if (rawRequest == null) {
            return null;
        }
        for (String line : rawRequest.split("\\r?\\n")) {
            if (line.regionMatches(true, 0, "Authorization:", 0, "Authorization:".length())) {
                return line.substring("Authorization:".length()).trim();
            }
        }
        return null;
    }

    private static String host(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "localhost";
        }
    }

    private static int port(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            int port = u.getPort();
            return port == -1 ? (u.getProtocol().equals("https") ? 443 : 80) : port;
        } catch (Exception e) {
            return 443;
        }
    }

    private static String path(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String path = u.getPath();
            String query = u.getQuery();
            return path + (query != null ? "?" + query : "");
        } catch (Exception e) {
            return "/";
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == byte.class) return (byte) 0;
        if (type == void.class) return null;
        if (List.class.isAssignableFrom(type)) return List.of();
        return null;
    }
}
