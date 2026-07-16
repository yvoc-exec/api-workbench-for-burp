package burp.exporter;

import burp.models.*;
import burp.parser.InsomniaParser;
import burp.utils.AuthInheritanceResolver;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class InsomniaAuthPropertyCompatibilityTest {
    @TempDir Path tempDir;

    @Test
    void apiKeyAndOauthPropertiesUseCanonicalNamesAndReverseMap() throws Exception {
        ApiCollection collection=new ApiCollection();collection.id="wrk";collection.name="Auth";
        ApiRequest api=request("Api");ApiRequest.Auth key=auth("apikey","key","X-Key","value","secret","in","query","placement","header");AuthInheritanceResolver.markRequestExplicitAuth(api,key);
        ApiRequest oauth=request("OAuth");ApiRequest.Auth oa=auth("oauth2","grantType","authorization_code","authorizationUrl","https://auth","accessTokenUrl","https://token","redirectUri","https://callback","clientId","id","clientSecret","secret","unknown","hidden");AuthInheritanceResolver.markRequestExplicitAuth(oauth,oa);
        collection.requests.addAll(List.of(api,oauth));List<String>warnings=new ArrayList<>();JsonObject root=InsomniaCollectionExporter.build(collection,null,warnings);
        JsonObject apiJson=requestResource(root,"Api").getAsJsonObject("authentication");
        assertThat(apiJson.keySet()).containsExactlyInAnyOrder("type","key","value","addTo");
        assertThat(apiJson.get("addTo").getAsString()).isEqualTo("queryParams");
        JsonObject oauthJson=requestResource(root,"OAuth").getAsJsonObject("authentication");
        assertThat(oauthJson.get("redirectUrl").getAsString()).isEqualTo("https://callback");
        assertThat(oauthJson.has("redirectUri")).isFalse();
        assertThat(warnings).anyMatch(w->w.contains("unknown")&&!w.contains("hidden"));
        Path file=tempDir.resolve("auth.json");Files.writeString(file,root.toString());ApiCollection imported=new InsomniaParser().parse(file.toFile());
        assertThat(imported.requests.stream().filter(r->"OAuth".equals(r.name)).findFirst().orElseThrow().auth.properties)
                .containsEntry("redirectUri","https://callback");
    }

    private static ApiRequest request(String name){ApiRequest r=new ApiRequest();r.name=name;r.path=name;r.method="GET";r.url="https://e.test";r.body=new ApiRequest.Body();r.body.mode="none";return r;}
    private static ApiRequest.Auth auth(String type,String...pairs){ApiRequest.Auth a=new ApiRequest.Auth();a.type=type;for(int i=0;i+1<pairs.length;i+=2)a.properties.put(pairs[i],pairs[i+1]);return a;}
    private static JsonObject requestResource(JsonObject root,String name){for(JsonElement e:root.getAsJsonArray("resources")){JsonObject o=e.getAsJsonObject();if("request".equals(o.get("_type").getAsString())&&name.equals(o.get("name").getAsString()))return o;}throw new AssertionError();}
}
