package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.RequestPathResolver;
import java.util.*;
import java.util.regex.*;

/**
 * Resolves {{variables}} in strings using environment + collection variables.
 * Supports nested variables and default values: {{var|default}}.
 */
public class VariableResolver {
    private final Map<String, String> variables = new HashMap<>();
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([^}|]+)(?:\\|([^}]+))?\\}\\}");

    public void addEnvironmentVariables(ApiCollection collection) {
        if (collection.environment != null) {
            variables.putAll(collection.environment);
        }
    }

    public void addCollectionVariables(ApiCollection collection) {
        for (ApiRequest.Variable var : collection.variables) {
            if (var == null || !var.enabled || var.key == null || var.value == null) {
                continue;
            }
            variables.put(var.key, var.value);
        }
    }

    public void addFolderVariables(ApiCollection collection, ApiRequest request) {
        if (collection == null || request == null || collection.folderVars == null || collection.folderVars.isEmpty()) {
            return;
        }
        String folderPath = RequestPathResolver.getRequestFolderPath(collection, request);
        if (folderPath == null || folderPath.isBlank()) {
            return;
        }
        String[] parts = folderPath.split("/");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (current.length() > 0) {
                current.append("/");
            }
            current.append(part.trim());
            Map<String, String> vars = collection.folderVars.get(current.toString());
            if (vars != null) {
                variables.putAll(vars);
            }
        }
    }

    public void addRequestVariables(ApiRequest request) {
        for (ApiRequest.Variable var : request.variables) {
            if (var == null || !var.enabled || var.key == null || var.value == null) {
                continue;
            }
            variables.put(var.key, var.value);
        }
    }

    public void addCustomVariable(String key, String value) {
        variables.put(key, value);
    }

    public void addAll(Map<String, String> vars) {
        variables.putAll(vars);
    }

    public String resolve(String input) {
        if (input == null) return null;

        String result = input;
        int maxIterations = 10; // Prevent infinite loops

        for (int i = 0; i < maxIterations; i++) {
            Matcher matcher = VARIABLE_PATTERN.matcher(result);
            StringBuffer sb = new StringBuffer();
            boolean found = false;

            while (matcher.find()) {
                found = true;
                String varName = matcher.group(1).trim();
                String defaultValue = matcher.group(2);
                String value = variables.getOrDefault(varName, null);

                if (value == null && defaultValue != null) {
                    value = defaultValue;
                } else if (value == null) {
                    value = "{{" + varName + "}}"; // Keep unresolved
                }

                matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
            matcher.appendTail(sb);
            result = sb.toString();

            if (!found) break;
        }

        return result;
    }

    public Map<String, String> getVariables() {
        return new HashMap<>(variables);
    }

    /**
     * Returns the live mutable variables map for callers that need to mutate
     * (e.g., removing request-scoped variables after each request).
     */
    public Map<String, String> mutableVariables() {
        return variables;
    }

    public Set<String> findUnresolvedVariables(String input) {
        Set<String> unresolved = new LinkedHashSet<>();
        if (input == null) return unresolved;

        Matcher matcher = VARIABLE_PATTERN.matcher(input);
        while (matcher.find()) {
            String varName = matcher.group(1).trim();
            String defaultValue = matcher.group(2);
            if (varName.isEmpty() || defaultValue != null) {
                continue;
            }
            if (!variables.containsKey(varName)) {
                unresolved.add(varName);
                continue;
            }
            String value = variables.get(varName);
            if (value == null || value.isBlank()) {
                unresolved.add(varName);
            }
        }
        return unresolved;
    }

    public void clear() {
        variables.clear();
    }
}
