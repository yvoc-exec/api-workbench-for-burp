package burp.ui;

import burp.auth.OAuth2Config;
import burp.auth.OAuth2Manager;
import burp.auth.TokenStore;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class OAuth2Panel extends JPanel {
    private final OAuth2Manager manager;
    private JComboBox<String> grantTypeBox;
    private JTextField tokenUrlField;
    private JTextField authUrlField;
    private JTextField redirectUriField;
    private JTextField clientIdField;
    private JPasswordField clientSecretField;
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JTextField scopeField;
    private JCheckBox pkceBox;
    private JButton acquireBtn;
    private JButton refreshBtn;
    private JButton clearBtn;
    private JTextArea statusArea;
    private JTextField tokenPreviewField;

    public OAuth2Panel(OAuth2Manager manager) {
        this.manager = manager;
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(createFormPanel(), BorderLayout.NORTH);
        add(createStatusPanel(), BorderLayout.CENTER);
    }

    private JPanel createFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("OAuth2 Configuration"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Grant Type:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        grantTypeBox = new JComboBox<>(new String[]{"Client Credentials", "Password", "Authorization Code", "Refresh Token"});
        grantTypeBox.addActionListener(e -> updateFieldVisibility());
        panel.add(grantTypeBox, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(new JLabel("Token URL:"), gbc);
        gbc.gridx = 1;
        tokenUrlField = new JTextField("https://auth.example.com/oauth/token");
        panel.add(tokenUrlField, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        panel.add(new JLabel("Auth URL:"), gbc);
        gbc.gridx = 1;
        authUrlField = new JTextField("https://auth.example.com/authorize");
        authUrlField.setEnabled(false);
        panel.add(authUrlField, gbc);

        gbc.gridx = 0; gbc.gridy = 3;
        panel.add(new JLabel("Redirect URI:"), gbc);
        gbc.gridx = 1;
        redirectUriField = new JTextField("http://localhost:9876/callback");
        redirectUriField.setEnabled(false);
        panel.add(redirectUriField, gbc);

        gbc.gridx = 0; gbc.gridy = 4;
        panel.add(new JLabel("Client ID:"), gbc);
        gbc.gridx = 1;
        clientIdField = new JTextField();
        panel.add(clientIdField, gbc);

        gbc.gridx = 0; gbc.gridy = 5;
        panel.add(new JLabel("Client Secret:"), gbc);
        gbc.gridx = 1;
        clientSecretField = new JPasswordField();
        panel.add(clientSecretField, gbc);

        gbc.gridx = 0; gbc.gridy = 6;
        panel.add(new JLabel("Username:"), gbc);
        gbc.gridx = 1;
        usernameField = new JTextField();
        usernameField.setEnabled(false);
        panel.add(usernameField, gbc);

        gbc.gridx = 0; gbc.gridy = 7;
        panel.add(new JLabel("Password:"), gbc);
        gbc.gridx = 1;
        passwordField = new JPasswordField();
        passwordField.setEnabled(false);
        panel.add(passwordField, gbc);

        gbc.gridx = 0; gbc.gridy = 8;
        panel.add(new JLabel("Scope:"), gbc);
        gbc.gridx = 1;
        scopeField = new JTextField("api:read api:write");
        panel.add(scopeField, gbc);

        gbc.gridx = 0; gbc.gridy = 9;
        pkceBox = new JCheckBox("Use PKCE", true);
        pkceBox.setEnabled(false);
        gbc.gridx = 1;
        panel.add(pkceBox, gbc);

        // Buttons
        gbc.gridx = 0; gbc.gridy = 10; gbc.gridwidth = 2;
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        acquireBtn = new JButton("Acquire Token");
        acquireBtn.addActionListener(e -> acquireToken());
        refreshBtn = new JButton("Refresh Token");
        refreshBtn.addActionListener(e -> refreshToken());
        refreshBtn.setEnabled(false);
        clearBtn = new JButton("Clear Tokens");
        clearBtn.addActionListener(e -> {
            manager.clearTokens();
            updateStatus("Tokens cleared");
            refreshBtn.setEnabled(false);
            tokenPreviewField.setText("");
        });
        btnPanel.add(acquireBtn);
        btnPanel.add(refreshBtn);
        btnPanel.add(clearBtn);
        panel.add(btnPanel, gbc);

        return panel;
    }

    private JPanel createStatusPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Token Status"));

        tokenPreviewField = new JTextField();
        tokenPreviewField.setEditable(false);
        tokenPreviewField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(tokenPreviewField, BorderLayout.NORTH);

        statusArea = new JTextArea(10, 50);
        statusArea.setEditable(false);
        statusArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(statusArea), BorderLayout.CENTER);
        return panel;
    }

    private void updateFieldVisibility() {
        String grant = (String) grantTypeBox.getSelectedItem();
        boolean isAuthCode = "Authorization Code".equals(grant);
        boolean isPassword = "Password".equals(grant);
        boolean isRefresh = "Refresh Token".equals(grant);

        authUrlField.setEnabled(isAuthCode);
        redirectUriField.setEnabled(isAuthCode);
        pkceBox.setEnabled(isAuthCode);
        usernameField.setEnabled(isPassword);
        passwordField.setEnabled(isPassword);
        clientSecretField.setEnabled(!isAuthCode || clientSecretField.getPassword().length > 0);
    }

    private void acquireToken() {
        OAuth2Config config = buildConfig();
        if (!config.isValid()) {
            updateStatus("ERROR: Invalid configuration. Check required fields.");
            return;
        }

        SwingWorker<TokenStore.TokenEntry, String> worker = new SwingWorker<>() {
            @Override
            protected TokenStore.TokenEntry doInBackground() throws Exception {
                publish("Acquiring token via " + config.grantType + "...");
                return manager.acquireToken(config);
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                for (String s : chunks) updateStatus(s);
            }

            @Override
            protected void done() {
                try {
                    TokenStore.TokenEntry entry = get();
                    String preview = entry.accessToken.substring(0, Math.min(20, entry.accessToken.length())) + "...";
                    tokenPreviewField.setText("Access Token: " + preview + " | Expires: " + ((entry.expiresAt - System.currentTimeMillis()) / 1000) + "s");
                    refreshBtn.setEnabled(entry.refreshToken != null);
                    updateStatus("SUCCESS: Token acquired. Refresh token available: " + (entry.refreshToken != null));
                } catch (Exception e) {
                    updateStatus("FAILED: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void refreshToken() {
        OAuth2Config config = buildConfig();
        String key = TokenStore.makeKey(config);
        TokenStore.TokenEntry existing = TokenStore.get(key);
        if (existing == null || existing.refreshToken == null) {
            updateStatus("No refresh token available");
            return;
        }
        config.refreshToken = existing.refreshToken;
        config.grantType = OAuth2Config.GrantType.REFRESH_TOKEN;

        SwingWorker<TokenStore.TokenEntry, String> worker = new SwingWorker<>() {
            @Override
            protected TokenStore.TokenEntry doInBackground() throws Exception {
                publish("Refreshing token...");
                return manager.acquireToken(config);
            }
            @Override
            protected void done() {
                try {
                    TokenStore.TokenEntry entry = get();
                    updateStatus("Token refreshed. New expiry: " + ((entry.expiresAt - System.currentTimeMillis()) / 1000) + "s");
                } catch (Exception e) {
                    updateStatus("Refresh failed: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    private OAuth2Config buildConfig() {
        OAuth2Config config = new OAuth2Config();
        String grant = (String) grantTypeBox.getSelectedItem();
        switch (grant) {
            case "Client Credentials": config.grantType = OAuth2Config.GrantType.CLIENT_CREDENTIALS; break;
            case "Password": config.grantType = OAuth2Config.GrantType.PASSWORD; break;
            case "Authorization Code": config.grantType = OAuth2Config.GrantType.AUTHORIZATION_CODE; break;
            case "Refresh Token": config.grantType = OAuth2Config.GrantType.REFRESH_TOKEN; break;
        }
        config.tokenUrl = tokenUrlField.getText().trim();
        config.authUrl = authUrlField.getText().trim();
        config.redirectUri = redirectUriField.getText().trim();
        config.clientId = clientIdField.getText().trim();
        config.clientSecret = new String(clientSecretField.getPassword());
        config.username = usernameField.getText().trim();
        config.password = new String(passwordField.getPassword());
        config.scope = scopeField.getText().trim();
        config.usePkce = pkceBox.isSelected();
        return config;
    }

    public Map<String, String> getVariables() {
        Map<String, String> vars = new HashMap<>();
        OAuth2Config config = buildConfig();
        if (config.clientId != null && !config.clientId.isEmpty()) {
            vars.put("oauth2_client_id", config.clientId);
            vars.put("oauth2_client_secret", config.clientSecret);
            vars.put("oauth2_token_url", config.tokenUrl);
            vars.put("oauth2_scope", config.scope);
            vars.put("oauth2_grant", config.grantType.name().toLowerCase().replace("_", "-"));
            // Inject current token if available
            String key = TokenStore.makeKey(config);
            TokenStore.TokenEntry entry = TokenStore.get(key);
            if (entry != null && entry.accessToken != null) {
                vars.put("oauth2_access_token", entry.accessToken);
            }
        }
        return vars;
    }

    private void updateStatus(String msg) {
        SwingUtilities.invokeLater(() -> {
            statusArea.append(msg + "\n");
            statusArea.setCaretPosition(statusArea.getDocument().getLength());
        });
    }
}
