package burp.utils;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.models.RedirectHop;
import burp.models.RedirectPolicy;
import burp.models.RedirectTerminationReason;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.Set;
import java.util.function.BooleanSupplier;

public class RedirectExecutor {
    @FunctionalInterface
    public interface HopSender {
        HttpRequestResponse send(HttpRequest request) throws Exception;
    }

    public static final class RedirectRequest {
        public HttpRequest initialRequest;
        public String initialUrl;
        public byte[] initialRawRequestBytes;
        public boolean followRedirects;
        public RedirectPolicy redirectPolicy;
        public HopSender hopSender;
        public int responseTimeoutMillis;
        public BooleanSupplier cancellationRequested = () -> false;
    }

    public static final class RedirectResult {
        public HttpRequestResponse finalResponse;
        public HttpRequest initialRequest;
        public HttpRequest finalRequest;
        public String initialUrl;
        public String finalUrl;
        public long totalElapsedMs;
        public List<RedirectHop> redirectHops = new ArrayList<>();
        public boolean success;
        public String errorMessage;
        public RedirectTerminationReason terminationReason = RedirectTerminationReason.NONE;
        public boolean responseTimedOut;
        public int timeoutMillis;
        public boolean requestSent;
    }

    public RedirectResult execute(RedirectRequest request) {
        RedirectResult result = new RedirectResult();
        result.initialRequest = request != null ? request.initialRequest : null;
        result.initialUrl = request != null ? request.initialUrl : null;
        result.finalRequest = result.initialRequest;
        result.finalUrl = result.initialUrl;
        result.success = true;
        result.timeoutMillis = request != null ? request.responseTimeoutMillis : 0;

        RedirectPolicy policy = RedirectPolicy.copyOf(request != null ? request.redirectPolicy : null);
        policy.normalize();
        HopSender sender = request != null ? request.hopSender : null;
        boolean followRedirects = request != null && request.followRedirects;
        HttpRequest currentRequest = result.initialRequest;
        String currentUrl = result.initialUrl;
        byte[] currentRaw = request != null && request.initialRawRequestBytes != null
                ? request.initialRawRequestBytes.clone()
                : request != null && request.initialRequest != null && request.initialRequest.toByteArray() != null
                ? request.initialRequest.toByteArray().getBytes().clone()
                : null;
        String currentCanonicalUrl = canonicalRequestUrl(currentUrl);
        Set<String> visited = new LinkedHashSet<>();
        if (currentCanonicalUrl != null) {
            visited.add(currentCanonicalUrl);
        }
        long started = System.nanoTime();
        int followedRedirectCount = 0;
        int hopNumber = 0;
        HttpRequestResponse lastResponse = null;

        try {
            while (true) {
                if (isCancellationRequested(request != null ? request.cancellationRequested : null)) {
                    return cancelled(result, currentRequest, currentUrl, lastResponse != null);
                }
                long exchangeStart = System.nanoTime();
                HttpRequestResponse currentResponse;
                try {
                    result.requestSent = true;
                    currentResponse = send(sender, currentRequest);
                } catch (Exception e) {
                    if (isCancellationRequested(request != null ? request.cancellationRequested : null)) {
                        return cancelled(result, currentRequest, currentUrl, true);
                    }
                    if (isTimeoutFailure(e)) {
                        result.success = false;
                        result.responseTimedOut = true;
                        result.errorMessage = "Response timed out after " + Math.max(0, result.timeoutMillis) + " ms";
                        result.terminationReason = RedirectTerminationReason.RESPONSE_TIMEOUT;
                        result.totalElapsedMs = elapsedMs(started);
                        result.finalRequest = currentRequest;
                        result.finalUrl = currentUrl;
                        result.finalResponse = lastResponse;
                        return result;
                    }
                    result.success = false;
                    result.errorMessage = extractCleanError(e);
                    result.terminationReason = RedirectTerminationReason.SEND_FAILED;
                    result.totalElapsedMs = elapsedMs(started);
                    result.finalRequest = currentRequest;
                    result.finalUrl = currentUrl;
                    result.finalResponse = lastResponse;
                    return result;
                }
                if (isCancellationRequested(request != null ? request.cancellationRequested : null)) {
                    return cancelled(result, currentRequest, currentUrl, true);
                }
                long exchangeElapsedMs = elapsedMs(exchangeStart);
                if (currentResponse == null || currentResponse.response() == null) {
                    result.success = false;
                    result.errorMessage = "No response received";
                    result.terminationReason = RedirectTerminationReason.SEND_FAILED;
                    result.totalElapsedMs = elapsedMs(started);
                    result.finalRequest = currentRequest;
                    result.finalUrl = currentUrl;
                    result.finalResponse = lastResponse;
                    return result;
                }
                lastResponse = currentResponse;

                HttpResponse response = currentResponse.response();
                int statusCode = response.statusCode();
                String location = headerValue(response, "Location");
                if (isCancellationRequested(request != null ? request.cancellationRequested : null)) {
                    return cancelled(result, currentRequest, currentUrl, true);
                }
                if (!isSupportedRedirect(statusCode) || location == null || location.isBlank()) {
                    if (isCancellationRequested(request != null ? request.cancellationRequested : null)) {
                        return cancelled(result, currentRequest, currentUrl, true);
                    }
                    result.finalResponse = currentResponse;
                    result.finalRequest = currentRequest;
                    result.finalUrl = currentUrl;
                    result.totalElapsedMs = elapsedMs(started);
                    result.success = true;
                    result.terminationReason = RedirectTerminationReason.FINAL_RESPONSE;
                    return result;
                }

                hopNumber++;
                if (isCancellationRequested(request != null ? request.cancellationRequested : null)) {
                    return cancelled(result, currentRequest, currentUrl, true);
                }
                URI sourceUri = parseAbsoluteUri(currentUrl);
                URI targetUri;
                try {
                    targetUri = resolveLocation(sourceUri, location);
                } catch (IllegalArgumentException e) {
                    RedirectTerminationReason reason = isUnsupportedTargetReason(e)
                            ? RedirectTerminationReason.UNSUPPORTED_SCHEME
                            : RedirectTerminationReason.INVALID_LOCATION;
                    result.redirectHops.add(buildHop(hopNumber, currentUrl, currentRequest, statusCode, location, null,
                            exchangeElapsedMs, currentResponse, false, reason.displayLabel(),
                            currentRaw, null, null));
                    result.finalResponse = currentResponse;
                    result.finalRequest = currentRequest;
                    result.finalUrl = currentUrl;
                    result.success = false;
                    result.errorMessage = reason.displayLabel();
                    result.terminationReason = reason;
                    result.totalElapsedMs = elapsedMs(started);
                    return result;
                }

                String targetUrl = targetUri.toString();
                String targetCanonical = canonicalRequestUrl(targetUrl);
                String sourceOrigin = redirectOrigin(sourceUri);
                String targetOrigin = redirectOrigin(targetUri);
                if (targetOrigin == null) {
                    result.redirectHops.add(buildHop(hopNumber, currentUrl, currentRequest, statusCode, location, targetUrl,
                            exchangeElapsedMs, currentResponse, false, RedirectTerminationReason.UNSUPPORTED_SCHEME.displayLabel(),
                            currentRaw, null, null));
                    result.finalResponse = currentResponse;
                    result.finalRequest = currentRequest;
                    result.finalUrl = currentUrl;
                    result.success = false;
                    result.errorMessage = RedirectTerminationReason.UNSUPPORTED_SCHEME.displayLabel();
                    result.terminationReason = RedirectTerminationReason.UNSUPPORTED_SCHEME;
                    result.totalElapsedMs = elapsedMs(started);
                    return result;
                }

                if (!followRedirects) {
                    result.redirectHops.add(buildHop(hopNumber, currentUrl, currentRequest, statusCode, location, targetUrl,
                            exchangeElapsedMs, currentResponse, false, RedirectTerminationReason.FOLLOW_DISABLED.displayLabel(),
                            currentRaw, null, null));
                    result.finalResponse = currentResponse;
                    result.finalRequest = currentRequest;
                    result.finalUrl = currentUrl;
                    result.success = true;
                    result.terminationReason = RedirectTerminationReason.FOLLOW_DISABLED;
                    result.totalElapsedMs = elapsedMs(started);
                    return result;
                }

                if (followedRedirectCount >= policy.maxHops) {
                    result.redirectHops.add(buildHop(hopNumber, currentUrl, currentRequest, statusCode, location, targetUrl,
                            exchangeElapsedMs, currentResponse, false, RedirectTerminationReason.LIMIT_EXCEEDED.displayLabel(),
                            currentRaw, null, null));
                    result.finalResponse = currentResponse;
                    result.finalRequest = currentRequest;
                    result.finalUrl = currentUrl;
                    result.success = false;
                    result.errorMessage = RedirectTerminationReason.LIMIT_EXCEEDED.displayLabel();
                    result.terminationReason = RedirectTerminationReason.LIMIT_EXCEEDED;
                    result.totalElapsedMs = elapsedMs(started);
                    return result;
                }

                if (targetCanonical != null && visited.contains(targetCanonical)) {
                    result.redirectHops.add(buildHop(hopNumber, currentUrl, currentRequest, statusCode, location, targetUrl,
                            exchangeElapsedMs, currentResponse, false, RedirectTerminationReason.LOOP_DETECTED.displayLabel(),
                            currentRaw, null, null));
                    result.finalResponse = currentResponse;
                    result.finalRequest = currentRequest;
                    result.finalUrl = currentUrl;
                    result.success = false;
                    result.errorMessage = RedirectTerminationReason.LOOP_DETECTED.displayLabel();
                    result.terminationReason = RedirectTerminationReason.LOOP_DETECTED;
                    result.totalElapsedMs = elapsedMs(started);
                    return result;
                }

                RedirectBuild build;
                try {
                    build = buildNextRequest(currentRequest, currentRaw, targetUri, statusCode, policy, sourceOrigin, targetOrigin);
                } catch (IllegalArgumentException e) {
                    result.redirectHops.add(buildHop(hopNumber, currentUrl, currentRequest, statusCode, location, targetUrl,
                            exchangeElapsedMs, currentResponse, false, RedirectTerminationReason.REQUEST_BUILD_FAILED.displayLabel(),
                            currentRaw, null, null));
                    result.finalResponse = currentResponse;
                    result.finalRequest = currentRequest;
                    result.finalUrl = currentUrl;
                    result.success = false;
                    result.errorMessage = RedirectTerminationReason.REQUEST_BUILD_FAILED.displayLabel();
                    result.terminationReason = RedirectTerminationReason.REQUEST_BUILD_FAILED;
                    result.totalElapsedMs = elapsedMs(started);
                    return result;
                }

                RedirectHop hop = buildHop(hopNumber, currentUrl, currentRequest, statusCode, location, targetUrl,
                        exchangeElapsedMs, currentResponse, true, null, currentRaw,
                        build.forwardedSensitiveHeaders, build.strippedSensitiveHeaders);
                result.redirectHops.add(hop);

                currentRequest = build.request;
                currentRaw = build.rawBytes != null ? build.rawBytes.clone() : null;
                currentUrl = targetUrl;
                currentCanonicalUrl = targetCanonical;
                if (currentCanonicalUrl != null) {
                    visited.add(currentCanonicalUrl);
                }
                result.finalRequest = currentRequest;
                result.finalUrl = currentUrl;
                result.finalResponse = null;
                followedRedirectCount++;
            }
        } finally {
            result.totalElapsedMs = elapsedMs(started);
            if (result.redirectHops == null) {
                result.redirectHops = new ArrayList<>();
            }
            if (result.finalRequest == null) {
                result.finalRequest = currentRequest;
            }
            if (result.finalUrl == null) {
                result.finalUrl = currentUrl;
            }
        }
    }

