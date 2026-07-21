package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.scripts.ScriptBlock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelCanonicalRequestStateCopyTest {
    @Test
    void editedRequestCopyPreservesLiveIdentityAndCopiesCanonicalState() {
        ApiCollection collection = new ApiCollection();
        AtomicInteger changes = new AtomicInteger();
        collection.addChangeListener(changes::incrementAndGet);
        ApiRequest live = liveRequest();
        ApiRequest edited = richRequest();

        ImporterPanel.applyEditedRequestToLiveRequest(collection, live, edited);

        assertIdentity(live);
        assertThat(live.description).isEqualTo("live description");
        assertThat(live.variables).extracting(v -> v.key).containsExactly("live-variable");
        assertThat(live.sourceMetadata).containsOnlyKeys("live.metadata");
        assertCanonicalState(live, edited);
        assertIndependent(live, edited);
        assertThat(changes).hasValue(1);
    }

    @Test
    void historySnapshotCopyRestoresCompleteAuthoredCanonicalState() {
        ApiCollection collection = new ApiCollection();
        ApiRequest live = liveRequest();
        ApiRequest authored = richRequest();
        authored.description = "authored description";
        authored.variables = variables("authored-variable");
        authored.sourceMetadata.put("history.metadata", "retained");

        ImporterPanel.applyHistorySnapshotToLiveRequest(collection, live, authored);

        assertIdentity(live);
        assertThat(live.description).isEqualTo("authored description");
        assertThat(live.variables).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(authored.variables);
        assertThat(live.sourceMetadata).isEqualTo(authored.sourceMetadata).isNotSameAs(authored.sourceMetadata);
        assertCanonicalState(live, authored);
        assertIndependent(live, authored);
        assertThat(live.auth).usingRecursiveComparison().isEqualTo(authored.explicitAuth);
    }

    @Test
    void copiedParametersDoNotShareMutableMetadata() {
        ApiRequest source = richRequest();
        ApiRequest destination = liveRequest();
        ImporterPanel.applyEditedRequestToLiveRequest(null, destination, source);

        source.parameters.get(0).value = "source-mutated";
        source.parameters.get(0).sourceMetadata.put("new", "source");
        source.parameters.add(parameter("query", "later", "3", true));
        source.parameters.get(1).disabled = true;
        assertThat(destination.parameters).hasSize(6);
        assertThat(destination.parameters.get(0).value).isEqualTo("42");
        assertThat(destination.parameters.get(0).sourceMetadata).doesNotContainKey("new");
        assertThat(destination.parameters.get(1).disabled).isFalse();

        destination.parameters.get(0).value = "destination-mutated";
        destination.parameters.get(0).sourceMetadata.put("other", "destination");
        assertThat(source.parameters.get(0).value).isEqualTo("source-mutated");
        assertThat(source.parameters.get(0).sourceMetadata).doesNotContainKey("other");
    }

    @Test
    void copiedBodyAndFieldsDoNotShareMutableMetadata() {
        ApiRequest source = richRequest();
        ApiRequest destination = liveRequest();
        ImporterPanel.applyHistorySnapshotToLiveRequest(null, destination, source);

        source.body.sourceMetadata.put("body-new", "source");
        source.body.formdata.get(0).sourceMetadata.put("field-new", "source");
        source.body.formdata.get(0).description = "changed";
        source.body.formdata.add(new ApiRequest.Body.FormField("second", "two"));
        source.body.graphql.query = "changed query";

        assertThat(destination.body.sourceMetadata).doesNotContainKey("body-new");
        assertThat(destination.body.formdata).hasSize(1);
        assertThat(destination.body.formdata.get(0).description).isEqualTo("field description");
        assertThat(destination.body.formdata.get(0).sourceMetadata).doesNotContainKey("field-new");
        assertThat(destination.body.graphql.query).isEqualTo("query");
    }

    @Test
    void requestStateCopySupportsNullCollectionWithoutLosingCanonicalFields() {
        ApiRequest editedDestination = liveRequest();
        ApiRequest historyDestination = liveRequest();
        ApiRequest source = richRequest();

        ImporterPanel.applyEditedRequestToLiveRequest(null, editedDestination, source);
        ImporterPanel.applyHistorySnapshotToLiveRequest(null, historyDestination, source);

        assertCanonicalState(editedDestination, source);
        assertCanonicalState(historyDestination, source);
    }

    @Test
    void nullCanonicalCollectionsBecomeMutableEmptyCollections() {
        ApiRequest source = richRequest();
        source.parameters = null;
        source.headers = null;
        source.suppressedAutoHeaders = null;
        source.preRequestScripts = null;
        source.postResponseScripts = null;
        source.scriptBlocks = null;
        source.variables = null;
        source.sourceMetadata = null;
        source.body.formdata = null;
        source.body.urlencoded = null;
        source.body.sourceMetadata = null;
        ApiRequest destination = liveRequest();

        ImporterPanel.applyHistorySnapshotToLiveRequest(null, destination, source);

        assertMutable(destination.parameters, new ApiRequest.Parameter());
        assertMutable(destination.headers, new ApiRequest.Header("X", "1"));
        destination.suppressedAutoHeaders.add("host");
        assertMutable(destination.preRequestScripts, new ApiRequest.Script("js", ""));
        assertMutable(destination.postResponseScripts, new ApiRequest.Script("js", ""));
        assertMutable(destination.scriptBlocks, new ScriptBlock());
        assertMutable(destination.variables, new ApiRequest.Variable());
        destination.sourceMetadata.put("key", "value");
        assertMutable(destination.body.formdata, new ApiRequest.Body.FormField("a", "1"));
        assertMutable(destination.body.urlencoded, new ApiRequest.Body.FormField("a", "1"));
        destination.body.sourceMetadata.put("key", "value");
    }

    private static ApiRequest liveRequest() {
        ApiRequest request = new ApiRequest();
        request.id = "live-id";
        request.name = "Live Name";
        request.path = "Live/Folder";
        request.sourceCollection = "Live Collection";
        request.sequenceOrder = 17;
        request.description = "live description";
        request.variables = variables("live-variable");
        request.sourceMetadata.put("live.metadata", "keep");
        return request;
    }

    private static ApiRequest richRequest() {
        ApiRequest request = new ApiRequest();
        request.id = "edited-id";
        request.name = "Edited Name";
        request.path = "Edited/Folder";
        request.sourceCollection = "Edited Collection";
        request.sequenceOrder = 99;
        request.method = "POST";
        request.url = "https://example.test/items/{id}";
        request.parameters = new ArrayList<>(List.of(
                parameter("path", "id", "42", true),
                parameter("query", "tag", "one", true),
                parameter("query", "tag", "two", true),
                parameter("query", "flag", "retained", false),
                parameter("header", "X-Meta", "header", true),
                parameter("cookie", "session", "cookie", true)));
        request.headers = new ArrayList<>(List.of(new ApiRequest.Header("X-Explicit", "one", false)));
        request.editorMaterialized = true;
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.suppressedAutoHeaders = new LinkedHashSet<>(List.of("user-agent"));
        request.body = body();
        request.preRequestScripts = new ArrayList<>(List.of(new ApiRequest.Script("js", "pre")));
        request.postResponseScripts = new ArrayList<>(List.of(new ApiRequest.Script("js", "post")));
        ScriptBlock block = new ScriptBlock();
        block.source = "block";
        block.metadata.put("retained", "yes");
        request.scriptBlocks = new ArrayList<>(List.of(block));
        request.authOverrideMode = "explicit";
        request.explicitAuth = new ApiRequest.Auth();
        request.explicitAuth.type = "bearer";
        request.explicitAuth.properties.put("token", "token-template");
        request.exactHttpRequest = snapshot();
        request.disabled = true;
        request.description = "edited description";
        request.variables = variables("edited-variable");
        request.sourceMetadata.put("edited.metadata", "replace-on-history");
        return request;
    }

    private static ApiRequest.Parameter parameter(String location, String key, String value, boolean valuePresent) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter(location, key, value);
        parameter.rawKey = "raw-" + key;
        parameter.rawValue = "raw-" + value;
        parameter.valuePresent = valuePresent;
        parameter.required = true;
        parameter.type = "string";
        parameter.format = "wave5";
        parameter.description = "parameter description";
        parameter.style = "form";
        parameter.explode = Boolean.FALSE;
        parameter.allowReserved = true;
        parameter.source = "test:authored";
        parameter.sourceMetadata.put("retained.parameter", "value");
        return parameter;
    }

    private static ApiRequest.Body body() {
        ApiRequest.Body body = new ApiRequest.Body();
        body.mode = "formdata";
        body.raw = "retained raw";
        body.contentType = "multipart/form-data";
        body.required = true;
        body.description = "body description";
        body.filePath = "body-path.bin";
        body.source = "test:body";
        body.sourceMetadata.put("retained.body", "value");
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField("upload", "retained text");
        field.type = "file";
        field.fileUpload = true;
        field.filePath = null;
        field.disabled = true;
        field.required = true;
        field.description = "field description";
        field.contentType = "application/octet-stream";
        field.style = "form";
        field.explode = Boolean.FALSE;
        field.allowReserved = true;
        field.source = "test:field";
        field.sourceMetadata.put("retained.fileName", "payload.bin");
        body.formdata.add(field);
        body.urlencoded.add(new ApiRequest.Body.FormField("encoded", "one"));
        body.graphql = new ApiRequest.Body.GraphQL();
        body.graphql.query = "query";
        body.graphql.variables = "{}";
        return body;
    }

    private static ExactHttpRequestSnapshot snapshot() {
        ExactHttpRequestSnapshot snapshot = new ExactHttpRequestSnapshot();
        snapshot.rawRequestBytes = "GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        snapshot.serviceHost = "example.test";
        snapshot.servicePort = 443;
        snapshot.secure = true;
        snapshot.httpVersion = "HTTP/1.0";
        snapshot.pristine = true;
        snapshot.binaryBody = false;
        snapshot.semanticFingerprint = "fingerprint";
        snapshot.sourceContext = "test";
        snapshot.invalidationReason = "";
        return snapshot;
    }

    private static List<ApiRequest.Variable> variables(String key) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = "value";
        return new ArrayList<>(List.of(variable));
    }

    private static void assertIdentity(ApiRequest request) {
        assertThat(request.id).isEqualTo("live-id");
        assertThat(request.name).isEqualTo("Live Name");
        assertThat(request.path).isEqualTo("Live/Folder");
        assertThat(request.sourceCollection).isEqualTo("Live Collection");
        assertThat(request.sequenceOrder).isEqualTo(17);
    }

    private static void assertCanonicalState(ApiRequest actual, ApiRequest expected) {
        assertThat(actual.method).isEqualTo(expected.method);
        assertThat(actual.url).isEqualTo(expected.url);
        assertThat(actual.parameters).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(expected.parameters);
        assertThat(actual.headers).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(expected.headers);
        assertThat(actual.body).usingRecursiveComparison().isEqualTo(expected.body);
        assertThat(actual.preRequestScripts).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(expected.preRequestScripts);
        assertThat(actual.postResponseScripts).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(expected.postResponseScripts);
        assertThat(actual.scriptBlocks).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(expected.scriptBlocks);
        assertThat(actual.authOverrideMode).isEqualTo(expected.authOverrideMode);
        assertThat(actual.explicitAuth).usingRecursiveComparison().isEqualTo(expected.explicitAuth);
        assertThat(actual.buildMode).isEqualTo(expected.buildMode);
        assertThat(actual.suppressedAutoHeaders).isEqualTo(expected.suppressedAutoHeaders);
        assertThat(actual.disabled).isEqualTo(expected.disabled);
        assertThat(actual.exactHttpRequest).usingRecursiveComparison().isEqualTo(expected.exactHttpRequest);
    }

    private static void assertIndependent(ApiRequest actual, ApiRequest source) {
        assertThat(actual.parameters).isNotSameAs(source.parameters);
        assertThat(actual.parameters.get(0)).isNotSameAs(source.parameters.get(0));
        assertThat(actual.parameters.get(0).sourceMetadata).isNotSameAs(source.parameters.get(0).sourceMetadata);
        assertThat(actual.headers).isNotSameAs(source.headers);
        assertThat(actual.body).isNotSameAs(source.body);
        assertThat(actual.body.sourceMetadata).isNotSameAs(source.body.sourceMetadata);
        assertThat(actual.body.formdata).isNotSameAs(source.body.formdata);
        assertThat(actual.body.formdata.get(0).sourceMetadata)
                .isNotSameAs(source.body.formdata.get(0).sourceMetadata);
        assertThat(actual.preRequestScripts).isNotSameAs(source.preRequestScripts);
        assertThat(actual.scriptBlocks).isNotSameAs(source.scriptBlocks);
        assertThat(actual.exactHttpRequest).isNotSameAs(source.exactHttpRequest);
        assertThat(actual.exactHttpRequest.rawRequestBytes).isNotSameAs(source.exactHttpRequest.rawRequestBytes);
        assertThat(actual.exactHttpRequest.rawRequestBytes).containsExactly(source.exactHttpRequest.rawRequestBytes);
    }

    private static <T> void assertMutable(List<T> list, T value) {
        assertThat(list).isNotNull();
        list.add(value);
        assertThat(list).contains(value);
    }
}
