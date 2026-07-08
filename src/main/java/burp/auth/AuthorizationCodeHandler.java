package burp.auth;

import burp.api.montoya.MontoyaApi;
import burp.ui.DialogParentResolver;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.*;

/**
 * Authorization Code + PKCE flow with localhost callback listener.
 */
public class AuthorizationCodeHandler {
    private static final int CALLBACK_PORT = 9876;
    private static final String CALLBACK_PATH = "/callback";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final MontoyaApi api;

    public record CallbackEndpoint(String host, int port, String path) {}

    public AuthorizationCodeHandler(MontoyaApi api) {
        this.api = api;
    }

    public TokenStore.TokenEntry execute(OAuth2Config config) throws Exception {
        // Generate PKCE parameters
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String state = generateState();

        // Build auth URL
        String authUrl = config.authUrl +
                (config.authUrl.contains("?") ? "&" : "?") +
                "response_type=code" +
                "&client_id=" + URLEncoder.encode(config.clientId, StandardCharsets.UTF_8) +
                "&redirect_uri=" + URLEncoder.encode(config.redirectUri, StandardCharsets.UTF_8) +
                "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8) +
                "&code_challenge=" + URLEncoder.encode(codeChallenge, StandardCharsets.UTF_8) +
                "&code_challenge_method=S256" +
                (config.scope != null && !config.scope.isEmpty()
                    ? "&scope=" + URLEncoder.encode(config.scope, StandardCharsets.UTF_8)
                    : "");

        // Start localhost listener
        CallbackEndpoint callbackEndpoint = parseCallbackEndpoint(config.redirectUri);
        CompletableFuture<String> codeFuture = startCallbackListener(state, callbackEndpoint);

        // Open browser
        api.logging().logToOutput("Opening browser for OAuth2 authorization: " + config.authUrl);
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(new URI(authUrl));
        } else {
            // Fallback: show dialog with URL
            SwingUtilities.invokeLater(() -> {
                JTextField urlField = new JTextField(authUrl);
                urlField.setEditable(false);
                urlField.setColumns(60);
                JOptionPane.showMessageDialog(DialogParentResolver.parentComponent(null), urlField, "Authorize this application", JOptionPane.INFORMATION_MESSAGE);
            });
        }

        // Wait for callback (timeout 5 minutes)
        String code = codeFuture.get(5, TimeUnit.MINUTES);

        // Exchange code for token
        ClientCredentialsHandler.validateBodySecretIfNeeded(config);
        StringBuilder body = new StringBuilder();
        ClientCredentialsHandler.appendFormParam(body, "grant_type", "authorization_code", true);
        ClientCredentialsHandler.appendFormParam(body, "client_id", config.clientId, false);
        if (ClientCredentialsHandler.isBodyClientSecret(config)) {
            ClientCredentialsHandler.appendFormParam(body, "client_secret", config.clientSecret, false);
        }
        ClientCredentialsHandler.appendFormParam(body, "code", code, false);
        ClientCredentialsHandler.appendFormParam(body, "redirect_uri", config.redirectUri, false);
        ClientCredentialsHandler.appendFormParam(body, "code_verifier", codeVerifier, false);

        return ClientCredentialsHandler.executeTokenRequest(config, body.toString(), api);
    }

    static CallbackEndpoint parseCallbackEndpoint(String redirectUri) {
        try {
            URI uri = new URI(redirectUri != null && !redirectUri.isBlank()
                    ? redirectUri
                    : "http://localhost:9876/callback");
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("OAuth2 redirect URI must use http loopback for local callback flow");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("OAuth2 redirect URI missing host");
            }
            if (!isLoopbackHost(host)) {
                throw new IllegalArgumentException("OAuth2 redirect URI must use a loopback host");
            }
            int port = uri.getPort();
            if (port <= 0) {
                port = CALLBACK_PORT;
            }
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                path = CALLBACK_PATH;
            }
            return new CallbackEndpoint(host, port, path);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid OAuth2 redirect URI: " + e.getMessage(), e);
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String candidate = host;
        if (candidate.startsWith("[") && candidate.endsWith("]") && candidate.length() > 2) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        try {
            return InetAddress.getByName(candidate).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private CompletableFuture<String> startCallbackListener(String expectedState, CallbackEndpoint endpoint) {
        CompletableFuture<String> future = new CompletableFuture<>();
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(endpoint.port(), 1, InetAddress.getByName(endpoint.host()))) {
                server.setSoTimeout(300000); // 5 min timeout
                try (Socket client = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                     OutputStream out = client.getOutputStream()) {

                    String line = in.readLine();
                    String expectedPrefix = "GET " + endpoint.path();
                    if (line != null && line.startsWith(expectedPrefix)) {
                        String query = line.split(" ")[1].substring(endpoint.path().length());
                        if (query.startsWith("?")) query = query.substring(1);

                String code = null;
                String state = null;
                for (String param : query.split("&")) {
                    String[] kv = param.split("=");
                    if (kv.length == 2) {
                        if ("code".equals(kv[0])) code = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                        if ("state".equals(kv[0])) state = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    }
                }

                        // Send response to browser
                        String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" +
                                "<html><body><h2>Authorization complete</h2><p>You can close this window.</p></body></html>";
                        out.write(response.getBytes(StandardCharsets.UTF_8));

                        if (code != null && expectedState.equals(state)) {
                            future.complete(code);
                        } else {
                            future.completeExceptionally(new Exception("Invalid state or missing code"));
                        }
                    }
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, "OAuth2-Callback").start();
        return future;
    }

    private String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateCodeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private String generateState() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
