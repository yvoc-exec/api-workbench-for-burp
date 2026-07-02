package burp.scripts;

import burp.api.montoya.MontoyaApi;
import burp.models.ApiRequest;
import burp.parser.VariableResolver;
import org.graalvm.polyglot.HostAccess;

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

        @HostAccess.Export
        public void log(Object msg) {
            String text = msg != null ? msg.toString() : "null";
            if (context != null) {
                context.log("info", text, null, null);
            }
            if (api != null) {
                api.logging().logToOutput("[Script] " + text);
            }
        }

        @HostAccess.Export
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

        @HostAccess.Export
        public String get(String key) {
            if (context == null || key == null) {
                return "";
            }
            String value = context.variableStore.get(key);
            return value != null ? value : "";
        }

        @HostAccess.Export
        public boolean has(String key) {
            return context != null && key != null && context.variableStore.has(key);
        }

        @HostAccess.Export
        public void set(String key, Object value) {
            set(key, value, null);
        }

        @HostAccess.Export
        public void set(String key, Object value, Object options) {
            boolean persist = persistDefault || optionsIndicatesPersist(options);
            applySet(key, value != null ? value.toString() : "", persist);
        }

        @HostAccess.Export
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
            if (options instanceof org.graalvm.polyglot.Value value) {
                if (value.hasMember("persist")) {
                    try {
                        return value.getMember("persist").asBoolean();
                    } catch (Exception ignored) {
                        Object raw = value.getMember("persist");
                        return raw != null && Boolean.parseBoolean(raw.toString());
                    }
                }
            }
            try {
                java.lang.reflect.Method hasMember = options.getClass().getMethod("hasMember", String.class);
                java.lang.reflect.Method getMember = options.getClass().getMethod("getMember", String.class);
                Object hasPersist = hasMember.invoke(options, "persist");
                if (hasPersist instanceof Boolean b && b) {
                    Object persist = getMember.invoke(options, "persist");
                    if (persist instanceof Boolean bool) {
                        return bool;
                    }
                    if (persist != null) {
                        return Boolean.parseBoolean(persist.toString());
                    }
                }
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    public static class RequestApi {
        @HostAccess.Export
        public String method;
        @HostAccess.Export
        public String url;
        @HostAccess.Export
        public HeaderApi headers;
        @HostAccess.Export
        public BodyApi body;
        @HostAccess.Export
        public AuthApi auth;
        @HostAccess.Export
        public String name;
        @HostAccess.Export
        public String path;
        @HostAccess.Export
        public String sourceCollection;
        boolean originalHasBody;
        public RequestApi() {
        }

        RequestApi(ScriptExecutionContext context) {
            ApiRequest request = context != null ? context.request : null;
            this.method = request != null ? request.method : null;
            this.url = request != null ? request.url : null;
            this.name = request != null ? request.name : null;
            this.path = request != null ? request.path : null;
            this.sourceCollection = request != null ? request.sourceCollection : null;
            this.originalHasBody = request != null && request.body != null;
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
                ApiRequest.Body convertedBody = body.toBody();
                if (convertedBody != null) {
                    request.body = convertedBody;
                } else if (originalHasBody) {
                    request.body = null;
                }
            } else if (originalHasBody) {
                request.body = null;
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

        @HostAccess.Export
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

        @HostAccess.Export
        public boolean has(String name) {
            return findIndex(name) >= 0;
        }

        @HostAccess.Export
        public void add(String name, String value) {
            if (name == null || name.isBlank()) {
                return;
            }
            entries.add(new ApiRequest.Header(name, value != null ? value : ""));
        }

        @HostAccess.Export
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

        @HostAccess.Export
        public void remove(String name) {
            if (name == null || name.isBlank()) {
                return;
            }
            entries.removeIf(header -> header != null && header.key != null && header.key.equalsIgnoreCase(name));
        }

        @HostAccess.Export
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
        @HostAccess.Export
        public String mode;
        @HostAccess.Export
        public String raw;
        @HostAccess.Export
        public String contentType;
        @HostAccess.Export
        public GraphQLApi graphql;
        @HostAccess.Export
        public List<FormFieldApi> formdata = new ArrayList<>();
        @HostAccess.Export
        public List<FormFieldApi> urlencoded = new ArrayList<>();

        BodyApi(ApiRequest.Body source) {
            if (source != null) {
                this.mode = source.mode;
                this.raw = source.raw;
                this.contentType = source.contentType;
                if (source.graphql != null) {
                    this.graphql = new GraphQLApi(source.graphql);
                }
                if (source.formdata != null) {
                    for (ApiRequest.Body.FormField field : source.formdata) {
                        this.formdata.add(new FormFieldApi(field));
                    }
                }
                if (source.urlencoded != null) {
                    for (ApiRequest.Body.FormField field : source.urlencoded) {
                        this.urlencoded.add(new FormFieldApi(field));
                    }
                }
            }
        }

        ApiRequest.Body toBody() {
            List<ApiRequest.Body.FormField> copiedFormData = copyFields(formdata);
            List<ApiRequest.Body.FormField> copiedUrlEncoded = copyFields(urlencoded);
            boolean hasFormData = !copiedFormData.isEmpty();
            boolean hasUrlEncoded = !copiedUrlEncoded.isEmpty();
            if (mode == null && raw == null && contentType == null && graphql == null && !hasFormData && !hasUrlEncoded) {
                return null;
            }
            ApiRequest.Body body = new ApiRequest.Body();
            body.mode = mode;
            body.raw = raw;
            body.contentType = contentType;
            if (graphql != null) {
                body.graphql = graphql.toGraphQL();
            }
            body.formdata = copiedFormData;
            body.urlencoded = copiedUrlEncoded;
            return body;
        }

        private List<ApiRequest.Body.FormField> copyFields(List<FormFieldApi> fields) {
            List<ApiRequest.Body.FormField> out = new ArrayList<>();
            if (fields == null) {
                return out;
            }
            for (FormFieldApi field : fields) {
                if (field != null) {
                    out.add(field.toFormField());
                }
            }
            return out;
        }
    }

    public static final class FormFieldApi {
        @HostAccess.Export
        public String key;
        @HostAccess.Export
        public String value;
        @HostAccess.Export
        public String type;
        @HostAccess.Export
        public boolean fileUpload;
        @HostAccess.Export
        public String filePath;
        @HostAccess.Export
        public boolean disabled;

        public FormFieldApi() {
        }

        FormFieldApi(ApiRequest.Body.FormField source) {
            if (source != null) {
                this.key = source.key;
                this.value = source.value;
                this.type = source.type;
                this.fileUpload = source.fileUpload;
                this.filePath = source.filePath;
                this.disabled = source.disabled;
            }
        }

        ApiRequest.Body.FormField toFormField() {
            ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(key, value);
            field.type = type;
            field.fileUpload = fileUpload;
            field.filePath = filePath;
            field.disabled = disabled;
            return field;
        }
    }

    public static final class GraphQLApi {
        @HostAccess.Export
        public String query;
        @HostAccess.Export
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
        @HostAccess.Export
        public String type;
        @HostAccess.Export
        public final ScriptMapView properties = new ScriptMapView();

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
        @HostAccess.Export
        public int code;
        @HostAccess.Export
        public String status;
        @HostAccess.Export
        public long responseTime;
        @HostAccess.Export
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

        @HostAccess.Export
        public String text() {
            return text;
        }

        @HostAccess.Export
        public Object json() {
            return new ScriptJsonValue(parsedJson);
        }
    }

    public static final class ResponseCodeWrapper {
        @HostAccess.Export
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

        @HostAccess.Export
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

        @HostAccess.Export
        public boolean has(String name) {
            return find(name) != null;
        }

        @HostAccess.Export
        public Map<String, List<String>> asMap() {
            return new LinkedHashMap<>(headers);
        }

        @HostAccess.Export
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

        @HostAccess.Export
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
        @HostAccess.Export
        public final String key;
        @HostAccess.Export
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

        @HostAccess.Export
        public final ExpectApi to = this;
        @HostAccess.Export
        public final ExpectApi be = this;
        @HostAccess.Export
        public final ExpectApi have = this;

        @HostAccess.Export
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

        @HostAccess.Export
        public void have_status(int expected) {
            status(expected);
        }

        @HostAccess.Export
        public void header(String name) {
            boolean passed = hasHeader(name);
            ScriptAssertionResult assertion = new ScriptAssertionResult("Header: " + name, passed, "present", passed ? "present" : "missing");
            assertion.scriptId = scriptId;
            context.addAssertion(assertion);
            if (!passed) {
                throw new AssertionError("expected header " + name + " to be present");
            }
        }

        @HostAccess.Export
        public void have_header(String name) {
            header(name);
        }

        @HostAccess.Export
        public void property(String name) {
            boolean passed = hasProperty(target, name);
            ScriptAssertionResult assertion = new ScriptAssertionResult("Property: " + name, passed, "present", passed ? "present" : "missing");
            assertion.scriptId = scriptId;
            context.addAssertion(assertion);
            if (!passed) {
                throw new AssertionError("expected property " + name + " to be present");
            }
        }

        @HostAccess.Export
        public void equal(Object expected) {
            boolean passed = valuesEqual(target, expected);
            ScriptAssertionResult assertion = new ScriptAssertionResult("Equals " + expected, passed, String.valueOf(expected), String.valueOf(target));
            assertion.scriptId = scriptId;
            context.addAssertion(assertion);
            if (!passed) {
                throw new AssertionError("expected " + expected + " but got " + target);
            }
        }

        @HostAccess.Export
        public void eql(Object expected) {
            equal(expected);
        }

        private boolean valuesEqual(Object actual, Object expected) {
            actual = unwrap(actual);
            expected = unwrap(expected);
            if (actual instanceof Number && expected instanceof Number) {
                Number left = (Number) actual;
                Number right = (Number) expected;
                try {
                    java.math.BigDecimal leftDecimal = new java.math.BigDecimal(left.toString());
                    java.math.BigDecimal rightDecimal = new java.math.BigDecimal(right.toString());
                    return leftDecimal.compareTo(rightDecimal) == 0;
                } catch (NumberFormatException ignored) {
                    return Double.compare(left.doubleValue(), right.doubleValue()) == 0;
                }
            }
            if (actual instanceof Boolean || expected instanceof Boolean) {
                return Boolean.parseBoolean(String.valueOf(actual)) == Boolean.parseBoolean(String.valueOf(expected));
            }
            if (actual == null || expected == null) {
                return actual == expected;
            }
            return Objects.equals(actual, expected) || String.valueOf(actual).equals(String.valueOf(expected));
        }

        private Object unwrap(Object value) {
            if (value instanceof org.graalvm.polyglot.Value polyValue) {
                try {
                    if (polyValue.isNull()) {
                        return null;
                    }
                    if (polyValue.isBoolean()) {
                        return polyValue.asBoolean();
                    }
                    if (polyValue.isNumber()) {
                        try {
                            return polyValue.as(Object.class);
                        } catch (Exception ignored) {
                            return polyValue.asDouble();
                        }
                    }
                    if (polyValue.isString()) {
                        return polyValue.asString();
                    }
                    return polyValue.as(Object.class);
                } catch (Exception ignored) {
                    return polyValue.toString();
                }
            }
            return value;
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
        @HostAccess.Export
        public final VariableScopeApi environment;
        @HostAccess.Export
        public final VariableScopeApi collectionVariables;
        @HostAccess.Export
        public final VariableScopeApi variables;
        @HostAccess.Export
        public final RequestApi request;
        @HostAccess.Export
        public final ExecutionApi execution;
        @HostAccess.Export
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

        @HostAccess.Export
        public ExpectApi expect(Object target) {
            return new ExpectApi(target, context, null, null);
        }

        @HostAccess.Export
        public void test(String name, Object fn) {
            String assertionName = name != null ? name : "pm.test";
            if (fn == null) {
                ScriptAssertionResult assertion = new ScriptAssertionResult(assertionName, false, "callback", "null");
                context.addAssertion(assertion);
                return;
            }
            int before = context.result.assertions.size();
            try {
                invokeCallback(fn);
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

        @HostAccess.Export
        public void sendRequest(Object request) {
            context.warn("pm.sendRequest is not executed in the sandbox yet.", null, null);
        }

        private void invokeCallback(Object fn) throws Throwable {
            if (fn instanceof Runnable runnable) {
                runnable.run();
                return;
            }
            if (fn instanceof org.graalvm.polyglot.Value value) {
                if (value.canExecute()) {
                    value.execute();
                    return;
                }
            }
            Throwable lastError = null;
            for (String methodName : java.util.List.of("execute", "call", "apply", "invoke", "run")) {
                for (java.lang.reflect.Method method : fn.getClass().getMethods()) {
                    if (!method.getName().equals(methodName)) {
                        continue;
                    }
                    try {
                        Object[] args = buildCallbackArguments(method);
                        if (args == null) {
                            continue;
                        }
                        method.setAccessible(true);
                        method.invoke(fn, args);
                        return;
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        if (ite.getTargetException() != null) {
                            throw ite.getTargetException();
                        }
                        throw ite;
                    } catch (Throwable t) {
                        lastError = t;
                    }
                }
            }
            if (lastError != null) {
                throw lastError;
            }
            throw new IllegalArgumentException("Unsupported callback type: " + fn.getClass().getName());
        }

        private Object[] buildCallbackArguments(java.lang.reflect.Method method) {
            if (method == null) {
                return null;
            }
            int parameterCount = method.getParameterCount();
            if (parameterCount == 0) {
                return new Object[0];
            }
            if (parameterCount == 1 && method.isVarArgs()) {
                Class<?> arrayType = method.getParameterTypes()[0];
                Class<?> componentType = arrayType != null ? arrayType.getComponentType() : Object.class;
                Object emptyArray = java.lang.reflect.Array.newInstance(componentType != null ? componentType : Object.class, 0);
                return new Object[]{emptyArray};
            }
            if (parameterCount == 1 && method.getParameterTypes()[0].isArray()) {
                Class<?> componentType = method.getParameterTypes()[0].getComponentType();
                Object emptyArray = java.lang.reflect.Array.newInstance(componentType != null ? componentType : Object.class, 0);
                return new Object[]{emptyArray};
            }
            return null;
        }
    }

    public static class BrunoApi {
        @HostAccess.Export
        public final VariableScopeApi reqScope;
        @HostAccess.Export
        public final VariableScopeApi envScope;
        @HostAccess.Export
        public final VariableScopeApi collectionScope;
        @HostAccess.Export
        public final VariableScopeApi folderScope;
        @HostAccess.Export
        public final VariableScopeApi requestScope;
        @HostAccess.Export
        public final VariableScopeApi localScope;
        @HostAccess.Export
        public final RequestApi req;
        @HostAccess.Export
        public final ResponseApi res;
        @HostAccess.Export
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

        @HostAccess.Export
        public String getVar(String key) {
            return reqScope.get(key);
        }

        @HostAccess.Export
        public void setVar(String key, Object value) {
            reqScope.set(key, value);
        }

        @HostAccess.Export
        public String getEnvVar(String key) {
            return envScope.get(key);
        }

        @HostAccess.Export
        public void setEnvVar(String key, Object value) {
            envScope.set(key, value);
        }

        @HostAccess.Export
        public void setEnvVar(String key, Object value, Object options) {
            envScope.set(key, value, options);
        }

        @HostAccess.Export
        public String getCollectionVar(String key) {
            return collectionScope.get(key);
        }

        @HostAccess.Export
        public void setCollectionVar(String key, Object value) {
            collectionScope.set(key, value);
        }

        @HostAccess.Export
        public String getFolderVar(String key) {
            return folderScope.get(key);
        }

        @HostAccess.Export
        public void setFolderVar(String key, Object value) {
            folderScope.set(key, value);
        }

        @HostAccess.Export
        public String getRequestVar(String key) {
            return requestScope.get(key);
        }

        @HostAccess.Export
        public void setRequestVar(String key, Object value) {
            requestScope.set(key, value);
        }

        @HostAccess.Export
        public ExpectApi expect(Object target) {
            return new ExpectApi(target, context, null, null);
        }

        @HostAccess.Export
        public void test(String name, Object fn) {
            new PostmanApi(api, context).test(name, fn);
        }

        @HostAccess.Export
        public void sendRequest(Object request) {
            context.warn("bru.sendRequest is not executed in the sandbox yet.", null, null);
        }

        @HostAccess.Export
        public void runRequest(Object request) {
            if (context != null) {
                context.runDependentRequest(request != null ? request.toString() : null);
            }
        }

        @HostAccess.Export
        public void setNextRequest(String name) {
            if (context != null && !context.runnerOnlyFlowControlsAllowed) {
                context.warn("bru.setNextRequest is ignored in single Send mode.", null, null);
                return;
            }
            context.setFlowControl(ScriptFlowControl.SET_NEXT_REQUEST, name, null, "bru.setNextRequest");
        }

        @HostAccess.Export
        public void skipRequest() {
            if (context != null && !context.runnerOnlyFlowControlsAllowed) {
                context.warn("bru.skipRequest is ignored in single Send mode.", null, null);
                return;
            }
            context.setFlowControl(ScriptFlowControl.SKIP_REQUEST, null, null, "bru.skipRequest");
        }

        @HostAccess.Export
        public void stopExecution() {
            if (context != null && !context.runnerOnlyFlowControlsAllowed) {
                context.warn("bru.stopExecution is ignored in single Send mode.", null, null);
                return;
            }
            context.setFlowControl(ScriptFlowControl.STOP_RUN, null, null, "bru.stopExecution");
        }
    }

    public static class InsomniaApi {
        @HostAccess.Export
        public final VariableScopeApi environment;
        @HostAccess.Export
        public final VariableScopeApi baseEnvironment;
        @HostAccess.Export
        public final VariableScopeApi collectionVariables;
        @HostAccess.Export
        public final VariableScopeApi variables;
        @HostAccess.Export
        public final RequestApi request;
        @HostAccess.Export
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

        @HostAccess.Export
        public ExpectApi expect(Object target) {
            return new ExpectApi(target, context, null, null);
        }

        @HostAccess.Export
        public void test(String name, Object fn) {
            new PostmanApi(api, context).test(name, fn);
        }

        @HostAccess.Export
        public void sendRequest(Object request) {
            context.warn("insomnia.sendRequest is not executed in the sandbox yet.", null, null);
        }
    }

    public static class NativeApi {
        @HostAccess.Export
        public final VariableScopeApi variables;
        @HostAccess.Export
        public final VariableScopeApi environment;
        @HostAccess.Export
        public final VariableScopeApi collection;
        @HostAccess.Export
        public final RequestApi request;
        @HostAccess.Export
        public final ResponseApi response;
        @HostAccess.Export
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

        @HostAccess.Export
        public ExpectApi expect(Object target) {
            return new ExpectApi(target, context, null, null);
        }

        @HostAccess.Export
        public void test(String name, Object fn) {
            new PostmanApi(api, context).test(name, fn);
        }

        @HostAccess.Export
        public void sendRequest(Object request) {
            context.warn("awb.sendRequest is not executed in the sandbox yet.", null, null);
        }
    }

    public static final class ExecutionApi {
        private final ScriptExecutionContext context;

        ExecutionApi(ScriptExecutionContext context) {
            this.context = context;
        }

        @HostAccess.Export
        public void setNextRequest(String name) {
            if (context != null && !context.runnerOnlyFlowControlsAllowed) {
                context.warn("setNextRequest is ignored in single Send mode.", null, null);
                return;
            }
            if (context != null) {
                context.setFlowControl(ScriptFlowControl.SET_NEXT_REQUEST, name, null, "setNextRequest");
            }
        }

        @HostAccess.Export
        public void skipRequest() {
            if (context != null && !context.runnerOnlyFlowControlsAllowed) {
                context.warn("skipRequest is ignored in single Send mode.", null, null);
                return;
            }
            if (context != null) {
                context.setFlowControl(ScriptFlowControl.SKIP_REQUEST, null, null, "skipRequest");
            }
        }

        @HostAccess.Export
        public void stopExecution() {
            if (context != null && !context.runnerOnlyFlowControlsAllowed) {
                context.warn("stopExecution is ignored in single Send mode.", null, null);
                return;
            }
            if (context != null) {
                context.setFlowControl(ScriptFlowControl.STOP_RUN, null, null, "stopExecution");
            }
        }

        @HostAccess.Export
        public void runRequest(String name) {
            runRequest((Object) name);
        }

        @HostAccess.Export
        public void runRequest(Object target) {
            if (context == null) {
                return;
            }
            context.runDependentRequest(target != null ? target.toString() : null);
        }

        @HostAccess.Export
        public void sendAdHocRequest(String name) {
            sendAdHocRequest((Object) name);
        }

        @HostAccess.Export
        public void sendAdHocRequest(Object request) {
            if (context == null) {
                return;
            }
            context.sendAdHocRequest(ScriptAdHocRequest.from(request));
        }
    }

    public static final class ScriptMapView extends LinkedHashMap<String, String> {
        public ScriptMapView() {
        }

        public ScriptMapView(Map<String, String> source) {
            if (source != null) {
                putAll(source);
            }
        }

        @HostAccess.Export
        @Override
        public String get(Object key) {
            String value = super.get(key);
            return value != null ? value : "";
        }

        @HostAccess.Export
        public String getOrDefault(String key, String defaultValue) {
            return super.getOrDefault(key, defaultValue);
        }

        @HostAccess.Export
        public boolean has(String key) {
            return containsKey(key);
        }

        @HostAccess.Export
        public void set(String key, Object value) {
            if (key == null || key.isBlank()) {
                return;
            }
            put(key, value != null ? value.toString() : "");
        }

        @HostAccess.Export
        public void unset(String key) {
            if (key != null) {
                remove(key);
            }
        }
    }

    public static final class ScriptJsonValue {
        private final Object value;

        ScriptJsonValue(Object value) {
            this.value = value;
        }

        @HostAccess.Export
        public Object get(String key) {
            Object raw = read(key);
            return wrap(raw);
        }

        @HostAccess.Export
        public boolean has(String key) {
            return read(key) != null;
        }

        @HostAccess.Export
        public Object raw() {
            return value;
        }

        @HostAccess.Export
        public String text() {
            return value == null ? "" : value.toString();
        }

        private Object read(String key) {
            if (value instanceof Map<?, ?> map) {
                return map.get(key);
            }
            if (value instanceof List<?> list) {
                try {
                    int index = Integer.parseInt(key);
                    return index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        private Object wrap(Object raw) {
            if (raw instanceof Map<?, ?> || raw instanceof List<?>) {
                return new ScriptJsonValue(raw);
            }
            return raw;
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
