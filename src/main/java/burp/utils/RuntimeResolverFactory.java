package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Centralized runtime resolver construction for execution and preview paths.
 *
 * Precedence mirrors SharedRequestPipeline execution resolution:
 * collection defaults -> folder vars -> collection runtime vars -> active
 * environment -> runtime/script overlay -> request vars -> auth/runtime mapping.
 */
public final class RuntimeResolverFactory {
    private static final Pattern BARE_VARIABLE_REFERENCE = Pattern.compile("^\\s*\\{\\{[^}|]+(?:\\|[^}]+)?\\}\\}\\s*$");

    private RuntimeResolverFactory() {
    }

    public static VariableResolver build(ApiCollection collection, ApiRequest request) {
        return build(collection, request, Options.defaultOptions());
    }

    public static VariableResolver build(ApiCollection collection, ApiRequest request, Options options) {
        return build(collection, request, null, options != null ? options.runtimeVariableOverlay : null, options);
    }

    public static VariableResolver build(ApiCollection collection,
                                        ApiRequest request,
                                        EnvironmentProfile activeEnvironment,
                                        Map<String, String> runtimeOverlay) {
        return build(collection, request, activeEnvironment, runtimeOverlay, Options.defaultOptions());
    }

    public static VariableResolver build(ApiCollection collection,
                                         ApiRequest request,
                                         EnvironmentProfile activeEnvironment,
                                         Map<String, String> runtimeOverlay,
                                         Options options) {
        Options effectiveOptions = options != null ? options : Options.defaultOptions();
        VariableResolver resolver = new VariableResolver();

        if (collection != null) {
            resolver.addEnvironmentVariables(collection);
            resolver.addCollectionVariables(collection);
            resolver.addFolderVariables(collection, request);
            if (effectiveOptions.includeCollectionRuntimeLayers) {
                addRuntimeLayers(resolver, collection.runtimeOAuth2);
                addRuntimeLayers(resolver, collection.runtimeVars);
            }
        }

        if (activeEnvironment != null) {
            resolver.addAll(activeEnvironment.toRuntimeOverlay());
        }

        if (runtimeOverlay != null && !runtimeOverlay.isEmpty()) {
            resolver.addAll(runtimeOverlay);
        }

        if (request != null) {
            resolver.addRequestVariables(request);
            if (effectiveOptions.mapAuthToRuntimeVars && request.auth != null) {
                Map<String, String> authVars = OAuth2RuntimeMapper.mapAuthToVars(
                        request.auth,
                        resolver.getVariables(),
                        true
                );
                authVars.entrySet().removeIf(entry -> shouldSkipAuthMappedValue(entry.getValue()));
                if (!authVars.isEmpty()) {
                    resolver.addAll(authVars);
                }
            }
        }

        return resolver;
    }

    public static ResolutionTrace inspect(ApiCollection collection,
                                          ApiRequest request,
                                          EnvironmentProfile activeEnvironment,
                                          Map<String, String> runtimeOverlay,
                                          String key) {
        return inspect(collection, request, activeEnvironment, runtimeOverlay, key, Options.defaultOptions());
    }

