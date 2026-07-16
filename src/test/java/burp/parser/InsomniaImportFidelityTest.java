package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InsomniaImportFidelityTest {
    @TempDir Path tempDir;

    @Test
    void workspaceNameFolderTypesAndRootPathsArePreserved() throws Exception {
        ApiCollection collection = parse("""
                {"__type":"export","__export_format":5,"resources":[
                  {"_type":"workspace","_id":"wrk","name":"Workspace","description":"Description"},
                  {"_type":"folder","_id":"one","parentId":"wrk","name":"One"},
                  {"_type":"request_group","_id":"two","parentId":"one","name":"Two"},
                  {"_type":"folder","_id":"empty","parentId":"wrk","name":"Empty"},
                  {"_type":"request","_id":"root","parentId":"wrk","name":"Root","url":"https://e.test/root"},
                  {"_type":"request","_id":"nested","parentId":"two","name":"Nested","url":"https://e.test/nested"}
                ]}
                """);
        assertThat(collection.id).isEqualTo("wrk");
        assertThat(collection.name).isEqualTo("Workspace");
        assertThat(collection.description).isEqualTo("Description");
        assertThat(collection.version).isEqualTo("5");
        assertThat(collection.folderPaths).contains("One", "One/Two", "Empty");
        assertThat(collection.requests).extracting(r -> r.path).containsExactly("Root", "One/Two/Nested");
    }

    @Test
    void importsStructuredQueryParametersAndReconcilesRawEncoding() throws Exception {
        ApiRequest request = onlyRequest("""
                {"url":"https://e.test/a?encoded=a%2Fb&raw=one&flag&empty=&tag=one&tag=two",
                 "parameters":[
                   {"name":"encoded","value":"a/b","description":"encoded","type":"text"},
                   {"name":"disabled","value":"hidden","disabled":true},
                   {"name":"flag"},{"name":"empty","value":""},
                   {"name":"tag","value":"one"},{"name":"tag","value":"two"},
                   {"name":"","value":"x"},{"name":" ","value":"space"}
                 ]}
                """);
        assertThat(request.parameters).hasSize(9);
        assertThat(request.parameters.get(0).rawValue).isEqualTo("a%2Fb");
        assertThat(request.parameters.get(1).disabled).isTrue();
        assertThat(request.parameters.get(2).valuePresent).isFalse();
        assertThat(request.parameters.get(3).valuePresent).isTrue();
        assertThat(request.parameters.get(8).key).isEqualTo("raw");
        assertThat(request.url).isEqualTo("https://e.test/a?encoded=a%2Fb&flag&empty=&tag=one&tag=two&=x&%20=space&raw=one");
    }

    @Test
    void disabledStructuredQueryDoesNotReactivateRawOccurrence() throws Exception {
        ApiRequest request = onlyRequest("""
                {"url":"https://e.test/a?skip=x","parameters":[{"name":"skip","value":"x","disabled":true}]}
                """);
        assertThat(request.parameters).singleElement().satisfies(p -> assertThat(p.disabled).isTrue());
        assertThat(request.url).isEqualTo("https://e.test/a");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/vnd.api+json", "application/problem+json", "application/graphql", "text/csv"})
    void customRawMimeTypesRemainRaw(String mimeType) throws Exception {
        ApiRequest request = onlyRequest("{\"url\":\"https://e.test/a\",\"body\":{\"mimeType\":\""
                + mimeType + "\",\"text\":\"payload\"}}");
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.contentType).isEqualTo(mimeType);
        assertThat(request.body.raw).isEqualTo("payload");
    }

    @Test
    void multipartFileAliasesPreserveFileMetadata() throws Exception {
        ApiRequest request = onlyRequest("""
                {"url":"https://e.test/a","body":{"mimeType":"multipart/form-data","params":[
                  {"name":"a","value":"original-a","type":"file","fileName":"a.bin"},
                  {"name":"b","value":"original-b","filePath":"b.bin"},
                  {"name":"c","value":"original-c","src":"c.bin","disabled":true},
                  {"name":"d","value":"d.bin","type":"file"},
                  {"name":"text","value":"file word only"}
                ]}}
                """);
        assertThat(request.body.formdata).extracting(f -> f.filePath)
                .containsExactly("a.bin", "b.bin", "c.bin", "d.bin", null);
        assertThat(request.body.formdata).extracting(f -> f.value)
                .containsExactly("original-a", "original-b", "original-c", "d.bin", "file word only");
        assertThat(request.body.formdata.get(2).disabled).isTrue();
        assertThat(request.body.formdata.get(4).fileUpload).isFalse();
    }

    @Test
    void emptyAuthenticationObjectInheritsFolderAuth() throws Exception {
        ApiCollection collection = parse("""
                {"__type":"export","resources":[
                  {"_type":"workspace","_id":"wrk","name":"W"},
                  {"_type":"folder","_id":"f","parentId":"wrk","name":"F","authentication":{"type":"bearer","token":"t"}},
                  {"_type":"request","_id":"r","parentId":"f","name":"R","url":"https://e.test","authentication":{}}
                ]}
                """);
        assertThat(collection.requests.get(0).authInherited).isTrue();
        assertThat(collection.requests.get(0).auth.type).isEqualTo("bearer");
    }

    @Test
    void unsupportedAuthenticationIsPreservedAndWarned() throws Exception {
        ApiCollection collection = parseRequestResource("""
                {"url":"https://e.test","authentication":{"type":"digest","username":"u","options":{"qop":"auth"}}}
                """);
        ApiRequest request = collection.requests.get(0);
        assertThat(request.explicitAuth.type).isEqualTo("digest");
        assertThat(request.explicitAuth.properties).containsEntry("username", "u")
                .containsEntry("options", "{\"qop\":\"auth\"}");
        assertThat(collection.importWarnings).anyMatch(w -> w.contains("digest"));
    }

    @Test
    void baseEnvironmentImportsWithoutChildEnvironmentOverride() throws Exception {
        ApiCollection collection = parse("""
                {"__type":"export","resources":[
                  {"_type":"workspace","_id":"wrk","name":"W"},
                  {"_type":"environment","_id":"base","parentId":"wrk","name":"Base","data":{"token":"base","nested":{"a":1}}},
                  {"_type":"environment","_id":"child","parentId":"base","name":"Child","data":{"token":"secret-child"}}
                ]}
                """);
        assertThat(collection.environment).containsEntry("token", "base").containsEntry("nested", "{\"a\":1}");
        assertThat(collection.importWarnings).anySatisfy(w -> {
            assertThat(w).contains("Child", "base environment only");
            assertThat(w).doesNotContain("secret-child");
        });
    }

    @Test
    void scriptShapesPreserveOrderSourcePathMetadataAndEnabledState() throws Exception {
        ApiRequest request = onlyRequest("""
                {"url":"https://e.test",
                 "preRequestScript":"one();",
                 "requestHooks":["two();",{"code":"three();","name":"Three","id":"s3","disabled":true}],
                 "afterResponseScript":{"source":"four();"},
                 "scripts":{"after":[{"text":"five();","enabled":true}]}}
                """);
        assertThat(request.scriptBlocks).hasSize(5);
        assertThat(request.scriptBlocks).extracting(b -> b.order).containsExactly(0, 1, 2, 3, 4);
        assertThat(request.scriptBlocks).extracting(b -> b.phase)
                .containsExactly(ScriptPhase.PRE_REQUEST, ScriptPhase.PRE_REQUEST, ScriptPhase.PRE_REQUEST,
                        ScriptPhase.POST_RESPONSE, ScriptPhase.POST_RESPONSE);
        assertThat(request.scriptBlocks).allSatisfy(b -> assertThat(b.dialect).isEqualTo(ScriptDialect.INSOMNIA));
        assertThat(request.scriptBlocks.get(2).enabled).isFalse();
        assertThat(request.scriptBlocks.get(2).metadata).containsEntry("name", "Three").containsEntry("id", "s3");
        assertThat(request.preRequestScripts).hasSize(2);
        assertThat(request.postResponseScripts).hasSize(2);
    }

    @Test
    void unknownScriptMetadataIsNotExecutedAsSource() throws Exception {
        ApiCollection collection = parseRequestResource("""
                {"url":"https://e.test","scripts":{"pre":{"name":"metadata-only","description":"not javascript"}}}
                """);
        assertThat(collection.requests.get(0).scriptBlocks).isEmpty();
        assertThat(collection.importWarnings).anyMatch(w -> w.contains("no source was recovered"));
    }

    @Test
    void malformedRequestResourceDoesNotAbortLaterRequests() throws Exception {
        ApiCollection collection = parse("""
                {"__type":"export","resources":[
                  {"_type":"request","_id":"bad","name":"Bad","url":{"invalid":true}},
                  {"_type":"request","_id":"good","name":"Good","url":"https://e.test/good"}
                ]}
                """);
        assertThat(collection.requests).extracting(r -> r.name).containsExactly("Good");
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(1);
        assertThat(collection.importWarnings).anyMatch(w -> w.contains("Bad"));
    }

    private ApiRequest onlyRequest(String body) throws Exception {
        return parseRequestResource(body).requests.get(0);
    }

    private ApiCollection parseRequestResource(String body) throws Exception {
        return parse("{\"__type\":\"export\",\"resources\":[{\"_type\":\"request\",\"_id\":\"r\",\"name\":\"R\",\"method\":\"GET\"," + body.substring(1) + "]}");
    }

    private ApiCollection parse(String json) throws Exception {
        Path file = tempDir.resolve("insomnia-" + System.nanoTime() + ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return new InsomniaParser().parse(file.toFile());
    }
}
