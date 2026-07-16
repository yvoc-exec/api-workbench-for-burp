package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.UnresolvedVariableIssue;
import burp.parser.VariableResolver;
import burp.utils.RequestPathResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExportVariableResolutionService {
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([^}|]+)(?:\\|([^}]+))?\\}\\}");

    private ExportVariableResolutionService() {
    }

    public static VariableResolver buildResolver(ApiCollection collection,
                                                  ApiRequest request,
                                                  EnvironmentProfile activeEnvironment,
                                                  Map<String, String> exportOnlyVariables) {
        VariableResolver resolver = new VariableResolver();
        if (collection != null && collection.environment != null) {
            resolver.addAll(collection.environment);
        }
        if (collection != null && collection.variables != null) {
            for (ApiRequest.Variable variable : collection.variables) {
                if (variable != null && variable.enabled && variable.key != null && variable.value != null) {
                    resolver.addCustomVariable(variable.key, variable.value);
                }
            }
        }
        if (collection != null && request != null && collection.folderVars != null) {
            for (String ancestor : RequestPathResolver.getAncestorFolderPaths(collection, request)) {
                Map<String, String> folder = collection.folderVars.get(ancestor);
                if (folder != null) resolver.addAll(folder);
            }
        }
        if (request != null && request.variables != null) {
            for (ApiRequest.Variable variable : request.variables) {
                if (variable != null && variable.enabled && variable.key != null && variable.value != null) {
                    resolver.addCustomVariable(variable.key, variable.value);
                }
            }
        }
        if (activeEnvironment != null && activeEnvironment.variables != null) {
            resolver.addAll(activeEnvironment.variables);
        }
        if (exportOnlyVariables != null && !exportOnlyVariables.isEmpty()) {
            resolver.addAll(exportOnlyVariables);
        }
        return resolver;
    }

    static void addDuplicateEnabledVariableWarnings(ApiCollection collection,
                                                     ApiRequest request,
                                                     List<String> warnings) {
        warnDuplicates(collection != null ? collection.variables : null, "collection", warnings);
        warnDuplicates(request != null ? request.variables : null,
                request != null ? "request '" + ExportWarningSupport.label(request.name) + "'" : "request",
                warnings);
    }

    private static void warnDuplicates(List<ApiRequest.Variable> variables,
                                       String scope,
                                       List<String> warnings) {
        Set<String> enabled = new java.util.LinkedHashSet<>();
        if (variables == null) return;
        for (ApiRequest.Variable variable : variables) {
            if (variable == null || !variable.enabled || variable.key == null) continue;
            if (!enabled.add(variable.key)) {
                ExportWarningSupport.add(warnings, "Export resolution collapsed duplicate enabled variable '"
                        + ExportWarningSupport.label(variable.key) + "' in " + scope
                        + " using the last occurrence.");
            }
        }
    }

    public static List<UnresolvedVariableIssue> collectUnresolvedIssues(ApiCollection collection,
                                                                        EnvironmentProfile activeEnvironment) {
        return collectUnresolvedIssues(collection, activeEnvironment, Map.of());
    }

    public static List<UnresolvedVariableIssue> collectUnresolvedIssues(ApiCollection collection,
                                                                        EnvironmentProfile activeEnvironment,
                                                                        Map<String, String> exportOnlyVariables) {
        List<UnresolvedVariableIssue> issues = new ArrayList<>();
        if (collection == null || collection.requests == null) {
            return issues;
        }
        for (ApiRequest request : collection.requests) {
            if (request == null) {
                continue;
            }
            VariableResolver resolver = buildResolver(collection, request, activeEnvironment, exportOnlyVariables);
            scanRequestValues(issues, collection, request, resolver);
        }
        scanCollectionLevelValues(issues, collection,
                buildResolver(collection, null, activeEnvironment, exportOnlyVariables));
        return dedupe(issues);
    }

    private static void scanRequestValues(List<UnresolvedVariableIssue> issues, ApiCollection collection,
                                          ApiRequest request, VariableResolver resolver) {
        String collectionName = collection.name;
        scanValue(issues, collectionName, request.name, "method", request.method, resolver);
        scanValue(issues, collectionName, request.name, "url", request.url, resolver);
        scanValue(issues, collectionName, request.name, "description", request.description, resolver);
        if (request.parameters != null) for (ApiRequest.Parameter parameter : request.parameters) if (parameter != null && !parameter.disabled) {
            scanValue(issues, collectionName, request.name, "parameter:key", parameter.key, resolver);
            scanValue(issues, collectionName, request.name, "parameter:value", parameter.value, resolver);
        }
        if (request.headers != null) for (ApiRequest.Header header : request.headers) if (header != null && !header.disabled) {
            scanValue(issues, collectionName, request.name, "header:key", header.key, resolver);
            scanValue(issues, collectionName, request.name, "header:value", header.value, resolver);
        }
        if (request.body != null) {
            scanValue(issues, collectionName, request.name, "body:raw", request.body.raw, resolver);
            if (request.body.graphql != null) {
                scanValue(issues, collectionName, request.name, "body:graphql", request.body.graphql.query, resolver);
                scanValue(issues, collectionName, request.name, "body:graphql:variables", request.body.graphql.variables, resolver);
            }
            scanFormValues(issues, collectionName, request.name, request.body.urlencoded, resolver);
            scanFormValues(issues, collectionName, request.name, request.body.formdata, resolver);
        }
        ApiRequest.Auth auth = request.explicitAuth != null ? request.explicitAuth : request.auth;
        if (auth != null && auth.properties != null) for (Map.Entry<String, String> entry : auth.properties.entrySet())
            scanValue(issues, collectionName, request.name, "auth:" + safe(entry.getKey()), entry.getValue(), resolver);
        if (request.scriptBlocks != null) for (burp.scripts.ScriptBlock block : request.scriptBlocks)
            if (block != null && block.enabled) scanValue(issues, collectionName, request.name,
                    "script:" + block.phase, block.source, resolver);
        boolean nativePre = request.scriptBlocks != null && request.scriptBlocks.stream()
                .anyMatch(block -> block != null && block.phase == burp.scripts.ScriptPhase.PRE_REQUEST);
        boolean nativePost = request.scriptBlocks != null && request.scriptBlocks.stream()
                .anyMatch(block -> block != null && block.phase == burp.scripts.ScriptPhase.POST_RESPONSE);
        if (!nativePre && request.preRequestScripts != null) for (ApiRequest.Script script : request.preRequestScripts)
            if (script != null) scanValue(issues, collectionName, request.name, "script:pre-request", script.exec, resolver);
        if (!nativePost && request.postResponseScripts != null) for (ApiRequest.Script script : request.postResponseScripts)
            if (script != null) scanValue(issues, collectionName, request.name, "script:post-response", script.exec, resolver);
    }

    private static void scanFormValues(List<UnresolvedVariableIssue> issues, String collectionName,
                                       String requestName, List<ApiRequest.Body.FormField> fields,
                                       VariableResolver resolver) {
        if (fields == null) return;
        for (ApiRequest.Body.FormField field : fields) if (field != null && !field.disabled) {
            scanValue(issues, collectionName, requestName, "form:key", field.key, resolver);
            scanValue(issues, collectionName, requestName, "form:value", field.value, resolver);
            scanValue(issues, collectionName, requestName, "form:file", field.filePath, resolver);
        }
    }

    private static void scanCollectionLevelValues(List<UnresolvedVariableIssue> issues,
                                                  ApiCollection collection,
                                                  VariableResolver resolver) {
        if (collection == null) {
            return;
        }
        String collectionName = collection.name != null ? collection.name : "";
        if (collection.auth != null && collection.auth.properties != null) {
            for (Map.Entry<String, String> entry : collection.auth.properties.entrySet()) {
                scanValue(issues, collectionName, collectionName, "collection-auth:" + safe(entry.getKey()), entry.getValue(), resolver);
            }
        }
        if (collection.folderAuth != null) {
            for (Map.Entry<String, ApiRequest.Auth> entry : collection.folderAuth.entrySet()) {
                ApiRequest.Auth auth = entry.getValue();
                if (auth == null || auth.properties == null) {
                    continue;
                }
                for (Map.Entry<String, String> authEntry : auth.properties.entrySet()) {
                    scanValue(issues, collectionName, collectionName, "folder-auth:" + safe(entry.getKey()) + ":" + safe(authEntry.getKey()), authEntry.getValue(), resolver);
                }
            }
        }
        if (collection.variables != null) {
            for (ApiRequest.Variable variable : collection.variables) {
                if (variable == null) {
                    continue;
                }
                if (variable.enabled) scanValue(issues, collectionName, collectionName, "collection-variable:" + safe(variable.key), variable.value, resolver);
            }
        }
        if (collection.folderVars != null) {
            for (Map.Entry<String, Map<String, String>> entry : collection.folderVars.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                for (Map.Entry<String, String> nested : entry.getValue().entrySet()) {
                    scanValue(issues, collectionName, collectionName, "folder-variable:" + safe(entry.getKey()) + ":" + safe(nested.getKey()), nested.getValue(), resolver);
                }
            }
        }
        if (collection.environment != null) {
            for (Map.Entry<String, String> entry : collection.environment.entrySet()) {
                scanValue(issues, collectionName, collectionName, "collection-environment:" + safe(entry.getKey()), entry.getValue(), resolver);
            }
        }
        if (collection.scriptBlocks != null) for (burp.scripts.ScriptBlock block : collection.scriptBlocks)
            if (block != null && block.enabled) scanValue(issues, collectionName, collectionName,
                    "collection-script:" + block.phase, block.source, resolver);
        if (collection.folderScriptBlocks != null) for (Map.Entry<String, List<burp.scripts.ScriptBlock>> entry : collection.folderScriptBlocks.entrySet())
            if (entry.getValue() != null) for (burp.scripts.ScriptBlock block : entry.getValue())
                if (block != null && block.enabled) scanValue(issues, collectionName, collectionName,
                        "folder-script:" + safe(entry.getKey()) + ":" + block.phase, block.source, resolver);
    }

    private static void scanValues(List<UnresolvedVariableIssue> issues,
                                   String collectionName,
                                   String requestName,
                                   String location,
                                   List<String> values,
                                   VariableResolver resolver) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (String value : values) {
            scanValue(issues, collectionName, requestName, location, value, resolver);
        }
    }

    private static void scanValue(List<UnresolvedVariableIssue> issues,
                                  String collectionName,
                                  String requestName,
                                  String location,
                                  String input,
                                  VariableResolver resolver) {
        if (input == null || input.isEmpty()) {
            return;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(input);
        while (matcher.find()) {
            String variableName = matcher.group(1) != null ? matcher.group(1).trim() : "";
            String defaultValue = matcher.group(2);
            if (variableName.isEmpty() || defaultValue != null) {
                continue;
            }
            String token = "{{" + variableName + "}}";
            String resolved = resolver != null ? resolver.resolve(token) : token;
            if (resolved != null && !token.equals(resolved) && !resolved.isBlank()) continue;
            issues.add(new UnresolvedVariableIssue(
                    collectionName != null ? collectionName : "",
                    requestName != null ? requestName : "",
                    variableName,
                    location,
                    resolved != null && !token.equals(resolved)
                            ? "Variable \"" + variableName + "\" exists in the export scope but has an empty value."
                            : "Variable \"" + variableName + "\" is unresolved."
            ));
        }
    }

    private static List<UnresolvedVariableIssue> dedupe(List<UnresolvedVariableIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        List<UnresolvedVariableIssue> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (UnresolvedVariableIssue issue : issues) {
            if (issue == null) {
                continue;
            }
            String key = issue.collectionName + "\u0000" + issue.requestName + "\u0000" + issue.variableName + "\u0000" + issue.location;
            if (seen.add(key)) {
                out.add(issue);
            }
        }
        return out;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
