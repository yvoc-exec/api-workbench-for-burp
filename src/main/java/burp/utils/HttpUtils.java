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
        try {
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
        return new HostInfo(host, 80, false);
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
}