    public static ResolutionTrace inspect(ApiCollection collection,
                                          ApiRequest request,
                                          EnvironmentProfile activeEnvironment,
                                          Map<String, String> runtimeOverlay,
                                          String key,
                                          Options options) {
        Options effectiveOptions = options != null ? options : Options.defaultOptions();
        String normalizedKey = key != null ? key.trim() : "";
        List<ResolutionCandidate> candidates = new ArrayList<>();
        Map<String, String> cumulative = new LinkedHashMap<>();

        if (collection != null) {
            collectCandidate(candidates, cumulative, collection.environment, "collection environment", "collection default", normalizedKey);
            collectCandidate(candidates, cumulative, collection.variables, "collection variables", "collection default", normalizedKey);
            collectCandidate(candidates, cumulative, collection.folderVars, request, "folder variables", normalizedKey);
            if (effectiveOptions.includeCollectionRuntimeLayers) {
                collectCandidate(candidates, cumulative, collection.runtimeOAuth2, "collection runtime OAuth2", "runtime", normalizedKey);
                collectCandidate(candidates, cumulative, collection.runtimeVars, "collection runtime vars", "runtime", normalizedKey);
            }
        }

        if (activeEnvironment != null) {
            collectCandidate(candidates, cumulative, activeEnvironment.toRuntimeOverlay(), "active environment", "active environment", normalizedKey);
        }

        if (runtimeOverlay != null && !runtimeOverlay.isEmpty()) {
            collectCandidate(candidates, cumulative, runtimeOverlay, "runtime overlay", "runtime/script", normalizedKey);
        }

        if (request != null) {
            collectCandidate(candidates, cumulative, request.variables, "request variables", "request", normalizedKey);
            if (effectiveOptions.mapAuthToRuntimeVars && request.auth != null) {
                Map<String, String> authVars = OAuth2RuntimeMapper.mapAuthToVars(request.auth, cumulative, true);
                authVars.entrySet().removeIf(entry -> shouldSkipAuthMappedValue(entry.getValue()));
                collectCandidate(candidates, cumulative, authVars, "auth/runtime mapping", "auth/runtime mapping", normalizedKey);
            }
        }

        ResolutionCandidate winner = null;
        ResolutionCandidate shadowed = null;
        for (ResolutionCandidate candidate : candidates) {
            if (candidate == null || !candidate.matchesKey(normalizedKey)) {
                continue;
            }
            if (winner != null && !equalsNullable(winner.value, candidate.value)) {
                shadowed = winner;
            }
            winner = candidate;
        }

        boolean resolved = winner != null && winner.value != null && !winner.value.isBlank();
        String message;
        if (winner == null) {
            message = normalizedKey.isBlank()
                    ? "Variable key is blank."
                    : "Variable \"" + normalizedKey + "\" unresolved.";
        } else if (!resolved) {
            message = shadowed != null
                    ? "Variable \"" + normalizedKey + "\" exists in " + winner.source + " but has an empty value. Shadowed " + shadowed.source + " value exists."
                    : "Variable \"" + normalizedKey + "\" exists in " + winner.source + " but has an empty value.";
        } else if (shadowed != null) {
            message = "Variable \"" + normalizedKey + "\" resolved from " + winner.source + ". Shadowed " + shadowed.source + " value exists.";
        } else {
            message = "Variable \"" + normalizedKey + "\" resolved from " + winner.source + ".";
        }

        return new ResolutionTrace(
                normalizedKey,
                resolved,
                winner != null ? winner.value : null,
                winner != null ? winner.source : null,
                winner != null ? winner.scope : null,
                winner != null ? winner.layer : null,
                winner != null ? winner.layerValue : null,
                shadowed != null ? shadowed.source : null,
                shadowed != null ? shadowed.value : null,
                activeEnvironment != null ? activeEnvironment.displayName() : null,
                candidates,
                message
        );
    }

    private static void addRuntimeLayers(VariableResolver resolver, Map<String, String> vars) {
        if (resolver == null || vars == null || vars.isEmpty()) {
            return;
        }
        resolver.addAll(vars);
    }

    private static void collectCandidate(List<ResolutionCandidate> candidates,
                                         Map<String, String> cumulative,
                                         Map<String, String> values,
                                         String source,
                                         String scope,
                                         String normalizedKey) {
        if (values == null || values.isEmpty() || normalizedKey == null || normalizedKey.isBlank()) {
            return;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey().trim();
            String value = entry.getValue();
            cumulative.put(key, value);
            if (key.equals(normalizedKey)) {
                candidates.add(new ResolutionCandidate(key, value, source, scope, source, value));
            }
        }
    }

