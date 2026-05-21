package burp.auth;

import org.junit.jupiter.api.Test;

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
}
