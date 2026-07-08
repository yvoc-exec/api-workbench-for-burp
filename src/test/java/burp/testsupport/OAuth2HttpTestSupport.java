package burp.testsupport;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public final class OAuth2HttpTestSupport {
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(750);

    private OAuth2HttpTestSupport() {
    }

    public static MontoyaApi mockMontoyaApi(TokenEndpointServer server) throws Exception {
        MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        when(api.http().sendRequest(any(HttpRequest.class))).thenAnswer(invocation -> server.forward(invocation.getArgument(0)));
        when(api.http().sendRequest(any(HttpRequest.class), any(RequestOptions.class)))
                .thenAnswer(invocation -> server.forward(invocation.getArgument(0)));
        return api;
    }

    public interface RequestHandler {
        ResponseSpec handle(RecordedRequest request, int invocation) throws Exception;
    }

    public record ResponseSpec(int statusCode, String body, Map<String, String> headers) {
        public static ResponseSpec json(int statusCode, String body) {
            return new ResponseSpec(statusCode, body, Map.of("Content-Type", "application/json"));
        }

        public static ResponseSpec plain(int statusCode, String body) {
            return new ResponseSpec(statusCode, body, Map.of("Content-Type", "text/plain; charset=utf-8"));
        }
    }

    public static final class RecordedRequest {
        public final String method;
        public final String path;
        public final String body;
        public final Map<String, List<String>> headers;

        private RecordedRequest(String method, String path, String body, Map<String, List<String>> headers) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.headers = headers;
        }

        public String headerValue(String name) {
            if (name == null) {
                return null;
            }
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }

        public Map<String, String> formParams() {
            if (body == null || body.isBlank()) {
                return Collections.emptyMap();
            }
            Map<String, String> params = new LinkedHashMap<>();
            for (String pair : body.split("&")) {
                if (pair.isBlank()) {
                    continue;
                }
                String[] kv = pair.split("=", 2);
                String key = decode(kv[0]);
                String value = kv.length > 1 ? decode(kv[1]) : "";
                params.put(key, value);
            }
            return params;
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }

    public static final class TokenEndpointServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final AtomicInteger requestCount = new AtomicInteger();
        private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        private final RequestHandler handler;

        public TokenEndpointServer(RequestHandler handler) throws IOException {
            this.handler = Objects.requireNonNull(handler, "handler");
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            this.executor = Executors.newCachedThreadPool();
            this.server.setExecutor(executor);
            this.server.createContext("/", this::handle);
            this.server.start();
        }

        public String url(String path) {
            String normalizedPath = path == null || path.isBlank() ? "/" : path;
            if (!normalizedPath.startsWith("/")) {
                normalizedPath = "/" + normalizedPath;
            }
            return "http://127.0.0.1:" + server.getAddress().getPort() + normalizedPath;
        }

        public int requestCount() {
            return requestCount.get();
        }

        public List<RecordedRequest> requests() {
            return new ArrayList<>(requests);
        }

        public HttpRequestResponse forward(HttpRequest request) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) URI.create(request.url()).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout((int) REQUEST_TIMEOUT.toMillis());
            connection.setReadTimeout((int) REQUEST_TIMEOUT.toMillis());
            connection.setRequestMethod(request.method());
            for (HttpHeader header : request.headers()) {
                String name = header.name();
                if (name == null) {
                    continue;
                }
                String normalized = name.toLowerCase(Locale.ROOT);
                if ("host".equals(normalized) || "content-length".equals(normalized) || "connection".equals(normalized)) {
                    continue;
                }
                connection.setRequestProperty(name, header.value());
            }

            byte[] bodyBytes = request.body() != null ? request.body().getBytes() : new byte[0];
            if (bodyBytes.length > 0) {
                connection.setDoOutput(true);
                try (var output = connection.getOutputStream()) {
                    output.write(bodyBytes);
                }
            }

            int status = connection.getResponseCode();
            byte[] responseBytes;
            try (var input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                responseBytes = input != null ? input.readAllBytes() : new byte[0];
            }
            Throwable failure = handlerFailure.getAndSet(null);
            if (failure != null) {
                if (failure instanceof Exception exception) {
                    throw exception;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                throw new RuntimeException(failure);
            }

            String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
            HttpResponse response = Mockito.mock(HttpResponse.class);
            when(response.statusCode()).thenReturn((short) status);
            when(response.bodyToString()).thenReturn(responseBody);
            HttpRequestResponse requestResponse = Mockito.mock(HttpRequestResponse.class);
            when(requestResponse.request()).thenReturn(request);
            when(requestResponse.response()).thenReturn(response);
            when(requestResponse.hasResponse()).thenReturn(true);
            when(requestResponse.statusCode()).thenReturn((short) status);
            return requestResponse;
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            Map<String, List<String>> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
            RecordedRequest request = new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    new String(bodyBytes, StandardCharsets.UTF_8),
                    headers);
            requests.add(request);

            ResponseSpec response;
            try {
                response = handler.handle(request, requestCount.incrementAndGet());
            } catch (Throwable t) {
                handlerFailure.compareAndSet(null, t);
                response = ResponseSpec.plain(500, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            }

            byte[] responseBytes = (response.body() != null ? response.body() : "").getBytes(StandardCharsets.UTF_8);
            Map<String, String> responseHeaders = response.headers() != null ? response.headers() : Collections.emptyMap();
            responseHeaders.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.sendResponseHeaders(response.statusCode(), responseBytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(responseBytes);
            } finally {
                exchange.close();
            }
        }

        private static String reasonPhrase(int statusCode, String responseMessage) {
            if (responseMessage != null && !responseMessage.isBlank()) {
                return responseMessage;
            }
            return switch (statusCode) {
                case 200 -> "OK";
                case 201 -> "Created";
                case 302 -> "Found";
                case 400 -> "Bad Request";
                case 401 -> "Unauthorized";
                case 429 -> "Too Many Requests";
                case 500 -> "Internal Server Error";
                default -> "Status";
            };
        }
    }
}
