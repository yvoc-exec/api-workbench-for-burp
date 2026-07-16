package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExportVariableResolutionSafetyTest {
    @Test
    void exportResolverUsesOnlyPersistedEnabledAuthorableLayers() throws Exception {
        ApiCollection collection=new ApiCollection();collection.name="Resolution";
        collection.environment.put("value","environment");
        collection.variables.add(variable("value","collection",true));
        collection.variables.add(variable("disabledOnly","DO_NOT_EXPORT_DISABLED_VALUE",false));
        collection.runtimeVars.put("runtime","DO_NOT_EXPORT_RUNTIME_TOKEN");
        collection.runtimeOAuth2.put("oauth","DO_NOT_EXPORT_RUNTIME_TOKEN");
        collection.runtimeFolderVars.put("Folder",Map.of("folderRuntime","DO_NOT_EXPORT_RUNTIME_TOKEN"));
        collection.folderVars.put("Folder",new java.util.LinkedHashMap<>(Map.of("value","folder")));
        ApiRequest request=request();request.variables.add(variable("value","request",true));
        request.variables.add(variable("requestDisabled","DO_NOT_EXPORT_DISABLED_VALUE",false));
        collection.requests.add(request);
        EnvironmentProfile active=new EnvironmentProfile();active.name="Active";active.variables.put("value","active");
        active.runtimeVariables.put("runtime","DO_NOT_EXPORT_RUNTIME_TOKEN");
        active.oauth2.config.put("access_token","DO_NOT_EXPORT_RUNTIME_TOKEN");

        VariableResolver withoutActive=ExportVariableResolutionService.buildResolver(collection,request,null,Map.of());
        assertThat(withoutActive.resolve("{{value}}/{{disabledOnly}}/{{runtime}}"))
                .isEqualTo("request/{{disabledOnly}}/{{runtime}}");
        VariableResolver activeResolver=ExportVariableResolutionService.buildResolver(collection,request,active,Map.of());
        assertThat(activeResolver.resolve("{{value}}/{{runtime}}" )).isEqualTo("active/{{runtime}}");
        VariableResolver exportOnly=ExportVariableResolutionService.buildResolver(collection,request,active,Map.of("value","export"));
        assertThat(exportOnly.resolve("{{value}}" )).isEqualTo("export");

        CollectionExportOptions options=new CollectionExportOptions(CollectionExportFormat.BRUNO_ZIP,Path.of("unused"),true,active,Map.of());
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();BrunoCollectionExporter.write(collection,options,bytes,new ArrayList<>());
        String exported=new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
        assertThat(exported).doesNotContain("DO_NOT_EXPORT_RUNTIME_TOKEN","DO_NOT_EXPORT_DISABLED_VALUE");
        String insomnia=InsomniaCollectionExporter.build(collection,options,new ArrayList<>()).toString();
        assertThat(insomnia).doesNotContain("DO_NOT_EXPORT_RUNTIME_TOKEN","DO_NOT_EXPORT_DISABLED_VALUE");
    }

    private static ApiRequest request(){ApiRequest r=new ApiRequest();r.name="Request";r.path="Folder/Request";r.method="GET";r.url="https://e.test/{{value}}/{{disabledOnly}}/{{runtime}}";r.body=new ApiRequest.Body();r.body.mode="raw";r.body.raw="{{requestDisabled}}/{{runtime}}";r.headers.add(new ApiRequest.Header("X-Test","{{runtime}}",false));return r;}
    private static ApiRequest.Variable variable(String k,String v,boolean e){ApiRequest.Variable x=new ApiRequest.Variable();x.key=k;x.value=v;x.type="string";x.enabled=e;return x;}
}
