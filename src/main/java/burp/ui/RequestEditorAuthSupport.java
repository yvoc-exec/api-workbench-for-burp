package burp.ui;

import burp.models.ApiRequest;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Encapsulates auth-field orchestration for the request editor.
 */
final class RequestEditorAuthSupport {

    private RequestEditorAuthSupport() {
    }

    static final class AuthUi {
        final JPanel authFieldsPanel;
        final Map<String, JTextField> authFields = new HashMap<>();
        final Runnable refreshResolvedMirror;

        AuthUi(JPanel authFieldsPanel, Runnable refreshResolvedMirror) {
            this.authFieldsPanel = authFieldsPanel;
            this.refreshResolvedMirror = refreshResolvedMirror;
        }
    }

    static void rebuildAuthFields(AuthUi authUi,
                                  String selectedType,
                                  ApiRequest currentRequest,
                                  ApiRequest.Auth sourceAuth) {
        authUi.authFieldsPanel.removeAll();
        authUi.authFields.clear();

        boolean readOnly = "inherit".equals(selectedType);
        String displayType = selectedType;
        if (readOnly) {
            displayType = currentRequest != null && currentRequest.auth != null && currentRequest.auth.type != null
                    ? currentRequest.auth.type.toLowerCase(Locale.ROOT)
                    : null;
        }

        if ("bearer".equals(displayType)) {
            addAuthField(authUi, "token", "Token", readOnly);
        } else if ("basic".equals(displayType)) {
            addAuthField(authUi, "username", "Username", readOnly);
            addAuthField(authUi, "password", "Password", readOnly);
        } else if ("apikey".equals(displayType)) {
            addAuthField(authUi, "key", "Key Name", readOnly);
            addAuthField(authUi, "value", "Key Value", readOnly);
            addAuthField(authUi, "in", "In (header/query/cookie)", readOnly);
        } else if ("oauth2".equals(displayType)) {
            addAuthField(authUi, "grantType", "Grant Type", readOnly);
            addAuthField(authUi, "accessTokenUrl", "Token URL", readOnly);
            addAuthField(authUi, "authorizationUrl", "Authorization URL", readOnly);
            addAuthField(authUi, "redirectUri", "Redirect URI", readOnly);
            addAuthField(authUi, "clientId", "Client ID", readOnly);
            addAuthField(authUi, "clientSecret", "Client Secret", readOnly);
            addAuthField(authUi, "username", "Username", readOnly);
            addAuthField(authUi, "password", "Password", readOnly);
            addAuthField(authUi, "scope", "Scope", readOnly);
            addAuthField(authUi, "refreshToken", "Refresh Token", readOnly);
            addAuthField(authUi, "code", "Authorization Code", readOnly);
            addAuthField(authUi, "codeVerifier", "Code Verifier", readOnly);
            addAuthField(authUi, "clientAuth", "Client Auth (body/basic/none)", readOnly);
            addAuthField(authUi, "accessToken", "Access Token", readOnly);
        }

        populateAuthFields(authUi, sourceAuth);
        authUi.authFieldsPanel.revalidate();
        authUi.authFieldsPanel.repaint();
        authUi.refreshResolvedMirror.run();
    }

    static ApiRequest.Auth buildAuthFromFields(AuthUi authUi, String authType) {
        String normalized = normalizeEditorSelection(authType);
        if ("inherit".equals(normalized)) {
            return null;
        }
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = normalized;
        for (Map.Entry<String, JTextField> e : authUi.authFields.entrySet()) {
            String val = e.getValue().getText().trim();
            if (!val.isEmpty()) {
                auth.properties.put(e.getKey(), val);
            }
        }
        return auth;
    }

    static String normalizeEditorSelection(String authType) {
        if (authType == null || authType.isBlank()) {
            return "inherit";
        }
        String normalized = authType.trim().toLowerCase(Locale.ROOT);
        if ("inherit".equals(normalized) || "none".equals(normalized) || "bearer".equals(normalized)
                || "basic".equals(normalized) || "apikey".equals(normalized) || "oauth2".equals(normalized)) {
            return normalized;
        }
        return "inherit";
    }

    private static void addAuthField(AuthUi authUi, String key, String label, boolean readOnly) {
        authUi.authFieldsPanel.add(new JLabel(label));
        JTextField field = new JTextField();
        field.setEnabled(!readOnly);
        field.getDocument().addDocumentListener(new SimpleDocumentListener(authUi.refreshResolvedMirror));
        authUi.authFields.put(key, field);
        authUi.authFieldsPanel.add(field);
    }

    private static void populateAuthFields(AuthUi authUi, ApiRequest.Auth sourceAuth) {
        if (sourceAuth == null || sourceAuth.properties == null || sourceAuth.properties.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : sourceAuth.properties.entrySet()) {
            JTextField f = authUi.authFields.get(e.getKey());
            if (f != null) {
                f.setText(e.getValue());
            }
        }
    }

    private static final class SimpleDocumentListener implements DocumentListener {
        private final Runnable onChange;

        private SimpleDocumentListener(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            onChange.run();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            onChange.run();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            onChange.run();
        }
    }
}
