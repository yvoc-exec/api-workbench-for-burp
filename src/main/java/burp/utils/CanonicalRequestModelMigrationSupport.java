package burp.utils;

import burp.models.ApiRequest;

import java.util.List;

public final class CanonicalRequestModelMigrationSupport {
    private CanonicalRequestModelMigrationSupport() {
    }

    public static boolean migrateLegacyEmbeddedQuery(
            ApiRequest request,
            boolean parametersDeclared) {
        if (request == null
                || parametersDeclared
                || request.exactHttpRequest != null
                || request.parameters == null
                || !request.parameters.isEmpty()
                || request.url == null
                || !hasTransportQuery(request.url)) {
            return false;
        }

        List<ApiRequest.Parameter> migrated =
                RequestParameterSupport.parseQueryParameters(
                        request.url,
                        "api-workbench:legacy-url");

        if (migrated.isEmpty()) {
            return false;
        }

        request.parameters =
                RequestParameterSupport.copyParameters(migrated);
        request.url =
                RequestParameterSupport.stripQuery(request.url);
        return true;
    }

    private static boolean hasTransportQuery(String url) {
        if (url == null) {
            return false;
        }

        int question = url.indexOf('?');
        int fragment = url.indexOf('#');
        return question >= 0
                && (fragment < 0 || question < fragment);
    }
}
