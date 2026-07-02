package burp.models;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class EnvironmentProfile {
    public String id;
    public String name;
    public String sourceFormat;
    public String sourceFileName;
    public Map<String, String> variables = new LinkedHashMap<>();
    public transient Map<String, String> runtimeVariables = new LinkedHashMap<>();
    public OAuth2EnvironmentState oauth2 = new OAuth2EnvironmentState();

    public EnvironmentProfile() {
        ensureDefaults();
    }

    public String ensureId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    public String displayName() {
        return name == null || name.isBlank() ? "Environment" : name;
    }

    public EnvironmentProfile copy() {
        ensureDefaults();
        EnvironmentProfile copy = new EnvironmentProfile();
        copy.id = id;
        copy.name = name;
        copy.sourceFormat = sourceFormat;
        copy.sourceFileName = sourceFileName;
        copy.variables = new LinkedHashMap<>(variables != null ? variables : new LinkedHashMap<>());
        copy.runtimeVariables = new LinkedHashMap<>(runtimeVariables != null ? runtimeVariables : new LinkedHashMap<>());
        copy.oauth2 = oauth2 != null ? oauth2.copy() : new OAuth2EnvironmentState();
        return copy;
    }

    public Map<String, String> toRuntimeOverlay() {
        LinkedHashMap<String, String> overlay = new LinkedHashMap<>();
        if (oauth2 != null && oauth2.config != null) {
            overlay.putAll(oauth2.config);
        }
        if (variables != null) {
            overlay.putAll(variables);
        }
        if (runtimeVariables != null) {
            overlay.putAll(runtimeVariables);
        }
        return overlay;
    }

    public void ensureDefaults() {
        if (variables == null) {
            variables = new LinkedHashMap<>();
        }
        if (runtimeVariables == null) {
            runtimeVariables = new LinkedHashMap<>();
        }
        if (oauth2 == null) {
            oauth2 = new OAuth2EnvironmentState();
        }
        oauth2.ensureDefaults();
    }

    public Map<String, String> toPersistedOverlay() {
        ensureDefaults();
        LinkedHashMap<String, String> overlay = new LinkedHashMap<>();
        if (oauth2 != null && oauth2.config != null) {
            overlay.putAll(oauth2.config);
        }
        overlay.putAll(variables);
        return overlay;
    }
}
