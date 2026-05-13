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

    public int getEnabledRequestCount() {
        return (int) requests.stream().filter(r -> !r.disabled).count();
    }
}
