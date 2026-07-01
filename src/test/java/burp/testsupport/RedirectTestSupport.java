package burp.testsupport;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.requests.HttpRequest;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;

public final class RedirectTestSupport {
    private RedirectTestSupport() {
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    public static <T> T withHttpFactories(ThrowingSupplier<T> supplier) {
        try (MockedStatic<HttpRequest> requestFactory = Mockito.mockStatic(HttpRequest.class);
             MockedStatic<HttpService> serviceFactory = Mockito.mockStatic(HttpService.class);
             MockedStatic<ByteArray> byteArrayFactory = Mockito.mockStatic(ByteArray.class);
             MockedStatic<RequestOptions> requestOptionsFactory = Mockito.mockStatic(RequestOptions.class)) {
            serviceFactory.when(() -> HttpService.httpService(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean()))
                    .thenReturn(Mockito.mock(HttpService.class));
            requestFactory.when(() -> HttpRequest.httpRequest(Mockito.any(HttpService.class), Mockito.any(ByteArray.class)))
                    .thenAnswer(invocation -> mockRequest(((ByteArray) invocation.getArgument(1)).getBytes()));
            byteArrayFactory.when(() -> ByteArray.byteArray((byte[]) Mockito.any(byte[].class)))
                    .thenAnswer(invocation -> mockByteArray(toBytes(invocation.getArguments())));
            RequestOptions options = Mockito.mock(RequestOptions.class);
            requestOptionsFactory.when(RequestOptions::requestOptions).thenReturn(options);
            Mockito.when(options.withRedirectionMode(Mockito.any())).thenReturn(options);
            return supplier.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static HttpRequest mockRequest(byte[] rawBytes) {
        HttpRequest request = Mockito.mock(HttpRequest.class);
        byte[] bytes = rawBytes != null ? rawBytes.clone() : new byte[0];
        ByteArray byteArray = mockByteArray(bytes);
        Mockito.when(request.toByteArray()).thenReturn(byteArray);
        Mockito.when(request.method()).thenReturn(parseMethod(bytes));
        Mockito.when(request.toString()).thenReturn(new String(bytes, StandardCharsets.UTF_8));
        return request;
    }

    private static ByteArray mockByteArray(byte[] rawBytes) {
        ByteArray byteArray = Mockito.mock(ByteArray.class);
        byte[] bytes = rawBytes != null ? rawBytes.clone() : new byte[0];
        Mockito.when(byteArray.getBytes()).thenReturn(bytes);
        Mockito.when(byteArray.length()).thenReturn(bytes.length);
        return byteArray;
    }

    private static byte[] toBytes(Object[] values) {
        if (values == null || values.length == 0) {
            return new byte[0];
        }
        if (values.length == 1 && values[0] instanceof byte[] bytes) {
            return bytes;
        }
        if (values.length == 1 && values[0] instanceof Byte[] boxed) {
            byte[] bytes = new byte[boxed.length];
            for (int i = 0; i < boxed.length; i++) {
                bytes[i] = boxed[i] != null ? boxed[i] : 0;
            }
            return bytes;
        }
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value instanceof Byte single) {
                bytes[i] = single;
            } else if (value instanceof Number number) {
                bytes[i] = number.byteValue();
            } else if (value instanceof byte[] raw && raw.length > 0) {
                bytes[i] = raw[0];
            } else {
                bytes[i] = 0;
            }
        }
        return bytes;
    }

    private static String parseMethod(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) {
            return "GET";
        }
        String raw = new String(rawBytes, StandardCharsets.UTF_8);
        int space = raw.indexOf(' ');
        return space > 0 ? raw.substring(0, space) : "GET";
    }
}
