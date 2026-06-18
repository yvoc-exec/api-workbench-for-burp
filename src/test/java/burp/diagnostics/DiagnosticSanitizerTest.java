package burp.diagnostics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticSanitizerTest {

    @Test
    void sanitizeTextMasksAuthorizationCookieAndTokenSecrets() {
        String input = """
                Authorization: Bearer secret-token
                Cookie: session=abc123
                Set-Cookie: id=xyz
                access_token=token-123
                refresh_token: refresh-456
                client_secret=client-789
                password=my-password
                api_key=api-000
                basic dXNlcjpzZWNyZXQ=
                """;

        String sanitized = DiagnosticSanitizer.sanitizeText(input);

        assertThat(sanitized).doesNotContain("secret-token");
        assertThat(sanitized).doesNotContain("abc123");
        assertThat(sanitized).doesNotContain("token-123");
        assertThat(sanitized).doesNotContain("refresh-456");
        assertThat(sanitized).doesNotContain("client-789");
        assertThat(sanitized).doesNotContain("my-password");
        assertThat(sanitized).contains("AUTHORIZATION: ***");
        assertThat(sanitized).contains("COOKIE: ***");
    }
}
