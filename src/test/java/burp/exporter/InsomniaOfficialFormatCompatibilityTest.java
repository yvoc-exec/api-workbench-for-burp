package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InsomniaOfficialFormatCompatibilityTest {
    @TempDir java.nio.file.Path tempDir;

    @Test
    void emitsOfficialHeadersParametersPathsResourcesAndMultipartShape() throws Exception {
        ApiCollection collection = new ApiCollection(); collection.id = "wrk_exact"; collection.name = "Official";
        collection.folderPaths.add("Parent");
        collection.folderVars.put("Parent", new java.util.LinkedHashMap<>(java.util.Map.of("folder", "value")));
        ApiRequest first = requestModel("Request", "Parent/Request"); first.id = "req_same";
        first.headers.add(new ApiRequest.Header("X-Test", "one", false));
        first.headers.add(new ApiRequest.Header("x-test", "two", true));
        first.parameters.add(parameter("query", "q", "one", false));
        first.parameters.add(parameter("path", "id", "42", false));
        first.parameters.add(parameter("path", "skip", "x", true));
        ApiRequest.Auth apiKey = new ApiRequest.Auth(); apiKey.type = "apikey";
        apiKey.properties.put("key", "X-Key"); apiKey.properties.put("value", "secret"); apiKey.properties.put("in", "query");
        burp.utils.AuthInheritanceResolver.markRequestExplicitAuth(first, apiKey);
        first.body.mode = "formdata";
        ApiRequest.Body.FormField file = new ApiRequest.Body.FormField("upload", "retained");
        file.type = "file"; file.fileUpload = true; file.filePath = "/tmp/file.bin"; first.body.formdata.add(file);
        collection.requests.add(first);
        ApiRequest duplicate = requestModel("Duplicate", "Duplicate"); duplicate.id = "req_same"; collection.requests.add(duplicate);

        java.util.List<String> warnings = new ArrayList<>();
        JsonObject root = InsomniaCollectionExporter.build(collection, null, warnings);
        assertThat(root.get("__type").getAsString()).isEqualTo("export");
        assertThat(root.get("__export_format").getAsInt()).isEqualTo(4);
        JsonObject exported = requestResource(root, "Request");
        assertThat(exported.getAsJsonArray("headers")).allSatisfy(element -> {
            assertThat(element.getAsJsonObject().has("name")).isTrue();
            assertThat(element.getAsJsonObject().has("key")).isFalse();
        });
        assertThat(exported.getAsJsonArray("parameters").get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("q");
        assertThat(exported.getAsJsonArray("pathParameters")).singleElement().satisfies(element ->
                assertThat(element.getAsJsonObject().get("name").getAsString()).isEqualTo("id"));
        JsonObject fileRow = exported.getAsJsonObject("body").getAsJsonArray("params").get(0).getAsJsonObject();
        assertThat(fileRow.get("fileName").getAsString()).isEqualTo("/tmp/file.bin");
        assertThat(fileRow.get("value").getAsString()).isEqualTo("retained");
        assertThat(exported.getAsJsonObject("authentication").get("addTo").getAsString()).isEqualTo("queryParams");
        JsonObject group = resource(root, "request_group", "Parent");
        assertThat(group.getAsJsonObject("environment").get("folder").getAsString()).isEqualTo("value");

        Set<String> ids = new HashSet<>(); Set<String> existing = new HashSet<>();
        for (JsonElement element : root.getAsJsonArray("resources")) existing.add(element.getAsJsonObject().get("_id").getAsString());
        for (JsonElement element : root.getAsJsonArray("resources")) {
            JsonObject object = element.getAsJsonObject(); assertThat(ids.add(object.get("_id").getAsString())).isTrue();
            if (object.has("parentId")) assertThat(existing).contains(object.get("parentId").getAsString());
            if ("request".equals(object.get("_type").getAsString()) || "request_group".equals(object.get("_type").getAsString()))
                assertThat(object.has("metaSortKey")).isTrue();
        }
        assertThat(warnings).anyMatch(value -> value.contains("duplicate resource ID"));
        assertThat(warnings).anyMatch(value -> value.contains("disabled") && value.contains("path"));

        java.nio.file.Path exportedFile = tempDir.resolve("official.json");
        java.nio.file.Files.writeString(exportedFile, root.toString());
        ApiCollection imported = new burp.parser.InsomniaParser().parse(exportedFile.toFile());
        ApiRequest importedRequest = imported.requests.stream().filter(r -> "Request".equals(r.name)).findFirst().orElseThrow();
        assertThat(importedRequest.headers).extracting(h -> h.key + ":" + h.value + ":" + h.disabled)
                .containsExactly("X-Test:one:false", "x-test:two:true");
    }

    private static ApiRequest requestModel(String name, String path) { ApiRequest r = new ApiRequest(); r.name=name; r.path=path; r.method="POST"; r.url="https://e.test/{id}"; r.body=new ApiRequest.Body(); r.body.mode="none"; return r; }
    private static ApiRequest.Parameter parameter(String location,String key,String value,boolean disabled){ApiRequest.Parameter p=new ApiRequest.Parameter(location,key,value);p.disabled=disabled;return p;}
    private static JsonObject requestResource(JsonObject root,String name){return resource(root,"request",name);}
    private static JsonObject resource(JsonObject root,String type,String name){for(JsonElement e:root.getAsJsonArray("resources")){JsonObject o=e.getAsJsonObject();if(type.equals(o.get("_type").getAsString())&&name.equals(o.get("name").getAsString()))return o;}throw new AssertionError();}
}
