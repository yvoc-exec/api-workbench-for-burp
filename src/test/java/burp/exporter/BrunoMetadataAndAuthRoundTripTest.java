package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.BrunoParser;
import burp.utils.AuthInheritanceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrunoMetadataAndAuthRoundTripTest {
    @TempDir Path tempDir;

    @Test
    void collectionFolderAndRequestAuthAndVariablesRoundTripCanonically() throws Exception {
        ApiCollection collection = new ApiCollection(); collection.name="Auth"; collection.folderPaths.add("Admin");
        collection.variables.add(variable("enabled","one","string",true));
        collection.variables.add(variable("disabled","two","boolean",false));
        collection.variables.add(variable("enabled","three","string",true));
        collection.folderVars.put("Admin", new java.util.LinkedHashMap<>(java.util.Map.of("folder", "value")));
        ApiRequest.Auth collectionAuth=auth("apikey","key","X-Key","value","secret","in","query");
        AuthInheritanceResolver.setCollectionAuth(collection,collectionAuth);
        AuthInheritanceResolver.setFolderAuth(collection,"Admin","none",auth("none"));
        ApiRequest inherited=request("Root","Root"); AuthInheritanceResolver.markRequestInherit(inherited);
        ApiRequest explicit=request("Explicit","Admin/Explicit");
        ApiRequest.Auth oauth=auth("oauth2","grantType","authorization_code","accessTokenUrl","https://token",
                "authorizationUrl","https://auth","redirectUri","https://callback","clientId","id","clientSecret","secret");
        AuthInheritanceResolver.markRequestExplicitAuth(explicit,oauth);
        ApiRequest none=request("None","None");AuthInheritanceResolver.markRequestNoAuth(none);
        collection.requests.addAll(List.of(inherited,explicit,none));

        List<String> warnings=new ArrayList<>();Path zip=export(collection,warnings);
        ApiCollection imported=new BrunoParser().parse(zip.toFile());
        assertThat(imported.variables).extracting(v->v.key).containsExactly("enabled","disabled","enabled");
        assertThat(imported.variables).extracting(v->v.enabled).containsExactly(true,false,true);
        assertThat(imported.variables).extracting(v->v.type).containsExactly("string","boolean","string");
        assertThat(imported.auth.type).isEqualTo("apikey");
        assertThat(imported.auth.properties).containsEntry("in","query");
        assertThat(imported.folderAuthModes).containsEntry("Admin","none");
        ApiRequest importedExplicit=imported.requests.stream().filter(r->"Explicit".equals(r.name)).findFirst().orElseThrow();
        assertThat(importedExplicit.explicitAuth.properties).containsEntry("redirectUri","https://callback")
                .containsEntry("clientSecret","secret");
        assertThat(imported.requests.stream().filter(r->"None".equals(r.name)).findFirst().orElseThrow().authOverrideMode)
                .isEqualTo("none");
    }

    @Test
    void unsupportedAuthFailsClosedAndWarnsWithoutValues() throws Exception {
        ApiCollection collection=new ApiCollection();collection.name="Unsupported";ApiRequest request=request("Request","Request");
        ApiRequest.Auth auth=auth("digest","password","DO_NOT_DISCLOSE");AuthInheritanceResolver.markRequestExplicitAuth(request,auth);
        collection.requests.add(request);List<String>warnings=new ArrayList<>();Path zip=export(collection,warnings);
        ApiRequest imported=new BrunoParser().parse(zip.toFile()).requests.get(0);
        assertThat(imported.authOverrideMode).isEqualTo("none");
        assertThat(warnings).singleElement().asString().contains("digest","none").doesNotContain("DO_NOT_DISCLOSE");
    }

    @Test
    void legacyMetadataVariablesAuthCustomMethodAndQuotedValuesImportButReexportCanonically() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("legacy"));
        Files.writeString(root.resolve("_collection.bru"), """
                vars {
                  token: "literal\\nvalue"
                }
                auth {
                  mode: bearer
                  token: legacy-token
                }
                """, StandardCharsets.UTF_8);
        Path folder = Files.createDirectories(root.resolve("Folder"));
        Files.writeString(folder.resolve("_folder.bru"), "vars { folder: yes }", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("Legacy.bru"), """
                meta {
                  name: Legacy
                  type: http
                }
                PROPFIND {
                  url: "https://e.test/legacy"
                }
                """, StandardCharsets.UTF_8);

        ApiCollection imported = new BrunoParser().parse(root.toFile());
        assertThat(imported.variables).singleElement().satisfies(variable ->
                assertThat(variable.value).isEqualTo("literal\nvalue"));
        assertThat(imported.folderVars.get("Folder")).containsEntry("folder", "yes");
        assertThat(imported.auth.type).isEqualTo("bearer");
        assertThat(imported.requests).singleElement().satisfies(request -> {
            assertThat(request.method).isEqualTo("PROPFIND");
            assertThat(request.url).isEqualTo("https://e.test/legacy");
        });
        assertThat(imported.importWarnings).anyMatch(warning -> warning.contains("Legacy quoted"));

        Path canonical = export(imported, new ArrayList<>());
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                Files.newInputStream(canonical), StandardCharsets.UTF_8)) {
            java.util.List<String> names = new ArrayList<>();
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) names.add(entry.getName());
            assertThat(names).anyMatch(name -> name.endsWith("/collection.bru"));
            assertThat(names).anyMatch(name -> name.endsWith("/folder.bru"));
            assertThat(names).noneMatch(name -> name.endsWith("/_collection.bru") || name.endsWith("/_folder.bru"));
        }
    }

    private Path export(ApiCollection c,List<String>w)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();BrunoCollectionExporter.write(c,null,out,w);Path p=tempDir.resolve("auth-"+System.nanoTime()+".zip");Files.write(p,out.toByteArray());return p;}
    private static ApiRequest request(String name,String path){ApiRequest r=new ApiRequest();r.name=name;r.path=path;r.method="GET";r.url="https://e.test";r.body=new ApiRequest.Body();r.body.mode="none";return r;}
    private static ApiRequest.Variable variable(String k,String v,String t,boolean e){ApiRequest.Variable x=new ApiRequest.Variable();x.key=k;x.value=v;x.type=t;x.enabled=e;return x;}
    private static ApiRequest.Auth auth(String type,String...pairs){ApiRequest.Auth a=new ApiRequest.Auth();a.type=type;for(int i=0;i+1<pairs.length;i+=2)a.properties.put(pairs[i],pairs[i+1]);return a;}
}
