package burp.runner;

import burp.models.RunnerResult;
import burp.utils.ExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CollectionRunnerTest {

    @Test
    void mergeExecutionVariablesRemovesBeforeAddingNewValues() {
        Map<String, String> runnerExtractedVars = new HashMap<>();
        runnerExtractedVars.put("stale", "old");
        runnerExtractedVars.put("keep", "yes");

        RunnerResult result = new RunnerResult();
        result.extractedVariables.put("stale", "old");
        result.extractedVariables.put("keep", "yes");

        ExecutionResult exec = new ExecutionResult();
        exec.removedVars.add("stale");
        exec.extractedVars.put("fresh", "new");
        exec.extractedVars.put("keep", "updated");

        CollectionRunner.mergeExecutionVariables(runnerExtractedVars, result, exec);

        assertThat(runnerExtractedVars).doesNotContainKey("stale");
        assertThat(result.extractedVariables).doesNotContainKey("stale");
        assertThat(runnerExtractedVars).containsEntry("keep", "updated");
        assertThat(result.extractedVariables).containsEntry("keep", "updated");
        assertThat(runnerExtractedVars).containsEntry("fresh", "new");
        assertThat(result.extractedVariables).containsEntry("fresh", "new");
    }
}
