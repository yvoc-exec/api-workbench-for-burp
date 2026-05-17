package burp.ui;

import burp.models.ApiCollection;
import burp.models.UnresolvedVariableIssue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class UnresolvedVariablesDialogTest {

    @Test
    void applyEnteredValuesWritesRuntimeVarsToMatchingCollections() {
        ApiCollection alpha = new ApiCollection();
        alpha.name = "Alpha";
        AtomicInteger alphaChanges = new AtomicInteger();
        alpha.addChangeListener(alphaChanges::incrementAndGet);

        ApiCollection beta = new ApiCollection();
        beta.name = "Beta";
        AtomicInteger betaChanges = new AtomicInteger();
        beta.addChangeListener(betaChanges::incrementAndGet);

        List<UnresolvedVariableIssue> issues = List.of(
                new UnresolvedVariableIssue("Alpha", "Request A", "baseUrl", "url"),
                new UnresolvedVariableIssue("Beta", "Request B", "baseUrl", "header:value"),
                new UnresolvedVariableIssue("Beta", "Request B", "apiKey", "auth:apikey")
        );

        UnresolvedVariablesDialog.applyEnteredValuesToCollections(
                List.of(alpha, beta),
                issues,
                Map.of(
                        "baseUrl", "https://api.example.com",
                        "apiKey", "secret"
                )
        );

        assertThat(alpha.runtimeVars).containsEntry("baseUrl", "https://api.example.com");
        assertThat(beta.runtimeVars)
                .containsEntry("baseUrl", "https://api.example.com")
                .containsEntry("apiKey", "secret");
        assertThat(alphaChanges.get()).isEqualTo(1);
        assertThat(betaChanges.get()).isEqualTo(1);
    }
}
