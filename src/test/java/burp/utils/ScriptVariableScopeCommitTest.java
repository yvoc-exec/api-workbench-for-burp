package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.scripts.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptVariableScopeCommitTest {
    @Test
    void localVariableDoesNotLeakToNextRequest() {
        ApiCollection c = collection("awb.variables.set('k','local');");
        execute(c, request("r1"), null);
        execute(collection(""), request("r2"), null);
        assertThat(c.runtimeVars).doesNotContainKey("k");
    }

    @Test
    void requestVariableDoesNotBecomeCollectionRuntime() {
        ApiCollection c = collection("bru.requestScope.set('k','request');", ScriptDialect.BRUNO);
        execute(c, request("r1"), null);
        assertThat(c.runtimeVars).doesNotContainKey("k");
    }

    @Test
    void environmentMutationUpdatesOnlyEnvironmentScope() {
        EnvironmentProfile env = new EnvironmentProfile();
        ApiCollection c = collection("awb.environment.set('k','env');");
        execute(c, request("r1"), env);
        assertThat(env.variables).containsEntry("k", "env");
        assertThat(c.runtimeVars).doesNotContainKey("k");
    }

    @Test
    void collectionMutationUpdatesOnlyCollectionScope() {
        EnvironmentProfile env = new EnvironmentProfile();
        ApiCollection c = collection("awb.collection.set('k','col');");
        execute(c, request("r1"), env);
        assertThat(c.runtimeVars).containsEntry("k", "col");
        assertThat(env.variables).doesNotContainKey("k");
    }

    @Test
    void folderMutationUpdatesOnlyOwningFolder() {
        ApiCollection c = collection("bru.folderScope.set('k','folder');", ScriptDialect.BRUNO);
        ApiRequest r = request("r1");
        r.path = "Folder/Request";
        execute(c, r, null);
        assertThat(c.folderVars.values()).anySatisfy(vars -> assertThat(vars).containsEntry("k", "folder"));
        assertThat(c.folderVars.get("Sibling")).containsEntry("k", "sibling");
    }

    @Test
    void localUnsetDoesNotDeleteCollectionValue() {
        ApiCollection c = collection("awb.variables.unset('k');");
        c.runtimeVars.put("k", "collection");
        execute(c, request("r1"), null);
        assertThat(c.runtimeVars).containsEntry("k", "collection");
    }

    @Test
    void sameKeyInMultipleScopesRemainsDistinct() {
        EnvironmentProfile env = new EnvironmentProfile();
        ApiCollection c = collection("awb.environment.set('k','env'); awb.collection.set('k','col');");
        execute(c, request("r1"), env);
        assertThat(env.variables).containsEntry("k", "env");
        assertThat(c.runtimeVars).containsEntry("k", "col");
    }

    @Test
    void persistentAndRuntimeMutationPathsAreNotDoubleApplied() {
        ApiCollection c = collection("awb.collection.set('k','one'); awb.collection.set('k','two');");
        execute(c, request("r1"), null);
        assertThat(c.runtimeVars).containsEntry("k", "two");
        assertThat(c.runtimeVars).hasSize(1);
    }

    private void execute(ApiCollection collection, ApiRequest request, EnvironmentProfile env) {
        new SharedRequestPipeline(null, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.FULL_JS), null)
                .execute(request, collection, false, null, null, null, env);
    }

    private ApiCollection collection(String script) {
        return collection(script, ScriptDialect.API_WORKBENCH);
    }

    private ApiCollection collection(String script, ScriptDialect dialect) {
        ApiCollection c = new ApiCollection();
        c.name = "c";
        c.requests = new ArrayList<>();
        c.scriptBlocks = new ArrayList<>();
        c.scriptBlocks.add(ScriptBlock.of(script, dialect, ScriptPhase.PRE_REQUEST, ScriptScope.COLLECTION));
        c.folderVars.put("Sibling", new java.util.LinkedHashMap<>(Map.of("k", "sibling")));
        return c;
    }

    private ApiRequest request(String name) {
        ApiRequest r = new ApiRequest();
        r.name = name;
        r.id = name;
        r.method = "GET";
        r.url = "https://example.test";
        r.path = "Folder/" + name;
        return r;
    }
}
