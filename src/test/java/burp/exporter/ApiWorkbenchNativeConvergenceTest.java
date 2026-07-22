package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.parser.ApiWorkbenchCollectionParser;
import burp.parser.BrunoParser;
import burp.parser.HarParser;
import burp.parser.InsomniaParser;
import burp.parser.OpenApiParser;
import burp.parser.PostmanParser;
import burp.parser.VariableResolver;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.utils.AuthInheritanceResolver;
import burp.utils.RequestBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiWorkbenchNativeConvergenceTest {
    @TempDir
    Path tempDir;

    @Test
    void minimalModelDefaultsConvergeOnFirstNativeExport() throws Exception {
        ApiCollection collection = new ApiCollection();
        collection.id = null;
        collection.name = null;
        collection.format = null;
        ApiRequest request = new ApiRequest();
        request.id = null;
        request.name = null;
        request.sourceCollection = null;
        request.method = null;
        request.url = null;
        request.buildMode = null;
        request.authOverrideMode = null;
        collection.requests.add(request);

        ConvergenceResult result = assertConverges("minimal defaults", collection);
        JsonObject firstCollection = result.firstJson().getAsJsonObject("collection");
        JsonObject firstRequest = firstCollection.getAsJsonArray("requests").get(0).getAsJsonObject();
        assertThat(firstCollection.get("id").getAsString()).isNotBlank().isEqualTo(collection.id);
        assertThat(firstCollection.get("name").getAsString()).isEqualTo("Untitled Collection");
        assertThat(firstCollection.get("format").getAsString()).isEqualTo("api-workbench");
        assertThat(firstRequest.get("name").getAsString()).isEqualTo("Unnamed Request");
        assertThat(firstRequest.get("sourceCollection").getAsString()).isEqualTo("Untitled Collection");
        assertThat(firstRequest.get("method").getAsString()).isEqualTo("GET");
        assertThat(firstRequest.get("buildMode").getAsString()).isEqualTo("AUTO_COMPATIBLE");
        assertThat(firstRequest.get("authOverrideMode").getAsString()).isEqualTo("inherit");
        assertThat(firstRequest.get("authSource").getAsString()).isEqualTo("none");
    }

    @Test
    void optionalNativeStringsPreserveAbsentEmptyAndNonemptyStates() throws Exception {
        for (String value : Arrays.asList(null, "", "retained")) {
            ApiCollection collection = baseCollection("optional-" + String.valueOf(value));
            collection.description = value;
            collection.version = value;
            collection.requests.get(0).description = value;
            ConvergenceResult result = assertConverges("optional strings " + value, collection);
            JsonObject col = result.firstJson().getAsJsonObject("collection");
            JsonObject req = col.getAsJsonArray("requests").get(0).getAsJsonObject();
            if (value == null) {
                assertThat(col.has("description")).isFalse();
                assertThat(col.has("version")).isFalse();
                assertThat(req.has("description")).isFalse();
            } else {
                assertThat(col.get("description").getAsString()).isEqualTo(value);
                assertThat(col.get("version").getAsString()).isEqualTo(value);
                assertThat(req.get("description").getAsString()).isEqualTo(value);
            }
        }

        for (int schema : List.of(1, 2)) {
            JsonObject root = minimalNativeJson(schema);
            ApiCollection absent = reload(root, "tri-state-absent-" + schema + ".json");
            assertThat(absent.description).isNull();
            assertThat(absent.version).isNull();
            assertThat(absent.requests.get(0).description).isNull();
            assertConverges("schema " + schema + " absent strings", absent);

            JsonObject col = root.getAsJsonObject("collection");
            col.addProperty("description", "");
            col.addProperty("version", "");
            col.getAsJsonArray("requests").get(0).getAsJsonObject().addProperty("description", "");
            ApiCollection empty = reload(root, "tri-state-empty-" + schema + ".json");
            assertThat(empty.description).isEmpty();
            assertThat(empty.version).isEmpty();
            assertThat(empty.requests.get(0).description).isEmpty();
            assertConverges("schema " + schema + " empty strings", empty);
        }
    }

    @Test
    void allAuthInheritanceAndOverrideStatesConverge() throws Exception {
        List<AuthCase> cases = new ArrayList<>();
        cases.add(new AuthCase("none", authCollection(null, null, null, null), null));
        cases.add(new AuthCase("collection bearer", authCollection(auth("bearer", "token", "wave6-token"), null, null, null), "Authorization: Bearer wave6-token"));
        cases.add(new AuthCase("collection basic nested", authCollection(auth("basic", "username", "wave6", "password", "secret-placeholder"), "Root/Nested", null, null), "Authorization: Basic "));
        cases.add(new AuthCase("folder bearer", authCollection(null, "Root/Nested", "explicit", auth("bearer", "token", "folder-token")), "Authorization: Bearer folder-token"));

        ApiCollection nested = authCollection(auth("bearer", "token", "collection-token"), "Root/Nested", null, null);
        nested.folderAuthModes.put("Root", "explicit");
        nested.folderAuth.put("Root", auth("bearer", "token", "parent-token"));
        nested.folderAuthModes.put("Root/Nested", "explicit");
        nested.folderAuth.put("Root/Nested", auth("bearer", "token", "nested-token"));
        cases.add(new AuthCase("nested override", nested, "Authorization: Bearer nested-token"));

        ApiCollection folderInherit = authCollection(auth("bearer", "token", "collection-token"), "Root/Nested", "inherit", auth("bearer", "token", "ignored"));
        cases.add(new AuthCase("folder inherit", folderInherit, "Authorization: Bearer collection-token"));
        ApiCollection folderNone = authCollection(auth("bearer", "token", "collection-token"), "Root/Nested", "none", null);
        cases.add(new AuthCase("folder none", folderNone, null));

        ApiCollection requestBearer = authCollection(auth("bearer", "token", "collection-token"), null, null, null);
        AuthInheritanceResolver.markRequestExplicitAuth(requestBearer.requests.get(0), auth("bearer", "token", "request-token"));
        cases.add(new AuthCase("request bearer", requestBearer, "Authorization: Bearer request-token"));

        ApiCollection requestKey = authCollection(null, null, null, null);
        AuthInheritanceResolver.markRequestExplicitAuth(requestKey.requests.get(0), auth("apikey", "key", "X-API-Key", "value", "key-placeholder", "in", "header"));
        cases.add(new AuthCase("request api key", requestKey, "X-API-Key: key-placeholder"));

        ApiCollection requestNone = authCollection(auth("bearer", "token", "collection-token"), null, null, null);
        AuthInheritanceResolver.markRequestNoAuth(requestNone.requests.get(0));
        cases.add(new AuthCase("request no auth", requestNone, null));

        ApiCollection legacyAuth = authCollection(null, null, null, null);
        ApiRequest legacyAuthRequest = legacyAuth.requests.get(0);
        legacyAuthRequest.auth = auth("bearer", "token", "legacy-token");
        legacyAuthRequest.explicitAuth = null;
        legacyAuthRequest.authOverrideMode = null;
        cases.add(new AuthCase("legacy auth", legacyAuth, "Authorization: Bearer legacy-token"));

        ApiCollection legacyInherited = authCollection(auth("bearer", "token", "inherited-token"), null, null, null);
        legacyInherited.requests.get(0).authOverrideMode = null;
        legacyInherited.requests.get(0).authInherited = true;
        cases.add(new AuthCase("legacy inherited", legacyInherited, "Authorization: Bearer inherited-token"));

        ApiCollection legacyDisabled = authCollection(auth("bearer", "token", "inherited-token"), null, null, null);
        legacyDisabled.requests.get(0).authOverrideMode = null;
        legacyDisabled.requests.get(0).authExplicitlyDisabled = true;
        cases.add(new AuthCase("legacy disabled", legacyDisabled, null));

        ApiCollection explicitWithoutAuth = authCollection(null, null, null, null);
        explicitWithoutAuth.requests.get(0).authOverrideMode = "explicit";
        explicitWithoutAuth.requests.get(0).auth = null;
        explicitWithoutAuth.requests.get(0).explicitAuth = null;
        cases.add(new AuthCase("empty explicit", explicitWithoutAuth, null));

        ApiCollection nullModeExplicit = authCollection(null, null, null, null);
        nullModeExplicit.requests.get(0).authOverrideMode = null;
        nullModeExplicit.requests.get(0).explicitAuth = auth("bearer", "token", "null-mode-token");
        cases.add(new AuthCase("null mode explicit", nullModeExplicit, "Authorization: Bearer null-mode-token"));

        ApiCollection explicitNone = authCollection(auth("bearer", "token", "collection-token"), null, null, null);
        explicitNone.requests.get(0).authOverrideMode = "explicit";
        explicitNone.requests.get(0).explicitAuth = auth("none");
        cases.add(new AuthCase("explicit none auth", explicitNone, null));

        ApiCollection retainedSource = authCollection(null, null, null, null);
        retainedSource.requests.get(0).authSource = "retained-source-label";
        cases.add(new AuthCase("retained auth source", retainedSource, null));

        for (AuthCase authCase : cases) {
            AuthInheritanceResolver.recomputeCollectionAuth(authCase.collection());
            if ("retained auth source".equals(authCase.label())) {
                authCase.collection().requests.get(0).authSource = "retained-source-label";
            }
            byte[] before = build(authCase.collection(), authCase.collection().requests.get(0));
            ConvergenceResult result = assertConverges(authCase.label(), authCase.collection());
            byte[] afterFirst = build(result.firstReload(), result.firstReload().requests.get(0));
            byte[] afterSecond = build(result.secondReload(), result.secondReload().requests.get(0));
            assertThat(afterFirst).as(authCase.label()).isEqualTo(before);
            assertThat(afterSecond).as(authCase.label()).isEqualTo(before);
            String raw = new String(afterSecond, StandardCharsets.UTF_8);
            if (authCase.expectedHeader() == null) {
                assertThat(raw).doesNotContain("Authorization:", "X-API-Key:");
            } else {
                assertThat(raw).contains(authCase.expectedHeader());
            }
            JsonObject requestJson = result.firstJson().getAsJsonObject("collection")
                    .getAsJsonArray("requests").get(0).getAsJsonObject();
            assertThat(requestJson.get("authSource").getAsString()).isNotBlank();
            if ("retained auth source".equals(authCase.label())) {
                assertThat(requestJson.get("authSource").getAsString()).isEqualTo("retained-source-label");
            }
        }
    }

    @Test
    void folderPathsAndFolderScopedMapsCanonicalizeOnce() throws Exception {
        ApiCollection collection = baseCollection("paths");
        collection.folderPaths.addAll(List.of("/Root//Nested", "Root\\Nested", "Root/Nested"));
        collection.folderAuthModes.put("/Root//Nested", "explicit");
        collection.folderAuthModes.put("Root\\Nested", "none");
        collection.folderAuth.put("/Root//Nested", auth("bearer", "token", "first"));
        collection.folderAuth.put("Root\\Nested", auth("none"));
        collection.folderVars.put("/Root//Nested", new LinkedHashMap<>(Map.of("one", "1")));
        collection.folderVars.put("Root\\Nested", new LinkedHashMap<>(Map.of("two", "2")));
        collection.folderScriptBlocks.put("/Root//Nested", List.of(block("one", ScriptScope.FOLDER, 1)));
        collection.folderScriptBlocks.put("Root\\Nested", List.of(block("two", ScriptScope.FOLDER, 2)));
        collection.requests.get(0).path = "/Root//Nested";

        ConvergenceResult result = assertConverges("folder paths", collection);
        JsonObject col = result.firstJson().getAsJsonObject("collection");
        assertThat(col.getAsJsonArray("folderPaths")).extracting(JsonElement::getAsString)
                .containsExactly("Root/Nested", "Root/Nested", "Root/Nested");
        assertThat(col.getAsJsonObject("folderAuthModes").keySet()).containsExactly("Root/Nested");
        assertThat(col.getAsJsonObject("folderVars").getAsJsonObject("Root/Nested").keySet()).containsExactly("two");
        assertThat(col.getAsJsonObject("folderScriptBlocks").getAsJsonArray("Root/Nested").get(0)
                .getAsJsonObject().get("source").getAsString()).isEqualTo("two");
        assertThat(col.getAsJsonArray("requests").get(0).getAsJsonObject().get("path").getAsString())
                .isEqualTo("Root/Nested");
    }

    @Test
    void requestOperationalDefaultsAndBuildModesConverge() throws Exception {
        List<ApiRequest> requests = new ArrayList<>();
        requests.add(request(null, null, null, null, null, false));
        requests.add(request("", "", "", "", null, true));
        requests.add(request("auto", "Root", "Source", "GET", ApiRequest.BuildMode.AUTO_COMPATIBLE, false));
        requests.add(request("manual", "Root/Nested", "Source", "POST", ApiRequest.BuildMode.MANUAL_PRESERVE, true));
        requests.add(request("exact", "Root/Nested", "Source", "GET", ApiRequest.BuildMode.EXACT_HTTP, true));
        requests.get(1).id = "";
        requests.get(0).disabled = true;
        requests.get(1).disabled = true;
        requests.get(2).suppressedAutoHeaders.add(" Content-Type ");
        for (int i = 0; i < requests.size(); i++) {
            requests.get(i).sequenceOrder = i + 10;
        }
        ApiCollection collection = new ApiCollection();
        collection.id = "request-matrix";
        collection.name = "Request Matrix";
        collection.format = "postman";
        collection.requests = requests;

        ConvergenceResult result = assertConverges("request defaults", collection);
        JsonArray jsonRequests = result.firstJson().getAsJsonObject("collection").getAsJsonArray("requests");
        assertThat(jsonRequests.get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("Unnamed Request");
        assertThat(jsonRequests.get(0).getAsJsonObject().get("sourceCollection").getAsString()).isEqualTo("Request Matrix");
        assertThat(jsonRequests.get(0).getAsJsonObject().get("method").getAsString()).isEqualTo("GET");
        assertThat(jsonRequests.get(0).getAsJsonObject().get("buildMode").getAsString()).isEqualTo("AUTO_COMPATIBLE");
        assertThat(jsonRequests.get(1).getAsJsonObject().get("buildMode").getAsString()).isEqualTo("MANUAL_PRESERVE");
        assertThat(jsonRequests.get(4).getAsJsonObject().get("buildMode").getAsString()).isEqualTo("EXACT_HTTP");
        assertThat(jsonRequests.get(0).getAsJsonObject().get("disabled").getAsBoolean()).isTrue();
        assertThat(jsonRequests.get(4).getAsJsonObject().get("sequenceOrder").getAsInt()).isEqualTo(14);
        assertThat(jsonRequests.get(2).getAsJsonObject().getAsJsonArray("suppressedAutoHeaders"))
                .extracting(JsonElement::getAsString).containsExactly("Content-Type");
    }

    @Test
    void parameterBodyAndFileMetadataStatesConverge() throws Exception {
        ApiCollection collection = baseCollection("parameters-body");
        ApiRequest request = collection.requests.get(0);
        ApiRequest.Parameter encoded = parameter("query", "tag", "one", true);
        encoded.rawKey = "tag";
        encoded.rawValue = "%6Fne";
        encoded.required = true;
        encoded.type = "string";
        encoded.format = "custom";
        encoded.description = "encoded";
        encoded.style = "form";
        encoded.explode = Boolean.TRUE;
        encoded.allowReserved = true;
        encoded.source = "fixture";
        encoded.sourceMetadata.put("retained", "yes");
        request.parameters.add(encoded);
        request.parameters.add(parameter("query", "tag", "two", true));
        request.parameters.add(parameter(null, "flag", "retained", false));
        request.parameters.add(parameter("query", "empty", "", true));
        request.parameters.add(parameter("path", "id", "42", true));
        request.parameters.add(parameter("header", "X-Wave", "header", true));
        request.parameters.add(parameter("cookie", "session", "cookie", true));
        request.parameters.get(1).disabled = true;
        request.headers.add(new ApiRequest.Header("X-Dupe", "one"));
        request.headers.add(new ApiRequest.Header("X-Dupe", "two"));
        request.headers.add(new ApiRequest.Header("X-Empty", "", true));

        ApiRequest.Body body = new ApiRequest.Body();
        body.mode = "urlencoded";
        body.raw = null;
        body.required = true;
        body.description = "body";
        body.source = "fixture";
        body.sourceMetadata.put("retained", "yes");
        ApiRequest.Body.FormField url = field("item", "one", "text", false, null);
        url.explode = Boolean.FALSE;
        body.urlencoded.add(url);
        ApiRequest.Body.FormField file = field("upload", "retained", "file", false, "fixtures/not-read.bin");
        file.disabled = true;
        file.required = true;
        file.description = "file";
        file.contentType = "application/octet-stream";
        file.style = "form";
        file.allowReserved = true;
        file.source = "fixture";
        file.sourceMetadata.put("name", "not-read.bin");
        body.formdata.add(file);
        body.graphql = new ApiRequest.Body.GraphQL();
        body.graphql.query = "query { wave6 }";
        body.graphql.variables = "{}";
        request.body = body;

        ConvergenceResult result = assertConverges("parameters and body", collection);
        ApiRequest reloaded = result.firstReload().requests.get(0);
        assertThat(reloaded.parameters).extracting(p -> p.location)
                .containsExactly("query", "query", "query", "query", "path", "header", "cookie");
        assertThat(reloaded.parameters).extracting(p -> p.key)
                .containsExactly("tag", "tag", "flag", "empty", "id", "X-Wave", "session");
        assertThat(reloaded.parameters.get(2).valuePresent).isFalse();
        assertThat(reloaded.parameters.get(3).valuePresent).isTrue();
        assertThat(reloaded.parameters.get(0).rawValue).isEqualTo("%6Fne");
        assertThat(reloaded.body.mode).isEqualTo("urlencoded");
        assertThat(reloaded.body.urlencoded).hasSize(1);
        assertThat(reloaded.body.formdata).hasSize(1);
        assertThat(reloaded.body.formdata.get(0).fileUpload).isTrue();
        assertThat(reloaded.body.formdata.get(0).filePath).isEqualTo("fixtures/not-read.bin");
        assertThat(reloaded.body.graphql.query).isEqualTo("query { wave6 }");

        assertMissingBodyModeRemainsInactive("default body", new ApiRequest.Body(), null);

        ApiRequest.Body blankMode = new ApiRequest.Body();
        blankMode.mode = " ";
        assertMissingBodyModeRemainsInactive("blank body mode", blankMode, null);

        ApiRequest.Body latentRaw = new ApiRequest.Body();
        latentRaw.raw = "latent-raw-content";
        assertMissingBodyModeRemainsInactive("latent raw body", latentRaw, "latent-raw-content");

        ApiRequest.Body latentUrlEncoded = new ApiRequest.Body();
        latentUrlEncoded.urlencoded.add(field("latent-urlencoded", "retained", "text", false, null));
        assertMissingBodyModeRemainsInactive("latent urlencoded body", latentUrlEncoded, "latent-urlencoded");

        ApiRequest.Body latentMultipart = new ApiRequest.Body();
        latentMultipart.formdata.add(field("latent-multipart", "retained", "text", false, null));
        assertMissingBodyModeRemainsInactive("latent multipart body", latentMultipart, "latent-multipart");

        ApiRequest.Body latentGraphql = new ApiRequest.Body();
        latentGraphql.graphql = new ApiRequest.Body.GraphQL();
        latentGraphql.graphql.query = "query { latentGraphql }";
        assertMissingBodyModeRemainsInactive("latent graphql body", latentGraphql, "latentGraphql");
    }

    @Test
    void legacyNativeBodiesWithoutModeStillInferTheirShape() throws Exception {
        assertLegacyBodyMode("raw", bodyWithRaw(), "legacy-raw-content");
        assertLegacyBodyMode("urlencoded", bodyWithField("urlencoded", "legacy-urlencoded"), "legacy-urlencoded");
        assertLegacyBodyMode("formdata", bodyWithField("formdata", "legacy-multipart"), "legacy-multipart");
        assertLegacyBodyMode("graphql", bodyWithGraphql(), "legacyGraphql");
    }

    @Test
    void variablesLegacyScriptsAndScriptBlocksConverge() throws Exception {
        ApiCollection collection = baseCollection("scripts");
        ApiRequest.Variable enabled = variable("enabled", "one", null, true);
        ApiRequest.Variable disabled = variable("disabled", "two", "secret", false);
        collection.variables.addAll(List.of(enabled, disabled));
        collection.environment.put("baseUrl", "https://example.test");
        collection.scriptBlocks.add(block("collection", ScriptScope.COLLECTION, 0));
        collection.folderScriptBlocks.put("/Root//Nested", List.of(block("folder", ScriptScope.FOLDER, 1)));
        ApiRequest request = collection.requests.get(0);
        request.path = "Root/Nested";
        request.variables.add(variable("request", "three", "string", true));
        request.preRequestScripts.add(new ApiRequest.Script(null, "pre();"));
        request.postResponseScripts.add(new ApiRequest.Script("js", "post();"));
        request.scriptBlocks.add(block("request", ScriptScope.REQUEST, 2));

        ConvergenceResult result = assertConverges("scripts and variables", collection);
        JsonObject requestJson = result.firstJson().getAsJsonObject("collection")
                .getAsJsonArray("requests").get(0).getAsJsonObject();
        assertThat(requestJson.getAsJsonArray("preRequestScripts").get(0).getAsJsonObject().get("type").getAsString())
                .isEqualTo("js");
        assertThat(result.firstReload().variables).extracting(v -> v.enabled).containsExactly(true, false);
        assertThat(result.firstReload().scriptBlocks).extracting(b -> b.source).containsExactly("collection");
        assertThat(result.firstReload().folderScriptBlocks.get("Root/Nested")).extracting(b -> b.source).containsExactly("folder");
        assertThat(result.firstReload().requests.get(0).scriptBlocks).extracting(b -> b.source).containsExactly("request");
    }

    @Test
    void exactSnapshotStatesAndLegacyMigrationConverge() throws Exception {
        ApiCollection none = baseCollection("no-exact");
        assertConverges("no exact", none);

        for (String version : List.of("HTTP/1.0", "HTTP/1.1")) {
            ApiCollection collection = baseCollection("exact-" + version);
            ApiRequest request = collection.requests.get(0);
            request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
            request.exactHttpRequest = snapshot(version, request);
            if ("HTTP/1.1".equals(version)) {
                request.exactHttpRequest.sourceContext = "fixture-context";
                request.exactHttpRequest.invalidationReason = "retained-reason";
            }
            ConvergenceResult result = assertConverges("exact " + version, collection);
            assertThat(result.firstReload().requests.get(0).exactHttpRequest.rawRequestBytes)
                    .isEqualTo(request.exactHttpRequest.rawRequestBytes);
            if ("HTTP/1.1".equals(version)) {
                assertThat(result.firstReload().requests.get(0).exactHttpRequest.sourceContext)
                        .isEqualTo("fixture-context");
                assertThat(result.firstReload().requests.get(0).exactHttpRequest.invalidationReason)
                        .isEqualTo("retained-reason");
            }
        }

        ApiCollection legacy = baseCollection("legacy-fingerprint");
        ApiRequest legacyRequest = legacy.requests.get(0);
        legacyRequest.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        legacyRequest.exactHttpRequest = snapshot(null, legacyRequest);
        legacyRequest.exactHttpRequest.semanticFingerprint = legacyRequest.computeLegacySemanticFingerprintV1();
        ConvergenceResult migrated = assertConverges("legacy exact fingerprint", legacy);
        assertThat(migrated.firstReload().requests.get(0).exactHttpRequest.httpVersion).isEqualTo("HTTP/1.0");

        JsonObject legacySchema = export(legacy);
        legacySchema.addProperty("schemaVersion", 1);
        JsonObject legacyRequestJson = legacySchema.getAsJsonObject("collection")
                .getAsJsonArray("requests").get(0).getAsJsonObject();
        legacyRequestJson.getAsJsonObject("exactHttpRequest").remove("httpVersion");
        legacyRequestJson.getAsJsonObject("exactHttpRequest").addProperty(
                "semanticFingerprint",
                legacyRequest.computeLegacySemanticFingerprintV1());
        ApiCollection importedLegacySchema = reload(legacySchema, "legacy-exact-schema1.json");
        ConvergenceResult migratedSchema = assertConverges("schema 1 legacy exact fingerprint", importedLegacySchema);
        assertThat(migratedSchema.firstReload().requests.get(0).exactHttpRequest.httpVersion)
                .isEqualTo("HTTP/1.0");

        ApiCollection invalid = baseCollection("invalid-fingerprint");
        ApiRequest invalidRequest = invalid.requests.get(0);
        invalidRequest.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        invalidRequest.exactHttpRequest = snapshot("HTTP/1.1", invalidRequest);
        invalidRequest.exactHttpRequest.semanticFingerprint = "invalid-fingerprint";
        ConvergenceResult unchanged = assertConverges("invalid exact fingerprint", invalid);
        assertThat(unchanged.firstReload().requests.get(0).exactHttpRequest.semanticFingerprint)
                .isEqualTo("invalid-fingerprint");
    }

    @Test
    void representativeExternalParserModelsConvergeWhenSavedNatively() throws Exception {
        List<Origin> origins = List.of(
                new Origin("Postman", parsePostman()),
                new Origin("Bruno", parseBruno()),
                new Origin("Insomnia", parseInsomnia()),
                new Origin("OpenAPI", parseOpenApi(false)),
                new Origin("Swagger", parseOpenApi(true)),
                new Origin("HAR", parseHar()),
                new Origin("Native schema 1", reload(minimalNativeJson(1), "origin-schema1.json")),
                new Origin("Native schema 2", reload(minimalNativeJson(2), "origin-schema2.json"))
        );
        for (Origin origin : origins) {
            assertThat(origin.collection().requests).as(origin.label()).isNotEmpty();
            assertConverges(origin.label(), origin.collection());
        }
    }

    @Test
    void nativeExportDoesNotMutateCanonicalRequestState() {
        ApiCollection collection = baseCollection("mutation");
        ApiRequest request = collection.requests.get(0);
        request.path = "/Root//Nested";
        request.sourceCollection = null;
        request.authOverrideMode = null;
        request.auth = auth("bearer", "token", "wave6-token");
        request.parameters.add(parameter(null, "flag", "retained", false));
        request.body = new ApiRequest.Body();
        request.body.mode = null;
        request.body.formdata.add(field("upload", "retained", "file", false, "fixtures/not-read.bin"));
        request.preRequestScripts.add(new ApiRequest.Script(null, "pre();"));
        request.exactHttpRequest = snapshot("HTTP/1.0", request);
        collection.folderAuthModes.put("/Root//Nested", "none");
        collection.folderVars.put("Root\\Nested", new LinkedHashMap<>(Map.of("scope", "nested")));

        String path = request.path;
        String sourceCollection = request.sourceCollection;
        String override = request.authOverrideMode;
        boolean upload = request.body.formdata.get(0).fileUpload;
        String scriptType = request.preRequestScripts.get(0).type;
        byte[] exact = request.exactHttpRequest.rawRequestBytes.clone();
        Map<String, String> folderModes = new LinkedHashMap<>(collection.folderAuthModes);
        Map<String, Map<String, String>> folderVars = new LinkedHashMap<>(collection.folderVars);

        export(collection);

        assertThat(collection.id).isEqualTo("mutation");
        assertThat(request.path).isEqualTo(path);
        assertThat(request.sourceCollection).isEqualTo(sourceCollection);
        assertThat(request.authOverrideMode).isEqualTo(override);
        assertThat(request.body.formdata.get(0).fileUpload).isEqualTo(upload);
        assertThat(request.preRequestScripts.get(0).type).isEqualTo(scriptType);
        assertThat(request.exactHttpRequest.rawRequestBytes).isEqualTo(exact);
        assertThat(collection.folderAuthModes).isEqualTo(folderModes);
        assertThat(collection.folderVars).isEqualTo(folderVars);
    }

    private JsonObject export(ApiCollection collection) {
        return ApiWorkbenchCollectionExporter.build(
                collection,
                new CollectionExportOptions(
                        CollectionExportFormat.API_WORKBENCH_JSON,
                        null,
                        false,
                        null,
                        Map.of()),
                new ArrayList<>());
    }

    private ApiCollection reload(JsonObject json, String filename) throws Exception {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
        return new ApiWorkbenchCollectionParser().parse(file.toFile());
    }

    private ConvergenceResult assertConverges(String label, ApiCollection original) throws Exception {
        Map<Integer, byte[]> originalRequests = buildEnabledRequests(original);
        JsonObject firstJson = export(original);
        ApiCollection firstReload = reload(firstJson, safe(label) + "-first.json");
        Map<Integer, byte[]> firstReloadRequests = buildEnabledRequests(firstReload);
        JsonObject secondJson = export(firstReload);
        ApiCollection secondReload = reload(secondJson, safe(label) + "-second.json");
        Map<Integer, byte[]> secondReloadRequests = buildEnabledRequests(secondReload);
        JsonObject thirdJson = export(secondReload);

        assertThat(secondJson)
                .withFailMessage(label + " second export differs at " + firstDifference(firstJson, secondJson, "$"))
                .isEqualTo(firstJson);
        assertThat(thirdJson)
                .withFailMessage(label + " third export differs at " + firstDifference(secondJson, thirdJson, "$"))
                .isEqualTo(secondJson);
        assertBuiltRequestsEqual(label + " original to first reload", originalRequests, firstReloadRequests);
        assertBuiltRequestsEqual(label + " first to second reload", firstReloadRequests, secondReloadRequests);
        return new ConvergenceResult(firstJson, firstReload, secondJson, secondReload, thirdJson);
    }

    private static Map<Integer, byte[]> buildEnabledRequests(ApiCollection collection) throws Exception {
        Map<Integer, byte[]> built = new LinkedHashMap<>();
        if (collection == null || collection.requests == null) {
            return built;
        }
        for (int i = 0; i < collection.requests.size(); i++) {
            ApiRequest request = collection.requests.get(i);
            if (request == null || request.disabled
                    || ((request.url == null || request.url.isBlank())
                    && (request.exactHttpRequest == null || request.exactHttpRequest.rawRequestBytes == null))) {
                continue;
            }
            built.put(i, build(collection, request));
        }
        return built;
    }

    private static void assertBuiltRequestsEqual(String label,
                                                 Map<Integer, byte[]> expected,
                                                 Map<Integer, byte[]> actual) {
        assertThat(actual.keySet()).as(label + " request indexes").isEqualTo(expected.keySet());
        for (Map.Entry<Integer, byte[]> entry : expected.entrySet()) {
            assertThat(actual.get(entry.getKey())).as(label + " request " + entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    private static String firstDifference(JsonElement expected, JsonElement actual, String path) {
        if (expected == null || actual == null || expected.getClass() != actual.getClass()) {
            return path + " expected=" + expected + " actual=" + actual;
        }
        if (expected.isJsonObject()) {
            JsonObject left = expected.getAsJsonObject();
            JsonObject right = actual.getAsJsonObject();
            for (String key : left.keySet()) {
                if (!right.has(key)) return path + "." + key + " missing";
                String difference = firstDifference(left.get(key), right.get(key), path + "." + key);
                if (difference != null) return difference;
            }
            for (String key : right.keySet()) {
                if (!left.has(key)) return path + "." + key + " unexpected=" + right.get(key);
            }
            return null;
        }
        if (expected.isJsonArray()) {
            JsonArray left = expected.getAsJsonArray();
            JsonArray right = actual.getAsJsonArray();
            if (left.size() != right.size()) return path + " size expected=" + left.size() + " actual=" + right.size();
            for (int i = 0; i < left.size(); i++) {
                String difference = firstDifference(left.get(i), right.get(i), path + "[" + i + "]");
                if (difference != null) return difference;
            }
            return null;
        }
        return expected.equals(actual) ? null : path + " expected=" + expected + " actual=" + actual;
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static ApiCollection baseCollection(String id) {
        ApiCollection collection = new ApiCollection();
        collection.id = id;
        collection.name = "Collection " + id;
        collection.format = "api-workbench";
        ApiRequest request = request("Request", "", collection.name, "GET", ApiRequest.BuildMode.AUTO_COMPATIBLE, false);
        collection.requests.add(request);
        return collection;
    }

    private static ApiRequest request(String name, String path, String sourceCollection, String method,
                                      ApiRequest.BuildMode buildMode, boolean materialized) {
        ApiRequest request = new ApiRequest();
        request.id = name == null ? null : "id-" + name;
        request.name = name;
        request.path = path;
        request.sourceCollection = sourceCollection;
        request.method = method;
        request.url = "https://example.test/items";
        request.editorMaterialized = materialized;
        request.buildMode = buildMode;
        request.authOverrideMode = "inherit";
        return request;
    }

    private static ApiCollection authCollection(ApiRequest.Auth collectionAuth, String path,
                                                String folderMode, ApiRequest.Auth folderAuth) {
        ApiCollection collection = baseCollection("auth");
        collection.auth = collectionAuth;
        ApiRequest request = collection.requests.get(0);
        request.path = path != null ? path : "";
        if (folderMode != null && path != null) {
            collection.folderAuthModes.put(path, folderMode);
            if (folderAuth != null) collection.folderAuth.put(path, folderAuth);
        }
        return collection;
    }

    private static ApiRequest.Auth auth(String type, String... properties) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = type;
        for (int i = 0; i + 1 < properties.length; i += 2) {
            auth.properties.put(properties[i], properties[i + 1]);
        }
        return auth;
    }

    private static ApiRequest.Parameter parameter(String location, String key, String value, boolean valuePresent) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter();
        parameter.location = location;
        parameter.key = key;
        parameter.value = value;
        parameter.valuePresent = valuePresent;
        return parameter;
    }

    private static ApiRequest.Body.FormField field(String key, String value, String type,
                                                   boolean fileUpload, String path) {
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(key, value);
        field.type = type;
        field.fileUpload = fileUpload;
        field.filePath = path;
        return field;
    }

    private static ApiRequest.Variable variable(String key, String value, String type, boolean enabled) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        variable.type = type;
        variable.enabled = enabled;
        return variable;
    }

    private static ScriptBlock block(String source, ScriptScope scope, int order) {
        ScriptBlock block = ScriptBlock.of(source, ScriptDialect.LEGACY_JAVASCRIPT, ScriptPhase.PRE_REQUEST, scope);
        block.id = "block-" + order;
        block.order = order;
        block.enabled = false;
        return block;
    }

    private static ExactHttpRequestSnapshot snapshot(String version, ApiRequest request) {
        ExactHttpRequestSnapshot snapshot = new ExactHttpRequestSnapshot();
        String wireVersion = version != null ? version : "HTTP/1.0";
        snapshot.rawRequestBytes = ("GET /items " + wireVersion + "\r\nHost: example.test\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        snapshot.serviceHost = "example.test";
        snapshot.servicePort = 443;
        snapshot.secure = true;
        snapshot.httpVersion = version;
        snapshot.pristine = true;
        snapshot.binaryBody = false;
        request.exactHttpRequest = snapshot;
        snapshot.semanticFingerprint = request.computeSemanticFingerprint();
        return snapshot;
    }

    private static byte[] build(ApiCollection collection, ApiRequest request) throws Exception {
        VariableResolver resolver = new VariableResolver();
        resolver.addCollectionVariables(collection);
        resolver.addFolderVariables(collection, request);
        resolver.addRequestVariables(request);
        return new RequestBuilder(null).buildRequest(request, resolver);
    }

    private void assertMissingBodyModeRemainsInactive(String label,
                                                      ApiRequest.Body body,
                                                      String inactiveMarker) throws Exception {
        ApiCollection collection = baseCollection(safe(label));
        ApiRequest request = collection.requests.get(0);
        request.method = "POST";
        request.body = body;

        String originalBytes = new String(build(collection, request), StandardCharsets.UTF_8);
        if (inactiveMarker != null) {
            assertThat(originalBytes).doesNotContain(inactiveMarker);
        }

        ConvergenceResult result = assertConverges(label, collection);
        JsonObject firstBody = result.firstJson().getAsJsonObject("collection")
                .getAsJsonArray("requests").get(0).getAsJsonObject().getAsJsonObject("body");
        assertThat(firstBody.get("mode").getAsString()).isEqualTo("none");
        if (inactiveMarker != null) {
            assertThat(firstBody.toString()).contains(inactiveMarker);
        }
        assertThat(result.firstReload().requests.get(0).body.mode).isEqualTo("none");
        assertThat(result.secondReload().requests.get(0).body.mode).isEqualTo("none");

        String reloadedBytes = new String(
                build(result.firstReload(), result.firstReload().requests.get(0)),
                StandardCharsets.UTF_8);
        if (inactiveMarker != null) {
            assertThat(reloadedBytes).doesNotContain(inactiveMarker);
        }
    }

    private void assertLegacyBodyMode(String expectedMode,
                                      JsonObject body,
                                      String transportMarker) throws Exception {
        JsonObject legacyJson = minimalNativeJson(2);
        JsonObject requestJson = legacyJson.getAsJsonObject("collection")
                .getAsJsonArray("requests").get(0).getAsJsonObject();
        requestJson.addProperty("method", "POST");
        if ("formdata".equals(expectedMode)) {
            JsonObject contentType = new JsonObject();
            contentType.addProperty("key", "Content-Type");
            contentType.addProperty("value", "multipart/form-data; boundary=legacy-fixed-boundary");
            JsonArray headers = new JsonArray();
            headers.add(contentType);
            requestJson.add("headers", headers);
        }
        assertThat(body.has("mode")).isFalse();
        requestJson.add("body", body);

        ApiCollection imported = reload(legacyJson, "legacy-body-" + expectedMode + ".json");
        ApiRequest importedRequest = imported.requests.get(0);
        assertThat(importedRequest.body.mode).isEqualTo(expectedMode);
        assertThat(new String(build(imported, importedRequest), StandardCharsets.UTF_8))
                .contains(transportMarker);

        ConvergenceResult result = assertConverges("legacy body " + expectedMode, imported);
        assertThat(result.firstReload().requests.get(0).body.mode).isEqualTo(expectedMode);
        assertThat(result.secondReload().requests.get(0).body.mode).isEqualTo(expectedMode);
    }

    private static JsonObject bodyWithRaw() {
        JsonObject body = new JsonObject();
        body.addProperty("raw", "legacy-raw-content");
        return body;
    }

    private static JsonObject bodyWithField(String property, String key) {
        JsonObject body = new JsonObject();
        JsonObject field = new JsonObject();
        field.addProperty("key", key);
        field.addProperty("value", "retained");
        field.addProperty("type", "text");
        JsonArray fields = new JsonArray();
        fields.add(field);
        body.add(property, fields);
        return body;
    }

    private static JsonObject bodyWithGraphql() {
        JsonObject body = new JsonObject();
        JsonObject graphql = new JsonObject();
        graphql.addProperty("query", "query { legacyGraphql }");
        graphql.addProperty("variables", "{}");
        body.add("graphql", graphql);
        return body;
    }

    private JsonObject minimalNativeJson(int schema) {
        JsonObject root = new JsonObject();
        root.addProperty("format", "api-workbench-collection");
        root.addProperty("schemaVersion", schema);
        JsonObject collection = new JsonObject();
        collection.addProperty("id", "schema-" + schema);
        collection.addProperty("name", "Schema " + schema);
        collection.addProperty("format", "api-workbench");
        JsonArray requests = new JsonArray();
        JsonObject request = new JsonObject();
        request.addProperty("id", "request");
        request.addProperty("name", "Request");
        request.addProperty("method", "GET");
        request.addProperty("url", "https://example.test/items");
        request.add("parameters", new JsonArray());
        requests.add(request);
        collection.add("requests", requests);
        root.add("collection", collection);
        return root;
    }

    private ApiCollection parsePostman() throws Exception {
        Path file = tempDir.resolve("origin.postman_collection.json");
        Files.writeString(file, """
                {"info":{"name":"Origin Postman","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"Request","request":{"method":"GET","url":"https://example.test/items?flag"}}]}
                """, StandardCharsets.UTF_8);
        return new PostmanParser().parse(file.toFile());
    }

    private ApiCollection parseBruno() throws Exception {
        Path file = tempDir.resolve("origin.bru");
        Files.writeString(file, """
                meta {
                  name: Origin Bruno
                  type: http
                }
                get {
                  url: https://example.test/items?flag
                }
                """, StandardCharsets.UTF_8);
        return new BrunoParser().parse(file.toFile());
    }

    private ApiCollection parseInsomnia() throws Exception {
        Path file = tempDir.resolve("origin.insomnia.json");
        Files.writeString(file, """
                {"_type":"export","__export_format":4,"resources":[
                  {"_id":"wrk_origin","_type":"workspace","name":"Origin Insomnia"},
                  {"_id":"req_origin","_type":"request","parentId":"wrk_origin","name":"Request","method":"GET","url":"https://example.test/items?flag","headers":[],"parameters":[]}
                ]}
                """, StandardCharsets.UTF_8);
        return new InsomniaParser().parse(file.toFile());
    }

    private ApiCollection parseOpenApi(boolean swagger) throws Exception {
        Path file = tempDir.resolve(swagger ? "origin-swagger.yaml" : "origin-openapi.yaml");
        String source = swagger
                ? """
                  swagger: "2.0"
                  info: {title: Origin Swagger, version: "1"}
                  host: example.test
                  schemes: [https]
                  paths:
                    /items/{id}:
                      get:
                        operationId: Request
                        parameters:
                          - {name: id, in: path, required: true, type: string, default: "42"}
                        responses: {"200": {description: ok}}
                  """
                : """
                  openapi: 3.0.3
                  info: {title: Origin OpenAPI, version: "1"}
                  servers: [{url: https://example.test}]
                  paths:
                    /items/{id}:
                      get:
                        operationId: Request
                        parameters:
                          - {name: id, in: path, required: true, schema: {type: string, default: "42"}}
                        responses: {"200": {description: ok}}
                  """;
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return new OpenApiParser().parse(file.toFile());
    }

    private ApiCollection parseHar() throws Exception {
        Path file = tempDir.resolve("origin.har");
        Files.writeString(file, """
                {"log":{"version":"1.2","entries":[{"request":{"method":"GET","url":"https://example.test/items?flag","httpVersion":"HTTP/1.0","headers":[{"name":"Host","value":"example.test"}],"queryString":[{"name":"flag"}],"headersSize":-1,"bodySize":0},"response":{"status":200,"statusText":"OK","httpVersion":"HTTP/1.0","headers":[],"content":{"size":0,"mimeType":"text/plain"},"redirectURL":"","headersSize":-1,"bodySize":0},"cache":{},"timings":{"send":0,"wait":0,"receive":0},"time":0,"startedDateTime":"2026-01-01T00:00:00Z"}]}}
                """, StandardCharsets.UTF_8);
        return new HarParser().parse(file.toFile());
    }

    private record ConvergenceResult(
            JsonObject firstJson,
            ApiCollection firstReload,
            JsonObject secondJson,
            ApiCollection secondReload,
            JsonObject thirdJson) {
    }

    private record AuthCase(String label, ApiCollection collection, String expectedHeader) {
    }

    private record Origin(String label, ApiCollection collection) {
    }
}
