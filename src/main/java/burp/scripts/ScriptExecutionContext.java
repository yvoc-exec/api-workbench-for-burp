package burp.scripts;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerResult;
import burp.api.montoya.MontoyaApi;
import burp.parser.VariableResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScriptExecutionContext {
    public final ApiCollection collection;
    public final ApiRequest originalRequest;
    public final ApiRequest request;
    public final EnvironmentProfile activeEnvironment;
    public final MontoyaApi api;
    public final VariableScopeStore variableStore;
    public final ScriptExecutionResult result = new ScriptExecutionResult();
    public final ExecutionSource executionSource;
    public final String source;
    public final int attemptNumber;
    public final List<String> unresolvedVariables = new ArrayList<>();
    public ScriptBindingsFactory.RequestApi requestBinding;

    public String responseText;
    public int responseStatusCode;
    public String responseStatusText;
    public long responseTimeMs;
    public Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
    public Object parsedResponseJson;
    public RunnerResult runnerResult;
    public ScriptDependentRequestExecutor dependentRequestExecutor;
    public boolean runnerOnlyFlowControlsAllowed = true;
    public boolean scriptErrorsStopExecution = false;

    public ScriptExecutionContext(ApiCollection collection,
                                  ApiRequest originalRequest,
                                  EnvironmentProfile activeEnvironment,
                                  ExecutionSource executionSource,
                                  int attemptNumber) {
        this(null, collection, originalRequest, activeEnvironment, executionSource, attemptNumber);
    }

    public ScriptExecutionContext(ApiCollection collection,
                                  ApiRequest originalRequest,
                                  EnvironmentProfile activeEnvironment,
                                  String source,
                                  int attemptNumber) {
        this(collection, originalRequest, activeEnvironment, UnifiedScriptRuntime.executionSourceFromString(source), attemptNumber);
    }

    public ScriptExecutionContext(MontoyaApi api,
                                  ApiCollection collection,
                                  ApiRequest originalRequest,
                                  EnvironmentProfile activeEnvironment,
                                  ExecutionSource executionSource,
                                  int attemptNumber) {
        this.api = api;
        this.collection = collection;
        this.originalRequest = originalRequest;
        this.request = copyRequest(originalRequest);
        this.activeEnvironment = activeEnvironment;
        this.variableStore = new VariableScopeStore(collection, this.request, activeEnvironment);
        this.executionSource = executionSource != null ? executionSource : ExecutionSource.WORKBENCH_SEND;
        this.source = this.executionSource.name();
        this.attemptNumber = Math.max(1, attemptNumber);
    }

    public ScriptExecutionContext(MontoyaApi api,
                                  ApiCollection collection,
                                  ApiRequest originalRequest,
                                  EnvironmentProfile activeEnvironment,
                                  String source,
                                  int attemptNumber) {
        this(api, collection, originalRequest, activeEnvironment, UnifiedScriptRuntime.executionSourceFromString(source), attemptNumber);
    }

    public VariableResolver resolver() {
        return variableStore.resolver();
    }

    public String resolve(String input) {
        return variableStore.resolve(input);
    }

    public void log(String level, String message, String scriptId, String scriptName) {
        result.logs.add(new ScriptLogEntry(level, message, scriptId, scriptName));
    }

    public void warn(String message, String scriptId, String scriptName) {
        result.warnings.add(message);
        log("warn", message, scriptId, scriptName);
    }

    public void error(String message, String scriptId, String scriptName) {
        result.errors.add(message);
        log("error", message, scriptId, scriptName);
        result.success = false;
    }

    public void addAssertion(ScriptAssertionResult assertion) {
        if (assertion != null) {
            result.assertions.add(assertion);
            if (!assertion.passed) {
                result.success = false;
            }
        }
    }

    public void addMutation(ScriptVariableMutation mutation) {
        if (mutation != null) {
            result.variableMutations.add(mutation);
        }
    }

    public void setFlowControl(ScriptFlowControl flowControl, String nextRequestName, String nextRequestId, String message) {
        result.flowControl = flowControl != null ? flowControl : ScriptFlowControl.CONTINUE;
        result.nextRequestName = nextRequestName;
        result.nextRequestId = nextRequestId;
        result.message = message;
    }

    public ScriptDependentRequestResult runDependentRequest(String targetNameOrId) {
        if (executionSource != ExecutionSource.RUNNER) {
            warn("runRequest is ignored outside Runner mode.", null, null);
            return ScriptDependentRequestResult.ignored("runRequest is ignored outside Runner mode.");
        }
        if (dependentRequestExecutor == null) {
            warn("runRequest is recognized but no dependent executor is available.", null, null);
            return ScriptDependentRequestResult.ignored("runRequest is recognized but no dependent executor is available.");
        }
        ScriptDependentRequestResult dependent = dependentRequestExecutor.runRequest(this, targetNameOrId);
        if (dependent != null && dependent.executed) {
            result.flowControl = ScriptFlowControl.RUN_REQUEST;
            result.nextRequestName = dependent.resolvedRequestName != null ? dependent.resolvedRequestName : targetNameOrId;
            result.nextRequestId = dependent.resolvedRequestId;
            result.message = dependent.message != null ? dependent.message : "runRequest";
            result.dependentRequestResults.add(dependent);
            result.dependentRequestCount++;
        } else if (dependent != null) {
            String message = dependent.warningMessage != null && !dependent.warningMessage.isBlank()
                    ? dependent.warningMessage
                    : dependent.errorMessage;
            if (message != null && !message.isBlank()) {
                warn(message, null, null);
            }
        }
        return dependent;
    }

    public ScriptDependentRequestResult sendAdHocRequest(ScriptAdHocRequest request) {
        if (executionSource != ExecutionSource.RUNNER) {
            warn("sendAdHocRequest is ignored outside Runner mode.", null, null);
            return ScriptDependentRequestResult.ignored("sendAdHocRequest is ignored outside Runner mode.");
        }
        if (dependentRequestExecutor == null) {
            warn("sendAdHocRequest is recognized but no dependent executor is available.", null, null);
            return ScriptDependentRequestResult.ignored("sendAdHocRequest is recognized but no dependent executor is available.");
        }
        ScriptDependentRequestResult dependent = dependentRequestExecutor.sendAdHocRequest(this, request);
        if (dependent != null && dependent.executed) {
            result.flowControl = ScriptFlowControl.SEND_AD_HOC_REQUEST;
            result.nextRequestName = dependent.resolvedRequestName;
            result.nextRequestId = dependent.resolvedRequestId;
            result.message = dependent.message != null ? dependent.message : "sendAdHocRequest";
            result.dependentRequestResults.add(dependent);
            result.dependentRequestCount++;
        } else if (dependent != null) {
            String message = dependent.warningMessage != null && !dependent.warningMessage.isBlank()
                    ? dependent.warningMessage
                    : dependent.errorMessage;
            if (message != null && !message.isBlank()) {
                warn(message, null, null);
            }
        }
        return dependent;
    }

    public ScriptVariableMutation setEnvironment(String key, String value, boolean persist, String scriptId, String scriptName) {
        ScriptVariableMutation mutation = variableStore.setEnvironment(key, value, persist, scriptId, scriptName);
        addMutation(mutation);
        return mutation;
    }

    public ScriptVariableMutation unsetEnvironment(String key, boolean persist, String scriptId, String scriptName) {
        ScriptVariableMutation mutation = variableStore.unsetEnvironment(key, persist, scriptId, scriptName);
        addMutation(mutation);
        return mutation;
    }

    public ScriptVariableMutation setCollection(String key, String value, boolean persist, String scriptId, String scriptName) {
        ScriptVariableMutation mutation = variableStore.setCollection(key, value, persist, scriptId, scriptName);
        addMutation(mutation);
        return mutation;
    }

    public ScriptVariableMutation unsetCollection(String key, boolean persist, String scriptId, String scriptName) {
        ScriptVariableMutation mutation = variableStore.unsetCollection(key, persist, scriptId, scriptName);
        addMutation(mutation);
        return mutation;
    }

    public ScriptVariableMutation setFolder(String key, String value, boolean persist, String scriptId, String scriptName) {
        ScriptVariableMutation mutation = variableStore.setFolder(key, value, persist, scriptId, scriptName);
        addMutation(mutation);
        return mutation;
    }

    public ScriptVariableMutation unsetFolder(String key, boolean persist, String scriptId, String scriptName) {
        ScriptVariableMutation mutation = variableStore.unsetFolder(key, persist, scriptId, scriptName);
        addMutation(mutation);
        return mutation;
    }

    public ScriptVariableMutation setRequestVar(String key, String value, String scriptId, String scriptName) {
        ScriptVariableMutation mutation = variableStore.setRequest(key, value, scriptId, scriptName);
        addMutation(mutation);
        return mutation;
    }

    public ScriptVariableMutation unsetRequestVar(String key, String scriptId, String scriptName) {
        ScriptVariableMutation mutation = variableStore.unsetRequest(key, scriptId, scriptName);
        addMutation(mutation);
        return mutation;
    }

    public ScriptVariableMutation setLocalVar(String key, String value, String scriptId, String scriptName) {
        ScriptVariableMutation mutation = variableStore.setLocal(key, value, scriptId, scriptName);
        addMutation(mutation);
        return mutation;
    }

    public ScriptVariableMutation unsetLocalVar(String key, String scriptId, String scriptName) {
        ScriptVariableMutation mutation = variableStore.unsetLocal(key, scriptId, scriptName);
        addMutation(mutation);
        return mutation;
    }

    public ScriptVariableMutation setGlobal(String key, String value, String scriptId, String scriptName) {
        ScriptVariableMutation mutation = variableStore.setGlobal(key, value, scriptId, scriptName);
        addMutation(mutation);
        return mutation;
    }

    public ScriptVariableMutation unsetGlobal(String key, String scriptId, String scriptName) {
        ScriptVariableMutation mutation = variableStore.unsetGlobal(key, scriptId, scriptName);
        addMutation(mutation);
        return mutation;
    }

    public String resolvedRequestText() {
        return request != null ? request.url : "";
    }

    public static ApiRequest copyRequest(ApiRequest source) {
        if (source == null) {
            return new ApiRequest();
        }
        ApiRequest copy = new ApiRequest();
        copy.id = source.id;
        copy.name = source.name;
        copy.path = source.path;
        copy.sourceCollection = source.sourceCollection;
        copy.method = source.method;
        copy.url = source.url;
        copy.description = source.description;
        copy.editorMaterialized = source.editorMaterialized;
        copy.buildMode = source.buildMode;
        copy.suppressedAutoHeaders = source.suppressedAutoHeaders != null
                ? new java.util.LinkedHashSet<>(source.suppressedAutoHeaders)
                : new java.util.LinkedHashSet<>();
        copy.disabled = source.disabled;
        copy.sequenceOrder = source.sequenceOrder;
        copy.authInherited = source.authInherited;
        copy.authExplicitlyDisabled = source.authExplicitlyDisabled;
        copy.authSource = source.authSource;
        copy.authOverrideMode = source.authOverrideMode;
        copy.auth = copyAuth(source.auth);
        copy.explicitAuth = copyAuth(source.explicitAuth);
        copy.headers = copyHeaders(source.headers);
        copy.body = copyBody(source.body);
        copy.variables = copyVariables(source.variables);
        copy.preRequestScripts = copyScripts(source.preRequestScripts);
        copy.postResponseScripts = copyScripts(source.postResponseScripts);
        copy.scriptBlocks = copyScriptBlocks(source.scriptBlocks);
        if (source.hasBody() && copy.body == null) {
            copy.body = new ApiRequest.Body();
            copy.body.mode = source.body != null ? source.body.mode : null;
        }
        return copy;
    }

    private static ApiRequest.Auth copyAuth(ApiRequest.Auth source) {
        if (source == null) {
            return null;
        }
        ApiRequest.Auth copy = new ApiRequest.Auth();
        copy.type = source.type;
        if (source.properties != null) {
            copy.properties.putAll(source.properties);
        }
        return copy;
    }

    private static List<ApiRequest.Header> copyHeaders(List<ApiRequest.Header> headers) {
        List<ApiRequest.Header> out = new ArrayList<>();
        if (headers == null) {
            return out;
        }
        for (ApiRequest.Header header : headers) {
            if (header == null) {
                continue;
            }
            out.add(new ApiRequest.Header(header.key, header.value, header.disabled));
        }
        return out;
    }

    private static ApiRequest.Body copyBody(ApiRequest.Body source) {
        if (source == null) {
            return null;
        }
        ApiRequest.Body copy = new ApiRequest.Body();
        copy.mode = source.mode;
        copy.raw = source.raw;
        copy.contentType = source.contentType;
        if (source.graphql != null) {
            copy.graphql = new ApiRequest.Body.GraphQL();
            copy.graphql.query = source.graphql.query;
            copy.graphql.variables = source.graphql.variables;
        }
        if (source.urlencoded != null) {
            copy.urlencoded = new ArrayList<>();
            for (ApiRequest.Body.FormField field : source.urlencoded) {
                if (field == null) {
                    continue;
                }
                ApiRequest.Body.FormField copyField = new ApiRequest.Body.FormField(field.key, field.value);
                copyField.type = field.type;
                copyField.fileUpload = field.fileUpload;
                copyField.filePath = field.filePath;
                copyField.disabled = field.disabled;
                copy.urlencoded.add(copyField);
            }
        }
        if (source.formdata != null) {
            copy.formdata = new ArrayList<>();
            for (ApiRequest.Body.FormField field : source.formdata) {
                if (field == null) {
                    continue;
                }
                ApiRequest.Body.FormField copyField = new ApiRequest.Body.FormField(field.key, field.value);
                copyField.type = field.type;
                copyField.fileUpload = field.fileUpload;
                copyField.filePath = field.filePath;
                copyField.disabled = field.disabled;
                copy.formdata.add(copyField);
            }
        }
        return copy;
    }

    private static List<ApiRequest.Variable> copyVariables(List<ApiRequest.Variable> variables) {
        List<ApiRequest.Variable> out = new ArrayList<>();
        if (variables == null) {
            return out;
        }
        for (ApiRequest.Variable variable : variables) {
            if (variable == null) {
                continue;
            }
            ApiRequest.Variable copy = new ApiRequest.Variable();
            copy.key = variable.key;
            copy.value = variable.value;
            copy.type = variable.type;
            copy.enabled = variable.enabled;
            out.add(copy);
        }
        return out;
    }

    private static List<ApiRequest.Script> copyScripts(List<ApiRequest.Script> scripts) {
        List<ApiRequest.Script> out = new ArrayList<>();
        if (scripts == null) {
            return out;
        }
        for (ApiRequest.Script script : scripts) {
            if (script == null) {
                continue;
            }
            out.add(new ApiRequest.Script(script.type, script.exec));
        }
        return out;
    }

    private static java.util.List<burp.scripts.ScriptBlock> copyScriptBlocks(java.util.List<burp.scripts.ScriptBlock> scripts) {
        java.util.List<burp.scripts.ScriptBlock> out = new java.util.ArrayList<>();
        if (scripts == null) {
            return out;
        }
        for (burp.scripts.ScriptBlock script : scripts) {
            burp.scripts.ScriptBlock copy = burp.scripts.ScriptBlock.copyOf(script);
            if (copy != null) {
                out.add(copy);
            }
        }
        return out;
    }

    public static String toUtf8(byte[] bytes) {
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : "";
    }
}
