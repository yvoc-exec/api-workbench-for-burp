package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.UnresolvedVariableIssue;
import burp.parser.VariableResolver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UnresolvedVariableAnalyzer {
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([^}|]+)(?:\\|([^}]+))?\\}\\}");

    public List<UnresolvedVariableIssue> analyze(ApiCollection collection, ApiRequest request) {
        return analyze(collection, request, java.util.Collections.emptyMap());
    }

    public List<UnresolvedVariableIssue> analyze(ApiCollection collection, ApiRequest request, java.util.Map<String, String> runtimeOverlay) {
        List<UnresolvedVariableIssue> issues = new ArrayList<>();
        if (request == null) {
            return issues;
        }

        VariableResolver resolver = seedResolver(collection, request, runtimeOverlay);
        Set<String> seen = new LinkedHashSet<>();
        String collectionName = collection != null ? collection.name : request.sourceCollection;
        String requestName = request.name;

        scanValue(issues, seen, resolver, collectionName, requestName, "url", request.url);

        if (request.headers != null) {
            for (ApiRequest.Header header : request.headers) {
                if (header == null || header.disabled) {
                    continue;
                }
                scanValue(issues, seen, resolver, collectionName, requestName, "header:key", header.key);
                scanValue(issues, seen, resolver, collectionName, requestName, "header:value", header.value);
            }
        }

        if (request.body != null) {
            scanValue(issues, seen, resolver, collectionName, requestName, "body", request.body.raw);

            if (request.body.urlencoded != null) {
                for (ApiRequest.Body.FormField field : request.body.urlencoded) {
                    if (field == null || field.disabled) {
                        continue;
                    }
                    scanValue(issues, seen, resolver, collectionName, requestName, "urlencoded:key", field.key);
                    scanValue(issues, seen, resolver, collectionName, requestName, "urlencoded:value", field.value);
                    scanValue(issues, seen, resolver, collectionName, requestName, "urlencoded:filePath", field.filePath);
                }
            }

            if (request.body.formdata != null) {
                for (ApiRequest.Body.FormField field : request.body.formdata) {
                    if (field == null || field.disabled) {
                        continue;
                    }
                    scanValue(issues, seen, resolver, collectionName, requestName, "formdata:key", field.key);
                    scanValue(issues, seen, resolver, collectionName, requestName, "formdata:value", field.value);
                    scanValue(issues, seen, resolver, collectionName, requestName, "formdata:filePath", field.filePath);
                }
            }

            if (request.body.graphql != null) {
                scanValue(issues, seen, resolver, collectionName, requestName, "graphql:query", request.body.graphql.query);
                scanValue(issues, seen, resolver, collectionName, requestName, "graphql:variables", request.body.graphql.variables);
            }
        }

        if (request.auth != null && request.auth.properties != null) {
            for (var entry : request.auth.properties.entrySet()) {
                if (entry == null) {
                    continue;
                }
                scanValue(issues, seen, resolver, collectionName, requestName, "auth:" + entry.getKey(), entry.getValue());
            }
        }

        return issues;
    }

    private VariableResolver seedResolver(ApiCollection collection, ApiRequest request, java.util.Map<String, String> runtimeOverlay) {
        return RuntimeResolverFactory.build(
                collection,
                request,
                runtimeOverlay != null && !runtimeOverlay.isEmpty()
                        ? RuntimeResolverFactory.Options.withRuntimeVariableOverlay(runtimeOverlay)
                        : RuntimeResolverFactory.Options.defaultOptions()
        );
    }

    private void scanValue(List<UnresolvedVariableIssue> issues,
                           Set<String> seen,
                           VariableResolver resolver,
                           String collectionName,
                           String requestName,
                           String location,
                           String input) {
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
            if (resolver.getVariables().containsKey(variableName)) {
                continue;
            }

            String dedupeKey = collectionName + "\u0000" + requestName + "\u0000" + variableName + "\u0000" + location;
            if (seen.add(dedupeKey)) {
                issues.add(new UnresolvedVariableIssue(collectionName, requestName, variableName, location));
            }
        }
    }
}
