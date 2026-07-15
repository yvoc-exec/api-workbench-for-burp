package burp.utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

public class HttpUtils {
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_HTTP_PORT = 80;
    private static final int DEFAULT_HTTPS_PORT = 443;
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{\\{[^}]+\\}\\}");

    public static class HostInfo {
        public final boolean useHttps;
        public final String host;
        public final int port;

        public HostInfo(String host, int port, boolean useHttps) {
            this.host = normalizeHostValue(host);
            this.useHttps = useHttps;
            this.port = port;
        }
    }

    /**
     * Robust parsed target for building Burp-compatible requests.
     */
    public static final class ParsedTarget {
        public final boolean useHttps;
        public final String pathWithQuery;
        public final String host;
        public final int port;

        public ParsedTarget(String host, int port, boolean useHttps, String pathWithQuery) {
            this.useHttps = useHttps;
            this.pathWithQuery = normalizeRequestTarget(pathWithQuery);
            this.host = host;
            this.port = port;
        }
    }

    public static HostInfo parseUrl(String urlString) {
        String candidate = trimToNull(urlString);
        if (candidate == null) {
            return new HostInfo(DEFAULT_HOST, DEFAULT_HTTP_PORT, false);
        }

        SchemeHint hint = SchemeHint.detect(candidate);
        if (containsTemplateVariable(candidate)) {
            return parseHostInfoFromText(candidate, hint);
        }

        URI uri = uriOrNull(candidate);
        if (uri != null && isHttpOrHttps(uri.getScheme()) && hasText(uri.getHost())) {
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            int port = uri.getPort() > 0 ? uri.getPort() : defaultPort(https);
            return new HostInfo(uri.getHost(), port, https);
        }

        if (candidate.startsWith("//")) {
            URI schemeRelative = uriOrNull("http:" + candidate);
            if (schemeRelative != null && hasText(schemeRelative.getHost())) {
                int port = schemeRelative.getPort() > 0 ? schemeRelative.getPort() : DEFAULT_HTTP_PORT;
                return new HostInfo(schemeRelative.getHost(), port, false);
            }
        }

        return parseHostInfoFromText(candidate, hint);
    }

    public static String extractPathFromUrl(String urlString) {
        String candidate = trimToNull(urlString);
        if (candidate == null) {
            return "/";
        }

        String withoutFragment = stripFragment(candidate);
        SchemeHint hint = SchemeHint.detect(withoutFragment);
        if (hint != SchemeHint.NONE) {
            URI uri = uriOrNull(withoutFragment);
            if (uri != null) {
                return pathAndQuery(uri);
            }
            if (containsTemplateVariable(withoutFragment)) {
                return pathFromAuthorityText(removeSchemePrefix(withoutFragment));
            }
        }

        if (withoutFragment.startsWith("/")) {
            return withoutFragment;
        }

        return "/" + withoutFragment;
    }

    public static String buildHostWithPort(String host, int port, boolean useHttps) {
        String renderedHost = hostForHeader(host);
        boolean defaultPort = (useHttps && port == DEFAULT_HTTPS_PORT)
                || (!useHttps && port == DEFAULT_HTTP_PORT)
                || port <= 0;
        return defaultPort ? renderedHost : renderedHost + ":" + port;
    }

    /**
     * Parses a resolved URL into host, port, scheme, and request path.
     * Throws {@link IllegalArgumentException} for path-only URLs without a host.
     */
    public static ParsedTarget parseTargetForRequest(String resolvedUrl) {
        String original = trimToNull(resolvedUrl);
        if (original == null) {
            throw new IllegalArgumentException("Request URL cannot be empty");
        }

        String candidate = stripFragment(original).trim();
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("Request URL cannot be empty");
        }
        if (isUnsafeUrlScheme(candidate)) {
            throw new IllegalArgumentException("Unsupported URL scheme: " + resolvedUrl);
        }

        SchemeHint hint = SchemeHint.detect(candidate);
        if (hint != SchemeHint.NONE) {
            return parseAbsoluteTarget(candidate, resolvedUrl, hint);
        }

        if (candidate.startsWith("//")) {
            return parseAuthorityTarget(candidate.substring(2), true, resolvedUrl);
        }

        if (candidate.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Request URL must include host (provide a baseUrl variable), got: " + resolvedUrl);
        }

        return parseAuthorityTarget(candidate, false, resolvedUrl);
    }

    private static ParsedTarget parseAbsoluteTarget(String candidate, String original, SchemeHint hint) {
        try {
            URI uri = new URI(candidate);
            if (!hasText(uri.getHost())) {
                throw new IllegalArgumentException("URL has no host: " + original);
            }
            boolean https = hint == SchemeHint.HTTPS;
            int port = uri.getPort() > 0 ? uri.getPort() : defaultPort(https);
            return new ParsedTarget(uri.getHost(), port, https, pathAndQuery(uri));
        } catch (URISyntaxException | IllegalArgumentException e) {
            if (containsTemplateVariable(candidate)) {
                return parseTemplatedAbsoluteTarget(candidate, original, hint);
            }
            throw new IllegalArgumentException("Invalid URL: " + original, e);
        }
    }

    private static ParsedTarget parseTemplatedAbsoluteTarget(String candidate, String original, SchemeHint hint) {
        String authorityAndPath = removeSchemePrefix(candidate);
        int split = firstAuthorityTerminator(authorityAndPath);
        String authority = split >= 0 ? authorityAndPath.substring(0, split) : authorityAndPath;
        String suffix = split >= 0 ? authorityAndPath.substring(split) : "";

        AuthorityParts parts = splitAuthority(authority);
        if (!hasText(parts.host) || containsTemplateVariable(parts.host)) {
            throw new IllegalArgumentException("Invalid URL: " + original);
        }

        boolean https = hint == SchemeHint.HTTPS;
        int port = parts.port != null ? parts.port : defaultPort(https);
        return new ParsedTarget(parts.host, port, https, suffixToRequestTarget(suffix));
    }

    private static ParsedTarget parseAuthorityTarget(String authorityAndPath, boolean schemeRelative, String original) {
        int split = firstAuthorityTerminator(authorityAndPath);
        String authority = split >= 0 ? authorityAndPath.substring(0, split) : authorityAndPath;
        String suffix = split >= 0 ? authorityAndPath.substring(split) : "";

        AuthorityParts parts = splitAuthority(authority);
        if (!hasText(parts.host)) {
            throw new IllegalArgumentException("URL has no host: " + original);
        }

        boolean https = true;
        int port = DEFAULT_HTTPS_PORT;
        if (parts.port != null) {
            port = parts.port;
            https = port != DEFAULT_HTTP_PORT;
        } else if (schemeRelative) {
            https = true;
            port = DEFAULT_HTTPS_PORT;
        }

        return new ParsedTarget(parts.host, port, https, suffixToRequestTarget(suffix));
    }

    private static HostInfo parseHostInfoFromText(String candidate, SchemeHint hint) {
        String authority = leadingAuthority(removeSchemePrefix(candidate));
        AuthorityParts parts = splitAuthority(authority);

        boolean https = hint == SchemeHint.HTTPS;
        int port = hint == SchemeHint.HTTPS ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;

        if (parts.port != null) {
            port = parts.port;
            if (hint == SchemeHint.NONE) {
                https = port == DEFAULT_HTTPS_PORT;
            }
        }

        String host = hasText(parts.host) ? parts.host : DEFAULT_HOST;
        return new HostInfo(host, port, https);
    }

    private static AuthorityParts splitAuthority(String rawAuthority) {
        String authority = rawAuthority != null ? rawAuthority.trim() : "";
        if (authority.isEmpty()) {
            return new AuthorityParts("", null);
        }

        int userInfo = authority.lastIndexOf('@');
        if (userInfo >= 0 && userInfo + 1 < authority.length()) {
            authority = authority.substring(userInfo + 1);
        }

        if (authority.startsWith("[")) {
            int closing = authority.indexOf(']');
            if (closing > 0) {
                String host = authority.substring(0, closing + 1);
                Integer port = parsePortAfterBracket(authority, closing);
                return new AuthorityParts(host, port);
            }
            return new AuthorityParts(authority, null);
        }

        int colon = authority.lastIndexOf(':');
        if (colon > 0 && authority.indexOf(':') == colon) {
            String possiblePort = authority.substring(colon + 1);
            Integer parsedPort = parseValidPort(possiblePort);
            if (parsedPort != null) {
                return new AuthorityParts(authority.substring(0, colon), parsedPort);
            }
        }

        return new AuthorityParts(authority, null);
    }

    private static Integer parsePortAfterBracket(String authority, int closingBracket) {
        int separator = closingBracket + 1;
        if (separator < authority.length() && authority.charAt(separator) == ':') {
            return parseValidPort(authority.substring(separator + 1));
        }
        return null;
    }

    private static Integer parseValidPort(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return null;
            }
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 && parsed <= 65535 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String leadingAuthority(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String withoutSlashes = value.startsWith("//") ? value.substring(2) : value;
        int end = firstAuthorityTerminator(withoutSlashes);
        return end >= 0 ? withoutSlashes.substring(0, end) : withoutSlashes;
    }

    private static int firstAuthorityTerminator(String value) {
        int slash = value.indexOf('/');
        int query = value.indexOf('?');
        if (slash < 0) {
            return query;
        }
        if (query < 0) {
            return slash;
        }
        return Math.min(slash, query);
    }

    private static String suffixToRequestTarget(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return "/";
        }
        if (suffix.charAt(0) == '?') {
            return "/" + suffix;
        }
        return normalizeRequestTarget(suffix);
    }

    private static String pathFromAuthorityText(String authorityAndPath) {
        int split = firstAuthorityTerminator(authorityAndPath);
        return split >= 0 ? suffixToRequestTarget(authorityAndPath.substring(split)) : "/";
    }

    private static String pathAndQuery(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getRawQuery();
        return query == null ? path : path + "?" + query;
    }

    private static String normalizeRequestTarget(String value) {
        if (value == null || value.isBlank()) {
            return "/";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private static String stripFragment(String value) {
        int hash = value.indexOf('#');
        return hash >= 0 ? value.substring(0, hash) : value;
    }

    private static String removeSchemePrefix(String value) {
        int separator = value.indexOf("://");
        return separator >= 0 ? value.substring(separator + 3) : value;
    }

    private static boolean isUnsafeUrlScheme(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("javascript:")
                || lower.startsWith("data:")
                || lower.startsWith("file:")
                || lower.startsWith("ftp:");
    }

    private static boolean isHttpOrHttps(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static URI uriOrNull(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private static int defaultPort(boolean https) {
        return https ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
    }

    private static boolean containsTemplateVariable(String value) {
        return value != null && TEMPLATE_VARIABLE.matcher(value).find();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeHostValue(String host) {
        String cleaned = host != null ? host.trim() : "";
        return cleaned.isEmpty() ? DEFAULT_HOST : cleaned;
    }

    private static String hostForHeader(String host) {
        String cleaned = normalizeHostValue(host);
        if (cleaned.indexOf(':') >= 0
                && !cleaned.startsWith("[")
                && !cleaned.endsWith("]")
                && !containsTemplateVariable(cleaned)) {
            return "[" + cleaned + "]";
        }
        return cleaned;
    }

    private enum SchemeHint {
        HTTP,
        HTTPS,
        NONE;

        static SchemeHint detect(String value) {
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("https://")) {
                return HTTPS;
            }
            if (lower.startsWith("http://")) {
                return HTTP;
            }
            return NONE;
        }
    }

    private static final class AuthorityParts {
        final String host;
        final Integer port;

        AuthorityParts(String host, Integer port) {
            this.host = host;
            this.port = port;
        }
    }
}
