package burp.runner;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.RunnerPreviewRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionRunnerPreviewTest {

    @Test
    void buildRunPreviewSortsRequestsAndResolvesVariables() {
        CollectionRunner runner = new CollectionRunner(null);

        ApiCollection firstCollection = new ApiCollection();
        firstCollection.name = "Alpha";
        firstCollection.environment.put("baseUrl", "https://alpha.example.com");
        firstCollection.runtimeVars.put("runtime_token", "alpha-token");

        ApiRequest firstRequest = new ApiRequest();
        firstRequest.name = "First";
        firstRequest.method = "get";
        firstRequest.url = "{{baseUrl}}/users/{{missingId}}?token={{runtime_token}}";
        firstRequest.sequenceOrder = 20;
        firstRequest.sourceCollection = firstCollection.name;
        firstRequest.auth = new ApiRequest.Auth();
        firstRequest.auth.type = "bearer";
        firstRequest.auth.properties.put("token", "{{runtime_token}}");
        firstCollection.requests.add(firstRequest);

        ApiCollection secondCollection = new ApiCollection();
        secondCollection.name = "Beta";
        secondCollection.environment.put("apiHost", "https://beta.example.com");
        secondCollection.runtimeOAuth2.put("oauth2_access_token", "beta-oauth-token");

        ApiRequest secondRequest = new ApiRequest();
        secondRequest.name = "Second";
        secondRequest.method = "post";
        secondRequest.url = "{{apiHost}}/preview/{{oauth2_access_token}}";
        secondRequest.sequenceOrder = 5;
        secondRequest.sourceCollection = secondCollection.name;
        secondRequest.auth = new ApiRequest.Auth();
        secondRequest.auth.type = "oauth2";

        List<RunnerPreviewRow> rows = runner.buildRunPreview(
                List.of(firstCollection, secondCollection),
                List.of(firstRequest, secondRequest)
        );

        assertThat(rows).hasSize(2);
        assertThat(rows)
                .extracting(row -> row.order + ":" + row.collectionName + ":" + row.requestName)
                .containsExactly(
                        "5:Beta:Second",
                        "20:Alpha:First"
                );

        RunnerPreviewRow firstRow = rows.get(0);
        assertThat(firstRow.method).isEqualTo("POST");
        assertThat(firstRow.urlPreview).isEqualTo("https://beta.example.com/preview/beta-oauth-token");
        assertThat(firstRow.unresolvedVariables).isEmpty();
        assertThat(firstRow.authStatus).isEqualTo("oauth2");

        RunnerPreviewRow secondRow = rows.get(1);
        assertThat(secondRow.method).isEqualTo("GET");
        assertThat(secondRow.urlPreview).isEqualTo("https://alpha.example.com/users/{{missingId}}?token=alpha-token");
        assertThat(secondRow.unresolvedVariables).containsExactly("missingId");
        assertThat(secondRow.authStatus).isEqualTo("bearer");
    }

    @Test
    void buildRunPreviewIncludesInheritedAuthSourceInAuthStatus() {
        CollectionRunner runner = new CollectionRunner(null);

        ApiCollection collection = new ApiCollection();
        collection.name = "Auth Demo";

        ApiRequest request = new ApiRequest();
        request.name = "Get Me";
        request.method = "GET";
        request.url = "https://api.example.test/me";
        request.auth = new ApiRequest.Auth();
        request.auth.type = "bearer";
        request.authInherited = true;
        request.authSource = "collection: Auth Demo";
        collection.requests.add(request);

        List<RunnerPreviewRow> rows = runner.buildRunPreview(List.of(collection), List.of(request));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).authStatus).contains("bearer");
        assertThat(rows.get(0).authStatus).contains("collection:");
    }

    @Test
    void previewPreservesSelectionOrderWhenSequenceOrderIsDefaultZero() {
        CollectionRunner runner = new CollectionRunner(null, new burp.utils.SharedRequestPipeline(null, null, null, null), null);
        ApiCollection collection = new ApiCollection();
        collection.name = "Postman";
        collection.format = "postman";

        ApiRequest first = new ApiRequest();
        first.name = "First";
        first.method = "GET";
        first.url = "https://example.test/first";
        first.sourceCollection = collection.name;
        ApiRequest second = new ApiRequest();
        second.name = "Second";
        second.method = "GET";
        second.url = "https://example.test/second";
        second.sourceCollection = collection.name;
        collection.requests.add(first);
        collection.requests.add(second);

        List<RunnerPreviewRow> rows = runner.buildRunPreview(List.of(collection), List.of(second, first));

        assertThat(rows).extracting(row -> row.requestName).containsExactly("Second", "First");
    }
}
