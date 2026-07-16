package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.InsomniaParser;
import burp.utils.AuthInheritanceResolver;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InsomniaHierarchyAndAuthRoundTripTest {
    @TempDir Path tempDir;

    @Test
    void hierarchyFolderEnvironmentAuthPathParametersAndWarningsRoundTrip() throws Exception {
        ApiCollection source=new ApiCollection();source.id="wrk_hierarchy";source.name="Hierarchy";
        source.folderPaths.addAll(List.of("Parent","Parent/Child","Empty"));
        source.folderVars.put("Parent",new java.util.LinkedHashMap<>(java.util.Map.of("value","parent")));
        source.folderVars.put("Parent/Child",new java.util.LinkedHashMap<>(java.util.Map.of("value","child")));
        AuthInheritanceResolver.setCollectionAuth(source,auth("bearer","token","collection-token"));
        AuthInheritanceResolver.setFolderAuth(source,"Parent","explicit",auth("apikey","key","X-Key","value","folder-key","in","query"));
        ApiRequest root=request("Root","Root");AuthInheritanceResolver.markRequestInherit(root);
        ApiRequest child=request("Child","Parent/Child/Child");AuthInheritanceResolver.markRequestInherit(child);
        child.parameters.add(new ApiRequest.Parameter("path","id","42"));
        child.variables.add(variable("requestOnly","secret-definition"));
        ApiRequest none=request("None","Parent/None");AuthInheritanceResolver.markRequestNoAuth(none);
        source.requests.addAll(List.of(root,child,none));
        List<String>warnings=new ArrayList<>();JsonObject json=InsomniaCollectionExporter.build(source,null,warnings);
        ApiCollection imported=parse(json);

        assertThat(imported.requests).extracting(r -> r.name).containsExactly("Root", "Child", "None");
        assertThat(imported.folderPaths).contains("Parent","Parent/Child","Empty");
        assertThat(imported.folderVars.get("Parent")).containsEntry("value","parent");
        assertThat(imported.folderVars.get("Parent/Child")).containsEntry("value","child");
        ApiRequest importedChild=byName(imported,"Child");
        assertThat(importedChild.parameters).filteredOn(p->"path".equals(p.location)).singleElement().satisfies(p->assertThat(p.value).isEqualTo("42"));
        assertThat(importedChild.auth.properties).containsEntry("in","query");
        assertThat(byName(imported,"None").authOverrideMode).isEqualTo("none");
        assertThat(byName(imported,"Root").auth.type).isEqualTo("bearer");
        assertThat(importedChild.authOverrideMode).isEqualTo("inherit");
        assertThat(warnings).anyMatch(value->value.contains("collection authentication"));
        assertThat(warnings).anyMatch(value->value.contains("request variables")&&!value.contains("secret-definition"));
    }

    @Test
    void disabledAuthFailsClosedAndUnsupportedFolderFeaturesWarnWithoutSource() throws Exception {
        JsonObject root=new JsonObject();root.addProperty("__type","export");root.addProperty("__export_format",4);JsonArray resources=new JsonArray();
        JsonObject workspace=new JsonObject();workspace.addProperty("_id","wrk");workspace.addProperty("_type","workspace");workspace.addProperty("name","W");resources.add(workspace);
        JsonObject folder=new JsonObject();folder.addProperty("_id","fld");folder.addProperty("_type","request_group");folder.addProperty("parentId","wrk");folder.addProperty("name","Folder");
        folder.addProperty("preRequestScript","DO_NOT_EXPORT_SCRIPT_SECRET");JsonArray headers=new JsonArray();JsonObject h=new JsonObject();h.addProperty("name","X");h.addProperty("value","secret");headers.add(h);folder.add("headers",headers);
        JsonObject folderAuth=new JsonObject();folderAuth.addProperty("type","bearer");folderAuth.addProperty("token","secret");folderAuth.addProperty("disabled",true);folder.add("authentication",folderAuth);resources.add(folder);
        JsonObject request=new JsonObject();request.addProperty("_id","req");request.addProperty("_type","request");request.addProperty("parentId","fld");request.addProperty("name","R");request.addProperty("method","GET");request.addProperty("url","https://e.test");
        JsonArray requestHeaders=new JsonArray();JsonObject legacyHeader=new JsonObject();legacyHeader.addProperty("key","X-Legacy");legacyHeader.addProperty("value","yes");requestHeaders.add(legacyHeader);request.add("headers",requestHeaders);resources.add(request);root.add("resources",resources);
        ApiCollection imported=parse(root);
        assertThat(imported.folderAuthModes).containsEntry("Folder","none");
        assertThat(imported.requests.get(0).headers).singleElement().satisfies(header ->
                assertThat(header.key).isEqualTo("X-Legacy"));
        assertThat(imported.importWarnings).anyMatch(value->value.contains("Folder scripts")&&!value.contains("DO_NOT_EXPORT_SCRIPT_SECRET"));
        assertThat(imported.importWarnings).anyMatch(value->value.contains("Folder headers")&&!value.contains("secret"));
    }

    private ApiCollection parse(JsonObject json)throws Exception{Path p=tempDir.resolve("insomnia-"+System.nanoTime()+".json");Files.writeString(p,new Gson().toJson(json),StandardCharsets.UTF_8);return new InsomniaParser().parse(p.toFile());}
    private static ApiRequest request(String n,String p){ApiRequest r=new ApiRequest();r.name=n;r.path=p;r.method="GET";r.url="https://e.test/{id}";r.body=new ApiRequest.Body();r.body.mode="none";return r;}
    private static ApiRequest.Auth auth(String t,String...pairs){ApiRequest.Auth a=new ApiRequest.Auth();a.type=t;for(int i=0;i+1<pairs.length;i+=2)a.properties.put(pairs[i],pairs[i+1]);return a;}
    private static ApiRequest.Variable variable(String k,String v){ApiRequest.Variable x=new ApiRequest.Variable();x.key=k;x.value=v;x.enabled=true;x.type="string";return x;}
    private static ApiRequest byName(ApiCollection c,String n){return c.requests.stream().filter(r->n.equals(r.name)).findFirst().orElseThrow();}
}
