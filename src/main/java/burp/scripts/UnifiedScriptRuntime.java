package burp.scripts;

import burp.api.montoya.MontoyaApi;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class UnifiedScriptRuntime {
    private final MontoyaApi api;
    private final GraalJsSandboxEngine sandboxEngine;
    private final ScriptLifecycleExecutor lifecycleExecutor;
    private final burp.utils.ScriptMode scriptMode;

    public UnifiedScriptRuntime(MontoyaApi api, burp.utils.ScriptMode scriptMode) {
        this.api = api;
        this.scriptMode = scriptMode != null ? scriptMode : burp.utils.ScriptMode.DISABLED;
        this.sandboxEngine = new GraalJsSandboxEngine();
        this.lifecycleExecutor = new ScriptLifecycleExecutor(sandboxEngine);
    }

    public boolean isEnabled() {
        return scriptMode != burp.utils.ScriptMode.DISABLED && sandboxEngine.isAvailable();
    }

    public String getEngineName() {
        return sandboxEngine != null ? sandboxEngine.getEngineName() : "Unavailable";
    }

    public ScriptExecutionResult executePreRequest(ApiCollection collection,
                                                   ApiRequest request,
                                                   EnvironmentProfile activeEnvironment,
                                                   String source,
                                                   int attemptNumber) {
        return executePreRequest(collection, request, activeEnvironment, executionSourceFromString(source), attemptNumber, null);
    }

    public ScriptExecutionResult executePreRequest(ApiCollection collection,
                                                   ApiRequest request,
                                                   EnvironmentProfile activeEnvironment,
                                                   String source,
                                                   int attemptNumber,
                                                   ScriptDependentRequestExecutor dependentRequestExecutor) {
        return executePreRequest(collection, request, activeEnvironment, executionSourceFromString(source), attemptNumber, dependentRequestExecutor);
    }

    public ScriptExecutionResult executePreRequest(ApiCollection collection,
                                                   ApiRequest request,
                                                   EnvironmentProfile activeEnvironment,
                                                   ExecutionSource executionSource,
                                                   int attemptNumber) {
        return executePreRequest(collection, request, activeEnvironment, executionSource, attemptNumber, null);
    }

    public ScriptExecutionResult executePreRequest(ApiCollection collection,
                                                   ApiRequest request,
                                                   EnvironmentProfile activeEnvironment,
                                                   ExecutionSource executionSource,
                                                   int attemptNumber,
                                                   ScriptDependentRequestExecutor dependentRequestExecutor) {
        ScriptExecutionContext context = new ScriptExecutionContext(api, collection, request, activeEnvironment, executionSource, attemptNumber);
        context.dependentRequestExecutor = dependentRequestExecutor;
        context.runnerOnlyFlowControlsAllowed = executionSource == ExecutionSource.RUNNER;
        context.result.engineName = sandboxEngine.getEngineName();
        if (!isEnabled()) {
            context.warn("Script execution disabled or sandbox unavailable.", null, null);
            return context.result;
        }

        lifecycleExecutor.execute(context, resolveBlocks(collection, request, ScriptPhase.PRE_REQUEST));
        context.result.mutatedRequest = context.request;
        return context.result;
    }

    public ScriptExecutionResult executePostResponse(ApiCollection collection,
                                                     ApiRequest request,
                                                     EnvironmentProfile activeEnvironment,
                                                     String source,
                                                     int attemptNumber,
                                                     String responseText,
                                                     int statusCode,
                                                     Map<String, List<String>> responseHeaders,
                                                     long responseTimeMs,
                                                     RunnerResult runnerResult) {
        return executePostResponse(collection, request, activeEnvironment, executionSourceFromString(source), attemptNumber, responseText, statusCode, responseHeaders, responseTimeMs, runnerResult, null);
    }

    public ScriptExecutionResult executePostResponse(ApiCollection collection,
                                                     ApiRequest request,
                                                     EnvironmentProfile activeEnvironment,
                                                     String source,
                                                     int attemptNumber,
                                                     String responseText,
                                                     int statusCode,
                                                     Map<String, List<String>> responseHeaders,
                                                     long responseTimeMs,
                                                     RunnerResult runnerResult,
                                                     ScriptDependentRequestExecutor dependentRequestExecutor) {
        return executePostResponse(collection, request, activeEnvironment, executionSourceFromString(source), attemptNumber, responseText, statusCode, responseHeaders, responseTimeMs, runnerResult, dependentRequestExecutor);
    }

    public ScriptExecutionResult executePostResponse(ApiCollection collection,
                                                     ApiRequest request,
                                                     EnvironmentProfile activeEnvironment,
                                                     ExecutionSource executionSource,
                                                     int attemptNumber,
                                                     String responseText,
                                                     int statusCode,
                                                     Map<String, List<String>> responseHeaders,
                                                     long responseTimeMs,
                                                     RunnerResult runnerResult) {
        return executePostResponse(collection, request, activeEnvironment, executionSource, attemptNumber, responseText, statusCode, responseHeaders, responseTimeMs, runnerResult, null);
    }

    public ScriptExecutionResult executePostResponse(ApiCollection collection,
                                                     ApiRequest request,
                                                     EnvironmentProfile activeEnvironment,
                                                     ExecutionSource executionSource,
                                                     int attemptNumber,
                                                     String responseText,
                                                     int statusCode,
                                                     Map<String, List<String>> responseHeaders,
                                                     long responseTimeMs,
                                                     RunnerResult runnerResult,
                                                     ScriptDependentRequestExecutor dependentRequestExecutor) {
        ScriptExecutionContext context = new ScriptExecutionContext(api, collection, request, activeEnvironment, executionSource, attemptNumber);
        context.dependentRequestExecutor = dependentRequestExecutor;
        context.responseText = responseText;
        context.responseStatusCode = statusCode;
        context.responseTimeMs = responseTimeMs;
        context.responseHeaders = responseHeaders != null ? new LinkedHashMap<>(responseHeaders) : new LinkedHashMap<>();
        context.runnerResult = runnerResult;
        context.parsedResponseJson = parseJson(responseText);
        context.runnerOnlyFlowControlsAllowed = executionSource == ExecutionSource.RUNNER;
        context.result.engineName = sandboxEngine.getEngineName();
        if (!isEnabled()) {
            context.warn("Script execution disabled or sandbox unavailable.", null, null);
            return context.result;
        }

        lifecycleExecutor.execute(context, resolveBlocks(collection, request, ScriptPhase.POST_RESPONSE));
        lifecycleExecutor.execute(context, resolveBlocks(collection, request, ScriptPhase.TEST));
        context.result.mutatedRequest = context.request;
        return context.result;
    }

    public List<ScriptBlock> resolveBlocks(ApiCollection collection, ApiRequest request, ScriptPhase phase) {
        List<ScriptBlock> blocks = new ArrayList<>();
        if (collection != null) {
            if (collection.scriptBlocks != null) {
                for (ScriptBlock block : collection.scriptBlocks) {
                    if (block != null && block.enabled && block.phase == phase) {
                        blocks.add(ScriptBlock.copyOf(block));
                    }
                }
            }
            if (collection.folderScriptBlocks != null && request != null) {
                String folderPath = burp.utils.RequestPathResolver.getRequestFolderPath(collection, request);
                if (folderPath != null && !folderPath.isBlank()) {
                    StringBuilder current = new StringBuilder();
                    for (String part : folderPath.split("/")) {
                        if (part == null || part.isBlank()) {
                            continue;
                        }
                        if (current.length() > 0) {
                            current.append("/");
                        }
                        current.append(part.trim());
                        List<ScriptBlock> folderBlocks = collection.folderScriptBlocks.get(current.toString());
                        if (folderBlocks != null) {
                            for (ScriptBlock block : folderBlocks) {
                                if (block != null && block.enabled && block.phase == phase) {
                                    blocks.add(ScriptBlock.copyOf(block));
                                }
                            }
                        }
                    }
                }
            }
        }
        if (request != null) {
            boolean hasNativeBlocks = hasNativeRequestBlocks(request.scriptBlocks, phase);
            if (hasNativeBlocks) {
                if (request.scriptBlocks != null) {
                    for (ScriptBlock block : request.scriptBlocks) {
                        if (block != null && block.enabled && block.phase == phase) {
                            blocks.add(ScriptBlock.copyOf(block));
                        }
                    }
                }
            } else {
                if (phase == ScriptPhase.PRE_REQUEST && request.preRequestScripts != null) {
                    blocks.addAll(legacyBlocks(request.preRequestScripts, ScriptDialect.LEGACY_NASHORN, ScriptPhase.PRE_REQUEST, ScriptScope.REQUEST, request));
                }
                if (phase == ScriptPhase.POST_RESPONSE && request.postResponseScripts != null) {
                    blocks.addAll(legacyBlocks(request.postResponseScripts, ScriptDialect.LEGACY_NASHORN, phase, ScriptScope.REQUEST, request));
                }
            }
        }
        return blocks;
    }

    public static Map<String, Object> buildBindings(ScriptExecutionContext context, ScriptBlock block) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        if (context == null || block == null) {
            return bindings;
        }

        ScriptBindingsFactory.RequestApi requestBinding = new ScriptBindingsFactory.RequestApi(context);
        context.requestBinding = requestBinding;
        ScriptBindingsFactory.ExecutionApi executionBinding = new ScriptBindingsFactory.ExecutionApi(context);
        ScriptBindingsFactory.ResponseApi responseBinding = new ScriptBindingsFactory.ResponseApi(
                context.responseStatusCode,
                context.responseText,
                context.responseHeaders,
                context.parsedResponseJson,
                context.responseTimeMs
        );

        ScriptBindingsFactory.PostmanApi postman = new ScriptBindingsFactory.PostmanApi(context.api, context, requestBinding, executionBinding, responseBinding);
        ScriptBindingsFactory.BrunoApi bruno = new ScriptBindingsFactory.BrunoApi(context.api, context, requestBinding, responseBinding);
        ScriptBindingsFactory.InsomniaApi insomnia = new ScriptBindingsFactory.InsomniaApi(context.api, context, requestBinding, responseBinding);
        ScriptBindingsFactory.NativeApi nativeApi = new ScriptBindingsFactory.NativeApi(context.api, context, requestBinding, responseBinding, executionBinding);
        ScriptBindingsFactory.ConsoleApi consoleApi = new ScriptBindingsFactory.ConsoleApi(context.api, context);

        ScriptDialect dialect = block.dialect != null ? block.dialect : ScriptDialect.LEGACY_NASHORN;
        switch (dialect) {
            case POSTMAN -> {
                bindings.put("pm", postman);
                bindings.put("console", consoleApi);
            }
            case BRUNO -> {
                bindings.put("bru", bruno);
                bindings.put("req", bruno.req);
                bindings.put("res", bruno.res);
                bindings.put("console", consoleApi);
            }
            case INSOMNIA -> {
                bindings.put("insomnia", insomnia);
                bindings.put("request", insomnia.request);
                bindings.put("response", insomnia.response);
                bindings.put("console", consoleApi);
            }
            case API_WORKBENCH -> {
                bindings.put("awb", nativeApi);
                bindings.put("console", consoleApi);
            }
            case LEGACY_NASHORN -> {
                bindings.put("pm", postman);
                bindings.put("bru", bruno);
                bindings.put("insomnia", insomnia);
                bindings.put("awb", nativeApi);
                bindings.put("console", consoleApi);
                bindings.put("req", bruno.req);
                bindings.put("res", bruno.res);
                bindings.put("request", postman.request);
                bindings.put("response", postman.response);
                bindings.put("responseCode", new ScriptBindingsFactory.ResponseCodeWrapper(context.responseStatusCode));
                bindings.put("jsonData", context.parsedResponseJson);
                bindings.put("responseBody", context.responseText);
            }
        }
        return bindings;
    }

    private static boolean hasNativeRequestBlocks(List<ScriptBlock> blocks, ScriptPhase phase) {
        if (blocks == null || phase == null) {
            return false;
        }
        for (ScriptBlock block : blocks) {
            if (block != null && block.enabled && block.phase == phase && block.source != null && !block.source.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static List<ScriptBlock> legacyBlocks(List<ApiRequest.Script> scripts,
                                                  ScriptDialect dialect,
                                                  ScriptPhase phase,
                                                  ScriptScope scope,
                                                  ApiRequest request) {
        List<ScriptBlock> blocks = new ArrayList<>();
        if (scripts == null) {
            return blocks;
        }
        int order = 0;
        for (ApiRequest.Script script : scripts) {
            ScriptBlock block = ScriptBlock.fromLegacy(script, dialect, phase, scope,
                    request != null ? request.sourceCollection : null,
                    request != null ? request.path : null,
                    order++);
            if (block != null) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(json);
            return toJava(element);
        } catch (Exception ignored) {
            return json;
        }
    }

    private static Object toJava(com.google.gson.JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                return primitive.getAsNumber();
            }
            return primitive.getAsString();
        }
        if (element.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (com.google.gson.JsonElement child : element.getAsJsonArray()) {
                list.add(toJava(child));
            }
            return list;
        }
        if (element.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : element.getAsJsonObject().entrySet()) {
                map.put(entry.getKey(), toJava(entry.getValue()));
            }
            return map;
        }
        return null;
    }

    public static String normalizeDialectName(ScriptDialect dialect) {
        if (dialect == null) {
            return "legacy";
        }
        return dialect.name().toLowerCase(Locale.ROOT);
    }

    public static ExecutionSource executionSourceFromString(String source) {
        if (source == null || source.isBlank()) {
            return ExecutionSource.WORKBENCH_SEND;
        }
        String normalized = source.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "history_replay", "history replay", "historyreplay" -> ExecutionSource.HISTORY_REPLAY;
            case "runner" -> ExecutionSource.RUNNER;
            case "build", "build_preview", "preview", "buildpreview" -> ExecutionSource.BUILD_PREVIEW;
            default -> ExecutionSource.WORKBENCH_SEND;
        };
    }
}
