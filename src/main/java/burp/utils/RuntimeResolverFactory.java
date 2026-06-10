package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.VariableResolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Centralized runtime resolver construction for execution and preview paths.
 *
 * Precedence mirrors SharedRequestPipeline execution resolution:
 * environment -> collection vars -> folder vars -> optional explicit runtime overlay
 * -> request vars -> auth/runtime mapping.
 *
 * When an explicit runtime overlay is supplied, legacy scoped runtime sources
 * (runtimeOAuth2/runtimeVars) are intentionally excluded so the active
 * environment can fully own the runtime layer.
 */
public final class RuntimeResolverFactory {
    private static final Pattern BARE_VARIABLE_REFERENCE = Pattern.compile("^\\s*\\{\\{[^}|]+(?:\\|[^}]+)?\\}\\}\\s*$");

    private RuntimeResolverFactory() {
    }

    public static VariableResolver build(ApiCollection collection, ApiRequest request) {
        return build(collection, request, Options.defaultOptions());
    }

    public static VariableResolver build(ApiCollection collection, ApiRequest request, Options options) {
        Options effectiveOptions = options != null ? options : Options.defaultOptions();
        VariableResolver resolver = new VariableResolver();

        if (collection != null) {
            resolver.addEnvironmentVariables(collection);
            resolver.addCollectionVariables(collection);
            resolver.addFolderVariables(collection, request);
            if (!effectiveOptions.explicitRuntimeOverlayProvided) {
                if (collection.runtimeOAuth2 != null && !collection.runtimeOAuth2.isEmpty()) {
                    resolver.addAll(collection.runtimeOAuth2);
                }
                if (collection.runtimeVars != null && !collection.runtimeVars.isEmpty()) {
                    resolver.addAll(collection.runtimeVars);
                }
            }
        }

        if (effectiveOptions.runtimeVariableOverlay != null && !effectiveOptions.runtimeVariableOverlay.isEmpty()) {
            resolver.addAll(effectiveOptions.runtimeVariableOverlay);
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

    public static final class Options {
        final boolean mapAuthToRuntimeVars;
        final Map<String, String> runtimeVariableOverlay;
        final boolean explicitRuntimeOverlayProvided;

        private Options(boolean mapAuthToRuntimeVars, Map<String, String> runtimeVariableOverlay,
                        boolean explicitRuntimeOverlayProvided) {
            this.mapAuthToRuntimeVars = mapAuthToRuntimeVars;
            this.runtimeVariableOverlay = runtimeVariableOverlay != null
                    ? new LinkedHashMap<>(runtimeVariableOverlay)
                    : Collections.emptyMap();
            this.explicitRuntimeOverlayProvided = explicitRuntimeOverlayProvided;
        }

        public static Options defaultOptions() {
            return new Options(true, Collections.emptyMap(), false);
        }

        public static Options withoutAuthRuntimeMapping() {
            return new Options(false, Collections.emptyMap(), false);
        }

        public static Options withRuntimeVariableOverlay(Map<String, String> runtimeVariableOverlay) {
            return new Options(true, runtimeVariableOverlay, true);
        }

        public Options withAuthRuntimeMapping(boolean enabled) {
            return new Options(enabled, runtimeVariableOverlay, explicitRuntimeOverlayProvided);
        }
    }

    private static boolean shouldSkipAuthMappedValue(String value) {
        return value != null && BARE_VARIABLE_REFERENCE.matcher(value).matches();
    }
}
