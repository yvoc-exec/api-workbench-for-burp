package burp.utils;

import burp.models.ApiRequest;

/**
 * Tiny request-build policy helper that centralizes the explicit build intent
 * used by RequestBuilder and editor previews.
 */
public final class RequestBuildPolicy {
    private final ApiRequest request;

    private RequestBuildPolicy(ApiRequest request) {
        this.request = request;
    }

    public static RequestBuildPolicy forRequest(ApiRequest request) {
        return new RequestBuildPolicy(request);
    }

    public boolean autoCompatible() {
        return request != null && request.isAutoCompatibleMode();
    }

    public boolean manualPreserve() {
        return request == null || request.isManualPreserveMode();
    }

    public boolean exactHttp() {
        return request != null && request.isExactHttpMode();
    }

    public boolean shouldApplyDefaultHeaders(ApiRequest request) {
        return request == null ? autoCompatible() : request.isAutoCompatibleMode();
    }

    public boolean shouldApplyAuthentication(ApiRequest request) {
        return request == null ? autoCompatible() : request.isAutoCompatibleMode();
    }

    public boolean shouldSynthesizeBodyContentType(ApiRequest request) {
        return request == null ? autoCompatible() : request.isAutoCompatibleMode();
    }

    public boolean isSuppressed(ApiRequest request, String headerName) {
        return request != null && request.isAutoHeaderSuppressed(headerName);
    }
}
