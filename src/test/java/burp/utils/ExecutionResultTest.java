package burp.utils;

import burp.models.RunnerResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ExecutionResultTest {

    @Test
    void assertionsListIsWritableAndReadable() {
        ExecutionResult result = new ExecutionResult();
        assertThat(result.assertions).isEmpty();

        result.assertions.add(new RunnerResult.AssertionResult("Status 200", true, "200", "200"));
        assertThat(result.assertions).hasSize(1);
        assertThat(result.assertions.get(0).name).isEqualTo("Status 200");
    }

    @Test
    void extractedVarsMapIsWritableAndReadable() {
        ExecutionResult result = new ExecutionResult();
        result.extractedVars.put("token", "abc123");
        assertThat(result.extractedVars).containsEntry("token", "abc123");
    }

    @Test
    void resolvedUrlFieldExists() {
        ExecutionResult result = new ExecutionResult();
        result.resolvedUrl = "https://example.com/api/users";
        assertThat(result.resolvedUrl).isEqualTo("https://example.com/api/users");
    }
}
