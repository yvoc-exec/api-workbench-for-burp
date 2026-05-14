package burp.utils;

import java.net.URL;
import java.util.regex.Pattern;

public class HttpUtils {
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(.+?)\\}\\}");

    public static class HostInfo {
        public final String host;
        public final int port;
        public final boolean useHttps;

        public HostInfo(String host, int port, boolean useHttps) {
            this.host = host;
            this.port = port;
            this.useHttps = useHttps;
        }
    }

    /**
     * Robust parsed target for building Burp-compatible requests.
     * <p>
     * Heuristics:
     * <ul>
     *   <li>http:// or https:// -> parsed via java.net.URL</li>
     *   <li>//host/path -> treated as https unless :80 is explicit</li>
     *   <li>host[:port]/path -> scheme by port heuristic (443=https, 80=http, else https)</li>
     *   <li>/path-only -> IllegalArgumentException (requires baseUrl variable)</li>
     * </ul>
     */
    public static final class ParsedTarget {
        public final String host;
        public final int port;
        public final boolean useHttps;
        public final String pathWithQuery;

        public ParsedTarget(String host, int port, boolean useHttps, String pathWithQuery) {
            this.host = host;
            this.port = port;
            this.useHttps = useHttps;
            this.pathWithQuery = pathWithQuery;
        }
    }

    public static HostInfo parseUrl(String urlString) {
        if (urlString == null || urlString.isEmpty()) {
            return new HostInfo("localhost", 80, false);
        }

        // Check if URL contains unresolved variables - preserve them as-is
        if (VARIABLE_PATTERN.matcher(urlString).find()) {
            return parseUrlWithVariables(urlString);
        }

        try {
            URL url = new URL(urlString);
            String host = url.getHost();
            int port = url.getPort();
            boolean useHttps = "https".equalsIgnoreCase(url.getProtocol());

            if (port == -1) {
                port = useHttps ? 443 : 80;
            }

            return new HostInfo(host, port, useHttps);
        } catch (Exception e) {
            return parseUrlWithVariables(urlString);
        }
    }

    private static HostInfo parseUrlWithVariables(String urlString) {
        String host = "localhost";
        // FIX: Detect protocol even when variables are present
        boolean useHttps = false;
        int port = 80;
        try {
            String lowerUrl = urlString.toLowerCase();
            if (lowerUrl.startsWith("https://")) {
                useHttps = true;
                port = 443;
            } else if (lowerUrl.startsWith("http://")) {
                useHttps = false;
                port = 80;
            }

            String withoutProtocol = urlString;
            if (urlString.contains("://")) {
                withoutProtocol = urlString.substring(urlString.indexOf("://") + 3);
            }
            int slashIndex = withoutProtocol.indexOf('/');
            int colonIndex = withoutProtocol.indexOf(':');
            int endIndex = withoutProtocol.length();
            if (slashIndex != -1 && colonIndex != -1) {
                endIndex = Math.min(slashIndex, colonIndex);
            } else if (slashIndex != -1) {
                endIndex = slashIndex;
            } else if (colonIndex != -1) {
                endIndex = colonIndex;
            }
            String hostPart = withoutProtocol.substring(0, endIndex);
            if (VARIABLE_PATTERN.matcher(hostPart).find()) {
                host = hostPart;
            } else if (!hostPart.isEmpty()) {
                host = hostPart;
            }
        } catch (Exception e) {
            // Keep default
        }
        return new HostInfo(host, port, useHttps);
    }

    public static String extractPathFromUrl(String urlString) {
        if (urlString == null || urlString.isEmpty()) return "/";
        try {
            int protocolEnd = urlString.indexOf("://");
            if (protocolEnd != -1) {
                int pathStart = urlString.indexOf('/', protocolEnd + 3);
                if (pathStart != -1) {
                    return urlString.substring(pathStart);
                } else {
                    return "/";
                }
            } else {
                return urlString.startsWith("/") ? urlString : "/" + urlString;
            }
        } catch (Exception e) {
            return "/";
        }
    }

    public static String buildHostWithPort(String host, int port, boolean useHttps) {
        boolean isDefaultPort = (useHttps && port == 443) || (!useHttps && port == 80);
        if (isDefaultPort) {
            return host;
        } else {
            return host + ":" + port;
        }
    }

    /**
     * Parses a resolved URL into host, port, scheme, and request path.
     * Throws {@link IllegalArgumentException} for path-only URLs without a host.
     */
    public static ParsedTarget parseTargetForRequest(String resolvedUrl) {
        if (resolvedUrl == null || resolvedUrl.isEmpty()) {
            throw new IllegalArgumentException("Request URL cannot be empty");
        }

        // Strip fragment
        int hashIdx = resolvedUrl.indexOf('#');
        String url = hashIdx >= 0 ? resolvedUrl.substring(0, hashIdx) : resolvedUrl;
        url = url.trim();

        // 1) Explicit scheme: http:// or https://
        String lower = url.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            try {
                URL u = new URL(url);
                String host = u.getHost();
                if (host == null || host.isEmpty()) {
                    throw new IllegalArgumentException("URL has no host: " + resolvedUrl);
                }
                boolean useHttps = "https".equalsIgnoreCase(u.getProtocol());
                int port = u.getPort();
                if (port == -1) {
                    port = useHttps ? 443 : 80;
                }
                String path = u.getFile();
                if (path == null || path.isEmpty()) {
                    path = "/";
                }
                return new ParsedTarget(host, port, useHttps, path);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid URL: " + resolvedUrl, e);
            }
        }

        // 2) Scheme-relative: //host/path
        if (lower.startsWith("//")) {
            String remainder = url.substring(2);
            return parseHostPortPath(remainder, true); // default https unless :80
        }

        // 3) Path-only like /v1/users -> reject (must provide baseUrl)
        if (url.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Request URL must include host (provide a baseUrl variable), got: " + resolvedUrl);
        }

        // 4) Schemeless host[:port]/path or host[:port]
        return parseHostPortPath(url, false);
    }

    private static ParsedTarget parseHostPortPath(String input, boolean fromSchemeRelative) {
        // Find where host:port ends and path begins
        int slashIdx = input.indexOf('/');
        String hostPortPart = slashIdx >= 0 ? input.substring(0, slashIdx) : input;
        String path = slashIdx >= 0 ? input.substring(slashIdx) : "/";
        if (path.isEmpty()) {
            path = "/";
        }

        // Parse host and explicit port
        String host;
        int explicitPort = -1;
        int colonIdx = hostPortPart.lastIndexOf(':');
        if (colonIdx > 0) {
            // Could be IPv6 or port; try numeric port first
            String afterColon = hostPortPart.substring(colonIdx + 1);
            try {
                explicitPort = Integer.parseInt(afterColon);
                if (explicitPort > 0 && explicitPort <= 65535) {
                    host = hostPortPart.substring(0, colonIdx);
                } else {
                    explicitPort = -1;
                    host = hostPortPart;
                }
            } catch (NumberFormatException e) {
                host = hostPortPart;
            }
        } else {
            host = hostPortPart;
        }

        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("URL has no host: " + input);
        }

        boolean useHttps;
        int port;
        if (explicitPort != -1) {
            port = explicitPort;
            if (port == 80) {
                useHttps = false;
            } else if (port == 443) {
                useHttps = true;
            } else {
                useHttps = fromSchemeRelative ? true : true; // default https for non-standard ports
            }
        } else {
            // No explicit port: scheme-relative defaults https, otherwise https
            useHttps = true;
            port = 443;
        }

        return new ParsedTarget(host, port, useHttps, path);
    }
}
