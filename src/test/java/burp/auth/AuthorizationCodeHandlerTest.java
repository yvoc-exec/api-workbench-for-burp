package burp.auth;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationCodeHandlerTest {
    @Test
    void parsesLoopbackRedirectEndpoint() throws Exception {
        AuthorizationCodeHandler.CallbackEndpoint endpoint =
                AuthorizationCodeHandler.parseCallbackEndpoint("http://127.0.0.1:9988/oauth/callback");

        assertThat(endpoint.host()).isEqualTo("127.0.0.1");
        assertThat(endpoint.port()).isEqualTo(9988);
        assertThat(endpoint.path()).isEqualTo("/oauth/callback");
    }

    @Test
    void defaultRedirectEndpointMatchesExistingWorkflow() throws Exception {
        AuthorizationCodeHandler.CallbackEndpoint endpoint =
                AuthorizationCodeHandler.parseCallbackEndpoint("http://localhost:9876/callback");

        assertThat(endpoint.host()).isEqualTo("localhost");
        assertThat(endpoint.port()).isEqualTo(9876);
        assertThat(endpoint.path()).isEqualTo("/callback");
    }

    @Test
    void rejectsNonLoopbackRedirectEndpoint() {
        assertThatThrownBy(() -> AuthorizationCodeHandler.parseCallbackEndpoint("https://example.test/callback"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void acceptsAlternateLoopbackAddressesAndDefaultsMissingPath() {
        AuthorizationCodeHandler.CallbackEndpoint endpoint =
                AuthorizationCodeHandler.parseCallbackEndpoint("http://127.0.0.2:9988");

        assertThat(endpoint.host()).isEqualTo("127.0.0.2");
        assertThat(endpoint.port()).isEqualTo(9988);
        assertThat(endpoint.path()).isEqualTo("/callback");
    }

    @Test
    void callbackListenerCompletesForMatchingCodeAndState() throws Exception {
        int port = freePort();
        AuthorizationCodeHandler handler = new AuthorizationCodeHandler(null);
        CompletableFuture<String> future = startCallbackListener(
                handler,
                "expected-state",
                new AuthorizationCodeHandler.CallbackEndpoint("127.0.0.1", port, "/oauth/callback"));

        sendCallback(port, "/oauth/callback?code=code-123&state=expected-state");

        assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("code-123");
    }

    @Test
    void callbackListenerRejectsMismatchedState() throws Exception {
        int port = freePort();
        AuthorizationCodeHandler handler = new AuthorizationCodeHandler(null);
        CompletableFuture<String> future = startCallbackListener(
                handler,
                "expected-state",
                new AuthorizationCodeHandler.CallbackEndpoint("127.0.0.1", port, "/oauth/callback"));

        sendCallback(port, "/oauth/callback?code=code-123&state=wrong-state");

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(Exception.class)
                .hasMessageContaining("Invalid state or missing code");
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<String> startCallbackListener(AuthorizationCodeHandler handler,
                                                                   String expectedState,
                                                                   AuthorizationCodeHandler.CallbackEndpoint endpoint) throws Exception {
        Method method = AuthorizationCodeHandler.class.getDeclaredMethod(
                "startCallbackListener",
                String.class,
                AuthorizationCodeHandler.CallbackEndpoint.class);
        method.setAccessible(true);
        return (CompletableFuture<String>) method.invoke(handler, expectedState, endpoint);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void sendCallback(int port, String pathAndQuery) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (true) {
            try (Socket socket = new Socket("127.0.0.1", port);
                 OutputStream out = socket.getOutputStream()) {
                String request = "GET " + pathAndQuery + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n";
                out.write(request.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            } catch (Exception e) {
                if (System.currentTimeMillis() >= deadline) {
                    throw e;
                }
                Thread.sleep(25L);
            }
        }
    }
}
