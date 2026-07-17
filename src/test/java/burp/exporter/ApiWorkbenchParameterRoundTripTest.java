package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.ApiWorkbenchCollectionParser;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        assertThat(parameter.description).isEqualTo(expected.description);
        assertThat(parameter.style).isEqualTo(expected.style);
        assertThat(parameter.explode).isEqualTo(expected.explode);
        assertThat(parameter.allowReserved).isEqualTo(expected.allowReserved);
        assertThat(parameter.source).isEqualTo(expected.source);
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
    void schemaVersion1WithoutParametersPreservesUrlAndMutableEmptyList() throws Exception {
        Path file = tempDir.resolve("v1.json");
        Files.writeString(file, """
                {"format":"api-workbench-collection","schemaVersion":1,"collection":{
                  "name":"Legacy","requests":[{"name":"R","method":"GET","url":"https://example.test/a?x=1"}]
                }}
                """, StandardCharsets.UTF_8);
        ApiRequest request = new ApiWorkbenchCollectionParser().parse(file.toFile()).requests.get(0);
        assertThat(request.url).isEqualTo("https://example.test/a?x=1");
        assertThat(request.parameters).isEmpty();
        request.parameters.add(new ApiRequest.Parameter());
        assertThat(request.parameters).hasSize(1);
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

    private static JsonObject export(ApiRequest request) {
        ApiCollection collection = new ApiCollection();
        collection.name = "C";
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
        parameter.description = "description";
        parameter.style = "form";
        parameter.explode = Boolean.FALSE;
        parameter.allowReserved = true;
        parameter.source = "native-test";
        return parameter;
    }
}
