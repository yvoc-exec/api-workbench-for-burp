package burp.utils;

import burp.models.ApiRequest;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2PopulateHelperTest {

    @Test
    void infersTokenUrlFromUrlencodedTokenRequest() {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "https://auth.example.com/oauth/token";
        req.body = new ApiRequest.Body();
        req.body.mode = "urlencoded";
        req.body.urlencoded.add(new ApiRequest.Body.FormField("grant_type", "client_credentials"));
        req.body.urlencoded.add(new ApiRequest.Body.FormField("client_id", "abc"));

        Map<String, String> fields = OAuth2PopulateHelper.extractOAuth2Fields(req);

        assertThat(fields).containsEntry("oauth2_token_url", "https://auth.example.com/oauth/token");
        assertThat(fields).containsEntry("oauth2_grant", "client_credentials");
        assertThat(fields).containsEntry("oauth2_client_id", "abc");
    }

    @Test
    void infersTokenUrlFromRawUrlencodedBodyWithContentType() {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "https://login.example.com/oauth/start";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.contentType = "application/x-www-form-urlencoded";
        req.body.raw = "grant_type=refresh_token&refresh_token=abc";
        req.headers.add(new ApiRequest.Header("Content-Type", "application/x-www-form-urlencoded"));

        Map<String, String> fields = OAuth2PopulateHelper.extractOAuth2Fields(req);

        assertThat(fields).containsEntry("oauth2_token_url", "https://login.example.com/oauth/start");
    }

    @Test
    void infersTokenUrlFromUrlencodedBodyModeWithoutContentType() {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "https://auth.example.com/session";
        req.body = new ApiRequest.Body();
        req.body.mode = "urlencoded";
        req.body.urlencoded.add(new ApiRequest.Body.FormField("grant_type", "client_credentials"));
        req.body.urlencoded.add(new ApiRequest.Body.FormField("client_id", "abc"));

        Map<String, String> fields = OAuth2PopulateHelper.extractOAuth2Fields(req);

        assertThat(fields).containsEntry("oauth2_token_url", "https://auth.example.com/session");
        assertThat(fields).containsEntry("oauth2_grant", "client_credentials");
        assertThat(fields).containsEntry("oauth2_client_id", "abc");
    }

    @Test
    void doesNotOverwriteExplicitTokenUrlFromAuthMetadata() {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "https://wrong.example.com/oauth/token";
        req.auth = new ApiRequest.Auth();
        req.auth.type = "oauth2";
        req.auth.properties.put("accessTokenUrl", "https://auth.example.com/oauth/token");
        req.body = new ApiRequest.Body();
        req.body.mode = "urlencoded";
        req.body.urlencoded.add(new ApiRequest.Body.FormField("grant_type", "client_credentials"));

        Map<String, String> fields = OAuth2PopulateHelper.extractOAuth2Fields(req);

        assertThat(fields).containsEntry("oauth2_token_url", "https://auth.example.com/oauth/token");
    }

    @Test
    void doesNotInferTokenUrlForOrdinaryBearerRequest() {
        ApiRequest req = new ApiRequest();
        req.method = "GET";
        req.url = "https://api.example.com/users";
        req.headers.add(new ApiRequest.Header("Authorization", "Bearer {{accessToken}}"));

        Map<String, String> fields = OAuth2PopulateHelper.extractOAuth2Fields(req);

        assertThat(fields).doesNotContainKey("oauth2_token_url");
    }

    @Test
    void infersTokenUrlFromVariableUrlWhenPathLooksTokenLike() {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "{{authBaseUrl}}/oauth2/token";
        req.body = new ApiRequest.Body();
        req.body.mode = "urlencoded";
        req.body.urlencoded.add(new ApiRequest.Body.FormField("grant_type", "client_credentials"));

        Map<String, String> fields = OAuth2PopulateHelper.extractOAuth2Fields(req);

        assertThat(fields).containsEntry("oauth2_token_url", "{{authBaseUrl}}/oauth2/token");
    }

    @Test
    void doesNotInferFromOAuthLookingValueWhenStructuredFieldKeyIsNotOAuth() {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "https://api.example.com/session";
        req.body = new ApiRequest.Body();
        req.body.mode = "urlencoded";
        req.body.urlencoded.add(new ApiRequest.Body.FormField("note", "client_id=abc or code"));

        Map<String, String> fields = OAuth2PopulateHelper.extractOAuth2Fields(req);

        assertThat(fields).doesNotContainKey("oauth2_token_url");
    }

    @Test
    void doesNotInferFromRawBodyIncidentalCodeSubstring() {
        ApiRequest req = new ApiRequest();
        req.method = "POST";
        req.url = "https://api.example.com/users";
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.contentType = "application/x-www-form-urlencoded";
        req.body.raw = "zipcode=12345";
        req.headers.add(new ApiRequest.Header("Content-Type", "application/x-www-form-urlencoded"));

        Map<String, String> fields = OAuth2PopulateHelper.extractOAuth2Fields(req);

        assertThat(fields).doesNotContainKey("oauth2_token_url");
    }

    @Test
    void extractOAuth2FieldsResolvesPlaceholdersFromCollectionResolver() {
        ApiRequest req = new ApiRequest();
        req.name = "Get Token";
        req.url = "{{auth_base_url}}/oauth2/token";
        req.method = "POST";
        req.auth = new ApiRequest.Auth();
        req.auth.properties.put("accessTokenUrl", "{{auth_base_url}}/oauth2/token");
        req.body = new ApiRequest.Body();
        req.body.mode = "urlencoded";
        req.body.urlencoded.add(new ApiRequest.Body.FormField("grant_type", "client_credentials"));
        req.body.urlencoded.add(new ApiRequest.Body.FormField("client_id", "{{apim_client_id}}"));
        req.body.urlencoded.add(new ApiRequest.Body.FormField("client_secret", "{{apim_client_secret}}"));

        VariableResolver resolver = new VariableResolver();
        resolver.addCustomVariable("auth_base_url", "https://login.example.test");
        resolver.addCustomVariable("apim_client_id", "client-from-env");
        resolver.addCustomVariable("apim_client_secret", "secret-from-env");

        Map<String, String> fields = OAuth2PopulateHelper.extractOAuth2Fields(req, resolver);

        assertThat(fields)
                .containsEntry("oauth2_token_url", "https://login.example.test/oauth2/token")
                .containsEntry("oauth2_grant", "client_credentials")
                .containsEntry("oauth2_client_id", "client-from-env")
                .containsEntry("oauth2_client_secret", "secret-from-env");
    }

    @Test
    void extractOAuth2FieldsKeepsUnresolvedPlaceholderWhenResolverCannotResolveIt() {
        ApiRequest req = new ApiRequest();
        req.name = "Get Token";
        req.url = "{{auth_base_url}}/oauth2/token";
        req.method = "POST";
        req.body = new ApiRequest.Body();
        req.body.mode = "urlencoded";
        req.body.urlencoded.add(new ApiRequest.Body.FormField("grant_type", "client_credentials"));
        req.body.urlencoded.add(new ApiRequest.Body.FormField("client_id", "{{missing_client_id}}"));

        VariableResolver resolver = new VariableResolver();

        Map<String, String> fields = OAuth2PopulateHelper.extractOAuth2Fields(req, resolver);

        assertThat(fields)
                .containsEntry("oauth2_client_id", "{{missing_client_id}}")
                .containsEntry("oauth2_token_url", "{{auth_base_url}}/oauth2/token");
    }
}
