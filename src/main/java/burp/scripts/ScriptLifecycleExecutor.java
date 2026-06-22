package burp.scripts;

import java.util.List;

public class ScriptLifecycleExecutor {
    private final GraalJsSandboxEngine sandboxEngine;

    public ScriptLifecycleExecutor(GraalJsSandboxEngine sandboxEngine) {
        this.sandboxEngine = sandboxEngine;
    }

    public ScriptExecutionResult execute(ScriptExecutionContext context, List<ScriptBlock> blocks) {
        ScriptExecutionResult result = context != null ? context.result : new ScriptExecutionResult();
        if (context == null || blocks == null || blocks.isEmpty()) {
            return result;
        }
        result.engineName = sandboxEngine != null ? sandboxEngine.getEngineName() : "Unavailable";
        for (ScriptBlock block : blocks) {
            if (block == null || !block.enabled || block.source == null || block.source.isBlank()) {
                continue;
            }
            try {
                java.util.Map<String, Object> bindings = UnifiedScriptRuntime.buildBindings(context, block);
                sandboxEngine.execute(block.source, bindings);
            } catch (Throwable t) {
                String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                context.error("Script error in " + (block.id != null ? block.id : "block") + ": " + message, block.id, block.sourceFormat);
                if (context.scriptErrorsStopExecution) {
                    break;
                }
            } finally {
                ScriptBindingsFactory.applyRequestMutation(context);
            }
            if (result.flowControl == ScriptFlowControl.STOP_RUN) {
                break;
            }
            if (result.flowControl == ScriptFlowControl.SKIP_REQUEST || result.flowControl == ScriptFlowControl.SET_NEXT_REQUEST) {
                break;
            }
        }
        return result;
    }
}
