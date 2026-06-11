package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.UnresolvedVariableIssue;
import burp.parser.VariableResolver;
import burp.utils.RuntimeResolverFactory;
import burp.utils.UnresolvedVariableAnalyzer;

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
    private static final UnresolvedVariableAnalyzer ANALYZER = new UnresolvedVariableAnalyzer();

    private ExportVariableResolutionService() {
    }

    public static VariableResolver buildResolver(ApiCollection collection,
                                                  ApiRequest request,
                                                  EnvironmentProfile activeEnvironment,
                                                  Map<String, String> exportOnlyVariables) {
        Map<String, String> overlay = new LinkedHashMap<>();
        if (activeEnvironment != null) {
            overlay.putAll(activeEnvironment.toRuntimeOverlay());
        }
        if (exportOnlyVariables != null && !exportOnlyVariables.isEmpty()) {
            overlay.putAll(exportOnlyVariables);
        }
        return RuntimeResolverFactory.build(collection, request, RuntimeResolverFactory.Options.withRuntimeVariableOverlay(overlay));
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
        Map<String, String> overlay = new LinkedHashMap<>();
        if (activeEnvironment != null) {
            overlay.putAll(activeEnvironment.toRuntimeOverlay());
        }
        if (exportOnlyVariables != null && !exportOnlyVariables.isEmpty()) {
            overlay.putAll(exportOnlyVariables);
        }
        for (ApiRequest request : collection.requests) {
            if (request == null) {
                continue;
            }
            issues.addAll(ANALYZER.analyze(collection, request, overlay));
            scanValues(issues, collection.name, request.name, "script:pre-request", request.preRequestScripts != null ? request.preRequestScripts.stream().map(s -> s != null ? s.exec : null).toList() : List.of(), overlay);
            scanValues(issues, collection.name, request.name, "script:post-response", request.postResponseScripts != null ? request.postResponseScripts.stream().map(s -> s != null ? s.exec : null).toList() : List.of(), overlay);
        }
        scanCollectionLevelValues(issues, collection, overlay);
        return dedupe(issues);
    }

    private static void scanCollectionLevelValues(List<UnresolvedVariableIssue> issues,
                                                  ApiCollection collection,
                                                  Map<String, String> overlay) {
        if (collection == null) {
            return;
        }
        String collectionName = collection.name != null ? collection.name : "";
        if (collection.auth != null && collection.auth.properties != null) {
            for (Map.Entry<String, String> entry : collection.auth.properties.entrySet()) {
                scanValue(issues, collectionName, collectionName, "collection-auth:" + safe(entry.getKey()), entry.getValue(), overlay);
            }
        }
        if (collection.folderAuth != null) {
            for (Map.Entry<String, ApiRequest.Auth> entry : collection.folderAuth.entrySet()) {
                ApiRequest.Auth auth = entry.getValue();
                if (auth == null || auth.properties == null) {
                    continue;
                }
                for (Map.Entry<String, String> authEntry : auth.properties.entrySet()) {
                    scanValue(issues, collectionName, collectionName, "folder-auth:" + safe(entry.getKey()) + ":" + safe(authEntry.getKey()), authEntry.getValue(), overlay);
                }
            }
        }
        if (collection.variables != null) {
            for (ApiRequest.Variable variable : collection.variables) {
                if (variable == null) {
                    continue;
                }
                scanValue(issues, collectionName, collectionName, "collection-variable:" + safe(variable.key), variable.value, overlay);
            }
        }
        if (collection.folderVars != null) {
            for (Map.Entry<String, Map<String, String>> entry : collection.folderVars.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                for (Map.Entry<String, String> nested : entry.getValue().entrySet()) {
                    scanValue(issues, collectionName, collectionName, "folder-variable:" + safe(entry.getKey()) + ":" + safe(nested.getKey()), nested.getValue(), overlay);
                }
            }
        }
        if (collection.environment != null) {
            for (Map.Entry<String, String> entry : collection.environment.entrySet()) {
                scanValue(issues, collectionName, collectionName, "collection-environment:" + safe(entry.getKey()), entry.getValue(), overlay);
            }
        }
    }

    private static void scanValues(List<UnresolvedVariableIssue> issues,
                                   String collectionName,
                                   String requestName,
                                   String location,
                                   List<String> values,
                                   Map<String, String> overlay) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (String value : values) {
            scanValue(issues, collectionName, requestName, location, value, overlay);
        }
    }

    private static void scanValue(List<UnresolvedVariableIssue> issues,
                                  String collectionName,
                                  String requestName,
                                  String location,
                                  String input,
                                  Map<String, String> overlay) {
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
            if (overlay != null && overlay.containsKey(variableName)) {
                String resolved = overlay.get(variableName);
                if (resolved != null && !resolved.isBlank()) {
                    continue;
                }
            }
            issues.add(new UnresolvedVariableIssue(
                    collectionName != null ? collectionName : "",
                    requestName != null ? requestName : "",
                    variableName,
                    location,
                    overlay != null && overlay.containsKey(variableName)
                            ? "Variable \"" + variableName + "\" exists in the active/runtime scope but has an empty value."
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
