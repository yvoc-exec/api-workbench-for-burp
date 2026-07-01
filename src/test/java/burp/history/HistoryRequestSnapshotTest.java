package burp.history;

import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryRequestSnapshotTest {

    @Test
    void fromNullRequestProducesEmptySnapshot() {
        HistoryRequestSnapshot snapshot = HistoryRequestSnapshot.from(null);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.method).isNull();
        assertThat(snapshot.urlTemplate).isNull();
        assertThat(snapshot.headersAsAuthored).isNotNull().isEmpty();
        assertThat(snapshot.requestVariablesAsAuthored).isNotNull().isEmpty();
        assertThat(snapshot.displayBodyText()).isEmpty();
        assertThat(snapshot.hasRawRequestSent()).isFalse();
        assertThat(snapshot.preferredRawRequestText()).isEmpty();
    }

    @Test
    void fromRequestDeepCopiesAuthoredDataAndSupportsBodyModes() {
        ApiRequest request = request("POST", "https://api.example.test/login");
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.headers.add(new ApiRequest.Header("X-Test", "alpha", false));
        request.headers.add(new ApiRequest.Header("X-Disabled", "beta", true));
        request.variables.add(variable("", "ignored"));
        request.variables.add(variable("token", "abc123"));
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"message\":\"hello\"}";
        request.auth = new ApiRequest.Auth();
        request.auth.type = "bearer";
        request.auth.properties.put("token", "secret-token");

        HistoryRequestSnapshot snapshot = HistoryRequestSnapshot.from(request);

        assertThat(snapshot.method).isEqualTo("POST");
        assertThat(snapshot.urlTemplate).isEqualTo("https://api.example.test/login");
        assertThat(snapshot.headersAsAuthored).extracting(header -> header.name)
                .containsExactly("X-Test", "X-Disabled");
        assertThat(snapshot.headersAsAuthored.get(1).disabled).isTrue();
        assertThat(snapshot.requestVariablesAsAuthored).containsEntry("token", "abc123");
        assertThat(snapshot.requestVariablesAsAuthored).doesNotContainKey("");
        assertThat(snapshot.displayBodyText()).isEqualTo("{\"message\":\"hello\"}");
        assertThat(snapshot.authType).isEqualTo("bearer");
        assertThat(snapshot.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(snapshot.authoredRequest).isNotSameAs(request);

        request.headers.get(0).value = "mutated";
        request.variables.get(1).value = "mutated";
        request.body.raw = "{\"message\":\"mutated\"}";

        assertThat(snapshot.headersAsAuthored.get(0).value).isEqualTo("alpha");
        assertThat(snapshot.requestVariablesAsAuthored).containsEntry("token", "abc123");
        assertThat(snapshot.displayBodyText()).isEqualTo("{\"message\":\"hello\"}");
    }

    @Test
    void exactSnapshotRoundTripPreservesMetadataDuplicatesTransportRowsAndBodyState() {
        ApiRequest request = request("POST", "https://api.example.test/exact");
        request.description = "history exact";
        request.disabled = true;
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.headers.add(new ApiRequest.Header("Host", "one.example", false));
        request.headers.add(new ApiRequest.Header("Host", "two.example", false));
        request.headers.add(new ApiRequest.Header("Cookie", "a=1", false));
        request.headers.add(new ApiRequest.Header("Cookie", "b=2", false));
        request.headers.add(new ApiRequest.Header("Transfer-Encoding", "chunked", false));
        request.headers.add(new ApiRequest.Header("Connection", "close", true));
        request.variables.add(variable("tenant", "acme"));
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "hello";
        request.body.contentType = "text/plain";
        request.preRequestScripts.add(new ApiRequest.Script("js", "pre();"));
        request.postResponseScripts.add(new ApiRequest.Script("js", "post();"));
        request.auth = new ApiRequest.Auth();
        request.auth.type = "bearer";
        request.auth.properties.put("token", "secret");

        ApiRequest rebuilt = HistoryRequestSnapshot.from(request).toApiRequest();

        assertThat(rebuilt.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(rebuilt.description).isEqualTo("history exact");
        assertThat(rebuilt.disabled).isTrue();
        assertThat(rebuilt.headers).extracting(header -> header.key)
                .containsExactly("Host", "Host", "Cookie", "Cookie", "Transfer-Encoding", "Connection");
        assertThat(rebuilt.headers.get(5).disabled).isTrue();
        assertThat(rebuilt.variables).extracting(variable -> variable.key).containsExactly("tenant");
        assertThat(rebuilt.body.contentType).isEqualTo("text/plain");
        assertThat(rebuilt.preRequestScripts.get(0).exec).isEqualTo("pre();");
        assertThat(rebuilt.postResponseScripts.get(0).exec).isEqualTo("post();");
        assertThat(rebuilt.auth.type).isEqualTo("bearer");
    }

    @Test
    void copyOfHandlesNullNestedStateAndDeepCopiesArraysMapsAndRequest() {
        HistoryRequestSnapshot source = new HistoryRequestSnapshot();
        source.method = "POST";
        source.urlTemplate = "https://api.example.test/login";
        source.headersAsAuthored = null;
        source.bodyAsAuthored = null;
        source.requestVariablesAsAuthored = null;
        source.authoredRequest = request("POST", "https://api.example.test/login");
        source.authoredRequest.headers.add(new ApiRequest.Header("X-Source", "one", false));
        source.rawRequestSent = "GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        source.rawRequestSentText = "GET / HTTP/1.1\r\n\r\n";
        source.resolvedVariables = null;

        HistoryRequestSnapshot copy = HistoryRequestSnapshot.copyOf(source);

        assertThat(HistoryRequestSnapshot.copyOf(null)).isNull();
        assertThat(copy.headersAsAuthored).isNotNull().isEmpty();
        assertThat(copy.requestVariablesAsAuthored).isNotNull().isEmpty();
        assertThat(copy.rawRequestSentText).isEqualTo("GET / HTTP/1.1\r\n\r\n");
        assertThat(copy.rawRequestSent).isEqualTo(source.rawRequestSent);
        assertThat(copy.authoredRequest).isNotSameAs(source.authoredRequest);
        assertThat(copy.authoredRequest.headers).extracting(header -> header.key).containsExactly("X-Source");
    }

    @Test
    void reconstructsLegacyRequestWithDisabledHeadersBodyVariablesAndManualPreserveMode() {
        HistoryRequestSnapshot snapshot = new HistoryRequestSnapshot();
        snapshot.method = "PATCH";
        snapshot.urlTemplate = "https://api.example.test/users/{{id}}";
        snapshot.headersAsAuthored = List.of(
                new HistoryHeader("X-Enabled", "yes", false),
                new HistoryHeader("X-Disabled", "no", true)
        );
        snapshot.bodyMode = "raw";
        snapshot.bodyAsAuthored = "{\"name\":\"Alice\"}".getBytes(StandardCharsets.UTF_8);
        snapshot.authType = "basic";
        snapshot.requestVariablesAsAuthored = new LinkedHashMap<>(Map.of("id", "42", "blank", ""));
        snapshot.authoredRequest = null;

        ApiRequest rebuilt = snapshot.toApiRequest();

        assertThat(rebuilt.method).isEqualTo("PATCH");
        assertThat(rebuilt.url).isEqualTo("https://api.example.test/users/{{id}}");
        assertThat(rebuilt.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(rebuilt.editorMaterialized).isTrue();
        assertThat(rebuilt.suppressedAutoHeaders).isEmpty();
        assertThat(rebuilt.headers).extracting(header -> header.key).containsExactly("X-Enabled", "X-Disabled");
        assertThat(rebuilt.headers.get(1).disabled).isTrue();
        assertThat(rebuilt.body.mode).isEqualTo("raw");
        assertThat(rebuilt.body.raw).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(rebuilt.auth.type).isEqualTo("basic");
        assertThat(rebuilt.variables).extracting(variable -> variable.key)
                .containsExactlyInAnyOrder("id", "blank");
    }

    @Test
    void legacySnapshotsWithoutBuildModeStillDefaultToManualPreserveRebuilds() {
        HistoryRequestSnapshot snapshot = new HistoryRequestSnapshot();
        snapshot.method = "GET";
        snapshot.urlTemplate = "https://api.example.test/legacy";
        snapshot.authoredRequest = null;

        ApiRequest rebuilt = snapshot.toApiRequest();

        assertThat(rebuilt.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(rebuilt.editorMaterialized).isTrue();
    }

    @Test
    void prefersExplicitRawTextThenBytesAndSupportsDisplayAndCurlFormatting() {
        HistoryRequestSnapshot snapshot = new HistoryRequestSnapshot();
        snapshot.method = null;
        snapshot.urlTemplate = "https://api.example.test/quote";
        snapshot.headersAsAuthored = List.of(
                new HistoryHeader("X-Enabled", "one", false),
                new HistoryHeader("X-Disabled", "two", true),
                new HistoryHeader("X-Quote", "O'Reilly", false)
        );
        snapshot.bodyAsAuthored = "body with 'quote'".getBytes(StandardCharsets.UTF_8);
        snapshot.rawRequestSentText = "GET /raw HTTP/1.1\r\n\r\n";
        snapshot.rawRequestSent = "GET /bytes HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8);

        assertThat(snapshot.preferredRawRequestText()).isEqualTo("GET /raw HTTP/1.1\r\n\r\n");
        assertThat(snapshot.hasRawRequestSent()).isTrue();
        assertThat(snapshot.displayBodyText()).isEqualTo("body with 'quote'");
        assertThat(snapshot.toCurlCommand()).contains("https://api.example.test/quote");
        assertThat(snapshot.toCurlCommand())
                .contains("curl -X GET")
                .contains("X-Enabled: one")
                .contains("X-Quote: O'\"'\"'Reilly")
                .doesNotContain("X-Disabled")
                .contains("--data-raw 'body with '\"'\"'quote'\"'\"''");
        assertThat(snapshot.approximateSizeBytes()).isGreaterThan(0);
        assertThat(snapshot.displayHeaderBlock()).contains("X-Enabled: one");
        assertThat(snapshot.displayHeaderBlock()).contains("[disabled] X-Disabled: two");
        assertThat(snapshot.describe()).contains("Method: GET").contains("Body as Authored:");
    }

    @Test
    void serializesStructuredBodyModesIntoDisplayBodyText() {
        assertThat(snapshotWithBodyMode("urlencoded", body -> body.urlencoded.add(formField("a", "1")))
                .displayBodyText())
                .contains("a=1");

        assertThat(snapshotWithBodyMode("formdata", body -> {
            body.formdata.add(formField("field", "value"));
            ApiRequest.Body.FormField file = formField("upload", null);
            file.fileUpload = true;
            file.filePath = "C:/temp/file.txt";
            file.type = "file";
            body.formdata.add(file);
        }).displayBodyText())
                .contains("field=value")
                .contains("upload=C:/temp/file.txt");

        assertThat(snapshotWithBodyMode("graphql", body -> {
            body.graphql = new ApiRequest.Body.GraphQL();
            body.graphql.query = "{ users }";
            body.graphql.variables = "{\"limit\":1}";
        }).displayBodyText())
                .contains("\"query\":\"{ users }\"")
                .contains("\"variables\":\"{\\\"limit\\\":1}\"");

        assertThat(snapshotWithBodyMode("none", body -> {
        }).displayBodyText()).isEmpty();
    }

    private static HistoryRequestSnapshot snapshotWithBodyMode(String mode, java.util.function.Consumer<ApiRequest.Body> bodyConfigurer) {
        ApiRequest request = request("POST", "https://api.example.test/body");
        request.body = new ApiRequest.Body();
        request.body.mode = mode;
        bodyConfigurer.accept(request.body);
        return HistoryRequestSnapshot.from(request);
    }

    private static ApiRequest.Body.FormField formField(String key, String value) {
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(key, value);
        return field;
    }

    private static ApiRequest request(String method, String url) {
        ApiRequest request = new ApiRequest();
        request.method = method;
        request.url = url;
        return request;
    }

    private static ApiRequest.Variable variable(String key, String value) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        return variable;
    }
}
