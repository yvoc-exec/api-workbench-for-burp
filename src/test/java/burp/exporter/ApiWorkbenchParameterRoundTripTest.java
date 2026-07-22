package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.parser.ApiWorkbenchCollectionParser;
import burp.utils.RequestBuilder;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiWorkbenchParameterRoundTripTest {
    @TempDir
    Path tempDir;

    @Test
    void exporterWritesSchemaVersion2() {
        assertThat(export(request()).get("schemaVersion").getAsInt()).isEqualTo(2);
    }

    @Test
    void nativeExportCanonicalizesRequestPathsOnceWithoutChangingTransport() throws Exception {
        Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put("/items/{id}", "items/{id}");
        cases.put("Core\\Nested///Leaf", "Core/Nested/Leaf");
        cases.put("Core/Nested", "Core/Nested");

        for (Map.Entry<String, String> entry : cases.entrySet()) {
            ApiRequest original = request();
            original.path = entry.getKey();
            original.sourceCollection = "C";
            original.authSource = "none";
            byte[] before = new RequestBuilder(null).buildRequest(original, null);

            JsonObject first = export(original);
            JsonObject exportedRequest = first.getAsJsonObject("collection")
                    .getAsJsonArray("requests").get(0).getAsJsonObject();
            assertThat(exportedRequest.get("path").getAsString()).isEqualTo(entry.getValue());
            assertNativeExportImportExportIdempotent(first);

            Path file = tempDir.resolve("path-" + Math.abs(entry.getKey().hashCode()) + ".json");
            Files.writeString(file, new GsonBuilder().create().toJson(first), StandardCharsets.UTF_8);
            ApiRequest restored = new ApiWorkbenchCollectionParser().parse(file.toFile()).requests.get(0);
            assertThat(restored.path).isEqualTo(entry.getValue());
            assertThat(new RequestBuilder(null).buildRequest(restored, null)).containsExactly(before);
        }
    }

    @Test
    void absentOptionalDescriptionsAndVersionRemainAbsent() throws Exception {
        for (int schemaVersion : new int[]{1, 2}) {
            JsonObject first = parseAndExportOptionalFields(schemaVersion, null, false);
            JsonObject collection = first.getAsJsonObject("collection");
            JsonObject request = collection.getAsJsonArray("requests").get(0).getAsJsonObject();

            assertThat(collection.has("description")).isFalse();
            assertThat(collection.has("version")).isFalse();
            assertThat(request.has("description")).isFalse();
            assertNativeExportImportExportIdempotent(first);
        }
    }

    @Test
    void explicitEmptyOptionalDescriptionsAndVersionRemainExplicit() throws Exception {
        for (int schemaVersion : new int[]{1, 2}) {
            JsonObject first = parseAndExportOptionalFields(schemaVersion, "", true);
            JsonObject collection = first.getAsJsonObject("collection");
            JsonObject request = collection.getAsJsonArray("requests").get(0).getAsJsonObject();

            assertThat(collection.get("description").getAsString()).isEmpty();
            assertThat(collection.get("version").getAsString()).isEmpty();
            assertThat(request.get("description").getAsString()).isEmpty();
            assertNativeExportImportExportIdempotent(first);
        }
    }

    @Test
    void nonemptyOptionalDescriptionsAndVersionSurvive() throws Exception {
        for (int schemaVersion : new int[]{1, 2}) {
            JsonObject first = parseAndExportOptionalFields(schemaVersion, "retained", true);
            JsonObject collection = first.getAsJsonObject("collection");
            JsonObject request = collection.getAsJsonArray("requests").get(0).getAsJsonObject();

            assertThat(collection.get("description").getAsString()).isEqualTo("retained");
            assertThat(collection.get("version").getAsString()).isEqualTo("retained");
            assertThat(request.get("description").getAsString()).isEqualTo("retained");
            assertNativeExportImportExportIdempotent(first);
        }
    }

    @Test
    void nativeExportImportRetainsEveryParameterField() throws Exception {
        ApiRequest original = request();
        ApiRequest.Parameter expected = original.parameters.get(0);
        ApiRequest actual = roundTrip(original).requests.get(0);
        ApiRequest.Parameter parameter = actual.parameters.get(0);
        assertThat(parameter.location).isEqualTo(expected.location);
        assertThat(parameter.key).isEqualTo(expected.key);
        assertThat(parameter.value).isEqualTo(expected.value);
        assertThat(parameter.rawKey).isEqualTo(expected.rawKey);
        assertThat(parameter.rawValue).isEqualTo(expected.rawValue);
        assertThat(parameter.valuePresent).isEqualTo(expected.valuePresent);
        assertThat(parameter.disabled).isEqualTo(expected.disabled);
        assertThat(parameter.required).isEqualTo(expected.required);
        assertThat(parameter.type).isEqualTo(expected.type);
        assertThat(parameter.format).isEqualTo(expected.format);
        assertThat(parameter.description).isEqualTo(expected.description);
        assertThat(parameter.style).isEqualTo(expected.style);
        assertThat(parameter.explode).isEqualTo(expected.explode);
        assertThat(parameter.allowReserved).isEqualTo(expected.allowReserved);
        assertThat(parameter.source).isEqualTo(expected.source);
        assertThat(parameter.sourceMetadata).isEqualTo(expected.sourceMetadata);
    }

    @Test
    void nativeExportImportRetainsAllWaveThreeMetadata() throws Exception {
        ApiRequest original = request();
        original.sourceMetadata.put("openapi.operation.extensions", "{\"x-op\":true}");
        original.body = new ApiRequest.Body();
        original.body.mode = "formdata";
        original.body.required = true;
        original.body.description = "body";
        original.body.filePath = "relative.bin";
        original.body.source = "openapi:requestBody";
        original.body.sourceMetadata.put("openapi.requestBody.selectedMediaType", "multipart/form-data");
        ApiRequest.Body.FormField field = bodyField("file", "", "");
        field.required = true;
        field.description = "field";
        field.contentType = "image/png";
        field.style = "form";
        field.explode = Boolean.FALSE;
        field.allowReserved = true;
        field.source = "openapi:requestBody.property";
        field.sourceMetadata.put("openapi.schema", "{\"type\":\"string\",\"format\":\"binary\"}");
        original.body.formdata.add(field);

        ApiCollection collection = new ApiCollection();
        collection.name = "C";
        collection.sourceMetadata.put("openapi.sourceVersion", "\"3.1.0\"");
        collection.requests.add(original);
        Path file = tempDir.resolve("wave3-native.json");
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(
                ApiWorkbenchCollectionExporter.build(collection,
                        new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON, null, false, null, Map.of()),
                        new ArrayList<>())));
        ApiCollection actualCollection = new ApiWorkbenchCollectionParser().parse(file.toFile());
        ApiRequest actual = actualCollection.requests.get(0);
        assertThat(actualCollection.sourceMetadata).isEqualTo(collection.sourceMetadata);
        assertThat(actual.sourceMetadata).isEqualTo(original.sourceMetadata);
        assertThat(actual.body).usingRecursiveComparison().isEqualTo(original.body);
    }

    @Test
    void duplicateRowsAndListOrderSurvive() throws Exception {
        ApiRequest original = request();
        original.parameters.add(parameter("tag", "two", false));
        assertThat(roundTrip(original).requests.get(0).parameters)
                .extracting(p -> p.value).containsExactly("one", "two");
    }

    @Test
    void disabledRowsSurvive() throws Exception {
        ApiRequest original = request();
        original.parameters.get(0).disabled = true;
        assertThat(roundTrip(original).requests.get(0).parameters.get(0).disabled).isTrue();
    }

    @Test
    void schemaVersion1WithoutParametersMigratesEmbeddedQuery() throws Exception {
        Path file = tempDir.resolve("v1.json");
        Files.writeString(file, """
                {"format":"api-workbench-collection","schemaVersion":1,"collection":{
                  "name":"Legacy","requests":[{"name":"R","method":"GET","url":"https://example.test/a?x=1&x=2&flag&empty=&encoded=a%2Fb#fragment"}]
                }}
                """, StandardCharsets.UTF_8);
        ApiRequest request = new ApiWorkbenchCollectionParser().parse(file.toFile()).requests.get(0);
        assertThat(request.url).isEqualTo("https://example.test/a#fragment");
        assertThat(request.parameters).extracting(p -> p.key)
                .containsExactly("x", "x", "flag", "empty", "encoded");
        assertThat(request.parameters).extracting(p -> p.valuePresent)
                .containsExactly(true, true, false, true, true);
        assertThat(request.parameters.get(4).rawValue).isEqualTo("a%2Fb");
        request.parameters.add(new ApiRequest.Parameter());
        assertThat(request.parameters).hasSize(6);

        byte[] before = new RequestBuilder(null).buildRequest(request, null);
        JsonObject exported = export(request);
        assertThat(exported.get("schemaVersion").getAsInt()).isEqualTo(2);
        Path second = tempDir.resolve("migrated.json");
        Files.writeString(second, new GsonBuilder().create().toJson(exported), StandardCharsets.UTF_8);
        ApiRequest restored = new ApiWorkbenchCollectionParser().parse(second.toFile()).requests.get(0);
        assertThat(restored.parameters).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(request.parameters);
        assertThat(new RequestBuilder(null).buildRequest(restored, null)).containsExactly(before);
    }

    @Test
    void schemaVersion1ExactSnapshotDoesNotMigrateEmbeddedQuery() throws Exception {
        byte[] raw = "GET /a?x=1 HTTP/1.1\r\nHost: example.test\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8);
        Path file = tempDir.resolve("v1-exact.json");
        Files.writeString(file, """
                {"format":"api-workbench-collection","schemaVersion":1,"collection":{
                  "name":"Legacy","requests":[{"name":"R","method":"GET","url":"https://example.test/a?x=1",
                  "buildMode":"EXACT_HTTP","exactHttpRequest":{"rawRequestBase64":"%s","serviceHost":"example.test","servicePort":443,"secure":true,"pristine":true}}]
                }}
                """.formatted(Base64.getEncoder().encodeToString(raw)), StandardCharsets.UTF_8);
        ApiRequest request = new ApiWorkbenchCollectionParser().parse(file.toFile()).requests.get(0);
        assertThat(request.url).isEqualTo("https://example.test/a?x=1");
        assertThat(request.parameters).isEmpty();
        assertThat(request.exactHttpRequest).isNotNull();
        assertThat(request.exactHttpRequest.rawRequestBytes).containsExactly(raw);
    }

    @Test
    void schemaVersion1DeclaredEmptyParametersDoesNotMigrate() throws Exception {
        Path file = tempDir.resolve("v1-declared.json");
        Files.writeString(file, """
                {"format":"api-workbench-collection","schemaVersion":1,"collection":{
                  "name":"Legacy","requests":[{"name":"R","method":"GET","url":"https://example.test/a?x=1","parameters":[]}]
                }}
                """, StandardCharsets.UTF_8);
        ApiRequest request = new ApiWorkbenchCollectionParser().parse(file.toFile()).requests.get(0);
        assertThat(request.url).isEqualTo("https://example.test/a?x=1");
        assertThat(request.parameters).isEmpty();
    }

    @Test
    void nativeRoundTripRetainsEmptyQueryKeysAndSegments() throws Exception {
        ApiRequest original = request();
        original.parameters.clear();
        ApiRequest.Parameter emptyValue = parameter("", "x", false);
        emptyValue.rawKey = "";
        emptyValue.rawValue = "x";
        ApiRequest.Parameter whitespace = parameter(" ", "two", false);
        whitespace.rawKey = "%20";
        whitespace.rawValue = "two";
        ApiRequest.Parameter emptySegment = parameter("", "", false);
        emptySegment.rawKey = "";
        emptySegment.rawValue = "";
        emptySegment.valuePresent = false;
        original.parameters.add(emptyValue);
        original.parameters.add(whitespace);
        original.parameters.add(emptySegment);

        ApiRequest actual = roundTrip(original).requests.get(0);

        assertThat(actual.parameters).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(original.parameters);
    }

    @Test
    void nativeRoundTripRetainsExplicitEmptyBodyFilePath() throws Exception {
        ApiRequest original = request();
        original.body = new ApiRequest.Body();
        original.body.mode = "urlencoded";
        original.body.urlencoded.add(bodyField("url", "1", ""));
        original.body.formdata.add(bodyField("form", "2", ""));

        ApiRequest actual = roundTrip(original).requests.get(0);

        assertThat(actual.body.urlencoded.get(0).filePath).isEmpty();
        assertThat(actual.body.formdata.get(0).filePath).isEmpty();
    }

    @Test
    void nativeRoundTripRetainsEmptyBodyFieldKeys() throws Exception {
        ApiRequest original = request();
        original.body = new ApiRequest.Body();
        original.body.mode = "urlencoded";
        original.body.urlencoded.add(bodyField("", "one", null));
        original.body.urlencoded.add(bodyField(" ", "two", ""));

        ApiRequest actual = roundTrip(original).requests.get(0);

        assertThat(actual.body.urlencoded).extracting(field -> field.key).containsExactly("", " ");
        assertThat(actual.body.urlencoded).extracting(field -> field.filePath).containsExactly(null, "");
    }

    private static ApiRequest.Body.FormField bodyField(String key, String value, String filePath) {
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(key, value);
        field.type = null;
        field.filePath = filePath;
        return field;
    }

    private ApiCollection roundTrip(ApiRequest request) throws Exception {
        Path file = tempDir.resolve("native.json");
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(export(request)), StandardCharsets.UTF_8);
        return new ApiWorkbenchCollectionParser().parse(file.toFile());
    }

    private JsonObject parseAndExportOptionalFields(int schemaVersion,
                                                    String value,
                                                    boolean declared) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("format", "api-workbench-collection");
        root.addProperty("schemaVersion", schemaVersion);
        JsonObject collection = new JsonObject();
        collection.addProperty("id", "optional-fields");
        collection.addProperty("name", "Optional Fields");
        collection.addProperty("format", "api-workbench");
        if (declared) {
            collection.addProperty("description", value);
            collection.addProperty("version", value);
        }
        JsonObject request = new JsonObject();
        request.addProperty("id", "optional-request");
        request.addProperty("name", "Optional Request");
        request.addProperty("method", "GET");
        request.addProperty("url", "https://example.test/optional");
        if (declared) {
            request.addProperty("description", value);
        }
        com.google.gson.JsonArray requests = new com.google.gson.JsonArray();
        requests.add(request);
        collection.add("requests", requests);
        root.add("collection", collection);

        Path input = tempDir.resolve("optional-" + schemaVersion + "-" + declared + "-"
                + (value == null ? "absent" : value.isEmpty() ? "empty" : "value") + ".json");
        Files.writeString(input, new GsonBuilder().create().toJson(root), StandardCharsets.UTF_8);
        ApiCollection parsed = new ApiWorkbenchCollectionParser().parse(input.toFile());
        assertThat(parsed.description).isEqualTo(declared ? value : null);
        assertThat(parsed.version).isEqualTo(declared ? value : null);
        assertThat(parsed.requests.get(0).description).isEqualTo(declared ? value : null);
        return ApiWorkbenchCollectionExporter.build(parsed,
                new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON,
                        null, false, null, Map.of()), new ArrayList<>());
    }

    private void assertNativeExportImportExportIdempotent(JsonObject first) throws Exception {
        Path firstFile = tempDir.resolve("first-" + Math.abs(first.hashCode()) + ".json");
        Files.writeString(firstFile, new GsonBuilder().create().toJson(first), StandardCharsets.UTF_8);
        ApiCollection reloaded = new ApiWorkbenchCollectionParser().parse(firstFile.toFile());
        JsonElement second = ApiWorkbenchCollectionExporter.build(reloaded,
                new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON,
                        null, false, null, Map.of()), new ArrayList<>());
        assertThat(second).isEqualTo(JsonParser.parseString(Files.readString(firstFile)));
    }

    private static JsonObject export(ApiRequest request) {
        ApiCollection collection = new ApiCollection();
        collection.name = "C";
        collection.format = "api-workbench";
        collection.requests.add(request);
        return ApiWorkbenchCollectionExporter.build(collection,
                new CollectionExportOptions(CollectionExportFormat.API_WORKBENCH_JSON, null, false, null, Map.of()),
                new ArrayList<>());
    }

    private static ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.name = "R";
        request.method = "GET";
        request.url = "https://example.test/a?tag=one";
        request.parameters.add(parameter("tag", "one", false));
        return request;
    }

    private static ApiRequest.Parameter parameter(String key, String value, boolean disabled) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter("QUERY-custom-case", key, value);
        parameter.rawKey = "t%61g";
        parameter.rawValue = "%6Fne";
        parameter.valuePresent = true;
        parameter.disabled = disabled;
        parameter.required = true;
        parameter.type = "string";
        parameter.format = "uuid";
        parameter.description = "description";
        parameter.style = "form";
        parameter.explode = Boolean.FALSE;
        parameter.allowReserved = true;
        parameter.source = "native-test";
        parameter.sourceMetadata.put("openapi.schema", "{\"type\":\"string\"}");
        return parameter;
    }
}
