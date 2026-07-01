package burp.utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class RedirectOrigin {
    private RedirectOrigin() {
    }

    public static String canonicalOrigin(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            return canonicalOrigin(new URI(input.trim()));
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public static String canonicalOrigin(URI uri) {
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
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            return null;
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null && !rawPath.isEmpty() && !"/".equals(rawPath)) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || host.contains("*")) {
            return null;
        }
        int port = uri.getPort();
        if (port == 0 || port < -1 || port > 65535) {
            return null;
        }
        int effectivePort = port == -1 ? defaultPort(normalizedScheme) : port;
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.contains(":") && !normalizedHost.startsWith("[")) {
            normalizedHost = "[" + normalizedHost + "]";
        }
        return normalizedScheme + "://" + normalizedHost + ":" + effectivePort;
    }

    public static boolean sameOrigin(URI left, URI right) {
        String canonicalLeft = canonicalOrigin(left);
        String canonicalRight = canonicalOrigin(right);
        return canonicalLeft != null && canonicalLeft.equals(canonicalRight);
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }
}
