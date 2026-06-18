package burp.auth;

import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OAuth2ManagerDiagnosticsTest {

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        DiagnosticStore.getInstance().setCaptureEnabled(false);
        DiagnosticStore.getInstance().clear();
    }

    @Test
    void clearTokensRecordsDiagnosticsEvent() {
        DiagnosticStore.getInstance().setCaptureEnabled(true);
        DiagnosticStore.getInstance().clear();

        OAuth2Manager manager = new OAuth2Manager(Mockito.mock(burp.api.montoya.MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS));
        manager.clearTokens();

        assertThat(DiagnosticStore.getInstance().snapshot())
                .anySatisfy(event -> {
                    assertThat(event.operation).isEqualTo(DiagnosticOperation.OAUTH2_TOKEN_FETCH);
                    assertThat(event.severity).isEqualTo(DiagnosticSeverity.INFO);
                    assertThat(event.message).isEqualTo("All OAuth2 tokens cleared");
                });
        DiagnosticStore.getInstance().clear();
    }

    @Test
    void refreshIfNeededWithInvalidVariablesRecordsDiagnostic() {
        DiagnosticStore.getInstance().setCaptureEnabled(true);
        DiagnosticStore.getInstance().clear();

        OAuth2Manager manager = new OAuth2Manager(Mockito.mock(burp.api.montoya.MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS));
        manager.refreshIfNeeded(Collections.emptyMap());

        assertThat(DiagnosticStore.getInstance().snapshot())
                .anySatisfy(event -> {
                    assertThat(event.operation).isEqualTo(DiagnosticOperation.OAUTH2_TOKEN_FETCH);
                    assertThat(event.message).isEqualTo("OAuth2 refresh check skipped");
                    assertThat(event.severity).isEqualTo(DiagnosticSeverity.DEBUG);
                });
        DiagnosticStore.getInstance().clear();
    }

    @Test
    void acquireTokenNullConfigRecordsErrorDiagnostics() {
        DiagnosticStore.getInstance().setCaptureEnabled(true);
        DiagnosticStore.getInstance().clear();

        OAuth2Manager manager = new OAuth2Manager(Mockito.mock(burp.api.montoya.MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS));
        assertThrows(Exception.class, () -> manager.acquireToken(null));

        assertThat(DiagnosticStore.getInstance().snapshot())
                .anySatisfy(event -> {
                    assertThat(event.operation).isEqualTo(DiagnosticOperation.OAUTH2_TOKEN_FETCH);
                    assertThat(event.message).isEqualTo("OAuth2 configuration is null");
                    assertThat(event.severity).isEqualTo(DiagnosticSeverity.ERROR);
                });
        DiagnosticStore.getInstance().clear();
    }
}
