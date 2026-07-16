package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.InsomniaParser;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.utils.AuthInheritanceResolver;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InsomniaCollectionRoundTripTest {
    @TempDir Path tempDir;

    @Test
    void supportedCollectionRoundTripsThroughInsomnia() throws Exception {
        ApiCollection source = sourceCollection();
        JsonObject json = InsomniaCollectionExporter.build(source, null, new ArrayList<>());
        ApiCollection imported = parse(json);
        assertThat(imported.name).isEqualTo("Insomnia Round Trip");
        assertThat(imported.description).isEqualTo("Description");
        assertThat(imported.environment).containsEntry("base", "value");
        assertThat(imported.folderPaths).contains("Folder");

        ApiRequest request = imported.requests.stream().filter(r -> "Request".equals(r.name)).findFirst().orElseThrow();
        assertThat(request.path).isEqualTo("Folder/Request");
        assertThat(request.parameters).extracting(p -> p.key)
                .containsExactly("tag", "tag", "flag", "empty", "", " ", "disabled");
        assertThat(request.parameters).extracting(p -> p.valuePresent)
                .containsExactly(true, true, false, true, true, true, false);
        assertThat(request.parameters.get(6).disabled).isTrue();
        assertThat(request.url).isEqualTo("https://e.test/a?tag=one&tag=two&flag&empty=&=x&%20=space");
        assertThat(request.body.contentType).isEqualTo("application/vnd.api+json");
        assertThat(request.body.raw).isEqualTo("{\"ok\":true}");
        assertThat(request.authOverrideMode).isEqualTo("explicit");
        assertThat(request.scriptBlocks).extracting(b -> b.phase)
                .containsExactly(ScriptPhase.PRE_REQUEST, ScriptPhase.POST_RESPONSE);
        assertThat(request.scriptBlocks).allSatisfy(b -> assertThat(b.dialect).isEqualTo(ScriptDialect.INSOMNIA));

        ApiRequest upload = imported.requests.stream().filter(r -> "Upload".equals(r.name)).findFirst().orElseThrow();
        assertThat(upload.body.formdata.get(0).fileUpload).isTrue();
        assertThat(upload.body.formdata.get(0).filePath).isEqualTo("/tmp/a.bin");
        assertThat(upload.body.formdata.get(1).disabled).isTrue();
        assertThat(imported.requests.stream().filter(r -> "Public".equals(r.name)).findFirst().orElseThrow()
                .authOverrideMode).isEqualTo("none");
    }

    @Test
    void insomniaExporterWritesStandardRootParametersFilesAndScripts() {
        JsonObject root = InsomniaCollectionExporter.build(sourceCollection(), null, new ArrayList<>());
        assertThat(root.get("__type").getAsString()).isEqualTo("export");
        assertThat(root.get("__export_format").getAsInt()).isEqualTo(4);
        JsonObject request = resource(root, "request", "Request");
        assertThat(request.getAsJsonArray("parameters")).hasSize(7);
        assertThat(request.get("preRequestScript").getAsString()).contains("pre();");
        assertThat(request.get("afterResponseScript").getAsString()).contains("post();");
        JsonObject upload = resource(root, "request", "Upload");
        assertThat(upload.getAsJsonObject("body").getAsJsonArray("params").get(0).getAsJsonObject()
                .get("fileName").getAsString()).isEqualTo("/tmp/a.bin");
    }

    @Test
    void disabledInsomniaScriptWarnsAndIsNotExportedAsActiveCode() {
        ApiCollection collection = sourceCollection();
        ApiRequest request = collection.requests.get(0);
        ScriptBlock disabled = script("secretDisabled();", ScriptPhase.PRE_REQUEST, 2);
        disabled.enabled = false;
        request.scriptBlocks.add(disabled);
        List<String> warnings = new ArrayList<>();
        JsonObject root = InsomniaCollectionExporter.build(collection, null, warnings);
        assertThat(resource(root, "request", "Request").get("preRequestScript").getAsString())
                .doesNotContain("secretDisabled");
        assertThat(warnings).anyMatch(w -> w.contains("disabled PRE_REQUEST") && w.contains("Request"));
    }

    private ApiCollection sourceCollection() {
        ApiCollection collection = new ApiCollection();
        collection.id = "workspace-id";
        collection.name = "Insomnia Round Trip";
        collection.description = "Description";
        collection.environment.put("base", "value");
        collection.folderPaths.add("Folder");

        ApiRequest request = request("Request", "Folder/Request", "https://e.test/a?stale=x", "raw");
        request.body.raw = "{\"ok\":true}";
        request.body.contentType = "application/vnd.api+json";
        request.parameters.add(parameter("tag", "one", true, false));
        request.parameters.add(parameter("tag", "two", true, false));
        request.parameters.add(parameter("flag", "", false, false));
        request.parameters.add(parameter("empty", "", true, false));
        request.parameters.add(parameter("", "x", true, false));
        request.parameters.add(parameter(" ", "space", true, false));
        request.parameters.add(parameter("disabled", "", false, true));
        ApiRequest.Auth bearer = new ApiRequest.Auth();
        bearer.type = "bearer";
        bearer.properties.put("token", "token");
        AuthInheritanceResolver.markRequestExplicitAuth(request, bearer);
        request.scriptBlocks.add(script("pre();", ScriptPhase.PRE_REQUEST, 0));
        request.scriptBlocks.add(script("post();", ScriptPhase.POST_RESPONSE, 1));

        ApiRequest upload = request("Upload", "Upload", "https://e.test/upload", "formdata");
        upload.body.contentType = "multipart/form-data";
        upload.body.formdata.add(field("file", "original", false, true, "/tmp/a.bin"));
        upload.body.formdata.add(field("text", "value", true, false, null));
        ApiRequest publicRequest = request("Public", "Public", "https://e.test/public", "none");
        AuthInheritanceResolver.markRequestNoAuth(publicRequest);
        collection.requests.addAll(List.of(request, upload, publicRequest));
        return collection;
    }

    private static ApiRequest request(String name, String path, String url, String mode) {
        ApiRequest request = new ApiRequest();
        request.name = name; request.path = path; request.method = "POST"; request.url = url;
        request.body = new ApiRequest.Body(); request.body.mode = mode;
        return request;
    }

    private static ApiRequest.Parameter parameter(String key, String value, boolean present, boolean disabled) {
        ApiRequest.Parameter p = new ApiRequest.Parameter("query", key, value);
        p.valuePresent = present; p.disabled = disabled; p.description = "description"; p.type = "text";
        return p;
    }

    private static ApiRequest.Body.FormField field(String key, String value, boolean disabled,
                                                   boolean file, String path) {
        ApiRequest.Body.FormField f = new ApiRequest.Body.FormField(key, value);
        f.disabled = disabled; f.fileUpload = file; f.type = file ? "file" : "text"; f.filePath = path;
        return f;
    }

    private static ScriptBlock script(String source, ScriptPhase phase, int order) {
        ScriptBlock block = ScriptBlock.of(source, ScriptDialect.INSOMNIA, phase, ScriptScope.REQUEST);
        block.order = order;
        return block;
    }

    private ApiCollection parse(JsonObject json) throws Exception {
        Path file = tempDir.resolve("insomnia.json");
        Files.writeString(file, new GsonBuilder().disableHtmlEscaping().create().toJson(json), StandardCharsets.UTF_8);
        return new InsomniaParser().parse(file.toFile());
    }

    private static JsonObject resource(JsonObject root, String type, String name) {
        for (var element : root.getAsJsonArray("resources")) {
            JsonObject object = element.getAsJsonObject();
            if (type.equals(object.get("_type").getAsString()) && name.equals(object.get("name").getAsString())) return object;
        }
        throw new AssertionError("Missing resource " + type + " " + name);
    }
}
