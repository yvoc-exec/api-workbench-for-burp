package burp.scripts;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.utils.ScriptMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptBindingsPersistenceOverrideTest {

    @Test
    void persistentDefaultWithoutOptionStaysPersistent() {
        ScriptExecutionResult result = nativeMutation(api -> api.environment.set("token", "value"));
        assertMutation(result, "environment", "token", "value", true);
    }

    @Test
    void persistentDefaultExplicitTrueStaysPersistent() {
        ScriptExecutionResult result = nativeMutation(api -> api.environment.set("token", "value", Map.of("persist", true)));
        assertMutation(result, "environment", "token", "value", true);
    }

    @Test
    void persistentDefaultExplicitFalseBecomesRuntimeOnly() {
        ScriptExecutionResult result = nativeMutation(api -> api.environment.set("token", "value", Map.of("persist", false)));
        assertMutation(result, "environment", "token", "value", false);
    }

    @Test
    void runtimeDefaultWithoutOptionStaysRuntimeOnly() {
        ScriptExecutionResult result = brunoMutation(api -> api.envScope.set("token", "value"));
        assertMutation(result, "environment", "token", "value", false);
    }

    @Test
    void runtimeDefaultExplicitTrueBecomesPersistent() {
        ScriptExecutionResult result = brunoMutation(api -> api.envScope.set("token", "value", Map.of("persist", true)));
        assertMutation(result, "environment", "token", "value", true);
    }

    @Test
    void runtimeDefaultExplicitFalseStaysRuntimeOnly() {
        ScriptExecutionResult result = brunoMutation(api -> api.envScope.set("token", "value", Map.of("persist", false)));
        assertMutation(result, "environment", "token", "value", false);
    }

    @Test
    void mapFalseOverridesPersistentDefault() {
        ScriptExecutionResult result = nativeMutation(api -> api.environment.set("token", "value", Map.of("persist", false)));
        assertMutation(result, "environment", "token", "value", false);
    }

    @Test
    void mapTrueOverridesRuntimeDefault() {
        ScriptExecutionResult result = brunoMutation(api -> api.envScope.set("token", "value", Map.of("persist", true)));
        assertMutation(result, "environment", "token", "value", true);
    }

    @Test
    void graalGuestFalseOverridesPersistentDefault() {
        ScriptExecutionResult result = runScript("""
                awb.environment.set('token', 'value', { persist: false });
                """, ScriptDialect.API_WORKBENCH);
        assertMutation(result, "environment", "token", "value", false);
    }

    @Test
    void graalGuestTrueOverridesRuntimeDefault() {
        ScriptExecutionResult result = runScript("""
                bru.envScope.set('token', 'value', { persist: true });
                """, ScriptDialect.BRUNO);
        assertMutation(result, "environment", "token", "value", true);
    }

    @Test
    void absentPersistMemberUsesDefault() {
        ScriptExecutionResult result = nativeMutation(api -> api.environment.set("token", "value", Map.of()));
        assertMutation(result, "environment", "token", "value", true);
    }

    @Test
    void nullPersistMemberUsesDefault() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("persist", null);
        ScriptExecutionResult result = nativeMutation(api -> api.environment.set("token", "value", options));
        assertMutation(result, "environment", "token", "value", true);
    }

    @Test
    void invalidPersistStringUsesDefault() {
        ScriptExecutionResult result = nativeMutation(api -> api.environment.set("token", "value", Map.of("persist", "maybe")));
        assertMutation(result, "environment", "token", "value", true);
    }

    @Test
    void explicitStringFalseOverridesPersistentDefault() {
        ScriptExecutionResult result = nativeMutation(api -> api.environment.set("token", "value", Map.of("persist", "false")));
        assertMutation(result, "environment", "token", "value", false);
    }

    @Test
    void explicitStringTrueOverridesRuntimeDefault() {
        ScriptExecutionResult result = brunoMutation(api -> api.envScope.set("token", "value", Map.of("persist", "TRUE")));
        assertMutation(result, "environment", "token", "value", true);
    }

    @Test
    void persistentDefaultUnsetWithFalseBecomesRuntimeUnset() {
        ScriptExecutionResult result = nativeMutation(api -> api.environment.unset("token", Map.of("persist", false)));
        assertMutation(result, "environment", "token", null, false);
    }

    @Test
    void runtimeDefaultUnsetWithTrueBecomesPersistentUnset() {
        ScriptExecutionResult result = brunoMutation(api -> api.envScope.unset("token", Map.of("persist", true)));
        assertMutation(result, "environment", "token", null, true);
    }

    @Test
    void oneArgumentUnsetUsesDefault() {
        ScriptExecutionResult result = nativeMutation(api -> api.environment.unset("token"));
        assertMutation(result, "environment", "token", null, true);
    }

    @Test
    void nativeEnvironmentDefaultsPersistent() {
        ScriptExecutionResult result = nativeMutation(api -> api.environment.set("token", "value"));
        assertMutation(result, "environment", "token", "value", true);
    }

    @Test
    void nativeEnvironmentExplicitFalseIsRuntimeOnly() {
        ScriptExecutionResult result = nativeMutation(api -> api.environment.set("token", "value", Map.of("persist", false)));
        assertMutation(result, "environment", "token", "value", false);
    }

    @Test
    void brunoEnvironmentDefaultsRuntimeOnly() {
        ScriptExecutionResult result = brunoMutation(api -> api.envScope.set("token", "value"));
        assertMutation(result, "environment", "token", "value", false);
    }

    @Test
    void brunoEnvironmentExplicitTrueIsPersistent() {
        ScriptExecutionResult result = brunoMutation(api -> api.envScope.set("token", "value", Map.of("persist", true)));
        assertMutation(result, "environment", "token", "value", true);
    }

    @Test
    void insomniaEnvironmentExplicitFalseOverridesPersistentDefault() {
        ScriptExecutionResult result = insomniaMutation(api -> api.environment.set("token", "value", Map.of("persist", false)));
        assertMutation(result, "environment", "token", "value", false);
    }

    @Test
    void nativeEnvironmentUnsetExplicitFalseTargetsRuntimeLayer() {
        ScriptExecutionResult result = nativeMutation(api -> api.environment.unset("token", Map.of("persist", false)));
        assertMutation(result, "environment", "token", null, false);
    }

    private ScriptExecutionResult nativeMutation(Consumer<ScriptBindingsFactory.NativeApi> action) {
        ScriptExecutionContext context = context();
        action.accept(new ScriptBindingsFactory.NativeApi(null, context));
        return context.result;
    }

    private ScriptExecutionResult brunoMutation(Consumer<ScriptBindingsFactory.BrunoApi> action) {
        ScriptExecutionContext context = context();
        action.accept(new ScriptBindingsFactory.BrunoApi(null, context));
        return context.result;
    }

    private ScriptExecutionResult insomniaMutation(Consumer<ScriptBindingsFactory.InsomniaApi> action) {
        ScriptExecutionContext context = context();
        action.accept(new ScriptBindingsFactory.InsomniaApi(null, context));
        return context.result;
    }

    private ScriptExecutionResult runScript(String source, ScriptDialect dialect) {
        ApiCollection collection = collection();
        collection.scriptBlocks.add(block(source, dialect));
        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS, 5_000);
        try {
            return runtime.executePreRequest(collection, request(), environment(), "Send", 1);
        } finally {
            runtime.close();
        }
    }

    private ScriptExecutionContext context() {
        return new ScriptExecutionContext(null, collection(), request(), environment(), ExecutionSource.WORKBENCH_SEND, 1);
    }

    private static ApiCollection collection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        collection.scriptBlocks = new ArrayList<>();
        collection.requests = new ArrayList<>();
        return collection;
    }

    private static ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.id = "req";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test/start";
        request.path = "Parent/Child/Request";
        return request;
    }

    private static EnvironmentProfile environment() {
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Environment";
        return environment;
    }

    private static ScriptBlock block(String source, ScriptDialect dialect) {
        ScriptBlock block = new ScriptBlock();
        block.id = "script";
        block.dialect = dialect;
        block.phase = ScriptPhase.PRE_REQUEST;
        block.scope = ScriptScope.REQUEST;
        block.source = source;
        block.enabled = true;
        return block;
    }

    private static void assertMutation(ScriptExecutionResult result,
                                       String scope,
                                       String key,
                                       String newValue,
                                       boolean persistent) {
        assertThat(result.variableMutations).hasSize(1);
        ScriptVariableMutation mutation = result.variableMutations.get(0);
        assertThat(mutation.scope).isEqualTo(scope);
        assertThat(mutation.key).isEqualTo(key);
        assertThat(mutation.newValue).isEqualTo(newValue);
        assertThat(mutation.persistent).isEqualTo(persistent);
    }
}
