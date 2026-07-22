package burp.fidelity;

import burp.exporter.CollectionExportFormat;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.OpenApiParser;
import burp.scripts.ScriptBlock;
import burp.utils.HarMetadataSupport;
import burp.utils.RequestPathResolver;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static burp.fidelity.Wave6FixtureSupport.FixtureCase;
import static org.assertj.core.api.Assertions.assertThat;

class Wave6EndToEndFidelityTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixtureCase.class)
    void allRepresentativeFixturesCompleteFullLifecycle(FixtureCase fixture) throws Exception {
        Wave6FixtureSupport.LifecycleResult result = run(fixture, "complete");
        assertThat(result.sourceImport()).isNotNull();
        assertThat(result.nativeReload()).isNotNull();
        assertThat(result.targetReload()).isNotNull();
        assertThat(result.sourceImport().requests).isNotEmpty();
        assertThat(result.nativeReload().requests).hasSameSizeAs(result.sourceImport().requests);
        assertThat(result.targetReload().requests).hasSameSizeAs(result.sourceImport().requests);
        assertArtifact(result.firstNativeFile());
        assertArtifact(result.secondNativeFile());
        assertArtifact(result.targetFile());
        assertThat(result.firstNativeExport().requestCount).isEqualTo(result.sourceImport().requests.size());
        assertThat(result.targetExport().requestCount).isEqualTo(result.nativeReload().requests.size());
        Wave6FixtureSupport.assertSameBuiltRequests(
                result.sourceBuiltRequests(), result.nativeBuiltRequests());
        if (fixture.targetTransportIsExactlyRepresentable()) {
            if (fixture == FixtureCase.OPENAPI_31_JSON
                    || fixture == FixtureCase.OPENAPI_31_YAML
                    || fixture == FixtureCase.SWAGGER_20_JSON) {
                assertSameBuiltPayloads(result.nativeBuiltRequests(), result.targetBuiltRequests());
            } else {
                Wave6FixtureSupport.assertSameBuiltRequests(
                        result.nativeBuiltRequests(), result.targetBuiltRequests());
            }
        }
    }

    @Test
    void nativePersistenceIsJsonTreeIdempotentForEveryFixture() throws Exception {
        for (FixtureCase fixture : FixtureCase.values()) {
            Wave6FixtureSupport.LifecycleResult result = run(fixture, "idempotent");
            JsonElement first = JsonParser.parseString(Files.readString(result.firstNativeFile()));
            JsonElement second = JsonParser.parseString(Files.readString(result.secondNativeFile()));
            assertThat(second).as(fixture.displayName).isEqualTo(first);
        }
    }

    @Test
    void everyCollectionExportFormatHasWave6LifecycleCoverage() {
        EnumSet<CollectionExportFormat> covered = EnumSet.noneOf(CollectionExportFormat.class);
        for (FixtureCase fixture : FixtureCase.values()) {
            covered.add(fixture.targetFormat);
        }
        assertThat(covered).isEqualTo(EnumSet.allOf(CollectionExportFormat.class));
    }

    @Test
    void postmanV21RecognizedStructuresSurvive() throws Exception {
        Wave6FixtureSupport.LifecycleResult result = run(FixtureCase.POSTMAN_V21, "postman21");
        for (ApiCollection stage : stages(result)) {
            assertThat(stage.name).isEqualTo("Wave 6 Postman");
            assertThat(stage.requests).hasSize(3);
            ApiRequest get = request(stage, "Canonical GET");
            assertThat(RequestPathResolver.getRequestFolderPath(stage, get)).isEqualTo("Core/Nested");
            assertThat(query(get)).extracting(parameter -> parameter.key)
                    .containsExactly("tag", "tag", "flag", "empty", "disabled");
            assertThat(query(get).get(2).valuePresent).isFalse();
            assertThat(query(get).get(3).valuePresent).isTrue();
            assertThat(query(get).get(4).disabled).isTrue();
            assertThat(get.parameters).anyMatch(parameter -> "path".equals(parameter.location)
                    && "id".equals(parameter.key));
            assertDuplicateAndDisabledHeaders(get);
            assertScriptsDisabled(get.scriptBlocks);
            ApiRequest structured = request(stage, "Structured Body");
            assertThat(structured.body.urlencoded).extracting(field -> field.key)
                    .containsExactly("item", "item", "disabledField");
            assertThat(structured.body.urlencoded.get(2).disabled).isTrue();
            ApiRequest multipart = request(stage, "Multipart Body");
            ApiRequest.Body.FormField upload = multipart.body.formdata.get(1);
            assertThat(upload.disabled).isTrue();
            assertThat(upload.fileUpload).isTrue();
        }
        String raw = builtByName(result.nativeBuiltRequests(), "Canonical GET");
        assertThat(raw).contains("/items/42", "tag=%6Fne", "tag=two", "flag", "empty=",
                "X-Dupe: one", "X-Dupe: two", "Cookie: session=cookie")
                .doesNotContain("disabled=no", "X-Disabled: no", "disabledField=no");
    }

    @Test
    void postmanV20ImportsAndNormalizesWithoutTransportLoss() throws Exception {
        Wave6FixtureSupport.LifecycleResult result = run(FixtureCase.POSTMAN_V20, "postman20");
        assertThat(Files.readString(result.targetFile()))
                .contains("https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        for (ApiCollection stage : stages(result)) {
            ApiRequest request = request(stage, "V20 Canonical GET");
            assertThat(query(request)).extracting(parameter -> parameter.key)
                    .containsExactly("tag", "tag", "flag", "empty");
            assertThat(query(request).get(2).valuePresent).isFalse();
            assertThat(query(request).get(3).valuePresent).isTrue();
            assertDuplicateAndDisabledHeaders(request);
        }
        Wave6FixtureSupport.assertSameBuiltRequests(
                result.sourceBuiltRequests(), result.targetBuiltRequests());
    }

    @Test
    void brunoFolderAndZipLifecyclePreservesRecognizedStructures() throws Exception {
        Wave6FixtureSupport.LifecycleResult result = run(FixtureCase.BRUNO_FOLDER, "bruno");
        for (ApiCollection stage : List.of(result.sourceImport(), result.nativeReload())) {
            assertThat(stage.name).isEqualTo("Wave 6 Bruno");
            ApiRequest get = request(stage, "Canonical GET");
            assertThat(RequestPathResolver.getRequestFolderPath(stage, get)).isEqualTo("Core/Nested");
            assertThat(stage.variables).anyMatch(variable -> "baseUrl".equals(variable.key));
            assertThat(stage.folderVars).containsKeys("Core", "Core/Nested");
            assertThat(get.variables).anyMatch(variable -> "requestVar".equals(variable.key));
            assertThat(get.parameters).anyMatch(parameter -> "path".equals(parameter.location)
                    && "id".equals(parameter.key));
            assertThat(query(get)).extracting(parameter -> parameter.key)
                    .contains("tag", "tag", "disabled", "flag", "empty", "raw");
            assertDuplicateAndDisabledHeaders(get);
            assertThat(get.explicitAuth).isNotNull();
            assertScriptsDisabled(get.scriptBlocks);
            ApiRequest body = request(stage, "Structured Body");
            assertThat(body.body.urlencoded).extracting(field -> field.key)
                    .containsExactly("item", "item", "disabledField");
        }
        Wave6FixtureSupport.assertSameBuiltRequests(
                result.sourceBuiltRequests(), result.nativeBuiltRequests());
        assertThat(zipNames(result.targetFile()))
                .anyMatch(name -> name.endsWith("bruno.json"))
                .anyMatch(name -> name.endsWith("collection.bru"))
                .anyMatch(name -> name.contains("Core"))
                .anyMatch(name -> name.contains("Nested"))
                .anyMatch(name -> name.endsWith("Canonical GET.bru"))
                .anyMatch(name -> name.endsWith("Structured Body.bru"));
        ApiRequest target = request(result.targetReload(), "Canonical GET");
        assertThat(query(target)).extracting(parameter -> parameter.key)
                .contains("tag", "tag", "flag", "empty", "raw");
        assertThat(builtByName(result.sourceBuiltRequests(), "Canonical GET")).isNotBlank();
        assertThat(builtByName(result.nativeBuiltRequests(), "Canonical GET")).isNotBlank();
        assertThat(builtByName(result.targetBuiltRequests(), "Canonical GET")).isNotBlank();
    }

    @Test
    void insomniaLifecyclePreservesHierarchyEnvironmentAuthParametersBodiesAndScripts() throws Exception {
        Wave6FixtureSupport.LifecycleResult result = run(FixtureCase.INSOMNIA_V4, "insomnia");
        for (ApiCollection stage : List.of(result.sourceImport(), result.nativeReload())) {
            assertThat(stage.name).isEqualTo("Wave 6 Insomnia");
            assertThat(stage.environment).containsEntry("baseUrl", "https://example.test");
            assertThat(stage.environment.get("nested")).contains("scope", "base");
            ApiRequest get = request(stage, "Canonical GET");
            assertThat(RequestPathResolver.getRequestFolderPath(stage, get)).isEqualTo("Core/Nested");
            assertThat(get.authInherited).isTrue();
            assertThat(query(get)).extracting(parameter -> parameter.key)
                    .containsExactly("encoded", "disabled", "flag", "empty", "tag", "tag", "raw");
            assertThat(query(get).get(2).valuePresent).isFalse();
            assertDuplicateAndDisabledHeaders(get);
            assertScriptsDisabled(get.scriptBlocks);
            assertThat(request(stage, "Structured Body").body.urlencoded)
                    .extracting(field -> field.key).containsExactly("item", "item", "disabledField");
            ApiRequest.Body.FormField upload = request(stage, "Multipart Body").body.formdata.get(1);
            assertThat(upload.disabled).isTrue();
            assertThat(upload.filePath).endsWith("not-read.bin")
                    .doesNotStartWith("C:")
                    .doesNotStartWith("/");
            assertThat(upload.fileUpload).isTrue();
            assertThat(upload.value).isEqualTo("not-read.bin");
        }
        Wave6FixtureSupport.assertSameBuiltRequests(
                result.sourceBuiltRequests(), result.nativeBuiltRequests());
        String nativeRaw = builtByName(result.nativeBuiltRequests(), "Canonical GET");
        String targetRaw = builtByName(result.targetBuiltRequests(), "Canonical GET");
        assertThat(nativeRaw).contains("flag&");
        assertThat(targetRaw).contains("flag=&");
        assertThat(result.targetExport().warnings)
                .anyMatch(warning -> warning.contains("bare query parameters")
                        && warning.contains("explicit-empty"));
        assertThat(result.targetExport().warnings).allSatisfy(warning -> assertThat(warning)
                .doesNotContain("wave6-token", "session=cookie", "retained text", "\r", "\n"));
    }

    @Test
    void openApi31JsonAndYamlLifecyclesPreserveRecognizedStructures() throws Exception {
        for (FixtureCase fixture : List.of(FixtureCase.OPENAPI_31_JSON, FixtureCase.OPENAPI_31_YAML)) {
            Wave6FixtureSupport.LifecycleResult result = run(fixture, "openapi");
            assertOpenApiRequest(request(result.sourceImport(), "createItem"));
            assertOpenApiRequest(request(result.nativeReload(), "createItem"));
            assertThat(result.targetReload().requests).singleElement().satisfies(
                    Wave6EndToEndFidelityTest::assertOpenApiRequest);
            assertThat(result.nativeReload().sourceMetadata).isNotEmpty();
            assertThat(request(result.nativeReload(), "createItem").sourceMetadata).isNotEmpty();
            assertSameBuiltPayloads(result.sourceBuiltRequests(), result.targetBuiltRequests());
            String artifact = Files.readString(result.targetFile());
            assertThat(artifact).doesNotContain("components.yaml");
            if (fixture == FixtureCase.OPENAPI_31_JSON) {
                assertThat(JsonParser.parseString(artifact).isJsonObject()).isTrue();
            } else {
                assertThat(new OpenApiParser().parse(result.targetFile().toFile()).requests).isNotEmpty();
            }
            assertInternalReferencesPortable(artifact);
        }
    }

    @Test
    void swagger20ImportsAndExportsAsOpenApi3WithoutTransportLoss() throws Exception {
        Wave6FixtureSupport.LifecycleResult result = run(FixtureCase.SWAGGER_20_JSON, "swagger");
        ApiRequest source = request(result.sourceImport(), "swaggerItem");
        assertThat(source.url).startsWith("https://example.test/v2/items/");
        assertThat(source.parameters).anyMatch(parameter -> "path".equals(parameter.location));
        assertThat(source.parameters).anyMatch(parameter -> "query".equals(parameter.location));
        assertThat(source.parameters).anyMatch(parameter -> "header".equals(parameter.location));
        assertThat(source.body.urlencoded).isNotEmpty();
        assertThat(result.nativeReload().sourceMetadata.toString()).contains("2.0");
        assertThat(Files.readString(result.targetFile())).contains("\"openapi\"");
        assertSameBuiltPayloads(result.sourceBuiltRequests(), result.targetBuiltRequests());
    }

    @Test
    void harLifecyclePreservesExactTransportAndRecognizedHarMetadata() throws Exception {
        Wave6FixtureSupport.LifecycleResult result = run(FixtureCase.HAR_12, "har");
        for (ApiCollection stage : List.of(result.sourceImport(), result.nativeReload())) {
            assertThat(stage.requests).hasSize(3);
            ApiRequest first = stage.requests.get(0);
            assertThat(first.exactHttpRequest).isNotNull();
            assertThat(first.exactHttpRequest.httpVersion).isEqualTo("HTTP/1.0");
            assertThat(first.headers).extracting(header -> header.key)
                    .containsSequence("X-Dupe", "X-Dupe");
            assertThat(query(first)).extracting(parameter -> parameter.key)
                    .containsExactly("tag", "tag", "flag", "empty");
            assertThat(query(first).get(2).valuePresent).isFalse();
            boolean cookiePreserved = first.parameters.stream()
                    .anyMatch(parameter -> "cookie".equals(parameter.location))
                    || first.headers.stream().anyMatch(header -> "Cookie".equalsIgnoreCase(header.key));
            assertThat(cookiePreserved).isTrue();
            assertThat(stage.requests.get(1).body.urlencoded).isNotEmpty();
            ApiRequest.Body.FormField upload = stage.requests.get(2).body.formdata.get(1);
            assertThat(upload.filePath).isNull();
            assertThat(upload.contentType).isEqualTo("application/octet-stream");
            assertThat(stage.sourceMetadata).containsKeys(
                    HarMetadataSupport.ROOT_FIELDS, HarMetadataSupport.LOG_FIELDS);
        }
        assertThat(result.nativeReload().requests.get(0).exactHttpRequest.rawRequestBytes)
                .isEqualTo(result.sourceImport().requests.get(0).exactHttpRequest.rawRequestBytes);
        JsonObject har = JsonParser.parseString(Files.readString(result.targetFile())).getAsJsonObject();
        assertThat(har.has("rootVendor")).isTrue();
        assertThat(har.getAsJsonObject("log").has("browser")).isTrue();
        assertThat(har.getAsJsonObject("log").getAsJsonArray("entries")).hasSize(3);
        Wave6FixtureSupport.assertSameBuiltRequests(
                result.sourceBuiltRequests(), result.targetBuiltRequests());
    }

    @Test
    void nativeLifecyclePreservesCompleteCanonicalModelAndExactSnapshot() throws Exception {
        Wave6FixtureSupport.LifecycleResult result = run(FixtureCase.NATIVE_V2, "native");
        JsonObject first = JsonParser.parseString(Files.readString(result.firstNativeFile())).getAsJsonObject();
        JsonObject second = JsonParser.parseString(Files.readString(result.secondNativeFile())).getAsJsonObject();
        assertThat(first.get("schemaVersion").getAsInt()).isEqualTo(2);
        assertThat(second).isEqualTo(first);
        ApiCollection collection = result.nativeReload();
        assertThat(collection.sourceMetadata).isNotEmpty();
        assertThat(collection.folderPaths).contains("Core", "Core/Nested", "Bodies");
        assertThat(collection.variables).anyMatch(variable -> !variable.enabled);
        assertThat(collection.auth).isNotNull();
        assertThat(collection.scriptBlocks).isNotEmpty();
        assertThat(collection.folderScriptBlocks).isNotEmpty();
        ApiRequest request = request(collection, "Canonical GET");
        assertThat(request.parameters).hasSize(8).allSatisfy(parameter -> {
            assertThat(parameter.source).isEqualTo("native:fixture");
            assertThat(parameter.sourceMetadata).isNotEmpty();
        });
        assertThat(request.body.raw).isEqualTo("retained-native-structured-source");
        assertThat(request.body.sourceMetadata).isNotEmpty();
        assertThat(request.body.urlencoded).allSatisfy(field -> assertThat(field.sourceMetadata).isNotEmpty());
        assertThat(request.sourceMetadata).isNotEmpty();
        ApiRequest exact = request(collection, "Exact HTTP");
        assertThat(exact.exactHttpRequest).isNotNull();
        assertThat(exact.exactHttpRequest.httpVersion).isEqualTo("HTTP/1.0");
        assertThat(exact.exactHttpRequest.rawRequestBytes)
                .isEqualTo(request(result.sourceImport(), "Exact HTTP").exactHttpRequest.rawRequestBytes);
        Wave6FixtureSupport.assertSameBuiltRequests(
                result.sourceBuiltRequests(), result.targetBuiltRequests());
    }

    private Wave6FixtureSupport.LifecycleResult run(FixtureCase fixture, String label) throws Exception {
        return Wave6FixtureSupport.runLifecycle(fixture, tempDir.resolve(label));
    }

    private static List<ApiCollection> stages(Wave6FixtureSupport.LifecycleResult result) {
        return List.of(result.sourceImport(), result.nativeReload(), result.targetReload());
    }

    private static ApiRequest request(ApiCollection collection, String name) {
        return Wave6FixtureSupport.requestByName(collection, name);
    }

    private static List<ApiRequest.Parameter> query(ApiRequest request) {
        return request.parameters.stream().filter(parameter -> "query".equals(parameter.location)).toList();
    }

    private static ApiRequest.Parameter parameter(ApiRequest request, String location, String key) {
        return request.parameters.stream()
                .filter(value -> location.equals(value.location) && key.equals(value.key))
                .findFirst().orElseThrow();
    }

    private static void assertOpenApiRequest(ApiRequest request) {
        assertThat(request.parameters).extracting(parameter -> parameter.location)
                .contains("path", "query", "header", "cookie");
        ApiRequest.Parameter id = parameter(request, "path", "id");
        assertThat(id.required).isTrue();
        assertThat(id.style).isEqualTo("simple");
        assertThat(id.explode).isFalse();
        assertThat(parameter(request, "query", "reserved").allowReserved).isTrue();
        assertThat(request.body.required).isTrue();
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.contentType).isEqualTo("application/json");
        assertThat(request.body.sourceMetadata.toString())
                .contains("application/x-www-form-urlencoded", "items", "enabled");
    }

    private static void assertDuplicateAndDisabledHeaders(ApiRequest request) {
        assertThat(request.headers).filteredOn(header -> "X-Dupe".equals(header.key)).hasSize(2);
        assertThat(request.headers).anyMatch(header -> "X-Disabled".equals(header.key) && header.disabled);
    }

    private static void assertScriptsDisabled(List<ScriptBlock> scripts) {
        assertThat(scripts).isNotEmpty().allMatch(script -> !script.enabled);
    }

    private static String builtByName(Map<String, byte[]> requests, String name) {
        return requests.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("::" + name))
                .map(entry -> new String(entry.getValue(), StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
    }

    private static Set<String> zipNames(Path zip) throws Exception {
        Set<String> names = new java.util.LinkedHashSet<>();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static void assertArtifact(Path path) throws Exception {
        assertThat(path).exists();
        assertThat(Files.size(path)).isPositive();
    }

    private static void assertSameBuiltPayloads(Map<String, byte[]> expected,
                                                Map<String, byte[]> actual) {
        assertThat(actual).hasSameSizeAs(expected);
        List<byte[]> remaining = new java.util.ArrayList<>(actual.values());
        for (byte[] expectedBytes : expected.values()) {
            int match = -1;
            for (int index = 0; index < remaining.size(); index++) {
                if (java.util.Arrays.equals(expectedBytes, remaining.get(index))) {
                    match = index;
                    break;
                }
            }
            assertThat(match).as("matching built request payload").isNotNegative();
            remaining.remove(match);
        }
        assertThat(remaining).isEmpty();
    }

    private static void assertInternalReferencesPortable(String artifact) {
        Object root = new Yaml().load(artifact);
        assertThat(root).isInstanceOf(Map.class);
        assertPortableReferences(root, root);
    }

    private static void assertPortableReferences(Object value, Object root) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("$ref".equals(entry.getKey())) {
                    assertThat(entry.getValue()).isInstanceOf(String.class);
                    String reference = (String) entry.getValue();
                    assertThat(reference).startsWith("#/").doesNotContain("components.yaml");
                    assertThat(resolveJsonPointer(root, reference))
                            .as("resolved internal reference " + reference)
                            .isNotNull();
                }
                assertPortableReferences(entry.getValue(), root);
            }
        } else if (value instanceof List<?> list) {
            list.forEach(item -> assertPortableReferences(item, root));
        }
    }

    private static Object resolveJsonPointer(Object root, String reference) {
        Object current = root;
        for (String encoded : reference.substring(2).split("/")) {
            String token = encoded.replace("~1", "/").replace("~0", "~");
            if (current instanceof Map<?, ?> map) {
                current = map.get(token);
            } else if (current instanceof List<?> list) {
                try {
                    current = list.get(Integer.parseInt(token));
                } catch (NumberFormatException | IndexOutOfBoundsException exception) {
                    return null;
                }
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}
