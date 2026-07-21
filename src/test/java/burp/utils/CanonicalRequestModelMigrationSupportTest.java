package burp.utils;

import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalRequestModelMigrationSupportTest {
    @Test
    void migratesDuplicateBareEmptyRawAndFragmentQuery() {
        ApiRequest request = request("https://example.test/search?a=one&a=two&flag&empty=&encoded=a%2Fb&%20=space&#fragment");

        assertThat(CanonicalRequestModelMigrationSupport.migrateLegacyEmbeddedQuery(request, false)).isTrue();

        assertThat(request.url).isEqualTo("https://example.test/search#fragment");
        assertThat(request.parameters).extracting(p -> p.key)
                .containsExactly("a", "a", "flag", "empty", "encoded", " ", "");
        assertThat(request.parameters).extracting(p -> p.value)
                .containsExactly("one", "two", "", "", "a/b", "space", "");
        assertThat(request.parameters).extracting(p -> p.valuePresent)
                .containsExactly(true, true, false, true, true, true, false);
        assertThat(request.parameters).extracting(p -> p.rawKey)
                .containsExactly("a", "a", "flag", "empty", "encoded", "%20", "");
        assertThat(request.parameters.get(4).rawValue).isEqualTo("a%2Fb");
    }

    @Test
    void declaredParameterPropertyPreventsMigration() {
        ApiRequest request = request("https://example.test/?a=1");
        assertThat(CanonicalRequestModelMigrationSupport.migrateLegacyEmbeddedQuery(request, true)).isFalse();
        assertThat(request.url).contains("?a=1");
        assertThat(request.parameters).isEmpty();
    }

    @Test
    void existingParametersPreventMigration() {
        ApiRequest request = request("https://example.test/?a=1");
        request.parameters.add(new ApiRequest.Parameter("query", "current", "2"));
        assertThat(CanonicalRequestModelMigrationSupport.migrateLegacyEmbeddedQuery(request, false)).isFalse();
        assertThat(request.url).contains("?a=1");
        assertThat(request.parameters).extracting(p -> p.key).containsExactly("current");
    }

    @Test
    void exactSnapshotPreventsMigration() {
        ApiRequest request = request("https://example.test/?a=1");
        request.exactHttpRequest = new ExactHttpRequestSnapshot();
        ExactHttpRequestSnapshot snapshot = request.exactHttpRequest;
        assertThat(CanonicalRequestModelMigrationSupport.migrateLegacyEmbeddedQuery(request, false)).isFalse();
        assertThat(request.url).contains("?a=1");
        assertThat(request.parameters).isEmpty();
        assertThat(request.exactHttpRequest).isSameAs(snapshot);
    }

    @Test
    void migrationIsIdempotent() {
        ApiRequest request = request("https://example.test/?a=1");
        assertThat(CanonicalRequestModelMigrationSupport.migrateLegacyEmbeddedQuery(request, false)).isTrue();
        String url = request.url;
        assertThat(CanonicalRequestModelMigrationSupport.migrateLegacyEmbeddedQuery(request, false)).isFalse();
        assertThat(request.url).isEqualTo(url);
        assertThat(request.parameters).hasSize(1);
    }

    @Test
    void urlWithoutQueryDoesNotMigrate() {
        ApiRequest request = request("https://example.test/path#fragment");
        assertThat(CanonicalRequestModelMigrationSupport.migrateLegacyEmbeddedQuery(request, false)).isFalse();
    }

    @Test
    void questionMarkAfterFragmentDoesNotMigrate() {
        ApiRequest request = request("https://example.test/path#fragment?not=query");
        assertThat(CanonicalRequestModelMigrationSupport.migrateLegacyEmbeddedQuery(request, false)).isFalse();
    }

    private static ApiRequest request(String url) {
        ApiRequest request = new ApiRequest();
        request.method = "GET";
        request.url = url;
        request.parameters = new ArrayList<>();
        return request;
    }
}
