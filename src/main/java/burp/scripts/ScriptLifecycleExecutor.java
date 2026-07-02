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
            boolean completed = false;
            int mutationCountBeforeBlock = result.variableMutations.size();
            try {
                java.util.Map<String, Object> bindings = UnifiedScriptRuntime.buildBindings(context, block);
                sandboxEngine.execute(block.source, bindings);
                completed = true;
            } catch (GraalJsSandboxEngine.ScriptTimedOutException t) {
                trimMutations(result, mutationCountBeforeBlock);
                result.timedOut = true;
                result.timeoutMillis = t.timeoutMillis;
                context.error(t.getMessage() + scriptLabel(block), block.id, block.sourceFormat);
                break;
            } catch (GraalJsSandboxEngine.ScriptCancelledException t) {
                trimMutations(result, mutationCountBeforeBlock);
                result.cancelled = true;
                context.warn(t.getMessage() + scriptLabel(block), block.id, block.sourceFormat);
                break;
            } catch (Throwable t) {
                String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                context.error("Script error in " + (block.id != null ? block.id : "block") + ": " + message, block.id, block.sourceFormat);
                if (context.scriptErrorsStopExecution) {
                    break;
                }
            } finally {
                if (completed) {
                    ScriptBindingsFactory.applyRequestMutation(context);
                }
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

    private String scriptLabel(ScriptBlock block) {
        if (block == null) {
            return "";
        }
        String id = block.id != null && !block.id.isBlank() ? block.id : null;
        String name = block.metadata != null ? block.metadata.get("name") : null; name = name != null && !name.isBlank() ? name : null;
        if (id == null && name == null) {
            return "";
        }
        return " in " + (name != null ? name : id);
    }

    private void trimMutations(ScriptExecutionResult result, int size) {
        while (result.variableMutations.size() > size) {
            result.variableMutations.remove(result.variableMutations.size() - 1);
        }
    }
}