    private static boolean isCancellationRequested(BooleanSupplier cancellationRequested) {
        try {
            return cancellationRequested != null && cancellationRequested.getAsBoolean();
        } catch (Exception ignored) {
            return true;
        }
    }

    private static RedirectResult cancelled(RedirectResult result, HttpRequest currentRequest, String currentUrl, boolean requestSent) {
        RedirectResult out = result != null ? result : new RedirectResult();
        out.success = false;
        out.errorMessage = "Runner execution cancelled.";
        out.terminationReason = RedirectTerminationReason.SEND_FAILED;
        out.responseTimedOut = false;
        out.requestSent = out.requestSent || requestSent;
        out.finalResponse = null;
        out.finalRequest = currentRequest;
        out.finalUrl = currentUrl;
        return out;
    }

    private static HttpRequestResponse send(HopSender sender, HttpRequest request) throws Exception {
        if (sender == null) {
            throw new IllegalStateException("No redirect sender configured");
        }
        return sender.send(request);
    }

    private static boolean isSupportedRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    static boolean isTimeoutFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            String simpleName = current.getClass().getSimpleName();
            if (simpleName != null && simpleName.toLowerCase(Locale.ROOT).contains("timeout")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("timed out") || lower.contains("timeout")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static long elapsedMs(long startedNano) {
        return Math.max(0L, (System.nanoTime() - startedNano) / 1_000_000L);
    }

    private static String headerValue(HttpResponse response, String name) {
        if (response == null || response.headers() == null || name == null) {
            return null;
        }
        for (var header : response.headers()) {
            if (header != null && header.name() != null && header.name().equalsIgnoreCase(name)) {
                return header.value();
            }
        }
        return null;
    }

    private static URI parseAbsoluteUri(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing URL");
        }
        try {
            URI uri = new URI(value.trim());
            if (!uri.isAbsolute()) {
                throw new IllegalArgumentException("URL must be absolute");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL", e);
        }
    }

    private static URI resolveLocation(URI source, String location) {
        if (source == null || location == null || location.isBlank()) {
            throw new IllegalArgumentException("Invalid redirect Location");
        }
        try {
            URI resolved = source.resolve(new URI(location.trim()));
            String scheme = resolved.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Unsupported redirect target");
            }
            if (resolved.getHost() == null || resolved.getHost().isBlank()) {
                throw new IllegalArgumentException("Invalid redirect target");
            }
            if (resolved.getUserInfo() != null) {
                throw new IllegalArgumentException("Invalid redirect target");
            }
            int port = resolved.getPort();
            if (port < -1 || port == 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid redirect target");
            }
            return stripFragmentPreservingRawEncoding(resolved);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid redirect target", e);
        }
    }

