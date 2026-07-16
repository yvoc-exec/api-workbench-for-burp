package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.UnresolvedVariableIssue;
import burp.parser.ApiWorkbenchCollectionParser;
import burp.parser.CollectionParser;
import burp.parser.ParserRegistry;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.utils.AuthInheritanceResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionExportServiceTest {
    @TempDir
    Path tempDir;

    private final CollectionExportService service = new CollectionExportService();

    @Test
    void exportsNativeCollectionJsonWithRoundTripMetadataAndNoRuntimeState() throws Exception {
        ApiCollection collection = ExportTestFixtures.sampleCollection();
        collection.id = "col-apim";
        Path output = tempDir.resolve("apim.api-workbench.collection.json");

        ExportResult result = service.exportCollection(
                collection,
                new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON, output, false, null, Map.of())
        );

        assertThat(result.outputPath).isEqualTo(output.toAbsolutePath().normalize());
        assertThat(result.formatLabel).isEqualTo(CollectionExportFormat.API_WORKBENCH_JSON.displayName());
        assertThat(result.requestCount).isEqualTo(collection.requests.size());
        JsonObject root = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertThat(root.get("format").getAsString()).isEqualTo("api-workbench-collection");
        JsonObject exported = root.getAsJsonObject("collection");
        assertThat(exported.get("id").getAsString()).isEqualTo("col-apim");
        assertThat(exported.get("name").getAsString()).isEqualTo("APIM");
        assertThat(exported.get("description").getAsString()).isEqualTo("API collection for exports");
        assertThat(jsonArrayStrings(exported.getAsJsonArray("folderPaths")))
                .contains("Auth", "Auth/OAuth", "Users");
        assertThat(exported.getAsJsonArray("scriptBlocks")).isNotNull();
        assertThat(exported.getAsJsonObject("folderScriptBlocks").getAsJsonArray("Auth")).isNotNull();
        assertThat(exported.toString()).doesNotContain("runtime_only", "runtime-token");

        JsonObject login = requestByName(exported.getAsJsonArray("requests"), "Auth");
        assertThat(login.get("path").getAsString()).isEqualTo("Auth");
        assertThat(login.get("url").getAsString()).contains("{{base_url}}");
        assertThat(login.get("editorMaterialized").getAsBoolean()).isTrue();
        assertThat(login.get("buildMode").getAsString()).isEqualTo("MANUAL_PRESERVE");
        assertThat(jsonArrayStrings(login.getAsJsonArray("suppressedAutoHeaders")))
                .contains("accept");
        assertThat(login.getAsJsonObject("body").get("raw").getAsString()).contains("{{missing_password}}");
        assertThat(login.getAsJsonObject("auth").get("type").getAsString()).isEqualTo("bearer");
    }

    @Test
    void roundTripsNativeCollectionThroughParserRegistry() throws Exception {
        ApiCollection collection = ExportTestFixtures.sampleCollection();
        collection.id = "col-apim";
        Path output = tempDir.resolve("apim.api-workbench.collection.json");

        service.exportCollection(
                collection,
                new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON, output, false, null, Map.of())
        );

        ParserRegistry registry = new ParserRegistry();
        CollectionParser parser = registry.detectParser(output.toFile());
        assertThat(parser).isInstanceOf(ApiWorkbenchCollectionParser.class);

        ApiCollection imported = parser.parse(output.toFile());
        assertThat(imported.id).isEqualTo("col-apim");
        assertThat(imported.name).isEqualTo("APIM");
        assertThat(imported.description).isEqualTo("API collection for exports");
        assertThat(imported.format).isEqualTo("api-workbench");
        assertThat(imported.version).isEqualTo("1.2.3");
        assertThat(imported.folderPaths).containsExactly("Auth", "Auth/OAuth", "Users");
        assertThat(imported.variables).extracting("key", "value")
                .contains(
                        org.assertj.core.groups.Tuple.tuple("base_url", "https://api.example.test"),
                        org.assertj.core.groups.Tuple.tuple("role", "admin"),
                        org.assertj.core.groups.Tuple.tuple("client_id", "client-123"),
                        org.assertj.core.groups.Tuple.tuple("collection_token", "collection-token")
                );
        assertThat(imported.environment).containsEntry("env_mode", "uat");
        assertThat(imported.auth.type).isEqualTo("basic");
        assertThat(imported.auth.properties).containsEntry("username", "collection-user");
        assertThat(imported.folderAuthModes).containsEntry("Auth", "explicit");
        assertThat(imported.folderAuth.get("Auth").type).isEqualTo("bearer");
        assertThat(imported.folderVars.get("Auth")).containsEntry("folder_var", "folder-value");
        assertThat(imported.requests).hasSize(collection.requests.size());
        assertThat(imported.runtimeVars).isEmpty();
        assertThat(imported.runtimeOAuth2).isEmpty();

        ApiRequest login = requestById(imported.requests, "req-login");
        assertThat(login.name).isEqualTo("Auth");
        assertThat(login.path).isEqualTo("Auth");
        assertThat(login.sourceCollection).isEqualTo("APIM");
        assertThat(login.method).isEqualTo("POST");
        assertThat(login.url).isEqualTo("{{base_url}}/login");
        assertThat(login.description).isEqualTo("Login request");
        assertThat(login.editorMaterialized).isTrue();
        assertThat(login.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(login.suppressedAutoHeaders).contains("accept");
        assertThat(login.headers).anySatisfy(header -> {
            assertThat(header.key).isEqualTo("X-Test");
            assertThat(header.value).isEqualTo("workflow");
        });
        assertThat(login.headers).anySatisfy(header -> {
            assertThat(header.key).isEqualTo("Accept");
            assertThat(header.value).isEqualTo("application/json");
        });
        assertThat(login.body.mode).isEqualTo("raw");
        assertThat(login.body.raw).contains("{{missing_password}}");
        assertThat(login.auth.type).isEqualTo("bearer");
        assertThat(login.explicitAuth.type).isEqualTo("bearer");
        assertThat(login.authOverrideMode).isEqualTo("explicit");
        assertThat(login.authInherited).isFalse();
        assertThat(login.authExplicitlyDisabled).isFalse();
        assertThat(login.authSource).isEqualTo("request:Auth");
        assertThat(login.variables).extracting("key", "value")
                .contains(org.assertj.core.groups.Tuple.tuple("request_var", "request-value"));
        assertThat(login.preRequestScripts).hasSize(1);
        assertThat(login.postResponseScripts).hasSize(1);
        assertThat(login.scriptBlocks).hasSize(2);
        assertThat(login.scriptBlocks.get(0).dialect).isEqualTo(ScriptDialect.POSTMAN);
        assertThat(login.scriptBlocks.get(0).phase).isEqualTo(ScriptPhase.PRE_REQUEST);
        assertThat(login.scriptBlocks.get(0).scope).isEqualTo(ScriptScope.REQUEST);
        assertThat(login.scriptBlocks.get(1).dialect).isEqualTo(ScriptDialect.INSOMNIA);
        assertThat(imported.scriptBlocks).hasSize(1);
        assertThat(imported.scriptBlocks.get(0).dialect).isEqualTo(ScriptDialect.POSTMAN);
        assertThat(imported.folderScriptBlocks.get("Auth")).hasSize(1);
        assertThat(imported.folderScriptBlocks.get("Auth").get(0).dialect).isEqualTo(ScriptDialect.BRUNO);
        assertThat(login.disabled).isFalse();
        assertThat(login.sequenceOrder).isEqualTo(1);

        ApiRequest oauth = requestById(imported.requests, "req-oauth");
        assertThat(oauth.name).isEqualTo("OAuth");
        assertThat(oauth.path).isEqualTo("Auth/OAuth");
        assertThat(oauth.method).isEqualTo("POST");
        assertThat(oauth.url).isEqualTo("{{base_url}}/oauth/token");
        assertThat(oauth.body.mode).isEqualTo("urlencoded");
        assertThat(oauth.body.urlencoded).hasSize(3);
        assertThat(oauth.auth.type).isEqualTo("none");
        assertThat(oauth.authOverrideMode).isEqualTo("none");
        assertThat(oauth.authExplicitlyDisabled).isTrue();

        ApiRequest usersRoot = requestById(imported.requests, "req-users-root");
        assertThat(usersRoot.name).isEqualTo("GET /users");
        assertThat(usersRoot.path).isBlank();
        assertThat(usersRoot.url).contains("role={{role}}");
        assertThat(usersRoot.headers).anySatisfy(header -> {
            assertThat(header.key).isEqualTo("Cookie");
            assertThat(header.value).contains("session={{session_cookie}}");
        });

        ApiRequest usersFolder = requestById(imported.requests, "req-users-folder");
        assertThat(usersFolder.name).isEqualTo("users\\{id}");
        assertThat(usersFolder.path).isEqualTo("Users");
        assertThat(usersFolder.url).isEqualTo("{{base_url}}/users/{{user_id}}");

        ApiRequest graphql = requestById(imported.requests, "req-graphql");
        assertThat(graphql.path).isEqualTo("Auth");
        assertThat(graphql.body.mode).isEqualTo("graphql");
        assertThat(graphql.body.graphql.query).contains("GetUser");
        assertThat(graphql.auth.type).isEqualTo("bearer");
        assertThat(graphql.authInherited).isTrue();

        ApiRequest upload = requestById(imported.requests, "req-upload");
        assertThat(upload.path).isEqualTo("Users");
        assertThat(upload.body.mode).isEqualTo("formdata");
        assertThat(upload.body.formdata).anySatisfy(field -> {
            assertThat(field.key).isEqualTo("file");
            assertThat(field.fileUpload).isTrue();
            assertThat(field.filePath).isEqualTo("{{upload_path}}");
        });
    }

    @Test
    void nativeCollectionRoundTripPreservesExactTransportRequestState() throws Exception {
        ApiCollection collection = new ApiCollection();
        collection.id = "col-exact";
        collection.name = "Exact";
        collection.requests.add(exactNativeRequest());
        Path output = tempDir.resolve("exact.api-workbench.collection.json");

        service.exportCollection(
                collection,
                new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON, output, false, null, Map.of())
        );

        ApiCollection imported = new ApiWorkbenchCollectionParser().parse(output.toFile());
        ApiRequest restored = imported.requests.get(0);

        assertThat(restored.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(restored.editorMaterialized).isTrue();
        assertThat(restored.description).isEqualTo("exact description");
        assertThat(restored.disabled).isTrue();
        assertThat(restored.variables).hasSize(1);
        assertThat(restored.headers).extracting(header -> header.key)
                .containsExactly("Host", "Host", "Cookie", "Cookie", "Content-Length", "Transfer-Encoding", "Connection");
        assertThat(restored.headers.get(6).disabled).isTrue();
        assertThat(restored.body.contentType).isEqualTo("text/plain");
        assertThat(restored.body.formdata.get(0).fileUpload).isTrue();
        assertThat(restored.body.formdata.get(0).filePath).isEqualTo("payload.bin");
    }

    @Test
    void legacyNativeCollectionWithoutIdStillImportsWithGeneratedId() throws Exception {
        ApiCollection collection = ExportTestFixtures.sampleCollection();
        collection.id = null;
        JsonObject root = ApiWorkbenchCollectionExporter.build(collection, new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON, tempDir.resolve("legacy.json"), false, null, Map.of()), new java.util.ArrayList<>());
        JsonObject exported = root.getAsJsonObject("collection");
        exported.remove("id");
        Path output = tempDir.resolve("legacy.api-workbench.collection.json");
        Files.writeString(output, new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(root));

        ApiCollection imported = new ApiWorkbenchCollectionParser().parse(output.toFile());
        assertThat(imported.id).isNotBlank();
    }

    @Test
    void resolvesVariablesUsingActiveEnvironmentAndExportOnlyOverlay() throws Exception {
        ApiCollection collection = ExportTestFixtures.sampleCollectionWithMissingVariable();
        EnvironmentProfile activeEnvironment = ExportTestFixtures.activeEnvironmentWithoutMissingPassword();
        Path output = tempDir.resolve("apim.postman_collection.json");

        ExportResult result = service.exportCollection(
                collection,
                new CollectionExportOptions(
                        CollectionExportFormat.POSTMAN_JSON,
                        output,
                        true,
                        activeEnvironment,
                        Map.of("missing_password", "quick-entry-password")
                )
        );

        assertThat(result.unresolvedVariableCount).isEqualTo(0);
        assertThat(collection.requests.get(0).url).contains("{{base_url}}");
        assertThat(collection.requests.get(0).body.raw).contains("{{missing_password}}");
        JsonObject root = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        JsonObject login = postmanRequestByName(root.getAsJsonArray("item"), "Auth");
        assertThat(login.getAsJsonObject("request").getAsJsonPrimitive("url").getAsString())
                .isEqualTo("https://api.example.test/login");
        assertThat(login.getAsJsonObject("request").getAsJsonObject("body").getAsJsonPrimitive("raw").getAsString())
                .contains("quick-entry-password");
    }

    @Test
    void collectionExportResolutionExcludesRuntimeVarsAndRuntimeOauth2() throws Exception {
        ApiCollection collection = runtimeSensitiveCollection();
        EnvironmentProfile activeEnvironment = ExportTestFixtures.activeEnvironmentWithoutMissingPassword();
        Path output = tempDir.resolve("runtime.postman_collection.json");

        ExportResult result = service.exportCollection(
                collection,
                new CollectionExportOptions(
                        CollectionExportFormat.POSTMAN_JSON,
                        output,
                        true,
                        activeEnvironment,
                        Map.of("missing_password", "export-only-password")
                )
        );

        assertThat(result.unresolvedVariableCount).isEqualTo(2);
        String exported = Files.readString(output);
        assertThat(exported).contains("https://api.example.test/");
        assertThat(exported).contains("export-only-password");
        assertThat(exported).contains("{{runtime_only}}");
        assertThat(exported).contains("{{accessToken}}");
        assertThat(collection.requests.get(0).url).isEqualTo("{{base_url}}/{{runtime_only}}/{{accessToken}}");
        assertThat(collection.requests.get(0).body.raw).isEqualTo("{\"password\":\"{{missing_password}}\"}");
        assertThat(activeEnvironment.variables).doesNotContainEntry("missing_password", "export-only-password");
        assertThat(collection.runtimeVars).containsEntry("runtime_only", "should-not-export");
        assertThat(collection.runtimeOAuth2).containsEntry("accessToken", "runtime-token");
    }

    @Test
    void unresolvedExportIssuesAreDetectedWithoutExportOnlyOverlay() {
        ApiCollection collection = ExportTestFixtures.sampleCollectionWithMissingVariable();
        EnvironmentProfile activeEnvironment = ExportTestFixtures.activeEnvironmentWithoutMissingPassword();

        List<UnresolvedVariableIssue> issues = ExportVariableResolutionService.collectUnresolvedIssues(collection, activeEnvironment);

        assertThat(issues).isNotEmpty();
        assertThat(issues).anySatisfy(issue -> assertThat(issue.variableName).isEqualTo("missing_password"));
    }

    @Test
    void scriptUnresolvedScanUsesActiveAndExportOverlay() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Scripts";
        ApiRequest request = new ApiRequest();
        request.id = "req-script";
        request.name = "Scripted";
        request.method = "GET";
        request.url = "https://api.example.test";
        request.preRequestScripts = new java.util.ArrayList<>();
        request.preRequestScripts.add(new ApiRequest.Script("js", "pm.environment.get(\"{{token}}\");"));
        request.postResponseScripts = new java.util.ArrayList<>();
        request.postResponseScripts.add(new ApiRequest.Script("js", "console.log(\"{{token}}\");"));
        collection.requests = new java.util.ArrayList<>(List.of(request));

        List<UnresolvedVariableIssue> unresolvedWithoutOverlay = ExportVariableResolutionService.collectUnresolvedIssues(collection, null);
        assertThat(unresolvedWithoutOverlay).anySatisfy(issue -> {
            assertThat(issue.variableName).isEqualTo("token");
            assertThat(issue.location).startsWith("script:");
        });

        EnvironmentProfile activeEnvironment = new EnvironmentProfile();
        activeEnvironment.name = "UAT";
        activeEnvironment.variables.put("token", "active-token");
        assertThat(ExportVariableResolutionService.collectUnresolvedIssues(collection, activeEnvironment)).isEmpty();
        assertThat(ExportVariableResolutionService.collectUnresolvedIssues(collection, null, Map.of("token", "export-token"))).isEmpty();
    }

    @Test
    void crossDialectScriptExportWarnsAndPreservesText() throws Exception {
        ApiCollection collection = scriptedCollection(ScriptDialect.INSOMNIA, "insomnia.environment.set('token', 'abc');");
        Path output = tempDir.resolve("insomnia-to-postman.postman_collection.json");

        ExportResult result = service.exportCollection(collection,
                new CollectionExportOptions(CollectionExportFormat.POSTMAN_JSON, output, false, null, Map.of()));

        assertThat(result.warnings).contains(CollectionExportSupport.CROSS_FORMAT_SCRIPT_WARNING);
        String exported = Files.readString(output);
        assertThat(exported).contains("insomnia.environment.set('token', 'abc');");
        assertThat(exported).doesNotContain("pm.environment.set('token', 'abc');");
    }

    @Test
    void brunoScriptExportWarnsAndSameDialectPostmanDoesNot() throws Exception {
        ExportResult bruno = service.exportCollection(
                scriptedCollection(ScriptDialect.BRUNO, "bru.setVar('token', 'abc');"),
                new CollectionExportOptions(CollectionExportFormat.POSTMAN_JSON, tempDir.resolve("bruno.postman_collection.json"), false, null, Map.of()));
        ExportResult postman = service.exportCollection(
                scriptedCollection(ScriptDialect.POSTMAN, "pm.environment.set('token', 'abc');"),
                new CollectionExportOptions(CollectionExportFormat.POSTMAN_JSON, tempDir.resolve("postman.postman_collection.json"), false, null, Map.of()));

        assertThat(bruno.warnings).contains(CollectionExportSupport.CROSS_FORMAT_SCRIPT_WARNING);
        assertThat(Files.readString(tempDir.resolve("bruno.postman_collection.json"))).contains("bru.setVar('token', 'abc');");
        assertThat(postman.warnings).doesNotContain(CollectionExportSupport.CROSS_FORMAT_SCRIPT_WARNING);
    }

    @Test
    void unresolvedCountsMatchBrunoAndInsomniaEmittedScopes() throws Exception {
        ApiCollection collection = new ApiCollection(); collection.name = "Exact diagnostics";
        collection.folderPaths.add("F");
        collection.scriptBlocks.add(ExportTestFixtures.scriptBlock("collection", ScriptDialect.BRUNO,
                ScriptPhase.PRE_REQUEST, ScriptScope.COLLECTION, "{{collection_only}}", 0));
        collection.folderScriptBlocks.put("F", new java.util.ArrayList<>(List.of(
                ExportTestFixtures.scriptBlock("folder", ScriptDialect.INSOMNIA,
                        ScriptPhase.PRE_REQUEST, ScriptScope.FOLDER, "{{folder_script}}", 0))));
        ApiRequest inherited = diagnosticRequest("Inherited", "F/Inherited", "{{request_script}}");
        inherited.auth = new ApiRequest.Auth(); inherited.auth.type = "bearer";
        inherited.auth.properties.put("token", "{{inherited_auth}}");
        AuthInheritanceResolver.markRequestInherit(inherited);
        ApiRequest explicit = diagnosticRequest("Explicit", "F/Explicit", null);
        ApiRequest.Auth bearer = new ApiRequest.Auth(); bearer.type = "bearer";
        bearer.properties.put("token", "{{auth_token}}");
        bearer.properties.put("unsupported", "{{omitted_property}}");
        AuthInheritanceResolver.markRequestExplicitAuth(explicit, bearer);
        ApiRequest none = diagnosticRequest("None", "F/None", null);
        AuthInheritanceResolver.markRequestNoAuth(none);
        none.explicitAuth.properties.put("token", "{{stale_none_auth}}");
        collection.requests.addAll(List.of(inherited, explicit, none));

        ExportResult insomnia = service.exportCollection(collection, new CollectionExportOptions(
                CollectionExportFormat.INSOMNIA_JSON, tempDir.resolve("diagnostics.insomnia.json"),
                true, null, Map.of()));
        ExportResult bruno = service.exportCollection(collection, new CollectionExportOptions(
                CollectionExportFormat.BRUNO_ZIP, tempDir.resolve("diagnostics.bruno.zip"),
                true, null, Map.of()));
        assertThat(insomnia.unresolvedVariableCount).isEqualTo(4);
        assertThat(bruno.unresolvedVariableCount).isEqualTo(4);
    }

    private static ApiRequest diagnosticRequest(String name, String path, String script) {
        ApiRequest request = new ApiRequest(); request.name = name; request.path = path;
        request.method = "GET"; request.url = "https://e.test";
        request.body = new ApiRequest.Body(); request.body.mode = "none";
        if (script != null) request.scriptBlocks.add(ExportTestFixtures.scriptBlock(name,
                ScriptDialect.INSOMNIA, ScriptPhase.PRE_REQUEST, ScriptScope.REQUEST, script, 0));
        return request;
    }

    private static ApiCollection runtimeSensitiveCollection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Runtime";
        collection.runtimeVars.put("runtime_only", "should-not-export");
        collection.runtimeOAuth2.put("accessToken", "runtime-token");

        ApiRequest request = new ApiRequest();
        request.id = "req-runtime";
        request.name = "Runtime";
        request.path = "";
        request.sourceCollection = "Runtime";
        request.method = "POST";
        request.url = "{{base_url}}/{{runtime_only}}/{{accessToken}}";
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"password\":\"{{missing_password}}\"}";
        collection.requests = new java.util.ArrayList<>(List.of(request));
        return collection;
    }

    private static ApiCollection scriptedCollection(ScriptDialect dialect, String source) {
        ApiCollection collection = new ApiCollection();
        collection.name = "Scripts";
        ApiRequest request = new ApiRequest();
        request.id = "req-scripted";
        request.name = "Scripted";
        request.method = "GET";
        request.url = "https://api.example.test/scripted";
        request.preRequestScripts.add(new ApiRequest.Script("js", source));
        request.scriptBlocks.add(ExportTestFixtures.scriptBlock("script", dialect, ScriptPhase.PRE_REQUEST, ScriptScope.REQUEST, source, 0));
        collection.requests = new java.util.ArrayList<>(List.of(request));
        return collection;
    }

    private static ApiRequest exactNativeRequest() {
        ApiRequest request = new ApiRequest();
        request.id = "req-exact";
        request.name = "Exact";
        request.path = "Folder/Exact";
        request.sourceCollection = "Exact";
        request.method = "POST";
        request.url = "https://api.example.test/exact";
        request.description = "exact description";
        request.disabled = true;
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.editorMaterialized = true;
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = "tenant";
        variable.value = "acme";
        request.variables.add(variable);
        request.headers.add(new ApiRequest.Header("Host", "one.example", false));
        request.headers.add(new ApiRequest.Header("Host", "two.example", false));
        request.headers.add(new ApiRequest.Header("Cookie", "a=1", false));
        request.headers.add(new ApiRequest.Header("Cookie", "b=2", false));
        request.headers.add(new ApiRequest.Header("Content-Length", "999", false));
        request.headers.add(new ApiRequest.Header("Transfer-Encoding", "chunked", false));
        request.headers.add(new ApiRequest.Header("Connection", "keep-alive", true));
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "hello";
        request.body.contentType = "text/plain";
        ApiRequest.Body.FormField upload = new ApiRequest.Body.FormField("upload", "");
        upload.type = "file";
        upload.fileUpload = true;
        upload.filePath = "payload.bin";
        request.body.formdata.add(upload);
        return request;
    }

    private static JsonObject requestByName(JsonArray requests, String name) {
        for (int i = 0; i < requests.size(); i++) {
            JsonObject request = requests.get(i).getAsJsonObject();
            if (name.equals(request.get("name").getAsString())) {
                return request;
            }
        }
        throw new AssertionError("Request not found: " + name);
    }

    private static JsonObject postmanRequestByName(JsonArray items, String name) {
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            if (item.has("request") && name.equals(item.get("name").getAsString())) {
                return item;
            }
            if (item.has("item")) {
                try {
                    return postmanRequestByName(item.getAsJsonArray("item"), name);
                } catch (AssertionError ignored) {
                    // continue search
                }
            }
        }
        throw new AssertionError("Postman item not found: " + name);
    }

    private static ApiRequest requestById(List<ApiRequest> requests, String id) {
        for (ApiRequest request : requests) {
            if (request != null && id.equals(request.id)) {
                return request;
            }
        }
        throw new AssertionError("Request not found: " + id);
    }

    private static List<String> jsonArrayStrings(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            values.add(array.get(i).getAsString());
        }
        return values;
    }
}
