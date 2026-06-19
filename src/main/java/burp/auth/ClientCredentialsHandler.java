package burp.auth;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;

public class ClientCredentialsHandler {
    public TokenStore.TokenEntry execute(OAuth2Config config, MontoyaApi api) throws Exception {
        validateBodySecretIfNeeded(config);
        StringBuilder body = new StringBuilder();
        appendFormParam(body, "grant_type", "client_credentials", true);
        appendFormParam(body, "client_id", config.clientId, false);
        if (isBodyClientSecret(config)) {
            appendFormParam(body, "client_secret", config.clientSecret, false);
        }
        if (config.scope != null && !config.scope.isEmpty()) {
            appendFormParam(body, "scope", config.scope, false);
        }
        return executeTokenRequest(config, body.toString(), api);
    }

    static TokenStore.TokenEntry executeTokenRequest(OAuth2Config config, String body, MontoyaApi api) throws Exception {
        String mode = normalizeClientAuthMode(config);

        if ("basic".equals(mode)) {
            if (config.clientId == null || config.clientId.isEmpty() || config.clientSecret == null || config.clientSecret.isEmpty()) {
                throw new Exception("OAuth2 client_auth=basic requires oauth2_client_id and oauth2_client_secret");
            }
        }
        String authHeaderValue = null;
        if ("basic".equals(mode)) {
            String creds = config.clientId + ":" + config.clientSecret;
            String basic = java.util.Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
            authHeaderValue = "Basic " + basic;
        }
        TokenEndpointResponse response = sendTokenRequest(config, body, api, authHeaderValue);

        if (response.body == null) {
            throw new Exception("No response from token endpoint");
        }

        String respBody = response.body;
        JsonObject json;
        try {
            json = JsonParser.parseString(respBody).getAsJsonObject();
        } catch (Exception e) {
            String preview = respBody.length() > 200 ? respBody.substring(0, 200) + "..." : respBody;
            throw new Exception("OAuth2 token endpoint returned non-JSON response (status: " +
                    response.statusCode + "): " + preview);
        }

        if (json.has("error")) {
            String error = json.get("error").isJsonPrimitive() ? json.get("error").getAsString() : "unknown";
            String desc = json.has("error_description") && json.get("error_description").isJsonPrimitive()
                    ? json.get("error_description").getAsString() : "";
            throw new Exception("OAuth2 error: " + error + (desc.isEmpty() ? "" : " - " + desc));
        }

        TokenStore.TokenEntry entry = new TokenStore.TokenEntry();
        entry.accessToken = json.has("access_token") ? json.get("access_token").getAsString() : null;
        entry.refreshToken = json.has("refresh_token") ? json.get("refresh_token").getAsString() : null;
        entry.tokenType = json.has("token_type") ? json.get("token_type").getAsString() : "Bearer";
        entry.scope = json.has("scope") ? json.get("scope").getAsString() : config.scope;
        entry.acquiredAt = System.currentTimeMillis();

        int expiresIn = json.has("expires_in") ? json.get("expires_in").getAsInt() : 3600;
        entry.expiresAt = entry.acquiredAt + (expiresIn * 1000L);

        if (entry.accessToken == null || entry.accessToken.isEmpty()) {
            throw new Exception("No access_token in response");
        }

        return entry;
    }

    private static TokenEndpointResponse sendTokenRequest(OAuth2Config config,
                                                          String body,
                                                          MontoyaApi api,
                                                          String authHeaderValue) throws Exception {
        if (api != null) {
            try {
                return sendTokenRequestViaMontoya(config, body, api, authHeaderValue);
            } catch (NullPointerException e) {
                if (!isMissingMontoyaFactory(e)) {
                    throw e;
                }
            }
        }
        return sendTokenRequestViaJdk(config, body, authHeaderValue);
    }

    private static TokenEndpointResponse sendTokenRequestViaMontoya(OAuth2Config config,
                                                                    String body,
                                                                    MontoyaApi api,
                                                                    String authHeaderValue) {
        HttpService service = HttpService.httpService(extractHost(config.tokenUrl), extractPort(config.tokenUrl), config.tokenUrl.startsWith("https"));
        String path = extractPath(config.tokenUrl);
        String authHeader = authHeaderValue != null && !authHeaderValue.isBlank()
                ? "Authorization: " + authHeaderValue + "\r\n"
                : "";
        String requestStr = "POST " + path + " HTTP/1.1\r\n" +
                "Host: " + service.host() + (service.port() != 443 && service.port() != 80 ? ":" + service.port() : "") + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                authHeader +
                "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                "\r\n" + body;

        HttpRequest request = HttpRequest.httpRequest(service, ByteArray.byteArray(requestStr.getBytes(StandardCharsets.UTF_8)));
        var response = api.http().sendRequest(request);
        return response.response() == null
                ? new TokenEndpointResponse(0, null)
                : new TokenEndpointResponse(response.response().statusCode(), response.response().bodyToString());
    }

    private static TokenEndpointResponse sendTokenRequestViaJdk(OAuth2Config config,
                                                                String body,
                                                                String authHeaderValue) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder(URI.create(config.tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (authHeaderValue != null && !authHeaderValue.isBlank()) {
            requestBuilder.header("Authorization", authHeaderValue);
        }
        var response = client.send(requestBuilder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new TokenEndpointResponse(response.statusCode(), response.body());
    }

    private static boolean isMissingMontoyaFactory(NullPointerException e) {
        String message = e.getMessage();
        return message != null && message.contains("ObjectFactoryLocator.FACTORY");
    }

    static String normalizeClientAuthMode(OAuth2Config config) {
        String mode = config.clientAuth == null ? "body" : config.clientAuth.trim().toLowerCase();
        if ("prefer_basic".equals(mode)) mode = "basic";
        if (!"basic".equals(mode) && !"none".equals(mode)) mode = "body";
        return mode;
    }

    static boolean isBasicAuth(OAuth2Config config) {
        return "basic".equals(normalizeClientAuthMode(config));
    }

    static boolean isBodyClientSecret(OAuth2Config config) {
        return "body".equals(normalizeClientAuthMode(config));
    }

    static boolean isNoClientAuth(OAuth2Config config) {
        return "none".equals(normalizeClientAuthMode(config));
    }

    static void validateBodySecretIfNeeded(OAuth2Config config) throws Exception {
        if (isBodyClientSecret(config) && (config.clientSecret == null || config.clientSecret.isEmpty())) {
            throw new Exception("OAuth2 client_auth=body requires oauth2_client_secret");
        }
    }

    static void appendFormParam(StringBuilder sb, String key, String value, boolean first) {
        if (!first) sb.append('&');
        try {
            sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
            if (value != null) {
                sb.append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            sb.append(key);
            if (value != null) sb.append('=').append(value);
        }
    }

    private static String extractHost(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            return u.getHost();
        } catch (Exception e) { return "localhost"; }
    }

    private static int extractPort(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            int port = u.getPort();
            return port == -1 ? (u.getProtocol().equals("https") ? 443 : 80) : port;
        } catch (Exception e) { return 443; }
    }

    private static String extractPath(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String path = u.getPath();
            String query = u.getQuery();
            return path + (query != null ? "?" + query : "");
        } catch (Exception e) { return "/"; }
    }

    private record TokenEndpointResponse(int statusCode, String body) {
    }
}
