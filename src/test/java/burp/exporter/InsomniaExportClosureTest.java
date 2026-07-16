package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.InsomniaParser;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InsomniaExportClosureTest {
    @TempDir Path tempDir;

    @Test
    void existingAndFallbackWorkspaceIdsAreUsedConsistently() throws Exception {
        ApiCollection exact = collection("wrk_original_exact_id");
        exact.folderPaths.add("Folder"); exact.requests.get(0).path = "Request";
        JsonObject root = InsomniaCollectionExporter.build(exact, null, new ArrayList<>());
        assertThat(resource(root, "workspace", null).get("_id").getAsString()).isEqualTo("wrk_original_exact_id");
        assertThat(resource(root, "environment", null).get("parentId").getAsString()).isEqualTo("wrk_original_exact_id");
        assertThat(resource(root, "request", "Request").get("parentId").getAsString()).isEqualTo("wrk_original_exact_id");
        assertThat(resource(root, "request_group", "Folder").get("parentId").getAsString()).isEqualTo("wrk_original_exact_id");
        assertThat(parse(root).id).isEqualTo("wrk_original_exact_id");

        ApiCollection blank = collection(" ");
        JsonObject fallback = InsomniaCollectionExporter.build(blank, null, new ArrayList<>());
        String generated = resource(fallback, "workspace", null).get("_id").getAsString();
        assertThat(generated).isNotBlank();
        assertThat(resource(fallback, "environment", null).get("parentId").getAsString()).isEqualTo(generated);
        assertThat(resource(fallback, "request", "Request").get("parentId").getAsString()).isEqualTo(generated);
    }

    @Test
    void disabledAndDuplicateVariablesExportSafely() throws Exception {
        ApiCollection collection = collection("wrk");
        collection.environment.put("token", "base");
        collection.variables.add(variable("token", "disabled-secret", false));
        collection.variables.add(variable("missing", "hidden-secret", false));
        collection.variables.add(variable("dup", "first-secret", true));
        collection.variables.add(variable("dup", "last-secret", true));
        List<String> warnings = new ArrayList<>();
        JsonObject root = InsomniaCollectionExporter.build(collection, null, warnings);
        JsonObject data = resource(root, "environment", null).getAsJsonObject("data");
        assertThat(data.get("token").getAsString()).isEqualTo("base");
        assertThat(data.has("missing")).isFalse();
        assertThat(data.get("dup").getAsString()).isEqualTo("last-secret");
        assertThat(warnings).hasSize(3).allSatisfy(w -> assertThat(w)
                .doesNotContain("disabled-secret", "hidden-secret", "first-secret", "last-secret"));
        assertThat(parse(root).environment).containsEntry("token", "base").containsEntry("dup", "last-secret")
                .doesNotContainKey("missing");
    }

    @Test
    void fileOnlyBodiesRoundTripAsFileMetadataIncludingEmptyPath() throws Exception {
        ApiCollection collection = collection("wrk");
        ApiRequest full = collection.requests.get(0); full.body.mode = "file"; full.body.raw = "/tmp/file.bin";
        full.body.contentType = "application/octet-stream";
        ApiRequest empty = request("Empty", "Empty"); empty.body.mode = "file"; empty.body.raw = "";
        empty.body.contentType = "application/custom"; collection.requests.add(empty);
        List<String> warnings = new ArrayList<>(); JsonObject root = InsomniaCollectionExporter.build(collection, null, warnings);
        JsonObject fullBody = resource(root, "request", "Request").getAsJsonObject("body");
        JsonObject emptyBody = resource(root, "request", "Empty").getAsJsonObject("body");
        assertThat(fullBody.get("fileName").getAsString()).isEqualTo("/tmp/file.bin");
        assertThat(fullBody.has("text")).isFalse();
        assertThat(emptyBody.has("fileName")).isTrue(); assertThat(emptyBody.get("fileName").getAsString()).isEmpty();
        ApiCollection imported = parse(root);
        assertThat(byName(imported, "Request").body.mode).isEqualTo("file");
        assertThat(byName(imported, "Request").body.raw).isEqualTo("/tmp/file.bin");
        assertThat(byName(imported, "Request").body.contentType).isEqualTo("application/octet-stream");
        assertThat(byName(imported, "Empty").body.mode).isEqualTo("file");
        assertThat(byName(imported, "Empty").body.raw).isEmpty();
        assertThat(warnings).anyMatch(w -> w.contains("empty file body") && !w.contains("/tmp"));
    }

    @Test
    void graphqlVariablesKeepJsonTypesAndInvalidTextWarns() {
        for (String variables : List.of("{\"id\":1,\"enabled\":true}", "[1,2]", "42", "true", "null", "")) {
            ApiCollection collection = graphqlCollection(variables);
            List<String> warnings = new ArrayList<>();
            JsonObject root = InsomniaCollectionExporter.build(collection, null, warnings);
            JsonObject payload = JsonParser.parseString(resource(root, "request", "GraphQL")
                    .getAsJsonObject("body").get("text").getAsString()).getAsJsonObject();
            assertThat(payload.get("query").getAsString()).isEqualTo("query Q { item }");
            JsonElement expected = variables.isBlank() ? new JsonObject() : JsonParser.parseString(variables);
            assertThat(payload.get("variables")).isEqualTo(expected);
            assertThat(warnings).anyMatch(w -> w.contains("application/json transport payload"));
        }

        ApiCollection invalid = graphqlCollection("secret:not-json"); List<String> warnings = new ArrayList<>();
        JsonObject root = InsomniaCollectionExporter.build(invalid, null, warnings);
        JsonObject payload = JsonParser.parseString(resource(root, "request", "GraphQL")
                .getAsJsonObject("body").get("text").getAsString()).getAsJsonObject();
        assertThat(payload.get("variables").isJsonPrimitive()).isTrue();
        assertThat(payload.get("variables").getAsString()).isEqualTo("secret:not-json");
        assertThat(warnings).anyMatch(w -> w.contains("invalid GraphQL variables") && !w.contains("secret:not-json"));
    }

    @Test
    void graphqlExportReimportsAsEquivalentRawPayload() throws Exception {
        List<String> warnings = new ArrayList<>(); JsonObject root = InsomniaCollectionExporter.build(
                graphqlCollection("{\"id\":1}"), null, warnings);
        ApiRequest imported = byName(parse(root), "GraphQL");
        assertThat(imported.body.mode).isEqualTo("raw");
        assertThat(imported.body.contentType).isEqualTo("application/json");
        JsonObject payload = JsonParser.parseString(imported.body.raw).getAsJsonObject();
        assertThat(payload.get("query").getAsString()).isEqualTo("query Q { item }");
        assertThat(payload.getAsJsonObject("variables").get("id").getAsInt()).isEqualTo(1);
        assertThat(warnings).anyMatch(w -> w.contains("application/json transport payload"));
    }

    @Test
    void testPhaseExportsInGlobalOrderAndDisabledTestOnlyWarnsAsDisabled() {
        ApiCollection collection = collection("wrk"); ApiRequest request = collection.requests.get(0);
        request.scriptBlocks.add(script("post1", ScriptPhase.POST_RESPONSE, 1, true));
        request.scriptBlocks.add(script("test", ScriptPhase.TEST, 2, true));
        request.scriptBlocks.add(script("post2", ScriptPhase.POST_RESPONSE, 3, true));
        request.scriptBlocks.add(script("disabled-secret", ScriptPhase.TEST, 4, false));
        List<String> warnings = new ArrayList<>(); JsonObject root = InsomniaCollectionExporter.build(collection, null, warnings);
        assertThat(resource(root, "request", "Request").get("afterResponseScript").getAsString())
                .isEqualTo("post1\n\ntest\n\npost2").doesNotContain("disabled-secret");
        assertThat(warnings).filteredOn(w -> w.contains("represented TEST script")).hasSize(1);
        assertThat(warnings).anyMatch(w -> w.contains("omitted disabled TEST"));
    }

    private ApiCollection graphqlCollection(String variables) {
        ApiCollection c = new ApiCollection(); c.id = "wrk"; c.name = "G"; ApiRequest r = request("GraphQL", "GraphQL");
        r.body.mode = "graphql"; r.body.graphql = new ApiRequest.Body.GraphQL();
        r.body.graphql.query = "query Q { item }"; r.body.graphql.variables = variables; c.requests.add(r); return c;
    }

    private static ApiCollection collection(String id) {
        ApiCollection c = new ApiCollection(); c.id = id; c.name = "Collection"; c.requests.add(request("Request", "Request")); return c;
    }

    private static ApiRequest request(String name, String path) {
        ApiRequest r = new ApiRequest(); r.name = name; r.path = path; r.method = "POST"; r.url = "https://e.test";
        r.body = new ApiRequest.Body(); r.body.mode = "none"; return r;
    }

    private static ApiRequest.Variable variable(String key, String value, boolean enabled) {
        ApiRequest.Variable v = new ApiRequest.Variable(); v.key = key; v.value = value; v.enabled = enabled; v.type = "string"; return v;
    }

    private static ScriptBlock script(String source, ScriptPhase phase, int order, boolean enabled) {
        ScriptBlock b = ScriptBlock.of(source, ScriptDialect.INSOMNIA, phase, ScriptScope.REQUEST);
        b.order = order; b.enabled = enabled; return b;
    }

    private ApiCollection parse(JsonObject root) throws Exception {
        Path file = tempDir.resolve("insomnia-" + System.nanoTime() + ".json");
        Files.writeString(file, new Gson().toJson(root), StandardCharsets.UTF_8); return new InsomniaParser().parse(file.toFile());
    }

    private static ApiRequest byName(ApiCollection collection, String name) {
        return collection.requests.stream().filter(r -> name.equals(r.name)).findFirst().orElseThrow();
    }

    private static JsonObject resource(JsonObject root, String type, String name) {
        for (JsonElement element : root.getAsJsonArray("resources")) {
            JsonObject object = element.getAsJsonObject();
            if (type.equals(object.get("_type").getAsString())
                    && (name == null || name.equals(object.get("name").getAsString()))) return object;
        }
        throw new AssertionError("missing resource " + type + " " + name);
    }
}
