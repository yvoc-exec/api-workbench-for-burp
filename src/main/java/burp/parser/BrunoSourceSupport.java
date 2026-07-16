package burp.parser;

import java.util.List;

/** Bruno source files use only CRLF, LF, and CR as physical line boundaries. */
final class BrunoSourceSupport {
    private BrunoSourceSupport() {
    }

    static String normalizePhysicalLines(String source) {
        return source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n');
    }

    static List<String> lines(String source) {
        return List.of(normalizePhysicalLines(source).split("\n", -1));
    }

    static String decodeTextBlock(String blockContent) {
        return decodeStructuralTextBlock(blockContent);
    }

    static String decodeTextBlock(String blockContent, boolean canonicalStructuralIndent) {
        return canonicalStructuralIndent
                ? decodeStructuralTextBlock(blockContent)
                : decodeLegacyLooseTextBlock(blockContent);
    }

    static String decodeStructuralTextBlock(String blockContent) {
        String normalized = normalizePhysicalLines(blockContent);
        if (!normalized.contains("\n")) {
            // Whitespace surrounding an inline block value belongs to the
            // declaration, not to its payload.
            return normalized.trim();
        }
        List<String> lines = new java.util.ArrayList<>(lines(normalized));
        if (!lines.isEmpty() && lines.get(0).isEmpty()) {
            lines.remove(0);
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        boolean hasCanonicalStructuralIndent = lines.stream()
                .filter(line -> !line.isBlank())
                .allMatch(line -> line.startsWith("  "));
        if (hasCanonicalStructuralIndent) {
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                lines.set(index, line.startsWith("  ") ? line.substring(2) : line);
            }
        }
        return String.join("\n", lines);
    }

    static String decodeLegacyLooseTextBlock(String blockContent) {
        return normalizePhysicalLines(blockContent).trim();
    }
}
