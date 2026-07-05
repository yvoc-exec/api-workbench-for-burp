package burp.testsupport;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import org.mockito.Mockito;

import javax.swing.JPanel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

final class MockUiApiFactory {
    private MockUiApiFactory() {
    }

    static MontoyaApi create(Function<HttpRequest, HttpRequestResponse> responseFactory) {
        AtomicInteger fallbackSendCount = new AtomicInteger();
        MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        when(api.userInterface().createHttpRequestEditor(any(EditorOptions.class))).thenAnswer(invocation -> {
            HttpRequestEditor editor = Mockito.mock(HttpRequestEditor.class);
            when(editor.uiComponent()).thenReturn(new JPanel());
            return editor;
        });
        when(api.userInterface().createHttpResponseEditor(any(EditorOptions.class))).thenAnswer(invocation -> {
            HttpResponseEditor editor = Mockito.mock(HttpResponseEditor.class);
            when(editor.uiComponent()).thenReturn(new JPanel());
            return editor;
        });
        when(api.http().sendRequest(any(HttpRequest.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            HttpRequestResponse response = applyResponseFactory(responseFactory, request);
            return response != null
                    ? response
                    : RunnerScriptTestFixtures.mockResponse(
                            200,
                            "OK-" + fallbackSendCount.incrementAndGet(),
                            "text/plain");
        });
        when(api.http().sendRequest(any(HttpRequest.class), any(RequestOptions.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            HttpRequestResponse response = applyResponseFactory(responseFactory, request);
            return response != null
                    ? response
                    : RunnerScriptTestFixtures.mockResponse(
                            200,
                            "OK-" + fallbackSendCount.incrementAndGet(),
                            "text/plain");
        });
        return api;
    }

    private static HttpRequestResponse applyResponseFactory(
            Function<HttpRequest, HttpRequestResponse> responseFactory,
            HttpRequest request) {
        return responseFactory != null
                ? responseFactory.apply(withStableRoutingUrl(request))
                : null;
    }

    private static HttpRequest withStableRoutingUrl(HttpRequest request) {
        if (request == null) {
            return null;
        }
        String route = firstNonBlank(
                safeString(request, "path"),
                safeString(request, "url"),
                requestTarget(String.valueOf(request)));
        if (route == null || route.isBlank()) {
            return request;
        }
        String stableRoute = route;
        return (HttpRequest) Proxy.newProxyInstance(
                HttpRequest.class.getClassLoader(),
                new Class<?>[]{HttpRequest.class},
                (proxy, method, args) -> invokeRequest(request, stableRoute, proxy, method, args));
    }

    private static Object invokeRequest(
            HttpRequest delegate,
            String stableRoute,
            Object proxy,
            Method method,
            Object[] args) throws Throwable {
        return switch (method.getName()) {
            case "url", "path" -> stableRoute;
            case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> delegate.toString();
            default -> invokeDelegate(delegate, method, args);
        };
    }

    private static Object invokeDelegate(HttpRequest delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() != null ? failure.getCause() : failure;
        }
    }

    private static String safeString(HttpRequest request, String methodName) {
        try {
            Method method = request.getClass().getMethod(methodName);
            Object value = method.invoke(request);
            return value != null ? String.valueOf(value) : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static String requestTarget(String rawRequest) {
        if (rawRequest == null || rawRequest.isBlank()) {
            return null;
        }
        int lineEnd = rawRequest.indexOf('\n');
        String firstLine = (lineEnd >= 0 ? rawRequest.substring(0, lineEnd) : rawRequest).trim();
        String[] parts = firstLine.split("\\s+", 3);
        return parts.length >= 2 ? parts[1] : null;
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
