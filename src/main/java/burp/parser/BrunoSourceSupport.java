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
        return decodeTextBlock(blockContent, false);
    }

    static String decodeTextBlock(String blockContent, boolean canonicalStructuralIndent) {
        String normalized = normalizePhysicalLines(blockContent);
        if (!canonicalStructuralIndent) {
            return normalized.trim();
        }
        List<String> lines = new java.util.ArrayList<>(lines(normalized));
        if (!lines.isEmpty() && lines.get(0).isEmpty()) {
            lines.remove(0);
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            lines.set(index, line.startsWith("  ") ? line.substring(2) : line);
        }
        return String.join("\n", lines);
    }
}
