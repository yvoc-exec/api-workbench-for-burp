package burp.auth;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class ClientCredentialsHandler {
    public TokenStore.TokenEntry execute(OAuth2Config config, MontoyaApi api) throws Exception {
        String body = "grant_type=client_credentials" +
                "&client_id=" + URLEncoder.encode(config.clientId, StandardCharsets.UTF_8) +
                "&client_secret=" + URLEncoder.encode(config.clientSecret, StandardCharsets.UTF_8);
        if (config.scope != null && !config.scope.isEmpty()) {
            body += "&scope=" + URLEncoder.encode(config.scope, StandardCharsets.UTF_8);
        }
        return executeTokenRequest(config, body, api);
    }

    static TokenStore.TokenEntry executeTokenRequest(OAuth2Config config, String body, MontoyaApi api) throws Exception {
        HttpService service = HttpService.httpService(extractHost(config.tokenUrl), extractPort(config.tokenUrl), config.tokenUrl.startsWith("https"));
        String path = extractPath(config.tokenUrl);
        String requestStr = "POST " + path + " HTTP/1.1\r\n" +
                "Host: " + service.host() + (service.port() != 443 && service.port() != 80 ? ":" + service.port() : "") + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                "\r\n" + body;

        HttpRequest request = HttpRequest.httpRequest(service, ByteArray.byteArray(requestStr.getBytes(StandardCharsets.UTF_8)));
        var response = api.http().sendRequest(request);

        if (response.response() == null) {
            throw new Exception("No response from token endpoint");
        }

        String respBody = response.response().bodyToString();
        JsonObject json;
        try {
            json = JsonParser.parseString(respBody).getAsJsonObject();
        } catch (Exception e) {
            // Non-JSON response (e.g., HTML error page, WAF block)
            String preview = respBody.length() > 200 ? respBody.substring(0, 200) + "..." : respBody;
            throw new Exception("OAuth2 token endpoint returned non-JSON response (status: " + 
                    response.response().statusCode() + "): " + preview);
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
}