    private static void collectCandidate(List<ResolutionCandidate> candidates,
                                         Map<String, String> cumulative,
                                         Map<String, Map<String, String>> folderVars,
                                         ApiRequest request,
                                         String source,
                                         String normalizedKey) {
        if (folderVars == null || folderVars.isEmpty() || request == null || normalizedKey == null || normalizedKey.isBlank()) {
            return;
        }
        String folderPath = burp.utils.RequestPathResolver.getRequestFolderPath(request);
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
            Map<String, String> values = folderVars.get(current.toString());
            if (values == null || values.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                String key = entry.getKey().trim();
                String value = entry.getValue();
                cumulative.put(key, value);
                if (key.equals(normalizedKey)) {
                    candidates.add(new ResolutionCandidate(key, value, source, "folder:" + current, source, value));
                }
            }
        }
    }

    private static void collectCandidate(List<ResolutionCandidate> candidates,
                                         Map<String, String> cumulative,
                                         List<ApiRequest.Variable> variables,
                                         String source,
                                         String scope,
                                         String normalizedKey) {
        if (variables == null || variables.isEmpty() || normalizedKey == null || normalizedKey.isBlank()) {
            return;
        }
        for (ApiRequest.Variable variable : variables) {
            if (variable == null || variable.key == null) {
                continue;
            }
            String key = variable.key.trim();
            String value = variable.value;
            cumulative.put(key, value);
            if (key.equals(normalizedKey)) {
                candidates.add(new ResolutionCandidate(key, value, source, scope, source, value));
            }
        }
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public static final class Options {
        final boolean mapAuthToRuntimeVars;
        final Map<String, String> runtimeVariableOverlay;
        final boolean explicitRuntimeOverlayProvided;
        final boolean includeCollectionRuntimeLayers;

        private Options(boolean mapAuthToRuntimeVars, Map<String, String> runtimeVariableOverlay,
                        boolean explicitRuntimeOverlayProvided,
                        boolean includeCollectionRuntimeLayers) {
            this.mapAuthToRuntimeVars = mapAuthToRuntimeVars;
            this.runtimeVariableOverlay = runtimeVariableOverlay != null
                    ? new LinkedHashMap<>(runtimeVariableOverlay)
                    : Collections.emptyMap();
            this.explicitRuntimeOverlayProvided = explicitRuntimeOverlayProvided;
            this.includeCollectionRuntimeLayers = includeCollectionRuntimeLayers;
        }

        public static Options defaultOptions() {
            return new Options(true, Collections.emptyMap(), false, true);
        }

        public static Options withoutAuthRuntimeMapping() {
            return new Options(false, Collections.emptyMap(), false, true);
        }

        public static Options withRuntimeVariableOverlay(Map<String, String> runtimeVariableOverlay) {
            return new Options(true, runtimeVariableOverlay, true, true);
        }

        public static Options withoutCollectionRuntimeLayers() {
            return new Options(true, Collections.emptyMap(), false, false);
        }

        public Options withAuthRuntimeMapping(boolean enabled) {
            return new Options(enabled, runtimeVariableOverlay, explicitRuntimeOverlayProvided, includeCollectionRuntimeLayers);
        }

        public Options withCollectionRuntimeLayers(boolean enabled) {
            return new Options(mapAuthToRuntimeVars, runtimeVariableOverlay, explicitRuntimeOverlayProvided, enabled);
        }
    }

    public static final class ResolutionTrace {
        public final String key;
        public final boolean resolved;
        public final String value;
        public final String source;
        public final String scope;
        public final String layer;
        public final String layerValue;
        public final String shadowedSource;
        public final String shadowedValue;
        public final String activeEnvironmentName;
        public final List<ResolutionCandidate> candidates;
        public final String message;

        ResolutionTrace(String key,
                        boolean resolved,
                        String value,
                        String source,
                        String scope,
                        String layer,
                        String layerValue,
                        String shadowedSource,
                        String shadowedValue,
                        String activeEnvironmentName,
                        List<ResolutionCandidate> candidates,
                        String message) {
            this.key = key;
            this.resolved = resolved;
            this.value = value;
            this.source = source;
            this.scope = scope;
            this.layer = layer;
            this.layerValue = layerValue;
            this.shadowedSource = shadowedSource;
            this.shadowedValue = shadowedValue;
            this.activeEnvironmentName = activeEnvironmentName;
            this.candidates = candidates != null ? List.copyOf(candidates) : List.of();
            this.message = message;
        }
    }

    public static final class ResolutionCandidate {
        public final String key;
        public final String value;
        public final String source;
        public final String scope;
        public final String layer;
        public final String layerValue;

        ResolutionCandidate(String key, String value, String source, String scope, String layer, String layerValue) {
            this.key = key;
            this.value = value;
            this.source = source;
            this.scope = scope;
            this.layer = layer;
            this.layerValue = layerValue;
        }

        boolean matchesKey(String normalizedKey) {
            return normalizedKey != null && !normalizedKey.isBlank() && normalizedKey.equals(key);
        }
    }

    private static boolean shouldSkipAuthMappedValue(String value) {
        return value != null && BARE_VARIABLE_REFERENCE.matcher(value).matches();
    }
}
