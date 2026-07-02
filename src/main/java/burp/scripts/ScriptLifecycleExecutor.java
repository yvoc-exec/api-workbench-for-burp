package burp.scripts;

import burp.models.ApiRequest;

import java.util.List;

public class ScriptLifecycleExecutor {
    private final GraalJsSandboxEngine sandboxEngine;

    public ScriptLifecycleExecutor(GraalJsSandboxEngine sandboxEngine) {
        this.sandboxEngine = sandboxEngine;
    }

    public ScriptExecutionResult execute(ScriptExecutionContext context, List<ScriptBlock> blocks) {
        ScriptExecutionResult result = context != null ? context.result : new ScriptExecutionResult();
        if (context == null || blocks == null || blocks.isEmpty()) {
            if (context != null) {
                result.effectiveVariables.clear();
                result.effectiveVariables.putAll(context.variableStore.effectiveVariablesSnapshot());
                result.mutatedRequest = context.request;
            }
            return result;
        }
        if (sandboxEngine == null) {
            context.error("Script sandbox is unavailable.", null, null);
            result.effectiveVariables.clear();
            result.effectiveVariables.putAll(context.variableStore.effectiveVariablesSnapshot());
            result.mutatedRequest = context.request;
            return result;
        }
        result.engineName = sandboxEngine.getEngineName();
        VariableScopeStore.Snapshot phaseCheckpoint = context.variableStore.checkpoint();
        ApiRequest phaseRequestSnapshot = ScriptExecutionContext.copyRequest(context.request);
        int phaseMutationCount = result.variableMutations.size();
        for (ScriptBlock block : blocks) {
            if (block == null || !block.enabled || block.source == null || block.source.isBlank()) {
                continue;
            }
            VariableScopeStore.Snapshot blockCheckpoint = context.variableStore.checkpoint();
            ApiRequest blockRequestSnapshot = ScriptExecutionContext.copyRequest(context.request);
            int mutationCountBeforeBlock = result.variableMutations.size();
            try {
                java.util.Map<String, Object> bindings = UnifiedScriptRuntime.buildBindings(context, block);
                sandboxEngine.execute(block.source, bindings, () -> {
                    ScriptBindingsFactory.applyRequestMutation(context);
                    context.variableStore.refreshRequestState();
                });
            } catch (GraalJsSandboxEngine.ScriptTimedOutException t) {
                context.restoreRequest(phaseRequestSnapshot);
                context.variableStore.restore(phaseCheckpoint);
                trimMutations(result, phaseMutationCount);
                result.timedOut = true;
                result.success = false;
                result.timeoutMillis = t.timeoutMillis;
                context.error(t.getMessage() + scriptLabel(block), block.id, block.sourceFormat);
                break;
            } catch (GraalJsSandboxEngine.ScriptCancelledException t) {
                context.restoreRequest(phaseRequestSnapshot);
                context.variableStore.restore(phaseCheckpoint);
                trimMutations(result, phaseMutationCount);
                result.cancelled = true;
                result.success = false;
                context.warn(t.getMessage() + scriptLabel(block), block.id, block.sourceFormat);
                break;
            } catch (Throwable t) {
                context.restoreRequest(blockRequestSnapshot);
                context.variableStore.restore(blockCheckpoint);
                trimMutations(result, mutationCountBeforeBlock);
                String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                context.error("Script error in " + (block.id != null ? block.id : "block") + ": " + message, block.id, block.sourceFormat);
                if (context.scriptErrorsStopExecution) {
                    break;
                }
            }
            if (result.flowControl == ScriptFlowControl.STOP_RUN) {
                break;
            }
            if (result.flowControl == ScriptFlowControl.SKIP_REQUEST || result.flowControl == ScriptFlowControl.SET_NEXT_REQUEST) {
                break;
            }
        }
        result.mutatedRequest = context.request;
        result.effectiveVariables.clear();
        result.effectiveVariables.putAll(context.variableStore.effectiveVariablesSnapshot());
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
