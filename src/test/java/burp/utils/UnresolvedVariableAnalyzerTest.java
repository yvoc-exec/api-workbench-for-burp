package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.UnresolvedVariableIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnresolvedVariableAnalyzerTest {

    @Test
    void analyzeReturnsUrlIssueForMissingVariable() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Checkout";

        ApiRequest request = new ApiRequest();
        request.name = "Get Cart";
        request.url = "https://api.example.com/{{baseUrl}}/cart";

        UnresolvedVariableAnalyzer analyzer = new UnresolvedVariableAnalyzer();

        List<UnresolvedVariableIssue> issues = analyzer.analyze(collection, request);

        assertThat(issues)
                .hasSize(1);
        assertThat(issues.get(0).collectionName).isEqualTo("Checkout");
        assertThat(issues.get(0).requestName).isEqualTo("Get Cart");
        assertThat(issues.get(0).variableName).isEqualTo("baseUrl");
        assertThat(issues.get(0).location).isEqualTo("url");
    }

    @Test
    void analyzeIgnoresResolvedScopesAndDefaultTokens() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Profile";
        collection.runtimeVars.put("baseUrl", "https://api.example.com");
        collection.runtimeOAuth2.put("oauth2_access_token", "token-123");
        collection.environment.put("envHost", "https://env.example.com");

        ApiRequest request = new ApiRequest();
        request.name = "Update Profile";
        request.url = "{{baseUrl}}/users/{{userId|me}}";
        request.variables.add(variable("userId", "42"));
        request.headers.add(new ApiRequest.Header("Authorization", "Bearer {{oauth2_access_token}}"));
        request.body = new ApiRequest.Body();
        request.body.raw = "{\"host\":\"{{envHost}}\"}";

        UnresolvedVariableAnalyzer analyzer = new UnresolvedVariableAnalyzer();

        List<UnresolvedVariableIssue> issues = analyzer.analyze(collection, request);

        assertThat(issues).isEmpty();
    }

    @Test
    void analyzeUsesActiveEnvironmentOverlay() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Profile";

        ApiRequest request = new ApiRequest();
        request.name = "Overlay";
        request.url = "{{activeBaseUrl}}/users";

        UnresolvedVariableAnalyzer analyzer = new UnresolvedVariableAnalyzer();

        List<UnresolvedVariableIssue> issues = analyzer.analyze(collection, request, java.util.Map.of("activeBaseUrl", "https://active.example.test"));

        assertThat(issues).isEmpty();
    }

    @Test
    void analyzeIgnoresAuthMappedOauth2RuntimeVariables() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Profile";

        ApiRequest request = new ApiRequest();
        request.name = "Refresh Profile";
        request.url = "https://api.example.com/{{oauth2_access_token}}";
        request.auth = new ApiRequest.Auth();
        request.auth.type = "oauth2";
        request.auth.properties.put("accessToken", "token-123");

        UnresolvedVariableAnalyzer analyzer = new UnresolvedVariableAnalyzer();

        List<UnresolvedVariableIssue> issues = analyzer.analyze(collection, request);

        assertThat(issues).isEmpty();
    }

    @Test
    void analyzeDetectsVariablesAcrossHeaderBodyAndAuthLocations() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Orders";

        ApiRequest request = new ApiRequest();
        request.name = "Create Order";
        request.url = "https://api.example.com/orders";
        request.headers.add(new ApiRequest.Header("X-Trace-{{traceId}}", "Bearer {{authToken}}"));
        request.body = new ApiRequest.Body();
        request.body.raw = "{\"customer\":\"{{customerId}}\"}";
        request.auth = new ApiRequest.Auth();
        request.auth.type = "oauth2";
        request.auth.properties.put("access_token", "{{oauthToken}}");

        UnresolvedVariableAnalyzer analyzer = new UnresolvedVariableAnalyzer();

        List<UnresolvedVariableIssue> issues = analyzer.analyze(collection, request);

        assertThat(issues).extracting(issue -> issue.variableName + "@" + issue.location)
                .containsExactlyInAnyOrder(
                        "traceId@header:key",
                        "authToken@header:value",
                        "customerId@body",
                        "oauthToken@auth:access_token"
                );
    }

    @Test
    void analyzeReturnsOneIssuePerVariableRequestAndLocation() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Notifications";

        ApiRequest request = new ApiRequest();
        request.name = "Send Notification";
        request.body = new ApiRequest.Body();
        request.body.raw = "{{missing}} again {{missing}}";

        UnresolvedVariableAnalyzer analyzer = new UnresolvedVariableAnalyzer();

        List<UnresolvedVariableIssue> issues = analyzer.analyze(collection, request);

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).variableName).isEqualTo("missing");
        assertThat(issues.get(0).location).isEqualTo("body");
    }

    private static ApiRequest.Variable variable(String key, String value) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        return variable;
    }
}
