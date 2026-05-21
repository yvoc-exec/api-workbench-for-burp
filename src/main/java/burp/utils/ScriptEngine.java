package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.models.ApiRequest;
import burp.models.RunnerResult;
import burp.parser.VariableResolver;

import javax.script.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Nashorn-based JavaScript execution engine for collection scripts.
 * Supports Postman (pm.*) and Bruno (bru.*) script APIs.
 * Respects ScriptMode: FULL_JS runs Nashorn, LIMITED uses regex fallback, DISABLED skips entirely.
 */
public class ScriptEngine {
    private final ScriptEngineManager engineManager;
    private final MontoyaApi api;
    private final ScriptMode scriptMode;

    public ScriptEngine(MontoyaApi api, ScriptMode scriptMode) {
        this.api = api;
        this.scriptMode = scriptMode != null ? scriptMode : ScriptMode.DISABLED;
        this.engineManager = new ScriptEngineManager();
    }

    /**
     * Execute a pre-request script. Can modify variables before the request is sent.
     */
    public void executePreRequest(ApiRequest request, VariableResolver resolver, Map<String, String> context) {
        if (scriptMode == ScriptMode.DISABLED) {
            logOncePerSend("Script execution disabled (Java 17+ required). Skipping pre-request scripts.");
            return;
        }
        if (scriptMode == ScriptMode.LIMITED) {
            // No regex fallback for pre-request scripts
            return;
        }
        for (ApiRequest.Script script : request.preRequestScripts) {
            if (script.exec == null || script.exec.trim().isEmpty()) continue;
            try {
                javax.script.ScriptEngine engine = getNashornEngine();
                if (engine == null) {
                    if (api != null) api.logging().logToOutput("Nashorn engine not available. Using regex fallback for scripts.");
                    return;
                }

                // Bind APIs
                engine.put("pm", new PostmanApi(resolver, context, api));
                engine.put("bru", new BrunoApi(resolver, context, api));
                engine.put("console", new ConsoleApi(api));

                engine.eval(script.exec);
            } catch (Exception e) {
                if (api != null) api.logging().logToOutput("Pre-request script error in '" + request.name + "': " + e.getMessage());
            }
        }
    }

    /**
     * Execute a post-response script. Can extract variables and run assertions.
     */
    public void executePostResponse(ApiRequest request, VariableResolver resolver, Map<String, String> context,
                                     RunnerResult result, String responseBody,
                                     int statusCode, Map<String, List<String>> responseHeaders) {
        if (scriptMode == ScriptMode.DISABLED) {
            logOncePerSend("Script execution disabled (Java 17+ required). Skipping post-response scripts.");
            return;
        }
        if (scriptMode == ScriptMode.LIMITED) {
            for (ApiRequest.Script script : request.postResponseScripts) {
                if (script.exec == null || script.exec.trim().isEmpty()) continue;
                regexFallbackExtract(script.exec, result, context);
            }
            return;
        }
        for (ApiRequest.Script script : request.postResponseScripts) {
            if (script.exec == null || script.exec.trim().isEmpty()) continue;
            try {
                javax.script.ScriptEngine engine = getNashornEngine();
                if (engine == null) {
                    if (api != null) api.logging().logToOutput("Nashorn engine not available. Using regex fallback for post-response script.");
                    regexFallbackExtract(script.exec, result, context);
                    return;
                }

                // Parse JSON response for jsonData
                Object jsonData = parseJson(responseBody);

                // Bind APIs
                engine.put("pm", new PostmanApi(resolver, context, api, result, statusCode, responseHeaders, jsonData));
                engine.put("bru", new BrunoApi(resolver, context, api, result, statusCode, responseHeaders, jsonData));
                engine.put("console", new ConsoleApi(api));
                engine.put("jsonData", jsonData);
                engine.put("responseBody", responseBody);
                engine.put("responseCode", new ResponseCodeWrapper(statusCode));
                engine.put("responseHeaders", responseHeaders);

                engine.eval(script.exec);

                // Sync extracted variables back
                if (engine.get("pm") instanceof PostmanApi) {
                    context.putAll(((PostmanApi) engine.get("pm")).getExtractedVars());
                }
                if (engine.get("bru") instanceof BrunoApi) {
                    context.putAll(((BrunoApi) engine.get("bru")).getExtractedVars());
                }

            } catch (Exception e) {
                if (api != null) api.logging().logToOutput("Post-response script error in '" + request.name + "': " + e.getMessage());
                // Fallback to regex extraction
                regexFallbackExtract(script.exec, result, context);
            }
        }
    }

