package burp.auth;

import burp.api.montoya.MontoyaApi;
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
    private final MontoyaApi api;

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
                "&code_challenge_method=S256";
        if (config.scope != null && !config.scope.isEmpty()) {
            authUrl += "&scope=" + URLEncoder.encode(config.scope, StandardCharsets.UTF_8);
        }

        // Start localhost listener
        CompletableFuture<String> codeFuture = startCallbackListener(state);

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
                JOptionPane.showMessageDialog(null, urlField, "Authorize this application", JOptionPane.INFORMATION_MESSAGE);
            });
        }

        // Wait for callback (timeout 5 minutes)
        String code = codeFuture.get(5, TimeUnit.MINUTES);

        // Exchange code for token
        String body = "grant_type=authorization_code" +
                "&client_id=" + URLEncoder.encode(config.clientId, StandardCharsets.UTF_8) +
                "&client_secret=" + URLEncoder.encode(config.clientSecret, StandardCharsets.UTF_8) +
                "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) +
                "&redirect_uri=" + URLEncoder.encode(config.redirectUri, StandardCharsets.UTF_8) +
                "&code_verifier=" + URLEncoder.encode(codeVerifier, StandardCharsets.UTF_8);

        return ClientCredentialsHandler.executeTokenRequest(config, body, api);
    }

    private CompletableFuture<String> startCallbackListener(String expectedState) {
        CompletableFuture<String> future = new CompletableFuture<>();
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(CALLBACK_PORT, 1, InetAddress.getByName("127.0.0.1"))) {
                server.setSoTimeout(300000); // 5 min timeout
                try (Socket client = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                     OutputStream out = client.getOutputStream()) {

                    String line = in.readLine();
                    if (line != null && line.startsWith("GET " + CALLBACK_PATH)) {
                        String query = line.split(" ")[1].substring(CALLBACK_PATH.length());
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
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateCodeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private String generateState() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
