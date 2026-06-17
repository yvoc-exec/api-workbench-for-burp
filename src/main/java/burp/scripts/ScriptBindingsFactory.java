package burp.scripts;

import burp.api.montoya.MontoyaApi;
import burp.models.ApiRequest;
import burp.parser.VariableResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ScriptBindingsFactory {
    private ScriptBindingsFactory() {
    }

    static PostmanApi postman(MontoyaApi api, ScriptExecutionContext context) {
        return new PostmanApi(api, context);
    }

    static BrunoApi bruno(MontoyaApi api, ScriptExecutionContext context) {
        return new BrunoApi(api, context);
    }

    static InsomniaApi insomnia(MontoyaApi api, ScriptExecutionContext context) {
        return new InsomniaApi(api, context);
    }

    static NativeApi nativeApi(MontoyaApi api, ScriptExecutionContext context) {
        return new NativeApi(api, context);
    }

    static ConsoleApi console(MontoyaApi api, ScriptExecutionContext context) {
        return new ConsoleApi(api, context);
    }

    public static final class ConsoleApi {
        private final MontoyaApi api;
        private final ScriptExecutionContext context;

        ConsoleApi(MontoyaApi api, ScriptExecutionContext context) {
            this.api = api;
            this.context = context;
        }

        public void log(Object msg) {
            String text = msg != null ? msg.toString() : "null";
            if (context != null) {
                context.log("info", text, null, null);
            }
            if (api != null) {
                api.logging().logToOutput("[Script] " + text);
            }
        }

        public void error(Object msg) {
            String text = msg != null ? msg.toString() : "null";
            if (context != null) {
                context.error(text, null, null);
            }
            if (api != null) {
                api.logging().logToError("[Script] " + text);
            }
        }
    }

    public enum VariableTarget {
        ENVIRONMENT,
        COLLECTION,
        REQUEST,
        FOLDER,
        LOCAL,
        GLOBAL
    }

    public static class VariableScopeApi {
        private final ScriptExecutionContext context;
        private final VariableTarget target;
        private final boolean persistDefault;
        private final String scopeLabel;

        VariableScopeApi(ScriptExecutionContext context, VariableTarget target, boolean persistDefault, String scopeLabel) {
            this.context = context;
            this.target = target;
            this.persistDefault = persistDefault;
            this.scopeLabel = scopeLabel;
        }

        public String get(String key) {
            if (context == null || key == null) {
                return "";
            }
            String value = context.variableStore.get(key);
            return value != null ? value : "";
        }

        public boolean has(String key) {
            return context != null && key != null && context.variableStore.has(key);
        }

        public void set(String key, Object value) {
            set(key, value, null);
        }

        public void set(String key, Object value, Object options) {
            boolean persist = persistDefault || optionsIndicatesPersist(options);
            applySet(key, value != null ? value.toString() : "", persist);
        }

        public void unset(String key) {
            applyUnset(key, persistDefault);
        }

        private void applySet(String key, String value, boolean persist) {
            if (context == null || key == null || key.isBlank()) {
                return;
            }
            switch (target) {
                case ENVIRONMENT -> context.setEnvironment(key, value, persist, null, scopeLabel);
                case COLLECTION -> context.setCollection(key, value, persist, null, scopeLabel);
                case REQUEST -> context.setRequestVar(key, value, null, scopeLabel);
                case FOLDER -> context.setFolder(key, value, persist, null, scopeLabel);
                case LOCAL -> context.setLocalVar(key, value, null, scopeLabel);
                case GLOBAL -> context.setGlobal(key, value, null, scopeLabel);
            }
        }

        private void applyUnset(String key, boolean persist) {
            if (context == null || key == null || key.isBlank()) {
                return;
            }
            switch (target) {
                case ENVIRONMENT -> context.unsetEnvironment(key, persist, null, scopeLabel);
                case COLLECTION -> context.unsetCollection(key, persist, null, scopeLabel);
                case REQUEST -> context.unsetRequestVar(key, null, scopeLabel);
                case FOLDER -> context.unsetFolder(key, persist, null, scopeLabel);
                case LOCAL -> context.unsetLocalVar(key, null, scopeLabel);
                case GLOBAL -> context.unsetGlobal(key, null, scopeLabel);
            }
        }

        private boolean optionsIndicatesPersist(Object options) {
            if (options == null) {
                return false;
            }
            if (options instanceof Map<?, ?> map) {
                Object persist = map.get("persist");
                if (persist instanceof Boolean b) {
                    return b;
                }
                if (persist != null) {
                    return Boolean.parseBoolean(persist.toString());
                }
            }
            return false;
        }
    }

    public static class RequestApi {
        public String method;
        public String url;
        public HeaderApi headers;
        public BodyApi body;
        public AuthApi auth;
        public String name;
        public String path;
        public String sourceCollection;
        public RequestApi() {
        }

        RequestApi(ScriptExecutionContext context) {
            ApiRequest request = context != null ? context.request : null;
            this.method = request != null ? request.method : null;
            this.url = request != null ? request.url : null;
            this.name = request != null ? request.name : null;
            this.path = request != null ? request.path : null;
            this.sourceCollection = request != null ? request.sourceCollection : null;
            this.headers = new HeaderApi(request != null ? request.headers : null);
            this.body = new BodyApi(request != null ? request.body : null);
            this.auth = new AuthApi(request != null ? request.auth : null);
        }

        void applyTo(ApiRequest request) {
            if (request == null) {
                return;
            }
            request.method = method != null ? method : request.method;
            request.url = url != null ? url : request.url;
            request.name = name != null ? name : request.name;
            request.path = path != null ? path : request.path;
            request.sourceCollection = sourceCollection != null ? sourceCollection : request.sourceCollection;
            if (headers != null) {
                request.headers = headers.toHeaders();
            }
            if (body != null) {
                request.body = body.toBody();
            }
            if (auth != null) {
                request.auth = auth.toAuth();
            }
        }
    }

    public static final class HeaderApi {
        private final List<ApiRequest.Header> entries = new ArrayList<>();

        HeaderApi(List<ApiRequest.Header> source) {
            if (source != null) {
                for (ApiRequest.Header header : source) {
                    if (header == null) {
                        continue;
                    }
                    entries.add(new ApiRequest.Header(header.key, header.value, header.disabled));
                }
            }
        }

        public String get(String name) {
            if (name == null) {
                return "";
            }
            for (ApiRequest.Header header : entries) {
                if (header != null && header.key != null && header.key.equalsIgnoreCase(name)) {
                    return header.value != null ? header.value : "";
                }
            }
            return "";
        }

        public boolean has(String name) {
            return findIndex(name) >= 0;
        }

        public void add(String name, String value) {
            if (name == null || name.isBlank()) {
                return;
            }
            entries.add(new ApiRequest.Header(name, value != null ? value : ""));
        }

        public void upsert(String name, String value) {
            if (name == null || name.isBlank()) {
                return;
            }
            int index = findIndex(name);
            if (index >= 0) {
                entries.set(index, new ApiRequest.Header(name, value != null ? value : "", false));
            } else {
                add(name, value);
            }
        }

        public void remove(String name) {
            if (name == null || name.isBlank()) {
                return;
            }
            entries.removeIf(header -> header != null && header.key != null && header.key.equalsIgnoreCase(name));
        }

        public List<ApiRequest.Header> toHeaders() {
            List<ApiRequest.Header> out = new ArrayList<>();
            for (ApiRequest.Header header : entries) {
                if (header == null || header.key == null || header.key.isBlank()) {
                    continue;
                }
                out.add(new ApiRequest.Header(header.key, header.value, header.disabled));
            }
            return out;
        }

        private int findIndex(String name) {
            if (name == null) {
                return -1;
            }
            for (int i = 0; i < entries.size(); i++) {
                ApiRequest.Header header = entries.get(i);
                if (header != null && header.key != null && header.key.equalsIgnoreCase(name)) {
                    return i;
                }
            }
            return -1;
        }
    }

    public static final class BodyApi {
        public String mode;
        public String raw;
        public String contentType;
        public GraphQLApi graphql;

        BodyApi(ApiRequest.Body source) {
            if (source != null) {
                this.mode = source.mode;
                this.raw = source.raw;
                this.contentType = source.contentType;
                if (source.graphql != null) {
                    this.graphql = new GraphQLApi(source.graphql);
                }
            }
        }

        ApiRequest.Body toBody() {
            if (mode == null && raw == null && contentType == null && graphql == null) {
                return null;
            }
            ApiRequest.Body body = new ApiRequest.Body();
            body.mode = mode;
            body.raw = raw;
            body.contentType = contentType;
            if (graphql != null) {
                body.graphql = graphql.toGraphQL();
            }
            return body;
        }
    }

    public static final class GraphQLApi {
        public String query;
        public String variables;

        GraphQLApi(ApiRequest.Body.GraphQL source) {
            if (source != null) {
                this.query = source.query;
                this.variables = source.variables;
            }
        }

        ApiRequest.Body.GraphQL toGraphQL() {
            ApiRequest.Body.GraphQL gql = new ApiRequest.Body.GraphQL();
            gql.query = query;
            gql.variables = variables;
            return gql;
        }
    }

    public static final class AuthApi {
        public String type;
        public final Map<String, String> properties = new LinkedHashMap<>();

        AuthApi(ApiRequest.Auth source) {
            if (source != null) {
                this.type = source.type;
                if (source.properties != null) {
                    properties.putAll(source.properties);
                }
            }
        }

        ApiRequest.Auth toAuth() {
            if (type == null && properties.isEmpty()) {
                return null;
            }
            ApiRequest.Auth auth = new ApiRequest.Auth();
            auth.type = type;
            auth.properties.putAll(properties);
            return auth;
        }
    }

    public static class ResponseApi {
        public int code;
        public String status;
        public long responseTime;
        public HeaderLookup headers;
        private final Object parsedJson;
        private final String text;

        ResponseApi(int code, String text, Map<String, List<String>> headers, Object parsedJson, long responseTime) {
            this.code = code;
            this.status = code > 0 ? String.valueOf(code) : "";
            this.responseTime = responseTime;
            this.parsedJson = parsedJson;
            this.text = text != null ? text : "";
            this.headers = new HeaderLookup(headers);
        }

        public String text() {
            return text;
        }

        public Object json() {
            return parsedJson;
        }
    }

    public static final class ResponseCodeWrapper {
        public int code;

        ResponseCodeWrapper(int code) {
            this.code = code;
        }
    }

    public static final class HeaderLookup {
        private final Map<String, List<String>> headers = new LinkedHashMap<>();

        HeaderLookup(Map<String, List<String>> source) {
            if (source != null) {
                for (Map.Entry<String, List<String>> entry : source.entrySet()) {
                    if (entry.getKey() == null) {
                        continue;
                    }
                    headers.put(entry.getKey(), entry.getValue() != null ? new ArrayList<>(entry.getValue()) : List.of());
                }
            }
        }

        public String get(String name) {
            if (name == null) {
                return "";
            }
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return "";
        }

        public boolean has(String name) {
            return find(name) != null;
        }

        public Map<String, List<String>> asMap() {
            return new LinkedHashMap<>(headers);
        }

        public List<HeaderEntry> all() {
            List<HeaderEntry> out = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                List<String> values = entry.getValue();
                if (values == null || values.isEmpty()) {
                    out.add(new HeaderEntry(entry.getKey(), ""));
                    continue;
                }
                for (String value : values) {
                    out.add(new HeaderEntry(entry.getKey(), value));
                }
            }
            return out;
        }

        public HeaderEntry find(String name) {
            if (name == null) {
                return null;
            }
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    List<String> values = entry.getValue();
                    return new HeaderEntry(entry.getKey(), values != null && !values.isEmpty() ? values.get(0) : "");
                }
            }
            return null;
        }
    }

    public static final class HeaderEntry {
        public final String key;
        public final String value;

        HeaderEntry(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    public static class ExpectApi {
        private final Object target;
        private final ScriptExecutionContext context;
        private final String scriptId;
        private final String scriptName;

        public ExpectApi(Object target, ScriptExecutionContext context, String scriptId, String scriptName) {
            this.target = target;
            this.context = context;
            this.scriptId = scriptId;
            this.scriptName = scriptName;
        }

        public final ExpectApi to = this;
        public final ExpectApi be = this;
        public final ExpectApi have = this;

        public void status(int expected) {
            int actual = extractStatusCode(target);
            boolean passed = actual == expected;
            ScriptAssertionResult assertion = new ScriptAssertionResult("Status " + expected, passed, String.valueOf(expected), String.valueOf(actual));
            assertion.scriptId = scriptId;
            context.addAssertion(assertion);
            if (!passed) {
                throw new AssertionError("expected status " + expected + " but got " + actual);
            }
        }

        public void have_status(int expected) {
            status(expected);
        }

        public void header(String name) {
            boolean passed = hasHeader(name);
            ScriptAssertionResult assertion = new ScriptAssertionResult("Header: " + name, passed, "present", passed ? "present" : "missing");
            assertion.scriptId = scriptId;
            context.addAssertion(assertion);
            if (!passed) {
                throw new AssertionError("expected header " + name + " to be present");
            }
        }

        public void have_header(String name) {
            header(name);
        }

        public void property(String name) {
            boolean passed = hasProperty(target, name);
            ScriptAssertionResult assertion = new ScriptAssertionResult("Property: " + name, passed, "present", passed ? "present" : "missing");
            assertion.scriptId = scriptId;
            context.addAssertion(assertion);
            if (!passed) {
                throw new AssertionError("expected property " + name + " to be present");
            }
        }

        public void equal(Object expected) {
            boolean passed = Objects.equals(target, expected);
            ScriptAssertionResult assertion = new ScriptAssertionResult("Equals " + expected, passed, String.valueOf(expected), String.valueOf(target));
            assertion.scriptId = scriptId;
            context.addAssertion(assertion);
            if (!passed) {
                throw new AssertionError("expected " + expected + " but got " + target);
            }
        }

        public void eql(Object expected) {
            equal(expected);
        }

        private boolean hasHeader(String name) {
            if (name == null) {
                return false;
            }
            if (target instanceof ResponseApi responseApi) {
                return responseApi.headers != null && responseApi.headers.has(name);
            }
            if (target instanceof HeaderLookup lookup) {
                return lookup.has(name);
            }
            return false;
        }

        private boolean hasProperty(Object value, String name) {
            if (value == null || name == null || name.isEmpty()) {
                return false;
            }
            if (value instanceof Map<?, ?> map) {
                return map.containsKey(name);
            }
            if (value instanceof List<?> list) {
                try {
                    int index = Integer.parseInt(name);
                    return index >= 0 && index < list.size();
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
            try {
                return value.getClass().getField(name) != null;
            } catch (Exception ignored) {
                return false;
            }
        }

        private int extractStatusCode(Object target) {
            if (target instanceof Number number) {
                return number.intValue();
            }
            if (target instanceof ResponseApi responseApi) {
                return responseApi.code;
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
            return context != null ? context.responseStatusCode : -1;
        }
    }

    public static class PostmanApi {
        public final VariableScopeApi environment;
        public final VariableScopeApi collectionVariables;
        public final VariableScopeApi variables;
        public final RequestApi request;
        public final ExecutionApi execution;
        public ResponseApi response;
        private final ScriptExecutionContext context;
        private final MontoyaApi api;

        PostmanApi(MontoyaApi api, ScriptExecutionContext context) {
            this(api, context, null, null, null);
        }

        PostmanApi(MontoyaApi api, ScriptExecutionContext context, RequestApi sharedRequest, ExecutionApi sharedExecution, ResponseApi sharedResponse) {
            this.api = api;
            this.context = context;
            this.environment = new VariableScopeApi(context, VariableTarget.ENVIRONMENT, true, "environment");
            this.collectionVariables = new VariableScopeApi(context, VariableTarget.COLLECTION, true, "collectionVariables");
            this.variables = new VariableScopeApi(context, VariableTarget.LOCAL, false, "variables");
            this.request = sharedRequest != null ? sharedRequest : new RequestApi(context);
            this.execution = sharedExecution != null ? sharedExecution : new ExecutionApi(context);
            this.response = sharedResponse != null ? sharedResponse : new ResponseApi(context.responseStatusCode, context.responseText, context.responseHeaders, context.parsedResponseJson, context.responseTimeMs);
        }

        public ExpectApi expect(Object target) {
            return new ExpectApi(target, context, null, null);
        }

        public void test(String name, Runnable fn) {
            String assertionName = name != null ? name : "pm.test";
            if (fn == null) {
                ScriptAssertionResult assertion = new ScriptAssertionResult(assertionName, false, "callback", "null");
                context.addAssertion(assertion);
                return;
            }
            int before = context.result.assertions.size();
            try {
                fn.run();
                if (context.result.assertions.size() == before) {
                    context.addAssertion(new ScriptAssertionResult(assertionName, true, "no exception", "no exception"));
                }
            } catch (Throwable t) {
                while (context.result.assertions.size() > before) {
                    context.result.assertions.remove(context.result.assertions.size() - 1);
                }
                String actual = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                context.addAssertion(new ScriptAssertionResult(assertionName, false, "no exception", actual));
                context.error("Script assertion failed: " + actual, null, assertionName);
            }
        }

        public void sendRequest(Object request) {
            context.warn("pm.sendRequest is not executed in the sandbox yet.", null, null);
        }
    }

    public static class BrunoApi {
        public final VariableScopeApi reqScope;
        public final VariableScopeApi envScope;
        public final VariableScopeApi collectionScope;
        public final VariableScopeApi folderScope;
        public final VariableScopeApi requestScope;
        public final VariableScopeApi localScope;
        public final RequestApi req;
        public final ResponseApi res;
        public final BrunoApi bru = this;
        private final ScriptExecutionContext context;
        private final MontoyaApi api;

        BrunoApi(MontoyaApi api, ScriptExecutionContext context) {
            this(api, context, null, null);
        }

        BrunoApi(MontoyaApi api, ScriptExecutionContext context, RequestApi sharedRequest, ResponseApi sharedResponse) {
            this.api = api;
            this.context = context;
            this.reqScope = new VariableScopeApi(context, VariableTarget.REQUEST, false, "request");
            this.envScope = new VariableScopeApi(context, VariableTarget.ENVIRONMENT, false, "environment");
            this.collectionScope = new VariableScopeApi(context, VariableTarget.COLLECTION, true, "collection");
            this.folderScope = new VariableScopeApi(context, VariableTarget.FOLDER, true, "folder");
            this.requestScope = reqScope;
            this.localScope = new VariableScopeApi(context, VariableTarget.LOCAL, false, "local");
            this.req = sharedRequest != null ? sharedRequest : new RequestApi(context);
            this.res = sharedResponse != null ? sharedResponse : new ResponseApi(context.responseStatusCode, context.responseText, context.responseHeaders, context.parsedResponseJson, context.responseTimeMs);
        }

        public String getVar(String key) {
            return reqScope.get(key);
        }

        public void setVar(String key, Object value) {
            reqScope.set(key, value);
        }

        public String getEnvVar(String key) {
            return envScope.get(key);
        }

        public void setEnvVar(String key, Object value) {
            envScope.set(key, value);
        }

        public void setEnvVar(String key, Object value, Object options) {
            envScope.set(key, value, options);
        }

        public String getCollectionVar(String key) {
            return collectionScope.get(key);
        }

        public void setCollectionVar(String key, Object value) {
            collectionScope.set(key, value);
        }

        public String getFolderVar(String key) {
            return folderScope.get(key);
        }

        public void setFolderVar(String key, Object value) {
            folderScope.set(key, value);
        }

        public String getRequestVar(String key) {
            return requestScope.get(key);
        }

        public void setRequestVar(String key, Object value) {
            requestScope.set(key, value);
        }

        public ExpectApi expect(Object target) {
            return new ExpectApi(target, context, null, null);
        }

        public void test(String name, Runnable fn) {
            new PostmanApi(api, context).test(name, fn);
        }

        public void sendRequest(Object request) {
            context.warn("bru.sendRequest is not executed in the sandbox yet.", null, null);
        }

        public void runRequest(Object request) {
            context.warn("bru.runRequest is not executed in the sandbox yet.", null, null);
        }

        public void setNextRequest(String name) {
            context.setFlowControl(ScriptFlowControl.SET_NEXT_REQUEST, name, null, "bru.setNextRequest");
        }

        public void skipRequest() {
            context.setFlowControl(ScriptFlowControl.SKIP_REQUEST, null, null, "bru.skipRequest");
        }

        public void stopExecution() {
            context.setFlowControl(ScriptFlowControl.STOP_RUN, null, null, "bru.stopExecution");
        }
    }

    public static class InsomniaApi {
        public final VariableScopeApi environment;
        public final VariableScopeApi baseEnvironment;
        public final VariableScopeApi collectionVariables;
        public final VariableScopeApi variables;
        public final RequestApi request;
        public final ResponseApi response;
        private final ScriptExecutionContext context;
        private final MontoyaApi api;

        InsomniaApi(MontoyaApi api, ScriptExecutionContext context) {
            this(api, context, null, null);
        }

        InsomniaApi(MontoyaApi api, ScriptExecutionContext context, RequestApi sharedRequest, ResponseApi sharedResponse) {
            this.api = api;
            this.context = context;
            this.environment = new VariableScopeApi(context, VariableTarget.ENVIRONMENT, true, "environment");
            this.baseEnvironment = new VariableScopeApi(context, VariableTarget.ENVIRONMENT, true, "baseEnvironment");
            this.collectionVariables = new VariableScopeApi(context, VariableTarget.COLLECTION, true, "collectionVariables");
            this.variables = new VariableScopeApi(context, VariableTarget.LOCAL, false, "variables");
            this.request = sharedRequest != null ? sharedRequest : new RequestApi(context);
            this.response = sharedResponse != null ? sharedResponse : new ResponseApi(context.responseStatusCode, context.responseText, context.responseHeaders, context.parsedResponseJson, context.responseTimeMs);
        }

        public ExpectApi expect(Object target) {
            return new ExpectApi(target, context, null, null);
        }

        public void test(String name, Runnable fn) {
            new PostmanApi(api, context).test(name, fn);
        }

        public void sendRequest(Object request) {
            context.warn("insomnia.sendRequest is not executed in the sandbox yet.", null, null);
        }
    }

    public static class NativeApi {
        public final VariableScopeApi variables;
        public final VariableScopeApi environment;
        public final VariableScopeApi collection;
        public final RequestApi request;
        public final ResponseApi response;
        public final ExecutionApi execution;
        private final ScriptExecutionContext context;
        private final MontoyaApi api;

        NativeApi(MontoyaApi api, ScriptExecutionContext context) {
            this(api, context, null, null, null);
        }

        NativeApi(MontoyaApi api, ScriptExecutionContext context, RequestApi sharedRequest, ResponseApi sharedResponse, ExecutionApi sharedExecution) {
            this.api = api;
            this.context = context;
            this.variables = new VariableScopeApi(context, VariableTarget.LOCAL, false, "variables");
            this.environment = new VariableScopeApi(context, VariableTarget.ENVIRONMENT, true, "environment");
            this.collection = new VariableScopeApi(context, VariableTarget.COLLECTION, true, "collection");
            this.request = sharedRequest != null ? sharedRequest : new RequestApi(context);
            this.response = sharedResponse != null ? sharedResponse : new ResponseApi(context.responseStatusCode, context.responseText, context.responseHeaders, context.parsedResponseJson, context.responseTimeMs);
            this.execution = sharedExecution != null ? sharedExecution : new ExecutionApi(context);
        }

        public ExpectApi expect(Object target) {
            return new ExpectApi(target, context, null, null);
        }

        public void test(String name, Runnable fn) {
            new PostmanApi(api, context).test(name, fn);
        }

        public void sendRequest(Object request) {
            context.warn("awb.sendRequest is not executed in the sandbox yet.", null, null);
        }
    }

    public static final class ExecutionApi {
        private final ScriptExecutionContext context;

        ExecutionApi(ScriptExecutionContext context) {
            this.context = context;
        }

        public void setNextRequest(String name) {
            if (context != null && !context.runnerOnlyFlowControlsAllowed) {
                context.warn("setNextRequest is ignored in single Send mode.", null, null);
                return;
            }
            if (context != null) {
                context.setFlowControl(ScriptFlowControl.SET_NEXT_REQUEST, name, null, "setNextRequest");
            }
        }

        public void skipRequest() {
            if (context != null && !context.runnerOnlyFlowControlsAllowed) {
                context.warn("skipRequest is ignored in single Send mode.", null, null);
                return;
            }
            if (context != null) {
                context.setFlowControl(ScriptFlowControl.SKIP_REQUEST, null, null, "skipRequest");
            }
        }

        public void stopExecution() {
            if (context != null) {
                context.setFlowControl(ScriptFlowControl.STOP_RUN, null, null, "stopExecution");
            }
        }

        public void runRequest(String name) {
            if (context != null) {
                context.setFlowControl(ScriptFlowControl.RUN_REQUEST, name, null, "runRequest");
            }
        }

        public void sendAdHocRequest(String name) {
            if (context != null) {
                context.setFlowControl(ScriptFlowControl.SEND_AD_HOC_REQUEST, name, null, "sendAdHocRequest");
            }
        }
    }

    static void applyRequestMutation(ScriptExecutionContext context) {
        if (context == null || context.request == null || context.requestBinding == null) {
            return;
        }
        context.requestBinding.applyTo(context.request);
    }

    static String sanitizeValue(String value) {
        return value != null ? value : "";
    }
}
