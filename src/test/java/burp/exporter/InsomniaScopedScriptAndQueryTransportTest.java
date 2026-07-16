package burp.exporter;

import burp.models.*;
import burp.parser.InsomniaParser;
import burp.scripts.*;
import burp.utils.ScriptMode;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class InsomniaScopedScriptAndQueryTransportTest {
    @TempDir Path tempDir;

    @Test
    void folderScriptsAndStructuredQueryUseCanonicalTargetTransport() throws Exception {
        ApiCollection collection=new ApiCollection();collection.id="wrk";collection.name="I";collection.folderPaths.add("F");
        collection.scriptBlocks.add(block("collection",ScriptPhase.PRE_REQUEST,ScriptScope.COLLECTION,0));
        collection.folderScriptBlocks.put("F",new ArrayList<>(List.of(
                block("pre",ScriptPhase.PRE_REQUEST,ScriptScope.FOLDER,1),
                block("post",ScriptPhase.POST_RESPONSE,ScriptScope.FOLDER,2),
                block("test",ScriptPhase.TEST,ScriptScope.FOLDER,3))));
        ApiRequest request=new ApiRequest();request.name="R";request.path="F/R";request.method="GET";
        request.url="https://e.test/a?stale=1#frag";request.body=new ApiRequest.Body();request.body.mode="none";
        request.parameters.add(parameter("q","one",true,false));
        request.parameters.add(parameter("q","two",true,false));
        request.parameters.add(parameter("flag","",false,false));
        request.parameters.add(parameter("disabled","x",true,true));
        request.scriptBlocks.add(block("request",ScriptPhase.PRE_REQUEST,ScriptScope.REQUEST,1));collection.requests.add(request);
        List<String>warnings=new ArrayList<>();JsonObject root=InsomniaCollectionExporter.build(collection,null,warnings);
        JsonObject group=resource(root,"request_group","F");JsonObject exported=resource(root,"request","R");
        assertThat(group.get("preRequestScript").getAsString()).isEqualTo("pre");
        assertThat(group.get("afterResponseScript").getAsString()).isEqualTo("post\n\ntest");
        assertThat(exported.get("url").getAsString()).isEqualTo("https://e.test/a#frag");
        assertThat(exported.getAsJsonArray("parameters")).hasSize(5);
        assertThat(exported.getAsJsonArray("parameters").get(2).getAsJsonObject().get("value").getAsString()).isEmpty();
        assertThat(warnings).anyMatch(w->w.contains("bare query"));
        assertThat(warnings).anyMatch(w->w.contains("collection-level PRE_REQUEST"));

        Path file=tempDir.resolve("i.json");Files.writeString(file,root.toString());ApiCollection imported=new InsomniaParser().parse(file.toFile());
        ApiRequest importedRequest=imported.requests.get(0);
        assertThat(importedRequest.url).isEqualTo("https://e.test/a?q=one&q=two&flag=&stale=1#frag");
        List<ScriptBlock> resolved=new UnifiedScriptRuntime(null, ScriptMode.FULL_JS)
                .resolveBlocks(imported,importedRequest,ScriptPhase.PRE_REQUEST);
        assertThat(resolved).extracting(b->b.source).containsExactly("pre","request");
    }

    @Test
    void structuredEnvironmentTypesRoundTripWhileJsonLookingStringsRemainStrings() throws Exception {
        JsonObject root=new JsonObject();root.addProperty("__type","export");root.addProperty("__export_format",4);JsonArray resources=new JsonArray();
        JsonObject workspace=new JsonObject();workspace.addProperty("_id","wrk");workspace.addProperty("_type","workspace");workspace.addProperty("name","W");resources.add(workspace);
        JsonObject env=new JsonObject();env.addProperty("_id","env");env.addProperty("_type","environment");env.addProperty("parentId","wrk");env.addProperty("name","Base");JsonObject data=new JsonObject();
        data.add("object",JsonParser.parseString("{\"a\":1}"));data.add("array",JsonParser.parseString("[1,2]"));data.addProperty("number",42);data.addProperty("boolean",true);data.add("null",JsonNull.INSTANCE);data.addProperty("looks","{\"a\":1}");env.add("data",data);resources.add(env);root.add("resources",resources);
        Path file=tempDir.resolve("types.json");Files.writeString(file,root.toString());ApiCollection imported=new InsomniaParser().parse(file.toFile());
        JsonObject exported=InsomniaCollectionExporter.build(imported,null,new ArrayList<>());JsonObject values=resource(exported,"environment","W Environment").getAsJsonObject("data");
        assertThat(values.get("object").isJsonObject()).isTrue();assertThat(values.get("array").isJsonArray()).isTrue();
        assertThat(values.get("number").getAsInt()).isEqualTo(42);assertThat(values.get("boolean").getAsBoolean()).isTrue();assertThat(values.get("null").isJsonNull()).isTrue();
        assertThat(values.get("looks").isJsonPrimitive()).isTrue();assertThat(values.get("looks").getAsString()).isEqualTo("{\"a\":1}");
    }

    @Test
    void resolvedStructuredEnvironmentValuesRetainSourceCategoriesOrWarnAsStrings() throws Exception {
        JsonObject root=new JsonObject();root.addProperty("__type","export");root.addProperty("__export_format",4);JsonArray resources=new JsonArray();
        JsonObject workspace=new JsonObject();workspace.addProperty("_id","wrk");workspace.addProperty("_type","workspace");workspace.addProperty("name","W");resources.add(workspace);
        JsonObject env=new JsonObject();env.addProperty("_id","env");env.addProperty("_type","environment");env.addProperty("parentId","wrk");env.addProperty("name","Base");JsonObject data=new JsonObject();
        data.add("object",JsonParser.parseString("{\"v\":\"{{word}}\"}"));data.add("array",JsonParser.parseString("[\"{{word}}\"]"));data.addProperty("number",42);data.addProperty("boolean",true);data.add("null",JsonNull.INSTANCE);data.addProperty("looks","{\"a\":1}");data.add("invalid",JsonParser.parseString("{\"v\":\"{{bad}}\"}"));env.add("data",data);resources.add(env);
        JsonObject folder=new JsonObject();folder.addProperty("_id","fld");folder.addProperty("_type","request_group");folder.addProperty("parentId","wrk");folder.addProperty("name","F");JsonObject folderData=new JsonObject();folderData.add("object",JsonParser.parseString("{\"v\":\"{{word}}\"}"));folder.add("environment",folderData);resources.add(folder);root.add("resources",resources);
        Path file=tempDir.resolve("resolved-types.json");Files.writeString(file,root.toString());ApiCollection imported=new InsomniaParser().parse(file.toFile());
        EnvironmentProfile profile=new EnvironmentProfile();profile.variables.put("word","resolved");profile.variables.put("bad","\"");
        CollectionExportOptions options=new CollectionExportOptions(CollectionExportFormat.INSOMNIA_JSON,tempDir.resolve("out.json"),true,profile,Map.of());
        List<String>warnings=new ArrayList<>();JsonObject exported=InsomniaCollectionExporter.build(imported,options,warnings);
        JsonObject values=resource(exported,"environment","W Environment").getAsJsonObject("data");
        assertThat(values.get("object").isJsonObject()).isTrue();assertThat(values.get("array").isJsonArray()).isTrue();
        assertThat(values.get("number").isJsonPrimitive()).isTrue();assertThat(values.get("number").getAsJsonPrimitive().isNumber()).isTrue();
        assertThat(values.get("boolean").getAsJsonPrimitive().isBoolean()).isTrue();assertThat(values.get("null").isJsonNull()).isTrue();
        assertThat(values.get("looks").getAsString()).isEqualTo("{\"a\":1}");assertThat(values.get("invalid").isJsonPrimitive()).isTrue();
        assertThat(resource(exported,"request_group","F").getAsJsonObject("environment").get("object").isJsonObject()).isTrue();
        assertThat(warnings).anyMatch(w->w.contains("invalid")&&w.contains("no longer matched")&&!w.contains("{{bad}}"));
    }

    @Test
    void editedBaseAndFolderEnvironmentValuesInvalidateRememberedTypesWithAndWithoutResolution() throws Exception {
        for (boolean resolve : List.of(false, true)) {
            JsonObject root=new JsonObject();root.addProperty("__type","export");root.addProperty("__export_format",4);JsonArray resources=new JsonArray();
            JsonObject workspace=new JsonObject();workspace.addProperty("_id","wrk");workspace.addProperty("_type","workspace");workspace.addProperty("name","W");resources.add(workspace);
            JsonObject env=new JsonObject();env.addProperty("_id","env");env.addProperty("_type","environment");env.addProperty("parentId","wrk");env.addProperty("name","Base");JsonObject data=new JsonObject();
            data.add("unchanged",JsonParser.parseString("{\"v\":\"{{word}}\"}"));data.add("same",JsonParser.parseString("{\"old\":1}"));data.add("different",JsonParser.parseString("[1]"));env.add("data",data);resources.add(env);
            JsonObject folder=new JsonObject();folder.addProperty("_id","fld");folder.addProperty("_type","request_group");folder.addProperty("parentId","wrk");folder.addProperty("name","F");JsonObject folderData=new JsonObject();
            folderData.add("unchanged",JsonParser.parseString("[\"{{word}}\"]"));folderData.add("same",JsonParser.parseString("[1]"));folderData.add("different",JsonParser.parseString("true"));folder.add("environment",folderData);resources.add(folder);root.add("resources",resources);
            Path file=tempDir.resolve("edited-types-"+resolve+".json");Files.writeString(file,root.toString());ApiCollection imported=new InsomniaParser().parse(file.toFile());
            imported.environment.put("same","{\"new\":2}");imported.environment.put("different","plain");
            imported.folderVars.get("F").put("same","[2]");imported.folderVars.get("F").put("different","false");
            EnvironmentProfile profile=new EnvironmentProfile();profile.variables.put("word","resolved");
            CollectionExportOptions options=new CollectionExportOptions(CollectionExportFormat.INSOMNIA_JSON,tempDir.resolve("edited-out-"+resolve+".json"),resolve,profile,Map.of());
            List<String>warnings=new ArrayList<>();JsonObject exported=InsomniaCollectionExporter.build(imported,options,warnings);
            JsonObject baseValues=resource(exported,"environment","W Environment").getAsJsonObject("data");
            JsonObject folderValues=resource(exported,"request_group","F").getAsJsonObject("environment");
            assertThat(baseValues.get("unchanged").isJsonObject()).isTrue();assertThat(folderValues.get("unchanged").isJsonArray()).isTrue();
            assertThat(baseValues.get("same").getAsString()).isEqualTo("{\"new\":2}");assertThat(baseValues.get("different").getAsString()).isEqualTo("plain");
            assertThat(folderValues.get("same").getAsString()).isEqualTo("[2]");assertThat(folderValues.get("different").getAsString()).isEqualTo("false");
            assertThat(warnings.stream().filter(w->w.contains("type provenance was invalidated")).count()).isEqualTo(4);
            assertThat(warnings).allSatisfy(w->assertThat(w).doesNotContain("{\"new\":2}","plain","[2]","false"));
        }
    }

    private static ApiRequest.Parameter parameter(String key,String value,boolean present,boolean disabled){ApiRequest.Parameter p=new ApiRequest.Parameter("query",key,value);p.valuePresent=present;p.disabled=disabled;return p;}
    private static ScriptBlock block(String source,ScriptPhase phase,ScriptScope scope,int order){ScriptBlock b=ScriptBlock.of(source,ScriptDialect.INSOMNIA,phase,scope);b.order=order;return b;}
    private static JsonObject resource(JsonObject root,String type,String name){for(JsonElement e:root.getAsJsonArray("resources")){JsonObject o=e.getAsJsonObject();if(type.equals(o.get("_type").getAsString())&&name.equals(o.get("name").getAsString()))return o;}throw new AssertionError();}
}
