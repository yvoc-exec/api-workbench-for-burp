package burp.ui;

import burp.auth.OAuth2Config;
import burp.auth.OAuth2Manager;
import burp.auth.TokenStore;

import burp.models.ApiCollection;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class OAuth2Panel extends JPanel {
    public interface VariablesChangeListener {
        void onVariablesChanged(Map<String, String> vars, boolean replaceMode);
    }
    public interface TokenAcquiredListener {
        void onTokenAcquired(TokenStore.TokenEntry entry, ApiCollection collection, Map<String, String> oauth2Vars);
    }
    public interface ClearTokensListener {
        void onClearTokensRequested();
    }

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
    private JButton bindBtn;
    private JButton clearBtn;
    private JButton populateBtn;
    private JCheckBox autoBindCheck;
    private JTextArea statusArea;
    private JTextField tokenPreviewField;
    private boolean editable = true;
    private boolean suppressChangeNotifications = false;
    private TokenStore.TokenEntry lastAcquiredToken;
    private VariablesChangeListener variablesChangeListener;
    private TokenAcquiredListener tokenAcquiredListener;
    private ClearTokensListener clearTokensListener;
    private Supplier<ApiCollection> tokenAcquiredCollectionSupplier;

    public OAuth2Panel(OAuth2Manager manager) {
        this.manager = manager;
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(createFormPanel(), BorderLayout.NORTH);
        add(createStatusPanel(), BorderLayout.CENTER);
        attachLiveSyncListeners();
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
        populateBtn = new JButton("Populate from Request");
        btnPanel.add(populateBtn);

        acquireBtn = new JButton("Acquire Token");
        acquireBtn.addActionListener(e -> acquireToken());
        btnPanel.add(acquireBtn);

        bindBtn = new JButton("Bind Token");
        bindBtn.setEnabled(false);
        btnPanel.add(bindBtn);

        clearBtn = new JButton("Clear Tokens");
        clearBtn.addActionListener(e -> {
            manager.clearTokens();
            updateStatus("Tokens cleared");
            tokenPreviewField.setText("");
            lastAcquiredToken = null;
            bindBtn.setEnabled(false);
            if (clearTokensListener != null) {
                clearTokensListener.onClearTokensRequested();
            }
            notifyVariablesChanged(true);
        });
        btnPanel.add(clearBtn);
        gbc.gridy = 11;
        panel.add(btnPanel, gbc);

        gbc.gridx = 0; gbc.gridy = 12; gbc.gridwidth = 2;
        autoBindCheck = new JCheckBox("Auto-bind token to Active Environment", true);
        panel.add(autoBindCheck, gbc);

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

    public void setEditable(boolean editable) {
        this.editable = editable;
        grantTypeBox.setEnabled(editable);
        tokenUrlField.setEnabled(editable);
        authUrlField.setEnabled(editable);
        redirectUriField.setEnabled(editable);
        clientIdField.setEnabled(editable);
        clientSecretField.setEnabled(editable);
        usernameField.setEnabled(editable);
        passwordField.setEnabled(editable);
        scopeField.setEnabled(editable);
        pkceBox.setEnabled(editable);
        acquireBtn.setEnabled(editable);
        bindBtn.setEnabled(editable && lastAcquiredToken != null);
        clearBtn.setEnabled(editable);
        populateBtn.setEnabled(editable);
        autoBindCheck.setEnabled(editable);
        if (editable) {
            updateFieldVisibility();
        }
    }

    private void updateFieldVisibility() {
        if (!editable) return;
        String grant = (String) grantTypeBox.getSelectedItem();
        boolean isAuthCode = "Authorization Code".equals(grant);
        boolean isPassword = "Password".equals(grant);
        authUrlField.setEnabled(isAuthCode);
        redirectUriField.setEnabled(isAuthCode);
        pkceBox.setEnabled(isAuthCode);
        usernameField.setEnabled(isPassword);
        passwordField.setEnabled(isPassword);
        clientSecretField.setEnabled(!isAuthCode || clientSecretField.getPassword().length > 0);
    }

    private void attachLiveSyncListeners() {
        grantTypeBox.addActionListener(e -> notifyVariablesChanged(true));
        pkceBox.addActionListener(e -> notifyVariablesChanged(true));

        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { notifyVariablesChanged(true); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { notifyVariablesChanged(true); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { notifyVariablesChanged(true); }
        };
        tokenUrlField.getDocument().addDocumentListener(dl);
        authUrlField.getDocument().addDocumentListener(dl);
        redirectUriField.getDocument().addDocumentListener(dl);
        clientIdField.getDocument().addDocumentListener(dl);
        clientSecretField.getDocument().addDocumentListener(dl);
        usernameField.getDocument().addDocumentListener(dl);
        passwordField.getDocument().addDocumentListener(dl);
        scopeField.getDocument().addDocumentListener(dl);
    }

    private void acquireToken() {
        OAuth2Config config = buildConfig();
        if (!config.isValid()) {
            updateStatus("ERROR: Invalid configuration. Check required fields.");
            return;
        }
        ApiCollection targetCollection = tokenAcquiredCollectionSupplier != null ? tokenAcquiredCollectionSupplier.get() : null;
        appendRequestSummary(config);

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
                    lastAcquiredToken = entry;
                    bindBtn.setEnabled(editable && entry != null && entry.accessToken != null && !entry.accessToken.isBlank());
                    if (entry.accessToken == null || entry.accessToken.isBlank()) {
                        updateStatus("FAILED: Token acquisition returned no access token.");
                        return;
                    }
                    tokenPreviewField.setText(entry.accessToken);
                    appendResponseSummary(entry);
                    Map<String, String> vars = getVariables();
                    vars.put("oauth2_access_token", entry.accessToken);
                    if (entry.refreshToken != null && !entry.refreshToken.isBlank()) {
                        vars.put("oauth2_refresh_token", entry.refreshToken);
                    }
                    if (entry.tokenType != null && !entry.tokenType.isBlank()) {
                        vars.put("oauth2_token_type", entry.tokenType);
                    }
                    if (entry.scope != null && !entry.scope.isBlank()) {
                        vars.put("oauth2_scope", entry.scope);
                    }
                    if (entry.expiresAt > 0) {
                        long expiresInSeconds = Math.max(0, (entry.expiresAt - System.currentTimeMillis()) / 1000);
                        vars.put("oauth2_expires_in", String.valueOf(expiresInSeconds));
                    }
                    if (tokenAcquiredListener != null) {
                        tokenAcquiredListener.onTokenAcquired(entry, targetCollection, vars);
                    }
                } catch (Exception e) {
                    updateStatus("FAILED: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    public void setTokenAcquiredListener(TokenAcquiredListener listener) {
        this.tokenAcquiredListener = listener;
    }

    public void setTokenAcquiredCollectionSupplier(Supplier<ApiCollection> supplier) {
        this.tokenAcquiredCollectionSupplier = supplier;
    }

    public TokenStore.TokenEntry getLastAcquiredToken() {
        return lastAcquiredToken;
    }

    public void setLastAcquiredToken(TokenStore.TokenEntry entry) {
        this.lastAcquiredToken = entry;
        if (bindBtn != null) {
            bindBtn.setEnabled(editable && entry != null && entry.accessToken != null && !entry.accessToken.isBlank());
        }
    }

    public JButton getBindTokenButton() {
        return bindBtn;
    }

    public JCheckBox getAutoBindCheckBox() {
        return autoBindCheck;
    }

    public boolean isAutoBindSelected() {
        return autoBindCheck != null && autoBindCheck.isSelected();
    }

    public void setBindTokenEnabled(boolean enabled) {
        if (bindBtn != null) {
            bindBtn.setEnabled(editable && enabled && lastAcquiredToken != null && lastAcquiredToken.accessToken != null && !lastAcquiredToken.accessToken.isBlank());
        }
    }

    public void setClearTokensListener(ClearTokensListener listener) {
        this.clearTokensListener = listener;
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

    public JButton getPopulateButton() {
        return populateBtn;
    }

    public void populateFromOAuth2Map(Map<String, String> vars) {
        populateFromOAuth2Map(vars, null);
    }

    public void populateFromOAuth2Map(Map<String, String> vars, Runnable afterPopulate) {
        SwingUtilities.invokeLater(() -> {
            suppressChangeNotifications = true;
            try {
                grantTypeBox.setSelectedItem("Client Credentials");
                tokenUrlField.setText("");
                authUrlField.setText("");
                redirectUriField.setText("");
                clientIdField.setText("");
                clientSecretField.setText("");
                usernameField.setText("");
                passwordField.setText("");
                scopeField.setText("");
                pkceBox.setSelected(true);
                tokenPreviewField.setText("");

                if (vars == null) {
                    updateFieldVisibility();
                    return;
                }
                String grant = vars.get("oauth2_grant");
                if (grant != null) {
                    switch (grant.toLowerCase()) {
                        case "client_credentials":
                            grantTypeBox.setSelectedItem("Client Credentials");
                            break;
                        case "password":
                            grantTypeBox.setSelectedItem("Password");
                            break;
                        case "authorization_code":
                            grantTypeBox.setSelectedItem("Authorization Code");
                            break;
                        case "refresh_token":
                            grantTypeBox.setSelectedItem("Refresh Token");
                            break;
                    }
                }
                if (vars.containsKey("oauth2_token_url")) tokenUrlField.setText(vars.get("oauth2_token_url"));
                if (vars.containsKey("oauth2_auth_url")) authUrlField.setText(vars.get("oauth2_auth_url"));
                if (vars.containsKey("oauth2_redirect_uri")) redirectUriField.setText(vars.get("oauth2_redirect_uri"));
                if (vars.containsKey("oauth2_client_id")) clientIdField.setText(vars.get("oauth2_client_id"));
                if (vars.containsKey("oauth2_client_secret")) clientSecretField.setText(vars.get("oauth2_client_secret"));
                if (vars.containsKey("oauth2_username")) usernameField.setText(vars.get("oauth2_username"));
                if (vars.containsKey("oauth2_password")) passwordField.setText(vars.get("oauth2_password"));
                if (vars.containsKey("oauth2_scope")) scopeField.setText(vars.get("oauth2_scope"));
                if (vars.containsKey("oauth2_use_pkce")) {
                    pkceBox.setSelected(Boolean.parseBoolean(vars.get("oauth2_use_pkce")));
                }
                if (vars.containsKey("oauth2_access_token")) {
                    String t = vars.get("oauth2_access_token");
                    if (t != null && !t.isEmpty()) {
                        tokenPreviewField.setText("Access Token: " + t.substring(0, Math.min(20, t.length())) + "...");
                    }
                }
                updateFieldVisibility();
            } finally {
                suppressChangeNotifications = false;
                if (afterPopulate != null) {
                    try {
                        afterPopulate.run();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    public void setVariablesChangeListener(VariablesChangeListener listener) {
        this.variablesChangeListener = listener;
    }

    public void appendStatus(String msg) {
        updateStatus(msg);
    }

    private void notifyVariablesChanged(boolean replaceMode) {
        if (suppressChangeNotifications || !editable || variablesChangeListener == null) return;
        try {
            variablesChangeListener.onVariablesChanged(getVariables(), replaceMode);
        } catch (Exception ignored) {}
    }

    private void appendRequestSummary(OAuth2Config config) {
        if (config == null) {
            return;
        }
        updateStatus("OAuth2 Request:");
        updateStatus("  Grant: " + config.grantType);
        updateStatus("  Token URL: " + safe(config.tokenUrl));
        updateStatus("  Auth URL: " + safe(config.authUrl));
        updateStatus("  Client ID: " + safe(config.clientId));
        updateStatus("  Scope: " + safe(config.scope));
        updateStatus("  PKCE: " + config.usePkce);
    }

    private void appendResponseSummary(TokenStore.TokenEntry entry) {
        if (entry == null) {
            updateStatus("OAuth2 Response:");
            updateStatus("  Token acquired: no");
            return;
        }
        updateStatus("OAuth2 Response:");
        updateStatus("  Token acquired: yes");
        updateStatus("  Access Token: " + safe(entry.accessToken));
        updateStatus("  Refresh Token: " + safe(entry.refreshToken));
        updateStatus("  Token Type: " + safe(entry.tokenType));
        if (entry.expiresAt > 0) {
            long expiresInSeconds = Math.max(0, (entry.expiresAt - System.currentTimeMillis()) / 1000);
            updateStatus("  Expires In: " + expiresInSeconds + "s");
        }
        if (entry.scope != null && !entry.scope.isBlank()) {
            updateStatus("  Scope: " + entry.scope);
        }
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    public Map<String, String> getVariables() {
        Map<String, String> vars = new HashMap<>();
        OAuth2Config config = buildConfig();
        if (config.clientId != null && !config.clientId.isEmpty()) {
            vars.put("oauth2_client_id", config.clientId);
            if (config.clientSecret != null) vars.put("oauth2_client_secret", config.clientSecret);
            if (config.tokenUrl != null) vars.put("oauth2_token_url", config.tokenUrl);
            if (config.scope != null) vars.put("oauth2_scope", config.scope);
            // Normalize grant type to underscore style (client_credentials, authorization_code, etc.)
            vars.put("oauth2_grant", config.grantType.name().toLowerCase().replace("-", "_"));

            // Expanded field export
            if (config.username != null && !config.username.isEmpty()) {
                vars.put("oauth2_username", config.username);
            }
            if (config.password != null && !config.password.isEmpty()) {
                vars.put("oauth2_password", config.password);
            }
            if (config.authUrl != null && !config.authUrl.isEmpty()) {
                vars.put("oauth2_auth_url", config.authUrl);
            }
            if (config.redirectUri != null && !config.redirectUri.isEmpty()) {
                vars.put("oauth2_redirect_uri", config.redirectUri);
            }
            if (config.refreshToken != null && !config.refreshToken.isEmpty()) {
                vars.put("oauth2_refresh_token", config.refreshToken);
            }
            // Inject current token if available
            String key = TokenStore.makeKey(config);
            TokenStore.TokenEntry entry = TokenStore.get(key);
            if (entry != null) {
                if (entry.accessToken != null) {
                    vars.put("oauth2_access_token", entry.accessToken);
                }
                if (entry.refreshToken != null) {
                    vars.put("oauth2_refresh_token", entry.refreshToken);
                }
            }
            vars.put("oauth2_use_pkce", String.valueOf(config.usePkce));
            // Default client auth mode; extensions can override via vars tab
            vars.putIfAbsent("oauth2_client_auth", "body");
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