    private void logOncePerSend(String message) {
        // In DISABLED mode, avoid flooding logs. A simple hash-set per session could be added,
        // but for now we log every call since send frequency is human-paced.
        if (api != null) api.logging().logToOutput("[ScriptMode] " + message);
    }

    private javax.script.ScriptEngine getNashornEngine() {
        javax.script.ScriptEngine engine = engineManager.getEngineByName("nashorn");
        if (engine == null) {
            engine = engineManager.getEngineByName("javascript");
        }
        if (engine == null) {
            try {
                Class<?> factoryClass = Class.forName("org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory");
                Object factory = factoryClass.getDeclaredConstructor().newInstance();
                engine = (javax.script.ScriptEngine) factoryClass.getMethod("getScriptEngine").invoke(factory);
            } catch (Exception ex) {
                // Factory not available
            }
        }
        return engine;
    }

    private Object parseJson(String json) {
        if (json == null || json.trim().isEmpty()) return new HashMap<>();
        try {
            com.google.gson.JsonElement elem = com.google.gson.JsonParser.parseString(json);
            return jsonElementToJava(elem);
        } catch (Exception e) {
            return json; // Return as string if not valid JSON
        }
    }

    private Object jsonElementToJava(com.google.gson.JsonElement elem) {
        if (elem == null || elem.isJsonNull()) return null;
        if (elem.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive p = elem.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsNumber();
            return p.getAsString();
        }
        if (elem.isJsonObject()) {
            Map<String, Object> map = new HashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> e : elem.getAsJsonObject().entrySet()) {
                map.put(e.getKey(), jsonElementToJava(e.getValue()));
            }
            return map;
        }
        if (elem.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (com.google.gson.JsonElement e : elem.getAsJsonArray()) {
                list.add(jsonElementToJava(e));
            }
            return list;
        }
        return null;
    }

    private void regexFallbackExtract(String script, RunnerResult result, Map<String, String> context) {
        // pm.environment.set("key", jsonData.path)
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "(?:pm\\.environment\\.set|bru\\.setVar|bru\\.setEnvVar)\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*(.+?)\\s*\\)"
        );
        java.util.regex.Matcher m = p.matcher(script);
        while (m.find()) {
            String key = m.group(1);
            String expr = m.group(2).trim();
            String value = resolveSimpleExpr(expr, result);
            if (value != null) {
                context.put(key, value);
                result.extractedVariables.put(key, value);
            }
        }
    }

    private String resolveSimpleExpr(String expr, RunnerResult result) {
        // Handle string literals
        if ((expr.startsWith("\"") && expr.endsWith("\"")) || (expr.startsWith("'") && expr.endsWith("'"))) {
            return expr.substring(1, expr.length() - 1);
        }
        // jsonData.xxx or responseBody patterns
        String sourceBody = result.responseBody != null ? result.responseBody : result.responseBodyPreview;
        if (sourceBody != null) {
            try {
                com.google.gson.JsonElement elem = com.google.gson.JsonParser.parseString(sourceBody);
                // Strip prefix only, preserve path separators
                String path = expr;
                if (path.startsWith("jsonData.")) path = path.substring("jsonData.".length());
                else if (path.startsWith("jsonData")) path = path.substring("jsonData".length());
                else if (path.startsWith("res.body.")) path = path.substring("res.body.".length());
                else if (path.startsWith("res.body")) path = path.substring("res.body".length());
                String[] parts = path.split("\\.");
                com.google.gson.JsonElement current = elem;
                for (String part : parts) {
                    if (part.isEmpty()) continue;
                    if (current != null && current.isJsonObject()) {
                        current = current.getAsJsonObject().get(part);
                    } else {
                        current = null;
                        break;
                    }
                }
                if (current != null && current.isJsonPrimitive()) {
                    return current.getAsString();
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return null;
    }

    // Postman API bindings for Nashorn
    public static class PostmanApi {
        private final VariableResolver resolver;
        private final Map<String, String> context;
        private final MontoyaApi api;
        private final RunnerResult result;
        private final int statusCode;
        private final Map<String, List<String>> headers;
        private final Object jsonData;
        private final Map<String, String> extractedVars = new HashMap<>();

        public PostmanApi(VariableResolver resolver, Map<String, String> context, MontoyaApi api) {
            this(resolver, context, api, null, 0, null, null);
        }

        public PostmanApi(VariableResolver resolver, Map<String, String> context, MontoyaApi api,
                          RunnerResult result, int statusCode, Map<String, List<String>> headers, Object jsonData) {
            this.resolver = resolver;
            this.context = context;
            this.api = api;
            this.result = result;
            this.statusCode = statusCode;
            this.headers = headers;
            this.jsonData = jsonData;
        }

        public EnvironmentApi environment = new EnvironmentApi();
        public CollectionVariablesApi collectionVariables = new CollectionVariablesApi();
        public ExpectApi expect(Object target) { return new ExpectApi(target); }
        public ResponseApi response = new ResponseApi();

        public void test(String name, Runnable fn) {
            String assertionName = name != null ? name : "pm.test";
            if (result == null) {
                if (fn != null) {
                    fn.run();
                }
                return;
            }
            if (fn == null) {
                result.assertions.add(new RunnerResult.AssertionResult(assertionName, false, "callback", "null"));
                return;
            }

            int before = result.assertions.size();
            try {
                fn.run();
                if (result.assertions.size() == before) {
                    result.assertions.add(new RunnerResult.AssertionResult(assertionName, true, "no exception", "no exception"));
                }
            } catch (Throwable t) {
                while (result.assertions.size() > before) {
                    result.assertions.remove(result.assertions.size() - 1);
                }
                String actual = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                result.assertions.add(new RunnerResult.AssertionResult(assertionName, false, "no exception", actual));
            }
        }

        private Map<String, String> resolverVariables() {
            if (resolver == null) {
                return Collections.emptyMap();
            }
            Map<String, String> vars = resolver.getVariables();
            return vars != null ? vars : Collections.emptyMap();
        }

        public class EnvironmentApi {
            public void set(String key, Object value) {
                String str = value != null ? value.toString() : "";
                context.put(key, str);
                if (resolver != null) {
                    resolver.addCustomVariable(key, str);
                }
                extractedVars.put(key, str);
                if (result != null) result.extractedVariables.put(key, str);
            }
            public String get(String key) {
                if (context.containsKey(key)) {
                    return context.get(key);
                }
                return resolverVariables().getOrDefault(key, "");
            }
            public boolean has(String key) {
                return context.containsKey(key) || resolverVariables().containsKey(key);
            }
            public void unset(String key) {
                context.remove(key);
                Map<String, String> live = resolver != null ? resolver.mutableVariables() : null;
                if (live != null) {
                    live.remove(key);
                }
            }
        }

        public class CollectionVariablesApi {
            public void set(String key, Object value) {
                String str = value != null ? value.toString() : "";
                context.put(key, str);
                if (resolver != null) {
                    resolver.addCustomVariable(key, str);
                }
                extractedVars.put(key, str);
            }
            public String get(String key) {
                if (context.containsKey(key)) {
                    return context.get(key);
                }
                return resolverVariables().getOrDefault(key, "");
            }
            public void unset(String key) {
                context.remove(key);
                Map<String, String> live = resolver != null ? resolver.mutableVariables() : null;
                if (live != null) {
                    live.remove(key);
                }
                extractedVars.remove(key);
            }
        }

        public class ExpectApi {
            private final Object target;
            public ExpectApi(Object target) { this.target = target; }
            public ExpectApi to = this;
            public ExpectApi be = this;
            public ExpectApi have = this;

            public void have_status(int expected) {
                status(expected);
            }

            public void status(int expected) {
                int actual = extractStatusCode(target);
                boolean passed = actual == expected;
                if (result != null) {
                    result.assertions.add(new RunnerResult.AssertionResult(
                        "Status " + expected, passed, String.valueOf(expected), String.valueOf(actual)
                    ));
                }
                if (!passed) {
                    throw new AssertionError("expected status " + expected + " but got " + actual);
                }
            }

            public void have_header(String name) {
                header(name);
            }

            public void header(String name) {
                boolean passed = hasResponseHeader(name);
                if (result != null) {
                    result.assertions.add(new RunnerResult.AssertionResult(
                        "Header: " + name, passed, "present", passed ? "present" : "missing"
                    ));
                }
                if (!passed) {
                    throw new AssertionError("expected header " + name + " to be present");
                }
            }

            public void property(String name) {
                boolean passed = hasProperty(target, name);
                if (result != null) {
                    result.assertions.add(new RunnerResult.AssertionResult(
                            "Property: " + name, passed, "present", passed ? "present" : "missing"
                    ));
                }
                if (!passed) {
                    throw new AssertionError("expected property " + name + " to be present");
                }
            }

            public void equal(Object expected) {
                boolean passed = Objects.equals(target, expected);
                if (result != null) {
                    result.assertions.add(new RunnerResult.AssertionResult(
                        "Equals " + expected, passed, String.valueOf(expected), String.valueOf(target)
                    ));
                }
                if (!passed) {
                    throw new AssertionError("expected " + expected + " but got " + target);
                }
            }

            public void eql(Object expected) {
                equal(expected);
            }
        }

        private int extractStatusCode(Object target) {
            if (target instanceof Number number) {
                return number.intValue();
            }
            if (target instanceof ResponseApi) {
                return statusCode;
            }
            if (target instanceof ResponseCodeWrapper wrapper) {
                return wrapper.code;
            }
            try {
                if (target != null) {
                    java.lang.reflect.Method codeMethod = target.getClass().getMethod("code");
                    Object value = codeMethod.invoke(target);
                    if (value instanceof Number number) {
                        return number.intValue();
                    }
                }
            } catch (Exception ignored) {
            }
            return statusCode;
        }

        private boolean hasResponseHeader(String name) {
            if (name == null || headers == null) {
                return false;
            }
            for (String key : headers.keySet()) {
                if (key != null && key.equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasProperty(Object target, String name) {
            if (target == null || name == null || name.isEmpty()) {
                return false;
            }
            if (target instanceof Map<?, ?> map) {
                return map.containsKey(name);
            }
            if (target instanceof List<?> list) {
                try {
                    int index = Integer.parseInt(name);
                    return index >= 0 && index < list.size();
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
            try {
                java.lang.reflect.Field field = target.getClass().getField(name);
                return field != null;
            } catch (NoSuchFieldException ignored) {
            }
            try {
                String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                java.lang.reflect.Method getter = target.getClass().getMethod("get" + suffix);
                return getter != null;
            } catch (Exception ignored) {
            }
            return false;
        }

        public class ResponseApi {
            public int code() { return statusCode; }
            public String text() { return result != null ? result.responseBodyPreview : ""; }
            public Object json() { return jsonData; }
            public boolean hasHeader(String name) {
                if (headers == null) return false;
                for (String key : headers.keySet()) {
                    if (key.equalsIgnoreCase(name)) return true;
                }
                return false;
            }
        }

        public Map<String, String> getExtractedVars() { return extractedVars; }
    }

    // Bruno API bindings for Nashorn
    public static class BrunoApi {
        private final VariableResolver resolver;
        private final Map<String, String> context;
        private final MontoyaApi api;
        private final RunnerResult result;
        private final int statusCode;
        private final Map<String, List<String>> headers;
        private final Object jsonData;
        private final Map<String, String> extractedVars = new HashMap<>();

        public BrunoApi(VariableResolver resolver, Map<String, String> context, MontoyaApi api) {
            this(resolver, context, api, null, 0, null, null);
        }

        public BrunoApi(VariableResolver resolver, Map<String, String> context, MontoyaApi api,
                        RunnerResult result, int statusCode, Map<String, List<String>> headers, Object jsonData) {
            this.resolver = resolver;
            this.context = context;
            this.api = api;
            this.result = result;
            this.statusCode = statusCode;
            this.headers = headers;
            this.jsonData = jsonData;
        }

        public void setVar(String key, Object value) {
            String str = value != null ? value.toString() : "";
            context.put(key, str);
            resolver.addCustomVariable(key, str);
            extractedVars.put(key, str);
            if (result != null) result.extractedVariables.put(key, str);
        }

        public String getVar(String key) {
            return context.getOrDefault(key, resolver.getVariables().getOrDefault(key, ""));
        }

        public void setEnvVar(String key, Object value) {
            setVar(key, value);
        }

        public String getEnvVar(String key) {
            return getVar(key);
        }

        public ResponseApi res = new ResponseApi();

        public class ResponseApi {
            public Object getBody() { return jsonData; }
            public int getStatus() { return statusCode; }
            public String getBodyAsString() { return result != null ? result.responseBodyPreview : ""; }
        }

        public Map<String, String> getExtractedVars() { return extractedVars; }
    }

    public static class ConsoleApi {
        private final MontoyaApi api;
        public ConsoleApi(MontoyaApi api) { this.api = api; }
        public void log(Object msg) {
            if (api != null) api.logging().logToOutput("[Script] " + (msg != null ? msg.toString() : "null"));
        }
        public void error(Object msg) {
            if (api != null) api.logging().logToError("[Script] " + (msg != null ? msg.toString() : "null"));
        }
    }

    public static class ResponseCodeWrapper {
        public final int code;
        public ResponseCodeWrapper(int code) { this.code = code; }
    }
}
