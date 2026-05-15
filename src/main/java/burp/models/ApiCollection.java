package burp.models;

import java.util.*;

/**
 * Unified API Collection model.
 */
public class ApiCollection {
    public String name;
    public String description;
    public String format;         // postman, bruno, openapi, insomnia, har
    public String version;
    public List<ApiRequest> requests = new ArrayList<>();
    public List<ApiRequest.Variable> variables = new ArrayList<>();
    public Map<String, String> environment = new HashMap<>();

    /** Collection-scoped runtime overrides (Variables tab / env file bound to this collection). */
    public Map<String, String> runtimeVars = new HashMap<>();
    /** Collection-scoped OAuth2 overrides bound to this collection. */
    public Map<String, String> runtimeOAuth2 = new HashMap<>();

    public int getEnabledRequestCount() {
        return (int) requests.stream().filter(r -> !r.disabled).count();
    }
}
