package burp.exporter;

import burp.models.*;
import burp.scripts.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

import static org.assertj.core.api.Assertions.assertThat;

class ExportResolutionDiagnosticsTest {
    @Test
    void diagnosticsAndDecompressedArtifactsUseOnlyExportableVariableLayers() throws Exception {
        ApiCollection collection=new ApiCollection();collection.name="Diagnostics";
        collection.variables.add(variable("disabled","DO_NOT_EXPORT_DISABLED_VALUE",false));
        collection.runtimeVars.put("runtime","DO_NOT_EXPORT_RUNTIME_TOKEN");
        collection.runtimeVars.put("scriptSecret","DO_NOT_EXPORT_SCRIPT_SECRET");
        collection.runtimeOAuth2.put("mapped","DO_NOT_EXPORT_AUTH_MAPPED_SECRET");
        collection.scriptBlocks.add(block("{{disabled}}/{{runtime}}/{{scriptSecret}}",ScriptScope.COLLECTION));
        collection.folderScriptBlocks.put("F",new ArrayList<>(List.of(block("{{mapped}}",ScriptScope.FOLDER))));
        ApiRequest request=new ApiRequest();request.name="R";request.path="F/R";request.method="GET";request.url="https://e.test/{{disabled}}/{{runtime}}";request.body=new ApiRequest.Body();request.body.mode="none";
        request.scriptBlocks.add(block("{{mapped}}",ScriptScope.REQUEST));collection.requests.add(request);

        List<UnresolvedVariableIssue> issues=ExportVariableResolutionService.collectUnresolvedIssues(collection,null,Map.of());
        assertThat(issues).extracting(issue->issue.variableName).contains("disabled","runtime","scriptSecret","mapped");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();BrunoCollectionExporter.write(collection,null,bytes,new ArrayList<>());
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()),StandardCharsets.UTF_8)){
            ZipEntry entry;while((entry=zip.getNextEntry())!=null){String text=new String(zip.readAllBytes(),StandardCharsets.UTF_8);assertThat(text).doesNotContain("DO_NOT_EXPORT_RUNTIME_TOKEN","DO_NOT_EXPORT_DISABLED_VALUE","DO_NOT_EXPORT_SCRIPT_SECRET","DO_NOT_EXPORT_AUTH_MAPPED_SECRET");}
        }
        String insomnia=InsomniaCollectionExporter.build(collection,null,new ArrayList<>()).toString();
        assertThat(insomnia).doesNotContain("DO_NOT_EXPORT_RUNTIME_TOKEN","DO_NOT_EXPORT_DISABLED_VALUE","DO_NOT_EXPORT_SCRIPT_SECRET","DO_NOT_EXPORT_AUTH_MAPPED_SECRET");
    }

    private static ApiRequest.Variable variable(String key,String value,boolean enabled){ApiRequest.Variable v=new ApiRequest.Variable();v.key=key;v.value=value;v.enabled=enabled;return v;}
    private static ScriptBlock block(String source,ScriptScope scope){ScriptBlock b=ScriptBlock.of(source,ScriptDialect.BRUNO,ScriptPhase.PRE_REQUEST,scope);b.enabled=true;return b;}
}
