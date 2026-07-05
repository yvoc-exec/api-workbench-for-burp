package burp.testsupport;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.Method;
import java.util.Set;

public final class CoreUiIsolationCondition implements ExecutionCondition {
    private static final String TARGET_CLASS = "burp.ui.ImporterPanelRunnerQueueTest";
    private static final Set<String> DISPLAY_ONLY_METHODS = Set.of(
            "cancelButtonWhilePausedUsesRealRunnerAndReturnsIdleControls",
            "cancelButtonDuringDelayUsesRealRunnerAndKeepsCompletedRows");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Class<?> testClass = context.getTestClass().orElse(null);
        Method testMethod = context.getTestMethod().orElse(null);
        if (GraphicsEnvironment.isHeadless()
                && testClass != null
                && testMethod != null
                && TARGET_CLASS.equals(testClass.getName())
                && DISPLAY_ONLY_METHODS.contains(testMethod.getName())) {
            return ConditionEvaluationResult.disabled(
                    "Display-only Runner Preview interaction is covered by the Xvfb UI profile; "
                            + "headless lifecycle coverage runs separately.");
        }
        return ConditionEvaluationResult.enabled("No display-only isolation required.");
    }
}
