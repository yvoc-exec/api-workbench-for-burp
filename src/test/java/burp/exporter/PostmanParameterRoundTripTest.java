package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.PostmanParser;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PostmanParameterRoundTripTest {
    @TempDir
    Path tempDir;

    @Test
    void enabledAndDisabledParametersExportAsPostmanUrlObject() {
        JsonObject url = exportedUrl(request());
        assertThat(url).isNotNull();
        assertThat(url.has("raw")).isTrue();
        assertThat(url.has("query")).isTrue();
    }

    @Test
    void rawContainsActiveRowsOnly() {
        JsonObject url = exportedUrl(request());
        assertThat(url.get("raw").getAsString()).isEqualTo("https://example.test/a?flag&empty=&tag=one");
        assertThat(url.get("raw").getAsString()).doesNotContain("skip");
    }

    @Test
    void queryContainsAllRowsIncludingDisabled() {
        JsonObject url = exportedUrl(request());
        assertThat(url.getAsJsonArray("query")).hasSize(4);
        assertThat(url.getAsJsonArray("query").get(3).getAsJsonObject().get("disabled").getAsBoolean()).isTrue();
    }

    @Test
    void reimportPreservesPostmanParameterMetadataAndOrder() throws Exception {
        ApiRequest original = request();
        JsonObject root = export(original);
        Path file = tempDir.resolve("postman.json");
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        ApiRequest actual = new PostmanParser().parse(file.toFile()).requests.get(0);

        assertThat(actual.parameters).extracting(p -> p.key).containsExactly("flag", "empty", "tag", "skip");
        assertThat(actual.parameters).extracting(p -> p.value).containsExactly("", "", "one", "x");
        assertThat(actual.parameters).extracting(p -> p.disabled).containsExactly(false, false, false, true);
        assertThat(actual.parameters).extracting(p -> p.description).containsExactly("bare", "empty", "duplicate", "disabled");
        assertThat(actual.parameters).extracting(p -> p.type).containsExactly("text", "text", "string", "string");
    }

    @Test
    void bareAndExplicitEmptyRemainDistinguishableAfterRoundTrip() throws Exception {
        Path file = tempDir.resolve("postman-bare-empty.json");
        Files.writeString(file, new GsonBuilder().create().toJson(export(request())), StandardCharsets.UTF_8);
        ApiRequest actual = new PostmanParser().parse(file.toFile()).requests.get(0);
        assertThat(actual.parameters).extracting(p -> p.valuePresent).containsExactly(false, true, true, true);
    }

    private JsonObject exportedUrl(ApiRequest request) {
        JsonArray items = export(request).getAsJsonArray("item");
        return items.get(0).getAsJsonObject().getAsJsonObject("request").getAsJsonObject("url");
    }

    private static JsonObject export(ApiRequest request) {
        ApiCollection collection = new ApiCollection();
        collection.name = "C";
        collection.requests.add(request);
        return PostmanCollectionExporter.build(collection,
                new CollectionExportOptions(CollectionExportFormat.POSTMAN_JSON, null, false, null, Map.of()),
                new ArrayList<>());
    }

    private static ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.name = "R";
        request.method = "GET";
        request.url = "https://example.test/a?stale=1";
        request.parameters.add(parameter("flag", "", false, false, "bare", "text"));
        request.parameters.add(parameter("empty", "", true, false, "empty", "text"));
        request.parameters.add(parameter("tag", "one", true, false, "duplicate", "string"));
        request.parameters.add(parameter("skip", "x", true, true, "disabled", "string"));
        return request;
    }

    private static ApiRequest.Parameter parameter(String key, String value, boolean valuePresent,
                                                   boolean disabled, String description, String type) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter("query", key, value);
        parameter.valuePresent = valuePresent;
        parameter.disabled = disabled;
        parameter.description = description;
        parameter.type = type;
        return parameter;
    }
}
