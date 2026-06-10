package burp.models;

import java.util.LinkedHashMap;
import java.util.Map;

public class OAuth2EnvironmentState {
    public Map<String, String> config = new LinkedHashMap<>();
    public Map<String, String> outputBindings = new LinkedHashMap<>();

    public OAuth2EnvironmentState() {
        ensureDefaults();
    }

    public OAuth2EnvironmentState copy() {
        OAuth2EnvironmentState copy = new OAuth2EnvironmentState();
        copy.config = new LinkedHashMap<>(config != null ? config : new LinkedHashMap<>());
        copy.outputBindings = new LinkedHashMap<>(outputBindings != null ? outputBindings : new LinkedHashMap<>());
        return copy;
    }

    public void ensureDefaults() {
        if (config == null) {
            config = new LinkedHashMap<>();
        }
        if (outputBindings == null || outputBindings.isEmpty()) {
            outputBindings = new LinkedHashMap<>();
            outputBindings.put("accessToken", "oauth2_access_token");
            outputBindings.put("refreshToken", "oauth2_refresh_token");
            outputBindings.put("tokenType", "oauth2_token_type");
            outputBindings.put("expiresIn", "oauth2_expires_in");
        } else {
            outputBindings = new LinkedHashMap<>(outputBindings);
            outputBindings.putIfAbsent("accessToken", "oauth2_access_token");
            outputBindings.putIfAbsent("refreshToken", "oauth2_refresh_token");
            outputBindings.putIfAbsent("tokenType", "oauth2_token_type");
            outputBindings.putIfAbsent("expiresIn", "oauth2_expires_in");
        }
    }
}
