package burp.ui;

import burp.models.ApiRequest;
import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

final class AuthSettingsDialog extends JDialog {
    static final class Result {
        final String mode;
        final ApiRequest.Auth auth;

        Result(String mode, ApiRequest.Auth auth) {
            this.mode = mode;
            this.auth = auth;
        }
    }

    private final JComboBox<String> modeBox;
    private final JPanel fieldsPanel;
    private final Map<String, JTextField> fields = new LinkedHashMap<>();
    private Result result;

    static Result showDialog(Component parent, String title, boolean allowInherit, String initialMode, ApiRequest.Auth initialAuth) {
        Window owner = parent != null ? SwingUtilities.getWindowAncestor(parent) : null;
        AuthSettingsDialog dialog = new AuthSettingsDialog(owner, title, allowInherit, initialMode, initialAuth);
        dialog.setVisible(true);
        return dialog.result;
    }

    private AuthSettingsDialog(Window owner, String title, boolean allowInherit, String initialMode, ApiRequest.Auth initialAuth) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(root, BorderLayout.CENTER);

        String[] modes = allowInherit
                ? new String[]{"inherit", "none", "bearer", "basic", "apikey", "oauth2"}
                : new String[]{"none", "bearer", "basic", "apikey", "oauth2"};
        modeBox = new JComboBox<>(modes);
        modeBox.addActionListener(e -> rebuildFields(initialAuth));

        JPanel top = new JPanel(new BorderLayout(5, 5));
        top.add(new JLabel("Auth mode:"), BorderLayout.WEST);
        top.add(modeBox, BorderLayout.CENTER);
        root.add(top, BorderLayout.NORTH);

        fieldsPanel = new JPanel(new GridLayout(0, 2, 6, 6));
        root.add(fieldsPanel, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> {
            result = null;
            dispose();
        });
        JButton okBtn = new JButton("OK");
        okBtn.addActionListener(e -> {
            result = buildResult();
            dispose();
        });
        buttons.add(cancelBtn);
        buttons.add(okBtn);
        root.add(buttons, BorderLayout.SOUTH);

        modeBox.setSelectedItem(normalizeInitialMode(initialMode, allowInherit));
        rebuildFields(initialAuth);

        setSize(460, 360);
        setLocationRelativeTo(owner);
    }

    private String normalizeInitialMode(String initialMode, boolean allowInherit) {
        String mode = initialMode != null ? initialMode.trim().toLowerCase() : "";
        if (allowInherit && "inherit".equals(mode)) {
            return "inherit";
        }
        if ("none".equals(mode) || "bearer".equals(mode) || "basic".equals(mode)
                || "apikey".equals(mode) || "oauth2".equals(mode)) {
            return mode;
        }
        if (allowInherit) {
            return "inherit";
        }
        return "none";
    }

    private void rebuildFields(ApiRequest.Auth initialAuth) {
        fieldsPanel.removeAll();
        fields.clear();
        String selection = (String) modeBox.getSelectedItem();
        if (selection == null || "inherit".equals(selection) || "none".equals(selection)) {
            JLabel info = new JLabel("No auth fields required.");
            info.setForeground(Color.GRAY);
            fieldsPanel.add(info);
            fieldsPanel.add(new JLabel(""));
        } else {
            addAuthFields(selection);
            populateFields(initialAuth);
        }
        fieldsPanel.revalidate();
        fieldsPanel.repaint();
    }

    private void addAuthFields(String authType) {
        switch (authType) {
            case "bearer" -> addField("token", "Token");
            case "basic" -> {
                addField("username", "Username");
                addField("password", "Password");
            }
            case "apikey" -> {
                addField("key", "Key Name");
                addField("value", "Key Value");
                addField("in", "In (header/query/cookie)");
            }
            case "oauth2" -> {
                addField("grantType", "Grant Type");
                addField("accessTokenUrl", "Token URL");
                addField("authorizationUrl", "Authorization URL");
                addField("redirectUri", "Redirect URI");
                addField("clientId", "Client ID");
                addField("clientSecret", "Client Secret");
                addField("username", "Username");
                addField("password", "Password");
                addField("scope", "Scope");
                addField("refreshToken", "Refresh Token");
                addField("code", "Authorization Code");
                addField("codeVerifier", "Code Verifier");
                addField("clientAuth", "Client Auth (body/basic/none)");
                addField("accessToken", "Access Token");
            }
            default -> {
                // no-op
            }
        }
    }

    private void addField(String key, String label) {
        fieldsPanel.add(new JLabel(label));
        JTextField field = new JTextField();
        fields.put(key, field);
        fieldsPanel.add(field);
    }

    private void populateFields(ApiRequest.Auth initialAuth) {
        if (initialAuth == null || initialAuth.properties == null) {
            return;
        }
        for (Map.Entry<String, String> entry : initialAuth.properties.entrySet()) {
            JTextField field = fields.get(entry.getKey());
            if (field != null) {
                field.setText(entry.getValue());
            }
        }
    }

    private Result buildResult() {
        String selection = (String) modeBox.getSelectedItem();
        if (selection == null || selection.isBlank() || "inherit".equals(selection)) {
            return new Result("inherit", null);
        }
        if ("none".equals(selection)) {
            ApiRequest.Auth none = new ApiRequest.Auth();
            none.type = "none";
            return new Result("none", none);
        }
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = selection;
        for (Map.Entry<String, JTextField> entry : fields.entrySet()) {
            String value = entry.getValue().getText();
            if (value != null && !value.trim().isEmpty()) {
                auth.properties.put(entry.getKey(), value.trim());
            }
        }
        return new Result("explicit", auth);
    }
}