    private static URI stripFragmentPreservingRawEncoding(URI resolved) {
        try {
            String raw = resolved.toASCIIString();
            int fragmentIndex = raw.indexOf('#');
            String withoutFragment = fragmentIndex >= 0 ? raw.substring(0, fragmentIndex) : raw;
            URI stripped = URI.create(withoutFragment);
            if (stripped.getRawPath() == null || stripped.getRawPath().isEmpty()) {
                StringBuilder normalized = new StringBuilder();
                normalized.append(stripped.getScheme())
                        .append("://")
                        .append(stripped.getRawAuthority())
                        .append('/');
                if (stripped.getRawQuery() != null) {
                    normalized.append('?').append(stripped.getRawQuery());
                }
                stripped = URI.create(normalized.toString());
            }
            return stripped;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid redirect target", e);
        }
    }

    private static boolean isUnsupportedTargetReason(IllegalArgumentException e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String message = e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("unsupported redirect target");
    }

    private static String redirectOrigin(URI uri) {
        if (uri == null || !uri.isAbsolute()) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
            return null;
        }
        if (uri.getUserInfo() != null) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || host.contains("*")) {
            return null;
        }
        int port = uri.getPort();
        if (port < -1 || port == 0 || port > 65535) {
            return null;
        }
        int effectivePort = port == -1 ? defaultPort(normalizedScheme) : port;
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.contains(":") && !normalizedHost.startsWith("[")) {
            normalizedHost = "[" + normalizedHost + "]";
        }
        return normalizedScheme + "://" + normalizedHost + ":" + effectivePort;
    }

    private static String extractCleanError(Exception e) {
        if (e == null) {
            return RedirectTerminationReason.SEND_FAILED.displayLabel();
        }
        String msg = e.getMessage();
        if (msg == null) {
            return e.getClass().getSimpleName();
        }
        if (msg.contains("UnknownHostException")) {
            return "DNS resolution failed - check network/VPN";
        }
        if (msg.contains("ConnectException")) {
            return "Connection refused - service may be down or firewalled";
        }
        if (msg.contains("SocketTimeoutException")) {
            return "Connection timeout - target unresponsive";
        }
        return msg;
    }

    private static String canonicalRequestUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return value.trim();
            }
            int effectivePort = uri.getPort() == -1 ? defaultPort(scheme) : uri.getPort();
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(scheme.toLowerCase(Locale.ROOT)).append("://").append(host.toLowerCase(Locale.ROOT)).append(":").append(effectivePort);
            if (!"/".equals(path)) {
                sb.append(path);
            }
            if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
                sb.append('?').append(uri.getRawQuery());
            }
            return sb.toString();
        } catch (URISyntaxException e) {
            return value.trim();
        }
    }

    private static RedirectHop buildHop(int hopNumber,
                                        String sourceUrl,
                                        HttpRequest sourceRequest,
                                        int statusCode,
                                        String location,
                                        String targetUrl,
                                        long elapsedMs,
                                        HttpRequestResponse sourceResponse,
                                        boolean followed,
                                        String failureReason,
                                        byte[] sourceRawRequestBytes,
                                        List<String> forwardedNames,
                                        List<String> strippedNames) {
        RedirectHop hop = new RedirectHop();
        hop.hopNumber = hopNumber;
        hop.sourceUrl = sourceUrl;
        hop.sourceMethod = sourceRequest != null ? sourceRequest.method() : null;
        hop.statusCode = statusCode;
        hop.location = location;
        hop.targetUrl = targetUrl;
        hop.targetMethod = nextMethod(sourceRequest != null ? sourceRequest.method() : null, statusCode);
        hop.elapsedMs = elapsedMs;
        byte[] raw = sourceRawRequestBytes != null ? sourceRawRequestBytes.clone() : null;
        if (raw == null && sourceRequest != null && sourceRequest.toByteArray() != null) {
            raw = sourceRequest.toByteArray().getBytes().clone();
        }
        hop.rawRequestBytes = raw;
        hop.rawRequestText = raw != null ? new String(raw, StandardCharsets.UTF_8) : null;
        hop.responseHeadersText = responseHeadersText(sourceResponse);
        hop.responseBody = sourceResponse != null && sourceResponse.response() != null && sourceResponse.response().body() != null
                ? sourceResponse.response().body().getBytes().clone()
                : null;
        hop.followed = followed;
        hop.failureReason = failureReason;
        hop.forwardedSensitiveHeaderNames = dedupe(forwardedNames);
        hop.strippedSensitiveHeaderNames = dedupe(strippedNames);
        return hop;
    }

    private static String responseHeadersText(HttpRequestResponse response) {
        if (response == null || response.response() == null) {
            return "";
        }
        HttpResponse httpResponse = response.response();
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(httpResponse.statusCode());
        if (httpResponse.reasonPhrase() != null && !httpResponse.reasonPhrase().isBlank()) {
            sb.append(' ').append(httpResponse.reasonPhrase());
        }
        sb.append("\r\n");
        if (httpResponse.headers() != null) {
            for (var header : httpResponse.headers()) {
                if (header == null || header.name() == null) {
                    continue;
                }
                sb.append(header.name()).append(": ").append(header.value() != null ? header.value() : "").append("\r\n");
            }
        }
        return sb.toString().trim();
    }

    private static RedirectBuild buildNextRequest(HttpRequest currentRequest,
                                                  byte[] currentRaw,
                                                  URI targetUri,
                                                  int statusCode,
                                                  RedirectPolicy policy,
                                                  String sourceOrigin,
                                                  String targetOrigin) {
        RawMessage message = RawMessage.parse(currentRaw, currentRequest);
        String currentMethod = message.method != null ? message.method.toUpperCase(Locale.ROOT) : "GET";
        String nextMethod = nextMethod(currentMethod, statusCode);
        boolean bodyMustBeDropped =
                (statusCode == 303 && !"HEAD".equalsIgnoreCase(currentMethod))
                        || ((statusCode == 301 || statusCode == 302) && "POST".equalsIgnoreCase(currentMethod));
        boolean preserveBody = !bodyMustBeDropped;
        byte[] body = preserveBody && message.body != null ? message.body : new byte[0];
        boolean outgoingHasBody = body != null && body.length > 0;
        HeaderPolicyResult headerPolicy = applyHeaderPolicy(message.headers, sourceOrigin, targetOrigin, targetUri, policy);
        List<HeaderLine> headers = new ArrayList<>(headerPolicy.headers);
        removeHeaderIgnoreCase(headers, "Host");
        removeHeaderIgnoreCase(headers, "Content-Length");
        removeHeaderIgnoreCase(headers, "Transfer-Encoding");
        removeHeaderIgnoreCase(headers, "Connection");
        removeHeaderIgnoreCase(headers, "Proxy-Connection");
        removeHeaderIgnoreCase(headers, "Keep-Alive");
        removeHeaderIgnoreCase(headers, "TE");
        removeHeaderIgnoreCase(headers, "Trailer");
        removeHeaderIgnoreCase(headers, "Upgrade");

        for (String nominated : nominatedConnectionHeaders(message.headers)) {
            removeHeaderIgnoreCase(headers, nominated);
        }

        if (bodyMustBeDropped) {
            removeDroppedBodyHeaders(headers);
        }

        String requestTarget = requestTarget(targetUri);
        String hostHeader = hostHeaderValue(targetUri);
        headers.add(0, new HeaderLine("Host", hostHeader));
        if (outgoingHasBody) {
            headers.add(new HeaderLine("Content-Length", String.valueOf(body.length)));
        } else {
            removeHeaderIgnoreCase(headers, "Content-Length");
            removeHeaderIgnoreCase(headers, "Transfer-Encoding");
        }

        StringBuilder raw = new StringBuilder();
        raw.append(nextMethod).append(' ').append(requestTarget).append(" HTTP/1.1\r\n");
        for (HeaderLine header : headers) {
            if (header == null || header.name == null) {
                continue;
            }
            raw.append(sanitize(header.name)).append(": ").append(sanitize(header.value)).append("\r\n");
        }
        raw.append("\r\n");
        byte[] headerBytes = raw.toString().getBytes(StandardCharsets.UTF_8);
        byte[] rawBytes = new byte[headerBytes.length + body.length];
        System.arraycopy(headerBytes, 0, rawBytes, 0, headerBytes.length);
        System.arraycopy(body, 0, rawBytes, headerBytes.length, body.length);

        HttpService service = HttpService.httpService(targetUri.getHost(), targetUri.getPort() == -1 ? defaultPort(targetUri.getScheme()) : targetUri.getPort(), "https".equalsIgnoreCase(targetUri.getScheme()));
        HttpRequest nextRequest = HttpRequest.httpRequest(service, ByteArray.byteArray(rawBytes));
        return new RedirectBuild(nextRequest, rawBytes, headerPolicy.forwardedSensitiveHeaderNames, headerPolicy.strippedSensitiveHeaderNames);
    }

    private static void removeDroppedBodyHeaders(List<HeaderLine> headers) {
        removeHeaderIgnoreCase(headers, "Content-Type");
        removeHeaderIgnoreCase(headers, "Content-Encoding");
        removeHeaderIgnoreCase(headers, "Content-Language");
        removeHeaderIgnoreCase(headers, "Content-Location");
        removeHeaderIgnoreCase(headers, "Content-Disposition");
        removeHeaderIgnoreCase(headers, "Digest");
    }

    private static HeaderPolicyResult applyHeaderPolicy(List<HeaderLine> headers,
                                                        String sourceOrigin,
                                                        String targetOrigin,
                                                        URI targetUri,
                                                        RedirectPolicy policy) {
        List<HeaderLine> kept = new ArrayList<>();
        List<String> forwarded = new ArrayList<>();
        List<String> stripped = new ArrayList<>();
        boolean sameOrigin = sourceOrigin != null && sourceOrigin.equals(targetOrigin);
        Set<String> sensitive = new LinkedHashSet<>();
        for (String name : policy.effectiveSensitiveHeaderNames()) {
            sensitive.add(name.toLowerCase(Locale.ROOT));
        }
        for (HeaderLine header : headers) {
            if (header == null || header.name == null) {
                continue;
            }
            String lower = header.name.trim().toLowerCase(Locale.ROOT);
            if (isHopByHop(lower) || "proxy-authorization".equals(lower)) {
                stripped.add(header.name);
                continue;
            }
            boolean isSensitive = sensitive.contains(lower);
            if (sameOrigin) {
                kept.add(header.copy());
                if (isSensitive) {
                    forwarded.add(header.name);
                }
                continue;
            }
            switch (policy.crossOriginMode) {
                case STRIP_SENSITIVE -> {
                    if (isSensitive) {
                        stripped.add(header.name);
                    } else {
                        kept.add(header.copy());
                    }
                }
                case TRUSTED_ORIGINS_ONLY -> {
                    if (isSensitive) {
                        if (policy.isTrustedHeaderAllowed(sourceOrigin, targetOrigin, header.name)) {
                            kept.add(header.copy());
                            forwarded.add(header.name);
                        } else {
                            stripped.add(header.name);
                        }
                    } else {
                        kept.add(header.copy());
                    }
                }
                case PRESERVE_ANY_HTTPS_TARGET -> {
                    if ("https".equalsIgnoreCase(targetUri.getScheme()) && isSensitive) {
                        kept.add(header.copy());
                        forwarded.add(header.name);
                    } else if (isSensitive) {
                        stripped.add(header.name);
                    } else {
                        kept.add(header.copy());
                    }
                }
            }
        }
        return new HeaderPolicyResult(kept, dedupe(forwarded), dedupe(stripped));
    }

    private static boolean isHopByHop(String lower) {
        return "connection".equals(lower)
                || "proxy-connection".equals(lower)
                || "keep-alive".equals(lower)
                || "te".equals(lower)
                || "trailer".equals(lower)
                || "upgrade".equals(lower);
    }

    private static String nextMethod(String currentMethod, int statusCode) {
        String method = currentMethod != null && !currentMethod.isBlank() ? currentMethod.toUpperCase(Locale.ROOT) : "GET";
        if (statusCode == 303) {
            return "HEAD".equals(method) ? "HEAD" : "GET";
        }
        if ((statusCode == 301 || statusCode == 302) && "POST".equals(method)) {
            return "GET";
        }
        return method;
    }

    private static List<String> nominatedConnectionHeaders(List<HeaderLine> headers) {
        List<String> names = new ArrayList<>();
        for (HeaderLine header : headers) {
            if (header == null || header.name == null || !"connection".equalsIgnoreCase(header.name) || header.value == null) {
                continue;
            }
            for (String part : header.value.split(",")) {
                String trimmed = part != null ? part.trim() : "";
                if (!trimmed.isBlank()) {
                    names.add(trimmed);
                }
            }
        }
        return dedupe(names);
    }

    private static String requestTarget(URI targetUri) {
        String path = targetUri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        String query = targetUri.getRawQuery();
        return query == null || query.isBlank() ? path : path + "?" + query;
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static String hostHeaderValue(URI targetUri) {
        String host = targetUri.getHost();
        if (host == null) {
            return "";
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.contains(":") && !normalizedHost.startsWith("[")) {
            normalizedHost = "[" + normalizedHost + "]";
        }
        int port = targetUri.getPort();
        int effectivePort = port == -1 ? defaultPort(targetUri.getScheme()) : port;
        boolean defaultPort = ("https".equalsIgnoreCase(targetUri.getScheme()) && effectivePort == 443)
                || ("http".equalsIgnoreCase(targetUri.getScheme()) && effectivePort == 80);
        return defaultPort ? normalizedHost : normalizedHost + ":" + effectivePort;
    }

    private static void removeHeaderIgnoreCase(List<HeaderLine> headers, String name) {
        if (headers == null || name == null || name.isBlank()) {
            return;
        }
        headers.removeIf(header -> header != null && header.name != null && header.name.equalsIgnoreCase(name));
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace("\r", " ").replace("\n", " ");
    }

    private static List<String> dedupe(List<String> values) {
        List<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            String trimmed = value != null ? value.trim() : "";
            if (trimmed.isBlank()) {
                continue;
            }
            String key = trimmed.toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static final class RedirectBuild {
        final HttpRequest request;
        final byte[] rawBytes;
        final List<String> forwardedSensitiveHeaders;
        final List<String> strippedSensitiveHeaders;

        RedirectBuild(HttpRequest request, byte[] rawBytes, List<String> forwardedSensitiveHeaders, List<String> strippedSensitiveHeaders) {
            this.request = request;
            this.rawBytes = rawBytes;
            this.forwardedSensitiveHeaders = forwardedSensitiveHeaders != null ? new ArrayList<>(forwardedSensitiveHeaders) : new ArrayList<>();
            this.strippedSensitiveHeaders = strippedSensitiveHeaders != null ? new ArrayList<>(strippedSensitiveHeaders) : new ArrayList<>();
        }
    }

    private static final class HeaderPolicyResult {
        final List<HeaderLine> headers;
        final List<String> forwardedSensitiveHeaderNames;
        final List<String> strippedSensitiveHeaderNames;

        HeaderPolicyResult(List<HeaderLine> headers, List<String> forwardedSensitiveHeaderNames, List<String> strippedSensitiveHeaderNames) {
            this.headers = headers;
            this.forwardedSensitiveHeaderNames = forwardedSensitiveHeaderNames;
            this.strippedSensitiveHeaderNames = strippedSensitiveHeaderNames;
        }
    }

    private static final class HeaderLine {
        final String name;
        final String value;

        HeaderLine(String name, String value) {
            this.name = name;
            this.value = value;
        }

        HeaderLine copy() {
            return new HeaderLine(name, value);
        }
    }

    private static final class RawMessage {
        final String method;
        final String target;
        final List<HeaderLine> headers;
        final byte[] body;

        RawMessage(String method, String target, List<HeaderLine> headers, byte[] body) {
            this.method = method;
            this.target = target;
            this.headers = headers;
            this.body = body;
        }

        static RawMessage parse(byte[] rawRequest, HttpRequest request) {
            byte[] bytes = rawRequest;
            if (bytes == null) {
                bytes = request != null && request.toByteArray() != null ? request.toByteArray().getBytes() : new byte[0];
            }
            int separator = indexOf(bytes, "\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            int separatorLength = 4;
            if (separator < 0) {
                separator = indexOf(bytes, "\n\n".getBytes(StandardCharsets.UTF_8));
                separatorLength = 2;
            }
            byte[] headBytes = separator >= 0 ? slice(bytes, 0, separator) : bytes;
            byte[] bodyBytes = separator >= 0 ? slice(bytes, separator + separatorLength, bytes.length) : new byte[0];
            String head = new String(headBytes, StandardCharsets.UTF_8);
            String[] lines = head.replace("\r", "").split("\n", -1);
            String startLine = lines.length > 0 ? lines[0].trim() : "";
            String[] parts = startLine.split("\\s+", 3);
            String method = parts.length > 0 ? parts[0] : "GET";
            String target = parts.length > 1 ? parts[1] : "/";
            List<HeaderLine> headers = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line == null || line.isBlank()) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = sanitize(line.substring(0, colon));
                String value = sanitize(line.substring(colon + 1).trim());
                headers.add(new HeaderLine(name, value));
            }
            return new RawMessage(method, target, headers, bodyBytes);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] slice(byte[] source, int start, int end) {
        if (source == null || start < 0 || end < start || start > source.length) {
            return new byte[0];
        }
        int safeEnd = Math.min(end, source.length);
        byte[] out = new byte[Math.max(0, safeEnd - start)];
        System.arraycopy(source, start, out, 0, out.length);
        return out;
    }
}
