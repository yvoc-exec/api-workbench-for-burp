package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.BrunoParser;
import burp.scripts.*;
import burp.utils.AuthInheritanceResolver;
import burp.utils.ScriptMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static org.assertj.core.api.Assertions.assertThat;

class BrunoScopedScriptCompatibilityTest {
    @TempDir Path tempDir;

    @Test
    void scopedScriptsAndMetadataAuthSelectorsRoundTripInRuntimeOrder() throws Exception {
        ApiCollection collection = new ApiCollection(); collection.name = "Scoped";
        collection.folderPaths.addAll(List.of("Parent", "Parent/Child"));
        collection.scriptBlocks.add(block("collection", ScriptPhase.PRE_REQUEST, ScriptScope.COLLECTION, 1));
        collection.folderScriptBlocks.put("Parent", new ArrayList<>(List.of(
                block("parent", ScriptPhase.PRE_REQUEST, ScriptScope.FOLDER, 1))));
        collection.folderScriptBlocks.put("Parent/Child", new ArrayList<>(List.of(
                block("child", ScriptPhase.PRE_REQUEST, ScriptScope.FOLDER, 1),
                block("child-post", ScriptPhase.POST_RESPONSE, ScriptScope.FOLDER, 2))));
        AuthInheritanceResolver.setCollectionAuth(collection, auth("bearer", "token", "{{token}}"));
        AuthInheritanceResolver.setFolderAuth(collection, "Parent", "explicit",
                auth("apikey", "key", "X-Key", "value", "v", "in", "header"));
        AuthInheritanceResolver.setFolderAuth(collection, "Parent/Child", "none", null);
        ApiRequest request = request(); request.scriptBlocks.add(
                block("request", ScriptPhase.PRE_REQUEST, ScriptScope.REQUEST, 1));
        collection.requests.add(request);

        Path zip = export(collection); Map<String,String> entries = entries(zip);
        assertThat(entries.get("Scoped/collection.bru")).contains("auth {\n  mode: bearer", "auth:bearer {", "script:pre-request {");
        assertThat(entries.get("Scoped/Parent/folder.bru")).contains("auth {\n  mode: apikey", "auth:apikey {", "script:pre-request {");
        assertThat(entries.get("Scoped/Parent/Child/folder.bru")).contains("auth {\n  mode: none").doesNotContain("auth:none");

        ApiCollection imported = new BrunoParser().parse(zip.toFile());
        ApiRequest importedRequest = imported.requests.get(0);
        List<ScriptBlock> resolved = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS)
                .resolveBlocks(imported, importedRequest, ScriptPhase.PRE_REQUEST);
        assertThat(resolved).extracting(block -> block.source)
                .containsExactly("collection", "parent", "child", "request");
        assertThat(imported.folderAuthModes).containsEntry("Parent/Child", "none");
    }

    private Path export(ApiCollection collection) throws Exception { ByteArrayOutputStream out=new ByteArrayOutputStream();BrunoCollectionExporter.write(collection,null,out,new ArrayList<>());Path p=tempDir.resolve("scoped.zip");Files.write(p,out.toByteArray());return p; }
    private static Map<String,String> entries(Path path)throws Exception{Map<String,String> out=new LinkedHashMap<>();try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(path),StandardCharsets.UTF_8)){ZipEntry e;while((e=zip.getNextEntry())!=null)out.put(e.getName(),new String(zip.readAllBytes(),StandardCharsets.UTF_8));}return out;}
    private static ScriptBlock block(String source, ScriptPhase phase, ScriptScope scope, int order){ScriptBlock b=ScriptBlock.of(source, ScriptDialect.BRUNO,phase,scope);b.order=order;return b;}
    private static ApiRequest.Auth auth(String type,String...pairs){ApiRequest.Auth a=new ApiRequest.Auth();a.type=type;for(int i=0;i+1<pairs.length;i+=2)a.properties.put(pairs[i],pairs[i+1]);return a;}
    private static ApiRequest request(){ApiRequest r=new ApiRequest();r.name="R";r.path="Parent/Child/R";r.method="GET";r.url="https://e.test";r.body=new ApiRequest.Body();r.body.mode="none";AuthInheritanceResolver.markRequestInherit(r);return r;}
}
