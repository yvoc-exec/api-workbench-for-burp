package burp.scripts;

public interface ScriptDependentRequestExecutor {
    ScriptDependentRequestResult runRequest(ScriptExecutionContext context, String targetNameOrId);

    ScriptDependentRequestResult sendAdHocRequest(ScriptExecutionContext context, ScriptAdHocRequest request);
}
