package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.UnresolvedVariableIssue;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExportTargetExactResolutionMatrixTest {
    @TempDir Path tempDir;

    @Test
    void brunoDiagnosticsMatchOnlyActiveTargetFields() throws Exception {
        assertTarget(CollectionExportFormat.BRUNO_ZIP, Set.of(
                "request_name", "url_emit", "query_key", "query_value", "query_description",
                "header_value", "file_path", "text_value", "content_type", "bruno_auth_alias",
                "request_variable", "native_test", "legacy_post"));
    }

    @Test
    void insomniaDiagnosticsMatchOnlyActiveTargetFields() throws Exception {
        assertTarget(CollectionExportFormat.INSOMNIA_JSON, Set.of(
                "request_name", "request_description", "url_emit", "query_key", "query_value",
                "query_description", "query_type", "header_value", "file_path", "file_retained",
                "text_value", "content_type", "bruno_auth_alias", "native_test", "generic_auth"));
    }

    private void assertTarget(CollectionExportFormat format, Set<String> expected) throws Exception {
        ApiCollection collection = matrixCollection();
        Path output = tempDir.resolve(format == CollectionExportFormat.BRUNO_ZIP
                ? "matrix.zip" : "matrix.json");
        ExportResult result = new CollectionExportService().exportCollection(collection,
                new CollectionExportOptions(format, output, true, null, Map.of()));

        List<UnresolvedVariableIssue> issues =
                ExportVariableResolutionService.collectUnresolvedIssuesFromArtifact(output, format, collection);
        Set<String> actual = new LinkedHashSet<>();
        for (UnresolvedVariableIssue issue : issues) actual.add(issue.variableName);

        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(result.unresolvedVariableCount).isEqualTo(actual.size());
        assertThat(actual).doesNotContain(
                "transport_header", "disabled_header", "disabled_query", "disabled_form",
                "shadowed_environment", "unsupported_bruno_auth", "legacy_omit_insomnia");
        if (format == CollectionExportFormat.BRUNO_ZIP) {
            assertThat(actual).doesNotContain("request_description", "query_type", "file_retained", "generic_auth");
        } else {
            assertThat(actual).doesNotContain("request_variable", "legacy_post");
        }
    }

    private static ApiCollection matrixCollection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Target exact";
        collection.folderPaths.add("F");
        collection.environment.put("override", "{{shadowed_environment}}");
        collection.variables.add(variable("override", "fixed", true));
        collection.folderVars.put("F", Map.of("folderBase", "https://folder.test"));

        ApiRequest request = new ApiRequest();
        request.name = "R-{{request_name}}";
        request.path = "F/R";
        request.method = "POST";
        request.url = "{{folderBase}}/{{url_emit}}";
        request.description = "{{request_description}}";
        request.variables.add(variable("rv", "{{request_variable}}", true));

        request.headers.add(header("Host", "{{transport_header}}", false));
        request.headers.add(header("X-Active", "{{header_value}}", false));
        request.headers.add(header("X-Disabled", "{{disabled_header}}", true));

        ApiRequest.Parameter query = new ApiRequest.Parameter("query", "q-{{query_key}}", "{{query_value}}");
        query.description = "{{query_description}}";
        query.type = "{{query_type}}";
        request.parameters.add(query);
        ApiRequest.Parameter disabled = new ApiRequest.Parameter("query", "disabled", "{{disabled_query}}");
        disabled.disabled = true;
        disabled.description = "{{disabled_query_description}}";
        request.parameters.add(disabled);

        request.body = new ApiRequest.Body();
        request.body.mode = "formdata";
        request.body.contentType = "multipart/{{content_type}}";
        request.body.formdata.add(fileField("upload", "{{file_retained}}", "{{file_path}}", false));
        request.body.formdata.add(textField("text", "{{text_value}}", false));
        request.body.formdata.add(textField("disabled", "{{disabled_form}}", true));

        ApiRequest.Auth bearer = new ApiRequest.Auth();
        bearer.type = "bearer";
        bearer.properties.put("value", "{{bruno_auth_alias}}");
        request.authOverrideMode = "explicit";
        request.explicitAuth = bearer;
        request.auth = bearer;

        ScriptBlock test = ScriptBlock.of("test({{native_test}});", ScriptDialect.BRUNO,
                ScriptPhase.TEST, ScriptScope.REQUEST);
        test.enabled = true;
        test.order = 1;
        request.scriptBlocks.add(test);
        request.postResponseScripts.add(new ApiRequest.Script("js", "legacy({{legacy_post}});"));
        collection.requests.add(request);

        ApiRequest generic = new ApiRequest();
        generic.name = "Generic";
        generic.method = "GET";
        generic.url = "https://example.test/generic";
        generic.body = new ApiRequest.Body();
        generic.body.mode = "none";
        ApiRequest.Auth custom = new ApiRequest.Auth();
        custom.type = "custom";
        custom.properties.put("customProperty", "{{generic_auth}}");
        generic.authOverrideMode = "explicit";
        generic.explicitAuth = custom;
        generic.auth = custom;
        collection.requests.add(generic);

        ApiRequest raw = new ApiRequest();
        raw.name = "Raw";
        raw.method = "POST";
        raw.url = "https://example.test/raw";
        raw.body = new ApiRequest.Body();
        raw.body.mode = "raw";
        raw.body.raw = "payload";
        raw.body.contentType = "application/{{content_type}}";
        collection.requests.add(raw);

        ApiRequest oauth = new ApiRequest();
        oauth.name = "OAuth";
        oauth.method = "GET";
        oauth.url = "https://example.test/oauth";
        oauth.body = new ApiRequest.Body();
        oauth.body.mode = "none";
        ApiRequest.Auth oauthAuth = new ApiRequest.Auth();
        oauthAuth.type = "oauth2";
        oauthAuth.properties.put("clientId", "client");
        oauthAuth.properties.put("unsupported", "{{unsupported_bruno_auth}}");
        oauth.authOverrideMode = "explicit";
        oauth.explicitAuth = oauthAuth;
        oauth.auth = oauthAuth;
        collection.requests.add(oauth);
        return collection;
    }

    private static ApiRequest.Variable variable(String key, String value, boolean enabled) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        variable.enabled = enabled;
        return variable;
    }

    private static ApiRequest.Header header(String key, String value, boolean disabled) {
        ApiRequest.Header header = new ApiRequest.Header(key, value);
        header.disabled = disabled;
        return header;
    }

    private static ApiRequest.Body.FormField textField(String key, String value, boolean disabled) {
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(key, value);
        field.type = "text";
        field.disabled = disabled;
        return field;
    }

    private static ApiRequest.Body.FormField fileField(
            String key, String value, String path, boolean disabled) {
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(key, value);
        field.type = "file";
        field.fileUpload = true;
        field.filePath = path;
        field.disabled = disabled;
        return field;
    }
}
